#!/usr/bin/env bash
# Host KAT runner for BIP-86 key-path-only Taproot output key/address
# (BRKeyTaprootOutputKey / BRKeyTaprootAddress) added to BRKey.c/BRKey.h in
# Taproot Task 4.
#
# Same real-file compile approach as Task 3's bip340_kat (see that run.sh's
# comments for the full rationale) -- this compiles the REAL, live submodule
# files directly out of the tree (no shim headers, no scratch-dir copy),
# because BRKeyTaprootOutputKey needs the actual BRSHA256 (via
# BRKeyTaggedHash) and the actual vendored secp256k1 amalgamation.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- both for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh.
#
# Why BRCrypto.c/BRBase58.c/BRBech32.c/crypto/* must be linked at all even
# though this KAT never calls most of their functions: BRKey.c is one
# translation unit containing many functions besides the two new ones
# (BRKeySetPrivKey, BRKeyAddress, BRKeySegwitAddress, BRKeyHash160, ...) that
# reference BRBase58CheckDecode/BRBech32Encode/BRSHA256/BRHash160 etc. Static
# linking must resolve every symbol BRKey.o references, not just the ones
# this KAT's main() actually calls.
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error).
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
    "$SCRIPT_DIR/taproot_addr_kat_main.c" \
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
    -o "$BUILD_DIR/taproot_addr_kat"

"$BUILD_DIR/taproot_addr_kat"
