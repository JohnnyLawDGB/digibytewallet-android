#!/usr/bin/env bash
# Host KAT runner for BRAssetDetect.h: DigiAsset detection, what the wallet writes about its own outputs of one
# transaction that carries a DigiAsset payload (BRAssetDetect, BRAssetDetectBounds, the claim, the rules answer,
# the rename after the issuance walk, the facts walk), and the two entry points BRAssetHold.h gained for it
# (BRAssetHoldCarrierOf, BRAssetHoldInputFacts). Moved from Kotlin core/.../asset/AssetManager.kt
# (processIncomingAssetTx and what it calls) and AssetTxQuantity.kt (forOutput, implicitChange,
# forOutputTotal); the assertions of AssetTxQuantityTest, AssetImplicitChangeTest, UnbackedAssetCreditTest, the
# claim and receive cases of AssetArithmeticNeverWrapsTest, AssetPersistOnDetectTest's protect cases and
# AssetZeroQuantityInputTest's detection case run here value for value, beside a line-for-line C model of
# processIncomingAssetTx that the detection is compared with over fixed pseudo-random wallets. See
# asset_detect_kat_main.c.
#
#   comparison  one arm per rule. d<bit>: -DASSET_DETECT_UNFIXED=<bit> (BRAssetDetect.h): 1 an output that is
#               not the wallet's own gets a row; 2 an OP_RETURN output gets a row; 4 with no owned scripts no
#               output gets a row; 8 a transaction whose rows are all settled is allocated again; 16 an input
#               the allocation cannot read is passed over; 32 a locked issuance is not named by its first input;
#               64 an asset whose rule state is UNKNOWN counts as rule-free; 128 an asset nothing was said of
#               counts as rule-free, unasked; 256 the indexer is asked with no owned set; 512 the indexer is
#               asked about our own unconfirmed send; 1024 the rename takes any placeholder, whatever the
#               carrier; 2048 the issuance walk is due only while a row is unresolved; 4096 the facts walk
#               names rows whose credit does not count; 8192 the claim is BRAssetQuantityForOutputTotal's;
#               16384 two rows for one outpoint that differ in credit or id are read; 32768 a carrier that
#               cannot be classified writes UNCHECKED rows. h<bit>: -DASSET_HOLD_UNFIXED=<bit> (BRAssetHold.h,
#               the pieces detection reads through it): 8 a funding transaction's remainder is never walked
#               back; 128 a remainder proof is not read; 256 a transaction's carrier is its first carrier
#               output. Each arm fails exactly the cases CASES lists for it and passes every other.
#   shipped     both macros 0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The units under test are the headers; the core sources they call
# (BRDigiAsset.c's classifier, BRCrypto.c, BRBase58.c, and what those need) are built once, without seams. The
# arms run with detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the
# platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: each header must test its seam's VALUE (#if, never #ifdef) with an #ifndef default, and define
# every function this suite calls. Then:
#   VECTORS GATE      the core's vectors/asset-detect/vectors.txt is what vectors/asset-detect/generate.c writes
#                     from these headers: the shared vectors a platform's own detection is held to are current.
#   PORTABILITY GATE  BRAssetDetect.h includes only libc and the core headers whose types or functions it uses,
#                     reads no clock, does no I/O and allocates nothing, and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the second); for
#                     iphoneos/iphonesimulator, and through Swift's C importer (swiftc -import-objc-header) on
#                     macOS, where an Xcode SDK / swiftc is present (skipped, and said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_DETECT_UNFIXED and -DASSET_HOLD_UNFIXED, each a set of bits (one bit per
# comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
DETECT="$CORE_DIR/BRAssetDetect.h"
HOLD="$CORE_DIR/BRAssetHold.h"
VEC_DIR="$CORE_DIR/vectors/asset-detect"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms that must fail it, comma-separated; - for none>. d<bit> is a bit of
# ASSET_DETECT_UNFIXED, h<bit> a bit of ASSET_HOLD_UNFIXED.
CASES=(
    claim_quantity:d8192
    claim_remainder:d8192
    claim_arithmetic:d8192
    recv_unknown_inputs:-
    recv_known_inputs:-
    recv_voided_to_last:-
    recv_honest_known:-
    recv_honest_unknown:-
    recv_second_input:-
    recv_skip:-
    recv_indexer_names:-
    recv_mixed:-
    recv_issuance:d32
    recv_offline_and_retries:d1,d8
    arith_receive:d1,d128,d32768
    protect_targets:d32768
    rows_outputs:d1,d2,d4
    rows_carrier:d32768,h256
    placeholder_rules:d32
    all_settled:d8
    rules_step:d64,d128
    inputs_read:d16,h8,h128
    outgoing_and_asks:d1,d4,d256,d512
    issuance_walk:d32,d2048,d65536
    rename_rows:d32,d1024,h256
    facts_walk:d4096
    refusals:d16384
    capacity:-
    input_facts_direct:-
    second_unit:-
    model_sweep:d1,d2,d4,d8,d16,d32,d64,d128,d256,d512,d2048,d8192,d32768,d65536,h128,h256
)
ARMS=(d1:ownership-ignored d2:opreturn-rows d4:empty-owned-none d8:settled-reallocated d16:input-dropped
      d32:placeholder-always d64:rules-unknown-free d128:rules-unasked-free d256:ask-unowned d512:ask-outgoing
      d1024:rename-any d2048:walk-once-dropped d4096:facts-uncounted d8192:claim-earlier-rule d16384:duplicate-rows
      d32768:unclassifiable-rows d65536:walk-unrenamable h8:walk-stops h128:proof-ignored h256:first-carrier)

