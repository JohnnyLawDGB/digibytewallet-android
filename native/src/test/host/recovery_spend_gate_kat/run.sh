#!/usr/bin/env bash
#
# recovery_spend_gate_kat — a signed recovery transaction leaves only through signed_spend_gate (4.1.3, review R3).
# See recovery_spend_gate_kat_main.c.
#
#   RED    -DRECOVERY_SPEND_GATE_UNFIXED=1: the gate lets everything through. Cases A and B must FAIL there.
#   GREEN  every case passes; no AddressSanitizer report.
#   GATE   each recovery signer in jni_derive.c asks wallet_signed_spend_gate after it signs and before it
#          serializes; jni_wallet.c's wallet_signed_spend_gate asks signed_spend_gate against g_wallet.
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
for fn in buildAndSignLegacySweep buildAndSignForeignAssetTransfer buildAndSignForeignDigiDollarTransfer; do
    body="$(fn_body "$BRIDGE_DIR/jni_derive.c" "^NativeBridge_${fn}")"
    [ -n "$body" ] || { gate_fail "$fn was not found in jni_derive.c. Re-anchor the gate."; continue; }
    sign_at=$(awk -v s="$body" 'BEGIN{print index(s, "BRTransactionSign(")}')
    gate_at=$(awk -v s="$body" 'BEGIN{print index(s, "wallet_signed_spend_gate(tx")}')
    ser_at=$(awk -v s="$body" 'BEGIN{print index(s, "BRTransactionSerialize(")}')
    if [ "$gate_at" -gt "$sign_at" ] && [ "$sign_at" -gt 0 ] && [ "$gate_at" -lt "$ser_at" ]; then
        echo "  [gate] $fn: asks the gate after it signs and before it serializes"
    else gate_fail "$fn does not ask wallet_signed_spend_gate between BRTransactionSign and BRTransactionSerialize"; fi
done
WG="$(fn_body "$BRIDGE_DIR/jni_wallet.c" '^int wallet_signed_spend_gate')"
<<<"$WG" grep -qF "signed_spend_gate(g_wallet, tx," || gate_fail "wallet_signed_spend_gate does not ask signed_spend_gate against g_wallet"
<<<"$WG" grep -qF "WALLET_READ_GUARD();" || gate_fail "wallet_signed_spend_gate reads g_wallet without WALLET_READ_GUARD"

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/recovery_spend_gate_kat_main.c" "$BRIDGE_DIR/coin_reservation.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'
export ASAN_OPTIONS="halt_on_error=1"
build "$BUILD_DIR/red" -DRECOVERY_SPEND_GATE_UNFIXED=1 || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; RED="$(timeout 120 "$BUILD_DIR/red" 2>&1)"; RRC=$?; GREEN="$(timeout 120 "$BUILD_DIR/green" 2>&1)"; GRC=$?; set -e
if [ "$RRC" -ne 0 ] && <<<"$RED" grep -q "^FAIL: A: a second" && <<<"$RED" grep -q "^FAIL: B: " \
   && ! <<<"$RED" grep -q "^FAIL: setup" && ! <<<"$RED" grep -Eq "$SAN_RE"; then
    echo "  [red]   cases A and B failed, as required"
else gate_fail "the red arm did not fail cases A and B (rc=$RRC)"; <<<"$RED" grep -E "^(FAIL|PASS)" | head; fi
if [ "$GRC" -eq 0 ] && ! <<<"$GREEN" grep -Eq "$SAN_RE" && <<<"$GREEN" grep -q "^PASS: recovery_spend_gate_kat"; then
    echo "  [green] every case passed"
else gate_fail "the green arm (rc=$GRC)"; <<<"$GREEN" grep -E "^FAIL|$SAN_RE" | head; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: recovery_spend_gate_kat (A, B red-then-green; C-E guards; the three signers gated)"; exit 0; }
echo "FAIL: recovery_spend_gate_kat"; exit 1
