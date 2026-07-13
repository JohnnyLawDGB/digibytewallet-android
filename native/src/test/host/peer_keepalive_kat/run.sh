#!/usr/bin/env bash
# Host KAT runner for ANR fix #2 (native peer-manager keepalive lock-starvation,
# .superpowers/sdd/anr-fix2-native-design.md).
#
# The KAT main #includes BRPeer.c directly (the same amalgamation idiom BRKey.c
# already uses for secp256k1), so BRPeer.c must NOT also be passed as a separate
# translation unit here -- that would duplicate every symbol it defines. Every
# other .c file BRPeer.c calls into (transitively, via BRMerkleBlock/BRTransaction)
# does need to be compiled and linked, same as the other "real submodule files"
# KATs in this tree (legacy_gap_uaf_kat, digidollar_wallet_kat).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- for the same
# crypto/odocrypt.h array-bound-via-macro reason documented in the other KATs
# (bip340_kat/run.sh has the long version of this note). The real Android build
# uses the NDK's clang, so this stays representative.
#
# Exit code 0 = all checks passed, 1 = check failed / build error (e.g. the RED
# state before BRPeerSendPingProbe / lastRecvTime / KEEPALIVE_* exist).
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
    -I "$CORE_DIR/secp256k1" \
    "$SCRIPT_DIR/peer_keepalive_kat_main.c" \
    "$CORE_DIR/BRMerkleBlock.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lpthread -lm \
    -o "$BUILD_DIR/peer_keepalive_kat"

"$BUILD_DIR/peer_keepalive_kat"
