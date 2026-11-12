#!/usr/bin/env bash
# Host KAT runner for BRAssetEncode.h and BRAssetSendPlan.h: the DigiAsset transfer carrier, and what an asset
# send spends and pays (the coin selector, the transfer planner, the size-aware fee, the check of each asset
# input against an indexer, the choice among the wallet's coins and its outcomes, and a typed quantity).
# Moved from Kotlin core/.../asset/DigiAssetEncoder.kt, BitWriter.kt, core/.../asset/send/*.kt,
# core/.../model/AssetQuantity.kt and the checks of AssetManager.planAssetTransfer; their tests
# (DigiAssetEncoderTest, AssetCoinSelectorTest, AssetCoinSelectorSecurityTest, AssetDustPolicyTest,
# AssetFeeEstimatorTest, AssetTransferPlanTest, AssetSendSelectionTest, AssetSendSourceAddressTest,
# AssetQuantityTest) run here value for value. See asset_send_plan_kat_main.c.
#
#   comparison  one arm per defect. e<bit>: -DASSET_ENCODE_UNFIXED=<bit> (BRAssetEncode.h): 1 a payload of
#               76..80 bytes is framed with a direct push; 2 a payload over 80 bytes is written; 4 an amount
#               past 2^54 - 1 is written as its low 54 bits; 8 trailing zeros are not taken into the exponent;
#               16 a range instruction's output index is written in 5 bits; 32 an instruction whose fields do
#               not fit them is written, masked. s<bit>: -DASSET_SEND_PLAN_UNFIXED=<bit> (BRAssetSendPlan.h):
#               1 coins are taken in the order given; 2 coins of equal size are taken last given first; 4 a
#               fee coin that is an asset coin, or given twice, is taken; 8 outpoints are told apart by the
#               case of their txid; 16 the asset-change marker is budgeted for an exact send too; 32 the fee
#               is not floored at the minimum relay fee; 64 satoshi sums wrap; 128 any plain coin pays the
#               fee; 256 a coin whose creating transaction is unread pays the fee; 512 an input is judged by
#               its stack's first entry only; 1024 an input with no answer is left out and the send goes on;
#               2048 an output the indexer has not seen is set aside for good; 4096 an asset whose rules are
#               UNKNOWN is sent; 8192 a fee above the approval is signed; 16384 DGB change at or below the
#               dust line is an output; 32768 the last instruction drawing from an input does not skip;
#               65536 a send draws from every address at once; 131072 the DGB change is the last output;
#               262144 decimals past the divisibility are dropped; 524288 zero is a quantity; 1048576 a lone
#               comma is grouping; 2097152 the text's length is not bounded; 4194304 an output confirmed
#               earlier is asked again; 8388608 an answer with entries NULL and a count is judged, and its coin
#               set aside. Each arm fails exactly the cases CASES lists for it and passes every other.
#   shipped     both macros 0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The headers are the units under test; nothing is linked. The arms run
# with detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the
# platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: each header must test its seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PLAN GATE         the transfer-rule gate cannot be bypassed: every public (non-underscore) function in
#                     BRAssetSendPlan.h that calls the planner (_BRAssetPlan, _BRAssetPlanTransfer) or answers or
#                     writes a plan (BRAssetTransferPlan, BRAssetSendOutcome) refuses on BRAssetSendGate (or
#                     BRAssetRuleAllowsSend) before it plans. Only TransferRuleState.NONE may move an asset
#                     (CLAUDE.md); the gate is shown to fail on a public planner and on a choice that skips it.
#   VECTORS GATE      the core's vectors/asset-send-plan/vectors.txt is what vectors/asset-send-plan/generate.c
#                     writes from these headers: the shared vectors a platform's own encoder, selector or
#                     planner is held to are current.
#   PORTABILITY GATE  each header includes only libc and the core headers whose types or functions it uses,
#                     reads no clock, does no I/O and allocates nothing, and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the headers included twice in the second);
#                     for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and said
#                     so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_ENCODE_UNFIXED and -DASSET_SEND_PLAN_UNFIXED, each a set of bits (one bit
# per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
ENCODE="$CORE_DIR/BRAssetEncode.h"
PLAN="$CORE_DIR/BRAssetSendPlan.h"
VEC_DIR="$CORE_DIR/vectors/asset-send-plan"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms that must fail it, comma-separated; - for none>. e<bit> is a bit of
# ASSET_ENCODE_UNFIXED, s<bit> a bit of ASSET_SEND_PLAN_UNFIXED.
CASES=(
    enc_bitwriter:-
    enc_sffc:-
    enc_fixtures:e8,e16
    enc_validation:e32
    enc_push_framing:e1,e2
    sel_basic:s16
    sel_order:s1
    sel_insufficient:-
    sec_preconditions:-
    sec_empty_lists:-
    sec_overflow:-
    sec_largest_first:s1
    sec_conservation:-
    sec_distinct:s4,s8
    dust_marker:-
    fee_default:-
    fee_linear:-
    fee_more:-
    fee_floor:s32
    fee_covers:-
    plan_exact:s131072
    plan_exact_no_fee_input:-
    plan_dust_remainder:-
    plan_partial:s131072
    plan_consolidating:-
    plan_approval:s8192
    choose_judge:s512
    choose_confirmed_planned:-
    choose_overflow_rows:e8,s65536
    choose_overflow_transfer:-
    choose_set_aside:-
    choose_unconfirmed:-
    choose_no_answer:s1024
    choose_spent_set_aside:-
    choose_spans:s65536
    choose_one_address:s65536
    choose_fee_coins_data:-
    choose_fee_rule_note:s128
    choose_not_asked_again:s4194304
    choose_not_yet_asked_later:s2048
    src_unassigned_stay:s131072
    src_red_previous_layout:-
    src_whole_holding:-
    src_quantity_above_entry:-
    src_first_entry_other:-
    src_fee_coin_elsewhere:s128,s256
    src_fee_coin_at_source:-
    src_last_output:s131072
    src_two_addresses:-
    src_groups:s65536
    qty_div0:-
    qty_div2:-
    qty_div8:-
    qty_separators:s262144,s1048576
    qty_refused_forms:-
    qty_zero:s524288
    qty_decimals:s262144
    qty_empty:-
    qty_range:-
    qty_length:s2097152
    qty_whitespace:-
    qty_divisibility:-
    qty_format:-
    qty_locale:-
    qty_roundtrip:-
    enc_rules:e4,e8,e16,e32
    gate_rules:s4096
    choose_prechecks:-
    choose_step:s8,s4194304
    choose_repeated_outpoint:-
    choose_malformed_answer:s2048,s8388608
    select_order:s1,s2,s4,s8
    plan_rules:e1,s16384,s32768
    overflow_rules:s1,s64
    parse_rules:s262144,s1048576
    format_rules:-
    arguments:-
    capacity:s65536
    zero_structs:-
    constants:s1048576
    model_sweep:e1,e2,e4,e8,e16,e32,s1,s2,s4,s8,s16,s32,s64,s128,s256,s512,s1024,s2048,s4096,s16384,s32768,s65536,s131072,s262144,s524288,s1048576,s4194304
)
ARMS=(e1:single-push e2:no-length-cap e4:amount-truncates e8:no-exponent e16:range-narrow e32:fields-unchecked
      s1:input-order s2:ties-reversed s4:pools-shared s8:txid-case s16:change-marker-always s32:no-relay-floor
      s64:sums-wrap s128:fee-coin-any s256:parent-unknown-pays s512:judge-front-only s1024:no-answer-goes-on
      s2048:not-yet-kept s4096:rules-unknown-send s8192:approval-loose s16384:dust-change s32768:no-skip
      s65536:across-addresses s131072:change-last s262144:parse-rounds s524288:parse-zero s1048576:parse-comma-groups
      s2097152:parse-unbounded s4194304:confirmed-asked s8388608:null-entries-judged)

