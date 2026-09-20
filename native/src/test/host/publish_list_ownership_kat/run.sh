#!/usr/bin/env bash
# Host KAT: every published transaction object has exactly one owner.
#
# The rule: the peer manager's publish list releases only objects it OWNS, and the wallet's
# records are released only by the wallet. An entry the list fetched from the wallet itself (a
# send's parent input, or a send re-added by the relay path) is a wallet record: the list may drop
# the entry but never releases the object. And when the wallet becomes the owner of an object the
# list holds, the entry follows it — the wallet is then its one owner. The confirmation and
# invalid-request seams release an object iff the list owns it AND the wallet holds no record of
# its hash; the disconnect seam releases iff the list owns the object.
#
# The _main.c #includes BRPeerManager.c (and BRPeer.c) and drives the REAL publish, relay, request,
# confirm and disconnect functions. Neither .c is on the link line below — every symbol would be
# defined twice. One file-static name, _dummyThreadCleanup, is renamed for BRPeer.c's copy by a
# preprocessor substitution scoped to that #include. Same pattern as tx_publish_ownership_kat.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of a macro selects a comparison arm (the shipped arm is built with no -D at all), matching
# the sibling publish_cancel_survivor_kat. The main.c and the seams inside BRPeerManager.c test the
# macros with #ifdef/#ifndef.
#   comparison PUBLISH_LIST_OWNERSHIP_UNFIXED: a shape without the rule at the release seams — release
#       keyed off the wallet's knowledge of the hash instead of off ownership.
#   comparison PUBLISH_OWNED_FOLLOWS_UNFIXED: a shape without the rule where the wallet becomes an
#       object's owner — the entry does not follow the object.
#   shipped: the rule above.
# Four scenarios are RED-THEN-GREEN against a comparison arm (a comparison arm is reported by
# AddressSanitizer while the shipped arm is clean). Four are GUARDs the shipped arm asserts directly;
# for the two whose shape no committed comparison arm can express, this runner mutates the seam in a
# scratch copy of BRPeerManager.c and requires the mutant to be reported — proving the guard is not
# vacuous. A comparison/mutant arm that is not reported proves nothing, so that is a failure here; a
# non-zero exit alone does not count.
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
for macro in PUBLISH_LIST_OWNERSHIP_UNFIXED PUBLISH_OWNED_FOLLOWS_UNFIXED; do
    if ! grep -q "$macro" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILED: $macro is not present in BRPeerManager.c — the -D would select nothing"
        echo "             and its comparison arm would run the shipped code."
        exit 1
    fi
done

