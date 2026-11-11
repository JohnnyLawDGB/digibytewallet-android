#!/usr/bin/env bash
#
# ownnode_live_kat — OWNNODE-LIVE: own-node health reports whether the paired node serves compact
# filters NOW. See ownnode_live_kat_main.c.
#
#   RED    -DOWNNODE_LIVE_UNFIXED: "served" is "answered once this session". Cases A and B must FAIL
#          there (a node that served, then stopped, reads SERVING), and only those.
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
[ "$(grep -c OWNNODE_LIVE_UNFIXED "$CORE_DIR/BRPeerManager.c")" -ge 1 ] || { echo "GATE FAILURE: the seam is missing"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/ownnode_live_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
FAIL=0
build "$BUILD_DIR/red" -DOWNNODE_LIVE_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout -k 5 120 "$BUILD_DIR/red" 2>&1)"; RC=$?
REDS="$(<<<"$OUT" grep -E '^FAIL: [A-Z]:' | cut -d: -f2 | tr -d ' ' | sort -u | tr '\n' ' ')"
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && [ "$REDS" = "A B " ] \
   && ! <<<"$OUT" grep -q "^  FAIL"; then
    echo "  [red] cases A and B failed, as required, and no guard did"
else echo "GATE FAILURE: the red arm (rc=$RC, red cases '$REDS')"; <<<"$OUT" grep -E "FAIL|ERROR" | head -6; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout -k 5 120 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "ERROR: (AddressSanitizer|LeakSanitizer)" && <<<"$OUT" grep -q "^PASS: ownnode_live_kat"; then
    echo "  [green] all cases pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -6; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: ownnode_live_kat (A, B red-then-green; C-F guards; ASan)"; exit 0; }
echo "FAIL: ownnode_live_kat"; exit 1
