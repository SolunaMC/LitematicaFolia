#!/usr/bin/env bash
# LitematicaFolia smoke test harness.
#
# Boots a hermetic Folia (Luminol 26.1.2) server with the LitematicaFolia
# plugin installed, runs a few RCON commands, and scans logs/latest.log + the
# JVM stdout/stderr (run.log) for Folia-incompatibility patterns.
#
# Exit code: 0 = PASS, 1 = FAIL.
#
# Prerequisites:
#   - JDK 25 available (defaults to /opt/homebrew/opt/openjdk@25 on macOS)
#   - python3 (used by lib/rcon.py — stdlib only)
#   - curl (only if the Luminol jar still needs to be downloaded)
#   - optional: mcrcon  (brew install mcrcon)  — falls back to lib/rcon.py
#
# This script does NOT need network access if:
#   - the plugin jar is already built (build/libs/LitematicaFolia-*-all.jar)
#   - a Luminol/Paper jar is already cached under test-harness/server/

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
RESULT_FILE="${HARNESS_DIR}/result.txt"
RUN_LOG="${HARNESS_DIR}/run.log"
PID_FILE="${HARNESS_DIR}/server.pid"
LOG_FILE="${SERVER_DIR}/logs/latest.log"
RCON_LIB="${HARNESS_DIR}/lib/rcon.py"

# JDK 25: prefer brew openjdk@25 if present, otherwise fall back to PATH `java`.
JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [[ -x "${JAVA_HOME_DIR}/bin/java" ]]; then
    JAVA_BIN="${JAVA_HOME_DIR}/bin/java"
else
    JAVA_BIN="$(command -v java || true)"
fi

# Server runtime config.
SERVER_PORT=25699
RCON_PORT=25698
RCON_PASS="lftest"
RCON_HOST="127.0.0.1"

BOOT_TIMEOUT_SECS=180
QUIET_WINDOW_SECS=10
POST_RCON_WAIT_SECS=15
SHUTDOWN_TIMEOUT_SECS=60

# Server jar name we prefer to reuse if already cached (matches axiom-folia layout).
LUMINOL_JAR_NAME="luminol-paperclip-26.1.2.local-SNAPSHOT.jar"
# Fallback Paper jar name (chosen on download).
PAPER_JAR_NAME="paper-1.21.11.jar"

# Failure patterns: any match in run.log OR latest.log = FAIL.
FAIL_PATTERNS=(
    'WrongThreadException'
    'is not Folia compatible'
    'SEVERE.*[Ll]itematica'
    'Exception.*LitematicaFolia'
    'Could not load plugin'
    'Error loading plugin'
    'Initialized 0 plugins'
    'RegionFileSizeException'
    'Region file corruption'
    '\[STDERR\]'
)

cleanup() {
    local exit_code=$?
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            echo "[harness] cleanup: terminating server pid=${pid}" >&2
            kill -TERM "${pid}" 2>/dev/null || true
            for _ in $(seq 1 10); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            if kill -0 "${pid}" 2>/dev/null; then
                kill -KILL "${pid}" 2>/dev/null || true
            fi
        fi
        rm -f "${PID_FILE}"
    fi
    local stragglers
    stragglers="$(lsof -ti tcp:${SERVER_PORT} 2>/dev/null || true)"
    if [[ -n "${stragglers}" ]]; then
        echo "[harness] cleanup: killing leftover listeners on ${SERVER_PORT}: ${stragglers}" >&2
        kill -KILL ${stragglers} 2>/dev/null || true
    fi
    exit "${exit_code}"
}
trap cleanup EXIT INT TERM

log() { echo "[harness] $*" >&2; }

write_result() {
    local verdict="$1"
    local diag="$2"
    {
        echo "${verdict}"
        echo ""
        echo "${diag}"
        echo ""
        echo "harness: ${HARNESS_DIR}"
        echo "log: ${LOG_FILE}"
        echo "run.log: ${RUN_LOG}"
    } > "${RESULT_FILE}"
}

# Scan a file for any FAIL_PATTERN. Echo the matching line(s); rc=0 on match.
scan_for_fail() {
    local file="$1"
    [[ -f "${file}" ]] || return 1
    local pat
    for pat in "${FAIL_PATTERNS[@]}"; do
        if grep -E -m1 "${pat}" "${file}" >/dev/null 2>&1; then
            grep -E -n "${pat}" "${file}" | head -n5
            return 0
        fi
    done
    return 1
}

