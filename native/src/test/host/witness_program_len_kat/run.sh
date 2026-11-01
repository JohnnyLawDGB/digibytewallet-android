#!/usr/bin/env bash
# Host KAT runner: the wallet pays only to witness programs that someone controls.
# See witness_program_len_kat_main.c for the invariant and the cases.
#
# Two builds under AddressSanitizer:
#   COMPARISON ARM (-DWITNESS_PROGRAM_LEN_UNFIXED)  exactly the REFUSED_BY_FIX cases fail.
#   SHIPPED ARM                                      every case passes.
#
# MACRO CONVENTION: PRESENCE of -DWITNESS_PROGRAM_LEN_UNFIXED selects the comparison arm.
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
REFUSED_BY_FIX=11   # v1/20 reported + v1 at 2,16,20,31,33,40 + v2/20, v2/32, v16/32, v16/40

if ! grep -q 'WITNESS_PROGRAM_LEN_UNFIXED' "$CORE_DIR/BRAddress.c"; then
    echo "GATE FAILURE: BRAddress.c has no WITNESS_PROGRAM_LEN_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRAddress.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c"
       "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
       "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")

build() {   # <out> [-D...]
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address,undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/witness_program_len_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

FAIL=0
for arm in red green; do
    if [ "$arm" = red ]; then build "$BUILD_DIR/$arm" -DWITNESS_PROGRAM_LEN_UNFIXED >"$BUILD_DIR/$arm.log" 2>&1
    else build "$BUILD_DIR/$arm" >"$BUILD_DIR/$arm.log" 2>&1; fi
    if [ ! -x "$BUILD_DIR/$arm" ]; then
        echo "GATE FAILURE: $arm did not compile (a build error is not evidence)."; head -20 "$BUILD_DIR/$arm.log"; exit 1
    fi
    timeout 20 "$BUILD_DIR/$arm" >"$BUILD_DIR/$arm.out" 2>&1; rc=$?
    if grep -Eq "$SAN_RE" "$BUILD_DIR/$arm.out"; then
        echo "  [$arm] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$BUILD_DIR/$arm.out" | head -3; FAIL=1; continue
    fi
    nfail=$(grep -c '^  FAIL' "$BUILD_DIR/$arm.out")
    governed_fail=$(sed -n '/^--- the cases the fix governs ---$/,$p' "$BUILD_DIR/$arm.out" | grep -c '^  FAIL')
    if [ "$arm" = red ]; then
        if [ $rc -eq 1 ] && [ "$nfail" -eq "$REFUSED_BY_FIX" ] && [ "$governed_fail" -eq "$REFUSED_BY_FIX" ]; then
            echo "  [red] failed exactly the $REFUSED_BY_FIX governed cases, as required"
        else
            echo "  [red] GATE FAILURE: rc=$rc, $nfail failures ($governed_fail governed); want exactly $REFUSED_BY_FIX governed"
            sed 's/^/      /' "$BUILD_DIR/$arm.out"; FAIL=1
        fi
    else
        if [ $rc -eq 0 ] && [ "$nfail" -eq 0 ]; then
            echo "  [green] all $(grep -c '^  PASS' "$BUILD_DIR/$arm.out") cases pass"
        else
            echo "  [green] GATE FAILURE: rc=$rc, $nfail failures"; grep '^  FAIL' "$BUILD_DIR/$arm.out" | sed 's/^/      /'; FAIL=1
        fi
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: witness_program_len_kat"; exit 0; else echo "FAIL: witness_program_len_kat"; exit 1; fi
