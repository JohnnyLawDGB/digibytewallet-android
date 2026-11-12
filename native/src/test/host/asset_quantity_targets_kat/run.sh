#!/usr/bin/env bash
# Host KAT runner for the part of BRAssetQuantity.h that says which outputs a transaction may put asset
# units on: BRAssetTargetsOutput, BRAssetDestroys, BRAssetMayVoid, BRAssetIssuanceMayCreditLastOutput,
# BRAssetAssignedUnits and BRAssetConsumptionUncountable. Moved from Kotlin core/.../asset/AssetTxQuantity.kt
# (targetsOutput, destroys, mayVoid, issuanceMayCreditLastOutput, assignedUnits, consumptionUncountable);
# the Kotlin tests' assertions on them (AssetHoldCoversCreditedOutputsTest, AssetInputTargetingTest, ForeignAssetTransferPlanTest,
# AssetImplicitChangeTest, AssetArithmeticNeverWrapsTest, DigiAssetDecoderTest, AssetInputUnitsTest,
# UnbackedAssetCreditTest) run here value for value, beside a line-for-line C model of the Kotlin (with
# AssetTransferAllocator under it) that the header is compared with over fixed pseudo-random inputs.
# See asset_quantity_targets_kat_main.c.
#
#   comparison  one arm per rule, -DASSET_TARGETS_UNFIXED=<bit> (BRAssetQuantity.h): 1 a range instruction
#               names only its end output; 2 non-range output 31 destroys in every operation; 4 an
#               instruction to an output the transaction lacks does not void; 8 an instruction of zero
#               units does not void; 16 a void is counted when no units came in; 32 a remainder that is
#               not known is not a target; 64 instructions that assign more than came in leave nothing;
#               128 what a range draws is multiplied without a check; 256 a percent instruction is counted
#               as assigning nothing; 512 a carrier that is not classified targets no output; 1024 an
#               issuance's remainder is not counted; 2048 an issuance with rules, or before version 3, is
#               allocated; 4096 an issuance's instruction to a missing output does not void; 8192 an
#               issuance's first non-OP_RETURN output is not a target. Each arm fails exactly the cases
#               CASES lists for its bit and passes every other.
#   shipped     -DASSET_TARGETS_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The header is the whole unit under test: nothing of the core is
# linked. The arms run with detect_leaks=0; then every case runs once more on the shipped arm under
# LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc and the one core header whose types it uses, reads no
#                     clock, does no I/O and allocates nothing, and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the second);
#                     for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_TARGETS_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRAssetQuantity.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of ASSET_TARGETS_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    hold_issuance_remainder:1024
    hold_issuance_no_instruction:1024
    hold_issuance_missing_output:-
    hold_issuance_over_assigned:-
    hold_issuance_not_read_exactly:2048
    hold_issuance_whole_supply:-
    hold_transfer_missing_output:4
    hold_zero_amount_after_inputs_used:8
    hold_range_past_outputs:-
    hold_void_transfer_no_units:16
    hold_burn_marker:-
    hold_every_credited_output:1,4,8,32,64,1024,4096
    targeting_named_outputs:1
    targeting_last_output_beside_percent:32,256
    targeting_issuance_change:-
    targeting_unnamed_middle_output:-
    targeting_remainder:32
    targeting_settled_totals:32
    targeting_recovery_layout:-
    never_wraps_unclassifiable:512
    never_wraps_range_consumption:32
    never_wraps_remainder_bounded:32,64
    never_wraps_negative_amount:32
    never_wraps_sum:-
    never_wraps_wrapping_range:128
    decoder_unclassifiable:512
    input_units_last_output:32
    implicit_change_leftover:-
    implicit_change_fully_assigned:-
    implicit_change_range:-
    implicit_change_burn_flag_in_transfer:-
    implicit_change_percent:32,256
    implicit_change_unknown_inputs:32
    implicit_change_over_assigned:64
    implicit_change_issuance:-
    implicit_change_burn:-
    implicit_change_vout:-
    uncountable_bound:-
    destroys_rule:2
    may_void_rule:2,4,8
    assigned_units_rule:256
    uncountable_rule:-
    issuance_not_decided:2048
    issuance_total_range:1024,2048
    issuance_instructions:128,1024,4096
    issuance_skip:1024
    targets_named:1,2
    targets_issuance:1024,2048,8192
    targets_last_output:16,32,64,256,512
    edges:1024
    arguments:1,2,4,16,256,512,1024,2048,8192
    sweep_destroys_may_void:2,4,8
    sweep_assigned:128,256
    sweep_issuance:128,1024,2048,4096
    sweep_targets:1,2,4,8,16,32,64,128,256,512,1024,2048,4096,8192
)
ARMS=(1:range-end-only 2:destroy-any-op 4:missing-output-kept 8:zero-amount-kept 16:void-without-units
      32:unknown-not-held 64:overdrawn-nothing 128:range-wraps 256:percent-counted 512:unclassifiable-open
      1024:issuance-no-remainder 2048:issuance-rules-read 4096:issuance-no-void 8192:issuer-output-open)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRAssetDestroys BRAssetMayVoid BRAssetAssignedUnits BRAssetConsumptionUncountable \
          BRAssetIssuanceMayCreditLastOutput BRAssetTargetsOutput; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn: a core pin from before these functions moved into the core."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?ASSET_TARGETS_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of ASSET_TARGETS_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_TARGETS_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_TARGETS_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+ASSET_TARGETS_RED_[A-Z0-9_]+[[:space:]]+${arm%%:*}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER names no ASSET_TARGETS_RED_* seam of value ${arm%%:*}; that arm would be the shipped code."; exit 1
    fi
