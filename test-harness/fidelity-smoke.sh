#!/usr/bin/env bash
# fidelity-smoke.sh — E2E battery for issue #4 (Servux placement fidelity).
#
# Boots ONE hermetic Folia/Paper 26.2 server on a superflat world (surface
# y=-61, everything above is air), then drives ProtocolBot through six
# Direct-Paste scenarios over wire v2 and asserts world state via RCON
# setblock-replace probes ("Could not set" == block already in exactly the
# requested state) and paste-then-save round trips dumped with
# tools/DumpLitematic.java:
#
#   S1 ReplaceMode=WITH_NON_AIR — schematic air skipped, structure_void never
#      pasted, stairs keep facing, entities at exact double positions
#   S2 ReplaceMode=NONE         — destination non-air survives
#   S3 ReplaceMode=ALL          — schematic air clears destination blocks
#   S4 Rotation=CLOCKWISE_90    — rotated block box, rotated stair facing,
#      cell-preserving fractional entity transform, entity yaw +90
#   S5 SubRegions overrides     — region B disabled, region A moved
#   S6 PasteLayerBehavior       — single-layer paste only writes that layer
#
# The plugin config pins paste.maxBlocksPerChunkTask=16 so every scenario
# also exercises the issue #4.7 batching path (65-74 writes -> 5+ batches).
#
# Exit 0 on PASS (all scenarios), 1 on FAIL.

set -uo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
BOT_DIR="${HARNESS_DIR}/protocol-bot"
TOOLS_DIR="${HARNESS_DIR}/tools"
RESULT_FILE="${HARNESS_DIR}/fidelity-smoke.txt"
RUN_LOG="${HARNESS_DIR}/fidelity-run.log"
BOT_LOG="${HARNESS_DIR}/fidelity-bot.log"
PID_FILE="${HARNESS_DIR}/fidelity.pid"
LOG_FILE="${SERVER_DIR}/logs/latest.log"
RCON_LIB="${HARNESS_DIR}/lib/rcon.py"

FIXTURE_DIR="${REPO_DIR}/schematics-fixtures"
FIX_CUBE="${FIXTURE_DIR}/fidelity-cube.litematic"
FIX_TOWERS="${FIXTURE_DIR}/two-towers.litematic"

PLUGIN_DIR="${SERVER_DIR}/plugins/LitematicaFolia"
PLUGIN_CONFIG="${PLUGIN_DIR}/config.yml"
SCHEM_OUT_DIR="${PLUGIN_DIR}/schematics"

SERVER_PORT=25699
RCON_PORT=25698
RCON_PASS="lftest"
RCON_HOST="127.0.0.1"

BOOT_TIMEOUT_SECS=180
BOT_HOLD_SECS=8
PASTE_WAIT_SECS=45

JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [[ -x "${JAVA_HOME_DIR}/bin/java" ]]; then
    JAVA_BIN="${JAVA_HOME_DIR}/bin/java"
    JAVAC_BIN="${JAVA_HOME_DIR}/bin/javac"
else
    JAVA_BIN="$(command -v java)"
    JAVAC_BIN="$(command -v javac)"
fi

log() { echo "[fidelity] $*" >&2; }

FAILURES=()
CHECKS=0
fail_check() { FAILURES+=("$1"); log "CHECK FAIL: $1"; }
pass_check() { log "check ok: $1"; }

write_result() {
    local verdict="$1"; local diag="$2"
    {
        echo "${verdict}"
        echo ""
        echo "${diag}"
        echo ""
        echo "checks run : ${CHECKS}"
        echo "failures   : ${#FAILURES[@]}"
        for f in "${FAILURES[@]:-}"; do [[ -n "$f" ]] && echo "  - $f"; done
        echo ""
        echo "server log: ${LOG_FILE}"
        echo "bot log   : ${BOT_LOG}"
    } > "${RESULT_FILE}"
}

