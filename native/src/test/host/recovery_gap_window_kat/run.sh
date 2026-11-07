#!/usr/bin/env bash
# Host KAT runner for BRRecoveryGapWindow.h -- how far the plain-DGB recovery scan derives on each
# path, and when a path's answer is finished or capped (4.1.3 G1). From Android's RecoveryGapWindow.kt
# and the per-path decision in RecoveryScanService.classifyWidening; Android now calls the core
# (jni_recovery_gap.c) and iOS calls the same functions.
#
# Header-only: includes BRRecoveryGapWindow.h (libc only).
#
#   RED    two comparison arms, each failing only its own cases:
#          -DRECOVERY_GAP_RED  a grown window grows only to a gap past the last use, not by a whole
#                              gap, so a lookup that answers "used" near the far end walks a few
#                              addresses per round and the round bound stops it below the cap;
#          -DRECOVERY_CAP_RED  a path the cap or the round bound stopped is reported finished (the
#                              fixed window's old answer: "that is everything").
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

GAP_RED_CASES="grown_window_grows_by_a_whole_gap path_in_use_at_the_cap_is_capped
               use_near_the_far_end_reaches_the_cap_in_whole_gaps"
CAP_RED_CASES="round_bound_stop_is_capped cap_stop_is_capped path_in_use_at_the_cap_is_capped
               always_used_lookup_is_bounded_and_capped use_near_the_far_end_reaches_the_cap_in_whole_gaps"
GUARD_CASES="gaps_and_caps_are_kotlins nothing_used_keeps_the_window use_a_gap_from_the_end_keeps_the_window
             use_past_the_window_grows_a_gap_past_it growth_stops_at_the_cap use_within_a_gap_of_the_cap_is_capped
             never_below_count a_negative_index_is_nothing_used arithmetic_wraps_as_kotlin_int
             unknown_chain_never_grows_and_is_capped round_bound growing_path_asks_again_and_settled_path_is_finished
             coin_past_the_fixed_window_is_found empty_path_is_asked_once paid_but_emptied_address_widens_the_window"
ALL_CASES="$(echo $GAP_RED_CASES $CAP_RED_CASES $GUARD_CASES | tr ' ' '\n' | awk '!seen[$0]++' | tr '\n' ' ')"

build() {
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Wpedantic -Werror -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer "$@" -I "$CORE_DIR" "$SCRIPT_DIR/recovery_gap_window_kat_main.c" -o "$out"
}

for seam in RECOVERY_GAP_RED RECOVERY_CAP_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRRecoveryGapWindow.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRRecoveryGapWindow.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/red_gap" -DRECOVERY_GAP_RED || { echo "GATE FAILURE: RECOVERY_GAP_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_cap" -DRECOVERY_CAP_RED || { echo "GATE FAILURE: RECOVERY_CAP_RED arm did not compile."; exit 1; }
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

comparison_arm "$BUILD_DIR/red_gap" RECOVERY_GAP_RED "$GAP_RED_CASES"
comparison_arm "$BUILD_DIR/red_cap" RECOVERY_CAP_RED "$CAP_RED_CASES"

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

[ $FAIL -eq 0 ] && echo "recovery_gap_window_kat: GATE HELD (RED grew by less than a gap and reported capped paths finished; GREEN holds every rule)"
exit $FAIL
