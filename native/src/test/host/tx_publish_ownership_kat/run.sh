#!/usr/bin/env bash
# Host KAT runner: a published transaction has exactly one owner.
#
# The rule: the wallet's record of a send and the object the peer manager broadcasts are always
# DISTINCT objects, and the publisher may release its object at any time. The send functions in
# jni_transaction.c follow it by registering an independent COPY into the wallet and handing the
# ORIGINAL to the peer manager.
#
# tx_publish_ownership_kat_main.c #include-s BOTH BRPeer.c (for the private BRPeerContext — there
# is no public setter for connect status or gotVerack) AND BRPeerManager.c (for _peerDisconnected,
# _BRPeerManagerAddTxToPublishList and the otherwise-opaque BRPeerManagerStruct). Neither is ALSO
# on the link line below — every symbol would be defined twice. One file-static name,
# _dummyThreadCleanup, is defined by both; main.c renames BRPeer.c's copy with a preprocessor
# substitution scoped to that #include. Same pattern as publish_cancel_survivor_kat.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Macro convention: PRESENCE selects the red arm (green is built with no -D at all), matching the
# sibling publish_cancel_survivor_kat. The main.c tests the flag with #ifdef.
#   * RED (-DPUBLISH_OWNERSHIP_UNFIXED): the single-object shape, which this test rules out — the
#     wallet registers the SAME object the publisher is handed. MUST be reported by
#     AddressSanitizer. The sanitizer stops a run at its first report, so each of the four arms
#     is run ON ITS OWN (argv selects one): every arm has to show it can see the shape.
#   * GREEN: the two-object shape — the wallet registers a COPY, the publisher owns the original.
#     MUST print ALL PASS.
#
# ==== SOURCE GATE ===========================================================
# The arms above only MIRROR the JNI function's shape: jni_transaction.c includes Android headers
# and cannot be compiled on the host. So this runner also greps the real sendDigiDollar and fails
# unless it copies the transaction, registers the copy, publishes the original — in that order —
# and never registers the original into the wallet.
set -uo pipefail   # deliberately NOT -e: the red arm's non-zero exit is expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
JNI="$REPO_ROOT/native/src/main/jni/bridge/jni_transaction.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# LeakSanitizer OFF: the arms deliberately do not tear the managers down (BRPeerManagerFree would
# drive teardown through synthetic socket-less peers). AddressSanitizer itself stays ON: it is
# what tells the two shapes apart, and what checks "released exactly once" in the green arm.
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_publish_ownership_kat_main.c" \
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

# ---------------------------------------------------------------- [1] RED ----
echo "=== red-before-green [1/3]: the single-object shape must be reported by AddressSanitizer, in every arm ==="
if ! build "$BUILD_DIR/single" -DPUBLISH_OWNERSHIP_UNFIXED; then
    echo "GATE FAILED: single-object build error"; exit 1
fi
for arm in 1 2 3 4; do
    # The brace group's own stderr is discarded so the shell's notice about how the child ended
    # stays out of the output; the child's stdout and stderr both land in the log.
    { "$BUILD_DIR/single" "$arm" > "$BUILD_DIR/red.$arm.log" 2>&1; } 2>/dev/null
    RED_EXIT=$?
    if [ "$RED_EXIT" -eq 0 ]; then
        echo "GATE FAILED: arm $arm ran the single-object shape to a clean exit — this arm cannot"
        echo "             tell the two shapes apart."
        sed 's/^/             | /' "$BUILD_DIR/red.$arm.log"
        exit 1
    fi
    # Match the sanitizer report by its stable banner, not a subtype word.
    if ! grep -Eq 'ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/red.$arm.log"; then
        echo "GATE FAILED: arm $arm exited $RED_EXIT on the single-object shape, but not on a"
        echo "             sanitizer report — something else stopped it:"
        sed 's/^/             | /' "$BUILD_DIR/red.$arm.log"
        exit 1
    fi
    echo "RED confirmed, arm $arm — the sanitizer reports the single-object shape:"
    grep -E '^-- \[|ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/red.$arm.log" \
        | head -n 2 | sed 's/^/    | /'
done

# -------------------------------------------------------------- [2] GREEN ----
echo "=== red-before-green [2/3]: the two-object shape must be CLEAN ==="
if ! build "$BUILD_DIR/two"; then
    echo "GATE FAILED: two-object build error"; exit 1
