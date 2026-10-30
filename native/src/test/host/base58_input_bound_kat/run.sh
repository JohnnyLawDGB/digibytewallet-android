#!/usr/bin/env bash
# Host KAT runner: a base58 string longer than any value the wallet can use is rejected before it
# is decoded, and decoding never sizes a stack buffer from the length of the string it is given.
# See base58_input_bound_kat_main.c for the invariant and the cases.
#
# Four builds, all under AddressSanitizer: the comparison arm (-DBASE58_INPUT_BOUND_UNFIXED) and
# the shipped arm, each 64-bit and 32-bit. Every run is time-limited.
#   COMPARISON ARM  `bound` must fail; every `entry` must still be decoding at RED_LIMIT_S; every
#                   `stack` must end by a signal, a sanitizer report or the limit, not by
#                   returning; every `prefix` must end with a sanitizer report. `vectors` must pass.
#   SHIPPED ARM     every case returns 0, within LIMIT_S, with no sanitizer report.
#   SAME ANSWERS    `vectors` prints the same lines in all four builds.
#
# MACRO CONVENTION: PRESENCE of -DBASE58_INPUT_BOUND_UNFIXED selects the comparison arm.
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
LIMIT_S=20       # a run that must return. It returns in well under a second.
RED_LIMIT_S=3    # a comparison-arm `entry` run, which would take minutes to finish

if ! grep -q 'BASE58_INPUT_BOUND_UNFIXED' "$CORE_DIR/BRBase58.c"; then
    echo "GATE FAILURE: BRBase58.c has no BASE58_INPUT_BOUND_UNFIXED seam; -D would be inert."
    exit 1
fi
BOUND="$(grep -oE '#define[[:space:]]+BR_BASE58_MAX_STR_LEN[[:space:]]+[0-9]+' "$CORE_DIR/BRBase58.h" | grep -oE '[0-9]+$')"
if [ -z "${BOUND:-}" ]; then echo "GATE FAILURE: BRBase58.h does not define BR_BASE58_MAX_STR_LEN."; exit 1; fi

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
        "$SCRIPT_DIR/base58_input_bound_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
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

ARMS=("red64 64 -DBASE58_INPUT_BOUND_UNFIXED" "green64 64" "red32 32 -DBASE58_INPUT_BOUND_UNFIXED" "green32 32")
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
ENTRIES=($("$BUILD_DIR/green64" list))
DECODERS=($("$BUILD_DIR/green64" list-decoders))
if [ "${#ENTRIES[@]}" -eq 0 ] || [ "${#DECODERS[@]}" -eq 0 ]; then echo "GATE FAILURE: the KAT lists no entry points."; exit 1; fi
echo "bound from BRBase58.h: $BOUND characters; ${#ENTRIES[@]} entry points: ${ENTRIES[*]}"

FAIL=0
must_return() {   # <arm> <case...>: returns 0 within LIMIT_S, with no sanitizer report
    local arm="$1"; shift
    local out="$BUILD_DIR/$arm.$(echo "$*" | tr ' ' '.').out"
    bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" "$@"; local rc=$?
    if [ $rc -eq 124 ]; then echo "  [$arm $*] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1
    elif grep -Eq "$SAN_RE" "$out"; then
        echo "  [$arm $*] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$out" | head -2 | sed 's/^/      /'; FAIL=1
    elif [ $rc -ne 0 ]; then
        echo "  [$arm $*] GATE FAILURE: rc=$rc"; grep 'FAIL' "$out" | head -4 | sed 's/^/      /'; FAIL=1
    else
        echo "  [$arm $*] $(grep -E '^(vectors|bound): |^(entry|stack|prefix) .*: answered' "$out" | tail -1)"
    fi
}

rc_of() {   # <rc file>: the exit code a background run recorded, or "none"
    local rc; rc="$(cat "$1" 2>/dev/null)"
    case "$rc" in ''|*[!0-9]*) echo none ;; *) echo "$rc" ;; esac
}

reached() {   # <out file>: the run got as far as the decode call and never answered
    grep -q ': decoding [0-9]* characters' "$1" 2>/dev/null && ! grep -q ': answered' "$1" 2>/dev/null
}

