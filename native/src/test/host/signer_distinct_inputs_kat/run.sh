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
#   SOURCE GATE over every .c and .h file of the bridge: a function that reaches a signing call
#               (BRTransactionSign, BRWalletSignTransaction, seed_sign_transaction) asks
#               tx_inputs_distinct of THE TRANSACTION IT SIGNS -- itself, or through
#               dd_transfer_check -- before that signing call, after its last input is added, with
#               no bytes parsed and no assignment of that transaction in between, and refuses on the
#               answer. A function of the core that signs what it builds counts as a signing call
#               too: those are derived from the core's own .c and .h files (whatever reaches
#               BRTransactionSign or BRWalletSignTransaction), not listed here, and a bridge function
#               may sign through one only if that builder names a DUPLICATE_INPUT refusal before it
#               signs. Two functions are named as exempt, each held to the reason it is:
#               seed_sign_transaction (the wallet's signing call itself; its callers are the signers)
#               and sendApprovedDigiDollar (it lists no input: the core builder selects them, and it
#               neither adds an input nor parses bytes).
#   PLANTS      the gate is then run on copies of the bridge, each with one function added that
#               breaks the rule in one way (in a .c file, in a header, through a core builder, by
#               asking about another transaction, by parsing or assigning after the check): it must
#               report that function with that breach and nothing else, and pass a planted signer
#               that keeps the rule. That is what shows a signer added later is held to the rule.
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

