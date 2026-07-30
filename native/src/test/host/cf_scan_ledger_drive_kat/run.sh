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
# CF_RETENTION_MAX_SPAN is GONE: paced-convoy Task 5 deleted the tip-anchored
# depth ceiling that read it, and the depth-refusal removal deleted the #define
# in BRPeerManager.h along with the app-layer refusal gate and its three JNI
# accessors. No build here passes -DCF_RETENTION_MAX_SPAN any more. The retention
# FLOOR below is a different mechanism and is unaffected.
#
# ==== DETERMINISM-GUARD RED-BEFORE-GREEN GATE (re-homed onto the B2 valve) ===
# NAMING (fix wave, Task-10 M3): this gate and its two -D flags used to be called
# kat_ceiling_unguarded / KAT_CEILING_REDGREEN_ONLY / RETENTION_PREEMPTIVE_ADVANCE.
# There is no ceiling any more -- paced-convoy Task 5 deleted the tip-anchored depth
# ceiling -- and what these actually toggle is the DETERMINISM GUARD, so they are
# renamed to say so.
#
# The Part-3b determinism guard (BRCFScanLedgerAbandonGaveUpBelow advances
# abandonedBelow ONLY to cover gaveUp actually dropped, never preemptively) is
# proven by building test_abandon_guard_no_preemptive_advance TWICE:
#   * PRE-GUARD (-DDETERMINISM_GUARD_PREEMPTIVE_ADVANCE): AbandonGaveUpBelow raises
#     abandonedBelow to `target`/`clamp` even when NOTHING was dropped, so a scan
#     that never ran raises its own floor and would COMPLETE with a WRONG BALANCE
#     -> the case FAILS (nonzero exit == RED). A guard that can't go red is
#     worthless, so we HARD-FAIL run.sh if this build unexpectedly PASSES.
#   * FIXED (default): abandonedBelow stays 0, the floor is not raised -> GREEN.
# KAT_DETERMINISM_GUARD_REDGREEN_ONLY runs ONLY that one case so the RED is unambiguous.
# (Paced-convoy Task 5 deleted the tip-anchored DEPTH ceiling this gate used to
# run, but the guard itself is RETAINED and the B2 abandonment valve is now its
# only production caller — the valve's "every abandonment is warn-logged and
# abandonedBelow==0 is a verified fact" contract rests on it — so the gate moved
# onto the guard's own case rather than being dropped.)
#
# ⚠️ WHAT THIS GATE IS, PRECISELY (fix wave, Task-10 M3 -- an earlier report
# OVERSTATED it and that overstatement invites deleting a live safety gate): this
# is the only RED-BEFORE-GREEN GATE on the determinism guard, NOT the only
# coverage of it. cf_scan_ledger_kat's test_abandon_no_advance_when_nothing_dropped
# (cf_scan_ledger_kat_main.c) asserts the same property GREEN-ONLY -- its runner has
# no pre-fix -D build, so it can pass against a broken guard. Do NOT delete this
# build on the strength of "the property is already covered elsewhere"; the green-only
# case cannot go red, and a gate that cannot fail proves nothing.
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
# ==== F4: CF_GAVEUP_MAX / B2 ARM PREDICATE / RUN-WIDE VALVE ==================
# Three more builds, all wired the ordinary way (compile a fix out, expect RED):
#   * -DCF_GAVEUP_CEILING_UNFIXED       : CF_GAVEUP_MAX back to 512 while
#     CF_REREQ_MAX_RANGE is 1000, so one RetireCapped over a MAXIMAL coalesced run
#     parks 512 heights and leaves 488 CAPPED in `outstanding` -- offered by no
#     driver, pinning the scan frontier forever -> RED (pure-ledger case).
#   * -DCONVOY_B2_ARM_PREDICATE_UNFIXED : the pre-F4 arm predicate
#     (gaveUp[0] < outstanding[0]) reads a CAPPED outstanding pin as "being retried",
#     so the valve stays inert while that hole pins the frontier -> RED.
#   * -DCONVOY_B2_SINGLE_HEIGHT_STEP    : the valve acts on ONE height per decision.
#     Correct per height, but ~15 days to clear one CF_REREQ_MAX_RANGE dead band, so
#     the band does not clear inside the budget -> RED.
# All three HARD-FAIL run.sh if they unexpectedly PASS.
#
# ==== FROZEN-FRONTIER CONVOY WEDGE — ESCAPE ACCEPTANCE (BOTH WAYS) ===========
# Two more builds. The first USED TO BE wired the opposite way round from every gate
# above (it reproduced a wedge unfixed in shipped code, so the ORDINARY build had to
# exit NONZERO). F4 LANDED THE ESCAPE, so it is now an ordinary GREEN acceptance
# gate; the second build exists because a gate with only one direction checked is how
# you ship a permanently-wrong verdict:
#   * -DKAT_WEDGE_REPRO_ONLY on an ORDINARY (no fix-removal -D) build runs only
#     test_frozen_frontier_convoy_does_not_recover, which used to reproduce a wedge that
#     was UNFIXED in shipped code. Post-F4 it MUST exit ZERO (the frontier moves past
#     the pinning hole, by loudly abandoning the dead band), and run.sh HARD-FAILS if
#     it FAILS. It is ALSO in the default suite now. NOTE its LIVENESS assertion is the
#     design's DISJUNCTION, so it greens on any ONE of F4's three sub-fixes: it is the
#     ESCAPE-AT-ALL gate, and the two F4 stanzas above are the per-mechanism gates.
#   * the same flag PLUS -DKAT_WEDGE_SIMULATE_RECOVERY makes the dead band become
#     servable partway through the tick loop -- a HARNESS-ONLY simulation of a landed
#     fix (production is untouched) -> it MUST exit ZERO, and run.sh HARD-FAILS if it
#     FAILS. This is what proves the case can still RECOGNISE a fix; without it, an
#     assertion that encodes the wedge keeps the exit code at 1 after the wedge is
#     fixed and the ordinary stanza reports "RED confirmed ... (expected)" forever.
#   * a THIRD build (added with the constant-relation sweep) adds
#     -DWEDGE_RECOVERY_TICK=1 -DKAT_WEDGE_ESCAPE_ROTATES_PEER: the simulated fix lands
#     on the FIRST tick and one CF peer is disconnected as part of recovering. It MUST
#     exit ZERO. It exists because "can it recognise A fix" is weaker than it looks --
#     two assertions in the case still required the escape to be SLOW (>= 3 dead
#     re-requests, the field's 3x/5x signature) and to leave the peer set INTACT
#     (connectedPeers == 2). Both are properties of the BUG, so a better fix -- faster,
#     or one that rotates away from the misbehaving peer, which production does in three
#     places -- read as a FAILURE. They are now a print and a floored "at least one peer
#     is still offerable"; this build is what keeps them from being promoted back.
# F4 landed that production fix: the ordinary stanza now expects 0 and the recovery
# stanza is unchanged, exactly as written above. See the full stanzas near the bottom.
#
# Exit code 0 = every red-before-green gate satisfied, the frozen-frontier wedge
# RECOVERS on production code AND is still detectable-as-recovered under BOTH
# simulated-fix builds (a slow escape and a fast, peer-rotating one), AND the fixed
# full suite passed.
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
build "$BUILD_DIR/kat_unfixed" -DRETENTION_UNFIXED -DKAT_REDGREEN_ONLY
if "$BUILD_DIR/kat_unfixed"; then
    echo "GATE FAILURE: the UNFIXED retention build PASSED. The red-before-green gate"
    echo "cannot go red -- the scan-floor prune was not detected. Refusing to green."
    exit 1
