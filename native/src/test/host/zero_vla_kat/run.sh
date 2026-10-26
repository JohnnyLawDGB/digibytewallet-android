#!/usr/bin/env bash
# zero_vla_kat — no zero-length VLA (4.1.2 row 12, N9). See zero_vla_kat_main.c.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
FAIL=0
[ "$(grep -c ZERO_VLA_UNFIXED "$CORE_DIR/BRAddress.c")" -ge 3 ] && [ "$(grep -c ZERO_VLA_UNFIXED "$CORE_DIR/BRBIP39Mnemonic.c")" -ge 1 ] ||
    { echo "GATE FAILURE: a ZERO_VLA_UNFIXED seam is missing; the red arm would be inert"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -g -fsanitize=vla-bound,undefined -fno-sanitize-recover=all \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$SCRIPT_DIR/zero_vla_kat_main.c" \
        "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
build "$BUILD_DIR/red" -DZERO_VLA_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
# Review R9: the sites a host run cannot reach without a peer harness -- each VLA sized by a count that can be 0
# takes BR_VLA_LEN (BRInt.h), and the macro keeps the bare length under the red seam.
grep -q '^#define BR_VLA_LEN(n) ((n) > 0 ? (n) : 1)$' "$CORE_DIR/BRInt.h" || { echo "GATE FAILURE: BR_VLA_LEN is missing from BRInt.h"; FAIL=1; }
for site in "BRPeer.c:BRPeer peers\[BR_VLA_LEN(count)\]" \
            "BRPeerManager.c:BRTransaction \*txs\[BR_VLA_LEN(" "BRPeerManager.c:UInt256 hashes\[BR_VLA_LEN(" \
            "BRPeerManager.c:BRTransaction \*tx\[BR_VLA_LEN(txCount)\]" "BRPeerManager.c:UInt256 txHashes\[BR_VLA_LEN(txCount)\]" \
            "BRPeerManager.c:BRPeer save\[BR_VLA_LEN(peersCount)\]" "BRPeer.c:UInt256 added\[BR_VLA_LEN(txCount)\]" \
            "BRWallet.c:BRKey keys\[BR_VLA_LEN(totalKeys)\]"; do
    f="${site%%:*}"; pat="${site#*:}"
    grep -q "$pat" "$CORE_DIR/$f" || { echo "GATE FAILURE: $f no longer sizes a VLA with BR_VLA_LEN ($pat)"; FAIL=1; }
done
for arm in pkh spk sig phrase verify wallet unconfirmed sign; do
    OUT="$(timeout 30 "$BUILD_DIR/red" "$arm" 2>&1)"; RC=$?
    if [ "$RC" -ne 0 ] && <<<"$OUT" grep -q "variable length array bound evaluates to non-positive value 0"; then
        echo "  [red]   $arm: zero-length VLA reported, as required"
    else echo "GATE FAILURE: red arm $arm reported no zero-length VLA (rc=$RC)"; <<<"$OUT" tail -2; FAIL=1; fi
    OUT="$(timeout 30 "$BUILD_DIR/green" "$arm" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -q "runtime error"; then echo "  [green] $(<<<"$OUT" tail -1)"
    else echo "GATE FAILURE: green arm $arm (rc=$RC)"; <<<"$OUT" tail -2; FAIL=1; fi
done
[ "$FAIL" -eq 0 ] && { echo "PASS: zero_vla_kat (8 arms red-then-green under UBSan, peer and signing sites gated)"; exit 0; }
echo "FAIL: zero_vla_kat"; exit 1
