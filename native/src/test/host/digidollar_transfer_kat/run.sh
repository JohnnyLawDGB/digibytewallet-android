#!/usr/bin/env bash
# Host KAT runner: the consensus-significant shape of a DigiDollar transfer — version marker,
# OP_RETURN bytes, recipient script and cent bounds — checked against a real mainnet transfer.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
clang -w -include stdint.h -g -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/digidollar_transfer_kat_main.c" \
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" \
    -lm -o "$BUILD_DIR/digidollar_transfer_kat"
ASAN_OPTIONS=abort_on_error=1:detect_leaks=0 "$BUILD_DIR/digidollar_transfer_kat"
