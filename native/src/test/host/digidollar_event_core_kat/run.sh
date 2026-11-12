#!/usr/bin/env bash
# Host KAT runner for the core's BRDigiDollarEvent.h: a DigiDollar activity row says what the transaction
# is -- minted, sent, received, moved to the wallet itself, redeemed -- and never shows an amount that
# wrapped. The rule moved from Android's bridge header (native/src/main/jni/bridge/digidollar_event.h) and
# the wallet's two answers to it from jni_transaction.c; digidollar_realtx_kat and dd_event_overflow_kat
# still run the bridge's copy. Three mains here:
#   digidollar_event_core_kat_main.c   digidollar_realtx_kat's vectors, value for value (the rule, with a
#                                      wallet view the test supplies)
#   digidollar_event_overflow_main.c   dd_event_overflow_kat's vectors, value for value
#   digidollar_event_wallet_main.c     new: BRWalletDigiDollarEvent / BRWalletDigiDollarEventForHash over a
#                                      real BRWallet (the answers that were in jni_transaction.c, which no
#                                      host KAT could reach there)
#
#   comparison  -DDIGIDOLLAR_EVENT_UNFIXED=1: the rule before (direction read from the DGB movement). Each
#               case of the first and third mains meets the expectation its list gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the invariant
#                 pass  a guard, which holds in both arms
#               -DDD_EVENT_OVERFLOW_UNFIXED=1: the plain add. The second main's case A overflows -- a
#               UBSan report, or a row made from a wrapped amount -- and its setup holds.
#   shipped     both seams 0: every case of every main passes, with no sanitizer report.
# The first and third mains run in a 64-bit build (ASan + UBSan) and a 32-bit build (-m32, ASan); the
# overflow main in the 64-bit build only (its comparison arm is read through UBSan, which the 32-bit
# build does not carry). The arms run with detect_leaks=0; then every case runs once more on the 64-bit
# shipped arm under LeakSanitizer where the platform has one (said so, and skipped, where it does not --
# Apple clang on macOS). Every run is time-limited (a sanitizer report can stall instead of aborting).
#
# Gates first: the header must test each seam's VALUE (#if, never #ifdef) with an #ifndef default, and
# read no other build-time macro (Swift imports the header with flags of its own).
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock, does no I/O and
#                     calls no allocator of its own (the wallet's answers free the transaction copies the
#                     wallet hands them, through BRTransactionFree), and builds clean under -Wall -Wextra
#                     -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the
#                     second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# The bridge's source gate (digiDollarEvent calls the rule under the wallet read guard) stays in
# digidollar_realtx_kat: it reads Android's jni_transaction.c, not the core.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DDIGIDOLLAR_EVENT_UNFIXED and of -DDD_EVENT_OVERFLOW_UNFIXED (=1 comparison,
# =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
KAT=digidollar_event_core_kat
NAME=BRDigiDollarEvent
SEAM=DIGIDOLLAR_EVENT_UNFIXED
SEAM2=DD_EVENT_OVERFLOW_UNFIXED
HEADER="$CORE_DIR/$NAME.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60

# <case>:<comparison-arm expectation> -- digidollar_realtx_kat's list, unchanged
CASES=(
    realtx_decodes:pass
    event_realtx_received:pass
    event_realtx_sent:pass
    event_mint_created:fail
    event_self_transfer:fail
    event_self_transfer_with_change:fail
    event_redeem_full:fail
    event_redeem_with_change:fail
    event_plain_dgb:pass
)
# the wallet-backed main's cases, under the same seam
WALLET_CASES=(
    wallet_received:pass
    wallet_sent:fail
    wallet_self_transfer:fail
    wallet_plain_dgb:pass
    wallet_no_answer:pass
    wallet_redeemed:fail
    wallet_redeemed_second_output:fail
    wallet_received_others_output:pass
    wallet_received_missing_output:pass
    wallet_redeemed_two_coins:fail
    wallet_redeemed_others_coin:fail
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the DigiDollar activity rule moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRDigiDollarEventOf BRWalletDigiDollarEvent BRWalletDigiDollarEventForHash; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
for seam in $SEAM $SEAM2; do
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?$seam" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not test the value of $seam; the arms would not differ."; exit 1
    fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+$seam" <<<"$HDR" ||
       grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+$seam" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER selects an arm by the presence of $seam, or gives it no default of 0."; exit 1
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
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | LC_ALL=C sort -u | tr '\n' ' ')"
want='"BRDigiDollar.h" "BRInt.h" "BRTransaction.h" "BRWallet.h" <stddef.h> <stdint.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
# Every conditional names the include guard, C++ or a seam: no other build-time macro selects code.
conds="$(grep -E '^[[:space:]]*#[[:space:]]*(if|ifdef|ifndef|elif)([^A-Za-z0-9_]|$)' <<<"$CODE" | sed -E 's/^[[:space:]]*#[[:space:]]*(ifdef|ifndef|elif|if)//; s/[^A-Za-z0-9_]+/ /g; s/^ +//; s/ +$//' | LC_ALL=C sort -u | tr '\n' ' ')"
want_conds="${NAME}_h $SEAM2 $SEAM __cplusplus "
if [ "$conds" != "$want_conds" ]; then
    echo "PORTABILITY GATE FAILED: the header's conditionals read $conds(want $want_conds)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRDigiDollarEvent.h"
