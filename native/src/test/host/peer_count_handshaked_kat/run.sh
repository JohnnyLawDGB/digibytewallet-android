#!/usr/bin/env bash
# Host KAT runner for peer_count_handshaked_kat: the peer count a screen shows counts handshaken
# peers only, and the in-flight count the recovery paths read keeps counting connecting peers
# (PEER-COUNT-HANDSHAKED; see the header of peer_count_handshaked_kat_main.c).
# Compiles the REAL, live submodule BRPeer.c + BRPeerManager.c into the test (same shape and link
# line as download_peer_promote_kat/run.sh), under ASan.
#
# ARMS. Shipped: no -D, every check passes, no sanitizer report. Comparison:
# -DPEER_COUNT_HANDSHAKED_UNFIXED (a connecting peer counts as handshaken, which is what the one
# count the apps showed did): exactly the checks in RED_EXPECT fail, the ones taken while a peer is
# still connecting. The comparison arm failing anything else, or nothing, fails this gate.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h reasons as bip340_kat.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

if ! grep -q 'PEER_COUNT_HANDSHAKED_UNFIXED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c has no PEER_COUNT_HANDSHAKED_UNFIXED seam; the comparison arm would be inert."
    exit 1
fi

RED_EXPECT="dialled.handshaked
dropped.handshaked
one.handshaked
redial.handshaked
two.handshaked"

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; shift
    "${CC:-clang}" -w -include stdint.h "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_count_handshaked_kat_main.c" \
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
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
FAIL=0

# ── comparison arm ───────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/red" -DPEER_COUNT_HANDSHAKED_UNFIXED || { echo "GATE FAILURE: the comparison arm did not compile"; exit 1; }
RED_OUT="$(timeout 120 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?
RED_GOT="$(<<<"$RED_OUT" sed -n 's/^FAIL \([a-z.]*\):.*/\1/p' | sort)"
if <<<"$RED_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the comparison arm faulted (a fault is not the failure it must show)"
    <<<"$RED_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED_RC" -ne 1 ] || [ "$RED_GOT" != "$RED_EXPECT" ]; then
    echo "GATE FAILURE: the comparison arm (rc $RED_RC) did not fail exactly the connecting-peer checks."
    echo "  wanted:"; <<<"$RED_EXPECT" sed 's/^/    /'
    echo "  got:";    <<<"$RED_GOT" sed 's/^/    /'
    FAIL=1
else
    echo "comparison arm (-DPEER_COUNT_HANDSHAKED_UNFIXED): fails exactly its $(<<<"$RED_EXPECT" grep -c .) checks, as it must"
    <<<"$RED_OUT" grep '^FAIL ' | sed 's/^/    | /'
fi

# ── shipped arm ──────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || { echo "FAIL: the shipped arm did not compile"; exit 1; }
GREEN_OUT="$(timeout 120 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?
echo "$GREEN_OUT" | grep -v '^$' | grep -E '^(PASS|FAIL|--|PASSED|FAILED)' 
if <<<"$GREEN_OUT" grep -Eq "$SAN_RE"; then
    echo "FAIL: sanitizer report in the shipped arm"; <<<"$GREEN_OUT" grep -EA12 -m1 "$SAN_RE"; FAIL=1
elif [ "$GREEN_RC" -ne 0 ]; then
    echo "FAIL: the shipped arm exited $GREEN_RC"; <<<"$GREEN_OUT" tail -20; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then echo "peer_count_handshaked_kat: ALL PASS"; exit 0; fi
echo "peer_count_handshaked_kat: FAILED"; exit 1
