#!/usr/bin/env bash
# Host KAT runner: a data push in a script never extends past the script.
# See script_push_bounds_kat_main.c for the invariant and the two cases.
#
# The red evidence here is a call that DOES NOT RETURN, so every run of the 32-bit comparison
# arm is time-limited and "killed at the limit" is the expected result for it. Everything else
# must return promptly with no sanitizer report.
#
# MACRO CONVENTION: PRESENCE of -DSCRIPT_PUSH_BOUND_UNFIXED selects the comparison arm.
set -uo pipefail   # not -e: exit codes of the arms are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=10

if ! grep -q 'SCRIPT_PUSH_BOUND_UNFIXED' "$CORE_DIR/BRAddress.c"; then
    echo "GATE FAILURE: BRAddress.c has no SCRIPT_PUSH_BOUND_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRGCSFilter.c"
       "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c"
       "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRCrypto.c"
       "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/crypto/groestl.c"
       "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
       "${SHA3_SRCS[@]}")

build() {   # <out> <64|32> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/script_push_bounds_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

# bounded <outfile> <cmd...>: exit code of the command, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

FAIL=0
for arm in "red64 64 -DSCRIPT_PUSH_BOUND_UNFIXED" "green64 64" "red32 32 -DSCRIPT_PUSH_BOUND_UNFIXED" "green32 32"; do
    set -- $arm; name="$1"; bits="$2"; shift 2
    build "$BUILD_DIR/$name" "$bits" "$@" || { echo "GATE FAILURE: $name did not compile (a build error is not evidence)."; exit 1; }
done

must_return() {   # <arm> <case>
    bounded "$BUILD_DIR/$1.$2.out" "$BUILD_DIR/$1" "$2"; local rc=$?
    if [ $rc -eq 124 ]; then echo "  [$1 $2] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1
    elif grep -Eq "$SAN_RE" "$BUILD_DIR/$1.$2.out"; then echo "  [$1 $2] GATE FAILURE: sanitizer report"; head -4 "$BUILD_DIR/$1.$2.out" | sed 's/^/      /'; FAIL=1
    elif [ $rc -ne 0 ]; then echo "  [$1 $2] GATE FAILURE: rc=$rc"; FAIL=1
    else echo "  [$1 $2] returned cleanly"; fi
}

echo "--- COMPARISON ARM, 32-bit: the parse MUST NOT return (killed at ${LIMIT_S}s) ---"
bounded "$BUILD_DIR/red32.parse.out" "$BUILD_DIR/red32" parse; rc=$?
if [ $rc -eq 124 ]; then echo "  [red32 parse] did not return; killed at the limit (rc=124) -- the KAT sees what the bound governs"
else echo "  [red32 parse] GATE FAILURE: returned (rc=$rc); this KAT cannot see what the bound governs"; FAIL=1; fi

echo "--- SHIPPED ARM: every case returns, in both word sizes ---"
for c in parse walk walk_wide; do must_return green32 "$c"; must_return green64 "$c"; done

echo "--- 64-bit: the bound changes nothing a 64-bit build can observe ---"
for c in parse walk walk_wide; do must_return red64 "$c"; done
for c in walk walk_wide parse; do
    if cmp -s "$BUILD_DIR/red64.$c.out" "$BUILD_DIR/green64.$c.out"; then echo "  [64 $c] same answers with and without the bound"
    else echo "  [64 $c] GATE FAILURE: answers differ"; diff "$BUILD_DIR/red64.$c.out" "$BUILD_DIR/green64.$c.out" | head -6 | sed 's/^/      /'; FAIL=1; fi
done

echo "--- 32-bit answers equal the 64-bit answers ---"
for c in walk walk_wide parse; do
    if cmp -s "$BUILD_DIR/green32.$c.out" "$BUILD_DIR/green64.$c.out"; then echo "  [$c] identical"
    else echo "  [$c] GATE FAILURE: 32-bit and 64-bit answers differ"; diff "$BUILD_DIR/green32.$c.out" "$BUILD_DIR/green64.$c.out" | head -6 | sed 's/^/      /'; FAIL=1; fi
done
echo "--- the corpus, as the shipped 64-bit arm reads it ---"; sed 's/^/  /' "$BUILD_DIR/green64.walk.out" "$BUILD_DIR/green64.walk_wide.out"

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: script_push_bounds_kat"; exit 0; else echo "FAIL: script_push_bounds_kat"; exit 1; fi
