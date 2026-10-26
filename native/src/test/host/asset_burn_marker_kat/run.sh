#!/usr/bin/env bash
# Host KAT: output index 31 is the destroy marker only in a BURN.
#
# A non-range instruction to output 31 burns its units only in a BURN (opcode 0x25). In a TRANSFER
# or an ISSUANCE it names a real output, which the native reader must recognise as an asset carrier
# so it is not spendable as plain DGB. A burn's other instructions still mark their outputs.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_BURN_MARKER_UNFIXED, #ifdef):
#   RED  : index 31 read as a burn in every operation. Each RED-THEN-GREEN check named below must
#          FAIL, with no sanitizer report.
#   GREEN: every check passes -> exit 0, no sanitizer report.
#
# Exit code 0 = red arm failed the named checks AND green passed clean; 1 = otherwise.
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
        "$SCRIPT_DIR/asset_burn_marker_kat_main.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        -lm -o "$out"
}

echo "=== building RED (index 31 read as a burn in every operation) ==="
build "$BUILD_DIR/red" -DASSET_BURN_MARKER_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
echo "=== building GREEN ==="
build "$BUILD_DIR/green" || { echo "FAIL: green arm did not build"; exit 1; }

echo "--- RED: must fault (a check must fail) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out"
if [ $rc -eq 0 ]; then
    echo "FAIL: red arm did not fault (a test that cannot go red proves nothing)"; exit 1
fi
if grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: red arm faulted for the wrong reason (a sanitizer report, not the expected check)"; exit 1
fi
for want in \
    "FAIL: a transfer to output 31 marks output 31" \
    "FAIL: an issuance instruction to output 31 marks output 31"; do
    if ! grep -qF "$want" <<<"$out"; then
        echo "FAIL: red arm did not fail the check it must: ${want#FAIL: }"; exit 1
    fi
done

echo "--- GREEN: must pass clean ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out"
if [ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" <<<"$out"; then
    echo "FAIL: green arm"; exit 1
fi

echo "PASS: asset_burn_marker_kat"
