#!/usr/bin/env bash
# Host KAT runner for BRAssetHold.h: which of the wallet's outputs are held out of every plain DGB spend
# because they may carry DigiAsset units -- BRAssetHoldCombine (the decoder's and the classifier's
# verdicts as one), BRAssetHoldOutputs (the pre-spend pass and the replay over a wallet's transactions and
# rows), BRAssetHoldBounds, and BRWalletHoldAssetOutputs (BRWallet.c: the same over the wallet's own
# transactions, holding what it finds). Moved from Kotlin core/.../asset/AssetManager.kt
# (holdAssetOutputsBeforeSpendImpl, the TargetingWalk, resolveInputAssetUnits as those call it,
# replayAssetOutpointExclusions, resolveRowTargetsImpl, replayAnswerForAbsentTransaction,
# provenInputUnits); the assertions of AssetPreSpendPassTest, the hold assertions of
# AssetHoldCoversCreditedOutputsTest asked of the pass, and asset_implicit_change_kat's wallet checks run
# here value for value, beside a line-for-line C model of the Kotlin that the pass is compared with over
# fixed pseudo-random wallets. See asset_hold_kat_main.c.
#
#   comparison  one arm per rule, -DASSET_HOLD_UNFIXED=<bit> (BRAssetHold.h): 1 a verdict is the
#               classifier's alone; 2 a verdict is the decoder's alone; 4 a verdict neither reader can
#               give is "carries nothing"; 8 a funding transaction's remainder is never walked back; 16
#               the rows are not replayed; 32 a row whose transaction the wallet does not hold is held;
#               64 an output that is not the wallet's own is held like one that is; 128 a remainder
#               proof is not read; 256 a transaction's carrier is its first carrier output; 512 an input
#               the pass cannot read is passed over; 1024 a walk records totals taken before everything
#               under them was resolved; 2048 a held list the buffer cannot hold is shortened; 4096 a pass
#               that cannot complete answers "complete, nothing held"; 8192 only the decoder's reading is
#               made; 16384 the other reading too reads a funding transaction by its first OP_RETURN;
#               32768 two rows for one outpoint that differ are read, one of them winning. Each arm fails
#               exactly the cases CASES lists for its bit and passes every other.
#   shipped     -DASSET_HOLD_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The unit under test is the header, and BRWallet.c, which includes
# it, built once per arm; the rest of the core is built once, without the seam. The arms run with
# detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the platform
# has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default, and
# define every function this suite calls. Then:
#   PORTABILITY GATE  the header includes only libc and the core headers whose types or functions it uses,
#                     reads no clock, does no I/O and allocates nothing, and builds clean under -Wall
#                     -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the
#                     second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_HOLD_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRAssetHold.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of ASSET_HOLD_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    combine_exhaustive:1,2,4
    prespend_vector:-
    prespend_targeted_held:1,64
    prespend_untargeted_spendable:1,64
    prespend_not_ours:1,64
    prespend_plain_opreturn_first:1
    prespend_unclassifiable_beside_readable:-
    prespend_unclassifiable_every_owned:64
    prespend_plain_pushdata2:-
    prespend_two_carriers:256
    prespend_refusals:4096
    prespend_amount_unused:1,64
    prespend_unreadable_input:1,64,512
    prespend_later_pass:1,64
    prespend_arrived:1
    covers_issuance:1
    covers_transfer:1
    covers_output_31:-
    covers_every_credited_output:1
    walk_long_run:1,8,64
    walk_zero_rows:1,8,64
    walk_two_below:8,64
    walk_unreadable_funding:1,64
    walk_pass_record:1,8,16,64,128
    walk_record_units:1,4,8,64,128,256,8192,16384
    walk_cycle_proven:1,8,64,128,1024
    walk_proof:1,64,128
    replay_rows:1,16,32,64
    replay_unreadable_input:1,64,512
    union_output:1,2,16,64
    union_funding_output:1,2,8192
    union_funding_tx:1,2,8192
    union_walk:1,2,8,16,64,8192
    union_proof:1,16,64
    union_agree:1
    union_two_carriers:1,4,256,16384
    rows_disagree:1,16,64,32768
    refusal_arguments:-
    refusal_unreadable:4096
    capacity:1,16,64,2048,4096
    edges:1,16,64
    implicit_change_wallet:1,16,64,4096
    wallet_agree:-
    sweep_decoder:1,8,16,32,64,128,256,512
    sweep_agree:1,4,8,16,32,64,128
    sweep_union:1,2,16,256,512
    sweep_must_be_excluded:1
    second_unit:-
)
ARMS=(1:decoder-dropped 2:classifier-dropped 4:unknown-free 8:walk-stops 16:replay-skipped 32:absent-held
      64:ownership-ignored 128:proof-ignored 256:first-carrier 512:input-dropped 1024:settled-ungated
      2048:capacity-shortens 4096:refusal-empty 8192:facts-decoder-only 16384:shape-first-only 32768:duplicate-rows)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the pre-spend pass moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRAssetHoldCombine BRAssetHoldOutputs BRAssetHoldBounds; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^BRAssetHoldResult BRWalletHoldAssetOutputs\(' <<<"$HDR" ||
   ! grep -Eq '^BRAssetHoldResult BRWalletHoldAssetOutputs\(' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: BRWalletHoldAssetOutputs is not declared in $HEADER and defined in BRWallet.c."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?ASSET_HOLD_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of ASSET_HOLD_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_HOLD_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_HOLD_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+ASSET_HOLD_RED_[A-Z0-9_]+[[:space:]]+${arm%%:*}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER names no ASSET_HOLD_RED_* seam of value ${arm%%:*}; that arm would be the shipped code."; exit 1
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
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
WANT='"BRAssetAllocate.h" "BRAssetData.h" "BRAssetDecode.h" "BRAssetQuantity.h" "BRDigiAsset.h" "BRInt.h" "BRTransaction.h" "BRWallet.h" <stddef.h> <stdint.h> <string.h> '
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
if [ "$includes" != "$WANT" ]; then
    echo "PORTABILITY GATE FAILED: BRAssetHold.h includes $includes(want $WANT)"; FAIL=1
fi
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: BRAssetHold.h calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetHold.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetHoldView v;
    BRAssetHoldWork w[1];
    BRUTXO held[1];
    BRAssetHoldResult r;

    memset(&v, 0, sizeof(v));
    r = BRAssetHoldOutputs(&v, w, 1, held, 1);
    return r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0 &&
           BRAssetHoldCombine(BR_ASSET_FACT_NO, BR_ASSET_FACT_YES) == BR_ASSET_FACT_YES;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetHold.h"
#include "BRAssetHold.h"
int strict_tu1(void);
static int owned(void *info, const BRTransaction *tx, size_t vout) { (void)info; (void)tx; (void)vout; return 1; }
int main(void)
{
    static uint8_t script[] = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a }, ours[] = { 0x00, 0x14 };
    BRTransaction tx;
    BRTxOutput outs[3];
    BRTxInput ins[1];
    const BRTransaction *txs[1];
    BRAssetHoldView v;
    BRAssetHoldWork w[1];
    BRUTXO held[4];
    BRAssetHoldResult r;
    size_t workNeeded = 0, heldNeeded = 0;

    memset(&tx, 0, sizeof(tx));
    memset(outs, 0, sizeof(outs));
    memset(ins, 0, sizeof(ins));
    outs[0].script = ours; outs[0].scriptLen = sizeof(ours);
    outs[1].script = script; outs[1].scriptLen = sizeof(script);
    outs[2].script = ours; outs[2].scriptLen = sizeof(ours);
    tx.outputs = outs; tx.outCount = 3;
    tx.inputs = ins; tx.inCount = 1;
    txs[0] = &tx;
    memset(&v, 0, sizeof(v));
    v.txs = txs; v.txCount = 1; v.owned = owned;
    r = BRAssetHoldOutputs(&v, w, 1, held, 4);
    return (r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 2 && BRAssetHoldBounds(&v, &workNeeded, &heldNeeded) &&
            workNeeded == 1 && heldNeeded == 3 && strict_tu1()) ? 0 : 1;
}
EOF
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
# The core without BRWallet.c, which includes the header: built once, with the sanitizers, for every arm.
CORE_SRCS=("$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")
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
"$CC" -w -std=c99 -include stdint.h "${INC[@]}" -c "$CORE_DIR/BRWallet.c" -o "$BUILD_DIR/plain/wallet.o" ||
    { echo "GATE FAILURE: BRWallet.c did not compile."; exit 1; }
