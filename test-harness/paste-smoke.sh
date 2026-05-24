#!/usr/bin/env bash
# LitematicaFolia paste-smoke harness.
#
# Run AFTER ./run-tests.sh PASS. Boots the server (or reuses an already-running
# instance), copies schematic fixtures into plugins/LitematicaFolia/schematics/,
# fires `/litematica paste <fixture> 100 64 100` for each fixture, and checks
# logs + world-dir mtime for evidence of actual writes.
#
# Exit code: 0 = PASS, 1 = FAIL.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
RESULT_FILE="${HARNESS_DIR}/paste-result.txt"
RUN_LOG="${HARNESS_DIR}/run.log"
PID_FILE="${HARNESS_DIR}/server.pid"
LOG_FILE="${SERVER_DIR}/logs/latest.log"
RCON_LIB="${HARNESS_DIR}/lib/rcon.py"

FIXTURE_DIR="${REPO_DIR}/schematics-fixtures"
PLUGIN_SCHEM_DIR="${SERVER_DIR}/plugins/LitematicaFolia/schematics"
WORLD_DIR="${SERVER_DIR}/world_smoke"

RCON_HOST="127.0.0.1"
RCON_PORT="25698"
RCON_PASS="lftest"
SERVER_PORT=25699

PASTE_X=100
PASTE_Y=64
PASTE_Z=100
PASTE_SETTLE_SECS=5
SAVE_SETTLE_SECS=10
BOOT_TIMEOUT_SECS=180

FAIL_PATTERNS=(
    'WrongThreadException'
    'is not Folia compatible'
    'SEVERE.*[Ll]itematica'
    'Exception.*LitematicaFolia'
    'Could not load plugin'
    'RegionFileSizeException'
    'Region file corruption'
    '\[STDERR\]'
)

log() { echo "[paste-smoke] $*" >&2; }

write_result() {
    local verdict="$1"
    local diag="$2"
    {
        echo "${verdict}"
        echo ""
        echo "${diag}"
    } > "${RESULT_FILE}"
}

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
        printf 'run.log:\n%s\n' "${hit}"; return 0
    fi
    if [[ -f "${LOG_FILE}" ]] && hit="$(scan_for_fail "${LOG_FILE}")"; then
        printf 'latest.log:\n%s\n' "${hit}"; return 0
    fi
    return 1
}

send_rcon() {
    local cmd="$1"
    if command -v mcrcon >/dev/null 2>&1; then
        mcrcon -H "${RCON_HOST}" -P "${RCON_PORT}" -p "${RCON_PASS}" "${cmd}"
    else
        python3 "${RCON_LIB}" "${RCON_HOST}" "${RCON_PORT}" "${RCON_PASS}" "${cmd}"
    fi
}

dir_mtime() {
    local dir="$1"
    [[ -d "${dir}" ]] || { echo 0; return; }
    if stat -f '%m' "${dir}" >/dev/null 2>&1; then
        stat -f '%m' "${dir}"
    else
        stat -c '%Y' "${dir}"
    fi
}

###############################################################################
# 1. Sanity checks.
###############################################################################
if [[ ! -d "${FIXTURE_DIR}" ]]; then
    diag="Fixture dir missing: ${FIXTURE_DIR} — waiting for parser agent to drop schematics."
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi

FIXTURES=()
while IFS= read -r line; do
    FIXTURES+=("$line")
