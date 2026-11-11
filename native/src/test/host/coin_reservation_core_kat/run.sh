#!/usr/bin/env bash
#
# coin_reservation_core_kat — the coin reservation and the spend gates, moved from the JNI bridge into the core
# (BRCoinReservation.{h,c}, BRSpendGate.h), give the bridge's answers. See coin_reservation_core_kat_main.c.
#
#   GREEN  the bridge's coin_reservation.c, spend_guard.h and signed_spend_gate.h and the core's units, side by side:
#          every scenario passes on both with the expected answers, under AddressSanitizer + UndefinedBehaviorSanitizer,
#          and again under ThreadSanitizer with no report.
#   RED    each comparison arm reaches the core's side only and must fail exactly its scenarios, every other passing:
#            BR_COIN_RESERVATION_UNFIXED=1            every claim granted
#            BR_COIN_RESERVATION_RED_TTL_INCLUSIVE    an entry survives at now == expires
#            BR_COIN_RESERVATION_RED_CAP_OFF_BY_ONE   a claim reaching exactly the bound is refused
#            BR_SPEND_GUARD_UNFIXED=1                 the guard allows everything
#            BR_SIGNED_SPEND_GATE_UNFIXED=1           the gate lets everything through
#            BR_SIGNED_SPEND_GATE_RED_NO_RECHECK      no second spent-set check after the claim
#   RED    BR_COIN_RESERVATION_RED_NO_LOCK (ThreadSanitizer): the table's mutex is not taken; TSan must report a data race.
#          The macro reaches the core's units only and the same units with the lock are the TSan green arm, which must
#          be clean, so the race is the core's. (The scenarios' answers are not required to differ: a race need not show
#          in one run.)
#   GATES  the two headers compile clean under -Wall -Wextra -Wpedantic -Werror, each included twice, and
#          BRCoinReservation.c under the same flags; the bridge's constants equal the core's (_Static_assert in _main.c).
set -uo pipefail   # NOT -e: a comparison arm's non-zero exit is captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

for f in BRCoinReservation.h BRCoinReservation.c BRSpendGate.h; do
    [ -f "$CORE_DIR/$f" ] || gate_fail "$CORE_DIR has no $f (core pin too old? set CORE_DIR)"
done
for f in coin_reservation.c coin_reservation.h spend_guard.h signed_spend_gate.h; do
    [ -f "$BRIDGE_DIR/$f" ] || gate_fail "the bridge has no $f (moved already? retire this suite's bridge side)"
done

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# ── the headers and the .c on their own terms ─────────────────────────────────────────────────────────────────────
# The other core headers are not -Wextra clean, so they come in as system headers; these are checked from a copy.
mkdir -p "$BUILD_DIR/hdr" && cp "$CORE_DIR/BRCoinReservation.h" "$CORE_DIR/BRSpendGate.h" "$CORE_DIR/BRCoinReservation.c" "$BUILD_DIR/hdr/"
cat > "$BUILD_DIR/hdr.c" <<'EOF'
#include "BRSpendGate.h"
#include "BRSpendGate.h"
#include "BRCoinReservation.h"
#include "BRCoinReservation.h"
int main(void) { BRCoinReservationClear(); return BRSignedSpendGate(NULL, NULL, 0.0) + BRSpendGuardInputsUnspent(NULL, NULL); }
EOF
for std in c99 c11 c17; do
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" \
        -fsyntax-only "$BUILD_DIR/hdr.c" || gate_fail "the headers do not compile clean under -std=$std -Wall -Wextra -Werror"
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" \
        -fsyntax-only "$BUILD_DIR/hdr/BRCoinReservation.c" || gate_fail "BRCoinReservation.c does not compile clean under -std=$std"
done
echo "  [gate] BRCoinReservation.h, BRSpendGate.h (each twice) and BRCoinReservation.c clean under -Wall -Wextra -Wpedantic -Werror, c99/c11/c17"