macro_of() {   # <e|s> -> the seam macro
    case "$1" in e) echo ASSET_ENCODE ;; s) echo ASSET_SEND_PLAN ;; esac
}

# ---- the seams are real --------------------------------------------------------------------
for h in "$ENCODE" "$PLAN"; do
    if [ ! -f "$h" ]; then
        echo "GATE FAILURE: $h not found: a core pin from before the asset send plan moved into the core."; exit 1
    fi
done
HDR_E="$(cat "$ENCODE")"
HDR_S="$(cat "$PLAN")"
for fn in BRAssetBitWriterMake BRAssetBitsWrite BRAssetBitsWriteByte BRAssetBitsWriteBytes BRAssetBitsWriteFixedPrecision \
          BRAssetFixedPrecisionLength BRAssetTransferInstructionValid BRAssetEncodeTransferPayload BRAssetEncodeTransferScript \
          BRAssetEncodeSimpleTransfer; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_E"; then
        echo "GATE FAILURE: $ENCODE does not define $fn."; exit 1
    fi
done
for fn in BRAssetFeeEstimate BRAssetCoinSelect BRAssetOutpointsDistinct BRAssetFeeWithinApproval BRAssetFeeCoinEligible \
          _BRAssetPlanTransfer BRAssetPlanInputsDistinct BRAssetSourceGroups BRAssetLargestSingleSource BRAssetSourceGroupHas \
          BRAssetInputJudge BRAssetSendWorkLength BRAssetSendGate BRAssetSendChoose BRAssetAmountParse BRAssetAmountFormat; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_S"; then
        echo "GATE FAILURE: $PLAN does not define $fn."; exit 1
    fi
