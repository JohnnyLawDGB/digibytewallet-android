#!/usr/bin/env bash
# Host KAT runner: BIP158 filter-element construction.
# Compiles the REAL, live submodule files directly out of the tree with AddressSanitizer,
# same real-file approach as watched_addr_kat/run.sh.
#
# Pins the DigiDollar element alias (a DD payment's filter element is emitted by
# taprootExternalChain[0], not by the DD address string), asserts the element count is
# non-decreasing as the address set grows, and checks the build-stats snapshot balances.
#
# NOTE: this is the only working KAT that compiles BRWalletFilterElements.c —
# cf_confirm_kat also lists it but has not built since BRBloomFilter.c was deleted in the
# v4.0.0 bloom excision.
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
    "$SCRIPT_DIR/filter_elements_kat_main.c" \
    "$CORE_DIR/BRWallet.c" \
    "$CORE_DIR/BRWalletFilterElements.c" \
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
    -o "$BUILD_DIR/filter_elements_kat"

ASAN_OPTIONS=abort_on_error=1:detect_leaks=0 "$BUILD_DIR/filter_elements_kat"
