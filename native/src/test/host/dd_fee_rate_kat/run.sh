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
#       so this scans Java_io_digibyte_core_bridge_NativeBridge_createTransaction and fails unless it reads the wallet's rate
#       before it sets the caller's, and sets the saved rate back after BRWalletCreateTransaction
#       with no return in between -- the custom rate is for that one build, success or failure.
#       DD_FEE_GATE_FILE=<path> points the gate at another copy (used to show it refuses the old shape).
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

# LeakSanitizer OFF: BRWalletCreateTransaction never frees the output script BRTxOutputSetAddress
# allocates (BRWallet.c, a core leak of its own -- every DGB build leaks one script), and the
# "prior DGB build" step calls it. AddressSanitizer itself stays on.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=0" "$BUILD_DIR/dd_fee_rate_kat"
kat=$?

echo
echo "[2/2] source gate: createTransaction puts the wallet's rate back ($JNI)"
awk '/^Java_io_digibyte_core_bridge_NativeBridge_createTransaction\(/{p=1} p{print} p&&/^}/{exit}' "$JNI" \
    | sed 's@/\*.*\*/@@g; s@//.*$@@' > "$BUILD_DIR/create.c"
gate=0
if [ ! -s "$BUILD_DIR/create.c" ]; then
    echo "  FAIL could not locate NativeBridge_createTransaction"
    gate=1
else
    read -r save set build restore early <<<"$(awk '
        /BRWalletFeePerKb\(g_wallet\)/ && !save                         { save = NR }
        /BRWalletSetFeePerKb\(g_wallet, *\(uint64_t\) *feePerKb\)/ && !set { set = NR }
        /BRWalletCreateTransaction\(/ && !build                         { build = NR }
        build && NR > build && /BRWalletSetFeePerKb\(g_wallet,/ && !restore { restore = NR }
        set && !restore && /return/                                     { early = NR }
        END { printf "%d %d %d %d %d\n", save, set, build, restore, early }' "$BUILD_DIR/create.c")"
    if [ "$save" -gt 0 ] && [ "$save" -lt "$set" ] && [ "$set" -lt "$build" ] && [ "$build" -lt "$restore" ]; then
        echo "  ok   the rate is read before the caller's is set, and set back after the build"
    else
        echo "  FAIL the rate is not saved before and restored after the build (save=$save set=$set build=$build restore=$restore)"
        gate=1
    fi
    if [ "$early" -eq 0 ]; then
        echo "  ok   no return between setting the caller's rate and restoring the wallet's"
    else
        echo "  FAIL a return at line $early of the function leaves the caller's rate on the wallet"
        gate=1
    fi
fi

[ "$kat" -eq 0 ] && [ "$gate" -eq 0 ] && exit 0
exit 1
