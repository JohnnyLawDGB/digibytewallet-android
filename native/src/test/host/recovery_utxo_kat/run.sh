#!/usr/bin/env bash
# Host KAT runner for BRRecoveryUtxo.h: which unspent outputs a Recover-funds scan counts and sweeps
# (one coin per outpoint, the confirmed copy kept; zero-value P2TR rows are not coins; a non-positive
# amount refuses the sweep). See recovery_utxo_kat_main.c.
#
# Pure functions, libc only: the header is compiled into the KAT (two translation units, the header
# included twice in the second).
#
#   comparison  -DRECOVERY_UTXO_UNFIXED=1: the unconfirmed copy kept, zero-value P2TR rows kept, a zero
#               amount admitted. Each case meets the expectation CASES gives it:
#                 fail  the case fails: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#               and the arm must exit nonzero.
#               One macro trips all three defects, so a case may fail through the dedup inversion
#               rather than the defect it is named for (end_to_end_observed, the two large cases).
#   shipped     -DRECOVERY_UTXO_UNFIXED=0: every case passes, exit 0.
# Both arms under ASan + UBSan, 64-bit.
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc headers, reads no clock and does no I/O, and builds
#                     clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 and as C++17 (what a
#                     Swift or Objective-C++ importer needs); for iphoneos/iphonesimulator too where an
#                     Xcode SDK is present (skipped, and said so, where none is).
#   FIXTURES GATE     the core's vectors/reconcile-response/ is what its generate.py writes, and every
#                     hand-written expectation there agrees with the generator's reference normaliser.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DRECOVERY_UTXO_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRRecoveryUtxo.h"
FIX_DIR="$CORE_DIR/vectors/reconcile-response"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<comparison-arm expectation>
CASES=(
    same_outpoint_twice_collapses:pass
    total_stops_double_counting:pass
    confirmed_copy_wins:fail
    different_vouts_not_duplicates:pass
    order_preserved:pass
    empty_list_stays_empty:pass
    clean_list_unchanged:pass
    confirmed_copies_keep_first:pass
    unconfirmed_copies_keep_first:pass
    negative_height_is_unconfirmed:fail
    confirmed_later_takes_first_place:fail
    txid_last_byte_differs:pass
    taproot_keeps_dgb_drops_dollar_outputs:fail
    taproot_negative_dropped:fail
    non_taproot_zero_kept:pass
    unknown_format_keeps_zero:pass
    dedup_before_filter:fail
    gate_positive_reachable_admits:pass
    gate_zero_refuses:fail
    gate_negative_refuses:pass
    gate_unreachable_first:pass
    gate_reports_first_offending:fail
    sweep_zero_after_select:fail
    end_to_end_observed:fail
    buffer_too_small:pass
    null_arguments:pass
    total_overflow:pass
    bad_index:pass
    many_rows_many_duplicates:fail
    confirmed_after_many_unconfirmed:fail
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the recovery UTXO rules."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRRecoveryUtxoDedup BRRecoveryUtxoIsCoinForFormat BRRecoveryUtxoSelect BRRecoveryUtxoTotal BRRecoverySweepAdmit; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ $fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+RECOVERY_UTXO_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of RECOVERY_UTXO_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+RECOVERY_UTXO_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+RECOVERY_UTXO_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

FAIL=0

# ---- PORTABILITY GATE ----------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//' | sort -u | tr '\n' ' ')"
if [ "$includes" != "<stddef.h> <stdint.h> <string.h> " ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(libc <stddef.h> <stdint.h> <string.h> only)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRRecoveryUtxo.h"
int strict_tu1(void);
int strict_tu1(void) { return BRRecoveryUtxoIsCoinForFormat(BR_RECOVERY_FORMAT_P2TR, 1); }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRRecoveryUtxo.h"
#include "BRRecoveryUtxo.h"
int strict_tu1(void);
int main(void) { size_t k = 0; BRRecoveryUtxo u[1] = {{{0}, 0, 1, 1}}; size_t kept[2];
    return (BRRecoveryUtxoSelect(0, u, 1, kept, 2, &k) == BR_RECOVERY_UTXO_OK && k == 1 && strict_tu1()) ? 0 : 1; }
EOF
cp "$BUILD_DIR/strict2.c" "$BUILD_DIR/strict2.cpp"
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -I "$CORE_DIR" "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
    fi
done
CXX="${CXX:-clang++}"
if command -v "$CXX" >/dev/null 2>&1; then
    if ! "$CXX" -std=c++17 -Wall -Wextra -Wpedantic -Werror -I "$CORE_DIR" -c "$BUILD_DIR/strict2.cpp" -o "$BUILD_DIR/strict.o"; then
        echo "PORTABILITY GATE FAILED: clang++ -std=c++17"; FAIL=1; strict_ok=0
    fi
    cxx_note="C++17"
else
    cxx_note="no C++ compiler here, C++17 skipped"
fi
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -I "$CORE_DIR" \
                -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc only, no clock/I/O/allocation; clean at c99/c11/c17 ($cxx_note; $ios_note)"

# ---- FIXTURES GATE -------------------------------------------------------------------------
PY="${PYTHON:-python3}"
if [ ! -f "$FIX_DIR/generate.py" ] || [ ! -f "$FIX_DIR/expected.txt" ]; then
    echo "FIXTURES GATE FAILED: $FIX_DIR/generate.py or expected.txt is missing"; FAIL=1
elif ! command -v "$PY" >/dev/null 2>&1; then
    echo "FIXTURES GATE FAILED: no $PY to run the generator (a missing tool is not evidence)"; FAIL=1
else
    out="$("$PY" -I "$FIX_DIR/generate.py" --check 2>&1)"; rc=$?
    if [ $rc -ne 0 ]; then
        echo "FIXTURES GATE FAILED: vectors/reconcile-response/ is not current, or an expectation disagrees"
        sed -n '1,12p' <<<"$out" | sed 's/^/      /'; FAIL=1
    else
        echo "fixtures gate: $out"
    fi
fi

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRRecoveryUtxo.h"
#include "BRRecoveryUtxo.h"
int recovery_utxo_kat_tu2(int64_t a);
int recovery_utxo_kat_tu2(int64_t a) { return BRRecoveryUtxoAmountIsSweepable(a); }
EOF

build() {   # <out> <unfixed 0|1>
    "$CC" -w -std=c99 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer -DRECOVERY_UTXO_UNFIXED="$2" -I "$CORE_DIR" \
        "$SCRIPT_DIR/recovery_utxo_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
}

build "$BUILD_DIR/red" 1 || { echo "GATE FAILURE: comparison arm did not compile (a build error is not evidence)."; exit 1; }
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

echo "--- COMPARISON ARM (-DRECOVERY_UTXO_UNFIXED=1, unconfirmed copy kept, zero-value P2TR kept, zero admitted) ---"
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
        else echo "  [red $c] GATE FAILURE: the guard did not hold in the comparison arm"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1; fi ;;
    *)
        echo "  [red $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
    esac
done
[ $red_any -eq 0 ] && { echo "  [red] GATE FAILURE: every case passed in the comparison arm; it proves nothing"; FAIL=1; }

echo "--- SHIPPED ARM (-DRECOVERY_UTXO_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
    fi
done

[ $FAIL -eq 0 ] && echo "PASS: recovery_utxo_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean under ASan + UBSan; portability; fixtures current)"
[ $FAIL -ne 0 ] && echo "FAIL: recovery_utxo_kat"
exit $FAIL