scan_all_logs() {
    local hit
    if hit="$(scan_for_fail "${RUN_LOG}")"; then
        printf 'run.log:\n%s\n' "${hit}"
        return 0
    fi
    if [[ -f "${LOG_FILE}" ]] && hit="$(scan_for_fail "${LOG_FILE}")"; then
        printf 'latest.log:\n%s\n' "${hit}"
        return 0
    fi
    return 1
}

# RCON helper. Tries mcrcon (if installed), else lib/rcon.py.
send_rcon() {
    local cmd="$1"
    if command -v mcrcon >/dev/null 2>&1; then
        mcrcon -H "${RCON_HOST}" -P "${RCON_PORT}" -p "${RCON_PASS}" "${cmd}"
    else
        python3 "${RCON_LIB}" "${RCON_HOST}" "${RCON_PORT}" "${RCON_PASS}" "${cmd}"
    fi
}

###############################################################################
# 1. Locate (and if needed, build) the plugin jar.
###############################################################################
find_plugin_jar() {
    # Pick the build/libs/LitematicaFolia-*-all.jar with the lexicographically
    # highest version. Output: absolute path on stdout, or empty.
    local libs="${REPO_DIR}/build/libs"
    [[ -d "${libs}" ]] || return 0
    local jar
    jar="$(ls -1 "${libs}"/LitematicaFolia-*-all.jar 2>/dev/null | sort -V | tail -n1)"
    [[ -n "${jar}" ]] && echo "${jar}"
}

PLUGIN_JAR="$(find_plugin_jar)"
if [[ -z "${PLUGIN_JAR}" ]]; then
    log "no plugin jar found in build/libs/ — running ./gradlew build..."
    if (cd "${REPO_DIR}" && ./gradlew build --no-daemon >>"${HARNESS_DIR}/gradle.log" 2>&1); then
        PLUGIN_JAR="$(find_plugin_jar)"
    else
        diag="./gradlew build FAILED. See ${HARNESS_DIR}/gradle.log"
        log "${diag}"
        write_result "FAIL" "${diag}"
        exit 1
    fi
fi

if [[ -z "${PLUGIN_JAR}" || ! -f "${PLUGIN_JAR}" ]]; then
    diag="plugin jar not found — wait for P1a/b (parser + paste) to land. Looked under ${REPO_DIR}/build/libs/LitematicaFolia-*-all.jar"
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "plugin jar: ${PLUGIN_JAR}"

###############################################################################
# 2. Java availability.
###############################################################################
if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
    diag="No usable Java found. Looked at \$JAVA_HOME (${JAVA_HOME_DIR}) and PATH."
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "java: ${JAVA_BIN}"

JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+).*/\1/')"
EXTRA_JVM_ARGS=()
if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 17 ]]; then
    EXTRA_JVM_ARGS+=(
        '--add-opens=java.base/java.lang=ALL-UNNAMED'
        '--enable-native-access=ALL-UNNAMED'
    )
fi
# Paperclip on JDK 25 wants the vector incubator module.
if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 21 ]]; then
    EXTRA_JVM_ARGS+=('--add-modules=jdk.incubator.vector')
fi

###############################################################################
# 3. Locate / download server jar.
###############################################################################
mkdir -p "${SERVER_DIR}/plugins"

SERVER_JAR=""
if [[ -f "${SERVER_DIR}/${LUMINOL_JAR_NAME}" ]]; then
    SERVER_JAR="${LUMINOL_JAR_NAME}"
    log "server: reusing cached Luminol jar (${SERVER_JAR})"
fi

# Also accept anything matching luminol*.jar already present.
if [[ -z "${SERVER_JAR}" ]]; then
    cand="$(ls -1 "${SERVER_DIR}"/luminol*.jar 2>/dev/null | head -n1 || true)"
    if [[ -n "${cand}" ]]; then
        SERVER_JAR="$(basename "${cand}")"
        log "server: reusing cached Luminol jar (${SERVER_JAR})"
    fi
fi

# Already-cached Paper jar?
if [[ -z "${SERVER_JAR}" && -f "${SERVER_DIR}/${PAPER_JAR_NAME}" ]]; then
    SERVER_JAR="${PAPER_JAR_NAME}"
    log "server: reusing cached Paper jar (${SERVER_JAR})"
