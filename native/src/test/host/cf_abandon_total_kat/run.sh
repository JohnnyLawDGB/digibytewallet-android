#!/usr/bin/env bash
# Host KAT runner for the ABANDONED-HEIGHTS COUNTER.
#
# BRPeerManagerAbandonedCount reports (abandonedBelow - start). Three production paths re-Init
# the ledger mid-session (cfheaders floor snap, CF chain re-anchor, arming clamp) and
# BRCFScanLedgerInit memsets it and sets start to the NEW floor — so the accessor reads ZERO
# immediately after the largest abandonment event in the system. Every device measurement of
# "how much history did the wallet write off" taken through it was unsound.
#
# The fix is additive: BRPeerManagerAbandonedHeightsTotal accumulates, on the manager, the count
# the ledger itself reports as dropped. The old accessor is deliberately left alone — its span
# semantics are pinned by cf_scan_ledger_drive_kat and the UI already refuses to render it.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DABANDON_TOTAL_UNFIXED): the accumulation is compiled out, the total stays 0,
#     and the "total SURVIVES the ledger re-Init" assertion MUST FAIL. This build MUST FAIL.
#   * FIXED (no flag): MUST PASS.
#
# The red arm must fail on that SPECIFIC assertion, never merely on a nonzero exit — a kill, an
# ASan abort, a timeout or a segfault all exit nonzero and would fake a red arm.
#
# DETERMINISTIC — no threads, no timing, no network. Fails 100% of the time when unfixed.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

# NOTE: BRPeerManager.c is NOT listed. cf_abandon_total_kat_main.c #include-s it directly to
# reach the static surfacing funnel and the manager's private fields; passing it again would be
# a duplicate-symbol link error.
build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/cf_abandon_total_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
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

# LeakSanitizer OFF for both arms, matching the sibling KATs: a manager built without a full
# network teardown reports leaks and exits nonzero on its own, which would mask the arm result
# entirely (the red arm would "fail" for the wrong reason and the gate would reject it).
export ASAN_OPTIONS=detect_leaks=0

echo "=== building UNFIXED (red arm: accumulation compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DABANDON_TOTAL_UNFIXED; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed"; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must FAIL, and for the RIGHT REASON ----"
RED_OUT="$BUILD_DIR/red.out"
if "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build PASSED. The counter accumulated with the accumulation"
    echo "compiled out, so this gate is not exercising the defect it exists for."
    exit 1
fi
if ! grep -q "^FAIL: event 2 FIX: the total SURVIVES the ledger re-Init" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build failed, but NOT on the survives-re-Init assertion."
    echo "A crash, an ASan abort or a setup failure exits nonzero too and would fake a red arm."
    exit 1
fi
echo "RED confirmed: without the accumulation the total is wiped by the ledger re-Init (expected)."
grep -m1 "^FAIL: event 2 FIX" "$RED_OUT" | sed 's/^/    /'

echo
echo "---- GREEN ARM: must PASS ----"
GREEN_OUT="$BUILD_DIR/green.out"
if ! "$BUILD_DIR/kat_fixed" > "$GREEN_OUT" 2>&1; then
    cat "$GREEN_OUT"
    echo
    echo "GATE FAILURE: the FIXED build FAILED."
    exit 1
fi
cat "$GREEN_OUT"
echo
echo "ALL PASS"
