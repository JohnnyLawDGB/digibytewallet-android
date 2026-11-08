#!/usr/bin/env bash
# Host KAT runner for the DigiDollar half of BRRecoveryGapWindow.h -- how far the DigiDollar recovery
# scan derives at m/86'/20'/0', and when its answer is finished or capped (4.1.2 row 19). From
# Android's DigiDollarGapWindow.kt, the gaps in RecoveryScanService and its scanDigiDollar stop
# decision; Android now calls the core (jni_recovery_gap.c) and iOS calls the same functions.
#
# Header-only: includes BRRecoveryGapWindow.h (libc only).
#
#   RED    three comparison arms, each failing only its own cases:
#          -DDIGIDOLLAR_GAP_RED  the fixed window: a window never grows, so DigiDollar change past
#                                the tenth send is lost and the scan still says finished (the 4.1.2
#                                row 19 bug);
#          -DDIGIDOLLAR_STEP_RED a grown window grows only to a gap past the last use, not by a whole
#                                gap (the rule before 2026-11), so a lookup that keeps answering
#                                "used one gap from the end" walks one address per round;
#          -DDIGIDOLLAR_CAP_RED  a scan the cap or the round bound stopped is reported finished
#                                ("that is everything").
#          In each arm every one of its RED cases MUST fail and every other case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Every arm under ASan + UBSan.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

GAP_RED_CASES="use_near_the_end_widens_the_window_by_at_least_a_gap the_window_grows_to_the_cap_and_not_past_it
               twelve_sends_are_found_where_the_fixed_window_stopped_at_ten external_chain_widens_at_its_own_gap
               index_zero_is_used_and_grows_the_window_by_a_whole_gap
               change_past_ten_sends_is_found_and_finished always_used_lookup_stops_at_the_cap_and_is_capped
               a_lookup_one_gap_from_the_end_reaches_the_cap_a_gap_per_round"
STEP_RED_CASES="use_near_the_end_widens_the_window_by_at_least_a_gap external_chain_widens_at_its_own_gap
                index_zero_is_used_and_grows_the_window_by_a_whole_gap change_past_ten_sends_is_found_and_finished
                a_lookup_one_gap_from_the_end_reaches_the_cap_a_gap_per_round"
CAP_RED_CASES="cap_stop_is_capped round_bound_stop_is_capped always_used_lookup_stops_at_the_cap_and_is_capped
               a_lookup_one_gap_from_the_end_reaches_the_cap_a_gap_per_round"
GUARD_CASES="gaps_cap_and_starts_are_kotlins an_unused_chain_keeps_its_window a_window_a_gap_past_the_last_use_stops_growing
             capped_is_kotlins each_chain_caps_within_its_own_gap a_negative_index_is_nothing_used
             arithmetic_wraps_as_kotlin_int a_window_past_the_cap_is_kept unknown_chain_never_grows_and_is_capped
             round_bound_is_the_internal_chains growing_scan_asks_again_and_settled_scan_is_finished
             empty_wallet_is_asked_once"
ALL_CASES="$(echo $GAP_RED_CASES $STEP_RED_CASES $CAP_RED_CASES $GUARD_CASES | tr ' ' '\n' | awk '!seen[$0]++' | tr '\n' ' ')"

build() {
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Wpedantic -Werror -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer "$@" -I "$CORE_DIR" "$SCRIPT_DIR/digidollar_gap_window_kat_main.c" -o "$out"
}

for seam in DIGIDOLLAR_GAP_RED DIGIDOLLAR_STEP_RED DIGIDOLLAR_CAP_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRRecoveryGapWindow.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRRecoveryGapWindow.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/red_gap" -DDIGIDOLLAR_GAP_RED || { echo "GATE FAILURE: DIGIDOLLAR_GAP_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_step" -DDIGIDOLLAR_STEP_RED || { echo "GATE FAILURE: DIGIDOLLAR_STEP_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_cap" -DDIGIDOLLAR_CAP_RED || { echo "GATE FAILURE: DIGIDOLLAR_CAP_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

# comparison_arm <binary> <label> <cases that must fail>: every other case must pass.
comparison_arm() {
    local bin="$1" label="$2" red="$3" out rc c
    echo "--- COMPARISON ARM (-D$label): its RED cases MUST fail, every other case pass ---"
    out="$("$bin" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$label] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [$label] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in $ALL_CASES; do
        case " $(echo $red) " in
        *" $c "*)
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$label $c] failed, as it must"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'
            else echo "  [$label $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi ;;
        *)
            if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [$label $c] GUARD held"
            else echo "  [$label $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi ;;
        esac
    done
}

comparison_arm "$BUILD_DIR/red_gap" DIGIDOLLAR_GAP_RED "$GAP_RED_CASES"
comparison_arm "$BUILD_DIR/red_step" DIGIDOLLAR_STEP_RED "$STEP_RED_CASES"
comparison_arm "$BUILD_DIR/red_cap" DIGIDOLLAR_CAP_RED "$CAP_RED_CASES"

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in $ALL_CASES; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done
# A case the binary prints but this runner does not list would go unchecked.
for c in $(<<<"$out" sed -n 's/^RESULT \([^ ]*\) .*$/\1/p'); do
    case " $ALL_CASES " in *" $c "*) ;; *) echo "  [green $c] GATE FAILURE: case not listed in run.sh"; FAIL=1 ;; esac
done

[ $FAIL -eq 0 ] && echo "digidollar_gap_window_kat: GATE HELD (RED kept the fixed window, grew by less than a gap and reported capped scans finished; GREEN holds every rule)"
exit $FAIL
