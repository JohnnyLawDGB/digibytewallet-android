#!/usr/bin/env bash
# Host KAT for bridge/checkpoint_time.h: the checkpoint time looked up by height, handed to the
# header-anchor rule as a creation time, selects a checkpoint at or below that height, on the
# real BRMainNetCheckpoints / BRTestNetCheckpoints tables.
#
# Red arm: -DCHECKPOINT_TIME_UNFIXED makes the lookup take the lowest checkpoint at or ABOVE the
# height; the KAT must fail with it. Green: no -D. Both at 64 and 32 bits.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${HOST_CLANG:-clang}"

grep -q 'CHECKPOINT_TIME_UNFIXED' "$BRIDGE_DIR/checkpoint_time.h" \
    || { echo "GATE FAILURE: checkpoint_time.h has no CHECKPOINT_TIME_UNFIXED seam"; exit 1; }

build() { # <out> <bits> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/checkpoint_time_kat_main.c" -o "$out"
}

fail=0
for bits in 64 32; do
    if [ "$bits" = "32" ] && ! echo 'int main(void){return 0;}' | "$CC" -m32 -fsanitize=address -x c - -o "$BUILD_DIR/probe32" 2>/dev/null; then
        echo "SKIP: no 32-bit host toolchain"; continue
    fi
    build "$BUILD_DIR/red$bits" "$bits" -DCHECKPOINT_TIME_UNFIXED
    if "$BUILD_DIR/red$bits" >"$BUILD_DIR/red$bits.out"; then
        echo "FAIL: red arm ($bits-bit) passed; the KAT cannot see the defect"; fail=1
    else
        echo "ok: red arm ($bits-bit) fails as required ($(grep -c '^FAIL' "$BUILD_DIR/red$bits.out") failing probes)"
    fi
    build "$BUILD_DIR/green$bits" "$bits"
    if "$BUILD_DIR/green$bits"; then echo "ok: green ($bits-bit)"; else echo "FAIL: green ($bits-bit)"; fail=1; fi
done

# Wiring: the JNI lookup uses this header over the active network's table.
PEER="$BRIDGE_DIR/jni_peer.c"
if grep -q 'checkpoint_time_at_or_below(params->checkpoints, params->checkpointsCount' "$PEER"; then
    echo "ok: jni_peer.c looks the time up in the active network's table"
else
    echo "FAIL: jni_peer.c does not use checkpoint_time_at_or_below over params"; fail=1
fi
[ "$fail" = 0 ] && echo "PASS: checkpoint_time_kat" || echo "FAIL: checkpoint_time_kat"
exit $fail
