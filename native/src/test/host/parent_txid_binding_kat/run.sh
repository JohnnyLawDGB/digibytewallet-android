#!/usr/bin/env bash
# Host KAT runner: the id a fetched parent transaction is checked against, and the
# distinct-input check of the asset send. Compiles bridge/asset_tx_checks.h -- the header the
# JNI accessor and the asset-send builder call -- against the REAL, live core parser, with
# AddressSanitizer and UndefinedBehaviorSanitizer, in a 64-bit and a 32-bit build.
#
# Exit code 0 = all checks passed, 1 = check failed / sanitizer report / build error.
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

UNITS=(
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build_and_run() {
    local bits="$1"
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    echo "=== ${bits}-bit ==="
    # The suite and the header under test build with warnings as errors; the core sources
    # build as the app builds them.
    clang -Wall -Wextra -Werror -include stdint.h -g -fsanitize=address,undefined \
        -fno-omit-frame-pointer "${m[@]}" \
        -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        -c "$SCRIPT_DIR/parent_txid_binding_kat_main.c" -o "$BUILD_DIR/main${bits}.o"
    clang -w -include stdint.h -g -fsanitize=address,undefined -fno-omit-frame-pointer \
        "${m[@]}" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$BUILD_DIR/main${bits}.o" "${UNITS[@]}" -lpthread -lm \
        -o "$BUILD_DIR/parent_txid_binding_kat${bits}"
    ASAN_OPTIONS=abort_on_error=1:detect_leaks=0 UBSAN_OPTIONS=halt_on_error=1 \
        "$BUILD_DIR/parent_txid_binding_kat${bits}"
}

build_and_run 64
build_and_run 32
