#!/usr/bin/env bash
#
# asset_send_kat -- the DigiAsset transfer builder and signer, moved from the JNI bridge into the core (BRAssetSend.c,
# BRWalletBuildAssetTransfer), signs byte for byte what the bridge signs, and refuses where BRAssetSend.h says it
# does. See the header of asset_send_kat_main.c for the scenarios.
#
#   GREEN    the bridge's jni_asset_send.c (NativeBridge_buildAndSignAssetTransferTx, signing through jni_wallet.c's
#            seed_sign_transaction) and the core's BRAssetSend.c side by side over one wallet the bridge recovered:
#            every scenario passes under AddressSanitizer and UndefinedBehaviorSanitizer (64-bit). Where both sign,
#            the bytes are the same; every core refusal is the status its scenario names.
#   RED      one arm per check (BR_ASSET_TRANSFER_UNFIXED=<bit>, on the core's unit only). Each must fail exactly
#            its scenarios and pass every other one, with no sanitizer report.
#   MUTANTS  every `if (` of BRAssetSend.c the shipped arm compiles, one at a time, made never to fire (its condition
#            C becomes `0 && (C)`): the green arm must fail on each, except the lines listed in SURVIVORS with the
#            reason the KAT cannot see them.
#   GATES    the files compiled are the shipping ones; the seam is tested by value; the arms' bits are the ones
#            BRAssetSend.c reads; the Kotlin caller still writes the arrays the KAT writes; native/CMakeLists.txt
#            builds BRAssetSend.c and BRCoinReservation.c; BRAssetSend.c draws no random number and reads no
#            clock; PORTABILITY: BRAssetSend.h and BRAssetSend.c build clean under -Wall -Wextra -Wpedantic
#            -Werror at c99/c11/c17 (the header in two units, twice in one), for iphoneos/iphonesimulator and
#            through Swift's C importer where an Xcode SDK / swiftc is present (skipped, and said so, elsewhere).
# Leaks: the green arm runs under LeakSanitizer where it exists (Linux CI); skipped, and said so, elsewhere.
# KAT_FUZZ=<n> sets the fuzzed sends of the green arm (default 300), KAT_FUZZ_RED those of each red arm (default
# 100), KAT_FUZZ_MUTANT those of each mutant (default 40). KAT_MUTANTS=0 skips the mutant pass and KAT_ARMS=0 the
# comparison arms, for a quicker local run; the gate is the run with both.
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DBR_ASSET_TRANSFER_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
set -uo pipefail   # NOT -e: a comparison arm's non-zero exit is captured
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
CORE_DIR="$(cd "$CORE_DIR" 2>/dev/null && pwd)" || { echo "GATE FAILURE: no core directory"; exit 1; }
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

# ---- the files are real, and the seam is ------------------------------------------------------------------------
for f in BRAssetSend.h BRAssetSend.c BRAssetSendPlan.h BRSpendGate.h BRCoinReservation.c; do
    [ -f "$CORE_DIR/$f" ] || gate_fail "$CORE_DIR has no $f (core pin too old? set CORE_DIR)"
done
grep -q "^NativeBridge_buildAndSignAssetTransferTx(" "$BRIDGE_DIR/jni_asset_send.c" ||
    gate_fail "jni_asset_send.c has no NativeBridge_buildAndSignAssetTransferTx"
grep -q "seed_sign_transaction(g_wallet, tx, 0)" "$BRIDGE_DIR/jni_asset_send.c" ||
    gate_fail "jni_asset_send.c no longer signs with seed_sign_transaction(g_wallet, tx, 0)"
SRC="$(cat "$CORE_DIR/BRAssetSend.c")"
grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+BR_ASSET_TRANSFER_UNFIXED" <<<"$SRC" ||
    gate_fail "BRAssetSend.c gives BR_ASSET_TRANSFER_UNFIXED no default of 0"
if grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+BR_ASSET_TRANSFER_UNFIXED" <<<"$SRC"; then
    gate_fail "BRAssetSend.c selects an arm by the macro's PRESENCE"
fi
grep -Eq "^#define _RED\(bit\) \(BR_ASSET_TRANSFER_UNFIXED & \(bit\)\)" <<<"$SRC" ||
    gate_fail "BRAssetSend.c does not read the seam's value through _RED"
