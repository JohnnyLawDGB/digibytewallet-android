#!/usr/bin/env bash
# Host KAT: the tip sweep never removes a transaction that carries a token output the wallet owns.
#
# At the chain tip the peer manager's unrelayed-transaction cleanup removes an unconfirmed record no
# connected peer still has, when the wallet has no stake in it. The stake was DGB alone, and a
# DigiDollar output is zero-value by protocol, so a DigiDollar receive saved while pending came back
# at TX_UNCONFIRMED and was removed at the tip -- before the app's confirmation reconcile could
# promote it and release its withheld credit. The stake now also counts an owned token output
# (BRWalletTxHasOwnedTokenOutput: a DigiDollar output bound to an amount slot, or DigiAsset units).
#
# The _main.c #includes BRPeerManager.c and BRPeer.c and drives the REAL path: the wallet's saver,
# the bridge's own JNI-free loader, BRWalletNewDual + BRWalletSetTaprootKey, the completion's
# mempool step in compact-filter mode, _postSyncDone, the getdata/ping of the cleanup, BRPeer.c's own
# notfound / tx / pong handlers, the cleanup, and the reconcile's promotion. Neither .c is on the link
# line below -- every symbol would be defined twice. Same pattern as publish_list_ownership_kat.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of the macro selects the comparison arm (the shipped arm is built with no -D at all).
#   RED-THEN-GREEN (assertion)  dd_receive, asset_zero_carrier: the comparison arm must FAIL its own
#                               checks (nothing for a sanitizer to see: the record is removed cleanly),
#                               only the invariant's, with its control passing in the same pass; the
#                               shipped arm must pass.
#   GUARD (both arms)           asset_standard_carrier: a 700-dsat carrier has DGB stake, so no arm can
#                               be red; gates: the cleanup's own early returns (below the tip, relayed).
#                               Both arms must pass.
# A comparison arm that passes, crashes, or reports through a sanitizer proves nothing: each is a
# failure here. Every arm runs under a time limit. Then every scenario runs on the shipped arm under
# the leak checker, and both arms are built -m32 and must give the same verdicts there.
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

gate_fail() { echo "GATE FAILED: $*"; exit 1; }

# Seam self-check: a -D that names nothing builds the shipped path, and the comparison arm would
# (wrongly) be the shipped code.
grep -q "TIPSWEEP_TOKEN_STAKE_UNFIXED" "$CORE_DIR/BRPeerManager.c" ||
    gate_fail "TIPSWEEP_TOKEN_STAKE_UNFIXED is not present in BRPeerManager.c -- the -D would select nothing."

# Source gates: the three bridge steps the _main.c reproduces but cannot compile (JNI). Needles are
# matched with white space collapsed, so a re-indentation does not move a gate.
flat() { tr -s '[:space:]' ' ' < "$1"; }
fn_body() { awk -v start="$2" '$0 ~ start {on=1} on {print} on && /^\}/ {exit}' "$1" | tr -s '[:space:]' ' '; }

# 1. The relaunch (restoreWalletFromBytes, the restore from disk) builds the wallet from the saved records
#    first and installs the taproot key after, which is the order openWallet() uses.
RESTORE="$(fn_body "$BRIDGE_DIR/jni_wallet.c" '^NativeBridge_restoreWalletFromBytes')"
[ -n "$RESTORE" ] || gate_fail "restoreWalletFromBytes was not found in jni_wallet.c. Re-anchor the gate."
AFTER_NEW="${RESTORE#*BRWalletNewDual(g_savedTransactions, g_savedTransactionCount, mpkBIP84, mpkLegacy)}"
[ "$AFTER_NEW" != "$RESTORE" ] ||
    gate_fail "restoreWalletFromBytes no longer builds the wallet with BRWalletNewDual over g_savedTransactions."
<<<"$AFTER_NEW" grep -qF "BRWalletSetTaprootKey(g_wallet, mpkBIP86)" ||
    gate_fail "restoreWalletFromBytes no longer installs the taproot key after the saved records are loaded."

# 2. The saved records are written by BRWalletSerializeTransactions and read back by the guarded
#    loader this suite compiles directly.
PERSIST="$(flat "$BRIDGE_DIR/jni_transaction_persist.c")"
for needle in "BRWalletSerializeTransactions(g_wallet, buf, need)" \
              "deserialize_saved_transactions_guarded((const uint8_t *)bytes, (size_t)len, &g_savedTransactions)"; do
    <<<"$PERSIST" grep -qF "$needle" ||
        gate_fail "jni_transaction_persist.c no longer contains: $needle"
done

# 3. The confirmation reconcile promotes a record only while it is still at TX_UNCONFIRMED, with the
#    unproven-height update -- what confirmLikeBridge() does.
CONFIRM="$(fn_body "$BRIDGE_DIR/jni_transaction.c" '^NativeBridge_confirmTransaction')"
[ -n "$CONFIRM" ] || gate_fail "confirmTransaction was not found in jni_transaction.c. Re-anchor the gate."
#    The height is read from a copy the wallet makes under its lock (tx_read_snapshot_kat).
for needle in "BRTransaction *existing = BRWalletCopyTransactionForHash(g_wallet, h);" \
              "int pending = existing && existing->blockHeight == TX_UNCONFIRMED;" \
              "if (pending) {" \
              "BRWalletUpdateTransactionsUnproven(g_wallet, &h, 1, (uint32_t)blockHeight, (uint32_t)blockTimestamp);"; do
    <<<"$CONFIRM" grep -qF "$needle" ||
        gate_fail "confirmTransaction no longer contains: $needle"
done

