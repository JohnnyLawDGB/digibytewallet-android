#!/usr/bin/env bash
# Host KAT runner for the DigiDollar wallet-wiring integration (DD Wallet-Wiring
# Task 1): detection + cents-balance accumulation + spent-prune in
# _BRWalletUpdateBalance (BRWallet.c), consuming BRDigiDollarOutputAmount
# (BRDigiDollar.c).
#
# Same real-file compile approach as the other taproot/digidollar host KATs
# (see e.g. bip341_signtx_kat/run.sh for the full rationale): compiles the
# REAL, live submodule files directly out of the tree (no shim headers, no
# scratch-dir copy).
#
# Unlike bip341_signtx_kat_main.c, this KAT's main.c does NOT
# `#include "BRTransaction.c"` (no need to reach a file-static symbol here),
# so BRTransaction.c must be passed on the clang line explicitly like any
# other translation unit -- otherwise BRTransactionNew/AddInput/AddOutput
# etc. are undefined at link time. BRDigiDollar.c is also added explicitly to
# supply BRDigiDollarOutputAmount, which BRWallet.c's balance-update path now
# calls.
#
# Why so many .c files must be linked even though main() calls only a handful:
# each translation unit (BRWallet.c, BRKey.c, BRBIP32Sequence.c, ...) contains
# many functions besides the ones this KAT calls, and those reference symbols
# across the whole dependency chain (BRSet, BRAddress, BRBech32, BRBase58,
# BRBIP39Mnemonic, BRDigiAsset, BRDigiDollar, and crypto/* down through
# groestl/skein/qubit/odocrypt and the X11/sph_* helpers under
# crypto/sha3/). Static linking must resolve every symbol each .o references,
# not just the ones main() invokes.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh.
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build error).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/digidollar_wallet_kat_main.c" \
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
    -o "$BUILD_DIR/digidollar_wallet_kat"

"$BUILD_DIR/digidollar_wallet_kat"
