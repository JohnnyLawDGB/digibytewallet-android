#!/usr/bin/env bash
# sweep_fee_ceiling_kat -- SWEEP-ABS-CEIL: the legacy sweep's native backstop bounds the fee for single-input
# sweeps too (an absolute ceiling, not an exemption). See the _main.c for the cases.
#   RED    -DSWEEP_ABS_CEIL_UNFIXED=1: the guard before this item. Cases A-C, H and I must FAIL on their
#          named checks; every guard (D-G, H's boundary guard) must still pass.
#   GREEN  every check passes; no sanitizer report. 64- and 32-bit.
# Then a SOURCE GATE over the real jni_derive.c: buildAndSignLegacySweep takes its fee from sweep_fee_check,
# refuses on any verdict but OK before it signs, and keeps no inline single-input exemption.
# SWEEP_FEE_GATE_FILE=<path> points the source gate at another copy (used to show it refuses one).
# MACRO CONVENTION: VALUE of -DSWEEP_ABS_CEIL_UNFIXED.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/sweep_fee_guard.h"
JNI="${SWEEP_FEE_GATE_FILE:-$BRIDGE_DIR/jni_derive.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'

[ -f "$HEADER" ] || { echo "GATE FAILURE: $HEADER not found"; exit 1; }
grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+SWEEP_ABS_CEIL_UNFIXED' "$HEADER" \
    || { echo "GATE FAILURE: the header does not test the VALUE of SWEEP_ABS_CEIL_UNFIXED"; exit 1; }

build() {   # <out> <unfixed> <bits>
    local m=(); [ "$3" = 32 ] && m=(-m32)
    clang -w -g "${m[@]}" -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer \
        -DSWEEP_ABS_CEIL_UNFIXED="$2" -I "$BRIDGE_DIR" "$SCRIPT_DIR/sweep_fee_ceiling_kat_main.c" -o "$1"
}
FAIL=0
RED_MUST_FAIL=(
    "^FAIL: A: a single-input sweep paying"
    "^FAIL: B: a single-input sweep above the rate ceiling is signed"
    "^FAIL: C: a sweep paying more than the absolute ceiling is signed"
    "^FAIL: H: a single input listed far below what it holds is signed"
    "^FAIL: H: a single input whose fee is 5% of its listed amount is signed"
    "^FAIL: I: a sweep at a rate below minimum relay is signed$"
    "^FAIL: I: a sweep at a rate below minimum relay is signed (one under)"
)
for bits in 64 32; do
    build "$BUILD_DIR/red$bits" 1 "$bits" || { echo "GATE FAILURE: red$bits did not build (not evidence)"; FAIL=1; continue; }
    OUT="$(timeout -k 5 60 "$BUILD_DIR/red$bits" 2>&1)"; RC=$?
    ok=1
    [ "$RC" -ne 0 ] && [ "$RC" -ne 124 ] || ok=0
    <<<"$OUT" grep -qE "$SAN_RE" && ok=0
    for want in "${RED_MUST_FAIL[@]}"; do <<<"$OUT" grep -q "$want" || { echo "  [red$bits] did not fail: $want"; ok=0; }; done
    EXTRA="$(<<<"$OUT" grep "^FAIL" | grep -vE "^FAIL: (A|B|C|H|I): ")"
    [ -z "$EXTRA" ] || { echo "  [red$bits] an unexpected check failed: $EXTRA"; ok=0; }
    if [ "$ok" -eq 1 ]; then echo "  [red$bits] ${#RED_MUST_FAIL[@]} named checks failed, as required; guards held"
    else echo "GATE FAILURE: red$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head; FAIL=1; fi

    build "$BUILD_DIR/green$bits" 0 "$bits" || { echo "GATE FAILURE: green$bits did not build"; FAIL=1; continue; }
    OUT="$(timeout -k 5 60 "$BUILD_DIR/green$bits" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "$SAN_RE" && <<<"$OUT" grep -q "^PASS: sweep_fee_ceiling_kat"; then
        echo "  [green$bits] all $(<<<"$OUT" grep -c '^PASS:') checks pass"
    else echo "GATE FAILURE: green$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head; FAIL=1; fi
done

# ---- SOURCE GATE ------------------------------------------------------------------------------
gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
[ -f "$JNI" ] || { echo "GATE FAILURE: $JNI not found -- the gate would observe nothing"; exit 1; }
STRIPPED="$(sed -e 's@//.*$@@' "$JNI" | awk 'BEGIN{c=0} { line=$0; out="";
    while (length(line)) {
        if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
        else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
               out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
    print out }')"
BODY="$(awk '!f && /^NativeBridge_buildAndSignLegacySweep[[:space:]]*[(]/ { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
        | tr '\n\t' '  ' | sed -E 's/ +/ /g')"
offsets() { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }
grep -Eq '#[[:space:]]*include[[:space:]]+"sweep_fee_guard\.h"' <<<"$STRIPPED" || say "jni_derive.c does not include sweep_fee_guard.h"
if [ -z "$BODY" ]; then say "NativeBridge_buildAndSignLegacySweep not found"
else
    CHECKS=($(offsets 'sweep_fee_check[[:space:]]*\(' "$BODY"))
    SIGNS=($(offsets 'BRTransactionSign[[:space:]]*\(' "$BODY"))
    OUTS=($(offsets 'BRTransactionAddOutput[[:space:]]*\(' "$BODY"))
    [ "${#CHECKS[@]}" -eq 1 ] || say "buildAndSignLegacySweep must call sweep_fee_check exactly once (found ${#CHECKS[@]})"
    [ "${#SIGNS[@]}" -eq 1 ] && [ "${#OUTS[@]}" -eq 1 ] || say "expected one output and one signature"
    if [ "${#CHECKS[@]}" -eq 1 ] && [ "${#SIGNS[@]}" -eq 1 ] && [ "${#OUTS[@]}" -eq 1 ]; then
        [ "${CHECKS[0]}" -lt "${OUTS[0]}" ] && [ "${CHECKS[0]}" -lt "${SIGNS[0]}" ] || say "the fee is not checked before the output is built and signed"
    fi
    grep -Eq 'if[[:space:]]*\([[:space:]]*feeVerdict[[:space:]]*!=[[:space:]]*SWEEP_FEE_OK[[:space:]]*\)' <<<"$BODY" || say "a verdict other than SWEEP_FEE_OK is not refused"
    grep -Eq 'sweep_fee_check[[:space:]]*\([^;]*&fee[[:space:]]*\)' <<<"$BODY" || say "the fee is not the one sweep_fee_check computed"
    grep -Eq 'inputCount[[:space:]]*>[[:space:]]*1[[:space:]]*&&' <<<"$BODY" && say "an inline input-count exemption remains"
    grep -Eq 'fee[[:space:]]*=[[:space:]]*\(\(uint64_t\)estSize' <<<"$BODY" && say "an inline fee computation remains"
fi
if [ "$gate_fail" -eq 0 ]; then echo "  gate: buildAndSignLegacySweep takes its fee from sweep_fee_check and refuses before building and signing"
else echo "SOURCE GATE FAILED"; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: sweep_fee_ceiling_kat (A-C, H, I red-then-green; D-G guards; 64/32-bit, ASan+UBSan; source gate)"; exit 0; }
echo "FAIL: sweep_fee_ceiling_kat"; exit 1
