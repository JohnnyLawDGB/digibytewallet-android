#!/usr/bin/env bash
# Host KAT runner for the PONG-QUEUE DATA RACE fix (BRPeer.c pongLock).
#
# WHAT IT PROVES. BRPeerContext::pongInfo / ::pongCallback are BRArray buffers appended from
# the manager/keepalive thread (BRPeerSendPing / BRPeerSendPingProbe) and drained from the peer
# thread (_BRPeerAcceptPongMessage and the disconnect teardown loop). array_add() reallocs --
# moving the base pointer and freeing the old block -- while array_rm()'s shift loop re-reads
# array_count() out of a header at [-1] on every iteration. Unsynchronized, a push can free the
# buffer a concurrent drain is walking. That is the 2026-08-03 19:45 SIGSEGV at BRPeer.c:1487
# (bogus ~235,708 count in the loop registers, walk off the end into an unmapped page).
#
# The KAT main #includes BRPeer.c directly to reach the file-static _BRPeerPongPush /
# _BRPeerPongPop and the opaque BRPeerContext, so BRPeer.c is deliberately NOT ALSO passed as a
# separate compilation unit -- every symbol would be defined twice and the link would fail.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Built TWICE:
#   * UNFIXED (-DPONG_LOCK_UNFIXED): PONG_LOCK/PONG_UNLOCK compile to no-ops, restoring the
#     exact shipped-before shape. This build MUST FAIL. If it passes, the gate proves nothing.
#   * FIXED (no flag): the lock is live. This build MUST PASS.
#
# The red arm must fail on the SPECIFIC evidence -- an ASan report or one of this KAT's own
# assertions -- not merely on a nonzero exit. A killed or crashed-for-other-reasons process
# also exits nonzero, and accepting that once let a slow arm masquerade as a passing gate.
#
# ASan, not TSan, is the sanitizer here: the failure is a real use-after-free / overflow, which
# ASan reports with a concrete allocation, and ASan is what the rest of this suite already uses.
# The (callback, info) pairing assertion covers the arm ASan cannot see -- two arrays observed
# out of step while both are still live memory.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build_arm() {
    # $1 = output binary, rest = extra flags
    local out="$1"; shift
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_peer_pong_race_kat_main.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- VERIFY THE SEAM IS REAL -------------------------------------------------------------
# -D silently loses to a plain #define in a header, and -w hides the redefinition warning.
# That has already produced a KAT that printed "harness-scaled" while running production
# constants. Confirm PONG_LOCK is guarded by #ifdef before trusting the red arm.
if ! grep -q '^#ifdef PONG_LOCK_UNFIXED' "$CORE_DIR/BRPeer.c"; then
    echo "GATE FAILURE: BRPeer.c has no '#ifdef PONG_LOCK_UNFIXED' seam."
    echo "Without it -DPONG_LOCK_UNFIXED is inert and the red arm would pass for the wrong reason."
    exit 1
fi

# ---- RED ARM -----------------------------------------------------------------------------
echo "=== RED ARM (-DPONG_LOCK_UNFIXED) -- must FAIL ==="
if ! build_arm "$BUILD_DIR/red" -DPONG_LOCK_UNFIXED; then
    echo "GATE FAILURE: the UNFIXED arm did not compile. A build error is not evidence of the defect."
    exit 1
fi

# The race is nondeterministic; retry a bounded number of times before declaring the gate weak.
RED_TRIPPED=0
for attempt in 1 2 3 4 5; do
    red_out="$("$BUILD_DIR/red" 2>&1)"; red_rc=$?
    if echo "$red_out" | grep -qE "ERROR: AddressSanitizer|ASSERTION FAILED"; then
        echo "  attempt $attempt: tripped as required (rc=$red_rc)"
        echo "$red_out" | grep -E "ERROR: AddressSanitizer|ASSERTION FAILED" | head -3 | sed 's/^/    /'
        RED_TRIPPED=1
        break
    fi
    echo "  attempt $attempt: did not trip (rc=$red_rc)"
done

if [ "$RED_TRIPPED" -ne 1 ]; then
    echo "GATE FAILURE: the UNFIXED arm survived 5 attempts without an ASan report or a failed"
    echo "assertion. Either the seam is inert or the harness does not exercise the race hard"
    echo "enough -- in both cases the green arm below proves nothing."
    exit 1
fi

# ---- GREEN ARM ---------------------------------------------------------------------------
echo
echo "=== GREEN ARM (fixed) -- must PASS ==="
if ! build_arm "$BUILD_DIR/green"; then
    echo "GATE FAILURE: the FIXED arm did not compile."
    exit 1
fi

# Run it several times: passing once tells you little about a race.
for attempt in 1 2 3; do
    green_out="$("$BUILD_DIR/green" 2>&1)"; green_rc=$?
    echo "$green_out" | sed 's/^/  /'
    if [ "$green_rc" -ne 0 ] || echo "$green_out" | grep -qE "ERROR: AddressSanitizer|ASSERTION FAILED"; then
        echo "GATE FAILURE: the FIXED arm failed on attempt $attempt (rc=$green_rc)."
        exit 1
    fi
done

echo
echo "cf_peer_pong_race_kat: GATE PASSED (red tripped, green clean x3)"
exit 0
