#!/usr/bin/env bash
#
# unrelayed_tx_heap_kat — the unrelayed-transaction request's tables are not stack VLAs (4.1.3 G2).
# See the header of unrelayed_tx_heap_kat_main.c.
#
#   RED    -DUNRELAYED_TX_STACK_UNFIXED, the call on a 256 KiB thread stack: it overflows the stack (killed by a signal).
#   GREEN  the shipped core on the same stack: it returns and asks for every record; and again under ASan
#          with no report.
# The red arm is built WITHOUT ASan and bounded by timeout: an ASan stack-overflow report on a small stack can
# wedge rather than abort. Both stack arms use -fstack-clash-protection: without it a table larger than the stack
# skips the single guard page and writes into whatever is mapped beyond it -- silently, which is how an unprobed
# build behaves -- so the red arm would prove nothing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

[ "$(grep -c UNRELAYED_TX_STACK_UNFIXED "$CORE_DIR/BRPeerManager.c")" -ge 2 ] ||
    { echo "GATE FAILURE: BRPeerManager.c has no UNRELAYED_TX_STACK_UNFIXED seam; the red arm would be inert."; exit 1; }

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -g -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        -DBRPeerSendGetdata=__wrap_BRPeerSendGetdata -DBRPeerSendPing=__wrap_BRPeerSendPing \
        -c "$SCRIPT_DIR/unrelayed_tx_heap_kat_main.c" -o "$out.o"
    clang -w -include stdint.h "$@" -g -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$out.o" \
        "$CORE_DIR/BRPeer.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}

FAIL=0
build "$BUILD_DIR/red" -O1 -fstack-clash-protection -DUNRELAYED_TX_STACK_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
build "$BUILD_DIR/green" -O1 -fstack-clash-protection || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
build "$BUILD_DIR/asan" -fsanitize=address -fno-omit-frame-pointer || { echo "GATE FAILURE: the ASan arm did not compile"; exit 1; }

set +e
RED_OUT="$(timeout 300 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?
GREEN_OUT="$(timeout 300 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?
ASAN_OUT="$(timeout 300 "$BUILD_DIR/asan" 2>&1)"; ASAN_RC=$?
set -e

# a stack overflow ends the process by a signal: 128 + SIGSEGV (139) or SIGBUS (138); timeout's own 124 is not one
if [ "$RED_RC" -gt 128 ] && [ "$RED_RC" -ne 124 ] && <<<"$RED_OUT" grep -q "^NOTE: the wallet holds" \
                         && ! <<<"$RED_OUT" grep -q "^PASS:"; then
    echo "  [red]   the stack tables overflowed a 256 KiB thread stack, as required (rc=$RED_RC)"
else
    echo "GATE FAILURE: the red arm did not overflow the stack (rc=$RED_RC)"; <<<"$RED_OUT" tail -3; FAIL=1
fi
if [ "$GREEN_RC" -eq 0 ] && <<<"$GREEN_OUT" grep -q "^PASS: unrelayed_tx_heap_kat"; then
    echo "  [green] $(<<<"$GREEN_OUT" grep -m1 '^NOTE: getdata')"
else
    echo "GATE FAILURE: the green arm on a 256 KiB thread stack (rc=$GREEN_RC)"; <<<"$GREEN_OUT" tail -3; FAIL=1
fi
if [ "$ASAN_RC" -eq 0 ] && ! <<<"$ASAN_OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$ASAN_OUT" grep -q "^PASS:"; then
    echo "  [asan]  no AddressSanitizer report"
else
    echo "GATE FAILURE: the ASan arm (rc=$ASAN_RC)"; <<<"$ASAN_OUT" grep -m3 -E "ERROR|FAIL" ; FAIL=1
fi

[ "$FAIL" -eq 0 ] && { echo "PASS: unrelayed_tx_heap_kat (red overflows a 256 KiB thread stack, green returns on it, ASan clean)"; exit 0; }
echo "FAIL: unrelayed_tx_heap_kat"; exit 1
