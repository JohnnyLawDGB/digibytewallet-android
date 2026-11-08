#!/usr/bin/env bash
# Host KAT runner: coin selection does not spend a coin worth less than its own input's fee before economic coins
# (4.1.3 G3; see the _main.c header for the cases).
#
# ARMS. Shipped: no -D. Comparison: -DEFFECTIVE_VALUE_UNFIXED (coins in wallet order, dust first). 64- and 32-bit.
# EXPECTED. Shipped: every case passes. Comparison: "skip", "order" and "fallback" fail; "plain" passes.
# BRWallet.c is #included by the main, so it is NOT on the clang line. Exit code 0 = every expectation held.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1 symbolize=0"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'
LIMIT_S=120

if ! grep -q 'EFFECTIVE_VALUE_UNFIXED' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: the EFFECTIVE_VALUE_UNFIXED comparison seam is not in BRWallet.c"; exit 1
fi

# The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was
# stopped. Asking whether the watchdog is still alive does not: `wait` returns as soon as the
# kill lands, often before the watchdog subshell has exited, and a stopped run was then reported
# as one that returned rc=137 by itself (the race fixed in base58_input_bound_kat, #211).
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
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
        "$SCRIPT_DIR/effective_value_kat_main.c" \
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
    elif [ "$rc" -ge 128 ]; then got="signal $((rc - 128)) before the limit, not the watchdog (rc=$rc)"
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
    build "$BUILD_DIR/red$bits" "$bits" -DEFFECTIVE_VALUE_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    echo "--- comparison arm (-DEFFECTIVE_VALUE_UNFIXED) ---"
    expect "red$bits" "$BUILD_DIR/red$bits" skip fail
    expect "red$bits" "$BUILD_DIR/red$bits" order fail
    expect "red$bits" "$BUILD_DIR/red$bits" fallback fail
    expect "red$bits" "$BUILD_DIR/red$bits" plain pass
    echo "--- shipped arm ---"
    for c in skip order fallback plain; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "effective_value_kat: ALL PASS"; exit 0; fi
echo "effective_value_kat: $FAILS expectation(s) failed"; exit 1