cleanup() {
    local exit_code=$?
    if [[ -f "${PID_FILE}" ]]; then
        local pid; pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            log "cleanup: stopping server pid=${pid}"
            send_rcon "stop" >/dev/null 2>&1 || true
            for _ in $(seq 1 30); do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
            kill -TERM "${pid}" 2>/dev/null || true; sleep 2
            kill -KILL "${pid}" 2>/dev/null || true
        fi
        rm -f "${PID_FILE}"
    fi
    local stragglers; stragglers="$(lsof -ti tcp:${SERVER_PORT} 2>/dev/null || true)"
    [[ -n "${stragglers}" ]] && kill -KILL ${stragglers} 2>/dev/null || true
    exit "${exit_code}"
}
trap cleanup EXIT INT TERM

send_rcon() { python3 "${RCON_LIB}" "${RCON_HOST}" "${RCON_PORT}" "${RCON_PASS}" "$1"; }

# ---------------------------------------------------------------- assets
PLUGIN_JAR="$(ls -1 "${REPO_DIR}/build/libs"/LitematicaFolia-*-all.jar 2>/dev/null | sort -V | tail -n1 || true)"
if [[ -z "${PLUGIN_JAR}" ]]; then
    log "no plugin jar — run ./gradlew build first"; write_result FAIL "no plugin jar"; exit 1
fi
SERVER_JAR="$(ls -1 "${SERVER_DIR}"/folia*.jar "${SERVER_DIR}"/luminol*.jar "${SERVER_DIR}"/paper*.jar 2>/dev/null | head -n1 || true)"
[[ -z "${SERVER_JAR}" ]] && { write_result FAIL "no server jar in ${SERVER_DIR}"; exit 1; }
if [[ ! -f "${FIX_CUBE}" || ! -f "${FIX_TOWERS}" ]]; then
    log "fixtures missing — running ./gradlew test to generate"
    (cd "${REPO_DIR}" && ./gradlew test --no-daemon -q >/dev/null 2>&1) || true
fi
[[ -f "${FIX_CUBE}" ]] || { write_result FAIL "missing ${FIX_CUBE}"; exit 1; }
[[ -f "${FIX_TOWERS}" ]] || { write_result FAIL "missing ${FIX_TOWERS}"; exit 1; }
log "plugin: $(basename "${PLUGIN_JAR}")  server: $(basename "${SERVER_JAR}")"

# ---------------------------------------------------------------- server prep
log "wiping stale server state"
rm -f "${RESULT_FILE}" "${RUN_LOG}" "${BOT_LOG}" "${PID_FILE}"
rm -rf "${SERVER_DIR}/logs" "${SERVER_DIR}/world_fid"* \
       "${SERVER_DIR}/world" "${SERVER_DIR}/world_nether" "${SERVER_DIR}/world_the_end" \
       "${SERVER_DIR}/usercache.json" "${SERVER_DIR}/plugins/.paper-remapped"
mkdir -p "${SERVER_DIR}/plugins"

echo "eula=true" > "${SERVER_DIR}/eula.txt"
cat > "${SERVER_DIR}/server.properties" <<EOF
online-mode=false
server-port=${SERVER_PORT}
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASS}
broadcast-rcon-to-ops=false
motd=LitematicaFolia fidelity smoke
level-name=world_fid
level-type=minecraft\\:flat
max-tick-time=-1
spawn-protection=0
view-distance=4
simulation-distance=4
allow-flight=true
network-compression-threshold=-1
EOF

rm -f "${SERVER_DIR}/plugins/"LitematicaFolia-*.jar 2>/dev/null || true
cp "${PLUGIN_JAR}" "${SERVER_DIR}/plugins/"

mkdir -p "${SCHEM_OUT_DIR}"
cat > "${PLUGIN_CONFIG}" <<'EOF'
paste:
  # Deliberately tiny so every fidelity paste exercises the batching path.
  maxBlocksPerChunkTask: 16
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

# ---------------------------------------------------------------- boot
JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+).*/\1/')"
EXTRA_JVM_ARGS=()
[[ "${JAVA_MAJOR}" -ge 17 ]] && EXTRA_JVM_ARGS+=('--add-opens=java.base/java.lang=ALL-UNNAMED' '--enable-native-access=ALL-UNNAMED')
[[ "${JAVA_MAJOR}" -ge 21 ]] && EXTRA_JVM_ARGS+=('--add-modules=jdk.incubator.vector')

