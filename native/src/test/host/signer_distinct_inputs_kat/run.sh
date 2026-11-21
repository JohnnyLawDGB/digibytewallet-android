#!/usr/bin/env bash
# Host KAT runner: every native signer that takes its inputs from the caller checks, before it signs,
# that every input names a different outpoint; a transaction with a repeated input is refused, signs
# nothing and leaves no coin claimed (FOREIGN-ASSET-DISTINCT). See signer_distinct_inputs_kat_main.c.
#
# The signers are the shipping ones: the bridge's jni_derive.c, jni_transaction.c and jni_wallet.c are
# compiled over identity_sign_bytes_kat's host JNI stand-ins, with AddressSanitizer and
# UndefinedBehaviorSanitizer (64-bit), and every case calls the native itself.
#
#   SHIPPED     the bridge as it is: every case passes, with no sanitizer report.
#   COMPARISON  one arm per signer, built from a copy of the bridge in which that signer's
#               tx_inputs_distinct check never fires (its condition C becomes `0 && C`: the signer as it
#               was before this row). The copy differs from the shipping file in exactly that one line.
#               Each arm must fail exactly its signer's *_repeated_input_refused case, by a failed
#               check and no sanitizer report, and pass every other case.
#   VECTORS     the valid transactions' signed bytes (VECTOR lines): the same in the shipped arm and
#               in every comparison arm, and their digests are pinned in the main.
#   SOURCE GATE over every .c file of the bridge: a function that reaches a signing call
#               (BRTransactionSign, BRWalletSignTransaction, seed_sign_transaction) asks
#               tx_inputs_distinct(tx) -- itself, or through dd_transfer_check -- before its first
#               signing call, after its last input is added, and refuses on the answer. Two functions
#               are named as exempt, each held to the reason it is: seed_sign_transaction (the wallet's
#               signing call itself; its callers are the signers) and sendApprovedDigiDollar (it lists
#               no input: the core builder selects them, and it neither adds an input nor parses
#               bytes). A signer added later is held to the same rule without an edit here.
#
# SIGNER_DISTINCT_BRIDGE_DIR=<dir> points the shipped arm and the source gate at another copy of the
# bridge (used to show both red on the bridge before this row).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arms' nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early: here-strings and files only.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="${SIGNER_DISTINCT_BRIDGE_DIR:-$REPO_ROOT/native/src/main/jni/bridge}"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
export LSAN_OPTIONS="detect_leaks=0"   # the recovered wallet lives until exit
export UBSAN_OPTIONS="halt_on_error=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=120
N_VECTORS=12
CHECK='!tx_inputs_distinct(tx)'

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

# <case>:<the comparison arm that must fail it, or - for a guard>
CASES=(
    valid_vectors_sign_unchanged:-
    sweep_repeated_input_refused:sweep
    foreign_asset_repeated_input_refused:foreign_asset
    sign_transaction_repeated_input_refused:sign_transaction
    sign_prepared_repeated_input_refused:sign_prepared
)
# <arm>:<file>:<the signer whose check never fires there>
ARMS=(
    sweep:jni_derive.c:NativeBridge_buildAndSignLegacySweep
    foreign_asset:jni_derive.c:NativeBridge_buildAndSignForeignAssetTransfer
    sign_transaction:jni_transaction.c:NativeBridge_signTransaction
    sign_prepared:jni_transaction.c:NativeBridge_signPreparedTransaction
)
UNITS=(jni_derive.c jni_wallet.c jni_transaction.c coin_reservation.c slip13.c jni_transaction_persist.c)

[ -d "$BRIDGE_DIR" ] || gate_fail "$BRIDGE_DIR not found"
for u in "${UNITS[@]}" asset_tx_checks.h; do [ -f "$BRIDGE_DIR/$u" ] || gate_fail "$BRIDGE_DIR has no $u"; done

# ---- the build ---------------------------------------------------------------------------------
shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
SAN=(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g)
LIBS=("$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c"
      "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
      "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c"
      "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRCoinReservation.c"
      "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
      "${SHA3[@]}")
mkdir -p "$BUILD_DIR/lib"
LIBO=()
for src in "${LIBS[@]}"; do
    o="$BUILD_DIR/lib/${#LIBO[@]}_$(basename "$src" .c).o"   # numbered: crypto/ and crypto/sha3/ share basenames
    clang -w -include stdint.h "${SAN[@]}" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -c "$src" -o "$o" || gate_fail "$src did not compile"
    LIBO+=("$o")
