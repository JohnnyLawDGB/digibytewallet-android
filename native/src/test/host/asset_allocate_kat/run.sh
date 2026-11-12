#!/usr/bin/env bash
# Host KAT runner for BRAssetAllocate.h and BRAssetId.h: where the units of a DigiAsset transaction land
# (transfers, burns, issuances), what one output may be credited, the most an output can be delivered,
# what a transaction's inputs carried, one input's stack, an indexer's answer, and the id of the asset an
# issuance creates. Moved from Kotlin core/.../asset/AssetTransferAllocator.kt, AssetCredit.kt and the
# input rules of AssetManager.kt, and from the bridge's deriveIssuanceAssetId (jni_asset.c); their tests
# (AssetTransferAllocatorTest, AssetHoldCoversCreditedOutputsTest's allocations, UnbackedAssetCreditTest,
# AssetInputUnitsTest, AssetArithmeticNeverWrapsTest's input total) run here value for value, with ids
# computed outside this repository. See asset_allocate_kat_main.c.
#
#   comparison  one arm per defect. a<bit>: -DASSET_ALLOCATE_UNFIXED=<bit> (BRAssetAllocate.h): 1 an invalid
#               instruction leaves what earlier ones delivered; 2 what the instructions leave goes to no
#               output; 4 before version 3 the instructions are always read; 8 output 31 destroys in every
#               operation; 16 every asset is drawn across entries; 32 a sum or product past INT64_MAX wraps;
#               64 an input whose contents are unknown is read as given; 128 a payload not read exactly is
#               allocated; 256 an undecided allocation credits every output nothing; 512 a percent
#               instruction is read as a count; 1024 an asset that may carry transfer rules is allocated;
#               2048 the last output is bounded by the instructions naming it; 4096 a row storing zero
#               carries nothing, targeted or not; 8192 an input without a row carries nothing; 16384 an
#               issuance of any amount is allocated; 32768 a row's quantity is believed whatever its credit
#               state; 65536 a skip moves on even when the stack was just used up; 131072 an input with no
#               counted entry keeps its place in the draw order. i<bit>: -DASSET_ID_UNFIXED=<bit>
#               (BRAssetId.h): 1 an unlocked issuance is given the locked id; 2 a txid shorter than 64 bytes
#               is hashed; 4 the divisibility is left out; 8 every aggregation gets the aggregatable prefix;
#               16 the output index is always 0. Each arm fails exactly the cases CASES lists for it and
#               passes every other.
#   shipped     both macros 0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The headers are the units under test; the core's hashes and base58
# (BRCrypto.c, BRBase58.c and the hash units they call) are linked for BRAssetId.h, built once, without
# the seams. The arms run with detect_leaks=0; then every case runs once more on the shipped arm under
# LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: each header must test its seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   VECTORS GATE      the core's vectors/asset-allocate/vectors.txt is what vectors/asset-allocate/generate.c
#                     writes from these headers: the shared vectors a platform's own allocator is held to
#                     are current.
#   PORTABILITY GATE  each header includes only libc and the core headers whose types or functions it
#                     uses, reads no clock, does no I/O and allocates nothing, and builds clean under -Wall
#                     -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the headers included twice in the
#                     second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_ALLOCATE_UNFIXED and -DASSET_ID_UNFIXED, each a set of bits (one bit
# per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
ALLOCATE="$CORE_DIR/BRAssetAllocate.h"
ID="$CORE_DIR/BRAssetId.h"
VEC_DIR="$CORE_DIR/vectors/asset-allocate"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms that must fail it, comma-separated; - for none>. a<bit> is a bit of
# ASSET_ALLOCATE_UNFIXED, i<bit> a bit of ASSET_ID_UNFIXED.
CASES=(
    allocator_over_assignment:a1,a2
    allocator_no_asset_input:-
    allocator_million_from_one:a2
    allocator_honest_control:a2
    allocator_second_input_asset:a65536
    allocator_skip_past_carrier:a65536
    allocator_plain_first_input:a2,a131072
    allocator_mixed_input:a1,a2
    allocator_missing_output:a1,a2
    allocator_range:a2
    allocator_output_31:a1,a2,a8
    allocator_skip_after_emptied:a65536
    allocator_legacy:a2,a4
    allocator_fails_closed:a64,a512,a1024
    allocator_draw_across_entries:a2,a16
    allocator_issuance:a2,a128
    allocator_reads_exactly:-
    allocator_issuance_not_here:a32
    allocator_reference_model:a1,a2,a65536,a131072
    hold_issuance:a1,a2
    hold_transfer:a1,a2
    credit_unbacked_claim_unknown_inputs:a64,a256,a8192
    credit_unbacked_claim_known_inputs:a1,a2
    credit_voided_transfer:a1,a2
    credit_honest_control:a2
    credit_second_input_asset:a65536
    credit_mixed_input:-
    credit_indexer:-
    credit_issuance:a2,a128,a256
    credit_issuance_not_exact:a128,a256
    credit_issuance_core_rejects:a2,a16384
    credit_bounds:a32,a2048
    credit_unnamed_output:a64,a8192
    units_rows_and_plain_inputs:a8192
    units_zero_rows:a4096
    units_zero_rows_why:a4096,a8192
    never_wraps_input_total:a32
    backed_stack_rules:a4096,a8192,a32768
    credit_states:-
    aggregation_of_id:-
    allocate_rules:a1,a2,a64,a128,a1024,a131072
    allocate_draws:a1,a2,a16,a65536,a131072
    allocate_burns_and_legacy:a1,a2,a4,a131072
    allocate_overflow:a1,a2,a32
    issuance_rules:a1,a2,a8,a128,a512,a16384
    credit_rules:a2,a64,a256,a512
    bound_rules:a32,a2048
    resolve_rules:a32,a4096,a8192
    id_mainnet:i16
    id_vectors:i4,i8,i16
    id_refusals:i1,i2
    id_capacity:i1,i16
    arguments:a2,a32,a64,a256,a2048,a4096
    capacity:a2
    zero_structs:a256,a8192
    model_sweep:a1,a2,a4,a8,a16,a32,a64,a128,a256,a512,a1024,a2048,a4096,a8192,a16384,a32768,a65536,a131072,i1,i2,i4,i8,i16
)
ARMS=(a1:void-keeps a2:no-leftover a4:legacy-reads a8:burn-any-op a16:any-draws a32:sums-wrap
      a64:unknown-input-read a128:inexact-read a256:undecided-empty a512:percent-read a1024:rules-ignored
      a2048:last-bounded a4096:zero-row-counts a8192:no-row-empty a16384:issuance-unbounded
      a32768:unchecked-counts a65536:skip-always a131072:empty-input-placed
      i1:unlocked-derived i2:short-txid i4:divisibility-zero i8:one-prefix i16:vout-zero)

