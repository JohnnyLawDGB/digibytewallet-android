#!/usr/bin/env bash
# Host KAT runner for BRStaleSend.h -- the stale-send clear's decision (4.1.3, review R8): whether a
# valid, unconfirmed own send a day old is one the network never took, on the pinned node's
# evidence (from Android's StaleSendPredicate and DgbNodeClient.sendNeverRelayed; Android now
# calls the core for both, through jni_stale_send.c).
#
# Header-only: includes BRStaleSend.h and links nothing but libc. The node is a scripted stub.
#
#   RED    two comparison arms, each failing only its own cases:
#          -DSTALE_SEND_AGE_RED    no timestamp guard, and exactly one day is not yet old enough;
#          -DSTALE_SEND_RELAY_RED  any node is asked (not only the pinned one), and an input with
#                                  no answer reads as unspent (only an explicit "spent" stops it).
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

AGE_CASES="a_day_old_send_never_relayed_is_stale a_send_with_no_timestamp_is_never_stale"
RELAY_CASES="a_custom_node_is_never_asked only_an_explicit_unspent_answer_counts"
GUARD_CASES="a_send_the_network_took_is_never_stale a_young_send_is_never_stale age_wraps_as_kotlin_long
             min_age_is_one_day a_send_with_no_inputs_is_never_asked
             unknown_txid_and_every_input_unspent_is_never_relayed only_the_nodes_own_not_found_goes_on
             a_spent_input_stops_it more_answers_than_inputs_is_no_verdict codes_are_fixed"
ALL_CASES="$AGE_CASES $RELAY_CASES $GUARD_CASES"

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/stale_send_kat_main.c" -o "$out"
}

for seam in STALE_SEND_AGE_RED STALE_SEND_RELAY_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRStaleSend.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRStaleSend.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/red_age" -DSTALE_SEND_AGE_RED || { echo "GATE FAILURE: STALE_SEND_AGE_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_relay" -DSTALE_SEND_RELAY_RED || { echo "GATE FAILURE: STALE_SEND_RELAY_RED arm did not compile."; exit 1; }
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
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$label $c] failed, as it must"
            else echo "  [$label $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi ;;
        *)
            if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [$label $c] GUARD held"
            else echo "  [$label $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi ;;
        esac
    done
}

comparison_arm "$BUILD_DIR/red_age" STALE_SEND_AGE_RED "$AGE_CASES"
comparison_arm "$BUILD_DIR/red_relay" STALE_SEND_RELAY_RED "$RELAY_CASES"

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
    case " $(echo $ALL_CASES) " in *" $c "*) ;; *) echo "  [green $c] GATE FAILURE: case not listed in run.sh"; FAIL=1 ;; esac
done

[ $FAIL -eq 0 ] && echo "stale_send_kat: GATE HELD (RED cleared sends with no timestamp, kept day-old ones, asked a custom node and read no answer as unspent; GREEN holds every rule)"
exit $FAIL
