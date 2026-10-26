#!/usr/bin/env bash
#
# ping_echo_kat — a peer cannot make the wallet echo or hold an oversized ping (4.1.2 row 31). See the _main.c.
#
#   RED    -DPING_ECHO_UNFIXED: the whole ping goes back as the pong. Case A must FAIL there.
#   GREEN  every case passes; no AddressSanitizer report.
#   GATES  the receive buffer is shrunk after a large message, and a failed grow drops the peer (no assert).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

if ! grep -q 'if (payloadLen > PAYLOAD_KEEP && msgLen <= PAYLOAD_KEEP) {' "$CORE_DIR/BRPeer.c" || \
   ! grep -q 'uint8_t \*bigger = realloc(payload, msgLen);' "$CORE_DIR/BRPeer.c"; then
    echo "GATE FAILURE: the read loop no longer shrinks its buffer or checks its grow"; exit 1
fi
if [ "$(grep -c PING_ECHO_UNFIXED "$CORE_DIR/BRPeer.c")" -lt 1 ]; then
    echo "GATE FAILURE: BRPeer.c is missing a PING_ECHO_UNFIXED seam; the red arm would be inert."
    exit 1
fi

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer -DDEBUG "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/ping_echo_kat_main.c" \
        "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lpthread -lm -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
FAIL=0
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0"

# ── RED ──────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/red" -DPING_ECHO_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; RED_OUT="$(timeout 120 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?; set -e
if <<<"$RED_OUT" grep -Eq "$SAN_RE"; then
    echo "GATE FAILURE: the red arm faulted (a fault is not the failure A and B assert)"; <<<"$RED_OUT" grep -Em3 "$SAN_RE"; FAIL=1
elif [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -q "^FAIL: A: the ping was answered" && ! <<<"$RED_OUT" grep -q "^FAIL: setup"; then
    echo "  [red] case A failed, as required: $(<<<"$RED_OUT" grep -m1 '^FAIL: A:')"
else
    echo "GATE FAILURE: the red arm did not fail case A (rc=$RED_RC)"; <<<"$RED_OUT" grep -E '(FAIL)' | head -8; FAIL=1
fi

# ── GREEN ────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; GREEN_OUT="$(timeout 120 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?; set -e
if [ "$GREEN_RC" -eq 0 ] && ! <<<"$GREEN_OUT" grep -Eq "$SAN_RE" && <<<"$GREEN_OUT" grep -q "^PASS: ping_echo_kat" \
; then
    echo "  [green] all cases pass"
else
    echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -E "FAIL|$SAN_RE" | head -12; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: ping_echo_kat (A red-then-green, B guard, buffer gates; ASan)"
    exit 0
fi
echo "FAIL: ping_echo_kat"
exit 1
