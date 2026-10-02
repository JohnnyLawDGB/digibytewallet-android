#!/usr/bin/env bash
# Host KAT runner: the wallet's address snapshot lists every address exactly once.
# Compiles the REAL, live submodule files out of the tree with AddressSanitizer.
#
# WHAT IT PROVES. BRWalletCopyAllAddrs / BRWalletAllAddrs emit the derived chains and then the
# explicitly-watched tail. A watched pin that belongs to one of the wallet's chains is resolved
# into the derived set when it is added, so its chain already emits it. The snapshot must list
# each address once (P2WPKH, P2TR and legacy-chain pins alike, and a pin derived because of the
# pin), keep a pin that is not derived in the tail, report the distinct count from every count
# accessor (sizing call, BRWalletAllAddrsCount, BRWalletAddrSetKey, the origins split), and give
# the compact-filter element builder a set that loses no script and repeats none.
#
# MACRO CONVENTION. Presence selects the arm: the comparison arm is -DADDR_SET_DISTINCT_UNFIXED
# (the watched tail emitted whole), the shipped arm is no -D at all, so a build that never
# defines the flag always gets the shipped code. Every RED case must report
# "RESULT <case> fail" in the comparison arm and pass in the shipped arm. GUARD cases have no
# comparison-arm expression (the earlier code already met them) and must pass in both arms.
# Neither arm may trip the sanitizer. Both arms run at 64 and 32 bits.
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

RED_CASES=(watched_derived_segwit_once watched_derived_taproot_once watched_derived_legacy_once
           watched_resolved_once counts_are_distinct filter_elements_distinct pin_derived_later)
GUARD_CASES=(watched_only_kept)

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

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/addr_set_distinct_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seam is real ------------------------------------------------
# -D silently loses to a plain #define, and -w hides a redefinition warning, so confirm the
# source actually carries the seam before trusting the comparison arm.
if ! grep -q "ADDR_SET_DISTINCT_UNFIXED" "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: BRWallet.c has no ADDR_SET_DISTINCT_UNFIXED seam; -D would be inert."
    exit 1
fi

FAIL=0

run_bits() {
    local bits="$1" out rc c
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DADDR_SET_DISTINCT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; return
    fi

    echo "--- COMPARISON ARM (-DADDR_SET_DISTINCT_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
    out="$("$BUILD_DIR/red${bits}" 2>&1)"; rc=$?
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$bits red] GATE FAILURE: sanitizer report in the comparison arm (rc=$rc)"
        echo "$out" | grep -E "$SAN_RE" | head -3 | sed 's/^/      /'; FAIL=1
    fi
    if [ $rc -eq 0 ]; then
        echo "  [$bits red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1
    fi
    for c in "${RED_CASES[@]}"; do
        if echo "$out" | grep -q "^RESULT $c fail$"; then
            echo "  [$bits red $c] failed, as it must: $(echo "$out" | awk -v c="$c" '/^RESULT /{ if ($2 == c) { print first; exit } first = "" } /^FAIL:/ && first == "" { first = $0 }')"
        else
            echo "  [$bits red $c] GATE FAILURE: did not fail in the comparison arm"
            echo "$out" | grep "RESULT $c" | sed 's/^/      /'; FAIL=1
        fi
    done
    for c in "${GUARD_CASES[@]}"; do
        if echo "$out" | grep -q "^RESULT $c pass$"; then
            echo "  [$bits red $c] GUARD held in the comparison arm"
        else
            echo "  [$bits red $c] GATE FAILURE: GUARD case did not hold in the comparison arm"; FAIL=1
        fi
    done

    echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
    out="$("$BUILD_DIR/green${bits}" 2>&1)"; rc=$?
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
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: addr_set_distinct_kat (comparison arm failed every RED case, shipped arm clean; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: addr_set_distinct_kat"
    exit 1
fi
