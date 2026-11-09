#!/usr/bin/env bash
# Host KAT (DA-014-REF): the native DigiAsset carrier rule matches the shared vector set the Kotlin
# side is held to (vectors/da-carrier/vectors.txt): a transaction is credited only from its single
# nulldata output when that is also its single tagged output (DigiAsset Core reads the last nulldata
# output, and offers the first to other readers first). Compiles the REAL, live submodule files with ASan.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_CARRIER_REF_UNFIXED, #ifdef):
#   RED  : the earlier rule (the last output that frames as a carrier, header failures not seen).
#          Every RED-THEN-GREEN check
#          named below must FAIL, with no sanitizer report.
#   GREEN: every check passes -> exit 0, no sanitizer report.
#
# Exit code 0 = red arm failed every named check AND green passed clean; 1 = otherwise.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
VECTORS="$CORE_DIR/vectors/da-carrier/vectors.txt"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1:symbolize=0"

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() { # $1=output  $2..=extra flags
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_carrier_reference_kat_main.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread -o "$out"
}

[ -f "$VECTORS" ] || { echo "FAIL: $VECTORS missing"; exit 1; }

echo "=== building RED (the last output that frames as a carrier) ==="
build "$BUILD_DIR/red" -DASSET_CARRIER_REF_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
echo "=== building GREEN ==="
build "$BUILD_DIR/green" || { echo "FAIL: green arm did not build"; exit 1; }

echo "--- RED: must fault (a check must fail) ---"
out="$(timeout 60 "$BUILD_DIR/red" "$VECTORS" 2>&1)"; rc=$?
echo "$out"
if [ $rc -eq 0 ]; then
    echo "FAIL: red arm did not fault (a test that cannot go red proves nothing)"; exit 1
fi
if grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" <<<"$out"; then
    echo "FAIL: red arm faulted for the wrong reason (a sanitizer report, not the expected check)"; exit 1
fi
while IFS= read -r want; do
    [ -n "$want" ] || continue
    if ! grep -qF "FAIL: $want: carrier verdict" <<<"$out"; then
        echo "FAIL: red arm did not fail the check it must: $want"; exit 1
    fi
done <<'CHECKS'
plain_then_carrier
carrier_then_plain
two_carriers
two_carriers_then_plain
carrier_plain_carrier
carrier_then_bare_opreturn
carrier_then_op0
carrier_then_op16
carrier_then_op_reserved
carrier_then_pushdata1_plain
carrier_not_nulldata
carrier_not_nulldata_then_plain
plain_pushdata2_then_carrier
decoy_transfer_then_issuance
issuance_then_plain
two_issuances
version0_then_carrier
badop_then_carrier
single_version0
single_badop
op1negate_after
four_nulldata
enckey_shape
enckey_shape_two_outputs
CHECKS

echo "--- GREEN: must pass clean ---"
out="$(timeout 60 "$BUILD_DIR/green" "$VECTORS" 2>&1)"; rc=$?
echo "$out"
if [ $rc -ne 0 ] || grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" <<<"$out"; then
    echo "FAIL: green arm"; exit 1
fi

echo "PASS: asset_carrier_reference_kat"
