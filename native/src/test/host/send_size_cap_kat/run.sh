#!/usr/bin/env bash
# Host KAT runner: coin selection never changes a caller's output; when the coins it would need do
# not fit TX_MAX_SIZE the build is refused (see the _main.c header for the cases).
#
# ARMS. Shipped: no -D. Comparison: -DSEND_SIZE_CAP_UNFIXED (the earlier shape, which rebuilt the
# send with its last output lowered, or with the last output removed). Both arms are built with
# -DNDEBUG, as a release build is: the earlier shape's "outCount > 0" assert is compiled out there.
# Each arm runs in a 64-bit and a 32-bit build.
#
# EXPECTED. Shipped: every case passes, no sanitizer report. Comparison: "small" reports a
# transaction paying less than asked (a FAIL line, no sanitizer report needed); "tiny" ends in a
# sanitizer report (the read before the first output); "fits" and "plain" pass in both arms.
# Every run is under a time limit.
#
# BRWallet.c is #included by the main, so it is NOT on the clang line.
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=120

if ! grep -q 'SEND_SIZE_CAP_UNFIXED' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: the SEND_SIZE_CAP_UNFIXED comparison seam is not in BRWallet.c"; exit 1
fi

bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -O1 -fsanitize=address -fno-omit-frame-pointer -DNDEBUG \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/send_size_cap_kat_main.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lpthread -lm \
        -o "$out"
}

FAILS=0
# expect <label> <binary> <case> <want: pass|fail|san>
expect() {
    local label="$1" bin="$2" kase="$3" want="$4" out="$BUILD_DIR/$1.$3.out" rc got
    bounded "$out" "$bin" "$kase"; rc=$?
    if grep -Eq "$SAN_RE" "$out"; then got=san
    elif [ "$rc" -eq 124 ]; then got=timeout
    elif [ "$rc" -eq 0 ] && grep -q "^CASE PASS $kase" "$out"; then got=pass
    elif [ "$rc" -eq 1 ] && grep -q "^CASE FAIL $kase" "$out"; then got=fail
    else got="other(rc=$rc)"
    fi
    if [ "$got" = "$want" ]; then
        echo "  ok   $label $kase: $got"
    else
        echo "  FAIL $label $kase: wanted $want, got $got"; cat "$out"; FAILS=$((FAILS + 1))
    fi
    grep -E "result:|output\[|FAIL |$SAN_RE" "$out" | sed -n "1,6s/^/       | /p"
}

for bits in 64 32; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DSEND_SIZE_CAP_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    echo "--- comparison arm (-DSEND_SIZE_CAP_UNFIXED) ---"
    expect "red$bits" "$BUILD_DIR/red$bits" small fail
    expect "red$bits" "$BUILD_DIR/red$bits" tiny san
    expect "red$bits" "$BUILD_DIR/red$bits" fits pass
    expect "red$bits" "$BUILD_DIR/red$bits" plain pass
    echo "--- shipped arm ---"
    for c in small tiny fits plain; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "send_size_cap_kat: ALL PASS"; exit 0; fi
echo "send_size_cap_kat: $FAILS expectation(s) failed"; exit 1
