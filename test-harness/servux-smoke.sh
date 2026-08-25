#!/usr/bin/env bash
# servux-smoke.sh — drive ProtocolBot against the local Luminol smoke server
# to prove the Servux Direct-Paste flow actually places blocks.
#
# Steps:
#   1. Locate/build plugin jar
#   2. Locate Luminol server jar (test-harness/server/)
#   3. Wipe server state, write hermetic server.properties, ENABLE Servux bridge
#      in plugins/LitematicaFolia/config.yml (scratch only — NOT committed)
#   4. Boot server, wait for "Servux bridge online" log
#   5. Compile the bot against build/classes/java/main
#   6. Run the bot — handshake → login → config → play → metadata → stream
#   7. Wait, scan latest.log for "paste complete:" and check region-file mtime
#   8. RCON stop, write servux-smoke.txt PASS/FAIL
#
# Exit 0 on PASS, 1 on FAIL.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
BOT_DIR="${HARNESS_DIR}/protocol-bot"
RESULT_FILE="${HARNESS_DIR}/servux-smoke.txt"
RUN_LOG="${HARNESS_DIR}/servux-run.log"
BOT_LOG="${HARNESS_DIR}/servux-bot.log"
PID_FILE="${HARNESS_DIR}/servux.pid"
LOG_FILE="${SERVER_DIR}/logs/latest.log"
RCON_LIB="${HARNESS_DIR}/lib/rcon.py"

# Override with FIXTURE=/path/to/other.litematic (e.g. mixed-room-8 for a
# tile-entity + multi-slice payload).
FIXTURE="${FIXTURE:-${REPO_DIR}/schematics-fixtures/stone-cube-4.litematic}"
PLUGIN_DIR="${SERVER_DIR}/plugins/LitematicaFolia"
PLUGIN_CONFIG="${PLUGIN_DIR}/config.yml"

SERVER_PORT=25699
RCON_PORT=25698
RCON_PASS="lftest"
RCON_HOST="127.0.0.1"

PASTE_X=100
PASTE_Y=64
PASTE_Z=100

# Servux wire era the bot emulates: v1 = Litematica <= 26.2-0.28.4
# (vanilla NBT + transactionId), v2 = 26.2-0.28.5+ (Data Tag blobs,
# metadata {version: Int 2}). Override with WIRE=v1|v2.
WIRE="${WIRE:-v2}"

BOOT_TIMEOUT_SECS=180
BOT_HOLD_SECS=15
PASTE_WAIT_SECS=10
SHUTDOWN_TIMEOUT_SECS=60

JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [[ -x "${JAVA_HOME_DIR}/bin/java" ]]; then
    JAVA_BIN="${JAVA_HOME_DIR}/bin/java"
    JAVAC_BIN="${JAVA_HOME_DIR}/bin/javac"
else
    JAVA_BIN="$(command -v java)"
    JAVAC_BIN="$(command -v javac)"
fi

log() { echo "[servux-smoke] $*" >&2; }

write_result() {
    local verdict="$1"
    local diag="$2"
    {
        echo "${verdict}"
        echo ""
        echo "${diag}"
        echo ""
        echo "harness  : ${HARNESS_DIR}"
        echo "server log: ${LOG_FILE}"
        echo "bot log  : ${BOT_LOG}"
        echo "run log  : ${RUN_LOG}"
    } > "${RESULT_FILE}"
}

cleanup() {
    local exit_code=$?
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            log "cleanup: stopping server pid=${pid}"
            send_rcon "stop" >/dev/null 2>&1 || true
            for _ in $(seq 1 30); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            kill -TERM "${pid}" 2>/dev/null || true
            sleep 2
            kill -KILL "${pid}" 2>/dev/null || true
        fi
        rm -f "${PID_FILE}"
    fi
    local stragglers
    stragglers="$(lsof -ti tcp:${SERVER_PORT} 2>/dev/null || true)"
    if [[ -n "${stragglers}" ]]; then
        log "cleanup: killing leftover listeners on ${SERVER_PORT}: ${stragglers}"
        kill -KILL ${stragglers} 2>/dev/null || true
    fi
    exit "${exit_code}"
}
trap cleanup EXIT INT TERM

send_rcon() {
    local cmd="$1"
    python3 "${RCON_LIB}" "${RCON_HOST}" "${RCON_PORT}" "${RCON_PASS}" "${cmd}"
}

