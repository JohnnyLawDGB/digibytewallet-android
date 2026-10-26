#!/usr/bin/env bash
# Host KAT runner: the penalty persistence buffer holds every penalty the peer manager keeps (see the
# _main.c header).
#
# ARMS. Shipped: no -D. Comparison: -DPEER_PENALTY_BLOB_UNFIXED (BRPeerPenalty.h gives the earlier
# 4 + 32*26 buffer size). Shipped must pass; comparison must fail cases [1] and [2] and pass [3].
# GUARD (no red can exist on the host: the bridge needs JNI): the bridge's serializePeerPenalties
# sizes its buffer with BR_PEER_PENALTY_BLOB_MAX.
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE="$REPO_ROOT/native/src/main/jni/bridge/jni_peer.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="abort_on_error=1 detect_leaks=0 symbolize=0"

if ! grep -q 'PEER_PENALTY_BLOB_UNFIXED' "$CORE_DIR/BRPeerPenalty.h"; then
    echo "GATE FAILURE: the PEER_PENALTY_BLOB_UNFIXED comparison seam is not in BRPeerPenalty.h"; exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_penalty_blob_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

FAILS=0
echo "--- GUARD: the bridge sizes its serialize buffer from the core constant ---"
fn="$(awk '/^NativeBridge_serializePeerPenalties/,/^}/' "$BRIDGE")"
if <<<"$fn" grep -q 'uint8_t buf\[BR_PEER_PENALTY_BLOB_MAX\];' && ! <<<"$fn" grep -Eq 'buf\[[^]]*[0-9]+ *\* *26'; then
    echo "  ok   serializePeerPenalties declares uint8_t buf[BR_PEER_PENALTY_BLOB_MAX]"
else
    echo "  FAIL serializePeerPenalties does not size its buffer with BR_PEER_PENALTY_BLOB_MAX"; FAILS=$((FAILS + 1))
fi

for bits in 64 32; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DPEER_PENALTY_BLOB_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }

    echo "--- comparison arm (-DPEER_PENALTY_BLOB_UNFIXED): [1] and [2] must fail, [3] pass ---"
    timeout 120 "$BUILD_DIR/red$bits" > "$BUILD_DIR/red$bits.out" 2>&1; rc=$?
    cat "$BUILD_DIR/red$bits.out"
    fails_red="$(grep -c '^  \[FAIL\]' "$BUILD_DIR/red$bits.out")"
    if [ "$rc" -ne 0 ] && [ "$fails_red" -eq 4 ] && ! grep -q 'ERROR: AddressSanitizer' "$BUILD_DIR/red$bits.out" \
       && [ "$(sed -n '/^\[3\]/,$p' "$BUILD_DIR/red$bits.out" | grep -c '^  \[FAIL\]')" -eq 0 ]; then
        echo "  ok   comparison arm: [1] and [2] serialize nothing, [3] holds"
    else
        echo "  FAIL comparison arm did not show the expected result (rc=$rc, fails=$fails_red)"; FAILS=$((FAILS + 1))
    fi

    echo "--- shipped arm: all pass ---"
    timeout 120 "$BUILD_DIR/green$bits" > "$BUILD_DIR/green$bits.out" 2>&1; rc=$?
    cat "$BUILD_DIR/green$bits.out"
    if [ "$rc" -eq 0 ] && grep -q '^peer_penalty_blob_kat: ALL PASS' "$BUILD_DIR/green$bits.out"; then
        echo "  ok   shipped arm"
    else
        echo "  FAIL shipped arm (rc=$rc)"; FAILS=$((FAILS + 1))
    fi
done

if [ "$FAILS" -eq 0 ]; then echo "peer_penalty_blob_kat: ALL EXPECTATIONS HELD"; exit 0; fi
echo "peer_penalty_blob_kat: $FAILS expectation(s) failed"; exit 1
