#!/usr/bin/env bash
# Host KAT runner for the CF scan-height COMPLETION GATE (finding C1 — remote receive-hiding).
#
# WHAT IS UNDER TEST
# ------------------
# _peerRelayedBlockTxns (BRPeerManager.c) retires a compact-filter scan height when the full
# block for that height is delivered. Its input — BRPeer.c's `block` message path — is neither
# request-gated (MSG_BLOCK is dispatched unconditionally, and relayedBlockTxns is wired on EVERY
# connected peer) nor commitment-checked (the delivered tx list is never hashed against the
# header's committed merkleRoot). So before this fix ANY dialed peer could erase an outstanding
# hole with an unsolicited block, and the peer actually serving a height could answer our own
# getdata with the real header plus a tx list with the wallet's payment stripped out. Either way
# scannedThrough advances past a height that was never scanned, the ledger is persisted on the
# spot, nothing re-requests it, and the receive is invisible until a manual rescan.
#
# The fix is two gates, both in the completion decision only (the tx-CONFIRMATION half of the
# handler is deliberately untouched — CRUX-A asserts that):
#   1. a manager-owned, bounded, manager-INLINE solicitation table recorded at the two getdata
#      dispatch sites that follow a VERIFIED cfilter match (_peerRelayedCFilter and _cfBufEval)
#   2. BRMerkleRootFromTxHashes (BRMerkleBlock.c) recomputed over the delivered txids and
#      compared against the resident header's committed merkleRoot
#
# cf_block_completion_gate_kat_main.c #include-s BRPeerManager.c directly to reach the
# file-static _peerRelayedBlockTxns / _peerRelayedCFilter and the otherwise-opaque
# BRPeerManagerStruct / BRPeerCallbackInfo / BRCFSolicitedBlock definitions — the same
# #include-a-.c-for-statics pattern cf_confirm_kat and cf_scan_ledger_drive_kat use, and the
# reason BRPeerManager.c is NOT also passed as a separate compilation unit below (every symbol
# it defines would be defined twice and the link would fail). Every OTHER dependency IS compiled
# and linked separately.
#
# --wrap seam: CRUX-D drives the real _peerRelayedCFilter, which dispatches through BRPeer.c's
# public BRPeerSendGetdataBlocks. That one call is intercepted at link time and redirected to
# the __wrap_ shim in main.c, so the KAT touches no socket and "a getdata really was put on the
# wire for this hash" is directly observable. Requires GNU ld's `--wrap` (Linux/clang CI).
#
# ASan is compiled in with LeakSanitizer LIVE (no detect_leaks=0): every case ends by calling
# BRPeerManagerFree(m), so the fixtures must be leak-clean too.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` — same crypto/odocrypt.h reasons documented
# in bip340_kat/run.sh.
#
# ==== RED-BEFORE-GREEN GATE =================================================================
# Two builds, so the gate is a real regression test rather than one that would pass either way:
#
#   RED    -DCF_BLOCK_COMPLETION_UNGATED_UNFIXED restores the pre-fix shape in
#          _peerRelayedBlockTxns: an unconditional BRCFScanLedgerMarkEvaluated +
#          _BRPeerManagerPersistCFLedgerLocked, byte-for-byte the completion logic that shipped
#          in the lab series. That build MUST fail, and MUST fail at BOTH crux checks — the
#          unsolicited erase (CRUX-A) and the stripped-tx-list completion (CRUX-B). A merely
#          non-zero exit is NOT accepted: that is also what a build or link failure looks like,
#          and a gate that can go red on a compile error proves nothing.
#   GREEN  the production shape must run every check and exit 0.
#
# Exit code 0 = the RED arm failed for exactly the right reasons AND the fixed build passed
# every check; 1 = otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# build <output> <extra -D flags...>
build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/cf_block_completion_gate_kat_main.c" \
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
        -Wl,--wrap=BRPeerSendGetdataBlocks \
        -lm -lpthread \
        -o "$out"
}

# ── RED: the pre-fix completion trusts any `block` message ────────────────────
build "$BUILD_DIR/kat_ungated" -DCF_BLOCK_COMPLETION_UNGATED_UNFIXED
red_log="$BUILD_DIR/red.log"
set +e
"$BUILD_DIR/kat_ungated" > "$red_log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the CF_BLOCK_COMPLETION_UNGATED_UNFIXED build PASSED. The red-before-green"
    echo "             gate cannot go red — an unverified, unsolicited block completing a scan"
    echo "             height would not be detected. Refusing to green."
    sed 's/^/             | /' "$red_log"
    exit 1
fi

missing=0
for crux in \
    "FAIL: CRUX-A: unsolicited full block does NOT complete an outstanding scan height" \
    "FAIL: CRUX-B: solicited block with a stripped tx list does NOT complete the height"
do
    if ! grep -qF "$crux" "$red_log"; then
        echo "GATE FAILED: the pre-fix build did not fail at the expected crux:"
        echo "             $crux"
        missing=1
    fi
done
if [ "$missing" -ne 0 ]; then
    echo "             The RED arm failed for some OTHER reason (build break, fixture bug), so it"
    echo "             does not prove the defect. Full output:"
    sed 's/^/             | /' "$red_log"
    exit 1
fi

# The pre-fix build must still get the POSITIVE cases right — otherwise "red" could just mean
# the fixture is broken for everything, which would make the green arm meaningless.
for pos in \
    "PASS: CRUX-C: walletCount==0 does NOT block completion" \
    "PASS: CRUX-D: the answering block completes the height it was solicited for"
do
    if ! grep -qF "$pos" "$red_log"; then
        echo "GATE FAILED: the pre-fix build also failed a POSITIVE case ($pos)."
        echo "             RED is then indistinguishable from a broken fixture. Refusing to green."
        sed 's/^/             | /' "$red_log"
        exit 1
    fi
done

echo "RED gate OK: pre-fix completion accepted an unsolicited block AND a stripped tx list,"
echo "             while still completing the honest cases (exit $RED_STATUS)."

# ── GREEN: the production shape must pass every check ────────────────────────
build "$BUILD_DIR/kat_gated"
"$BUILD_DIR/kat_gated"
