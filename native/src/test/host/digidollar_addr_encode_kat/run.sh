#!/usr/bin/env bash
# Host KAT runner for BRDigiDollarAddressEncode (DigiDollar receive-address encoder).
# Cross-checks our C encode against tonymorony's independent Kotlin golden vector and
# verifies the encode/decode round-trip. Same real-file compile approach as the sibling
# digidollar_decode_kat/run.sh (compiles the live submodule sources out of the tree).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build error).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/digidollar_addr_encode_kat_main.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm \
    -o "$BUILD_DIR/digidollar_addr_encode_kat"

"$BUILD_DIR/digidollar_addr_encode_kat"
