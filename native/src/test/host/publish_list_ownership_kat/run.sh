#!/usr/bin/env bash
# Host KAT: every published transaction object has exactly one owner, and every publish callback is
# answered exactly once.
#
# The rule: the peer manager's publish list releases only objects it OWNS, and the wallet's records
# are released only by the wallet. Ownership is decided exactly when an entry is added (an object the
# wallet already holds as its record is the wallet's; a distinct object is the list's), the entry
# follows its object when the wallet becomes the owner, and every drop site — confirmation, an
# invalid request, cancellation, a wallet-side removal, teardown — releases by ownership alone and
# answers a pending callback exactly once, after the manager lock is released. A duplicate publish
# while pending is answered EALREADY; a peer's invalid/non-standard/dust rejection resolves a pending
# publish with EINVAL once unless another peer relayed it; the relay path releases the parsed object
# it was handed when the wallet already holds the hash; the stem decision lives on the entry and is
# stamped on the served copy only.
#
# The _main.c #includes BRPeerManager.c (and BRPeer.c) and drives the REAL publish, relay, request,
# reject, confirm, remove and disconnect functions. Neither .c is on the link line below — every
# symbol would be defined twice. One file-static name, _dummyThreadCleanup, is renamed for BRPeer.c's
# copy by a preprocessor substitution scoped to that #include. Same pattern as tx_publish_ownership_kat.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of a macro selects a comparison arm (the shipped arm is built with no -D at all), matching
# the sibling publish_cancel_survivor_kat. The main.c and the seams inside BRPeerManager.c test the
# macros with #ifdef/#ifndef. Nine comparison arms (see the main.c header) and three mutants of the
# shipped source (a scratch copy with one seam changed) are built; each scenario is one of:
#   RED-THEN-GREEN (AddressSanitizer)  — the named arm is reported by AddressSanitizer; shipped is clean.
#   RED-THEN-GREEN (LeakSanitizer)     — the named arm leaks an owner-less object or an unanswered
#                                        context under detect_leaks=1; shipped is leak-clean.
#   RED-THEN-GREEN (assertion)         — the named arm fails the scenario's own check (a verdict that
#                                        never arrives leaves nothing for a sanitizer to see); shipped
#                                        passes. Labelled so a reader knows the red is an assertion.
#   GUARD                              — the shipped arm asserts the guarantee; where named, a mutated
#                                        seam must be reported, so the guard is proven not vacuous.
# An arm that is not reported proves nothing, so that is a failure here; a non-zero exit alone does
# not count. Finally EVERY scenario but the two-thread race runs on the shipped arm under the leak
# checker and must be clean: the shipped arm leaves no object without an owner.
set -uo pipefail   # NOT -e: a comparison/mutant arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# Seam self-check: a -D that names nothing silently builds the shipped path, so a comparison arm
# would run the shipped code and (wrongly) pass. Require each seam to exist in the real source.
for macro in PUBLISH_LIST_OWNERSHIP_UNFIXED PUBLISH_OWNED_FOLLOWS_UNFIXED PUBLISH_OWNED_AT_ADD_UNFIXED \
             PUBLISH_REMOVE_PURGE_UNFIXED PUBLISH_REMOVE_OWNED_SKIP_UNFIXED PUBLISH_SERVED_COPY_UNFIXED PUBLISH_ANSWER_ONCE_UNFIXED \
             PUBLISH_RELAY_OBJECT_UNFIXED PUBLISH_STEM_TYPE_UNFIXED PUBLISH_REJECT_UNFIXED \
             UNRELAYED_SWEEP_RELOOKUP_UNFIXED; do
    if ! grep -q "$macro" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILED: $macro is not present in BRPeerManager.c — the -D would select nothing"
        echo "             and its comparison arm would run the shipped code."
        exit 1
    fi
done