done < <(find "${FIXTURE_DIR}" -maxdepth 1 -type f -name '*.litematic' 2>/dev/null | sort)
if (( ${#FIXTURES[@]} == 0 )); then
    diag="No *.litematic fixtures found in ${FIXTURE_DIR}. Parser agent has not committed yet."
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "found ${#FIXTURES[@]} fixture(s)"

###############################################################################
# 2. Boot server if not already running.
###############################################################################
server_running=0
if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    server_running=1
    log "server already running (pid=$(cat "${PID_FILE}")) — reusing"
else
    # If no server running, we need run-tests.sh to have left a working setup
    # but we don't want to re-boot in that case (it wipes state). Caller is
    # expected to chain: ./run-tests.sh && ./paste-smoke.sh. If not running,
    # boot a fresh one.
    log "no running server — booting via run-tests.sh first"
    if ! "${HARNESS_DIR}/run-tests.sh"; then
        diag="run-tests.sh failed — see ${HARNESS_DIR}/result.txt"
        log "${diag}"
        write_result "FAIL" "${diag}"
        exit 1
    fi
    # run-tests.sh shuts the server down on PASS. Re-boot it for the paste
    # phase, hermetic re-use of state from the previous run.
    log "re-booting server for paste phase"

    JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
    JAVA_BIN="${JAVA_HOME_DIR}/bin/java"
    [[ -x "${JAVA_BIN}" ]] || JAVA_BIN="$(command -v java)"
    JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+).*/\1/')"
    EXTRA_JVM_ARGS=()
    if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 17 ]]; then
        EXTRA_JVM_ARGS+=('--add-opens=java.base/java.lang=ALL-UNNAMED' '--enable-native-access=ALL-UNNAMED')
    fi
    if [[ -n "${JAVA_MAJOR}" && "${JAVA_MAJOR}" -ge 21 ]]; then
        EXTRA_JVM_ARGS+=('--add-modules=jdk.incubator.vector')
    fi
    SERVER_JAR="$(ls -1 "${SERVER_DIR}"/luminol*.jar 2>/dev/null; ls -1 "${SERVER_DIR}"/paper*.jar 2>/dev/null; true)"
    SERVER_JAR="$(printf '%s\n' "${SERVER_JAR}" | head -n1)"
    if [[ -z "${SERVER_JAR}" ]]; then
        diag="No server jar in ${SERVER_DIR}"
        write_result "FAIL" "${diag}"
        exit 1
    fi
    # Snapshot run.log length BEFORE re-boot so the 'Done (' grep below only
    # sees lines emitted by the new server process.
    REBOOT_LOG_OFFSET=$(wc -l <"${RUN_LOG}" 2>/dev/null || echo 0)
    REBOOT_LOG_OFFSET=$((REBOOT_LOG_OFFSET + 0))
    (
        cd "${SERVER_DIR}" || exit 99
        export JAVA_HOME="${JAVA_HOME_DIR}"
        exec "${JAVA_BIN}" -Xmx2G -Xms1G "${EXTRA_JVM_ARGS[@]}" -jar "$(basename "${SERVER_JAR}")" nogui
    ) >>"${RUN_LOG}" 2>&1 &
    SERVER_PID=$!
    echo "${SERVER_PID}" > "${PID_FILE}"

    boot_done_after_offset() {
        tail -n +"$((REBOOT_LOG_OFFSET + 1))" "${RUN_LOG}" 2>/dev/null | grep -E 'Done \(' >/dev/null 2>&1
    }

    deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECS ))
    while :; do
        (( $(date +%s) > deadline )) && break
        if boot_done_after_offset; then break; fi
        kill -0 "${SERVER_PID}" 2>/dev/null || break
        sleep 1
    done
    if ! boot_done_after_offset; then
        diag="server re-boot did not reach Done within ${BOOT_TIMEOUT_SECS}s"
        write_result "FAIL" "${diag}"
        exit 1
    fi
    sleep 5
fi

cleanup_paste() {
    local exit_code=$?
    if (( server_running == 0 )) && [[ -f "${PID_FILE}" ]]; then
        local pid; pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            log "shutting down server pid=${pid}"
            send_rcon "stop" >/dev/null 2>&1 || true
            for _ in $(seq 1 30); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            kill -TERM "${pid}" 2>/dev/null || true
            rm -f "${PID_FILE}"
        fi
    fi
    exit "${exit_code}"
}
trap cleanup_paste EXIT INT TERM

