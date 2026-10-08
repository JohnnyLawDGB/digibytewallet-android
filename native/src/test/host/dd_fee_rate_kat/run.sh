#!/usr/bin/env bash
# Host KAT runner: a DigiDollar transfer pays the fee its confirmation shows (the 0.1 DGB floor,
# plus at most a sub-dust DGB change), whatever fee rate an earlier DGB build left on the wallet.
#
# [1/2] dd_fee_rate_kat_main.c: the live builder against a grid of funding shapes x left-over rates,
#       with the pre-fix reference builder (dd_coin_selection_kat/base_builder.c, which read the
#       wallet's rate) as the red arm -- it must break the bound, or the KAT fails. BRWallet.c and
#       base_builder.c are #included by the main, so neither is on the compiler line.
#
# [2/2] SOURCE GATE. jni_transaction.c includes Android headers and cannot be compiled on the host,
#       so this scans Java_io_digibyte_core_bridge_NativeBridge_createTransaction and fails unless it builds through
#       BRWalletCreateTransactionAtFeePerKb and never calls BRWalletSetFeePerKb: the custom rate is
#       a parameter of that one build, not wallet state that outlives it or that a concurrent build
#       or feefilter update could change mid-build. DD_FEE_GATE_FILE=<path> points the gate at
#       another copy (used to show it refuses the older shapes).
#
# Exit code 0 = all checks passed, 1 = a check failed / sanitizer fault / build error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
JNI="${DD_FEE_GATE_FILE:-$REPO_ROOT/native/src/main/jni/bridge/jni_transaction.c}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

echo "[1/2] the transfer's fee against left-over wallet rates"
clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
    -I "$SCRIPT_DIR/../dd_coin_selection_kat" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/dd_fee_rate_kat_main.c" \
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
    -o "$BUILD_DIR/dd_fee_rate_kat" || { echo "FAIL: build error"; exit 1; }

# LeakSanitizer ON: every wallet and transaction the main makes is released.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=1" "$BUILD_DIR/dd_fee_rate_kat"
kat=$?

echo
echo "[2/2] source gate: createTransaction passes the rate to the builder ($JNI)"
awk '/^Java_io_digibyte_core_bridge_NativeBridge_createTransaction\(/{p=1} p{print} p&&/^}/{exit}' "$JNI" \
    | sed 's@/\*.*\*/@@g; s@//.*$@@' > "$BUILD_DIR/create.c"
gate=0
if [ ! -s "$BUILD_DIR/create.c" ]; then
    echo "  FAIL could not locate NativeBridge_createTransaction"
    gate=1
else
    if grep -q 'BRWalletCreateTransactionAtFeePerKb(' "$BUILD_DIR/create.c"; then
        echo "  ok   the build is made with the rate as a parameter"
    else
        echo "  FAIL the build does not go through BRWalletCreateTransactionAtFeePerKb"
        gate=1
    fi
    if grep -q 'BRWalletSetFeePerKb(' "$BUILD_DIR/create.c"; then
        echo "  FAIL createTransaction sets the wallet's rate"
        gate=1
    else
        echo "  ok   the wallet's rate is never set"
    fi
fi

[ "$kat" -eq 0 ] && [ "$gate" -eq 0 ] && exit 0
exit 1
