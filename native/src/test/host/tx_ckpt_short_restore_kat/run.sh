#!/usr/bin/env bash
# Host KAT runner for the short-restore verdict in the core's BRSavedTransactions.h
# (BRSavedTransactionsDeclaredCount, BRSavedTransactionsRestoreCheck): a restore that loads fewer
# checkpointed transactions than the checkpoint declares, or finds the file lost or unreadable after a
# checkpoint of records landed, rebuilds from the birth floor. Moved from Kotlin (SavedTransactionsCheckpoint.kt,
# WalletManager.kt restoreFromDisk, CfRestorePreflight.kt TRANSACTION_CHECKPOINT_SHORT); its tests
# (SavedTransactionsCheckpointTest, TransactionCheckpointPreflightTest) run here value for value, beside a
# line-for-line C model of the Kotlin. See tx_ckpt_short_restore_kat_main.c.
#
#   comparison  one arm per defect, -DSAVED_TRANSACTIONS_SHORT_RESTORE_UNFIXED=<bit>: 1 no verdict (every
#               restore complete); 2 a lost file is complete; 4 a lost file is short on the flag alone; 8 an
#               unreadable file is complete; 16 the declared count read as BRSavedTransactionsFloorMinima reads
#               it (a 4-byte blob declares 0). Each arm fails exactly the cases CASES lists for it.
#   shipped     the macro 0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit; the core's parser and its units are linked, built once, without the seam.
# The arms run with detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where
# the platform has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header tests the seam's VALUE (#if, never #ifdef) with an #ifndef default, and defines a
# SAVED_TRANSACTIONS_SHORT_RED_* seam for every arm. Then a PORTABILITY GATE: the new API builds clean under
# -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the second), for
# iphoneos/iphonesimulator, and through Swift's C importer on macOS where an Xcode SDK / swiftc is present.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DSAVED_TRANSACTIONS_SHORT_RESTORE_UNFIXED, a set of bits (=0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HDR="$CORE_DIR/BRSavedTransactions.h"
export TX_CKPT_VECTOR="$SCRIPT_DIR/../tx_ckpt_cap_kat/real_writer_3.hex"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
M=SAVED_TRANSACTIONS_SHORT_RESTORE_UNFIXED

# <case>:<the comparison arms that must fail it, comma-separated; - for none>
CASES=(
    declared_count:s16
    declared_unreadable:-
    short_rule:s1,s8
    record_restore:s1,s16
    sticky_record:s1,s8
    lost_file:s1,s2,s4
    real_writer_blob:s1
    over_limit:s1
    preflight_complete_resumes:-
    preflight_short_rebuilds:-
    preflight_old_reader_limit:s1
    model_sweep:s1,s2,s4,s8,s16
    names:s1
)
ARMS=(s1:no-verdict s2:lost-file-ignored s4:flag-alone s8:unreadable-complete s16:floor-count)

# ---- the seam is real ----------------------------------------------------------------------
[ -f "$HDR" ] || { echo "GATE FAILURE: $HDR not found."; exit 1; }
[ -f "$TX_CKPT_VECTOR" ] || { echo "GATE FAILURE: $TX_CKPT_VECTOR not found."; exit 1; }
TEXT="$(cat "$HDR")"
for fn in BRSavedTransactionsDeclaredCount BRSavedTransactionsRestoreCheck BRSavedTransactionsRestoreName; do
    grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$TEXT" || { echo "GATE FAILURE: $HDR does not define $fn."; exit 1; }
done
if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?${M}" <<<"$TEXT"; then
    echo "GATE FAILURE: $HDR does not test the value of $M; the arms would not differ."; exit 1
fi
if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+$M" <<<"$TEXT" || grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+$M" <<<"$TEXT"; then
    echo "GATE FAILURE: $HDR selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; bit="${tag:1}"
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+SAVED_TRANSACTIONS_SHORT_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$TEXT"; then
        echo "GATE FAILURE: no SAVED_TRANSACTIONS_SHORT_RED_* seam of value $bit; arm $tag would be the shipped code."; exit 1
    fi
done

