#!/usr/bin/env bash
# Host KAT runner: an implicit-change asset output must be keepable out of the spendable
# DGB set. Compiles the REAL, live submodule files with AddressSanitizer, same real-file
# approach as dd_unconfirmed_credit_kat/run.sh.
#
# RED before the fix: BRWalletRegisterAssetOutpoint does not exist, so the KAT does not
# build — and the "before registration" checks document that the output is spendable DGB
# today. GREEN after: the outpoint is excluded, the exclusion survives a balance rebuild,
# and BRWalletCreateTransaction can no longer reach it.
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

clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/asset_implicit_change_kat_main.c" \
    "$CORE_DIR/BRWallet.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm \
    -o "$BUILD_DIR/asset_implicit_change_kat"

ASAN_OPTIONS=abort_on_error=1:detect_leaks=0 "$BUILD_DIR/asset_implicit_change_kat"
