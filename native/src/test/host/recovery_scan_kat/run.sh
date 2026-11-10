#!/usr/bin/env bash
# Host KAT runner for BRRecoveryScan.h: the Recover-funds scan decisions above one UTXO row (which
# addresses the lookup shows as used, what one widening round means, what a finished scan may say, the
# sweep set, the passphrase verdict, recoverable value). See recovery_scan_kat_main.c.
#
#   comparison  -DRECOVERY_SCAN_UNFIXED=1: an unreachable profile (and an unanswered DigiDollar lookup)
#               read as answered and empty, incomplete ranked ahead of a positive finding, coins only
#               (an address paid and since emptied not used), the native profiles swept on the
#               wallet's own seed. Each case meets the expectation CASES gives it:
#                 fail  the case fails: the arm cannot keep the rule
#                 pass  a guard: holds in both arms
#               and the arm must exit nonzero. One macro trips all four defects.
#   shipped     -DRECOVERY_SCAN_UNFIXED=0: every case passes, exit 0.
# Both arms under ASan + UBSan, 64-bit, linked against the core's transaction and address code (the
# header parses output scripts with BRTransactionParseSigned and BRAddressScriptPubKey).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock and does no I/O,
#                     and builds clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs,
#                     the header included twice in the second); for iphoneos/iphonesimulator too where
#                     an Xcode SDK is present (skipped, and said so, where none is). Not C++: it
#                     includes BRTransaction.h, hence BRInt.h, which is not valid C++ (push-down recipe).
#   VECTORS GATE      the core's vectors/recovery-scan/vectors.txt is what vectors/recovery-scan/generate.c
#                     writes from this header.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DRECOVERY_SCAN_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRRecoveryScan.h"
VEC_DIR="$CORE_DIR/vectors/recovery-scan"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<comparison-arm expectation>
CASES=(
    pv_funds_is_success:pass
    pv_typo:pass
    pv_none_anywhere:pass
    pv_incomplete_cannot_claim_nothing:pass
    pv_positive_survives_incomplete:fail
    pv_no_comparison_no_typo:pass
    pv_found_needs_no_comparison:pass
    pv_negative_totals_are_not_funds:pass
    partial_one_failed_among_several:fail
    partial_clean_empty_not_partial:pass
    partial_total_outage_is_both:fail
    partial_no_addresses_not_unreachable:pass
    partial_failure_with_funds_still_matters:fail
    partial_nothing_asked_is_all_unreachable:pass
    cache_only_when_all_reachable:fail
    incomplete_from_capped_or_unreachable:fail
    rv_dollars_alone:pass
    rv_nothing:pass
    rv_no_dd_scan_nothing:pass
    rv_utxos_alone:pass
    rv_unreachable_dd_not_answered:fail
    rv_reachable_zero_answered:pass
    sweep_foreign_includes_native_own_excludes:fail
    sweep_unfunded_not_swept:pass
    sweep_and_total_arguments:pass
    widen_coin_past_first_window:pass
    widen_empty_path_asked_once:pass
    widen_capped_marked_incomplete:pass
    widen_unknown_outcome_capped:pass
    widen_paid_but_emptied_counts:fail
    widen_round_bound_and_numbering:pass
    widen_unreachable_kept_unreachable:fail
    round_bound_from_profiles:pass
    used_highest_per_chain:pass
    used_coin_matching:pass
    used_script_matching:fail
    used_unparseable_tx_skipped:pass
    used_large_inputs:fail
    used_one_address_many_times:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the recovery scan rules."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRRecoveryScanUsedBegin BRRecoveryScanUsedMarkAddress BRRecoveryScanUsedMarkRawTx BRRecoveryScanUsedHighest \
          BRRecoveryScanRoundStep BRRecoveryScanAnyUnreachable BRRecoveryScanAllUnreachable BRRecoveryScanMayCache \
          BRRecoveryScanSweepSet BRRecoveryPassphraseVerdict BRRecoverableValueExists BRRecoverableValueAnswered; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+RECOVERY_SCAN_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of RECOVERY_SCAN_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+RECOVERY_SCAN_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+RECOVERY_SCAN_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

FAIL=0

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

# The core units, compiled once per arm-independent flag set (they do not read the seam).
objs() {   # <dir> <extra flags...>: compile UNITS into <dir>, print nothing; fails on a build error
    local dir="$1"; shift
    mkdir -p "$dir"
    local u
    for u in "${UNITS[@]}"; do
        "$CC" -w -std=c99 -include stdint.h "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
            -c "$u" -o "$dir/$(basename "$(dirname "$u")")_$(basename "${u%.c}").o" || return 1
    done
}

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
want='"BRAddress.h" "BRDerivationProfiles.h" "BRRecoveryGapWindow.h" "BRTransaction.h" <stddef.h> <stdint.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRRecoveryScan.h"
int strict_tu1(void);
int strict_tu1(void) { return BRRecoveryPassphraseVerdict(1, 0, 0, 1) == BR_RECOVERY_PASSPHRASE_FOUND; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRRecoveryScan.h"
#include "BRRecoveryScan.h"
int strict_tu1(void);
int main(void) { BRRecoveryScanUsed u; BRRecoveryScanAddress a[1] = {{ "", 0, 0 }}; BRRecoveryScanSlot s[1];
    return (BRRecoveryScanUsedBegin(&u, a, 1, s, 1) == BR_RECOVERY_SCAN_OK && BRRecoveryScanUsedHighest(&u, 0) == -1 &&
            strict_tu1()) ? 0 : 1; }
EOF
strict_ok=1
if ! objs "$BUILD_DIR/strict_objs" -O0; then
    echo "PORTABILITY GATE FAILED: the core units did not compile"; FAIL=1; strict_ok=0
else
    for std in c99 c11 c17; do
        if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
                "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
           ! "$BUILD_DIR/strict_$std"; then
            echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
        fi
    done
fi
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + core headers, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note)"

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$VEC_DIR/generate.c" \
        "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
else
    "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from this header; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'
        FAIL=1
    else
        echo "vectors gate: vectors/recovery-scan/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRRecoveryScan.h"
#include "BRRecoveryScan.h"
int recovery_scan_kat_tu2(int64_t a);
int recovery_scan_kat_tu2(int64_t a) { return BRRecoveryPassphraseVerdict(a, 0, 0, 0); }
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
build() {   # <out> <unfixed 0|1>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DRECOVERY_SCAN_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/recovery_scan_kat_main.c" "$BUILD_DIR/tu2.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
}

build "$BUILD_DIR/red" 1 || { echo "GATE FAILURE: comparison arm did not compile (a build error is not evidence)."; exit 1; }
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

echo "--- COMPARISON ARM (-DRECOVERY_SCAN_UNFIXED=1: unreachable read as empty, incomplete first, coins only, native swept on own seed) ---"
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

echo "--- SHIPPED ARM (-DRECOVERY_SCAN_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
    fi
done

[ $FAIL -eq 0 ] && echo "PASS: recovery_scan_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean under ASan + UBSan; portability; vectors current)"
[ $FAIL -ne 0 ] && echo "FAIL: recovery_scan_kat"
exit $FAIL
