#!/usr/bin/env bash
# Host KAT: a RANGE instruction names every output up to its endpoint.
#
# A range transfer credits every output 0..endpoint, and the 13-bit endpoint is
# (highFiveBits << 8) | lowEightBits. Every named output is an asset carrier and cannot be
# spent as plain DGB.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_RANGE_TARGETS_UNFIXED, #ifdef):
#   RED  : the earlier shape of the range step -> a check fails, the arm exits nonzero.
#          A failed assertion IS the red evidence here.
#   GREEN: marks 0..endpoint -> exit 0, no sanitizer report.
#
# Exit code 0 = red arm faulted AND green passed clean; 1 = otherwise.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1:detect_leaks=0:symbolize=0"

build() { # $1=output  $2..=extra flags
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_range_targets_kat_main.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        -lm -o "$out"
}

echo "=== building RED (earlier shape of the range step) ==="
build "$BUILD_DIR/red" -DASSET_RANGE_TARGETS_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
echo "=== building GREEN ==="
build "$BUILD_DIR/green" || { echo "FAIL: green arm did not build"; exit 1; }

echo "--- RED: must fault (a check must fail) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out"
if [ $rc -eq 0 ]; then
    echo "FAIL: red arm did not fault (a test that cannot see the defect is worthless)"; exit 1
fi
if grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: red arm faulted for the wrong reason (a sanitizer report, not the expected check)"; exit 1
fi

echo "--- GREEN: must pass clean ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out"
if [ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: green arm"; exit 1
fi

echo "PASS: asset_range_targets_kat"
