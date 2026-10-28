#!/usr/bin/env bash
# Host KAT runner: BRKeySetPubKey takes a key only when it has a public key's length (33 or 65 bytes),
# checked at runtime, in every build type. See pubkey_length_runtime_kat_main.c for the cases.
#
# BUILT WITH -DNDEBUG, the Release reality: the release native build passes -DNDEBUG (AGP Release /
# RelWithDebInfo), so an assert() is not in the shipped library. The shipped arm is ALSO built without
# NDEBUG: a length that is refused in one build type and ends the process in another is not "refused in
# every build type".
#
# MACRO CONVENTION: PRESENCE of -DPUBKEY_LENGTH_RUNTIME_UNFIXED selects the comparison arm, the earlier
# shape whose only length check was an assert().
#   comparison arm (NDEBUG; 64- and 32-bit): len256 and len_edge MUST be reported by AddressSanitizer as
#     a WRITE of exactly that many bytes into the caller's stack variable `key`, and must not return.
#     The valid case must give the shipped arm's answers. If the comparison arm stops showing this, the
#     KAT can no longer see what the runtime check governs, and the suite fails.
#   shipped arm (NDEBUG and debug; 64- and 32-bit): every case returns with no sanitizer report, and the
#     four builds give identical answers.
# Every run is time-limited (bounded): a sanitizer report must not be able to hang the sweep.
set -uo pipefail   # not -e: the exit codes of the arms are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=20

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRCrypto.c"
       "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
       "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")

build() {   # <out> <64|32> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/pubkey_length_runtime_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

# bounded <outfile> <cmd...>: exit code of the command, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog subshell inherits this script's EXIT trap; a plain kill would run it and remove
    # $BUILD_DIR under the other arms. Drop the trap inside, and stop the watchdog with a signal that
    # runs no trap at all.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

FAIL=0
ARMS=("red64 64 -DNDEBUG -DPUBKEY_LENGTH_RUNTIME_UNFIXED" "red32 32 -DNDEBUG -DPUBKEY_LENGTH_RUNTIME_UNFIXED"
      "green64 64 -DNDEBUG" "green32 32 -DNDEBUG" "debug64 64" "debug32 32")
for arm in "${ARMS[@]}"; do
    set -- $arm; name="$1"; bits="$2"; shift 2
    build "$BUILD_DIR/$name" "$bits" "$@" || { echo "GATE FAILURE: $name did not compile (a build error is not evidence)."; exit 1; }
done

must_return() {   # <arm> <case>
    bounded "$BUILD_DIR/$1.$2.out" "$BUILD_DIR/$1" "$2"; local rc=$?
    if [ $rc -eq 124 ]; then echo "  [$1 $2] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1
    elif grep -Eq "$SAN_RE" "$BUILD_DIR/$1.$2.out"; then
        echo "  [$1 $2] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE|WRITE of size|READ of size" "$BUILD_DIR/$1.$2.out" | head -3 | sed 's/^/      /'; FAIL=1
    elif [ $rc -ne 0 ]; then echo "  [$1 $2] GATE FAILURE: rc=$rc"; grep -E '^FAIL|Assertion|calling' "$BUILD_DIR/$1.$2.out" | head -4 | sed 's/^/      /'; FAIL=1
    else echo "  [$1 $2] returned cleanly, every check held"; fi
}

must_fault() {   # <arm> <case>: a WRITE of exactly the call's length into the caller's stack `key`, and no return
    bounded "$BUILD_DIR/$1.$2.out" "$BUILD_DIR/$1" "$2"; local rc=$? out="$BUILD_DIR/$1.$2.out" len
    len="$(sed -n "s/^$2: calling BRKeySetPubKey with \([0-9][0-9]*\) bytes.*/\1/p" "$out")"
    if [ $rc -eq 124 ]; then echo "  [$1 $2] GATE FAILURE: killed at the limit, no report (not the expected result)"; FAIL=1
    elif [ $rc -eq 0 ] || [ -z "$len" ] || grep -q "^$2: returned" "$out" ||
         ! grep -q 'ERROR: AddressSanitizer' "$out" || ! grep -q "WRITE of size $len " "$out" ||
         ! grep -q 'is located in stack of thread' "$out" || ! grep -q "'key'" "$out"; then
        echo "  [$1 $2] GATE FAILURE: the comparison arm did not show the expected report (rc=$rc); this KAT cannot see what the runtime check governs"
        head -12 "$out" | sed 's/^/      /'; FAIL=1
    else
        echo "  [$1 $2] reported as expected: $(grep -m1 -o "WRITE of size $len" "$out") into the caller's stack 'key', no return (rc=$rc)"
    fi
}

echo "--- COMPARISON ARM (-DNDEBUG -DPUBKEY_LENGTH_RUNTIME_UNFIXED): a longer key is written past BRKey ---"
for arm in red64 red32; do for c in len256 len_edge; do must_fault "$arm" "$c"; done; done

echo "--- SHIPPED ARM: every case returns, with -DNDEBUG and without, in both word sizes ---"
for arm in green64 green32 debug64 debug32; do for c in valid sweep len256 len_edge; do must_return "$arm" "$c"; done; done

echo "--- the comparison arm takes exactly what the shipped arm takes ---"
for arm in red64 red32; do must_return "$arm" valid; done
for bits in 64 32; do
    if cmp -s "$BUILD_DIR/red$bits.valid.out" "$BUILD_DIR/green$bits.valid.out"; then echo "  [$bits] valid: same answers with and without the runtime check"
    else echo "  [$bits] GATE FAILURE: valid answers differ"; diff "$BUILD_DIR/red$bits.valid.out" "$BUILD_DIR/green$bits.valid.out" | head -6 | sed 's/^/      /'; FAIL=1; fi
done

echo "--- the four shipped builds give identical answers ---"
for c in valid sweep len256 len_edge; do
    same=1
    for arm in green32 debug64 debug32; do cmp -s "$BUILD_DIR/$arm.$c.out" "$BUILD_DIR/green64.$c.out" || same=0; done
    if [ $same -eq 1 ]; then echo "  [$c] identical in green64, green32, debug64, debug32"
    else echo "  [$c] GATE FAILURE: answers differ between builds"; for arm in green32 debug64 debug32; do diff "$BUILD_DIR/green64.$c.out" "$BUILD_DIR/$arm.$c.out" | head -4 | sed "s/^/      $arm: /"; done; FAIL=1; fi
done

echo "--- the answers, as the shipped 64-bit Release build gives them ---"
sed 's/^/  /' "$BUILD_DIR/green64.valid.out" "$BUILD_DIR/green64.sweep.out" "$BUILD_DIR/green64.len256.out" "$BUILD_DIR/green64.len_edge.out"

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: pubkey_length_runtime_kat"; exit 0; else echo "FAIL: pubkey_length_runtime_kat"; exit 1; fi
