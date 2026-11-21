#!/usr/bin/env bash
# Host KAT runner for BRAssetTxAmount.h: what one DigiAsset transaction moved, for its activity row and details
# (BRAssetTxActivityAmount: the units and the asset they resolve to). Moved from Kotlin v4.1.4
# core/.../asset/AssetManager.kt (assetTokenCountForTx, receivedBackedUnits, the count and id of assetAmountForTx)
# and UtxoDao.getResolvedAssetIdForTx; the expectations are hand-written from them and from the end states of
# UnbackedAssetCreditTest and AssetArithmeticNeverWrapsTest. See asset_tx_amount_kat_main.c.
#
#   comparison  one arm per rule, -DASSET_TX_AMOUNT_UNFIXED=<bit> (BRAssetTxAmount.h): 1 a receive counts a row
#               whatever its credit; 2 a receive leaves spent rows out; 4 a sum past the int64 range wraps; 8 a
#               send counts the wallet's own outputs too; 16 a send with an empty owned set counts nothing; 32 a
#               send counts an OP_RETURN output; 64 a placeholder names the asset; 128 a row whose credit does not
#               count names the asset; 256 a send reads the earlier quantity rule (a burn delivers nothing); 512 a
#               placeholder is told by its case; 1024 a receive counts what the instructions assign the wallet's
#               outputs; 2048 a row of another transaction counts and names. Each arm fails exactly the cases
#               CASES lists for its bit and passes every other.
#   shipped     -DASSET_TX_AMOUNT_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The header is the unit under test; the core's transaction code and the one
# carrier rule (BRDigiAsset.c) are linked, built once, without the seam; so is the second translation unit. Then
# every case once more on the shipped arm under LeakSanitizer where the platform has one (said so, and skipped,
# where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default. Then:
#   VECTORS GATE      the core's vectors/asset-tx-amount/vectors.txt is what generate.c writes from corpus.txt
#                     with this header; the case vectors_file holds every line of it to the hand-written answers.
#   PORTABILITY GATE  the header includes only libc and the core headers whose types or functions it uses,
#                     reads no clock, does no I/O and allocates nothing, and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the second); for
#                     iphoneos/iphonesimulator, and through Swift's C importer on macOS, where present.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_TX_AMOUNT_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arms' nonzero exits are inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRAssetTxAmount.h"
VEC_DIR="$CORE_DIR/vectors/asset-tx-amount"
export ASSET_TX_AMOUNT_CORPUS="$VEC_DIR/corpus.txt"
export ASSET_TX_AMOUNT_VECTORS="$VEC_DIR/vectors.txt"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of ASSET_TX_AMOUNT_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    recv_forged_unchecked:1,1024
    recv_forged_settled_zero:1024
    recv_honest_verified:-
    recv_honest_backed_two:1024
    recv_known_forged_zero:1024
    recv_issuance_output0:1024
    recv_range_settled_zero:-
    recv_spent_counts:2,1024
    recv_sums_saturate:4,1024
    recv_unsettled_uncounted:1
    recv_other_txid:1024,2048
    recv_not_asset_row:-
    recv_id_skips_placeholder:64,1024
    recv_id_skips_upper_placeholder:64,512,1024
    recv_id_skips_uncounted:1,128,1024
    recv_id_placeholder_only:64,1024
    recv_id_absent:1024
    recv_no_rows:1024
    send_to_recipient:8
    send_owned_empty:16
    send_to_self:8
    send_data_output_named:16,32
    send_burn:16,256
    send_issuance:16
    send_range_saturates:4,16
    send_uncountable:-
    send_no_carrier:-
    send_undecided:-
    send_no_transaction:-
    send_unresolved_change:8
    vectors_file:1,2,4,8,16,32,64,128,256,512,1024,2048
    arguments:16,1024,2048
    second_unit:-
)
ARMS=(1:unbacked-counts 2:spent-skipped 4:sums-wrap 8:owned-sent 16:empty-owned-nothing 32:data-output-sent
      64:placeholder-named 128:uncounted-named 256:burn-dropped 512:placeholder-case 1024:receive-claimed 2048:any-txid)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the transaction's asset amount moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?BRAssetTxActivityAmount\(" <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not define BRAssetTxActivityAmount."; exit 1
fi
if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?ASSET_TX_AMOUNT_UNFIXED" <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of ASSET_TX_AMOUNT_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_TX_AMOUNT_UNFIXED" <<<"$HDR" ||
   grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_TX_AMOUNT_UNFIXED" <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    bit="${arm%%:*}"
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+ASSET_TX_AMOUNT_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: no ASSET_TX_AMOUNT_RED_* seam of value $bit; arm $bit would be the shipped code."; exit 1
    fi
done

FAIL=0

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")
objs() {   # <dir> <extra flags...>: compile UNITS into <dir>; fails on a build error
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
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
WANT_INCLUDES="\"BRAssetAllocate.h\" \"BRAssetDecode.h\" \"BRAssetDetect.h\" \"BRAssetHold.h\" \"BRAssetHoldings.h\" \"BRAssetQuantity.h\" \"BRTransaction.h\" <stddef.h> <stdint.h> <string.h> "
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | LC_ALL=C sort -u | tr '\n' ' ')"
if [ "$includes" != "$WANT_INCLUDES" ]; then
    echo "PORTABILITY GATE FAILED: BRAssetTxAmount.h includes $includes(want $WANT_INCLUDES)"; FAIL=1
