#!/usr/bin/env bash
#
# cf_empty_batch_kat — an empty cfheaders batch is not progress (4.1.2 row 26). See cf_empty_batch_kat_main.c.
#
#   RED    -DCF_EMPTY_BATCH_UNFIXED: the empty batch returns OK, resets cfTriedCount and credits the peer.
#          Case A must FAIL there.
#   GREEN  every case passes; no AddressSanitizer report.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=(
    "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)
[ "$(grep -c CF_EMPTY_BATCH_UNFIXED "$CORE_DIR/BRPeerManager.c")" -ge 1 ] || { echo "GATE FAILURE: the seam is missing"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_empty_batch_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
FAIL=0
build "$BUILD_DIR/red" -DCF_EMPTY_BATCH_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout -k 5 120 "$BUILD_DIR/red" 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^FAIL: A: the empty batch counted"; then
    echo "  [red] case A failed, as required: $(<<<"$OUT" grep -m1 '^FAIL: A:')"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout -k 5 120 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^PASS: cf_empty_batch_kat" \
   && <<<"$OUT" grep -q "cfheaders: empty batch -- not progress, dropping peer"; then echo "  [green] all cases pass; the gate's log line seen"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -4; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: cf_empty_batch_kat (A red-then-green, B guard; ASan)"; exit 0; }
echo "FAIL: cf_empty_batch_kat"; exit 1
