#!/usr/bin/env bash
# Host KAT runner for BRAmountParse.h: DGB text <-> satoshis, exactly (Android's DgbAmount
# rules; DgbAmountTest.kt's cases and values).
#
#   RED    -DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED: the old double conversion. The RED cases (plain
#          amounts, the dust-code round trip) MUST fail; every GUARD case pass.
#   GREEN  the exact parser: every case passes, ALL PASS, exit 0, under ASan + UBSan.
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

# Every case that converts 0.29, a dust code or the exact ceiling is RED: the double is wrong on
# all of them. The rest do not depend on the conversion and must hold in both arms.
RED_CASES=(plain_amounts_exact every_dust_code_round_trips grouping_and_whitespace
           lone_comma_is_the_decimal_point overflow_refused_ceiling_accepted)
GUARD_CASES=(absurd_magnitude_refused_without_materialising more_than_eight_decimals_refused negative_empty_nonnumeric_refused_zero_allowed
             format_plain_decimal bigdecimal_grammar_edges long_inputs_have_no_length_limit ascii_only)

# GREEN runs under ASan + UBSan. RED runs under ASan only: the old double conversion's
# out-of-range cast is exactly the undefined behaviour it is being shown to have, and the gate
# asserts its wrong VALUES, not its UB report.
build() {
    local out="$1" san="$2"; shift 2
    "$CC" -std=c99 -Wall -Wextra -Werror -g -fsanitize="$san" -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" "$SCRIPT_DIR/amount_parse_kat_main.c" -o "$out"
}

grep -q "AMOUNT_PARSE_VIA_DOUBLE_UNFIXED" "$CORE_DIR/BRAmountParse.h" 2>/dev/null ||
    { echo "GATE FAILURE: $CORE_DIR/BRAmountParse.h missing or has no RED seam."; exit 1; }

FAIL=0
build "$BUILD_DIR/red" address -DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" address,undefined || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c fail$" && echo "  [red $c] failed, as it must: $(echo "$out" | grep "^FAIL: $c" | head -1)" \
        || { echo "  [red $c] GATE FAILURE: did not fail"; FAIL=1; }
done
for c in "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [red $c] GUARD held" \
        || { echo "  [red $c] GATE FAILURE: GUARD did not hold"; echo "$out" | grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; }
done

echo "--- SHIPPED ARM ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; echo "$out" | grep -E "$SAN_RE" | head -3; FAIL=1; }
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; echo "$out" | grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE"; FAIL=1; }
done
[ $FAIL -eq 0 ] && echo "amount_parse_kat: GATE HELD (RED was a satoshi low; GREEN exact)"
exit $FAIL
