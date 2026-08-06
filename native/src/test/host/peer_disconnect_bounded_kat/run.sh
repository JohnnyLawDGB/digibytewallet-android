#!/usr/bin/env bash
# Host KAT runner for the UNBOUNDED BRPeerManagerDisconnect wait.
#
# The wait for peer/dns threads to exit had no exit condition and span on a ONE-NANOSECOND
# nanosleep. startSync calls it while holding PEER_GUARD -- the global JNI mutex every bridge
# entry point needs -- so a peer thread that never decrements peerThreadCount wedged the ENTIRE
# wallet. Measured 2026-08-06: PEER_GUARD held 308s and climbing, peers still relaying blocks,
# 93 of 103 threads queued, CF ledger never persisted (costing 333,701 abandoned blocks on the
# next launch).
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DDISCONNECT_WAIT_UNBOUNDED): the deadline is compiled out, so this HANGS. The red
#     signal is the timeout below -- a hang IS the defect, so here (unlike every other gate in
#     this suite) a timeout is the correct evidence rather than a disqualifying one.
#   * FIXED: returns near PEER_DISCONNECT_WAIT_SECS and says it gave up. MUST PASS.
#
# The green arm asserts BOTH bounds: it must return, AND it must actually have waited. An instant
# return would mean the loop never executed, which would prove nothing about the deadline.
set -u

export ASAN_OPTIONS=detect_leaks=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)


build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_disconnect_bounded_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
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

# LeakSanitizer OFF for both arms. This KAT deliberately does not tear the manager down, so LSan
# reports ~900 KB and exits nonzero on its own — which masks the result completely: the red arm
# "failed" for the wrong reason and the gate correctly rejected it. Leaks are not what this gate
# measures.
export ASAN_OPTIONS=detect_leaks=0

echo "=== building UNFIXED (red arm: checkpoint-identity guard compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DDISCONNECT_WAIT_UNBOUNDED; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed"; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must HANG (the defect IS the hang) ----"
RED_OUT="$BUILD_DIR/red.out"
timeout 25 "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1
rc=$?
if [ "$rc" -ne 124 ]; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build exited (rc=$rc) instead of hanging. The unbounded wait"
    echo "is what this gate exists to catch; if it terminates, the seam is inert."
    exit 1
fi
grep -E "pinned at 1|ARM:" "$RED_OUT" | sed 's/^/  red: /'
echo "(red arm hung and was killed at 25s, as required)"

echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "peer_disconnect_bounded_kat: RED-BEFORE-GREEN OK"
exit 0
