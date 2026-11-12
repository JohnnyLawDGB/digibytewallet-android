#!/usr/bin/env bash
# Host KAT runner for BRRawTxBinding.h (digibytewallet-core): the id of raw transaction bytes, moved from the
# JNI bridge (bridge/asset_tx_checks.h raw_tx_id), and the parent binding, the admit gate's binding part and
# the fee read-back, moved from Kotlin (core/.../recovery/RawTxBinding.kt, LegacySweepService.admit). See
# raw_tx_binding_kat_main.c for what each case compares.
#
#   shipped  every seam =0: every case passes, under ASan + UBSan, 64-bit.
#   comparison arms, one defect each (BRRawTxBinding.h's MACRO CONVENTION):
#     RAW_TX_ID_UNFIXED                      an id is reported for whatever the parser read (trailing bytes,
#                                            truncations it tolerates, non-canonical varints);
#     RAW_TX_BINDING_OTHER_TX_UNFIXED        a parent whose computed id differs is read as the coin's parent;
#     RAW_TX_BINDING_TRUST_UNPROVEN_UNFIXED  a coin with no readable parent is proven at the server's amount;
#     RAW_TX_FEE_OUTPOINTS_UNFIXED           the fee is answered without comparing the outpoints spent;
#     RAW_TX_FEE_SET_UNFIXED                 the outpoints are compared as a count and a set (a repeated
#                                            outpoint on both sides is accepted).
#   CASES gives each case "<case>:<id>:<other tx>:<trust unproven>:<fee outpoints>:<fee set>", each fail (the
#   arm cannot keep the rule) or pass (a guard: holds in that arm too); each arm must exit nonzero somewhere.
#
# Gates first: the bridge header this suite compiles is the one that ships, and the JNI accessor still
# reaches it; the core header tests every seam by VALUE with an #ifndef default; the binding reads ids
# only through BRRawTxId; the header compiles clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17,
# included twice and linked from two units, and for iphoneos/iphonesimulator where an Xcode SDK exists.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: a comparison arm's nonzero exit is inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$CORE_DIR/BRRawTxBinding.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

BINDING_SEAMS=(RAW_TX_BINDING_OTHER_TX_UNFIXED RAW_TX_BINDING_TRUST_UNPROVEN_UNFIXED RAW_TX_FEE_OUTPOINTS_UNFIXED RAW_TX_FEE_SET_UNFIXED)

CASES=(
    id_fixed_vectors:pass:pass:pass:pass:pass
    id_hostile_vectors:fail:pass:pass:pass:pass
    id_fuzz:fail:pass:pass:pass:pass
    rules_fuzz:fail:fail:fail:pass:pass
    fee_fuzz:pass:pass:pass:fail:pass
    genesis_parent_proves:pass:pass:pass:pass:pass
    segwit_parent_proves:pass:pass:pass:pass:pass
    witness_hashing_id_contradicts:pass:fail:pass:pass:pass
    other_tx_contradicts:pass:fail:pass:pass:pass
    amount_index_script_contradict:pass:pass:pass:pass:pass
    one_contradiction_refuses_profile:pass:pass:pass:pass:pass
    no_parent_or_unreadable_unproven:pass:pass:fail:pass:pass
    proven_carries_parent_script:pass:pass:pass:pass:pass
    fee_is_inputs_less_outputs:pass:pass:pass:pass:pass
    fee_needs_exact_outpoints:pass:pass:pass:fail:pass
    fee_repeated_outpoint_refused:pass:pass:pass:fail:fail
    no_memory:pass:pass:pass:pass:pass
    gate_amount_lower:pass:pass:pass:pass:pass
    gate_amount_higher:pass:pass:pass:pass:pass
    gate_other_tx:pass:fail:pass:pass:pass
    gate_missing_index:pass:pass:pass:pass:pass
    gate_other_script:pass:pass:pass:pass:pass
    gate_no_parent_held_back:pass:pass:fail:pass:pass
    gate_unreadable_held_back:pass:pass:fail:pass:pass
    gate_honest_sign_and_fee:pass:pass:pass:pass:pass
    gate_signed_not_exact_not_sent:pass:pass:pass:fail:pass
    gate_zero_refused_before_parent:pass:pass:pass:pass:pass
    gate_order:pass:pass:pass:pass:pass
    signer_values_from_parent_only:pass:pass:fail:pass:pass
    first_parent_and_listing:pass:pass:fail:pass:pass
    huge_amount_parent_unproven:pass:pass:fail:pass:pass
    large_inputs:pass:pass:fail:pass:pass
)

FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

# ---- the seams are real --------------------------------------------------------------------
[ -f "$HEADER" ] || gate_fail "$HEADER not found: a core pin from before the raw-transaction binding (set CORE_DIR)."
[ -f "$BRIDGE_DIR/asset_tx_checks.h" ] || gate_fail "bridge/asset_tx_checks.h not found"
grep -Eq '^static inline int raw_tx_id\(' "$BRIDGE_DIR/asset_tx_checks.h" || gate_fail "asset_tx_checks.h has no raw_tx_id"
grep -q 'raw_tx_id(' "$BRIDGE_DIR/jni_asset_send.c" || gate_fail "the JNI accessor no longer calls raw_tx_id"
HDR="$(cat "$HEADER")"
for fn in BRRawTxId BRRawTxIdFromDisplayHex BRRawTxBindingCheck BRRawTxBindingAdmit BRRawTxBindingFeePaid; do
    grep -Eq "static inline [A-Za-z0-9_ ]+\*? ?$fn\(" <<<"$HDR" || gate_fail "$HEADER does not define $fn"
done
for m in RAW_TX_ID_UNFIXED "${BINDING_SEAMS[@]}"; do
    grep -Eq "^[[:space:]]*#[[:space:]]*if[[:space:]]+!?$m" <<<"$HDR" || gate_fail "$HEADER does not test the value of $m"
    grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+$m" <<<"$HDR" || gate_fail "$HEADER gives $m no default"
    grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+$m" <<<"$HDR" && gate_fail "$HEADER selects an arm of $m by presence"
done
# the binding's only id function is BRRawTxId's exact parse: RawTxBindingTest's "production takes the id
# from the wallet's own parser", for the core
if [ "$(grep -c '_BRRawTxParseExact(' <<<"$HDR")" -ne 3 ] || grep -q 'BRSHA256_2\|BRSHA256(' <<<"$HDR"; then
    gate_fail "the binding computes a parent's id some other way than BRRawTxId's exact parse"
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=(
    "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}"
)

# ---- PORTABILITY GATE ----------------------------------------------------------------------
# The other core headers are not -Wextra/-Wpedantic clean, so they come in as system headers; this one is
# checked from a copy, included twice, from two units linked together.
mkdir -p "$BUILD_DIR/hdr" && cp "$HEADER" "$BUILD_DIR/hdr/"
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRRawTxBinding.h"
int strict_tu1(void);
int strict_tu1(void) { UInt256 u; return BRRawTxIdFromDisplayHex("00", 2, &u); }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRRawTxBinding.h"
#include "BRRawTxBinding.h"
int strict_tu1(void);
int main(void) { BRRawTxBindingVerdict v; int64_t fee;
    return (BRRawTxBindingCheck(NULL, 0, NULL, 0, NULL, &v) == BR_RAW_TX_BINDING_CHECKED
            && !BRRawTxBindingFeePaid(NULL, 0, NULL, 0, &fee) && !strict_tu1()) ? 0 : 1; }
EOF
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-\#pragma-messages -include stdint.h \
            -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" -isystem "$CORE_DIR/secp256k1/include" \
            -c "$BUILD_DIR/strict1.c" -o "$BUILD_DIR/s1_$std.o" ||
       ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-\#pragma-messages -include stdint.h \
            -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" -isystem "$CORE_DIR/secp256k1/include" \
            -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/s2_$std.o" ||
       ! "$CC" -w "$BUILD_DIR/s1_$std.o" "$BUILD_DIR/s2_$std.o" "${UNITS[@]}" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
            -I "$CORE_DIR/secp256k1" -include stdint.h -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two units, header twice)"; FAIL=1; strict_ok=0
    fi
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces \
                -Wno-\#pragma-messages -include stdint.h -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" \
                -isystem "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
