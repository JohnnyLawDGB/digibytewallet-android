#!/usr/bin/env bash
# Host KAT runner: the DigiDollar transfer builder's per-call working sets are never charged to the
# caller's stack, and each one is released exactly once.
#
# WHAT IT PROVES. The builder needs a working set per call whose size comes from a count it reads out
# of the wallet -- its DigiDollar coins, and the plain DGB coins available to pay the fee. The
# invariant: that size is taken from the heap with a multiply checked for wrap, it is never charged to
# the caller's stack, and it is released exactly once on whatever path the call returns by. Each arm
# runs the builder on a pthread with a fixed stack size while the wallet holds a different number of
# plain coins, and asserts the outcome that arm must have. AddressSanitizer and LeakSanitizer are
# enabled below (detect_leaks=1) and a report from either fails the run. Single-arm KAT: the invariant
# is a property of the shipped function, so there is no -D seam.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/dd_selection_bounds_kat_main.c" \
    "$CORE_DIR/BRWallet.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lpthread -lm \
    -o "$BUILD_DIR/dd_selection_bounds_kat" || { echo "FAIL: build error"; exit 1; }

# detect_leaks on: the builder must release its heap work arrays on every return path.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=1" "$BUILD_DIR/dd_selection_bounds_kat"
