#!/usr/bin/env bash
# Host KAT runner for BRAssetDecode.h: the DigiAssets carrier decoder (the carrier and its payload, the
# header, the transfer instructions, the exact re-read, the metadata CID, and the bit reader under them).
# Moved from Kotlin core/.../asset/DigiAssetDecoder.kt, BitReader.kt and AssetPayloadCheck.kt; their tests
# (DigiAssetDecoderTest, the fixed cases of DigiAssetDecoderFuzzTest, BitReaderTest,
# AssetArithmeticNeverWrapsTest, AssetTransferAllocatorTest's re-read cases) run here value for value,
# with the scripts of the asset_*_kat suites beside this one. See asset_decode_kat_main.c.
#
#   comparison  one arm per defect, -DASSET_DECODE_UNFIXED=<bit> (BRAssetDecode.h): 1 an amount past
#               INT64_MAX is read, wrapped; 2 a tagged carrier that cannot be classified is not an asset;
#               4 an OP_PUSHDATA2/4 carrier is read; 8 a transfer's stream is read as leniently as an
#               issuance's; 16 output 31 is a burn in every operation; 32 the re-read does not ask where
#               the reading ended; 64 the re-read accepts bytes after the push; 128 an amount is read from
#               the next byte boundary; 256 a hash of 32 zero bytes gets a CID; 512 the re-read answers
#               for an issuance with rules or before version 3. Each arm fails exactly the cases CASES
#               lists for its bit and passes every other.
#   shipped     -DASSET_DECODE_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The header is the whole unit under test: nothing of the core is
# linked. The arms run with detect_leaks=0; then every case runs once more on the shipped arm under
# LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   VECTORS GATE      the core's vectors/asset-decode/vectors.txt is what vectors/asset-decode/generate.c
#                     writes from this header: the shared vectors a platform's own reader is held to are
#                     current.
#   PORTABILITY GATE  the header includes only libc and the two core headers whose types it uses, reads no
#                     clock, does no I/O and allocates nothing, and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the second);
#                     for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_DECODE_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRAssetDecode.h"
VEC_DIR="$CORE_DIR/vectors/asset-decode"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of ASSET_DECODE_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    decoder_transfer_v3:-
    decoder_issuance_locked:-
    decoder_burn_v3:-
    decoder_issuance_dispersed:-
    decoder_transfer_skip:-
    decoder_issuance_rules:256
    decoder_non_da:-
    decoder_mainnet_vectors:16
    decoder_tag_only_unclassifiable:2
    decoder_pushdata_framing:2,4
    decoder_burn_marker:16
    fuzz_not_asset:-
    fuzz_unclassifiable:2
    fuzz_truncated_transfer:2,8
    bits_read:-
    bits_fixed_precision:-
    bits_fixed_precision_unaligned:128
    bits_pow10:-
    bits_short_reads:-
    never_wraps_fixed_precision:1
    never_wraps_carriers:1,2,8
    exact_allocator_vectors:-
    exact_refuses_trailing:64
    exact_truncated_transfer:2,8
    exact_hold_covers:16
    exact_unbacked:32
    exact_rules_and_pre_v3:512
    exact_header_mismatch:-
    kat_burn_marker:16
    kat_classified:-
    kat_unclassifiable:2,4,8
    issuance_flags:2
    issuance_lenient_stream:32
    list_capacity:2,8
    carrier_offsets:2
    carrier_direct_75:-
    carrier_push_one_past:2
    opcode_issuance_range:2
    reread_sha1_skip:-
    reread_header_not_asset:2
    test_scripts_decoded:512
    arguments:-
    model_sweep:1,2,4,8,16,32,64,128,256,512
)
ARMS=(1:amount-wraps 2:fail-open 4:pushdata-read 8:lenient-transfer 16:burn-any-op 32:exact-end-unchecked
      64:exact-push-loose 128:amount-byte-aligned 256:zero-hash-cid 512:exact-rules-read)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the asset decoder moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRAssetBitReaderMake BRAssetBitsRemaining BRAssetBitsRead BRAssetBitsSkip BRAssetBitsReadBytes BRAssetPow10 \
          BRAssetBitsReadFixedPrecision BRAssetDecodeCarrier BRAssetHeaderHasRules BRAssetDecodeHeader \
          BRAssetDecodeInstructions BRAssetPayloadReadsExactly BRAssetMetadataCid; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?ASSET_DECODE_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of ASSET_DECODE_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_DECODE_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_DECODE_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+ASSET_DECODE_RED_[A-Z0-9_]+[[:space:]]+${arm%%:*}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER names no ASSET_DECODE_RED_* seam of value ${arm%%:*}; that arm would be the shipped code."; exit 1
    fi
done

