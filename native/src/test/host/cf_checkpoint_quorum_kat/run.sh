#!/usr/bin/env bash
# Host KAT runner for Task 5 of the cfcheckpt-active-rejection plan: the
# continuity-mismatch re-anchor QUORUM decision requires "a strict majority
# of connected filter peers AND >= CF_CONTINUITY_REANCHOR_FLOOR (3) distinct
# disagreers that SHARE THE SAME claimed prevFilterHeader" -- replacing the
# old "CF_CONTINUITY_REANCHOR_K (2) distinct disagreers, any complaint"
# trigger, which let two peers with UNRELATED complaints (different claimed
# prevFilterHeader -- independent transients, not a coherent alternate
# chain) force a re-anchor.
#
# cf_checkpoint_quorum_kat_main.c #include-s BOTH BRPeer.c (for the private
# BRPeerContext -- there is no public setter for connect status, and the
# quorum's majority denominator, _BRPeerManagerConnectedFilterPeerCount,
# gates on BRPeerConnectStatus(p)==Connected) AND BRPeerManager.c (for the
# quorum decision itself and the otherwise-opaque BRPeerManagerStruct /
# BRPeerCallbackInfo definitions) directly, same #include-a-.c-for-statics
# pattern cf_checkpoint_veto_kat/cf_checkpoint_enforce_kat/cf_confirm_kat/
# cf_scan_ledger_drive_kat use. BRPeer.c and BRPeerManager.c are therefore
# deliberately NOT ALSO passed as separate compilation units below -- every
# symbol they define would otherwise be defined twice and the link would
# fail. (One file-static name, _dummyThreadCleanup, is defined by BOTH
# files; the main.c file scopes a preprocessor rename around BRPeer.c's
# #include only -- see that file's header comment.)
#
# Every other BRPeerManager.c dependency IS compiled as a separate unit and
# linked in -- identical file list to cf_checkpoint_veto_kat/run.sh minus
# BRPeer.c (this KAT needs the same dependency closure: a real
# BRPeerManager + BRWallet built through the same public constructors).
#
# --wrap seam: -Wl,--wrap=BRCompactFilterChainHeader lets ONE test
# (test_quorum_veto_checkpoint_confirmed) force the ONE accessor call
# _BRPeerManagerCheckpointConfirmsOurChainLocked makes -- reading our
# chain's header at the pinned checkpoint height -- to return the real
# pinned value, simulating what a genuine committed match would read back
# without requiring one (matching a real pinned 256-bit filterHeader from
# constructed chain data is a SHA256d preimage break; see
# cf_checkpoint_veto_kat and this KAT's own file header comment for the
# same escape hatch used there). The wrap in main.c defaults to calling
# through to the REAL __real_ function everywhere else.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
#
# ==== RED-BEFORE-GREEN GATE =================================================
# main.c runs every test with NO #ifdef branching -- every check() asserts
# the FIXED (quorum-enforced) outcome unconditionally, in source identical
# between builds:
#   RED   -DCF_QUORUM_UNFIXED undefines CF_CONTINUITY_REANCHOR_FLOOR
#         entirely (BRPeerManager.h), and the quorum decision in
#         BRPeerManager.c falls back, via its own #ifndef guard, to the
#         pre-Task-5 "cfDisagreedCount >= CF_CONTINUITY_REANCHOR_K"
#         (any-disagree) trigger -- restoring the K=2 false-fire.
#         test_independent_disagreers_below_floor_no_reanchor's checks --
#         which expect 2 disagreeing peers with DIFFERENT claimed
#         prevFilterHeader to NOT re-anchor -- therefore FAIL, and the whole
#         binary exits nonzero. This is the ONE red-arm requirement the
#         plan brief specifies; run.sh does not assert anything about the
#         other three (GREEN-only) tests' outcome in the red log -- see
#         main.c's file header for why a same-final-state comparison isn't
#         meaningful for them (RED's weaker K=2 threshold is a strict
#         subset of GREEN's stricter floor=3+majority requirement, so RED
#         necessarily fires no later than GREEN on any input that also
#         satisfies GREEN's requirement).
#   GREEN the production shape (no flag) must pass every check in every
#         test and exit 0.
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
    "$SCRIPT_DIR/cf_checkpoint_quorum_kat_main.c" \
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

# ── RED: unfixed shape must FAIL the independent-disagreers checks ─────────
build "$BUILD_DIR/cf_checkpoint_quorum_kat_unfixed" -DCF_QUORUM_UNFIXED

set +e
"$BUILD_DIR/cf_checkpoint_quorum_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: -DCF_QUORUM_UNFIXED build exited 0 -- the quorum floor"
    echo "             +agreement check must be load-bearing (its absence"
    echo "             must fail the independent-disagreers checks). Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: independent: cfReanchorCount unchanged after 2nd disagreer -- re-anchor did NOT fire" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail at the expected"
    echo "             cfReanchorCount assertion -- the -D flag is not"
    echo "             reaching the quorum decision, so RED is not actually"
    echo "             the pre-fix K=2/any-disagree shape."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: independent: compactFilterChain was NOT torn down" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail the chain-intact check --"
    echo "             the unfixed decision must let 2 disagreeing peers with"
    echo "             DIFFERENT claimed prevFilterHeader actually tear the"
    echo "             chain down, and this build didn't reproduce that."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix K=2/any-disagree shape let 2 peers with UNRELATED"
echo "             complaints (different claimed prevFilterHeader) force a"
echo "             re-anchor; the floor+agreement checks failed as expected."

# ── GREEN: the production shape must pass every check ──────────────────────
build "$BUILD_DIR/cf_checkpoint_quorum_kat"

"$BUILD_DIR/cf_checkpoint_quorum_kat"
