#!/usr/bin/env bash
# Host KAT runner: every header the wallet accepts carries a proof-of-work hash computed with the
# algorithm its version names, that hash meets the header's target, and a header whose algorithm
# is unknown has no hash and is not accepted.
#
# FIXTURES. headers.inc: real headers from full nodes on both chains (mainnet /DigiByte:8.26.2/,
# testnet26 /DigiByte:9.26.3/), each with the node's own pow_hash and pow_algo from `getblock`, kept
# only where dSHA256(80 bytes) == the block hash. Generated from the fixture JSON files kept with the evidence by a
# script that verifies that equality per row; regenerate rather than hand-edit.
#
# ARMS. The check is compiled in at a level, DGB_HEADER_POW_CHECK (see BRMerkleBlock.h):
#   level 2 (+ -DNDEBUG, the release shape)  every fixture row passes and reproduces the node's hash;
#                                            a header with a changed nonce is REJECTED; an unknown
#                                            algorithm (incl. bit 8 set) is REJECTED without aborting;
#                                            the mismatch counter counts each of them exactly once
#   level 1 (observe)                          nothing is rejected; the counter still counts exactly
#   level 0 (COMPARISON / GUARD)             the code path of the builds before the level existed: the
#                                            changed-nonce and unknown headers are ACCEPTED and the
#                                            counter never moves. run.sh REQUIRES that acceptance -- it
#                                            is the red evidence that the arm sees what the check governs.
# The algorithm table, the Odocrypt interval and the re-serialisation identity are level-independent
# and run in every arm. Every arm runs in 64-bit and 32-bit builds (scrypt and Odocrypt use 64-bit
# words; the 32-bit build is where a width or endianness slip would show).
#
# SEAM GATE. -D silently loses to a plain #define, so the sources must carry the level macro.
#
# BENCH. A separate -O2 build without the sanitizer prints ns/header per algorithm (not pass/fail).
set -uo pipefail   # not -e: the comparison arm's verdicts are read, not its exit code

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# build <out> <bits: 64|32> [flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/merkleblock_pow_kat_main.c" "${UNITS[@]}" \
        -lpthread -lm -o "$out"
}

# ---- seam gate ------------------------------------------------------------------------------
for f in BRMerkleBlock.h BRMerkleBlock.c BRPeerManager.c BRPeer.c; do
    if ! grep -q 'DGB_HEADER_POW_CHECK' "$CORE_DIR/$f"; then
        echo "GATE FAILURE: $f has no DGB_HEADER_POW_CHECK seam; -D would be inert."
        exit 1
    fi
done
if ! grep -q '^#define DGB_HEADER_POW_CHECK 0' "$CORE_DIR/BRMerkleBlock.h"; then
    echo "GATE FAILURE: BRMerkleBlock.h does not default DGB_HEADER_POW_CHECK to 0."
    exit 1
fi
if ! grep -q 'DDGB_HEADER_POW_CHECK=1' "$REPO_ROOT/native/build.gradle.kts"; then
    echo "GATE FAILURE: native/build.gradle.kts does not set the shipped level (expected -DDGB_HEADER_POW_CHECK=1)."
    exit 1
fi
echo "seam gate OK (level macro present in the sources; header default 0; app build sets level 1)"

FAIL=0

