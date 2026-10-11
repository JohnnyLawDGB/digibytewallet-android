#!/usr/bin/env bash
# Host KAT: the saved records reach only the wallet they were loaded for, and the bridge keeps no pointer
# to them once that wallet is built.
#
# loadSerializedTransactions parses the saved records for the disk restore, restoreWalletFromBytes, the only
# entry that builds a wallet from loaded records (WalletManager.restoreFromDisk: the load, then it).
#   rule 1  once the wallet is built, the bridge names none of the records: the ones the wallet did not take
#           are released, then the array, and the array is cleared;
#   rule 2  creating a wallet and recovering one from a phrase first drop any pending load, so a wallet is
#           never built from records loaded for another one.
#
# The _main.c compiles the bridge's OWN jni_transaction_persist.c and jni_wallet.c (with slip13.c), over the
# stand-ins in host_jni/ for <jni.h> and <android/log.h>, boots the bridge through the real JNI_OnLoad, and
# calls the real entry points the app calls. The Kotlin side is held below to the same rule: the load and
# restoreWalletFromBytes are called from WalletManager.restoreFromDisk alone, the load first.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of a macro selects a comparison arm (the shipped arm is built with no -D at all):
#   -DSAVED_TX_ARRAY_UNFIXED         rule 1 off: the array keeps naming the records after the build.
#   -DSAVED_TX_PENDING_LOAD_UNFIXED  rule 2 off: creating and recovering keep a pending load.
# Each red scenario must be red in ITS comparison arm, and pass in the other comparison arm and the shipped
# one -- so each red is that rule's and the two seams are independent:
#   RED-THEN-GREEN (AddressSanitizer)  rule 1: reload_after_build, recover_after_build -- stopped by an
#                                      AddressSanitizer use-after-free or double-free report.
#   RED-THEN-GREEN (assertion)         rule 1: unadopted_released; rule 2: foreign_load_recover,
#                                      foreign_load_create -- the arm fails the invariant's checks and only
#                                      those (nothing reaches a sanitizer).
#   GUARD (every arm)                  load_twice_before_build: two loads before any build are already
#                                      right; no arm can be red.
# A comparison arm that passes, or stops for any other reason, proves nothing and fails the gate. Every arm
# runs under a time limit. All arms are built 64- and 32-bit and must give the same verdicts; then every
# scenario runs on the 64-bit shipped arm under the leak checker and must be clean.
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

# Seam self-checks: a -D that names nothing builds the shipped path, and the comparison arm would
# (wrongly) be the shipped code. Rule 2's seam guards both the create and the recover entry.
grep -q "#ifndef SAVED_TX_ARRAY_UNFIXED" "$BRIDGE_DIR/jni_wallet.c" ||
    gate_fail "SAVED_TX_ARRAY_UNFIXED is not present in jni_wallet.c -- the -D would select nothing."
PENDING_SEAMS="$(grep -c "#ifndef SAVED_TX_PENDING_LOAD_UNFIXED" "$BRIDGE_DIR/jni_wallet.c")"
[ "$PENDING_SEAMS" -eq 2 ] ||
    gate_fail "SAVED_TX_PENDING_LOAD_UNFIXED guards $PENDING_SEAMS sites in jni_wallet.c; expected 2 (create, recover)."

# Source gates, Kotlin side: the loaded records are WalletManager.restoreFromDisk's alone. In the app's
# sources, loadSerializedTransactions and restoreWalletFromBytes are each called exactly once, from
# restoreFromDisk, the load first; restoreFromDisk neither creates nor recovers a wallet. (Comments do not
# count: they never write the receiver, NativeBridge.)
WALLET_KT="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt"
KT_SITES="$BUILD_DIR/kt_sites.txt"
grep -rnF --include=*.kt -e "NativeBridge.loadSerializedTransactions(" -e "NativeBridge.restoreWalletFromBytes(" \
    "$REPO_ROOT/core/src/main" "$REPO_ROOT/app/src/main" > "$KT_SITES"
