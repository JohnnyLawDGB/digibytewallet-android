#!/usr/bin/env bash
# one_family_kat — ONE-FAMILY: an output belongs to at most one token family. See the _main.c.
#   RED    -DONE_FAMILY_UNFIXED -DONE_FAMILY_SIGNER_UNFIXED: no cross-family exclusion. Cases A, B and C
#          must FAIL on their named checks; every guard must still pass.
#   GREEN  every check passes; no AddressSanitizer report.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
[ "$(grep -c ONE_FAMILY_UNFIXED "$CORE_DIR/BRWallet.c")" -ge 2 ] || { echo "GATE FAILURE: the wallet seam is missing"; exit 1; }
[ "$(grep -c ONE_FAMILY_SIGNER_UNFIXED "$BRIDGE_DIR/one_family_gate.h")" -ge 1 ] || { echo "GATE FAILURE: the signer seam is missing"; exit 1; }
grep -q "tx_inputs_one_family_ok(g_wallet, tx)" "$BRIDGE_DIR/jni_asset_send.c" \
    || { echo "GATE FAILURE: buildAndSignAssetTransferTx does not run the ONE-FAMILY gate"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/one_family_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
FAIL=0
RED_MUST_FAIL=(
    "^FAIL: A: the DD output reads HELD for the asset layer"
    "^FAIL: A: the dual-family tx was credited as DigiDollar"
    "^FAIL: A: the dual-family tx's DGB output counts as a plain coin"
    "^FAIL: A: coin selection offers an output of the dual-family tx"
    "^FAIL: A: a plain send spends the dual-family tx"
    "^FAIL: A: the DD builder spends the dual-family tx's DD output"
    "^FAIL: A: the asset signer accepts an output of the dual-family tx"
    "^FAIL: B: the DD output reads HELD for the asset layer"
    "^FAIL: B: the dual-family tx was credited as DigiDollar"
    "^FAIL: C: an ordinary DD output reads HELD for the asset layer"
    "^FAIL: C: the asset signer accepts a DD output"
)
build "$BUILD_DIR/red" -DONE_FAMILY_UNFIXED -DONE_FAMILY_SIGNER_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout -k 5 300 "$BUILD_DIR/red" 2>&1)"; RC=$?
red_ok=1
[ "$RC" -ne 0 ] || red_ok=0
<<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && red_ok=0
for want in "${RED_MUST_FAIL[@]}"; do <<<"$OUT" grep -q "$want" || { echo "  [red] did not fail: $want"; red_ok=0; }; done
# Every other FAIL line in the red arm is a guard or a setup check breaking: not allowed.
EXTRA="$(<<<"$OUT" grep "^FAIL" | grep -vE "^FAIL: (A|B|C): " )"
[ -z "$EXTRA" ] || { echo "  [red] an unexpected check failed: $EXTRA"; red_ok=0; }
if [ "$red_ok" -eq 1 ]; then echo "  [red] ${#RED_MUST_FAIL[@]} named checks failed, as required; guards held"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -20; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout -k 5 300 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^PASS: one_family_kat"; then
    echo "  [green] all $(<<<"$OUT" grep -c '^PASS:') checks pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -20; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: one_family_kat (A,B,C red-then-green; C,D guards; ASan)"; exit 0; }
echo "FAIL: one_family_kat"; exit 1
