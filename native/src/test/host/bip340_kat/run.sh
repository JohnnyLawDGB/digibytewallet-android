#!/usr/bin/env bash
# Host KAT runner for BIP-340 Schnorr signing (BRKeySchnorrSign) + tagged
# hash (BRKeyTaggedHash) added to BRKey.c/BRKey.h in Taproot Task 3.
#
# Unlike bech32m_kat (Task 2), this compiles the REAL, live submodule files
# directly out of the tree -- no shim headers, no scratch-dir copy.
# BRKeySchnorrSign needs the actual BRSHA256 + the actual vendored
# secp256k1 amalgamation, not a stand-in, so there's nothing to shim away:
# BRKey.c / BRCrypto.c / BRBase58.c / BRBech32.c / crypto/* are all pure C
# with zero Android/JNI dependency (same fact Task 1's full-BRKey.c host KAT
# and Task 2's BRBech32.c-only KAT already established for their respective
# slices of this file tree).
#
# Compiler: clang, NOT gcc. crypto/odocrypt.h declares array bounds via
# file-scope `const static int` (not `#define`), which is not a valid ISO C
# constant-expression. GCC 13 (-std=c11 and default gnu17 alike) rejects it
# outright ("variably modified ... at file scope"). Clang 18 accepts it
# (probably the same const-fold Clang's frontend has always done for this
# pattern). The real Android build uses the NDK's clang, so building this
# KAT with clang instead of gcc keeps it representative of what actually
# ships, rather than requiring an unrelated fix to a vendored DigiByte X11
# hashing header that is out of scope for this task.
#
# `-include stdint.h`: crypto/odocrypt.h uses uint32_t/uint16_t/uint64_t
# without including <stdint.h> itself. On the real NDK build those typedefs
# arrive transitively from a header included earlier in the compile; on a
# bare host compile there's no such earlier header, so it's forced in
# explicitly here rather than patching the vendored header (out of scope).
#
# Why BRCrypto.c/BRBase58.c/BRBech32.c/crypto/* must be linked at all even
# though the KAT never calls their functions: BRKey.c is one translation
# unit containing many functions besides BRKeySchnorrSign/BRKeyTaggedHash
# (BRKeySetPrivKey, BRKeyAddress, BRKeySegwitAddress, BRKeyHash160, ...) that
# reference BRBase58CheckDecode/BRBech32Encode/BRSHA256/BRHash160 etc. Static
# linking must resolve every symbol BRKey.o references, not just the ones
# this KAT's main() actually calls -- so the whole real dependency chain
# (down through crypto/groestl.c, crypto/skein.c, crypto/qubit.c,
# crypto/odocrypt.c, and the X11/sph_* helpers under crypto/sha3/) has to be
# linked in, even though none of those hash algorithms are exercised by this
# particular KAT.
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
    "$SCRIPT_DIR/bip340_kat_main.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/bip340_kat"

"$BUILD_DIR/bip340_kat"
