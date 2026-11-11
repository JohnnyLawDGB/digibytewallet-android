#!/usr/bin/env bash
# Host KAT runner: PEER-PENALTY-SHOTGUN -- the fallback dial honours the peer penalty set exactly
# like the filter-first dial, and never starves the pinned own-node or the fixed peer (see the
# _main.c header for the cases).
#
# ARMS. Shipped: no -D. Comparisons: -DPEER_PENALTY_SHOTGUN_UNFIXED (the shotgun never reads the
# penalty set) and -DPEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT (the fixed peer is not exempt). 64-bit
# and 32-bit builds; every run under a time limit and with LeakSanitizer on.
#
# EXPECTED. Shipped: every case passes. UNFIXED: penalized, crowd and pinned fail (a penalized
# peer is dialed); fixed passes. NO_FIXED_EXEMPT: fixed fails (the fixed peer is not dialed).
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
LIMIT_S=60

for seam in PEER_PENALTY_SHOTGUN_UNFIXED PEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT; do
    if ! grep -q "$seam" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILURE: the $seam comparison seam is not in BRPeerManager.c"; exit 1
    fi
done

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
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_penalty_shotgun_kat_main.c" \
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
        -lm -lpthread -Wl,--wrap=pthread_create \
        -o "$out"
}

FAILS=0
# expect <label> <binary> <case> <want: pass|fail>
expect() {
    local label="$1" bin="$2" kase="$3" want="$4" out="$BUILD_DIR/$1.$3.out" rc got
    ASAN_OPTIONS="abort_on_error=1 detect_leaks=1 symbolize=0" bounded "$out" "$bin" "$kase"; rc=$?
    # A comparison arm's dial storm can leave discovery's refused-thread allocations behind; that
    # leak report follows the verdict line, so a printed CASE FAIL is the comparison outcome. The
    # shipped arm must still be leak-free (a CASE PASS followed by a leak reads as "leak").
    if grep -q "ERROR: AddressSanitizer" "$out"; then got=san
    elif grep -q "^CASE FAIL $kase" "$out"; then got=fail
    elif grep -q "ERROR: LeakSanitizer" "$out"; then got=leak
    elif [ "$rc" -eq 124 ]; then got=timeout
    elif [ "$rc" -eq 0 ] && grep -q "^CASE PASS $kase" "$out"; then got=pass
    elif grep -q "^SETUP:" "$out"; then got=setup
    elif [ "$rc" -ge 128 ]; then got="signal $((rc - 128)) before the limit, not the watchdog (rc=$rc)"
    else got="other(rc=$rc)"
    fi
    if [ "$got" = "$want" ]; then
        echo "  ok   $label $kase: $got"
    else
        echo "  FAIL $label $kase: wanted $want, got $got"; cat "$out"; FAILS=$((FAILS + 1))
    fi
    grep -E "^\[|dials|dialed|ERROR: |SUMMARY: |FAIL " "$out" | sed -n "1,5s/^/       | /p"
}

for bits in 64 32; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DPEER_PENALTY_SHOTGUN_UNFIXED || { echo "FAIL: build error (UNFIXED, $bits)"; exit 1; }
    build "$BUILD_DIR/nofix$bits" "$bits" -DPEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT || { echo "FAIL: build error (NO_FIXED_EXEMPT, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    echo "--- comparison arm (-DPEER_PENALTY_SHOTGUN_UNFIXED) ---"
    for c in penalized crowd pinned; do expect "red$bits" "$BUILD_DIR/red$bits" "$c" fail; done
    expect "red$bits" "$BUILD_DIR/red$bits" fixed pass
    echo "--- comparison arm (-DPEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT) ---"
    expect "nofix$bits" "$BUILD_DIR/nofix$bits" fixed fail
    echo "--- shipped arm ---"
    for c in penalized crowd pinned fixed; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "peer_penalty_shotgun_kat: ALL PASS"; exit 0; fi
echo "peer_penalty_shotgun_kat: $FAILS expectation(s) failed"; exit 1