# Source gate 1 — BRPeer.c's getdata handler. No scenario in this suite, and none in any sibling
# suite, enters _BRPeerAcceptGetdataMessage: it needs a live socket. So the one release the handler
# performs has no arm that would notice it going away, and the object it releases is the private copy
# _peerRequestedTx hands out — released exactly once, on every path out of the item. Hold that by
# reading the source, the same way the seam self-check above holds the comparison macros.
GETDATA_FN="$(awk '/^static int _BRPeerAcceptGetdataMessage/,/^\}/' "$CORE_DIR/BRPeer.c")"
if [ -z "$GETDATA_FN" ]; then
    echo "GATE FAILED: _BRPeerAcceptGetdataMessage was not found in BRPeer.c, so the source gate"
    echo "             over the served copy's release cannot hold. Re-anchor the gate."
    exit 1
fi
GETDATA_FREES=$(printf '%s\n' "$GETDATA_FN" | grep -c "BRTransactionFree(tx)")
if [ "$GETDATA_FREES" -lt 2 ]; then
    echo "GATE FAILED: BRPeer.c's getdata handler must release the object requestedTx returns on"
    echo "             BOTH paths through an inv_tx item — after it has been sent, and when it is"
    echo "             too large to send. Found $GETDATA_FREES release(s) of tx, expected 2."
    exit 1
fi
if ! printf '%s\n' "$GETDATA_FN" | grep -q "tx = ctx->requestedTx ? ctx->requestedTx(ctx->info, hash) : NULL;"; then
    echo "GATE FAILED: BRPeer.c's getdata handler must set tx for every inv_tx item (NULL when there"
    echo "             is no provider), so no item can read or send the previous item's object."
    exit 1
fi

# Source gate 2 — the bridge's removal. jni_transaction.c cannot be compiled on the host (JNI), and
# this suite drives the core call directly, so nothing here would notice the bridge stopping using
# it. Read the source: the removal takes the peer guard, routes through the manager only while the
# manager holds this same wallet, and reports on what the wallet holds afterwards. Same pattern as
# tx_publish_ownership_kat's gate over the publish entry points. Needles are matched with white
# space collapsed, so a re-indentation does not move the gate.
BRIDGE_C="$REPO_ROOT/native/src/main/jni/bridge/jni_transaction.c"
REMOVE_FN="$(awk '/^Java_io_digibyte_core_bridge_NativeBridge_removeTransaction/,/^\}/' "$BRIDGE_C" | tr -s '[:space:]' ' ')"
if [ -z "$REMOVE_FN" ]; then
    echo "GATE FAILED: removeTransaction was not found in jni_transaction.c. Re-anchor the gate."
    exit 1
fi
for needle in "PEER_GUARD();" \
              "g_peerManager && !g_walletSwapped" \
              "BRPeerManagerRemoveTransaction(g_peerManager, h)" \
              "BRWalletRemoveTransaction(g_wallet, h)" \
              "removed = BRWalletTransactionForHash(g_wallet, h) ? JNI_FALSE : JNI_TRUE;"; do
    if ! printf '%s\n' "$REMOVE_FN" | grep -qF "$needle"; then
        echo "GATE FAILED: the bridge's removeTransaction no longer contains: $needle"
        echo "             It must hold the peer guard, take the manager-locked removal only while"
        echo "             the manager holds this g_wallet (the marker says it may not), fall back to"
        echo "             the wallet's own removal otherwise, and report on what the wallet holds."
        exit 1
    fi
done

# Source gate 3 — the bridge's publish-result callback consumes EALREADY as "no verdict": it releases
# the context and records nothing, so a duplicate publish never overwrites the earlier publish's
# pending or delivered verdict. The core never records anything itself, so only the source shows it.
RESULT_FN="$(awk '/^static void _publishResult\(/,/^\}/' "$BRIDGE_C" | tr -s '[:space:]' ' ')"
if [ -z "$RESULT_FN" ]; then
    echo "GATE FAILED: _publishResult was not found in jni_transaction.c. Re-anchor the gate."
    exit 1
fi
if ! printf '%s\n' "$RESULT_FN" | grep -qE 'if \(error == EALREADY\) \{[^}]*free\(ctx\);[^}]*return;[^}]*\}'; then
    echo "GATE FAILED: the bridge's _publishResult must consume EALREADY by releasing the context and"
    echo "             returning before anything is recorded (the earlier publish carries the verdict)."
    exit 1
