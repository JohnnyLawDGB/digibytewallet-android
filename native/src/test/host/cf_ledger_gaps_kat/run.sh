#!/usr/bin/env bash
# Host KAT runner for 4.1.4 item CF-LEDGER-GAPS. See cf_ledger_gaps_kat_main.c for the three
# invariants (floor memo, band kept across a ledger re-Init, contiguous never-brick park).
#
# Compiles the REAL, live submodule sources with AddressSanitizer. The main file #includes BRPeer.c
# and BRPeerManager.c (for their file statics), so neither is passed as a separate unit.
#
# ARMS. Green is no -D at all, so a shipped build (which defines none of these) gets the fixed code.
# Each red arm restores ONE earlier shape:
#   -DCF_FLOOR_MEMO_UNFIXED   a same-hash replace drops the floor memo only when it displaced the tip
#   -DCF_BAND_KEPT_UNFIXED    a ledger re-Init zeroes a band surfaced earlier in the same locked section
#   -DCF_PARK_CONTIG_UNFIXED  the never-brick park sets the cursor to the checkpoint even when that is
#                             above what the ledger requested
#   -DCF_PARK_LAG_UNFIXED     when the ledger lags below the filter-header chain's start, the park
#                             still jumps to the checkpoint over the in-chain heights below it
# RED cases must FAIL in their arm and PASS in green. GUARD cases must PASS in every arm.
# Every case, every arm: no sanitizer report. A build error is never evidence.
set -uo pipefail   # not -e: the red arms' nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"

declare -A RED=(
    [CF_FLOOR_MEMO_UNFIXED]="floor_memo_relayed_stub_replace floor_memo_install_saved_stub_replace"
    [CF_BAND_KEPT_UNFIXED]="band_survives_cfheaders_floor_snap band_survives_floor_reanchor"
    [CF_PARK_CONTIG_UNFIXED]="park_never_jumps_past_requested"
    [CF_PARK_LAG_UNFIXED]="park_lagging_stops_at_chain_start"
)
ARMS=(CF_FLOOR_MEMO_UNFIXED CF_BAND_KEPT_UNFIXED CF_PARK_CONTIG_UNFIXED CF_PARK_LAG_UNFIXED)
GUARD_CASES=(band_not_carried_below_or_absent park_still_moves_back)

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

# build <out> [extra -D flags...]. Header check levels: the shipped ones (run-host-kats.sh appends
# them); this suite names none.
build() {
    local out="$1"; shift
    "${CC:-clang}" -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_ledger_gaps_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$out"
}

# ---- the seams are real (-D silently loses to a plain #define; -w hides the warning) -------------
seam() { if ! grep -q "$1" "$CORE_DIR/$2"; then echo "GATE FAILURE: $2 has no $1 seam; -D would be inert."; exit 1; fi; }
seam CF_FLOOR_MEMO_UNFIXED BRPeerManager.c
seam CF_BAND_KEPT_UNFIXED BRPeerManager.c
seam CF_PARK_CONTIG_UNFIXED BRPeerManager.c
seam CF_PARK_LAG_UNFIXED BRPeerManager.c

FAIL=0
run_case() { OUT="$("$1" "$2" 2>&1)"; RC=$?; }
san() { <<<"$OUT" grep -Eq "$SAN_RE"; }

if ! build "$BUILD_DIR/green"; then echo "GATE FAILURE: green arm did not compile."; exit 1; fi
known="$("$BUILD_DIR/green" | sort)"
listed="$( (for a in "${ARMS[@]}"; do for c in ${RED[$a]}; do echo "$c"; done; done; printf '%s\n' "${GUARD_CASES[@]}") | sort)"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: the case list in run.sh and the binary differ:"; diff <(echo "$known") <(echo "$listed"); FAIL=1
fi

echo "--- GREEN (fixed): every case PASSES, no sanitizer report ---"
for c in $known; do
    run_case "$BUILD_DIR/green" "$c"
    if san; then echo "  [green $c] GATE FAILURE: sanitizer report"; <<<"$OUT" grep -E "$SAN_RE" | head -3; FAIL=1
    elif [ $RC -eq 0 ] && <<<"$OUT" grep -q "RESULT $c PASS"; then echo "  [green $c] PASS"
    else echo "  [green $c] GATE FAILURE: did not pass (rc=$RC)"; <<<"$OUT" grep -E "FAIL|RESULT" | sed 's/^/      /'; FAIL=1; fi
done

for a in "${ARMS[@]}"; do
    if ! build "$BUILD_DIR/red_$a" "-D$a"; then echo "GATE FAILURE: -D$a arm did not compile."; FAIL=1; continue; fi
    echo "--- RED -D$a: its cases MUST FAIL; the guards MUST PASS ---"
    for c in ${RED[$a]}; do
        run_case "$BUILD_DIR/red_$a" "$c"
        if san; then echo "  [$a $c] GATE FAILURE: sanitizer report"; <<<"$OUT" grep -E "$SAN_RE" | head -3; FAIL=1
        elif <<<"$OUT" grep -q '^  FAIL setup:'; then
            # A red arm must fail on the case's invariant, not on its fixture: a broken setup is
            # never evidence that the seam is load-bearing.
            echo "  [$a $c] GATE FAILURE: failed in setup, not on its invariant"; <<<"$OUT" grep '^  FAIL setup:' | sed 's/^/      /'; FAIL=1
        elif [ $RC -ne 0 ] && <<<"$OUT" grep -q "RESULT $c FAIL" && <<<"$OUT" grep -Eq '^  FAIL '; then
            echo "  [$a $c] failed, as required: $(<<<"$OUT" grep -m1 '^  FAIL ' | sed 's/^ *FAIL *//')"
        else echo "  [$a $c] GATE FAILURE: passed in the earlier shape (rc=$RC) -- the seam is not load-bearing"; FAIL=1; fi
    done
    for c in "${GUARD_CASES[@]}"; do
        run_case "$BUILD_DIR/red_$a" "$c"
        if san; then echo "  [$a $c] GATE FAILURE: sanitizer report"; FAIL=1
        elif [ $RC -eq 0 ] && <<<"$OUT" grep -q "RESULT $c PASS"; then echo "  [$a $c] guard holds"
        else echo "  [$a $c] GATE FAILURE: guard failed (rc=$RC)"; <<<"$OUT" grep FAIL | sed 's/^/      /'; FAIL=1; fi
    done
done

if [ $FAIL -ne 0 ]; then echo "cf_ledger_gaps_kat: GATE FAILED"; exit 1; fi
echo "cf_ledger_gaps_kat: ALL PASS (green passes every case; each red arm fails its own cases; guards hold)"
