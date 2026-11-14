#!/usr/bin/env bash
# Host KAT runner: a peer gets one peer thread for its life (see the _main.c header for the cases).
#
# ARMS. Shipped: no -D. Comparison: -DPEER_CONNECT_ONCE_UNFIXED (BRPeerConnect reads "no thread"
# from the peer's status, which the peer thread sets before it frees the peer). 64-bit and 32-bit
# builds (64-bit only on Darwin, which has no 32-bit target); every run under a time limit.
#
# EXPECTED. Shipped: "exiting" and "waiting" pass, no sanitizer report. Comparison: "exiting" ends
# in an AddressSanitizer report (a second peer thread outlives the peer); "waiting" passes (the
# re-dial of a peer waiting for the network is what the fix must keep).
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
LIMIT_S=60

for f in BRPeer.c; do
    if ! grep -q 'PEER_CONNECT_ONCE_UNFIXED' "$CORE_DIR/$f"; then
        echo "GATE FAILURE: the PEER_CONNECT_ONCE_UNFIXED comparison seam is not in $f"; exit 1
    fi
done

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
    "${CC:-clang}" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_connect_once_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
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
        -lm -lpthread \
        -o "$out"
}

HAVE_LSAN=0
[ "$(uname -s)" = Linux ] && HAVE_LSAN=1
BITS_LIST="64 32"
[ "$(uname -s)" = Darwin ] && BITS_LIST="64"

FAILS=0
# expect <label> <binary> <case> <want: pass|san>
expect() {
    local label="$1" bin="$2" kase="$3" want="$4" out="$BUILD_DIR/$1.$3.out" rc got
    ASAN_OPTIONS="abort_on_error=1 detect_leaks=$HAVE_LSAN symbolize=0" bounded "$out" "$bin" "$kase"; rc=$?
    if grep -q "ERROR: AddressSanitizer" "$out"; then got=san
    elif grep -q "ERROR: LeakSanitizer" "$out"; then got=leak
    elif [ "$rc" -eq 124 ]; then got=timeout
    elif [ "$rc" -eq 0 ] && grep -q "^CASE PASS $kase" "$out"; then got=pass
    elif grep -q "^SETUP:" "$out"; then got=setup
    else got="other(rc=$rc)"
    fi
    if [ "$got" = "$want" ]; then
        echo "  ok   $label $kase: $got"
    else
        echo "  FAIL $label $kase: wanted $want, got $got"; cat "$out"; FAILS=$((FAILS + 1))
    fi
    grep -E "^\[|second|ran|ERROR: |SUMMARY: |FAIL " "$out" | sed -n "1,6s/^/       | /p"
}

for bits in $BITS_LIST; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DPEER_CONNECT_ONCE_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    echo "--- comparison arm (-DPEER_CONNECT_ONCE_UNFIXED) ---"
    expect "red$bits" "$BUILD_DIR/red$bits" exiting san
    expect "red$bits" "$BUILD_DIR/red$bits" waiting pass
    echo "--- shipped arm ---"
    for c in exiting waiting; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "peer_connect_once_kat: ALL PASS"; exit 0; fi
echo "peer_connect_once_kat: $FAILS expectation(s) failed"; exit 1
