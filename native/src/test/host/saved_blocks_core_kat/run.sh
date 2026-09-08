#!/usr/bin/env bash
# Host KAT runner for BRSavedBlocks.h -- parsing the persisted saved-blocks blob.
#
# Supersedes saved_blocks_kat's guard coverage. That one proved the count guard
# by running under `ulimit -v` so an absurd allocation would fail; macOS does
# not implement a virtual-memory ceiling, so it has been failing here for that
# reason alone. This intercepts malloc instead, which is portable AND a stronger
# claim: the absurd size is never REQUESTED, not merely rejected when it is.
#
# The interception uses the repo's per-TU -D seam (core 68abf333) rather than
# GNU ld's --wrap, which Apple's ld64 does not implement: -Dmalloc=__wrap_malloc
# renames this TU's call sites -- including the ones inside the header-only
# function under test -- while the real definition keeps its name and is reached
# through an asm label.
#
#   RED    -DSAVED_BLOCKS_COUNT_UNGUARDED restores the pre-fix code, which sized
#          an allocation straight from a corruption-supplied 32-bit count. That
#          build MUST fail at test3's "never reaches malloc" check -- the
#          boot-loop case.
#   GREEN  the production shape must pass every check.
#
# Exit 0 = all checks passed; 1 = a check failed or the gate misbehaved.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
command -v "$CC" >/dev/null 2>&1 || CC=cc

shopt -s nullglob
CRYPTO_SRCS=("$CORE_DIR"/crypto/*.c "$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# The real BRMerkleBlockParse, not a stand-in -- the whole point is that the
# blob a production wallet wrote parses back. BRAddress.c supplies BRVarInt*
# (called from BRMerkleBlock.c) and drags Base58/Bech32; BRNetwork.c supplies
# BRNetworkIsTestnet; BRCrypto.c + crypto/* supply BRSHA256_2 and the multi-algo
# dispatch used to compute blockHash on every parse.
DEPS=(
    "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRCrypto.c"
    "${CRYPTO_SRCS[@]}"
)
# Object names are path-mangled, not basenamed: crypto/groestl.c and
# crypto/sha3/groestl.c share a basename (so do the two skein.c), and a
# basename collision silently drops one of each -- the link then fails on
# groestl_hash and skein_hash with no hint as to why.
mkdir -p "$BUILD_DIR/obj"
for src in "${DEPS[@]}"; do
    "$CC" -w -std=c99 -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" -c "$src" -o "$BUILD_DIR/obj/$(echo "${src#$CORE_DIR/}" | tr '/' '_').o"
done

build() {
    local out="$1"; shift
    # -Dmalloc=__wrap_malloc applies to THIS TU only, which is what makes the
    # header-only function's allocation observable without touching the core.
    # -Wno-missing-braces: BRInt.h's compound literals trip it under gcc.
    "$CC" -std=c99 -Wall -Wextra -Werror -Wno-missing-braces \
        -Dmalloc=__wrap_malloc "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        "$SCRIPT_DIR/saved_blocks_core_kat_main.c" "$BUILD_DIR/obj"/*.o \
        -o "$out"
}

build "$BUILD_DIR/kat_unguarded" -DSAVED_BLOCKS_COUNT_UNGUARDED
set +e
ASAN_OPTIONS=detect_leaks=0 "$BUILD_DIR/kat_unguarded" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the unguarded count passed. The cap is not load-bearing --"
    echo "             the KAT would pass either way."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test3: an absurd count never reaches malloc" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the absurd-count checkpoint --"
    echo "             so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: an unguarded count would have asked malloc for ~34 GB on every launch."

build "$BUILD_DIR/saved_blocks_core_kat"
ASAN_OPTIONS=detect_leaks=0 "$BUILD_DIR/saved_blocks_core_kat"