fi
"$BUILD_DIR/two" > "$BUILD_DIR/green.log" 2>&1
GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "GATE FAILED: the two-object shape failed (exit $GREEN_EXIT):"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if ! grep -q "ALL PASS" "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: the two-object shape did not print ALL PASS:"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
echo "GREEN confirmed:"
sed 's/^/    | /' "$BUILD_DIR/green.log"

# --------------------------------------------------------- [3] SOURCE GATE ----
echo "=== [3/3]: the real sendDigiDollar must give the wallet and the publisher distinct objects ==="
if [ ! -f "$JNI" ]; then
    echo "GATE FAILED: $JNI not found — the file was moved or renamed and this check would"
    echo "             otherwise silently observe nothing."
    exit 1
fi

# Slice out just the sendDigiDollar function body (signature line through its column-0 close).
FN="$(awk '/_sendDigiDollar\(/{f=1} f{print} f&&/^}/{exit}' "$JNI")"

# First line (within the slice) matching an extended regex; empty when there is none.
first_line() { grep -nE "$1" <<<"$FN" | head -n 1 | cut -d: -f1; }

gate_fail=0
# The scanner is not blind: the slice must actually contain the function.
if ! grep -q '_sendDigiDollar(' <<<"$FN"; then
    echo "  gate: could not locate sendDigiDollar — the scanner is blind"; gate_fail=1
fi
# It must take a copy of the transaction.
COPY_LINE="$(first_line 'walletCopy[[:space:]]*=[[:space:]]*BRTransactionCopy\([[:space:]]*tx[[:space:]]*\)')"
if [ -z "$COPY_LINE" ]; then
    echo "  gate: sendDigiDollar does not copy the transaction"; gate_fail=1
fi
# It must register the COPY (named walletCopy, as the plain-DGB paths do).
REGISTER_LINE="$(first_line 'BRWalletRegisterTransaction\(g_wallet,[[:space:]]*walletCopy\)')"
if [ -z "$REGISTER_LINE" ]; then
    echo "  gate: sendDigiDollar does not register the copy into the wallet"; gate_fail=1
fi
# It must publish the ORIGINAL.
PUBLISH_LINE="$(first_line 'BRPeerManagerPublishTx\(g_peerManager,[[:space:]]*tx,')"
if [ -z "$PUBLISH_LINE" ]; then
    echo "  gate: sendDigiDollar does not publish the original transaction"; gate_fail=1
fi
# Order: copy, then register the copy, then publish. Once the original has been handed over it
# belongs to the publisher, so the copy must already exist and already be registered.
if [ -n "$COPY_LINE" ] && [ -n "$REGISTER_LINE" ] && [ -n "$PUBLISH_LINE" ]; then
    if ! { [ "$COPY_LINE" -le "$REGISTER_LINE" ] && [ "$REGISTER_LINE" -lt "$PUBLISH_LINE" ]; }; then
        echo "  gate: sendDigiDollar must copy (line +$COPY_LINE), register the copy (line" \
             "+$REGISTER_LINE) and only then publish (line +$PUBLISH_LINE) — the order is wrong"
        gate_fail=1
    fi
fi
# It must NOT register the original into the wallet — that is the single-object shape.
if grep -Eq 'BRWalletRegisterTransaction\(g_wallet,[[:space:]]*tx\)' <<<"$FN"; then
    echo "  gate: sendDigiDollar registers the original into the wallet — the wallet and the"
    echo "        publisher would hold one object"; gate_fail=1
fi
# Nor anything else: every register call in the function registers walletCopy. (A mention of the
# call in a comment counts too — that fails safe.)
REG_ALL="$(grep -cE 'BRWalletRegisterTransaction\(' <<<"$FN")"
REG_COPY="$(grep -cE 'BRWalletRegisterTransaction\(g_wallet,[[:space:]]*walletCopy\)' <<<"$FN")"
if [ "$REG_ALL" != "$REG_COPY" ]; then
    echo "  gate: sendDigiDollar registers something other than walletCopy into the wallet" \
         "($REG_COPY of $REG_ALL register calls name the copy)"; gate_fail=1
fi

if [ "$gate_fail" -ne 0 ]; then
    echo "SOURCE GATE FAILED"
    exit 1
fi
echo "SOURCE GATE confirmed: sendDigiDollar copies, registers the copy, then publishes the original."

echo
echo "tx_publish_ownership_kat: RED-BEFORE-GREEN OK (4/4 arms), source gate OK"
exit 0