done
for pair in "ASSET_ENCODE:$ENCODE" "ASSET_SEND_PLAN:$PLAN"; do
    m="${pair%%:*}"; h="${pair#*:}"; text="$(cat "$h")"
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?${m}_UNFIXED" <<<"$text"; then
        echo "GATE FAILURE: $h does not test the value of ${m}_UNFIXED; the arms would not differ."; exit 1
    fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+${m}_UNFIXED" <<<"$text" ||
       grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+${m}_UNFIXED" <<<"$text"; then
        echo "GATE FAILURE: $h selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
    fi
done
for arm in "${ARMS[@]}"; do   # every arm's bit is one its header reads
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"; m="$(macro_of "$kind")"
    if [ "$kind" = e ]; then text="$HDR_E"; else text="$HDR_S"; fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+${m}_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$text"; then
        echo "GATE FAILURE: no ${m}_RED_* seam of value $bit; arm $tag would be the shipped code."; exit 1
    fi
done

FAIL=0

# ---- PLAN GATE -----------------------------------------------------------------------------
# One line per function the header defines: <name> <underscore 0|1> <yields a plan 0|1> <offset of the first
# planner call, 0 none> <offset of the first `if (! <rule gate>(`, 0 none>; or `UNPARSED <text>` for a definition
# whose `{` and `}` are not alone at the start of their lines, which this gate cannot read (a failure).
cat > "$BUILD_DIR/plan_gate.awk" <<'AWK'
function report(   s, p, pre, name, ret, params, q, yields, plannerAt, gateAt) {
    s = sig
    sub(/^[[:space:]]*static[[:space:]]+inline[[:space:]]+/, "", s)
    p = index(s, "(")
    if (p == 0) { print "UNPARSED " substr(s, 1, 60); return }
    pre = substr(s, 1, p - 1); params = substr(s, p)
    sub(/[[:space:]]+$/, "", pre)
    name = pre; sub(/^.*[^A-Za-z0-9_]/, "", name)
    ret = substr(pre, 1, length(pre) - length(name))
    yields = (ret ~ /BRAsset(TransferPlan|SendOutcome)/) ? 1 : 0
    q = params; gsub(/const[[:space:]]+BRAsset(TransferPlan|SendOutcome)/, "", q)
    if (q ~ /BRAsset(TransferPlan|SendOutcome)[[:space:]]*\*/) yields = 1
    plannerAt = match(body, /(^|[^A-Za-z0-9_])_BRAssetPlan(Transfer)?[[:space:]]*\(/)
    gateAt = match(body, /if[[:space:]]*\([[:space:]]*![[:space:]]*(BRAssetSendGate|BRAssetRuleAllowsSend)[[:space:]]*\(/)
    print name, (substr(name, 1, 1) == "_") ? 1 : 0, yields, plannerAt, gateAt
}
state == 0 && /^[[:space:]]*static[[:space:]]+inline[[:space:]]/ { state = 1; sig = ""; body = "" }
state == 1 {
    if ($0 ~ /^\{[[:space:]]*$/) { state = 2; next }
    if ($0 ~ /[{}]/) { print "UNPARSED " $0; state = 0; next }
    sig = sig " " $0; next
}
state == 2 { if ($0 ~ /^\}/) { report(); state = 0; next } body = body " " $0; next }
AWK
plan_gate() {   # <header> -> prints each public function that plans or yields a plan without the gate first
    local h="$1" rows name under yields at gate
    strip_comments "$h" > "$BUILD_DIR/plan_gate.c"
    rows="$(awk -f "$BUILD_DIR/plan_gate.awk" "$BUILD_DIR/plan_gate.c")"
    while read -r name under yields at gate; do
        [ -n "$name" ] || continue
        if [ "$name" = UNPARSED ]; then echo "unparsed definition: $under $yields $at $gate"; continue; fi
        [ "$under" = 1 ] && continue
        [ "$yields" = 1 ] || [ "$at" != 0 ] || continue
        if [ "$gate" = 0 ] || { [ "$at" != 0 ] && [ "$gate" -gt "$at" ]; }; then echo "$name"; fi
    done <<<"$rows"
}
plan_public() {   # <header> -> the public functions that plan or yield a plan
    local h="$1" rows name under yields at gate
    strip_comments "$h" > "$BUILD_DIR/plan_gate.c"
    rows="$(awk -f "$BUILD_DIR/plan_gate.awk" "$BUILD_DIR/plan_gate.c")"
    while read -r name under yields at gate; do
        [ "$name" != UNPARSED ] && [ "$under" = 0 ] && { [ "$yields" = 1 ] || [ "$at" != 0 ]; } && echo "$name"
    done <<<"$rows"
}
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
plan_bad="$(plan_gate "$PLAN")"
plan_pub="$(plan_public "$PLAN" | tr '\n' ' ')"
# The gate is real: it must name a public planner, and a choice whose gate is gone.
sed -e 's/static inline BRAssetTransferPlan _BRAssetPlanTransfer(/static inline BRAssetTransferPlan BRAssetPlanTransfer(/' "$PLAN" > "$BUILD_DIR/plan_mut1.h"
sed -e '/if (! BRAssetSendGate(ruleState, &refusal)) return/d' "$PLAN" > "$BUILD_DIR/plan_mut2.h"
mut1="$(plan_gate "$BUILD_DIR/plan_mut1.h" | tr '\n' ' ')"
mut2="$(plan_gate "$BUILD_DIR/plan_mut2.h" | tr '\n' ' ')"
if [ -n "$plan_bad" ]; then
    echo "PLAN GATE FAILED: a public function in BRAssetSendPlan.h reaches a plan without the transfer-rule gate first:"
    echo "$plan_bad" | sed 's/^/      /'; FAIL=1
elif [ "$plan_pub" != "BRAssetSendChoose " ]; then
    echo "PLAN GATE FAILED: the public ways to a plan are '$plan_pub', not BRAssetSendChoose alone"; FAIL=1
elif [ "$mut1" != "BRAssetPlanTransfer " ] || [ "$mut2" != "BRAssetSendChoose " ]; then
    echo "PLAN GATE FAILED: the gate cannot see a bypass (public planner: '$mut1'; choice without its gate: '$mut2')"; FAIL=1
else
    echo "plan gate: the only public way to a plan is BRAssetSendChoose, and it asks the rule gate first (a public planner and a choice without its gate are both caught)"
fi

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" "$VEC_DIR/generate.c" -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
elif ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: generate.c did not write its vectors (it exits 1 when one does not fit its buffer)"; FAIL=1
else
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from these headers; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    else
        echo "vectors gate: vectors/asset-send-plan/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- PORTABILITY GATE ----------------------------------------------------------------------
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
for pair in "$ENCODE|\"BRAssetDecode.h\" <stddef.h> <stdint.h> <string.h> " \
            "$PLAN|\"BRAssetEncode.h\" \"BRAssetHoldings.h\" \"BRAssetRuleGate.h\" \"BRDustPolicy.h\" <stddef.h> <stdint.h> <string.h> "; do
    h="${pair%%|*}"; want="${pair#*|}"
    CODE="$(strip_comments "$h")"
    includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
    if [ "$includes" != "$want" ]; then
        echo "PORTABILITY GATE FAILED: $(basename "$h") includes $includes(want $want)"; FAIL=1
    fi
    if grep -Eq "$IO_RE" <<<"$CODE"; then
        echo "PORTABILITY GATE FAILED: $(basename "$h") calls a clock, I/O or the allocator:"
        grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
    fi
done
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetSendPlan.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetRow a[1], d[1];
    size_t ao[1], dor[1];
    BRAssetTransferPlan p;

    memset(a, 0, sizeof(a));
    memset(d, 0, sizeof(d));
    a[0].txid = "a"; a[0].satoshis = 6000; a[0].assetQuantity = 100;
    d[0].txid = "d"; d[0].satoshis = 1000000;
    p = _BRAssetPlanTransfer(a, 1, d, 1, 100, 100000, ao, 1, dor, 1);
    return p.status == BR_ASSET_PLAN_READY && p.outputCount == 4 && p.paidFeeSats == p.estimatedFeeSats && p.estimatedFeeSats == 58200;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetEncode.h"
#include "BRAssetEncode.h"
#include "BRAssetSendPlan.h"
#include "BRAssetSendPlan.h"
int strict_tu1(void);
int main(void)
{
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
    size_t n = 0, groupCount = 0;
    int64_t units = 0, fee = 0;
    char text[32];
    BRAssetUnits e[1] = { { "A", 5, 0 } };
    BRAssetRow r[1];
    BRAssetSourceGroup g[1];
    BRAssetSendKind refusal;
    int ok;

    memset(r, 0, sizeof(r));
    r[0].txid = "a"; r[0].assetQuantity = 5;
    ok = BRAssetEncodeSimpleTransfer(3, 0, 500, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && n == 9 &&
         BRAssetFeeEstimate(1, 1, 3, 9, 100000, &fee) && fee == 58200 &&
         BRAssetInputJudge(BR_ASSET_STACK_FOUND, e, 1, "A", 5) == BR_ASSET_INPUT_VERIFIED &&
         BRAssetSourceGroups(r, 1, 5, g, 1, &groupCount) && groupCount == 1 && BRAssetLargestSingleSource(r, 1) == 5 &&
         ! BRAssetSendGate(BR_SWEEP_RULE_UNKNOWN, &refusal) && refusal == BR_ASSET_SEND_RULES_UNKNOWN &&
         BRAssetAmountParse("1,5", 3, 2, &units) && units == 150 && BRAssetAmountFormat(units, 2, text, sizeof(text)) == 3 &&
         BR_ASSET_MARKER_SATS == 6000 && BR_ASSET_CHANGE_DUST_SATS == 5460 && BR_ASSET_PLAN_MAX_OUTPUTS == 4;
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
    printf '#include "BRAssetEncode.h"\n#include "BRAssetSendPlan.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var script = [UInt8](repeating: 0, count: Int(BR_ASSET_ENCODE_MAX_SCRIPT))
var written = 0
var why = BR_ASSET_ENCODE_WHY_NONE
let encoded: BRAssetEncodeStatus = BRAssetEncodeSimpleTransfer(3, 0, 500, &script, script.count, &written, &why)
var list = [BRAssetTransferInstruction](repeating: BRAssetTransferInstruction(), count: 1)
list[0].amount = 5
let valid: Int32 = BRAssetTransferInstructionValid(&list[0])
var fee: Int64 = 0
let estimated: Int32 = BRAssetFeeEstimate(1, 1, 3, 9, Int64(BR_ASSET_MIN_RELAY_FEE_PER_KB), &fee)
let txid = "a"
let outcome: (BRAssetTransferPlan, BRAssetSendOutcome, BRAssetSelection) = txid.withCString { t in
    var assets = [BRAssetRow](repeating: BRAssetRow(), count: 1)
    assets[0].txid = t
    assets[0].satoshis = 6000
    assets[0].assetQuantity = 100
    var plain = [BRAssetRow](repeating: BRAssetRow(), count: 1)
    plain[0].txid = t
    plain[0].vout = 1
    plain[0].satoshis = 1_000_000
    var assetOrder = [Int](repeating: 0, count: 1)
    var dgbOrder = [Int](repeating: 0, count: 1)
    let s = BRAssetCoinSelect(&assets, 1, &plain, 1, 100, 500, 12_000, 0, &assetOrder, 1, &dgbOrder, 1)
    var parents = [BR_ASSET_FACT_NO]
    var marks = [UInt8](repeating: 0, count: 1)
    var work = [Int](repeating: 0, count: BRAssetSendWorkLength(1, 1))
    let o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, "A", &assets, 1, &plain, &parents, 1, 100, 100_000, nil, 0, nil, 0, nil, 0,
                              &marks, marks.count, &work, work.count)
    return (o.plan, o, s)
}
let plan: BRAssetTransferPlan = outcome.0
let firstRole: BRAssetOutputRole = plan.outputs.0.role
let carrierFirst: UInt8 = plan.opReturnScript.0
let kind: BRAssetSendKind = outcome.1.kind
let selected: BRAssetSelectStatus = outcome.2.status
var units: Int64 = 0
let parsed: Int32 = BRAssetAmountParse("1,5", 3, 2, &units)
var text = [CChar](repeating: 0, count: 32)
let formatted: Int = BRAssetAmountFormat(units, 2, &text, text.count)
var refusal = BR_ASSET_SEND_UNVERIFIED
let gate: Int32 = BRAssetSendGate(BR_SWEEP_RULE_BOUND, &refusal)
let approved: Int32 = BRAssetFeeWithinApproval(1, 2)
var entries = [BRAssetUnits(assetId: nil, count: 5, ruleFree: 0)]
let verdict: BRAssetInputVerdict = BRAssetInputJudge(BR_ASSET_STACK_FOUND, &entries, 1, "A", 5)
let limits: (Int32, Int32, Int64, Int32, Int32, Int32) = (BR_ASSET_MARKER_SATS, BR_ASSET_CHANGE_DUST_SATS,
                                                          BR_ASSET_FIXED_PRECISION_MAX, BR_ASSET_PLAN_MAX_OUTPUTS,
                                                          BR_ASSET_PLAN_MAX_INSTRUCTIONS, BR_ASSET_AMOUNT_MAX_TEXT)
print(encoded == BR_ASSET_ENCODE_OK, valid, estimated, fee, plan.status == BR_ASSET_PLAN_READY, firstRole == BR_ASSET_ROLE_RECIPIENT_MARKER,
      carrierFirst, kind == BR_ASSET_SEND_LOOKUP, selected == BR_ASSET_SELECT_OK, parsed, units, formatted, gate,
      refusal == BR_ASSET_SEND_RULE_BOUND, approved, verdict == BR_ASSET_INPUT_MISMATCH, limits, BR_ASSET_SEND_MARK_VERIFIED,
      BR_ASSET_PLAN_WHY_FEE_RATE, BR_ASSET_ENCODE_MAX_PAYLOAD, BR_ASSET_OPCODE_TRANSFER)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    # BRCrypto.h includes crypto/odocrypt.h, so the crypto headers come too.
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports them"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the headers"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + the core headers each uses, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetSendPlan.h"
#include "BRAssetSendPlan.h"
#include "BRAssetEncode.h"
#include "BRAssetEncode.h"
int asset_send_plan_kat_tu2(void);
int asset_send_plan_kat_tu2(void)
{
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
    size_t n = 0;
    int64_t u = 0;

    return BRAssetEncodeSimpleTransfer(3, 0, 500, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && n == 9 &&
           BRAssetAmountParse("1,5", 3, 2, &u) && u == 150 && BRAssetFeeWithinApproval(1, 1);
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <ASSET_ENCODE_UNFIXED bits> <ASSET_SEND_PLAN_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_ENCODE_UNFIXED="$2" -DASSET_SEND_PLAN_UNFIXED="$3" -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_send_plan_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
}

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"
    if [ "$kind" = e ]; then e=$bit; s=0; else e=0; s=$bit; fi
    build "$BUILD_DIR/red_$tag" "$e" "$s" || { echo "GATE FAILURE: comparison arm $tag did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; name="${arm#*:}"; kind="${tag:0:1}"; bit="${tag:1}"
    echo "--- COMPARISON ARM -D$(macro_of "$kind")_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$tag,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$tag" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then
            echo "  [$tag $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" > "$BUILD_DIR/san.txt"; sed -n '1,3p' "$BUILD_DIR/san.txt"
            FAIL=1; continue
        fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [$tag $c] failed, as it must: ${first:6:150}"
            else
                echo "  [$tag $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$tag $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$tag] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (both macros 0): every case passes, no sanitizer report ---"
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
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" > "$BUILD_DIR/leak.txt"; sed -n '1,6p' "$BUILD_DIR/leak.txt" | sed 's/^/      /'
            FAIL=1; leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: asset_send_plan_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; vectors current; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_send_plan_kat"
exit $FAIL
