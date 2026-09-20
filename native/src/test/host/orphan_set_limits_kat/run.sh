#!/usr/bin/env bash
# Host KAT: the parentless-header ("orphan") set has a fixed upper limit, every header that
# leaves it has exactly one owner, its byte total always equals what is resident, and its
# re-anchor request is spaced per peer.
#
# The _main.c #includes BRPeerManager.c, so it reaches the file-static helpers and the real
# _peerRelayedBlock (see its header for what each scenario proves). This script requires:
#
#   * ORPHAN_SET_LIMITS_UNFIXED=1 (reference arm: the earlier store, connect step and request
#     rule, selected inside BRPeerManager.c). Run once per scenario, and each run must be
#     reported for its own reason:
#       limits   -> this KAT's own count-limit check fails, AND LeakSanitizer reports
#       relay    -> LeakSanitizer reports (the store's real call site)
#       connect  -> AddressSanitizer reports (lastOrphan after its header left the set)
#       rescan   -> this KAT's own exact-total check at the store's SECOND call site fails,
#                   AND LeakSanitizer reports (a header displaced there)
#   * ORPHAN_SET_LIMITS_UNFIXED=0 (fixed arm): every check passes, exit 0, and the run is
#     clean under AddressSanitizer and LeakSanitizer.
#
# A reference arm that is not reported proves nothing, so that is a failure of this gate; a
# non-zero exit alone does not count (a kill or a stop in setup also exits non-zero).
#
# BRPeer.c is compiled through orphan_set_limits_kat_peer.c, which #includes it so that the
# rig's peer can be handed a version message (its announced best height) by the real handler.
#
# LEAK DETECTION IS ON here: single ownership is what this gate proves.
# Value-macro convention: -D...=1 / -D...=0, tested with #if.
# Each arm compiles BRPeerManager.c under ASan; allow about a minute per arm on a busy host.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/orphan_set_limits_kat_main.c" \
        "$SCRIPT_DIR/orphan_set_limits_kat_peer.c" \
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

# Seam: the reference arm is selected by a macro INSIDE BRPeerManager.c. If that macro were
# gone, -D...=1 would select nothing and both arms would be the same code.
if ! grep -q 'ORPHAN_SET_LIMITS_UNFIXED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c no longer tests ORPHAN_SET_LIMITS_UNFIXED — the reference arm has no seam."
    exit 1
fi

# Leak detection ON. symbolize=0 for speed: the gate detects a report, it does not name frames.
export ASAN_OPTIONS="detect_leaks=1 symbolize=0"

echo "=== building the reference arm ==="
if ! build "$BUILD_DIR/kat_reference" -DORPHAN_SET_LIMITS_UNFIXED=1; then
    echo "BUILD FAILURE (reference arm) — gate cannot run"; exit 1
fi
echo "=== building the fixed arm ==="
if ! build "$BUILD_DIR/kat_fixed" -DORPHAN_SET_LIMITS_UNFIXED=0; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

# $1 = scenario; remaining args = patterns that must ALL appear in the reference arm's output.
require_reference_reported() {
    local scenario="$1"; shift
    local out="$BUILD_DIR/reference_$scenario.out"
    echo
    echo "---- REFERENCE ARM, scenario=$scenario: must be reported, and for its own reason ----"
    if "$BUILD_DIR/kat_reference" "$scenario" > "$out" 2>&1; then
        grep -vE "^BITCOIN_TESTNET|^Starting sync" "$out" | tail -12
        echo
        echo "GATE FAILURE: the reference arm PASSED scenario=$scenario — this gate is not"
        echo "exercising the invariant it exists for."
        exit 1
    fi
    local pattern
    for pattern in "$@"; do
        if ! grep -qE "$pattern" "$out"; then
            grep -vE "^BITCOIN_TESTNET|^Starting sync" "$out" | tail -12
            echo
            echo "GATE FAILURE: the reference arm exited non-zero in scenario=$scenario WITHOUT"
            echo "\"$pattern\" — stopped for some other reason, which does not count."
            exit 1
        fi
        grep -E "$pattern" "$out" | head -1 | cut -c1-160 | sed 's/^/  reference: /'
    done
}

require_reference_reported limits  "\[FAIL\] \(R\) parentless-header count stays within the fixed upper limit" "ERROR: LeakSanitizer"
require_reference_reported relay   "ERROR: LeakSanitizer"
require_reference_reported connect "ERROR: AddressSanitizer"
require_reference_reported rescan  "\[FAIL\] \(R\) byte total equals the resident sum after an insert at the second call site" "ERROR: LeakSanitizer"

echo
echo "---- FIXED ARM: every scenario must pass, clean under both sanitizers ----"
FIXED_OUT="$BUILD_DIR/fixed.out"
if ! "$BUILD_DIR/kat_fixed" > "$FIXED_OUT" 2>&1; then
    grep -vE "^BITCOIN_TESTNET|^Starting sync" "$FIXED_OUT" | grep -E "\[FAIL\]|ERROR: |^after|^===" | head -20
    echo "GATE FAILURE: the fixed arm did not pass (a failed check or a sanitizer report)."
    exit 1
fi
if grep -qE "ERROR: (Address|Leak)Sanitizer" "$FIXED_OUT"; then
    echo "GATE FAILURE: the fixed arm exited 0 but a sanitizer reported."; exit 1
fi
grep -E "^after |orphan_set_limits_kat: PASS" "$FIXED_OUT" | sed 's/^/  fixed: /'
echo "  fixed: $(grep -c '\[PASS\]' "$FIXED_OUT") checks passed, $(grep -c '\[FAIL\]' "$FIXED_OUT") failed"

echo
echo "orphan_set_limits_kat: PASS (reference arm reported in every scenario, fixed arm clean)"
exit 0
