#!/usr/bin/env bash
# Host KAT runner for Task 4 of the cfcheckpt-active-rejection plan: a
# checkpoint-confirmed compact-filter chain VETOES a continuity-mismatch
# re-anchor instead of letting a lone diverging peer force one -- closing
# the single-peer-liar hole downstream of Task 3's pre-commit enforcement.
#
# cf_checkpoint_veto_kat_main.c #include-s BRPeerManager.c directly (to reach
# the file-static _peerRelayedCFHeaders, the new
# _BRPeerManagerCheckpointConfirmsOurChainLocked helper, and the otherwise-
# opaque BRPeerManagerStruct/BRPeerCallbackInfo definitions), same pattern
# cf_checkpoint_enforce_kat/cf_confirm_kat/cf_scan_ledger_drive_kat use.
# BRPeerManager.c is therefore deliberately NOT ALSO passed as a separate
# compilation unit below -- every symbol it defines would otherwise be
# defined twice and the link would fail.
#
# Every other BRPeerManager.c dependency IS compiled as a separate unit and
# linked in -- identical file list to cf_checkpoint_enforce_kat/run.sh (this
# KAT needs the same dependency closure: a real BRPeerManager + BRWallet
# built through the same public constructors).
#
# --wrap seam: -Wl,--wrap=BRCompactFilterChainHeader lets ONE test
# (test_veto_confirmed_chain) force the ONE accessor call
# _BRPeerManagerCheckpointConfirmsOurChainLocked makes -- reading our chain's
# header at the pinned checkpoint height -- to return the real pinned value,
# simulating what a genuine committed match would read back without
# requiring one (matching a real pinned 256-bit filterHeader from
# constructed chain data is a SHA256d preimage break; see
# cf_checkpoint_enforce_kat and this KAT's own file header comment for the
# same escape hatch used there). The wrap in main.c defaults to calling
# through to the REAL __real_ function everywhere else, including every call
# in test_no_veto_above_top_checkpoint and every OTHER accessor
# (BRCFHighestCheckpointAtOrBelow, BRCompactFilterChainCount,
# BRCompactFilterChainStartHeight) the veto helper itself calls -- none of
# those are wrapped. Requires GNU ld's `--wrap` (Linux/clang CI, same as
# cf_scan_ledger_drive_kat/run.sh).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
#
# ==== RED-BEFORE-GREEN GATE =================================================
# main.c runs both tests with NO #ifdef branching -- test_veto_confirmed_chain's
# checks assert the FIXED (veto-enforced) outcome unconditionally, in source
# identical between builds:
#   RED   -DCF_CHECKPOINT_VETO_UNFIXED compiles OUT the veto guard at BOTH
#         gated re-anchor call sites in _peerRelayedCFHeaders (their own
#         #ifndef guards), restoring the pre-Task-4 shape: a lone diverging
#         peer that reaches CF_SINGLE_PEER_REANCHOR_ROUNDS forces the
#         re-anchor regardless of checkpoint confirmation.
#         test_veto_confirmed_chain's checks -- which expect the veto to
#         fire -- therefore FAIL, and the whole binary exits nonzero. A gate
#         that can't go red on the unfixed shape proves nothing, so run.sh
#         HARD-FAILS if this build unexpectedly exits 0.
#   GREEN the production shape (no flag) must pass every check in both
#         tests and exit 0.
# test_no_veto_above_top_checkpoint must PASS in BOTH builds: its contested
# height sits above the top pinned checkpoint, so the veto guard (present or
# compiled out) never actually changes the outcome there -- proving the RED
# build's failure is specific to the checkpoint-confirmed veto case, not a
# broken harness.
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
    "$SCRIPT_DIR/cf_checkpoint_veto_kat_main.c" \
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
    -Wl,--wrap=BRCompactFilterChainHeader \
    -lm -lpthread \
    -o "$out"
}

# ── RED: unfixed shape must FAIL the veto checks ────────────────────────────
build "$BUILD_DIR/cf_checkpoint_veto_kat_unfixed" -DCF_CHECKPOINT_VETO_UNFIXED

set +e
"$BUILD_DIR/cf_checkpoint_veto_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: -DCF_CHECKPOINT_VETO_UNFIXED build exited 0 -- the veto"
    echo "             guard must be load-bearing (its absence must fail the"
    echo "             veto checks). Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: veto: cfReanchorCount unchanged after 3rd diverged round -- re-anchor did NOT fire" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail at the expected veto"
    echo "             assertion -- the -D flag is not reaching the veto"
    echo "             guard, so RED is not actually the pre-fix shape."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: veto: the lone diverging peer WAS misbehavin'd/banned instead of winning the re-anchor" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail the misbehavin' check --"
    echo "             the unfixed re-anchor path must let the liar win without"
    echo "             being banned, and this build didn't reproduce that."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "PASS: safety: real re-anchor fired at round 3 -- our chain does not reach the top checkpoint" "$BUILD_DIR/red.log" ||
   ! grep -q "PASS: safety: chain WAS torn down (re-anchor completed, no veto)" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build's non-veto-dependent checks (safety"
    echo "             control -- above the top checkpoint) did not pass -- the"
    echo "             harness itself is broken, not just the veto guard."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix shape let the lone diverging peer force a re-anchor off our checkpoint-confirmed chain, unbanned; the veto checks failed as expected, the safety-control checks still passed."

# ── GREEN: the production shape must pass every check ──────────────────────
build "$BUILD_DIR/cf_checkpoint_veto_kat"

"$BUILD_DIR/cf_checkpoint_veto_kat"
