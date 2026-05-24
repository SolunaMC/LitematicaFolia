#!/usr/bin/env bash
# LitematicaFolia stress-smoke harness.
#
# Generates the large 256×64×256 fixture (≈4.2M cells), boots the server,
# stages the fixture into the plugin schematics dir, and fires
# `/litematica paste large-256 100 64 100`. Watches the log for paste
# completion or any of the known failure patterns. PASS only if paste
# completes AND no failure pattern is seen.
#
# Expected wall-clock: 5–10 min for the paste phase. Hard timeout 15 min.
# Exit code: 0 = PASS, 1 = FAIL.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
RESULT_FILE="${HARNESS_DIR}/stress-result.txt"
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

# Paste origin coords (mid-world to ensure no axis crosses 0).
PASTE_X=100
PASTE_Y=64
PASTE_Z=100

BOOT_TIMEOUT_SECS=180
PASTE_TIMEOUT_SECS=900   # 15 min hard cap

FAIL_PATTERNS=(
    'WrongThreadException'
    'is not Folia compatible'
    'SEVERE.*[Ll]itematica'
    'Exception.*LitematicaFolia'
    'Could not load plugin'
    'RegionFileSizeException'
    'OutOfMemoryError'
    'Region file corruption'
    'java.lang.NullPointerException.*LitematicaFolia'
    '\[STDERR\]'
)

log() { echo "[stress-smoke] $*" >&2; }

write_result() {
    local verdict="$1"
    local diag="$2"
    {
        echo "${verdict}"
        echo ""
        echo "${diag}"
    } > "${RESULT_FILE}"
}