macro_of() {   # <d|h> -> the seam macro
    case "$1" in d) echo ASSET_DETECT ;; h) echo ASSET_HOLD ;; esac
}

# ---- the seams are real --------------------------------------------------------------------
for h in "$DETECT" "$HOLD"; do
    if [ ! -f "$h" ]; then
        echo "GATE FAILURE: $h not found: a core pin from before detection moved into the core."; exit 1
    fi
done
HDR_D="$(cat "$DETECT")"
HDR_H="$(cat "$HOLD")"
for fn in BRAssetClaimForOutput BRAssetClaimRemainder BRAssetClaimForOutputTotal BRAssetDetectRuleFree \
          BRAssetDetectBounds BRAssetDetect BRAssetDetectRename BRAssetDetectFactsWalk; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_D"; then
        echo "GATE FAILURE: $DETECT does not define $fn."; exit 1
    fi
done
for fn in BRAssetHoldCarrierOf BRAssetHoldInputFacts; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_H"; then
        echo "GATE FAILURE: $HOLD does not define $fn."; exit 1
    fi
done
for pair in "ASSET_DETECT:$DETECT" "ASSET_HOLD:$HOLD"; do
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
    if [ "$kind" = d ]; then text="$HDR_D"; else text="$HDR_H"; fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+${m}_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$text"; then
        echo "GATE FAILURE: no ${m}_RED_* seam of value $bit; arm $tag would be the shipped code."; exit 1
    fi
done
# every ASSET_DETECT seam the header defines has an arm here
while read -r bit; do
    [ -n "$bit" ] || continue
    found=0
    for arm in "${ARMS[@]}"; do [ "${arm%%:*}" = "d$bit" ] && found=1; done
    if [ $found -eq 0 ]; then echo "GATE FAILURE: ASSET_DETECT seam $bit has no comparison arm here."; exit 1; fi
done <<<"$(grep -E '^#define ASSET_DETECT_RED_[A-Z0-9_]+[[:space:]]+[0-9]+' <<<"$HDR_D" | awk '{print $3}')"

