#!/usr/bin/env bash
# Host KAT: once a wallet is built from the saved records, the bridge holds no pointer to them.
#
# loadSerializedTransactions parses the saved records into the bridge's array and recoverWalletFromBytes
# builds the wallet from them; BRWalletNewDual takes the records it keeps and the wallet releases them in
# BRWalletFree. The array must not go on naming them: the records the wallet did not take are released when
# it is built, then the array, and the array is cleared. A second load in the same process then frees
# nothing a wallet owns, and a recover after a build never builds from a record a wallet released.
#
# The _main.c compiles the bridge's OWN jni_transaction_persist.c and jni_wallet.c (with slip13.c), over the
# stand-ins in host_jni/ for <jni.h> and <android/log.h>, boots the bridge through the real JNI_OnLoad, and
# calls the real entry points in the order the app does (getSerializedTransactions; then the restore from
# disk: loadSerializedTransactions, recoverWalletFromBytes).
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of the macro selects the comparison arm (the shipped arm is built with no -D at all).
#   RED-THEN-GREEN (AddressSanitizer)  reload_after_build, recover_after_build: the comparison arm must be
#                                      stopped by an AddressSanitizer use-after-free or double-free report;
#                                      the shipped arm must pass clean.
#   RED-THEN-GREEN (assertion)         unadopted_released: the comparison arm must fail the invariant's
#                                      checks and only those (nothing reaches a sanitizer: the record is
#                                      merely kept); the shipped arm must pass.
#   GUARD (both arms)                  load_twice_before_build: two loads before any build are already
#                                      right; no arm can be red. Both arms must pass.
# A comparison arm that passes, or stops for any other reason, proves nothing and fails the gate. Every
# arm runs under a time limit. Both arms are built 64- and 32-bit and must give the same verdicts; then
# every scenario runs on the 64-bit shipped arm under the leak checker and must be clean.
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

gate_fail() { echo "GATE FAILED: $*"; exit 1; }

# Seam self-check: a -D that names nothing builds the shipped path, and the comparison arm would
# (wrongly) be the shipped code.
grep -q "SAVED_TX_ARRAY_UNFIXED" "$BRIDGE_DIR/jni_wallet.c" ||
    gate_fail "SAVED_TX_ARRAY_UNFIXED is not present in jni_wallet.c -- the -D would select nothing."

