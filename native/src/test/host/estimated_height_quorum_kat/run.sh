#!/usr/bin/env bash
# Host KAT runner for estimated_height_quorum_kat: the sync target is the height the connected peers
# agree on (lower median of their reports), bounded by plausible growth since the last verified
# header, never below the held tip, recomputed up and down as the peer set changes; a downward
# recompute that lands on the held tip mid-sync runs the completion action once. See the header of
# estimated_height_quorum_kat_main.c.
#
# Compiles the REAL, live submodule BRPeer.c + BRPeerManager.c into the test (same link line as
# download_peer_promote_kat/run.sh), under ASan, in BOTH a 64-bit and a 32-bit build.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of ESTIMATED_HEIGHT_QUORUM_UNFIXED selects the comparison arm (the shipped arm is built
# with no -D at all): the two download-peer sites assign that one peer's report and the other
# recompute hooks are absent. Every scenario is RED-THEN-GREEN: the comparison arm must print
# `RESULT <scenario> FAIL` for each, and the shipped arm must print `RESULT <scenario> PASS` for each
# and `ALL PASS`. Neither arm may draw a sanitizer report -- a comparison arm that stops for another
# reason proves nothing. Checks that hold in both arms (the promoted download peer, a pending send
# not cancelled) are guards inside the scenarios.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h reasons as bip340_kat.
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
PM="$CORE_DIR/BRPeerManager.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
BANNER='ERROR: AddressSanitizer|runtime error:'
SCENARIOS=(quorum_adopt outlier_leaves median_and_bound downward_landing single_peer growth_bound)

# ---- seam self-check: a -D that names nothing silently builds the shipped path -----------------
if ! grep -q 'ESTIMATED_HEIGHT_QUORUM_UNFIXED' "$PM"; then
    echo "GATE FAILED: ESTIMATED_HEIGHT_QUORUM_UNFIXED is not present in BRPeerManager.c — the -D would"
    echo "             select nothing and the comparison arm would run the shipped code."
    exit 1
fi

# ---- source gates: completion stays where it is --------------------------------------------------
# The recompute never edits _peerRelayedBlock. Completion is the two equality sites there, and the
# high-water line after them is the only other writer of the estimate. Hold that by reading the
# source, so a later change to that region is a deliberate one.
EQ_COUNT=$(grep -c 'if (block->height == manager->estimatedHeight) { // chain download is complete' "$PM")
if [ "$EQ_COUNT" -ne 2 ]; then
    echo "GATE FAILED: expected exactly 2 completion equality sites in BRPeerManager.c, found $EQ_COUNT."
    exit 1
fi
HW_COUNT=$(grep -c 'if (block->height > manager->estimatedHeight) manager->estimatedHeight = block->height;' "$PM")
if [ "$HW_COUNT" -ne 1 ]; then
    echo "GATE FAILED: expected exactly 1 high-water line in BRPeerManager.c, found $HW_COUNT."
    exit 1
fi
# The recompute's own completion action is the same call the equality sites make.
RECOMPUTE_FN="$(awk '/^static void _BRPeerManagerRecomputeEstimatedHeight\(BRPeerManager \*manager\)$/,/^\}/' "$PM")"
if [ -z "$RECOMPUTE_FN" ]; then
    echo "GATE FAILED: _BRPeerManagerRecomputeEstimatedHeight was not found in BRPeerManager.c."
    exit 1
fi
if ! printf '%s\n' "$RECOMPUTE_FN" | grep -q '_BRPeerManagerLoadMempools(manager);'; then
    echo "GATE FAILED: the recompute's completion action must be _BRPeerManagerLoadMempools(manager),"
    echo "             the same call the two equality sites make."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1" bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/estimated_height_quorum_kat_main.c" \
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
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

FAIL=0

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build (BRPeerManager.c compiles under ASan; about a minute per arm) ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DESTIMATED_HEIGHT_QUORUM_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; return
    fi

    echo "--- COMPARISON ARM (-DESTIMATED_HEIGHT_QUORUM_UNFIXED): every scenario MUST fail, no sanitizer report ---"
    "$BUILD_DIR/red${bits}" > "$BUILD_DIR/red${bits}.log" 2>&1; local rc=$?
    if [ "$rc" -eq 0 ]; then
        echo "  [$bits red] GATE FAILURE: the comparison arm exited 0 — it cannot see the shape."
        FAIL=1
    fi
    if grep -Eq "$BANNER" "$BUILD_DIR/red${bits}.log"; then
        echo "  [$bits red] GATE FAILURE: sanitizer report in the comparison arm — stopped for another reason."
        grep -E "$BANNER" "$BUILD_DIR/red${bits}.log" | head -3 | sed 's/^/      /'
        FAIL=1
    fi
    for s in "${SCENARIOS[@]}"; do
        if grep -q "^RESULT $s FAIL" "$BUILD_DIR/red${bits}.log"; then
            echo "  [$bits red $s] fails as it must (rc=$rc): $(grep -m1 '^   FAIL:' <(awk "/^-- $s --/,/^RESULT $s/" "$BUILD_DIR/red${bits}.log") | sed 's/^ *FAIL: //')"
        else
            echo "  [$bits red $s] GATE FAILURE: the comparison arm did not fail this scenario."
            awk "/^-- $s --/,/^RESULT $s/" "$BUILD_DIR/red${bits}.log" | sed 's/^/      /' | head -12
            FAIL=1
        fi
    done

    echo "--- SHIPPED ARM: every scenario MUST pass, ALL PASS, no sanitizer report ---"
    "$BUILD_DIR/green${bits}" > "$BUILD_DIR/green${bits}.log" 2>&1; rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "  [$bits green] GATE FAILURE: the shipped arm exited $rc."
        grep -E '^   FAIL:|^RESULT' "$BUILD_DIR/green${bits}.log" | sed 's/^/      /'
        FAIL=1
    fi
    if grep -Eq "$BANNER" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] GATE FAILURE: sanitizer report in the shipped arm."
        grep -E "$BANNER" "$BUILD_DIR/green${bits}.log" | head -3 | sed 's/^/      /'
        FAIL=1
    fi
    if ! grep -q '^ALL PASS' "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] GATE FAILURE: the shipped arm did not print ALL PASS."
        FAIL=1
    fi
    for s in "${SCENARIOS[@]}"; do
        if grep -q "^RESULT $s PASS" "$BUILD_DIR/green${bits}.log"; then
            echo "  [$bits green $s] PASS"
        else
            echo "  [$bits green $s] GATE FAILURE: not passed."
            awk "/^-- $s --/,/^RESULT $s/" "$BUILD_DIR/green${bits}.log" | sed 's/^/      /' | head -20
            FAIL=1
        fi
    done
    grep -E '^   (PASS|FAIL):' "$BUILD_DIR/green${bits}.log" | sed "s/^/   [$bits shipped] /"
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: estimated_height_quorum_kat (comparison arm failed every scenario, shipped arm clean; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: estimated_height_quorum_kat"
    exit 1
fi
