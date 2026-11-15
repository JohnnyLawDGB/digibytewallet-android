#!/usr/bin/env bash
# Host KAT runner for the core's BRAssetSignGate.h and BROneFamilyGate.h: ASSET-SIGN-GATE and ONE-FAMILY at the
# DigiAsset signer, which iOS calls from the core (BRWalletBuildAssetTransfer, BRAssetSend.c, checks 15a, 15b and
# 18a). The bridge keeps its own copies (native/src/main/jni/bridge/asset_sign_gate.h, held by asset_sign_gate_kat;
# one_family_gate.h, held by one_family_kat); the core headers must give the same verdicts in every arm, so the
# bridge can include them in place of its copies.
#
# No cases of its own: it compiles asset_sign_gate_kat_main.c and one_family_kat_main.c, unchanged, against the
# CORE headers, through shims written here (asset_sign_gate.h, one_family_gate.h) that name the core's functions by
# the bridge's names (asset_sign_check = BRAssetSignCheck, AssetSignVerdict = BRAssetSignVerdict, ASSET_SIGN_* =
# BR_ASSET_SIGN_*, tx_inputs_one_family_ok = BRWalletTxInputsOneFamilyOK). The expectations are read from the two
# suites' run.sh, so the copies are held to one set of cases:
#   ASSET-SIGN-GATE  -DASSET_SIGN_GATE_UNFIXED=1: every pattern of asset_sign_gate_kat's RED1_MUST_FAIL fails, and no
#                    check outside those letters; =2: the same for RED2_MUST_FAIL; shipped: every check passes.
#                    ASan + UBSan, 64-bit, and 32-bit where a 32-bit link works (said so, and skipped, where it
#                    does not: no 32-bit link on an arm64 Mac).
#   ONE-FAMILY       -DONE_FAMILY_UNFIXED -DONE_FAMILY_SIGNER_UNFIXED: every pattern of one_family_kat's
#                    RED_MUST_FAIL fails, and no check outside its letters; -DONE_FAMILY_SIGNER_UNFIXED alone (the
#                    core header's seam, the wallet's rule shipped): exactly the "asset signer accepts" patterns of
#                    that list fail, so the core gate refuses on its own; shipped: every check passes. ASan.
#
# Gates first: the seams are tested the way the bridge copies test them (ASSET_SIGN_GATE_UNFIXED by VALUE with an
# #ifndef default, ONE_FAMILY_SIGNER_UNFIXED by PRESENCE with no default); the shims' includes resolve to the core
# headers and never to the bridge copies (the bridge directory is not on the include path); a DROP-IN GATE builds
# the core headers and the bridge copies in one unit, in every arm, and asserts at compile time that every verdict
# has the same value under both names; and a SOURCE GATE over BRAssetSend.c's shipped arm (preprocessed): the
# signer asks ONE-FAMILY and BRAssetSignCheck before its one BRWalletSignTransaction, and BRAssetSignCheck again
# after BRSignedSpendGate, each handed the recipient's script, BR_ASSET_MARKER_SATS, the plan's fee and the
# approved fee, and releases the claim when the second refuses.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_SIGN_GATE_UNFIXED (=1, =2 comparison, =0 shipped); PRESENCE of
# -DONE_FAMILY_SIGNER_UNFIXED and -DONE_FAMILY_UNFIXED (comparison when defined).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arms' nonzero exits are inspected
# Here-strings and files only, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
CORE_DIR="$(cd "$CORE_DIR" 2>/dev/null && pwd)" || { echo "GATE FAILURE: no core directory"; exit 1; }
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
ASG_DIR="$SCRIPT_DIR/../asset_sign_gate_kat"
OF_DIR="$SCRIPT_DIR/../one_family_kat"
SIGN_H="$CORE_DIR/BRAssetSignGate.h"
FAMILY_H="$CORE_DIR/BROneFamilyGate.h"
SEND_C="$CORE_DIR/BRAssetSend.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=0"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'
LIMIT_S=300
CC="${CC:-clang}"
FAIL=0