fi
EARLY="${RESULT_FN%%_publishResultRecord*}"
if ! printf '%s\n' "$EARLY" | grep -q 'EALREADY'; then
    echo "GATE FAILED: _publishResult's EALREADY branch must come BEFORE the verdict is recorded."
    exit 1
fi

# $1 out, $2 dir holding the BRPeerManager.c to #include (first on the -I path), rest: extra -D...
# KAT_BITS=32 builds a 32-bit binary (needs gcc-multilib), as wire_count_bounds_kat does.
build() {
    local out="$1" pmdir="$2"; shift 2
    local m=()
    [ "${KAT_BITS:-64}" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$pmdir" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/publish_list_ownership_kat_main.c" \
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

# Mutants: a scratch copy of the core with exactly one seam changed. Each sed must apply exactly
# once, or the mutant would be the shipped code and prove nothing.
mutate() {   # $1 dir, $2 sed expression, $3 text that must appear exactly once afterwards, $4 label
    mkdir -p "$1"
    sed "$2" "$CORE_DIR/BRPeerManager.c" > "$1/BRPeerManager.c"
    local n; n=$(grep -cF -- "$3" "$1/BRPeerManager.c")
    if [ "$n" -ne 1 ]; then echo "GATE FAILED: the $4 mutant did not apply exactly once ($n)."; exit 1; fi
}
# mut_release: ownership is NOT decided at add time — a caller's owned = 1 is kept even when the
# object is the wallet's own record (the seam the legacy same-object scenarios pin).
mutate "$BUILD_DIR/src_mut_release" \
    's/if (owned) owned = (BRWalletTransactionForHash(manager->wallet, tx->txHash) != tx);/if (owned) owned = 1;/' \
    'if (owned) owned = 1;' mut_release
# mut_invalid: the one drop site releases REGARDLESS of ownership (a wallet record listed with
# owned = 0 is released).
mutate "$BUILD_DIR/src_mut_invalid" \
    's/^    release = e\.owned;$/    release = 1;/' \
    '    release = 1;' mut_invalid
# mut_conjunct: the one drop site releases the list's own object only when the wallet holds no
# equal-hash record — the earlier rule, which left the list's copy without an owner at every
# confirmation of a send the wallet also holds.
mutate "$BUILD_DIR/src_mut_conjunct" \
    's/^    release = e\.owned;$/    release = e.owned \&\& ! BRWalletTransactionForHash(manager->wallet, manager->publishedTxHashes[idx]);/' \
    'release = e.owned && ! BRWalletTransactionForHash(manager->wallet, manager->publishedTxHashes[idx]);' mut_conjunct

echo "=== building arms (BRPeerManager.c compiles under ASan; about 15 s per arm, 15 arms + the 32-bit shipped arm) ==="
build_arm() {   # $1 name, $2 pmdir, rest -D
    local name="$1" pmdir="$2"; shift 2
    if ! build "$BUILD_DIR/$name" "$pmdir" "$@"; then echo "GATE FAILED: arm $name did not build"; exit 1; fi
}
build_arm ref_release  "$CORE_DIR" -DPUBLISH_LIST_OWNERSHIP_UNFIXED
build_arm ref_follows  "$CORE_DIR" -DPUBLISH_OWNED_FOLLOWS_UNFIXED
build_arm ref_owned    "$CORE_DIR" -DPUBLISH_OWNED_AT_ADD_UNFIXED
build_arm ref_remove   "$CORE_DIR" -DPUBLISH_REMOVE_PURGE_UNFIXED
build_arm ref_ownskip  "$CORE_DIR" -DPUBLISH_REMOVE_OWNED_SKIP_UNFIXED
build_arm ref_served   "$CORE_DIR" -DPUBLISH_SERVED_COPY_UNFIXED
build_arm ref_answer   "$CORE_DIR" -DPUBLISH_ANSWER_ONCE_UNFIXED
build_arm ref_relay    "$CORE_DIR" -DPUBLISH_RELAY_OBJECT_UNFIXED
build_arm ref_stem     "$CORE_DIR" -DPUBLISH_STEM_TYPE_UNFIXED
build_arm ref_reject   "$CORE_DIR" -DPUBLISH_REJECT_UNFIXED
build_arm ref_sweep    "$CORE_DIR" -DUNRELAYED_SWEEP_RELOOKUP_UNFIXED
build_arm mut_release  "$BUILD_DIR/src_mut_release"
build_arm mut_invalid  "$BUILD_DIR/src_mut_invalid"
build_arm mut_conjunct "$BUILD_DIR/src_mut_conjunct"
build_arm shipped      "$CORE_DIR"
if ! KAT_BITS=32 build "$BUILD_DIR/shipped32" "$CORE_DIR"; then echo "GATE FAILED: the 32-bit shipped arm did not build (gcc-multilib?)"; exit 1; fi

ASAN_OFF="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export LSAN_OPTIONS="exitcode=23"
BANNER="ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:"

# The shipped arm must run a scenario clean and print ALL PASS. $2 = ASAN_OPTIONS to use.
run_shipped_with() {
    local scenario="$1" opts="$2" tag="$3"
    ASAN_OPTIONS="$opts" "$BUILD_DIR/shipped" "$scenario" > "$BUILD_DIR/ship.$tag.$scenario.log" 2>&1
    local ship_exit=$?
    if [ "$ship_exit" -ne 0 ]; then
        echo "GATE FAILED: the shipped arm failed scenario=$scenario ($tag, exit $ship_exit):"
        sed 's/^/   | /' "$BUILD_DIR/ship.$tag.$scenario.log"; exit 1
    fi
    if grep -Eq "$BANNER" "$BUILD_DIR/ship.$tag.$scenario.log"; then
        echo "GATE FAILED: the shipped arm exited 0 but a sanitizer reported for scenario=$scenario ($tag)."
        sed 's/^/   | /' "$BUILD_DIR/ship.$tag.$scenario.log"; exit 1
    fi
    if ! grep -q "ALL PASS" "$BUILD_DIR/ship.$tag.$scenario.log"; then
        echo "GATE FAILED: the shipped arm did not print ALL PASS for scenario=$scenario ($tag):"
        sed 's/^/   | /' "$BUILD_DIR/ship.$tag.$scenario.log"; exit 1
    fi
}
run_shipped() {
    run_shipped_with "$1" "$ASAN_OFF" asan
    grep -E "^   PASS:|publish_list_ownership_kat\[$1\]" "$BUILD_DIR/ship.asan.$1.log" | sed 's/^/   shipped: /'
}

# An arm ($2) must be REPORTED by a sanitizer for scenario $1 (and exit non-zero for that reason).
require_reported_with() {
    local scenario="$1" armbin="$2" armname="$3" opts="$4"
    ASAN_OPTIONS="$opts" "$armbin" "$scenario" > "$BUILD_DIR/$armname.$scenario.log" 2>&1
    local ex=$?
    if [ "$ex" -eq 0 ]; then
        echo "GATE FAILED: the $armname arm exited 0 for scenario=$scenario — it cannot see the shape."
        sed 's/^/   | /' "$BUILD_DIR/$armname.$scenario.log"; exit 1
    fi
    if ! grep -Eq "$BANNER" "$BUILD_DIR/$armname.$scenario.log"; then
        echo "GATE FAILED: the $armname arm exited $ex for scenario=$scenario WITHOUT a sanitizer"
        echo "             report — stopped for some other reason, which does not count."
        sed 's/^/   | /' "$BUILD_DIR/$armname.$scenario.log"; exit 1
    fi
    grep -E "$BANNER" "$BUILD_DIR/$armname.$scenario.log" | head -1 | sed "s/^/   $armname: /"
}

# An arm must FAIL the scenario's own assertion (non-zero exit, a FAIL line, ALL PASS absent).
require_assert_failed() {
    local scenario="$1" armbin="$2" armname="$3"
    ASAN_OPTIONS="$ASAN_LEAK" "$armbin" "$scenario" > "$BUILD_DIR/$armname.$scenario.log" 2>&1
    local ex=$?
    if [ "$ex" -eq 0 ] || grep -q "ALL PASS" "$BUILD_DIR/$armname.$scenario.log"; then
        echo "GATE FAILED: the $armname arm passed scenario=$scenario — it cannot see the shape."
        sed 's/^/   | /' "$BUILD_DIR/$armname.$scenario.log"; exit 1
    fi
    if ! grep -q "^   FAIL:" "$BUILD_DIR/$armname.$scenario.log"; then
        echo "GATE FAILED: the $armname arm exited $ex for scenario=$scenario without failing a check."
        sed 's/^/   | /' "$BUILD_DIR/$armname.$scenario.log"; exit 1
    fi
    grep -m1 "^   FAIL:" "$BUILD_DIR/$armname.$scenario.log" | sed "s/^   /   $armname: /"
}

run_rtg() {          # AddressSanitizer red
    echo; echo "---- [RED-THEN-GREEN, AddressSanitizer] scenario=$1 : $3 must be reported, shipped must be clean ----"
    require_reported_with "$1" "$2" "$3" "$ASAN_OFF"; run_shipped "$1"
}
run_rtg_leak() {     # LeakSanitizer red
    echo; echo "---- [RED-THEN-GREEN, LeakSanitizer] scenario=$1 : $3 must leak, shipped must be leak-clean ----"
    require_reported_with "$1" "$2" "$3" "$ASAN_LEAK"; run_shipped "$1"
}
run_rtg_assert() {   # assertion red
    echo; echo "---- [RED-THEN-GREEN, assertion] scenario=$1 : $3 must fail its check, shipped must pass ----"
    require_assert_failed "$1" "$2" "$3"; run_shipped "$1"
}
run_guard() {        # $2/$3 optional mutant (AddressSanitizer), $4 = "leak" to require a leak report instead
    echo
    if [ -n "${2:-}" ]; then
        if [ "${4:-}" = "leak" ]; then
            echo "---- [GUARD] scenario=$1 : shipped clean; $3 (mutated seam) must leak ----"
            require_reported_with "$1" "$2" "$3" "$ASAN_LEAK"
        else
            echo "---- [GUARD] scenario=$1 : shipped clean; $3 (mutated seam) must be reported ----"
            require_reported_with "$1" "$2" "$3" "$ASAN_OFF"
        fi
    else
        echo "---- [GUARD] scenario=$1 : shipped must assert the guarantee clean ----"
    fi
    run_shipped "$1"
}

# -- ownership at every seam --
run_rtg   survives                  "$BUILD_DIR/ref_release" ref_release
run_guard confirm_released
run_guard confirm_released_copy     "$BUILD_DIR/mut_conjunct" mut_conjunct leak
run_guard invalid_released
run_guard invalid_survives          "$BUILD_DIR/mut_invalid"  mut_invalid
run_guard invalid_window            "$BUILD_DIR/mut_conjunct" mut_conjunct leak
run_rtg   invalid_window_legacy     "$BUILD_DIR/ref_owned"   ref_owned
run_rtg   confirm_legacy            "$BUILD_DIR/ref_owned"   ref_owned
run_rtg   cancel_legacy             "$BUILD_DIR/ref_owned"   ref_owned
run_rtg   requested                 "$BUILD_DIR/ref_follows" ref_follows
run_rtg   hastx                     "$BUILD_DIR/ref_follows" ref_follows
run_rtg   relay                     "$BUILD_DIR/ref_follows" ref_follows
# -- the wallet-side removals --
run_rtg   remove_relay              "$BUILD_DIR/ref_remove"  ref_remove
run_rtg   remove_parent             "$BUILD_DIR/ref_remove"  ref_remove
run_rtg   remove_readers            "$BUILD_DIR/ref_remove"  ref_remove
run_rtg   remove_unrelayed_sweep    "$BUILD_DIR/ref_remove"  ref_remove
run_rtg   sweep_dependant           "$BUILD_DIR/ref_sweep"   ref_sweep
run_rtg   remove_getdata_race       "$BUILD_DIR/ref_served"  ref_served
# -- every callback answered exactly once --
run_rtg_leak answered_confirm       "$BUILD_DIR/ref_answer"  ref_answer
run_rtg_leak answered_teardown      "$BUILD_DIR/ref_answer"  ref_answer
run_rtg_leak answered_duplicate     "$BUILD_DIR/ref_answer"  ref_answer
run_guard    answered_duplicate_legacy "$BUILD_DIR/mut_release" mut_release
run_rtg_leak answered_removed       "$BUILD_DIR/ref_answer"  ref_answer
run_rtg_assert answered_removed_bridge "$BUILD_DIR/ref_ownskip" ref_ownskip
# -- the relay path's own object --
run_rtg_leak relay_known            "$BUILD_DIR/ref_relay"   ref_relay
run_rtg_leak relay_block_known      "$BUILD_DIR/ref_relay"   ref_relay
# -- the stem decision on the entry --
run_rtg_leak stem_duplicate         "$BUILD_DIR/ref_stem"    ref_stem
# -- a peer's rejection --
run_rtg_assert answered_reject      "$BUILD_DIR/ref_reject"  ref_reject
run_rtg_assert reject_stem          "$BUILD_DIR/ref_reject"  ref_reject
run_guard    reject_policy_pending
run_guard    reject_relayed_pending

# -- the shipped arm leaves nothing without an owner: every scenario under the leak checker. The
#    two-thread race stays leak-off (LeakSanitizer and deliberately racing threads). --
echo
echo "---- [LEAK CHECK] every scenario on the shipped arm under detect_leaks=1 must be clean ----"
for scenario in survives confirm_released confirm_released_copy invalid_released invalid_survives \
                invalid_window invalid_window_legacy confirm_legacy cancel_legacy requested hastx relay \
                remove_relay remove_parent remove_readers remove_unrelayed_sweep sweep_dependant \
                answered_confirm answered_teardown answered_duplicate answered_duplicate_legacy answered_removed answered_removed_bridge \
                relay_known relay_block_known stem_duplicate \
                answered_reject reject_stem reject_policy_pending reject_relayed_pending; do
    run_shipped_with "$scenario" "$ASAN_LEAK" leak
    echo "   leak-clean: $scenario"
done

# -- the 32-bit pass: every scenario on the shipped arm built -m32 (AddressSanitizer on; the leak
#    checker is not available on i386, so leak-off). --
echo
echo "---- [32-BIT] every scenario on the shipped arm built -m32 must be clean ----"
for scenario in survives confirm_released confirm_released_copy invalid_released invalid_survives \
                invalid_window invalid_window_legacy confirm_legacy cancel_legacy requested hastx relay \
                remove_relay remove_parent remove_readers remove_unrelayed_sweep sweep_dependant remove_getdata_race \
                answered_confirm answered_teardown answered_duplicate answered_duplicate_legacy answered_removed answered_removed_bridge \
                relay_known relay_block_known stem_duplicate \
                answered_reject reject_stem reject_policy_pending reject_relayed_pending; do
    ASAN_OPTIONS="$ASAN_OFF" "$BUILD_DIR/shipped32" "$scenario" > "$BUILD_DIR/ship32.$scenario.log" 2>&1
    ex=$?
    if [ "$ex" -ne 0 ] || grep -Eq "$BANNER" "$BUILD_DIR/ship32.$scenario.log" || ! grep -q "ALL PASS" "$BUILD_DIR/ship32.$scenario.log"; then
        echo "GATE FAILED: the 32-bit shipped arm failed scenario=$scenario (exit $ex):"
        sed 's/^/   | /' "$BUILD_DIR/ship32.$scenario.log"; exit 1
    fi
    echo "   32-bit clean: $scenario"
done

echo
echo "publish_list_ownership_kat: RED-BEFORE-GREEN OK (15 AddressSanitizer + 7 LeakSanitizer + 3 assertion comparison-arm scenarios, 4 mutant-proven guards, 4 guards), leak check OK (30 scenarios), 32-bit pass OK (31 scenarios), source gates OK"
exit 0