else
    echo "RED confirmed: unfixed _BRPeerManagerClearMemory prunes the scan-floor header (expected)."
fi

# ---- RED: pre-guard preemptive abandonedBelow raise (scan-not-started) MUST fail ----
build "$BUILD_DIR/kat_determinism_guard_unguarded" -DDETERMINISM_GUARD_PREEMPTIVE_ADVANCE -DKAT_DETERMINISM_GUARD_REDGREEN_ONLY
if "$BUILD_DIR/kat_determinism_guard_unguarded"; then
    echo "GATE FAILURE: the PRE-GUARD (preemptive abandonedBelow) build PASSED. The"
    echo "determinism-guard red-before-green cannot go red -- an empty-scan deep restore"
    echo "would raise the scan floor and complete with a WRONG BALANCE undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: pre-guard AbandonGaveUpBelow raises abandonedBelow with nothing dropped (expected)."
fi

# ---- RED: convoy gate compiled OUT (the pre-fix shape) MUST fail ------------
build "$BUILD_DIR/kat_convoy_ungated" -DCONVOY_UNGATED -DKAT_CONVOY_REDGREEN_ONLY
if "$BUILD_DIR/kat_convoy_ungated"; then
    echo "GATE FAILURE: the CONVOY-UNGATED build PASSED. The convoy gate's red-before-green"
    echo "cannot go red -- an unpaced header/cfheader fast-forward to the tip would not be"
    echo "detected, and a deep restore would OOM undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the gate the convoy advance sends at a full window (expected)."
fi

# ---- RED: naive NextHeight-1 on a NULL chain (0xFFFFFFFF underflow) MUST fail ----
build "$BUILD_DIR/kat_convoy_nullnaive" -DCONVOY_NULLCHAIN_NAIVE -DKAT_CONVOY_NULL_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_convoy_nob1" -DCONVOY_NO_B1_DRIVER -DKAT_B1_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_convoy_unthrottled" -DCONVOY_HDR_REKICK_UNTHROTTLED -DKAT_B1_THROTTLE_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_convoy_stalegate" -DCONVOY_HDR_REKICK_STALE_ACROSS_GATE -DKAT_B1_GATERESET_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_resume_snap_unfixed" -DRESUME_SNAP_UNFIXED -DKAT_RESUME_SNAP_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_b2_novalve" -DCONVOY_NO_B2_VALVE -DKAT_B2_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_b2_peerblind" -DCONVOY_B2_PEER_BLIND -DKAT_B2_REDGREEN_ONLY
if "$BUILD_DIR/kat_b2_peerblind"; then
    echo "GATE FAILURE: the PEER-BLIND B2 build PASSED. The valve's 'never abandon when it isn't"
    echo "the height's fault' rule cannot go red -- a wallet that simply has no connected CF peer"
    echo "would abandon real, servable history it never offered to anyone, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: a peer-blind valve abandons a height no live CF peer was ever asked for (expected)."
fi

# ---- RED: valve that checks peer presence at the abandon INSTANT MUST fail --
build "$BUILD_DIR/kat_b2_latchblind" -DCONVOY_B2_IGNORE_OFFER_LATCH -DKAT_B2_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_b2_rearmonce" -DCONVOY_B2_REARM_ONCE -DKAT_B2_REDGREEN_ONLY
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
build "$BUILD_DIR/kat_scale_ungated" -DCONVOY_UNGATED -DKAT_SCALE_REDGREEN_ONLY
if "$BUILD_DIR/kat_scale_ungated"; then
    echo "GATE FAILURE: the CONVOY-UNGATED SCALE build PASSED. The memory bound's red-before-green"
    echo "cannot go red -- an unpaced header fast-forward would fill manager->blocks with the whole"
    echo "[birth..tip] span on every deep restore and OOM the wallet, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the gate a >100k-block descent grows manager->blocks to chain length (expected)."