macro_of() {   # <a|i> -> the seam macro
    case "$1" in a) echo ASSET_ALLOCATE ;; i) echo ASSET_ID ;; esac
}

# ---- the seams are real --------------------------------------------------------------------
for h in "$ALLOCATE" "$ID"; do
    if [ ! -f "$h" ]; then
        echo "GATE FAILURE: $h not found: a core pin from before the asset allocator moved into the core."; exit 1
    fi
done
HDR_A="$(cat "$ALLOCATE")"
HDR_I="$(cat "$ID")"
for fn in BRAssetCreditCounts BRAssetCreditIsSettled BRAssetAggregationOfId BRAssetAllocate BRAssetAllocateIssuance \
          BRAssetCreditForOutput BRAssetMaxDeliverable BRAssetResolveInputUnits BRAssetBackedInputStack \
          BRAssetCreditFromIndexer; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_A"; then
        echo "GATE FAILURE: $ALLOCATE does not define $fn."; exit 1
    fi
done
if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?BRAssetIdForIssuance\(" <<<"$HDR_I"; then
    echo "GATE FAILURE: $ID does not define BRAssetIdForIssuance."; exit 1
fi
for pair in "ASSET_ALLOCATE:$ALLOCATE" "ASSET_ID:$ID"; do
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
    if [ "$kind" = a ]; then text="$HDR_A"; else text="$HDR_I"; fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+${m}_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$text"; then
        echo "GATE FAILURE: no ${m}_RED_* seam of value $bit; arm $tag would be the shipped code."; exit 1
    fi
done

FAIL=0

# The core's hashes and base58, for BRAssetId.h: built once, without the seams, and linked by every build.
shopt -s nullglob
CRYPTO_SRCS=("$CORE_DIR/BRBase58.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR"/crypto/*.c "$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
mkdir -p "$BUILD_DIR/crypto"
CRYPTO_OBJS=()
n=0
for src in "${CRYPTO_SRCS[@]}"; do
    n=$((n + 1)); obj="$BUILD_DIR/crypto/$n.o"
    "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" -c "$src" -o "$obj" ||
        { echo "GATE FAILURE: $src did not compile (a build error is not evidence)."; exit 1; }
    CRYPTO_OBJS+=("$obj")
done

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" "$VEC_DIR/generate.c" "${CRYPTO_OBJS[@]}" -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
elif ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: generate.c did not write its vectors (it exits 1 when one does not fit its buffer)"; FAIL=1
else
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from these headers; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    else
        echo "vectors gate: vectors/asset-allocate/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
    fi
fi

# ---- PORTABILITY GATE ----------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
for pair in "$ALLOCATE|\"BRAssetData.h\" \"BRAssetDecode.h\" \"BRAssetQuantity.h\" <stddef.h> <stdint.h> <string.h> " \
            "$ID|\"BRAssetDecode.h\" \"BRBase58.h\" \"BRCrypto.h\" <stddef.h> <stdint.h> "; do
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
#include "BRAssetAllocate.h"
#include "BRAssetId.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetHeader h;
    BRAssetTransferInstruction l[1];
    BRAssetUnits u = { "Laxx", 9, 1 };
    BRAssetInputStack s = { 1, &u, 1 };
    BRAssetOutputUnits out[2];
    int64_t work[1];
    BRAssetAllocation r;

    memset(&h, 0, sizeof(h));
    memset(l, 0, sizeof(l));
    h.version = 3;
    h.operation = DA_TRANSFER;
    l[0].amount = 5;
    r = BRAssetAllocate(&h, l, 1, 1, &s, 1, 3, work, 1, out, 2);
    return r.status == BR_ASSET_ALLOCATION_ALLOCATED && r.count == 2 && out[0].vout == 0 && out[0].count == 5 &&
           out[1].vout == 2 && out[1].count == 4;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetAllocate.h"
#include "BRAssetAllocate.h"
#include "BRAssetId.h"
#include "BRAssetId.h"
int strict_tu1(void);
int main(void)
{
    BRAssetHeader h;
    BRAssetTransferInstruction l[1];
    BRAssetOutputUnits out[3];
    BRAssetUnits units[1] = { { "Laxx", 7, 0 } }, slot;
    BRAssetInputFacts f = { 1, BR_ASSET_CREDIT_VERIFIED, 7, "Laxx", BR_ASSET_FACT_UNKNOWN, BR_ASSET_FACT_UNKNOWN, BR_ASSET_FACT_UNKNOWN };
    BRAssetOutputCredit credit;
    BRAssetAggregation aggregation = BR_ASSET_DISPERSED;
    char id[BR_ASSET_ID_BUFFER];
    size_t rows[1];
    BRAssetAllocation r;
    BRAssetDeliverable d;
    BRAssetInputUnits in;
    BRAssetInputStack s;
    int ok;

    memset(&h, 0, sizeof(h));
    memset(l, 0, sizeof(l));
    h.operation = DA_ISSUANCE;
    h.hasTotalQuantity = 1;
    h.totalQuantity = 10;
    l[0].amount = 4;
    r = BRAssetAllocateIssuance(&h, l, 1, "Laxx", 3, 1, out, 3);
    credit = BRAssetCreditForOutput(r, out, l, 1, 0, 3);
    d = BRAssetMaxDeliverable(&h, l, 1, 0, 3);
    in = BRAssetResolveInputUnits(&f, 1, rows, 1);
    s = BRAssetBackedInputStack(&f, &slot);
    ok = r.status == BR_ASSET_ALLOCATION_ALLOCATED && credit.kind == BR_ASSET_OUTPUT_HOLDS && credit.count == 4 &&
         ! d.unbounded && d.max == 4 && in.known && in.units == 7 && s.known && s.unitCount == 1 &&
         BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, units, 1, &credit) && credit.count == 7 &&
         BRAssetCreditCounts(BR_ASSET_CREDIT_BACKED) && BRAssetCreditIsSettled(BR_ASSET_CREDIT_MIXED) &&
         BRAssetAggregationOfId("Lhxx", &aggregation) && aggregation == BR_ASSET_HYBRID &&
         BRAssetIdForIssuance("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b728825178", 1, 1, BR_ASSET_AGGREGATABLE, 0,
                              id, sizeof(id)) && strcmp(id, "La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo") == 0 &&
         BR_ASSET_MAX_ISSUANCE == 18014398509481983LL && BR_ASSET_BURN_OUTPUT == 31 && BR_ASSET_ID_LENGTH == 38;
    return (ok && strict_tu1()) ? 0 : 1;
}
EOF
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
            "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "${CRYPTO_OBJS[@]}" -o "$BUILD_DIR/strict_$std" ||
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
    printf '#include "BRAssetAllocate.h"\n#include "BRAssetId.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var header = BRAssetHeader()
header.operation = DA_TRANSFER
header.version = 3
var list = [BRAssetTransferInstruction](repeating: BRAssetTransferInstruction(), count: 1)
list[0].amount = 5
let assetId = "Laxx"
let result: (BRAssetAllocation, [BRAssetOutputUnits], BRAssetOutputCredit) = assetId.withCString { id in
    var units = [BRAssetUnits(assetId: id, count: 9, ruleFree: 1)]
    return units.withUnsafeMutableBufferPointer { u in
        var stacks = [BRAssetInputStack(known: 1, units: UnsafePointer(u.baseAddress), unitCount: 1)]
        var work = [Int64](repeating: 0, count: 1)
        var out = [BRAssetOutputUnits](repeating: BRAssetOutputUnits(), count: 4)
        let r = BRAssetAllocate(&header, list, 1, 1, &stacks, 1, 3, &work, work.count, &out, out.count)
        let c = BRAssetCreditForOutput(r, out, list, 1, 0, 3)
        return (r, out, c)
    }
}
let allocation: BRAssetAllocation = result.0
let status: BRAssetAllocationStatus = allocation.status
let why: BRAssetAllocationWhy = allocation.why
let creditKind: BRAssetOutputCreditKind = result.2.kind
var issueOut = [BRAssetOutputUnits](repeating: BRAssetOutputUnits(), count: 2)
let issued: BRAssetAllocation = BRAssetAllocateIssuance(&header, list, 1, "Laxx", 3, 1, &issueOut, issueOut.count)
let bound: BRAssetDeliverable = BRAssetMaxDeliverable(&header, list, 1, 0, 3)
var facts = [BRAssetInputFacts(hasRow: 1, credit: BR_ASSET_CREDIT_VERIFIED, quantity: 7, assetId: nil,
                               rowIsTargeted: BR_ASSET_FACT_UNKNOWN, fundingIsAssetTx: BR_ASSET_FACT_NO,
                               fundingHasDataOutput: BR_ASSET_FACT_YES)]
var rows = [Int](repeating: 0, count: 1)
let total: BRAssetInputUnits = BRAssetResolveInputUnits(&facts, 1, &rows, rows.count)
var slot = BRAssetUnits()
let stack: BRAssetInputStack = BRAssetBackedInputStack(&facts, &slot)
var indexed = BRAssetOutputCredit()
let said: Int32 = BRAssetCreditFromIndexer(BR_ASSET_STACK_NOT_UNSPENT, nil, 0, &indexed)
var aggregation = BR_ASSET_AGGREGATABLE
let says: Int32 = BRAssetAggregationOfId("Lhxx", &aggregation)
var id = [CChar](repeating: 0, count: Int(BR_ASSET_ID_BUFFER))
let derived: Int32 = BRAssetIdForIssuance("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b728825178", 1, 1,
                                          BR_ASSET_AGGREGATABLE, 0, &id, id.count)
let limits: (Int64, Int32, Int32) = (BR_ASSET_MAX_ISSUANCE, BR_ASSET_BURN_OUTPUT, BR_ASSET_ID_LENGTH)
print(status == BR_ASSET_ALLOCATION_ALLOCATED, why == BR_ASSET_WHY_NONE, creditKind == BR_ASSET_OUTPUT_HOLDS, issued.count,
      bound.max, total.known, stack.known, said, says, derived, limits, BRAssetCreditCounts(BR_ASSET_CREDIT_BACKED),
      BRAssetCreditIsSettled(BR_ASSET_CREDIT_MIXED), BR_ASSET_STACK_FOUND, BR_ASSET_WHY_OVERFLOW)
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
#include "BRAssetAllocate.h"
#include "BRAssetAllocate.h"
#include "BRAssetId.h"
#include "BRAssetId.h"
int asset_allocate_kat_tu2(void);
int asset_allocate_kat_tu2(void)
{
    BRAssetInputFacts f;
    BRAssetUnits slot;
    char id[BR_ASSET_ID_BUFFER];

    memset(&f, 0, sizeof(f));
    f.hasRow = 1;
    f.credit = BR_ASSET_CREDIT_BACKED;
    f.quantity = 3;
    f.assetId = "Laxx";
    return BRAssetBackedInputStack(&f, &slot).unitCount == 1 && slot.count == 3 &&
           BRAssetIdForIssuance("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b728825178", 0, 1, BR_ASSET_AGGREGATABLE, 0,
                                id, sizeof(id)) && strcmp(id, "La47gBb6ieY4pk4rJSjv36eQMfz7NsXoo8JVbB") == 0;
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <ASSET_ALLOCATE_UNFIXED bits> <ASSET_ID_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_ALLOCATE_UNFIXED="$2" -DASSET_ID_UNFIXED="$3" -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_allocate_kat_main.c" "$BUILD_DIR/tu2.c" "${CRYPTO_OBJS[@]}" -o "$1"
}

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"
    if [ "$kind" = a ]; then a=$bit; i=0; else a=0; i=$bit; fi
    build "$BUILD_DIR/red_$tag" "$a" "$i" || { echo "GATE FAILURE: comparison arm $tag did not compile (a build error is not evidence)."; exit 1; }
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
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$tag $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
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
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: asset_allocate_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; vectors current; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_allocate_kat"
exit $FAIL
