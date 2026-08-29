#!/usr/bin/env bash
# Derives addresses for any derivation profile, using the live C submodule.
#
# Lives in native/tools/, NOT native/src/test/host/. It was under the KAT directory at first, on
# the assumption that omitting the _kat suffix kept the runner away from it. That was wrong:
# run-host-kats.sh globs every directory there and executes any run.sh it finds, so this tool ran
# with no arguments, printed its usage, exited non-zero and failed CI as if a known-answer test
# had regressed. A tool is not a test and does not belong among them.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
clang -w -include stdint.h -g -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/bread_wallet_addresses_main.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm \
    -o "$BUILD_DIR/bread_addr"
"$BUILD_DIR/bread_addr" "$@"