int strict_tu1(void);
int strict_tu1(void) { BRDigiDollarEvent ev = BRDigiDollarEventOf(NULL, NULL);
    return ev.kind == BR_DIGIDOLLAR_EVENT_NONE && ev.cents == 0 && ev.changeCents == 0; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRDigiDollarEvent.h"
#include "BRDigiDollarEvent.h"
int strict_tu1(void);
int main(void) { BRDigiDollarEvent ev = BRWalletDigiDollarEvent(NULL, NULL), held = { BR_DIGIDOLLAR_EVENT_SENT, 1, 2 };
    return (ev.kind == BR_DIGIDOLLAR_EVENT_NONE && BRWalletDigiDollarEventForHash(NULL, UINT256_ZERO, &held) == 0 &&
            held.kind == BR_DIGIDOLLAR_EVENT_SENT && strict_tu1()) ? 0 : 1; }
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
    printf '#include "BRDigiDollarEvent.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var view = BRDigiDollarEventWallet()
view.paysWallet = { _, _ in 0 }
view.spendsWallet = { _, _, cents in cents?.pointee = -1; return 0 }
let ruled: BRDigiDollarEvent = BRDigiDollarEventOf(nil, &view)
let asked: BRDigiDollarEvent = BRWalletDigiDollarEvent(nil, nil)
var held = BRDigiDollarEvent()
let found: Int32 = BRWalletDigiDollarEventForHash(nil, UInt256(), &held)
let kinds: [BRDigiDollarEventKind] = [BR_DIGIDOLLAR_EVENT_NONE, BR_DIGIDOLLAR_EVENT_MINTED, BR_DIGIDOLLAR_EVENT_SENT,
    BR_DIGIDOLLAR_EVENT_RECEIVED, BR_DIGIDOLLAR_EVENT_SELF_TRANSFER, BR_DIGIDOLLAR_EVENT_REDEEMED]
let cents: Int64 = ruled.cents + ruled.changeCents + asked.cents + held.cents
print(ruled.kind == BR_DIGIDOLLAR_EVENT_NONE, asked.kind.rawValue, found, kinds.count, cents)
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
[ $strict_ok -eq 1 ] && [ $FAIL -eq 0 ] && echo "portability gate: libc + core headers, no clock/I/O, no allocator call, no build-time macro but the two seams; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
SAN64=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
SAN32=(-m32 -g -fsanitize=address -fno-omit-frame-pointer)
build() {   # <out> <64|32> <main file> <flags...>
    local out="$1" bits="$2" main="$3"; shift 3
    local san=("${SAN64[@]}"); [ "$bits" = 32 ] && san=("${SAN32[@]}")
    "$CC" -w -std=c99 -include stdint.h "${san[@]}" "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/$main" "$BUILD_DIR"/san_objs"$bits"/*.o -lm -lpthread -o "$out"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit. The watchdog
# leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was stopped (the race fixed in
# base58_input_bound_kat, #211).
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; a plain kill would run it and remove $BUILD_DIR under
    # the other runs. Drop the trap inside; stop it with a trap-free signal.
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }   # first lines of a file, indented

# suite <tag> <bits> <main file> <case:expectation>...: both arms of one case-per-process main
suite() {
    local tag="$1" bits="$2" main="$3"; shift 3
    local red="$BUILD_DIR/red_$tag$bits" green="$BUILD_DIR/green_$tag$bits" entry c want out rc first known listed
    if ! build "$red" "$bits" "$main" -D$SEAM=1 2>"$BUILD_DIR/build$bits.err"; then
        echo "GATE FAILURE: ${bits}-bit comparison arm of $main did not compile (a build error is not evidence)."
        show "$BUILD_DIR/build$bits.err" 3; FAIL=1; return
    fi
    if ! build "$green" "$bits" "$main" -D$SEAM=0 2>"$BUILD_DIR/build$bits.err"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm of $main did not compile."
        show "$BUILD_DIR/build$bits.err" 3; FAIL=1; return
    fi
    known="$("$green" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${@%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree ($main)"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; return
    fi

    echo "--- $main, COMPARISON ARM (-D$SEAM=1): each case meets its expectation ---"
    for entry in "$@"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red_$tag$bits.$c.out"
        bounded "$out" "$red" "$c"; rc=$?
        if [ $rc -eq 124 ]; then
            echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue
        fi
        if grep -Eq "$SAN_RE" "$out"; then
            echo "  [red$bits $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue
        fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                first="$(grep -m1 '^FAIL:' "$out")"
                echo "  [red$bits $c] failed, as it must: ${first:6:150}"
            else
                echo "  [red$bits $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"
                show "$out"; FAIL=1
            fi ;;
        pass)
            if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
                echo "  [red$bits $c] passed (guard)"
            else
                echo "  [red$bits $c] GATE FAILURE: the guard did not hold in the comparison arm (rc=$rc)"
                show "$out" 12; FAIL=1
            fi ;;
        *)
            echo "  [red$bits $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
        esac
    done

    echo "--- $main, SHIPPED ARM (-D$SEAM=0): every case passes, no sanitizer report ---"
    for entry in "$@"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green_$tag$bits.$c.out"
        bounded "$out" "$green" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
        fi
    done
}

# The overflow main, one process per arm: case A must overflow in the comparison arm (a UBSan report, or a
# row made from a wrapped amount) with its setup intact; the shipped arm passes every case, UBSan clean.
overflow() {
    local red="$BUILD_DIR/red_overflow" green="$BUILD_DIR/green_overflow" out rc
    if ! build "$red" 64 digidollar_event_overflow_main.c -D$SEAM2=1 2>"$BUILD_DIR/build64.err"; then
        echo "GATE FAILURE: the overflow main's comparison arm did not compile (a build error is not evidence)."
        show "$BUILD_DIR/build64.err" 3; FAIL=1; return
    fi
    if ! build "$green" 64 digidollar_event_overflow_main.c -D$SEAM2=0 2>"$BUILD_DIR/build64.err"; then
        echo "GATE FAILURE: the overflow main's shipped arm did not compile."
        show "$BUILD_DIR/build64.err" 3; FAIL=1; return
    fi
    echo "--- digidollar_event_overflow_main.c, COMPARISON ARM (-D$SEAM2=1): case A overflows ---"
    out="$BUILD_DIR/red_overflow.out"
    bounded "$out" "$red"; rc=$?
    if [ $rc -ne 0 ] && [ $rc -ne 124 ] && ! grep -q "FAIL: setup" "$out" && ! grep -q "ERROR: AddressSanitizer" "$out" &&
       grep -Eq "signed integer overflow|^FAIL: A:" "$out"; then
        echo "  [red64 overflow] case A overflows, as it must: $(grep -m1 -oE 'signed integer overflow|FAIL: A:.*' "$out" | cut -c1-150)"
    else
        echo "  [red64 overflow] GATE FAILURE: the comparison arm did not overflow on case A (rc=$rc)"; show "$out"; FAIL=1
    fi
    echo "--- digidollar_event_overflow_main.c, SHIPPED ARM (-D$SEAM2=0): every case passes, no sanitizer report ---"
    out="$BUILD_DIR/green_overflow.out"
    bounded "$out" "$green"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^PASS: $KAT (overflow)$" "$out"; then
        echo "  [green64 overflow] passed ($(grep -c '^PASS: [^d]' "$out") checks)"
    else
        echo "  [green64 overflow] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
    fi
}

for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    san=("${SAN64[@]}"); [ "$bits" = 32 ] && san=("${SAN32[@]}")
    if ! objs "$BUILD_DIR/san_objs$bits" "${san[@]}" 2>"$BUILD_DIR/objs$bits.err"; then
        echo "GATE FAILURE: the core units did not compile for the ${bits}-bit build (a build error is not evidence)."
        show "$BUILD_DIR/objs$bits.err" 3; FAIL=1; continue
    fi
    suite rule "$bits" "${KAT}_main.c" "${CASES[@]}"
    suite wallet "$bits" digidollar_event_wallet_main.c "${WALLET_CASES[@]}"
    [ "$bits" = 64 ] && overflow
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
if [ ! -x "$BUILD_DIR/green_rule64" ] || [ ! -x "$BUILD_DIR/green_wallet64" ] || [ ! -x "$BUILD_DIR/green_overflow" ]; then
    leak_note="not run: no 64-bit shipped arm"
else
    leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green_rule64" list 2>&1)"
    if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
        leak_note="every case clean"
        echo "--- LEAK CHECK: every case on the 64-bit shipped arm under detect_leaks=1 ---"
        for entry in "${CASES[@]/#/rule:}" "${WALLET_CASES[@]/#/wallet:}"; do
            tag="${entry%%:*}"; c="${entry#*:}"; c="${c%%:*}"; out="$BUILD_DIR/leak.$c.out"
            ASAN_OPTIONS="$ASAN_LEAK" bounded "$out" "$BUILD_DIR/green_$tag""64" "$c"; rc=$?
            if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" "$out" || ! grep -q "^RESULT $c pass$" "$out"; then
                echo "  [leak $c] GATE FAILURE (rc=$rc)"; show "$out" 6; FAIL=1
                leak_note="FAILED"
            fi
        done
        out="$BUILD_DIR/leak.overflow.out"
        ASAN_OPTIONS="$ASAN_LEAK" bounded "$out" "$BUILD_DIR/green_overflow"; rc=$?
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" "$out"; then
            echo "  [leak overflow] GATE FAILURE (rc=$rc)"; show "$out" 6; FAIL=1
            leak_note="FAILED"
        fi
    fi
fi
echo "leak check: $leak_note"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: $KAT (${#CASES[@]} rule cases, ${#WALLET_CASES[@]} wallet cases, overflow A red-then-green and B guard: comparison arms as expected, shipped arms clean; 64-bit ASan + UBSan and 32-bit ASan; leak check $leak_note; portability)"
    exit 0
fi
echo "FAIL: $KAT"
exit 1