for f in "$ASG_DIR/run.sh" "$ASG_DIR/asset_sign_gate_kat_main.c" "$OF_DIR/run.sh" "$OF_DIR/one_family_kat_main.c" \
         "$SIGN_H" "$FAMILY_H" "$SEND_C" "$BRIDGE_DIR/asset_sign_gate.h" "$BRIDGE_DIR/one_family_gate.h"; do
    [ -f "$f" ] || { echo "GATE FAILURE: $f not found (core pin too old? set CORE_DIR)"; exit 1; }
done

# ---- the expectations: the two suites' own lists ---------------------------------------------------------------
LISTS="$(sed -n -e '/^RED1_MUST_FAIL=(/,/^)/p' -e '/^RED2_MUST_FAIL=(/,/^)/p' "$ASG_DIR/run.sh"; sed -n '/^RED_MUST_FAIL=(/,/^)/p' "$OF_DIR/run.sh")"
RED1_MUST_FAIL=(); RED2_MUST_FAIL=(); RED_MUST_FAIL=()
eval "$LISTS"
if [ "${#RED1_MUST_FAIL[@]}" -lt 10 ] || [ "${#RED2_MUST_FAIL[@]}" -lt 4 ] || [ "${#RED_MUST_FAIL[@]}" -lt 15 ]; then
    echo "GATE FAILURE: the suites' lists could not be read (${#RED1_MUST_FAIL[@]}/${#RED2_MUST_FAIL[@]}/${#RED_MUST_FAIL[@]})."; exit 1
fi
SIGNER_MUST_FAIL=()
for p in "${RED_MUST_FAIL[@]}"; do
    case "$p" in *"the asset signer accepts"*) SIGNER_MUST_FAIL+=("$p") ;; esac
done
[ "${#SIGNER_MUST_FAIL[@]}" -ge 5 ] ||
    { echo "GATE FAILURE: one_family_kat's list holds ${#SIGNER_MUST_FAIL[@]} signer checks, fewer than 5."; exit 1; }
for want in "^FAIL: K: a second recipient output is signed" "^FAIL: H: a signed send with redirected change is handed back"; do
    case " ${RED1_MUST_FAIL[*]} " in *"$want"*) ;; *) echo "GATE FAILURE: RED1_MUST_FAIL no longer holds '$want'."; exit 1 ;; esac
done
case " ${SIGNER_MUST_FAIL[*]} " in *"^FAIL: J: the asset signer accepts an input the wallet does not hold"*) ;;
    *) echo "GATE FAILURE: one_family_kat's list no longer holds its case J signer check."; exit 1 ;; esac

# letters <pattern...>: the case letters the patterns name, as a regex alternation ("B|C|...").
letters() {
    local p out=""
    for p in "$@"; do
        p="${p#^FAIL: }"; p="${p%%:*}"
        case "|$out|" in *"|$p|"*) ;; *) out="${out:+$out|}$p" ;; esac
    done
    printf '%s' "$out"
}

# ---- the seams are tested as the bridge copies test them -----------------------------------------------------
grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_SIGN_GATE_UNFIXED' "$SIGN_H" ||
    { echo "GATE FAILURE: $SIGN_H gives ASSET_SIGN_GATE_UNFIXED no default."; exit 1; }
for t in '==[[:space:]]*1' '!=[[:space:]]*2'; do
    grep -Eq "^[[:space:]]*#[[:space:]]*if[[:space:]]+ASSET_SIGN_GATE_UNFIXED[[:space:]]*$t" "$SIGN_H" ||
        { echo "GATE FAILURE: $SIGN_H does not test the VALUE of ASSET_SIGN_GATE_UNFIXED ($t)."; exit 1; }
done
if grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_SIGN_GATE_UNFIXED' "$SIGN_H"; then
    echo "GATE FAILURE: $SIGN_H selects an arm of ASSET_SIGN_GATE_UNFIXED by the macro's PRESENCE."; exit 1
