#!/usr/bin/env bash
# Host KAT runner: a coin-generation output counts as spendable only after maturity.
# Compiles the REAL, live submodule files out of the tree with AddressSanitizer.
#
# WHAT IT PROVES. A coinbase paid to the wallet is excluded from the spendable balance, the
# UTXO set and any built transaction until the chain tip is at least the maturity depth past
# the coin's own height (100; 8 for a mainnet coin below 145,000; 100 always on testnet), is
# carried as BRWalletImmatureBalance meanwhile, is re-hidden when the tip falls or a reorg
# un-confirms it, and BRWalletSetBlockHeight rebuilds the balance only when a held output
# crosses its maturity boundary or the tip falls -- observed through a compile-time rebuild
# counter, never through timing.
#
# MACRO CONVENTION. Presence selects the arm: the comparison arm is -DCOINBASE_MATURITY_UNFIXED
# (coin-generation outputs credited like any other), the shipped arm is no -D at all, so a build
# that never defines the flag always gets the shipped code. Both arms are built with
# -DWALLET_KAT_COUNT_REBUILD, which only exposes the rebuild counter. Every case marked RED in the
# main must report "RESULT <case> fail" in the comparison arm and pass in the shipped arm; the
# comparison arm's evidence is a failed check, not a sanitizer report, and neither arm may trip
# the sanitizer. Both arms run at 64 and 32 bits.
#
# Exit code 0 = all gates held, 1 = a gate failed / ASan fault / build error.
set -uo pipefail   # not -e: the comparison arm's nonzero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(mature_100 mature_8_early_mainnet testnet_100_always immature_only_send_null rebuild_gate
           lower_tip_rehides reorg_restamp plain_receive_unaffected restore_seeds_tip balance_changed_once
           unconfirmed_stamp_keeps_tip)
GUARD_CASES=(coinbase_shape)

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c"
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

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" -DWALLET_KAT_COUNT_REBUILD "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/coinbase_maturity_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seams are real ----------------------------------------------
# -D silently loses to a plain #define, and -w hides a redefinition warning, so confirm the
# source actually carries both seams before trusting the comparison arm or the counter.
for macro in COINBASE_MATURITY_UNFIXED WALLET_KAT_COUNT_REBUILD; do
    if ! grep -q "$macro" "$CORE_DIR/BRWallet.c"; then
        echo "GATE FAILURE: BRWallet.c has no $macro seam; -D would be inert."
        exit 1
    fi
done

FAIL=0

run_bits() {
    local bits="$1" out rc c
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DCOINBASE_MATURITY_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; return
    fi

    echo "--- COMPARISON ARM (-DCOINBASE_MATURITY_UNFIXED): every RED case MUST fail, no sanitizer report ---"
    out="$("$BUILD_DIR/red${bits}" 2>&1)"; rc=$?
    echo "$out" > "$BUILD_DIR/red${bits}.log"
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$bits red] GATE FAILURE: sanitizer report in the comparison arm (rc=$rc)"
        echo "$out" | grep -E "$SAN_RE" | head -3 | sed 's/^/      /'; FAIL=1
    fi
    if [ $rc -eq 0 ]; then
        echo "  [$bits red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1
    fi
    for c in "${RED_CASES[@]}"; do
        if echo "$out" | grep -q "^RESULT $c fail$"; then
            echo "  [$bits red $c] failed, as it must"
        else
            echo "  [$bits red $c] GATE FAILURE: did not fail in the comparison arm"
            echo "$out" | grep -A0 "RESULT $c" | sed 's/^/      /'; FAIL=1
        fi
    done

    echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
    out="$("$BUILD_DIR/green${bits}" 2>&1)"; rc=$?
    echo "$out" > "$BUILD_DIR/green${bits}.log"
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$bits green] GATE FAILURE: sanitizer report in the shipped arm (rc=$rc)"
        echo "$out" | grep -E "$SAN_RE" | head -3 | sed 's/^/      /'; FAIL=1
    fi
    if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
        echo "  [$bits green] GATE FAILURE: shipped arm did not pass (rc=$rc)"
        echo "$out" | grep "FAIL:" | sed 's/^/      /'; FAIL=1
    fi
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        if echo "$out" | grep -q "^RESULT $c pass$"; then
            echo "  [$bits green $c] passed"
        else
            echo "  [$bits green $c] GATE FAILURE: not passed in the shipped arm"; FAIL=1
        fi
    done
    for c in "${GUARD_CASES[@]}"; do
        echo "  [$bits $c] GUARD: the shipped arm asserts it directly; no comparison arm expresses it"
    done
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: coinbase_maturity_kat (comparison arm failed every RED case, shipped arm clean; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: coinbase_maturity_kat"
    exit 1
fi
