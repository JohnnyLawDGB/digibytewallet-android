#!/usr/bin/env bash
#
# pending_prune_kat — a coin a pending send spends is neither in the balance nor selectable (4.1.2 row 21).
#
#   RED    -DPENDING_PRUNE_UNFIXED: the coin stays. Case A must FAIL there.
#   GREEN  every case passes; no AddressSanitizer report.
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

[ "$(grep -c PENDING_PRUNE_UNFIXED "$CORE_DIR/BRWallet.c")" -ge 1 ] || gate_fail "the PENDING_PRUNE_UNFIXED seam is missing"

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/pending_prune_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
ARMS="valid pending verified asset"

build "$BUILD_DIR/red" -DPENDING_PRUNE_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/red" 2>&1)"; RC=$?; set -e
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^FAIL: A: the coin the pending send spends is still counted"; then
    echo "  [red] case A failed, as required: $(<<<"$OUT" grep -m1 '^FAIL: A:')"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/green" 2>&1)"; RC=$?; set -e
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^PASS: pending_prune_kat"; then echo "  [green] all cases pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then echo "PASS: pending_prune_kat (A red-then-green, B guard; ASan)"; exit 0; fi
echo "FAIL: pending_prune_kat"; exit 1
