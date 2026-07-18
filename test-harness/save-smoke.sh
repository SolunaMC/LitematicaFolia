#!/usr/bin/env bash
# LitematicaFolia save-smoke harness — paste → save → re-paste → save round-trip.
#
# Guards the coordinate contract that was misdiagnosed as "chunk snapshot
# staleness" in v0.3.0: a paste places region blocks at
#   world = paste origin + region Position (Litematica convention)
# and stone-cube-4 is generated (Fixtures.java) with Position=(0,64,0) — so a
# paste at (x,y,z) puts the cube at (x, y+64, z). The original "0 blocks after
# paste" report saved the wrong box. This smoke saves the RIGHT box and
# asserts all 64 blocks are captured, then re-pastes the saved file (whose
# origin is normalized to (0,0,0) on save) and saves it again where it landed.
#
# Steps:
#   1. Boot the hermetic server (reuse if already running, else via run-tests.sh).
#   2. Paste stone-cube-4 (4x4x4 stone = 64 blocks) at PASTE_X/Y/Z.
#   3. /litematica save smoke_roundtrip over (PASTE_X, PASTE_Y+64, PASTE_Z)+3.
#   4. Assert the 'save complete:' line reports exactly 64 blocks.
#   5. Re-paste smoke_roundtrip at REPASTE_X/Y/Z — origin-normalized saves land
#      exactly there — and save that box too, expecting 64 again.
#
# Exit code: 0 = PASS, 1 = FAIL. Verdict in save-result.txt.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${HARNESS_DIR}/.." && pwd)"
SERVER_DIR="${HARNESS_DIR}/server"
RESULT_FILE="${HARNESS_DIR}/save-result.txt"
RUN_LOG="${HARNESS_DIR}/run.log"
PID_FILE="${HARNESS_DIR}/server.pid"
LOG_FILE="${SERVER_DIR}/logs/latest.log"
RCON_LIB="${HARNESS_DIR}/lib/rcon.py"

FIXTURE="${REPO_DIR}/schematics-fixtures/stone-cube-4.litematic"
PLUGIN_SCHEM_DIR="${SERVER_DIR}/plugins/LitematicaFolia/schematics"

RCON_HOST="127.0.0.1"
RCON_PORT="25698"
RCON_PASS="lftest"

PASTE_X=100; PASTE_Y=64; PASTE_Z=100
FIXTURE_DY=64               # stone-cube-4 region Position=(0,64,0), see Fixtures.java
EXPECT_BLOCKS=64            # 4x4x4 all-stone fixture
REPASTE_X=140; REPASTE_Y=70; REPASTE_Z=140
SETTLE_SECS=5
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

log() { echo "[save-smoke] $*" >&2; }

write_result() {
    { echo "$1"; echo ""; echo "$2"; } > "${RESULT_FILE}"
}

