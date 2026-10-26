#!/usr/bin/env bash
# Host KAT runner: an unsigned spend of a wallet coin never counts, whatever order or route it arrives
# by, and checking a peer's transaction cannot exhaust a peer thread's stack (see the _main.c header).
# The main uses only the wallet's public API; UNSIGNED_SPEND_CORE_DIR=<core checkout> builds it against
# another core (recorded red against ee48b70).
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${UNSIGNED_SPEND_CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/unsigned_spend_order_kat_main.c" \
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
    -o "$BUILD_DIR/unsigned_spend_order_kat" || { echo "FAIL: build error"; exit 1; }

# LeakSanitizer OFF: BRWalletFree does not free the non-wallet unconfirmed transactions it keeps in allTx.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=0" "$BUILD_DIR/unsigned_spend_order_kat"
