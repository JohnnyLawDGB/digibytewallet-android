#!/usr/bin/env bash
# Host KAT runner for BRBech32.c bech32m (BIP350) version-awareness.
#
# Compiles the LIVE submodule source (native/src/main/jni/digibytewallet-core/
# BRBech32.c, BRBech32.h) — copied fresh into a scratch build dir on every run
# so this always tests current content, never a stale snapshot — against
# bech32m_kat_main.c and the shim_headers/ stand-ins for BRAddress.h/BRCrypto.h.
#
# Why copy instead of just -I'ing the real digibytewallet-core dir: for
# quote-form #include "X.h", gcc always searches the including file's own
# directory before any -I path. BRBech32.c lives alongside the REAL
# BRAddress.h (which drags in BRCrypto.h -> crypto/odocrypt.h), so if we
# compiled it in place, that heavy header would win over our shim regardless
# of -I order. Copying BRBech32.c+.h into an isolated scratch dir (with no
# BRAddress.h/BRCrypto.h of their own) makes the shim_headers/ -I path the
# only place those two names resolve.
#
# Exit code 0 = pass, 1 = at least one KAT vector failed (or build error).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

cp "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRBech32.h" "$BUILD_DIR/"

gcc -std=c11 -Wall \
    -I "$BUILD_DIR" \
    -I "$SCRIPT_DIR/shim_headers" \
    "$SCRIPT_DIR/bech32m_kat_main.c" \
    "$BUILD_DIR/BRBech32.c" \
    -o "$BUILD_DIR/bech32m_kat"

"$BUILD_DIR/bech32m_kat"