log "booting server (flat world, port ${SERVER_PORT})"
( cd "${SERVER_DIR}" && export JAVA_HOME="${JAVA_HOME_DIR}" && \
  exec "${JAVA_BIN}" -Xmx2G -Xms1G "${EXTRA_JVM_ARGS[@]}" -jar "$(basename "${SERVER_JAR}")" nogui \
) >"${RUN_LOG}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" > "${PID_FILE}"

deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECS ))
saw_done=0; saw_servux=0
while (( $(date +%s) <= deadline )); do
    kill -0 "${SERVER_PID}" 2>/dev/null || { log "server died during boot"; break; }
    grep -qE 'Done \(' "${RUN_LOG}" 2>/dev/null && saw_done=1
    grep -qF 'Servux bridge online' "${RUN_LOG}" 2>/dev/null && saw_servux=1
    (( saw_done && saw_servux )) && break
    sleep 1
done
if (( ! saw_done || ! saw_servux )); then
    write_result FAIL "boot failed (done=${saw_done} servux=${saw_servux})
$(tail -n40 "${RUN_LOG}" 2>/dev/null)"
    exit 1
fi
log "server booted, Servux bridge online"
sleep 2
send_rcon "op ProtoBot" >/dev/null 2>&1 || true

# ---------------------------------------------------------------- build tools
log "compiling bot + dump tool"
mkdir -p "${BOT_DIR}/build" "${TOOLS_DIR}/build"
"${JAVAC_BIN}" --release 25 -d "${BOT_DIR}/build" \
    -cp "${REPO_DIR}/build/classes/java/main" "${BOT_DIR}/src/ProtocolBot.java" || {
    write_result FAIL "bot compile failed"; exit 1; }
"${JAVAC_BIN}" --release 25 -d "${TOOLS_DIR}/build" \
    -cp "${REPO_DIR}/build/classes/java/main" "${TOOLS_DIR}/DumpLitematic.java" || {
    write_result FAIL "dump tool compile failed"; exit 1; }

# ---------------------------------------------------------------- scenario lib
PASTE_COUNT=0

# run_bot <fixture> <ox,oy,oz> [extra bot args…] — one full connect+paste.
run_bot() {
    local fixture="$1"; local origin="$2"; shift 2
    log "bot paste: $(basename "${fixture}") @ ${origin} $*"
    "${JAVA_BIN}" -cp "${BOT_DIR}/build:${REPO_DIR}/build/classes/java/main" ProtocolBot \
        --target "${RCON_HOST}:${SERVER_PORT}" \
        --litematic "${fixture}" \
        --paste-origin "${origin}" \
        --username ProtoBot \
        --hold-seconds "${BOT_HOLD_SECS}" \
        --wire v2 "$@" >>"${BOT_LOG}" 2>&1
    local rc=$?
    (( rc != 0 )) && log "WARN bot exit rc=${rc}"
    PASTE_COUNT=$((PASTE_COUNT + 1))
}

# wait_paste — waits until latest.log has PASTE_COUNT 'paste complete:' lines;
# echoes the newest line.
wait_paste() {
    local deadline=$(( $(date +%s) + PASTE_WAIT_SECS ))
    while (( $(date +%s) <= deadline )); do
        local n
        n=$( (grep -cE 'paste complete:' "${LOG_FILE}" 2>/dev/null || true) | head -n1)
        [[ -z "$n" ]] && n=0
        if (( n >= PASTE_COUNT )); then
            grep -E 'paste complete:' "${LOG_FILE}" | sed -n "${PASTE_COUNT}p"
            return 0
        fi
        sleep 1
    done
    echo "TIMEOUT"
    return 1
}

# assert_paste_line <line> <blocks> <entities> <label>
assert_paste_line() {
    local line="$1"; local blocks="$2"; local ents="$3"; local label="$4"
    CHECKS=$((CHECKS + 1))
    if [[ "${line}" == *"paste complete: ${blocks} blocks"* && "${line}" == *" ${ents} entities,"* ]]; then
        pass_check "${label}: ${blocks} blocks / ${ents} entities"
    else
        fail_check "${label}: expected '${blocks} blocks … ${ents} entities', got: ${line}"
    fi
}

forceload_box() { send_rcon "forceload add $1 $2 $3 $4" >/dev/null 2>&1 || true; sleep 2; }

