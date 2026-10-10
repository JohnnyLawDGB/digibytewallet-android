#!/usr/bin/env bash
# Host KAT runner for BRWalletOpen.h -- the one recipe for opening a wallet from its
# seed (which key trees it watches). See wallet_open_kat_main.c for the defect and the
# oracle.
#
# Two builds, so the recipe is a real red-before-green gate:
#
#   RED    -DWALLET_OPEN_SINGLE_TREE_UNFIXED restores the pre-fix iOS recipe
#          (BRWalletNew over the BIP84 key alone). Every RED case MUST fail -- the
#          legacy and Taproot trees are not watched -- and every GUARD case must
#          still pass, so the failure is the defect under test and not a broken build.
#   GREEN  the shipped recipe: every case passes, ALL PASS, exit 0.
#
# Both arms build with AddressSanitizer; a sanitizer report fails the gate.
#
# CORE_DIR may be set to run this against a core checkout other than the android
# submodule (the iOS repo runs it against its own pinned core).
#
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

RED_CASES=(legacy_p2pkh_watched legacy_p2wpkh_watched bip86_watched)
GUARD_CASES=(bip84_vector bip84_watched receive_is_bip84 keys_open_matches_seed_open
             trees_constant_names_all_three)

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
        "$SCRIPT_DIR/wallet_open_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seam is real ------------------------------------------------------
# -D silently does nothing if the header lost its seam, and -w would hide it.
if [ ! -f "$CORE_DIR/BRWalletOpen.h" ]; then
    echo "GATE FAILURE: $CORE_DIR has no BRWalletOpen.h."
    exit 1
fi
if ! grep -q "WALLET_OPEN_SINGLE_TREE_UNFIXED" "$CORE_DIR/BRWalletOpen.h"; then
    echo "GATE FAILURE: BRWalletOpen.h has no WALLET_OPEN_SINGLE_TREE_UNFIXED seam; -D would be inert."
    exit 1
fi

FAIL=0

if ! build "$BUILD_DIR/red" -DWALLET_OPEN_SINGLE_TREE_UNFIXED; then
    echo "GATE FAILURE: the comparison arm did not compile (a build error is not evidence)."
    exit 1
fi
if ! build "$BUILD_DIR/green"; then
    echo "GATE FAILURE: the shipped arm did not compile."
    exit 1
fi

echo "--- COMPARISON ARM (-DWALLET_OPEN_SINGLE_TREE_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
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
    echo "wallet_open_kat: GATE HELD (RED failed on the single-tree recipe; GREEN passed)"
fi
exit $FAIL