fi

# ==== Paced-convoy Task 7: THE REORG FORK-JOIN NULL GUARD — a CRASH gate ======
# The convoy gives manager->blocks a bottom edge, so _peerRelayedBlock's fork-join
# walk (BRPeerManager.c:1812) can now exit with b == NULL when the join point has
# been pruned — and the next two lines dereferenced b->height. That is a SIGSEGV,
# not a failed assertion, so this gate is shaped differently from every other one
# above: a nonzero exit is NOT sufficient evidence.
#
# ASan's default exitcode for a fatal signal is 1 — byte-identical to the exit code
# `check()` produces on a failed assertion — so the exit status ALONE cannot tell
# "crashed as expected" from "an assertion failed" or "the case never ran". The gate
# therefore requires ALL of:
#   1. the build itself SUCCEEDED — `build` is a bare statement, so `set -e` aborts
#      run.sh before the gate is even reached if compilation/link fails;
#   2. an ASan SEGV report is present in the output (a real fatal signal);
#   3. the FAULTING ADDRESS equals offsetof(BRMerkleBlock, height) — i.e. the fault is
#      a read of `->height` through a NULL pointer, which is precisely the unguarded
#      deref. The case prints that offset itself (REORG-KAT: ...) so the expected
#      address is DERIVED, never hardcoded, and stays correct if the struct changes;
#   4. NO "FAIL:" line was printed — the process died crashing, not asserting;
#   5. the construction's own preconditions were reached and PASSED first, i.e. the
#      join block really was absent from manager->blocks at the moment of the walk
#      ("JOIN POINT ABSENT" + "THE WINDOW EDGE IS EXACTLY AT THE JOIN"). Without (5)
#      a crash somewhere earlier in setup would satisfy the gate for the wrong reason.
# (3) deliberately replaces a symbolized-backtrace check: symbolizing this binary costs
# ~90 s (llvm-symbolizer) and would make the gate depend on a symbolizer being present,
# so the crash build runs under ASAN_OPTIONS=symbolize=0. For a human backtrace, re-run
# "$BUILD_DIR/kat_reorg_unguarded" without that option — the frame is
# _peerRelayedBlock in BRPeerManager.c, in the fork-join branch.
#
# NB the join height is not free: b (fork side) and b2 (main side) descend in
# lockstep, so a join BELOW floor-1 kills b2 FIRST and does NOT crash. The case pins
# the join at exactly floor-1 — see the long comment on test_reorg_below_window_no_crash.
build "$BUILD_DIR/kat_reorg_unguarded" -DREORG_NULLGUARD_UNFIXED -DKAT_REORG_NULLGUARD_REDGREEN_ONLY
reorg_log="$BUILD_DIR/reorg_unguarded.log"
set +e
ASAN_OPTIONS=symbolize=0 "$BUILD_DIR/kat_reorg_unguarded" > "$reorg_log" 2>&1
reorg_rc=$?
set -e

# NB: `grep -q X && var=1` under `set -e` ABORTS the script when the grep misses
# (the AND-list's own nonzero status is what errexit sees), which would turn every
# "gate did not go red" case into a silent early exit. if/then keeps it a decision.
reorg_segv=0
if grep -q "AddressSanitizer: SEGV on unknown address" "$reorg_log"; then reorg_segv=1; fi
reorg_asserted=0
if grep -q "^FAIL:" "$reorg_log"; then reorg_asserted=1; fi
reorg_precond=0
if grep -q "^PASS: JOIN POINT ABSENT" "$reorg_log" && \
   grep -q "^PASS: THE WINDOW EDGE IS EXACTLY AT THE JOIN" "$reorg_log"; then reorg_precond=1; fi

# The expected faulting address == offsetof(BRMerkleBlock, height), as printed by the
# case itself. Compared NUMERICALLY so ASan's zero-padding width is irrelevant.
reorg_addr=0
reorg_off="$(sed -n 's/^REORG-KAT: offsetof(BRMerkleBlock, height) = \([0-9][0-9]*\)$/\1/p' "$reorg_log" | head -1)"
reorg_fault="$(sed -n 's/.*SEGV on unknown address 0x\([0-9a-fA-F][0-9a-fA-F]*\).*/\1/p' "$reorg_log" | head -1)"
if [ -n "$reorg_off" ] && [ -n "$reorg_fault" ] && [ "$((16#$reorg_fault))" -eq "$reorg_off" ]; then reorg_addr=1; fi

if [ "$reorg_rc" -eq 0 ]; then
    echo "GATE FAILURE: the REORG-NULLGUARD-UNFIXED build exited 0. The NULL-guard's"
    echo "red-before-green cannot go red -- the fork-join walk never terminated at b == NULL,"
    echo "which means the construction did NOT actually free the join point (a join below"
    echo "floor-1 kills the MAIN-side walk first and never crashes). Refusing to green."
    sed 's/^/    | /' "$reorg_log"
    exit 1
elif [ "$reorg_segv" -ne 1 ]; then
    echo "GATE FAILURE: the REORG-NULLGUARD-UNFIXED build exited $reorg_rc WITHOUT crashing."
    echo "This gate demands a real SIGSEGV (ASan SEGV report); a failed assertion or any other"
    echo "nonzero exit is NOT evidence the unguarded deref was reached. Refusing to green."
    sed 's/^/    | /' "$reorg_log"
    exit 1
elif [ "$reorg_asserted" -ne 0 ]; then
    echo "GATE FAILURE: the REORG-NULLGUARD-UNFIXED build crashed, but a check() had ALREADY"
    echo "FAILED before it did -- so the construction was broken and the crash cannot be"
    echo "attributed to the unguarded fork-join deref. Refusing to green."
    sed 's/^/    | /' "$reorg_log"
    exit 1
