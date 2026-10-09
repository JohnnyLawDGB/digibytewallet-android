#!/usr/bin/env bash
# Host KAT runner for BB-2026-10-09-harley (F1-F4): a parser of the wallet's own saved
# data never reads or writes outside the blob, on 32-bit as on 64-bit; a malformed blob
# is rejected or treated as empty; a well-formed blob round-trips. See
# saved_data_bounds_kat_main.c for the parsers, the blobs and the cases.
#
# Compiles the REAL, live sources with AddressSanitizer: core BRPeerPenalty.h and
# BRSavedBlocks.h, and the bridge's saved_peers_deserialize.h,
# saved_blocks_deserialize.h and saved_transactions_deserialize.h (the JNI-free cores of
# loadSavedPeers, loadSavedBlocks and loadSerializedTransactions), linked against the core
# units BRMerkleBlockParse and BRTransactionParse need.
#
# ARMS, each in a 64-bit and a 32-bit (-m32) build, at the header check levels the app
# ships (read from native/build.gradle.kts):
#   red    -DBB_2026_10_09_HARLEY_UNFIXED (the earlier line at each site)
#            * each RED case MUST draw a sanitizer report in the word size it names:
#              the wrap cases (F1, F3, F4, BRSavedBlocks.h) at 32 bits only -- at 64 bits
#              the earlier guard holds, so there they MUST pass (the 64-bit control);
#              the peers allocation cases (F2) at both word sizes
#            * each GUARD case MUST pass
#   green  no seam flag: every case MUST pass with no sanitizer report
#
# ASan's allocator is capped (max_allocation_size_mb) and told to return NULL rather than
# abort, so an allocation a phone could not satisfy returns NULL here as it would there.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0 allocator_may_return_null=1 max_allocation_size_mb=256"

WRAP_CASES=(penalty_wrap blocks_wrap core_blocks_wrap txs_wrap)   # red at 32 bits; 64-bit control
ALLOC_CASES=(peers_count_huge peers_calloc_null)                  # red at both word sizes
GUARD_CASES=(penalty_roundtrip peers_roundtrip blocks_roundtrip txs_roundtrip)

shipped_level() {
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$REPO_ROOT/native/build.gradle.kts" 2>/dev/null | sort -u)"
    [ "$(printf '%s' "$found" | grep -c .)" -eq 1 ] || return 1
    printf '%s' "${found#D$1=}"
}
POW_LEVEL="$(shipped_level DGB_HEADER_POW_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_POW_CHECK"; exit 1; }
DIFF_LEVEL="$(shipped_level DGB_HEADER_DIFF_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_DIFF_CHECK"; exit 1; }

# ---- verify the seam is real ---------------------------------------------------
# -D silently does nothing if the source lost its seam; the red arm would then be the
# green arm under another name.
for f in "$CORE_DIR/BRPeerPenalty.h" "$CORE_DIR/BRSavedBlocks.h" \
         "$BRIDGE_DIR/saved_peers_deserialize.h" "$BRIDGE_DIR/saved_blocks_deserialize.h" \
         "$BRIDGE_DIR/saved_transactions_deserialize.h"; do
    if ! grep -q 'BB_2026_10_09_HARLEY_UNFIXED' "$f"; then
        echo "GATE FAILURE: $f has no BB_2026_10_09_HARLEY_UNFIXED seam; -D would be inert."
        exit 1
    fi
done
# The JNI loaders must parse through the guarded helpers, or the KAT tests code the app does not run.
grep -q 'deserialize_saved_peers_guarded' "$BRIDGE_DIR/jni_peer.c" &&
grep -q 'deserialize_saved_blocks_guarded' "$BRIDGE_DIR/jni_peer.c" &&
grep -q 'deserialize_saved_transactions_guarded' "$BRIDGE_DIR/jni_transaction_persist.c" || {
    echo "GATE FAILURE: a JNI loader no longer parses through its guarded helper."
    exit 1
}

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# build <out> <bits: 64|32> [extra flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        -DDGB_HEADER_POW_CHECK="$POW_LEVEL" -DDGB_HEADER_DIFF_CHECK="$DIFF_LEVEL" "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/saved_data_bounds_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

FAIL=0

run_case() {   # sets OUT and RC
    OUT="$("$1" "$2" 2>&1)"
    RC=$?
}

clean_pass() { ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $1 pass"; }

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DBB_2026_10_09_HARLEY_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    local known listed
    known="$("$BUILD_DIR/green${bits}" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${WRAP_CASES[@]}" "${ALLOC_CASES[@]}" "${GUARD_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    local must_fault=("${ALLOC_CASES[@]}") control=()
    if [ "$bits" = "32" ]; then must_fault+=("${WRAP_CASES[@]}"); else control=("${WRAP_CASES[@]}"); fi

    echo "--- RED ARM (-DBB_2026_10_09_HARLEY_UNFIXED): each case MUST draw a sanitizer report ---"
    for c in "${must_fault[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c"
        if echo "$OUT" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] tripped (rc=$RC): $(echo "$OUT" | grep -Eom1 "($SAN_RE).*" | cut -c1-110)"
        else
            echo "  [$bits red $c] GATE FAILURE: no sanitizer report (rc=$RC)"
            echo "$OUT" | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    if [ "${#control[@]}" -gt 0 ]; then
        echo "--- RED ARM, 64-bit control: the earlier guard holds where size_t cannot wrap ---"
        for c in "${control[@]}"; do
            run_case "$BUILD_DIR/red${bits}" "$c"
            if clean_pass "$c"; then
                echo "  [$bits red $c] rejected cleanly (rc=$RC): the defect is the 32-bit wrap"
            else
                echo "  [$bits red $c] GATE FAILURE: the 64-bit control did not pass (rc=$RC)"
                echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -8; FAIL=1
            fi
        done
    fi

    echo "--- RED ARM: each GUARD case MUST pass ---"
    for c in "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c"
        if clean_pass "$c"; then
            echo "  [$bits red $c] passed (guard)"
        else
            echo "  [$bits red $c] GATE FAILURE: guard did not pass in the comparison arm (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    echo "--- GREEN ARM (shipped): every case MUST pass, no sanitizer report ---"
    for c in "${WRAP_CASES[@]}" "${ALLOC_CASES[@]}" "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if clean_pass "$c"; then
            echo "  [$bits green $c] passed ($(echo "$OUT" | grep -c '^PASS:') checks)"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done
}

echo "levels: proof of work $POW_LEVEL, difficulty target $DIFF_LEVEL (shipped, from native/build.gradle.kts)"
run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: saved_data_bounds_kat (${#WRAP_CASES[@]} wrap cases red at 32 bits, ${#ALLOC_CASES[@]} allocation cases red at both, ${#GUARD_CASES[@]} guards; 64-bit and 32-bit, ASan)"
    exit 0
else
    echo "FAIL: saved_data_bounds_kat"
    exit 1
fi
