#!/usr/bin/env bash
# Host KAT runner: no copy or compare in BRWallet.c's DigiDollar transfer builder, or in the core's test.c
# transaction-equality helpers, is passed a NULL pointer, not even for 0 bytes (undefined behaviour in C;
# glibc declares memcpy/memmove/memcmp nonnull, so UBSan stops on it on Linux). The same class as the
# core's BRTransaction.c serializer fix (an empty scriptSig, output script or witness is not copied). See
# wallet_copy_guards_kat_main.c.
#
#   NONNULL PROBE  every build force-includes glibc_nonnull.h, which gives memcpy/memmove/memcmp glibc's
#                  nonnull declarations (Apple's libc has none, so a Mac run is otherwise blind to this
#                  class). A probe built the same way passes NULL, 0 to each of the three and must be
#                  stopped by UBSan, on every platform: without it the arms below would prove nothing.
#   SOURCE GATE    in BRWalletCreateDigiDollarTransfer, every memcpy from a transaction output's script
#                  is guarded by `scriptLen > 0` on its own line (there are two; the gate must find them).
#                  Neither copy can be reached with an empty script today (see the main's header), so no
#                  runtime arm can fail on the unguarded builder; this gate is what fails on it.
#   EXTRACT        test.c does not build against this core (the upstream suite, behind the API), so
#                  BRBytesEqual, BRTxOutputEqual, BRTxInputEqual and BRTransactionEqual are cut from it BY
#                  PATTERN (from the seam's #ifndef to BRTransactionEqual's closing brace) into
#                  test_equal_extract.c, which the main includes.
#   comparison     -DTEST_TX_EQUAL_UNFIXED=1 (test.c): the memcmp without its n == 0 test. Each tx_equal_
#                  case that compares an empty script or signature must stop on a UBSan nonnull report
#                  from test_equal_extract.c; every other case must pass.
#   shipped        TEST_TX_EQUAL_UNFIXED=0: every case passes, no sanitizer report.
# All arms under ASan + UBSan, 64-bit. The 32-bit arm is not built here (the core's wallet is 64-bit only
# in these host builds, as in dd_coin_selection_kat). The shipped arm runs every case once more under
# LeakSanitizer where the platform has one (said so, and skipped, where it does not -- Apple clang).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DTEST_TX_EQUAL_UNFIXED (0 shipped, 1 the comparison arm).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
NONNULL_RE='runtime error: null pointer passed as argument [12], which is declared to never be null'

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

# <case>:<the comparison arms that must fail it; - for none>
CASES=(
    dd_send_empty_dd_record:-
    dd_send_empty_fee_record:-
    dd_send_empty_records_both:-
    tx_equal_unsigned_input:t1
    tx_equal_empty_input_script:t1
    tx_equal_empty_output_script:t1
    tx_equal_copy_unsigned:t1
    tx_equal_bytes_still_compared:-
)

for f in BRWallet.c BRTransaction.c test.c; do
    [ -f "$CORE_DIR/$f" ] || gate_fail "$CORE_DIR has no $f (set CORE_DIR)"
done

# San flags. _FORTIFY_SOURCE=0: Apple's fortified <string.h> makes memcpy a __builtin___memcpy_chk macro,
# which would bypass glibc_nonnull.h's declarations.
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer
     -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -include "$SCRIPT_DIR/glibc_nonnull.h")

# ---- NONNULL PROBE -------------------------------------------------------------------------
cat > "$BUILD_DIR/probe.c" <<'EOF'
#include <stdlib.h>
#include <string.h>
int main(int argc, char **argv)
{
    char d[4];
    const char *volatile p = NULL;   /* volatile: the compiler cannot see the NULL */
    size_t volatile n = 0;
    if (argc != 2) return 2;
    if (argv[1][0] == 'c') memcpy(d, p, n);
    if (argv[1][0] == 'm') memmove(d, p, n);
    if (argv[1][0] == 'p') return memcmp(p, d, n);
    return 0;
}
EOF
"$CC" -w -std=c99 "${SAN[@]}" "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe" || gate_fail "the nonnull probe did not compile"
for fn in c:memcpy m:memmove p:memcmp; do
    out="$("$BUILD_DIR/probe" "${fn%%:*}" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] || ! grep -Eq "$NONNULL_RE" <<<"$out"; then
        gate_fail "UBSan does not stop ${fn#*:} from NULL with length 0 here (rc=$rc): every arm below would be blind"
    fi
done
echo "nonnull probe: UBSan stops memcpy, memmove and memcmp given NULL, 0"

# ---- SOURCE GATE ---------------------------------------------------------------------------
awk '/^BRTransaction \*BRWalletCreateDigiDollarTransfer\(/{p=1} p{print} p&&/^}/{exit}' "$CORE_DIR/BRWallet.c" > "$BUILD_DIR/transfer.c"
[ -s "$BUILD_DIR/transfer.c" ] || gate_fail "could not locate BRWalletCreateDigiDollarTransfer in BRWallet.c"
copies="$(grep -E 'memcpy\([^;]*->outputs\[[^]]*\]\.script,' "$BUILD_DIR/transfer.c")"
ncopies="$(grep -c . <<<"$copies")"
[ -n "$copies" ] && [ "$ncopies" -eq 2 ] ||
    gate_fail "expected the transfer builder's two copies from an output's script, found ${ncopies:-0}: re-derive this gate"
