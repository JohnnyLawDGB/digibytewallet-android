#!/usr/bin/env bash
# Host KAT runner: a wallet transaction is confirmed only into a block whose header the wallet judged, or one a
# checkpoint the chain has reached pins; an unjudged header run is not persisted. See judged_confirmation_kat_main.c
# for the cases, the entry layer and the fixtures.
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer. The main file #includes BRPeer.c
# and BRPeerManager.c (for their file-statics), so neither is a separate unit.
#
# ARMS. Both are built at the header check levels the app ships, read from native/build.gradle.kts (this suite names
# them itself, so it means the same thing run alone or from scripts/run-host-kats.sh).
#   red    -DJUDGED_CONFIRMATION_UNFIXED (no rule): each RED case MUST fail, every other case not in RED2 MUST pass
#   red2   -DJUDGED_CONFIRMATION_HOLD_UNFIXED (the rule without the held-state fixes): each RED2 case MUST fail,
#          every other case MUST pass
#   green  no seam flag: every case MUST pass
# Every case, both arms, 64-bit and 32-bit: no sanitizer report.
#
#   run.sh       the KAT
#   run.sh gen   rewrite judged_confirmation_vectors.inc (the synthetic headers; a nonce search, minutes)
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# arm 1 (-DJUDGED_CONFIRMATION_UNFIXED): no confirmation rule at all
RED_CASES=(unjudged_receive_not_confirmed held_receive_confirmed_at_checkpoint unjudged_delivery_not_trusted
           unjudged_run_not_persisted)
# arm 2 (-DJUDGED_CONFIRMATION_HOLD_UNFIXED): the rule holds receives, but a held one is stored as scanned and its
# block may be pruned before the checkpoint pins it
RED2_CASES=(held_receive_survives_restart held_block_stays_resident)
GUARD_CASES=(judged_receive_confirmed network_without_contexts_unchanged server_height_bounds_refused
             server_height_pinned_confirmable server_height_judged_confirmable)
RED3_CASES=(server_height_above_tip_refused server_height_unjudged_refused)

