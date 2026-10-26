#!/usr/bin/env bash
# Host KAT runner for BRSavedBlocks.h's write-side guard -- a saved window below the persisted
# tip never replaces it (from BlockPersistencePolicy.kt).
#
# Header-only use: includes BRSavedBlocks.h and links nothing but libc.
#
#   RED    -DSAVED_BLOCKS_GUARD_RED overwrites blindly (the 23 176 000 -> 23 060 000 regression).
#          Every RED case MUST fail; every GUARD case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan + UBSan.
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
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(a_lower_tip_is_rejected)
GUARD_CASES=(a_higher_tip_is_persisted an_equal_tip_is_persisted the_first_save_is_persisted an_unreadable_top_fails_open
             an_eleven_byte_buffer_is_unreadable the_top_height_is_read_from_the_buffer
             a_top_above_two_to_the_31_stays_positive)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/saved_blocks_guard_kat_main.c" -o "$out"
}

if ! grep -q "SAVED_BLOCKS_GUARD_RED" "$CORE_DIR/BRSavedBlocks.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRSavedBlocks.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSAVED_BLOCKS_GUARD_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSAVED_BLOCKS_GUARD_RED): every RED case MUST fail, every GUARD case pass ---"
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

[ $FAIL -eq 0 ] && echo "saved_blocks_guard_kat: GATE HELD (RED replaced a higher window with a lower one; GREEN keeps the higher)"
exit $FAIL
