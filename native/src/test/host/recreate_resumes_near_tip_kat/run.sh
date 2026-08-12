#!/usr/bin/env bash
# Host KAT runner for recreate_resumes_near_tip_kat -- Task 3 of the
# recreate-resumes-near-tip plan
# (.superpowers/sdd/2026-08-11-recreate-resumes-near-tip/task-3-brief.md).
#
# THE BEHAVIORAL GATE for the whole plan: an in-process peer-manager recreate at
# height H must resume at ~H on BOTH the block side (BRPeerManagerLastBlockHeight)
# and the CF side (BRPeerManagerLowestNeededHeight, the documented scan frontier)
# -- not collapse to the wallet's birth checkpoint / cf_birth_height, which is
# what a Galaxy Note 8 reproduced: 23,060,000 -> 22,690,000 with the scan
# frontier reset to 22,657,000 and "BIP158: recreate -- re-armed auto-fetch from
# remembered start 22650000" in logcat.
#
# recreate_resumes_near_tip_kat_main.c #include-s BRPeerManager.c directly (to
# reach the file-static internals and the otherwise-opaque
# BRPeerManagerStruct/BRCFScanLedger definitions), the same pattern
# cf_scan_ledger_drive_kat/run.sh, cf_checkpoint_enforce_kat/run.sh and
# cf_confirm_kat/run.sh use. BRPeerManager.c is therefore deliberately NOT ALSO
# passed as a separate compilation unit below -- every symbol it defines would be
# defined twice and the link would fail. Every other dependency IS compiled
# separately (same file list as cf_checkpoint_enforce_kat: a real BRPeerManager
# over a real BRWallet needs the whole address/key/crypto closure).
#
# The manager rebuild, the CF re-arm, the checkpoint table, the ledger
# serialize/restore and the cursor snap are all the REAL core functions. Only
# bridge/jni_peer.c's orchestration is MIRRORED, because jni_peer.c pulls in
# <jni.h>/<android/log.h> and cannot be host-compiled -- the same documented
# trade-off as saveblocks_race_kat and the sibling saved_blocks_reentrant_kat
# (Task 1 of this plan). The _main.c file header lists exactly which pieces are
# real and which are mirrored.
#
# ==== RED-BEFORE-GREEN GATE =================================================
# main.c's assertions are UNCONDITIONAL and assert the FIXED outcome; the source
# is byte-identical between builds (same discipline as cf_checkpoint_enforce_kat).
# Only the mirrored orchestration branches:
#   RED   -DRECREATE_NEARTIP_UNFIXED restores the shipped pre-fix recovery
#         sequence -- bare forceReconnect() + startSync() with g_savedBlocks
#         still NULLed by the previous ownership transfer, and no CF ledger
#         restore/snap. It MUST fail the block-side AND the CF-side assertions,
#         and MUST do so by landing exactly on the birth checkpoint /
#         cf_birth_height (the COLLAPSE-CONFIRMED lines below).
#   GREEN the fixed sequence (Task 2's reloadSavedBlocksNearTip before startSync
#         + restoreCfLedgerAndSnap after it) must pass every check and exit 0.
#
# A merely non-zero RED exit is NOT accepted -- that is also what a build error,
# a crash before the first test, or an unrelated assertion failure looks like, and
# a gate that can go red for the wrong reason proves nothing. This runner
# therefore separately requires: the build succeeded; the run reached the fixture
# line; the arm-independent controls still PASSED (harness sanity); the two
# specific FAIL lines are present; both COLLAPSE-CONFIRMED lines are present
# (proving the -D flag actually reached the mirrored orchestration and produced
# the OBSERVED collapse, not some other failure); and ALL PASS is absent.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh. -fsanitize=address with
# detect_leaks=0: this gate is about heights, and BRPeerManagerFree's known
# residual (blocks resident at teardown are not all freed -- see the
# "KNOWN RESIDUAL" note at jni_peer.c:885-889) would otherwise report a leak
# that has nothing to do with the property under test.
set -uo pipefail   # deliberately NOT -e: the RED arm's non-zero exit is expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() { # $1=output  $2...=extra clang flags (e.g. -DRECREATE_NEARTIP_UNFIXED)
    local out="$1"; shift
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/recreate_resumes_near_tip_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
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