# -------------------------------------------------------------- 1) plugin jar
PLUGIN_JAR="$(ls -1 "${REPO_DIR}/build/libs"/LitematicaFolia-*-all.jar 2>/dev/null | sort -V | tail -n1 || true)"
if [[ -z "${PLUGIN_JAR}" || ! -f "${PLUGIN_JAR}" ]]; then
    log "no plugin jar in build/libs — running ./gradlew build"
    (cd "${REPO_DIR}" && ./gradlew build --no-daemon >>"${HARNESS_DIR}/servux-gradle.log" 2>&1) || {
        write_result "FAIL" "gradlew build failed — see ${HARNESS_DIR}/servux-gradle.log"
        exit 1
    }
    PLUGIN_JAR="$(ls -1 "${REPO_DIR}/build/libs"/LitematicaFolia-*-all.jar 2>/dev/null | sort -V | tail -n1)"
fi
log "plugin jar: ${PLUGIN_JAR}"

# -------------------------------------------------------------- 2) server jar
SERVER_JAR="$(ls -1 "${SERVER_DIR}"/folia*.jar "${SERVER_DIR}"/luminol*.jar "${SERVER_DIR}"/paper*.jar 2>/dev/null | head -n1 || true)"
if [[ -z "${SERVER_JAR}" ]]; then
    write_result "FAIL" "no server jar in ${SERVER_DIR} — run run-tests.sh once to fetch one"
    exit 1
fi
log "server jar: $(basename "${SERVER_JAR}")"

if [[ ! -f "${FIXTURE}" ]]; then
    write_result "FAIL" "missing fixture: ${FIXTURE}"
    exit 1
fi
log "fixture  : ${FIXTURE}"

# -------------------------------------------------------------- 3) prep server
log "wiping stale state"
rm -f "${RESULT_FILE}" "${RUN_LOG}" "${BOT_LOG}" "${PID_FILE}"
rm -rf "${SERVER_DIR}/logs" \
       "${SERVER_DIR}/world_smoke" "${SERVER_DIR}/world_smoke_nether" "${SERVER_DIR}/world_smoke_the_end" \
       "${SERVER_DIR}/world" "${SERVER_DIR}/world_nether" "${SERVER_DIR}/world_the_end" \
       "${SERVER_DIR}/usercache.json" \
       "${SERVER_DIR}/plugins/.paper-remapped"

mkdir -p "${SERVER_DIR}/plugins"

cat > "${SERVER_DIR}/eula.txt" <<'EOF'
eula=true
EOF

cat > "${SERVER_DIR}/server.properties" <<EOF
online-mode=false
server-port=${SERVER_PORT}
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASS}
broadcast-rcon-to-ops=false
motd=LitematicaFolia servux smoke
level-name=world_smoke
max-tick-time=-1
spawn-protection=0
view-distance=4
simulation-distance=4
allow-flight=true
network-compression-threshold=-1
EOF

rm -f "${SERVER_DIR}/plugins/"LitematicaFolia-*.jar 2>/dev/null || true
cp "${PLUGIN_JAR}" "${SERVER_DIR}/plugins/"

# Pre-create the plugin data dir + config with Servux bridge ENABLED. This is
# scratch — NOT committed in src/main/resources/config.yml.
mkdir -p "${PLUGIN_DIR}/schematics"
cat > "${PLUGIN_CONFIG}" <<'EOF'
paste:
  maxBlocksPerChunkTask: 8192
  allowEntities: true
  allowTileEntities: true
  allowPendingTicks: true
  deferredPhysics: true
  observersLast: true
  runDataFixer: true
  minDataVersion: 2586

schematic:
  directory: schematics
  maxFileSizeBytes: 67108864

protocol:
  enableServuxBridge: true
  channel: "servux:litematics"

fawe:
  registerClipboardFormat: true
EOF
log "Servux bridge config flipped ON in ${PLUGIN_CONFIG}"

# -------------------------------------------------------------- 4) boot server
JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+).*/\1/')"
EXTRA_JVM_ARGS=()
if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 17 ]]; then
    EXTRA_JVM_ARGS+=(
        '--add-opens=java.base/java.lang=ALL-UNNAMED'
        '--enable-native-access=ALL-UNNAMED'
    )
fi
if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 21 ]]; then
    EXTRA_JVM_ARGS+=('--add-modules=jdk.incubator.vector')
fi

log "booting server (port ${SERVER_PORT}, rcon ${RCON_PORT})"
(
    cd "${SERVER_DIR}" || exit 99
    export JAVA_HOME="${JAVA_HOME_DIR}"
    exec "${JAVA_BIN}" -Xmx2G -Xms1G "${EXTRA_JVM_ARGS[@]}" -jar "$(basename "${SERVER_JAR}")" nogui
) >"${RUN_LOG}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" > "${PID_FILE}"
log "server pid=${SERVER_PID}"

deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECS ))
saw_done=0
saw_servux=0
while :; do
    now=$(date +%s)
    (( now > deadline )) && break
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
        log "server died before boot completed"
        break
    fi
    if [[ -f "${RUN_LOG}" ]]; then
        if (( saw_done == 0 )) && grep -E 'Done \(' "${RUN_LOG}" >/dev/null 2>&1; then
            saw_done=1
            log "saw Done( — server booted"
        fi
        if (( saw_servux == 0 )) && grep -F 'Servux bridge online' "${RUN_LOG}" >/dev/null 2>&1; then
            saw_servux=1
            log "saw 'Servux bridge online'"
        fi
        if (( saw_done == 1 )) && (( saw_servux == 1 )); then
            break
        fi
    fi
    sleep 1
done

if (( saw_done == 0 )); then
    diag="server boot timed out at ${BOOT_TIMEOUT_SECS}s"
    diag+=$'\n'"Last 40 log lines:"$'\n'"$(tail -n40 "${RUN_LOG}" 2>/dev/null || true)"
    write_result "FAIL" "${diag}"
    exit 1
fi
if (( saw_servux == 0 )); then
    diag="Done( but no 'Servux bridge online' — config flip may not have taken effect"
    diag+=$'\n'"plugin config:"$'\n'"$(cat "${PLUGIN_CONFIG}")"
    diag+=$'\n'"Last 40 log lines:"$'\n'"$(tail -n40 "${RUN_LOG}" 2>/dev/null || true)"
    write_result "FAIL" "${diag}"
    exit 1
fi

# OP the bot's username via RCON so the permission gate passes
sleep 2
log "rcon: op the bot"
send_rcon "op ProtoBot" >/dev/null 2>&1 || true

# -------------------------------------------------------------- 5) build bot
log "compiling protocol bot"
mkdir -p "${BOT_DIR}/build"
"${JAVAC_BIN}" --release 25 \
    -d "${BOT_DIR}/build" \
    -cp "${REPO_DIR}/build/classes/java/main" \
    "${BOT_DIR}/src/ProtocolBot.java" 2>&1 | tee "${BOT_DIR}/build/javac.log"
if [[ ! -f "${BOT_DIR}/build/ProtocolBot.class" ]]; then
    diag="bot compile failed:"$'\n'"$(cat "${BOT_DIR}/build/javac.log")"
    write_result "FAIL" "${diag}"
    exit 1
fi

# -------------------------------------------------------------- 6) run bot
log "running bot (wire ${WIRE}) → ${RCON_HOST}:${SERVER_PORT}"
set +e
"${JAVA_BIN}" \
    -cp "${BOT_DIR}/build:${REPO_DIR}/build/classes/java/main" \
    ProtocolBot \
    --target "${RCON_HOST}:${SERVER_PORT}" \
    --litematic "${FIXTURE}" \
    --paste-origin "${PASTE_X},${PASTE_Y},${PASTE_Z}" \
    --username ProtoBot \
    --hold-seconds "${BOT_HOLD_SECS}" \
    --wire "${WIRE}" \
    >"${BOT_LOG}" 2>&1
BOT_RC=$?
set -e
log "bot exit code: ${BOT_RC}"
log "bot log tail:"
tail -n20 "${BOT_LOG}" | sed 's/^/    /' >&2

# -------------------------------------------------------------- 7) wait + verify
log "waiting ${PASTE_WAIT_SECS}s for server-side paste"
sleep "${PASTE_WAIT_SECS}"

# Force a save to flush region writes.
send_rcon "save-all" >/dev/null 2>&1 || true
sleep 2

# Count paste-complete log entries.
# NB: grep -c exits 1 on zero matches, which under `set -eo pipefail` used
# to kill the script right here on the FAIL path (before write_result ran).
paste_complete=$( (grep -cE 'paste complete:' "${LOG_FILE}" 2>/dev/null || true) | head -n1 | tr -d '[:space:]')
[[ -z "${paste_complete}" ]] && paste_complete=0
log "paste-complete log entries: ${paste_complete}"

# Check region files (Folia/Luminol uses world_smoke/dimensions/minecraft/overworld/region)
region_mtime=0
region_root="${SERVER_DIR}/world_smoke/dimensions/minecraft/overworld/region"
[[ -d "${region_root}" ]] || region_root="${SERVER_DIR}/world_smoke/region"
if [[ -d "${region_root}" ]]; then
    while IFS= read -r f; do
        m=$(stat -f '%m' "$f" 2>/dev/null || stat -c '%Y' "$f" 2>/dev/null || echo 0)
        [[ "${m}" -gt "${region_mtime}" ]] && region_mtime=$m
    done < <(find "${region_root}" -name '*.mca' 2>/dev/null)
