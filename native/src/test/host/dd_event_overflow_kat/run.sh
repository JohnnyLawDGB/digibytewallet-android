#!/usr/bin/env bash
# dd_event_overflow_kat — a DigiDollar activity amount cannot overflow (4.1.2 row 10, N5). See the _main.c.
#   RED    -DDD_EVENT_OVERFLOW_UNFIXED=1 under UBSan: case A overflows (a sanitizer report or a wrapped row).
#   GREEN  every case passes, UBSan clean.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
[ "$(grep -c DD_EVENT_OVERFLOW_UNFIXED "$BRIDGE_DIR/digidollar_event.h")" -ge 1 ] || { echo "GATE FAILURE: the seam is missing"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -g -fsanitize=signed-integer-overflow,undefined -fno-sanitize-recover=all \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" "$SCRIPT_DIR/dd_event_overflow_kat_main.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -o "$out"
}
FAIL=0
build "$BUILD_DIR/red" -DDD_EVENT_OVERFLOW_UNFIXED=1 || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout 60 "$BUILD_DIR/red" 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -q "FAIL: setup" && <<<"$OUT" grep -qE "signed integer overflow|^FAIL: A:"; then
    echo "  [red] case A overflows, as required: $(<<<"$OUT" grep -m1 -oE 'signed integer overflow|FAIL: A:.*')"
else echo "GATE FAILURE: the red arm did not overflow on case A (rc=$RC)"; <<<"$OUT" tail -3; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout 60 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -q "runtime error" && <<<"$OUT" grep -q "^PASS: dd_event_overflow_kat"; then echo "  [green] all cases pass, UBSan clean"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|runtime error" | head -4; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: dd_event_overflow_kat (A red-then-green, B guard; UBSan)"; exit 0; }
echo "FAIL: dd_event_overflow_kat"; exit 1