PLAIN_OBJS+=("$BUILD_DIR/plain/wallet.o")

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
    printf '#include "BRAssetHold.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let union: BRAssetFact = BRAssetHoldCombine(BR_ASSET_FACT_NO, BR_ASSET_FACT_YES)
var view = BRAssetHoldView()
view.owned = { (_: UnsafeMutableRawPointer?, _: UnsafePointer<BRTransaction>?, _: Int) -> Int32 in 1 }
var row = BRAssetHoldRow()
row.n = 2
row.quantity = 0
var classifier = BRAssetHoldClassifier()
classifier.outputIsAsset = { (_: UnsafeMutableRawPointer?, _: UnsafePointer<BRTransaction>?, _: Int) -> Int32 in 0 }
var work = [BRAssetHoldWork](repeating: BRAssetHoldWork(), count: 4)
var held = [BRUTXO](repeating: BRUTXO(), count: 8)
let r: BRAssetHoldResult = BRAssetHoldOutputs(&view, &work, work.count, &held, held.count)
let status: BRAssetHoldStatus = r.status
var workNeeded = 0, heldNeeded = 0
let bounded: Int32 = BRAssetHoldBounds(&view, &workNeeded, &heldNeeded)
let walletPass: (OpaquePointer?, UnsafePointer<BRAssetHoldRow>?, Int, UnsafePointer<UInt256>?, Int,
                 UnsafeMutablePointer<BRAssetHoldWork>?, Int, UnsafeMutablePointer<BRUTXO>?, Int) -> BRAssetHoldResult = BRWalletHoldAssetOutputs