# $1 out, rest: extra flags. KAT_BITS=32 builds -m32 (needs gcc-multilib).
build() {
    local out="$1"; shift
    local m=()
    [ "${KAT_BITS:-64}" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$SCRIPT_DIR/host_jni" \
        -I "$BRIDGE_DIR" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/saved_tx_array_kat_main.c" \
        "$BRIDGE_DIR/jni_transaction_persist.c" \
        "$BRIDGE_DIR/jni_wallet.c" \
        "$BRIDGE_DIR/slip13.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

echo "=== building arms (the bridge and the wallet under ASan: comparison + shipped, 64- and 32-bit) ==="
build "$BUILD_DIR/comparison" -DSAVED_TX_ARRAY_UNFIXED || gate_fail "the comparison arm did not build"
build "$BUILD_DIR/shipped"                             || gate_fail "the shipped arm did not build"
KAT_BITS=32 build "$BUILD_DIR/comparison32" -DSAVED_TX_ARRAY_UNFIXED ||
    gate_fail "the 32-bit comparison arm did not build (gcc-multilib?)"
KAT_BITS=32 build "$BUILD_DIR/shipped32" || gate_fail "the 32-bit shipped arm did not build (gcc-multilib?)"

# symbolize=0: a report is printed and the arm aborts at once. Symbolizing a report can stall for minutes
# (measured on the 32-bit arm under load), and the gate reads the report's first line, not its frames.
ASAN_OFF="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export LSAN_OPTIONS="exitcode=23"
BANNER="ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:"
DEFECT="ERROR: AddressSanitizer: (heap-use-after-free|attempting double-free)"
LIMIT_S=300   # a hang, not a slow arm on a loaded machine: one scenario takes seconds

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; drop it there, and stop the watchdog with a
    # signal that runs no trap, so $BUILD_DIR survives for the other arms.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

# must_pass <bin> <armname> <scenario> <asan options>
must_pass() {
    local bin="$1" arm="$2" scenario="$3" opts="$4" log="$BUILD_DIR/$2.$3.log" rc
    ASAN_OPTIONS="$opts" bounded "$log" "$bin" "$scenario"; rc=$?
    if [ "$rc" -ne 0 ] || grep -Eq "$BANNER" "$log" || ! grep -q "ALL PASS" "$log"; then
        echo "GATE FAILED: the $arm arm did not pass scenario=$scenario cleanly (exit $rc):"
        sed 's/^/   | /' "$log"; exit 1
    fi
}

# must_report <bin> <armname> <scenario>: the arm is stopped by an AddressSanitizer use-after-free or
# double-free report -- not a pass, not a time-out, not another kind of stop.
must_report() {
    local bin="$1" arm="$2" scenario="$3" log="$BUILD_DIR/$2.$3.log" rc
    ASAN_OPTIONS="$ASAN_OFF" bounded "$log" "$bin" "$scenario"; rc=$?
    if [ "$rc" -eq 0 ] || grep -q "ALL PASS" "$log"; then
        echo "GATE FAILED: the $arm arm passed scenario=$scenario -- it cannot see the shape."
        sed 's/^/   | /' "$log"; exit 1
    fi
    if [ "$rc" -eq 124 ] || ! grep -Eq "$DEFECT" "$log"; then
        echo "GATE FAILED: the $arm arm stopped on scenario=$scenario (exit $rc) without a use-after-free or"
        echo "             double-free report, which does not count."
        sed 's/^/   | /' "$log"; exit 1
    fi
    grep -E "$DEFECT" "$log" > "$BUILD_DIR/first.txt"
    sed -n '1s/^/   '"$arm"': /p' "$BUILD_DIR/first.txt"
}

# must_fail_checks <bin> <armname> <scenario>: the arm fails the scenario's own checks, and only the
# invariant's -- no crash, no time-out, no sanitizer report.
must_fail_checks() {
    local bin="$1" arm="$2" scenario="$3" log="$BUILD_DIR/$2.$3.log" rc other
    ASAN_OPTIONS="$ASAN_OFF" bounded "$log" "$bin" "$scenario"; rc=$?
    if [ "$rc" -eq 0 ] || grep -q "ALL PASS" "$log"; then
        echo "GATE FAILED: the $arm arm passed scenario=$scenario -- it cannot see the shape."
        sed 's/^/   | /' "$log"; exit 1
    fi
    if [ "$rc" -ne 1 ] || grep -Eq "$BANNER" "$log" || ! grep -q "^   FAIL:" "$log"; then
        echo "GATE FAILED: the $arm arm stopped for another reason on scenario=$scenario (exit $rc), which does not count."
        sed 's/^/   | /' "$log"; exit 1
    fi
    other="$(grep "^   FAIL:" "$log" | grep -v "INVARIANT:")"
    if [ -n "$other" ]; then
        echo "GATE FAILED: the $arm arm failed a check other than the invariant's on scenario=$scenario:"
        printf '%s\n' "$other" | sed 's/^/   | /'; exit 1
    fi
    grep "^   FAIL:" "$log" | sed "s/^   /   $arm: /"
}

RED_ASAN="reload_after_build recover_after_build"
RED_ASSERT="unadopted_released"
GUARD="load_twice_before_build"

for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    for scenario in $RED_ASAN; do
        echo; echo "---- [RED-THEN-GREEN, AddressSanitizer, ${bits}-bit] scenario=$scenario : comparison must be reported, shipped must be clean ----"
        must_report "$BUILD_DIR/comparison$sfx" "comparison$sfx" "$scenario"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   shipped$sfx: ALL PASS"
    done
    for scenario in $RED_ASSERT; do
        echo; echo "---- [RED-THEN-GREEN, assertion, ${bits}-bit] scenario=$scenario : comparison must fail the invariant's checks, shipped must pass ----"
        must_fail_checks "$BUILD_DIR/comparison$sfx" "comparison$sfx" "$scenario"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   shipped$sfx: ALL PASS"
    done
    for scenario in $GUARD; do
        echo; echo "---- [GUARD, both arms, ${bits}-bit] scenario=$scenario : both arms must pass ----"
        must_pass "$BUILD_DIR/comparison$sfx" "comparison$sfx" "$scenario" "$ASAN_OFF"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   comparison$sfx and shipped$sfx: ALL PASS"
    done
done

# The shipped arm under the leak checker (64-bit; LeakSanitizer is not available on i386): every record
# ends up owned by a wallet or released, and nothing is left behind when the wallet is released.
echo
echo "---- [LEAK CHECK] every scenario on the shipped arm under detect_leaks=1 must be clean ----"
for scenario in $RED_ASAN $RED_ASSERT $GUARD; do
    must_pass "$BUILD_DIR/shipped" "shipped" "$scenario" "$ASAN_LEAK"
    echo "   leak-clean: $scenario"
done

echo
echo "saved_tx_array_kat: RED-BEFORE-GREEN OK (2 AddressSanitizer + 1 assertion comparison-arm scenarios, 1 guard in both arms, 64- and 32-bit), leak check OK (4 scenarios)"
exit 0