# the one level the app build sets for a header check macro
shipped_level() {
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$REPO_ROOT/native/build.gradle.kts" 2>/dev/null | sort -u)"
    [ "$(<<<"$found" grep -c .)" -eq 1 ] || return 1
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
        "$SCRIPT_DIR/judged_confirmation_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

if [ "${1:-}" = "gen" ]; then
    clang -w -include stdint.h -O2 -DJUDGED_CONFIRMATION_GEN -DDGB_HEADER_POW_CHECK=2 -DDGB_HEADER_DIFF_CHECK=2 \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" -I "$SCRIPT_DIR" \
        -I "$SCRIPT_DIR/../header_diff_v4_kat" "$SCRIPT_DIR/judged_confirmation_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$BUILD_DIR/gen" || { echo "gen: build error"; exit 1; }
    JC_KAT_VECTORS_OUT="$BUILD_DIR/vectors.inc" "$BUILD_DIR/gen" gen > /dev/null 2>&1 || { echo "gen: failed"; exit 1; }
    mv "$BUILD_DIR/vectors.inc" "$SCRIPT_DIR/judged_confirmation_vectors.inc"
    echo "wrote $SCRIPT_DIR/judged_confirmation_vectors.inc"
    exit 0
fi

# ---- verify the seam is real ---------------------------------------------------
for seam in JUDGED_CONFIRMATION_UNFIXED JUDGED_CONFIRMATION_HOLD_UNFIXED SERVER_HEIGHT_UNFIXED; do
    if [ "$(grep -c "$seam" "$CORE_DIR/BRPeerManager.c")" -lt 1 ]; then
        echo "GATE FAILURE: BRPeerManager.c has no $seam seam; -D would be inert."
        exit 1
    fi
done
echo "levels: proof of work $POW_LEVEL, difficulty target $DIFF_LEVEL (shipped, from native/build.gradle.kts)"

FAIL=0

run_case() {
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0" timeout 300 "$1" "$2" 2>&1)"
    RC=$?
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DJUDGED_CONFIRMATION_UNFIXED ||
       ! build "$BUILD_DIR/redb${bits}" "$bits" -DJUDGED_CONFIRMATION_HOLD_UNFIXED ||
       ! build "$BUILD_DIR/redc${bits}" "$bits" -DSERVER_HEIGHT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    local known listed
    known="$("$BUILD_DIR/green${bits}" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${RED_CASES[@]}" "${RED2_CASES[@]}" "${RED3_CASES[@]}" "${GUARD_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    # comparison_arm <tag> <binary> <flag> <must-fail cases...> -- <must-pass cases...>
    comparison_arm() {
        local tag="$1" bin="$2" flag="$3"; shift 3
        local fails=() passes=() c
        while [ $# -gt 0 ] && [ "$1" != "--" ]; do fails+=("$1"); shift; done
        shift; passes=("$@")
        echo "--- $tag ARM ($flag): each listed case MUST fail on what it asserts ---"
        for c in "${fails[@]}"; do
            run_case "$bin" "$c"
            if <<<"$OUT" grep -Eq "$SAN_RE"; then
                echo "  [$bits $tag $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"
                <<<"$OUT" grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
            elif <<<"$OUT" grep -q "RESULT $c fail" && ! <<<"$OUT" grep -q '^FAIL: setup'; then
                echo "  [$bits $tag $c] failed, as required: $(<<<"$OUT" grep -m1 '^FAIL:') | $(<<<"$OUT" grep -m1 '^NOTE:' | cut -c1-150)"
            else
                echo "  [$bits $tag $c] GATE FAILURE: did not fail on what it asserts (rc=$RC)"
                <<<"$OUT" grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
            fi
        done
        echo "--- $tag ARM: every other case MUST pass ---"
        for c in "${passes[@]}"; do
            run_case "$bin" "$c"
            if ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "RESULT $c pass"; then
                echo "  [$bits $tag $c] passed"
            else
                echo "  [$bits $tag $c] GATE FAILURE: did not pass in the comparison arm (rc=$RC)"
                <<<"$OUT" grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -8; FAIL=1
            fi
        done
    }
    # arm 1 holds nothing, so the held-state cases cannot reach their assertion there; they are arm 2's
    comparison_arm red "$BUILD_DIR/red${bits}" -DJUDGED_CONFIRMATION_UNFIXED "${RED_CASES[@]}" -- "${GUARD_CASES[@]}"
    comparison_arm red2 "$BUILD_DIR/redb${bits}" -DJUDGED_CONFIRMATION_HOLD_UNFIXED "${RED2_CASES[@]}" -- \
        "${RED_CASES[@]}" "${GUARD_CASES[@]}"
    # arm 3 changes only the server-height rule: every other case behaves as shipped
    comparison_arm red3 "$BUILD_DIR/redc${bits}" -DSERVER_HEIGHT_UNFIXED "${RED3_CASES[@]}" -- \
        "${RED_CASES[@]}" "${RED2_CASES[@]}" "${GUARD_CASES[@]}"

    echo "--- GREEN ARM (shipped): every case MUST pass, no sanitizer report ---"
    for c in "${RED_CASES[@]}" "${RED2_CASES[@]}" "${RED3_CASES[@]}" "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "RESULT $c pass"; then
            echo "  [$bits green $c] passed: $(<<<"$OUT" grep -m1 '^NOTE:' | cut -c1-150)"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$RC)"
            <<<"$OUT" grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done

    # the log line a device check greps for
    run_case "$BUILD_DIR/green${bits}" unjudged_receive_not_confirmed
    <<<"$OUT" grep -q "confirm-held h=" \
        && echo "  [$bits green] log: '$(<<<"$OUT" grep -m1 -o 'confirm-held.*' | cut -c1-140)'" \
        || { echo "  [$bits green] GATE FAILURE: the hold log line is missing"; FAIL=1; }
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: judged_confirmation_kat ($(( ${#RED_CASES[@]} + ${#RED2_CASES[@]} + ${#RED3_CASES[@]} )) red-then-green, ${#GUARD_CASES[@]} guards; levels $POW_LEVEL/$DIFF_LEVEL; 64-bit and 32-bit, ASan)"
    exit 0
else
    echo "FAIL: judged_confirmation_kat"
    exit 1
fi
