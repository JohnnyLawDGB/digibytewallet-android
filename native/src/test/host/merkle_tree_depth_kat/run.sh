#!/usr/bin/env bash
# Host KAT runner: the merkle tree depth is defined for every 32-bit transaction count (see the
# _main.c header for the cases).
#
# ARMS. Shipped: no -D. Comparison: -DMERKLE_TREE_DEPTH_UNFIXED (the depth taken as a signed int).
# Built with AddressSanitizer and UndefinedBehaviorSanitizer, in a 64-bit and a 32-bit build.
#
# EXPECTED. Shipped: every case passes, no sanitizer report. Comparison: "top" and "all" give a
# sanitizer report (signed overflow: "top" in the depth's x - 1, "all" in its counter after 2^31
# turns of a shift loop that never ends); a comparison build without UndefinedBehaviorSanitizer shows
# "all" itself: it does not return and is killed at the time limit. "ctl" passes in every build.
# Detection matches "runtime error:" / "ERROR: AddressSanitizer", never a subtype word.
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="print_stacktrace=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=10

if ! grep -q 'MERKLE_TREE_DEPTH_UNFIXED' "$CORE_DIR/BRMerkleBlock.c"; then
    echo "GATE FAILURE: the MERKLE_TREE_DEPTH_UNFIXED comparison seam is not in BRMerkleBlock.c"; exit 1
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
    local san="${SAN:-address,undefined}"
    clang -w -include stdint.h -g -fsanitize="$san" -fno-omit-frame-pointer \
        ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/merkle_tree_depth_kat_main.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lpthread -lm \
        -o "$out"
}

FAILS=0
# expect <label> <binary> <case> <want: pass|san|timeout>
expect() {
    local label="$1" bin="$2" kase="$3" want="$4" out="$BUILD_DIR/$1.$3.out" rc got
    bounded "$out" "$bin" "$kase"; rc=$?
    if grep -Eq "$SAN_RE" "$out"; then got=san
    elif [ "$rc" -eq 124 ]; then got=timeout
    elif [ "$rc" -eq 0 ] && grep -q "^CASE PASS $kase" "$out"; then got=pass
    else got="other(rc=$rc)"
    fi
    if [ "$got" = "$want" ]; then
        echo "  ok   $label $kase: $got"
    else
        echo "  FAIL $label $kase: wanted $want, got $got"; cat "$out"; FAILS=$((FAILS + 1))
    fi
    grep -E "parsed:|matched|returned|$SAN_RE" "$out" | sed -n "1,4s/^/       | /p"
}

for bits in 64 32; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DMERKLE_TREE_DEPTH_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    SAN=address build "$BUILD_DIR/redasan$bits" "$bits" -DMERKLE_TREE_DEPTH_UNFIXED || { echo "FAIL: build error (comparison, ASan only, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    echo "--- comparison arm (-DMERKLE_TREE_DEPTH_UNFIXED) ---"
    expect "red$bits" "$BUILD_DIR/red$bits" top san
    expect "red$bits" "$BUILD_DIR/red$bits" all san
    expect "red$bits" "$BUILD_DIR/red$bits" ctl pass
    echo "--- comparison arm, AddressSanitizer only ---"
    expect "redasan$bits" "$BUILD_DIR/redasan$bits" all timeout
    expect "redasan$bits" "$BUILD_DIR/redasan$bits" ctl pass
    echo "--- shipped arm ---"
    for c in top all ctl; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "merkle_tree_depth_kat: ALL PASS"; exit 0; fi
echo "merkle_tree_depth_kat: $FAILS expectation(s) failed"; exit 1
