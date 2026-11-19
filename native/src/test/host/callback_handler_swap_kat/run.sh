#!/usr/bin/env bash
# Host KAT for JNI-CB-HANDLER: a native peer callback never calls into a handler reference that
# setCallbackHandler has deleted, and never calls with no handler.
#
# Peer threads outlive a stopSync, so a callback can be running when the next handler is registered. The
# bridge (jni_peer.c, at g_cb) gives each callback its own local reference to the handler, taken under a
# lock that is never held across an upcall, and deletes a replaced handler's global reference only after
# the replacement is in place.
#
# The _main.c #includes the bridge's OWN jni_peer.c and links its jni_wallet.c and the core, over the
# stand-ins in host_jni/ for <jni.h> and <android/log.h>. Its env's references are records that know
# whether they are live, and it can run a step of the test INSIDE a bridge call (while a global reference
# is being deleted; between a callback taking the handler and calling it; inside an upcall), on another
# thread and with a time limit. Every red scenario is an interleaving forced that way; none depends on
# timing.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of the macro selects the comparison arm (the shipped arm is built with no -D at all):
#   -DCALLBACK_HANDLER_UNFIXED   the handler is read in place, unlocked, and deleted before it is replaced,
#                                as the bridge did before.
#   RED-THEN-GREEN  during-delete-swap, during-delete-clear -- the comparison arm fails exactly
#                       "no callback used a handler reference that had been deleted";
#                   clear-mid-callback -- the comparison arm fails exactly
#                       "no callback made an upcall with no handler".
#   GUARD (every arm)  deliver, none, swap-between, swap-mid-callback, swap-during-upcall -- no arm can be
#                   red: the handler does not change at a point where the comparison arm misreads it. They
#                   hold the harness (all ten upcall sites deliver, references balance) and, in the shipped
#                   arm, that no lock is held across an upcall.
# A comparison arm that passes a red scenario, fails another line, or stops for any other reason proves
# nothing and fails the gate. Every run has a time limit. All arms are built 64- and 32-bit and must give
# the same verdicts.
#
# Then, shipped arm only: the free-running `stress` scenario (one thread firing every callback while
# another registers and clears handlers) under ASan at both widths and, where the compiler has it, under
# ThreadSanitizer; and every scenario under the leak checker (64-bit). The comparison arm's stress run is
# printed for information and decides nothing: what it shows depends on timing.
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured
# Under pipefail nothing here pipes into a reader that can exit early: files and here-strings only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
BRIDGE_C=("$BRIDGE_DIR"/*.c)
shopt -u nullglob

gate_fail() { echo "GATE FAILED: $*"; exit 1; }

# Seam and coverage self-checks, on the source the arms are built from.
PEER_C="$BRIDGE_DIR/jni_peer.c"
# A -D that names nothing builds the shipped path, and the comparison arm would (wrongly) be the shipped code.
[ "$(grep -c "^#ifdef CALLBACK_HANDLER_UNFIXED$" "$PEER_C")" -eq 1 ] ||
    gate_fail "CALLBACK_HANDLER_UNFIXED does not select exactly one block in jni_peer.c -- the -D would select nothing."
# The main fires ten upcall sites. Every upcall the bridge makes on a handler is one of them, and takes
# the handler from the callback's own record.
SITES="$(grep -c "CallVoidMethod(" "$PEER_C")"
OWN_REF="$(grep -c "CallVoidMethod(env, CB_OBJ(cb), cb\.on[A-Za-z]*[,)]" "$PEER_C")"
[ "$SITES" -eq 10 ] && [ "$OWN_REF" -eq 10 ] ||
    gate_fail "jni_peer.c has $SITES upcall sites, $OWN_REF of them through CB_OBJ(cb); this suite covers ten, all through it."
for f in "${BRIDGE_C[@]}"; do
    [ "$f" = "$PEER_C" ] || [ "$f" = "$BRIDGE_DIR/jni_registry.c" ] && continue
    if grep -q "Call[A-Za-z]*Method[AV]\?(" "$f"; then
        gate_fail "$(basename "$f") makes a JNI upcall; this suite covers the upcalls of jni_peer.c only."
    fi
done
# The handler's global reference is created and deleted in one place per arm.
[ "$(grep -c "DeleteGlobalRef(" "$PEER_C")" -eq 2 ] && [ "$(grep -c "NewGlobalRef(" "$PEER_C")" -eq 1 ] ||
    gate_fail "jni_peer.c creates or deletes a global reference outside setCallbackHandler/cb_install."

# $1 out, $2 sanitizer (address|thread), rest: extra flags. KAT_BITS=32 builds -m32 (needs gcc-multilib).
build() {
    local out="$1" san="$2"; shift 2
    local m=()
    [ "${KAT_BITS:-64}" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize="$san" -fno-omit-frame-pointer -g \
        -I "$SCRIPT_DIR/host_jni" \
        -I "$BRIDGE_DIR" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/callback_handler_swap_kat_main.c" \
        "$BRIDGE_DIR/jni_wallet.c" \
        "$BRIDGE_DIR/jni_transaction_persist.c" \
        "$BRIDGE_DIR/coin_reservation.c" \
        "$BRIDGE_DIR/slip13.c" \
        "$CORE_DIR/BRPeer.c" \
        "$CORE_DIR/BRPeerManager.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

echo "=== building arms (the bridge and the core under ASan: comparison + shipped, 64- and 32-bit) ==="
for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    KAT_BITS=$bits build "$BUILD_DIR/unfixed$sfx" address -DCALLBACK_HANDLER_UNFIXED ||
        gate_fail "the ${bits}-bit comparison arm did not build (gcc-multilib for 32-bit?)"
    KAT_BITS=$bits build "$BUILD_DIR/shipped$sfx" address || gate_fail "the ${bits}-bit shipped arm did not build"
done

# One scenario in one arm, under a time limit; its output goes to a file named for it.
bounded() {   # $1 binary, $2 scenario, $3 output file, $4 leak checking (0/1)
    ASAN_OPTIONS="detect_leaks=$4:symbolize=0:abort_on_error=0" \
    TSAN_OPTIONS="halt_on_error=0:exitcode=66" timeout 300 "$1" "$2" > "$3" 2>&1
}

DELETED_LINE="FAIL: no callback used a handler reference that had been deleted"
NOHANDLER_LINE="FAIL: no callback made an upcall with no handler"
red_line() {   # $1 scenario: the one line the comparison arm fails
    case "$1" in
        during-delete-swap|during-delete-clear) printf '%s' "$DELETED_LINE" ;;
        clear-mid-callback) printf '%s' "$NOHANDLER_LINE" ;;
    esac
}
RED="during-delete-swap during-delete-clear clear-mid-callback"
GUARD="deliver none swap-between swap-mid-callback swap-during-upcall"

green() {   # $1 label, $2 output file, $3 exit code: counts a failure unless the run is clean and complete
    if [ "$3" -eq 0 ] && grep -q "^ALL PASS$" "$2" && ! grep -q "FAIL:\|Sanitizer" "$2"; then
        echo "  $1: GREEN"
    else
        echo "  $1: FAILED (exit $3)"; sed 's/^/      | /' "$2"; fails=$((fails + 1))
    fi
}

fails=0
for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    for s in $RED; do
        out="$BUILD_DIR/$s.shipped$sfx.txt"
        bounded "$BUILD_DIR/shipped$sfx" "$s" "$out" 0; green "[$bits] $s shipped" "$out" $?

        out="$BUILD_DIR/$s.unfixed$sfx.txt"
        bounded "$BUILD_DIR/unfixed$sfx" "$s" "$out" 0; rc=$?
        want="$(red_line "$s")"
        red_lines="$(grep -c "^   $want\$" "$out")"
        all_fails="$(grep -c "FAIL:" "$out")"
        if [ $rc -eq 1 ] && [ "$red_lines" -eq 1 ] && [ "$all_fails" -eq 1 ] &&
           grep -q "^1 FAILED$" "$out" && ! grep -q "Sanitizer" "$out"; then
            echo "  [$bits] $s comparison arm: RED on exactly \"${want#FAIL: }\""
        else
            echo "  [$bits] $s comparison arm: NOT RED as expected (exit $rc, $red_lines expected line(s), $all_fails FAIL line(s))"
            sed 's/^/      | /' "$out"; fails=$((fails + 1))
        fi
    done
    for s in $GUARD; do
        for arm in shipped unfixed; do
            out="$BUILD_DIR/$s.$arm$sfx.txt"
            bounded "$BUILD_DIR/$arm$sfx" "$s" "$out" 0; green "[$bits] $s $arm (GUARD)" "$out" $?
        done
    done
    out="$BUILD_DIR/stress.shipped$sfx.txt"
    bounded "$BUILD_DIR/shipped$sfx" stress "$out" 0; green "[$bits] stress shipped (ASan)" "$out" $?
    grep "^   upcalls:" "$out" | sed "s/^/      [$bits] shipped/"
done

echo "=== the comparison arm's free-running stress (information only: timing decides what it shows) ==="
bounded "$BUILD_DIR/unfixed" stress "$BUILD_DIR/stress.unfixed.txt" 0
grep "^   upcalls:" "$BUILD_DIR/stress.unfixed.txt" | sed 's/^/      comparison arm/'

echo "=== the shipped arm's stress under ThreadSanitizer (64-bit) ==="
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/tprobe.c"
if clang -fsanitize=thread "$BUILD_DIR/tprobe.c" -o "$BUILD_DIR/tprobe" 2>/dev/null && "$BUILD_DIR/tprobe" >/dev/null 2>&1; then
    build "$BUILD_DIR/shipped_tsan" thread || gate_fail "the ThreadSanitizer arm did not build"
    out="$BUILD_DIR/stress.tsan.txt"
    bounded "$BUILD_DIR/shipped_tsan" stress "$out" 0; green "stress shipped (TSan)" "$out" $?
else
    echo "  [tsan] skipped: ThreadSanitizer is not available here"
fi

echo "=== the shipped arm under the leak checker (64-bit) ==="
for s in $RED $GUARD stress; do
    out="$BUILD_DIR/$s.leaks.txt"
    bounded "$BUILD_DIR/shipped" "$s" "$out" 1; green "$s (leak checker)" "$out" $?
done

if [ "$fails" -ne 0 ]; then echo "callback_handler_swap_kat: $fails failure(s)"; exit 1; fi
echo "callback_handler_swap_kat: all arms as expected"
