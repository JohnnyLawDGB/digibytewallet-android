#!/usr/bin/env bash
# Host KAT runner for BRDustPolicy.h -- the dust line an output is held to, by its script, and the
# DigiDollar token exemption, from Android's DustPolicy.kt (TransactionBuilder refuses a payment
# below its line at review, and holds every output of the built bytes to the same line).
#
# [1/3] HEADER. Two translation units that each include BRDustPolicy.h twice are compiled under
#       -Wall -Wextra -Wpedantic -Werror as C99, C11 and C17, and as C++17, then linked together,
#       so the static inline helpers link from more than one unit. -Wno-gnu-folding-constant (C
#       only): crypto/odocrypt.h, reached through BRAddress.h and BRCrypto.h, sizes arrays with
#       const ints, which -Wpedantic reports. That is pre-existing core code; suppressing exactly
#       that one class keeps -Werror meaningful for everything else.
# [2/3] ARMS. dust_policy_kat_main.c, linked with the core units BRDigiDollarTxType and
#       BRVarIntSize need, under ASan + UBSan, 64-bit and 32-bit (armeabi-v7a ships):
#         red_rate          -DDUST_POLICY_RED_RATE          exactly RED_RATE_FAILS fail
#         red_no_exemption  -DDUST_POLICY_RED_NO_EXEMPTION  exactly RED_NO_EXEMPTION_FAILS fail
#         red_any_zero      -DDUST_POLICY_RED_ANY_ZERO      exactly RED_ANY_ZERO_FAILS fail
#         green             the shipped rule: every case passes, ALL PASS, exit 0
#       In a comparison arm every case not named must pass, so each named case is shown to see
#       the regression it is there for, and the others are shown not to depend on it.
# [3/3] SAME ANSWERS. The shipped arm prints the same lines 64-bit and 32-bit.
#
# An arm that cannot be built fails the gate rather than being skipped (CI installs gcc-multilib
# for -m32). CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail   # not -e: exit codes of the arms are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRDustPolicy.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
INC=(-I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1")

ALL_CASES=(constants p2pkh_line p2sh_line p2wpkh_line p2tr_line p2wsh_line unspendable_has_no_line
           witness_program_by_shape dd_token_exempt_in_dd_tx dd_exemption_is_exact
           dd_tx_is_marker_and_known_type real_dd_transfer_has_no_dust amount_bounds
           compact_size_boundary script_size_limit witness_length_bounds witness_version_opcodes
           dd_exempt_shape_bounds)
# Bitcoin's rate: every line value is a tenth; the exemption and the classifier are untouched.
RED_RATE_FAILS=(p2pkh_line p2sh_line p2wpkh_line p2tr_line p2wsh_line witness_program_by_shape
                dd_exemption_is_exact compact_size_boundary script_size_limit witness_length_bounds
                witness_version_opcodes)
# No exemption: the token outputs of a DigiDollar transfer are refused.
RED_NO_EXEMPTION_FAILS=(dd_token_exempt_in_dd_tx real_dd_transfer_has_no_dust dd_exempt_shape_bounds)
# Any zero-value output exempt: a zero-value output outside a DigiDollar transaction, or of the
# wrong shape, is let through.
RED_ANY_ZERO_FAILS=(dd_exemption_is_exact real_dd_transfer_has_no_dust dd_exempt_shape_bounds)

for seam in DUST_POLICY_RED_RATE DUST_POLICY_RED_NO_EXEMPTION DUST_POLICY_RED_ANY_ZERO; do
    if ! grep -q "$seam" "$HEADER" 2>/dev/null; then
        echo "GATE FAILURE: $HEADER missing or has no $seam seam; -D$seam would be inert."
        exit 1
    fi
done

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRKey.c"
       "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRNetwork.c"
       "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c"
       "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
       "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" ${SHA3_SRCS[@]+"${SHA3_SRCS[@]}"})

in_list() {   # <word> <list...>
    local w="$1"; shift
    local x; for x in "$@"; do [ "$x" = "$w" ] && return 0; done
    return 1
}

