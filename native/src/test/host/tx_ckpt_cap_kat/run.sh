#!/usr/bin/env bash
# Host KAT runner: TX-CKPT-CAP -- the saved-transactions writer and both readers share one limit
# (BR_SAVED_TRANSACTIONS_MAX_COUNT, BRSavedTransactions.h), so the wallet never writes a checkpoint
# its reader drops, and a wallet past the old 10,000-record reader limit restores in full. See the
# _main.c for the cases.
#
#   RED    -DTX_CKPT_CAP_UNFIXED: readers refuse above 10,000, the writer writes any count.
#          over_old_cap_round_trips and writer_never_emits_refused MUST fail; the GUARD cases pass.
#   GREEN  the shipped limit: every case passes, ALL PASS, exit 0.
#   Both arms under AddressSanitizer; any sanitizer report fails the gate.
# Also held by reading the source: the bridge reader takes its limit from the core, not a literal.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
RED_CASES=(over_old_cap_round_trips writer_never_emits_refused)
GUARD_CASES=(at_limit_round_trips hostile_count_refused old_blob_over_limit_refused real_writer_vector_current)
# The writer's blob for a fixed 3-record wallet; SavedTransactionsCheckpointTest (core, JVM) reads it.
export TX_CKPT_VECTOR="$SCRIPT_DIR/real_writer_3.hex"

# Seam self-check: a -D that names nothing builds the shipped code, and the RED arm proves nothing.
for f in "$CORE_DIR/BRSavedTransactions.h" "$CORE_DIR/BRWallet.c"; do
    grep -q "TX_CKPT_CAP_UNFIXED" "$f" || { echo "GATE FAILURE: $f has no TX_CKPT_CAP_UNFIXED seam; -D would be inert."; exit 1; }
done
# Source gate: the bridge's limit is the core's constant (one limit, not two that can drift).
grep -Eq '^#define SAVED_TRANSACTIONS_MAX_COUNT[[:space:]]+BR_SAVED_TRANSACTIONS_MAX_COUNT[[:space:]]*$' \
    "$BRIDGE_DIR/saved_transactions_deserialize.h" || {
    echo "GATE FAILURE: saved_transactions_deserialize.h does not take its limit from BR_SAVED_TRANSACTIONS_MAX_COUNT"; exit 1; }
# Source gate: the writer checks the shared limit.
grep -q 'txCount > BR_SAVED_TRANSACTIONS_MAX_COUNT' "$CORE_DIR/BRWallet.c" || {
    echo "GATE FAILURE: BRWalletSerializeTransactions does not check BR_SAVED_TRANSACTIONS_MAX_COUNT"; exit 1; }

shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
build() {
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -O1 -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_ckpt_cap_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}

FAIL=0
build "$BUILD_DIR/red" -DTX_CKPT_CAP_UNFIXED || { echo "GATE FAILURE: the comparison arm did not compile"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the shipped arm did not compile"; exit 1; }

echo "--- COMPARISON ARM (-DTX_CKPT_CAP_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
out="$(timeout -k 5 900 "$BUILD_DIR/red" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; FAIL=1; }
<<<"$out" grep -q "^FAIL: setup" && { echo "  [red] GATE FAILURE: setup failed"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c fail$" && echo "  [red $c] failed, as it must" || { echo "  [red $c] GATE FAILURE: did not fail"; FAIL=1; }
done
for c in "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [red $c] GUARD held" || { echo "  [red $c] GATE FAILURE: GUARD did not hold"; FAIL=1; }
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$(timeout -k 5 900 "$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "tx_ckpt_cap_kat: GATE HELD (RED wrote a checkpoint its reader dropped; GREEN round-tripped every count it wrote)"
exit $FAIL