send_rcon() {
    local cmd="$1"
    python3 "${RCON_LIB}" "${RCON_HOST}" "${RCON_PORT}" "${RCON_PASS}" "${cmd}"
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

###############################################################################
# 1. Generate the large fixture via gradle.
###############################################################################
log "generating large-256 fixture (litematica.stress=true gradle test)…"
GEN_LOG="${HARNESS_DIR}/stress-gen.log"
if ! ( cd "${REPO_DIR}" && ./gradlew test --tests fr.ekaii.litematica.core.LargeFixtureTest \
        -Dlitematica.stress=true ) >"${GEN_LOG}" 2>&1; then
    diag="$(printf 'Fixture generation failed. Last 40 lines:\n%s' "$(tail -n40 "${GEN_LOG}")")"
    log "${diag}"
    write_result "FAIL" "${diag}"
    exit 1
fi

LARGE_FIXTURE="${FIXTURE_DIR}/large-256.litematic"
if [[ ! -f "${LARGE_FIXTURE}" ]]; then
    diag="Fixture not present after generation: ${LARGE_FIXTURE}"
    write_result "FAIL" "${diag}"
    exit 1
fi
FIXTURE_SIZE_BYTES="$(stat -f '%z' "${LARGE_FIXTURE}" 2>/dev/null || stat -c '%s' "${LARGE_FIXTURE}")"
log "fixture ready: ${LARGE_FIXTURE} ($(echo "${FIXTURE_SIZE_BYTES}" | awk '{ printf "%.1f MiB", $1/1048576 }'))"

###############################################################################
# 2. Boot server via run-tests.sh (clean boot, plugin loaded).
###############################################################################
log "running run-tests.sh to boot a fresh server…"
if ! "${HARNESS_DIR}/run-tests.sh" >"${HARNESS_DIR}/stress-runtests.log" 2>&1; then
    diag="$(printf 'run-tests.sh failed. Last 40 lines:\n%s' "$(tail -n40 "${HARNESS_DIR}/stress-runtests.log")")"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "run-tests.sh PASS — re-booting server for stress phase"

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
    write_result "FAIL" "No server jar in ${SERVER_DIR}"
    exit 1
fi

# Snapshot run.log line count so we only look at new lines for boot detection.
REBOOT_LOG_OFFSET=$(wc -l <"${RUN_LOG}" 2>/dev/null || echo 0)
REBOOT_LOG_OFFSET=$((REBOOT_LOG_OFFSET + 0))

(
    cd "${SERVER_DIR}" || exit 99
    export JAVA_HOME="${JAVA_HOME_DIR}"
    # 4G heap for the stress test (default smoke uses 2G).
    exec "${JAVA_BIN}" -Xmx4G -Xms2G "${EXTRA_JVM_ARGS[@]}" -jar "$(basename "${SERVER_JAR}")" nogui
) >>"${RUN_LOG}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" > "${PID_FILE}"

cleanup() {
    local exit_code=$?
    if [[ -f "${PID_FILE}" ]]; then
        local pid; pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            log "shutting down server pid=${pid}"
            send_rcon "stop" >/dev/null 2>&1 || true
            for _ in $(seq 1 60); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            kill -TERM "${pid}" 2>/dev/null || true
            rm -f "${PID_FILE}"
        fi
    fi
    exit "${exit_code}"
}
trap cleanup EXIT INT TERM

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
    diag="$(printf 'Server reboot did not reach Done within %ss.\nLast 40 lines:\n%s' \
        "${BOOT_TIMEOUT_SECS}" "$(tail -n40 "${RUN_LOG}")")"
    write_result "FAIL" "${diag}"
    exit 1
fi
log "server reboot ready"
sleep 5

###############################################################################
# 3. Stage the large fixture.
###############################################################################
mkdir -p "${PLUGIN_SCHEM_DIR}"
cp "${LARGE_FIXTURE}" "${PLUGIN_SCHEM_DIR}/"
log "staged large-256.litematic into plugin dir"

###############################################################################
# 4. Fire the paste — async, then poll the log for completion / failure.
###############################################################################
log "issuing /litematica paste large-256 ${PASTE_X} ${PASTE_Y} ${PASTE_Z}"
paste_start_epoch=$(date +%s)

# Issue the paste — the command returns immediately (executor schedules async).
out="$(send_rcon "litematica paste large-256 ${PASTE_X} ${PASTE_Y} ${PASTE_Z}" 2>&1 || true)"
log "rcon: ${out}"

# Watch the log for completion or failure pattern.
deadline=$(( $(date +%s) + PASTE_TIMEOUT_SECS ))
peak_rss_kb=0
while :; do
    now=$(date +%s)
    if (( now > deadline )); then
        diag="$(printf 'Paste did not complete within %ss.\n\nLast 60 log lines:\n%s' \
            "${PASTE_TIMEOUT_SECS}" "$(tail -n60 "${LOG_FILE}")")"
        write_result "FAIL" "${diag}"
        exit 1
    fi

    # Failure pattern check.
    if hit="$(scan_for_fail "${LOG_FILE}")"; then
        diag="$(printf 'Fail pattern hit during stress paste:\n%s\n\nLast 60 log lines:\n%s' \
            "${hit}" "$(tail -n60 "${LOG_FILE}")")"
        write_result "FAIL" "${diag}"
        exit 1
    fi

    # Sample peak RSS — best-effort, macOS+linux variants.
    rss_kb=$(ps -o rss= -p "${SERVER_PID}" 2>/dev/null | tr -d ' ' || echo 0)
    if [[ -n "${rss_kb}" ]] && (( rss_kb > peak_rss_kb )); then
        peak_rss_kb=${rss_kb}
    fi

    # Completion signal — PasteOperation logs through Logger (no direct sender
    # for RCON), so we look in latest.log for the plugin's progress message.
    if grep -E -m1 'paste complete:' "${LOG_FILE}" >/dev/null 2>&1; then
        paste_end_epoch=$(date +%s)
        paste_secs=$(( paste_end_epoch - paste_start_epoch ))
        peak_rss_mib=$(( peak_rss_kb / 1024 ))
        log "paste reported complete after ${paste_secs}s (peak RSS ${peak_rss_mib} MiB)"
        # Final scan for any straggler failure pattern.
        if hit="$(scan_for_fail "${LOG_FILE}")"; then
            diag="$(printf 'Post-completion fail-pattern hit:\n%s' "${hit}")"
            write_result "FAIL" "${diag}"
            exit 1
        fi
        diag="$(printf 'Stress paste PASS.\nDuration: %ss\nPeak RSS: %s MiB\nFixture size: %s bytes\nRCON: %s' \
            "${paste_secs}" "${peak_rss_mib}" "${FIXTURE_SIZE_BYTES}" "${out}")"
        write_result "PASS" "${diag}"
        log "PASS"
        exit 0
    fi

    sleep 5
done