elif [ "$reorg_precond" -ne 1 ]; then
    echo "GATE FAILURE: the REORG-NULLGUARD-UNFIXED build crashed BEFORE its construction"
    echo "preconditions were reached (no 'JOIN POINT ABSENT' / 'THE WINDOW EDGE IS EXACTLY AT"
    echo "THE JOIN' PASS in the log), so the crash is not the fork-join deref. Refusing to green."
    sed 's/^/    | /' "$reorg_log"
    exit 1
elif [ "$reorg_addr" -ne 1 ]; then
    echo "GATE FAILURE: the REORG-NULLGUARD-UNFIXED build produced an ASan SEGV, but the faulting"
    echo "address (0x${reorg_fault:-?}) is NOT offsetof(BRMerkleBlock, height) (${reorg_off:-?}), so the"
    echo "fault is not a NULL '->height' read and cannot be attributed to the unguarded fork-join"
    echo "deref. This gate will not green on unattributed crash evidence."
    sed 's/^/    | /' "$reorg_log"
    exit 1
else
    echo "RED confirmed: unguarded fork-join walk dereferences the NULL b after the bounded window freed"
    echo "the join point (expected). Crash signature:"
    grep -m1 "AddressSanitizer: SEGV on unknown address" "$reorg_log" | sed 's/^/    /'
    echo "    faulting address 0x$reorg_fault == offsetof(BRMerkleBlock, height) == $reorg_off  (a NULL '->height' read)"
fi

# ==== FIX WAVE C-1: RESUME MID-DESCENT MUST NOT SILENTLY SKIP ================
# On a resume BRPeerManagerNewEx chains FORWARD from the highest saved block, so
# manager->blocks holds the checkpoints plus exactly ONE saved block and the block
# FLOOR is the saved tip. enableAutoCompactFilterFetch is then called with the
# never-advanced deep cf_birth_height, which cannot resolve, so its clamp arms both
# autoFetchCFiltersStart and the cursor AT that tip -- a full CF_CONVOY_WINDOW above
# the scan frontier the ledger restore puts back a moment later.
#   * -DCONVOY_C1_UNFIXED: the resume reconciliation is compiled out (the snap goes
#     back to RAISE-ONLY and nothing surfaces an unscannable band; the window/ledger
#     machinery stays live, so what is proven red is the RECONCILIATION, not the
#     arithmetic). The next forward fetch then starts at the clamped tip and
#     _cfLedgerAdvance sails scannedThrough over ~10,000 never-requested heights with
#     abandonedBelow still 0 -- no WARN, no banner, wallet reaches Synced -- and a
#     restored outstanding hole below the floor pins the frontier where no valve can
#     ever see it (its getcfilters can never be sent, so `attempts` never advances and
#     it can never reach gaveUp) -> the case FAILS (== RED).
# HARD-FAILS run.sh if it unexpectedly PASSES.
build "$BUILD_DIR/kat_resume_c1_unfixed" -DCONVOY_C1_UNFIXED -DKAT_RESUME_C1_REDGREEN_ONLY
if "$BUILD_DIR/kat_resume_c1_unfixed"; then
    echo "GATE FAILURE: the C1-UNFIXED build PASSED. The resume reconciliation's red-before-green"
    echo "cannot go red -- a wallet resumed mid-descent would mark up to CF_CONVOY_WINDOW"
    echo "never-requested heights SCANNED with abandonedBelow still 0 (no warn, no banner, and it"
    echo "reaches Synced), on every single resume of a deep restore. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the resume reconciliation a resumed descent silently skips the window (expected)."
fi

# ==== FIX WAVE R2: THE RESUME BLOCK FLOOR ===================================
# BRPeerManagerNewEx added every saved block to `orphans` and then chained FORWARD
# from the highest one -- the loop looked for that block's CHILD, found none (it IS
# the tip) and exited after ONE iteration. Exactly one of the SAVE_BLOCK_COUNT
# persisted headers reached manager->blocks; the other 299 were stranded and freed
# at teardown. So the resume block FLOOR was the saved TIP, and since saved blocks
# are persisted on every save callback while the CF scan ledger is on a 20-s
# coalescing timer, an ordinary abrupt kill of a HEALTHY, fully-synced wallet left
# the restored ledger 1-2 heights below the restored tip -- BELOW the floor, hence
# surfaced as a non-dismissible "history gap" band with Synced withheld, over
# heights that WERE scanned. Chaining the run DOWNWARD puts the floor at
# savedTip-(SAVE_BLOCK_COUNT-1) and that whole class becomes resolvable again.
#   * -DRESUME_FLOOR_UNFIXED: the forward-chaining loop is compiled back in (every
#     other resume mechanism -- the snap, the C-1 surfacing, the KeepAlive backstop
#     -- stays live, so what is proven red is the CHAINING DIRECTION, not the
#     surrounding machinery) -> the floor reads as the saved tip, the healthy-kill
#     case surfaces a false band, and both resume cases FAIL (== RED).
# HARD-FAILS run.sh if it unexpectedly PASSES.
build "$BUILD_DIR/kat_resume_floor_unfixed" -DRESUME_FLOOR_UNFIXED -DKAT_RESUME_FLOOR_REDGREEN_ONLY
if "$BUILD_DIR/kat_resume_floor_unfixed"; then
    echo "GATE FAILURE: the RESUME-FLOOR-UNFIXED build PASSED. The saved-run chaining's"
    echo "red-before-green cannot go red -- 299 of every 300 persisted headers would stay"
    echo "stranded in \`orphans\`, the resume block floor would sit at the saved tip, and an"
    echo "ordinary abrupt kill of a HEALTHY wallet would raise a non-dismissible history-gap"
    echo "banner (Synced withheld) over heights that were actually scanned. Refusing to green."
    exit 1
