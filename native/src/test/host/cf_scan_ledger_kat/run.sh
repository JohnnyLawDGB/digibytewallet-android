#!/usr/bin/env bash
# Host KAT runner for BRCFScanLedger — the pure per-height compact-filter scan
# completeness ledger (docs/superpowers/specs/2026-07-25-cf-scan-ledger-design.md).
#
# BRCFScanLedger.{h,c} is PURE: it depends only on BRInt.h (UInt128/UInt256),
# holds no locks and touches no sockets, so it links standalone with just the
# module .c beside the KAT main — no BRPeerManager.c, no pthread, no -lm, the
# same shape as cf_peer_status_kat / peer_penalty_kat.
#
# Compiler: clang (consistent with the other host KATs in this tree),
# `-include stdint.h` for parity with them.
#
# ==== PERSISTENCE VERSION-GATE RED-BEFORE-GREEN GATE (fix wave I-2) ==========
# Every back-compat branch in BRCFScanLedgerParse must compare against its OWN
# numbered constant (CF_LEDGER_VERSION_2 / CF_LEDGER_VERSION_3), NEVER against
# CF_LEDGER_VERSION — which means "the layout Serialize writes TODAY" and so
# silently changes meaning at the next bump. A branch written the wrong way makes
# every already-shipped blob take the NARROWER previous entry stride; the length
# checks still pass (a narrower stride needs FEWER bytes), so the mis-parse is
# SILENT, and a garbage low gaveUp height feeds the B2 abandonment valve, which
# can raise the MONOTONIC hard floor abandonedBelow to an arbitrary height = a
# permanent skip of real history.
#
# Proven by building test_v3_stride_survives_a_future_version_bump TWICE:
#   * PRE-FIX (-DCF_LEDGER_STRIDE_GATE_UNFIXED) + the simulated next bump
#     (-DCF_LEDGER_VERSION=4u): the hand-built v3 blob takes the v2 stride,
#     Parse still returns 1, and gaveUp[1] + the parked valve bytes come back
#     wrong -> the case FAILS (nonzero exit == RED). A gate that can't go red is
#     worthless, so we HARD-FAIL run.sh if this build unexpectedly PASSES.
#   * FIXED (default): the branch compares against CF_LEDGER_VERSION_3, so the
#     stride is correct at ANY current version -> the full suite PASSES.
# KAT_LEDGER_STRIDE_REDGREEN_ONLY runs ONLY that case so the RED is unambiguous.
#
# Exit code 0 = the red-before-green gate was satisfied AND the full fixed suite
# passed. 1 = at least one case failed (or a build error — e.g. BRCFScanLedger.c
# not present yet, the RED state before an implementation lands).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

# build <output> <extra -D flags...>
build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        "$SCRIPT_DIR/cf_scan_ledger_kat_main.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
        -o "$out"
}

# ---- RED: the pre-fix version comparison under a simulated bump MUST fail ----
build "$BUILD_DIR/kat_stride_unfixed" \
    -DCF_LEDGER_STRIDE_GATE_UNFIXED -DCF_LEDGER_VERSION=4u -DKAT_LEDGER_STRIDE_REDGREEN_ONLY
if "$BUILD_DIR/kat_stride_unfixed"; then
    echo "GATE FAILURE: the PRE-FIX version-gate build PASSED. The stride gate cannot go red --"
    echo "a future CF_LEDGER_VERSION bump would silently make every shipped v3 blob parse at the"
    echo "narrow v2 entry stride, feeding garbage gaveUp heights to the B2 valve and letting it"
    echo "raise the monotonic abandonedBelow floor over real history. Refusing to green."
    exit 1
else
    echo "RED confirmed: 'version >= CF_LEDGER_VERSION' mis-parses a v3 blob after a bump (expected)."
fi

# ---- GREEN: fixed full suite ------------------------------------------------
build "$BUILD_DIR/cf_scan_ledger_kat"
"$BUILD_DIR/cf_scan_ledger_kat"
