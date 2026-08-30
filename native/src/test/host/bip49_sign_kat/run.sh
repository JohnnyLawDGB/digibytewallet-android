#!/usr/bin/env bash
# Host KAT runner for the BRTransactionSign characterization suite.
#
# Compiles the REAL, live submodule files straight out of the tree, same as the
# other signing KATs. Unlike bip341_sighash_kat this one does NOT #include
# BRTransaction.c into main.c — it needs no file-static symbols — so
# BRTransaction.c IS passed on the clang line as its own translation unit.
#
# Why so many .c files link for a main() that calls a handful: each translation
# unit references symbols across the whole dependency chain (BRSet, BRAddress,
# BRBech32, BRBase58, BRBIP39Mnemonic, BRDigiAsset and crypto/* down through
# groestl/skein/qubit/odocrypt and the sph_* helpers under crypto/sha3/). Static
# linking must resolve every symbol each .o references, not only the invoked ones.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` — same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
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
    "$SCRIPT_DIR/bip49_sign_kat_main.c" \
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
    -lm \
    -o "$BUILD_DIR/bip49_sign_kat"

"$BUILD_DIR/bip49_sign_kat"
