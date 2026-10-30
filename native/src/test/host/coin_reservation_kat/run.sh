#!/usr/bin/env bash
#
# coin_reservation_kat — a coin signed for one send is never signed into another before the first is published
# (4.1.2 row 3, N8). See the header of coin_reservation_kat_main.c for the cases.
#
#   RED    -DCOIN_RESERVATION_UNFIXED=1: every claim granted, the 4.1.1 path. Case A must FAIL there.
#   GREEN  every case passes, no AddressSanitizer report.
#   GATES  the reservation is wired where it acts: seed_sign_transaction claims after signing, and every
#          publish path releases right after the wallet registers the transaction.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

fn_body() { awk -v start="$2" '$0 ~ start {on=1} on {print} on && /^\}/ {exit}' "$1" | tr -s '[:space:]' ' '; }

SIGN="$(fn_body "$BRIDGE_DIR/jni_wallet.c" '^int seed_sign_transaction')"
[ -n "$SIGN" ] || gate_fail "seed_sign_transaction was not found in jni_wallet.c. Re-anchor the gate."
<<<"$SIGN" grep -qF "if (!coin_reservation_claim(tx->txHash, tx->inputs, tx->inCount, coin_reservation_now())) {" ||
    gate_fail "seed_sign_transaction no longer claims the coins it signed"
REG=$(grep -c '^    _registerWalletCopy(tx);$' "$BRIDGE_DIR/jni_transaction.c" || true)
REL=$(grep -A1 '^    _registerWalletCopy(tx);$' "$BRIDGE_DIR/jni_transaction.c" | grep -c '^    coin_reservation_release(tx->txHash);' || true)
[ "$REG" -ge 3 ] || gate_fail "expected the three publish paths to register the transaction (found $REG). Re-anchor the gate."
[ "$REG" -eq "$REL" ] || gate_fail "$REG publish paths register a transaction but only $REL release its claim right after"
grep -q 'src/main/jni/bridge/coin_reservation.c' "$REPO_ROOT/native/CMakeLists.txt" || gate_fail "coin_reservation.c is not in the native build"

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/coin_reservation_kat_main.c" "$BRIDGE_DIR/coin_reservation.c" -lpthread -o "$out"
}
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"

build "$BUILD_DIR/red" -DCOIN_RESERVATION_UNFIXED=1 || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
set +e; RED_OUT="$(timeout 60 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?; set -e
if <<<"$RED_OUT" grep -Eq "$SAN_RE"; then echo "GATE FAILURE: the red arm faulted"; FAIL=1
elif [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -q "^FAIL: A: send B was signed on coin Y"; then echo "  [red] case A failed, as required"
else echo "GATE FAILURE: the red arm did not fail on case A (rc=$RED_RC)"; FAIL=1; fi

build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
set +e; GREEN_OUT="$(timeout 60 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?; set -e
if [ "$GREEN_RC" -eq 0 ] && ! <<<"$GREEN_OUT" grep -Eq "$SAN_RE" && <<<"$GREEN_OUT" grep -q "^PASS: coin_reservation_kat"; then
    echo "  [green] all cases pass"
else
    echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -E "FAIL|$SAN_RE" | head -8; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then echo "PASS: coin_reservation_kat (case A red-then-green, B-F guards, source gates; ASan)"; exit 0; fi
echo "FAIL: coin_reservation_kat"; exit 1