FAIL=0
shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c
       "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c
       "$CORE_DIR"/BRCrypto.c "$CORE_DIR"/crypto/groestl.c "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c
       "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")
objs() {   # <dir> <extra flags...>
    local dir="$1"; shift
    mkdir -p "$dir"
    local u
    for u in "${UNITS[@]}"; do
        "$CC" -w -std=c99 -include stdint.h "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
            -c "$u" -o "$dir/$(basename "$(dirname "$u")")_$(basename "${u%.c}").o" || return 1
    done
}

# ---- PORTABILITY GATE ----------------------------------------------------------------------
cat > "$BUILD_DIR/strict1.c" <<'C'
#include "BRSavedTransactions.h"
int strict_tu1(void);
int strict_tu1(void)
{
    static const uint8_t b[4] = { 3, 0, 0, 0 };
    int64_t r = 0;
    return BRSavedTransactionsRestoreCheck(1, 1, BRSavedTransactionsDeclaredCount(b, sizeof(b)), 0, 0, 0, &r) ==
           BR_SAVED_TX_RESTORE_SHORT_LOADED && r == 3;
}
C
cat > "$BUILD_DIR/strict2.c" <<'C'
#include "BRSavedTransactions.h"
#include "BRSavedTransactions.h"
int strict_tu1(void);
int main(void)
{
    return (strict_tu1() && BRSavedTransactionsRestoreCheck(0, 0, 0, 0, 1, 22, NULL) == BR_SAVED_TX_RESTORE_SHORT_FILE_LOST &&
            BRSavedTransactionsDeclaredCount(NULL, 0) == BR_SAVED_TRANSACTIONS_UNREADABLE &&
            BRSavedTransactionsRestoreName(BR_SAVED_TX_RESTORE_COMPLETE)[0] == '\0') ? 0 : 1;
}
C
strict_ok=1
if ! objs "$BUILD_DIR/strict_objs" -O0; then
    echo "PORTABILITY GATE FAILED: the core units did not compile"; FAIL=1; strict_ok=0
else
    for std in c99 c11 c17; do
        if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
                "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
           ! "$BUILD_DIR/strict_$std"; then
            echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
        fi
    done
fi
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRSavedTransactions.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'SWIFT'
let blob: [UInt8] = [3, 0, 0, 0]
let declared: Int64 = BRSavedTransactionsDeclaredCount(blob, blob.count)
var record: Int64 = 0
let verdict: BRSavedTransactionsRestore = BRSavedTransactionsRestoreCheck(1, 1, declared, 2, 1, 22, &record)
let lost = BRSavedTransactionsRestoreCheck(0, 0, Int64(BR_SAVED_TRANSACTIONS_UNREADABLE), 0, 1, 22, &record) == BR_SAVED_TX_RESTORE_SHORT_FILE_LOST
let name = String(cString: BRSavedTransactionsRestoreName(verdict))
let reset = BR_SAVED_TRANSACTIONS_SHORT_RESET_NAME
print(verdict == BR_SAVED_TX_RESTORE_SHORT_LOADED, lost, name, reset, record)
SWIFT
    mkdir -p "$BUILD_DIR/swift_inc/crypto" "$BUILD_DIR/swift_inc/secp256k1/include"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    cp "$CORE_DIR"/secp256k1/include/*.h "$BUILD_DIR/swift_inc/secp256k1/include/" 2>/dev/null
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            -Xcc -I"$BUILD_DIR/swift_inc/secp256k1/include" "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
build() {   # <out> <bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -D$M="$2" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_ckpt_short_restore_kat_main.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
}
for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"
    build "$BUILD_DIR/red_$tag" "${tag:1}" || { echo "GATE FAILURE: comparison arm $tag did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -D$M=${tag:1} ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$tag,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$tag" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$tag $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [$tag $c] failed, as it must: ${first:6:150}"
            else
                echo "  [$tag $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$tag $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$tag] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM ($M=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
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

[ $FAIL -eq 0 ] && echo "PASS: tx_ckpt_short_restore_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: tx_ckpt_short_restore_kat"
exit $FAIL
