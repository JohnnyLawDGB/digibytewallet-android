#!/usr/bin/env bash
# Host KAT runner for Task 2 of the cfcheckpt-active-rejection plan:
# BRCompactFilterChainBatchViolatesCheckpoint (BRCompactFilterChain.c) --
# the pure, no-mutation batch-vs-checkpoint validator.
#
# cf_candidate_header_kat_main.c #include-s BRCompactFilterChain.c directly
# (to reach the file-static _batchViolatesCheckpoints / _foldHeader helpers
# -- see that main.c's header comment for the full rationale), so
# BRCompactFilterChain.c is deliberately NOT also compiled as a separate
# translation unit below.
#
# BRCompactFilterChain.c's remaining dependency is BRGCSFilter.c
# (BRGCSFilterHeader, the dSHA256(filterHash||prev) fold primitive, plus the
# GCS-filter parse path that calls BRVarInt for compact-size decoding).
# BRVarInt is defined in BRAddress.c, which in turn needs BRBase58.c/
# BRBech32.c (address encode/decode it exposes) and BRNetwork.c
# (BRNetworkIsTestnet, a BRBech32.c call site) to link. BRGCSFilter.c and
# BRBase58.c both need BRCrypto.c for BRSHA256_2 et al.; BRCrypto.c's
# translation unit also defines DigiByte's multi-algo hash wrappers
# (Groestl/Skein/Qubit/Odocrypt/X11), so linking it pulls in those
# crypto/*.c sources too, same as cf_confirm_kat/run.sh's core-source list
# -- trimmed here to just BRCompactFilterChain.c's actual dependency chain
# (no wallet/peer/tx/key code needed; this validator is a pure
# data-structure function).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh (file-scope `const static int`
# array bounds GCC rejects; missing <stdint.h> include).
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
    "$SCRIPT_DIR/cf_candidate_header_kat_main.c" \
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
    -o "$BUILD_DIR/cf_candidate_header_kat"

"$BUILD_DIR/cf_candidate_header_kat"
