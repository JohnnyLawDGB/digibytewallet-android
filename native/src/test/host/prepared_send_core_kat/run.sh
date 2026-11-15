#!/usr/bin/env bash
# Host KAT runner for the core's BRPreparedSend.h: the checks between a DGB send's review and its signature
# (the fee a confirmation shows, and whether the bytes about to be signed are the approved send), which iOS
# calls from the core. The bridge keeps its own copy (native/src/main/jni/bridge/prepared_send.h, held by
# fee_quote_kat); the core header must give the same verdicts in every arm, so the bridge can include it in
# place of its copy.
#
# No cases of its own: it compiles fee_quote_kat's fee_quote_kat_main.c, unchanged, against the CORE header,
# through a shim prepared_send.h written here that names the core's functions by the bridge's names
# (prepared_send_quote = BRPreparedSendQuote, prepared_send_check = BRPreparedSendCheck,
# prepared_send_coin_ok = BRPreparedSendCoinOK, PREPARED_SEND_* = BRPreparedSend*). The case lists and their
# expectations are read from fee_quote_kat/run.sh, so the two suites cannot drift:
#   comparison  -DPREPARED_SEND_UNFIXED=1: the typical-send figure, nothing checked before signing; each case
#               meets fee_quote_kat's CASES expectation (fail, or pass for a guard);
#   SEND-GATE-SET -DSEND_GATE_SET_UNFIXED=1 (the raw set the balance sums, BRWalletUTXOs) and =2 (the selectable
#               set enumerated under one walk budget): each case meets SET_CASES / BUDGET_CASES;
#   shipped     every case passes, with no sanitizer report.
# 64-bit and 32-bit (-m32), AddressSanitizer, every run time-limited.
#
# Gates first: the core header carries both seams and tests their VALUE (#if, never #ifdef); the shim's
# include resolves to the core header and never to the bridge copy (the bridge directory is not on the
# include path); and a DROP-IN GATE: one translation unit includes the core header and the bridge copy
# together and asserts at compile time that every verdict has the same value under both names.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DPREPARED_SEND_UNFIXED and -DSEND_GATE_SET_UNFIXED (=0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arms' nonzero exits are inspected
# Here-strings and files only, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
FQ_DIR="$SCRIPT_DIR/../fee_quote_kat"
HEADER="$CORE_DIR/BRPreparedSend.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60

# ---- the cases: fee_quote_kat's own lists --------------------------------------------------
for f in "$FQ_DIR/run.sh" "$FQ_DIR/fee_quote_kat_main.c" "$HEADER" "$BRIDGE_DIR/prepared_send.h"; do
    [ -f "$f" ] || { echo "GATE FAILURE: $f not found."; exit 1; }
done
LISTS="$(sed -n -e '/^CASES=(/,/^)/p' -e '/^SET_CASES=(/,/^)/p' -e '/^BUDGET_CASES=(/,/^)/p' "$FQ_DIR/run.sh")"
CASES=(); SET_CASES=(); BUDGET_CASES=()
eval "$LISTS"
if [ "${#CASES[@]}" -lt 10 ] || [ "${#SET_CASES[@]}" -lt 3 ] || [ "${#BUDGET_CASES[@]}" -lt 3 ]; then
    echo "GATE FAILURE: fee_quote_kat/run.sh's case lists could not be read (${#CASES[@]}/${#SET_CASES[@]}/${#BUDGET_CASES[@]})."; exit 1
fi
for want in "check_coin_foreign_unconfirmed:fail" "check_own_change_signs:pass" "check_budget_own_change:pass"; do
    case " ${CASES[*]} " in *" $want "*) ;; *) echo "GATE FAILURE: fee_quote_kat's CASES no longer hold $want."; exit 1 ;; esac