fi
grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ONE_FAMILY_SIGNER_UNFIXED' "$FAMILY_H" ||
    { echo "GATE FAILURE: $FAMILY_H does not test ONE_FAMILY_SIGNER_UNFIXED by presence, as the bridge copy does."; exit 1; }
if grep -Eq '^[[:space:]]*#[[:space:]]*define[[:space:]]+ONE_FAMILY_SIGNER_UNFIXED' "$FAMILY_H"; then
    echo "GATE FAILURE: $FAMILY_H defines ONE_FAMILY_SIGNER_UNFIXED: a unit with the bridge copy would build its comparison arm."; exit 1
fi
for fn in BRWalletOutpointSelectable BRWalletAssetUTXOs BRWalletOutpointAssetState BRWalletContainsAddress; do
    grep -Eq "$fn[[:space:]]*\(" "$SIGN_H" || { echo "GATE FAILURE: $SIGN_H does not ask $fn."; exit 1; }
done
grep -Eq 'BRWalletOutpointIsOneFamilyHeld[[:space:]]*\(' "$FAMILY_H" ||
    { echo "GATE FAILURE: $FAMILY_H does not ask BRWalletOutpointIsOneFamilyHeld."; exit 1; }

# ---- the shims: the bridge's names for the core headers ----------------------------------------------------------
mkdir -p "$BUILD_DIR/shim"
cat > "$BUILD_DIR/shim/asset_sign_gate.h" <<'SHIM'
/* asset_sign_gate_core_kat: the bridge's names for the core's BRAssetSignGate.h. */
#ifndef ASSET_SIGN_GATE_CORE_SHIM_H
#define ASSET_SIGN_GATE_CORE_SHIM_H
#include "BRAssetSignGate.h"
#define ASSET_SIGN_GATE_CORE_HEADER 1
typedef BRAssetSignVerdict AssetSignVerdict;
#define ASSET_SIGN_OK                 BR_ASSET_SIGN_OK
#define ASSET_SIGN_COIN_UNKNOWN       BR_ASSET_SIGN_COIN_UNKNOWN
#define ASSET_SIGN_AMOUNT_MISMATCH    BR_ASSET_SIGN_AMOUNT_MISMATCH
#define ASSET_SIGN_COIN_NOT_WALLETS   BR_ASSET_SIGN_COIN_NOT_WALLETS
#define ASSET_SIGN_NOT_APPROVED       BR_ASSET_SIGN_NOT_APPROVED
#define ASSET_SIGN_FEE_CHANGED        BR_ASSET_SIGN_FEE_CHANGED
#define ASSET_SIGN_RECIPIENT_CHANGED  BR_ASSET_SIGN_RECIPIENT_CHANGED
#define ASSET_SIGN_FEE_ABOVE_APPROVED BR_ASSET_SIGN_FEE_ABOVE_APPROVED
#define asset_sign_check              BRAssetSignCheck
#endif
SHIM
cat > "$BUILD_DIR/shim/one_family_gate.h" <<'SHIM'
/* asset_sign_gate_core_kat: the bridge's name for the core's BROneFamilyGate.h. */
#ifndef ONE_FAMILY_GATE_CORE_SHIM_H
#define ONE_FAMILY_GATE_CORE_SHIM_H
#include "BROneFamilyGate.h"
#define ONE_FAMILY_GATE_CORE_HEADER 1
#define tx_inputs_one_family_ok BRWalletTxInputsOneFamilyOK
#endif
SHIM
# Each main, unchanged, with a check that the shim, and so the core header, is what it included.
{ cat "$ASG_DIR/asset_sign_gate_kat_main.c"
  printf '\n#ifndef ASSET_SIGN_GATE_CORE_HEADER\n#error "asset_sign_gate.h is not the core shim"\n#endif\n#ifdef ASSET_SIGN_GATE_H\n#error "the bridge copy was included"\n#endif\n'
} > "$BUILD_DIR/asg_main.c"
{ cat "$OF_DIR/one_family_kat_main.c"
  printf '\n#ifndef ONE_FAMILY_GATE_CORE_HEADER\n#error "one_family_gate.h is not the core shim"\n#endif\n#ifdef ONE_FAMILY_GATE_H\n#error "the bridge copy was included"\n#endif\n'
} > "$BUILD_DIR/of_main.c"

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRAddress.c "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c
       "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c
       "$CORE_DIR"/crypto/groestl.c "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c
       ${SHA3[@]+"${SHA3[@]}"})

