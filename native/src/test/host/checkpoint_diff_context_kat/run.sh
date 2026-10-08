#!/usr/bin/env bash
# Host KAT runner: a wallet whose resident chain starts at a checkpoint judges the first headers above it against the
# real chain's MultiShield V4 difficulty, from the checkpoint's difficulty context (BRCheckPointContext, BRChainParams.h).
#
# Compiles the REAL, live submodule sources out of the tree with AddressSanitizer, and UndefinedBehaviorSanitizer on
# the wallet's own C (the vendored hash code under crypto/ gets ASan only, as in header_diff_v4_kat). The main file
# #includes BRPeer.c and BRPeerManager.c for their file-statics. Fixture: checkpoint_diff_context_vectors.inc, written
# by gen_vectors.py from the local node and the shipped context.
#
# ARMS (every one builds the header proof-of-work check at 2, as the app ships):
#   shipped  difficulty 2, NDEBUG   the context loads; every real header above the checkpoint is judged (none skipped,
#                                   none mismatched); an easiest-target header on the checkpoint, and one on the 30th
#                                   real header above it (new wallet; restarted wallet; restarted with the
#                                   checkpoint's own header persisted), are REFUSED
#   level 1  difficulty 1           the same counts; every easiest-target header ACCEPTED with exactly one mismatch
#   RED      difficulty 2, -DCHECKPOINT_DIFF_CONTEXT_UNFIXED (the code before this change: no context loaded)
#                                   context_loaded and real_headers_judged MUST FAIL, and every easiest-target header
#                                   MUST BE ACCEPTED unjudged (diff-skip) -- the gap this change closes
# Every arm runs 64-bit and 32-bit. Any sanitizer report fails the run.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

