#!/usr/bin/env bash
# Host KAT runner: the sorted insertion of an unconfirmed transaction cannot be driven
# superlinear by the length of a peer-relayed input chain.
#
# WHAT IT PROVES. A peer can relay unconfirmed transactions that pay the wallet; each is
# registered and kept sorted by an insertion sort whose comparison walks a transaction's
# input chain transitively (_BRWalletTxIsAscending). A reverse-ordered chain made every
# comparison walk the whole chain, so the insertion was cubic and ran under the wallet
# lock. The shipped code bounds the links any one comparison walks; the bound is far above
# the ancestor depth of any honest unconfirmed chain, so the order the wallet presents is
# unchanged.
#
# The gate is DETERMINISTIC, not a wall-clock race. The build defines KAT_ASCENDING_COUNTER,
# so BRWallet.c records the most links any single comparison walked (_kat_asc_max). For a
# reverse-ordered chain of N transactions:
#   * the shipped arm holds _kat_asc_max at the per-comparison bound (budget + 1) for every
#     N -- a constant independent of the chain length;
#   * the comparison arm (-DWALLET_ASCENDING_DEPTH_UNFIXED) lets it grow to ~N -- the
#     superlinear behaviour the bound removes.
# Both arms must present an identical order for an honest mixed set of unconfirmed txs.
#
# MACRO CONVENTION. Presence: the comparison arm is -DWALLET_ASCENDING_DEPTH_UNFIXED, the
# shipped arm is no -D at all, so a shipped build always gets the bounded code.
#
# Exit code 0 = all checks passed, 1 = check failed / ASan fault / build error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"

export ASAN_OPTIONS="abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

# The per-comparison bound the shipped source holds, plus the one top node each comparison
# counts: WALLET_MAX_ASCENDING_STEPS (256) + 1. Read from the source so the two cannot drift.
BUDGET="$(grep -oE '#define[[:space:]]+WALLET_MAX_ASCENDING_STEPS[[:space:]]+[0-9]+' "$CORE_DIR/BRWallet.c" | grep -oE '[0-9]+$')"
if [ -z "${BUDGET:-}" ]; then echo "GATE FAILURE: could not read WALLET_MAX_ASCENDING_STEPS from BRWallet.c"; exit 1; fi
BOUND=$((BUDGET + 1))

# Confirm the presence seam exists, or -D would be inert.
if ! grep -q 'WALLET_ASCENDING_DEPTH_UNFIXED' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: BRWallet.c has no WALLET_ASCENDING_DEPTH_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=(
    "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}"
)

build() {   # <out> [extra -D]
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        -DKAT_ASCENDING_COUNTER "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/wallet_sort_bound_kat_main.c" "${UNITS[@]}" \
        -lpthread -lm -o "$out"
}

FAIL=0
if ! build "$BUILD_DIR/green"; then echo "GATE FAILURE: shipped arm did not compile."; exit 1; fi
if ! build "$BUILD_DIR/red" -DWALLET_ASCENDING_DEPTH_UNFIXED; then echo "GATE FAILURE: comparison arm did not compile."; exit 1; fi

maxsteps() {   # <bin> <N> -> echoes maxsteps, or "SAN"/"ERR"
    local out; out="$("$1" bound "$2" 2>&1)"
    if echo "$out" | grep -Eq "$SAN_RE"; then echo "SAN"; return; fi
    echo "$out" | grep -oE 'maxsteps [0-9]+' | grep -oE '[0-9]+$' || echo "ERR"
}

echo "per-comparison bound from source: $BUDGET (+1 top node = $BOUND)"

# SHIPPED ARM: the walk is bounded per comparison for every chain length -- the max is
# the same constant at two very different N, so it does not scale with the chain.
g_small="$(maxsteps "$BUILD_DIR/green" 300)"
g_large="$(maxsteps "$BUILD_DIR/green" 900)"
if [ "$g_small" = "$BOUND" ] && [ "$g_large" = "$BOUND" ]; then
    echo "  [green] a comparison walks at most $g_large links at N=300 and N=900 (bounded, chain-length independent)"
else
    echo "  [green] GATE FAILURE: maxsteps not held at $BOUND (N=300 -> $g_small, N=900 -> $g_large)"; FAIL=1
fi

# COMPARISON ARM: the walk scales with the chain -- the max grows with N and far exceeds
# the shipped bound, which is the superlinear driver.
r_small="$(maxsteps "$BUILD_DIR/red" 300)"
r_large="$(maxsteps "$BUILD_DIR/red" 900)"
if [ "$r_small" != "SAN" ] && [ "$r_small" != "ERR" ] && [ "$r_large" != "SAN" ] && [ "$r_large" != "ERR" ] &&
   [ "$r_large" -gt "$r_small" ] && [ "$r_small" -gt "$BOUND" ]; then
    echo "  [red] a comparison walks $r_small links at N=300 and $r_large at N=900 (scales with the chain)"
else
    echo "  [red] GATE FAILURE: the unbounded walk did not scale past the bound (N=300 -> $r_small, N=900 -> $r_large)"; FAIL=1
fi

# HONEST ORDER: identical in both arms (the bound never reorders an honest set).
og="$("$BUILD_DIR/green" honest 2>&1)"; orr="$("$BUILD_DIR/red" honest 2>&1)"
if echo "$og" | grep -Eq "$SAN_RE" || echo "$orr" | grep -Eq "$SAN_RE"; then
    echo "  [honest] GATE FAILURE: sanitizer report"; echo "$og$orr" | sed 's/^/      /' | head -6; FAIL=1
elif [ "$og" = "$orr" ] && echo "$og" | grep -q '^order:'; then
    echo "  [honest] both arms present the same order ($og)"
else
    echo "  [honest] GATE FAILURE: the honest order differs between arms"
    echo "      green: $og"; echo "      red:   $orr"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: wallet_sort_bound_kat (bounded per comparison, chain-length independent; honest order unchanged)"
    exit 0
else
    echo "FAIL: wallet_sort_bound_kat"
    exit 1
fi
