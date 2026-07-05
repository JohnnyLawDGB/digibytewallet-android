#!/usr/bin/env bash
# Golden-vector KAT: BRDigiDollarAddressDecode (TD/DD Base58Check -> 32-byte taproot key)
# (send Task 1), plus BRWalletCreateDigiDollarTransfer + BRDigiDollarWriteScriptNum
# (send Task 2). Task 2 needs a real BRWallet, so BRWallet.c is linked in here
# (Task 1's decoder-only test didn't require it).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
clang -w -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/digidollar_send_kat_main.c" \
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm \
    -o "$BUILD_DIR/digidollar_send_kat"
"$BUILD_DIR/digidollar_send_kat"
