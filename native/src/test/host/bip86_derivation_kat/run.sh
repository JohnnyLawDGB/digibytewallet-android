#!/usr/bin/env bash
# Host KAT runner for the BIP-86 account master-pubkey derivation twin
# (BRBIP32MasterPubKeyBIP86) added to BRBIP32Sequence.c/BRBIP32Sequence.h in
# Taproot Plan-2 Task 1.
#
# Same real-file compile approach as Plan-1's taproot_addr_kat/bip340_kat
# (see those run.sh's comments for the full rationale) -- this compiles the
# REAL, live submodule files directly out of the tree (no shim headers, no
# scratch-dir copy), because BRBIP32MasterPubKeyBIP86 needs the actual
# BRHMAC/BRSHA512/_CKDpriv machinery and BRKeyTaprootAddress (Plan-1 Task 4)
# needs the actual vendored secp256k1 amalgamation, and
# BRAddressFromScriptPubKey needs the actual BRBech32Encode/BRScriptElements.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- both for the same
# crypto/odocrypt.h reasons documented in bip340_kat/run.sh.
#
# Why BRAddress.c/BRCrypto.c/BRBase58.c/BRBech32.c/crypto/* must all be
# linked at all even though this KAT only calls a handful of their functions:
# BRBIP32Sequence.c and BRKey.c each are one translation unit containing many
# functions besides the ones this KAT calls directly, and those other
# functions reference symbols across the whole dependency chain (down
# through crypto/groestl.c, crypto/skein.c, crypto/qubit.c, crypto/odocrypt.c,
# and the X11/sph_* helpers under crypto/sha3/). Static linking must resolve
# every symbol each .o file references, not just the ones main() calls.
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
    "$SCRIPT_DIR/bip86_derivation_kat_main.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/bip86_derivation_kat"

"$BUILD_DIR/bip86_derivation_kat"
