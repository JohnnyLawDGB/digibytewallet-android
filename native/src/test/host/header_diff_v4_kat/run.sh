#!/usr/bin/env bash
# Host KAT runner: a header at a height MultiShield V4 governs carries exactly the target the reference client
# computes from its ancestors, and the wallet, with those ancestors resident, computes that same target.
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer, and UndefinedBehaviorSanitizer
# on the wallet's own C (the vendored hash code under crypto/ gets ASan only, see VENDOR_UNITS).
# The main file #includes BRPeer.c and BRPeerManager.c (for their file-statics). See header_diff_v4_kat_main.c for
# the entry layer and the fixtures (header_diff_v4_vectors.inc, written by gen_vectors.py from local nodes).
#
# ARMS. The check is compiled in at a level, DGB_HEADER_DIFF_CHECK (see BRMerkleBlock.h); every arm builds the
# header proof-of-work check at the shipped level 1.
#   level 2, NDEBUG (release shape)  real ranges: exactly the reference's judged/skipped counts, no mismatch; the
#                                    easiest-target header is REFUSED and the peer treated as misbehaving
#   level 1 (shipped)                the same counts; the easiest-target header is ACCEPTED with exactly one mismatch
#   level 0 (COMPARISON / GUARD)     the builds before the level existed: nothing is computed or counted, the
#                                    easiest-target header is ACCEPTED. run.sh REQUIRES that acceptance -- it is the
#                                    red evidence that the arm sees what the check governs.
#   RED (-DHEADER_DIFF_V4_UNFIXED, level 1)  the averaging timespan undamped, a plausible mis-port: every case on
#                                    real data and the edge inputs MUST FAIL.
# Every arm runs in 64-bit and 32-bit builds (256-bit arithmetic on 32-bit limbs; the 32-bit build is where a width
# slip would show). Any sanitizer report fails the run.
#
#   run.sh           the KAT
#   run.sh --live    also: the last 2,000 mainnet headers from the local node (read-only RPC), judged at level 1
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

DATA_CASES=(range_mainnet_recent range_mainnet_algo_swap range_mainnet_first_odo range_testnet_clamps level_real_header
            min_difficulty_rule)