# prefill <x> <y> <z> <block> — setblock with a retry, because the very
# first RCON world commands after boot can be dropped while the region is
# still spinning up (observed: S1 pre-fills missing, S2's identical ones
# fine). setblock is idempotent so the second call is harmless.
prefill() {
    send_rcon "setblock $1 $2 $3 $4 replace" >/dev/null 2>&1 || true
    sleep 2
    send_rcon "setblock $1 $2 $3 $4 replace" >/dev/null 2>&1 || true
}

# save_and_dump <name> <x1> <y1> <z1> <x2> <y2> <z2> — /litematica save then
# dump; echoes dump output.
save_and_dump() {
    local name="$1"; shift
    send_rcon "litematica save ${name} $*" >/dev/null 2>&1 || true
    # save is async — wait for the file
    local f="${SCHEM_OUT_DIR}/${name}.litematic"
    for _ in $(seq 1 30); do [[ -s "$f" ]] && break; sleep 1; done
    if [[ ! -s "$f" ]]; then echo "SAVE-MISSING"; return 1; fi
    sleep 1
    "${JAVA_BIN}" -cp "${TOOLS_DIR}/build:${REPO_DIR}/build/classes/java/main" DumpLitematic "$f"
}

# assert_dump_line <dump> <pattern> <label>
assert_dump_line() {
    local dump="$1"; local pattern="$2"; local label="$3"
    CHECKS=$((CHECKS + 1))
    if echo "${dump}" | grep -qF "${pattern}"; then
        pass_check "${label}"
    else
        fail_check "${label}: pattern '${pattern}' not in dump:
${dump}"
    fi
}

# assert_dump_absent <dump> <pattern> <label> — pattern must NOT appear.
assert_dump_absent() {
    local dump="$1"; local pattern="$2"; local label="$3"
    CHECKS=$((CHECKS + 1))
    if echo "${dump}" | grep -qF "${pattern}"; then
        fail_check "${label}: pattern '${pattern}' unexpectedly present in dump:
${dump}"
    else
        pass_check "${label}"
    fi
}

# ================================================================ scenarios
# fidelity-cube: region pos1=(10,0,20) size 5x3x5; blocks land at
# origin+(10..14, 0..2, 20..24). Non-air cells 66 incl 1 structure_void.
STAIRS_N='minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]'
STAIRS_E='minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]'

# ---------------- S1: WITH_NON_AIR + entity positions + structure_void
log "=== S1 ReplaceMode=WITH_NON_AIR ==="
forceload_box 8 18 16 26
prefill 12 121 22 minecraft:dirt   # cavity
prefill 14 122 24 minecraft:dirt   # structure_void spot
run_bot "${FIX_CUBE}" "0,120,0" --replace-mode with_non_air
line="$(wait_paste)" || true
assert_paste_line "${line}" 65 2 "S1 counts"
dump="$(save_and_dump s1 10 120 20 14 122 24)" || true
assert_dump_line "${dump}" "block 0,0,0 minecraft:stone" "S1 floor stone"
assert_dump_line "${dump}" "block 0,2,0 ${STAIRS_N}" "S1 stairs facing north"
assert_dump_line "${dump}" "block 2,1,2 minecraft:dirt" "S1 cavity dirt survives (schematic air skipped)"
assert_dump_line "${dump}" "block 4,2,4 minecraft:dirt" "S1 structure_void never pasted"
assert_dump_line "${dump}" "entity minecraft:armor_stand pos=2.500,1.000,2.250 yaw=0.0" "S1 armor stand exact pos (no double region offset)"
assert_dump_line "${dump}" "entity minecraft:minecart pos=1.500,1.000,3.250" "S1 minecart exact pos"

# ---------------- S2: NONE
log "=== S2 ReplaceMode=NONE ==="
forceload_box 72 18 80 26
prefill 74 120 20 minecraft:dirt   # floor corner
run_bot "${FIX_CUBE}" "64,120,0" --replace-mode none --ignore-entities
line="$(wait_paste)" || true
assert_paste_line "${line}" 64 0 "S2 counts (one dest-occupied write skipped, entities off)"
dump="$(save_and_dump s2 74 120 20 78 122 24)" || true
assert_dump_line "${dump}" "block 0,0,0 minecraft:dirt" "S2 dest dirt survives under NONE"
assert_dump_line "${dump}" "block 1,0,0 minecraft:stone" "S2 dest air got stone under NONE"