fi
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: BRAssetTxAmount.h calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetTxAmount.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetRow r;
    BRAssetTxAmount a;

    memset(&r, 0, sizeof(r));
    r.txid = "aa";
    r.isAsset = 1;
    r.assetId = "La1";
    r.assetQuantity = 4;
    r.credit = BR_ASSET_CREDIT_VERIFIED;
    a = BRAssetTxActivityAmount("aa", 0, &r, 1, NULL, NULL, 0);
    return a.status == BR_ASSET_TX_AMOUNT_OK && a.units == 4 && a.assetId == r.assetId;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetTxAmount.h"
#include "BRAssetTxAmount.h"
int strict_tu1(void);
int main(void)
{
    BRAssetTxAmount a = BRAssetTxActivityAmount("aa", 1, NULL, 0, NULL, NULL, 0);
    return (a.status == BR_ASSET_TX_AMOUNT_NONE && strict_tu1()) ? 0 : 1;
}
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
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRAssetTxAmount.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let answer: BRAssetTxAmount = "aa".withCString { txid in
    "La1".withCString { id in
        var r = BRAssetRow()
        r.txid = txid
        r.isAsset = 1
        r.assetId = id
        r.assetQuantity = 4
        r.credit = BR_ASSET_CREDIT_BACKED
        return BRAssetTxActivityAmount(txid, 0, &r, 1, nil, nil, 0)
    }
}
let statuses: (BRAssetTxAmountStatus, BRAssetTxAmountStatus, BRAssetTxAmountStatus) =
    (BR_ASSET_TX_AMOUNT_REFUSED, BR_ASSET_TX_AMOUNT_NONE, BR_ASSET_TX_AMOUNT_OK)
print(answer.status == statuses.2, answer.units, answer.assetId != nil, statuses.0, statuses.1)
EOF
    mkdir -p "$BUILD_DIR/swift_inc/crypto" "$BUILD_DIR/swift_inc/secp256k1/include"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    cp "$CORE_DIR"/secp256k1/include/*.h "$BUILD_DIR/swift_inc/secp256k1/include/" 2>/dev/null
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            -Xcc -I"$BUILD_DIR/swift_inc/secp256k1/include" "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi

# ---- VECTORS GATE --------------------------------------------------------------------------
if [ ! -f "$VEC_DIR/generate.c" ] || [ ! -f "$VEC_DIR/corpus.txt" ] || [ ! -f "$VEC_DIR/vectors.txt" ]; then
    echo "VECTORS GATE FAILED: $VEC_DIR/generate.c, corpus.txt or vectors.txt is missing"; FAIL=1
elif ! compgen -G "$BUILD_DIR/strict_objs/*.o" >/dev/null || ! "$CC" -w -std=c99 -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$VEC_DIR/generate.c" "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/gen"; then
    echo "VECTORS GATE FAILED: generate.c did not compile (a build error is not evidence)"; FAIL=1
elif ! "$BUILD_DIR/gen" "$VEC_DIR/corpus.txt" > "$BUILD_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: generate.c did not write its vectors (it exits 1 when a line does not read)"; FAIL=1
elif ! cmp -s "$BUILD_DIR/vectors.txt" "$VEC_DIR/vectors.txt"; then
    echo "VECTORS GATE FAILED: vectors.txt is not what generate.c writes from this header; regenerate it"
    diff "$VEC_DIR/vectors.txt" "$BUILD_DIR/vectors.txt" > "$BUILD_DIR/vec.diff"; sed -n '1,8p' "$BUILD_DIR/vec.diff" | cut -c1-200 | sed 's/^/      /'
    FAIL=1
else
    echo "vectors gate: vectors/asset-tx-amount/vectors.txt is current ($(grep -c '^amount ' "$VEC_DIR/vectors.txt") transactions)"
fi

[ $strict_ok -eq 1 ] && echo "portability gate: libc + the core headers it uses, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetTxAmount.h"
#include "BRAssetTxAmount.h"
int asset_tx_amount_kat_tu2(void);
int asset_tx_amount_kat_tu2(void)
{
    BRAssetRow r;
    BRAssetTxAmount a;

    memset(&r, 0, sizeof(r));
    r.txid = "aa";
    r.isAsset = 1;
    r.assetId = "La1";
    r.assetQuantity = 5;
    r.credit = BR_ASSET_CREDIT_BACKED;
    a = BRAssetTxActivityAmount("aa", 0, &r, 1, NULL, NULL, 0);
    return a.status == BR_ASSET_TX_AMOUNT_OK ? (int)a.units : -1;
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
"$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/tu2.c" \
    -o "$BUILD_DIR/san_objs/zz_tu2.o" || { echo "GATE FAILURE: the second translation unit did not compile."; exit 1; }
build() {   # <out> <ASSET_TX_AMOUNT_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_TX_AMOUNT_UNFIXED="$2" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_tx_amount_kat_main.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
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
    echo "--- COMPARISON ARM -DASSET_TX_AMOUNT_UNFIXED=$bit ($name): fails exactly its cases ---"
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
            echo "  [$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (=0): every case passes, no sanitizer report ---"
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

[ $FAIL -eq 0 ] && echo "PASS: asset_tx_amount_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; vectors current; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_tx_amount_kat"
exit $FAIL
