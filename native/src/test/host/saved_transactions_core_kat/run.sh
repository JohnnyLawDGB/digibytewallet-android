#!/usr/bin/env bash
# Host KAT runner for BRSavedTransactions.h -- the core reader of the saved-transactions
# blob (BRWalletSerializeTransactions writes it). See the _main.c for the cases.
#
#   RED    -DSAVED_TRANSACTIONS_METADATA_UNFIXED: a reader that restores transaction bytes
#          but drops height and timestamp. Every METADATA case MUST fail, every other pass.
#   RED    -DSAVED_TRANSACTIONS_UNSIGNED_PARSE_UNFIXED: a reader on the inferring
#          BRTransactionParse, which misreads a signed record whose scriptSig is shaped like a
#          scriptPubKey. Every PARSE case MUST fail, every other pass.
#   GREEN  the shipped reader: every case passes, ALL PASS, exit 0. All arms under ASan.
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
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

METADATA_CASES=(confirmed_heights_restored timestamps_restored)
PARSE_CASES=(signed_push20_scriptsig_restored signed_push32_scriptsig_restored)
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

for seam in SAVED_TRANSACTIONS_METADATA_UNFIXED SAVED_TRANSACTIONS_UNSIGNED_PARSE_UNFIXED; do
    if ! grep -q "$seam" "$CORE_DIR/BRSavedTransactions.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRSavedTransactions.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/red_meta" -DSAVED_TRANSACTIONS_METADATA_UNFIXED || { echo "GATE FAILURE: metadata comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/red_parse" -DSAVED_TRANSACTIONS_UNSIGNED_PARSE_UNFIXED || { echo "GATE FAILURE: parse comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

# comparison_arm <binary> <-D name> <label> <space-separated RED cases> <space-separated GUARD cases>
comparison_arm() {
    local bin="$1" macro="$2" label="$3" reds="$4" guards="$5" out rc c
    echo "--- COMPARISON ARM (-D$macro): every $label case MUST fail, every other case pass ---"
    out="$("$bin" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in $reds; do
        <<<"$out" grep -q "^RESULT $c fail$" && echo "  [red $c] failed, as it must" || { echo "  [red $c] GATE FAILURE: did not fail"; FAIL=1; }
    done
    for c in $guards; do
        <<<"$out" grep -q "^RESULT $c pass$" && echo "  [red $c] GUARD held" || { echo "  [red $c] GATE FAILURE: GUARD did not hold"; FAIL=1; }
    done
}
comparison_arm "$BUILD_DIR/red_meta" SAVED_TRANSACTIONS_METADATA_UNFIXED METADATA \
    "${METADATA_CASES[*]}" "${PARSE_CASES[*]} ${GUARD_CASES[*]}"
comparison_arm "$BUILD_DIR/red_parse" SAVED_TRANSACTIONS_UNSIGNED_PARSE_UNFIXED PARSE \
    "${PARSE_CASES[*]}" "${METADATA_CASES[*]} ${GUARD_CASES[*]}"

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${METADATA_CASES[@]}" "${PARSE_CASES[@]}" "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "saved_transactions_core_kat: GATE HELD (RED dropped the metadata / misparsed signed records; GREEN restored both)"
exit $FAIL