# No randomness and no clock in the core's unit (comments stripped).
CODE="$(sed -e 's@//.*$@@' "$CORE_DIR/BRAssetSend.c")"
if grep -Eq '(^|[^A-Za-z0-9_])(rand|random|srand|arc4random|arc4random_buf|getrandom|time|clock|clock_gettime|gettimeofday)[[:space:]]*\(' <<<"$CODE"; then
    gate_fail "BRAssetSend.c calls a random-number source or a clock"
fi
# The Kotlin caller writes the arrays this KAT writes (AssetManager.sendAsset).
SEND_KT="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt"
[ -f "$SEND_KT" ] || gate_fail "no AssetManager.kt (moved? update this gate)"
KT="$(cat "$SEND_KT")"
for line in 'val allInputs = plan.inputs' 'inputTxidsHex = allInputs.map { it.txid }.toTypedArray(),' \
            'inputVouts = allInputs.map { it.vout }.toIntArray(),' 'inputAmounts = allInputs.map { it.satoshis }.toLongArray(),' \
            'inputScriptPubKeysHex = allInputs.map { it.scriptPubKey.toHex() }.toTypedArray(),' \
            'outAddresses += toAddress' 'outScripts += plan.opReturnScript.toHex()' 'outScripts += plan.sourceScript.toHex()'; do
    grep -qF -- "$line" <<<"$KT" || gate_fail "AssetManager.sendAsset no longer writes '$line': re-derive the KAT's bridge arrays"
done
# Android compiles the new unit and the reservation it calls.
CMAKE="$REPO_ROOT/native/CMakeLists.txt"
for f in BRAssetSend.c BRCoinReservation.c; do
    grep -qE "^[[:space:]]*src/main/jni/digibytewallet-core/$f[[:space:]]*$" "$CMAKE" ||
        gate_fail "native/CMakeLists.txt does not build $f"
done

# ---- PORTABILITY -------------------------------------------------------------------------------------------------
# The header and the unit on their own terms; the other core headers are not -Wextra clean, so they come in as
# system headers and these two are checked from a copy.
mkdir -p "$BUILD_DIR/hdr"
cp "$CORE_DIR/BRAssetSend.h" "$CORE_DIR/BRAssetSend.c" "$BUILD_DIR/hdr/"
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetSend.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRTransaction *tx = NULL;
    BRAssetTransferReport r;
    return (int)BRWalletBuildAssetTransfer(&tx, NULL, NULL, NULL, 0, NULL, 0, "La", 1, "x", 1, NULL, 0, "s", 1, 0.0, &r);
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetSend.h"
#include "BRAssetSend.h"
int strict_tu1(void);
int main(void)
{
    return (strict_tu1() == BR_ASSET_TRANSFER_BAD_ARGUMENT && BR_ASSET_TRANSFER_GATE_REFUSED == 21) ? 0 : 1;
}
EOF
SYS=(-isystem "$CORE_DIR" -isystem "$CORE_DIR/secp256k1/include")
for std in c99 c11 c17; do
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" "${SYS[@]}" \
        -fsyntax-only "$BUILD_DIR/strict1.c" ||
        gate_fail "BRAssetSend.h does not compile clean under -std=$std -Wall -Wextra -Wpedantic -Werror"
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" "${SYS[@]}" \
        -fsyntax-only "$BUILD_DIR/strict2.c" ||
        gate_fail "BRAssetSend.h, included twice, does not compile clean under -std=$std"
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" "${SYS[@]}" \
        -fsyntax-only "$BUILD_DIR/hdr/BRAssetSend.c" ||
        gate_fail "BRAssetSend.c does not compile clean under -std=$std -Wall -Wextra -Wpedantic -Werror"
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        xcrun --sdk $sdk clang -arch arm64 -std=c11 -O2 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h \
            -I "$BUILD_DIR/hdr" "${SYS[@]}" -c "$BUILD_DIR/hdr/BRAssetSend.c" -o "$BUILD_DIR/ios_$sdk.o" ||
            gate_fail "BRAssetSend.c does not build for $sdk arm64"
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    mkdir -p "$BUILD_DIR/swift_inc"
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    cp -R "$CORE_DIR/secp256k1/include/." "$BUILD_DIR/swift_inc/"
    printf '#include <stdint.h>\n#include "BRAssetSend.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var tx: UnsafeMutablePointer<BRTransaction>? = nil
var report = BRAssetTransferReport()
var outcome = BRAssetSendOutcome()
let status: BRAssetTransferStatus = BRWalletBuildAssetTransfer(&tx, nil, &outcome, nil, 0, nil, 0, "La", 1, "x", Int64.max,
                                                               nil, 0, nil, 0, 0, &report)
