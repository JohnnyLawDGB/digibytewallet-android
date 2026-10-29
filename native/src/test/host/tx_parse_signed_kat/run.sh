#!/usr/bin/env bash
# tx_parse_signed_kat — a transaction from the network is parsed in its signed format (4.1.2 row 9, N2).
#   RED    -DTX_PARSE_SIGNED_UNFIXED: BRTransactionParseSigned is the old inferring parser; A, B and C fail.
#   GREEN  every case passes under ASan.
#   GATES  the wire paths (BRPeer.c tx + block messages, the signer's re-parse) use BRTransactionParseSigned.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }
[ "$(grep -c 'BRTransactionParseSigned(' "$CORE_DIR/BRPeer.c")" -ge 2 ] || gate_fail "BRPeer.c does not parse tx and block messages with BRTransactionParseSigned"
grep -q 'BRTransaction \*t = BRTransactionParseSigned(data, len);' "$CORE_DIR/BRTransaction.c" || gate_fail "the signer's re-parse is not signed-only"
grep -q 'BRTransactionParse(' "$CORE_DIR/BRPeer.c" && gate_fail "BRPeer.c still calls the inferring BRTransactionParse"
for f in jni_asset.c saved_transactions_deserialize.h asset_tx_checks.h; do
    grep -q 'BRTransactionParse(' "$BRIDGE_DIR/$f" && gate_fail "$f reads outside bytes with the inferring BRTransactionParse"
done
# Wave 10: the DGB send review reads its own unsigned draft; that reader is BRTransactionParse, in
# jni_transaction.c, and outside bytes keep the signed-only reader.
grep -q 'BRTransactionParse((const uint8_t' <<<"$(awk '/^NativeBridge_getBuiltTransactionOutputs\(/,/^}/' "$BRIDGE_DIR/jni_transaction.c")" \
    || gate_fail "getBuiltTransactionOutputs does not read the draft with BRTransactionParse"
grep -q 'BRTransactionParseSigned(' <<<"$(awk '/^NativeBridge_getRawTransactionOutputs\(/,/^}/' "$BRIDGE_DIR/jni_asset.c")" \
    || gate_fail "getRawTransactionOutputs is no longer signed-only"
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -g -fsanitize=address -fno-omit-frame-pointer -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_parse_signed_kat_main.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
build "$BUILD_DIR/red" -DTX_PARSE_SIGNED_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout 60 "$BUILD_DIR/red" 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^FAIL: A:" && <<<"$OUT" grep -q "^FAIL: B:" && <<<"$OUT" grep -q "^FAIL: C:"; then
    echo "  [red] A, B and C misparse, as required"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -5; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout 60 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && <<<"$OUT" grep -q "^PASS: tx_parse_signed_kat"; then echo "  [green] all cases pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -5; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: tx_parse_signed_kat (A-C red-then-green, D+E guards, source gates; ASan)"; exit 0; }
echo "FAIL: tx_parse_signed_kat"; exit 1
