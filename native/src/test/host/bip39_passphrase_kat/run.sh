#!/usr/bin/env bash
# Host KAT runner for the BIP39 passphrase work (12+1 / 24+1).
#
# Compiles the REAL, live submodule BRBIP39Mnemonic.c against the KAT — no shim,
# no copy — so the vectors are checked against the code the app actually ships.
# BRCrypto.c comes along for BRPBKDF2/BRSHA512, and the crypto/* chain because
# BRCrypto.c is one translation unit referencing the DigiByte hash zoo whether
# or not this KAT calls it; static linking must resolve every referenced symbol.
#
# clang with -include stdint.h, matching the other KATs (see bip340_kat/run.sh
# for the crypto/odocrypt.h rationale).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$SCRIPT_DIR/../../../main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/bip39_passphrase_kat_main.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/bip39_passphrase_kat" || exit 1

"$BUILD_DIR/bip39_passphrase_kat"
