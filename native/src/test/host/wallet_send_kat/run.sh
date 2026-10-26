#!/usr/bin/env bash
# Host KAT runner for BRWalletSend.h: the own-send registration rule (one owner per
# object: the wallet holds a COPY) and the publish verdict ring (EALREADY never recorded).
#
#   RED    -DWALLET_SEND_SAME_OBJECT_UNFIXED -DPUBLISH_RING_RECORDS_EALREADY_UNFIXED: every RED
#          case MUST fail and every GUARD case pass. (nonwallet_copy_released is RED because in
#          that arm the wallet takes the caller's object, so there is no copy to release.)
#   GUARD  cases pin what the seams do not reach, each checked against a scratch-patched
#          header: the record's timestamp, Trusted registration of a wallet-coin spend,
#          ECANCELED recorded, and retention at Android's literal 32 entries.
#   GREEN  the shipped rules: every case passes, ALL PASS, exit 0. Both arms under ASan.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail   # not -e: the comparison arm's nonzero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(copy_not_same_object ealready_never_overwrites nonwallet_copy_released)
GUARD_CASES=(copy_registered timestamp_set_before_register original_free_is_safe
             republish_keeps_the_record unknown_reads_pending verdict_recorded
             republish_replaces_its_verdict other_hash_still_pending oldest_ages_out_to_pending
             record_timestamp_set wallet_spend_registers_trusted ecanceled_recorded
             ring_keeps_32 ring_drops_after_32)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c"
    "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() {
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/wallet_send_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seam is real ------------------------------------------------------
# -D silently does nothing if the header lost its seam, and -w would hide it.
if [ ! -f "$CORE_DIR/BRWalletSend.h" ]; then
    echo "GATE FAILURE: $CORE_DIR has no BRWalletSend.h."
    exit 1
fi
if ! grep -q "WALLET_SEND_SAME_OBJECT_UNFIXED" "$CORE_DIR/BRWalletSend.h" || ! grep -q "PUBLISH_RING_RECORDS_EALREADY_UNFIXED" "$CORE_DIR/BRWalletSend.h"; then
    echo "GATE FAILURE: BRWalletSend.h has no WALLET_SEND_SAME_OBJECT_UNFIXED seam; -D would be inert."
    exit 1
fi

FAIL=0

if ! build "$BUILD_DIR/red" -DWALLET_SEND_SAME_OBJECT_UNFIXED -DPUBLISH_RING_RECORDS_EALREADY_UNFIXED; then
    echo "GATE FAILURE: the comparison arm did not compile (a build error is not evidence)."
    exit 1
fi
if ! build "$BUILD_DIR/green"; then
    echo "GATE FAILURE: the shipped arm did not compile."
    exit 1
fi

echo "--- COMPARISON ARM (both RED seams): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
if echo "$out" | grep -Eq "$SAN_RE"; then
    echo "  [red] GATE FAILURE: sanitizer report in the comparison arm (rc=$rc)"
    echo "$out" | grep -E "$SAN_RE" | head -3 | sed 's/^/      /'; FAIL=1
fi
if [ $rc -eq 0 ]; then
    echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1
fi
for c in "${RED_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c fail$"; then
        echo "  [red $c] failed, as it must: $(echo "$out" | grep "^FAIL: $c" | head -1)"
    else
        echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1
    fi
done
for c in "${GUARD_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c pass$"; then
        echo "  [red $c] GUARD held in the comparison arm"
    else
        echo "  [red $c] GATE FAILURE: GUARD case did not hold in the comparison arm"
        echo "$out" | grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1
    fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
if echo "$out" | grep -Eq "$SAN_RE"; then
    echo "  [green] GATE FAILURE: sanitizer report in the shipped arm (rc=$rc)"
    echo "$out" | grep -E "$SAN_RE" | head -3 | sed 's/^/      /'; FAIL=1
fi
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"
    echo "$out" | grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c pass$"; then
        echo "  [green $c] passed"
    else
        echo "  [green $c] GATE FAILURE: not passed in the shipped arm"; FAIL=1
    fi
done

if [ $FAIL -eq 0 ]; then
    echo "wallet_send_kat: GATE HELD (RED shared the object and recorded EALREADY; GREEN did neither)"
fi
exit $FAIL
