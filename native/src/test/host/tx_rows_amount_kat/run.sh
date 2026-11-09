#!/usr/bin/env bash
# Host KAT runner: the amount and fee of a transaction-list row (the AMOUNT and FEE rules) (BRTxRows.h). See tx_rows_amount_kat_main.c for the rule and the cases.
#
# Compiles the core's BRTxRows.h with the live core sources, under AddressSanitizer and
# UndefinedBehaviorSanitizer, in a 64-bit and a 32-bit (-m32) build, in two arms:
#   comparison  -DTX_ROWS_UNFIXED=1: the rows as the platforms read them before the header (every
#               amount received - sent, an unknown fee read as 0, the raw timestamp, iOS's order).
#               Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#   shipped     -DTX_ROWS_UNFIXED=0: every case passes, with no sanitizer report.
# Then every case again on the 64-bit shipped arm under LeakSanitizer (detect_leaks=1; LeakSanitizer
# has no i386 runtime, and none on macOS: that step fails there, as it does for every suite that runs
# it -- an environment limit, never a pass). Every run is time-limited.
#
# Gates first: the header must exist and test the seam's VALUE (#if, never #ifdef: the =0 build would
# otherwise run the comparison arm), with an #ifndef default.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DTX_ROWS_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRTxRows.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"
ASAN_RUN="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S="${TX_ROWS_LIMIT:-120}"

# <case>:<comparison-arm expectation>
CASES=(
    rule_table:fail
    amount_arithmetic:pass
    fee_unknown_pure:fail
    receive_is_net_fee_unknown:fail
    send_payee_only:fail
    send_everything:fail
    self_send_is_fee:pass
    self_sweep_foreign_key:pass
    missing_parent_p2wpkh_send:fail
    partial_parent_send:fail
    digiasset_send_marker:fail
    digidollar_keeps_net:fail
    taproot_send_payee:fail
    taproot_keypath_missing_parent:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the transaction-row reader."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRWalletTxRows BRWalletTxRowCount BRWalletTxRowFill BRTxRowCompare BRTxRowAmountRuleFor BRTxRowAmountFor \
          BRTxRowFeeFor BRTxRowShownTimestamp; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+[ *]$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+TX_ROWS_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of TX_ROWS_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+TX_ROWS_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+TX_ROWS_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

# A second translation unit that includes the header too: the static inline functions must link
# from two units of one program.
cat > "$BUILD_DIR/tu2.c" <<'TU'
#include "BRTxRows.h"
size_t tx_rows_kat_tu2(BRWallet *w, uint32_t now, BRTxRow *rows, size_t max);
size_t tx_rows_kat_tu2(BRWallet *w, uint32_t now, BRTxRow *rows, size_t max) { return BRWalletTxRows(w, now, rows, max); }
TU

build() {   # <out> <64|32> <unfixed 0|1>
    local out="$1" bits="$2" unfixed="$3"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer ${m[@]+"${m[@]}"} -DTX_ROWS_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_rows_amount_kat_main.c" "$BUILD_DIR/tu2.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <asan options> <cmd...>: the command's exit code, or 124 if it was stopped at the
# limit. The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was
# stopped (the race fixed in base58_input_bound_kat, #211).
bounded() {
    local outfile="$1" asan="$2"; shift 2
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    ASAN_OPTIONS="$asan" "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }

FAIL=0
for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/red$bits" "$bits" 1; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; continue
    fi
    if ! build "$BUILD_DIR/green$bits" "$bits" 0; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; continue
    fi

    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; continue
    fi

    echo "--- COMPARISON ARM (-DTX_ROWS_UNFIXED=1): each case meets its expectation ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red$bits.$c.out"
        bounded "$out" "$ASAN_RUN" "$BUILD_DIR/red$bits" "$c"; rc=$?
        if [ $rc -eq 124 ]; then
            echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue
        fi
        if grep -Eq "$SAN_RE" "$out"; then
            echo "  [red$bits $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue
        fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                echo "  [red$bits $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-160)"
            else
                echo "  [red$bits $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"
                show "$out"; FAIL=1
            fi ;;
        pass)
            if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
                echo "  [red$bits $c] passed (guard)"
            else
                echo "  [red$bits $c] GATE FAILURE: the guard did not hold in the comparison arm (rc=$rc)"
                show "$out" 12; FAIL=1
            fi ;;
        *)
            echo "  [red$bits $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
        esac
    done

    echo "--- SHIPPED ARM (-DTX_ROWS_UNFIXED=0): every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$ASAN_RUN" "$BUILD_DIR/green$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
        fi
    done
done

echo "======================================================================"
echo "=== LEAK CHECK: every case on the 64-bit shipped arm under detect_leaks=1 ==="
echo "======================================================================"
if [ -x "$BUILD_DIR/green64" ]; then
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/leak.$c.out"
        bounded "$out" "$ASAN_LEAK" "$BUILD_DIR/green64" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [leak $c] clean"
        else
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; show "$out" 6; FAIL=1
        fi
    done
else
    echo "GATE FAILURE: no 64-bit shipped build to leak-check"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: tx_rows_amount_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan + UBSan; leak check)"
    exit 0
fi
echo "FAIL: tx_rows_amount_kat"
exit 1