###############################################################################
# 3. Stage fixtures into plugin dir.
###############################################################################
mkdir -p "${PLUGIN_SCHEM_DIR}"
for fx in "${FIXTURES[@]}"; do
    cp "${fx}" "${PLUGIN_SCHEM_DIR}/"
done
log "staged $(ls -1 "${PLUGIN_SCHEM_DIR}"/*.litematic 2>/dev/null | wc -l | tr -d ' ') fixture(s) into plugin dir"

###############################################################################
# 4. Paste each fixture, scan for failures.
###############################################################################
world_mtime_before="$(dir_mtime "${WORLD_DIR}")"
paste_diag=""
for fx in "${FIXTURES[@]}"; do
    name="$(basename "${fx}" .litematic)"
    cmd="litematica paste ${name} ${PASTE_X} ${PASTE_Y} ${PASTE_Z}"
    log "rcon: ${cmd}"
    if out="$(send_rcon "${cmd}" 2>&1)"; then
        paste_diag+="${cmd} -> ${out}
"
    else
        paste_diag+="${cmd} -> RCON_FAILED: ${out}
"
    fi
    sleep "${PASTE_SETTLE_SECS}"
    if fail_hit="$(scan_all_logs)"; then
        diag="$(printf 'Fail pattern after paste %s:\n%s\n\nPaste diag:\n%s\n\nLast 40 log lines:\n%s\n' \
            "${name}" "${fail_hit}" "${paste_diag}" "$(tail -n40 "${RUN_LOG}" 2>/dev/null)")"
        write_result "FAIL" "${diag}"
        exit 1
    fi
done

###############################################################################
# 5. Force a save, verify either world files updated OR a "paste complete"
#    line is present in latest.log.
###############################################################################
log "rcon save-all"
send_rcon "save-all" >/dev/null 2>&1 || true
sleep "${SAVE_SETTLE_SECS}"

# Look for the plugin's authoritative completion line in latest.log first —
# this is more reliable than dir mtime (which only ticks when entries are
# added/removed, not when files inside are rewritten in place).
paste_complete_count="$(grep -cE 'paste complete:' "${LOG_FILE}" 2>/dev/null || echo 0)"
paste_complete_count=$((paste_complete_count + 0))

world_mtime_after="$(dir_mtime "${WORLD_DIR}")"
# Walk children too — region files / level.dat overwrites change FILE mtimes
# but not parent dir mtime on macOS.
deepest_mtime=0
while IFS= read -r f; do
    fm=$(stat -f '%m' "$f" 2>/dev/null || stat -c '%Y' "$f" 2>/dev/null || echo 0)
    [[ "${fm}" -gt "${deepest_mtime}" ]] && deepest_mtime=$fm
done < <(find "${WORLD_DIR}" -type f 2>/dev/null)

if (( paste_complete_count == 0 )) && (( deepest_mtime <= world_mtime_before )) \
        && [[ "${world_mtime_after}" == "${world_mtime_before}" ]]; then
    diag="$(printf 'No paste-complete log entry AND no file mtime advance after paste.\nworld_mtime_before=%s\nworld_mtime_after=%s\ndeepest_file_mtime=%s\n\nPaste diag:\n%s\n' \
        "${world_mtime_before}" "${world_mtime_after}" "${deepest_mtime}" "${paste_diag}")"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "paste-complete lines: ${paste_complete_count}; deepest file mtime: ${deepest_mtime} (was ${world_mtime_before})"

if fail_hit="$(scan_all_logs)"; then
    diag="$(printf 'Fail pattern in final scan:\n%s\n\nPaste diag:\n%s\n' "${fail_hit}" "${paste_diag}")"
    write_result "FAIL" "${diag}"
    exit 1
fi

diag_full="$(printf 'Pasted %d fixture(s), world dir mtime advanced (%s -> %s), logs clean.

Paste diag:
%b' \
    "${#FIXTURES[@]}" "${world_mtime_before}" "${world_mtime_after}" "${paste_diag}")"
write_result "PASS" "${diag_full}"
log "PASS"
exit 0
