#!/usr/bin/env bash
# Host KAT runner: a DigiDollar-shaped output is credited only at or above the network's
# activation floor. Compiles the REAL, live submodule files out of the tree with AddressSanitizer.
#
# WHAT IT PROVES. A DD token output confirmed one block below BRNetworkDigiDollarActivationHeight()
# (23,627,520 mainnet / 600 testnet) credits nothing and lists no DD coin -- the transaction stays
# ordinary history -- while one confirmed at or above the floor is credited normally; an
# unconfirmed one credits nothing; a reorg un-confirm, a re-stamp below and a re-stamp at the floor
# each re-evaluate the gate; and a DD transfer cannot be built from a coin below the floor.
#
# MACRO CONVENTION. Presence selects the arm: the comparison arm is -DDD_ACTIVATION_FLOOR_UNFIXED
# (a DD-shaped output credited at any confirmed height), the shipped arm is no -D at all, so a build
# that never defines the flag always gets the shipped code. Every case marked RED in the main must
# report "RESULT <case> fail" in the comparison arm and pass in the shipped arm; the comparison
# arm's evidence is a failed check, not a sanitizer report, and neither arm may trip the sanitizer.
# Both arms run at 64 and 32 bits.
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

RED_CASES=(mainnet_floor testnet_floor unconfirmed_then_confirmed reorg_restamp spend_below_floor_null)
GUARD_CASES=(accessor)

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
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/dd_activation_floor_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seam is real ------------------------------------------------
# -D silently loses to a plain #define, and -w hides a redefinition warning, so confirm the source
# actually carries the seam before trusting the comparison arm.
if ! grep -q 'DD_ACTIVATION_FLOOR_UNFIXED' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: BRWallet.c has no DD_ACTIVATION_FLOOR_UNFIXED seam; -D would be inert."
    exit 1
fi
# The floors the gate reads must be the pinned ones (the main asserts the accessor too).
for needle in 'DD_ACTIVATION_HEIGHT_MAINNET 23627520u' 'DD_ACTIVATION_HEIGHT_TESTNET 600u'; do
    if ! grep -q "$needle" "$CORE_DIR/BRNetwork.h"; then
        echo "GATE FAILURE: BRNetwork.h does not pin '$needle'."
        exit 1
    fi
done

FAIL=0

run_bits() {
    local bits="$1" out rc c
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DDD_ACTIVATION_FLOOR_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; return
    fi

    echo "--- COMPARISON ARM (-DDD_ACTIVATION_FLOOR_UNFIXED): every RED case MUST fail, no sanitizer report ---"
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
            echo "$out" | grep "RESULT $c" | sed 's/^/      /'; FAIL=1
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
    echo "PASS: dd_activation_floor_kat (comparison arm failed every RED case, shipped arm clean; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: dd_activation_floor_kat"
    exit 1
fi
