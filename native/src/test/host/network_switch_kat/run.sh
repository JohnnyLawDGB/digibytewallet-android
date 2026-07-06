#!/usr/bin/env bash
# Host KAT runner for the runtime network toggle (BRSetNetwork /
# BRNetworkIsTestnet, added in Task 1 of the network-switch plan) as
# consumed by the address/key/difficulty call sites converted from
# compile-time `#if BITCOIN_TESTNET` to runtime checks in Task 2
# (.superpowers/sdd/task-2-brief.md): BRAddress.c/.h, BRKey.c,
# BRMerkleBlock.c.
#
# Same real-file compile approach as taproot_addr_kat (see that run.sh's
# comments for the full rationale) -- this compiles the REAL, live
# submodule files directly out of the tree (no shim headers, no scratch-dir
# copy), because BRKeyAddress/BRKeySegwitAddress/BRAddressIsValid/etc. need
# the actual BRBase58/BRBech32/secp256k1 machinery, not a stand-in.
#
# BRNetwork.c is linked in because BRAddress.c and BRKey.c now call
# BRNetworkIsTestnet() at their converted call sites -- without it this
# would fail to link with "undefined reference to BRNetworkIsTestnet".
#
# BRMerkleBlock.c is linked in for the BRMerkleBlockVerifyDifficulty
# testnet-skip check; it pulls in BRCrypto.c (BRSHA256_2) same as the
# DigiDollar/BIP341 KATs that also link BRMerkleBlock-adjacent code.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- both for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh (file-scope
# `const static int` array bounds GCC rejects; missing <stdint.h> include).
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
    "$SCRIPT_DIR/network_switch_kat_main.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRMerkleBlock.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/network_switch_kat"

"$BUILD_DIR/network_switch_kat"
