#!/usr/bin/env bash
# Host KAT runner: a transaction reaches the wallet only as the answer to a request this wallet made
# of that peer, or inside a full block this wallet asked that peer for whose transactions hash to the
# block header's committed merkle root; nothing from a block delivery is registered or confirmed
# before both hold.
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer. The main file
# #includes BRPeer.c and BRPeerManager.c (for their file-statics), so neither is a separate unit.
# See block_delivery_gate_kat_main.c for the entry layer and the fixture.
#
# ARMS. Red is -DBLOCK_DELIVERY_GATE_UNFIXED (the earlier shape); green is no -D at all, so a
# shipped build, which never defines the flag, always gets the gated code.
#   RED cases   must FAIL in the red arm and PASS in the green arm (red-then-green).
#   GUARD cases must PASS in both arms (what the gate must keep working).
# Every case, both arms, both word sizes: no sanitizer report. The green arm of every case in which
# nothing may reach the wallet also runs with LeakSanitizer on: an ignored message is released.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

RED_CASES=(
    unsolicited_block_resident_header
    unsolicited_block_unknown_header
    solicited_block_root_mismatch
    tx_after_unrelated_getdata
    tx_answered_twice
    block_answered_twice
    request_set_bound
    notfound_consumes_request
    manager_confirm_needs_verified
)
GUARD_CASES=(
    real_block_verified
    solicited_block_verified_confirms
    solicited_block_header_not_resident
    requested_tx_registered
    merkleblock_proven_tx
    header_pow_close_tag
    algo_height_close_tag
    future_header_not_pow_tag
)
# The close tag of a header refused for its proof of work. These cases follow the build's level (above:
# whatever the caller compiles at; the aggregate runner passes the shipped level). They are also run in a
# dedicated level-2 arm below, which names its level itself. GUARD: the tag is new, so there is no
# earlier shape for a red arm to build.
TAG_CASES=(
    header_pow_close_tag
    algo_height_close_tag
    future_header_not_pow_tag
)
# Cases in which nothing may reach the wallet: their green arm is also run leak-checked.
LEAK_CASES=(
    unsolicited_block_resident_header
    unsolicited_block_unknown_header
    tx_after_unrelated_getdata
    notfound_consumes_request
)

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
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/block_delivery_gate_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

# ---- verify the seam is real ---------------------------------------------------
# -D silently loses to a plain #define and -w hides the warning, so confirm the edited sources
# carry the seam before trusting the red arm.
if [ "$(grep -c 'BLOCK_DELIVERY_GATE_UNFIXED' "$CORE_DIR/BRPeer.c")" -lt 4 ]; then
    echo "GATE FAILURE: BRPeer.c has fewer than four BLOCK_DELIVERY_GATE_UNFIXED seams; -D would be inert."
    exit 1
fi
if [ "$(grep -c 'BLOCK_DELIVERY_GATE_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 2 ]; then
    echo "GATE FAILURE: BRPeerManager.c has fewer than two BLOCK_DELIVERY_GATE_UNFIXED seams; -D would be inert."
    exit 1
fi

FAIL=0

# run_case <bin> <case> <leaks 0|1> -> sets OUT and RC
run_case() {
    local bin="$1" c="$2" leaks="$3"
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=$leaks symbolize=0" "$bin" "$c" 2>&1)"
    RC=$?
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DBLOCK_DELIVERY_GATE_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    # every case the binary knows must be listed exactly once
    local known listed
    known="$("$BUILD_DIR/green${bits}" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${RED_CASES[@]}" "${GUARD_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    echo "--- RED ARM (-DBLOCK_DELIVERY_GATE_UNFIXED): each RED case MUST fail ---"
    for c in "${RED_CASES[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c" 0
        if echo "$OUT" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"
            echo "$OUT" | grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
        elif echo "$OUT" | grep -q "RESULT $c fail"; then
            echo "  [$bits red $c] failed, as required: $(echo "$OUT" | grep -m1 '^FAIL:' )"
        else
            echo "  [$bits red $c] GATE FAILURE: did not fail (rc=$RC); this case cannot see what the gate governs"
            echo "$OUT" | grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    echo "--- RED ARM: each GUARD case MUST pass ---"
    for c in "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/red${bits}" "$c" 0
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass"; then
            echo "  [$bits red $c] passed (guard)"
        else
            echo "  [$bits red $c] GATE FAILURE: guard did not pass in the comparison arm (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -8; FAIL=1
        fi
    done

    echo "--- GREEN ARM: every case MUST pass, no sanitizer report ---"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c" 0
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass"; then
            echo "  [$bits green $c] passed"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done

    echo "--- GREEN ARM, leak-checked: what is ignored is released ---"
    for c in "${LEAK_CASES[@]}"; do
        run_case "$BUILD_DIR/green${bits}" "$c" 1
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass"; then
            echo "  [$bits green+lsan $c] passed, no leak"
        else
            echo "  [$bits green+lsan $c] GATE FAILURE (rc=$RC)"
            echo "$OUT" | grep -E "^(FAIL|RESULT)|$SAN_RE|Direct leak|#[0-9]" | sed 's/^/      /' | head -14; FAIL=1
        fi
    done

    # The log lines the device check greps for (neutral text; see the triage exit criteria).
    run_case "$BUILD_DIR/green${bits}" unsolicited_block_resident_header 0
    echo "$OUT" | grep -q "was not requested from this peer, ignoring" \
        && echo "  [$bits green] log: '$(echo "$OUT" | grep -m1 'was not requested')'" \
        || { echo "  [$bits green] GATE FAILURE: the ignore log line is missing"; FAIL=1; }
    run_case "$BUILD_DIR/green${bits}" solicited_block_root_mismatch 0
    echo "$OUT" | grep -q "tx list does not hash to its header's merkle root, ignoring" \
        && echo "  [$bits green] log: '$(echo "$OUT" | grep -m1 'does not hash')'" \
        || { echo "  [$bits green] GATE FAILURE: the root-mismatch log line is missing"; FAIL=1; }
}

# Level-2 arm (the shipped level): the tag cases must assert the refusal, the header-pow tag and the
# existing misbehaving penalty. The binary prints its level in each NOTE line; require "level 2".
run_tag_level2() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build, DGB_HEADER_POW_CHECK=2: header-pow close tag (GUARD) ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/tag2_${bits}" "$bits" -DDGB_HEADER_POW_CHECK=2; then
        echo "GATE FAILURE: ${bits}-bit level-2 arm did not compile."
        FAIL=1; return
    fi
    for c in "${TAG_CASES[@]}"; do
        run_case "$BUILD_DIR/tag2_${bits}" "$c" 0
        if ! echo "$OUT" | grep -Eq "$SAN_RE" && echo "$OUT" | grep -q "RESULT $c pass" \
           && echo "$OUT" | grep -q "^NOTE: level 2:"; then
            echo "  [$bits level2 $c] passed: $(echo "$OUT" | grep -m1 '^NOTE:')"
        else
            echo "  [$bits level2 $c] GATE FAILURE (rc=$RC)"
            echo "$OUT" | grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done
}

run_bits 64
run_bits 32
run_tag_level2 64
run_tag_level2 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: block_delivery_gate_kat (${#RED_CASES[@]} red-then-green, ${#GUARD_CASES[@]} guards, ${#TAG_CASES[@]} also at level 2; 64-bit and 32-bit, ASan)"
    exit 0
else
    echo "FAIL: block_delivery_gate_kat"
    exit 1
fi