while IFS= read -r line; do
    if ! grep -Eq 'if \([a-z]+\[[a-z]+\]\.scriptLen > 0\) memcpy\(' <<<"$line"; then
        echo "SOURCE GATE FAILED: a copy from an output's script is not guarded by scriptLen > 0:"
        echo "      $(sed -E 's/^[[:space:]]+//' <<<"$line")"
        echo "FAIL: wallet_copy_guards_kat"; exit 1
    fi
done <<<"$copies"
echo "source gate: both copies from an output's script in the transfer builder are guarded by scriptLen > 0"

# ---- the seam is real, and the extract -----------------------------------------------------
TESTC="$(cat "$CORE_DIR/test.c")"
grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+TEST_TX_EQUAL_UNFIXED' <<<"$TESTC" ||
    gate_fail "test.c does not test the value of TEST_TX_EQUAL_UNFIXED; the arms would not differ"
grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+TEST_TX_EQUAL_UNFIXED' <<<"$TESTC" &&
    ! grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+TEST_TX_EQUAL_UNFIXED' <<<"$TESTC" ||
    gate_fail "test.c selects the arm by the macro's PRESENCE, or gives it no default of 0"
{
    echo '#include <string.h>'
    echo '#include "BRTransaction.h"'
    echo '#include "BRArray.h"'
    awk '/^#ifndef TEST_TX_EQUAL_UNFIXED/{p=1} p{print} p&&/^static int BRTransactionEqual/{q=1} q&&/^}/{exit}' "$CORE_DIR/test.c"
} > "$BUILD_DIR/test_equal_extract.c"
EXTRACT="$(cat "$BUILD_DIR/test_equal_extract.c")"
for fn in BRBytesEqual BRTxOutputEqual BRTxInputEqual BRTransactionEqual; do
    grep -Eq "^static int $fn ?\(" <<<"$EXTRACT" || gate_fail "the extract from test.c has no $fn"
done
if grep -Eq '^(int|void|static [a-z]+ [A-Za-z_]+Tests)' <<<"$EXTRACT"; then
    gate_fail "the extract from test.c ran past BRTransactionEqual"
fi
if grep -nE 'memcmp *\((in|out)[12]->(script|signature)' <<<"$EXTRACT" > "$BUILD_DIR/raw.txt"; then
    echo "EXTRACT GATE FAILED: test.c still compares a script or signature with a bare memcmp:"
    sed 's/^/      /' "$BUILD_DIR/raw.txt"; echo "FAIL: wallet_copy_guards_kat"; exit 1
fi
echo "extract: BRBytesEqual, BRTxOutputEqual, BRTxInputEqual, BRTransactionEqual from test.c ($(grep -c . <<<"$EXTRACT") lines)"

# ---- the arms ------------------------------------------------------------------------------
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
build() {   # <out> <TEST_TX_EQUAL_UNFIXED>
    "$CC" -w -std=gnu11 -include stdint.h "${SAN[@]}" -DTEST_TX_EQUAL_UNFIXED="$2" \
        -I "$BUILD_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/wallet_copy_guards_kat_main.c" \
        "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lpthread -lm -o "$1"
}
build "$BUILD_DIR/red_t1" 1 || gate_fail "comparison arm t1 did not compile (a build error is not evidence)"
build "$BUILD_DIR/green" 0 || gate_fail "shipped arm did not compile"

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

FAIL=0
echo "--- COMPARISON ARM -DTEST_TX_EQUAL_UNFIXED=1 (bare memcmp): fails exactly its cases ---"
red_any=0
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; arms_of="${entry##*:}"
    out="$("$BUILD_DIR/red_t1" "$c" 2>&1)"; rc=$?
    if [ "$arms_of" = t1 ]; then
        red_any=1
        # The defect IS the report: UBSan stops on memcmp's NULL argument, inside the helpers from test.c.
        if [ $rc -ne 0 ] && grep -Eq "test_equal_extract\.c:[0-9]+:[0-9]+: $NONNULL_RE" <<<"$out"; then
            echo "  [t1 $c] stopped, as it must: $(grep -m1 -Eo "test_equal_extract\.c:[0-9]+:[0-9]+: runtime error: [^,]*" <<<"$out")"
        else
            echo "  [t1 $c] GATE FAILURE: this arm did not stop on a nonnull report from test.c's helpers (rc=$rc)"
            grep -E "^FAIL:|$SAN_RE" <<<"$out" | head -4 | sed 's/^/      /'; FAIL=1
        fi
    elif [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [t1 $c] GATE FAILURE: failed in an arm that is not its own (rc=$rc)"
        grep -E "^FAIL:|$SAN_RE" <<<"$out" | head -4 | sed 's/^/      /'; FAIL=1
    fi
done
[ $red_any -eq 0 ] && { echo "  [t1] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }

echo "--- SHIPPED ARM (TEST_TX_EQUAL_UNFIXED=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | head -6 | cut -c1-200 | sed 's/^/      /'; FAIL=1
    fi
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" list 2>&1)"
if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
    leak_note="every case clean"
    echo "--- LEAK CHECK: every case on the shipped arm under detect_leaks=1 ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"
        out="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: wallet_copy_guards_kat (${#CASES[@]} cases: the comparison arm stops on exactly its 4, shipped arm clean under ASan + UBSan with glibc's nonnull declarations; source gate; leak check $leak_note)"
[ $FAIL -ne 0 ] && echo "FAIL: wallet_copy_guards_kat"
exit $FAIL