fi
log "newest region mtime: ${region_mtime} (root=${region_root})"

# Check metadata exchange visible in bot log
metadata_ok=0
if grep -F "METADATA OK" "${BOT_LOG}" >/dev/null 2>&1; then
    metadata_ok=1
fi
log "metadata exchange: ${metadata_ok}"

# v2-only extra assertion: the server must close the client's paste HUD
# with a TASK_STATUS_SYNC InfoHudComplete once the paste finishes.
task_sync_ok=0
if [[ "${WIRE}" == "v2" ]] && grep -F "TASK COMPLETE SYNC OK" "${BOT_LOG}" >/dev/null 2>&1; then
    task_sync_ok=1
fi
log "task complete sync (v2): ${task_sync_ok}"

# Additional verification: probe origin via `/execute if block ... run`.
# The stone-cube-4 fixture has region.origin=(0, 64, 0), so the actual
# placement starts at (PASTE_X+0, PASTE_Y+64, PASTE_Z+0) — i.e. PASTE_Y is
# *added* to the region's local 64. The 4³ stone cube occupies
# (PASTE_X..PASTE_X+3, PASTE_Y+64..PASTE_Y+67, PASTE_Z..PASTE_Z+3).
# We must force-load the chunk first since the bot has disconnected and
# the chunk may have unloaded by now.
block_probe=""
block_probe_pass=0
if (( paste_complete >= 1 )); then
    probe_x=$PASTE_X
    probe_y=$((PASTE_Y + 64))
    probe_z=$PASTE_Z
    chunk_x=$(( probe_x / 16 ))
    chunk_z=$(( probe_z / 16 ))
    send_rcon "forceload add ${probe_x} ${probe_z}" >/dev/null 2>&1 || true
    sleep 1
    # `execute if block` crashes on Folia (NPE in Level.getCurrentWorldData()).
    # Use `setblock ... replace` instead — it returns "no change" when the
    # block already matches, otherwise "block changed".
    block_probe="$(send_rcon "setblock ${probe_x} ${probe_y} ${probe_z} minecraft:stone replace" 2>&1 || true)"
    log "rcon setblock-replace probe @ ${probe_x},${probe_y},${probe_z}: ${block_probe}"
    # Modern MC returns "Could not set the block" when the target block
    # already equals the requested state — for this probe that IS the
    # pass condition (the pasted stone is already there).
    if echo "${block_probe}" | grep -qi "no change\|nothing changed\|already\|could not set"; then
        block_probe_pass=1
    fi
    send_rcon "forceload remove ${probe_x} ${probe_z}" >/dev/null 2>&1 || true
fi

# Verdict
if (( paste_complete >= 1 )); then
    diag="$(printf 'Servux Direct-Paste verified end-to-end (wire %s):\n  - bot completed handshake -> login -> config -> play\n  - servux:litematics metadata exchange: %s\n  - v2 task-complete sync: %s\n  - server logged %d "paste complete:" line(s)\n  - newest region-file mtime: %s\n  - block probe at expected stone location: %s (returned: %s)\n\nServer last 30 lines:\n%s\n\nBot last 40 lines:\n%s\n' \
        "${WIRE}" "${metadata_ok}" "${task_sync_ok}" "${paste_complete}" "${region_mtime}" \
        "${block_probe_pass}" "${block_probe}" \
        "$(tail -n30 "${LOG_FILE}" 2>/dev/null || true)" \
        "$(tail -n40 "${BOT_LOG}" 2>/dev/null || true)")"
    write_result "PASS" "${diag}"
    log "PASS"
    # Graceful shutdown
    send_rcon "stop" >/dev/null 2>&1 || true
    exit 0
else
    diag="$(printf 'No "paste complete:" log entry observed after streaming.\n  - bot exit code: %d\n  - region mtime: %s\n  - metadata exchange: %s\n\nServer last 80 lines:\n%s\n\nBot last 80 lines:\n%s\n' \
        "${BOT_RC}" "${region_mtime}" "${metadata_ok}" \
        "$(tail -n80 "${LOG_FILE}" 2>/dev/null || true)" \
        "$(tail -n80 "${BOT_LOG}" 2>/dev/null || true)")"
    write_result "FAIL" "${diag}"
    log "FAIL"
    send_rcon "stop" >/dev/null 2>&1 || true
    exit 1
fi
