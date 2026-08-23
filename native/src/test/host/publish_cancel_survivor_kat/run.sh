#!/usr/bin/env bash
# Host KAT runner: a publish must not be cancelled while another live peer still holds it.
#
# BRPeerManagerPublishTx announces a send to every connected peer, but BRPublishedTx records no
# peer — so _peerDisconnected cancelled EVERY pending publish (and BRTransactionFree'd each)
# whenever any handshook peer timed out, even though the other peers holding the same inv were
# still connected. Measured on an S25 Ultra 2026-08-23: a peer sent getdata 2.2s after an
# unrelated peer's timeout had already freed the transaction.
#
# publish_cancel_survivor_kat_main.c #include-s BOTH BRPeer.c (for the private BRPeerContext —
# there is no public setter for connect status or gotVerack, and both are what decide whether a
# peer counts as a survivor) AND BRPeerManager.c (for _peerDisconnected,
# _BRPeerManagerAddTxToPublishList and the otherwise-opaque BRPeerManagerStruct). Neither is
# ALSO passed on the link line below — every symbol would be defined twice. One file-static name,
# _dummyThreadCleanup, is defined by both; main.c renames BRPeer.c's copy with a preprocessor
# substitution scoped to that #include. Same pattern as cf_checkpoint_quorum_kat.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Both arms compile IDENTICAL assertions — every check() states the intended contract
# unconditionally, so nothing in the test moves between arms.
#   * RED (-DPUBLISH_SURVIVOR_UNFIXED): compiles out the survivor gate, restoring the v4.0.47
#     behaviour where any handshook peer's timeout cancels every pending publish, and restores
#     the silent early-return on a duplicate publish. MUST FAIL.
#   * GREEN: MUST PASS.
#
# The gate asserts BOTH directions. test_last_peer_still_cancels and
# test_only_unhandshook_peers_left demand that cancellation STILL happens when nothing can carry
# the send — a "fix" that merely stopped cancelling would strand every failed send forever and
# must not be able to pass this.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

# LeakSanitizer OFF: this KAT deliberately does not tear the managers down (BRPeerManagerFree
# would drive teardown through synthetic socket-less peers, which is not what is being measured).
# ASan itself stays ON — the use-after-free read in test_survivor_keeps_publish_alive is an
# assertion, not an accident.
export ASAN_OPTIONS=detect_leaks=0

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/publish_cancel_survivor_kat_main.c" \
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

echo "=== building UNFIXED (red arm: survivor gate compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPUBLISH_SURVIVOR_UNFIXED; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed"; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must FAIL ----"
RED_OUT="$BUILD_DIR/red.out"
if "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build PASSED. The survivor gate is the only thing standing"
    echo "between a live peer's publish and a stray timeout; if the test passes without it, the"
    echo "test is not measuring it."
    exit 1
fi
grep -E "^   (PASS|FAIL)|^--|ARM:" "$RED_OUT" | sed 's/^/  red: /'

# Name the exact failure. A red arm that fails for an unrelated reason (a crash, a build quirk)
# proves nothing, and "it exited nonzero" would hide that.
if ! grep -q "FAIL: the publish callback did NOT fire" "$RED_OUT"; then
    echo
    echo "GATE FAILURE: the red arm failed, but NOT on the survivor assertion. Whatever went"
    echo "wrong is not the defect this gate exists to catch."
    exit 1
fi
echo "(red arm failed on the survivor assertion, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "publish_cancel_survivor_kat: RED-BEFORE-GREEN OK"
exit 0
