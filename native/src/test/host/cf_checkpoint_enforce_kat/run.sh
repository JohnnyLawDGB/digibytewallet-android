#!/usr/bin/env bash
# Host KAT runner for Task 3 of the cfcheckpt-active-rejection plan:
# pre-commit enforcement of filter-header checkpoints in
# _peerRelayedCFHeaders (BRPeerManager.c) -- reject+ban on a checkpoint
# mismatch, never commit a bad header, instead of the pre-Task-3
# observe-only log-and-append behavior.
#
# cf_checkpoint_enforce_kat_main.c #include-s BRPeerManager.c directly (to
# reach the file-static _peerRelayedCFHeaders and the otherwise-opaque
# BRPeerManagerStruct/BRPeerCallbackInfo definitions), same pattern
# cf_confirm_kat/run.sh and cf_scan_ledger_drive_kat/run.sh use.
# BRPeerManager.c is therefore deliberately NOT ALSO passed as a separate
# compilation unit below -- every symbol it defines would otherwise be
# defined twice and the link would fail.
#
# Every other BRPeerManager.c dependency IS compiled as a separate unit and
# linked in: BRPeer.c (the synthetic serving peer + connection/message
# handling), BRWallet.c (real BRWallet BRPeerManagerNew requires),
# BRTransaction.c, BRMerkleBlock.c, BRCompactFilterChain.c (the REAL,
# unmodified BRCompactFilterChainBatchViolatesCheckpoint under --wrap --
# see below), BRGCSFilter.c, BRWalletFilterElements.c, BRCFScanLedger.c,
# BRNetwork.c, BRDigiDollar.c, BRDigiAsset.c, plus the whole address/key/
# crypto chain those pull in (BRKey, BRAddress, BRSet, BRBase58, BRBech32,
# BRCrypto, BRBIP32Sequence, BRBIP39Mnemonic, and crypto/* down through
# groestl/skein/qubit/odocrypt and the X11/sph_* helpers under
# crypto/sha3/) -- same file list cf_confirm_kat/run.sh uses (this KAT
# needs the identical dependency closure: a real BRPeerManager + BRWallet
# built through the same public constructors).
#
# Call-interception seam (was --wrap): -Wl,--wrap=BRCompactFilterChainBatchViolatesCheckpoint lets
# ONE test (test_matching_batch_wiring) force the validator's verdict to
# "no violation" for a batch that genuinely spans a real mainnet checkpoint
# height -- simulating what a genuine cryptographic match would report,
# without needing one (matching the real pinned 256-bit value from
# constructed data is a SHA256d preimage break; see cf_candidate_header_kat
# and this KAT's own file header comment). The wrap in main.c defaults to
# calling through to the REAL __real_ function for every other test --
# test_mismatch_rejected exercises the genuine, unforced validator against
# the real BRMainNetCFCheckpoints table end to end. Portable seam: no linker
# support needed -- see the build() comment. (This replaced GNU ld's `--wrap`,
# which Apple's ld64 does not implement.)
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh (file-scope `const static int`
# array bounds GCC rejects; missing <stdint.h> include).
#
# ==== RED-BEFORE-GREEN GATE =================================================
# main.c runs every test with NO #ifdef branching -- test_mismatch_rejected's
# checks assert the FIXED (enforced/reject) outcome unconditionally, in
# source identical between builds:
#   RED   -DCF_CHECKPOINT_ENFORCE_UNFIXED compiles OUT the enforce block in
#         _peerRelayedCFHeaders (its own #ifndef guard), restoring the
#         pre-Task-3 observe-only shape: the checkpoint-crossing mismatching
#         batch gets APPENDED anyway (only logged). test_mismatch_rejected's
#         checks -- which expect rejection -- therefore FAIL, and the whole
#         binary exits nonzero. A gate that can't go red on the unfixed
#         shape proves nothing, so run.sh HARD-FAILS if this build
#         unexpectedly exits 0.
#   GREEN the production shape (no flag) must pass every check, including
#         test_mismatch_rejected's rejection assertions, and exit 0.
# test_safety_control_no_checkpoint_in_range and test_matching_batch_wiring
# must PASS in BOTH builds (they don't exercise the enforce/reject branch),
# proving the RED build's failure is specific to the mismatch-rejection
# checks, not a broken harness.
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
    # Portable stand-in for GNU ld --wrap (Apple ld64 has no equivalent).
    # Each wrapped symbol is DEFINED in its own core TU and CALLED from the
    # BRPeerManager.c that this main.c #include-s, so renaming the call sites
    # is a per-TU concern: only this object gets the -D. The core TUs below
    # keep the real definitions, and `__wrap_<sym>` is a distinct token the
    # macro never rewrites.
    clang -w -include stdint.h "$@" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    -DBRCompactFilterChainBatchViolatesCheckpoint=__wrap_BRCompactFilterChainBatchViolatesCheckpoint \
    -c "$SCRIPT_DIR/cf_checkpoint_enforce_kat_main.c" -o "$BUILD_DIR/kat_main.o"

    clang -w -include stdint.h "$@" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$BUILD_DIR/kat_main.o" \
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

# ── RED: unfixed shape must FAIL the mismatch-rejection checks ─────────────
build "$BUILD_DIR/cf_checkpoint_enforce_kat_unfixed" -DCF_CHECKPOINT_ENFORCE_UNFIXED

set +e
"$BUILD_DIR/cf_checkpoint_enforce_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: -DCF_CHECKPOINT_ENFORCE_UNFIXED build exited 0 -- the enforce"
    echo "             block must be load-bearing (its absence must fail the"
    echo "             mismatch-rejection checks). Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: mismatch: chain tip did NOT advance past the batch start (rejected pre-commit)" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build did not fail at the expected checkpoint-"
    echo "             rejection assertion -- the -D flag is not reaching the"
    echo "             enforce block, so RED is not actually the pre-fix shape."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "PASS: safety-control: no-checkpoint batch is fully appended" "$BUILD_DIR/red.log" ||
   ! grep -q "PASS: match: a checkpoint-crossing batch that MATCHES is appended (tip advances)" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build's non-enforcement-dependent checks (safety"
    echo "             control / matching-batch wiring) did not pass -- the harness"
    echo "             itself is broken, not just the enforce block."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix (observe-only) shape appended the mismatching checkpoint-crossing batch; the rejection checks failed as expected, everything else still passed."

# ── GREEN: the production shape must pass every check ──────────────────────
build "$BUILD_DIR/cf_checkpoint_enforce_kat"

"$BUILD_DIR/cf_checkpoint_enforce_kat"
