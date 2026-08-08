#!/usr/bin/env bash
# Host KAT runner for the DISCONNECT LEDGER's close classification.
#
# THE QUESTION THIS GATE PROTECTS. "Are peers evicting us, or are we hanging up on
# ourselves?" decides whether .onion adoption is the fix for peer churn or a regression.
# It is answered by the close histogram, and the histogram is only as good as the
# classification behind it.
#
# THE DEFECT. Both socket read loops mapped read()==0 (an ORDERLY FIN from the remote) onto
# ECONNRESET — the same value a hard reset produces. DigiByte Core's inbound eviction closes
# ORDERLY (AttemptToEvictConnection -> CloseSocketDisconnect), so eviction and reset were
# literally the same observation and the eviction question was unanswerable from our logs.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DPEER_CLOSE_LEDGER_UNFIXED): read()==0 classifies as PEER_RST, collapsing
#     onto the reset case, and the "orderly close is distinguishable" assertion MUST FAIL.
#     This build MUST FAIL.
#   * FIXED (no flag): MUST PASS.
#
# The red arm must fail on that SPECIFIC assertion, never merely on a nonzero exit — a
# kill, an ASan abort, a timeout or a segfault all exit nonzero and would fake a red arm.
#
# DETERMINISTIC — the classifier is pure, so there are no threads, sockets or timing here.
# Fails 100% of the time when unfixed.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

# BRPeer.c is #include-d by the KAT (not passed here — that would be a duplicate-symbol link
# error) so the lifetime assertions can reach BRPeerContext and simulate the ping stopwatch
# resetting mid-connection. BRPeerManager.c is deliberately absent: it would drag the whole SPV
# stack in, and nothing under test lives there.
build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        \
        "$@" \
        -fsanitize=address,undefined -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_close_ledger_kat_main.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRAssetData.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "$CORE_DIR"/crypto/sha3/*.c \
        -lm -lpthread \
        -o "$out"
}

# LeakSanitizer OFF for both arms, matching the sibling KATs — an unrelated leak would exit
# nonzero on its own and mask the arm result entirely.
export ASAN_OPTIONS=detect_leaks=0

echo "=== building UNFIXED-CMD12 (red arm: 12-char command names rejected) ==="
if ! build "$BUILD_DIR/kat_unfixed_cmd" -DPEER_CMD12_UNFIXED; then
    echo "BUILD FAILURE (cmd12 red arm) — gate cannot run"; exit 1
fi
echo "=== building UNFIXED-LIFETIME (red arm: lifetime read off the ping stopwatch) ==="
if ! build "$BUILD_DIR/kat_unfixed_life" -DPEER_LIFETIME_UNFIXED; then
    echo "BUILD FAILURE (lifetime red arm) — gate cannot run"; exit 1
fi
echo "=== building UNFIXED (red arm: FIN collapsed onto RST) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPEER_CLOSE_LEDGER_UNFIXED; then
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
    echo "GATE FAILURE: the UNFIXED build PASSED. An orderly FIN was distinguished from a"
    echo "reset with the conflation compiled IN, so this gate is not exercising the defect."
    exit 1
fi
if ! grep -q "^FAIL: an ORDERLY close (FIN) is distinguishable from a RESET" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build failed, but NOT on the FIN-vs-RST assertion."
    echo "A crash, an ASan abort or a setup failure exits nonzero too and would fake a red arm."
    exit 1
fi
echo "RED confirmed: with the conflation compiled in, an eviction is indistinguishable"
echo "from a network reset (expected)."
grep -m1 "^FAIL: an ORDERLY close" "$RED_OUT" | sed 's/^/    /'

echo
echo "---- RED ARM 2 (lifetime): must FAIL, and for the RIGHT REASON ----"
RED2_OUT="$BUILD_DIR/red2.out"
if "$BUILD_DIR/kat_unfixed_life" > "$RED2_OUT" 2>&1; then
    cat "$RED2_OUT"
    echo
    echo "GATE FAILURE: the lifetime UNFIXED build PASSED. A 120s connection reported its real"
    echo "lifetime while measuring from the ping stopwatch, so this arm proves nothing."
    exit 1
fi
if ! grep -q "^FAIL: a 120s connection reports its real lifetime after a ping reset startTime" "$RED2_OUT"; then
    cat "$RED2_OUT"
    echo
    echo "GATE FAILURE: the lifetime UNFIXED build failed, but NOT on the lifetime assertion."
    exit 1
fi
echo "RED confirmed: measured off the ping stopwatch, a 120s connection reports ~0s (expected)."
grep -m1 "\[lifetime\]" "$RED2_OUT" | sed 's/^/    /'

echo
echo "---- RED ARM 3 (12-char command): must FAIL, and for the RIGHT REASON ----"
RED3_OUT="$BUILD_DIR/red3.out"
if "$BUILD_DIR/kat_unfixed_cmd" > "$RED3_OUT" 2>&1; then
    cat "$RED3_OUT"
    echo
    echo "GATE FAILURE: the cmd12 UNFIXED build PASSED — a full-12 command name was accepted"
    echo "by the old NUL-terminator test, so this arm proves nothing."
    exit 1
fi
if ! grep -q "^FAIL: a legal 12-character command name (no NUL terminator) is ACCEPTED" "$RED3_OUT"; then
    cat "$RED3_OUT"
    echo
    echo "GATE FAILURE: the cmd12 UNFIXED build failed, but NOT on the 12-char command assertion."
    exit 1
fi
echo "RED confirmed: the old NUL-terminator test rejects a legal 12-character command (expected)."
grep -m1 "\[command\]" "$RED3_OUT" | sed 's/^/    /'

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
