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
# CF_RETENTION_MAX_SPAN=4000 is still passed to every build, but is now INERT:
# paced-convoy Task 5 deleted the tip-anchored depth ceiling that read it, and no
# code in this TU references it any more. The flag (and the #define itself) come
# out with the rest of the depth-refusal removal.
#
# ==== DETERMINISM-GUARD RED-BEFORE-GREEN GATE (re-homed onto the B2 valve) ===
# The Part-3b determinism guard (BRCFScanLedgerAbandonGaveUpBelow advances
# abandonedBelow ONLY to cover gaveUp actually dropped, never preemptively) is
# proven by building test_abandon_guard_no_preemptive_advance TWICE:
#   * PRE-GUARD (-DRETENTION_PREEMPTIVE_ADVANCE): AbandonGaveUpBelow raises
#     abandonedBelow to `target`/`clamp` even when NOTHING was dropped, so a scan
#     that never ran raises its own floor and would COMPLETE with a WRONG BALANCE
#     -> the case FAILS (nonzero exit == RED). A guard that can't go red is
#     worthless, so we HARD-FAIL run.sh if this build unexpectedly PASSES.
#   * FIXED (default): abandonedBelow stays 0, the floor is not raised -> GREEN.
# KAT_CEILING_REDGREEN_ONLY runs ONLY that one case so the RED is unambiguous.
# (Paced-convoy Task 5 deleted the tip-anchored DEPTH ceiling this gate used to
# run, but the guard itself is RETAINED and the B2 abandonment valve is now its
# only production caller — the valve's "every abandonment is warn-logged and
# abandonedBelow==0 is a verified fact" contract rests on it — so the gate moved
# onto the guard's own case rather than being dropped.)
#
# ==== Paced-convoy Task 2 GATE RED-BEFORE-GREEN GATES =======================
# The convoy gate (spec Part A) is proven by two more twice-built cases:
#   * -DCONVOY_UNGATED: the SUPPRESSION is compiled out (the window predicates
#     stay live as pure measurement, so what is being proven is the gate, not the
#     arithmetic). The convoy advance then sends at a full window and the peer
#     flag is never raised -> the case FAILS (nonzero exit == RED).
#   * -DCONVOY_NULLCHAIN_NAIVE: the NULL-chain carve-out is compiled out, so
#     BRCompactFilterChainNextHeight(NULL) - 1 underflows to 0xFFFFFFFF, the
#     window reads permanently FULL and the FIRST cfheaders request of a fresh
#     deep restore is suppressed forever -> the case FAILS (== RED).
# Both HARD-FAIL run.sh if they unexpectedly PASS.
#
# ==== Paced-convoy Task 3 B1 DRIVER RED-BEFORE-GREEN GATE ====================
# The Task-2 gate ALONE is a silent permanent wedge: suppressing a continuation
# removes the only thing that re-fires it, and the forward getcfilters auto-fetch
# has exactly ONE production trigger (a cfheaders arrival). The B1 KeepAlive
# driver is the un-suppressor. It is proven by a third twice-built shape:
#   * -DCONVOY_NO_B1_DRIVER: the whole KeepAlive convoy driver is compiled out
#     (the Task-2 gate stays in). A wallet resumed at a DRAIN TROUGH
#     (outstanding==0, gaveUp==0, cfHeadersFrontier > scannedThrough+1) then has
#     nothing that can create the first outstanding entry -- no hole for the
#     residual driver, no cfheaders arrival for the forward fetch -- so the scan
#     never advances and deep history is silently never scanned -> RED.
#   * -DCONVOY_HDR_REKICK_UNTHROTTLED (fix round 1): B1.3's re-kick RATE LIMIT is
#     compiled out (the backoff bookkeeping stays live, so what is proven red is
#     the throttle, not the arithmetic). A permanently frozen header tip -- a
#     stale-HIGH estimatedHeight on a fully synced wallet, or a slow link mid-batch
#     -- then re-issues a full-locator getheaders on EVERY ~10 s tick forever
#     (~10 MB/day upstream, and on a slow link every duplicate reply spawns its own
#     persistent continuation chain) -> RED.
#   * -DCONVOY_HDR_REKICK_STALE_ACROSS_GATE (fix round 2): B1.3's GATED->open
#     EPISODE RESET is compiled out (the convoyHdrWasGated tracking stays live, so
#     what is proven red is the reset, not the transition detection). A stalled tip
#     that escalated the backoff to the ceiling then carries that interval straight
#     through a gated period, so re-opening the window does NOT resume the held
#     continuation on the next tick -- it waits out up to 600 s, in exactly the case
#     B1.3 exists to serve -> RED.
# All three HARD-FAIL run.sh if they unexpectedly PASS.
#
# Exit code 0 = every red-before-green gate satisfied AND the fixed full suite passed.
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
        -Wl,--wrap=BRPeerSendGetCFHeaders \
        -Wl,--wrap=BRPeerSendGetheaders \
        -Wl,--wrap=BRPeerSetConvoyHdrGated \
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