# $1 out, rest: extra flags. KAT_BITS=32 builds -m32 (needs gcc-multilib).
build() {
    local out="$1"; shift
    local m=()
    [ "${KAT_BITS:-64}" = "32" ] && m=(-m32)
    clang -w -include stdint.h ${m[@]+"${m[@]}"} "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$BRIDGE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tipsweep_token_stake_kat_main.c" \
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

echo "=== building arms (BRPeerManager.c under ASan, about 15 s each: comparison + shipped, 64- and 32-bit) ==="
build "$BUILD_DIR/comparison" -DTIPSWEEP_TOKEN_STAKE_UNFIXED || gate_fail "the comparison arm did not build"
build "$BUILD_DIR/shipped"                                   || gate_fail "the shipped arm did not build"
KAT_BITS=32 build "$BUILD_DIR/comparison32" -DTIPSWEEP_TOKEN_STAKE_UNFIXED ||
    gate_fail "the 32-bit comparison arm did not build (gcc-multilib?)"
KAT_BITS=32 build "$BUILD_DIR/shipped32" || gate_fail "the 32-bit shipped arm did not build (gcc-multilib?)"

ASAN_OFF="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export LSAN_OPTIONS="exitcode=23"
BANNER="ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:"
LIMIT_S=60

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; drop it there, and stop the watchdog with a
    # signal that runs no trap, so $BUILD_DIR survives for the other arms.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

# must_pass <bin> <armname> <scenario> <asan options>
must_pass() {
    local bin="$1" arm="$2" scenario="$3" opts="$4" log="$BUILD_DIR/$2.$3.log" rc
    ASAN_OPTIONS="$opts" bounded "$log" "$bin" "$scenario"; rc=$?
    if [ "$rc" -ne 0 ] || grep -Eq "$BANNER" "$log" || ! grep -q "ALL PASS" "$log"; then
        echo "GATE FAILED: the $arm arm did not pass scenario=$scenario cleanly (exit $rc):"
        sed 's/^/   | /' "$log"; exit 1
    fi
}

# must_fail_checks <bin> <armname> <scenario>: the arm fails the scenario's own checks -- not a crash,
# not a time-out, not a sanitizer report -- and ONLY the invariant's: its control passes (the cleanup ran
# with every gate open), so what is red is the removal itself, never a fixture that stopped working.
INVARIANT_FAILS="INVARIANT:|the confirmation reconcile finds the record still pending"
must_fail_checks() {
    local bin="$1" arm="$2" scenario="$3" log="$BUILD_DIR/$2.$3.log" rc other
    ASAN_OPTIONS="$ASAN_OFF" bounded "$log" "$bin" "$scenario"; rc=$?
    if [ "$rc" -eq 0 ] || grep -q "ALL PASS" "$log"; then
        echo "GATE FAILED: the $arm arm passed scenario=$scenario -- it cannot see the shape."
        sed 's/^/   | /' "$log"; exit 1
    fi
    if [ "$rc" -ne 1 ] || grep -Eq "$BANNER" "$log" || ! grep -q "^   FAIL:" "$log"; then
        echo "GATE FAILED: the $arm arm stopped for another reason on scenario=$scenario (exit $rc), which does not count."
        sed 's/^/   | /' "$log"; exit 1
    fi
    if ! grep -q "^   PASS: control:" "$log"; then
        echo "GATE FAILED: the $arm arm's control did not pass on scenario=$scenario -- the cleanup may not have run."
        sed 's/^/   | /' "$log"; exit 1
    fi
    other="$(grep "^   FAIL:" "$log" | grep -Ev "$INVARIANT_FAILS")"
    if [ -n "$other" ]; then
        echo "GATE FAILED: the $arm arm failed a check other than the invariant's on scenario=$scenario:"
        printf '%s\n' "$other" | sed 's/^/   | /'; exit 1
    fi
    grep -E "^   (FAIL:|PASS: control:)" "$log" | sed "s/^   /   $arm: /"
}

RED="dd_receive asset_zero_carrier"
GUARD="asset_standard_carrier gates"

for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    for scenario in $RED; do
        echo; echo "---- [RED-THEN-GREEN, assertion, ${bits}-bit] scenario=$scenario : comparison must fail its checks, shipped must pass ----"
        must_fail_checks "$BUILD_DIR/comparison$sfx" "comparison$sfx" "$scenario"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        grep -E "INVARIANT|control:" "$BUILD_DIR/shipped$sfx.$scenario.log" | sed "s/^   /   shipped$sfx: /"
    done
    for scenario in $GUARD; do
        echo; echo "---- [GUARD, both arms, ${bits}-bit] scenario=$scenario : both arms must pass ----"
        must_pass "$BUILD_DIR/comparison$sfx" "comparison$sfx" "$scenario" "$ASAN_OFF"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   comparison$sfx and shipped$sfx: ALL PASS"
    done
done

# The shipped arm under the leak checker (64-bit; LeakSanitizer is not available on i386): every
# record the cleanup releases, keeps or promotes has exactly one owner, and every queued callback
# context is answered.
echo
echo "---- [LEAK CHECK] every scenario on the shipped arm under detect_leaks=1 must be clean ----"
for scenario in $RED $GUARD; do
    must_pass "$BUILD_DIR/shipped" "shipped" "$scenario" "$ASAN_LEAK"
    echo "   leak-clean: $scenario"
done

echo
echo "tipsweep_token_stake_kat: RED-BEFORE-GREEN OK (2 assertion comparison-arm scenarios, 2 guards in both arms, 64- and 32-bit), leak check OK (4 scenarios), source gates OK"
exit 0
