#!/usr/bin/env bash
# Host KAT runner for the core's BRPreparedSend.h -- the quote and the pre-sign check of a prepared
# DGB send (the bridge's quoteTransaction and signPreparedTransaction; the iOS send path). See
# prepared_send_kat_main.c for the invariant and the cases. fee_quote_kat runs the same header
# against sends the core builds and signs, and holds the source gate over jni_transaction.c.
#
#   HEADER  BRPreparedSend.h carries the seam and tests its VALUE (#if: an =0 build must never run
#           the comparison arm). A second translation unit includes it and is linked into every
#           build below (static inline: no duplicate symbol), and under -Wall -Wextra -Wpedantic,
#           in c99, c11 and c17 and both arms, no diagnostic is the header's own (other core headers
#           have warnings of their own; they are not this suite's). Where an iOS SDK is installed
#           (xcrun), the same unit compiles for iphoneos and iphonesimulator, and Swift imports the
#           header and type-checks a call of each function.
#   RED     -DPREPARED_SEND_UNFIXED=1: the typical figure, nothing checked before signing. Every
#           RED case MUST fail and every GUARD case pass.
#   GREEN   -DPREPARED_SEND_UNFIXED=0: every case passes, ALL PASS, exit 0.
#   Both arms are built 64-bit with ASan + UBSan, and 32-bit (-m32) as well where this host can
#   build -m32 with the sanitizers; where it cannot, that is printed, and the 64-bit arms decide.
#   Leak detection stays off (it is not this suite's subject, and it aborts on hosts without it).
#
#   DIFFERENTIAL (opt-in): PREPARED_SEND_REFERENCE=<file> names the Android bridge's former
#   prepared_send.h (e.g. `git show v4.1.0:native/src/main/jni/bridge/prepared_send.h > ref.h`).
#   Both arms are then built with it force-included, and case "differential" requires both headers
#   to give the same answer to every seeded scenario. Unset, it is reported as not run.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRPreparedSend.h"
REFERENCE="${PREPARED_SEND_REFERENCE:-}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=120

RED_CASES=(quote_never_invents check_needs_wallet_and_tx check_needs_an_approved_payment
           two_change_outputs_refused payment_twice_refused zero_value_change_refused
           change_without_payment_refused payment_raised_change_lowered_refused
           payment_lowered_change_raised_refused sibling_of_unspent_coin_refused
           digidollar_coin_refused refused_after_signing)
GUARD_CASES=(payment_only_passes change_before_payment_passes payment_twice_to_self_passes verdict_values_fixed)

FAIL=0

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER does not test the value of PREPARED_SEND_UNFIXED; the arms would not differ."
    exit 1
fi
if grep -Eq '^[[:space:]]*#[[:space:]]*if(n)?def[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER" &&
   ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+PREPARED_SEND_UNFIXED' "$HEADER"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE; the =0 build would run the comparison arm."
    exit 1
fi

# ---- a second translation unit -------------------------------------------------------------
TU2="$BUILD_DIR/prepared_send_kat_tu2.c"
cat > "$TU2" <<'EOF'
#include "BRPreparedSend.h"
int prepared_send_kat_tu2(BRWallet *w, const BRTransaction *tx, const uint8_t *s, size_t n, uint64_t *f, uint64_t *v);
int prepared_send_kat_tu2(BRWallet *w, const BRTransaction *tx, const uint8_t *s, size_t n, uint64_t *f, uint64_t *v)
{
    return BRPreparedSendQuote(w, tx, f, v) + (int)BRPreparedSendCheck(w, tx, s, n, *f, *v) +
           BRPreparedSendPaysAsApproved(w, tx, s, n, *f) + BRPreparedSendHasCoin(NULL, 0, UINT256_ZERO, 0);
}
EOF

# own_diagnostics <file>: the diagnostics in <file> located in the header or the test's own unit.
own_diagnostics() { grep -E '(BRPreparedSend\.h|prepared_send_kat_tu2\.c):[0-9]+:[0-9]+: (warning|error)' "$1"; }

echo "--- HEADER: no diagnostic of its own, both arms, c99 c11 c17 ---"
for std in c99 c11 c17; do
    for unfixed in 0 1; do
        diag="$BUILD_DIR/diag.$std.$unfixed"
        if ! "$CC" -std="$std" -Wall -Wextra -Wpedantic -fsyntax-only -DPREPARED_SEND_UNFIXED="$unfixed" \
                -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$TU2" 2>"$diag"; then
            echo "  [$std =$unfixed] GATE FAILURE: did not compile"; sed -n '1,6p' "$diag" | sed 's/^/      /'; FAIL=1
        elif mine="$(own_diagnostics "$diag")"; then
            echo "  [$std =$unfixed] GATE FAILURE: the header has diagnostics of its own"; sed 's/^/      /' <<<"$mine"; FAIL=1
        else
            echo "  [$std =$unfixed] clean"
        fi
    done