else
    echo "RED confirmed: forward-chaining strands SAVE_BLOCK_COUNT-1 headers and floors the resume at the saved tip (expected)."
fi

# ---- RED: the KeepAlive backstop's CURSOR RECONCILIATION compiled OUT MUST fail ----
# A gap R2 OPENS. Three of the four surfacing sites (the arming clamp, the cfheaders
# floor snap, the floor re-anchor) set start/cursor to the new floor themselves; the
# KeepAlive backstop did not, and pre-R2 that was safe purely by coincidence -- the
# resume block floor WAS the clamped cursor + 1, so the gap was zero. With the floor
# SAVE_BLOCK_COUNT-1 lower it is a real 299-height hole: the next forward fetch starts
# at the clamped saved tip, RecordRequested raises requestedThrough NON-CONTIGUOUSLY
# across the gap, and _cfLedgerAdvance sails scannedThrough over heights that were
# never requested AND are not below abandonedBelow -- a silent skip, above the
# watermark, invisible to the banner. Exactly the invariant the whole fix wave exists
# to hold.
#   * -DCONVOY_C1_NO_CURSOR_RECONCILE: the surfacing still runs in full (the band is
#     still abandoned and warn-logged); ONLY the cursor reconciliation is compiled
#     out, so what is proven red is the reconciliation and nothing else -> RED.
# HARD-FAILS run.sh if it unexpectedly PASSES.
build "$BUILD_DIR/kat_c1_no_cursor_reconcile" -DCONVOY_C1_NO_CURSOR_RECONCILE -DKAT_RESUME_C1_REDGREEN_ONLY
if "$BUILD_DIR/kat_c1_no_cursor_reconcile"; then
    echo "GATE FAILURE: the NO-CURSOR-RECONCILE build PASSED. The KeepAlive backstop's cursor"
    echo "reconciliation cannot go red -- after surfacing a band the forward fetch would resume"
    echo "ABOVE the frontier, raise requestedThrough non-contiguously, and mark SAVE_BLOCK_COUNT-1"
    echo "heights scanned that were never requested and are NOT below abandonedBelow. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the cursor reconciliation the backstop leaves a silent skip above the watermark (expected)."
fi

# ==== F1: getcfilters MUST NOT ASK BELOW THE RESIDENT BLOCK FLOOR ============
# _BRPeerManagerRequestCFiltersWithStopHashLocked refuses an unresolvable STOP
# (it is a hash) but put startHeight on the wire as a BARE INTEGER, checked
# against nothing -- so a range STRADDLING the resident block floor was sent
# verbatim and the peer answered with filters for headers we no longer hold, every
# one of which is then buffered against the 256 KiB budget where it can never
# drain. Two twice-built shapes, both of which MUST fail:
#   * -DCF_REQ_FLOOR_UNFIXED: the clamp is compiled out (everything else -- the
#     unresolvable-stop refusal, the residual driver, the ledger -- stays live, so
#     what is proven red is the CLAMP). The case asserts on the EMITTED WIRE
#     ARGUMENT captured by __wrap_BRPeerSendGetCFilters, so the RED is a measured
#     below-floor start, not an inferred one -> RED.
#   * -DCF_REQ_FLOOR_NO_MEMO: the clamp stays but reads the floor through the raw
#     O(chainLen) prevBlock descent instead of _BRPeerManagerBlockFloorCached, so a
#     2-send tick pays 2 full walks under manager->lock -- and Pass C sends up to
#     CF_REREQ_BATCH_PER_TICK(64) ranges per tick, which is exactly the per-send
#     under-the-lock walk cost the Pass A/B/C restructure was built to remove.
#     Walk cost is invisible at test scale unless it is COUNTED, so the case counts
#     descents (CF_KAT_COUNT_FLOOR_WALKS) and asserts <= 1 -> RED.
# Both HARD-FAIL run.sh if they unexpectedly PASS.
build "$BUILD_DIR/kat_reqfloor_unfixed" -DCF_REQ_FLOOR_UNFIXED -DKAT_REQ_FLOOR_REDGREEN_ONLY
if "$BUILD_DIR/kat_reqfloor_unfixed"; then
    echo "GATE FAILURE: the CF_REQ_FLOOR_UNFIXED build PASSED. F1's red-before-green cannot go red --"
    echo "a getcfilters whose startHeight sits hundreds of blocks below the resident block floor would"
    echo "go on the wire undetected, and every honest answer to it would be buffered against the"
    echo "256 KiB filter-byte budget where it can never drain. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the clamp a straddling range's start goes on the wire below the block floor (expected)."
fi

build "$BUILD_DIR/kat_reqfloor_nomemo" -DCF_REQ_FLOOR_NO_MEMO -DKAT_REQ_FLOOR_REDGREEN_ONLY
if "$BUILD_DIR/kat_reqfloor_nomemo"; then
    echo "GATE FAILURE: the CF_REQ_FLOOR_NO_MEMO build PASSED. The floor memo's red-before-green cannot"
    echo "go red -- the clamp could re-introduce one full O(chainLen) prevBlock descent PER getcfilters"
    echo "send under the non-recursive manager->lock (up to CF_REREQ_BATCH_PER_TICK per tick), the exact"
    echo "cost class the Pass A/B/C single-descent restructure removed, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: without the memo a 2-send tick pays one block-floor descent PER SEND (expected)."
fi