# ---- RED: convoy gate compiled OUT (the pre-fix shape) MUST fail ------------
build "$BUILD_DIR/kat_convoy_ungated" -DCONVOY_UNGATED -DKAT_CONVOY_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_convoy_ungated"; then
    echo "GATE FAILURE: the CONVOY-UNGATED build PASSED. The convoy gate's red-before-green"
    echo "cannot go red -- an unpaced header/cfheader fast-forward to the tip would not be"
    echo "detected, and a deep restore would OOM undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the gate the convoy advance sends at a full window (expected)."
fi

# ---- RED: naive NextHeight-1 on a NULL chain (0xFFFFFFFF underflow) MUST fail ----
build "$BUILD_DIR/kat_convoy_nullnaive" -DCONVOY_NULLCHAIN_NAIVE -DKAT_CONVOY_NULL_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_convoy_nullnaive"; then
    echo "GATE FAILURE: the NAIVE NULL-CHAIN build PASSED. The B-3 carve-out's red-before-green"
    echo "cannot go red -- a NULL compactFilterChain would underflow to 0xFFFFFFFF, score the"
    echo "window permanently FULL and deadlock the first cfheaders request of every fresh deep"
    echo "restore, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: the naive NextHeight-1 formula suppresses the first request on a NULL chain (expected)."
fi

# ---- RED: the B1 KeepAlive convoy DRIVER compiled OUT (gate-only shape) MUST fail ----
build "$BUILD_DIR/kat_convoy_nob1" -DCONVOY_NO_B1_DRIVER -DKAT_B1_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_convoy_nob1"; then
    echo "GATE FAILURE: the NO-B1-DRIVER build PASSED. The convoy driver's red-before-green"
    echo "cannot go red -- with the gate installed but no KeepAlive driver, a wallet resumed at a"
    echo "drain trough (outstanding==0, gaveUp==0, cfHeadersFrontier > scannedThrough+1) would"
    echo "never re-prime the forward cfilter fetch and would silently never scan deep history,"
    echo "while reporting itself as progressing. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the B1 KeepAlive driver the resumed drain trough never re-primes (expected)."
fi

# ---- RED: the B1.3 getheaders re-kick RATE LIMIT compiled OUT MUST fail -----
build "$BUILD_DIR/kat_convoy_unthrottled" -DCONVOY_HDR_REKICK_UNTHROTTLED -DKAT_B1_THROTTLE_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_convoy_unthrottled"; then
    echo "GATE FAILURE: the UNTHROTTLED B1.3 re-kick build PASSED. The rate limit's"
    echo "red-before-green cannot go red -- a permanently frozen header tip (stale-HIGH"
    echo "estimatedHeight on a synced wallet, or a slow link mid-batch) would re-issue a"
    echo "full-locator getheaders on EVERY ~10 s tick forever, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the rate limit a permanently frozen tip re-kicks every tick (expected)."
fi

# ---- RED: the B1.3 GATED->open episode reset compiled OUT MUST fail ---------
build "$BUILD_DIR/kat_convoy_stalegate" -DCONVOY_HDR_REKICK_STALE_ACROSS_GATE -DKAT_B1_GATERESET_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_convoy_stalegate"; then
    echo "GATE FAILURE: the STALE-BACKOFF-ACROSS-GATE build PASSED. The episode reset's"
    echo "red-before-green cannot go red -- a genuinely stalled header tip that escalated to"
    echo "the ceiling would carry that interval through a gated period, so re-opening the"
    echo "window would NOT resume the held continuation on the next tick but wait out up to"
    echo "CF_CONVOY_HDR_REKICK_MAX_SECS, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the episode reset a window reopen waits out the stale pre-gate backoff (expected)."
fi

# ---- RED: the resume cursor reconciliation snap compiled to a no-op MUST fail ----
# (paced-convoy Task 4, spec Part B1-resume). BRPeerManagerSnapAutoFetchThroughToScanFrontier's
# body is skipped under -DRESUME_SNAP_UNFIXED, so a wallet resumed after the
# ledger's scannedThrough advanced past birth still has its forward-fetch cursor
# stuck at birth-1. The next forward-fetch tick then re-requests already-scanned
# history from birth, and RecordRequested re-inserts those heights as
# outstanding, dragging scannedThrough back down and discarding the persisted
# scan progress -- and under the paced convoy's ~10 s KeepAlive drive, it would
# repeat every tick. The same assertion also catches the off-by-one in the other
# direction (snapping to LowestNeededHeight instead of LowestNeededHeight-1
# would silently skip one height forever), but that shape isn't separately
# red/green gated here -- the unfixed (no-op) build already proves the gate can
# go red, and the arithmetic (lowest - 1) is a one-line, directly-inspectable
# expression in BRPeerManagerSnapAutoFetchThroughToScanFrontier.
build "$BUILD_DIR/kat_resume_snap_unfixed" -DRESUME_SNAP_UNFIXED -DKAT_RESUME_SNAP_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_resume_snap_unfixed"; then
    echo "GATE FAILURE: the RESUME-SNAP-UNFIXED build PASSED. The resume cursor"
    echo "reconciliation's red-before-green cannot go red -- a wallet resumed after the"
    echo "ledger's scannedThrough advanced past birth would have its forward-fetch cursor"
    echo "stuck stale, re-request already-scanned history on the very next tick, and drag"
    echo "scannedThrough back down every KeepAlive tick, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the snap the forward-fetch cursor stays stale at birth-1 after a restore (expected)."
