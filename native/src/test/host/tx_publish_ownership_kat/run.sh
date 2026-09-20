#!/usr/bin/env bash
# Host KAT runner: a published transaction has exactly one owner.
#
# The rule: the wallet's record of a send and the object the peer manager broadcasts are always
# DISTINCT objects, and the publisher may release its object at any time. The send functions in
# jni_transaction.c follow it by registering an independent COPY into the wallet and handing the
# ORIGINAL to the peer manager.
#
# tx_publish_ownership_kat_main.c #include-s BOTH BRPeer.c (for the private BRPeerContext — there
# is no public setter for connect status or gotVerack) AND BRPeerManager.c (for _peerDisconnected,
# _BRPeerManagerAddTxToPublishList and the otherwise-opaque BRPeerManagerStruct). Neither is ALSO
# on the link line below — every symbol would be defined twice. One file-static name,
# _dummyThreadCleanup, is defined by both; main.c renames BRPeer.c's copy with a preprocessor
# substitution scoped to that #include. Same pattern as publish_cancel_survivor_kat.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Macro convention: PRESENCE selects the red arm (green is built with no -D at all), matching the
# sibling publish_cancel_survivor_kat. The main.c tests the flag with #ifdef.
#   * RED (-DPUBLISH_OWNERSHIP_UNFIXED): the single-object shape, which this test rules out — the
#     wallet registers the SAME object the publisher is handed. MUST be reported by
#     AddressSanitizer. The sanitizer stops a run at its first report, so each of the four arms
#     is run ON ITS OWN (argv selects one): every arm has to show it can see the shape.
#   * GREEN: the two-object shape — the wallet registers a COPY, the publisher owns the original.
#     MUST print ALL PASS.
#
# ==== SOURCE GATE ===========================================================
# The arms above only MIRROR the JNI function's shape: jni_transaction.c includes Android headers
# and cannot be compiled on the host. So this runner also scans the real sendDigiDollar and fails
# unless it copies the transaction, registers the copy, publishes the original — in that order —
# never registers anything but the copy into the wallet, does not release the copy around its
# registration, and does not name the original again once the publisher has it. The scan reads
# the function's CODE (comments dropped, literals emptied, calls counted one by one) and takes
# the two names from the function itself; section [3] states the rules.
set -uo pipefail   # deliberately NOT -e: the red arm's non-zero exit is expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
JNI="$REPO_ROOT/native/src/main/jni/bridge/jni_transaction.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# LeakSanitizer OFF: the arms deliberately do not tear the managers down (BRPeerManagerFree would
# drive teardown through synthetic socket-less peers). AddressSanitizer itself stays ON: it is
# what tells the two shapes apart, and what checks "released exactly once" in the green arm.
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_publish_ownership_kat_main.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
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

# ---------------------------------------------------------------- [1] RED ----
echo "=== red-before-green [1/3]: the single-object shape must be reported by AddressSanitizer, in every arm ==="
if ! build "$BUILD_DIR/single" -DPUBLISH_OWNERSHIP_UNFIXED; then
    echo "GATE FAILED: single-object build error"; exit 1
fi
for arm in 1 2 3 4; do
    # The brace group's own stderr is discarded so the shell's notice about how the child ended
    # stays out of the output; the child's stdout and stderr both land in the log.
    { "$BUILD_DIR/single" "$arm" > "$BUILD_DIR/red.$arm.log" 2>&1; } 2>/dev/null
    RED_EXIT=$?
    if [ "$RED_EXIT" -eq 0 ]; then
        echo "GATE FAILED: arm $arm ran the single-object shape to a clean exit — this arm cannot"
        echo "             tell the two shapes apart."
        sed 's/^/             | /' "$BUILD_DIR/red.$arm.log"
        exit 1
    fi
    # Match the sanitizer report by its stable banner, not a subtype word.
    if ! grep -Eq 'ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/red.$arm.log"; then
        echo "GATE FAILED: arm $arm exited $RED_EXIT on the single-object shape, but not on a"
        echo "             sanitizer report — something else stopped it:"
        sed 's/^/             | /' "$BUILD_DIR/red.$arm.log"
        exit 1
    fi
    echo "RED confirmed, arm $arm — the sanitizer reports the single-object shape:"
    grep -E '^-- \[|ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/red.$arm.log" \
        | head -n 2 | sed 's/^/    | /'
