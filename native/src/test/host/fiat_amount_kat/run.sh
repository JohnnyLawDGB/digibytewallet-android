#!/usr/bin/env bash
# Host KAT runner for BRFiatAmount.h: dollar text <-> cents, and cents <-> satoshis at a market
# rate, exactly (Android's UsdCents rules; UsdCentsTest.kt's cases and values, plus vectors
# computed in Python from repr(float) and exact fractions).
#
#   RED    -DFIAT_VIA_DOUBLE_UNFIXED: the naive double conversion, rounded half up. Every RED case
#          (ties, amounts beyond 2^53, the int64 ceiling) MUST fail; every GUARD case (parse,
#          format, the rate, the locale guard, the vectors the naive shape also gets right) pass.
#   GREEN  the exact conversion: every case passes, ALL PASS, exit 0, under ASan + UBSan,
#          64-bit and 32-bit (Android ships armeabi-v7a, which is why there is no __int128).
#
# Also: the header compiles cleanly under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 and,
# where a C++ compiler is present, as C++17; it links from two translation units; and it uses no
# __int128 and never calls setlocale.
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
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
HDR="$CORE_DIR/BRFiatAmount.h"
MAIN="$SCRIPT_DIR/fiat_amount_kat_main.c"

RED_CASES=(cents_round_half_even sats_round_half_even exact_beyond_double_precision ceiling_and_overflow)
GUARD_CASES=(whole_cents_convert_exactly more_than_two_typed_decimals_refused separators_read_the_dgb_way
             exponents_signs_nonfinite_refused empty_and_nonnumeric_refused
             out_of_range_refused_exact_ceiling_accepted format_plain_two_decimals
             every_cent_value_survives_the_round_trip rate_is_the_shortest_decimal_that_reads_back
             preview_empty_without_amount_or_price kotlin_conversion_vectors c_locale_decimal_point_guard
             header_links_from_two_units independent_vectors)

[ -f "$HDR" ] || { echo "GATE FAILURE: $HDR missing."; exit 1; }
grep -q "FIAT_VIA_DOUBLE_UNFIXED" "$HDR" || { echo "GATE FAILURE: $HDR has no RED seam; -D would be inert."; exit 1; }
# Code, not comments: strip // and /* */ comments before looking.
CODE="$(sed -e 's://.*$::' "$HDR" | perl -0pe 's:/\*.*?\*/::gs')"
<<<"$CODE" grep -q "__int128" && { echo "GATE FAILURE: BRFiatAmount.h uses __int128 (armeabi-v7a has none)."; exit 1; }
<<<"$CODE" grep -q "setlocale" && { echo "GATE FAILURE: BRFiatAmount.h calls setlocale."; exit 1; }

FAIL=0

# Header hygiene: a TU that includes it, at every C standard, pedantic, warnings as errors.
printf '#include "BRFiatAmount.h"\n#include "BRFiatAmount.h"\nint main(void){char b[32];return (int)BRUsdCentsFormat(1,b,sizeof b)!=4;}\n' >"$BUILD_DIR/hyg.c"
for std in c99 c11 c17; do
    "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -I "$CORE_DIR" "$BUILD_DIR/hyg.c" -o "$BUILD_DIR/hyg_$std" \
        && echo "  [hygiene $std] clean" || { echo "  [hygiene $std] GATE FAILURE"; FAIL=1; }
done
CXX="${CXX:-clang++}"
if command -v "$CXX" >/dev/null 2>&1; then
    cp "$BUILD_DIR/hyg.c" "$BUILD_DIR/hyg.cpp"
    "$CXX" -std=c++17 -Wall -Wextra -Werror -I "$CORE_DIR" -fsyntax-only "$BUILD_DIR/hyg.cpp" \
        && echo "  [hygiene c++17] clean" || { echo "  [hygiene c++17] GATE FAILURE"; FAIL=1; }
else
    echo "  [hygiene c++17] not run: no $CXX on this host"
fi

# <out> <sanitizers> <64|32> [-D...]. The 32-bit arm uses SSE2 doubles, as armeabi-v7a's VFP
# does, rather than x87's excess precision, which no Android ABI has.
build() {
    local out="$1" san="$2" bits="$3"; shift 3
    local m=(); [ "$bits" = "32" ] && m=(-m32 -msse2 -mfpmath=sse)
    "$CC" -std=c99 -Wall -Wextra -Werror -g -fsanitize="$san" -fno-omit-frame-pointer ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" -c "$MAIN" -o "$out.main.o" &&
    "$CC" -std=c99 -Wall -Wextra -Werror -g -fsanitize="$san" -fno-omit-frame-pointer ${m[@]+"${m[@]}"} "$@" \
        -DFIAT_KAT_SECOND_TU -I "$CORE_DIR" -c "$MAIN" -o "$out.tu2.o" &&
    "$CC" -fsanitize="$san" ${m[@]+"${m[@]}"} "$out.main.o" "$out.tu2.o" -lm -o "$out"
}

# RED runs under ASan only: the naive conversion's out-of-range cast is exactly the undefined
# behaviour it is being shown to have, and the gate asserts its wrong VALUES, not its UB report.
build "$BUILD_DIR/red" address 64 -DFIAT_VIA_DOUBLE_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" address,undefined 64 -fno-sanitize-recover=undefined || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DFIAT_VIA_DOUBLE_UNFIXED) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
<<<"$out" grep -E "ERROR: AddressSanitizer|ERROR: LeakSanitizer" >/dev/null && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c fail$" && echo "  [red $c] failed, as it must: $(<<<"$out" grep "^FAIL: $c" | head -1)" \
        || { echo "  [red $c] GATE FAILURE: did not fail"; FAIL=1; }
done
for c in "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [red $c] GUARD held" \
        || { echo "  [red $c] GATE FAILURE: GUARD did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; }
done

check_green() {   # <label> <binary>
    local label="$1" bin="$2" out rc
    echo "--- SHIPPED ARM ($label) ---"
    out="$("$bin" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$label] GATE FAILURE: sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; FAIL=1; }
    <<<"$out" grep "^NOTE:" | sed "s/^/  [$label] /"
    if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
        echo "  [$label] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
    fi
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        <<<"$out" grep -q "^RESULT $c pass$" && echo "  [$label $c] passed" || { echo "  [$label $c] GATE FAILURE"; FAIL=1; }
    done
}
check_green green64 "$BUILD_DIR/green"

# A 32-bit arm that cannot build fails rather than skips: install the toolchain (CI does,
# gcc-multilib), never relax the suite.
if build "$BUILD_DIR/green32" address,undefined 32 -fno-sanitize-recover=undefined >"$BUILD_DIR/green32.log" 2>&1; then
    check_green green32 "$BUILD_DIR/green32"
else
    echo "  [green32] GATE FAILURE: 32-bit arm did not build (needs 32-bit libc headers and sanitizer runtime)"
    head -3 "$BUILD_DIR/green32.log" | sed 's/^/      /'
    FAIL=1
fi

[ $FAIL -eq 0 ] && echo "fiat_amount_kat: GATE HELD (RED was off by a cent or a satoshi at ties and past 2^53; GREEN exact)"
exit $FAIL
