#!/usr/bin/env bash
# Host KAT runner: no single peer's message removes a transaction or unlocks its coins.
# See reject_quorum_kat_main.c for the invariant and the cases.
#
# Builds the real BRPeerManager.c (+ BRPeer.c, both #included by the main) against the live core,
# with AddressSanitizer, in a 64-bit and a 32-bit (-m32) build, in three arms:
#   comparison  -DREJECT_QUORUM_UNFIXED: one peer's terminal rejection counts as the network's.
#   comparison  -DRELAY_EVIDENCE_UNFIXED: only the peers relaying it now count as a peer having it, so
#               the evidence lapses when they disconnect, answer notfound or reject, and the relay
#               record outlives the wallet's removal of the transaction.
#               In each comparison arm every case meets the expectation CASES gives it for that arm:
#                 fail  a failed check, no sanitizer report: the arm calls a send refused that it must not
#                 pass  a guard: holds in that arm as in the shipped one
#   shipped     no -D: every case passes, no sanitizer report; on 64-bit also leak-clean.
# Every run is time-limited (a sanitizer report can stall instead of aborting).
#
# A source gate follows: the bridge's isRefusedByNetwork must answer from
# BRPeerManagerTxRefusedByNetwork under the peer guard, and nothing else.
# REJECT_QUORUM_GATE_FILE=<path> points it at another copy of jni_transaction.c.
#
# MACRO CONVENTION: PRESENCE of -DREJECT_QUORUM_UNFIXED or -DRELAY_EVIDENCE_UNFIXED selects a comparison
# arm (#ifdef), as BRPeerManager.c's other seams do; the shipped arm is built with no -D at all.
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
JNI="${REJECT_QUORUM_GATE_FILE:-$REPO_ROOT/native/src/main/jni/bridge/jni_transaction.c}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
ASAN_OFF="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export LSAN_OPTIONS="exitcode=23"
LIMIT_S=60

# The comparison arms, in the order of the expectation columns below.
ARMS=(REJECT_QUORUM_UNFIXED RELAY_EVIDENCE_UNFIXED)
# <case>:<REJECT_QUORUM_UNFIXED expectation>:<RELAY_EVIDENCE_UNFIXED expectation>
CASES=(
    one_peer_rejects:fail:pass
    one_peer_rejects_repeatedly:fail:pass
    two_peers_one_group:fail:pass
    two_groups_no_relay:pass:pass
    two_groups_then_relayed:fail:pass
    announced_then_two_groups:fail:pass
    rejecter_later_has_it:fail:pass
    ipv6_one_group:fail:pass
    ipv6_two_groups:pass:pass
    policy_rejects_only:pass:pass
    unknown_hash_unrecorded:pass:pass
    publish_answer_unchanged:pass:pass
    confirmed_forgets:pass:pass
    removed_forgets:pass:pass
    relayed_then_disconnected:fail:fail
    requested_then_disconnected:fail:fail
    announced_then_notfound:fail:fail
    relayer_then_rejects:fail:fail
    rejecter_later_has_it_then_leaves:fail:fail
    confirmed_drops_relay_record:pass:pass
    removed_drops_relay_record:pass:fail
)

for arm in "${ARMS[@]}"; do
    if ! grep -q "$arm" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILURE: BRPeerManager.c has no $arm seam; the -D would select nothing."
        exit 1
    fi
done

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRMerkleBlock.c
       "$CORE_DIR"/BRCompactFilterChain.c "$CORE_DIR"/BRGCSFilter.c "$CORE_DIR"/BRWalletFilterElements.c
       "$CORE_DIR"/BRCFScanLedger.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRDigiDollar.c "$CORE_DIR"/BRDigiAsset.c
       "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRAddress.c "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c
       "$CORE_DIR"/BRCrypto.c "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c
       "$CORE_DIR"/crypto/groestl.c "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c
       "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

