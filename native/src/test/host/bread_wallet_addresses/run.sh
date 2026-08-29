#!/usr/bin/env bash
# Derives legacy-BreadWallet addresses using the live C submodule. Not a KAT — a tool.
# Named without the _kat suffix so run-host-kats.sh does not treat it as a test.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
clang -w -include stdint.h -g -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/bread_wallet_addresses_main.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm \
    -o "$BUILD_DIR/bread_addr"
"$BUILD_DIR/bread_addr" "$@"