# expect <arm binary> <label> <case> <verdict>: the case prints RESULT <case> <verdict>, no sanitizer report
expect() {
    local bin="$1" label="$2" c="$3" want="$4"
    local out rc
    out="$("$bin" "$c" 2>&1)"; rc=$?
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (rc=$rc)"; echo "$out" | grep -E "$SAN_RE|FAIL" | head -4 | sed 's/^/      /'; FAIL=1
    elif echo "$out" | grep -q "RESULT $c $want"; then
        echo "  [$label $c] $want (rc=$rc)"
    else
        echo "  [$label $c] GATE FAILURE: expected '$want', got: $(echo "$out" | grep -o "RESULT $c [a-z]*" | head -1)"
        echo "$out" | grep -E "\[FAIL\]|row h=|table:|fixture row|counter:" | head -8 | sed 's/^/      /'; FAIL=1
    fi
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit builds ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/l2_$bits" "$bits" -DDGB_HEADER_POW_CHECK=2 -DNDEBUG; then
        echo "GATE FAILURE: ${bits}-bit level-2 arm did not compile."; FAIL=1; return
    fi
    if ! build "$BUILD_DIR/l1_$bits" "$bits" -DDGB_HEADER_POW_CHECK=1; then
        echo "GATE FAILURE: ${bits}-bit level-1 arm did not compile."; FAIL=1; return
    fi
    if ! build "$BUILD_DIR/l0_$bits" "$bits" -DDGB_HEADER_POW_CHECK=0; then
        echo "GATE FAILURE: ${bits}-bit level-0 arm did not compile."; FAIL=1; return
    fi

    echo "--- level 2, NDEBUG (enforce): rows reproduce the node's hash; changed nonce and unknown algorithm REJECTED ---"
    expect "$BUILD_DIR/l2_$bits" "$bits L2" rows pass
    expect "$BUILD_DIR/l2_$bits" "$bits L2" mutated rejected
    expect "$BUILD_DIR/l2_$bits" "$bits L2" unknown rejected
    expect "$BUILD_DIR/l2_$bits" "$bits L2" counter exact
    expect "$BUILD_DIR/l2_$bits" "$bits L2" algo_table pass
    expect "$BUILD_DIR/l2_$bits" "$bits L2" odo_interval pass
    expect "$BUILD_DIR/l2_$bits" "$bits L2" field_zero pass

    echo "--- level 1 (observe): nothing rejected; the counter counts exactly ---"
    expect "$BUILD_DIR/l1_$bits" "$bits L1" rows pass
    expect "$BUILD_DIR/l1_$bits" "$bits L1" mutated accepted
    expect "$BUILD_DIR/l1_$bits" "$bits L1" unknown accepted
    expect "$BUILD_DIR/l1_$bits" "$bits L1" counter exact
    expect "$BUILD_DIR/l1_$bits" "$bits L1" algo_table pass
    expect "$BUILD_DIR/l1_$bits" "$bits L1" odo_interval pass
    expect "$BUILD_DIR/l1_$bits" "$bits L1" field_zero pass

    echo "--- level 0 (COMPARISON ARM / GUARD): the earlier verdicts -- changed nonce and unknown algorithm ACCEPTED, counter never moves ---"
    expect "$BUILD_DIR/l0_$bits" "$bits L0" rows pass
    expect "$BUILD_DIR/l0_$bits" "$bits L0" mutated accepted
    expect "$BUILD_DIR/l0_$bits" "$bits L0" unknown accepted
    expect "$BUILD_DIR/l0_$bits" "$bits L0" counter zero
    expect "$BUILD_DIR/l0_$bits" "$bits L0" algo_table pass
    expect "$BUILD_DIR/l0_$bits" "$bits L0" odo_interval pass
    expect "$BUILD_DIR/l0_$bits" "$bits L0" field_zero pass
}

run_bits 64
run_bits 32

if [ "${1:-}" = "--bench" ] || [ -n "${POW_KAT_BENCH:-}" ]; then
    echo "======================================================================"
    echo "=== bench (-O2, no sanitizer; ns/header per algorithm) ==="
    echo "======================================================================"
    for bits in 64 32; do
        m=(); [ "$bits" = "32" ] && m=(-m32)
        if clang -w -include stdint.h -O2 "${m[@]}" -DDGB_HEADER_POW_CHECK=2 -DNDEBUG \
            -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
            "$SCRIPT_DIR/merkleblock_pow_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$BUILD_DIR/bench_$bits"; then
            "$BUILD_DIR/bench_$bits" --bench 2>&1 | sed "s/^/  [$bits] /"
        else
            echo "  [$bits] bench build failed (not a gate)"
        fi
    done
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: merkleblock_pow_kat (level 2 rejects, level 1 counts, level 0 reproduces the earlier verdicts; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: merkleblock_pow_kat"
    exit 1
fi