for bits in 64 32; do
    arm="red$bits"
    echo "--- COMPARISON ARM, ${bits}-bit (-DBASE58_INPUT_BOUND_UNFIXED): the KAT sees what the bound governs ---"
    # Exactly the three checks past the bound fail; everything at the bound passes in both arms.
    out="$BUILD_DIR/$arm.bound.out"
    bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" bound; rc=$?
    if [ $rc -eq 1 ] && ! grep -Eq "$SAN_RE" "$out" && [ "$(grep -c 'FAIL: ' "$out")" -eq 3 ] &&
       [ "$(grep -c 'FAIL: .*answered 0' "$out")" -eq 3 ]; then
        echo "  [$arm bound] failed, as required:"; grep 'FAIL: ' "$out" | sed 's/^ */      /'
    else
        echo "  [$arm bound] GATE FAILURE: rc=$rc; exactly the three checks past the bound must fail here, and nothing else"
        sed 's/^/      /' "$out" | head -12; FAIL=1
    fi
    must_return "$arm" vectors

    # Every entry point, at once: each must still be decoding when it is stopped.
    for e in "${ENTRIES[@]}"; do
        ( trap - EXIT; bounded "$RED_LIMIT_S" "$BUILD_DIR/$arm.entry.$e.out" "$BUILD_DIR/$arm" entry "$e"
          echo $? >"$BUILD_DIR/$arm.entry.$e.rc" ) &
    done
    wait
    for e in "${ENTRIES[@]}"; do
        rc="$(rc_of "$BUILD_DIR/$arm.entry.$e.rc")"
        if [ "$rc" = "124" ] && reached "$BUILD_DIR/$arm.entry.$e.out"; then
            printf '  [%s entry %s] still decoding at %ss; stopped\n' "$arm" "$e" "$RED_LIMIT_S"
        elif [ "$rc" = "124" ]; then
            printf '  [%s entry %s] GATE FAILURE: stopped before it reached the decode (a loaded machine?)\n' "$arm" "$e"
            FAIL=1
        else
            printf '  [%s entry %s] GATE FAILURE: returned (rc=%s); this KAT cannot see what the bound governs\n' "$arm" "$e" "$rc"
            FAIL=1
        fi
    done

    # The same, on a small thread stack: each must end without returning.
    for e in "${ENTRIES[@]}"; do
        ( trap - EXIT; bounded "$LIMIT_S" "$BUILD_DIR/$arm.stack.$e.out" "$BUILD_DIR/$arm" stack "$e"
          echo $? >"$BUILD_DIR/$arm.stack.$e.rc" ) &
    done
    wait
    for e in "${ENTRIES[@]}"; do
        rc="$(rc_of "$BUILD_DIR/$arm.stack.$e.rc")"
        kind="$(grep -oE 'ERROR: AddressSanitizer: [a-z-]+' "$BUILD_DIR/$arm.stack.$e.out" 2>/dev/null | head -1 | sed 's/ERROR: //')"
        if [ "$rc" = "none" ]; then
            printf '  [%s stack %s] GATE FAILURE: no result recorded\n' "$arm" "$e"; FAIL=1
        elif ! reached "$BUILD_DIR/$arm.stack.$e.out"; then
            printf '  [%s stack %s] GATE FAILURE: it did not reach the decode, or it answered (rc=%s)\n' "$arm" "$e" "$rc"
            FAIL=1
        elif [ "$rc" = "124" ] || [ "$rc" -ge 128 ] || [ -n "$kind" ]; then
            printf '  [%s stack %s] did not return (rc=%s%s)\n' "$arm" "$e" "$rc" "${kind:+, $kind}"
        else
            printf '  [%s stack %s] GATE FAILURE: returned (rc=%s); this KAT cannot see what the bound governs\n' "$arm" "$e" "$rc"
            FAIL=1
        fi
    done

    # Each decoder, handed one character more than the bound and no terminator: it reads past.
    for e in "${DECODERS[@]}"; do
        out="$BUILD_DIR/$arm.prefix.$e.out"
        bounded "$LIMIT_S" "$out" "$BUILD_DIR/$arm" prefix "$e"; rc=$?
        kind="$(grep -oE 'ERROR: AddressSanitizer: [a-z-]+' "$out" | head -1 | sed 's/ERROR: //')"
        if [ -n "$kind" ]; then printf '  [%s prefix %s] read past the first %s characters (rc=%s, %s)\n' "$arm" "$e" "$((BOUND + 1))" "$rc" "$kind"
        else
            printf '  [%s prefix %s] GATE FAILURE: no sanitizer report (rc=%s); this KAT cannot see what the bound governs\n' "$arm" "$e" "$rc"
            FAIL=1
        fi
    done
done

for bits in 64 32; do
    arm="green$bits"
    echo "--- SHIPPED ARM, ${bits}-bit: every case returns, promptly, with no sanitizer report ---"
    must_return "$arm" vectors
    must_return "$arm" bound
    for e in "${ENTRIES[@]}"; do must_return "$arm" entry "$e"; done
    for e in "${ENTRIES[@]}"; do must_return "$arm" stack "$e"; done
    for e in "${DECODERS[@]}"; do must_return "$arm" prefix "$e"; done
done

echo "--- the same answers: every vector decodes the same in all four builds ---"
for pair in "red64 green64" "red32 green32" "green32 green64"; do
    set -- $pair
    if cmp -s "$BUILD_DIR/$1.vectors.out" "$BUILD_DIR/$2.vectors.out"; then
        echo "  [$1 = $2] identical ($(grep -c '^  PASS' "$BUILD_DIR/$2.vectors.out") values)"
    else
        echo "  [$1 = $2] GATE FAILURE: answers differ"
        diff "$BUILD_DIR/$1.vectors.out" "$BUILD_DIR/$2.vectors.out" | head -6 | sed 's/^/      /'; FAIL=1
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: base58_input_bound_kat"; exit 0; else echo "FAIL: base58_input_bound_kat"; exit 1; fi
