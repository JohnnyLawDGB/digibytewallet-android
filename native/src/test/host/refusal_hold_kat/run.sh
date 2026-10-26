#!/usr/bin/env bash
# Host KAT runner for the refusal hold in BRStrandedSend.h -- when the stranded-send sweep removes a
# send the network refused, which unlocks its coins -- carrying Android's PublishOutcome.actionFor,
# RefusalHold and InvalidSendRemoval tests case for case (see refusal_hold_kat_main.c).
#
# [1/2] HEADER. Two translation units that each include BRStrandedSend.h twice are compiled under
#       -Wall -Wextra -Wpedantic -Werror as C99, C11 and C17, then linked together, so the static
#       inline helpers link from more than one unit. (Not C++: BRStrandedSend.h includes
#       BRTransaction.h, and so BRInt.h, which is not valid C++.)
# [2/2] ARMS. refusal_hold_kat_main.c, header-only (libc only), under ASan + UBSan:
#         red_no_plain_gate  -DSTRANDED_REFUSAL_RED_NO_PLAIN_GATE  exactly RED_NO_PLAIN_GATE_FAILS fail
#         red_one_peer       -DSTRANDED_REFUSAL_RED_ONE_PEER       exactly RED_ONE_PEER_FAILS fail
#         red_no_hold        -DSTRANDED_REFUSAL_RED_NO_HOLD        exactly RED_NO_HOLD_FAILS fail
#         red_any_manager    -DSTRANDED_REFUSAL_RED_ANY_MANAGER    exactly RED_ANY_MANAGER_FAILS fail
#         green              the shipped rule: every case passes, ALL PASS, exit 0
#       In a comparison arm every case not named must pass, so each named case is shown to see the
#       regression it is there for, and the others are shown not to depend on it.
#
# Pure integer policy with no pointer-size dependence, so 64-bit only (as stranded_send_kat).
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail   # not -e: exit codes of the arms are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRStrandedSend.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
INC=(-I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1")

ALL_CASES=(po_corroborated_refusal_removes_plain po_single_peer_rejection_removes_nothing
           po_asset_or_dd_send_left_alone po_nothing_but_terminal_removes
           rh_seen_once_not_acted rh_sweep_keeps_just_seen rh_acted_after_ten_minutes rh_sweep_cadence
           rh_false_answer_forgets rh_each_send_own_sighting rh_clock_step_back
           rh_new_session_remembers_nothing rh_forgotten_starts_again rh_hold_is_ten_minutes
           rh_rebuilt_manager_not_acted rh_sweep_keeps_straddling_rebuild
           rh_answer_across_rebuild_no_sighting rh_unknown_manager_never_completes
           inv_first_reading_removes_nothing inv_held_may_remove inv_valid_again_starts_over
           inv_never_mixes_with_refusal
           plain_dgb_only_held_no_asset_no_dd no_verdict_keeps hold_nonpositive_or_null_never_acts
           clock_difference_wraps_as_kotlin_long)
# No plain-DGB gate: a refused asset or DigiDollar send is removed.
RED_NO_PLAIN_GATE_FAILS=(po_asset_or_dd_send_left_alone)
# One peer's terminal rejection removes the send: the hold's answer is not consulted.
RED_ONE_PEER_FAILS=(po_single_peer_rejection_removes_nothing rh_sweep_keeps_just_seen
                    rh_sweep_keeps_straddling_rebuild)
# No hold: every first sighting is acted on.
RED_NO_HOLD_FAILS=(rh_seen_once_not_acted rh_sweep_keeps_just_seen rh_acted_after_ten_minutes
                   rh_sweep_cadence rh_false_answer_forgets rh_each_send_own_sighting rh_clock_step_back
                   rh_new_session_remembers_nothing rh_forgotten_starts_again rh_rebuilt_manager_not_acted
                   rh_sweep_keeps_straddling_rebuild rh_answer_across_rebuild_no_sighting
                   rh_unknown_manager_never_completes inv_first_reading_removes_nothing inv_held_may_remove
                   inv_valid_again_starts_over inv_never_mixes_with_refusal clock_difference_wraps_as_kotlin_long)
# The manager is not read: a hold spans a rebuild, and an unknown manager completes one.
RED_ANY_MANAGER_FAILS=(rh_rebuilt_manager_not_acted rh_sweep_keeps_straddling_rebuild
                       rh_answer_across_rebuild_no_sighting rh_unknown_manager_never_completes)

for seam in STRANDED_REFUSAL_RED_NO_PLAIN_GATE STRANDED_REFUSAL_RED_ONE_PEER STRANDED_REFUSAL_RED_NO_HOLD \
            STRANDED_REFUSAL_RED_ANY_MANAGER; do
    if ! grep -q "$seam" "$HEADER" 2>/dev/null; then
        echo "GATE FAILURE: $HEADER missing or has no $seam seam; -D$seam would be inert."
        exit 1
    fi
done

in_list() {   # <word> <list...>
    local w="$1"; shift
    local x; for x in "$@"; do [ "$x" = "$w" ] && return 0; done
    return 1
}

FAIL=0
echo "[1/2] the header: C99, C11, C17 under -Wall -Wextra -Wpedantic -Werror, two units linked"
for tu in a b; do
    {
        echo '#include "BRStrandedSend.h"'
        echo '#include "BRStrandedSend.h"'
        echo "int refusal_tu_$tu(BRStrandedRefusalHold *h, int64_t now);"
        echo "int refusal_tu_$tu(BRStrandedRefusalHold *h, int64_t now)"
        echo "{ return (int)BRStrandedRefusalActionFor(22, BRStrandedSendIsPlainDgb(1, 0, 0),"
        echo "      BRStrandedRefusalHoldObserve(h, 1, 1, 5, 1, 5, now, BR_STRANDED_REFUSAL_HOLD_MS)); }"
    } > "$BUILD_DIR/tu_$tu.c"
done
cat >> "$BUILD_DIR/tu_a.c" <<'EOF'
int refusal_tu_b(BRStrandedRefusalHold *h, int64_t now);
int main(void)
{
    BRStrandedRefusalHold h = { 0, 0, 0 };
    /* EINVAL is 22 on both Linux and Darwin. First sighting keeps; ten minutes later on the other unit removes. */
    return (refusal_tu_a(&h, 1000) == 0 && refusal_tu_b(&h, 1000 + 599999) == 0 &&
            refusal_tu_b(&h, 1000 + 600000) == 1) ? 0 : 1;
}
EOF
STRICT=(-Wall -Wextra -Wpedantic -Werror)
for std in c99 c11 c17; do
    for tu in a b; do
        if ! "$CC" -std=$std "${STRICT[@]}" -Wno-gnu-folding-constant "${INC[@]}" -c "$BUILD_DIR/tu_$tu.c" \
                -o "$BUILD_DIR/tu_${tu}_$std.o" >"$BUILD_DIR/tu_${tu}_$std.log" 2>&1; then
            echo "  GATE FAILURE: unit $tu does not compile cleanly as $std"
            grep -m 4 -E 'error|warning' "$BUILD_DIR/tu_${tu}_$std.log" | sed 's/^/      /'; FAIL=1
        fi
    done
done
if [ -f "$BUILD_DIR/tu_a_c99.o" ] && [ -f "$BUILD_DIR/tu_b_c99.o" ] && \
   "$CC" "$BUILD_DIR/tu_a_c99.o" "$BUILD_DIR/tu_b_c99.o" -o "$BUILD_DIR/two_units" >"$BUILD_DIR/two_units.log" 2>&1 && \
   "$BUILD_DIR/two_units"; then
    echo "  C99, C11, C17: clean; two units linked and agree"
else
    echo "  GATE FAILURE: two units including the header did not link, or disagree"
    grep -m 4 -E 'error|duplicate' "$BUILD_DIR/two_units.log" 2>/dev/null | sed 's/^/      /'; FAIL=1
fi

echo "[2/2] the arms"
ARMS=("red_no_plain_gate -DSTRANDED_REFUSAL_RED_NO_PLAIN_GATE" "red_one_peer -DSTRANDED_REFUSAL_RED_ONE_PEER"
      "red_no_hold -DSTRANDED_REFUSAL_RED_NO_HOLD" "red_any_manager -DSTRANDED_REFUSAL_RED_ANY_MANAGER" "green")
for arm in "${ARMS[@]}"; do
    set -- $arm; name="$1"; shift; tag="[$name]"
    if ! "$CC" -w -std=c99 "${SAN[@]}" "${INC[@]}" "$@" "$SCRIPT_DIR/refusal_hold_kat_main.c" \
            -o "$BUILD_DIR/$name" >"$BUILD_DIR/$name.build.log" 2>&1; then
        echo "  $tag GATE FAILURE: did not compile"
        grep -m 3 -E 'error' "$BUILD_DIR/$name.build.log" | sed 's/^/      /'; FAIL=1; continue
    fi
    out="$("$BUILD_DIR/$name" 2>&1)"; rc=$?
    if <<<"$out" grep -Eq "$SAN_RE"; then
        echo "  $tag GATE FAILURE: sanitizer report"; <<<"$out" grep -m 2 -E "$SAN_RE" | sed 's/^/      /'; FAIL=1
    fi
    case "$name" in
        red_no_plain_gate) want=("${RED_NO_PLAIN_GATE_FAILS[@]}") ;;
        red_one_peer)      want=("${RED_ONE_PEER_FAILS[@]}") ;;
        red_no_hold)       want=("${RED_NO_HOLD_FAILS[@]}") ;;
        red_any_manager)   want=("${RED_ANY_MANAGER_FAILS[@]}") ;;
        *)                 want=() ;;
    esac
    failed=(); bad=0
    for c in "${ALL_CASES[@]}"; do
        if <<<"$out" grep -q "^RESULT $c fail$"; then failed+=("$c")
            in_list "$c" ${want[@]+"${want[@]}"} || { echo "  $tag GATE FAILURE: $c failed"; <<<"$out" grep "^FAIL: $c:" | sed 's/^/      /'; bad=1; }
        elif <<<"$out" grep -q "^RESULT $c pass$"; then
            in_list "$c" ${want[@]+"${want[@]}"} && { echo "  $tag GATE FAILURE: $c passed; it cannot see this regression"; bad=1; }
        else
            echo "  $tag GATE FAILURE: $c reported no result"; bad=1
        fi
    done
    if [ "$name" = "green" ]; then
        if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then echo "  $tag GATE FAILURE: rc=$rc, not ALL PASS"; bad=1; fi
        [ $bad -eq 0 ] && echo "  $tag all ${#ALL_CASES[@]} cases passed"
    else
        [ $rc -eq 0 ] && { echo "  $tag GATE FAILURE: exited 0; it proves nothing"; bad=1; }
        [ $bad -eq 0 ] && echo "  $tag failed exactly its ${#failed[@]} cases (${failed[*]}); the other $(( ${#ALL_CASES[@]} - ${#failed[@]} )) passed"
    fi
    [ $bad -ne 0 ] && FAIL=1
done

[ $FAIL -eq 0 ] && echo "refusal_hold_kat: GATE HELD (each comparison arm failed exactly its cases; GREEN matches PublishOutcome.actionFor, RefusalHold and InvalidSendRemoval)"
exit $FAIL
