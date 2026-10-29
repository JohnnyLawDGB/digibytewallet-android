#!/usr/bin/env bash
# Host KAT runner for BRStrandedSend.h -- the stranded-send sweep's decision (republish,
# hold or settle), carrying Android's StrandedSendSelectorTest.kt case for case.
#
# Header-only: includes BRStrandedSend.h and links nothing but libc.
#
#   RED    -DSTRANDED_SEND_UNFIXED re-publishes anything not listed at a confirmed height,
#          without bound (the sweep before the rule). Every RED case MUST fail; every GUARD
#          case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan + UBSan.
#
# The cases use Kotlin's literal constants (StrandedSendSelector.kt: window 100, 3 attempts,
# 1 h hold), not the header's BR_STRANDED_* macros, so a changed macro fails here.
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

RED_CASES=(absent_from_full_window_aged_out absent_from_full_window_aged_out_after_attempts absent_tried_then_given_up unconfirmed_held_then_retried
           unreadable_height_tried_then_held all_unconfirmed_window_not_full
           one_confirmed_row_anchors_full hold_edges)
GUARD_CASES=(conflicted_only_when_held_and_invalid heights_classify confirmed_listed_settles sentinel_listed_republishes window_bound)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/stranded_send_kat_main.c" -o "$out"
}

if ! grep -q "STRANDED_SEND_UNFIXED" "$CORE_DIR/BRStrandedSend.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRStrandedSend.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSTRANDED_SEND_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSTRANDED_SEND_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [red $c] failed, as it must"
    else echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
done
for c in "${GUARD_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [red $c] GUARD held"
    else echo "  [red $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "stranded_send_kat: GATE HELD (RED re-published without bound; GREEN settled, held and gave up)"
exit $FAIL
