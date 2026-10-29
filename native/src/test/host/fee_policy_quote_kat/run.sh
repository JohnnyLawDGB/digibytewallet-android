#!/usr/bin/env bash
# Host KAT runner for BRFeePolicy.h's 4.1.0 rule -- the rate a DGB send is built at (the default, or
# a custom total over the vsize of this send as built at the default rate) and the "raised" verdict,
# from Android 4.1.0's FeeQuote.kt and TransactionBuilder.prepareTransaction. Carries every
# FeeQuoteTest.kt case with its values; see fee_policy_quote_kat_main.c.
#
# Header-only: includes BRFeePolicy.h (and through it BRAmountParse.h) and links nothing but libc.
# Two translation units are linked, the header included twice in the first.
#
#   comparison  -DFEE_POLICY_QUOTE_UNFIXED=1, the 4.0.89 rule (a total over a typical 141 vbytes, a
#               custom fee that is not an amount built at the default rate, nothing ever "raised").
#               Each case meets the expectation CASES gives it:
#                 fail  the case fails: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#               and the arm must exit nonzero.
#   shipped     -DFEE_POLICY_QUOTE_UNFIXED=0: every case passes, ALL PASS, exit 0.
# Both arms under ASan + UBSan, 64-bit. Gates first: the header must test the seam's VALUE (#if,
# never #ifdef: the =0 build would otherwise run the comparison arm) with an #ifndef default.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DFEE_POLICY_QUOTE_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRFeePolicy.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<comparison-arm expectation>
CASES=(
    six_input_default_quoted_as_built:pass
    custom_below_floor_shown_raised:fail
    custom_paid_exactly_not_raised:fail
    custom_rate_over_own_size:fail
    custom_not_positive_quotes_nothing:fail
    typed_total_rides_with_quote:fail
    custom_rate_never_zero:fail
    rate_exact_then_saturated:fail
    raised_strictly_more:fail
    default_rate_is_kotlins:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRFeeRequestFor BRFeeRateForTotal BRFeeRateForCustomTotal BRFeeQuoteRaised; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ $fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn: a core pin from before the 4.1.0 rule."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+FEE_POLICY_QUOTE_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of FEE_POLICY_QUOTE_UNFIXED; the arms would not differ."
    exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+FEE_POLICY_QUOTE_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+FEE_POLICY_QUOTE_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."
    exit 1
fi

cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRFeePolicy.h"
uint64_t fee_policy_quote_kat_tu2(int64_t totalSats, int64_t vsize);
uint64_t fee_policy_quote_kat_tu2(int64_t totalSats, int64_t vsize) { return BRFeeRateForTotal(totalSats, vsize); }
EOF

build() {   # <out> <unfixed 0|1>
    local out="$1" unfixed="$2"
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer -DFEE_POLICY_QUOTE_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/fee_policy_quote_kat_main.c" "$BUILD_DIR/tu2.c" -o "$out"
}

FAIL=0
build "$BUILD_DIR/red" 1 || { echo "GATE FAILURE: comparison arm did not compile (a build error is not evidence)."; exit 1; }
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

echo "--- COMPARISON ARM (-DFEE_POLICY_QUOTE_UNFIXED=1, the 4.0.89 rule): each case meets its expectation ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
grep -Eq "$SAN_RE" <<<"$out" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; want="${entry##*:}"
    case "$want" in
    fail)
        if grep -q "^RESULT $c fail$" <<<"$out"; then
            first="$(awk -v c="$c" '/^FAIL: / && f == "" { f = $0 } /^RESULT / { if ($0 == "RESULT " c " fail") { print f; exit } f = "" }' <<<"$out")"
            first="${first:0:170}"
            echo "  [red $c] failed, as it must: ${first#FAIL: }"
        else
            echo "  [red $c] GATE FAILURE: the comparison arm did not fail this case -- it cannot see the defect"; FAIL=1
        fi ;;
    pass)
        if grep -q "^RESULT $c pass$" <<<"$out"; then echo "  [red $c] passed (guard)"
        else echo "  [red $c] GATE FAILURE: the guard did not hold in the comparison arm"; FAIL=1; fi ;;
    *)
        echo "  [red $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
    esac
done

echo "--- SHIPPED ARM (-DFEE_POLICY_QUOTE_UNFIXED=0): every case passes, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
grep -Eq "$SAN_RE" <<<"$out" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! grep -q "^ALL PASS$" <<<"$out"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; grep "^FAIL:" <<<"$out" | sed 's/^/      /'; FAIL=1
fi
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    if grep -q "^RESULT $c pass$" <<<"$out"; then echo "  [green $c] passed"
    else echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; fi
done

[ $FAIL -eq 0 ] && echo "PASS: fee_policy_quote_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean under ASan + UBSan)"
exit $FAIL
