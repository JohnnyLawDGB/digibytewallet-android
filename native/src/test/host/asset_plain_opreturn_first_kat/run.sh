#!/usr/bin/env bash
# asset_plain_opreturn_first_kat — GUARD: native holds the last output when a plain OP_RETURN precedes the
# DigiAsset carrier (4.1.2 row 13, K5). See the _main.c; the red-then-green gate is Kotlin's AssetPreSpendPassTest.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/asset_plain_opreturn_first_kat_main.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" -lm -lpthread -o "$BUILD_DIR/kat"
ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0" timeout 60 "$BUILD_DIR/kat"
