#!/usr/bin/env bash
# Host KAT runner for BRComputeCFPeerStatus (BRPeerCFStatus.h, Task 1 of the
# own-node first-class pairing plan, .superpowers/sdd/task-1-brief.md).
#
# BRComputeCFPeerStatus is a pure, header-only `static inline` predicate
# over three caller-supplied booleans -- it needs only <stdint.h>, no
# BRInt.h, no submodule .c sources need linking, same shape as
# peer_penalty_kat / own_node_pin_kat.
#
# Compiler: clang (consistent with the other host KATs in this tree).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error -- e.g. BRPeerCFStatus.h not created yet, the RED state before
# Step 7).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/cf_peer_status_kat_main.c" \
    -o "$BUILD_DIR/cf_peer_status_kat"

"$BUILD_DIR/cf_peer_status_kat"