# ---------------- S3: ALL
log "=== S3 ReplaceMode=ALL ==="
forceload_box 136 18 144 26
prefill 140 121 22 minecraft:dirt  # cavity
run_bot "${FIX_CUBE}" "128,120,0" --replace-mode all --ignore-entities
line="$(wait_paste)" || true
assert_paste_line "${line}" 74 0 "S3 counts (air written too, void skipped)"
dump="$(save_and_dump s3 138 120 20 142 122 24)" || true
assert_dump_absent "${dump}" "block 2,1,2 " "S3 cavity dirt cleared by schematic air"
assert_dump_line "${dump}" "block 0,0,0 minecraft:stone" "S3 floor stone"

# ---------------- S4: Rotation CLOCKWISE_90
log "=== S4 Rotation=CLOCKWISE_90 ==="
forceload_box 166 8 174 16
run_bot "${FIX_CUBE}" "192,120,0" --rotation 1
line="$(wait_paste)" || true
assert_paste_line "${line}" 65 2 "S4 counts"
dump="$(save_and_dump s4 168 120 10 172 122 14)" || true
assert_dump_line "${dump}" "block 4,0,0 minecraft:stone" "S4 rotated box corner stone"
assert_dump_line "${dump}" "block 0,0,0 minecraft:stone" "S4 rotated box far corner stone"
assert_dump_line "${dump}" "block 4,2,0 ${STAIRS_E}" "S4 stairs rotated to facing east"
assert_dump_line "${dump}" "entity minecraft:armor_stand pos=2.750,1.000,2.500 yaw=90.0" "S4 armor stand cell-preserving transform + yaw+90"
assert_dump_line "${dump}" "entity minecraft:minecart pos=1.750,1.000,1.500" "S4 minecart fractional rotation"

# ---------------- S5: SubRegions overrides
log "=== S5 SubRegions (move A, disable B) ==="
forceload_box 254 -2 266 8
run_bot "${FIX_TOWERS}" "256,120,0" --sub-region "A:1:2,0,4:0" --sub-region "B:0:-:0"
line="$(wait_paste)" || true
assert_paste_line "${line}" 8 0 "S5 counts (A only)"
dump="$(save_and_dump s5 256 120 0 263 121 5)" || true
assert_dump_line "${dump}" "block 2,0,4 minecraft:stone" "S5 A pasted at override position"
assert_dump_absent "${dump}" "block 0,0,0 " "S5 nothing at A's original position"
assert_dump_absent "${dump}" "minecraft:diamond_block" "S5 disabled B not pasted"

# ---------------- S6: PasteLayerBehavior single layer
log "=== S6 layer-limited paste (y=121 only) ==="
forceload_box 328 18 336 26
run_bot "${FIX_CUBE}" "320,120,0" --layer-single y,121 --ignore-entities
line="$(wait_paste)" || true
assert_paste_line "${line}" 16 0 "S6 counts (ring layer only)"
dump="$(save_and_dump s6 330 120 20 334 122 24)" || true
assert_dump_line "${dump}" "block 0,1,0 minecraft:stone" "S6 allowed layer pasted"
assert_dump_absent "${dump}" "block 0,0,0 " "S6 layer below filtered"
assert_dump_absent "${dump}" "block 0,2,0 " "S6 layer above filtered"

# ---------------------------------------------------------------- verdict
send_rcon "save-all" >/dev/null 2>&1 || true
if (( ${#FAILURES[@]} == 0 )); then
    write_result PASS "All ${CHECKS} fidelity checks passed across 6 scenarios (wire v2, batching=16/task).

Paste log lines:
$(grep -E 'paste complete:' "${LOG_FILE}")"
    log "PASS (${CHECKS} checks)"
    send_rcon "stop" >/dev/null 2>&1 || true
    exit 0
else
    write_result FAIL "${#FAILURES[@]} of ${CHECKS} checks failed.

Server tail:
$(tail -n60 "${LOG_FILE}" 2>/dev/null)

Bot tail:
$(tail -n40 "${BOT_LOG}" 2>/dev/null)"
    log "FAIL (${#FAILURES[@]}/${CHECKS})"
    send_rcon "stop" >/dev/null 2>&1 || true
    exit 1
fi
