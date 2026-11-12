#!/usr/bin/env bash
# Host KAT runner: the activity list getTransactionRows hands the app (bridge/tx_rows_list.h). See
# tx_rows_list_kat_main.c for the contract and the cases.
#
# Compiles the bridge header with the live core sources, under AddressSanitizer and
# UndefinedBehaviorSanitizer, in a 64-bit and a 32-bit (-m32) build; every case passes with no
# sanitizer report. Then every case again on the 64-bit build under LeakSanitizer (detect_leaks=1;
# LeakSanitizer has no i386 runtime, and none on macOS: that step fails there, as it does for every
# suite that runs it -- an environment limit, never a pass). Every run is time-limited.
#
# One arm: this header computes no rule (BRTxRows.h does, and tx_rows_order_kat, tx_rows_amount_kat
# and tx_rows_wallet_kat hold it in both arms). What it holds is that the bridge prints the core's
# rows unchanged, in the core's order, inside its buffer.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = every case passed, 1 = otherwise (a build error is never evidence).
set -uo pipefail
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"
ASAN_RUN="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S="${TX_ROWS_LIMIT:-120}"

CASES=(
    lines_are_the_cores_rows
    limit_lists_the_newest
    empty_lists
    widest_lines_fit
)

if [ ! -f "$CORE_DIR/BRTxRows.h" ]; then
    echo "GATE FAILURE: $CORE_DIR/BRTxRows.h not found: a core pin from before the transaction-row reader."; exit 1
fi
if [ ! -f "$BRIDGE_DIR/tx_rows_list.h" ]; then
    echo "GATE FAILURE: $BRIDGE_DIR/tx_rows_list.h not found."; exit 1
fi
# The JNI body must be this header's list, or the KAT tests code the app does not run.
JNI="$(cat "$BRIDGE_DIR/jni_wallet.c")"
if ! grep -Fq 'char *buf = tx_rows_list(g_wallet, now, (size_t)limit);' <<<"$JNI"; then
    echo "GATE FAILURE: jni_wallet.c getTransactionRows does not return tx_rows_list's text."; exit 1
fi

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

build() {   # <out> <64|32>
    local out="$1" bits="$2"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer ${m[@]+"${m[@]}"} \
        -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_rows_list_kat_main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <asan options> <cmd...>: the command's exit code, or 124 if it was stopped at the
# limit. The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was
# stopped (the race fixed in base58_input_bound_kat, #211).
bounded() {
    local outfile="$1" asan="$2"; shift 2
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    ASAN_OPTIONS="$asan" "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }

FAIL=0
for bits in 64 32; do
    echo "=== ${bits}-bit build ==="
    if ! build "$BUILD_DIR/kat$bits" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit build did not compile (a build error is not evidence)."
        FAIL=1; continue
    fi
    known="$("$BUILD_DIR/kat$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"
        FAIL=1; continue
    fi
    for c in "${CASES[@]}"; do
        out="$BUILD_DIR/kat$bits.$c.out"
        bounded "$out" "$ASAN_RUN" "$BUILD_DIR/kat$bits" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [kat$bits $c] passed ($(grep -c '^PASS:' "$out") checks)"
        else
            echo "  [kat$bits $c] GATE FAILURE (rc=$rc)"; show "$out" 20; FAIL=1
        fi
    done
done

echo "=== LEAK CHECK: every case on the 64-bit build under detect_leaks=1 ==="
if [ -x "$BUILD_DIR/kat64" ]; then
    for c in "${CASES[@]}"; do
        out="$BUILD_DIR/leak.$c.out"
        bounded "$out" "$ASAN_LEAK" "$BUILD_DIR/kat64" "$c"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [leak $c] clean"
        else
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; show "$out" 6; FAIL=1
        fi
    done
else
    echo "GATE FAILURE: no 64-bit build to leak-check"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: tx_rows_list_kat (${#CASES[@]} cases; 64-bit and 32-bit, ASan + UBSan; leak check)"
    exit 0
fi
echo "FAIL: tx_rows_list_kat"
exit 1
