#!/usr/bin/env bash
# Host KAT runner for late_peer_callback_kat: a callback queued on a peer after its thread's own
# disconnect drain is answered once, with success 0, before _peerDisconnected frees the peer (see
# the header of late_peer_callback_kat_main.c).
#
# main.c #includes the REAL submodule BRPeer.c and BRPeerManager.c (same shape and link line as
# download_peer_promote_kat), so neither is on the link line below.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Both arms compile IDENTICAL assertions.
#   * RED (-DPEER_LATE_CALLBACK_UNFIXED): the answer in _peerDisconnected is compiled out, so the
#     late callback is dropped with the peer. MUST FAIL, on "the late pong was answered",
#     "the late mempool request was answered" and "the publish's pong was answered".
#   * GREEN: MUST PASS.
# LeakSanitizer stays on where the platform has it (Linux): the publish scenario's lost context is
# a leak and nothing else, and this suite frees everything it allocates.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h reasons as bip340_kat.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/late_peer_callback_kat_main.c" \
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

echo "=== building UNFIXED (red arm: late-callback answer compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPEER_LATE_CALLBACK_UNFIXED; then
    echo "BUILD FAILURE (unfixed arm) -- gate cannot run"; exit 1
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
    echo "GATE FAILURE: the UNFIXED build PASSED, so the test does not measure the late-callback answer."
    exit 1
fi
grep -E "^   (PASS|FAIL)|^--|ARM:" "$RED_OUT" | sed 's/^/  red: /'
# A red arm that fails for another reason (a crash, a sanitizer fault) proves nothing.
if ! grep -q "FAIL: the late pong was answered$" "$RED_OUT" ||
   ! grep -q "FAIL: the late mempool request was answered$" "$RED_OUT" ||
   ! grep -q "FAIL: the publish's pong was answered$" "$RED_OUT"; then
    echo
    echo "GATE FAILURE: the red arm failed, but NOT on the late-callback assertions."
    exit 1
fi
if grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" "$RED_OUT"; then
    cat "$RED_OUT"
    echo "GATE FAILURE: the red arm faulted; a fault is not the failure this gate asserts."
    exit 1
fi
echo "(red arm failed on the late-callback assertions, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "late_peer_callback_kat: RED-BEFORE-GREEN OK"
exit 0
