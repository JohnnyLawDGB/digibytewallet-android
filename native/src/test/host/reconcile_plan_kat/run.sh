#!/usr/bin/env bash
# Host KAT runner for BRReconcilePlan.h: the planning rules of a node reconcile (the known set, the
# history-import plan, the import step and its coverage, the pending rule, distinct addresses and
# outpoints). See reconcile_plan_kat_main.c for the rules and the cases, which carry Android's
# AddressHistoryPlanTest and HistoryImportCoverageTest case for case.
#
# Compiles the header with the live core sources (the known-set and pending cases read real wallets),
# under AddressSanitizer and UndefinedBehaviorSanitizer, 64-bit, in two arms:
#   comparison  -DRECONCILE_PLAN_UNFIXED=1: the known set truncated to the newest 100, a repeated txid
#               planned at its lowest height, a refused registration read as recovered. Each case meets
#               the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#               and at least one case must fail.
#   shipped     -DRECONCILE_PLAN_UNFIXED=0: every case passes, with no sanitizer report.
# Every run is time-limited.
#
# Gates first: the header must exist and define its functions, and test the seam's VALUE (#if, never
# #ifdef) with an #ifndef default. Then:
#   CLOCK/IO GATE  the header reads no clock and does no I/O (checked against fixtures, so it cannot pass
#                  vacuously).
#   VECTORS GATE   the core's vectors/reconcile-plan/vectors.txt is what vectors/reconcile-plan/generate.c
#                  writes from this header: the shared vectors a platform mirror reads are current.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DRECONCILE_PLAN_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRReconcilePlan.h"
VEC_DIR="$CORE_DIR/vectors/reconcile-plan"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S="${RECONCILE_PLAN_LIMIT:-120}"

