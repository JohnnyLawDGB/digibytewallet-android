#!/usr/bin/env bash
# Host KAT runner: a DigiDollar send's confirmation shows the fee of the transfer that is signed, and
# only the approved transfer is signed. See dd_send_preview_kat_main.c for the invariant and the cases.
#
# Compiles the bridge's digidollar_send.h (the JNI-free core of previewDigiDollar and
# sendApprovedDigiDollar) against the live core sources, with AddressSanitizer, in a 64-bit and a
# 32-bit (-m32) build, in two arms:
#   comparison  -DDIGIDOLLAR_SEND_UNFIXED=1: the behaviour before (the 0.1 DGB floor shown whatever the
#               transfer pays, the transfer signed as built). Each case meets the expectation CASES gives it:
#                 fail  a failed check and no sanitizer report: the arm cannot keep the invariant
#                 pass  a guard, which holds in both arms
#   shipped     -DDIGIDOLLAR_SEND_UNFIXED=0: every case passes, with no sanitizer report.
# Every run is time-limited (a sanitizer report can stall instead of aborting).
#
# Gates first: the header must carry the seam and test its VALUE (#if, never #ifdef). Last, a SOURCE
# GATE over the real jni_transaction.c (Android headers keep it off the host): previewDigiDollar builds
# the transfer, previews it through this header and frees it, signing, registering and publishing
# nothing; sendApprovedDigiDollar is handed the approved fee and checks the rebuilt transfer against it
# before signing and again after, then registers its copy and publishes; and no other bridge function
# builds a DigiDollar transfer (the one-step build-sign-publish that checked nothing is gone).
# DD_SEND_GATE_FILE=<path> points the source gate at another copy (used to show it refuses one).
#
# MACRO CONVENTION: VALUE of -DDIGIDOLLAR_SEND_UNFIXED (=1 comparison, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# the writer would die of SIGPIPE and pipefail would read a match as a miss. Here-strings and
# files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/digidollar_send.h"
JNI="${DD_SEND_GATE_FILE:-$BRIDGE_DIR/jni_transaction.c}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=60

# <case>:<comparison-arm expectation>
CASES=(
    preview_fee_is_built_fee:fail
    preview_reports_what_comes_back:fail
    preview_at_the_floor:pass
    preview_unknown_parent:fail
    check_approved_signs:pass
    check_fee_changed:fail
    check_fee_lower:fail
    check_recipient_changed:fail
    check_amount_changed:fail
    check_dd_change_foreign:fail
    check_dgb_change_foreign:fail
    check_self_send_signs:pass
    check_after_signing:pass
    dd_send_builds:pass
)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+!?[[:space:]]*DIGIDOLLAR_SEND_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of DIGIDOLLAR_SEND_UNFIXED; the arms would not differ."
    exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+DIGIDOLLAR_SEND_UNFIXED' "$HEADER"; then
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
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} \
        -DDIGIDOLLAR_SEND_UNFIXED="$unfixed" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/dd_send_preview_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
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

    echo "--- COMPARISON ARM (-DDIGIDOLLAR_SEND_UNFIXED=1): each case meets its expectation ---"
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

    echo "--- SHIPPED ARM (-DDIGIDOLLAR_SEND_UNFIXED=0): every case passes, no sanitizer report ---"
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
echo "======================================================================"
echo "=== source gate: $JNI ==="
echo "======================================================================"
gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
if [ ! -f "$JNI" ]; then
    echo "GATE FAILURE: $JNI not found -- the gate would observe nothing."; exit 1
fi
# Comments dropped, so a mention in prose is never read as code.
strip_comments() {
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
STRIPPED="$(strip_comments "$JNI")"
fn_body() {   # the definition of $1 through its column-0 close, flattened to one line
    awk -v re="^NativeBridge_$1[[:space:]]*[(]" '!f && $0 ~ re { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" \
        | tr '\n\t' '  ' | sed -E 's/ +/ /g'
}
offsets() { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }   # byte offset of every match

if ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]+"digidollar_send\.h"' <<<"$STRIPPED"; then
    say "jni_transaction.c does not include digidollar_send.h"
fi

PREVIEW="$(fn_body previewDigiDollar)"
if [ -z "$PREVIEW" ]; then
    say "NativeBridge_previewDigiDollar not found"
else
    before=$gate_fail; gate_fail=0
    for need in 'BRWalletCreateDigiDollarTransfer[[:space:]]*\(' 'dd_send_preview[[:space:]]*\(' 'BRTransactionFree[[:space:]]*\(' 'NewLongArray[[:space:]]*\('; do
        grep -Eq -- "$need" <<<"$PREVIEW" || say "previewDigiDollar does not call ${need%%\[*}"
    done
    BUILD_AT="$(offsets 'BRWalletCreateDigiDollarTransfer[[:space:]]*\(' "$PREVIEW" | sed -n 1p)"
    PREV_AT="$(offsets 'dd_send_preview[[:space:]]*\(' "$PREVIEW" | sed -n 1p)"
    FREE_AT="$(offsets 'BRTransactionFree[[:space:]]*\(' "$PREVIEW" | tail -n 1)"
    if [ -n "$BUILD_AT" ] && [ -n "$PREV_AT" ] && [ -n "$FREE_AT" ]; then
        [ "$BUILD_AT" -lt "$PREV_AT" ] || say "previewDigiDollar previews before it builds"
        [ "$PREV_AT" -lt "$FREE_AT" ] || say "previewDigiDollar frees the transfer before it previews it"
    fi
    for never in 'BRPeerManager(Publish|StemPublish)Tx' '_registerWalletCopy' 'BRWalletRegisterTransaction' 'seed_sign_transaction' 'BRWalletSignTransaction'; do
        grep -Eq -- "$never" <<<"$PREVIEW" && say "previewDigiDollar calls ${never} -- a preview signs, keeps and publishes nothing"
    done
    [ "$gate_fail" -eq 0 ] && echo "  gate: previewDigiDollar builds the transfer, previews it through dd_send_preview and frees it; it signs, keeps and publishes nothing"
    gate_fail=$(( before || gate_fail ))