# ---- SOURCE GATE -------------------------------------------------------------------------------
PRIMS=(BRTransactionSign BRWalletSignTransaction seed_sign_transaction)
# <file> with comments removed, one line per top-level function: "<name>\t<its text on one line>".
# A declaration (a ';' before any '{') is not a function, however many lines it runs to.
functions_of() {
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }' | awk '
        /^[A-Za-z_][A-Za-z0-9_ \t*]*\(/ && $0 !~ /;[ \t]*$/ {
            s = $0; sub(/\(.*/, "", s); n = split(s, parts, /[ \t*]+/); cur = parts[n]; body = ""; opened = 0 }
        cur != "" && !opened { if ($0 ~ /\{/) opened = 1; else if ($0 ~ /;[ \t]*$/) { cur = ""; body = "" } }
        { if (cur != "") body = body " " $0 }
        /^}/ { if (cur != "" && opened) { gsub(/[ \t]+/, " ", body); print cur "\t" body } cur = ""; body = "" }'
}
# core_signers <file...>: the functions of those files that reach a signing call of the core
# (BRTransactionSign or BRWalletSignTransaction, themselves or through one another), one per line:
# "<name>\tself" when its text names a DUPLICATE_INPUT verdict before its first signing call (it
# refuses a repeated outpoint itself), "<name>\tcaller" when it does not. Derived, not listed: a
# builder added to the core later is seen without an edit here.
core_signers() {
    local f
    for f in "$@"; do functions_of "$f"; done | awk -F'\t' '
        { body[$1] = body[$1] " " $2 }
        END {
            S["BRTransactionSign"] = 1; S["BRWalletSignTransaction"] = 1
            do { grew = 0
                for (f in body) { if (f in S) continue
                    hit = 0
                    for (s in S) if (body[f] ~ ("(^|[^A-Za-z0-9_])" s " ?\\(")) { hit = 1; break }
                    if (hit) { S[f] = 1; grew = 1 } }
            } while (grew)
            alt = ""; for (s in S) alt = alt (alt == "" ? "" : "|") s
            for (f in S) { if (f == "BRTransactionSign" || f == "BRWalletSignTransaction") continue
                t = body[f]; sub(/^[^(]*\(/, "", t)     # past its own header name
                p = match(t, /[A-Z][A-Z0-9_]*DUPLICATE_INPUT/); q = match(t, "(^|[^A-Za-z0-9_])(" alt ") ?\\(")
                print f "\t" ((p > 0 && p < q) ? "self" : "caller") } }' | sort
}
# Positions are counted the same way by awk and by the shell (bytes), and a text is handed to awk
# through the environment so that a backslash in it stays a backslash.
at() { LC_ALL=C S="$1" T="$2" awk 'BEGIN { print index(ENVIRON["S"], ENVIRON["T"]) }'; }                 # first position of T in S, 0 if none
last_re_at() { LC_ALL=C S="$1" awk -v re="$2" 'BEGIN { s = ENVIRON["S"]; n = 0; off = 0
    while (match(s, re)) { n = off + RSTART; off += RSTART; s = substr(s, RSTART + 1) } print n }'; }
# sign_calls <text> <names, |-separated>: every call of one of the names in the text, in order, as
# "<position>\t<name>\t<the transaction it is handed>" (the first argument of BRTransactionSign, the
# second of the two wallet calls; empty for a core builder, which is handed a list).
sign_calls() { LC_ALL=C S="$1" awk -v alt="$2" 'BEGIN { s = ENVIRON["S"]; re = "(" alt ") ?\\("; off = 0
    while (match(s, re)) {
        pos = off + RSTART; name = substr(s, RSTART, RLENGTH); sub(/ ?\($/, "", name)
        prev = (RSTART > 1) ? substr(s, RSTART - 1, 1) : " "
        s = substr(s, RSTART + RLENGTH); off = pos + RLENGTH - 1
        if (prev ~ /[A-Za-z0-9_]/) continue
        which = (name == "BRTransactionSign") ? 1 : ((name == "BRWalletSignTransaction" || name == "seed_sign_transaction") ? 2 : 0)
        id = ""
        if (which) { split(substr(s, 1, 400), a, /[,)]/); id = a[which]; gsub(/[ \t]/, "", id) }
        print pos "\t" name "\t" id } }'; }

# source_gate <bridge dir> <core signers file>: holds every function of <bridge dir>/*.[ch] that
# reaches a signing call to the rule. Prints one "  gate: ..." line per breach, leaves the functions
# it found in FOUND, and returns 1 if there was a breach.
source_gate() {
    local dir="$1" core="$2" bad=0 f base name body rest calls alt
    local LC_ALL=C
    say() { echo "  gate: $*"; bad=1; }
    alt="$(printf '%s\n' "${PRIMS[@]}" $(cut -f1 "$core") | paste -sd'|')"
    FOUND=""
    for f in "$dir"/*.[ch]; do
        base="$(basename "$f")"
        while IFS=$'\t' read -r name body; do
            [ -n "$name" ] || continue
            # The function's own header is not a call of itself.
            rest="${body#*"$name"}"
            if [ "$name" = seed_sign_transaction ]; then
                FOUND="$FOUND $name"
                [ "$base" = jni_wallet.c ] || say "$base defines seed_sign_transaction: the wallet's signing call lives in jni_wallet.c"
                continue
            fi
            calls="$(sign_calls "$rest" "$alt")"
            [ -n "$calls" ] || continue
            FOUND="$FOUND $name"
            grep -Eq '(^|[^A-Za-z0-9_])BRWalletSignTransaction ?\(' <<<"$rest" &&
                say "$name ($base) calls BRWalletSignTransaction itself: the wallet signs only through seed_sign_transaction"
            local sign_at callee id first_at
            IFS=$'\t' read -r first_at callee id <<<"$calls"
            if [ "$name" = NativeBridge_sendApprovedDigiDollar ]; then
                # It lists no input: the transfer is the core builder's, whose selection cannot repeat a coin.
                local build_at; build_at="$(at "$rest" 'BRWalletCreateDigiDollarTransfer(')"
                { [ "$build_at" -gt 0 ] && [ "$build_at" -lt "$first_at" ]; } || say "$name does not sign the core builder's transfer (BRWalletCreateDigiDollarTransfer before the signature)"
                grep -Eq 'BRTransactionAddInput ?\(|BRTransactionParse(Signed)? ?\(' <<<"$rest" && say "$name adds an input or parses bytes: its inputs are then the caller's, and it must ask tx_inputs_distinct of them before it signs"
                grep -Eq 'jobjectArray|jbyteArray|jlongArray' <<<"${body%%)*}" && say "$name takes an array from the caller: if it lists inputs it must ask tx_inputs_distinct of them before it signs"
                continue
            fi
            local seen=" " own_at via_at check_at add_at between
            while IFS=$'\t' read -r sign_at callee id; do
                case " ${PRIMS[*]} " in
                    *" $callee "*) ;;
                    *)  # A builder of the core that signs what it builds: the bridge has no transaction to ask before it.
                        grep -q "^$callee"$'\t'"self$" "$core" ||
                            say "$name ($base) signs through the core's $callee, which is handed a list and names no DUPLICATE_INPUT refusal before it signs: the inputs' outpoints must be shown different before anything is signed, here or there"
                        continue ;;
                esac
                case "$seen" in *" $id "*) continue ;; esac
                seen="$seen$id "
                if ! [[ "$id" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
                    say "$name ($base) hands $callee '$id': the gate cannot tell which transaction is signed"
                    continue
                fi
                own_at="$(at "$rest" "tx_inputs_distinct($id)")"
                via_at="$(at "$rest" "dd_transfer_check($id,")"
                if [ "$own_at" -gt 0 ]; then
                    check_at="$own_at"
                    grep -Eq "if ?\\( ?(ok ?&& ?)?! ?tx_inputs_distinct ?\\( ?$id ?\\) ?\\) ?\\{[^}]*(ok = 0;|return NULL;)" <<<"$rest" ||
                        say "$name ($base) asks tx_inputs_distinct($id) but does not refuse on the answer (if (!tx_inputs_distinct($id)) { ... ok = 0; | return NULL; })"
                elif [ "$via_at" -gt 0 ]; then
                    check_at="$via_at"
                    grep -Eq 'if ?\( ?! ?tx_inputs_distinct ?\( ?tx ?\) ?\) ?return DD_TRANSFER_DUPLICATE_INPUT;' "$dir/digidollar_transfer_guard.h" ||
                        say "$name ($base) relies on dd_transfer_check, which no longer refuses a repeated input (digidollar_transfer_guard.h)"
                else
                    say "$name ($base) signs $id and never asks tx_inputs_distinct($id): a signer checks that every input of the transaction it signs names a different outpoint before it signs"
                    continue
                fi
                [ "$check_at" -lt "$sign_at" ] || { say "$name ($base): the signing call comes before the distinct-input check"; continue; }
                add_at="$(last_re_at "$rest" 'BRTransactionAddInput')"
                [ "$add_at" -lt "$check_at" ] || say "$name ($base): an input is added after the distinct-input check, which would not see it"
                between="${rest:$((check_at - 1)):$((sign_at - check_at))}"
                if grep -Eq 'BRTransactionParse(Signed)? ?\(' <<<"$between"; then
                    say "$name ($base): bytes are parsed after the distinct-input check, which would not see their inputs"
                elif grep -Eq "(^|[^A-Za-z0-9_.>])$id ?=[^=]" <<<"$between"; then
                    say "$name ($base): $id is assigned after the distinct-input check, which was asked of another transaction"
                fi
                if ! grep -q 'return NULL;' <<<"$between" && ! grep -Eq "if ?\\( ?ok ?\\) ?signOk = (BRTransactionSign|seed_sign_transaction) ?\\(" <<<"$rest"; then
                    say "$name ($base): nothing between the distinct-input check and the signing call stops a refused transaction"
                fi
            done <<<"$calls"
        done <<<"$(functions_of "$f")"
    done
    return $bad
}

CORE_FILES=()
for f in "$CORE_DIR"/*.[ch]; do [ "$(basename "$f")" = test.c ] || CORE_FILES+=("$f"); done
core_signers "${CORE_FILES[@]}" >"$BUILD_DIR/core_signers"

echo "=== source gate: $BRIDGE_DIR/*.[ch] ==="
gate_bad=0
source_gate "$BRIDGE_DIR" "$BUILD_DIR/core_signers" || gate_bad=1
REAL_FOUND="$FOUND"
# Not blind: the signers this row is about, and the two exempt functions, were all found, and so
# were the core's own builders that sign.
for must in NativeBridge_buildAndSignLegacySweep NativeBridge_buildAndSignForeignAssetTransfer \
            NativeBridge_buildAndSignForeignDigiDollarTransfer NativeBridge_buildAndSignAssetTransferTx \
            NativeBridge_signTransaction NativeBridge_signPreparedTransaction \
            NativeBridge_sendApprovedDigiDollar seed_sign_transaction; do
    case " $REAL_FOUND " in *" $must "*) ;; *) echo "  gate: the gate did not find $must among the functions that reach a signing call -- blind, not clean"; gate_bad=1 ;; esac
done
for must in BRWalletBuildAssetTransfer BRLegacySweepBuildAndSign; do
    grep -q "^$must"$'\t' "$BUILD_DIR/core_signers" || { echo "  gate: the gate did not find $must among the core functions that reach a signing call -- blind, not clean"; gate_bad=1; }
done
if [ "$gate_bad" -eq 0 ]; then
    echo "  gate: $(wc -w <<<"$REAL_FOUND" | tr -d ' ') functions of the bridge reach a signing call:$REAL_FOUND"
    echo "  gate: $(wc -l <"$BUILD_DIR/core_signers" | tr -d ' ') functions of the core sign what they build, and count as signing calls: $(cut -f1 "$BUILD_DIR/core_signers" | paste -sd' ')"
    echo "  gate: each signer asks tx_inputs_distinct of the transaction it signs (itself or through dd_transfer_check) after its last input and before it signs, and refuses on the answer; seed_sign_transaction and sendApprovedDigiDollar are exempt for the reasons checked"
else
    echo "SOURCE GATE FAILED"; FAIL=1
fi

# ---- THE GATE SEES: planted signers ------------------------------------------------------------
# Each plant is a copy of the bridge with one function added that breaks the rule in one way. The
# gate must name that function, with that breach, and nothing else; the last plant keeps the rule
# and must pass. A gate that passes a plant is blind to that way of adding a signer.
echo "=== source gate on planted signers ==="
PLANT_CORE="$BUILD_DIR/plant_core.h"
cat >"$PLANT_CORE" <<'PLANT'
static inline int BRPlantBuildAndSign(BRTransaction **txOut, const BRPlantInput inputs[], size_t count, BRKey keys[])
{
    BRTransaction *tx = BRTransactionNew();
    for (size_t i = 0; i < count; i++) BRTransactionAddInput(tx, inputs[i].hash, inputs[i].n, inputs[i].amount, inputs[i].script, inputs[i].scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    if (! BRTransactionSign(tx, 0, keys, count)) { BRTransactionFree(tx); return 0; }
    *txOut = tx;
    return 1;
}
static inline int BRPlantCheckedBuildAndSign(BRTransaction **txOut, const BRPlantInput inputs[], size_t count, BRKey keys[])
{
    BRTransaction *tx = BRTransactionNew();
    for (size_t i = 0; i < count; i++) {
        for (size_t j = 0; j < i; j++) if (inputs[i].n == inputs[j].n && UInt256Eq(inputs[i].hash, inputs[j].hash)) { BRTransactionFree(tx); return BR_PLANT_DUPLICATE_INPUT; }
        BRTransactionAddInput(tx, inputs[i].hash, inputs[i].n, inputs[i].amount, inputs[i].script, inputs[i].scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    }
    if (! BRTransactionSign(tx, 0, keys, count)) { BRTransactionFree(tx); return 0; }
    *txOut = tx;
    return 1;
}
PLANT
core_signers "${CORE_FILES[@]}" "$PLANT_CORE" >"$BUILD_DIR/core_signers.plant"
{ grep -q $'^BRPlantBuildAndSign\tcaller$' "$BUILD_DIR/core_signers.plant" && grep -q $'^BRPlantCheckedBuildAndSign\tself$' "$BUILD_DIR/core_signers.plant"; } ||
    { echo "  plants: GATE FAILURE: the planted core builders were not derived as one that refuses a repeat and one that does not"; FAIL=1; }

# <plant>|<file the function is added to>|<the breach the gate must report, as a fixed string; - = none>
PLANTS=(
    "unchecked_c|jni_derive.c|signs tx and never asks tx_inputs_distinct(tx)"
    "unchecked_header|wallet_spend_gate.h|signs tx and never asks tx_inputs_distinct(tx)"
    "core_builder|jni_derive.c|signs through the core's BRPlantBuildAndSign"
    "other_transaction|jni_transaction.c|signs spend and never asks tx_inputs_distinct(spend)"
    "parsed_after_check|jni_transaction.c|bytes are parsed after the distinct-input check"
    "assigned_after_check|jni_transaction.c|tx is assigned after the distinct-input check"
    "checked_core_builder|jni_derive.c|-"
)
plant_text() {
    case "$1" in
    unchecked_c) cat <<'PLANT'
JNIEXPORT jstring JNICALL
NativeBridge_plantedSigner(JNIEnv *env, jobject thiz, jobjectArray txidsHex, jintArray vouts)
{
    BRTransaction *tx = BRTransactionNew();
    for (jsize i = 0; i < n; i++) BRTransactionAddInput(tx, hash[i], (uint32_t)v[i], 0, script, scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    BRTransactionSign(tx, 0, keys, (size_t)n);
    return NULL;
}
PLANT
    ;;
    unchecked_header) cat <<'PLANT'
static inline int NativeBridge_plantedSigner(BRTransaction *tx, UInt256 hash, uint32_t n, BRKey keys[], size_t count)
{
    BRTransactionAddInput(tx, hash, n, 0, NULL, 0, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    return BRTransactionSign(tx, 0, keys, count);
}
PLANT
    ;;
    core_builder|checked_core_builder)
        local b=BRPlantBuildAndSign; [ "$1" = checked_core_builder ] && b=BRPlantCheckedBuildAndSign
        cat <<PLANT
JNIEXPORT jstring JNICALL
NativeBridge_plantedSigner(JNIEnv *env, jobject thiz, jobjectArray txidsHex, jintArray vouts)
{
    BRTransaction *tx = NULL;
    if (! $b(&tx, inputs, (size_t)n, keys)) return NULL;
    return hex_of(env, tx);
}
PLANT
    ;;
    other_transaction) cat <<'PLANT'
JNIEXPORT jbyteArray JNICALL
NativeBridge_plantedSigner(JNIEnv *env, jobject thiz, jbyteArray bytes)
{
    BRTransaction *tx = BRTransactionNew();
    BRTransaction *spend = BRTransactionParse(buf, len);
    if (!tx_inputs_distinct(tx)) { return NULL; }
    BRTransactionSign(spend, 0, keys, n);
    return NULL;
}
PLANT
    ;;
    parsed_after_check|assigned_after_check)
        local later='BRTransactionParse(buf, len)'; [ "$1" = assigned_after_check ] && later='other'
        cat <<PLANT
JNIEXPORT jbyteArray JNICALL
NativeBridge_plantedSigner(JNIEnv *env, jobject thiz, jbyteArray bytes)
{
    BRTransaction *tx = BRTransactionNew();
    if (!tx_inputs_distinct(tx)) { BRTransactionFree(tx); return NULL; }
    tx = $later;
    if (!seed_sign_transaction(g_wallet, tx, 0)) return NULL;
    return NULL;
}
PLANT
    ;;
    esac
}
for plant in "${PLANTS[@]}"; do
    IFS='|' read -r pname pfile pwant <<<"$plant"
    pd="$BUILD_DIR/plant.$pname"; pout="$BUILD_DIR/plant.$pname.out"
    cp -R "$BRIDGE_DIR" "$pd" && plant_text "$pname" >>"$pd/$pfile" || gate_fail "plant $pname could not be made"
    [ "$(diff "$BRIDGE_DIR/$pfile" "$pd/$pfile" | grep -c '^>')" -gt 3 ] || gate_fail "plant $pname added nothing to $pfile"
    source_gate "$pd" "$BUILD_DIR/core_signers.plant" >"$pout"; prc=$?
    nlines="$(wc -l <"$pout" | tr -d ' ')"
    case " $FOUND " in *" NativeBridge_plantedSigner "*) ;; *) echo "  [plant $pname] GATE FAILURE: the gate did not see the planted function at all"; FAIL=1; continue ;; esac
    if [ "$pwant" = - ]; then
        if [ $prc -eq 0 ] && [ "$nlines" -eq 0 ]; then echo "  [plant $pname] passed, as it must (the planted signer keeps the rule)"
        else echo "  [plant $pname] GATE FAILURE: a planted signer that keeps the rule was reported"; show "$pout"; FAIL=1; fi
    elif [ $prc -ne 0 ] && [ "$nlines" -eq 1 ] && grep -qF "NativeBridge_plantedSigner ($pfile)" "$pout" && grep -qF "$pwant" "$pout"; then
        echo "  [plant $pname] reported, as it must: $(cut -c1-150 "$pout")"
    else
        echo "  [plant $pname] GATE FAILURE: the gate did not report exactly this breach (rc=$prc, $nlines lines; wanted '$pwant')"; show "$pout"; FAIL=1
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: signer_distinct_inputs_kat (${#CASES[@]} cases on the shipping signers; ${#ARMS[@]} comparison arms each red on exactly its signer; $N_VECTORS valid vectors byte-identical in every arm; ASan+UBSan; source gate over the bridge's .c and .h files and the core's builders, ${#PLANTS[@]} planted signers each judged as it must be)"
    exit 0
fi
echo "FAIL: signer_distinct_inputs_kat"
exit 1