# <case>:<comparison-arm expectation>
CASES=(
    extracts_known_txids_from_details:pass
    blank_lines_ignored:pass
    plan_drops_known_and_dedups_across_addresses:pass
    plan_keeps_highest_height_for_duplicate_txid:fail
    empty_history_yields_empty_plan:pass
    every_planned_tx_registered_is_covered:pass
    nothing_planned_is_covered:pass
    register_rejection_is_not_coverage:fail
    one_rejection_among_many_successes_sinks_coverage:fail
    fetch_failure_is_not_coverage:pass
    hex_decode_failure_is_not_coverage:pass
    all_three_failure_modes_counted_together:fail
    known_set_is_uncapped:fail
    wide_wallet_can_still_achieve_coverage:fail
    null_wallet_has_no_known_set:pass
    zero_and_null_txids_are_ignored:pass
    truncated_read_is_incomplete_never_capped:pass
    long_history_known_set_complete:fail
    registers_with_the_nodes_confirming_height:pass
    bytes_of_another_transaction_not_registered:pass
    the_check_is_given_the_requested_txid:pass
    first_seen_order:fail
    plan_buffer_and_known_order:pass
    coverage_counts_the_plan:pass
    coverage_needs_every_address_answered:pass
    plan_first_seen_with_many_repeats:pass
    large_inputs_are_n_log_n:pass
    pending_rule:pass
    pending_txids_from_wallet:pass
    distinct_outpoints:pass
    distinct_addresses:pass
    sizes_overflow_checked:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the reconcile plan."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRReconcileKnownTxHashes BRReconcilePendingTxHashes BRReconcileIsPending BRReconcilePlanHistoryImport \
          BRReconcileImportStepFor BRReconcileImportMayRegister BRReconcileImportStepRecovered BRReconcileImportBegin \
          BRReconcileImportRecord BRReconcileImportCovered BRReconcileDistinctOutpoints BRReconcileDistinctAddresses \
          BRReconcileAddressTrim BRReconcileSortTxHashes BRReconcileKnownContains BRReconcileCapacity BRReconcileArrayBytes; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+[ *]$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+RECONCILE_PLAN_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of RECONCILE_PLAN_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+RECONCILE_PLAN_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+RECONCILE_PLAN_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

FAIL=0

# ---- CLOCK/IO GATE -------------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|mach_absolute_time|fopen|fread|fwrite|open|read|write|socket|connect|send|recv|printf|fprintf|getenv)[[:space:]]*\('
does_io() { grep -Eq "$IO_RE" <<<"$(strip_comments "$1")"; }
printf 'static inline int f(void) { return (int)time(NULL) + printf("x"); }\n' > "$BUILD_DIR/io_fixture.h"
printf '// time(NULL), fopen("x") in a comment are not calls\nstatic inline int g(int now) { return now; }\n' > "$BUILD_DIR/noio_fixture.h"
if ! does_io "$BUILD_DIR/io_fixture.h" || does_io "$BUILD_DIR/noio_fixture.h"; then
    echo "GATE FAILURE: the clock/io gate does not tell a call from a comment; it would prove nothing"; FAIL=1
elif does_io "$HEADER"; then
    echo "CLOCK/IO GATE FAILED: $HEADER reads a clock or does I/O"
    grep -nE "$IO_RE" <<<"$(strip_comments "$HEADER")" | sed 's/^/      /'
    FAIL=1
else
    echo "clock/io gate: BRReconcilePlan.h reads no clock and does no I/O (the gate flags its fixture and passes its comment fixture)"
fi

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$VEC_DIR/generate.c" -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
else
    "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from this header; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'
        FAIL=1
    else
        echo "vectors gate: vectors/reconcile-plan/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- the arms ------------------------------------------------------------------------------
shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

# A second translation unit that includes the header twice: the guard holds, and the static inline
# functions link from two units of one program.
cat > "$BUILD_DIR/tu2.c" <<'TU'
#include "BRReconcilePlan.h"
#include "BRReconcilePlan.h"
int reconcile_plan_kat_tu2(const BRReconcileImportOutcome *o);
int reconcile_plan_kat_tu2(const BRReconcileImportOutcome *o) { return BRReconcileImportCovered(o); }
TU

build() {   # <out> <unfixed 0|1>
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer -DRECONCILE_PLAN_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/reconcile_plan_kat_main.c" "$BUILD_DIR/tu2.c" "${UNITS[@]}" -lm -lpthread -o "$1"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was stopped at the limit. The
# watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was stopped.
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }

build "$BUILD_DIR/red" 1 || { echo "GATE FAILURE: comparison arm did not compile (a build error is not evidence)."; exit 1; }
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

echo "--- COMPARISON ARM (-DRECONCILE_PLAN_UNFIXED=1): each case meets its expectation ---"
red_any=0
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red.$c.out"
    bounded "$out" "$BUILD_DIR/red" "$c"; rc=$?
    [ $rc -ne 0 ] && red_any=1
    if [ $rc -eq 124 ]; then echo "  [red $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
    if grep -Eq "$SAN_RE" "$out"; then echo "  [red $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
    case "$want" in
    fail)
        if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
            echo "  [red $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-150)"
        else
            echo "  [red $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"
            show "$out"; FAIL=1
        fi ;;
    pass)
        if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then echo "  [red $c] passed (guard)"
        else echo "  [red $c] GATE FAILURE: the guard did not hold in the comparison arm (rc=$rc)"; show "$out" 12; FAIL=1; fi ;;
    *)
        echo "  [red $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
    esac
done
[ $red_any -eq 0 ] && { echo "  [red] GATE FAILURE: every case passed in the comparison arm; it proves nothing"; FAIL=1; }

echo "--- SHIPPED ARM (-DRECONCILE_PLAN_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; out="$BUILD_DIR/green.$c.out"
    bounded "$out" "$BUILD_DIR/green" "$c"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' "$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: reconcile_plan_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean under ASan + UBSan, 64-bit; clock/io gate; vectors current)"
    exit 0
fi
echo "FAIL: reconcile_plan_kat"
exit 1
