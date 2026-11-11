#!/usr/bin/env bash
# asset_sign_gate_kat -- ASSET-SIGN-GATE: the DigiAsset sign path re-checks natively, before and after
# signing, that it spends only wallet or asset coins at the amounts the wallet holds and pays only the
# approved outputs and fee. See the _main.c for the cases.
#
#   RED    -DASSET_SIGN_GATE_UNFIXED=1: nothing checked (the sign path before this item). Cases B-L must FAIL
#          on their named checks; the guards (A, M) and every setup check must still pass.
#   RED2   -DASSET_SIGN_GATE_UNFIXED=2: the first version of the check (recipient by script alone, any number
#          of data outputs, the fee against the plan's own fee only). Cases I-L must FAIL; B-H and the guards
#          must pass.
#   GREEN  every check passes; no sanitizer report.
# Each arm is built 64- and 32-bit, with ASan and UBSan.
# Then a SOURCE GATE over the real jni_asset_send.c (Android headers keep it off the host): the bridge runs
# asset_sign_check exactly twice, once before its one signature and once after it, each handed the approved
# recipient, its amount, the plan's fee and the approved fee and each refusing on any verdict but OK; it
# serializes the signed bytes only after the second, releases the coins it claimed when the second refuses,
# and refuses an approval of no amount or fee.
# ASSET_SIGN_GATE_FILE=<path> points the source gate at another copy (used to show it refuses one).
#
# MACRO CONVENTION: VALUE of -DASSET_SIGN_GATE_UNFIXED (=1, =2 comparison, unset/0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arm's nonzero exit is inspected. No pipe into an early-exit reader.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/asset_sign_gate.h"
JNI="${ASSET_SIGN_GATE_FILE:-$BRIDGE_DIR/jni_asset_send.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=0"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'

# ---- the seam is real -----------------------------------------------------------------------
[ -f "$HEADER" ] || { echo "GATE FAILURE: $HEADER not found"; exit 1; }
grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+ASSET_SIGN_GATE_UNFIXED[[:space:]]*==[[:space:]]*1' "$HEADER" \
    || { echo "GATE FAILURE: the header does not test the VALUE of ASSET_SIGN_GATE_UNFIXED (==1)"; exit 1; }
grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+ASSET_SIGN_GATE_UNFIXED[[:space:]]*!=[[:space:]]*2' "$HEADER" \
    || { echo "GATE FAILURE: the header does not test the VALUE of ASSET_SIGN_GATE_UNFIXED (!=2)"; exit 1; }

build() {   # <out> <unfixed 0|1|2> <bits>
    local m=(); [ "$3" = 32 ] && m=(-m32)
    clang -w -include stdint.h -g "${m[@]}" -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer -DASSET_SIGN_GATE_UNFIXED="$2" \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_sign_gate_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$1"
}

FAIL=0
RED1_MUST_FAIL=(
    "^FAIL: B: an input listed below its held amount is signed"
    "^FAIL: C: a foreign unconfirmed fee coin is signed"
    "^FAIL: D: an input the wallet does not hold is signed"
    "^FAIL: E: a coin another send has spent is signed again"
    "^FAIL: F: change to an unapproved script is signed"
    "^FAIL: G: a send paying another fee than approved is signed"
    "^FAIL: H: a signed send with redirected change is handed back"
    "^FAIL: I: a plan paying more than the approved fee is signed"
    "^FAIL: J: a recipient output above the approved amount is signed"
    "^FAIL: K: a second recipient output is signed"
    "^FAIL: L: a second data output is signed"
)
RED2_MUST_FAIL=(
    "^FAIL: I: a plan paying more than the approved fee is signed"
    "^FAIL: J: a recipient output above the approved amount is signed"
    "^FAIL: K: a second recipient output is signed"
    "^FAIL: L: a second data output is signed"
)
red_arm() {   # <unfixed> <bits> <letters-regex> <patterns...>
    local unfixed="$1" bits="$2" letters="$3"; shift 3
    local bin="$BUILD_DIR/red$unfixed-$bits" OUT RC ok=1 EXTRA want
    build "$bin" "$unfixed" "$bits" || { echo "GATE FAILURE: red$unfixed/$bits did not build (a build error is not evidence)"; FAIL=1; return; }
    OUT="$(timeout -k 5 300 "$bin" 2>&1)"; RC=$?
    [ "$RC" -ne 0 ] && [ "$RC" -ne 124 ] || ok=0
    <<<"$OUT" grep -qE "$SAN_RE" && ok=0
    for want in "$@"; do <<<"$OUT" grep -q "$want" || { echo "  [red$unfixed/$bits] did not fail: $want"; ok=0; }; done
    EXTRA="$(<<<"$OUT" grep "^FAIL" | grep -vE "^FAIL: ($letters): ")"
    [ -z "$EXTRA" ] || { echo "  [red$unfixed/$bits] an unexpected check failed: $EXTRA"; ok=0; }
    if [ "$ok" -eq 1 ]; then echo "  [red$unfixed/$bits] $# named checks failed, as required; the guards and setup held"
    else echo "GATE FAILURE: red$unfixed/$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head -20; FAIL=1; fi
}
for bits in 64 32; do
    red_arm 1 "$bits" "B|C|D|E|F|G|H|I|J|K|L" "${RED1_MUST_FAIL[@]}"
    red_arm 2 "$bits" "I|J|K|L" "${RED2_MUST_FAIL[@]}"
    build "$BUILD_DIR/green$bits" 0 "$bits" || { echo "GATE FAILURE: green/$bits did not build"; FAIL=1; continue; }
    OUT="$(timeout -k 5 300 "$BUILD_DIR/green$bits" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "$SAN_RE" && <<<"$OUT" grep -q "^PASS: asset_sign_gate_kat"; then
        echo "  [green/$bits] all $(<<<"$OUT" grep -c '^PASS:') checks pass"
    else echo "GATE FAILURE: green/$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head -20; FAIL=1; fi
