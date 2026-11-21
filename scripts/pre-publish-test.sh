#!/usr/bin/env bash
# scripts/pre-publish-test.sh
#
# Pre-publish test suite: eight scenarios, each driving the real UI of the DEBUG build on an
# EMULATOR, on API 28 / 33 / 34 / 35.
#
# Usage:
#   ./scripts/pre-publish-test.sh              # all API levels
#   ./scripts/pre-publish-test.sh 33           # only API 33
#   ./scripts/pre-publish-test.sh --skip-build # use the existing APK
#   ./scripts/pre-publish-test.sh --test D     # run only scenario D
#   ./scripts/pre-publish-test.sh --self-test  # the suite's own red arm; no emulator needed
#
# What each scenario asserts (scripts/test-helpers/test-scenarios.sh has the detail):
#   A Fresh install, no lock screen    first onboarding screen; a wallet can be set up; the
#                                      "no screen lock" notice shows once and does not block;
#                                      Security says the wallet key is not tied (no lock).
#   B Fresh install, with lock screen  with an emulator PIN set, a wallet is created, the
#                                      device-credential prompt (at most one) is answered, and
#                                      Security says the wallet key is tied to the screen lock.
#   C Upgrade from previous version    a wallet created on the previous release keeps its PIN
#                                      and its receive address after `adb install -r`; no onboarding.
#   D Wallet creation flow             12 words read from the screen, the 3 verification
#                                      questions answered, PIN set, a receive address shown.
#   E Wallet recovery flow             the public BIP39 test phrase restores to the BIP84
#                                      address pinned in wallet_open_kat.
#   F Sync without ANR                 at least one peer, and the block height shown advances.
#   G Send transaction                 NO COINS ARE SENT: an empty, synced wallet is refused
#                                      with the insufficient-funds message.
#   H 60-second stability soak         same process, still the foreground window, after 60 s.
# Every scenario also fails on a crash or an ANR of the app.
#
# What it does NOT check: the release-signed build, a physical device, a real spend, a real
# balance, biometrics, an upgrade from any version but the fixture's. Those are separate
# (RELEASING.md).
#
# Result: test-results/<timestamp>.txt (one line per scenario: PASS, FAIL or SKIP, with the
# reason), and for each FAIL the screen and the app's last 300 logcat lines, redacted, under
# test-results/<timestamp>/. Exit status 1 if anything failed or an emulator did not boot.
#
# Devices: emulators only. The serial is emulator-$PREPUB_EMULATOR_PORT (default 5554), on a
# port this script starts the emulator on itself; it refuses a port that is already in use and
# any serial that is not an emulator. Every adb call is bounded by `timeout`.
#
# The upgrade fixture (scenario C) is NOT in git (test-fixtures/*.apk is ignored). Build it
# from the previous release's tag, with the same debug key the current build uses:
#   git worktree add /tmp/prev-release v<previous version>     # e.g. v4.1.4
#   cd /tmp/prev-release && git submodule update --init --recursive
#   ./gradlew :native:assembleMainnetDebug :app:assembleMainnetSideloadDebug
#   cp app/build/outputs/apk/mainnetSideload/debug/app-mainnet-sideload-debug.apk \
#      <this repo>/test-fixtures/previous-release.apk
# Without it, or with an APK of another package, C reports SKIP with the reason.
#
# The red arm: PREPUB_SABOTAGE=<A..H> breaks that scenario's SUBJECT (a crash at launch, no
# screen lock, the old install removed before the upgrade, wrong verification answers, a
# different phrase, the app's network cut, an invalid address, the app sent to the background)
# and the scenario must then report FAIL. Run it with --test <letter> on one API level.
#
# Tunables: PREPUB_EMULATOR_PORT, PREPUB_BOOT_TIMEOUT (300 s), PREPUB_SYNC_TIMEOUT (1500 s, the
# wait in G for a new wallet to finish syncing), ADB_TIMEOUT (30 s), ADB_INSTALL_TIMEOUT (180 s).