FAIL=0

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" "$VEC_DIR/generate.c" -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
elif ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: generate.c did not write its vectors (it exits 1 when one does not fit its buffer)"; FAIL=1
else
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from this header; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    else
        echo "vectors gate: vectors/asset-decode/vectors.txt is current ($(grep -vc '^#' "$VEC_DIR/vectors.txt") vectors)"
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
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
want='"BRAssetData.h" "BRAssetQuantity.h" <stddef.h> <stdint.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetDecode.h"
int strict_tu1(void);
int strict_tu1(void)
{
    static const uint8_t s[] = { 0x6a, 0x06, 0x44, 0x41, 0x02, 0x15, 0x00, 0x0a };
    size_t off = 0, len = 0;
    return BRAssetDecodeCarrier(s, sizeof(s), &off, &len) == BR_ASSET_SCRIPT_ASSET && off == 2 && len == 6;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetDecode.h"
#include "BRAssetDecode.h"
int strict_tu1(void);
int main(void)
{
    static const uint8_t s[] = { 0x6a, 0x2a, 0x44, 0x41, 0x03, 0x01, 0xd6, 0x6f, 0x11, 0x17, 0x23, 0x47, 0x67, 0xd6, 0x89, 0x27,
        0x00, 0xd5, 0x75, 0x51, 0x1e, 0x1e, 0xff, 0xcd, 0x64, 0x71, 0x01, 0x3d, 0xda, 0x0e, 0x83, 0x2d, 0xbb, 0x67, 0xe6, 0x29,
        0xd6, 0x74, 0x21, 0x55, 0x00, 0x21, 0x55, 0x50 };
    BRAssetHeader h, h2;
    BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS];
    char cid[BR_ASSET_CID_BUFFER];
    BRAssetBitReader r = BRAssetBitReaderMake(s, sizeof(s));
    uint64_t v = 0;
    int64_t p = 0;
    int ok = BRAssetDecodeInstructions(s, sizeof(s), &h, l, BR_ASSET_MAX_INSTRUCTIONS) == BR_ASSET_SCRIPT_ASSET &&
             BRAssetDecodeHeader(s, sizeof(s), &h2) == BR_ASSET_SCRIPT_ASSET && h2.instructionCount == h.instructionCount &&
             h.operation == DA_ISSUANCE && h.locked && ! BRAssetHeaderHasRules(&h) &&
             BRAssetPayloadReadsExactly(s, sizeof(s), &h, l, h.instructionCount) &&
             BRAssetMetadataCid(&h, cid, sizeof(cid)) == BR_ASSET_CID_LENGTH && strlen(cid) == BR_ASSET_CID_LENGTH &&
             BRAssetBitsRead(&r, 8, &v) && v == 0x6a && BRAssetBitsSkip(&r, 8) && BRAssetBitsRemaining(&r) == (sizeof(s) - 2) * 8 &&
             BRAssetPow10(18, &p) && p == 1000000000000000000LL && ! BRAssetPow10(19, &p);
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
    printf '#include "BRAssetDecode.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let script: [UInt8] = [0x6a, 0x07, 0x44, 0x41, 0x03, 0x15, 0x00, 0x20, 0x52]
var header = BRAssetHeader()
var list = [BRAssetTransferInstruction](repeating: BRAssetTransferInstruction(), count: Int(BR_ASSET_MAX_INSTRUCTIONS))
let kind: BRAssetScriptKind = BRAssetDecodeInstructions(script, script.count, &header, &list, list.count)
let headerOnly: BRAssetScriptKind = BRAssetDecodeHeader(script, script.count, &header)
let exact: Int32 = BRAssetPayloadReadsExactly(script, script.count, &header, list, header.instructionCount)
var cid = [CChar](repeating: 0, count: Int(BR_ASSET_CID_BUFFER))
let cidLength: Int = BRAssetMetadataCid(&header, &cid, cid.count)
var offset = 0, length = 0
let carrier: BRAssetScriptKind = BRAssetDecodeCarrier(script, script.count, &offset, &length)
let rules: Int32 = BRAssetHeaderHasRules(&header)
let amount: Int64 = list[0].amount
let operation: BRAssetOperation = header.operation
let aggregation: BRAssetAggregation = header.aggregation
print(kind == BR_ASSET_SCRIPT_ASSET, headerOnly == BR_ASSET_SCRIPT_UNCLASSIFIABLE, carrier == BR_ASSET_SCRIPT_NOT_ASSET, exact, cidLength,
      offset, length, rules, amount, operation == DA_TRANSFER, aggregation == BR_ASSET_AGGREGATABLE, header.totalQuantity)
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
[ $strict_ok -eq 1 ] && echo "portability gate: libc + two core headers, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetDecode.h"
#include "BRAssetDecode.h"
int asset_decode_kat_tu2(void);
int asset_decode_kat_tu2(void)
{
    static const uint8_t s[] = { 0x6a, 0x07, 0x44, 0x41, 0x03, 0x15, 0x00, 0x20, 0x52 };
    BRAssetHeader h;
    BRAssetTransferInstruction l[1];
    return BRAssetDecodeInstructions(s, sizeof(s), &h, l, 1) == BR_ASSET_SCRIPT_ASSET && h.instructionCount == 1 &&
           l[0].amount == 500 && BRAssetPayloadReadsExactly(s, sizeof(s), &h, l, 1);
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <unfixed bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_DECODE_UNFIXED="$2" -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_decode_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
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
    echo "--- COMPARISON ARM -DASSET_DECODE_UNFIXED=$bit ($name): fails exactly its cases ---"
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
                echo "  [red$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DASSET_DECODE_UNFIXED=0): every case passes, no sanitizer report ---"
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

[ $FAIL -eq 0 ] && echo "PASS: asset_decode_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; vectors current; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_decode_kat"
exit $FAIL