done

# ---- SOURCE GATE over the real bridge ---------------------------------------------------------
gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
[ -f "$JNI" ] || { echo "GATE FAILURE: $JNI not found -- the gate would observe nothing"; exit 1; }
STRIPPED="$(sed -e 's@//.*$@@' "$JNI" | awk 'BEGIN{c=0} { line=$0; out="";
    while (length(line)) {
        if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
        else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
               out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
    print out }')"
BODY="$(awk '!f && /^NativeBridge_buildAndSignAssetTransferTx[[:space:]]*[(]/ { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
        | tr '\n\t' '  ' | sed -E 's/ +/ /g')"
offsets() { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }
grep -Eq '#[[:space:]]*include[[:space:]]+"asset_sign_gate\.h"' <<<"$STRIPPED" || say "jni_asset_send.c does not include asset_sign_gate.h"
if [ -z "$BODY" ]; then
    say "NativeBridge_buildAndSignAssetTransferTx not found"
else
    CHECKS=($(offsets 'asset_sign_check[[:space:]]*\(' "$BODY"))
    SIGNS=($(offsets 'seed_sign_transaction[[:space:]]*\(' "$BODY"))
    SERS=($(offsets 'BRTransactionSerialize[[:space:]]*\(' "$BODY"))
    [ "${#CHECKS[@]}" -eq 2 ] || say "the asset sign path must check exactly twice, before and after signing (found ${#CHECKS[@]})"
    [ "${#SIGNS[@]}" -eq 1 ] || say "the asset sign path must sign exactly once (found ${#SIGNS[@]})"
    if [ "${#CHECKS[@]}" -eq 2 ] && [ "${#SIGNS[@]}" -eq 1 ]; then
        [ "${CHECKS[0]}" -lt "${SIGNS[0]}" ] || say "the first check does not come before the signature"
        [ "${CHECKS[1]}" -gt "${SIGNS[0]}" ] || say "the second check does not come after the signature"
        [ "${#SERS[@]}" -ge 1 ] || say "the signed bytes are never serialized"
        for s in "${SERS[@]}"; do [ "$s" -gt "${CHECKS[1]}" ] || say "signed bytes are serialized before the second check"; done
    fi
    n_bound="$(grep -oE 'asset_sign_check[[:space:]]*\([^;]*recipScript[^;]*approvedRecipientSats[^;]*planFeeSats[^;]*approvedMaxFeeSats[^;]*\)' <<<"$BODY" | wc -l | tr -d ' ')"
    [ "$n_bound" -eq 2 ] || say "each check must be handed the approved recipient, its amount, the plan's fee and the approved fee (found $n_bound)"
    REL=($(offsets 'coin_reservation_release[[:space:]]*\([[:space:]]*tx->txHash' "$BODY"))
    rel_after=0; for r in "${REL[@]}"; do [ "${#CHECKS[@]}" -eq 2 ] && [ "$r" -gt "${CHECKS[1]}" ] && rel_after=1; done
    [ "$rel_after" -eq 1 ] || say "a refusal by the second check does not release the coins the signature claimed"
    n_refuse="$(grep -oE 'if[[:space:]]*\([[:space:]]*gate[[:space:]]*!=[[:space:]]*ASSET_SIGN_OK[[:space:]]*\)' <<<"$BODY" | wc -l | tr -d ' ')"
    [ "$n_refuse" -eq 2 ] || say "each check must refuse on a verdict other than ASSET_SIGN_OK (found $n_refuse)"
    for v in approvedRecipientSats planFeeSats approvedMaxFeeSats; do
        grep -Eq "$v[[:space:]]*<=[[:space:]]*0" <<<"$BODY" || say "an approval of no $v is not refused"
        grep -Eq "jlong[[:space:]]+$v" <<<"$BODY" || say "the sign path is not handed $v"
    done
    grep -Eq 'jstring[[:space:]]+approvedToAddress' <<<"$BODY" || say "the sign path is not handed the approved recipient"
fi
if [ "$gate_fail" -eq 0 ]; then echo "  gate: buildAndSignAssetTransferTx checks the approval before its one signature and again after, then serializes"
else echo "SOURCE GATE FAILED"; FAIL=1; fi

[ "$FAIL" -eq 0 ] && { echo "PASS: asset_sign_gate_kat (B-L red-then-green vs nothing checked, I-L vs the first check; A, M guards; 64/32-bit, ASan+UBSan; source gate over jni_asset_send.c)"; exit 0; }
echo "FAIL: asset_sign_gate_kat"; exit 1
