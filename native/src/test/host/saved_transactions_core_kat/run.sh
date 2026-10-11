#!/usr/bin/env bash
# Host KAT runner for BRSavedTransactions.h -- the core reader of the saved-transactions
# blob (BRWalletSerializeTransactions writes it). See the _main.c for the cases.
#
#   RED    -DSAVED_TRANSACTIONS_METADATA_UNFIXED: a reader that restores transaction bytes
#          but drops height and timestamp. Every RED case MUST fail, every GUARD case pass.
#   GREEN  the shipped reader: every case passes, ALL PASS, exit 0. Both arms under ASan.
#
# 64-bit only. The 32-bit wrap of the old length check (BB-2026-10-09-harley F4) is gated
# by saved_data_bounds_kat; this suite gates the core copy of the reader.
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(confirmed_heights_restored timestamps_restored)
GUARD_CASES=(bytes_round_trip record_past_end_ends_walk zero_count_rejected count_over_max_rejected
             null_blob_rejected short_blob_rejected huge_length_parses_nothing)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c"
       "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c"
       "$CORE_DIR/BRSet.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
       "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c"
       "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")

build() {
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/saved_transactions_core_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

if ! grep -q "SAVED_TRANSACTIONS_METADATA_UNFIXED" "$CORE_DIR/BRSavedTransactions.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRSavedTransactions.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSAVED_TRANSACTIONS_METADATA_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSAVED_TRANSACTIONS_METADATA_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c fail$" && echo "  [red $c] failed, as it must" || { echo "  [red $c] GATE FAILURE: did not fail"; FAIL=1; }
done
for c in "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [red $c] GUARD held" || { echo "  [red $c] GATE FAILURE: GUARD did not hold"; FAIL=1; }
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; echo "$out" | grep -E "$SAN_RE" | head -3; FAIL=1; }
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; echo "$out" | grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "saved_transactions_core_kat: GATE HELD (RED dropped the metadata; GREEN restored it)"
exit $FAIL