# ==== F4 PART A: CF_GAVEUP_MAX MUST ABSORB A MAXIMAL COALESCED RUN ===========
# CF_GAVEUP_MAX was 512 while CF_REREQ_MAX_RANGE is 1000 -- and 1000 is exactly
# what BRCFScanLedgerPeekRerequestRange emits as ONE coalesced getcfilters. So a
# maximal dead run could never be fully retired: RetireCapped parks 512 heights,
# _cfLedgerMoveToGaveUp then returns 0, and the remaining 488 stay in `outstanding`
# CAPPED -- where no driver ever offers them again (Peek/NextRerequest both skip a
# capped entry) and, via _cfLedgerAdvance's min(outstanding[0], gaveUp[0]) - 1 cap,
# they pin the scan frontier and the whole paced convoy forever.
#   * -DCF_GAVEUP_CEILING_UNFIXED: CF_GAVEUP_MAX goes back to 512 (everything else
#     -- RetireCapped, the valve, the drivers -- stays live, so what is proven red is
#     the CEILING). The case is PURE LEDGER, so a RED cannot be a driver/peer/harness
#     artifact -> RED.
# HARD-FAILS run.sh if it unexpectedly PASSES.
build "$BUILD_DIR/kat_gaveup_ceiling_unfixed" -DCF_GAVEUP_CEILING_UNFIXED -DKAT_GAVEUP_CEILING_REDGREEN_ONLY
if "$BUILD_DIR/kat_gaveup_ceiling_unfixed"; then
    echo "GATE FAILURE: the CF_GAVEUP_CEILING_UNFIXED build PASSED. F4 Part A's red-before-green"
    echo "cannot go red -- a maximal coalesced dead run would leave CF_REREQ_MAX_RANGE-CF_GAVEUP_MAX"
    echo "heights CAPPED in \`outstanding\`, offered by no driver and pinning the scan frontier"
    echo "permanently, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: with CF_GAVEUP_MAX=512 a maximal 1000-height run cannot be fully retired (expected)."
fi

# ==== F4 PART B: THE B2 ARM PREDICATE + RUN-WIDE ACTION ======================
# Raising the ceiling alone does NOT fix the valve. Its arm predicate was
# "gaveUp[0] < outstanding[0].height", which treats EVERY outstanding entry as
# recoverable-and-being-retried -- false for a CAPPED one, which no driver will ever
# offer again. When such a hole is the pin the predicate reads FALSE and the valve is
# inert while the frontier stays frozen: exactly where the field trace terminated
# (outstanding[0] = F+1 at attempts 5, gaveUp[0] = F+2, gaveUp at its ceiling). That
# state survives ANY finite ceiling -- the valve re-arms gaveUp[0], another height
# retires into the freed slot, and the re-armed hole re-exhausts with gaveUp full
# again. Two twice-built shapes, BOTH with the Part A ceiling FIXED so each gate is
# about the VALVE alone, and both of which MUST fail:
#   * -DCONVOY_B2_ARM_PREDICATE_UNFIXED : the pre-F4 predicate is compiled back in
#     (the valve, its peer/latch/REARM_MAX rules and both abandonment primitives stay
#     live, so what is proven red is the PREDICATE) -> the valve never arms, the
#     frontier never moves -> RED.
#   * -DCONVOY_B2_SINGLE_HEIGHT_STEP    : the valve's run cap goes back to ONE height
#     per decision. Per height it stays correct -- so this gate is not about
#     correctness but about whether the escape exists at FIELD TIMESCALES: one height
#     per (1 + CF_CONVOY_REARM_MAX) x 7.5-min sequence is ~15 days for a
#     CF_REREQ_MAX_RANGE-wide band, so the band does not clear inside the budget -> RED.
# Both HARD-FAIL run.sh if they unexpectedly PASS.
build "$BUILD_DIR/kat_b2_pin_unfixed" -DCONVOY_B2_ARM_PREDICATE_UNFIXED -DKAT_B2_PIN_REDGREEN_ONLY
if "$BUILD_DIR/kat_b2_pin_unfixed"; then
    echo "GATE FAILURE: the CONVOY_B2_ARM_PREDICATE_UNFIXED build PASSED. F4 Part B's red-before-green"
    echo "cannot go red -- a capped outstanding hole below gaveUp[0] would read as 'still being retried',"
    echo "the valve would stay inert, and that one hole would pin the scan frontier (and the whole paced"
    echo "convoy) forever, undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: the pre-F4 arm predicate is FALSE forever on a capped outstanding pin (expected)."
fi

build "$BUILD_DIR/kat_b2_pin_singlestep" -DCONVOY_B2_SINGLE_HEIGHT_STEP -DKAT_B2_PIN_REDGREEN_ONLY
if "$BUILD_DIR/kat_b2_pin_singlestep"; then
    echo "GATE FAILURE: the CONVOY_B2_SINGLE_HEIGHT_STEP build PASSED. The run-wide half of F4 Part B"
    echo "cannot go red -- a one-height-per-cycle valve needs ~15 days to clear a single"
    echo "CF_REREQ_MAX_RANGE-wide dead band, i.e. an escape that exists on paper and not in the field,"
    echo "undetected. Refusing to green."
    exit 1
else
    echo "RED confirmed: a one-height-per-cycle valve does not clear a full-width band in the budget (expected)."
fi

