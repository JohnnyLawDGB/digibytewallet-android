#!/usr/bin/env bash
# Host KAT runner: the DigiDollar recovery signer checks, before it signs, that every input names a
# different outpoint and that the fee is neither below the required fee nor above the approved fee
# (DD-FEE-CEIL). See dd_transfer_guard_kat_main.c.
#
# Builds the main with the bridge's digidollar_transfer_guard.h against the live core, with
# AddressSanitizer, 64-bit and 32-bit (-m32), in two arms:
#   comparison  -DDD_TRANSFER_GUARD_UNFIXED=1: the checks before the header (outputs within the inputs,
#               the 0.1 DGB floor). Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm signs what must be refused
#                 pass  a guard, which holds in both arms
#   shipped     -DDD_TRANSFER_GUARD_UNFIXED=0: every case passes, with no sanitizer report.
# The valid transfers' signed bytes (VECTOR lines) must be the same in both arms and in both widths.
# Every run is time-limited. Last, a SOURCE GATE over the real jni_derive.c (Android headers keep it off
# the host): buildAndSignForeignDigiDollarTransfer takes the rate and the approved fee, asks
# dd_transfer_check once about the assembled transfer with both, and signs only after it, once, and only
# when it answered DD_TRANSFER_OK. DD_TRANSFER_GATE_FILE=<path> points the source gate at another copy.
#
# MACRO CONVENTION: VALUE of -DDD_TRANSFER_GUARD_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early: here-strings and files only.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/digidollar_transfer_guard.h"
JNI="${DD_TRANSFER_GATE_FILE:-$BRIDGE_DIR/jni_derive.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60
N_VECTORS=4

CASES=(
    valid_transfers_sign_unchanged:pass
    fee_below_dd_floor_refused:pass
    overspend_refused:pass
    fee_below_size_required_refused:fail
    fee_above_approved_refused:fail
    fee_above_band_refused:fail
    duplicate_input_refused:fail
)

if [ ! -f "$HEADER" ]; then echo "GATE FAILURE: $HEADER not found."; exit 1; fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+DD_TRANSFER_GUARD_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of DD_TRANSFER_GUARD_UNFIXED; the arms would not differ."; exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+DD_TRANSFER_GUARD_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE."; exit 1
fi

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
build() {   # <out> <64|32> <unfixed 0|1>
    local out="$1" bits="$2" unfixed="$3"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" \
        -DDD_TRANSFER_GUARD_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/dd_transfer_guard_kat_main.c" "$BRIDGE_DIR/coin_reservation.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3[@]}" -lm -lpthread -o "$out"
}
# The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was
# stopped. Asking whether the watchdog is still alive does not: `wait` returns as soon as the
# kill lands, often before the watchdog subshell has exited, and a stopped run was then reported
# as one that returned rc=137 by itself (the race fixed in base58_input_bound_kat, #211).
bounded() {   # <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
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

    echo "--- COMPARISON ARM (-DDD_TRANSFER_GUARD_UNFIXED=1): each case meets its expectation ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red$bits.$c.out"
        bounded "$out" "$BUILD_DIR/red$bits" "$c"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
        if grep -Eq "$SAN_RE" "$out"; then echo "  [red$bits $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                echo "  [red$bits $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-200)"
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

    echo "--- SHIPPED ARM (-DDD_TRANSFER_GUARD_UNFIXED=0): every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$BUILD_DIR/green$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
        fi
    done

    # The valid transfers' signed bytes: the same with and without the check, and in both widths.
    grep '^VECTOR ' "$BUILD_DIR/red$bits.valid_transfers_sign_unchanged.out" >"$BUILD_DIR/vectors.red$bits" 2>/dev/null
    grep '^VECTOR ' "$BUILD_DIR/green$bits.valid_transfers_sign_unchanged.out" >"$BUILD_DIR/vectors.green$bits" 2>/dev/null
    nvec="$(wc -l <"$BUILD_DIR/vectors.green$bits" | tr -d ' ')"
    if [ "$nvec" -eq "$N_VECTORS" ] && cmp -s "$BUILD_DIR/vectors.red$bits" "$BUILD_DIR/vectors.green$bits"; then
        echo "  [vectors$bits] $nvec valid transfers: signed bytes identical in the comparison and shipped arms"
    else
        echo "  [vectors$bits] GATE FAILURE: the valid transfers' signed bytes differ between the arms, or are not $N_VECTORS (got $nvec)"; FAIL=1
    fi
done
if [ -s "$BUILD_DIR/vectors.green64" ] && [ -s "$BUILD_DIR/vectors.green32" ]; then
    if cmp -s "$BUILD_DIR/vectors.green64" "$BUILD_DIR/vectors.green32"; then
        echo "  [vectors] 64-bit and 32-bit signed bytes identical"
    else
        echo "  [vectors] GATE FAILURE: 64-bit and 32-bit signed bytes differ"; FAIL=1
    fi
fi

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
grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]+"digidollar_transfer_guard\.h"' <<<"$STRIPPED" || say "jni_derive.c does not include digidollar_transfer_guard.h"
if [ -z "$FN" ]; then
    say "NativeBridge_buildAndSignForeignDigiDollarTransfer not found"