export ASAN_OPTIONS="detect_leaks=0 symbolize=0"

RED_BLOCK_FAIL="FAIL: block side: recreate resumed at the near-tip snapshot's top (lastBlockHeight == H)"
RED_CF_FAIL="FAIL: CF side: scan frontier resumed at the persisted near-tip frontier"

# ── RED: the shipped pre-fix recovery sequence must collapse to birth ──────
echo "=== red-before-green [1/2]: -DRECREATE_NEARTIP_UNFIXED must FLOOR to birth ==="
if ! build "$BUILD_DIR/unfixed" -DRECREATE_NEARTIP_UNFIXED; then
    echo "GATE FAILED: unfixed build error (a build failure must never read as RED)"
    exit 1
fi
"$BUILD_DIR/unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_EXIT=$?

if [ "$RED_EXIT" -eq 0 ]; then
    echo "GATE FAILED: the pre-fix (bare forceReconnect + startSync) sequence exited 0 --"
    echo "             it did NOT collapse, so this gate cannot detect the bug. Output:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "^FIXTURE> " "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed run never reached the fixture line -- harness broken,"
    echo "             not a real RED. Output:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if grep -q "ALL PASS" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed run printed ALL PASS -- the -D flag is not reaching the"
    echo "             mirrored orchestration, so RED is not the pre-fix shape."
    exit 1
fi
if grep -q "^FAIL: control: " "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: an arm-INDEPENDENT control failed on the unfixed build -- the harness"
    echo "             itself is broken, so the RED tells us nothing about the bug. Output:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -qF "$RED_BLOCK_FAIL" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed build did not fail the BLOCK-side resume assertion."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -qF "$RED_CF_FAIL" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed build did not fail the CF-side resume assertion -- a gate"
    echo "             that covers only the block half would let the CF half of the bug ship."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "^COLLAPSE-CONFIRMED> block: lastBlockHeight floored to the birth checkpoint " "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the block side failed but did NOT land on the birth checkpoint -- the"
    echo "             RED is some other regression, not the observed collapse."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "^COLLAPSE-CONFIRMED> cf: scan frontier floored to cf_birth_height " "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the CF side failed but did NOT land on cf_birth_height -- the RED is"
    echo "             some other regression, not the observed collapse."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED confirmed: pre-fix recreate floored BOTH halves to the wallet's birth region:"
grep -E "^(FIXTURE|RECREATE-RESULT|COLLAPSE-CONFIRMED|LOGI)> " "$BUILD_DIR/red.log" | sed 's/^/    | /'

# ── GREEN: the fixed recovery sequence must resume near-tip ────────────────
echo "=== red-before-green [2/2]: the fixed sequence must resume near-tip ==="
if ! build "$BUILD_DIR/fixed"; then
    echo "GATE FAILED: fixed-shape build error"
    exit 1
fi
"$BUILD_DIR/fixed" > "$BUILD_DIR/green.log" 2>&1
GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "GATE FAILED: the fixed sequence did not pass (exit $GREEN_EXIT):"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if ! grep -q "ALL PASS" "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: the fixed sequence did not print ALL PASS:"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if grep -q "^COLLAPSE-CONFIRMED> " "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: the fixed sequence still reported a collapse:"
    grep "^COLLAPSE-CONFIRMED> " "$BUILD_DIR/green.log" | sed 's/^/             | /'
    exit 1
fi
echo "GREEN confirmed:"
grep -E "^(PASS|FAIL|FIXTURE|RECREATE-RESULT|LOGI|WLOG)[:>] " "$BUILD_DIR/green.log" | sed 's/^/    | /'

echo "recreate_resumes_near_tip_kat: PASS (RED floors to birth on both halves, GREEN resumes near-tip)"
exit 0
