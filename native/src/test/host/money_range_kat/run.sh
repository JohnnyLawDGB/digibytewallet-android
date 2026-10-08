#!/usr/bin/env bash
# Host KAT runner: the wallet's amounts stay within DigiByte's money range (see the _main.c header).
# BRWallet.c is #included by the main, so it is NOT on the clang line. Recorded red against core
# 91000fd: MAX_MONEY is 21 million DGB, and an out-of-range receive wraps the balance.
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
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
    "$SCRIPT_DIR/money_range_kat_main.c" \
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
    -o "$BUILD_DIR/money_range_kat" || { echo "FAIL: build error"; exit 1; }

# LeakSanitizer OFF: the wallet keeps refused unconfirmed transactions in allTx, as before, and
# BRWalletFree does not free that set's non-wallet members.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=0" "$BUILD_DIR/money_range_kat"
