#!/usr/bin/env bash
# Host KAT runner: a 12-character P2P command, which has no NUL terminator, is never read past its
# 12 bytes by the peer read loop's logs, dispatch or acceptType copy (see the _main.c header).
#
# The main #includes BRPeer.c (same idiom as peer_keepalive_kat), so BRPeer.c is NOT on the clang
# line. -DDEBUG compiles peer_log in as printf: on Android it is always __android_log_print, and
# without DEBUG the host build drops the very calls under test. AddressSanitizer is the check: an over-read of the 24-byte header (stack) or of the [D]
# command buffer (heap) aborts the run. Recorded red against core 91000fd: [A] faults with a
# stack-buffer-overflow READ past `header` in _peerThreadRoutine.
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -DDEBUG \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    -I "$CORE_DIR/secp256k1" \
    "$SCRIPT_DIR/peer_command_field_kat_main.c" \
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
    -o "$BUILD_DIR/peer_command_field_kat"

ASAN_OPTIONS="abort_on_error=1 detect_leaks=0" timeout 120 "$BUILD_DIR/peer_command_field_kat"