build() {   # <out> <64|32> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/reject_quorum_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <asan-options> <cmd...>: the command's exit code, or 124 if killed at the limit.
bounded() {
    local outfile="$1" opts="$2"; shift 2
    ASAN_OPTIONS="$opts" "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

show() { sed -n "1,${2:-10}p" "$1" | sed 's/^/      /'; }

FAIL=0
for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    built=1
    for arm in "${ARMS[@]}"; do
        if ! build "$BUILD_DIR/$arm$bits" "$bits" "-D$arm"; then
            echo "GATE FAILURE: ${bits}-bit comparison arm -D$arm did not compile (a build error is not evidence)."
            built=0
        fi
    done
    [ "$built" -eq 1 ] || { FAIL=1; continue; }
    if ! build "$BUILD_DIR/green$bits" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; continue
    fi
    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; continue
    fi

    for k in "${!ARMS[@]}"; do
        arm="${ARMS[$k]}"; tag="$arm$bits"
        echo "--- COMPARISON ARM (-D$arm): each case meets its expectation ---"
        for entry in "${CASES[@]}"; do
            IFS=: read -r -a cols <<<"$entry"
            c="${cols[0]}"; want="${cols[$((k + 1))]:-}"; out="$BUILD_DIR/$tag.$c.out"
            bounded "$out" "$ASAN_OFF" "$BUILD_DIR/$tag" "$c"; rc=$?
            if [ $rc -eq 124 ]; then echo "  [$tag $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
            if grep -Eq "$SAN_RE" "$out"; then echo "  [$tag $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
            case "$want" in
            fail)
                if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                    echo "  [$tag $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-150)"
                else
                    echo "  [$tag $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"
                    show "$out"; FAIL=1
                fi ;;
            pass)
                if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
                    echo "  [$tag $c] passed (guard)"
                else
                    echo "  [$tag $c] GATE FAILURE: the guard did not hold in the comparison arm (rc=$rc)"; show "$out" 12; FAIL=1
                fi ;;
            *) echo "  [$tag $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
            esac
        done
    done

    echo "--- SHIPPED ARM: every case passes, no sanitizer report$([ "$bits" = 64 ] && printf ', leak-clean') ---"
    opts="$ASAN_OFF"; [ "$bits" = "64" ] && opts="$ASAN_LEAK"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$opts" "$BUILD_DIR/green$bits" "$c"; rc=$?
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
if [ ! -f "$JNI" ]; then echo "GATE FAILURE: $JNI not found -- the gate would observe nothing."; exit 1; fi
STRIPPED="$(sed -e 's@//.*$@@' "$JNI")"   # read whole, then cut from a here-string: no pipe into an early exit
FN="$(awk '/^NativeBridge_isRefusedByNetwork[[:space:]]*[(]/ { f=1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED" | tr '\n\t' '  ' | sed -E 's/ +/ /g')"
gate=0
if [ -z "$FN" ]; then
    echo "  gate: NativeBridge_isRefusedByNetwork not found"; gate=1
else
    grep -Eq 'PEER_GUARD[[:space:]]*\([[:space:]]*\)' <<<"$FN" || { echo "  gate: isRefusedByNetwork does not hold the peer guard"; gate=1; }
    grep -Eq 'BRPeerManagerTxRefusedByNetwork[[:space:]]*\([[:space:]]*g_peerManager' <<<"$FN" || { echo "  gate: isRefusedByNetwork does not answer from BRPeerManagerTxRefusedByNetwork"; gate=1; }
    for never in 'BRPeerManagerRemoveTransaction' 'BRWalletRemoveTransaction' 'getPublishResult' 'g_publishResults'; do
        grep -Eq -- "$never" <<<"$FN" && { echo "  gate: isRefusedByNetwork reads or does more than the core's answer ($never)"; gate=1; }
    done
    [ "$gate" -eq 0 ] && echo "  gate: isRefusedByNetwork answers from BRPeerManagerTxRefusedByNetwork under the peer guard, and does nothing else"
fi
[ "$gate" -ne 0 ] && { echo "SOURCE GATE FAILED"; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: reject_quorum_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms as expected, shipped arm clean; 64-bit and 32-bit, ASan, 64-bit leak-clean; source gate over jni_transaction.c)"
    exit 0
fi
echo "FAIL: reject_quorum_kat"
exit 1
