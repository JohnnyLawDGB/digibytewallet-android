#!/usr/bin/env bash
# Host KAT runner for the BIP-341 key-path sighash (_BRTransactionTaprootSighash)
# added to BRTransaction.c in Taproot Sign-Task 3.
#
# Same real-file compile approach as the sibling KAT runners (see e.g.
# bip341_sign_kat/run.sh for the full rationale): compiles the REAL, live
# submodule files directly out of the tree (no shim headers, no scratch copy).
#
# KEY DIFFERENCE from the other runners: _BRTransactionTaprootSighash is a
# FILE-STATIC function (internal linkage). To exercise it the KAT main
# #include-s BRTransaction.c directly, so BRTransaction.c is deliberately NOT
# listed among the compiled sources below -- listing it would define every
# BRTransaction symbol twice (once in the #include, once on the command line)
# and fail to link. All of BRTransaction.c's own dependencies
# (BRAddress/BRKey/BRCrypto/BRBase58/BRBech32/crypto/*) still must be linked so
# every symbol BRTransaction.o references resolves.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh.
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
    "$SCRIPT_DIR/bip341_sighash_kat_main.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/bip341_sighash_kat"

"$BUILD_DIR/bip341_sighash_kat"