# No `set -e`: a failed step is a FAIL line, not an abort. No pipefail: see keyboard_shown.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/mainnetSideload/debug/app-mainnet-sideload-debug.apk"
PREVIOUS_APK="$PROJECT_DIR/test-fixtures/previous-release.apk"
RESULTS_DIR="$PROJECT_DIR/test-results"
TIMESTAMP="$(date +%Y-%m-%d-%H%M%S)"
# Two runs started in the same second (one per API level, on different ports) must not share a file.
[[ -e "$RESULTS_DIR/$TIMESTAMP.txt" || -e "$RESULTS_DIR/$TIMESTAMP" ]] && TIMESTAMP="$TIMESTAMP-$$"
RESULT_FILE="$RESULTS_DIR/$TIMESTAMP.txt"
RUN_DIR="$RESULTS_DIR/$TIMESTAMP"
# The debug build carries the .debug suffix, but its classes stay in the namespace, so the
# activity must be named in full ("$PACKAGE/.MainActivity" would look in ...debug.MainActivity).
PACKAGE="app.aroundtheblock.wallet.debug"
ACTIVITY="$PACKAGE/app.aroundtheblock.wallet.MainActivity"

ALL_API_LEVELS=(28 33 34 35)
# The order the scenarios RUN in. E and F share the restored wallet; D, H and G share the
# created one, so G's wait for a finished sync overlaps the soak. Results are listed A to H.
RUN_ORDER=(A B C E F D H G)

SKIP_BUILD=false
SELF_TEST=false
ONLY_API=""
ONLY_TEST=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true; shift ;;
        --self-test) SELF_TEST=true; shift ;;
        --test) ONLY_TEST="${2:-}"; shift 2 ;;
        [0-9]*) ONLY_API="$1"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

if [[ -n "$ONLY_TEST" && ! "$ONLY_TEST" =~ ^[A-H]$ ]]; then
    echo "Unknown test: $ONLY_TEST (A to H)"; exit 1
fi

source "$SCRIPT_DIR/test-helpers/device.sh"
source "$SCRIPT_DIR/test-helpers/emulator-setup.sh"
source "$SCRIPT_DIR/test-helpers/report.sh"
source "$SCRIPT_DIR/test-helpers/test-scenarios.sh"

if [[ "$SELF_TEST" == true ]]; then
    exec bash "$SCRIPT_DIR/test-helpers/self-test.sh"
fi

SERIAL="emulator-$EMULATOR_PORT"
require_emulator_serial "$SERIAL" || exit 1

# ── Build ─────────────────────────────────────────────────────────────────────

if [[ "$SKIP_BUILD" == false ]]; then
    echo "=== Building APK ==="
    (cd "$PROJECT_DIR" && ./gradlew :native:assembleMainnetDebug :app:assembleMainnetSideloadDebug --quiet) || {
        echo "ERROR: the build failed"; exit 1; }
    echo "APK: $APK_PATH"
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "ERROR: APK not found at $APK_PATH"
    echo "Run without --skip-build or build manually first."
    exit 1
fi

mkdir -p "$RESULTS_DIR" "$RUN_DIR"
SUITE_LOG="$RUN_DIR/suite.log"
UI_DUMP_FILE="$(mktemp -t prepub-ui-XXXXXX.xml)"   # the raw dump stays out of test-results

