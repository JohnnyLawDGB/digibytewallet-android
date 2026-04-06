#!/usr/bin/env bash
# scripts/pre-publish-test.sh
#
# Pre-publish test suite for DigiByte Android Wallet.
# Runs 8 test scenarios across multiple Android API levels on emulators.
#
# Usage:
#   ./scripts/pre-publish-test.sh              # all API levels
#   ./scripts/pre-publish-test.sh 33           # only API 33
#   ./scripts/pre-publish-test.sh --skip-build # use existing APK
#   ./scripts/pre-publish-test.sh --test D     # run only test D

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk"
PREVIOUS_APK="$PROJECT_DIR/test-fixtures/previous-release.apk"
RESULTS_DIR="$PROJECT_DIR/test-results"
TIMESTAMP="$(date +%Y-%m-%d-%H%M%S)"
RESULT_FILE="$RESULTS_DIR/$TIMESTAMP.txt"
PACKAGE="io.digibyte"
ACTIVITY="$PACKAGE/.MainActivity"

# API levels to test
ALL_API_LEVELS=(26 28 33 34 36)

# Parse arguments
SKIP_BUILD=false
ONLY_API=""
ONLY_TEST=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true; shift ;;
        --test) ONLY_TEST="$2"; shift 2 ;;
        [0-9]*) ONLY_API="$1"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

# Source helpers
source "$SCRIPT_DIR/test-helpers/emulator-setup.sh"
source "$SCRIPT_DIR/test-helpers/test-scenarios.sh"
source "$SCRIPT_DIR/test-helpers/report.sh"

# ── Build ─────────────────────────────────────────────────────────────────────

if [[ "$SKIP_BUILD" == false ]]; then
    echo "=== Building APK ==="
    cd "$PROJECT_DIR"
    ./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug --quiet
    echo "APK: $APK_PATH"
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "ERROR: APK not found at $APK_PATH"
    echo "Run without --skip-build or build manually first."
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# ── Header ────────────────────────────────────────────────────────────────────

echo "=== DigiByte Wallet Pre-Publish Test ===" | tee "$RESULT_FILE"
echo "APK: $APK_PATH" | tee -a "$RESULT_FILE"
echo "Date: $(date)" | tee -a "$RESULT_FILE"
echo "" | tee -a "$RESULT_FILE"

# ── Determine API levels ──────────────────────────────────────────────────────

if [[ -n "$ONLY_API" ]]; then
    API_LEVELS=("$ONLY_API")
else
    API_LEVELS=("${ALL_API_LEVELS[@]}")
fi

# ── Run tests per API level ───────────────────────────────────────────────────

declare -A SUMMARY

for api in "${API_LEVELS[@]}"; do
    echo "--- API $api ---" | tee -a "$RESULT_FILE"

    ensure_system_image "$api"
    ensure_avd "$api"
    boot_emulator "$api"

    SERIAL="emulator-5554"

    # Wait for boot
    if ! wait_for_boot "$SERIAL" 120; then
        echo "  EMULATOR BOOT FAILED — skipping API $api" | tee -a "$RESULT_FILE"
        kill_emulator
        SUMMARY[$api]="BOOT FAILED"
        continue
    fi

    PASS=0
    FAIL=0
    SKIP=0
    TOTAL=0
    API_RESULT=""

    for test_id in A B C D E F G H; do
        if [[ -n "$ONLY_TEST" && "$ONLY_TEST" != "$test_id" ]]; then
            continue
        fi
        TOTAL=$((TOTAL + 1))

        START_TIME=$(date +%s)
        result=$(run_test "$test_id" "$SERIAL" "$APK_PATH" "$PREVIOUS_APK" "$PACKAGE" "$ACTIVITY")
        END_TIME=$(date +%s)
        ELAPSED=$((END_TIME - START_TIME))

        test_name=$(test_name "$test_id")

        case "$result" in
            PASS)
                PASS=$((PASS + 1))
                status="PASS"
                ;;
            SKIP*)
                SKIP=$((SKIP + 1))
                status="SKIP"
                ;;
            *)
                FAIL=$((FAIL + 1))
                status="FAIL"
                ;;
        esac

        line="  [$test_id] $test_name $(printf '%*s' $((40 - ${#test_name})) '')$status  (${ELAPSED}s)"
        echo "$line" | tee -a "$RESULT_FILE"
        API_RESULT+="${test_id}:${status} "

        # Clean up between tests
        adb -s "$SERIAL" shell am force-stop "$PACKAGE" 2>/dev/null || true
        adb -s "$SERIAL" shell pm uninstall "$PACKAGE" 2>/dev/null || true
    done

    echo "  Result: $PASS/$TOTAL PASS, $FAIL FAIL, $SKIP SKIP" | tee -a "$RESULT_FILE"
    echo "" | tee -a "$RESULT_FILE"

    if [[ $FAIL -gt 0 ]]; then
        SUMMARY[$api]="FAIL ($FAIL failures)"
    else
        SUMMARY[$api]="PASS ($PASS/$TOTAL)"
    fi

    kill_emulator
done

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== SUMMARY ===" | tee -a "$RESULT_FILE"
OVERALL="PASS"
for api in "${API_LEVELS[@]}"; do
    status="${SUMMARY[$api]:-NOT RUN}"
    echo "API $api: $status" | tee -a "$RESULT_FILE"
    if [[ "$status" == FAIL* ]]; then
        OVERALL="FAIL"
    fi
done
echo "Overall: $OVERALL" | tee -a "$RESULT_FILE"
echo ""
echo "Results saved to: $RESULT_FILE"

if [[ "$OVERALL" == "FAIL" ]]; then
    exit 1
fi
