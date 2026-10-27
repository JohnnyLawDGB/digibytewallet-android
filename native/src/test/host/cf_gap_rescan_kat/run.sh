#!/usr/bin/env bash
# Host KAT runner for owner checklist Q18: when the wallet's watched address set grows while a requested block hands
# its transactions over, the compact-filter scan re-checks every height at and above that block against the grown
# set, so a payment to an address beyond the original look-ahead window is found. See cf_gap_rescan_kat_main.c for
# the defect, the cases and the fixture.
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer. The main file #includes BRPeer.c
# and BRPeerManager.c (for their file-statics), so neither is a separate unit. CORE_DIR may be set in the
# environment to run the suite against another checkout of the core (a worktree of a core branch under review).
#
# ARMS, all built at the header check levels the app ships, read from native/build.gradle.kts:
#   red   -DCF_GAP_RESCAN_UNFIXED              the detection compiled out (the earlier shape)
#   red2  -DCF_GAP_RESCAN_FROM_HEIGHT_UNFIXED  the rescan starts at the delivering block, not the scan frontier
#   green no seam flag (shipped)
# RED cases must FAIL in the red arm, RED2 in red2; every other case must PASS in each
# comparison arm; every case must PASS in the green arm. Every case, every arm, 64-bit and 32-bit: no sanitizer report.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

RED_CASES=(
    beyond_window_found
    out_of_order_found
    delivering_block_rescanned
    abandoned_floor_respected
    frontier_start
    completed_height_rescanned
    top_height_rescanned
    below_start_clamped
)
RED2_CASES=(
    out_of_order_found
)
GUARD_CASES=(
    in_window_no_rescan
    unarmed_no_rescan
)
ALL_CASES=("${RED_CASES[@]}" "${GUARD_CASES[@]}")

# the one level the app build sets for a header check macro
shipped_level() {
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$REPO_ROOT/native/build.gradle.kts" 2>/dev/null | sort -u)"
    [ "$(<<<"$found" grep -c .)" -eq 1 ] || return 1
    printf '%s' "${found#D$1=}"
}
POW_LEVEL="$(shipped_level DGB_HEADER_POW_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_POW_CHECK"; exit 1; }
DIFF_LEVEL="$(shipped_level DGB_HEADER_DIFF_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_DIFF_CHECK"; exit 1; }

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCompactFilterChain.c"
    "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRCFScanLedger.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DDEBUG: on the host peer_log is compiled out without it (BRPeer.h); the log lines are evidence.
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer \
        -DDGB_HEADER_POW_CHECK="$POW_LEVEL" -DDGB_HEADER_DIFF_CHECK="$DIFF_LEVEL" ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_gap_rescan_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

# ---- verify the seam is real ---------------------------------------------------
# -D silently loses to a plain #define and -w hides the warning, so confirm the sources carry the seams.
if [ "$(grep -c 'CF_GAP_RESCAN_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 3 ]; then
    echo "GATE FAILURE: BRPeerManager.c has fewer than three CF_GAP_RESCAN_UNFIXED seams; -D would be inert."
    exit 1
fi
if [ "$(grep -c 'CF_GAP_RESCAN_FROM_HEIGHT_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeerManager.c has no CF_GAP_RESCAN_FROM_HEIGHT_UNFIXED seam; -D would be inert."
    exit 1
fi

echo "levels: proof of work $POW_LEVEL, difficulty target $DIFF_LEVEL (shipped, from native/build.gradle.kts)"

FAIL=0

# run_case <bin> <case> -> sets OUT and RC
run_case() {
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0" "$1" "$2" 2>&1)"
    RC=$?
}

# comparison_arm <arm> <bits> <flag> <space-separated cases that must fail>: the listed cases must fail, every other
# case must pass
comparison_arm() {
    local arm="$1" bits="$2" flag="$3" mustFail=" $4 " c
    echo "--- ${arm} ARM (${flag}): each of [${4}] MUST fail, every other case MUST pass ---"
    for c in "${ALL_CASES[@]}"; do
        run_case "$BUILD_DIR/${arm}${bits}" "$c"
        if <<<"$OUT" grep -Eq "$SAN_RE"; then
            echo "  [$bits $arm $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"
            <<<"$OUT" grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
        elif [ "${mustFail/ $c /}" != "$mustFail" ]; then
            if <<<"$OUT" grep -q "RESULT $c fail"; then
                echo "  [$bits $arm $c] failed, as required: $(<<<"$OUT" grep -m1 '^FAIL:')"
            else
                echo "  [$bits $arm $c] GATE FAILURE: did not fail (rc=$RC); this case cannot see what the fix governs"
                <<<"$OUT" grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
            fi
        elif <<<"$OUT" grep -q "RESULT $c pass"; then
            echo "  [$bits $arm $c] passed (not governed by this arm)"
        else
            echo "  [$bits $arm $c] GATE FAILURE: did not pass in the ${arm} arm (rc=$RC)"
            <<<"$OUT" grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
        fi
    done
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DCF_GAP_RESCAN_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/red2${bits}" "$bits" -DCF_GAP_RESCAN_FROM_HEIGHT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red2 arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    local known listed
    known="$("$BUILD_DIR/green${bits}" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${ALL_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    comparison_arm "red" "$bits" "-DCF_GAP_RESCAN_UNFIXED" "${RED_CASES[*]}"
    comparison_arm "red2" "$bits" "-DCF_GAP_RESCAN_FROM_HEIGHT_UNFIXED" "${RED2_CASES[*]}"

    echo "--- GREEN ARM (shipped): every case MUST pass, no sanitizer report ---"
    for c in "${ALL_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "RESULT $c pass"; then
            echo "  [$bits green $c] passed: $(<<<"$OUT" grep -m1 '^NOTE:' | cut -c1-140)"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$RC)"
            <<<"$OUT" grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done

    # the log line a device check greps for
    run_case "$BUILD_DIR/green${bits}" beyond_window_found
    <<<"$OUT" grep -q "cf-gap: the wallet's address set grew at block" \
        && echo "  [$bits green] log: '$(<<<"$OUT" grep -m1 -o "cf-gap: .*" | cut -c1-140)'" \
        || { echo "  [$bits green] GATE FAILURE: the cf-gap log line is missing"; FAIL=1; }
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: cf_gap_rescan_kat (${#RED_CASES[@]} red-then-green, ${#RED2_CASES[@]} red2-then-green, ${#GUARD_CASES[@]} guards; levels $POW_LEVEL/$DIFF_LEVEL; 64-bit and 32-bit, ASan)"
    exit 0
else
    echo "FAIL: cf_gap_rescan_kat"
    exit 1
fi
