#!/usr/bin/env bash
#
# bip39_nul_kat — a BIP39 passphrase with an embedded NUL is refused by the bridge, never truncated (4.1.2 row 25).
# See bip39_nul_kat_main.c. Drives the real createWalletFromBytes over identity_sign_bytes_kat's host JNI stand-ins.
#
#   RED    -DBIP39_NUL_UNFIXED: the copy keeps the NUL and the derivation reads up to it; case A must fail.
#   GREEN  every case passes under ASan.
#   GATES  the seam exists in both passphrase copies (jni_wallet.c passphrase_copy, jni_derive.c).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

[ "$(grep -c BIP39_NUL_UNFIXED "$BRIDGE_DIR/jni_wallet.c")" -ge 1 ] && [ "$(grep -c BIP39_NUL_UNFIXED "$BRIDGE_DIR/jni_derive.c")" -ge 1 ] ||
    gate_fail "a BIP39_NUL_UNFIXED seam is missing (jni_wallet.c passphrase_copy, jni_derive.c)"

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/bip39_nul_kat_main.c" \
        "$BRIDGE_DIR/jni_wallet_sign.c" "$BRIDGE_DIR/jni_transaction_persist.c" "$BRIDGE_DIR/jni_wallet.c" \
        "$BRIDGE_DIR/coin_reservation.c" "$BRIDGE_DIR/slip13.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0"

build "$BUILD_DIR/red" -DBIP39_NUL_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
RED_OUT="$(timeout -k 5 120 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?
if [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -q "^FAIL: A: a passphrase with an embedded NUL created a wallet"; then echo "  [red] case A failed, as required"
else echo "GATE FAILURE: the red arm did not fail case A (rc=$RED_RC)"; <<<"$RED_OUT" tail -3; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
GREEN_OUT="$(timeout -k 5 120 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?
if [ "$GREEN_RC" -eq 0 ] && <<<"$GREEN_OUT" grep -q "^PASS: bip39_nul_kat" && ! <<<"$GREEN_OUT" grep -q "ERROR: AddressSanitizer"; then echo "  [green] all cases pass"
else echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then echo "PASS: bip39_nul_kat (A red-then-green, B guard, seam gates; ASan)"; exit 0; fi
echo "FAIL: bip39_nul_kat"; exit 1