FAIL=0

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
CORE_SRCS=("$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")
INC=(-I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1")
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
mkdir -p "$BUILD_DIR/core" "$BUILD_DIR/plain"
CORE_OBJS=()
PLAIN_OBJS=()
n=0
for src in "${CORE_SRCS[@]}"; do
    n=$((n + 1))
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" "${INC[@]}" -c "$src" -o "$BUILD_DIR/core/$n.o" ||
        { echo "GATE FAILURE: $src did not compile (a build error is not evidence)."; exit 1; }
    "$CC" -w -std=c99 -include stdint.h "${INC[@]}" -c "$src" -o "$BUILD_DIR/plain/$n.o" ||
        { echo "GATE FAILURE: $src did not compile (a build error is not evidence)."; exit 1; }
    CORE_OBJS+=("$BUILD_DIR/core/$n.o")
    PLAIN_OBJS+=("$BUILD_DIR/plain/$n.o")
done

strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c or vectors.txt is missing"; FAIL=1
elif ! "$CC" -w -std=c99 -include stdint.h "${INC[@]}" "$VEC_DIR/generate.c" "${PLAIN_OBJS[@]}" -lm -lpthread -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
elif ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: generate.c did not write its vectors (it exits 1 when one does not fit its buffer)"; FAIL=1
else
    if ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
        echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from these headers; regenerate it"
        diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"
        sed -n '1,12p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    else
        echo "vectors gate: vectors/asset-detect/vectors.txt is current ($(grep -c '^detect ' "$VEC_DIR/vectors.txt") detections, $(grep -c '^claim ' "$VEC_DIR/vectors.txt") claims)"
    fi
fi

# ---- PORTABILITY GATE ----------------------------------------------------------------------
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|snprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
WANT='"BRAssetAllocate.h" "BRAssetDecode.h" "BRAssetHold.h" "BRAssetHoldings.h" "BRAssetId.h" "BRAssetProvenance.h" "BRAssetQuantity.h" "BRAssetRuleGate.h" "BRInt.h" "BRTransaction.h" <stddef.h> <stdint.h> <string.h> '
CODE="$(strip_comments "$DETECT")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
if [ "$includes" != "$WANT" ]; then
    echo "PORTABILITY GATE FAILED: BRAssetDetect.h includes $includes(want $WANT)"; FAIL=1
fi
for h in "$DETECT" "$HOLD"; do
    if grep -Eq "$IO_RE" <<<"$(strip_comments "$h")"; then
        echo "PORTABILITY GATE FAILED: $(basename "$h") calls a clock, I/O or the allocator:"
        grep -nE "$IO_RE" <<<"$(strip_comments "$h")" | sed 's/^/      /'; FAIL=1
    fi
done
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetDetect.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetTransferInstruction l[1];
    int64_t r = 0;

    memset(l, 0, sizeof(l));
    l[0].amount = 10;
    return BRAssetClaimForOutputTotal(DA_TRANSFER, 0, 0, l, 1, 3, 1, 0, 1, 100, 4) == 90 &&
           BRAssetClaimRemainder(DA_TRANSFER, l, 1, 1, 100, &r) && r == 90 && ! BRAssetDetectRuleFree(NULL);
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetDetect.h"
#include "BRAssetDetect.h"
int strict_tu1(void);
int main(void)
{
    static uint8_t script[] = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x15, 0x00, 0x05 }, ours[] = { 0x51 };
    static BRAssetDetection d;
    BRTransaction tx;
    BRTxOutput outs[2];
    BRAssetScript owned[1];
    BRAssetDetectView v;
    BRAssetDetectNeeds needs;
    BRAssetDetectBuffers b;
    BRAssetHoldWork work[1];
    BRAssetDetectOutput o[2];
    size_t outputRows[2];
    BRAssetOutputUnits delivered[2];

    memset(&tx, 0, sizeof(tx));
    memset(outs, 0, sizeof(outs));
    outs[0].script = ours; outs[0].scriptLen = sizeof(ours); outs[0].amount = 600;
    outs[1].script = script; outs[1].scriptLen = sizeof(script);
    tx.outputs = outs; tx.outCount = 2;
    owned[0].bytes = ours; owned[0].len = sizeof(ours);
    memset(&v, 0, sizeof(v));
    v.tx = &tx; v.owned = owned; v.ownedCount = 1;
    memset(&b, 0, sizeof(b));
    b.work = work; b.workCap = 1; b.outputRows = outputRows; b.outputs = o; b.outputCap = 2; b.delivered = delivered; b.deliveredCap = 2;
    if (! BRAssetDetectBounds(&v, &needs) || needs.outputs != 2 || needs.delivered != 1) return 1;
    if (BRAssetDetect(&v, &b, &d) != BR_ASSET_DETECT_DONE || d.outputCount != 1 || ! o[0].targeted || o[0].claimed != 5 ||
        o[0].persist.action != BR_ASSET_PERSIST_INSERT || o[0].credit.kind != BR_ASSET_OUTPUT_UNKNOWN) return 1;
    return strict_tu1() ? 0 : 1;
}
EOF
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages "${INC[@]}" \
            "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "${PLAIN_OBJS[@]}" -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
    fi
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages "${INC[@]}" \
                -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRAssetDetect.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var view = BRAssetDetectView()
