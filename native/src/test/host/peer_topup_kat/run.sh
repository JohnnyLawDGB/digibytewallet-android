#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -I "$CORE_DIR" -DPEER_TOPUP_INCREASE_ONLY_UNFIXED \
    "$SCRIPT_DIR/peer_topup_kat_main.c" -o "$BUILD_DIR/peer_topup_red"

set +e
red_output="$($BUILD_DIR/peer_topup_red 2>&1)"
red_status=$?
set -e
printf '%s\n' "$red_output"
if [[ $red_status -eq 0 ]] ||
   ! grep -q "FAIL: unchanged catch-up target tops up a one-of-eight pool" <<<"$red_output"; then
    echo "RED GATE FAILED: old increase-only policy did not expose the degraded-pool bug" >&2
    exit 1
fi
echo "RED CONFIRMED: unchanged target leaves degraded pool underfilled"

clang -w -I "$CORE_DIR" \
    "$SCRIPT_DIR/peer_topup_kat_main.c" -o "$BUILD_DIR/peer_topup_green"
"$BUILD_DIR/peer_topup_green"
