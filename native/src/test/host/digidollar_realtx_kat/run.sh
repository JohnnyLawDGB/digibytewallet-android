#!/usr/bin/env bash
# Golden-vector KAT: decode a real testnet26 DigiDollar transfer with BRDigiDollar.c -- and say what each
# kind of DigiDollar transaction is for the activity list (the bridge's digidollar_event.h). See
# digidollar_realtx_kat_main.c for the cases.
#
# Builds the main against the live core, with AddressSanitizer, in a 64-bit and a 32-bit (-m32) build,
# in two arms:
#   comparison  -DDIGIDOLLAR_EVENT_UNFIXED=1: the rule the activity list used (direction from the sign
#               of the DGB movement). Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the invariant
#                 pass  a guard, which holds in both arms
#   shipped     -DDIGIDOLLAR_EVENT_UNFIXED=0: every case passes, with no sanitizer report.
# Every run is time-limited. Last, a SOURCE GATE over the real jni_transaction.c (Android headers keep it
# off the host): digiDollarEvent computes the event through this header from the wallet's own view of
# the transaction's outputs and of the outputs its inputs spend, and hands it no DGB movement to read;
# and the old reader that took its direction from the DGB sign (digiDollarTxAmount) is gone.
# DD_EVENT_GATE_FILE=<path> points the source gate at another copy.
#
# MACRO CONVENTION: VALUE of -DDIGIDOLLAR_EVENT_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/digidollar_event.h"
JNI="${DD_EVENT_GATE_FILE:-$BRIDGE_DIR/jni_transaction.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60

# <case>:<comparison-arm expectation>
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

if [ ! -f "$HEADER" ]; then echo "GATE FAILURE: $HEADER not found."; exit 1; fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+DIGIDOLLAR_EVENT_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of DIGIDOLLAR_EVENT_UNFIXED; the arms would not differ."; exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+DIGIDOLLAR_EVENT_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE."; exit 1
fi

shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
build() {   # <out> <64|32> <unfixed 0|1>
    local out="$1" bits="$2" unfixed="$3"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" \
        -DDIGIDOLLAR_EVENT_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/digidollar_realtx_kat_main.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm \
        -o "$out"
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

    echo "--- COMPARISON ARM (-DDIGIDOLLAR_EVENT_UNFIXED=1): each case meets its expectation ---"
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

    echo "--- SHIPPED ARM (-DDIGIDOLLAR_EVENT_UNFIXED=0): every case passes, no sanitizer report ---"
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
fn_body() {   # $1 = awk regex for the definition line; the definition through its column-0 close, flattened
    awk -v re="$1" '!f && $0 ~ re && $0 !~ /;[[:space:]]*$/ { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
        | tr '\n\t' '  ' | sed -E 's/ +/ /g'
}
grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]+"digidollar_event\.h"' <<<"$STRIPPED" || say "jni_transaction.c does not include digidollar_event.h"
EVENT="$(fn_body '^NativeBridge_digiDollarEvent[[:space:]]*[(]')"
if [ -z "$EVENT" ]; then
    say "NativeBridge_digiDollarEvent not found"
else
    grep -Eq 'dd_event_of[[:space:]]*\(' <<<"$EVENT" || say "digiDollarEvent does not compute the event through dd_event_of"
    grep -Eq 'BRWalletCopyTransactionForHash[[:space:]]*\(' <<<"$EVENT" || say "digiDollarEvent does not read the wallet's transaction from a copy taken under the wallet lock"
    grep -Eq 'NewLongArray[[:space:]]*\(' <<<"$EVENT" || say "digiDollarEvent does not return [kind, cents, change]"
    # The wallet it hands the event: whose outputs, whose spent outputs -- and no DGB movement (4th member NULL).
    INIT="$(grep -oE 'DdEventWallet[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[[:space:]]*\{[^}]*\}' <<<"$EVENT")"
    if [ -z "$INIT" ]; then
        say "digiDollarEvent does not build a DdEventWallet"
    else
        IFS=',' read -r m_ctx m_pays m_spends m_net <<<"$(sed -E 's/.*\{//; s/\}.*//' <<<"$INIT")"
        [ "$(tr -d ' ' <<<"${m_net:-}")" = "NULL" ] || say "digiDollarEvent hands the event a DGB movement to read (${m_net:-missing})"
        for cb in "$m_pays" "$m_spends"; do
            cb="$(tr -d ' ' <<<"$cb")"
            BODY="$(fn_body "^static[^(]*[^A-Za-z0-9_]$cb[[:space:]]*[(]")"
            if [ -z "$BODY" ]; then say "callback $cb is not a file-static function"; continue; fi
            grep -Eq 'BRWalletContainsAddress[[:space:]]*\(' <<<"$BODY" || say "callback $cb does not ask the wallet whose address it is"
        done
        SP_BODY="$(fn_body "^static[^(]*[^A-Za-z0-9_]$(tr -d ' ' <<<"$m_spends")[[:space:]]*[(]")"
        grep -Eq 'BRWalletCopyTransactionForHash[[:space:]]*\(' <<<"$SP_BODY" || say "the spends callback does not look up the output the input spends (from a copy taken under the wallet lock)"
        grep -Eq 'BRDigiDollarOutputAmount[[:space:]]*\(' <<<"$SP_BODY" || say "the spends callback does not read the cents that output held"
    fi
fi
grep -Eq '^NativeBridge_digiDollarTxAmount[[:space:]]*[(]' <<<"$STRIPPED" && say "digiDollarTxAmount (direction from the DGB sign) still exists"
[ "$gate_fail" -eq 0 ] && echo "  gate: digiDollarEvent asks the wallet whose outputs and spent outputs they are and hands the event no DGB movement; digiDollarTxAmount is gone"
[ "$gate_fail" -ne 0 ] && { echo "SOURCE GATE FAILED"; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: digidollar_realtx_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan; source gate over jni_transaction.c)"
    exit 0
fi
echo "FAIL: digidollar_realtx_kat"
exit 1
