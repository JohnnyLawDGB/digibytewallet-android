#!/usr/bin/env bash
# Host KAT runner: the compact-filter header chain and the scan ledger follow the best chain through
# a reorg, and a peer is penalised only for filter data that contradicts a header the wallet holds.
# See cf_reorg_rewind_kat_main.c for the invariants, the fixture and each case.
#
# Compiles the REAL, live submodule sources with AddressSanitizer, 64-bit and 32-bit. The main file
# #includes BRPeer.c and BRPeerManager.c (for their file-statics), so neither is a separate unit.
#
# ARMS. Green is no -D at all, so a shipped build (which never defines any of these) gets the fixed
# code. Each red arm restores ONE earlier shape:
#   -DCF_REORG_REWIND_UNFIXED            the reorg leaves the filter-header chain and the ledger alone,
#                                        and a cfheaders batch ending on a replaced block still appends
#   -DCF_OUTSIDE_CHAIN_PENALTY_UNFIXED   any failed filter check closes the peer as misbehaving
#   -DCF_REANCHOR_REFUND_UNFIXED         the re-anchor budget is never restored
#   -DCF_PARK_BELOW_START_UNFIXED        the never-brick park goes to the checkpoint, below the chain
#   -DCF_BAND_SCANNED_UNFIXED            an abandoned band may include heights already evaluated
#   -DCF_DISAGREER_PORT_UNFIXED          disagreeing peers are counted by address alone
# RED cases must FAIL in their arm and PASS in green. GUARD cases must PASS in every arm built.
# Every case, every arm, both word sizes: no sanitizer report. A build error is never evidence.
set -uo pipefail   # not -e: the red arms' nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

# arm flag : RED cases for that arm
declare -A RED=(
    [CF_REORG_REWIND_UNFIXED]="one_block_reorg_rescans_replacement four_reorgs_no_park deep_reorg_below_chain_start_reanchors stale_cfheaders_for_replaced_block_ignored"
    [CF_OUTSIDE_CHAIN_PENALTY_UNFIXED]="cfilter_below_chain_start_no_penalty cfilter_above_chain_tip_no_penalty cfilter_for_replaced_block_no_penalty"
    [CF_REANCHOR_REFUND_UNFIXED]="reanchor_budget_restored_after_clean_appends"
    [CF_PARK_BELOW_START_UNFIXED]="park_never_below_chain_start"
    [CF_BAND_SCANNED_UNFIXED]="abandoned_band_excludes_scanned_heights"
    [CF_DISAGREER_PORT_UNFIXED]="disagreers_distinct_by_address_and_port"
)
ARMS=(CF_REORG_REWIND_UNFIXED CF_OUTSIDE_CHAIN_PENALTY_UNFIXED CF_REANCHOR_REFUND_UNFIXED
      CF_PARK_BELOW_START_UNFIXED CF_BAND_SCANNED_UNFIXED CF_DISAGREER_PORT_UNFIXED)
GUARD_CASES=(cfilter_contradicting_held_header_penalised reanchor_budget_bounds_a_run_of_failures ledger_rewind_unit
             orphan_credited_tx_unconfirmed)

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
        "$SCRIPT_DIR/cf_reorg_rewind_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$out"
}

# ---- the seams are real (-D silently loses to a plain #define; -w hides the warning) -------------
seam() { if ! grep -q "$1" "$CORE_DIR/$2"; then echo "GATE FAILURE: $2 has no $1 seam; -D would be inert."; exit 1; fi; }
seam CF_REORG_REWIND_UNFIXED BRPeerManager.c
seam CF_OUTSIDE_CHAIN_PENALTY_UNFIXED BRPeerManager.c
seam CF_REANCHOR_REFUND_UNFIXED BRPeerManager.c
seam CF_PARK_BELOW_START_UNFIXED BRPeerManager.c
seam CF_BAND_SCANNED_UNFIXED BRCFScanLedger.c
seam CF_DISAGREER_PORT_UNFIXED BRPeerManager.c

FAIL=0
run_case() {   # run_case <bin> <case> -> OUT, RC
    OUT="$("$1" "$2" 2>&1)"; RC=$?
}
san() { echo "$OUT" | grep -Eq "$SAN_RE"; }

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."; FAIL=1; return
    fi
    # every case the binary knows is listed exactly once
    local known listed
    known="$("$BUILD_DIR/green${bits}" | sort)"
    listed="$( (for a in "${ARMS[@]}"; do for c in ${RED[$a]}; do echo "$c"; done; done; printf '%s\n' "${GUARD_CASES[@]}") | sort)"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: the case list in run.sh and the binary differ:"; diff <(echo "$known") <(echo "$listed"); FAIL=1
    fi

    echo "--- GREEN (fixed): every case PASSES, no sanitizer report ---"
    for c in $known; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if san; then echo "  [$bits green $c] GATE FAILURE: sanitizer report"; echo "$OUT" | grep -E "$SAN_RE" | head -3; FAIL=1
        elif [ $RC -eq 0 ] && echo "$OUT" | grep -q "RESULT $c PASS"; then echo "  [$bits green $c] PASS"
        else echo "  [$bits green $c] GATE FAILURE: did not pass (rc=$RC)"; echo "$OUT" | grep -E "FAIL|RESULT" | sed 's/^/      /'; FAIL=1; fi
    done

    for a in "${ARMS[@]}"; do
        if ! build "$BUILD_DIR/red_${a}_${bits}" "$bits" "-D$a"; then
            echo "GATE FAILURE: ${bits}-bit -D$a arm did not compile."; FAIL=1; continue
        fi
        echo "--- RED -D$a: its cases MUST FAIL; the guards MUST PASS ---"
        for c in ${RED[$a]}; do
            run_case "$BUILD_DIR/red_${a}_${bits}" "$c"
            if san; then echo "  [$bits $a $c] GATE FAILURE: sanitizer report"; FAIL=1
            elif [ $RC -ne 0 ] && echo "$OUT" | grep -q "RESULT $c FAIL"; then
                echo "  [$bits $a $c] failed, as required: $(echo "$OUT" | grep -m1 '  FAIL' | sed 's/^ *FAIL *//')"
            else echo "  [$bits $a $c] GATE FAILURE: passed in the earlier shape (rc=$RC) -- the seam is not load-bearing"; FAIL=1; fi
        done
        for c in "${GUARD_CASES[@]}"; do
            run_case "$BUILD_DIR/red_${a}_${bits}" "$c"
            if san; then echo "  [$bits $a $c] GATE FAILURE: sanitizer report"; FAIL=1
            elif [ $RC -eq 0 ] && echo "$OUT" | grep -q "RESULT $c PASS"; then echo "  [$bits $a $c] guard holds"
            else echo "  [$bits $a $c] GATE FAILURE: guard failed (rc=$RC)"; echo "$OUT" | grep FAIL | sed 's/^/      /'; FAIL=1; fi
        done
    done
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: cf_reorg_rewind_kat (every red case failed in its arm, every case passed in green, guards held; 64-bit and 32-bit)"
    exit 0
fi
echo "FAIL: cf_reorg_rewind_kat"
exit 1
