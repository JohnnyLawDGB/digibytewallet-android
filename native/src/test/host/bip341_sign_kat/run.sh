#!/usr/bin/env bash
# Host KAT runner for the tap-tweaked BIP-341 key-path Schnorr signer
# (BRKeyTaprootSchnorrSign) added to BRKey.c/BRKey.h in Taproot Sign-Task 2.
#
# Same real-file compile approach as the bip340_kat runner (see its header for
# the full rationale): compiles the REAL, live submodule files directly out of
# the tree (no shim headers, no scratch-dir copy). BRKeyTaprootSchnorrSign needs
# the actual vendored secp256k1 amalgamation (extrakeys + schnorrsig modules)
# and the actual BRSHA256 behind BRKeyTaggedHash, so there is nothing to shim.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- both for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh (file-scope
# `const static int` array bounds GCC rejects; missing <stdint.h> include).
# The real Android build uses the NDK's clang, so this stays representative.
#
# Why BRCrypto.c/BRBase58.c/BRBech32.c/crypto/* must all be linked at all even
# though this KAT only calls a handful of their functions: BRKey.c is one
# translation unit containing many functions besides BRKeyTaprootSchnorrSign
# (BRKeySetPrivKey, BRKeyAddress, BRKeySegwitAddress, BRKeyHash160, ...) that
# reference BRBase58CheckDecode/BRBech32Encode/BRSHA256/BRHash160 etc. Static
# linking must resolve every symbol BRKey.o references, not just the ones this
# KAT's main() actually calls -- so the whole real dependency chain (down
# through crypto/groestl.c, crypto/skein.c, crypto/qubit.c, crypto/odocrypt.c,
# and the X11/sph_* helpers under crypto/sha3/) has to be linked in.
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error).
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
    "$SCRIPT_DIR/bip341_sign_kat_main.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/bip341_sign_kat"

"$BUILD_DIR/bip341_sign_kat"
