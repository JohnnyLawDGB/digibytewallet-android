#!/usr/bin/env bash
# Host KAT runner for INT-2026-10-09-E: at difficulty level 2 a header off the main chain that cannot be judged is
# refused; a header that extends the tip keeps the skip; a short reorg near the tip is judged and accepted. See
# int_2026_10_09_e_kat_main.c for the cases, the entry layer and the fixtures.
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer. The main file #includes BRPeer.c
# and BRPeerManager.c (for their file-statics), so neither is a separate unit.
#
# ARMS. Both are built at the header check levels the app ships, read from native/build.gradle.kts (this suite names
# them itself, so it means the same thing run alone or from scripts/run-host-kats.sh).
#   red    -DINT_2026_10_09_E_UNFIXED (the earlier shape): each RED case MUST fail, each GUARD case MUST pass
#   green  no seam flag: every case MUST pass
# Every case, both arms, 64-bit and 32-bit: no sanitizer report.
#
#   run.sh       the KAT
#   run.sh gen   rewrite int_2026_10_09_e_vectors.inc (the branch headers; a nonce search over odo, minutes)
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

RED_CASES=(unjudged_fork_refused)
GUARD_CASES=(unjudged_extends_tip_kept unjudged_known_header_kept short_reorg_near_tip honest_chain_wins_after_unjudged_first)

# the one level the app build sets for a header check macro
shipped_level() {
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$REPO_ROOT/native/build.gradle.kts" 2>/dev/null | sort -u)"
    [ "$(printf '%s' "$found" | grep -c .)" -eq 1 ] || return 1
    printf '%s' "${found#D$1=}"
}
POW_LEVEL="$(shipped_level DGB_HEADER_POW_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_POW_CHECK"; exit 1; }
DIFF_LEVEL="$(shipped_level DGB_HEADER_DIFF_CHECK)" || { echo "GATE FAILURE: no single shipped DGB_HEADER_DIFF_CHECK"; exit 1; }
if [ "$DIFF_LEVEL" -lt 2 ]; then
    echo "GATE FAILURE: the app ships difficulty level $DIFF_LEVEL; this suite asserts the level-2 refusal."
    exit 1
fi

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

# build <out> <bits: 64|32> [extra flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DDEBUG: on the host peer_log is compiled out without it (BRPeer.h); the log lines are evidence.
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer \
        -DDGB_HEADER_POW_CHECK="$POW_LEVEL" -DDGB_HEADER_DIFF_CHECK="$DIFF_LEVEL" "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        -I "$SCRIPT_DIR" \
        -I "$SCRIPT_DIR/../header_diff_v4_kat" \
        "$SCRIPT_DIR/int_2026_10_09_e_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

if [ "${1:-}" = "gen" ]; then
    clang -w -include stdint.h -O2 -DINT_2026_10_09_E_GEN -DDGB_HEADER_POW_CHECK=2 -DDGB_HEADER_DIFF_CHECK=2 \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" -I "$SCRIPT_DIR" \
        -I "$SCRIPT_DIR/../header_diff_v4_kat" "$SCRIPT_DIR/int_2026_10_09_e_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$BUILD_DIR/gen" || { echo "gen: build error"; exit 1; }
    E_KAT_VECTORS_OUT="$BUILD_DIR/vectors.inc" "$BUILD_DIR/gen" gen > /dev/null 2>&1 || { echo "gen: failed"; exit 1; }
    mv "$BUILD_DIR/vectors.inc" "$SCRIPT_DIR/int_2026_10_09_e_vectors.inc"
    echo "wrote $SCRIPT_DIR/int_2026_10_09_e_vectors.inc"
    exit 0
fi

# ---- verify the seam is real ---------------------------------------------------
if [ "$(grep -c 'INT_2026_10_09_E_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeerManager.c has no INT_2026_10_09_E_UNFIXED seam; -D would be inert."
    exit 1
fi
echo "levels: proof of work $POW_LEVEL, difficulty target $DIFF_LEVEL (shipped, from native/build.gradle.kts)"

FAIL=0

run_case() {
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0" "$1" "$2" 2>&1)"
    RC=$?
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DINT_2026_10_09_E_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    local known listed
    known="$("$BUILD_DIR/green${bits}" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${RED_CASES[@]}" "${GUARD_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    echo "--- RED ARM (-DINT_2026_10_09_E_UNFIXED): each RED case MUST fail ---"
    for c in "${RED_CASES[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c"
        if echo "$OUT" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"
            echo "$OUT" | grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
        elif echo "$OUT" | grep -q "RESULT $c fail" && ! echo "$OUT" | grep -q '^FAIL: setup'; then
            echo "  [$bits red $c] failed, as required: $(echo "$OUT" | grep -m1 '^FAIL:') | $(echo "$OUT" | grep -m1 '^NOTE:' | cut -c1-150)"
        else
            echo "  [$bits red $c] GATE FAILURE: did not fail on what it asserts (rc=$RC)"
            echo "$OUT" | grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    echo "--- RED ARM: each GUARD case MUST pass ---"
    for c in "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c"
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass"; then
            echo "  [$bits red $c] passed (guard)"
        else
            echo "  [$bits red $c] GATE FAILURE: guard did not pass in the comparison arm (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    echo "--- GREEN ARM (shipped): every case MUST pass, no sanitizer report ---"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass"; then
            echo "  [$bits green $c] passed: $(echo "$OUT" | grep -m1 '^NOTE:' | cut -c1-150)"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done

    # the log line a device check greps for
    run_case "$BUILD_DIR/green${bits}" unjudged_fork_refused
    echo "$OUT" | grep -q "diff-unjudged-fork h=" \
        && echo "  [$bits green] log: '$(echo "$OUT" | grep -m1 -o 'diff-unjudged-fork.*' | cut -c1-140)'" \
        || { echo "  [$bits green] GATE FAILURE: the refusal log line is missing"; FAIL=1; }
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: int_2026_10_09_e_kat (${#RED_CASES[@]} red-then-green, ${#GUARD_CASES[@]} guards; levels $POW_LEVEL/$DIFF_LEVEL; 64-bit and 32-bit, ASan)"
    exit 0
else
    echo "FAIL: int_2026_10_09_e_kat"
    exit 1
fi