done
clang -w -include stdint.h "${SAN[@]}" -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
    -c "$SCRIPT_DIR/signer_distinct_inputs_kat_main.c" -o "$BUILD_DIR/main.o" || gate_fail "signer_distinct_inputs_kat_main.c did not compile"

# build_arm <name> [<file> <function>]: the bridge copied beside the core (jni_derive.c includes
# "../digibytewallet-core/..."), with <function>'s check in <file> made never to fire when given.
build_arm() {
    local name="$1" file="${2:-}" fn="${3:-}" d="$BUILD_DIR/$1" u
    mkdir -p "$d/jni" "$d/o"
    cp -R "$BRIDGE_DIR" "$d/jni/bridge" || return 1
    ln -s "$CORE_DIR" "$d/jni/digibytewallet-core"
    if [ -n "$file" ]; then
        awk -v fn="$fn" -v check="$CHECK" '
            !done && !infn && index($0, fn "(") == 1 { infn = 1 }
            infn && !done { i = index($0, check); if (i) { $0 = substr($0, 1, i - 1) "0 && " substr($0, i); done = 1 } }
            infn && /^}/ { infn = 0 }
            { print }' "$BRIDGE_DIR/$file" > "$d/jni/bridge/$file" || return 1
        local changed
        changed="$(diff "$BRIDGE_DIR/$file" "$d/jni/bridge/$file" | grep -c '^>')"
        if [ "$changed" -ne 1 ]; then
            echo "GATE FAILURE: arm $name: $fn in $file has no '$CHECK' to switch off ($changed lines changed) -- the arm would be the shipped code"
            return 1
        fi
    fi
    for u in "${UNITS[@]}"; do
        [ "$u" = "$file" ] && continue
        cmp -s "$BRIDGE_DIR/$u" "$d/jni/bridge/$u" || { echo "GATE FAILURE: arm $name: the copied $u is not the shipping file"; return 1; }
    done
    local inc=(-I "$HOST_JNI" -I "$d/jni/bridge" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")
    local objs=()
    for u in "${UNITS[@]}"; do
        local extra=()
        # The two signing calls of the bridge are counted in the main on their way to the core.
        [ "$u" = jni_derive.c ] && extra=(-include stdio.h -DBRTransactionSign=kat_tx_sign)
        [ "$u" = jni_wallet.c ] && extra=(-DBRWalletSignTransaction=kat_wallet_sign)
        clang -w -include stdint.h "${SAN[@]}" "${inc[@]}" "${extra[@]}" -c "$d/jni/bridge/$u" -o "$d/o/${u%.c}.o" || return 1
        objs+=("$d/o/${u%.c}.o")
    done
    clang "${SAN[@]}" "$BUILD_DIR/main.o" "${objs[@]}" "${LIBO[@]}" -lm -lpthread -o "$d/kat"
}
# The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was stopped
# (the race fixed in base58_input_bound_kat, #211).
bounded() {   # <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit
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

FAIL=0

# ---- SHIPPED -----------------------------------------------------------------------------------
echo "=== shipped arm: $BRIDGE_DIR ==="
build_arm shipped || gate_fail "the shipped arm did not build"
known="$("$BUILD_DIR/shipped/kat" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
[ "$known" = "$listed" ] || gate_fail "case lists disagree ($known / $listed)"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"; out="$BUILD_DIR/shipped.$c.out"
    bounded "$out" "$BUILD_DIR/shipped/kat" "$c"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q "^RESULT $c pass$" "$out"; then
        echo "  [shipped $c] passed ($(grep -c '^PASS:' "$out") checks)"
    else
        echo "  [shipped $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" "$out" >"$out.fails"; show "$out.fails" 14; FAIL=1
    fi
done
grep '^VECTOR ' "$BUILD_DIR/shipped.valid_vectors_sign_unchanged.out" >"$BUILD_DIR/vectors.shipped" 2>/dev/null
nvec="$(wc -l <"$BUILD_DIR/vectors.shipped" | tr -d ' ')"
if [ "$nvec" -eq "$N_VECTORS" ]; then echo "  [vectors] $nvec valid transactions signed"
else echo "  [vectors] GATE FAILURE: expected $N_VECTORS valid transactions, got $nvec"; FAIL=1; fi

# ---- COMPARISON ARMS ---------------------------------------------------------------------------
for arm in "${ARMS[@]}"; do
    IFS=: read -r name file fn <<<"$arm"
    echo "=== comparison arm $name: $fn's check never fires ==="
    if ! build_arm "$name" "$file" "$fn"; then echo "GATE FAILURE: comparison arm $name did not build."; FAIL=1; continue; fi
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; red="${entry##*:}"; out="$BUILD_DIR/$name.$c.out"
        bounded "$out" "$BUILD_DIR/$name/kat" "$c"; rc=$?
        if [ $rc -eq 124 ]; then echo "  [$name $c] GATE FAILURE: did not return within ${LIMIT_S}s"; FAIL=1; continue; fi
        if grep -Eq "$SAN_RE" "$out"; then echo "  [$name $c] GATE FAILURE: sanitizer report (rc=$rc)"; show "$out"; FAIL=1; continue; fi
        if [ "$red" = "$name" ]; then
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" "$out"; then
                grep '^FAIL:' "$out" >"$out.fails"
                echo "  [$name $c] failed, as it must ($(wc -l <"$out.fails" | tr -d ' ') checks): $(sed -n 1p "$out.fails" | cut -c1-160)"
            else
                echo "  [$name $c] GATE FAILURE: the comparison arm did not fail this case (rc=$rc) -- it cannot see the defect"; show "$out"; FAIL=1
            fi
        else
            if [ $rc -eq 0 ] && grep -q "^RESULT $c pass$" "$out"; then
                echo "  [$name $c] passed (another signer's case, or the guard)"
            else
                echo "  [$name $c] GATE FAILURE: a case of another signer failed in this arm (rc=$rc)"; show "$out" 12; FAIL=1
            fi
        fi
    done
    grep '^VECTOR ' "$BUILD_DIR/$name.valid_vectors_sign_unchanged.out" >"$BUILD_DIR/vectors.$name" 2>/dev/null
    if [ -s "$BUILD_DIR/vectors.$name" ] && cmp -s "$BUILD_DIR/vectors.shipped" "$BUILD_DIR/vectors.$name"; then
        echo "  [vectors $name] the valid transactions' signed bytes are the shipped arm's, byte for byte"
    else
        echo "  [vectors $name] GATE FAILURE: the valid transactions' signed bytes differ from the shipped arm's"; FAIL=1
    fi
done

# ---- SOURCE GATE over the real bridge ----------------------------------------------------------
echo "=== source gate: $BRIDGE_DIR/*.c ==="
gate_bad=0
say() { echo "  gate: $*"; gate_bad=1; }
SIGN_RE='(BRTransactionSign|BRWalletSignTransaction|seed_sign_transaction) ?\('
# <file> with comments removed, one line per top-level function: "<name>\t<its text on one line>".
functions_of() {
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }' | awk '
        /^[A-Za-z_][A-Za-z0-9_ \t*]*\(/ && $0 !~ /;[ \t]*$/ {
            s = $0; sub(/\(.*/, "", s); n = split(s, parts, /[ \t*]+/); cur = parts[n]; body = "" }
        { if (cur != "") body = body " " $0 }
        /^}/ { if (cur != "") { gsub(/[ \t]+/, " ", body); print cur "\t" body } cur = ""; body = "" }'
}
at() { awk -v s="$1" -v t="$2" 'BEGIN { print index(s, t) }'; }                 # first position of t in s, 0 if none
last_at() { awk -v s="$1" -v t="$2" 'BEGIN { n = 0; off = 0; while ((i = index(s, t)) > 0) { n = off + i; off += i; s = substr(s, i + 1) } print n }'; }
first_sign_at() { awk -v s="$1" 'BEGIN { print match(s, /(BRTransactionSign|BRWalletSignTransaction|seed_sign_transaction) ?\(/) }'; }

FOUND=""
for f in "$BRIDGE_DIR"/*.c; do
    base="$(basename "$f")"
    fns="$(functions_of "$f")"
    while IFS=$'\t' read -r name body; do
        [ -n "$name" ] || continue
        grep -Eq "$SIGN_RE" <<<"$body" || continue
        # The function's own header is not a call of itself.
        rest="${body#*"$name"}"
        if [ "$name" = seed_sign_transaction ]; then
            FOUND="$FOUND $name"
            [ "$base" = jni_wallet.c ] || say "$base defines seed_sign_transaction: the wallet's signing call lives in jni_wallet.c"
            continue
        fi
        grep -Eq "$SIGN_RE" <<<"$rest" || continue
        FOUND="$FOUND $name"
        sign_at="$(first_sign_at "$rest")"
        if [ "$name" = NativeBridge_sendApprovedDigiDollar ]; then
            # It lists no input: the transfer is the core builder's, whose selection cannot repeat a coin.
            build_at="$(at "$rest" 'BRWalletCreateDigiDollarTransfer(')"
            { [ "$build_at" -gt 0 ] && [ "$build_at" -lt "$sign_at" ]; } || say "$name does not sign the core builder's transfer (BRWalletCreateDigiDollarTransfer before the signature)"
            grep -Eq 'BRTransactionAddInput ?\(|BRTransactionParse(Signed)? ?\(' <<<"$rest" && say "$name adds an input or parses bytes: its inputs are then the caller's, and it must ask tx_inputs_distinct(tx) before it signs"
            grep -Eq 'jobjectArray|jbyteArray|jlongArray' <<<"${body%%)*}" && say "$name takes an array from the caller: if it lists inputs it must ask tx_inputs_distinct(tx) before it signs"
            continue
        fi
        own_at="$(at "$rest" 'tx_inputs_distinct(tx)')"
        via_at="$(at "$rest" 'dd_transfer_check(tx,')"
        if [ "$own_at" -gt 0 ]; then
            check_at="$own_at"
            grep -Eq 'if ?\( ?(ok ?&& ?)?! ?tx_inputs_distinct ?\( ?tx ?\) ?\) ?\{[^}]*(ok = 0;|return NULL;)' <<<"$rest" ||
                say "$name ($base) asks tx_inputs_distinct(tx) but does not refuse on the answer (if (!tx_inputs_distinct(tx)) { ... ok = 0; | return NULL; })"
        elif [ "$via_at" -gt 0 ]; then
            check_at="$via_at"
            grep -Eq 'if ?\( ?! ?tx_inputs_distinct ?\( ?tx ?\) ?\) ?return DD_TRANSFER_DUPLICATE_INPUT;' "$BRIDGE_DIR/digidollar_transfer_guard.h" ||
                say "$name ($base) relies on dd_transfer_check, which no longer refuses a repeated input (digidollar_transfer_guard.h)"
        else
            say "$name ($base) reaches a signing call and never asks tx_inputs_distinct(tx): a signer checks that every input names a different outpoint before it signs"
            continue
        fi
        [ "$check_at" -lt "$sign_at" ] || say "$name ($base): the signing call comes before the distinct-input check"
        add_at="$(last_at "$rest" 'BRTransactionAddInput')"
        [ "$add_at" -lt "$check_at" ] || say "$name ($base): an input is added after the distinct-input check, which would not see it"
        between="${rest:$((check_at - 1)):$((sign_at - check_at))}"
        if ! grep -q 'return NULL;' <<<"$between" && ! grep -Eq 'if ?\( ?ok ?\) ?signOk = (BRTransactionSign|seed_sign_transaction) ?\(' <<<"$rest"; then
            say "$name ($base): nothing between the distinct-input check and the signing call stops a refused transaction"
        fi
    done <<<"$fns"
done
# Not blind: the signers this row is about, and the two exempt functions, were all found.
for must in NativeBridge_buildAndSignLegacySweep NativeBridge_buildAndSignForeignAssetTransfer \
            NativeBridge_buildAndSignForeignDigiDollarTransfer NativeBridge_buildAndSignAssetTransferTx \
            NativeBridge_signTransaction NativeBridge_signPreparedTransaction \
            NativeBridge_sendApprovedDigiDollar seed_sign_transaction; do
    case " $FOUND " in *" $must "*) ;; *) say "the gate did not find $must among the functions that reach a signing call -- blind, not clean" ;; esac
done
# The wallet's signing call is reached only through seed_sign_transaction.
for f in "$BRIDGE_DIR"/*.c; do
    while IFS=$'\t' read -r name body; do
        [ -n "$name" ] && [ "$name" != seed_sign_transaction ] || continue
        grep -Eq 'BRWalletSignTransaction ?\(' <<<"$body" && say "$name ($(basename "$f")) calls BRWalletSignTransaction itself: the wallet signs only through seed_sign_transaction"
    done <<<"$(functions_of "$f")"
done
if [ "$gate_bad" -eq 0 ]; then
    echo "  gate: $(wc -w <<<"$FOUND" | tr -d ' ') functions reach a signing call:$FOUND"
    echo "  gate: each asks tx_inputs_distinct(tx) (itself or through dd_transfer_check) after its last input and before it signs, and refuses on the answer; seed_sign_transaction and sendApprovedDigiDollar are exempt for the reasons checked"
else
    echo "SOURCE GATE FAILED"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: signer_distinct_inputs_kat (${#CASES[@]} cases on the shipping signers; ${#ARMS[@]} comparison arms each red on exactly its signer; $N_VECTORS valid vectors byte-identical in every arm; ASan+UBSan; source gate over the bridge)"
    exit 0
fi
echo "FAIL: signer_distinct_inputs_kat"
exit 1