# $1 out, $2 dir holding the BRPeerManager.c to #include (first on the -I path), rest: extra -D...
build() {
    local out="$1" pmdir="$2"; shift 2
    clang -w -include stdint.h "$@" \
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

# A scratch copy of the core whose confirmation seam releases iff the list owns the object.
MUT_RELEASE_DIR="$BUILD_DIR/src_mut_release"; mkdir -p "$MUT_RELEASE_DIR"
sed 's/if (owned \&\& ! BRWalletTransactionForHash(manager->wallet, tx->txHash)) BRTransactionFree(tx);/if (owned) BRTransactionFree(tx);/' \
    "$CORE_DIR/BRPeerManager.c" > "$MUT_RELEASE_DIR/BRPeerManager.c"
if ! grep -q "if (owned) BRTransactionFree(tx);" "$MUT_RELEASE_DIR/BRPeerManager.c"; then
    echo "GATE FAILED: the confirmation-seam mutant did not apply."; exit 1
fi

# A scratch copy with the invalid-request seam releasing REGARDLESS of ownership: it releases a
# wallet record listed with owned = 0.
MUT_INVALID_DIR="$BUILD_DIR/src_mut_invalid"; mkdir -p "$MUT_INVALID_DIR"
sed 's/if (owned \&\& ! BRWalletTransactionForHash(manager->wallet, txHash)) {/if (owned || 1) {/' \
    "$CORE_DIR/BRPeerManager.c" > "$MUT_INVALID_DIR/BRPeerManager.c"
if ! grep -q "if (owned || 1) {" "$MUT_INVALID_DIR/BRPeerManager.c"; then
    echo "GATE FAILED: the invalid-request-seam mutant did not apply."; exit 1
fi

echo "=== building arms (BRPeerManager.c compiles under ASan; about a minute per arm) ==="
if ! build "$BUILD_DIR/ref_release" "$CORE_DIR" -DPUBLISH_LIST_OWNERSHIP_UNFIXED; then
    echo "GATE FAILED: comparison arm PUBLISH_LIST_OWNERSHIP_UNFIXED did not build"; exit 1
fi
if ! build "$BUILD_DIR/ref_follows" "$CORE_DIR" -DPUBLISH_OWNED_FOLLOWS_UNFIXED; then
    echo "GATE FAILED: comparison arm PUBLISH_OWNED_FOLLOWS_UNFIXED did not build"; exit 1
fi
if ! build "$BUILD_DIR/mut_release" "$MUT_RELEASE_DIR"; then
    echo "GATE FAILED: confirmation-seam mutant arm did not build"; exit 1
fi
if ! build "$BUILD_DIR/mut_invalid" "$MUT_INVALID_DIR"; then
    echo "GATE FAILED: invalid-request-seam mutant arm did not build"; exit 1
fi
if ! build "$BUILD_DIR/shipped" "$CORE_DIR"; then
    echo "GATE FAILED: shipped arm did not build"; exit 1
fi

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
BANNER="ERROR: AddressSanitizer|runtime error:"

# The shipped arm must run a scenario clean and print ALL PASS.
run_shipped() {
    local scenario="$1"
    "$BUILD_DIR/shipped" "$scenario" > "$BUILD_DIR/ship.$scenario.log" 2>&1
    local ship_exit=$?
    if [ "$ship_exit" -ne 0 ]; then
        echo "GATE FAILED: the shipped arm failed scenario=$scenario (exit $ship_exit):"
        sed 's/^/   | /' "$BUILD_DIR/ship.$scenario.log"; exit 1
    fi
    if grep -Eq "$BANNER" "$BUILD_DIR/ship.$scenario.log"; then
        echo "GATE FAILED: the shipped arm exited 0 but a sanitizer reported for scenario=$scenario."
        sed 's/^/   | /' "$BUILD_DIR/ship.$scenario.log"; exit 1
    fi
    if ! grep -q "ALL PASS" "$BUILD_DIR/ship.$scenario.log"; then
        echo "GATE FAILED: the shipped arm did not print ALL PASS for scenario=$scenario:"
        sed 's/^/   | /' "$BUILD_DIR/ship.$scenario.log"; exit 1
    fi
    grep -E "^   PASS:|publish_list_ownership_kat\[$scenario\]" "$BUILD_DIR/ship.$scenario.log" | sed 's/^/   shipped: /'
}

# An arm ($2) must be REPORTED by AddressSanitizer for scenario $1 (and exit non-zero for that reason).
require_reported() {
    local scenario="$1" armbin="$2" armname="$3"
    "$armbin" "$scenario" > "$BUILD_DIR/$armname.$scenario.log" 2>&1
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

# RED-THEN-GREEN: a comparison arm is reported, the shipped arm is clean.
run_rtg() {
    local scenario="$1" armbin="$2" armname="$3"
    echo
    echo "---- [RED-THEN-GREEN] scenario=$scenario : $armname must be reported, shipped must be clean ----"
    require_reported "$scenario" "$armbin" "$armname"
    run_shipped "$scenario"
}

# GUARD: the shipped arm asserts the guarantee. An optional mutant arm ($2) must be reported so the
# guard is proven not vacuous.
run_guard() {
    local scenario="$1" mutbin="${2:-}" mutname="${3:-}"
    echo
    if [ -n "$mutbin" ]; then
        echo "---- [GUARD] scenario=$scenario : shipped clean; $mutname (mutated seam) must be reported ----"
        require_reported "$scenario" "$mutbin" "$mutname"
    else
        echo "---- [GUARD] scenario=$scenario : shipped must assert the guarantee clean ----"
    fi
    run_shipped "$scenario"
}

run_rtg   survives         "$BUILD_DIR/ref_release" ref_release
run_guard confirm_released
run_guard confirm_window   "$BUILD_DIR/mut_release" mut_release
run_guard invalid_released
run_guard invalid_survives "$BUILD_DIR/mut_invalid" mut_invalid
run_rtg   requested        "$BUILD_DIR/ref_follows" ref_follows
run_rtg   hastx            "$BUILD_DIR/ref_follows" ref_follows
run_rtg   relay            "$BUILD_DIR/ref_follows" ref_follows

echo
echo "publish_list_ownership_kat: RED-BEFORE-GREEN OK (4 comparison-arm scenarios + 2 mutant-proven guards + 2 guards)"
exit 0
