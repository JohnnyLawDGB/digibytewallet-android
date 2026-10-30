#!/usr/bin/env bash
#
# wallet_tx_walk_kat — the wallet's validity walkers read no parent the lock no longer holds (4.1.2 row 5, K2).
# See the header of wallet_tx_walk_kat_main.c for the arms.
#
#   RED    -DWALLET_TX_WALK_UNFIXED: the walkers read the wallet's own parent object after the lookup released
#          the lock. Every arm must report an AddressSanitizer heap-use-after-free.
#   GREEN  every arm runs clean and answers.
#   GATES  no BRWalletTransactionForHash result is read in BRWallet.c outside the accessor and the read helper;
#          the rejected-tx handler reads copies.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# Source gates.
N=$(grep -c 'BRWalletTransactionForHash(' "$CORE_DIR/BRWallet.c" || true)
[ "$N" -eq 2 ] || gate_fail "BRWallet.c calls BRWalletTransactionForHash $N times; expected 2 (its definition and _BRWalletTxForRead's comparison arm). A new caller must read a copy."
fn_body() { awk -v start="$2" '$0 ~ start {on=1} on {print} on && /^\}/ {exit}' "$1"; }
REJ="$(fn_body "$CORE_DIR/BRPeerManager.c" '^static void _peerRejectedTx')"
[ "$(<<<"$REJ" grep -c 'BRWalletCopyTransactionForHash(')" -ge 2 ] || gate_fail "_peerRejectedTx no longer reads the record and its parent from copies"
[ "$(grep -c WALLET_TX_WALK_UNFIXED "$CORE_DIR/BRWallet.c")" -ge 2 ] || gate_fail "the WALLET_TX_WALK_UNFIXED seam is missing; the red arm would be inert"

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -DWALLET_TX_READ_HOOK "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/wallet_tx_walk_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
ARMS="valid pending verified asset"

build "$BUILD_DIR/red" -DWALLET_TX_WALK_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
for arm in $ARMS; do
    set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/red" "$arm" 2>&1)"; RC=$?; set -e
    if [ "$RC" -ne 0 ] && <<<"$OUT" grep -q "ERROR: AddressSanitizer: heap-use-after-free" && ! <<<"$OUT" grep -q "FAIL: setup"; then
        echo "  [red]   $arm: heap-use-after-free, as required"
    else
        echo "GATE FAILURE: red arm $arm did not read the freed parent (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|answered" | head -3; FAIL=1
    fi
    set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/green" "$arm" 2>&1)"; RC=$?; set -e
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "answered .* with the parent removed mid-walk"; then
        echo "  [green] $arm: $(<<<"$OUT" grep -o 'answered .*')"
    else
        echo "GATE FAILURE: green arm $arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -3; FAIL=1
    fi
done

if [ "$FAIL" -eq 0 ]; then echo "PASS: wallet_tx_walk_kat (4 arms red-then-green under ASan, source gates)"; exit 0; fi
echo "FAIL: wallet_tx_walk_kat"; exit 1