done

if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    echo "--- HEADER: iOS ---"
    for sdk in iphoneos:arm64-apple-ios15.0 iphonesimulator:arm64-apple-ios15.0-simulator; do
        diag="$BUILD_DIR/diag.${sdk%%:*}"
        if ! xcrun --sdk "${sdk%%:*}" clang -target "${sdk#*:}" -std=c11 -Wall -Wextra -fsyntax-only \
                -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$TU2" 2>"$diag"; then
            echo "  [${sdk%%:*}] GATE FAILURE: did not compile"; sed -n '1,6p' "$diag" | sed 's/^/      /'; FAIL=1
        elif mine="$(own_diagnostics "$diag")"; then
            echo "  [${sdk%%:*}] GATE FAILURE: the header has diagnostics of its own"; sed 's/^/      /' <<<"$mine"; FAIL=1
        else
            echo "  [${sdk%%:*}] compiles for ${sdk#*:}"
        fi
    done
    if xcrun --find swiftc >/dev/null 2>&1; then
        # The core's headers by themselves: its module.modulemap names a header the vendored
        # secp256k1 no longer has, and Swift's importer would load it from an -I of the core.
        mkdir -p "$BUILD_DIR/inc"
        for h in "$CORE_DIR"/*.h; do ln -s "$h" "$BUILD_DIR/inc/"; done
        ln -s "$CORE_DIR/crypto" "$BUILD_DIR/inc/crypto"
        printf '#include "BRPreparedSend.h"\n' > "$BUILD_DIR/bridging.h"
        cat > "$BUILD_DIR/check.swift" <<'EOF'
func quoteThenCheck(_ wallet: OpaquePointer?, _ tx: UnsafeMutablePointer<BRTransaction>?,
                    _ script: [UInt8], _ amount: UInt64) -> Bool {
    var fee: UInt64 = 0, vsize: UInt64 = 0
    guard BRPreparedSendQuote(wallet, tx, &fee, &vsize) != 0 else { return false }
    let verdict: BRPreparedSendVerdict = script.withUnsafeBufferPointer {
        BRPreparedSendCheck(wallet, tx, $0.baseAddress, $0.count, amount, fee)
    }
    return verdict == BRPreparedSendOK && verdict.rawValue == 0 && BRPreparedSendNotApproved.rawValue == 4
}
EOF
        diag="$BUILD_DIR/diag.swift"
        if xcrun --sdk iphonesimulator swiftc -typecheck -target arm64-apple-ios15.0-simulator \
                -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/inc" -Xcc -I"$CORE_DIR/secp256k1/include" \
                "$BUILD_DIR/check.swift" 2>"$diag"; then
            echo "  [swift] imports the header: BRPreparedSendQuote, BRPreparedSendCheck and the verdicts type-check"
        else
            echo "  [swift] GATE FAILURE: Swift did not type-check a call of the header"; sed -n '1,8p' "$diag" | sed 's/^/      /'; FAIL=1
        fi
    fi
else
    echo "--- HEADER: iOS: no iOS SDK on this host (xcrun); not compiled for iphoneos/iphonesimulator ---"
fi

# ---- the cases -----------------------------------------------------------------------------
shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c
       ${SHA3[@]+"${SHA3[@]}"})

build() {   # <out> <64|32> <unfixed 0|1> [extra flags...]
    local out="$1" bits="$2" unfixed="$3"; shift 3
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer ${m[@]+"${m[@]}"} -DPREPARED_SEND_UNFIXED="$unfixed" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/prepared_send_kat_main.c" "$TU2" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; a plain kill would run it and remove
    # $BUILD_DIR under the other runs. Drop the trap inside; stop it with a trap-free signal.
    ( trap - EXIT; sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

BITS=(64)
printf 'int main(void) { return 0; }\n' > "$BUILD_DIR/probe.c"
if "$CC" -m32 -fsanitize=address,undefined "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe32" >/dev/null 2>&1 &&
   "$BUILD_DIR/probe32" >/dev/null 2>&1; then
    BITS+=(32)
else
    echo "--- 32-bit: this host cannot build and run -m32 with ASan + UBSan; the 64-bit arms decide ---"
fi

for bits in "${BITS[@]}"; do
    echo "=== ${bits}-bit ==="
    if ! build "$BUILD_DIR/red$bits" "$bits" 1; then
        echo "  GATE FAILURE: the ${bits}-bit comparison arm did not compile (a build error is not evidence)."; FAIL=1; continue
    fi
    if ! build "$BUILD_DIR/green$bits" "$bits" 0; then
        echo "  GATE FAILURE: the ${bits}-bit shipped arm did not compile."; FAIL=1; continue
    fi
    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${RED_CASES[@]}" "${GUARD_CASES[@]}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "  GATE FAILURE: case lists disagree"; echo "    binary: $known"; echo "    run.sh: $listed"; FAIL=1; continue
    fi

    echo "--- COMPARISON ARM (-DPREPARED_SEND_UNFIXED=1): every RED case MUST fail, every GUARD case pass ---"
    out="$BUILD_DIR/red$bits.out"
    bounded "$out" "$BUILD_DIR/red$bits"; rc=$?
    [ $rc -eq 124 ] && { echo "  [red$bits] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; }
    if grep -Eq "$SAN_RE" "$out"; then
        echo "  [red$bits] GATE FAILURE: sanitizer report (rc=$rc)"; grep -E "$SAN_RE" "$out" | sed -n '1,3p' | sed 's/^/      /'; FAIL=1
    fi
    [ $rc -eq 0 ] && { echo "  [red$bits] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in "${RED_CASES[@]}"; do
        if grep -q "^RESULT $c fail$" "$out"; then
            first="$(awk -v c="--- $c" '$0 == c { f = 1; next } f && /^FAIL:/ { print; exit } f && /^RESULT / { exit }' "$out")"
            echo "  [red$bits $c] failed, as it must: ${first:0:140}"
        else
            echo "  [red$bits $c] GATE FAILURE: did not fail in the comparison arm -- it cannot see the defect"; FAIL=1
        fi
    done
    for c in "${GUARD_CASES[@]}"; do
        if grep -q "^RESULT $c pass$" "$out"; then echo "  [red$bits $c] GUARD held"
        else echo "  [red$bits $c] GATE FAILURE: the GUARD did not hold in the comparison arm"; FAIL=1; fi
    done

    echo "--- SHIPPED ARM (-DPREPARED_SEND_UNFIXED=0): every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
    out="$BUILD_DIR/green$bits.out"
    bounded "$out" "$BUILD_DIR/green$bits"; rc=$?
    if grep -Eq "$SAN_RE" "$out"; then
        echo "  [green$bits] GATE FAILURE: sanitizer report (rc=$rc)"; grep -E "$SAN_RE" "$out" | sed -n '1,3p' | sed 's/^/      /'; FAIL=1
    fi
    if [ $rc -ne 0 ] || ! grep -q "^ALL PASS$" "$out"; then
        echo "  [green$bits] GATE FAILURE: the shipped arm did not pass (rc=$rc)"; grep "^FAIL:" "$out" | sed 's/^/      /'; FAIL=1
    fi
    for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
        if grep -q "^RESULT $c pass$" "$out"; then echo "  [green$bits $c] passed"
        else echo "  [green$bits $c] GATE FAILURE: not passed"; FAIL=1; fi
    done
done

# ---- differential (opt-in) -----------------------------------------------------------------
if [ -z "$REFERENCE" ]; then
    echo "--- DIFFERENTIAL: not run (set PREPARED_SEND_REFERENCE=<prepared_send.h> to compare against it) ---"
elif [ ! -f "$REFERENCE" ] || ! grep -q 'prepared_send_check' "$REFERENCE"; then
    echo "--- DIFFERENTIAL: GATE FAILURE: $REFERENCE is not a prepared_send.h ---"; FAIL=1
else
    echo "--- DIFFERENTIAL against $REFERENCE: both headers answer alike, both arms (64-bit) ---"
    for unfixed in 0 1; do
        bin="$BUILD_DIR/diff$unfixed"; out="$bin.out"
        if ! build "$bin" 64 "$unfixed" -DPREPARED_SEND_DIFFERENTIAL=1 -include "$REFERENCE"; then
            echo "  [diff =$unfixed] GATE FAILURE: did not compile"; FAIL=1; continue
        fi
        bounded "$out" "$bin" differential; rc=$?
        note="$(grep '^NOTE:' "$out")"
        if [ $rc -eq 0 ] && grep -q '^RESULT differential pass$' "$out" && ! grep -Eq "$SAN_RE" "$out"; then
            echo "  [diff =$unfixed] same answers: ${note#NOTE: }"
        else
            echo "  [diff =$unfixed] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" "$out" | sed -n '1,8p' | sed 's/^/      /'; FAIL=1
        fi
    done
fi

echo
if [ $FAIL -eq 0 ]; then
    echo "prepared_send_kat: GATE HELD (${#RED_CASES[@]} RED cases failed with nothing checked, ${#GUARD_CASES[@]} guards held; GREEN all $(( ${#RED_CASES[@]} + ${#GUARD_CASES[@]} )) under ASan + UBSan, ${BITS[*]}-bit)"
    exit 0
fi
echo "FAIL: prepared_send_kat"
exit 1
