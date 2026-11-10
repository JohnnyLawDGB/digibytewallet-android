#!/usr/bin/env bash
# Host KAT runner for BROutpointClassify.h: the reconcile outpoint classifier moved from the bridge
# (jni_wallet.c NativeBridge_classifyOutpoints / NativeBridge_spendableNotListed) into the core. The
# bridge's own jni_wallet.c is compiled beside the core and both are driven through the same wallets and
# lists; every answer and the wallet's state must be the same. See outpoint_classify_kat_main.c.
#
#   comparison  one arm per way the copy could differ, -DOUTPOINT_CLASSIFY_UNFIXED=<bit> (the core's copy
#               only; the bridge is unchanged): 1 a held output answered spendable; 2 the coin-generation
#               test skipped; 4 the validity test skipped; 8 the DigiDollar set not consulted; 16 every
#               address read as derived; 32 characters after the index accepted; 64 NOT LISTED counting
#               every spendable output; 128 the spent test skipped; 256 the pending test skipped. Each arm
#               fails exactly the cases CASES lists for its bit and passes every other.
#   shipped     -DOUTPOINT_CLASSIFY_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit, with -DNDEBUG as a release build is: with assertions on, copying
# a coin-generation transaction (BRWalletCopyTransactionForHash -> BRTransactionCopy -> the null-prevout
# assertion in BRTransactionAddInput) stops the bridge and the core alike, so a coinbase outpoint could
# not be compared. The arms run with detect_leaks=0; then every case runs once more on the shipped arm
# under LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default, and
# jni_wallet.c must still define both bridge functions (they are this suite's reference).
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock and does no I/O, and
#                     builds clean under -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header
#                     included twice in the second); for iphoneos/iphonesimulator, and through Swift's C
#                     importer (swiftc -import-objc-header) on macOS, where an Xcode SDK / swiftc is present
#                     (skipped, and said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DOUTPOINT_CLASSIFY_UNFIXED, a set of bits (one bit per arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
HEADER="$CORE_DIR/BROutpointClassify.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S="${OUTPOINT_CLASSIFY_LIMIT:-300}"

# <case>:<the comparison arms (bits of OUTPOINT_CLASSIFY_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    c_fixture:-
    c_spendable:-
    c_held:1
    c_digidollar:8
    c_immature:2
    c_not_credited:16
    c_pending:256
    c_spent:128
    c_conflicted:4
    c_not_held:-
    c_unreadable:32
    c_mixed_duplicates:1,2,4,8,16,32,128,256
    c_large:1,2,4,8,16,128,256
    c_no_wallet_and_empty:-
    n_not_listed:64
    n_unreadable:32,64
    n_large:64
    cost_matches_bridge:-
)
ARMS=(1:held-spendable 2:coinbase-held 4:validity-skipped 8:dd-skipped 16:derived-skipped
      32:trailing-accepted 64:listed-ignored 128:spent-skipped 256:pending-skipped)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the classifier moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BROutpointLineParse BRWalletClassifyOutpoints BRWalletSpendableNotListed; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?\(?OUTPOINT_CLASSIFY_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of OUTPOINT_CLASSIFY_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+OUTPOINT_CLASSIFY_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+OUTPOINT_CLASSIFY_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
JNI="$(cat "$BRIDGE_DIR/jni_wallet.c")"
for fn in NativeBridge_classifyOutpoints NativeBridge_spendableNotListed; do
    if ! grep -Eq "^$fn\(" <<<"$JNI"; then
        echo "GATE FAILURE: jni_wallet.c no longer defines $fn, this suite's reference."; exit 1
    fi
done

FAIL=0

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
CORE_UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRMerkleBlock.c "$CORE_DIR"/BRWalletFilterElements.c
            "$CORE_DIR"/BRDigiDollar.c "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c
            "$CORE_DIR"/BRAddress.c "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
            "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
            "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")
BRIDGE_UNITS=("$BRIDGE_DIR"/jni_wallet.c "$BRIDGE_DIR"/jni_wallet_sign.c "$BRIDGE_DIR"/jni_transaction_persist.c
              "$BRIDGE_DIR"/coin_reservation.c "$BRIDGE_DIR"/slip13.c)

