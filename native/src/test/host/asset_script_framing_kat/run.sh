#!/usr/bin/env bash
# Host KAT: the native asset-script reader recognises both standard push framings, and reads
# only the payload the push declares.
#
# A payload of 76 bytes or more can only be framed with OP_PUSHDATA1 (0x4c). A canonical
# PUSHDATA1 asset carrier is recognised, and the output its instruction names is held as an
# asset output; a single length byte of 76 is not a framing;
# and bytes after the declared payload are not instructions.
#
# RED-BEFORE-GREEN GATE (macro convention: PRESENCE of -DASSET_SCRIPT_FRAMING_UNFIXED, #ifdef):
#   RED  : restores the earlier shape of the payload lookup. Each of the three RED-THEN-GREEN
#          checks must FAIL (a failed assertion IS the red evidence here), with no sanitizer
#          report.
#   GREEN: every check passes -> exit 0, no sanitizer report.
#
# Exit code 0 = red arm failed all three named checks AND green passed clean; 1 = otherwise.
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
        "$SCRIPT_DIR/asset_script_framing_kat_main.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        -lm -o "$out"
}

echo "=== building RED (earlier payload lookup) ==="
build "$BUILD_DIR/red" -DASSET_SCRIPT_FRAMING_UNFIXED || { echo "FAIL: red arm did not build"; exit 1; }
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
    "FAIL: a canonical PUSHDATA1 asset script marks the output its instruction names" \
    "FAIL: a 76-byte payload behind a single length byte is not a valid asset script" \
    "FAIL: bytes after the declared payload are not read as instructions"; do
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

echo "PASS: asset_script_framing_kat"