fi

SEND="$(fn_body sendApprovedDigiDollar)"
if [ -z "$SEND" ]; then
    say "NativeBridge_sendApprovedDigiDollar not found"
else
    before=$gate_fail; gate_fail=0
    grep -Eq 'jlong[[:space:]]+approvedFeeSats' <<<"$SEND" || say "sendApprovedDigiDollar is not handed the approved fee"
    grep -Eq 'approvedFeeSats[[:space:]]*<=[[:space:]]*0' <<<"$SEND" || say "sendApprovedDigiDollar does not refuse an approval of no fee"
    grep -Eq 'seed_is_valid[[:space:]]*\(' <<<"$SEND" || say "sendApprovedDigiDollar does not refuse a locked session"
    BUILDS=($(offsets 'BRWalletCreateDigiDollarTransfer[[:space:]]*\(' "$SEND"))
    CHECKS=($(offsets 'dd_send_check[[:space:]]*\(' "$SEND"))
    SIGNS=($(offsets 'seed_sign_transaction[[:space:]]*\(' "$SEND"))
    REGS=($(offsets '_registerWalletCopy[[:space:]]*\(' "$SEND"))
    PUBS=($(offsets 'BRPeerManager(Publish|StemPublish)Tx[[:space:]]*\(' "$SEND"))
    [ "${#BUILDS[@]}" -eq 1 ] || say "sendApprovedDigiDollar must build the transfer exactly once (found ${#BUILDS[@]})"
    [ "${#CHECKS[@]}" -eq 2 ] || say "sendApprovedDigiDollar must check exactly twice, before and after signing (found ${#CHECKS[@]})"
    [ "${#SIGNS[@]}" -eq 1 ] || say "sendApprovedDigiDollar must sign exactly once (found ${#SIGNS[@]})"
    [ "${#REGS[@]}" -eq 1 ] && [ "${#PUBS[@]}" -eq 1 ] || say "sendApprovedDigiDollar must register its copy once and publish once (found ${#REGS[@]}, ${#PUBS[@]})"
    if [ "${#BUILDS[@]}" -eq 1 ] && [ "${#CHECKS[@]}" -eq 2 ] && [ "${#SIGNS[@]}" -eq 1 ] && [ "${#REGS[@]}" -eq 1 ] && [ "${#PUBS[@]}" -eq 1 ]; then
        [ "${BUILDS[0]}" -lt "${CHECKS[0]}" ] || say "the first check does not follow the build"
        [ "${CHECKS[0]}" -lt "${SIGNS[0]}" ] || say "the first check does not come before the signature"
        [ "${CHECKS[1]}" -gt "${SIGNS[0]}" ] || say "the second check does not come after the signature"
        [ "${REGS[0]}" -gt "${CHECKS[1]}" ] || say "the wallet's copy is registered before the second check"
        [ "${PUBS[0]}" -gt "${CHECKS[1]}" ] || say "the transfer is published before the second check"
    fi
    REFUSE='dd_send_check[[:space:]]*\([^;]*approvedFeeSats[^;]*\)[[:space:]]*\)?[[:space:]]*!=[[:space:]]*DD_SEND_OK'
    n_refuse="$(grep -oE -- "$REFUSE" <<<"$SEND" | wc -l | tr -d ' ')"
    [ "$n_refuse" -eq 2 ] || say "each check must be handed the approved fee and refuse on a verdict other than DD_SEND_OK (found $n_refuse)"
    [ "$gate_fail" -eq 0 ] && echo "  gate: sendApprovedDigiDollar rebuilds once, checks against the approved fee before signing, signs once, checks again, then registers its copy and publishes"
    gate_fail=$(( before || gate_fail ))
fi

# No other bridge function builds a DigiDollar transfer: the one-step build-sign-publish is gone.
for f in "$BRIDGE_DIR"/*.c; do
    S="$(strip_comments "$f")"
    while IFS= read -r fn; do
        [ -z "$fn" ] && continue
        case "$fn" in
            NativeBridge_previewDigiDollar|NativeBridge_sendApprovedDigiDollar) ;;
            *) say "$(basename "$f"): $fn builds a DigiDollar transfer outside the preview and the approved send" ;;
        esac
    done <<<"$(awk '/^[A-Za-z_][A-Za-z0-9_ *]*[(]/ && !/;[[:space:]]*$/ { if (match($0, /NativeBridge_[A-Za-z0-9_]+|^[A-Za-z_][A-Za-z0-9_]*[ *]+[A-Za-z_][A-Za-z0-9_]*[(]/)) cur = substr($0, RSTART, RLENGTH) }
                 /BRWalletCreateDigiDollarTransfer[[:space:]]*[(]/ { print cur }' <<<"$S" | sed -E 's/[(]$//; s/.*[ *]//' | sort -u)"
done
if grep -Eq '^NativeBridge_sendDigiDollar[[:space:]]*[(]' <<<"$STRIPPED"; then
    say "NativeBridge_sendDigiDollar still exists: a DigiDollar transfer signed with no approved fee"
fi

if [ "$gate_fail" -ne 0 ]; then
    echo "SOURCE GATE FAILED"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: dd_send_preview_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan; source gate over jni_transaction.c)"
    exit 0
fi
echo "FAIL: dd_send_preview_kat"
exit 1
