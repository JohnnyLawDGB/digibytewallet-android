#!/usr/bin/env bash
# Host KAT runner: the two-sided implied-fee guard used by
# buildAndSignForeignAssetTransfer.
#
# Builds the guard header standalone — it deliberately carries no JNI dependency so the
# arithmetic that decides "sign" vs "burn the user's coins" is testable off-device, at its
# exact boundaries.
#
# RED before the extraction: foreign_tx_fee_guard.h does not exist, so the KAT does not
# build and the band lives only inline in jni_derive.c where nothing can reach it.
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -Wall -Wextra -Werror -g -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$BRIDGE_DIR" \
    "$SCRIPT_DIR/foreign_tx_fee_guard_kat_main.c" \
    -o "$BUILD_DIR/foreign_tx_fee_guard_kat"

ASAN_OPTIONS=abort_on_error=1:detect_leaks=0 "$BUILD_DIR/foreign_tx_fee_guard_kat"