done

# -------------------------------------------------------------- [2] GREEN ----
echo "=== red-before-green [2/3]: the two-object shape must be CLEAN ==="
if ! build "$BUILD_DIR/two"; then
    echo "GATE FAILED: two-object build error"; exit 1
fi
"$BUILD_DIR/two" > "$BUILD_DIR/green.log" 2>&1
GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "GATE FAILED: the two-object shape failed (exit $GREEN_EXIT):"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if ! grep -q "ALL PASS" "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: the two-object shape did not print ALL PASS:"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
echo "GREEN confirmed:"
sed 's/^/    | /' "$BUILD_DIR/green.log"

# --------------------------------------------------------- [3] SOURCE GATE ----
echo "=== [3/3]: the real sendDigiDollar must give the wallet and the publisher distinct objects ==="
if [ ! -f "$JNI" ]; then
    echo "GATE FAILED: $JNI not found — the file was moved or renamed and this check would"
    echo "             otherwise silently observe nothing."
    exit 1
fi

# Everything below matches the CODE of the function, never its text: comments are dropped and
# string and character literals are emptied first, calls are counted one by one (grep -o, not
# lines), and white space is allowed wherever C allows it. Names are taken from the function
# itself — ORIG is whatever the publish call is handed, COPY is whatever BRTransactionCopy(ORIG)
# is assigned to — so the rule is checked, not one spelling of it:
#   (a) a copy of ORIG is made, into a variable that holds nothing else and has no alias;
#   (b) every wallet registration in the function registers COPY, and there is at least one;
#   (c) COPY is not released on the way to being registered, nor unconditionally after it;
#   (d) copy, then register, then publish ORIG — and every publish call is handed ORIG;
#   (e) ORIG is not named again once it has been handed to the publisher.
# (a)-(c) may live in one file-static helper that the function calls with ORIG before it
# publishes; the same checks are then applied to that helper.
# A text scan cannot follow control flow. Where it has to guess ((c), and a function that holds
# conditional compilation) it fails closed, and the message says what it looked for.
export LC_ALL=C   # offsets below are bytes

ID='[A-Za-z_][A-Za-z0-9_]*'
SP='[[:space:]]*'

# Drops /* */ and // comments; keeps the quotes of string and character literals, not their contents.
strip_c() {
    awk 'BEGIN { st = 0 }
    {
        line = $0; out = ""; n = length(line); i = 1
        while (i <= n) {
            c = substr(line, i, 1); d = substr(line, i, 2)
            if (st == 1) { if (d == "*/") { st = 0; out = out " "; i += 2 } else i++; continue }
            if (st == 2 || st == 3) {
                if (c == "\\") { i += 2; continue }
                if ((st == 2 && c == "\"") || (st == 3 && c == "\047")) { st = 0; out = out c }
                i++; continue
            }
            if (d == "/*") { st = 1; i += 2; continue }
            if (d == "//") break
            if (c == "\"") st = 2; else if (c == "\047") st = 3
            out = out c; i++
        }
        if (st != 1) st = 0
        print out
    }'
}
flatten()      { tr '\n\t' '  ' | sed -E 's/ +/ /g'; }
count()        { grep -oE -- "$1" <<<"$2" | wc -l | tr -d ' '; }            # $1 regex, $2 text: matches, not lines
offsets()      { grep -obE -- "$1" <<<"$2" | cut -d: -f1; }                 # byte offset of every match
# Brace depth at byte offset $2 of $1, and the last non-blank character before that offset.
stmt_context() {
    awk -v off="$2" '{ d = 0; p = ""
        for (i = 1; i <= off; i++) { c = substr($0, i, 1); if (c == "{") d++; else if (c == "}") d--; if (c != " ") p = c }
        print d, p }' <<<"$1"
}

gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }

# $1 = flattened code of one function, $2 = the name ORIG goes by in it, $3 = label for messages.
# Checks (a), (b), (c). Returns 2, silently, when the function makes no copy of ORIG at all.
# On return CR_FIRST_REG / CR_LAST_REG hold the offsets of the first and last registration.
check_copy_register() {
    local code="$1" orig="$2" label="$3"
    local copy_of="BRTransactionCopy$SP\\($SP$orig$SP\\)"
    local copy
    copy="$(grep -oE -- "\\<$ID$SP=$SP$copy_of" <<<"$code" | head -n 1 | sed -E "s/^($ID).*/\\1/")"
    [ -n "$copy" ] || return 2
    if [ "$copy" = "$orig" ]; then say "$label: the copy is assigned over the original ($orig)"; return 1; fi

    # (a) COPY holds a copy of ORIG, or NULL, and nothing else; its value is given to no other name.
    local n_assign n_assign_ok
    n_assign="$(count "\\<$copy$SP=([^=]|\$)" "$code")"
    n_assign_ok="$(count "\\<$copy$SP=$SP($copy_of|NULL\\>)" "$code")"
    if [ "$n_assign" != "$n_assign_ok" ]; then
        say "$label: $copy is assigned something other than BRTransactionCopy($orig) or NULL" \
            "($n_assign_ok of $n_assign assignments)"
    fi
    if grep -Eq -- "[^=!<>]=$SP\\<$copy$SP[;,]" <<<"$code"; then
        say "$label: the value of $copy is given to another name"
    fi

    # (b) every registration registers COPY. Every mention of the registering function counts as
    # a registration, whatever follows it, so nothing is registered by a spelling this misses.
    local reg_copy="\\<BRWalletRegisterTransaction$SP\\([^,()]*,$SP$copy$SP\\)"
    local n_reg n_reg_copy
    n_reg="$(count "BRWalletRegisterTransaction" "$code")"
    n_reg_copy="$(count "$reg_copy" "$code")"
    if [ "$n_reg_copy" -eq 0 ]; then say "$label: does not register the copy ($copy) into the wallet"; return 1; fi
    if [ "$n_reg" != "$n_reg_copy" ]; then
        say "$label: registers something other than $copy into the wallet" \
            "($n_reg_copy of $n_reg registrations name the copy)"
    fi

    local copy_at
    copy_at="$(offsets "\\<$copy$SP=$SP$copy_of" "$code" | head -n 1)"
    CR_FIRST_REG="$(offsets "$reg_copy" "$code" | head -n 1)"
    CR_LAST_REG="$(offsets "$reg_copy" "$code" | tail -n 1)"
    if [ "$copy_at" -ge "$CR_FIRST_REG" ]; then
        say "$label: registers $copy (offset $CR_FIRST_REG) before it holds the copy (offset $copy_at)"
    fi

    # (c) releases of COPY.
    local at between reg_before reg_depth free_depth prev
    for at in $(offsets "\\<BRTransactionFree$SP\\($SP$copy$SP\\)" "$code"); do
        if [ "$at" -lt "$CR_FIRST_REG" ]; then
            # Before the registration: only on a path that does not go on to register it.
            between="${code:$at:$((CR_FIRST_REG - at))}"
            if ! grep -Eq -- "\\<(else|return|goto)\\>|\\<$copy$SP=${SP}NULL\\>" <<<"$between"; then
                say "$label: $copy must reach its registration unreleased (released at offset $at," \
                    "registered at offset $CR_FIRST_REG)"
            fi
        else
            # After it: only under a condition. A release that starts a statement at the brace
            # depth of the registration before it (or shallower) is under none.
            reg_before="$(offsets "$reg_copy" "$code" | awk -v a="$at" '$1 < a { r = $1 } END { print r }')"
            read -r reg_depth _ <<<"$(stmt_context "$code" "$reg_before")"
            read -r free_depth prev <<<"$(stmt_context "$code" "$at")"
            case "$prev" in
                ';'|'{'|'}')
                    if [ "$free_depth" -le "$reg_depth" ]; then
                        say "$label: once registered (offset $reg_before) $copy belongs to the wallet;" \
                            "an unconditional release follows it (offset $at)"
                    fi ;;
            esac
        fi
    done
    return 0
}

STRIPPED="$(strip_c < "$JNI")"
# $1 = awk regex for the definition line. Prints that line through the function's column-0 close.
fn_slice() { awk -v re="$1" '! f && $0 ~ re && $0 !~ /;/ { f = 1 } f { print } f && /^}/ { exit }' <<<"$STRIPPED"; }

FN_LINES="$(fn_slice '_sendDigiDollar[[:space:]]*[(]')"
FN="$(flatten <<<"$FN_LINES")"

