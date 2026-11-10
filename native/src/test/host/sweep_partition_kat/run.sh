#!/usr/bin/env bash
# Host KAT runner for BRSweepPartition.h: which of a recovered seed's outputs a Recover-funds sweep may
# spend as plain DGB (the asset verdict, one fetch per transaction, the split, the hold-everything
# predicate, sweep exclusions, assets left behind). See sweep_partition_kat_main.c.
#
#   comparison  one arm per defect, -DSWEEP_PARTITION_UNFIXED=<bit> (BRSweepPartition.h): 1 an outpoint
#               that could not be classified read as plain; 2 an asset-bearing outpoint swept; 4 an absent
#               or unreadable raw transaction read as plain; 8 a failed move's plan inputs released to the
#               sweep; 16 every asset-bearing outpoint called left behind; 32 a failed or missing rule
#               lookup read as NONE; 64 the hold-everything predicate counting assets only; 128 the quoted
#               total counting held coins; 256 fetched bytes classified without the exact-id check. Each arm fails exactly the cases CASES lists for its bit and
#               passes every other.
#   shipped     -DSWEEP_PARTITION_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit, linked against the core's transaction and DigiAsset code (the header
# classifies raw transactions with BRRawTxBinding.h's exact parse and BRTXContainsAsset). The arms run with
# detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the
# platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock and does no I/O,
#                     and builds clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs,
#                     the header included twice in the second); for iphoneos/iphonesimulator too where
#                     an Xcode SDK is present (skipped, and said so, where none is). Not C++: it
#                     includes BRTransaction.h, hence BRInt.h, which is not valid C++ (push-down recipe).
#   VECTORS GATE      the core's vectors/sweep-partition/vectors.txt is what vectors/sweep-partition/generate.c
#                     writes from this header.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DSWEEP_PARTITION_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRSweepPartition.h"
VEC_DIR="$CORE_DIR/vectors/sweep-partition"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of SWEEP_PARTITION_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    sp_asset_never_swept:2
    sp_no_assets_sweeps_all:-
    sp_only_assets_sweeps_nothing:2
    sp_unclassifiable_held:1
    sp_classification_fails_entirely:1
    sp_every_utxo_one_bucket:-
    sp_sweepable_total:2,128
    sp_empty_wallet:-
    sp_arguments:-
    cl_plain_tx_plain:-
    cl_asset_tx_asset:-
    cl_every_output_held_change_included:-
    cl_unfetchable_unknown:4
    cl_empty_body_unknown:4
    cl_unparseable_unknown:4
    cl_one_unreachable_not_strand:4
    cl_parent_fetched_once:-
    cl_empty_wallet:-
    cl_rule_state_carried:-
    cl_resolver_throws_unknown:32
    cl_no_resolver_unknown:32
    cl_core_reader:4
    cl_wrong_tx_served:4,256
    cl_not_exact_bytes:4,256
    cl_groups_arguments:-
    ios_asset_holds:2
    ios_unknown_holds:1,64
    ios_plain_does_not_hold:-
    rs_spent_withheld:-
    rs_broadcast_unavailable:-
    rs_failed_move_holds_plan:8
    rs_no_plan_holds_outpoint:-
    rs_disjoint:-
    rs_several_moves:8
    rs_no_moves:-
    rs_set_and_arguments:-
    lb_moved_not_left:16
    lb_failed_left:16
    lb_never_reported_left:16
    lb_no_assets:-
    lb_order_preserved:16
    lb_arguments:16
    fs_foreign_includes_native_own_excludes:-
    cost_n_log_n:-
)
# The comparison arms, one per defect (BRSweepPartition.h's SWEEP_PARTITION_RED_* bits).
ARMS=(1:unknown-swept 2:asset-swept 4:unread-tx-plain 8:failed-move-frees-inputs 16:left-behind-all
      32:rule-lookup-fail-open 64:unknown-not-held 128:total-counts-held 256:txid-unchecked)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the sweep partition rules."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRSweepClassifyRawTx BRSweepRuleStateRead BRSweepVerdictOf BRSweepVerdictUnknown BRSweepTxidGroups \
          BRSweepBucketOf BRSweepPartitionSplit BRSweepFoundAssetOrUnknown BRSweepOutpointIn \
          BRSweepExclusionsCapacity BRSweepExclusions BRSweepAssetsLeftBehind; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?SWEEP_PARTITION_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of SWEEP_PARTITION_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+SWEEP_PARTITION_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+SWEEP_PARTITION_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

FAIL=0

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

# The core units, compiled once per flag set (they do not read the seam).
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
want='"BRDigiAsset.h" "BRRawTxBinding.h" "BRRecoveryUtxo.h" "BRTransaction.h" <stddef.h> <stdint.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRSweepPartition.h"
int strict_tu1(void);
int strict_tu1(void) { BRSweepAssetVerdict v = BRSweepVerdictOf(BR_SWEEP_RAW_TX_PLAIN, 0, 0);
    return BRSweepBucketOf(&v) == BR_SWEEP_BUCKET_SWEEPABLE; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRSweepPartition.h"
#include "BRSweepPartition.h"
int strict_tu1(void);
int main(void) { BRSweepAssetVerdict v = BRSweepVerdictUnknown(); size_t n = 9;
    return (BRSweepClassifyRawTx(NULL, 0, NULL) == BR_SWEEP_RAW_TX_ABSENT && BRSweepFoundAssetOrUnknown(&v, 1) &&
            BRSweepExclusions(NULL, 0, NULL, 0, &n) == BR_RECOVERY_UTXO_OK && strict_tu1()) ? 0 : 1; }
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
        echo "vectors gate: vectors/sweep-partition/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRSweepPartition.h"
#include "BRSweepPartition.h"
int sweep_partition_kat_tu2(void);
int sweep_partition_kat_tu2(void) { BRSweepAssetVerdict v = BRSweepVerdictOf(BR_SWEEP_RAW_TX_ASSET, 0, 0);
    return v.classified && v.carriesAsset; }   /* reads no seam: links in every arm */
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
build() {   # <out> <unfixed bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DSWEEP_PARTITION_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/sweep_partition_kat_main.c" "$BUILD_DIR/tu2.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
}

for arm in "${ARMS[@]}"; do
    build "$BUILD_DIR/red_${arm%%:*}" "${arm%%:*}" || { echo "GATE FAILURE: comparison arm ${arm%%:*} did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -DSWEEP_PARTITION_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$bit" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [red$bit $c] GATE FAILURE: sanitizer report"; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [red$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [red$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DSWEEP_PARTITION_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
    fi
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
# The shipped arm once more under LeakSanitizer, where the platform has one. Probed, never assumed: Apple
# clang's ASan refuses detect_leaks=1 outright, and that refusal is not a leak.
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" list 2>&1)"
if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
    leak_note="every case clean"
    echo "--- LEAK CHECK: every case on the shipped arm under detect_leaks=1 ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"
        out="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: sweep_partition_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability; vectors current)"
[ $FAIL -ne 0 ] && echo "FAIL: sweep_partition_kat"
exit $FAIL