# The core units, once per word size, into obj<bits>/, each object named by its path:
# crypto/groestl.c and crypto/sha3/groestl.c (and skein) share a base name. -w: they are the
# core's, not this KAT's.
build_units() {   # <bits>
    local bits="$1" m=() u o
    [ "$bits" = "32" ] && m=(-m32)
    mkdir -p "$BUILD_DIR/obj$bits"
    for u in "${UNITS[@]}"; do
        o="${u#"$CORE_DIR"/}"; o="$BUILD_DIR/obj$bits/$(printf '%s' "${o%.c}" | tr '/' '_').o"
        "$CC" -w -include stdint.h ${m[@]+"${m[@]}"} "${SAN[@]}" "${INC[@]}" -c "$u" -o "$o" || return 1
    done
}

build_arm() {   # <out> <bits> [-D...]
    local out="$1" bits="$2" m=(); shift 2
    [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -std=c99 ${m[@]+"${m[@]}"} "${SAN[@]}" "${INC[@]}" "$@" \
        "$SCRIPT_DIR/dust_policy_kat_main.c" "$BUILD_DIR/obj$bits"/*.o -lpthread -lm -o "$out"
}

FAIL=0
echo "--- building the core units, 64-bit and 32-bit ---"
( trap - EXIT; build_units 64 >"$BUILD_DIR/units64.log" 2>&1; echo $? >"$BUILD_DIR/units64.rc" ) &
( trap - EXIT; build_units 32 >"$BUILD_DIR/units32.log" 2>&1; echo $? >"$BUILD_DIR/units32.rc" ) &
wait
BITS=()
for bits in 64 32; do
    if [ "$(cat "$BUILD_DIR/units$bits.rc" 2>/dev/null)" = "0" ]; then BITS+=("$bits")
    else
        echo "  GATE FAILURE: the ${bits}-bit core units did not build (a build error is not evidence)"
        grep -m 3 -E 'error|fatal' "$BUILD_DIR/units$bits.log" | sed 's/^/      /'
        FAIL=1
    fi
done

echo "[1/3] the header: C99, C11, C17 and C++17 under -Wall -Wextra -Wpedantic -Werror, two units linked"
for tu in a b; do
    {
        echo '#include "BRDustPolicy.h"'
        echo '#include "BRDustPolicy.h"'
        echo "int64_t dust_tu_$tu(const uint8_t *s, size_t n, int64_t v, int dd);"
        echo "int64_t dust_tu_$tu(const uint8_t *s, size_t n, int64_t v, int dd)"
        echo "{ return BRDustIsDust(v, s, n, dd) ? BRDustThreshold(s, n) : -1; }"
    } > "$BUILD_DIR/tu_$tu.c"
done
cat >> "$BUILD_DIR/tu_a.c" <<'EOF'
int64_t dust_tu_b(const uint8_t *s, size_t n, int64_t v, int dd);
int main(void)
{
    static const uint8_t p2wpkh[22] = { 0x00, 0x14 };
    return (dust_tu_a(p2wpkh, 22, 2939, 0) == 2940 && dust_tu_b(p2wpkh, 22, 2939, 0) == 2940 &&
            dust_tu_a(p2wpkh, 22, 2940, 0) == -1) ? 0 : 1;
}
EOF
STRICT=(-Wall -Wextra -Wpedantic -Werror)
for std in c99 c11 c17; do
    for tu in a b; do
        if ! "$CC" -std=$std "${STRICT[@]}" -Wno-gnu-folding-constant -I "$CORE_DIR" -c "$BUILD_DIR/tu_$tu.c" \
                -o "$BUILD_DIR/tu_${tu}_$std.o" >"$BUILD_DIR/tu_${tu}_$std.log" 2>&1; then
            echo "  GATE FAILURE: unit $tu does not compile cleanly as $std"
            grep -m 4 -E 'error|warning' "$BUILD_DIR/tu_${tu}_$std.log" | sed 's/^/      /'; FAIL=1
        fi
    done
done
if "$CC" -x c++ -std=c++17 "${STRICT[@]}" -I "$CORE_DIR" -fsyntax-only "$BUILD_DIR/tu_a.c" >"$BUILD_DIR/cxx.log" 2>&1; then
    echo "  C++17: clean"
else
    echo "  GATE FAILURE: the header does not compile cleanly as C++17"
    grep -m 4 -E 'error|warning' "$BUILD_DIR/cxx.log" | sed 's/^/      /'; FAIL=1
fi
if in_list 64 ${BITS[@]+"${BITS[@]}"} && [ -f "$BUILD_DIR/tu_a_c99.o" ] && [ -f "$BUILD_DIR/tu_b_c99.o" ]; then
    if "$CC" "${SAN[@]}" "$BUILD_DIR/tu_a_c99.o" "$BUILD_DIR/tu_b_c99.o" "$BUILD_DIR/obj64"/*.o -lpthread -lm \
            -o "$BUILD_DIR/two_units" >"$BUILD_DIR/two_units.log" 2>&1 && "$BUILD_DIR/two_units"; then
        echo "  C99, C11, C17: clean; two units linked and agree"
    else
        echo "  GATE FAILURE: two units including the header did not link, or disagree"
        grep -m 4 -E 'error|duplicate' "$BUILD_DIR/two_units.log" | sed 's/^/      /'; FAIL=1
    fi
else
    echo "  GATE FAILURE: no 64-bit units or strict objects to link the two units against"; FAIL=1
fi

echo "[2/3] the arms"
ARMS=("red_rate -DDUST_POLICY_RED_RATE" "red_no_exemption -DDUST_POLICY_RED_NO_EXEMPTION"
      "red_any_zero -DDUST_POLICY_RED_ANY_ZERO" "green")
pids=()
for bits in ${BITS[@]+"${BITS[@]}"}; do
    for arm in "${ARMS[@]}"; do
        set -- $arm; name="$1"; shift
        ( trap - EXIT; build_arm "$BUILD_DIR/$name$bits" "$bits" "$@" >"$BUILD_DIR/$name$bits.build.log" 2>&1 ) &
        pids+=($!)
    done
done
for p in ${pids[@]+"${pids[@]}"}; do wait "$p"; done

for bits in ${BITS[@]+"${BITS[@]}"}; do
    for arm in "${ARMS[@]}"; do
        set -- $arm; name="$1"; tag="[$name $bits-bit]"
        if [ ! -x "$BUILD_DIR/$name$bits" ]; then
            echo "  $tag GATE FAILURE: did not compile"
            grep -E 'error:|^ld: ' "$BUILD_DIR/$name$bits.build.log" | grep -v '^ld: warning' | head -3 | sed 's/^/      /'; FAIL=1; continue
        fi
        out="$("$BUILD_DIR/$name$bits" 2>&1)"; rc=$?
        printf '%s\n' "$out" > "$BUILD_DIR/$name$bits.out"
        if <<<"$out" grep -Eq "$SAN_RE"; then
            echo "  $tag GATE FAILURE: sanitizer report"; <<<"$out" grep -m 2 -E "$SAN_RE" | sed 's/^/      /'; FAIL=1
        fi
        case "$name" in
            red_rate)         want=("${RED_RATE_FAILS[@]}") ;;
            red_no_exemption) want=("${RED_NO_EXEMPTION_FAILS[@]}") ;;
            red_any_zero)     want=("${RED_ANY_ZERO_FAILS[@]}") ;;
            *)                want=() ;;
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
            [ $bad -eq 0 ] && echo "  $tag failed exactly ${failed[*]}; the other $(( ${#ALL_CASES[@]} - ${#failed[@]} )) passed"
        fi
        [ $bad -ne 0 ] && FAIL=1
    done
done

echo "[3/3] the same answers, 64-bit and 32-bit"
if [ -f "$BUILD_DIR/green64.out" ] && [ -f "$BUILD_DIR/green32.out" ]; then
    l64="$(grep '^LINES' "$BUILD_DIR/green64.out")"; l32="$(grep '^LINES' "$BUILD_DIR/green32.out")"
    if [ -n "$l64" ] && [ "$l64" = "$l32" ]; then echo "  identical: $l64"
    else echo "  GATE FAILURE: 64-bit '$l64', 32-bit '$l32'"; FAIL=1; fi
else
    echo "  GATE FAILURE: a shipped arm did not run in both word sizes"; FAIL=1
fi

[ $FAIL -eq 0 ] && echo "dust_policy_kat: GATE HELD (each comparison arm failed exactly its cases; GREEN matches DustPolicy.kt)"
exit $FAIL
