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
# Exit code 0 = all §9 cases (1-5) passed, 1 = at least one failed (or a build
# error — e.g. BRCFScanLedger.c not present yet, the RED state before the
# implementation lands).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -include stdint.h \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/cf_scan_ledger_kat_main.c" \
    "$CORE_DIR/BRCFScanLedger.c" \
    -o "$BUILD_DIR/cf_scan_ledger_kat"

"$BUILD_DIR/cf_scan_ledger_kat"