[ $strict_ok -eq 1 ] && echo "portability gate: clean at c99/c11/c17, two units, header twice ($ios_note)"

# ---- the arms ------------------------------------------------------------------------------
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <extra flags...>
    local out="$1"; shift
    # The suite, the bridge header and the core header build with warnings as errors; the core sources
    # build as the app builds them.
    "$CC" -Wall -Wextra -Werror -Wno-\#pragma-messages -include stdint.h "${SAN[@]}" "$@" \
        -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        -c "$SCRIPT_DIR/raw_tx_binding_kat_main.c" -o "$out.o" || return 1
    "$CC" -w -include stdint.h "${SAN[@]}" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$out.o" "${UNITS[@]}" -lpthread -lm -o "$out"
}

build "$BUILD_DIR/green" || { echo "GATE FAILURE: the shipped arm did not compile."; exit 1; }
build "$BUILD_DIR/red_id" -DRAW_TX_ID_UNFIXED=1 || { echo "GATE FAILURE: the id arm did not compile."; exit 1; }
for m in "${BINDING_SEAMS[@]}"; do
    build "$BUILD_DIR/red_$m" "-D$m=1" || { echo "GATE FAILURE: the $m arm did not compile."; exit 1; }
done

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

run_red() {   # <arm name> <binary> <field 2|3>
    local arm="$1" bin="$2" field="$3" any=0
    echo "--- COMPARISON ARM ($arm) ---"
    for entry in "${CASES[@]}"; do
        local c="${entry%%:*}" want out rc
        want="$(cut -d: -f"$field" <<<"$entry")"
        out="$(timeout 600 "$bin" "$c" 2>&1)"; rc=$?
        [ $rc -ne 0 ] && any=1
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$arm $c] GATE FAILURE: sanitizer report"; grep -Em3 "$SAN_RE" <<<"$out"; FAIL=1; continue; fi
        if grep -q "FAIL: setup" <<<"$out"; then echo "  [$arm $c] GATE FAILURE: failed in setup"; FAIL=1; continue; fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                echo "  [$arm $c] failed, as it must: $(grep -m1 '^FAIL:' <<<"$out" | cut -c7-150)"
            else
                echo "  [$arm $c] GATE FAILURE: the arm did not fail this case -- it cannot see the defect"; FAIL=1
            fi ;;
        pass)
            if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" <<<"$out"; then echo "  [$arm $c] passed (guard)"
            else echo "  [$arm $c] GATE FAILURE: the guard did not hold"; grep '^FAIL:' <<<"$out" | head -3 | sed 's/^/      /'; FAIL=1; fi ;;
        *) echo "  [$arm $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
        esac
    done
    [ $any -eq 0 ] && { echo "  [$arm] GATE FAILURE: every case passed; the arm proves nothing"; FAIL=1; }
}

run_red "RAW_TX_ID_UNFIXED=1" "$BUILD_DIR/red_id" 2
f=3
for m in "${BINDING_SEAMS[@]}"; do run_red "$m=1" "$BUILD_DIR/red_$m" $f; f=$((f + 1)); done

echo "--- SHIPPED ARM: every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$(timeout 600 "$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] $(grep '^PASS:' <<<"$out" | cut -c7-) $(grep '^INFO:' <<<"$out" | cut -c7- | tr '\n' ' ')"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | head -5 | sed 's/^/      /'; FAIL=1
    fi
done

[ $FAIL -eq 0 ] && echo "PASS: raw_tx_binding_kat (${#CASES[@]} cases: all five comparison arms as expected, shipped arm clean under ASan + UBSan; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: raw_tx_binding_kat"
exit $FAIL
