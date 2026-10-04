#!/usr/bin/env bash
# Host KAT runner: a full 20,000-header DigiByte response is relayed before the paced convoy decides
# whether to ask for the next one, at every header proof-of-work level.
#
# FIXTURE. The pacing cases repeat one real mainnet header (height 1,430,000, sha256d, from
# ../merkleblock_pow_kat/headers.inc, where dSHA256 equals the node's block hash), so they carry real
# proof of work and pass at every DGB_HEADER_POW_CHECK level. A separate case feeds a header with no
# proof of work for its algorithm and asserts the level's own verdict (2 refuses, 1 counts, 0 neither).
#
# ARMS, at each of levels 0, 1 and 2, 64-bit and 32-bit, ASan:
#   RED   -DBRPEER_HEADERS_CONTINUE_BEFORE_RELAY (the continuation decided before relay) MUST fail on the
#         "decision is AFTER relay" assertion, with no sanitizer report.
#   GREEN no seam: MUST print ALL PASSED for its level.
# Each arm names its level itself, so the aggregate runner's shipped-level flag does not override it.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local output="$1"; local bits="$2"
    shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_header_pacing_kat_main.c" \
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
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lpthread -lm \
        -o "$output"
}

# The host digi_log line for a counted header has no trailing newline; at level 1 there are 20,000 of
# them. Print each run with those lines folded into a count, and match on here-strings (a pipe into
# grep -q under pipefail reads as "not found" when grep exits early on a large output).
MISMATCH_RE='pow-mismatch v=[0-9a-f]+ h=[0-9]+ algo=[a-z0-9-]+ hash=[0-9a-f]+ pow=[0-9a-f-]+ target=[0-9a-f]{8}'
show() {
    local n
    n="$(grep -Eo "$MISMATCH_RE" <<< "$1" | wc -l || true)"
    sed -E "s/$MISMATCH_RE//g" <<< "$1"
    echo "  ($n pow-mismatch log line(s) folded)"
}

FAIL=0
for bits in 64 32; do
    for L in 0 1 2; do
        echo "=== ${bits}-bit, level $L ==="
        if ! build "$BUILD_DIR/pre_relay_${bits}_$L" "$bits" -DDGB_HEADER_POW_CHECK=$L -DBRPEER_HEADERS_CONTINUE_BEFORE_RELAY; then
            echo "GATE FAILURE: red arm did not compile (${bits}-bit, level $L)"; exit 1
        fi
        if ! build "$BUILD_DIR/post_relay_${bits}_$L" "$bits" -DDGB_HEADER_POW_CHECK=$L; then
            echo "GATE FAILURE: green arm did not compile (${bits}-bit, level $L)"; exit 1
        fi

        out="$("$BUILD_DIR/pre_relay_${bits}_$L" 2>&1)" && rc=0 || rc=$?
        if grep -Eq "$SAN_RE" <<< "$out"; then
            show "$out"; echo "GATE FAILURE: sanitizer report in the red arm (${bits}-bit, level $L)"; FAIL=1
        elif [ "$rc" -eq 0 ]; then
            show "$out"; echo "GATE FAILURE: the pre-relay continuation build unexpectedly passed (${bits}-bit, level $L)"; FAIL=1
        elif ! grep -q "FAIL: a gate closed by relayedBlock suppresses the continuation" <<< "$out"; then
            show "$out"; echo "GATE FAILURE: the red arm failed, but not on the continuation assertion (${bits}-bit, level $L)"; FAIL=1
        else
            echo "RED confirmed: deciding before relayedBlock queues a stale-open continuation (${bits}-bit, level $L)"
        fi

        out="$("$BUILD_DIR/post_relay_${bits}_$L" 2>&1)" && rc=0 || rc=$?
        show "$out"
        if grep -Eq "$SAN_RE" <<< "$out"; then
            echo "GATE FAILURE: sanitizer report in the green arm (${bits}-bit, level $L)"; FAIL=1
        elif [ "$rc" -ne 0 ] || ! grep -q "^level $L\$" <<< "$out" || ! grep -q "ALL PASSED" <<< "$out"; then
            echo "GATE FAILURE: green arm (${bits}-bit, level $L) rc=$rc"; FAIL=1
        fi
    done
done

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: cf_header_pacing_kat (red arm red and green arm green at levels 0, 1, 2; 64-bit and 32-bit, ASan)"
    exit 0
fi
echo "FAIL: cf_header_pacing_kat"
exit 1
