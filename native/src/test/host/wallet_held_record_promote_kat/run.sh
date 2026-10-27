#!/usr/bin/env bash
# Host KAT runner: a record the wallet holds as non-wallet is moved into the wallet's transactions, with the same
# money-range and signature checks a new tx takes, once the address set grows to cover it. See
# wallet_held_record_promote_kat_main.c.
#
# Compiles the REAL submodule sources with AddressSanitizer and UndefinedBehaviorSanitizer. CORE_DIR may be set in the
# environment to run the suite against another checkout of the core.
#
# ARMS:
#   red   -DWALLET_FOREIGN_PROMOTE_UNFIXED       no promotion
#   red2  -DWALLET_PROMOTE_SAME_RECORD_UNFIXED   the wallet's own record passed back keeps the caller's route
#   green no seam flag (shipped)
# RED cases must FAIL in the red arm, RED2 in red2; every other case must PASS in each comparison arm; every case
# must PASS in the green arm; no sanitizer report anywhere.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the red arms' nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

RED_CASES=(
    promote_fresh_copy
    txadded_wallet_record
    unproven_route
    same_record_promotes
)
RED2_CASES=(
    same_record_checked
)
GUARD_CASES=(
    still_foreign_stays
    money_range_refused
)
ALL_CASES=("${RED_CASES[@]}" "${RED2_CASES[@]}" "${GUARD_CASES[@]}")

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/wallet_held_record_promote_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}

# -D silently loses to a plain #define and -w hides the warning, so confirm the sources carry the seams.
for seam in WALLET_FOREIGN_PROMOTE_UNFIXED WALLET_PROMOTE_SAME_RECORD_UNFIXED; do
    if [ "$(grep -c "$seam" "$CORE_DIR/BRWallet.c")" -lt 1 ]; then
        echo "GATE FAILURE: BRWallet.c has no $seam seam; -D would be inert."; exit 1
    fi
done

FAIL=0

run_case() {
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0" "$1" "$2" 2>&1)"
    RC=$?
}

# comparison_arm <arm> <flag> <space-separated cases that must fail>
comparison_arm() {
    local arm="$1" flag="$2" mustFail=" $3 " c
    echo "--- ${arm} ARM (${flag}): each of [${3}] MUST fail, every other case MUST pass ---"
    for c in "${ALL_CASES[@]}"; do
        run_case "$BUILD_DIR/${arm}" "$c"
        if <<<"$OUT" grep -Eq "$SAN_RE"; then
            echo "  [$arm $c] GATE FAILURE: sanitizer report"; <<<"$OUT" grep -Em3 "$SAN_RE" | sed 's/^/      /'; FAIL=1
        elif [ "${mustFail/ $c /}" != "$mustFail" ]; then
            if <<<"$OUT" grep -q "RESULT $c fail" && ! <<<"$OUT" grep -q "^FAIL: setup"; then
                echo "  [$arm $c] failed, as required: $(<<<"$OUT" grep -m1 '^FAIL:')"
            else
                echo "  [$arm $c] GATE FAILURE: did not fail as required (rc=$RC)"
                <<<"$OUT" grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
            fi
        elif <<<"$OUT" grep -q "RESULT $c pass"; then
            echo "  [$arm $c] passed (not governed by this arm)"
        else
            echo "  [$arm $c] GATE FAILURE: did not pass (rc=$RC)"
            <<<"$OUT" grep -E '^(NOTE|FAIL|RESULT)' | sed 's/^/      /' | head -8; FAIL=1
        fi
    done
}

build "$BUILD_DIR/red" -DWALLET_FOREIGN_PROMOTE_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
build "$BUILD_DIR/red2" -DWALLET_PROMOTE_SAME_RECORD_UNFIXED || { echo "GATE FAILURE: red2 did not build"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${ALL_CASES[@]}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

comparison_arm red -DWALLET_FOREIGN_PROMOTE_UNFIXED "${RED_CASES[*]}"
comparison_arm red2 -DWALLET_PROMOTE_SAME_RECORD_UNFIXED "${RED2_CASES[*]}"

echo "--- GREEN ARM (shipped): every case MUST pass, no sanitizer report ---"
for c in "${ALL_CASES[@]}"; do
    run_case "$BUILD_DIR/green" "$c"
    if ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "RESULT $c pass"; then
        echo "  [green $c] passed"
    else
        echo "  [green $c] GATE FAILURE (rc=$RC)"
        <<<"$OUT" grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" | sed 's/^/      /' | head -10; FAIL=1
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: wallet_held_record_promote_kat (${#RED_CASES[@]} red-then-green, ${#RED2_CASES[@]} red2-then-green, ${#GUARD_CASES[@]} guards; ASan+UBSan)"
    exit 0
fi
echo "FAIL: wallet_held_record_promote_kat"; exit 1
