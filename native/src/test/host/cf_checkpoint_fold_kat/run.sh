#!/usr/bin/env bash
# Host KAT runner: every pinned filter-header checkpoint equals the BIP157 fold of the
# chain's own data at that height. See cf_checkpoint_fold_kat_main.c for the invariant
# and gen_vectors.py for where the vectors come from.
#
# Links the REAL BRCompactFilterChain.c, so the comparison is made by the same
# BRCompactFilterChainBatchViolatesCheckpoint the wallet runs on a relayed batch, against
# the REAL BRMainNetCFCheckpoints table.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -g -fsanitize=address,undefined -fno-omit-frame-pointer -include stdint.h \
    -I "$CORE_DIR" -I "$SCRIPT_DIR" \
    "$SCRIPT_DIR/cf_checkpoint_fold_kat_main.c" \
    "$CORE_DIR/BRCompactFilterChain.c" \
    "$CORE_DIR/BRGCSFilter.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm \
    -o "$BUILD_DIR/cf_checkpoint_fold_kat"

"$BUILD_DIR/cf_checkpoint_fold_kat"
