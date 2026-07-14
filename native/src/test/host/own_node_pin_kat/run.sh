#!/usr/bin/env bash
# Host KAT runner for BRPeerIsPinned (BRPeerPin.h, Task 1 of the own-node
# first-class pairing plan, .superpowers/sdd/task-1-brief.md).
#
# BRPeerIsPinned is a pure, header-only `static inline` predicate (only
# depends on BRInt.h for UInt128/UInt128Eq) so this KAT compiles just the
# KAT main + the header -- no submodule .c sources need linking, same shape
# as peer_penalty_kat.
#
# Compiler: clang (consistent with the other host KATs in this tree).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error -- e.g. BRPeerPin.h not created yet, the RED state before Step 6).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/own_node_pin_kat_main.c" \
    -o "$BUILD_DIR/own_node_pin_kat"

"$BUILD_DIR/own_node_pin_kat"
