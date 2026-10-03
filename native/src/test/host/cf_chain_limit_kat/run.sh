#!/usr/bin/env bash
# Host KAT runner: the compact-filter header chain never stops at its size limit, and reaching the
# limit is never read as a peer disagreeing. See cf_chain_limit_kat_main.c for the invariant and cases.
#
# Compiles the REAL, live submodule sources with AddressSanitizer, 64-bit and 32-bit. The main file
# #includes BRPeer.c and BRPeerManager.c (for their file-statics), so neither is a separate unit.
#
# LIMITS. Every case runs with a small limit (-DBR_COMPACT_FILTER_CHAIN_MAX=8192u, the header's
# #ifndef seam). limit_crossing_continues also runs at the REAL limit (2^21 headers from 21,400,000,
# crossing at 23,497,151): about 20 s per run under ASan.
#
# ARMS. Green is no -D. Red is -DCF_CHAIN_LIMIT_UNFIXED (BRCompactFilterChain.c): the earlier shape, in
# which the size limit reads exactly like a continuity mismatch.
#   RED cases   must FAIL in red and PASS in green.
#   GUARD cases must PASS in both.
# Every case, both arms, both word sizes: no sanitizer report. A build error is never evidence.
set -uo pipefail   # not -e: the red arm's nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

RED_SMALL=(append_limit_distinct limit_crossing_continues limit_held_while_scan_needs_all)
GUARD_SMALL=(chain_unit_truncate_drop)
RED_REAL=(limit_crossing_continues)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DDEBUG: peer_log is compiled out on the host without it (BRPeer.h); the log lines are evidence.
    "${CC:-clang}" -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_chain_limit_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$out"
}

for f in BRCompactFilterChain.c; do
    if ! grep -q CF_CHAIN_LIMIT_UNFIXED "$CORE_DIR/$f"; then
        echo "GATE FAILURE: $f has no CF_CHAIN_LIMIT_UNFIXED seam; -D would be inert."; exit 1
    fi
done
if ! grep -q '#ifndef BR_COMPACT_FILTER_CHAIN_MAX' "$CORE_DIR/BRCompactFilterChain.h"; then
    echo "GATE FAILURE: BR_COMPACT_FILTER_CHAIN_MAX is not overridable; the small-limit arm would test the real one."; exit 1
fi

FAIL=0
run_case() { OUT="$("$1" "$2" 2>&1)"; RC=$?; }
san() { echo "$OUT" | grep -Eq "$SAN_RE"; }
expect_pass() {   # expect_pass <label> <bin> <case>
    run_case "$2" "$3"
    if san; then echo "  [$1 $3] GATE FAILURE: sanitizer report"; echo "$OUT" | grep -E "$SAN_RE" | head -3; FAIL=1
    elif [ $RC -eq 0 ] && echo "$OUT" | grep -q "RESULT $3 PASS"; then
        echo "  [$1 $3] PASS $(echo "$OUT" | grep -m1 'after:' | sed 's/^ *//')"
    else echo "  [$1 $3] GATE FAILURE: did not pass (rc=$RC)"; echo "$OUT" | grep -E "  FAIL|RESULT" | sed 's/^/      /'; FAIL=1; fi
}
expect_fail() {   # expect_fail <label> <bin> <case>
    run_case "$2" "$3"
    if san; then echo "  [$1 $3] GATE FAILURE: sanitizer report"; FAIL=1
    elif [ $RC -ne 0 ] && echo "$OUT" | grep -q "RESULT $3 FAIL"; then
        echo "  [$1 $3] failed, as required: $(echo "$OUT" | grep -m1 '  FAIL' | sed 's/^ *FAIL *//')"
    else echo "  [$1 $3] GATE FAILURE: passed in the earlier shape (rc=$RC) -- the seam is not load-bearing"; FAIL=1; fi
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    local ok=1
    build "$BUILD_DIR/gs$bits" "$bits" -DBR_COMPACT_FILTER_CHAIN_MAX=8192u || ok=0
    build "$BUILD_DIR/rs$bits" "$bits" -DBR_COMPACT_FILTER_CHAIN_MAX=8192u -DCF_CHAIN_LIMIT_UNFIXED || ok=0
    build "$BUILD_DIR/gr$bits" "$bits" || ok=0
    build "$BUILD_DIR/rr$bits" "$bits" -DCF_CHAIN_LIMIT_UNFIXED || ok=0
    if [ $ok -eq 0 ]; then echo "GATE FAILURE: a ${bits}-bit arm did not compile."; FAIL=1; return; fi

    local known listed
    known="$("$BUILD_DIR/gs$bits" | sort)"
    listed="$(printf '%s\n' "${RED_SMALL[@]}" "${GUARD_SMALL[@]}" | sort)"
    if [ "$known" != "$listed" ]; then echo "GATE FAILURE: case list differs from the binary's"; FAIL=1; fi

    echo "--- small limit (8192), GREEN: every case passes ---"
    for c in "${RED_SMALL[@]}" "${GUARD_SMALL[@]}"; do expect_pass "$bits green small" "$BUILD_DIR/gs$bits" "$c"; done
    echo "--- small limit (8192), RED -DCF_CHAIN_LIMIT_UNFIXED: red cases fail, guards hold ---"
    for c in "${RED_SMALL[@]}"; do expect_fail "$bits red small" "$BUILD_DIR/rs$bits" "$c"; done
    for c in "${GUARD_SMALL[@]}"; do expect_pass "$bits red small (guard)" "$BUILD_DIR/rs$bits" "$c"; done
    echo "--- REAL limit (2^21 from 21,400,000) ---"
    for c in "${RED_REAL[@]}"; do
        expect_pass "$bits green real" "$BUILD_DIR/gr$bits" "$c"
        expect_fail "$bits red real" "$BUILD_DIR/rr$bits" "$c"
    done
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: cf_chain_limit_kat (red failed, green passed, guards held; small and real limit; 64-bit and 32-bit)"
    exit 0
fi
echo "FAIL: cf_chain_limit_kat"
exit 1