done
case " ${SET_CASES[*]} " in *" check_coin_foreign_unconfirmed:fail "*) ;; *) echo "GATE FAILURE: SET_CASES no longer fails check_coin_foreign_unconfirmed."; exit 1 ;; esac
case " ${BUDGET_CASES[*]} " in *" check_budget_own_change:fail "*) ;; *) echo "GATE FAILURE: BUDGET_CASES no longer fails check_budget_own_change."; exit 1 ;; esac

# ---- the seams are real --------------------------------------------------------------------
for m in PREPARED_SEND_UNFIXED SEND_GATE_SET_UNFIXED; do
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*if[[:space:]]+!?[[:space:]]*\(?$m" "$HEADER"; then
        echo "GATE FAILURE: $HEADER does not test the value of $m; its arms would not differ."; exit 1
    fi
    if grep -Eq "^[[:space:]]*#[[:space:]]*if(n)?def[[:space:]]+$m" "$HEADER" &&
       ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+$m" "$HEADER"; then
        echo "GATE FAILURE: $HEADER selects an arm of $m by the macro's PRESENCE."; exit 1
    fi
done
grep -Eq 'BRWalletOutpointSelectable[[:space:]]*\(' "$HEADER" ||
    { echo "GATE FAILURE: $HEADER does not ask BRWalletOutpointSelectable (SEND-GATE-SET)."; exit 1; }

# ---- the shim: the bridge's names for the core header --------------------------------------
mkdir -p "$BUILD_DIR/shim"
cat > "$BUILD_DIR/shim/prepared_send.h" <<'SHIM'
/* prepared_send_core_kat: the bridge's names for the core's BRPreparedSend.h. */
#ifndef PREPARED_SEND_CORE_SHIM_H
#define PREPARED_SEND_CORE_SHIM_H
#include "BRPreparedSend.h"
#define PREPARED_SEND_CORE_HEADER 1
typedef BRPreparedSendVerdict PreparedSendVerdict;
#define PREPARED_SEND_OK               BRPreparedSendOK
#define PREPARED_SEND_FEE_UNKNOWN      BRPreparedSendFeeUnknown
#define PREPARED_SEND_FEE_CHANGED      BRPreparedSendFeeChanged
#define PREPARED_SEND_COIN_UNAVAILABLE BRPreparedSendCoinUnavailable
#define PREPARED_SEND_NOT_APPROVED     BRPreparedSendNotApproved
#define prepared_send_quote            BRPreparedSendQuote
#define prepared_send_check            BRPreparedSendCheck
#define prepared_send_coin_ok          BRPreparedSendCoinOK
#endif
SHIM
# fee_quote_kat_main.c with a check that the shim, and so the core header, is what it included
{ cat "$FQ_DIR/fee_quote_kat_main.c"
  printf '\n#ifndef PREPARED_SEND_CORE_HEADER\n#error "prepared_send.h is not the core shim"\n#endif\n#ifdef PREPARED_SEND_H\n#error "the bridge copy was included"\n#endif\n'
} > "$BUILD_DIR/main.c"

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

FAIL=0

# ---- DROP-IN GATE: one verdict, one value, under both names ----------------------------------
cat > "$BUILD_DIR/dropin.c" <<'DROPIN'
#include "BRPreparedSend.h"
#include "prepared_send.h"
#define SAME(a, b) _Static_assert((int)(a) == (int)(b), #a " is not " #b)
SAME(BRPreparedSendOK, PREPARED_SEND_OK);
SAME(BRPreparedSendFeeUnknown, PREPARED_SEND_FEE_UNKNOWN);
SAME(BRPreparedSendFeeChanged, PREPARED_SEND_FEE_CHANGED);
SAME(BRPreparedSendCoinUnavailable, PREPARED_SEND_COIN_UNAVAILABLE);
SAME(BRPreparedSendNotApproved, PREPARED_SEND_NOT_APPROVED);
_Static_assert(sizeof(BRPreparedSendVerdict) == sizeof(PreparedSendVerdict), "verdict types differ in size");
int main(void) { return 0; }
DROPIN
if clang -w -std=c11 -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        -c "$BUILD_DIR/dropin.c" -o "$BUILD_DIR/dropin.o"; then
    echo "drop-in gate: the core header and the bridge copy build together and give every verdict the same value"
else
    echo "DROP-IN GATE FAILED: the core header and the bridge copy disagree (or do not build together)"; FAIL=1
fi

build() {   # <out> <64|32> <PREPARED_SEND_UNFIXED> <SEND_GATE_SET_UNFIXED>
    local out="$1" bits="$2" unfixed="$3" setunfixed="$4"
    local m=(); [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} \
        -DPREPARED_SEND_UNFIXED="$unfixed" -DSEND_GATE_SET_UNFIXED="$setunfixed" \
        -I "$BUILD_DIR/shim" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$BUILD_DIR/main.c" "${UNITS[@]}" -lm -lpthread -o "$out"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was stopped at the limit (the watchdog
# leaves <outfile>.stopped before it kills; see fee_quote_kat/run.sh).
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }

# run_arm <tag> <binary> <case:expectation...>
run_arm() {
    local tag="$1" bin="$2"; shift 2
    local entry c want out rc
    for entry in "$@"; do
        c="${entry%%:*}"; want="${entry##*:}"; out="$BUILD_DIR/${tag//\//-}.$c.out"
        bounded "$out" "$bin" "$c"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [$tag $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
        if grep -Eq "$SAN_RE" "$out"; then echo "  [$tag $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
        if [ "$want" = fail ] && [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
            echo "  [$tag $c] failed, as it must: $(grep -m1 '^FAIL:' "$out" | cut -c1-150)"
        elif [ "$want" = pass ] && [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
            echo "  [$tag $c] passed"
        else
            echo "  [$tag $c] GATE FAILURE: expected $want (rc=$rc)"; show "$out" 12; FAIL=1
        fi
    done
}

for bits in 64 32; do
    echo "=== ${bits}-bit build ==="
    ok=1
    build "$BUILD_DIR/red$bits" "$bits" 1 0 || ok=0
    build "$BUILD_DIR/set1-$bits" "$bits" 0 1 || ok=0
    build "$BUILD_DIR/set2-$bits" "$bits" 0 2 || ok=0
    build "$BUILD_DIR/green$bits" "$bits" 0 0 || ok=0
    if [ $ok -ne 1 ]; then echo "GATE FAILURE: a ${bits}-bit arm did not compile (a build error is not evidence)."; FAIL=1; continue; fi

    known="$("$BUILD_DIR/green$bits" list | sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; FAIL=1; continue
    fi

    echo "--- COMPARISON ARM (-DPREPARED_SEND_UNFIXED=1): each case meets fee_quote_kat's expectation ---"
    run_arm "red$bits" "$BUILD_DIR/red$bits" "${CASES[@]}"
    echo "--- SEND-GATE-SET ARM (-DSEND_GATE_SET_UNFIXED=1): the raw coin set ---"
    run_arm "set1/$bits" "$BUILD_DIR/set1-$bits" "${SET_CASES[@]}"
    echo "--- SEND-GATE-SET ARM (-DSEND_GATE_SET_UNFIXED=2): the selectable set enumerated under one walk budget ---"
    run_arm "set2/$bits" "$BUILD_DIR/set2-$bits" "${BUDGET_CASES[@]}"
    echo "--- SHIPPED ARM: every case passes, no sanitizer report ---"
    run_arm "green$bits" "$BUILD_DIR/green$bits" $(printf '%s:pass\n' "${CASES[@]%%:*}")
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: prepared_send_core_kat (the core BRPreparedSend.h on fee_quote_kat's ${#CASES[@]} cases: comparison, two SEND-GATE-SET arms and shipped, each as fee_quote_kat expects; 64-bit and 32-bit, ASan; drop-in gate)"
    exit 0
fi
echo "FAIL: prepared_send_core_kat"
exit 1
