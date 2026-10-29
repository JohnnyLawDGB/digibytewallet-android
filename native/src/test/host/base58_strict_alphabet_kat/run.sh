#!/usr/bin/env bash
# Host KAT runner: a base58 string is decoded only if every character in it is in the alphabet; any
# other string is answered 0. See base58_strict_alphabet_kat_main.c for the invariant and the cases.
#
# Four builds, all under AddressSanitizer: the comparison arm (-DBASE58_STRICT_ALPHABET_UNFIXED) and
# the shipped arm, each 64-bit and 32-bit. Every run is time-limited.
#   COMPARISON ARM  in `trailing`, `every_byte` and `middle`, every line that is not a GUARD must
#                   fail and every GUARD line must pass; `vectors` must pass.
#   SHIPPED ARM     every case returns 0, within LIMIT_S, with no sanitizer report.
#   SAME ANSWERS    `vectors` prints the same lines in all four builds.
#
# MACRO CONVENTION: PRESENCE of -DBASE58_STRICT_ALPHABET_UNFIXED selects the comparison arm.
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail   # not -e: exit codes of the arms are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60       # every case returns in well under a second; the limit only stops a run that wedges

if ! grep -q 'BASE58_STRICT_ALPHABET_UNFIXED' "$CORE_DIR/BRBase58.c"; then
    echo "GATE FAILURE: BRBase58.c has no BASE58_STRICT_ALPHABET_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRBase58.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRBIP38Key.c"
       "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRNetwork.c"
       "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c"
       "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
       "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")

build() {   # <out> <64|32> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/base58_strict_alphabet_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

# bounded <limit> <outfile> <cmd...>: exit code of the command, or 124 if it was stopped at the limit.
bounded() {
    local limit="$1" outfile="$2"; shift 2
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog subshell inherits this script's EXIT trap; a plain kill would run it and
    # remove $BUILD_DIR under the other arms. Drop the trap inside, and stop the watchdog with a
    # signal that runs no trap at all.
    ( trap - EXIT; sleep "$limit"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

ARMS=("red64 64 -DBASE58_STRICT_ALPHABET_UNFIXED" "green64 64" "red32 32 -DBASE58_STRICT_ALPHABET_UNFIXED" "green32 32")
pids=()
for arm in "${ARMS[@]}"; do
    set -- $arm; name="$1"; bits="$2"; shift 2
    ( trap - EXIT; build "$BUILD_DIR/$name" "$bits" "$@" >"$BUILD_DIR/$name.build.log" 2>&1 ) &
    pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
for arm in "${ARMS[@]}"; do
    set -- $arm
    if [ ! -x "$BUILD_DIR/$1" ]; then
        echo "GATE FAILURE: $1 did not compile (a build error is not evidence)."
        head -20 "$BUILD_DIR/$1.build.log" | sed 's/^/    /'
        exit 1
    fi
done

FAIL=0
lines() {   # <out> <PASS|FAIL> <guard|other>: how many check lines of that verdict and kind
    grep -E "^  $2: " "$1" | if [ "$3" = guard ]; then grep -c ': GUARD'; else grep -vc ': GUARD'; fi
}

for bits in 64 32; do
    arm="red$bits"
    echo "--- COMPARISON ARM, ${bits}-bit (-DBASE58_STRICT_ALPHABET_UNFIXED): the KAT sees what the alphabet check governs ---"
    for c in trailing every_byte middle; do
        out="$BUILD_DIR/$arm.$c.out"
        bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" "$c"; rc=$?
        red_fail="$(lines "$out" FAIL other)"; red_pass="$(lines "$out" PASS other)"
        guard_pass="$(lines "$out" PASS guard)"; guard_fail="$(lines "$out" FAIL guard)"
        if [ $rc -eq 1 ] && ! grep -Eq "$SAN_RE" "$out" && [ "$red_fail" -gt 0 ] && [ "$red_pass" -eq 0 ] && [ "$guard_fail" -eq 0 ]; then
            held=""; [ "$guard_pass" -gt 0 ] && held="; $guard_pass GUARD lines held"
            echo "  [$arm $c] failed, as required: $red_fail of $red_fail lines accepted a string that is not wholly base58$held"
        else
            echo "  [$arm $c] GATE FAILURE: rc=$rc; lines: $red_fail failed and $red_pass passed (all must fail), $guard_fail GUARD lines failed (none may)"
            grep -E "$SAN_RE" "$out" | head -2 | sed 's/^/      /'; FAIL=1
        fi
    done
    out="$BUILD_DIR/$arm.vectors.out"
    bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" vectors; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out"; then echo "  [$arm vectors] $(tail -1 "$out")"
    else echo "  [$arm vectors] GATE FAILURE: rc=$rc"; grep -E 'FAIL|ERROR' "$out" | head -4 | sed 's/^/      /'; FAIL=1; fi
done

for bits in 64 32; do
    arm="green$bits"
    echo "--- SHIPPED ARM, ${bits}-bit: every case passes, with no sanitizer report ---"
    for c in vectors trailing every_byte middle; do
        out="$BUILD_DIR/$arm.$c.out"
        bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" "$c"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [$arm $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1
        elif grep -Eq "$SAN_RE" "$out"; then
            echo "  [$arm $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$out" | head -2 | sed 's/^/      /'; FAIL=1
        elif [ $rc -ne 0 ]; then
            echo "  [$arm $c] GATE FAILURE: rc=$rc"; grep '^  FAIL' "$out" | head -4 | sed 's/^/      /'; FAIL=1
        else
            echo "  [$arm $c] $(tail -1 "$out") ($(grep -c '^  PASS' "$out") lines)"
        fi
    done
done

echo "--- the same answers: every value decodes the same in all four builds ---"
for pair in "red64 green64" "red32 green32" "green32 green64"; do
    set -- $pair
    if cmp -s "$BUILD_DIR/$1.vectors.out" "$BUILD_DIR/$2.vectors.out"; then
        echo "  [$1 = $2] identical ($(grep -c '^  PASS' "$BUILD_DIR/$2.vectors.out") lines)"
    else
        echo "  [$1 = $2] GATE FAILURE: answers differ"
        diff "$BUILD_DIR/$1.vectors.out" "$BUILD_DIR/$2.vectors.out" | head -6 | sed 's/^/      /'; FAIL=1
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: base58_strict_alphabet_kat"; exit 0; else echo "FAIL: base58_strict_alphabet_kat"; exit 1; fi
