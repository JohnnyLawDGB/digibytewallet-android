#!/usr/bin/env bash
# Host KAT runner for BRTxRows.h's ORDER and DATE rules: the order a transaction list is shown in
# (shownTimestamp desc, blockHeight desc, walletIndex asc) and the date a row shows (its timestamp,
# or the caller's `now` while it has none). See tx_rows_order_kat_main.c.
#
# Pure functions: the header is compiled into the KAT (two translation units, the header included
# twice in the second) and only its pure rules run, so nothing but libc is linked.
#
#   comparison  -DTX_ROWS_UNFIXED=1, iOS's order (unconfirmed first, then height, then date) and the
#               raw timestamp. Each case meets the expectation CASES gives it:
#                 fail  the case fails: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#               and the arm must exit nonzero.
#   shipped     -DTX_ROWS_UNFIXED=0: every case passes, exit 0.
# Both arms under ASan + UBSan, 64-bit.
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then two more:
#   CLOCK GATE    the header reads no clock (the DATE rule's `now` is the caller's). Checked against a
#                 fixture that calls time() first, so it cannot pass vacuously.
#   VECTORS GATE  the core's vectors/tx-rows/vectors.txt is what vectors/tx-rows/generate.c writes from
#                 this header: the shared vectors a platform binding test reads are current.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DTX_ROWS_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRTxRows.h"
VEC_DIR="$CORE_DIR/vectors/tx-rows"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<comparison-arm expectation>
CASES=(
    date_desc_newest_first:pass
    date_over_height:fail
    height_breaks_date_tie:pass
    index_breaks_full_tie:pass
    pending_now_on_top:pass
    shown_timestamp_pending_is_now:fail
    shown_timestamp_own:pass
    old_pending_not_on_top:fail
    pending_tie_above_confirmed:pass
    pending_tie_by_index:pass
    edges:fail
    total_order:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the transaction-row reader."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRTxRowCompare BRTxRowShownTimestamp; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ $fn\(" <<<"$HDR"; then
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

FAIL=0

# ---- CLOCK GATE ----------------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
CLOCK_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|mach_absolute_time|CFAbsoluteTimeGetCurrent)[[:space:]]*\('
reads_clock() { grep -Eq "$CLOCK_RE" <<<"$(strip_comments "$1")"; }
printf 'static inline unsigned f(void) { return (unsigned)time(NULL); }\n' > "$BUILD_DIR/clock_fixture.h"
printf '// time(NULL) in a comment is not a clock read\nstatic inline unsigned g(unsigned now) { return now; }\n' > "$BUILD_DIR/noclock_fixture.h"
if ! reads_clock "$BUILD_DIR/clock_fixture.h" || reads_clock "$BUILD_DIR/noclock_fixture.h"; then
    echo "GATE FAILURE: the clock gate does not tell a clock read from a comment; it would prove nothing"; FAIL=1
elif reads_clock "$HEADER"; then
    echo "CLOCK GATE FAILED: $HEADER reads a clock; the DATE rule's now must be the caller's"
    grep -nE "$CLOCK_RE" <<<"$(strip_comments "$HEADER")" | sed 's/^/      /'
    FAIL=1
else
    echo "clock gate: BRTxRows.h reads no clock (the gate flags its time() fixture and passes its comment fixture)"
fi

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$VEC_DIR/generate.c" -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
else
    "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from this header; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | sed 's/^/      /'
        FAIL=1
    else
        echo "vectors gate: vectors/tx-rows/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRTxRows.h"
#include "BRTxRows.h"
int tx_rows_order_kat_tu2(const BRTxRow *a, const BRTxRow *b);
int tx_rows_order_kat_tu2(const BRTxRow *a, const BRTxRow *b) { return BRTxRowCompare(a, b); }
EOF

build() {   # <out> <unfixed 0|1>
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer -DTX_ROWS_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_rows_order_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
}

build "$BUILD_DIR/red" 1 || { echo "GATE FAILURE: comparison arm did not compile (a build error is not evidence)."; exit 1; }
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

echo "--- COMPARISON ARM (-DTX_ROWS_UNFIXED=1, iOS's order and the raw timestamp): each case meets its expectation ---"
red_any=0
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; want="${entry##*:}"
    out="$("$BUILD_DIR/red" "$c" 2>&1)"; rc=$?
    [ $rc -ne 0 ] && red_any=1
    if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [red $c] GATE FAILURE: sanitizer report"; FAIL=1; continue; fi
    case "$want" in
    fail)
        if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
            first="$(grep -m1 '^FAIL:' <<<"$out")"
            echo "  [red $c] failed, as it must: ${first:6:160}"
        else
            echo "  [red $c] GATE FAILURE: the comparison arm did not fail this case -- it cannot see the defect"; FAIL=1
        fi ;;
    pass)
        if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" <<<"$out"; then echo "  [red $c] passed (guard)"
        else echo "  [red $c] GATE FAILURE: the guard did not hold in the comparison arm"; FAIL=1; fi ;;
    *)
        echo "  [red $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
    esac
done
[ $red_any -eq 0 ] && { echo "  [red] GATE FAILURE: every case passed in the comparison arm; it proves nothing"; FAIL=1; }

echo "--- SHIPPED ARM (-DTX_ROWS_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
    fi
done

[ $FAIL -eq 0 ] && echo "PASS: tx_rows_order_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean under ASan + UBSan; clock gate; vectors current)"
[ $FAIL -ne 0 ] && echo "FAIL: tx_rows_order_kat"
exit $FAIL