print(status == BR_ASSET_TRANSFER_BAD_ARGUMENT, report.input, report.paidFeeSats, BR_ASSET_TRANSFER_SEED_MAX, BR_ASSET_TRANSFER_SCRIPT_MAX)
EOF
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" "$BUILD_DIR/use.swift" \
            >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        gate_fail "swiftc could not import BRAssetSend.h"
    fi
fi
echo "  [portability] clean at c99/c11/c17, header included twice ($ios_note; $swift_note)"

# ---- the build ---------------------------------------------------------------------------------------------------
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
SAN=(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g)
INC=(-I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")
LIBS=("$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c"
      "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
      "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
      "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRCoinReservation.c"
      "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
      "${SHA3_SRCS[@]}"
      "$BRIDGE_DIR/jni_wallet.c" "$BRIDGE_DIR/jni_asset_send.c" "$BRIDGE_DIR/coin_reservation.c" "$BRIDGE_DIR/slip13.c"
      "$BRIDGE_DIR/jni_transaction_persist.c")
mkdir -p "$BUILD_DIR/lib"
LIBO=()
for src in "${LIBS[@]}"; do
    o="$BUILD_DIR/lib/${#LIBO[@]}_$(basename "$src" .c).o"   # numbered: crypto/ and crypto/sha3/ share basenames
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -c "$src" -o "$o" || gate_fail "$src did not compile"
    LIBO+=("$o")
done
clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -c "$SCRIPT_DIR/asset_send_kat_main.c" -o "$BUILD_DIR/main.o" ||
    gate_fail "asset_send_kat_main.c did not compile"

# $1 out, $2 the core's source, rest: extra flags for the core's unit. The recorder sees its allocations.
build() {
    local out="$1" src="$2"; shift 2
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -Dcalloc=kat_calloc -Dmalloc=kat_malloc -Dfree=kat_free "$@" \
        -c "$src" -o "$out.core.o" || return 1
    clang "${SAN[@]}" "$BUILD_DIR/main.o" "$out.core.o" "${LIBO[@]}" -lm -lpthread -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 detect_stack_use_after_return=0"
export LSAN_OPTIONS="detect_leaks=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
FAIL=0

run_arm() {   # $1 bin, $2 fuzz -> sets OUT, RC
    OUT="$(KAT_FUZZ="$2" timeout 900 "$1" 2>&1)"; RC=$?
    [ -n "${KAT_LOG_DIR:-}" ] && printf '%s\n' "$OUT" > "$KAT_LOG_DIR/$(basename "$1").log"
}

# The scenarios the binary runs are the ones this file judges.
build "$BUILD_DIR/green" "$CORE_DIR/BRAssetSend.c" || gate_fail "the green arm did not build"
ARMS="${KAT_ARMS:-1}"   # KAT_ARMS=0 skips the comparison arms (a quicker local iteration; the gate needs them)
KNOWN="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"

# ---- GREEN -------------------------------------------------------------------------------------------------------
run_arm "$BUILD_DIR/green" "${KAT_FUZZ:-300}"
if [ "$RC" -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$OUT" && grep -q "^PASS: asset_send_kat" <<<"$OUT" \
   && ! grep -q "^SCENARIO-FAIL" <<<"$OUT"; then
    N="$(grep -c '^SCENARIO-PASS' <<<"$OUT")"
    echo "  [green] $N scenarios: the bridge and the core sign the same bytes wherever both sign; every core refusal is the status its scenario names"
    grep -E '^   fuzz:' <<<"$OUT" | sed 's/^   /      /'
else
    echo "GATE FAILURE: the green arm (rc=$RC)"; grep -E "FAIL|$SAN_RE" <<<"$OUT" | head -30; FAIL=1
fi

# red <bit> <name> <space-separated scenarios that must fail>
red() {
    [ "$ARMS" = "0" ] && return
    local bit="$1" name="$2" want
    want="$(tr ' ' '\n' <<<"$3" | sed '/^$/d' | sort)"
    for s in $want; do
        case " $KNOWN " in *" $s "*) ;; *) echo "GATE FAILURE: arm $bit lists $s, which the binary does not run"; FAIL=1 ;; esac
    done
    grep -Eq "^#define BR_ASSET_TRANSFER_RED_[A-Z_]+[[:space:]]+$bit[[:space:]]*$" <<<"$SRC" ||
        { echo "GATE FAILURE: BRAssetSend.c defines no seam of value $bit; arm $name would be the shipped code"; FAIL=1; return; }
    build "$BUILD_DIR/red_$bit" "$CORE_DIR/BRAssetSend.c" "-DBR_ASSET_TRANSFER_UNFIXED=$bit" ||
        { echo "GATE FAILURE: the $name arm did not build"; FAIL=1; return; }
    run_arm "$BUILD_DIR/red_$bit" "${KAT_FUZZ_RED:-100}"
    local failed
    failed="$(sed -n 's/^SCENARIO-FAIL: //p' <<<"$OUT" | sort)"
    if grep -Eq "$SAN_RE" <<<"$OUT"; then
        echo "GATE FAILURE: the $name arm faulted (a fault is not the failure it must show)"; grep -Em3 "$SAN_RE" <<<"$OUT"; FAIL=1
    elif [ "$RC" -ne 1 ] || [ "$failed" != "$want" ]; then
        echo "GATE FAILURE: the $name arm (=$bit, rc=$RC) failed [$(echo $failed)], required exactly [$(echo $want)]"; FAIL=1
    else
        echo "  [red $bit $name] failed exactly: $(echo $failed)"
    fi
}

red 1     shape          "shape_outputs fuzz"
red 2     asset          "binding_asset binding_quantity_bounds"
red 4     bounds         "bounds_amounts"
red 8     carrier        "carrier_list carrier_noncanonical carrier_not_exact carrier_not_transfer fuzz"
red 16    carrier-bytes  "carrier_noncanonical carrier_not_exact"
red 32    units          "binding_quantity carrier_units fuzz"
red 64    fee            "fee_mismatch fuzz"
red 128   approval       "binding_fee_approval keys_wiped fuzz"
red 256   conflicted     "conflicted_stuck_change"
red 512   unknown        "conflicted_undetected"
red 1024  record         "record_mismatch"
red 2048  held           "held_fee_coin fuzz"
red 4096  not-plain      "fee_coin_not_plain"
red 8192  prespent       "conflicted_spent_funding spent_before_sign"
red 16384 gate           "gate_claims"
red 32768 duplicate      "duplicate_input"

# ---- MUTANTS -----------------------------------------------------------------------------------------------------
# Each `if (` of BRAssetSend.c in turn is made never to fire: its whole condition C becomes `0 && (C)`. A line the
# shipped arm does not compile (a comparison arm's branch) changes nothing after the preprocessor and is passed
# over. Every other mutant must fail the green arm, or be listed here:
# <line text, trimmed>@@<why the KAT cannot see it>.
SURVIVORS=(
    "if (tx) BRTransactionFree(tx);@@a leak only: LeakSanitizer is its only witness, and the mutant pass runs without it"
    "if (funding) BRTransactionFree(funding);@@a leak only: LeakSanitizer is its only witness, and the mutant pass runs without it"
    "if (! tx) { r = BR_ASSET_TRANSFER_NO_MEMORY; goto out; }@@BRTransactionNew allocates in BRTransaction.c, where the recorder cannot fail it"
    "if (h->operation != DA_TRANSFER || h->version != 3 || BRAssetHeaderHasRules(h)) return 0;@@in the shipped arm check 12's byte comparison refuses every such carrier too (the plan's encoding is a version-3 transfer); this is the refusal where the bytes are not compared (arm 16 passes carrier_not_transfer because of it)"
    "if (! BRAssetPayloadReadsExactly(plan->opReturnScript, plan->opReturnLen, h, insts, count)) return 0;@@in the shipped arm equal bytes read as the same list; the reading decides where the bytes are not compared (arm 16)"
    "if (! BRSpendGuardInputsUnspent(wallet, tx)) { r = BR_ASSET_TRANSFER_INPUT_SPENT; goto out; }@@it closes a race (a send registered after check 15); single-threaded, check 15 refuses the same coins first"
)
mutate() {   # <line> <in> <out>: the `if (` condition on <line> wrapped as `0 && (...)`
    python3 - "$1" "$2" "$3" <<'PY'
import sys
line, src, dst = int(sys.argv[1]), sys.argv[2], sys.argv[3]
text = open(src).read()
lines = text.split("\n")
off = sum(len(l) + 1 for l in lines[:line - 1])
at = text.index("if (", off) + len("if (")
depth, i = 1, at
while depth:
    c = text[i]
    depth += (c == "(") - (c == ")")
    i += 1
open(dst, "w").write(text[:at] + "0 && (" + text[at:i - 1] + ")" + text[i - 1:])
PY
}
mutant_note="mutants skipped (KAT_MUTANTS=0)"
if [ "${KAT_MUTANTS:-1}" != "0" ]; then
    LINES=()
    while IFS= read -r n; do LINES+=("$n"); done < <(grep -nE '^[[:space:]]*(\}[[:space:]]*else[[:space:]]+)?if \(' "$CORE_DIR/BRAssetSend.c" | cut -d: -f1)
    for sv in "${SURVIVORS[@]}"; do
        text="${sv%%@@*}"
        c="$(grep -cF -- "$text" "$CORE_DIR/BRAssetSend.c")"
        [ "$c" -ge 1 ] || { echo "GATE FAILURE: survivor '$text' is no line of BRAssetSend.c"; FAIL=1; }
    done
    PP=(-w -include stdint.h -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -E -P)
    clang "${PP[@]}" "$CORE_DIR/BRAssetSend.c" -o "$BUILD_DIR/orig.i" || gate_fail "BRAssetSend.c does not preprocess"
    killed=0; survived=0; listed=0; inactive=0
    for n in "${LINES[@]}"; do
        text="$(sed -n "${n}p" "$CORE_DIR/BRAssetSend.c" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
        m="$BUILD_DIR/mutant.c"
        mutate "$n" "$CORE_DIR/BRAssetSend.c" "$m" || { echo "GATE FAILURE: line $n could not be mutated"; FAIL=1; continue; }
        if cmp -s "$m" "$CORE_DIR/BRAssetSend.c"; then echo "GATE FAILURE: mutant of line $n changed nothing"; FAIL=1; continue; fi
        clang "${PP[@]}" "$m" -o "$BUILD_DIR/mutant.i" || { echo "GATE FAILURE: the mutant of line $n does not preprocess"; FAIL=1; continue; }
        if cmp -s "$BUILD_DIR/mutant.i" "$BUILD_DIR/orig.i"; then inactive=$((inactive + 1)); continue; fi
        build "$BUILD_DIR/mutant" "$m" || { echo "GATE FAILURE: the mutant of line $n did not build"; FAIL=1; continue; }
        OUT="$(KAT_FUZZ="${KAT_FUZZ_MUTANT:-40}" timeout 900 "$BUILD_DIR/mutant" 2>&1)"; RC=$?
        is_listed=0
        for sv in "${SURVIVORS[@]}"; do [ "${sv%%@@*}" = "$text" ] && is_listed=1; done
        if [ "$RC" -ne 0 ]; then
            killed=$((killed + 1))
            [ $is_listed -eq 1 ] && { echo "GATE FAILURE: listed survivor (line $n) is now killed; take it off the list: $text"; FAIL=1; }
        elif [ $is_listed -eq 1 ]; then
            listed=$((listed + 1))
        else
            survived=$((survived + 1)); echo "GATE FAILURE: mutant survived, line $n: $text"; FAIL=1
        fi
    done
    mutant_note="$((${#LINES[@]} - inactive)) one-line mutants of the shipped arm: $killed killed, $listed survivors listed with their reason, $survived unexplained ($inactive lines only a comparison arm compiles)"
    echo "  [mutants] $mutant_note"
fi

# ---- LEAKS (where LeakSanitizer exists: Linux CI; not on macOS) --------------------------------------------------
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/probe.c"
leak_note="skipped: LeakSanitizer is not available here (the green leak pass runs on Linux)"
if clang -fsanitize=address "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe" 2>/dev/null &&
   ( ASAN_OPTIONS="detect_leaks=1" LSAN_OPTIONS="detect_leaks=1" "$BUILD_DIR/probe" ) >/dev/null 2>&1; then
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=1 detect_stack_use_after_return=0" LSAN_OPTIONS="detect_leaks=1" \
           KAT_FUZZ=60 timeout 900 "$BUILD_DIR/green" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$OUT"; then
        leak_note="the green arm is leak-clean"
    else
        echo "GATE FAILURE: the green arm under the leak checker (rc=$RC)"; grep -E -A8 "$SAN_RE" <<<"$OUT" | head -24; FAIL=1
        leak_note="FAILED"
    fi
fi
echo "  [leaks] $leak_note"

if [ "$FAIL" -eq 0 ]; then
    arms_note="sixteen comparison arms red on exactly theirs"
    [ "$ARMS" = "0" ] && arms_note="comparison arms skipped (KAT_ARMS=0)"
    echo "PASS: asset_send_kat (bridge and core sign the same bytes wherever both sign; $arms_note; $mutant_note; ASan+UBSan; portability; leaks: $leak_note)"
    exit 0
fi
echo "FAIL: asset_send_kat"
exit 1