INC=(-I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")
CORE_LIB=("$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}")
ASAN=(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g)
TSAN=(-fsanitize=thread -fno-omit-frame-pointer -g)

# build <out> <sanitizer flags var name> [core-side -D...]
build() {
    local out="$1" sanvar="$2"; shift 2
    local -a san
    eval "san=(\"\${${sanvar}[@]}\")"
    local d="$BUILD_DIR/$(basename "$out").o"
    mkdir -p "$d"
    clang -w -include stdint.h "${san[@]}" "${INC[@]}" -c "$SCRIPT_DIR/coin_reservation_core_kat_main.c" -o "$d/main.o" || return 1
    clang -w -include stdint.h "${san[@]}" "${INC[@]}" -Dcoin_reservation_claim=kat_bridge_claim -DBRCoinReservationClaim=kat_core_claim \
        "$@" -c "$SCRIPT_DIR/coin_reservation_core_kat_gates.c" -o "$d/gates.o" || return 1
    clang -w -include stdint.h "${san[@]}" "${INC[@]}" "$@" -c "$CORE_DIR/BRCoinReservation.c" -o "$d/core.o" || return 1
    clang -w -include stdint.h "${san[@]}" "${INC[@]}" -c "$BRIDGE_DIR/coin_reservation.c" -o "$d/bridge.o" || return 1
    clang -w -include stdint.h "${san[@]}" "${INC[@]}" "$d/main.o" "$d/gates.o" "$d/core.o" "$d/bridge.o" "${CORE_LIB[@]}" \
        -lm -lpthread -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:|WARNING: ThreadSanitizer'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
# symbolize=0: a report is judged by its warning line, never by a file name in its summary, so no arm waits on a
# symbolizer (macOS's atos can hang until the time limit).
export TSAN_OPTIONS="halt_on_error=0 exitcode=66 symbolize=0"
FAIL=0

run_arm() {   # $1 bin -> sets OUT, RC
    OUT="$(timeout 300 "$1" 2>&1)"; RC=$?
    [ -n "${KAT_LOG_DIR:-}" ] && printf '%s\n' "$OUT" > "$KAT_LOG_DIR/$(basename "$1").log"
}

green() {   # $1 label, $2 sanitizer var
    build "$BUILD_DIR/green_$1" "$2" || { echo "GATE FAILURE: the $1 green arm did not build"; FAIL=1; return; }
    run_arm "$BUILD_DIR/green_$1"
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "^PASS: coin_reservation_core_kat" \
       && ! <<<"$OUT" grep -q "^SCENARIO-FAIL"; then
        echo "  [green $1] $(<<<"$OUT" grep -c '^SCENARIO-PASS') scenarios: the bridge and the core give the expected answers on every one"
    else
        echo "GATE FAILURE: the $1 green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|$SAN_RE" | head -20; FAIL=1
    fi
}

# red <macro-or-macro=value> <space-separated scenarios that must fail>
red() {
    local macro="$1" want failed
    want="$(tr ' ' '\n' <<<"$2" | sed '/^$/d' | sort)"
    local tag="${macro%%=*}"
    build "$BUILD_DIR/red_$tag" ASAN "-D$macro" || { echo "GATE FAILURE: the $tag arm did not build"; FAIL=1; return; }
    run_arm "$BUILD_DIR/red_$tag"
    failed="$(<<<"$OUT" sed -n 's/^SCENARIO-FAIL: //p' | sort)"
    if <<<"$OUT" grep -Eq "$SAN_RE"; then
        echo "GATE FAILURE: the $tag arm faulted (a fault is not the failure it must show)"; <<<"$OUT" grep -Em3 "$SAN_RE"; FAIL=1
    elif <<<"$OUT" grep -q "FAIL: setup"; then
        echo "GATE FAILURE: the $tag arm failed in setup"; <<<"$OUT" grep "FAIL: setup" | head -3; FAIL=1
    elif [ "$RC" -ne 1 ] || [ "$failed" != "$want" ]; then
        echo "GATE FAILURE: the $tag arm (rc=$RC) failed [$(echo $failed)], required exactly [$(echo $want)]"; FAIL=1
    else
        echo "  [red $tag] failed exactly: $(echo $failed)"
    fi
}

# ── GREEN ────────────────────────────────────────────────────────────────────────────────────────────────────────
green asan_ubsan ASAN

printf 'int main(void){return 0;}\n' > "$BUILD_DIR/tprobe.c"
if clang -fsanitize=thread "$BUILD_DIR/tprobe.c" -o "$BUILD_DIR/tprobe" 2>/dev/null && "$BUILD_DIR/tprobe" >/dev/null 2>&1; then
    HAVE_TSAN=1
    green tsan TSAN
else
    HAVE_TSAN=0
    echo "  [tsan] skipped: ThreadSanitizer is not available here"
fi

# ── RED ──────────────────────────────────────────────────────────────────────────────────────────────────────────
red BR_COIN_RESERVATION_UNFIXED=1 "backwards_now backwards_renew gate_recheck_drops_earlier cr_A cr_B cr_D cr_E cr_F ttl_boundary ttl_renew_extends reclaim_after_expiry \
    reclaim_by_other_after_expiry cap_fill cap_511_plus_1 cap_dup_inputs dup_inputs_one_entry double_release \
    release_unknown release_one_owner partial_refusal own_plus_fresh null_and_empty conc_two_threads conc_stress rg_A rg_D rg_C \
    gate_wallet_ok"
red BR_COIN_RESERVATION_RED_TTL_INCLUSIVE "backwards_now backwards_renew ttl_boundary ttl_renew_extends reclaim_after_expiry \
    reclaim_by_other_after_expiry cap_expiry_frees"
red BR_COIN_RESERVATION_RED_CAP_OFF_BY_ONE "cr_F cap_fill cap_511_plus_1 cap_expiry_frees cap_dup_inputs"
red BR_SPEND_GUARD_UNFIXED=1 "sg_A sg_C rg_B gate_recheck gate_recheck_drops_earlier"
red BR_SIGNED_SPEND_GATE_UNFIXED=1 "rg_A rg_D rg_C rg_B rg_E gate_wallet_ok gate_recheck gate_recheck_drops_earlier"
red BR_SIGNED_SPEND_GATE_RED_NO_RECHECK "gate_recheck gate_recheck_drops_earlier"

if [ "$HAVE_TSAN" -eq 1 ]; then
    build "$BUILD_DIR/red_NO_LOCK" TSAN -DBR_COIN_RESERVATION_RED_NO_LOCK || { echo "GATE FAILURE: the NO_LOCK arm did not build"; FAIL=1; }
    if [ -x "$BUILD_DIR/red_NO_LOCK" ]; then
        run_arm "$BUILD_DIR/red_NO_LOCK"
        if <<<"$OUT" grep -q "WARNING: ThreadSanitizer: data race"; then
            echo "  [red BR_COIN_RESERVATION_RED_NO_LOCK] ThreadSanitizer reports a data race with the core's lock not taken"
        else
            echo "GATE FAILURE: the NO_LOCK arm (rc=$RC): ThreadSanitizer reported no data race"
            <<<"$OUT" grep -E "ThreadSanitizer|SCENARIO-FAIL" | head -6; FAIL=1
        fi
    fi
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: coin_reservation_core_kat (bridge and core agree on every scenario; seven comparison arms red on exactly theirs; ASan+UBSan$( [ "$HAVE_TSAN" -eq 1 ] && echo '+TSan'))"
    exit 0
fi
echo "FAIL: coin_reservation_core_kat"
exit 1