GREEN_CASES=(context_verifies context_loaded real_headers_judged)
EASY_CASES=(easiest_on_checkpoint easiest_above_checkpoint easiest_after_resume easiest_after_resume_checkpoint_header)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRCompactFilterChain.c"
    "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c" "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
)
VENDOR_UNITS=(
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

vendor_objs() {
    local bits="$1" m=()
    [ "$bits" = "32" ] && m=(-m32)
    mkdir -p "$BUILD_DIR/vendor$bits"
    local i=0
    for src in "${VENDOR_UNITS[@]}"; do
        local o="$BUILD_DIR/vendor$bits/$((i++))_$(basename "$src" .c).o"
        clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" \
            -I "$CORE_DIR" -c "$src" -o "$o" || return 1
    done
}

# build <out> <bits> [flags...]   (-DDEBUG: peer_log is compiled out on the host without it; the lines are evidence)
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address,undefined -fno-sanitize=vla-bound \
        -fno-sanitize-recover=undefined -fno-omit-frame-pointer -DDGB_HEADER_POW_CHECK=2 "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" -I "$SCRIPT_DIR" \
        "$SCRIPT_DIR/checkpoint_diff_context_kat_main.c" "${UNITS[@]}" "$BUILD_DIR/vendor$bits"/*.o \
        -lm -lpthread -o "$out"
}

# ---- seam gate ------------------------------------------------------------------------------------------------------
if ! grep -q 'CHECKPOINT_DIFF_CONTEXT_UNFIXED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c has no CHECKPOINT_DIFF_CONTEXT_UNFIXED seam; the red arm would be inert."; exit 1
fi
if ! grep -q 'DDGB_HEADER_DIFF_CHECK=2' "$REPO_ROOT/native/build.gradle.kts"; then
    echo "GATE FAILURE: native/build.gradle.kts does not ship -DDGB_HEADER_DIFF_CHECK=2; the shipped arm is not what ships."
    exit 1
fi
echo "seam gate OK (red seam present; app build ships difficulty level 2)"

FAIL=0
run_case() { OUT="$("$1" "$2" 2>&1)"; RC=$?; }

expect() {
    local bin="$1" label="$2" c="$3" want="$4"
    run_case "$bin" "$c"
    if echo "$OUT" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (rc=$RC)"; echo "$OUT" | grep -Em4 "$SAN_RE" | sed 's/^/      /'
        FAIL=1
    elif echo "$OUT" | grep -q "RESULT $c $want\$"; then
        echo "  [$label $c] $want: $(echo "$OUT" | grep -E '^(NOTE|PASS)' | tail -1 | cut -c1-170)"
    else
        echo "  [$label $c] GATE FAILURE: expected '$want', got: $(echo "$OUT" | grep -o "RESULT $c [a-z_]*" | head -1) (rc=$RC)"
        echo "$OUT" | grep -E '^(FAIL|NOTE)|diff-' | head -8 | sed 's/^/      /'; FAIL=1
    fi
}

expect_red() {
    local bin="$1" label="$2" c="$3"
    run_case "$bin" "$c"
    if echo "$OUT" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (a fault is not the failure this case asserts)"; FAIL=1
    elif echo "$OUT" | grep -q "RESULT $c fail"; then
        echo "  [$label $c] failed, as required: $(echo "$OUT" | grep -m1 '^FAIL:' | cut -c1-150)"
    else
        echo "  [$label $c] GATE FAILURE: did not fail (rc=$RC); this case cannot see the context"; FAIL=1
    fi
}

run_bits() {
    local bits="$1"
    echo "=== ${bits}-bit builds ==="
    vendor_objs "$bits" || { echo "GATE FAILURE: ${bits}-bit vendored hash code did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/ship_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=2 -DNDEBUG || { echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/l1_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=1 || { echo "GATE FAILURE: ${bits}-bit level-1 arm did not compile."; FAIL=1; return; }
    build "$BUILD_DIR/red_$bits" "$bits" -DDGB_HEADER_DIFF_CHECK=2 -DCHECKPOINT_DIFF_CONTEXT_UNFIXED || { echo "GATE FAILURE: ${bits}-bit red arm did not compile."; FAIL=1; return; }

    local known listed
    known="$("$BUILD_DIR/ship_$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${GREEN_CASES[@]}" "${EASY_CASES[@]}" | sort | tr '\n' ' ')"
    [ "$known" = "$listed" ] || { echo "GATE FAILURE: case lists disagree: $known / $listed"; FAIL=1; return; }

    echo "--- RED (no context loaded: the code before this change, difficulty 2) ---"
    expect "$BUILD_DIR/red_$bits" "$bits red" context_verifies pass
    expect_red "$BUILD_DIR/red_$bits" "$bits red" context_loaded
    expect_red "$BUILD_DIR/red_$bits" "$bits red" real_headers_judged
    for c in "${EASY_CASES[@]}"; do expect "$BUILD_DIR/red_$bits" "$bits red" "$c" accepted_skipped; done

    echo "--- shipped (proof of work 2, difficulty 2): judged from the first header; easiest-target headers REFUSED ---"
    for c in "${GREEN_CASES[@]}"; do expect "$BUILD_DIR/ship_$bits" "$bits ship" "$c" pass; done
    for c in "${EASY_CASES[@]}"; do expect "$BUILD_DIR/ship_$bits" "$bits ship" "$c" refused; done

    echo "--- level 1 (observe): the same counts; easiest-target headers accepted with one mismatch ---"
    for c in "${GREEN_CASES[@]}"; do expect "$BUILD_DIR/l1_$bits" "$bits L1" "$c" pass; done
    for c in "${EASY_CASES[@]}"; do expect "$BUILD_DIR/l1_$bits" "$bits L1" "$c" accepted_counted; done

    run_case "$BUILD_DIR/l1_$bits" easiest_on_checkpoint
    echo "$OUT" | grep -q 'diff-mismatch v=20000202' \
        && echo "  [$bits L1] log: '$(echo "$OUT" | grep -m1 -o 'diff-mismatch.*' | cut -c1-120)'" \
        || { echo "  [$bits L1] GATE FAILURE: the diff-mismatch log line is missing"; FAIL=1; }
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: checkpoint_diff_context_kat (red arm fails/accepts as required; shipped refuses; 64-bit and 32-bit, ASan+UBSan)"
    exit 0
else
    echo "FAIL: checkpoint_diff_context_kat"
    exit 1
fi