scan_for_fail() {
    local file="$1"; [[ -f "${file}" ]] || return 1
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
    if hit="$(scan_for_fail "${RUN_LOG}")"; then printf 'run.log:\n%s\n' "${hit}"; return 0; fi
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

###############################################################################
# 1. Sanity + boot (reuse a running server, else boot via run-tests.sh).
###############################################################################
[[ -f "${FIXTURE}" ]] || { write_result "FAIL" "fixture missing: ${FIXTURE}"; exit 1; }

server_running=0
if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    server_running=1
    log "server already running (pid=$(cat "${PID_FILE}")) — reusing"
else
    log "no running server — running run-tests.sh first"
    if ! "${HARNESS_DIR}/run-tests.sh"; then
        write_result "FAIL" "run-tests.sh failed — see ${HARNESS_DIR}/result.txt"
        exit 1
    fi
    log "re-booting server for save phase"
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
    SERVER_JAR="$(ls -1 "${SERVER_DIR}"/folia*.jar "${SERVER_DIR}"/luminol*.jar "${SERVER_DIR}"/paper*.jar 2>/dev/null | head -n1 || true)"
    [[ -n "${SERVER_JAR}" ]] || { write_result "FAIL" "No server jar in ${SERVER_DIR}"; exit 1; }
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
        write_result "FAIL" "server re-boot did not reach Done within ${BOOT_TIMEOUT_SECS}s"
        exit 1
    fi
    sleep 5
fi

cleanup_save() {
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
trap cleanup_save EXIT INT TERM

###############################################################################
# 2. Paste the cube, save the same box, verify block count.
###############################################################################
mkdir -p "${PLUGIN_SCHEM_DIR}"
cp "${FIXTURE}" "${PLUGIN_SCHEM_DIR}/"
rm -f "${PLUGIN_SCHEM_DIR}/smoke_roundtrip.litematic"

fixture_name="$(basename "${FIXTURE}" .litematic)"
diag=""

# expect_save <schem-name> <x1 y1 z1 x2 y2 z2> — runs /litematica save and
# asserts the completion line reports EXPECT_BLOCKS blocks.
expect_save() {
    local sname="$1"; shift
    local box="$*"
    local cmd="litematica save ${sname} ${box}"
    log "rcon: ${cmd}"
    local out; out="$(send_rcon "${cmd}" 2>&1 || true)"; diag+="${cmd} -> ${out}
"
    sleep "${SETTLE_SECS}"
    local save_line
    save_line="$(grep -E "save complete: ${sname}\.litematic" "${LOG_FILE}" | tail -n1 || true)"
    if [[ -z "${save_line}" ]]; then
        write_result "FAIL" "$(printf 'no "save complete: %s" line in latest.log\n\n%s\nLast 40 log lines:\n%s\n' \
            "${sname}" "${diag}" "$(tail -n40 "${LOG_FILE}" 2>/dev/null)")"
        exit 1
    fi
    local blocks; blocks="$(sed -E 's/.*\(([0-9]+) blocks.*/\1/' <<<"${save_line}")"
    diag+="save line: ${save_line}
"
    if [[ "${blocks}" != "${EXPECT_BLOCKS}" ]]; then
        write_result "FAIL" "$(printf '%s captured %s blocks, expected %s (paste/save coordinate contract broken? region offset regression?)\n\n%s' \
            "${sname}" "${blocks}" "${EXPECT_BLOCKS}" "${diag}")"
        exit 1
    fi
    log "${sname}: captured ${blocks}/${EXPECT_BLOCKS} blocks"
}

cmd="litematica paste ${fixture_name} ${PASTE_X} ${PASTE_Y} ${PASTE_Z}"
log "rcon: ${cmd}"
out="$(send_rcon "${cmd}" 2>&1 || true)"; diag+="${cmd} -> ${out}
"
sleep "${SETTLE_SECS}"

# The cube lands at paste origin + region Position=(0,FIXTURE_DY,0).
cy=$((PASTE_Y + FIXTURE_DY))
expect_save smoke_roundtrip "${PASTE_X} ${cy} ${PASTE_Z} $((PASTE_X + 3)) $((cy + 3)) $((PASTE_Z + 3))"

[[ -f "${PLUGIN_SCHEM_DIR}/smoke_roundtrip.litematic" ]] || {
    write_result "FAIL" "smoke_roundtrip.litematic not written to ${PLUGIN_SCHEM_DIR}
${diag}"
    exit 1
}

###############################################################################
# 3. Re-paste the saved schematic elsewhere; its origin is normalized to
#    (0,0,0) on save, so it must land exactly at the re-paste coordinates —
#    save that box too and expect the same 64 blocks.
###############################################################################
paste_before="$(grep -cE 'paste complete:' "${LOG_FILE}" 2>/dev/null || echo 0)"
cmd="litematica paste smoke_roundtrip ${REPASTE_X} ${REPASTE_Y} ${REPASTE_Z}"
log "rcon: ${cmd}"
out="$(send_rcon "${cmd}" 2>&1 || true)"; diag+="${cmd} -> ${out}
"
sleep "${SETTLE_SECS}"
paste_after="$(grep -cE 'paste complete:' "${LOG_FILE}" 2>/dev/null || echo 0)"
if (( paste_after <= paste_before )); then
    write_result "FAIL" "$(printf 're-paste of saved schematic produced no "paste complete" line (%s -> %s)\n\n%s\nLast 40 log lines:\n%s\n' \
        "${paste_before}" "${paste_after}" "${diag}" "$(tail -n40 "${LOG_FILE}" 2>/dev/null)")"
    exit 1
fi
expect_save smoke_roundtrip2 "${REPASTE_X} ${REPASTE_Y} ${REPASTE_Z} $((REPASTE_X + 3)) $((REPASTE_Y + 3)) $((REPASTE_Z + 3))"

###############################################################################
# 4. Final log scan.
###############################################################################
if fail_hit="$(scan_all_logs)"; then
    write_result "FAIL" "$(printf 'Fail pattern after round-trip:\n%s\n\n%s' "${fail_hit}" "${diag}")"
    exit 1
fi

write_result "PASS" "$(printf 'paste -> save -> re-paste -> save round-trip clean, %s blocks captured at both sites.\n\n%s' \
    "${EXPECT_BLOCKS}" "${diag}")"
log "PASS"
