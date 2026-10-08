#!/usr/bin/env bash
# Host KAT: a DigiAsset carrier the native reader cannot classify holds every output of its
# transaction out of the spendable DGB set.
#
# An OP_RETURN tagged "DA" whose framing (OP_PUSHDATA2/4) or payload the reader cannot classify
# holds every output of its transaction: BRTxOutputIsAsset answers 1 for each, the wallet files the
# owned ones in assetUtxos, they leave the spendable balance, and coin selection cannot reach them.
# An OP_RETURN not tagged "DA" leaves its transaction's outputs as ordinary DGB, however framed.
# Compiles the REAL, live submodule files with AddressSanitizer.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_CARRIER_FAILCLOSED_UNFIXED, #ifdef):
#   RED  : an unclassifiable carrier reads as no carrier (the earlier answer). Every
#          RED-THEN-GREEN check named below must FAIL, with no sanitizer report.
#   GREEN: every check passes -> exit 0, no sanitizer report.
#
# Exit code 0 = red arm failed every named check AND green passed clean; 1 = otherwise.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1:detect_leaks=0:symbolize=0"

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() { # $1=output  $2..=extra flags
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_unclassifiable_carrier_kat_main.c" \
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
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -o "$out"
}

echo "=== building RED (an unclassifiable carrier reads as no carrier) ==="
build "$BUILD_DIR/red" -DASSET_CARRIER_FAILCLOSED_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
echo "=== building GREEN ==="
build "$BUILD_DIR/green" || { echo "FAIL: green arm did not build"; exit 1; }

echo "--- RED: must fault (a check must fail) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out"
if [ $rc -eq 0 ]; then
    echo "FAIL: red arm did not fault (a test that cannot go red proves nothing)"; exit 1
fi
if grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: red arm faulted for the wrong reason (a sanitizer report, not the expected check)"; exit 1
fi
while IFS= read -r want; do
    [ -n "$want" ] || continue
    if ! grep -qF "FAIL: $want" <<<"$out"; then
        echo "FAIL: red arm did not fail the check it must: $want"; exit 1
    fi
done <<'CHECKS'
a PUSHDATA2 carrier holds an output its instruction does not name
a PUSHDATA2 carrier holds the output its instruction names
a PUSHDATA4 carrier holds an output its instruction does not name
a PUSHDATA4 carrier holds the output its instruction names
a 257-byte PUSHDATA2 carrier holds an output its instructions do not name
a PUSHDATA2 carrier is an asset outpoint
a tagged push too short for the header holds every output
a tagged push that runs past the script holds every output
an undefined opcode holds every output
version 0 holds every output
a transfer amount the payload ends inside holds every output
the PUSHDATA2 carrier's named output is held as an asset output
the PUSHDATA2 carrier's unnamed output is held as an asset output
the PUSHDATA4 carrier's output is held as an asset output
only the plain receive and the plain OP_RETURN's output count toward the spendable balance
a DGB send that would need a held output fails instead of spending it
CHECKS

echo "--- GREEN: must pass clean ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out"
if [ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: green arm"; exit 1
fi

echo "PASS: asset_unclassifiable_carrier_kat"