done

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
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
want='"BRAssetData.h" <stddef.h> <stdint.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetQuantity.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetTransferInstruction l[1] = { { 0 } };
    l[0].outputIndex = 31; l[0].amount = 25;
    return BRAssetDestroys(DA_BURN, &l[0]) && ! BRAssetDestroys(DA_TRANSFER, &l[0]) && BRAssetMayVoid(DA_TRANSFER, 3, l, 1) &&
           ! BRAssetMayVoid(DA_BURN, 3, l, 1);
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetQuantity.h"
#include "BRAssetQuantity.h"
int strict_tu1(void);
int main(void)
{
    BRAssetTransferInstruction l[2] = { { 0 }, { 0 } };
    int64_t assigned = -1;
    int ok;
    l[0].outputIndex = 0; l[0].amount = 5;
    l[1].outputIndex = 1; l[1].amount = 3; l[1].range = 1;
    ok = BRAssetAssignedUnits(DA_TRANSFER, l, 2, &assigned) && assigned == 11 &&
         ! BRAssetConsumptionUncountable(DA_TRANSFER, l, 2) &&
         BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 12, 3, l, 2) && ! BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 11, 3, l, 2) &&
         BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 1, 1, 0, 1, 11, 3, l, 2) &&
         ! BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, 1, 11, 3, l, 2) &&
         BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, 1, 12, 3, l, 2);
    return (ok && strict_tu1()) ? 0 : 1;
}
EOF
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
            "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
    fi
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRAssetQuantity.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var instruction = BRAssetTransferInstruction()
instruction.outputIndex = 0
instruction.amount = 10
let list = [instruction]
var assigned: Int64 = 0
let destroys: Int32 = BRAssetDestroys(DA_BURN, list)
let mayVoid: Int32 = BRAssetMayVoid(DA_TRANSFER, 3, list, list.count)
let counted: Int32 = BRAssetAssignedUnits(DA_TRANSFER, list, list.count, &assigned)
let uncountable: Int32 = BRAssetConsumptionUncountable(DA_TRANSFER, list, list.count)
let issuance: Int32 = BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 10, 3, list, list.count)
let targets: Int32 = BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, 1, 100, 3, list, list.count)
let operation: BRAssetOperation = DA_ISSUANCE
print(destroys, mayVoid, counted, assigned, uncountable, issuance, targets, operation == DA_ISSUANCE)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    mkdir -p "$BUILD_DIR/swift_inc"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + one core header, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetQuantity.h"
#include "BRAssetQuantity.h"
int asset_quantity_targets_kat_tu2(void);
int asset_quantity_targets_kat_tu2(void)
{
    BRAssetTransferInstruction l[1] = { { 0 } };
    int64_t assigned = -1;
    l[0].outputIndex = 0; l[0].amount = 10;
    return BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 3, 1, 0, 1, 100, 4, l, 1) == 1 &&
           BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 3, 1, 0, 1, 10, 4, l, 1) == 0 &&
           BRAssetAssignedUnits(DA_TRANSFER, l, 1, &assigned) == 1 && assigned == 10;
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <unfixed bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_TARGETS_UNFIXED="$2" -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_quantity_targets_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
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
    echo "--- COMPARISON ARM -DASSET_TARGETS_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$bit" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [red$bit $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [red$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [red$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the rule"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DASSET_TARGETS_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    fi
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
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

[ $FAIL -eq 0 ] && echo "PASS: asset_quantity_targets_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_quantity_targets_kat"
exit $FAIL
