#!/usr/bin/env bash
#
# spend_guard_kat — a coin the wallet already holds as spent is never signed into another send (4.1.2 row 3, R3).
#
#   RED    -DSPEND_GUARD_UNFIXED=1: the guard allows everything (the 4.1.2-before-R3 path). Case A must FAIL there.
#   GREEN  every case passes; no AddressSanitizer report.
#   GATE   seed_sign_transaction asks the guard before it signs.
set -euo pipefail

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

fn_body() { awk -v start="$2" '$0 ~ start {on=1} on {print} on && /^\}/ {exit}' "$1" | tr -s '[:space:]' ' '; }
SIGN="$(fn_body "$BRIDGE_DIR/jni_wallet.c" '^int seed_sign_transaction')"
[ -n "$SIGN" ] || gate_fail "seed_sign_transaction was not found in jni_wallet.c. Re-anchor the gate."
GUARD_AT=$(awk -v s="$SIGN" 'BEGIN{print index(s, "spend_guard_inputs_unspent(wallet, tx)")}')
SIGN_AT=$(awk -v s="$SIGN" 'BEGIN{print index(s, "BRWalletSignTransaction(")}')
[ "$GUARD_AT" -gt 0 ] || gate_fail "seed_sign_transaction does not ask spend_guard_inputs_unspent"
[ "$GUARD_AT" -gt 0 ] && [ "$GUARD_AT" -lt "$SIGN_AT" ] || gate_fail "the guard does not run before BRWalletSignTransaction"
# Repair review #2: asked again once the claim is held, releasing it on refusal (a send that registered while this
# one signed has released its own claim, so the first check and the claim can both pass).
CLAIM_AT=$(awk -v s="$SIGN" 'BEGIN{print index(s, "coin_reservation_claim(")}')
AFTER="${SIGN:$CLAIM_AT}"
<<<"$AFTER" grep -qF "if (!spend_guard_inputs_unspent(wallet, tx)) { coin_reservation_release(tx->txHash);" ||
    gate_fail "seed_sign_transaction does not ask the guard again after the claim, releasing the claim on refusal"

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/spend_guard_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"

build "$BUILD_DIR/red" -DSPEND_GUARD_UNFIXED=1 || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/red" 2>&1)"; RC=$?; set -e
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^FAIL: A: a coin a registered send already spends" \
   && ! <<<"$OUT" grep -q "^FAIL: setup"; then
    echo "  [red] case A failed, as required: $(<<<"$OUT" grep -m1 '^FAIL: A:')"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; OUT="$(timeout -k 5 60 "$BUILD_DIR/green" 2>&1)"; RC=$?; set -e
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^PASS: spend_guard_kat"; then echo "  [green] all cases pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then echo "PASS: spend_guard_kat (A red-then-green, B-C guards, wiring gate; ASan)"; exit 0; fi
echo "FAIL: spend_guard_kat"; exit 1
