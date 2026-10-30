#!/usr/bin/env bash
# Host KAT runner: the fee a DGB send's confirmation shows is the fee of the transaction that is
# signed. See fee_quote_kat_main.c for the invariant and the cases.
#
# Compiles the bridge's prepared_send.h (the JNI-free core of quoteTransaction and
# signPreparedTransaction) against the live core sources, with AddressSanitizer, in a 64-bit and a
# 32-bit (-m32) build, in two arms:
#   comparison  -DPREPARED_SEND_UNFIXED=1: today's behaviour (the typical 141-vbyte figure, nothing
#               checked before signing). Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the invariant
#                 pass  a guard: core behaviour the quote relies on, which holds in both arms
#   shipped     -DPREPARED_SEND_UNFIXED=0: every case passes, with no sanitizer report.
# Every run is time-limited (a sanitizer report can stall instead of aborting).
#
# Gates first: the header must carry the seam and test its VALUE (#if, never #ifdef: the =0 build
# would otherwise run the comparison arm). Last, a SOURCE GATE over the real jni_transaction.c
# (Android headers keep it off the host): quoteTransaction and signPreparedTransaction must go
# through this header -- the check before the signature and again after it, each handed the approved
# payment and fee, the signed bytes serialized only after the second check -- and publish nothing.
# FEE_QUOTE_GATE_FILE=<path> points the source gate at another copy (used to show it refuses one).
#
# MACRO CONVENTION: VALUE of -DPREPARED_SEND_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# the writer would die of SIGPIPE and pipefail would read a match as a miss. Here-strings and
# files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/prepared_send.h"
JNI="${FEE_QUOTE_GATE_FILE:-$BRIDGE_DIR/jni_transaction.c}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60

# <case>:<comparison-arm expectation>
CASES=(
    quote_is_fee_built:fail
    quote_unknown_parent:fail
    quote_includes_round_off:fail
    floor_and_round:pass
    balance_round_off:pass
    check_approved_signs:pass
    check_fee_lower:fail
    check_fee_higher:fail
    check_coin_spent:fail
    check_coin_held:fail
    check_parent_gone:fail
    check_recipient_changed:fail
    check_amount_changed:fail
    check_extra_output:fail
    check_change_foreign:fail
    check_self_send_signs:pass
    dd_transfer_builds:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of PREPARED_SEND_UNFIXED; the arms would not differ."
    exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*if(n)?def[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER" &&
   ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE; the =0 build would run the comparison arm."
    exit 1
fi

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

build() {   # <out> <64|32> <unfixed 0|1>
    local out="$1" bits="$2" unfixed="$3"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" \
        -DPREPARED_SEND_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/fee_quote_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; a plain kill would run it and remove
    # $BUILD_DIR under the other runs. Drop the trap inside; stop it with a trap-free signal.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }   # first lines of a file, indented

FAIL=0
for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/red$bits" "$bits" 1; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; continue
    fi
    if ! build "$BUILD_DIR/green$bits" "$bits" 0; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; continue
    fi

    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; continue
    fi

    echo "--- COMPARISON ARM (-DPREPARED_SEND_UNFIXED=1): each case meets its expectation ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red$bits.$c.out"
        bounded "$out" "$BUILD_DIR/red$bits" "$c"; rc=$?
        if [ $rc -eq 124 ]; then
            echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue
        fi
        if grep -Eq "$SAN_RE" "$out"; then
            echo "  [red$bits $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue
        fi
        case "$want" in
        fail)
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                echo "  [red$bits $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-150)"
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

    echo "--- SHIPPED ARM (-DPREPARED_SEND_UNFIXED=0): every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$BUILD_DIR/green$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            note="$(grep -m1 '^NOTE:' "$out" | cut -c7-150)"
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)${note:+ -- $note}"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 14; FAIL=1
        fi
    done
done

# ---- SOURCE GATE over the real bridge -------------------------------------------------------
echo "======================================================================"
echo "=== source gate: $JNI ==="
echo "======================================================================"
gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
if [ ! -f "$JNI" ]; then
    echo "GATE FAILURE: $JNI not found -- the gate would observe nothing."; exit 1
fi
# Comments dropped, so a mention in prose is never read as code.
STRIPPED="$(sed -e 's@//.*$@@' "$JNI" | awk 'BEGIN{c=0} { line=$0; out="";
    while (length(line)) {
        if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
        else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
               out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
    print out }')"
fn_body() {   # the definition of $1 through its column-0 close, flattened to one line
    awk -v re="^NativeBridge_$1[[:space:]]*[(]" '!f && $0 ~ re { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
        | tr '\n\t' '  ' | sed -E 's/ +/ /g'
}
offsets() { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }   # byte offset of every match

if ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]+"prepared_send\.h"' <<<"$STRIPPED"; then
    say "jni_transaction.c does not include prepared_send.h"
fi

QUOTE="$(fn_body quoteTransaction)"
if [ -z "$QUOTE" ]; then
    say "NativeBridge_quoteTransaction not found"
