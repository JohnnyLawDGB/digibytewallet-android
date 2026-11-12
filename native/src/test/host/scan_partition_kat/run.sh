#!/usr/bin/env bash
# Host KAT runner for BRScanPartition.h: how a reconcile scan reports what the node returned (each row's
# class from the wallet's code, why a held row is held, the partition, the match, move, the wallet-only
# answer). Moved from Kotlin core/.../reconcile/ScanPartition.kt; its ScanPartitionTest runs here case for
# case. See scan_partition_kat_main.c.
#
#   comparison  one arm per defect, -DSCAN_PARTITION_UNFIXED=<bit> (BRScanPartition.h): 1 SPENT and PENDING
#               rows read as missing; 2 the match ignores wallet-only outputs; 4 every HELD row reads as an
#               asset; 8 a move leaves its members in the class moved from; 16 a negative wallet-only
#               answer is read; 32 no hold-rule answer reads as proven. Each arm fails exactly the cases
#               CASES lists for its bit and passes every other.
#   shipped     -DSCAN_PARTITION_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit, linked against the core (the header includes BROutpointClassify.h for
# the codes). The arms run with detect_leaks=0; then every case runs once more on the shipped arm under
# LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock and does no I/O, and
#                     builds clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header
#                     included twice in the second); for iphoneos/iphonesimulator, and through Swift's C
#                     importer (swiftc -import-objc-header) on macOS, where an Xcode SDK / swiftc is present
#                     (skipped, and said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DSCAN_PARTITION_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRScanPartition.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of SCAN_PARTITION_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    fixture_adds_up:-
    address_sent_once:-
    outpoint_once_any_case:-
    headline_is_spendable_balance:4
    only_not_credited_missing:-
    dd_immature_pending_listed:1
    wallet_only_breaks_match:2
    no_wallet_no_partition:-
    proven_remainder_moves:4,8
    held_kind_of:32
    wallet_only_strict:16
    code_table:1,4
    move_onto_itself:4
    move_nothing_and_order:4,8
    arguments:-
    cost_n_log_n:-
)
ARMS=(1:pending-missing 2:wallet-only-ignored 4:held-kind-ignored 8:move-keeps-source 16:wallet-only-lax
      32:no-answer-proven)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the scan partition moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRScanClassForCode BRScanHeldKindOf BRScanWalletOnlyOf BRScanPartitionFree BRScanPartitionTotal \
          BRScanPartitionSpendableSat BRScanPartitionMatchesWallet BRScanPartitionMake BRScanPartitionMove; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?SCAN_PARTITION_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of SCAN_PARTITION_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+SCAN_PARTITION_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+SCAN_PARTITION_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi

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
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
want='"BRInt.h" "BROutpointClassify.h" "BRWallet.h" <stddef.h> <stdint.h> <stdlib.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock or I/O:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRScanPartition.h"
int strict_tu1(void);
int strict_tu1(void) { return BRScanClassForCode(BR_OUTPOINT_SPENT, BR_SCAN_HELD_KIND_ASSET) == BR_SCAN_PENDING; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRScanPartition.h"
#include "BRScanPartition.h"
int strict_tu1(void);
int main(void) { BRScanPartition p; int32_t c = 0; BRScanClassTotal w = { 0, 0 };
    int ok = BRScanPartitionMake(NULL, 0, &c, 0, NULL, &w, 0, &p) && BRScanPartitionMatchesWallet(&p);
    BRScanPartitionFree(&p);
    return (ok && BRScanHeldKindOf(NULL) == BR_SCAN_HELD_KIND_ASSET && strict_tu1()) ? 0 : 1; }
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
    printf '#include "BRScanPartition.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var p = BRScanPartition()
var w = BRScanClassTotal(count: 0, sat: 0)
var code: Int32 = BR_OUTPOINT_SPENDABLE.rawValue
let made = BRScanPartitionMake(nil, 0, &code, 0, nil, &w, 0, &p)
let matches = BRScanPartitionMatchesWallet(&p)
BRScanPartitionFree(&p)
let cls: BRScanClass = BRScanClassForCode(BR_OUTPOINT_HELD.rawValue, BR_SCAN_HELD_KIND_ASSET)
print(made, matches, cls)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"; cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" -Xcc -I"$CORE_DIR/secp256k1/include" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + core headers, no clock/I/O; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRScanPartition.h"
#include "BRScanPartition.h"
int scan_partition_kat_tu2(void);
int scan_partition_kat_tu2(void) { return BRScanClassForCode(BR_OUTPOINT_SPENDABLE, BR_SCAN_HELD_KIND_ASSET) == BR_SCAN_SPENDABLE; }
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
build() {   # <out> <unfixed bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DSCAN_PARTITION_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/scan_partition_kat_main.c" "$BUILD_DIR/tu2.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
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
    echo "--- COMPARISON ARM -DSCAN_PARTITION_UNFIXED=$bit ($name): fails exactly its cases ---"
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
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DSCAN_PARTITION_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | sed 's/^/      /'; FAIL=1
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

[ $FAIL -eq 0 ] && echo "PASS: scan_partition_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: scan_partition_kat"
exit $FAIL