fi

# ==== Paced-convoy Task 5: B2 ABANDONMENT VALVE — MATCHED-SET RED GATES ======
# The valve decides whether a retry-exhausted (gaveUp) hole is abandoned (visible,
# recoverable loss) or kept being retried (a permanent convoy wedge if it is never
# resolvable). A mistake in EITHER direction is severe, so the matched set is
# gated four ways — one shape per axis the valve must get right. Each MUST fail.

# ---- RED: no valve at all (today's shape) MUST fail -------------------------
build "$BUILD_DIR/kat_b2_novalve" -DCONVOY_NO_B2_VALVE -DKAT_B2_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_b2_novalve"; then
    echo "GATE FAILURE: the NO-B2-VALVE build PASSED. The abandonment valve's red-before-green"
    echo "cannot go red -- with the depth ceiling removed and no valve, a retry-exhausted hole is"
    echo "never re-armed and never abandoned, so it pins the scan frontier the whole paced convoy"
    echo "keys on and the wallet wedges forever, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the B2 valve a gaveUp hole pins the scan frontier forever (expected)."
fi

# ---- RED: peer-blind valve (abandons an un-offered height) MUST fail --------
build "$BUILD_DIR/kat_b2_peerblind" -DCONVOY_B2_PEER_BLIND -DKAT_B2_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_b2_peerblind"; then
    echo "GATE FAILURE: the PEER-BLIND B2 build PASSED. The valve's 'never abandon when it isn't"
    echo "the height's fault' rule cannot go red -- a wallet that simply has no connected CF peer"
    echo "would abandon real, servable history it never offered to anyone, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: a peer-blind valve abandons a height no live CF peer was ever asked for (expected)."
fi

# ---- RED: valve that checks peer presence at the abandon INSTANT MUST fail --
build "$BUILD_DIR/kat_b2_latchblind" -DCONVOY_B2_IGNORE_OFFER_LATCH -DKAT_B2_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_b2_latchblind"; then
    echo "GATE FAILURE: the IGNORE-OFFER-LATCH B2 build PASSED. The offered-vs-UN-offered axis"
    echo "cannot go red -- a CF peer that flapped away DURING the deciding cycle and came back"
    echo "before the check would read identically to five live refusals, so a transiently"
    echo "unreachable height would be abandoned, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the per-cycle latch a mid-cycle peer flap reads as live refusal (expected)."
fi

# ---- RED: abandoning after ONE re-arm cycle MUST fail -----------------------
build "$BUILD_DIR/kat_b2_rearmonce" -DCONVOY_B2_REARM_ONCE -DKAT_B2_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_b2_rearmonce"; then
    echo "GATE FAILURE: the REARM-ONCE B2 build PASSED. The CF_CONVOY_REARM_MAX=2 tuning cannot"
    echo "go red -- a single unlucky peer-rotation cycle would be enough to abandon a servable"
    echo "height, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: one re-arm cycle lets a single unlucky rotation cycle false-positive (expected)."
fi

# ==== Paced-convoy Task 6: THE MEMORY BOUND — the feature's headline claim ====
# Everything else in this branch exists to make ONE property true: a genuinely deep
# restore does not grow manager->blocks without bound. No other case can observe it
# — they all run on 300..6000-block chains, where an unpaced fast-forward and a
# paced convoy are indistinguishable. test_convoy_scale_bounded drives a >100k-block
# descent from a deep birth floor to the tip and asserts BRSetCount(manager->blocks)
# never exceeds CF_CONVOY_WINDOW + the 2-batch stale-flag overshoot + the retention
# margin, at EVERY tick.
#   * -DCONVOY_UNGATED (reusing the Task-2 gate shape): with the suppression compiled
#     out the modeled peer's header continuation fast-forwards straight to the tip,
#     manager->blocks grows to the full chain length (~105k headers, ~23 MB) instead
#     of ~14k, and the deep-restore OOM is back -> the case FAILS (== RED).
# HARD-FAILS run.sh if it unexpectedly PASSES.
build "$BUILD_DIR/kat_scale_ungated" -DCONVOY_UNGATED -DKAT_SCALE_REDGREEN_ONLY -DCF_RETENTION_MAX_SPAN=4000
if "$BUILD_DIR/kat_scale_ungated"; then
    echo "GATE FAILURE: the CONVOY-UNGATED SCALE build PASSED. The memory bound's red-before-green"
    echo "cannot go red -- an unpaced header fast-forward would fill manager->blocks with the whole"
    echo "[birth..tip] span on every deep restore and OOM the wallet, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the gate a >100k-block descent grows manager->blocks to chain length (expected)."
fi

# ---- GREEN: fixed full suite (ceiling override small) -----------------------
build "$BUILD_DIR/kat_fixed" -DCF_RETENTION_MAX_SPAN=4000
"$BUILD_DIR/kat_fixed"