# ==== FROZEN-FRONTIER CONVOY WEDGE — REPRODUCTION (RED ON SHIPPED CODE) ======
# ⚠️ THIS GATE IS WIRED THE OPPOSITE WAY ROUND FROM EVERY OTHER GATE ABOVE, AND
# THAT IS DELIBERATE. Every stanza above compiles a FIX out with a -D and expects
# THAT build to fail. This one has no fix to compile out: it reproduces a wedge
# that is UNFIXED IN SHIPPED CODE, so the build under test is the ORDINARY one and
# it must exit NONZERO. It therefore cannot sit in the default suite below (this
# script runs under `set -euo pipefail`), and it gets its own KAT_WEDGE_REPRO_ONLY
# build that runs ONLY that case.
#
# WHAT IT REPRODUCES (measured on hardware, deep restore, 2026-07-29):
# cfLedger.scannedThrough freezes at a height F and never advances again while
# cfilters keep arriving in volume (6,346 during the freeze), outstandingCount
# stays high and does not drain, the hole range starting exactly one block above F
# is re-requested repeatedly, and gaveUp/abandonedBelow never produce an escape.
# The case installs that shape with the REAL arrival handler (_peerRelayedCFilter)
# — the first case in this file to drive it — by serving the dead band under a
# fork hash the wallet does not hold, so an arrival legitimately fails to credit.
#
# ⚠️ AN INVERTED GATE NEEDS **BOTH** DIRECTIONS CHECKED MECHANICALLY, AND THE FIRST
# CUT OF THIS STANZA DID NOT HAVE THEM. It had only the "must exit nonzero" half, and
# the case encoded the wedge itself in three MECHANISM assertions — so a landed fix
# flipped LIVENESS green and those three red, the exit code stayed 1, and this stanza
# happily printed "RED confirmed … (expected)" forever. A fixed wedge, reported as
# unfixed, permanently, with nothing in the runner able to notice. The three
# assertions are now trace OUTPUT (see the MECHANISM TRACE block in the case), and
# the green direction is checked by a SECOND build below rather than by hand once.
#
# WHEN A REAL PRODUCTION FIX LANDS: flip THIS (ordinary) stanza to expect exit 0,
# fold the case into the default suite in main(), and delete the "expected nonzero"
# wording. The SIMULATED-RECOVERY stanza below keeps expecting exit 0, unchanged —
# it is not about any particular fix, it is the standing proof that this case's
# assertion set can still recognise a recovery at all. Do NOT delete either build:
# the case becomes the regression test for whatever escape the fix installs. The
# acceptance assertion is stated as the DISJUNCTION the production design already
# promises (scan the hole OR loudly abandon it), so it greens for either fix and
# prejudges neither.
# ⚠️ FLIPPED BY F4 — THIS STANZA NOW EXPECTS EXIT 0, AS THE CASE'S OWN INSTRUCTIONS
# DIRECT ("WHEN A REAL PRODUCTION FIX LANDS: flip THIS (ordinary) stanza to expect
# exit 0, fold the case into the default suite in main()"). F4 landed the escape:
# CF_GAVEUP_MAX now absorbs a maximal run (Part A), the B2 arm predicate sees a
# capped-outstanding pin, and the valve acts on the whole coalesced run (Part B). The
# dead band is now ABANDONED in one surfaced WARN and the frontier moves past the
# pinning hole. The case is folded into the default suite as well and this stanza is
# retained as the NAMED acceptance gate (it runs ONLY that case, so a regression here
# is unambiguous).
#
# WHAT THIS GATE DOES **NOT** DISCRIMINATE — state it, do not let it drift. Its
# LIVENESS assertion is the design's own DISJUNCTION ("scanned OR loudly abandoned"),
# deliberately so, and it is satisfied by ANY ONE of F4's three sub-fixes on its own:
#   * -DCF_GAVEUP_CEILING_UNFIXED           -> still exits 0 (2 WARNs)
#   * -DCONVOY_B2_ARM_PREDICATE_UNFIXED     -> still exits 0 (1 WARN)
#   * -DCONVOY_B2_SINGLE_HEIGHT_STEP        -> still exits 0, but only ONE height of
#                                              the 1000-height band is surfaced in the
#                                              whole budget
# So this is the ESCAPE-AT-ALL gate, NOT a per-mechanism gate. The per-mechanism
# red-before-green gates are the two F4 stanzas above
# (KAT_GAVEUP_CEILING_REDGREEN_ONLY / KAT_B2_PIN_REDGREEN_ONLY), and those DO isolate
# one mechanism each. Do not "strengthen" this case's acceptance assertion into a
# per-mechanism claim: the disjunction is what keeps it from prejudging the escape.
build "$BUILD_DIR/kat_wedge_repro" -DKAT_WEDGE_REPRO_ONLY
wedge_log="$BUILD_DIR/wedge.log"
if "$BUILD_DIR/kat_wedge_repro" >"$wedge_log" 2>&1; then
    echo "GREEN confirmed: the frozen-frontier convoy wedge now RECOVERS on production code (expected)."
    { grep -m1 "^PASS: LIVENESS" "$wedge_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] F=" "$wedge_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] OUTSTANDING TRAJECTORY" "$wedge_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] MECHANISM TRACE" "$wedge_log" || true; } | sed 's/^/    /'
else
    echo "GATE FAILURE: the FROZEN-FRONTIER WEDGE acceptance case FAILED. The F4 escape has"
    echo "REGRESSED: a wallet whose scan frontier freezes at a dead band no longer recovers —"
    echo "neither by scanning the band nor by loudly abandoning it — so it wedges permanently"
    echo "while reporting itself as progressing. Refusing to green."
    sed 's/^/    | /' "$wedge_log"
    exit 1
fi

