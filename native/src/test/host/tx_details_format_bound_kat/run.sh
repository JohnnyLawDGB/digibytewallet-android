#!/usr/bin/env bash
# Host KAT runner: the transaction-details list is sized from the longest line its format can
# produce, and its write position never passes the buffer, whatever the values.
# See tx_details_format_bound_kat_main.c for the line, the invariant and the cases.
#
# Builds the bridge's tx_details_format.h (the JNI-free core of getTransactionDetails) with
# AddressSanitizer and UndefinedBehaviorSanitizer, in a 64-bit and a 32-bit (-m32) build, in
# two arms:
#   comparison  -DTX_DETAILS_FORMAT_UNFIXED, the earlier sizing and advance. Each case meets
#               the expectation CASES gives it:
#                 write  an AddressSanitizer report of a WRITE (the earlier loop writes past
#                        the end of its buffer)
#                 fail   a failed check and no sanitizer report (a line cut short inside the
#                        buffer, or the earlier size)
#                 pass   passes: a guard, so the comparison arm is not simply failing everything
#   shipped     no flag: every case passes, with no sanitizer report.
# Every run is time-limited: a sanitizer report can stall instead of aborting.
#
# Two gates come first: the header must carry the -D seam (or the comparison arm would be the
# shipped arm under another name), and jni_wallet.c must build the list through the header and
# keep no copy of the line format of its own (or this KAT would test code the app does not run).
#
# MACRO CONVENTION: PRESENCE of -DTX_DETAILS_FORMAT_UNFIXED selects the comparison arm.
#
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/tx_details_format.h"
WALLET_C="$BRIDGE_DIR/jni_wallet.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=30

# <case>:<comparison-arm expectation>
CASES=(
    line_max:pass
    buffer_size:fail
    max_lines_15:fail
    max_lines_16:write
    max_lines_99:write
    most_recent_101:write
    most_recent_150:pass
    append_stop:write
    typical:pass
)

# ---- the seam is real, and the app runs the code under test ---------------------------
if ! grep -q 'TX_DETAILS_FORMAT_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER has no TX_DETAILS_FORMAT_UNFIXED seam; -D would be inert."
    exit 1
fi
if ! grep -q '#include "tx_details_format.h"' "$WALLET_C" || ! grep -q 'tx_details_format(' "$WALLET_C"; then
    echo "GATE FAILURE: jni_wallet.c does not build the list through tx_details_format.h."
    exit 1
fi
if grep -nF '%lld|%llu|%u|%u|%llu|%llu' "$WALLET_C"; then
    echo "GATE FAILURE: jni_wallet.c keeps its own copy of the line format (above)."
    exit 1
fi

build() {   # <out> <64|32> [-D...]
    local out="$1" bits="$2"; shift 2
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -Wall -Wextra -Werror -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer ${m[@]+"${m[@]}"} "$@" \
        -I "$BRIDGE_DIR" "$SCRIPT_DIR/tx_details_format_bound_kat_main.c" -o "$out"
}

# bounded <outfile> <cmd...>: exit code of the command, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog subshell inherits this script's EXIT trap; a plain kill would run it and
    # remove $BUILD_DIR under the other runs. Drop the trap inside, and stop the watchdog with
    # a signal that runs no trap at all.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

first_san()  { grep -Eom1 "($SAN_RE).*" "$1" | cut -c1-110; }
first_fail() { grep -m1 '^FAIL:' "$1" | cut -c1-120; }

FAIL=0
for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/red$bits" "$bits" -DTX_DETAILS_FORMAT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; continue
    fi
    if ! build "$BUILD_DIR/green$bits" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; continue
    fi

    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; continue
    fi

    echo "--- COMPARISON ARM (-DTX_DETAILS_FORMAT_UNFIXED): each case meets its expectation ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/red$bits.$c.out"
        bounded "$out" "$BUILD_DIR/red$bits" "$c"; rc=$?
        if [ $rc -eq 124 ]; then
            echo "  [red$bits $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue
        fi
        case "$want" in
        write)
            if grep -q 'ERROR: AddressSanitizer' "$out" && grep -q '^WRITE of size' "$out"; then
                echo "  [red$bits $c] wrote past the end of its buffer, as expected (rc=$rc): $(first_san "$out")"
                grep -m1 '^WRITE of size' "$out" | sed 's/^/      /'
                grep -m1 'is located' "$out" | sed 's/^/      /'
            else
                echo "  [red$bits $c] GATE FAILURE: no AddressSanitizer WRITE report (rc=$rc)"
                sed 's/^/      /' "$out" | head -8; FAIL=1
            fi ;;
        fail)
            if grep -Eq "$SAN_RE" "$out"; then
                echo "  [red$bits $c] GATE FAILURE: a sanitizer report where a failed check was expected (rc=$rc): $(first_san "$out")"; FAIL=1
            elif grep -q "^RESULT $c fail" "$out"; then
                echo "  [red$bits $c] failed a check inside its buffer, as expected: $(first_fail "$out")"
                grep -m1 '^NOTE: [0-9]* transactions' "$out" | sed 's/^/      /'
            else
                echo "  [red$bits $c] GATE FAILURE: expected a failed check (rc=$rc)"
                sed 's/^/      /' "$out" | head -8; FAIL=1
            fi ;;
        pass)
            if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass" "$out"; then
                echo "  [red$bits $c] passed (guard)"
            else
                echo "  [red$bits $c] GATE FAILURE: the guard did not pass in the comparison arm (rc=$rc)"
                grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" "$out" | sed 's/^/      /' | head -8; FAIL=1
            fi ;;
        *)
            echo "  [red$bits $c] GATE FAILURE: unknown expectation '$want'"; FAIL=1 ;;
        esac
    done

    echo "--- SHIPPED ARM: every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; out="$BUILD_DIR/green$bits.$c.out"
        bounded "$out" "$BUILD_DIR/green$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass" "$out"; then
            echo "  [green$bits $c] passed ($(grep -c '^PASS:' "$out") checks)$(grep -m1 '^NOTE: [0-9]* transactions' "$out" | sed 's/^NOTE:/:/')"
        else
            echo "  [green$bits $c] GATE FAILURE (rc=$rc)"
            grep -E "^(NOTE|FAIL|RESULT)|$SAN_RE" "$out" | sed 's/^/      /' | head -10; FAIL=1
        fi
    done
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: tx_details_format_bound_kat (${#CASES[@]} cases: comparison arm as expected, shipped arm clean; 64-bit and 32-bit, ASan + UBSan)"
    exit 0
else
    echo "FAIL: tx_details_format_bound_kat"
    exit 1
fi