cleanup() {
    # Never leave a screen lock this suite set, nor its emulator, nor a raw UI dump.
    device_lock_clear > /dev/null 2>&1 || true
    kill_emulator > /dev/null 2>&1 || true
    rm -f "$UI_DUMP_FILE" "$UI_DUMP_FILE.tmp"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ── Header ────────────────────────────────────────────────────────────────────

SUITE_START=$(date +%s)
{
    echo "=== DigiByte Wallet Pre-Publish Test ==="
    echo "APK: $APK_PATH"
    echo "Date: $(date)"
    echo "Commit: $(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "Debug build on emulators; no coins are sent; real-device checks are separate."
    [[ -n "${PREPUB_SABOTAGE:-}" ]] && echo "RED ARM: PREPUB_SABOTAGE=$PREPUB_SABOTAGE (that scenario is expected to FAIL)"
    echo ""
} | tee "$RESULT_FILE"

if [[ -n "$ONLY_API" ]]; then
    API_LEVELS=("$ONLY_API")
else
    API_LEVELS=("${ALL_API_LEVELS[@]}")
fi

# ── Run the scenarios per API level ───────────────────────────────────────────

declare -A SUMMARY

for api in "${API_LEVELS[@]}"; do
    API_LEVEL="$api"
    echo "--- API $api ---" | tee -a "$RESULT_FILE"

    if emulator_port_busy; then
        echo "  BOOT FAILED: $SERIAL is already in use; set PREPUB_EMULATOR_PORT to a free even port" | tee -a "$RESULT_FILE"
        SUMMARY[$api]="BOOT FAILED"
        echo "" | tee -a "$RESULT_FILE"
        continue
    fi

    ensure_system_image "$api"
    ensure_avd "$api"
    EMULATOR_LOG="$RUN_DIR/api$api-emulator.log"
    boot_emulator "$api"

    if ! wait_for_boot || ! verify_emulator; then
        echo "  EMULATOR BOOT FAILED: skipping API $api" | tee -a "$RESULT_FILE"
        kill_emulator
        SUMMARY[$api]="BOOT FAILED"
        echo "" | tee -a "$RESULT_FILE"
        continue
    fi
    prepare_emulator
    WALLET_STATE="none"

    PASS=0; FAIL=0; SKIP=0; TOTAL=0
    declare -A LINES=()

    for test_id in "${RUN_ORDER[@]}"; do
        if [[ -n "$ONLY_TEST" && "$ONLY_TEST" != "$test_id" ]]; then
            continue
        fi
        TOTAL=$((TOTAL + 1))
        name="$(test_name "$test_id")"
        log "===== API $api scenario $test_id: $name"
        echo "  ... running [$test_id] $name"

        START_TIME=$(date +%s)
        run_scenario "$test_id"
        ELAPSED=$(( $(date +%s) - START_TIME ))

        case "$RESULT" in
            PASS) PASS=$((PASS + 1)); detail="${NOTE:+($NOTE)}" ;;
            SKIP) SKIP=$((SKIP + 1)); detail="(${REASON:-no reason given})" ;;
            *)    RESULT="FAIL"; FAIL=$((FAIL + 1)); detail="(${REASON:-no reason given})" ;;
        esac
        LINES[$test_id]="$(result_line "$test_id" "$name" "$RESULT" "$ELAPSED" "$detail")"
        log "===== $test_id: $RESULT $detail"
        echo "  ... ${LINES[$test_id]#  }"
    done

    for test_id in A B C D E F G H; do
        [[ -n "${LINES[$test_id]:-}" ]] && echo "${LINES[$test_id]}" | tee -a "$RESULT_FILE"
    done
    echo "  Result: $PASS/$TOTAL PASS, $FAIL FAIL, $SKIP SKIP" | tee -a "$RESULT_FILE"
    echo "" | tee -a "$RESULT_FILE"

    if [[ $FAIL -gt 0 ]]; then
        SUMMARY[$api]="FAIL ($FAIL failures)"
    else
        SUMMARY[$api]="PASS ($PASS/$TOTAL)"
    fi

    uninstall_app
    kill_emulator
done

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== SUMMARY ===" | tee -a "$RESULT_FILE"
OVERALL="PASS"
for api in "${API_LEVELS[@]}"; do
    status="${SUMMARY[$api]:-NOT RUN}"
    echo "API $api: $status" | tee -a "$RESULT_FILE"
    if [[ "$status" != PASS* ]]; then
        OVERALL="FAIL"
    fi
done
echo "Overall: $OVERALL" | tee -a "$RESULT_FILE"
echo "Wall time: $(( ($(date +%s) - SUITE_START) / 60 )) min $(( ($(date +%s) - SUITE_START) % 60 )) s" | tee -a "$RESULT_FILE"
echo ""
echo "Results saved to: $RESULT_FILE"
[[ -n "$(ls -A "$RUN_DIR" 2>/dev/null | grep -v -E 'suite.log|emulator.log')" ]] && echo "Failure evidence:  $RUN_DIR/"

if [[ "$OVERALL" == "FAIL" ]]; then
    exit 1
fi
