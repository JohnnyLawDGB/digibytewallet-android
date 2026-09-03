#!/usr/bin/env bash
# Host KAT runner for BRPeerManagerFree under a still-running peer thread.
#
# Peer threads are detached; BRPeerManagerDisconnect's wait is bounded and can give up with threads
# still in dispatch. BRPeerManagerFree then destroyed the manager -- and every peer's pongLock --
# underneath them. Galaxy Ultra, v4.0.77, 2026-09-01: two "pthread_mutex_lock called on a destroyed
# mutex" in the same millisecond, SIGABRT in the peer thread's own pong drain.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DPEER_FREE_UNDEFERRED): Free ignores the live-thread count and tears down at once.
#     The thread's _peerThreadCleanup then locks freed memory: ASan heap-use-after-free. MUST FAIL
#     with exactly that report.
#   * FIXED: Free parks the manager (returns 0); the last thread out frees it. MUST PASS -- and with
#     LeakSanitizer ON, because a parked manager that nobody frees is the failure mode the green arm
#     has to rule out. This is the one peer KAT that keeps leak detection enabled.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

# The seam must exist in the core, or the red arm silently becomes a second green arm.
if ! grep -q '^#ifdef PEER_FREE_UNDEFERRED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: PEER_FREE_UNDEFERRED seam not found in BRPeerManager.c"; exit 1
fi

build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_free_live_thread_kat_main.c" \
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

echo "=== building UNFIXED (red arm: live-thread check compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPEER_FREE_UNDEFERRED; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed"; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must die with heap-use-after-free ----"
RED_OUT="$BUILD_DIR/red.out"
ASAN_OPTIONS=detect_leaks=0 timeout 60 "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1
rc=$?
if [ "$rc" -eq 0 ] || ! grep -q "ERROR: AddressSanitizer: heap-use-after-free" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build did not produce heap-use-after-free (rc=$rc). The seam"
    echo "is inert or the harness no longer exercises _peerThreadCleanup after the free."
    exit 1
fi
grep -E "ARM:|ERROR: AddressSanitizer|_peerThreadCleanup" "$RED_OUT" | head -5 | sed 's/^/  red: /'
echo "(red arm crashed with heap-use-after-free, as required)"

echo "---- GREEN ARM: must PASS with LeakSanitizer ON ----"
if ! ASAN_OPTIONS=detect_leaks=1 timeout 60 "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass (a LeakSanitizer report here means the"
    echo "parked manager was never freed by the last thread out)."
    exit 1
fi

echo
echo "peer_free_live_thread_kat: RED-BEFORE-GREEN OK"
exit 0