# ---- GREEN: the SAME case, with a SIMULATED landed fix, MUST pass -------------
# -DKAT_WEDGE_SIMULATE_RECOVERY changes NOTHING in production. It flips one
# harness-side fact partway through the tick loop — the modeled network starts
# serving the dead band under a hash the wallet actually holds — which is exactly
# what a real fix looks like at this harness's boundary, however the fix gets there
# (header re-fetch, cfheader re-anchor, peer rotation, re-keying back-pressure off
# progress). Whether the wallet then RECOVERS is decided entirely by unmodified
# production code; the harness never writes to the ledger.
#
# This build is the ONLY thing standing between this file and the defect described
# above: it fails loudly the moment anyone re-encodes the wedge as a pass condition,
# because a gate that can only ever go red is worth exactly as little as one that
# can only ever go green.
build "$BUILD_DIR/kat_wedge_recovered" -DKAT_WEDGE_REPRO_ONLY -DKAT_WEDGE_SIMULATE_RECOVERY
wedge_rec_log="$BUILD_DIR/wedge_recovered.log"
if "$BUILD_DIR/kat_wedge_recovered" >"$wedge_rec_log" 2>&1; then
    echo "GREEN confirmed: with the dead band made servable mid-run, the SAME case PASSES (expected) —"
    echo "so the wedge assertions can recognise a recovery and this gate is not red-only."
    { grep -m1 "^PASS: LIVENESS" "$wedge_rec_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] OUTSTANDING TRAJECTORY" "$wedge_rec_log" || true; } | sed 's/^/    /'
else
    echo "GATE FAILURE: the SIMULATED-RECOVERY build FAILED. This case can therefore go RED but not"
    echo "GREEN, which means it CANNOT DETECT A FIX: when the wedge is finally fixed, LIVENESS will"
    echo "pass, some other assertion will fail, the exit code will stay nonzero, and the ordinary"
    echo "stanza above will keep reporting 'RED confirmed … (expected)' forever — a fixed wedge"
    echo "reported as unfixed, permanently. Most likely cause: an assertion that encodes the WEDGE"
    echo "(outstanding staying >= LOWWATER, the convoy windows staying shut, scannedThrough staying"
    echo "at F on every tick) has been promoted back out of the MECHANISM TRACE block into a"
    echo "check(). Those are trace OUTPUT by design. Refusing to green."
    sed 's/^/    | /' "$wedge_rec_log"
    exit 1
fi

# ---- GREEN: a FAST escape must also pass (the bug-property demotion, pinned) ---
# The stanza above proves the case can recognise a recovery that happens at t8. This
# one proves it can recognise a recovery that happens IMMEDIATELY, and it exists
# because the case still carried two assertions of the same class as the three already
# demoted — claims that hold only WHILE THE WEDGE IS BROKEN:
#
#   * `cumSendsDead >= 3` — the field's 3x/5x re-request signature. A fix that escapes
#     on its FIRST or SECOND dead re-request (i.e. a better fix) produces 1 or 2 and
#     was reported as a FAILURE for succeeding quickly. Now a print; the
#     bug-independent `tick1HadDeadRange == 1` carries the anti-vacuity load.
#   * `array_count(connectedPeers) == 2` — that neither CF peer was ever dropped. Real
#     escape routes (cfheaders stall-recovery disconnect, PeerMisbehavin, the residual
#     driver's rotate-away) drop or rotate a peer AS PART of recovering. Now asserts
#     the property that survives a fix: at least one peer is still offerable, so the
#     RED can never be read as "we hung up on everybody".
#
# This build installs BOTH shapes at once, because both were false-RED'd by the pair:
#   -DWEDGE_RECOVERY_TICK=1         the simulated fix lands on the FIRST tick (2 dead
#                                   re-requests instead of 4) -> `cumSendsDead >= 3` fails
#   -DKAT_WEDGE_ESCAPE_ROTATES_PEER one CF peer is disconnected and removed from
#                                   connectedPeers, exactly as _BRPeerManagerPeerDisconnected
#                                   does -> `array_count(connectedPeers) == 2` fails
# Keep this stanza: it is what makes re-promoting either assertion fail LOUDLY instead of
# silently narrowing this gate to "only escapes that are slow, and that keep every peer,
# count as escapes".
build "$BUILD_DIR/kat_wedge_fast" -DKAT_WEDGE_REPRO_ONLY -DKAT_WEDGE_SIMULATE_RECOVERY \
      -DWEDGE_RECOVERY_TICK=1 -DKAT_WEDGE_ESCAPE_ROTATES_PEER
wedge_fast_log="$BUILD_DIR/wedge_fast.log"
if "$BUILD_DIR/kat_wedge_fast" >"$wedge_fast_log" 2>&1; then
    echo "GREEN confirmed: an IMMEDIATE escape (recovery at t1) that also ROTATES A PEER AWAY passes"
    echo "(expected) — no assertion in this case requires the bug to persist long enough to leave its"
    echo "field signature, or requires the peer set to survive the fix untouched."
    { grep -m1 "^PASS: LIVENESS" "$wedge_fast_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] DEAD-RANGE RE-REQUESTS" "$wedge_fast_log" || true; } | sed 's/^/    /'
    { grep -m1 "SIMULATED ESCAPE-BY-ROTATION" "$wedge_fast_log" || true; } | sed 's/^/    /'
    { grep -m1 "^   \[wedge\] PEER SET at end" "$wedge_fast_log" || true; } | sed 's/^/    /'
else
    echo "GATE FAILURE: a recovery that works FAST, or that rotates away from the misbehaving peer, is"
    echo "being reported as a failure. Some assertion in the frozen-frontier case is a property of the"
    echo "BUG rather than of a wallet — most likely the"
    echo "dead-range re-request COUNT (the 3x/5x field signature) or the connected-peer count has been"
    echo "promoted back out of the reported traces into a check(). Both are trace OUTPUT by design:"
    echo "a faster escape and an escape that rotates a peer are BETTER fixes, not failures."
    sed 's/^/    | /' "$wedge_fast_log"
    exit 1
fi

# ---- GREEN: fixed full suite ------------------------------------------------
build "$BUILD_DIR/kat_fixed"
"$BUILD_DIR/kat_fixed"
