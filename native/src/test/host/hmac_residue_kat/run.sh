#!/usr/bin/env bash
# Host KAT runner: BRHMAC (BRCrypto.c) leaves neither its data nor its inner hash on the stack, where the BIP32 master
# key's seed and every hardened step's parent key would otherwise stay once it returns. See hmac_residue_kat_main.c
# for why both matter, how the stack is searched, and the cases.
#
# Builds without a sanitizer (the main file says why), at -O0 and -O2, 64-bit and, where a 32-bit link works, 32-bit:
#   COMPARISON ARM  -DHMAC_PAD_RESIDUE_UNFIXED, BRHMAC as it was: every case fails exactly its `data` and `inner`
#                   checks and passes its `depth`, `mac`, `answer` and `key` checks, at each level and width. An arm
#                   that finds nothing cannot see the rule, and is a gate failure.
#   SHIPPED ARM     every case passes every check.
#
# MACRO CONVENTION: PRESENCE of -DHMAC_PAD_RESIDUE_UNFIXED selects the comparison arm.
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
# macOS's /bin/bash is 3.2. Before bash 4.4, "${a[@]}" of an empty array is an unbound variable under set -u (hence
# ${a[@]+"${a[@]}"} below), and bash 3.2 then stops the script and exits with its EXIT trap's status, 0 after a
# clean-up: a run that never reached its arms read as passed (measured on a Mac for PR #154). Stopped inside a build,
# its message is in that build's log, which the clean-up deletes. So a run that stops before its verdict says so on
# the runner's own output (fd 3, which no build redirects), with the shell's error lines from the build logs, and
# exits 1 whatever status the shell gave it.
exec 3>&1
VERDICT_GIVEN=0
on_exit() {
    local status=$?
    if [ "$VERDICT_GIVEN" -ne 1 ]; then
        echo "FAIL: hmac_residue_kat stopped before its verdict (exit $status)" >&3
        grep -hE ': line [0-9]+: ' "$BUILD_DIR"/*.build.log 2>/dev/null | sed 's/^/    /' >&3
        [ "$status" -ne 0 ] || status=1
    fi
    rm -rf "$BUILD_DIR"
    exit "$status"
}
trap on_exit EXIT
CC="${CC:-clang}"
LIMIT_S=60   # a case finishes in about a second

if ! grep -q 'HMAC_PAD_RESIDUE_UNFIXED' "$CORE_DIR/BRCrypto.c"; then
    echo "GATE FAILURE: BRCrypto.c has no HMAC_PAD_RESIDUE_UNFIXED seam; -D would be inert."; exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=("$CORE_DIR/BRCrypto.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c"
       "$CORE_DIR/crypto/odocrypt.c" ${SHA3_SRCS[@]+"${SHA3_SRCS[@]}"})

build() {   # <out> <-O level> <64|32> [-D...]
    local out="$1" opt="$2" bits="$3"; shift 3
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -g "$opt" ${m[@]+"${m[@]}"} "$@" -I "$CORE_DIR" \
        "$SCRIPT_DIR/hmac_residue_kat_main.c" "${UNITS[@]}" -lpthread -lm -o "$out"
}

WIDTHS=(64)
bits_note="32-bit skipped: no 32-bit link here"
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/probe32.c"
if "$CC" -m32 "$BUILD_DIR/probe32.c" -o "$BUILD_DIR/probe32" >/dev/null 2>&1 && "$BUILD_DIR/probe32"; then
    WIDTHS=(64 32); bits_note="64- and 32-bit"
fi

BINS=()
for bits in "${WIDTHS[@]}"; do
    for opt in -O0 -O2; do
        for arm in red green; do
            name="${arm}${bits}${opt}"
            flags=(); [ "$arm" = red ] && flags=(-DHMAC_PAD_RESIDUE_UNFIXED)
            if ! build "$BUILD_DIR/$name" "$opt" "$bits" ${flags[@]+"${flags[@]}"} >"$BUILD_DIR/$name.build.log" 2>&1; then
                echo "GATE FAILURE: $name did not compile (a build error is not evidence)."; sed -n '1,20p' "$BUILD_DIR/$name.build.log" | sed 's/^/    /'
                exit 1
            fi
            want="shipped"; [ "$arm" = red ] && want="comparison"
            got="$("$BUILD_DIR/$name" arm 2>&1)"
            if [ "$got" != "arm: $want, ${bits}-bit" ]; then
                echo "GATE FAILURE: $name says \"$got\"; it must say \"arm: $want, ${bits}-bit\"."; exit 1
            fi
            BINS+=("$name")
        done
    done
done
CASES=($("$BUILD_DIR/green64-O0" list))
[ "${#CASES[@]}" -gt 0 ] || { echo "GATE FAILURE: the KAT lists no cases."; exit 1; }
echo "arms: ${BINS[*]} (red = -DHMAC_PAD_RESIDUE_UNFIXED; each confirmed by the binary); cases: ${CASES[*]}"

FAIL=0
for name in "${BINS[@]}"; do
    case "$name" in red*) echo "--- COMPARISON ARM $name: each case fails exactly its data and inner checks ---" ;;
                    *)    echo "--- SHIPPED ARM $name: every case passes ---" ;; esac
    for c in "${CASES[@]}"; do
        out="$(timeout "$LIMIT_S" "$BUILD_DIR/$name" "$c" 2>&1)"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [$name $c] GATE FAILURE: did not finish within ${LIMIT_S}s"; FAIL=1; continue; fi
        failed="$(grep '^FAIL: ' <<<"$out" | awk '{sub(":", "", $3); print $3}' | sort | tr '\n' ' ')"
        passed="$(grep -c '^PASS: ' <<<"$out")"
        case "$name" in
            red*)
                if [ $rc -eq 1 ] && grep -q "^RESULT $c fail$" <<<"$out" && [ "$failed" = "data inner " ] && [ "$passed" -eq 4 ]; then
                    echo "  [$name $c] failed, as it must: $(grep -m1 '^FAIL: .* data: ' <<<"$out" | cut -c7-160)"
                else
                    echo "  [$name $c] GATE FAILURE: rc=$rc, failed checks \"${failed}\" (want \"data inner \"), $passed passed (want 4)"
                    sed 's/^/      /' <<<"$out" | head -8; FAIL=1
                fi ;;
            *)
                if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" <<<"$out" && [ -z "$failed" ] && [ "$passed" -eq 6 ]; then
                    echo "  [$name $c] passed (6 checks)"
                else
                    echo "  [$name $c] GATE FAILURE: rc=$rc, failed checks \"${failed}\", $passed passed (want 6)"
                    sed 's/^/      /' <<<"$out" | head -8; FAIL=1
                fi ;;
        esac
    done
done

[ $FAIL -eq 0 ] && echo "PASS: hmac_residue_kat (${#CASES[@]} cases; comparison arm red on exactly data and inner, shipped arm clean; -O0 and -O2; $bits_note)"
[ $FAIL -ne 0 ] && echo "FAIL: hmac_residue_kat"
VERDICT_GIVEN=1
exit $FAIL
