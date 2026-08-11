#!/usr/bin/env bash
# Host KAT runner for Task 6 of the cfcheckpt-active-rejection plan: NEVER-BRICK
# recovery once the continuity re-anchor budget (CF_CONTINUITY_REANCHOR_MAX==3)
# is truly exhausted. See cf_checkpoint_neverbrick_kat_main.c's file header for
# the full mechanics, the "which cursor field actually governs resume" judgment
# call, and why no --wrap seam is needed here (unlike cf_checkpoint_veto_kat/
# cf_checkpoint_quorum_kat's checkpoint-confirmed veto test).
#
# cf_checkpoint_neverbrick_kat_main.c #include-s BOTH BRPeer.c (for the private
# BRPeerContext -- there is no public setter for connect status, and the
# quorum path's majority denominator, _BRPeerManagerConnectedFilterPeerCount,
# gates on BRPeerConnectStatus(p)==Connected) AND BRPeerManager.c (for the
# exhaustion decision itself and the otherwise-opaque BRPeerManagerStruct /
# BRPeerCallbackInfo definitions) directly, same #include-a-.c-for-statics
# pattern cf_checkpoint_quorum_kat/cf_checkpoint_veto_kat/cf_checkpoint_enforce_kat/
# cf_confirm_kat/cf_scan_ledger_drive_kat use. BRPeer.c and BRPeerManager.c are
# therefore deliberately NOT ALSO passed as separate compilation units below --
# every symbol they define would otherwise be defined twice and the link would
# fail. (One file-static name, _dummyThreadCleanup, is defined by BOTH files;
# main.c scopes a preprocessor rename around BRPeer.c's #include only.)
#
# Every other BRPeerManager.c dependency IS compiled as a separate unit and
# linked in -- identical file list to cf_checkpoint_quorum_kat/run.sh (this
# KAT needs the same dependency closure: a real BRPeerManager + BRWallet built
# through the same public constructors).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
#
# ==== RED-BEFORE-GREEN GATE =================================================
# main.c runs every test with NO #ifdef branching -- every check() asserts the
# FIXED (never-brick) outcome unconditionally, in source identical between
# builds:
#   RED   -DCF_NEVERBRICK_UNFIXED compiles the entire Task 6 block out of
#         BRPeerManager.c (guarded by #ifndef CF_NEVERBRICK_UNFIXED),
#         restoring the pre-Task-6 shape: the budget-exhaustion branch is a
#         bare log + unlock + return, same as the below-quorum case. Round 4's
#         post-call checks in test_budget_exhausted_parks_at_checkpoint_and_surfaces
#         -- which expect autoFetchCFiltersStart/Through snapped to the
#         trusted checkpoint height and abandonedBelow raised above 0 -- FAIL:
#         the cursor is left wherever round 3's ordinary re-anchor-at-floor
#         left it (the block floor, 24000000), and abandonedBelow silently
#         stays 0 forever. The binary exits nonzero.
#   GREEN the production shape (no flag) must pass every check and exit 0.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/cf_checkpoint_neverbrick_kat_main.c" \
    "$CORE_DIR/BRWallet.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRMerkleBlock.c" \
    "$CORE_DIR/BRCompactFilterChain.c" \
    "$CORE_DIR/BRGCSFilter.c" \
    "$CORE_DIR/BRWalletFilterElements.c" \
    "$CORE_DIR/BRCFScanLedger.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm -lpthread \
    -o "$out"
}

# ── RED: unfixed shape must FAIL the exhaustion-park+surface checks ────────
build "$BUILD_DIR/cf_checkpoint_neverbrick_kat_unfixed" -DCF_NEVERBRICK_UNFIXED

set +e
"$BUILD_DIR/cf_checkpoint_neverbrick_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: -DCF_NEVERBRICK_UNFIXED build exited 0 -- the budget-"
    echo "             exhaustion park+surface must be load-bearing (its"
    echo "             absence must fail the post-round-4 checks). Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: neverbrick: autoFetchCFiltersStart snapped to the TRUSTED checkpoint height (compiled-in table value)" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail at the expected cursor-"
    echo "             snap assertion -- the -D flag is not reaching the"
    echo "             exhaustion decision, so RED is not actually the pre-"
    echo "             fix silent-stop shape."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: neverbrick: abandonedBelow > 0 -- a recoverable band now exists ('Scan for missing transactions' reachable)" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail the abandonedBelow"
    echo "             surfacing check -- the unfixed exhaustion branch must"
    echo "             leave abandonedBelow at 0 (no recover-me banner ever"
    echo "             becomes reachable), and this build didn't reproduce that."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix silent-stop shape left the exhausted-budget"
echo "             mismatch unsurfaced -- cursor NOT snapped to a checkpoint,"
echo "             abandonedBelow stayed 0. Both expected checks failed."

# ── GREEN: the production shape must pass every check ──────────────────────
build "$BUILD_DIR/cf_checkpoint_neverbrick_kat"

"$BUILD_DIR/cf_checkpoint_neverbrick_kat"