else
    grep -Eq 'jlong feePerKb[ ,)]' <<<"$FN" || say "the transfer does not take the rate it was sized at (jlong feePerKb)"
    grep -Eq 'jlong approvedMaxFeeSats[ ,)]' <<<"$FN" || say "the transfer does not take the approved fee (jlong approvedMaxFeeSats)"
    n_check="$(grep -oE 'dd_transfer_check ?\(' <<<"$FN" | wc -l | tr -d ' ')"
    [ "$n_check" -eq 1 ] || say "the assembled transfer must be asked of dd_transfer_check exactly once (found $n_check)"
    grep -Eq 'ddVerdict = dd_transfer_check ?\( ?tx, ?\(uint64_t\) ?feePerKb, ?\(uint64_t\) ?approvedMaxFeeSats, ?NULL ?\)' <<<"$FN" ||
        say "dd_transfer_check is not asked about tx with the caller's rate and approved fee"
    grep -Eq 'if ?\( ?ddVerdict != DD_TRANSFER_OK ?\) ?\{[^}]*ok = 0;' <<<"$FN" || say "a verdict other than DD_TRANSFER_OK does not clear ok"
    n_sign="$(grep -oE 'BRTransactionSign ?\(' <<<"$FN" | wc -l | tr -d ' ')"
    [ "$n_sign" -eq 1 ] || say "the transfer must be signed in exactly one place (found $n_sign BRTransactionSign calls)"
    grep -Eq 'if ?\( ?ok ?\) signOk = BRTransactionSign ?\(' <<<"$FN" || say "BRTransactionSign is not conditional on ok"
    check_at="$(awk -v s="$FN" 'BEGIN { print index(s, "dd_transfer_check") }')"
    sign_at="$(awk -v s="$FN" 'BEGIN { print index(s, "BRTransactionSign") }')"
    add_at="$(awk -v s="$FN" 'BEGIN { n = 0; t = s; off = 0; while ((i = index(t, "BRTransactionAdd")) > 0) { n = off + i; off += i; t = substr(t, i + 1) } print n }')"
    { [ "$check_at" -gt 0 ] && [ "$sign_at" -gt "$check_at" ]; } || say "dd_transfer_check does not come before BRTransactionSign"
    { [ "$check_at" -gt 0 ] && [ "$add_at" -gt 0 ] && [ "$check_at" -gt "$add_at" ]; } || say "an input or output is added after dd_transfer_check: the check would not see the transfer that is signed"
fi
[ "$gate_fail" -eq 0 ] && echo "  gate: buildAndSignForeignDigiDollarTransfer takes the rate and the approved fee, asks dd_transfer_check once about the assembled transfer, and signs once, after it, only on DD_TRANSFER_OK"
[ "$gate_fail" -ne 0 ] && { echo "SOURCE GATE FAILED"; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: dd_transfer_guard_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; $N_VECTORS valid vectors byte-identical across arms and widths; 64-bit and 32-bit, ASan; source gate over jni_derive.c)"
    exit 0
fi
echo "FAIL: dd_transfer_guard_kat"
exit 1
