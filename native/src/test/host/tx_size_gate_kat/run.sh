#!/usr/bin/env bash
#
# tx_size_gate_kat — an oversized tx / dandeliontx message is refused before it is parsed (4.1.2 row 4, K3).
# See the header of tx_size_gate_kat_main.c for the cases.
#
#   RED    -DTX_SIZE_GATE_UNFIXED: the 4.1.1 core, which parses first. Cases A and B must FAIL there.
#   GREEN  every case passes, the gate's log line is seen, no AddressSanitizer report.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

if [ "$(grep -c TX_SIZE_GATE_UNFIXED "$CORE_DIR/BRPeer.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeer.c is missing a TX_SIZE_GATE_UNFIXED seam; the red arm would be inert."
    exit 1
fi

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -DDEBUG "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/tx_size_gate_kat_main.c" \
        "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lpthread -lm -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
FAIL=0
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"

# ── RED ──────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/red" -DTX_SIZE_GATE_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; RED_OUT="$(timeout 120 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?; set -e
if <<<"$RED_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the red arm faulted (a fault is not the failure A and B assert)"; <<<"$RED_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -q "^FAIL: A: an 18 MB message was parsed" \
                          && <<<"$RED_OUT" grep -q "^FAIL: B: an 18 MB message was parsed" && ! <<<"$RED_OUT" grep -q "^FAIL: setup"; then
    echo "  [red] cases A and B failed, as required"
else
    echo "GATE FAILURE: the red arm did not fail on both A and B (rc=$RED_RC)"; <<<"$RED_OUT" grep -E '(FAIL)' | head -8; FAIL=1
fi

# ── GREEN ────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; GREEN_OUT="$(timeout 120 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?; set -e
if [ "$GREEN_RC" -eq 0 ] && ! <<<"$GREEN_OUT" grep -Eq "$SAN_RE" && <<<"$GREEN_OUT" grep -q "^PASS: tx_size_gate_kat" \
   && <<<"$GREEN_OUT" grep -q "tx message of 18000000 bytes is over 400000: refused unparsed" \
   && <<<"$GREEN_OUT" grep -q "dandeliontx message of 18000000 bytes is over 400000: refused unparsed"; then
    echo "  [green] all cases pass; the gate's log lines seen"
else
    echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -E "FAIL|$SAN_RE" | head -12; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: tx_size_gate_kat (cases A and B red-then-green, C and D guards; ASan)"
    exit 0
fi
echo "FAIL: tx_size_gate_kat"
exit 1
