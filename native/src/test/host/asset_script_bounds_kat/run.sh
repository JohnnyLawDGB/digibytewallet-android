#!/usr/bin/env bash
# Host KAT: the native asset-script reader stays inside the script it was given.
#
# Every read lies inside the payload the push declares, and the 3-bit header of a compact amount
# always selects one of the seven rows of the decode table (header values 6 and 7 both select
# the 7-byte form). See asset_script_bounds_kat_main.c for the cases.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_SCRIPT_BOUNDS_UNFIXED, #ifdef).
# Four binaries are built -- each arm once with asserts on and once with them compiled out
# (-DNDEBUG) -- and every case runs as its own process in all four:
#     * RED  : restores the earlier shape of the reader. Every case must fault: a nonzero exit
#              with asserts on, a sanitizer report with asserts off.
#     * GREEN: every case must exit 0 with no sanitizer report, in both builds.
#
# A sanitizer report is detected by matching "ERROR: AddressSanitizer" only (never a subtype
# word), per the campaign method.
#
# Exit code 0 = every case faulted in both red builds AND passed clean in both green builds.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1:detect_leaks=0:symbolize=0"

CASES="amount7-truncated amount7-complete short-metadata flags-last-byte"

build() { # $1=output  $2..=extra flags
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_script_bounds_kat_main.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        -lm -o "$out"
}

echo "=== building RED (asserts on) ==="
build "$BUILD_DIR/red" -DASSET_SCRIPT_BOUNDS_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
echo "=== building RED (asserts off, -DNDEBUG) ==="
build "$BUILD_DIR/red_ndebug" -DASSET_SCRIPT_BOUNDS_UNFIXED -DNDEBUG || { echo "FAIL: red -DNDEBUG arm did not build"; exit 1; }
echo "=== building GREEN (asserts on) ==="
build "$BUILD_DIR/green" || { echo "FAIL: green arm did not build"; exit 1; }
echo "=== building GREEN (asserts off, -DNDEBUG) ==="
build "$BUILD_DIR/green_ndebug" -DNDEBUG || { echo "FAIL: green -DNDEBUG arm did not build"; exit 1; }

for c in $CASES; do
    echo "--- [$c] RED, asserts on: must fault ---"
    out="$("$BUILD_DIR/red" "$c" 2>&1)"; rc=$?
    echo "$out"
    if [ $rc -eq 0 ] || [ $rc -eq 2 ]; then
        echo "FAIL: [$c] red arm (asserts on) did not fault (a test that cannot go red proves nothing)"; exit 1
    fi

    echo "--- [$c] RED, asserts off: must emit a sanitizer report ---"
    out="$("$BUILD_DIR/red_ndebug" "$c" 2>&1)"
    echo "$out"
    if ! grep -q "ERROR: AddressSanitizer" <<<"$out"; then
        echo "FAIL: [$c] red -DNDEBUG arm did not emit a sanitizer report"; exit 1
    fi

    echo "--- [$c] GREEN, asserts on: must pass clean ---"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    echo "$out"
    if [ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" <<<"$out"; then
        echo "FAIL: [$c] green arm"; exit 1
    fi

    echo "--- [$c] GREEN, asserts off: must pass clean ---"
    out="$("$BUILD_DIR/green_ndebug" "$c" 2>&1)"; rc=$?
    echo "$out"
    if [ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" <<<"$out"; then
        echo "FAIL: [$c] green -DNDEBUG arm"; exit 1
    fi
done

echo "PASS: asset_script_bounds_kat"