for call in loadSerializedTransactions restoreWalletFromBytes; do
    n="$(grep -cF "NativeBridge.$call(" "$KT_SITES")"
    [ "$n" -eq 1 ] || { sed 's/^/   | /' "$KT_SITES"; gate_fail "NativeBridge.$call is called from $n places in the app; expected 1 (restoreFromDisk)."; }
done
# restoreFromDisk's body, braces matched.
RESTORE_KT="$(awk '!on && /fun restoreFromDisk\(/ { on = 1 }
                   on { print; o = gsub(/\{/, "{"); c = gsub(/\}/, "}"); d += o - c; if (o > 0) seen = 1; if (seen && d <= 0) exit }' "$WALLET_KT")"
[ -n "$RESTORE_KT" ] || gate_fail "restoreFromDisk was not found in WalletManager.kt. Re-anchor the gate."
LOAD_AT="$(<<<"$RESTORE_KT" grep -nF "NativeBridge.loadSerializedTransactions(" | cut -d: -f1)"
BUILD_AT="$(<<<"$RESTORE_KT" grep -nF "NativeBridge.restoreWalletFromBytes(" | cut -d: -f1)"
[ -n "$LOAD_AT" ] && [ -n "$BUILD_AT" ] && [ "$LOAD_AT" -lt "$BUILD_AT" ] ||
    gate_fail "restoreFromDisk must call loadSerializedTransactions and then restoreWalletFromBytes (load at '${LOAD_AT}', build at '${BUILD_AT}')."
if <<<"$RESTORE_KT" grep -qE "NativeBridge\.(recoverWalletFromBytes|createWalletFromBytes)\("; then
    gate_fail "restoreFromDisk calls recoverWalletFromBytes or createWalletFromBytes, which drop its own load."
fi

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

echo "=== building arms (the bridge and the wallet under ASan: two comparison arms + shipped, 64- and 32-bit) ==="
for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    KAT_BITS=$bits build "$BUILD_DIR/array$sfx" -DSAVED_TX_ARRAY_UNFIXED ||
        gate_fail "the ${bits}-bit array arm did not build (gcc-multilib for 32-bit?)"
    KAT_BITS=$bits build "$BUILD_DIR/pending$sfx" -DSAVED_TX_PENDING_LOAD_UNFIXED ||
        gate_fail "the ${bits}-bit pending arm did not build"
    KAT_BITS=$bits build "$BUILD_DIR/shipped$sfx" || gate_fail "the ${bits}-bit shipped arm did not build"
done

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

RED_ASAN_ARRAY="reload_after_build recover_after_build"
RED_ASSERT_ARRAY="unadopted_released"
RED_ASSERT_PENDING="foreign_load_recover foreign_load_create"
GUARD="load_twice_before_build"

for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    for scenario in $RED_ASAN_ARRAY; do
        echo; echo "---- [RED-THEN-GREEN, AddressSanitizer, rule 1, ${bits}-bit] scenario=$scenario : array arm must be reported; pending and shipped must pass ----"
        must_report "$BUILD_DIR/array$sfx" "array$sfx" "$scenario"
        must_pass "$BUILD_DIR/pending$sfx" "pending$sfx" "$scenario" "$ASAN_OFF"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   pending$sfx and shipped$sfx: ALL PASS"
    done
    for scenario in $RED_ASSERT_ARRAY; do
        echo; echo "---- [RED-THEN-GREEN, assertion, rule 1, ${bits}-bit] scenario=$scenario : array arm must fail the invariant's checks; pending and shipped must pass ----"
        must_fail_checks "$BUILD_DIR/array$sfx" "array$sfx" "$scenario"
        must_pass "$BUILD_DIR/pending$sfx" "pending$sfx" "$scenario" "$ASAN_OFF"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   pending$sfx and shipped$sfx: ALL PASS"
    done
    for scenario in $RED_ASSERT_PENDING; do
        echo; echo "---- [RED-THEN-GREEN, assertion, rule 2, ${bits}-bit] scenario=$scenario : pending arm must fail the invariant's checks; array and shipped must pass ----"
        must_fail_checks "$BUILD_DIR/pending$sfx" "pending$sfx" "$scenario"
        must_pass "$BUILD_DIR/array$sfx" "array$sfx" "$scenario" "$ASAN_OFF"
        must_pass "$BUILD_DIR/shipped$sfx" "shipped$sfx" "$scenario" "$ASAN_OFF"
        echo "   array$sfx and shipped$sfx: ALL PASS"
    done
    for scenario in $GUARD; do
        echo; echo "---- [GUARD, every arm, ${bits}-bit] scenario=$scenario : every arm must pass ----"
        for arm in array pending shipped; do must_pass "$BUILD_DIR/$arm$sfx" "$arm$sfx" "$scenario" "$ASAN_OFF"; done
        echo "   array$sfx, pending$sfx and shipped$sfx: ALL PASS"
    done
done

# The shipped arm under the leak checker (64-bit; LeakSanitizer is not available on i386): every record
# ends up owned by a wallet or released, and nothing is left behind when the wallet is released.
echo
echo "---- [LEAK CHECK] every scenario on the shipped arm under detect_leaks=1 must be clean ----"
for scenario in $RED_ASAN_ARRAY $RED_ASSERT_ARRAY $RED_ASSERT_PENDING $GUARD; do
    must_pass "$BUILD_DIR/shipped" "shipped" "$scenario" "$ASAN_LEAK"
    echo "   leak-clean: $scenario"
done

echo
echo "saved_tx_array_kat: RED-BEFORE-GREEN OK (rule 1: 2 AddressSanitizer + 1 assertion scenarios; rule 2: 2 assertion scenarios; each red only in its own arm; 1 guard in every arm; 64- and 32-bit), leak check OK (6 scenarios), source gates OK"
exit 0