RED_CASES=(inputs "${DATA_CASES[@]}")
GUARD_CASES=(compact skip_without_history unknown_algorithm)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# The wallet's own C: built with ASan and UBSan.
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
)
# The vendored proof-of-work hash code (crypto/): ASan only. Its own UB (shifts of negative values, unaligned
# 32-bit stores in sph_*) fires on every header and is not this suite's to judge.
VENDOR_UNITS=(
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# vendor_objs <bits>: compile the vendored units once per word size (they read no level macro)
vendor_objs() {
    local bits="$1" m=() objs=()
    [ "$bits" = "32" ] && m=(-m32)
    mkdir -p "$BUILD_DIR/vendor$bits"
    local i=0
    for src in "${VENDOR_UNITS[@]}"; do
        local o="$BUILD_DIR/vendor$bits/$((i++))_$(basename "$src" .c).o"   # sha3/ repeats some file names
        clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" \
            -I "$CORE_DIR" -c "$src" -o "$o" || return 1
    done
}

# build <out> <bits: 64|32> [flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DDEBUG: on the host peer_log is compiled out without it (BRPeer.h); the log lines are evidence.
    # UBSan minus vla-bound: _peerRelayedBlockOnce (BRPeerManager.c) sizes a zero-length array for every header
    # (txCount 0) -- outside this suite, recorded, not fixed here.
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address,undefined -fno-sanitize=vla-bound \
        -fno-sanitize-recover=undefined -fno-omit-frame-pointer -DDGB_HEADER_POW_CHECK="${POW_LEVEL:-1}" "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" -I "$SCRIPT_DIR" \
        "$SCRIPT_DIR/header_diff_v4_kat_main.c" "${UNITS[@]}" "$BUILD_DIR/vendor$bits"/*.o \
        -lm -lpthread -o "$out"
}

# ---- seam gate ------------------------------------------------------------------------------------------------------
# -D silently loses to a plain #define and -w hides the warning, so confirm the sources carry the seams.
for f in BRMerkleBlock.h BRPeerManager.c BRPeer.c; do
    if ! grep -q 'DGB_HEADER_DIFF_CHECK' "$CORE_DIR/$f"; then
        echo "GATE FAILURE: $f has no DGB_HEADER_DIFF_CHECK seam; -D would be inert."; exit 1
    fi
done
if ! grep -q '^#define DGB_HEADER_DIFF_CHECK 0' "$CORE_DIR/BRMerkleBlock.h"; then
    echo "GATE FAILURE: BRMerkleBlock.h does not default DGB_HEADER_DIFF_CHECK to 0."; exit 1
fi
if ! grep -q 'HEADER_DIFF_V4_UNFIXED' "$CORE_DIR/BRMerkleBlock.c"; then
    echo "GATE FAILURE: BRMerkleBlock.c has no HEADER_DIFF_V4_UNFIXED seam; the red arm would be inert."; exit 1
fi
if ! grep -q 'DDGB_HEADER_DIFF_CHECK=2' "$REPO_ROOT/native/build.gradle.kts"; then
    echo "GATE FAILURE: native/build.gradle.kts does not set the shipped level (expected -DDGB_HEADER_DIFF_CHECK=2)."
    exit 1
fi
echo "seam gate OK (level macro present in the sources; header default 0; app build sets level 2; red seam present)"

FAIL=0

# run_case <bin> <case> -> sets OUT and RC
run_case() { OUT="$("$1" "$2" 2>&1)"; RC=$?; }

# expect <bin> <label> <case> <verdict>: the case prints RESULT <case> <verdict>, no sanitizer report
expect() {
    local bin="$1" label="$2" c="$3" want="$4"
    run_case "$bin" "$c"
    if echo "$OUT" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (rc=$RC)"; echo "$OUT" | grep -Em4 "$SAN_RE" | sed 's/^/      /'
        FAIL=1
    elif echo "$OUT" | grep -q "RESULT $c $want\$"; then
        echo "  [$label $c] $want: $(echo "$OUT" | grep -E '^(PASS|NOTE)' | tail -1 | cut -c1-150)"
    else
        echo "  [$label $c] GATE FAILURE: expected '$want', got: $(echo "$OUT" | grep -o "RESULT $c [a-z_]*" | head -1) (rc=$RC)"
        echo "$OUT" | grep -E '^(FAIL|NOTE)|diff-mismatch' | head -8 | sed 's/^/      /'; FAIL=1
    fi
}

# expect_red <bin> <label> <case>: the case MUST fail, without a sanitizer report
expect_red() {
    local bin="$1" label="$2" c="$3"
    run_case "$bin" "$c"
    if echo "$OUT" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"
        echo "$OUT" | grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
    elif echo "$OUT" | grep -q "RESULT $c fail"; then
        echo "  [$label $c] failed, as required: $(echo "$OUT" | grep -m1 '^FAIL:' | cut -c1-150)"
    else
        echo "  [$label $c] GATE FAILURE: did not fail (rc=$RC); this case cannot see the computation"; FAIL=1
    fi
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit builds ==="
    echo "======================================================================"
    vendor_objs "$bits" || { echo "GATE FAILURE: ${bits}-bit vendored hash code did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/l2_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=2 -DNDEBUG || { echo "GATE FAILURE: ${bits}-bit level-2 arm did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/l1_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=1 || { echo "GATE FAILURE: ${bits}-bit level-1 arm did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/l0_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=0 || { echo "GATE FAILURE: ${bits}-bit level-0 arm did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/red_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=1 -DHEADER_DIFF_V4_UNFIXED || { echo "GATE FAILURE: ${bits}-bit red arm did not compile."; FAIL=1; return; }
    # The combination the app ships: header proof of work at 2, difficulty target at 2 (enforce).
    POW_LEVEL=2 build "$BUILD_DIR/ship_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=2 || { echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."; FAIL=1; return; }

    local known listed
    known="$("$BUILD_DIR/l1_$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${RED_CASES[@]}" "${GUARD_CASES[@]}" level_easiest_target | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; FAIL=1; return
    fi

    echo "--- RED ARM (-DHEADER_DIFF_V4_UNFIXED, level 1): every case on real data and the edge inputs MUST fail ---"
    for c in "${RED_CASES[@]}"; do expect_red "$BUILD_DIR/red_$bits" "$bits red" "$c"; done

    echo "--- level 2, NDEBUG (enforce): real data matches exactly; the easiest-target header is REFUSED ---"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do expect "$BUILD_DIR/l2_$bits" "$bits L2" "$c" pass; done
    expect "$BUILD_DIR/l2_$bits" "$bits L2" level_easiest_target refused

    echo "--- level 1 (shipped, observe): the same counts; the easiest-target header accepted with one mismatch ---"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do expect "$BUILD_DIR/l1_$bits" "$bits L1" "$c" pass; done
    expect "$BUILD_DIR/l1_$bits" "$bits L1" level_easiest_target accepted_counted

    echo "--- shipped (proof of work 2, difficulty 2): real data passes; the easiest-target header is REFUSED ---"
    echo "    (unknown_algorithm is not run here: at proof-of-work level 2 such a header is refused before its target is judged)"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        [ "$c" = unknown_algorithm ] && continue
        expect "$BUILD_DIR/ship_$bits" "$bits ship" "$c" pass
    done
    expect "$BUILD_DIR/ship_$bits" "$bits ship" level_easiest_target refused

    echo "--- level 0 (COMPARISON ARM / GUARD): nothing computed or counted; the easiest-target header ACCEPTED ---"
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do expect "$BUILD_DIR/l0_$bits" "$bits L0" "$c" pass; done
    expect "$BUILD_DIR/l0_$bits" "$bits L0" level_easiest_target accepted_uncounted

    # the log lines the device check greps for
    run_case "$BUILD_DIR/l1_$bits" level_easiest_target
    echo "$OUT" | grep -q 'diff-mismatch v=20000202' \
        && echo "  [$bits L1] log: '$(echo "$OUT" | grep -m1 -o 'diff-mismatch.*' | cut -c1-140)'" \
        || { echo "  [$bits L1] GATE FAILURE: the diff-mismatch log line is missing"; FAIL=1; }
    echo "$OUT" | grep -q 'diff-batch n=1 judged=1 skip=0 mismatch=1' \
        && echo "  [$bits L1] log: '$(echo "$OUT" | grep -m1 -o 'diff-batch n=1 .*')'" \
        || { echo "  [$bits L1] GATE FAILURE: the diff-batch summary line is missing"; FAIL=1; }
    run_case "$BUILD_DIR/l1_$bits" skip_without_history
    echo "$OUT" | grep -q 'diff-skip h=' \
        && echo "  [$bits L1] log: '$(echo "$OUT" | grep -m1 -o 'diff-skip.*' | cut -c1-140)'" \
        || { echo "  [$bits L1] GATE FAILURE: the diff-skip log line is missing"; FAIL=1; }
}

run_bits 64
run_bits 32

if [ "${1:-}" = "--live" ]; then
    echo "======================================================================"
    echo "=== live: the last 2,000 mainnet headers from the local node, level 1 ==="
    echo "======================================================================"
    LIVE="$BUILD_DIR/live.txt"
    if python3 "$SCRIPT_DIR/gen_vectors.py" --live 2000 "$LIVE"; then
        for bits in 64 32; do
            out="$("$BUILD_DIR/l1_$bits" judge_file "$LIVE" 2>&1)"
            echo "$out" | grep -E '^live:' | sed "s/^/  [$bits] /"
            echo "$out" | grep -E 'diff-mismatch' | head -5 | sed "s/^/  [$bits] /"
            if echo "$out" | grep -Eq "$SAN_RE" || ! echo "$out" | grep -q 'RESULT judge_file pass'; then
                echo "  [$bits] GATE FAILURE: live check"; FAIL=1
            fi
        done
    else
        echo "  GATE FAILURE: could not read the local node"; FAIL=1
    fi
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: header_diff_v4_kat (${#RED_CASES[@]} red-then-green, ${#GUARD_CASES[@]} guards, level arms 0/1/2; 64-bit and 32-bit, ASan+UBSan)"
    exit 0
else
    echo "FAIL: header_diff_v4_kat"
    exit 1
fi
