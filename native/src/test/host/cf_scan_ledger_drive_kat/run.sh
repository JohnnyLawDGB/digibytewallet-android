#!/usr/bin/env bash
# Host KAT runner for the CF scan-ledger's residual peek/commit re-request
# DRIVER (BRPeerManager.c) + the Task 4 CF-RETENTION SCAN-FLOOR fix in
# _BRPeerManagerClearMemory (.superpowers/sdd/2026-07-27-cf-retention-scan-floor/).
#
# cf_scan_ledger_drive_kat_main.c #include-s BRPeerManager.c directly to reach
# the file-static re-request driver plumbing, _BRPeerManagerClearMemory, and the
# otherwise-opaque BRPeerManagerStruct/BRPeerCallbackInfo + BRCFScanLedger
# fields. BRPeerManager.c is therefore deliberately NOT ALSO passed as a separate
# compilation unit below -- every symbol it defines would otherwise be defined
# twice and the link would fail.
#
# --wrap send-capture seam: the driver calls out through BRPeer.c's public send
# functions (BRPeerSendGetCFilters / BRPeerSendGetdataBlocks) and status/socket
# queries (BRPeerConnectStatus / BRPeerIsSocketOpen) -- real BRPeer.c is still
# linked in, but these four calls are intercepted at link time and redirected to
# the __wrap_ shims in the main.c. Requires GNU ld's `--wrap` (Linux/clang CI).
#
# ASan is compiled in WITH LeakSanitizer LIVE (no detect_leaks=0 override): every
# case ends by calling BRPeerManagerFree(m) so LSan proves the buffer + manager
# are freed. The retention cases build >5000-block chains; the pruned tail is
# freed by _BRPeerManagerClearMemory and the retained span + checkpoints by
# BRPeerManagerFree, so both halves must be leak-clean.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh.
#
# ==== Task 4 RED-BEFORE-GREEN GATE ==========================================
# The CF-retention floor fix is proven by building the scan-floor-retention case
# TWICE:
#   * UNFIXED (-DRETENTION_UNFIXED): _BRPeerManagerClearMemory floors at the
#     cfHEADER frontier (cfNext-144), which is ABOVE the lagging scan floor, so
#     the scan-floor header is pruned -> the case FAILS (nonzero exit == RED).
#     A gate that can't go red is worthless, so we HARD-FAIL run.sh if this
#     build unexpectedly PASSES.
#   * FIXED (default): floors at min(cfNext,lowestNeeded)-144 == scan-floor-144,
#     so the header SURVIVES -> the full suite PASSES (exit 0 == GREEN).
# CF_RETENTION_MAX_SPAN is overridden small (4000) in BOTH builds so the ceiling
# case exercises the clamp without a 30k-block chain; the retain/descent cases
# keep tip-floor < that override so their ceilings never fire.
#
# ==== Task 4b CEILING DETERMINISM-GUARD RED-BEFORE-GREEN GATE ================
# The Part-3b determinism guard (BRCFScanLedgerAbandonGaveUpBelow advances
# abandonedBelow ONLY to cover gaveUp actually dropped, never preemptively) is
# proven by building the scan-not-started ceiling case TWICE:
#   * PRE-GUARD (-DRETENTION_PREEMPTIVE_ADVANCE): AbandonGaveUpBelow raises
#     abandonedBelow to `target`/`clamp` even when NOTHING was dropped, so an
#     empty-scan deep restore raises the scan floor and would COMPLETE with a
#     WRONG BALANCE -> the case FAILS (nonzero exit == RED). A guard that can't
#     go red is worthless, so we HARD-FAIL run.sh if this build unexpectedly PASSES.
#   * FIXED (default): abandonedBelow stays 0, the floor is not raised -> GREEN.
# KAT_CEILING_REDGREEN_ONLY runs ONLY that one case so the RED is unambiguous.
#
# Exit code 0 = both red-before-green gates satisfied AND the fixed full suite passed.
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
        -DCF_LEDGER_DRIVE_REREQUEST=1 \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/cf_scan_ledger_drive_kat_main.c" \
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
        -Wl,--wrap=BRPeerConnectStatus \
        -Wl,--wrap=BRPeerIsSocketOpen \
        -Wl,--wrap=BRPeerSendGetCFilters \
        -Wl,--wrap=BRPeerSendGetdataBlocks \
        -lm -lpthread \
        -o "$out"
}

# ---- RED: unfixed retention prunes the scan floor (MUST fail) ----------------
build "$BUILD_DIR/kat_unfixed" -DRETENTION_UNFIXED -DKAT_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_unfixed"; then
    echo "GATE FAILURE: the UNFIXED retention build PASSED. The red-before-green gate"
    echo "cannot go red -- the scan-floor prune was not detected. Refusing to green."
    exit 1
else
    echo "RED confirmed: unfixed _BRPeerManagerClearMemory prunes the scan-floor header (expected)."
fi

# ---- RED: pre-guard preemptive abandonedBelow raise (scan-not-started) MUST fail ----
build "$BUILD_DIR/kat_ceiling_unguarded" -DRETENTION_PREEMPTIVE_ADVANCE -DKAT_CEILING_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_ceiling_unguarded"; then
    echo "GATE FAILURE: the PRE-GUARD (preemptive abandonedBelow) build PASSED. The"
    echo "determinism-guard red-before-green cannot go red -- an empty-scan deep restore"
    echo "would raise the scan floor and complete with a WRONG BALANCE undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: pre-guard AbandonGaveUpBelow raises abandonedBelow with nothing dropped (expected)."
fi

# ---- GREEN: fixed full suite (ceiling override small) -----------------------
build "$BUILD_DIR/kat_fixed" -DCF_RETENTION_MAX_SPAN=4000
"$BUILD_DIR/kat_fixed"