print(union == BR_ASSET_FACT_YES, status == BR_ASSET_HOLD_COMPLETE, r.heldCount, r.newlyHeld, r.workNeeded, r.heldNeeded, bounded,
      BR_ASSET_HOLD_REFUSED_CAPACITY, BR_ASSET_HOLD_REFUSED_UNREADABLE, BR_ASSET_HOLD_REFUSED_ARGUMENTS, BR_ASSET_HOLD_REFUSED,
      row.n, classifier.containsAsset == nil, walletPass)
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
#include "BRAssetHold.h"
#include "BRAssetHold.h"
int asset_hold_kat_tu2(void);
int asset_hold_kat_tu2(void)
{
    BRAssetHoldView v;
    BRAssetHoldWork w[1];

    memset(&v, 0, sizeof(v));
    size_t work = 1, held = 1;

    return BRAssetHoldOutputs(&v, w, 1, NULL, 0).status == BR_ASSET_HOLD_COMPLETE &&
           BRAssetHoldBounds(&v, &work, &held) == 1 && work == 0 && held == 0;
}
EOF

build() {   # <out> <ASSET_HOLD_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_HOLD_UNFIXED="$2" "${INC[@]}" \
        "$SCRIPT_DIR/asset_hold_kat_main.c" "$BUILD_DIR/tu2.c" "$CORE_DIR/BRWallet.c" "${CORE_OBJS[@]}" -lm -lpthread -o "$1"
}

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"
    build "$BUILD_DIR/red_$bit" "$bit" || { echo "GATE FAILURE: comparison arm $bit did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -DASSET_HOLD_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$bit" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$bit $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | head -4 | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DASSET_HOLD_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | head -8 | cut -c1-200 | sed 's/^/      /'; FAIL=1
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

[ $FAIL -eq 0 ] && echo "PASS: asset_hold_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_hold_kat"
exit $FAIL
