#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local output="$1"
    shift
    clang -w -include stdint.h "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_header_pacing_kat_main.c" \
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
        -o "$output"
}

build "$BUILD_DIR/pre_relay" -DBRPEER_HEADERS_CONTINUE_BEFORE_RELAY
if "$BUILD_DIR/pre_relay"; then
    echo "GATE FAILURE: the pre-relay continuation build unexpectedly passed"
    exit 1
else
    echo "RED confirmed: deciding before relayedBlock queues a stale-open continuation"
fi

build "$BUILD_DIR/post_relay"
"$BUILD_DIR/post_relay"
