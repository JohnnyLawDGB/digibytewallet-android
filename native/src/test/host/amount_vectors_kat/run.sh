#!/usr/bin/env bash
# Host KAT for the shared BRAmountParse.h vectors (vectors.txt beside this file), which Android's
# DgbAmountVectorsTest holds DgbAmount to on the host JVM. iOS reads DGB text with the header
# itself (AmountText, PaymentRequest, WalletSend), so these are its answers too.
#
#   SEAM          BRAmountParse.h still carries the RED seam (AMOUNT_PARSE_VIA_DOUBLE_UNFIXED); without
#                 it the RED arm below would build the exact parser and prove nothing.
#   VECTORS GATE  vectors.txt is exactly what generate.c writes from the pinned header, under
#                 ASan + UBSan and again at -O2: the file the JVM test reads is current.
#   RED           generate.c built with -DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED (the old double conversion)
#                 MUST write different parse answers and the same format answers: the vectors can
#                 tell the exact parser from the double one, so a mirror that converts through a
#                 double cannot pass them.
#   COMMA         generate.c built with -DAMOUNT_PARSE_COMMA_THOUSANDS_UNFIXED (a lone comma always the
#                 point) MUST write different parse answers, and on every line that differs the
#                 header's answer is '-': the ambiguous-comma rule only refuses, it never changes a value.
#   CORPUS        the inputs the Android port was judged on are present: Unicode spaces (where the old
#                 Kotlin reader said yes), Arabic-Indic digits, nine decimals, the Long ceiling, the
#                 int exponent bound.
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
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
GEN="$SCRIPT_DIR/generate.c"
VEC="$SCRIPT_DIR/vectors.txt"
FAIL=0

[ -f "$GEN" ] && [ -f "$VEC" ] || { echo "GATE FAILURE: generate.c or vectors.txt missing"; exit 1; }
for seam in AMOUNT_PARSE_VIA_DOUBLE_UNFIXED AMOUNT_PARSE_COMMA_THOUSANDS_UNFIXED; do
    grep -q "$seam" "$CORE_DIR/BRAmountParse.h" 2>/dev/null \
        || { echo "GATE FAILURE: $CORE_DIR/BRAmountParse.h missing or has no $seam seam"; exit 1; }
    echo "  [seam] BRAmountParse.h carries $seam"
done

build() {   # <out> [flags...]
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Werror "$@" -I "$CORE_DIR" "$GEN" -o "$out"
}

build "$BUILD_DIR/gen" -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
    || { echo "GATE FAILURE: generator did not compile"; exit 1; }
build "$BUILD_DIR/gen_o2" -O2 || { echo "GATE FAILURE: -O2 generator did not compile"; exit 1; }
# No UBSan on the RED arm: the old double conversion's out-of-range cast is the undefined behaviour
# it is being shown to have; the gate asserts its wrong answers, not its UB report.
build "$BUILD_DIR/gen_red" -DAMOUNT_PARSE_VIA_DOUBLE_UNFIXED || { echo "GATE FAILURE: RED generator did not compile"; exit 1; }
build "$BUILD_DIR/gen_comma" -DAMOUNT_PARSE_COMMA_THOUSANDS_UNFIXED || { echo "GATE FAILURE: COMMA generator did not compile"; exit 1; }

if ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt" 2> "$BUILD_DIR/gen.err"; then
    echo "GATE FAILURE: generator failed"; head -5 "$BUILD_DIR/gen.err"; FAIL=1
fi
grep -Eq 'ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/gen.err" && { echo "GATE FAILURE: sanitizer report"; FAIL=1; }

if cmp -s "$BUILD_DIR/vectors.txt" "$VEC"; then
    echo "  [vectors] current ($(grep -c '^parse ' "$VEC") parse, $(grep -c '^format ' "$VEC") format)"
else
    echo "  [vectors] GATE FAILURE: vectors.txt is not what generate.c writes from this header; regenerate it"
    diff "$VEC" "$BUILD_DIR/vectors.txt" | head -10 | cut -c1-200 | sed 's/^/      /'
    FAIL=1
fi

"$BUILD_DIR/gen_o2" > "$BUILD_DIR/vectors_o2.txt"
cmp -s "$BUILD_DIR/vectors_o2.txt" "$VEC" && echo "  [-O2] same answers" \
    || { echo "  [-O2] GATE FAILURE: optimisation changed an answer"; FAIL=1; }

"$BUILD_DIR/gen_red" > "$BUILD_DIR/vectors_red.txt" 2>/dev/null
n="$(diff <(grep '^parse ' "$VEC") <(grep '^parse ' "$BUILD_DIR/vectors_red.txt") | grep -c '^>')"
[ "$n" -gt 0 ] && echo "  [red parse] $n answers differ, as they must" \
    || { echo "  [red parse] GATE FAILURE: the double conversion writes the same answers"; FAIL=1; }
cmp -s <(grep '^format ' "$VEC") <(grep '^format ' "$BUILD_DIR/vectors_red.txt") && echo "  [red format] GUARD held" \
    || { echo "  [red format] GATE FAILURE: the RED seam changed format, which it does not touch"; FAIL=1; }

"$BUILD_DIR/gen_comma" > "$BUILD_DIR/vectors_comma.txt"
# Lines are in the same order in both files, so a paste pairs each input with both answers.
changed="$(paste -d '\n' <(grep '^parse ' "$VEC") <(grep '^parse ' "$BUILD_DIR/vectors_comma.txt") \
    | paste - - | awk -F '\t' '$1 != $2' )"
n="$(printf '%s' "$changed" | grep -c . || true)"
if [ "$n" -gt 0 ]; then
    echo "  [comma] $n answers differ, as they must"
    bad="$(printf '%s\n' "$changed" | awk -F '\t' '$1 !~ / -$/' | head -3)"
    [ -z "$bad" ] && echo "  [comma] every one is a refusal: no value changed" \
        || { echo "  [comma] GATE FAILURE: the rule changed a value, not only refused:"; echo "$bad" | cut -c1-200 | sed 's/^/      /'; FAIL=1; }
else
    echo "  [comma] GATE FAILURE: the comma seam writes the same answers"; FAIL=1
fi

# Inputs the port was judged on, with the header's answer for each.
while IFS= read -r want; do
    grep -qxF "$want" "$VEC" && echo "  [corpus] $want" || { echo "  [corpus] GATE FAILURE: no line '$want'"; FAIL=1; }
done <<'EOF'
parse "\xc2\xa05" -
parse "5\xe2\x80\xaf" -
parse "\x1c5" -
parse "\xd9\xa1" -
parse "\xd9\xa0.\xd9\xa5" -
parse "1.123456789" -
parse "1.100000000" 110000000
parse "92233720368.54775807" 9223372036854775807
parse "92233720368.54775808" -
parse "0e2147483647" 0
parse "0e2147483648" -
parse "0,29" 29000000
parse "1,2,3" -
parse "1,000" -
parse "25,500" -
parse "0,123" -
parse "1,000e2" -
parse "\x201,000\x20" -
parse "1,234" -
parse "12,3456" 1234560000
parse "1,0000" 100000000
parse "1,000.50" 100050000000
parse "1,234,567.8" 123456780000000
parse "\xd9\xa1,\xd9\xa0\xd9\xa0\xd9\xa0" -
EOF

[ $FAIL -eq 0 ] && echo "amount_vectors_kat: GATE HELD (vectors current; the double conversion fails them; the comma rule only refuses)"
exit $FAIL
