#!/usr/bin/env bash
# Host KAT runner for the DigiDollar SHOW decoder module skeleton
# (BRDigiDollarTxType), added to BRDigiDollar.c/.h in DD-Show Task 1
# (.superpowers/sdd/task-1-brief.md).
#
# Same real-file compile approach as the taproot host KATs (see e.g.
# bip341_signtx_kat/run.sh for the full rationale): compiles the REAL, live
# submodule files directly out of the tree (no shim headers, no scratch-dir
# copy). BRDigiDollar.c only needs BRTransaction.h's struct layout, but
# BRTransaction.c (for BRTransactionNew/BRTransactionFree) drags in its full
# transitive dependency chain -- BRAddress, BRSet, BRKey, BRBase58, BRBech32,
# BRCrypto, BRDigiAsset, BRBIP32Sequence, BRBIP39Mnemonic, and crypto/*
# (groestl/skein/qubit/odocrypt + the X11/sph_* helpers under crypto/sha3/)
# -- so all of it must be linked for every symbol those .o files reference to
# resolve, not just the ones this KAT's main() calls directly.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh (file-scope
# `const static int` array bounds GCC rejects; missing <stdint.h> include).
# The real Android build uses the NDK's clang, so this stays representative.
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error -- expected before BRDigiDollar.h/.c exist).
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
    "$SCRIPT_DIR/digidollar_decode_kat_main.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRKey.c" \
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
    -o "$BUILD_DIR/digidollar_decode_kat"

"$BUILD_DIR/digidollar_decode_kat"