fi

# Try to copy from the sibling axiom-folia harness — same Luminol build.
if [[ -z "${SERVER_JAR}" ]]; then
    sib="/Users/paulchauvat/axiom-folia/test-harness/server/${LUMINOL_JAR_NAME}"
    if [[ -f "${sib}" ]]; then
        log "server: copying Luminol jar from axiom-folia harness"
        cp "${sib}" "${SERVER_DIR}/${LUMINOL_JAR_NAME}"
        SERVER_JAR="${LUMINOL_JAR_NAME}"
    fi
fi

# Otherwise, try to download.
if [[ -z "${SERVER_JAR}" ]]; then
    log "server: no cached jar; attempting download"
    pushd "${SERVER_DIR}" >/dev/null

    # 1) Forgejo Luminol release.
    forgejo_url="https://forgejo.ekaii.fr/admin_ekaii/luminol-ekaii/releases/latest"
    log "server: probing ${forgejo_url}"
    if asset_url="$(curl -sL --max-time 30 "${forgejo_url}" \
        | grep -oE 'href="[^"]+luminol[^"]*\.jar"' \
        | head -n1 | sed -E 's/href="([^"]+)"/\1/')"; then
        if [[ -n "${asset_url}" ]]; then
            case "${asset_url}" in
                http*) full_url="${asset_url}" ;;
                /*)    full_url="https://forgejo.ekaii.fr${asset_url}" ;;
                *)     full_url="https://forgejo.ekaii.fr/${asset_url}" ;;
            esac
            log "server: trying ${full_url}"
            if curl -fLsS --max-time 600 -o "${LUMINOL_JAR_NAME}.part" "${full_url}"; then
                mv "${LUMINOL_JAR_NAME}.part" "${LUMINOL_JAR_NAME}"
                SERVER_JAR="${LUMINOL_JAR_NAME}"
            fi
        fi
    fi

    # 2) GitHub LuminolMC release (best effort).
    if [[ -z "${SERVER_JAR}" ]]; then
        gh_api="https://api.github.com/repos/LuminolMC/Luminol/releases/latest"
        log "server: probing ${gh_api}"
        if asset_url="$(curl -sL --max-time 30 "${gh_api}" \
            | grep -oE '"browser_download_url":[[:space:]]*"[^"]+\.jar"' \
            | head -n1 | sed -E 's/.*"(https:[^"]+)"/\1/')"; then
            if [[ -n "${asset_url}" ]]; then
                log "server: trying ${asset_url}"
                if curl -fLsS --max-time 600 -o "${LUMINOL_JAR_NAME}.part" "${asset_url}"; then
                    mv "${LUMINOL_JAR_NAME}.part" "${LUMINOL_JAR_NAME}"
                    SERVER_JAR="${LUMINOL_JAR_NAME}"
                fi
            fi
        fi
    fi

    # 3) Paper 1.21.11 (Folia merged in 26.1+; safe fallback per axiom-paper-folia memory).
    if [[ -z "${SERVER_JAR}" ]]; then
        log "server: falling back to Paper 1.21.11"
        if builds_json="$(curl -fsSL --max-time 30 'https://api.papermc.io/v2/projects/paper/versions/1.21.11/builds')"; then
            # Pick the highest build number; do it without jq.
            latest_build="$(echo "${builds_json}" | grep -oE '"build":[[:space:]]*[0-9]+' \
                | grep -oE '[0-9]+' | sort -n | tail -n1)"
            if [[ -n "${latest_build}" ]]; then
                paper_url="https://api.papermc.io/v2/projects/paper/versions/1.21.11/builds/${latest_build}/downloads/paper-1.21.11-${latest_build}.jar"
                log "server: trying ${paper_url}"
                if curl -fLsS --max-time 600 -o "${PAPER_JAR_NAME}.part" "${paper_url}"; then
                    mv "${PAPER_JAR_NAME}.part" "${PAPER_JAR_NAME}"
                    SERVER_JAR="${PAPER_JAR_NAME}"
                fi
            fi
        fi
    fi

    popd >/dev/null
fi

if [[ -z "${SERVER_JAR}" ]]; then
    diag="No server jar available and all downloads failed. Drop a Luminol 26.1.2 jar at ${SERVER_DIR}/${LUMINOL_JAR_NAME} and re-run."
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "server jar: ${SERVER_JAR}"

###############################################################################
# 4. Hermetic prep: clean state, write properties + EULA, copy plugin jar.
###############################################################################
log "wiping stale state"
rm -f "${RESULT_FILE}" "${RUN_LOG}" "${PID_FILE}" "${HARNESS_DIR}/gradle.log"
rm -rf "${SERVER_DIR}/logs" \
       "${SERVER_DIR}/world_smoke" "${SERVER_DIR}/world_smoke_nether" "${SERVER_DIR}/world_smoke_the_end" \
       "${SERVER_DIR}/world" "${SERVER_DIR}/world_nether" "${SERVER_DIR}/world_the_end" \
       "${SERVER_DIR}/cache" \
       "${SERVER_DIR}/usercache.json" \
       "${SERVER_DIR}/banned-ips.json" "${SERVER_DIR}/banned-players.json" \
       "${SERVER_DIR}/ops.json" "${SERVER_DIR}/whitelist.json" \
       "${SERVER_DIR}/plugins/.paper-remapped"

# Keep `versions/` and `libraries/` between runs — they are expensive to rebuild.

cat > "${SERVER_DIR}/eula.txt" <<'EOF'
# Auto-accepted by litematica-folia-ekaii test harness.
eula=true
EOF

cat > "${SERVER_DIR}/server.properties" <<EOF
# Auto-generated by litematica-folia-ekaii test harness — do not hand-edit.
online-mode=false
server-port=${SERVER_PORT}
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASS}
broadcast-rcon-to-ops=false
motd=LitematicaFolia smoke harness
level-name=world_smoke
max-tick-time=-1
spawn-protection=0
view-distance=4
simulation-distance=4
allow-flight=true
network-compression-threshold=-1
EOF

# Wipe stale LitematicaFolia jars and copy fresh one.
rm -f "${SERVER_DIR}/plugins/"LitematicaFolia-*.jar 2>/dev/null || true
cp "${PLUGIN_JAR}" "${SERVER_DIR}/plugins/"
log "plugin installed: $(basename "${PLUGIN_JAR}")"

###############################################################################
# 5. Boot the server.
###############################################################################
log "booting server (port ${SERVER_PORT}, rcon ${RCON_PORT})"
(
    cd "${SERVER_DIR}" || exit 99
    export JAVA_HOME="${JAVA_HOME_DIR}"
    exec "${JAVA_BIN}" -Xmx2G -Xms1G "${EXTRA_JVM_ARGS[@]}" -jar "${SERVER_JAR}" nogui
) >"${RUN_LOG}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" > "${PID_FILE}"
log "server pid=${SERVER_PID}"

# Wait for "Done (" within BOOT_TIMEOUT_SECS, watching for fail patterns.
deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECS ))
saw_loading=0
saw_done=0
done_time=0
while :; do
    now=$(date +%s)
    (( now > deadline )) && break
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
        log "server JVM died before Done"
        break
    fi
    if [[ -f "${RUN_LOG}" ]]; then
        if (( saw_loading == 0 )) && grep -F "[LitematicaFolia]" "${RUN_LOG}" >/dev/null 2>&1; then
            saw_loading=1
            log "saw LitematicaFolia log lines"
        fi
        if (( saw_done == 0 )) && grep -E 'Done \(' "${RUN_LOG}" >/dev/null 2>&1; then
            saw_done=1
            done_time=$(date +%s)
            log "saw Done("
            break
        fi
        if fail_hit="$(scan_for_fail "${RUN_LOG}")"; then
            log "fail pattern matched during boot:"
            echo "${fail_hit}" >&2
            break
        fi
    fi
    sleep 1
done

if (( saw_done == 0 )); then
    diag="Server did not reach 'Done (' within ${BOOT_TIMEOUT_SECS}s."
    if (( saw_loading == 1 )); then
        diag="${diag} LitematicaFolia was being loaded but boot stalled."
    else
        diag="${diag} LitematicaFolia log lines never observed."
    fi
    if fail_hit="$(scan_all_logs)"; then
        diag="${diag}
Fail pattern hits:
${fail_hit}"
    fi
    tail_log="$(tail -n40 "${RUN_LOG}" 2>/dev/null || true)"
    diag_full="$(printf '%s\nLast 40 log lines:\n%s\n' "${diag}" "${tail_log}")"
    write_result "FAIL" "${diag_full}"
    exit 1
fi

###############################################################################
# 6. Quiet window + RCON shake-out + post-RCON settle.
###############################################################################
log "observing ${QUIET_WINDOW_SECS}s quiet window"
quiet_end=$(( done_time + QUIET_WINDOW_SECS ))
while (( $(date +%s) < quiet_end )); do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
        diag="Server JVM exited during quiet window."
        diag_full="$(printf '%s\nLast 40 log lines:\n%s\n' "${diag}" "$(tail -n40 "${RUN_LOG}" 2>/dev/null)")"
        write_result "FAIL" "${diag_full}"
        exit 1
    fi
    if fail_hit="$(scan_all_logs)"; then
        diag="$(printf 'Fail pattern in quiet window:\n%s\n\nLast 40 log lines:\n%s\n' \
            "${fail_hit}" "$(tail -n40 "${RUN_LOG}" 2>/dev/null)")"
        write_result "FAIL" "${diag}"
        exit 1
    fi
    sleep 1
done

log "quiet window clean; firing rcon commands"
rcon_diag=""
RCON_CMDS=(
    "op @a"
    "time set day"
    "weather clear"
    "tps"
    "list"
    "save-all"
)
for cmd in "${RCON_CMDS[@]}"; do
    if rcon_out="$(send_rcon "$cmd" 2>&1)"; then
        rcon_diag+="${cmd} -> ${rcon_out}
"
    else
        rcon_diag+="${cmd} -> RCON_FAILED: ${rcon_out}
"
    fi
    sleep 1
done

log "post-rcon ${POST_RCON_WAIT_SECS}s settle"
post_end=$(( $(date +%s) + POST_RCON_WAIT_SECS ))
while (( $(date +%s) < post_end )); do
    if fail_hit="$(scan_all_logs)"; then
        diag="$(printf 'Fail pattern after rcon:\n%s\n\nLast 40 log lines:\n%s\n' \
            "${fail_hit}" "$(tail -n40 "${RUN_LOG}" 2>/dev/null)")"
        write_result "FAIL" "${diag}"
        exit 1
    fi
    sleep 1
done

# Final scan covers anything we may have missed.
if fail_hit="$(scan_all_logs)"; then
    diag="$(printf 'Fail pattern (final scan):\n%s\n\nLast 40 log lines:\n%s\n' \
        "${fail_hit}" "$(tail -n40 "${RUN_LOG}" 2>/dev/null)")"
    write_result "FAIL" "${diag}"
    exit 1
fi

###############################################################################
# 7. Graceful shutdown + PASS.
###############################################################################
log "sending rcon stop"
send_rcon "stop" >/dev/null 2>&1 || true

shutdown_deadline=$(( $(date +%s) + SHUTDOWN_TIMEOUT_SECS ))
while (( $(date +%s) < shutdown_deadline )); do
    kill -0 "${SERVER_PID}" 2>/dev/null || break
    sleep 1
done
if kill -0 "${SERVER_PID}" 2>/dev/null; then
    log "server still alive after ${SHUTDOWN_TIMEOUT_SECS}s — TERM"
    kill -TERM "${SERVER_PID}" 2>/dev/null || true
fi

done_line="$(grep -E 'Done \(' "${RUN_LOG}" | head -n1)"
warn_count="$(grep -cE 'WARN' "${RUN_LOG}" || true)"
err_count="$(grep -cE 'ERROR|SEVERE' "${RUN_LOG}" || true)"

diag_full="$(printf 'LitematicaFolia enabled and survived %ss quiet + rcon shake-out.

Plugin jar : %s
Server jar : %s
Java       : %s
Done line  : %s
WARN count : %s
ERROR count: %s

RCON:
%b' \
    "$((QUIET_WINDOW_SECS + POST_RCON_WAIT_SECS))" \
    "$(basename "${PLUGIN_JAR}")" \
    "${SERVER_JAR}" \
    "${JAVA_BIN}" \
    "${done_line}" \
    "${warn_count}" \
    "${err_count}" \
    "${rcon_diag}")"
write_result "PASS" "${diag_full}"
log "PASS"
exit 0
