#!/usr/bin/env bash
# Host KAT runner for the Digi-ID SLIP-0013 identity derivation
# (bridge/slip13.c + BRBIP32Sequence's BRBIP32PrivKeyPath).
#
# Compiles the REAL live sources out of the tree — bridge/slip13.c and the core
# submodule's BRBIP32Sequence.c/BRKey.c chain. Same clang + link-everything
# rationale as bip340_kat/run.sh: BRKey.c is one translation unit whose other
# functions reference Base58/Bech32/X11 helpers, so the full dependency chain
# links even though this KAT never calls those hashes.
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

clang -w -include stdint.h -fsanitize=address \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    -I "$BRIDGE_DIR" \
    "$SCRIPT_DIR/slip13_identity_kat_main.c" \
    "$BRIDGE_DIR/slip13.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/slip13_identity_kat"

"$BUILD_DIR/slip13_identity_kat"
