#!/usr/bin/env bash
# recover_taproot_kat — Recover funds derives the taproot addresses this wallet hands out (4.1.2 row 34).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
grep -q 'if (addressFormat < 0 || addressFormat > 3) return NULL;' "$BRIDGE_DIR/jni_derive.c" ||
    { echo "GATE FAILURE: deriveAddresses does not accept format 3 (P2TR)"; exit 1; }
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/recover_taproot_kat_main.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRSet.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$BUILD_DIR/kat"
ASAN_OPTIONS="halt_on_error=1 detect_leaks=0" timeout 60 "$BUILD_DIR/kat"
