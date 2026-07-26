#!/usr/bin/env bash
# Host KAT runner for bridge_status_is_stale (bridge_status_stale.h, lock-free
# status-reads design — take PEER_GUARD off the UI poll path).
#
# bridge_status_is_stale is a pure, header-only predicate over three int64_t
# scalars (lastMs, nowMs, boundMs) — it needs only <stdint.h>, no BRPeerManager,
# no submodule .c sources to link, same shape as cf_peer_status_kat /
# peer_penalty_kat / own_node_pin_kat. The header lives in the JNI bridge
# (native/src/main/jni/bridge/), NOT the submodule — this is a bridge-only change.
#
# Compiler: clang (consistent with the other host KATs in this tree).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build error
# — e.g. bridge_status_stale.h not created yet, the RED state before the header).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w \
    -I "$BRIDGE_DIR" \
    "$SCRIPT_DIR/status_staleness_kat_main.c" \
    -o "$BUILD_DIR/status_staleness_kat"

"$BUILD_DIR/status_staleness_kat"
