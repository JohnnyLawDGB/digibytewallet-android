#!/usr/bin/env bash
# Host KAT runner: a peer's knownTxHashes dedup cache is bounded and still dedups recent hashes.
#
# WHAT IT PROVES. knownTxHashes (BRPeer.c) is appended from the inv path and the mempool and
# was never trimmed, so a peer streaming novel hashes drove unbounded memory growth. The
# shipped code caps the array and evicts the oldest third on overflow (mirroring the
# knownBlockHashes trim), rebuilding knownTxHashSet from the surviving interior pointers. The
# comparison arm -DPEER_KNOWN_TX_CAP_UNFIXED leaves it uncapped and grows to the number fed.
#
# MACRO CONVENTION. Presence: the comparison arm is -DPEER_KNOWN_TX_CAP_UNFIXED, the shipped
# arm is no -D at all. The build also enables AddressSanitizer, so the oldest-third eviction
# and the set rebuild out of the moved interior pointers are checked for memory safety.
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"

export ASAN_OPTIONS="abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

if ! grep -q 'PEER_KNOWN_TX_CAP_UNFIXED' "$CORE_DIR/BRPeer.c"; then
    echo "GATE FAILURE: BRPeer.c has no PEER_KNOWN_TX_CAP_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=(
    "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() {   # <out> [extra -D]
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/peer_known_tx_cap_kat_main.c" "${UNITS[@]}" \
        -lpthread -lm -o "$out"
}

FAIL=0
if ! build "$BUILD_DIR/green"; then echo "GATE FAILURE: shipped arm did not compile."; exit 1; fi
if ! build "$BUILD_DIR/red" -DPEER_KNOWN_TX_CAP_UNFIXED; then echo "GATE FAILURE: comparison arm did not compile."; exit 1; fi

echo "--- shipped arm (capped) ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out" | sed 's/^/  /'
if echo "$out" | grep -Eq "$SAN_RE"; then echo "  GATE FAILURE: sanitizer report in the shipped arm"; FAIL=1; fi
echo "$out" | grep -q "PASS: peer_known_tx_cap_kat" || { echo "  GATE FAILURE: shipped checks did not all pass (rc=$rc)"; FAIL=1; }

echo "--- comparison arm (uncapped: shows the unbounded growth the cap removes) ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out" | sed 's/^/  /'
if echo "$out" | grep -Eq "$SAN_RE"; then echo "  GATE FAILURE: sanitizer report in the comparison arm"; FAIL=1; fi
echo "$out" | grep -q "RESULT count 15000" || { echo "  GATE FAILURE: comparison arm did not grow unbounded (rc=$rc)"; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: peer_known_tx_cap_kat (bounded, recent dedup intact; unbounded without the cap)"
    exit 0
else
    echo "FAIL: peer_known_tx_cap_kat"
    exit 1
fi
