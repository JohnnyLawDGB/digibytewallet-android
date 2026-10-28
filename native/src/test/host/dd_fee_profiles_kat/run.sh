#!/usr/bin/env bash
# Host KAT runner: a DigiDollar recovery transfer whose fee is paid by coins of two derivation profiles
# signs every fee input with the key of its own derivation. See dd_fee_profiles_kat_main.c.
#
# Builds the main with the bridge's digidollar_fee_keys.h against the live core, with AddressSanitizer,
# 64-bit and 32-bit (-m32), in two arms:
#   comparison  -DDIGIDOLLAR_FEE_KEYS_UNFIXED=1: one derivation for every fee input (the single HMAC key
#               and prefix the bridge took). Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the invariant
#                 pass  a guard, which holds in both arms
#   shipped     -DDIGIDOLLAR_FEE_KEYS_UNFIXED=0: every case passes, with no sanitizer report.
# Every run is time-limited. Last, a SOURCE GATE over the real jni_derive.c (Android headers keep it off
# the host): buildAndSignForeignDigiDollarTransfer takes one HMAC key and one prefix PER fee input, holds
# each array to the fee input count, and derives the fee keys only through dd_fee_input_keys.
# DD_FEE_GATE_FILE=<path> points the source gate at another copy.
#
# MACRO CONVENTION: VALUE of -DDIGIDOLLAR_FEE_KEYS_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early: here-strings and files only.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/digidollar_fee_keys.h"
JNI="${DD_FEE_GATE_FILE:-$BRIDGE_DIR/jni_derive.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=60

CASES=(
    two_profiles_both_verify:fail
    one_profile_signs:pass
    bad_path_refused:pass
)

if [ ! -f "$HEADER" ]; then echo "GATE FAILURE: $HEADER not found."; exit 1; fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+DIGIDOLLAR_FEE_KEYS_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of DIGIDOLLAR_FEE_KEYS_UNFIXED; the arms would not differ."; exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+DIGIDOLLAR_FEE_KEYS_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE."; exit 1
fi

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
build() {   # <out> <64|32> <unfixed 0|1>
    local out="$1" bits="$2" unfixed="$3"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} \
        -DDIGIDOLLAR_FEE_KEYS_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/dd_fee_profiles_kat_main.c" \
        "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3[@]}" -lm -o "$out"
}
bounded() {   # <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}
show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }

FAIL=0
for bits in 64 32; do
    echo "=== ${bits}-bit build ==="
    if ! build "$BUILD_DIR/red$bits" "$bits" 1; then echo "GATE FAILURE: ${bits}-bit comparison arm did not compile."; FAIL=1; continue; fi
    if ! build "$BUILD_DIR/green$bits" "$bits" 0; then echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."; FAIL=1; continue; fi
    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then echo "GATE FAILURE: case lists disagree ($known / $listed)"; FAIL=1; continue; fi

    echo "--- COMPARISON ARM (-DDIGIDOLLAR_FEE_KEYS_UNFIXED=1): each case meets its expectation ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red$bits.$c.out"
        bounded "$out" "$BUILD_DIR/red$bits" "$c"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
        if grep -Eq "$SAN_RE" "$out"; then echo "  [red$bits $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                echo "  [red$bits $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-170)"
            else
                echo "  [red$bits $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"; show "$out"; FAIL=1
            fi ;;
        pass)
            if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
                echo "  [red$bits $c] passed (guard)"
            else
                echo "  [red$bits $c] GATE FAILURE: the guard did not hold in the comparison arm (rc=$rc)"; show "$out" 12; FAIL=1
            fi ;;
        esac
    done

    echo "--- SHIPPED ARM (-DDIGIDOLLAR_FEE_KEYS_UNFIXED=0): every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$BUILD_DIR/green$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
        fi
    done
done

# ---- SOURCE GATE over the real bridge -------------------------------------------------------
echo "=== source gate: $JNI ==="
gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
if [ ! -f "$JNI" ]; then echo "GATE FAILURE: $JNI not found -- the gate would observe nothing."; exit 1; fi
STRIPPED="$(sed -e 's@//.*$@@' "$JNI" | awk 'BEGIN{c=0} { line=$0; out="";
    while (length(line)) {
        if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
        else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
               out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
    print out }')"
FN="$(awk '!f && /^NativeBridge_buildAndSignForeignDigiDollarTransfer[[:space:]]*[(]/ { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
      | tr '\n\t' '  ' | sed -E 's/ +/ /g')"
grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]+"digidollar_fee_keys\.h"' <<<"$STRIPPED" || say "jni_derive.c does not include digidollar_fee_keys.h"
if [ -z "$FN" ]; then
    say "NativeBridge_buildAndSignForeignDigiDollarTransfer not found"
else
    grep -Eq 'jobjectArray[[:space:]]+feeHmacKeys' <<<"$FN" || say "the transfer does not take one HMAC key per fee input (jobjectArray feeHmacKeys)"
    grep -Eq 'jobjectArray[[:space:]]+feePrefixPaths' <<<"$FN" || say "the transfer does not take one prefix per fee input (jobjectArray feePrefixPaths)"
    grep -Eq 'jstring[[:space:]]+feeHmacKey[[:space:],)]' <<<"$FN" && say "the transfer still takes a single fee HMAC key"
    grep -Eq 'jintArray[[:space:]]+feePrefixPath[[:space:],)]' <<<"$FN" && say "the transfer still takes a single fee prefix"
    for arr in feeHmacKeys feePrefixPaths; do
        grep -Eq "GetArrayLength[[:space:]]*\\([[:space:]]*env[[:space:]]*,[[:space:]]*$arr[[:space:]]*\\)[[:space:]]*!=[[:space:]]*feeCount" <<<"$FN" ||
            say "the transfer does not hold $arr to one entry per fee input"
    done
    n_fee="$(grep -oE 'dd_fee_input_keys[[:space:]]*\(' <<<"$FN" | wc -l | tr -d ' ')"
    [ "$n_fee" -eq 1 ] || say "the fee keys must be derived through dd_fee_input_keys exactly once (found $n_fee)"
    n_path="$(grep -oE 'BRBIP32PrivKeyArrayPath[[:space:]]*\(' <<<"$FN" | wc -l | tr -d ' ')"
    [ "$n_path" -eq 1 ] || say "only the DigiDollar inputs may be derived inline -- one BRBIP32PrivKeyArrayPath (found $n_path)"
fi
[ "$gate_fail" -eq 0 ] && echo "  gate: buildAndSignForeignDigiDollarTransfer takes one HMAC key and one prefix per fee input, holds both to the fee input count, and derives the fee keys only through dd_fee_input_keys"
[ "$gate_fail" -ne 0 ] && { echo "SOURCE GATE FAILED"; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: dd_fee_profiles_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan; source gate over jni_derive.c)"
    exit 0
fi
echo "FAIL: dd_fee_profiles_kat"
exit 1
