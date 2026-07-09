#!/usr/bin/env bash
# Host KAT runner for BRPeerPenaltyContains (BRPeerPenalty.h, Task 1 of the
# cf-sync-peer-reliability plan, .superpowers/sdd/task-1-brief.md).
#
# BRPeerPenaltyContains is a pure, header-only `static inline` predicate
# (only depends on BRInt.h for UInt128/UInt128Eq) so this KAT compiles just
# the KAT main + the header -- no submodule .c sources need linking, unlike
# the taproot/network-switch KATs which need the real crypto machinery.
#
# Compiler: clang (consistent with the other host KATs in this tree).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error -- e.g. BRPeerPenalty.h not created yet, the RED state before Step 2).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/peer_penalty_kat_main.c" \
    -o "$BUILD_DIR/peer_penalty_kat"

"$BUILD_DIR/peer_penalty_kat"
