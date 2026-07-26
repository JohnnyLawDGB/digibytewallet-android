#!/usr/bin/env bash
# Host KAT runner for BIP158 GCS match against a REAL DigiByte block filter
# (gcs_match_kat_main.c). Proves BRGCSFilterBasicParse + BRGCSFilterMatch report
# hit=1 for two scriptPubKeys provably in mainnet block 23828952, under the
# wire-order block hash the parser keys the filter on (the on-device wallet once
# reported hit=0 for this exact 9-byte filter -- this pins the byte order).
#
# BRGCSFilter.c pulls in BRCrypto.c (siphash + the multi-algo PoW hashes),
# BRAddress.c (BRVarInt), BRNetwork.c (BRNetworkIsTestnet, called from BRAddress),
# and the address/key/base58/bech32 chain those reference, plus the crypto/*
# multi-algo sources (groestl/skein/qubit/odocrypt) and crypto/sha3/*.
#
# Compiler: clang, `-include stdint.h` -- same crypto/odocrypt.h reasons as the
# other host KATs (see bip340_kat/run.sh).
#
# Exit 0 = all checks passed, 1 = a check failed or a build error.
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
    "$SCRIPT_DIR/gcs_match_kat_main.c" \
    "$CORE_DIR/BRGCSFilter.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm \
    -o "$BUILD_DIR/gcs_match_kat"

"$BUILD_DIR/gcs_match_kat"
