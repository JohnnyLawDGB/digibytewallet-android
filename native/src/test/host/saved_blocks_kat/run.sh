#!/usr/bin/env bash
# Host KAT runner for deserialize_saved_blocks_guarded
# (native/src/main/jni/bridge/saved_blocks_deserialize.h), the count-cap +
# malloc-null-check guard extracted from loadSavedBlocks (jni_peer.c, Task 1
# of the Pixel-startup-hardening plan, .superpowers/sdd/task-1-brief.md).
#
# Same real-file compile approach as network_switch_kat/run.sh (see that
# file's comments for the full dependency rationale) -- this compiles the
# REAL, live submodule BRMerkleBlock.c/.h directly out of the tree so the
# KAT exercises the real BRMerkleBlockParse used in production, not a stand-in.
# BRAddress.c/BRBase58.c/BRBech32.c are linked because BRMerkleBlock.c calls
# BRVarInt/BRVarIntSize/BRVarIntSet, which are defined in BRAddress.c, which
# in turn needs Base58/Bech32 for its own address-encoding call sites (link
# requires the whole chain to resolve even though this KAT never calls an
# address-encoding function itself). BRNetwork.c supplies BRNetworkIsTestnet
# (called from BRMerkleBlock.c's difficulty-check path). BRCrypto.c + the
# crypto/* multi-algo hash sources supply BRSHA256_2 (used to compute
# block->blockHash on every parse) and the historical algo dispatch table.
#
# `ulimit -v` (virtual memory ceiling) is set before running the binary --
# NOT for sandboxing, but to make the RED/GREEN behavior this KAT guards
# deterministic on any host. Test 1 in saved_blocks_kat_main.c feeds a
# corrupt 0xFFFFFFFF block count; the pre-fix code would compute
# malloc(0xFFFFFFFF * sizeof(BRMerkleBlock*)) (~32 GB). On a workstation with
# ample RAM/overcommit that malloc can silently SUCCEED (just reserving
# virtual address space), masking the bug -- confirmed empirically against
# this exact allocation size on this repo's dev host. Capping virtual memory
# well below ~32 GB forces malloc to fail exactly as it would on a real
# memory-constrained mobile device, so a regression that removes the guard
# will reliably crash this KAT (SIGSEGV, non-zero exit) instead of silently
# passing on a beefy CI runner.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
#
# Exit code 0 = all checks passed, 1 = at least one check failed, crashed
# (e.g. SIGSEGV from a reintroduced unguarded malloc), or build error.
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

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    -I "$BRIDGE_DIR" \
    "$SCRIPT_DIR/saved_blocks_kat_main.c" \
    "$CORE_DIR/BRMerkleBlock.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm \
    -o "$BUILD_DIR/saved_blocks_kat"

# 200 MB virtual-memory ceiling: comfortably above what this KAT's own
# well-formed-path allocations need (a handful of bytes), comfortably below
# the ~32 GB a corrupt 0xFFFFFFFF count would request.
( ulimit -v 200000; "$BUILD_DIR/saved_blocks_kat" )