# ---- DROP-IN GATE: one verdict, one value, under both names, in every arm -----------------------------------------
cat > "$BUILD_DIR/dropin.c" <<'DROPIN'
#include "BRAssetSignGate.h"
#include "BROneFamilyGate.h"
#include "asset_sign_gate.h"
#include "one_family_gate.h"
#define SAME(a, b) _Static_assert((int)(a) == (int)(b), #a " is not " #b)
SAME(BR_ASSET_SIGN_OK, ASSET_SIGN_OK);
SAME(BR_ASSET_SIGN_COIN_UNKNOWN, ASSET_SIGN_COIN_UNKNOWN);
SAME(BR_ASSET_SIGN_AMOUNT_MISMATCH, ASSET_SIGN_AMOUNT_MISMATCH);
SAME(BR_ASSET_SIGN_COIN_NOT_WALLETS, ASSET_SIGN_COIN_NOT_WALLETS);
SAME(BR_ASSET_SIGN_NOT_APPROVED, ASSET_SIGN_NOT_APPROVED);
SAME(BR_ASSET_SIGN_FEE_CHANGED, ASSET_SIGN_FEE_CHANGED);
SAME(BR_ASSET_SIGN_RECIPIENT_CHANGED, ASSET_SIGN_RECIPIENT_CHANGED);
SAME(BR_ASSET_SIGN_FEE_ABOVE_APPROVED, ASSET_SIGN_FEE_ABOVE_APPROVED);
_Static_assert(sizeof(BRAssetSignVerdict) == sizeof(AssetSignVerdict), "verdict types differ in size");
/* Both copies of each function in one unit, with the same signature. */
static BRAssetSignVerdict (*const coreSign)(BRWallet *, const BRTransaction *, const uint8_t *, size_t, uint64_t, uint64_t,
                                            uint64_t) = BRAssetSignCheck;
static AssetSignVerdict (*const bridgeSign)(BRWallet *, const BRTransaction *, const uint8_t *, size_t, uint64_t, uint64_t,
                                            uint64_t) = asset_sign_check;
static int (*const coreFamily)(BRWallet *, const BRTransaction *) = BRWalletTxInputsOneFamilyOK;
static int (*const bridgeFamily)(BRWallet *, const BRTransaction *) = tx_inputs_one_family_ok;
int main(void) { return (coreSign && bridgeSign && coreFamily && bridgeFamily) ? 0 : 1; }
DROPIN
dropin_ok=1
for arm in "" "-DASSET_SIGN_GATE_UNFIXED=1" "-DASSET_SIGN_GATE_UNFIXED=2" "-DONE_FAMILY_SIGNER_UNFIXED"; do
    if ! "$CC" -w -std=c11 -include stdint.h ${arm:+$arm} -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
            -c "$BUILD_DIR/dropin.c" -o "$BUILD_DIR/dropin.o"; then
        echo "DROP-IN GATE FAILED (${arm:-shipped}): the core headers and the bridge copies disagree (or do not build together)"
        dropin_ok=0; FAIL=1
    fi
done
[ "$dropin_ok" -eq 1 ] &&
    echo "drop-in gate: the core headers and the bridge copies build together in every arm and give every verdict the same value"

