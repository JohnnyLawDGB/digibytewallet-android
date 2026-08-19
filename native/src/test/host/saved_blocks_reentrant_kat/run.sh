#!/usr/bin/env bash
# Host KAT runner for saved_blocks_reentrant_kat -- Task 1 of the
# recreate-resumes-near-tip plan
# (.superpowers/sdd/2026-08-11-recreate-resumes-near-tip/task-1-brief.md).
#
# Proves the invariant behind the startSync ownership-transfer ->
# loadSavedBlocks re-entrant-free pattern (jni_peer.c:862-891 and :1167-1223):
# once a rebuild hands g_savedBlocks' BRMerkleBlock* elements to
# BRPeerManagerNewEx and the array is freed, g_savedBlocks MUST be NULLed
# (and g_savedBlocksCount zeroed) so a later loadSavedBlocks call's own "free
# any previously loaded blocks" loop is a no-op instead of a double-free on
# manager-owned/freed memory. Same defect class ASan caught on-device
# 2026-08-05 (21 heap-buffer-overflows + 4 heap-use-after-frees, all on
# 192-byte == sizeof(BRMerkleBlock) regions).
#
# saved_blocks_reentrant_kat_main.c mirrors (does not #include) jni_peer.c's
# two call sites, because jni_peer.c pulls in <jni.h>/<android/log.h>, which
# don't exist on a host build -- same reason the sibling saved_blocks_kat
# drives the extracted saved_blocks_deserialize.h core rather than jni_peer.c
# itself (see that KAT's run.sh). See the _main.c file header for the full
# mirroring rationale and the "immediate free" modeling choice.
#
# Two builds:
#   default:                            production shape (NULLs g_savedBlocks
#                                        after the transfer free) -- MUST run
#                                        every check and exit 0.
#   -DSAVED_BLOCKS_REENTRANT_UNFIXED:   omits the NULL-out -- the regression
#                                        this KAT guards against -- MUST die on
#                                        an ASan double-free fault at test3.
# A merely non-zero exit is NOT accepted for the RED arm -- that's also what a
# build/link failure looks like, and a gate that can go red on a compile error
# proves nothing. The red-before-green heuristic, enforced structurally.
#
# Compiler: clang, -include stdint.h (same crypto/odocrypt.h reasons
# documented in bip340_kat/run.sh), -fsanitize=address.
set -uo pipefail   # deliberately NOT -e: the RED arm's non-zero ASan exit is expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() { # $1=output  $2...=extra clang flags (e.g. -DSAVED_BLOCKS_REENTRANT_UNFIXED)
    local out="$1"; shift
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/saved_blocks_reentrant_kat_main.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm \
        -o "$out"
}

# symbolize=0: skip the slow per-invocation llvm-symbolizer -- we only need to
# DETECT the fault ("attempting double-free" prints without symbolization),
# not name the frames. abort_on_error kills fast. detect_leaks=0: this KAT is
# about double-free/UAF, not leaks (test1's blocks are intentionally freed via
# the simulated transfer, not by the caller).
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

echo "=== red-before-green [1/2]: UNFIXED (dangling g_savedBlocks) must FAULT ==="
if ! build "$BUILD_DIR/unfixed" -DSAVED_BLOCKS_REENTRANT_UNFIXED; then
    echo "GATE FAILED: unfixed build error"
    exit 1
fi
"$BUILD_DIR/unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_EXIT=$?
if [ "$RED_EXIT" -eq 0 ]; then
    echo "GATE FAILED: unfixed (dangling-pointer) shape did NOT fault (exit 0)."
    echo "             The gate cannot detect the bug -- worthless. Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "test1: first load: 3 blocks loaded" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: unfixed run didn't even reach test1 -- harness broken, not a real RED."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if grep -q "test3: reentrant load: 2 fresh blocks loaded, no double-free" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: unfixed run survived past test3's assertion -- the -D flag isn't"
    echo "             reaching the omitted NULL-out, so RED isn't the pre-fix shape."
    exit 1
fi
if ! grep -qiE "double-free|heap-use-after-free|AddressSanitizer" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: unfixed shape exited $RED_EXIT but not via an ASan memory error:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED confirmed: unfixed (dangling g_savedBlocks) shape double-frees at the reentrant load:"
grep -iE "ERROR: AddressSanitizer|double-free|#0 " "$BUILD_DIR/red.log" | head -4

echo "=== red-before-green [2/2]: production shape must be CLEAN ==="
if ! build "$BUILD_DIR/fixed"; then
    echo "GATE FAILED: production-shape build error"
    exit 1
fi
"$BUILD_DIR/fixed" > "$BUILD_DIR/green.log" 2>&1
GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "GATE FAILED: production shape faulted (exit $GREEN_EXIT):"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if ! grep -q "ALL PASS" "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: production shape did not print ALL PASS:"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
echo "GREEN confirmed:"
sed 's/^/    | /' "$BUILD_DIR/green.log"

echo "saved_blocks_reentrant_kat: PASS (RED on dangling-pointer regression, GREEN on production shape)"
exit 0