else
    for need in 'BRTransactionParse[[:space:]]*\(' 'prepared_send_quote[[:space:]]*\(' 'NewLongArray[[:space:]]*\('; do
        grep -Eq -- "$need" <<<"$QUOTE" || say "quoteTransaction does not call ${need%%\[*}"
    done
    for never in 'BRPeerManager(Publish|StemPublish)Tx' '_registerWalletCopy' 'seed_sign_transaction' 'BRWalletSetFeePerKb'; do
        grep -Eq -- "$never" <<<"$QUOTE" && say "quoteTransaction calls ${never} -- a quote changes nothing"
    done
    [ "$gate_fail" -eq 0 ] && echo "  gate: quoteTransaction parses the bytes, quotes them through prepared_send_quote and returns [fee, vsize]"
fi

SIGN="$(fn_body signPreparedTransaction)"
if [ -z "$SIGN" ]; then
    say "NativeBridge_signPreparedTransaction not found"
else
    before=$gate_fail; gate_fail=0
    CHECKS=($(offsets 'prepared_send_check[[:space:]]*\(' "$SIGN"))
    SIGNS=($(offsets 'seed_sign_transaction[[:space:]]*\(' "$SIGN"))
    SERS=($(offsets 'BRTransactionSerialize[[:space:]]*\(' "$SIGN"))
    PARSES=($(offsets 'BRTransactionParse[[:space:]]*\(' "$SIGN"))
    [ "${#PARSES[@]}" -ge 1 ] || say "signPreparedTransaction does not parse the prepared bytes"
    if [ "${#CHECKS[@]}" -ne 2 ]; then say "signPreparedTransaction must check exactly twice, before and after signing (found ${#CHECKS[@]})"; fi
    if [ "${#SIGNS[@]}" -ne 1 ]; then say "signPreparedTransaction must sign exactly once (found ${#SIGNS[@]})"; fi
    if [ "${#CHECKS[@]}" -eq 2 ] && [ "${#SIGNS[@]}" -eq 1 ]; then
        [ "${CHECKS[0]}" -lt "${SIGNS[0]}" ] || say "the first check does not come before the signature"
        [ "${CHECKS[1]}" -gt "${SIGNS[0]}" ] || say "the second check does not come after the signature"
        for s in "${SERS[@]}"; do
            [ "$s" -gt "${CHECKS[1]}" ] || say "signed bytes are serialized (offset $s) before the second check (offset ${CHECKS[1]})"
        done
        [ "${#SERS[@]}" -ge 1 ] || say "signPreparedTransaction does not serialize what it signed"
    fi
    # Each check refuses: its verdict is compared with OK and a refusal returns NULL.
    REFUSE='prepared_send_check[[:space:]]*\([^;]*\)[[:space:]]*!=[[:space:]]*PREPARED_SEND_OK'
    n_refuse="$(grep -oE -- "$REFUSE" <<<"$SIGN" | wc -l | tr -d ' ')"
    [ "$n_refuse" -eq 2 ] || say "each check must refuse on a verdict other than PREPARED_SEND_OK (found $n_refuse such tests)"
    grep -Eq 'return[[:space:]]+NULL' <<<"$SIGN" || say "signPreparedTransaction never refuses (no return NULL)"
    # Sign only what was approved: the destination and amount are handed in, and both checks get them.
    { grep -Eq 'jstring[[:space:]]+toAddress' <<<"$SIGN" && grep -Eq 'jlong[[:space:]]+amountSats' <<<"$SIGN"; } ||
        say "signPreparedTransaction is not handed the approved destination and amount"
    n_bound="$(grep -oE 'prepared_send_check[[:space:]]*\([^;]*payScript[^;]*amountSats[^;]*approvedFeeSats[^;]*\)' <<<"$SIGN" | wc -l | tr -d ' ')"
    [ "$n_bound" -eq 2 ] || say "each check must be handed the approved payment (payScript, amountSats) and fee (found $n_bound)"
    grep -Eq 'approvedFeeSats[[:space:]]*<=[[:space:]]*0' <<<"$SIGN" || say "signPreparedTransaction does not refuse an approval of no fee"
    grep -Eq 'seed_is_valid[[:space:]]*\(' <<<"$SIGN" || say "signPreparedTransaction does not refuse a locked session"
    for never in 'BRPeerManager(Publish|StemPublish)Tx' '_registerWalletCopy' 'BRWalletSetFeePerKb' 'BRWalletCreateTransaction'; do
        grep -Eq -- "$never" <<<"$SIGN" && say "signPreparedTransaction calls ${never} -- it signs the prepared bytes and nothing else"
    done
    [ "$gate_fail" -eq 0 ] && echo "  gate: signPreparedTransaction checks the approved payment and fee before signing, signs once, checks again, then serializes; it builds and publishes nothing"
    gate_fail=$(( before || gate_fail ))
fi

if [ "$gate_fail" -ne 0 ]; then
    echo "SOURCE GATE FAILED"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: fee_quote_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan; source gate over jni_transaction.c)"
    exit 0
fi
echo "FAIL: fee_quote_kat"
exit 1