# ---- SOURCE GATE: where BRWalletBuildAssetTransfer asks them ------------------------------------------------------
src_fail=0
say() { echo "  source gate: $*"; src_fail=1; }
if "$CC" -w -include stdint.h -E -P -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$SEND_C" -o "$BUILD_DIR/send.i"; then
    # The definition: the last line that opens the name (the header's prototype comes first).
    DEF="$(grep -n '^BRAssetTransferStatus BRWalletBuildAssetTransfer(' "$BUILD_DIR/send.i" | tail -n 1 | cut -d: -f1)"
    BODY=""
    [ -n "$DEF" ] && BODY="$(sed -n "$DEF,\$p" "$BUILD_DIR/send.i" | awk '{ print } /^}/ { exit }' | tr '\n\t' '  ' | sed -E 's/ +/ /g')"
    offsets() { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }
    if [ -z "$BODY" ]; then
        say "BRWalletBuildAssetTransfer not found in the preprocessed BRAssetSend.c"
    else
        CHECKS=($(offsets 'BRAssetSignCheck[[:space:]]*\(' "$BODY"))
        FAMILY=($(offsets 'BRWalletTxInputsOneFamilyOK[[:space:]]*\([[:space:]]*wallet[[:space:]]*,[[:space:]]*tx[[:space:]]*\)' "$BODY"))
        SIGNS=($(offsets 'BRWalletSignTransaction[[:space:]]*\(' "$BODY"))
        GATES=($(offsets 'BRSignedSpendGate[[:space:]]*\(' "$BODY"))
        REL=($(offsets 'BRCoinReservationRelease[[:space:]]*\([[:space:]]*tx->txHash' "$BODY"))
        [ "${#CHECKS[@]}" -eq 2 ] || say "BRAssetSignCheck must be asked exactly twice, before and after signing (found ${#CHECKS[@]})"
        [ "${#FAMILY[@]}" -eq 1 ] || say "BRWalletTxInputsOneFamilyOK(wallet, tx) must be asked exactly once (found ${#FAMILY[@]})"
        [ "${#SIGNS[@]}" -eq 1 ] || say "the signer must sign exactly once (found ${#SIGNS[@]})"
        [ "${#GATES[@]}" -eq 1 ] || say "the signer must pass BRSignedSpendGate exactly once (found ${#GATES[@]})"
        if [ "${#CHECKS[@]}" -eq 2 ] && [ "${#SIGNS[@]}" -eq 1 ] && [ "${#GATES[@]}" -eq 1 ] && [ "${#FAMILY[@]}" -eq 1 ]; then
            [ "${FAMILY[0]}" -lt "${SIGNS[0]}" ] || say "ONE-FAMILY is not asked before the signature"
            [ "${CHECKS[0]}" -lt "${SIGNS[0]}" ] || say "the first BRAssetSignCheck does not come before the signature"
            [ "${CHECKS[1]}" -gt "${GATES[0]}" ] || say "the second BRAssetSignCheck does not come after BRSignedSpendGate (its claim)"
            rel_after=0
            for r in ${REL[@]+"${REL[@]}"}; do [ "$r" -gt "${CHECKS[1]}" ] && rel_after=1; done
            [ "$rel_after" -eq 1 ] || say "a refusal by the second BRAssetSignCheck does not release the claim"
        fi
        n_bound="$(grep -oE 'BRAssetSignCheck[[:space:]]*\([[:space:]]*wallet[[:space:]]*,[[:space:]]*tx[[:space:]]*,[[:space:]]*recipientScript[[:space:]]*,[[:space:]]*recipientLen[[:space:]]*,[[:space:]]*(BR_ASSET_MARKER_SATS|6000)[[:space:]]*,[[:space:]]*\(uint64_t\)[[:space:]]*plan->paidFeeSats[[:space:]]*,[[:space:]]*\(uint64_t\)[[:space:]]*approvedFeeSats[[:space:]]*\)' <<<"$BODY" | wc -l | tr -d ' ')"
        [ "$n_bound" -eq 2 ] || say "each BRAssetSignCheck must be handed the recipient's script, the marker, the plan's fee and the approved fee (found $n_bound)"
        n_refuse="$(grep -oE 'if[[:space:]]*\([[:space:]]*rep\.signVerdict[[:space:]]*!=[[:space:]]*BR_ASSET_SIGN_OK[[:space:]]*\)' <<<"$BODY" | wc -l | tr -d ' ')"
        [ "$n_refuse" -eq 2 ] || say "each BRAssetSignCheck must refuse on a verdict other than BR_ASSET_SIGN_OK (found $n_refuse)"
    fi
