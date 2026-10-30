#!/usr/bin/env bash
#
# server_height_recheck_kat — a confirmation height a server reports is never the proof (4.1.2 row 1, step B).
# See the header of server_height_recheck_kat_main.c for the defect and the cases.
#
#   RED    -DSERVER_HEIGHT_HINT_UNFIXED: BRPeerManagerApplyServerHeight confirms a height the wallet could re-check,
#          on the server's word (the 4.1.1 path behind step A). Case A must FAIL there, on what it asserts.
#   GREEN  the shipped core: every case passes, no AddressSanitizer report.
#
# Peer calls are intercepted the way cf_block_completion_gate_kat does it (a -D rename in this TU only), so the KAT
# touches no socket and counts the getcfilters / getdata the manager really sends.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

if [ "$(grep -c SERVER_HEIGHT_HINT_UNFIXED "$CORE_DIR/BRPeerManager.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeerManager.c has no SERVER_HEIGHT_HINT_UNFIXED seam; the red arm would be inert."
    exit 1
fi

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        -DBRPeerConnectStatus=__wrap_BRPeerConnectStatus \
        -DBRPeerIsSocketOpen=__wrap_BRPeerIsSocketOpen \
        -DBRPeerSendGetCFilters=__wrap_BRPeerSendGetCFilters \
        -DBRPeerSendGetdataBlocks=__wrap_BRPeerSendGetdataBlocks \
        -c "$SCRIPT_DIR/server_height_recheck_kat_main.c" -o "$BUILD_DIR/kat_main.o"
    clang -w -include stdint.h "$@" -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$BUILD_DIR/kat_main.o" \
        "$CORE_DIR/BRPeer.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
FAIL=0
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"

# ── RED ──────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/red" -DSERVER_HEIGHT_HINT_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; RED_OUT="$(timeout 120 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?; set -e
if <<<"$RED_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the red arm faulted (a fault is not the failure case A asserts)"; <<<"$RED_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -q "^FAIL: A: the payment is still pending" \
                          && ! <<<"$RED_OUT" grep -q "^FAIL: setup"; then
    echo "  [red] case A failed, as required: $(<<<"$RED_OUT" grep -m1 '^NOTE: apply(H)')"
else
    echo "GATE FAILURE: the red arm did not fail on case A (rc=$RED_RC)"; <<<"$RED_OUT" grep -E '^(FAIL|NOTE)' | head -8; FAIL=1
fi

# ── RED 2: a server height overwrites a height the peers proved ──────────────────────────────────────────────────
if [ "$(grep -c SERVER_HEIGHT_OVERWRITE_UNFIXED "$CORE_DIR/BRPeerManager.c")" -lt 1 ] ||
   [ "$(grep -c SERVER_HEIGHT_OVERWRITE_UNFIXED "$CORE_DIR/BRWallet.c")" -lt 1 ]; then
    echo "GATE FAILURE: the SERVER_HEIGHT_OVERWRITE_UNFIXED seam is missing; the second red arm would be inert."; exit 1
fi
build "$BUILD_DIR/red2" -DSERVER_HEIGHT_OVERWRITE_UNFIXED || { echo "GATE FAILURE: the second red arm did not compile"; exit 1; }
set +e; RED2_OUT="$(timeout 120 "$BUILD_DIR/red2" 2>&1)"; RED2_RC=$?; set -e
if <<<"$RED2_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the second red arm faulted"; <<<"$RED2_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED2_RC" -ne 0 ] && <<<"$RED2_OUT" grep -q "^FAIL: F: the payment keeps the height its block proved" \
                           && ! <<<"$RED2_OUT" grep -q "^FAIL: setup"; then
    echo "  [red2] case F failed, as required: $(<<<"$RED2_OUT" grep -m1 '^NOTE: apply(.*P2P-confirmed')"
else
    echo "GATE FAILURE: the second red arm did not fail on case F (rc=$RED2_RC)"; <<<"$RED2_OUT" grep -E '^(FAIL|NOTE)' | head -8; FAIL=1
fi

# ── RED 3: a held header the filter chain cannot check yet is taken on the server's word (review R2) ─────────────
if [ "$(grep -c SERVER_HEIGHT_HELD_UNFIXED "$CORE_DIR/BRPeerManager.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeerManager.c has no SERVER_HEIGHT_HELD_UNFIXED seam; the third red arm would be inert."; exit 1
fi
build "$BUILD_DIR/red3" -DSERVER_HEIGHT_HELD_UNFIXED || { echo "GATE FAILURE: the third red arm did not compile"; exit 1; }
set +e; RED3_OUT="$(timeout 120 "$BUILD_DIR/red3" 2>&1)"; RED3_RC=$?; set -e
if <<<"$RED3_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the third red arm faulted"; <<<"$RED3_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED3_RC" -ne 0 ] && <<<"$RED3_OUT" grep -q "^FAIL: G: the payment is still pending" \
                           && ! <<<"$RED3_OUT" grep -q "^FAIL: setup" && ! <<<"$RED3_OUT" grep -q "^FAIL: A:"; then
    echo "  [red3] case G failed, as required: $(<<<"$RED3_OUT" grep -m1 '^NOTE: apply(H) with the filter chain')"
else
    echo "GATE FAILURE: the third red arm did not fail on case G (rc=$RED3_RC)"; <<<"$RED3_OUT" grep -E '^(FAIL|NOTE)' | head -8; FAIL=1
fi

# ── GREEN ────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; GREEN_OUT="$(timeout 120 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?; set -e
if [ "$GREEN_RC" -eq 0 ] && ! <<<"$GREEN_OUT" grep -Eq "$SAN_RE" && <<<"$GREEN_OUT" grep -q "^PASS: server_height_recheck_kat"; then
    <<<"$GREEN_OUT" grep '^NOTE:' | sed 's/^/  [green] /'
else
    echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -E "^(FAIL|NOTE)|$SAN_RE" | head -12; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: server_height_recheck_kat (cases A, F and G red-then-green, H with G, B-E and I guards; ASan)"
    exit 0
fi
echo "FAIL: server_height_recheck_kat"
exit 1