objs() {   # <dir> <with bridge 0|1> <extra flags...>: compile the units into <dir>; fails on a build error
    local dir="$1" bridge="$2"; shift 2
    mkdir -p "$dir"
    local u units=("${CORE_UNITS[@]}")
    [ "$bridge" = 1 ] && units+=("${BRIDGE_UNITS[@]}")
    for u in "${units[@]}"; do
        "$CC" -w -std=gnu11 -include stdint.h "$@" -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" \
            -I "$CORE_DIR/secp256k1/include" \
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
want='"BRAddress.h" "BRInt.h" "BRTransaction.h" "BRWallet.h" <stddef.h> <stdint.h> <stdlib.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock or I/O:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BROutpointClassify.h"
int strict_tu1(void);
int strict_tu1(void) { UInt256 h; uint32_t n; return BROutpointLineParse("x", &h, &n) == 0; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BROutpointClassify.h"
#include "BROutpointClassify.h"
int strict_tu1(void);
int main(void) { int32_t c[1]; int64_t n = 0, s = 0; const char *l[1] = { "x" };
    return (! BRWalletClassifyOutpoints(NULL, l, 1, c) && ! BRWalletSpendableNotListed(NULL, l, 1, &n, &s) &&
            strict_tu1()) ? 0 : 1; }
EOF
strict_ok=1
if ! objs "$BUILD_DIR/strict_objs" 0 -O0; then
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
    printf '#include "BROutpointClassify.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var codes = [Int32](repeating: 0, count: 1)
let lines: [UnsafePointer<CChar>?] = [nil]
let ok = BRWalletClassifyOutpoints(nil, lines, 1, &codes)
var c: Int64 = 0, s: Int64 = 0
let ok2 = BRWalletSpendableNotListed(nil, lines, 1, &c, &s)
var h = UInt256(); var n: UInt32 = 0
let parsed = BROutpointLineParse("x", &h, &n)
let held: BROutpointCode = BR_OUTPOINT_HELD
print(ok, ok2, parsed, held.rawValue)
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
#include "BROutpointClassify.h"
#include "BROutpointClassify.h"
int outpoint_classify_kat_tu2(void);
int outpoint_classify_kat_tu2(void) { UInt256 h; uint32_t n; return BROutpointLineParse("x", &h, &n) == 0; }
EOF

SAN=(-g -DNDEBUG -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" 1 "${SAN[@]}" || { echo "GATE FAILURE: the core and bridge units did not compile under the sanitizers."; exit 1; }
build() {   # <out> <unfixed bits>
    "$CC" -w -std=gnu11 -include stdint.h "${SAN[@]}" -DOUTPOINT_CLASSIFY_UNFIXED="$2" \
        -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/outpoint_classify_kat_main.c" "$BUILD_DIR/tu2.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
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

# bounded <outfile> <asan options> <cmd...>: the command's exit code, or 124 if it was stopped at the limit.
bounded() {
    local outfile="$1" asan="$2"; shift 2
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    ASAN_OPTIONS="$asan" "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -DOUTPOINT_CLASSIFY_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        o="$BUILD_DIR/red_$bit.$c.out"
        bounded "$o" "$ASAN_OPTIONS" "$BUILD_DIR/red_$bit" "$c"; rc=$?
        out="$(cat "$o")"
        if [ $rc -eq 124 ]; then echo "  [red$bit $c] GATE FAILURE: stopped at the ${LIMIT_S}s limit"; FAIL=1; continue; fi
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [red$bit $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [red$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [red$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the difference (rc=$rc)"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own (rc=$rc)"; grep '^FAIL:' <<<"$out" | head -5 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (-DOUTPOINT_CLASSIFY_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    o="$BUILD_DIR/green.$c.out"
    bounded "$o" "$ASAN_OPTIONS" "$BUILD_DIR/green" "$c"; rc=$?
    out="$(cat "$o")"
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE|first difference" <<<"$out" | head -8 | sed 's/^/      /'; FAIL=1
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
        o="$BUILD_DIR/leak.$c.out"
        bounded "$o" "$ASAN_LEAK" "$BUILD_DIR/green" "$c"; rc=$?
        out="$(cat "$o")"
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: outpoint_classify_kat (${#CASES[@]} cases, bridge and core compared on each: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: outpoint_classify_kat"
exit $FAIL