var needs = BRAssetDetectNeeds()
let bounded: Int32 = BRAssetDetectBounds(&view, &needs)
var buffers = BRAssetDetectBuffers()
var work = [BRAssetHoldWork](repeating: BRAssetHoldWork(), count: 4)
var outputs = [BRAssetDetectOutput](repeating: BRAssetDetectOutput(), count: 4)
var asks = [UnsafePointer<CChar>?](repeating: nil, count: 4)
buffers.workCap = work.count
buffers.outputCap = outputs.count
var detection = BRAssetDetection()
let status: BRAssetDetectStatus = work.withUnsafeMutableBufferPointer { w in
    outputs.withUnsafeMutableBufferPointer { o in
        asks.withUnsafeMutableBufferPointer { a in
            buffers.work = w.baseAddress
            buffers.outputs = o.baseAddress
            buffers.asks = a.baseAddress
            return BRAssetDetect(&view, &buffers, &detection)
        }
    }
}
let first: BRAssetDetectOutput = outputs[0]
let persisted: BRAssetPersistAction = first.persist.action
let credit: BRAssetOutputCreditKind = first.credit.kind
var remainder: Int64 = 0
let known: Int32 = BRAssetClaimRemainder(DA_TRANSFER, nil, 0, 1, 100, &remainder)
let claim: Int64 = BRAssetClaimForOutputTotal(DA_TRANSFER, 0, 0, nil, 0, 3, 1, 0, 1, 100, 4)
var rule = BRAssetDetectRule()
let free: Int32 = BRAssetDetectRuleFree(&rule)
var renamed = [Int](repeating: 0, count: 2)
var renamedCount = 0
let rename: BRAssetHoldingsStatus = BRAssetDetectRename(&detection, nil, nil, 0, &renamed, renamed.count, &renamedCount)
var ids = [UnsafePointer<CChar>?](repeating: nil, count: 2)
var idCount = 0
let walk: BRAssetFactsWalkDue = BRAssetDetectFactsWalk(&detection, nil, 0, 0, 0, 0, 0, &ids, ids.count, &idCount)
var header = BRAssetHeader()
var at = 0
let carrier: BRAssetCarrierStatus = BRAssetHoldCarrierOf(nil, &at, &header, nil)
let inputs: (OpaquePointer?) -> Void = { _ in }
print(bounded, status == BR_ASSET_DETECT_REFUSED_ARGUMENTS, persisted, credit, known, remainder, claim, free, rename, walk,
      carrier == BR_ASSET_CARRIER_UNREADABLE, detection.placeholder.0, detection.txid.0, BR_ASSET_PLACEHOLDER_BUFFER,
      BR_ASSET_FACTS_WALK_RETRY_MS, needs.delivered, BR_ASSET_DETECT_NEEDS_RULES, type(of: inputs))
EOF
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            -Xcc -I"$CORE_DIR/secp256k1/include" "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,16p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + the core headers it uses, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetDetect.h"
#include "BRAssetDetect.h"
int asset_detect_kat_tu2(void);
int asset_detect_kat_tu2(void)
{
    BRAssetDetectNeeds needs;
    BRAssetDetection *d = NULL;
    int64_t r = 0;

    return ! BRAssetDetectBounds(NULL, &needs) && BRAssetDetect(NULL, NULL, d) == BR_ASSET_DETECT_REFUSED_ARGUMENTS &&
           BRAssetClaimRemainder(DA_ISSUANCE, NULL, 0, 0, 0, &r) && r == 0;
}
EOF

build() {   # <out> <ASSET_DETECT_UNFIXED bits> <ASSET_HOLD_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_DETECT_UNFIXED="$2" -DASSET_HOLD_UNFIXED="$3" "${INC[@]}" \
        "$SCRIPT_DIR/asset_detect_kat_main.c" "$BUILD_DIR/tu2.c" "${CORE_OBJS[@]}" -lm -lpthread -o "$1"
}

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"
    if [ "$kind" = d ]; then dd=$bit; hh=0; else dd=0; hh=$bit; fi
    build "$BUILD_DIR/red_$tag" "$dd" "$hh" || { echo "GATE FAILURE: comparison arm $tag did not compile (a build error is not evidence)."; exit 1; }
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
            echo "  [$tag $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" > "$BUILD_DIR/f.txt"; sed -n '1,4p' "$BUILD_DIR/f.txt" | cut -c1-200 | sed 's/^/      /'; FAIL=1
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
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" > "$BUILD_DIR/f.txt"; sed -n '1,8p' "$BUILD_DIR/f.txt" | cut -c1-200 | sed 's/^/      /'; FAIL=1
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

[ $FAIL -eq 0 ] && echo "PASS: asset_detect_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; vectors current; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_detect_kat"
exit $FAIL