# The scanner is not blind: the slice is the function's definition, body included.
if ! grep -Eq -- "_sendDigiDollar$SP\\([^;{]*\\)$SP\\{.*\\}" <<<"$FN"; then
    say "could not locate the definition of sendDigiDollar — the scanner is blind"
fi
if grep -Eq '^[[:space:]]*#' <<<"$FN_LINES"; then
    say "sendDigiDollar holds a preprocessor directive — a text scan cannot tell which branch is compiled"
fi

# (d) the publish call names ORIG; every publish call is handed that same object.
PUB_ANY='\<BRPeerManagerPublish[A-Za-z0-9_]*'
ORIG="$(grep -oE -- "$PUB_ANY$SP\\([^,()]*,$SP$ID$SP[,)]" <<<"$FN" | head -n 1 | sed -E "s/.*,$SP($ID)$SP[,)]\$/\\1/")"
if [ -z "$ORIG" ]; then
    say "sendDigiDollar does not hand a transaction to the publisher"
else
    PUB_ORIG="$PUB_ANY$SP\\([^,()]*,$SP$ORIG$SP[,)]"
    N_PUB="$(count "$PUB_ANY" "$FN")"; N_PUB_ORIG="$(count "$PUB_ORIG" "$FN")"
    if [ "$N_PUB" != "$N_PUB_ORIG" ]; then
        say "sendDigiDollar publishes something other than $ORIG ($N_PUB_ORIG of $N_PUB publish calls name it)"
    fi
    FIRST_PUB="$(offsets "$PUB_ORIG" "$FN" | head -n 1)"

    check_copy_register "$FN" "$ORIG" "sendDigiDollar"; cr=$?
    if [ "$cr" -eq 0 ]; then
        if [ "$CR_LAST_REG" -ge "$FIRST_PUB" ]; then
            say "sendDigiDollar must register the copy (offset $CR_LAST_REG) before it publishes" \
                "the original (offset $FIRST_PUB) — the order is wrong"
        fi
    elif [ "$cr" -eq 2 ]; then
        # No copy here: (a)-(c) may live in a file-static helper called with ORIG before the publish.
        helper_ok=0
        for h in $(grep -oE -- "\\<$ID$SP\\([^()]*\\<$ORIG\\>[^()]*\\)" <<<"${FN:0:$FIRST_PUB}" | sed -E "s/^($ID).*/\\1/" | sort -u); do
            H="$(fn_slice "^static[^(]*[^A-Za-z0-9_]$h[[:space:]]*[(]" | flatten)"
            [ -n "$H" ] || continue
            H_ORIG="$(grep -oE -- "\\<BRTransaction$SP\\*$SP$ID" <<<"${H%%\{*}" | head -n 1 | sed -E "s/.*\\*$SP//")"
            [ -n "$H_ORIG" ] || continue
            check_copy_register "$H" "$H_ORIG" "$h (called by sendDigiDollar)"; hcr=$?
            if [ "$hcr" -ne 2 ]; then helper_ok=1; echo "  gate: copy and registration found in helper $h"; fi
        done
        if [ "$helper_ok" -eq 0 ]; then say "sendDigiDollar does not copy the transaction ($ORIG) before publishing it"; fi
        if [ "$(count "BRWalletRegisterTransaction" "$FN")" -ne 0 ]; then
            say "sendDigiDollar registers a transaction it did not copy"
        fi
    fi

    # (e) once handed over, ORIG belongs to the publisher: the function does not name it again
    # (other than to hand the same object to a publish call on another branch).
    AFTER="$(sed -E "s/$PUB_ORIG/ /g" <<<"${FN:$FIRST_PUB}")"
    if grep -Eq -- "\\<$ORIG\\>" <<<"$AFTER"; then
        say "once handed to the publisher, $ORIG is not sendDigiDollar's to name again; found:" \
            "$(grep -oE -- ".{0,30}\\<$ORIG\\>.{0,20}" <<<"$AFTER" | head -n 1)"
    fi
fi

if [ "$gate_fail" -ne 0 ]; then
    echo "SOURCE GATE FAILED"
    exit 1
fi
echo "SOURCE GATE confirmed: sendDigiDollar copies ($ORIG), registers the copy, publishes the original, and leaves it alone."

echo
echo "tx_publish_ownership_kat: RED-BEFORE-GREEN OK (4/4 arms), source gate OK"
exit 0