else
    say "BRAssetSend.c does not preprocess"
fi
if [ "$src_fail" -eq 0 ]; then
    echo "source gate: BRWalletBuildAssetTransfer asks ONE-FAMILY and the sign gate before its one signature, and the sign gate again after its claim"
else
    echo "SOURCE GATE FAILED"; FAIL=1
fi

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was stopped at the limit (the watchdog leaves
# <outfile>.stopped before it kills; see prepared_send_core_kat/run.sh).
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

build() {   # <out> <main.c> <sanitizers> <extra flags...>
    local out="$1" main="$2" san="$3"; shift 3
    "$CC" -w -include stdint.h -g -fsanitize="$san" -fno-omit-frame-pointer "$@" \
        -I "$BUILD_DIR/shim" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$main" "${UNITS[@]}" -lm -lpthread -o "$out"
}

outname() { local t="${1// /_}"; printf '%s.out' "${t//\//-}"; }

# red_arm <tag> <binary> <pass line> <letters> <pattern...>: the binary exits nonzero with no sanitizer report,
# every pattern is a FAIL line, and no FAIL line names a letter outside <letters>.
red_arm() {
    local tag="$1" bin="$2" passline="$3" lets="$4"; shift 4
    local out="$BUILD_DIR/$(outname "$tag")" rc ok=1 want extra
    bounded "$out" "$bin"; rc=$?
    [ "$rc" -eq 124 ] && { echo "  [$tag] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; return; }
    [ "$rc" -ne 0 ] || ok=0
    grep -Eq "$SAN_RE" "$out" && { echo "  [$tag] sanitizer report"; ok=0; }
    grep -q "$passline" "$out" && ok=0
    for want in "$@"; do grep -q -- "$want" "$out" || { echo "  [$tag] did not fail: $want"; ok=0; }; done
    extra="$(grep '^FAIL' "$out" | grep -vE "^FAIL: ($lets): ")"
    [ -z "$extra" ] || { echo "  [$tag] an unexpected check failed: $extra"; ok=0; }
    if [ "$ok" -eq 1 ]; then echo "  [$tag] $# named checks failed, as required; the guards and setup held"
    else echo "  [$tag] GATE FAILURE (rc=$rc)"; grep -E "FAIL|ERROR|runtime" "$out" | head -20; FAIL=1; fi
}

green_arm() {   # <tag> <binary> <pass line>
    local tag="$1" bin="$2" passline="$3" out="$BUILD_DIR/$(outname "$1")" rc
    bounded "$out" "$bin"; rc=$?
    if [ "$rc" -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "$passline" "$out" && ! grep -q '^FAIL' "$out"; then
        echo "  [$tag] all $(grep -c '^PASS:' "$out") checks pass"
    else
        echo "  [$tag] GATE FAILURE (rc=$rc)"; grep -E "FAIL|ERROR|runtime" "$out" | head -20; FAIL=1
    fi
}

ASG_PASS="^PASS: asset_sign_gate_kat"
OF_PASS="^PASS: one_family_kat"
L1="$(letters "${RED1_MUST_FAIL[@]}")"; L2="$(letters "${RED2_MUST_FAIL[@]}")"
LF="$(letters "${RED_MUST_FAIL[@]}")"; LS="$(letters "${SIGNER_MUST_FAIL[@]}")"

# ---- ASSET-SIGN-GATE: asset_sign_gate_kat's cases on the core header ----------------------------------------------
asg_arms() {   # <bits> <-m32 or empty>
    local bits="$1" m="${2:-}" ok=1
    build "$BUILD_DIR/asg_red1_$bits" "$BUILD_DIR/asg_main.c" address,undefined -fno-sanitize-recover=undefined ${m:+$m} -DASSET_SIGN_GATE_UNFIXED=1 || ok=0
    build "$BUILD_DIR/asg_red2_$bits" "$BUILD_DIR/asg_main.c" address,undefined -fno-sanitize-recover=undefined ${m:+$m} -DASSET_SIGN_GATE_UNFIXED=2 || ok=0
    build "$BUILD_DIR/asg_green_$bits" "$BUILD_DIR/asg_main.c" address,undefined -fno-sanitize-recover=undefined ${m:+$m} -DASSET_SIGN_GATE_UNFIXED=0 || ok=0
    if [ "$ok" -ne 1 ]; then echo "GATE FAILURE: an ASSET-SIGN-GATE ${bits}-bit arm did not compile (a build error is not evidence)."; FAIL=1; return; fi
    red_arm "asg red1/$bits" "$BUILD_DIR/asg_red1_$bits" "$ASG_PASS" "$L1" "${RED1_MUST_FAIL[@]}"
    red_arm "asg red2/$bits" "$BUILD_DIR/asg_red2_$bits" "$ASG_PASS" "$L2" "${RED2_MUST_FAIL[@]}"
    green_arm "asg green/$bits" "$BUILD_DIR/asg_green_$bits" "$ASG_PASS"
}
echo "=== ASSET-SIGN-GATE (asset_sign_gate_kat's cases) ==="
asg_arms 64
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/probe32.c"
bits_note="32-bit skipped: no 32-bit link here"
if "$CC" -m32 -g -fsanitize=address,undefined "$BUILD_DIR/probe32.c" -o "$BUILD_DIR/probe32" >/dev/null 2>&1 && "$BUILD_DIR/probe32"; then
    before=$FAIL
    asg_arms 32 -m32
    bits_note="64-bit and 32-bit"
    [ "$FAIL" -ne "$before" ] && bits_note="32-bit FAILED"
else
    echo "  ($bits_note)"
fi

# ---- ONE-FAMILY: one_family_kat's cases on the core header --------------------------------------------------------
echo "=== ONE-FAMILY (one_family_kat's cases) ==="
if build "$BUILD_DIR/of_red" "$BUILD_DIR/of_main.c" address -DONE_FAMILY_UNFIXED -DONE_FAMILY_SIGNER_UNFIXED &&
   build "$BUILD_DIR/of_signer" "$BUILD_DIR/of_main.c" address -DONE_FAMILY_SIGNER_UNFIXED &&
   build "$BUILD_DIR/of_green" "$BUILD_DIR/of_main.c" address; then
    red_arm "of red" "$BUILD_DIR/of_red" "$OF_PASS" "$LF" "${RED_MUST_FAIL[@]}"
    red_arm "of signer-only" "$BUILD_DIR/of_signer" "$OF_PASS" "$LS" "${SIGNER_MUST_FAIL[@]}"
    # signer-only: no FAIL line may be any but the signer's own checks.
    extra="$(grep '^FAIL' "$BUILD_DIR/$(outname "of signer-only")" | grep -v 'the asset signer accepts')"
    [ -z "$extra" ] || { echo "  [of signer-only] GATE FAILURE: a check other than the signer's failed: $extra"; FAIL=1; }
    green_arm "of green" "$BUILD_DIR/of_green" "$OF_PASS"
else
    echo "GATE FAILURE: a ONE-FAMILY arm did not compile (a build error is not evidence)."; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: asset_sign_gate_core_kat (the core BRAssetSignGate.h on asset_sign_gate_kat's cases, two comparison arms and shipped, $bits_note, ASan+UBSan; BROneFamilyGate.h on one_family_kat's cases, both seams, the signer's alone, and shipped, ASan; drop-in gate; source gate over BRAssetSend.c)"
    exit 0
fi
echo "FAIL: asset_sign_gate_core_kat"
exit 1
