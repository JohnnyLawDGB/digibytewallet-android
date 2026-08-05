#!/usr/bin/env bash
# Host KAT runner for the FD_SET-overflow fix (BRPeer.c: select() -> poll()).
#
# WHAT IT PROVES. _BRPeerOpenSocket waited on an in-progress connect() with select()/FD_SET. An
# fd_set is a fixed 1024-bit bitmap indexed by descriptor NUMBER, so FD_SET on a descriptor >=
# FD_SETSIZE writes past a 128-byte stack object. Android FORTIFY turns that into __fortify_fatal
# and the wallet ABORTS -- observed twice on a Note 8 (2026-08-03 06:28, 2026-08-04 07:54), both
# tombstones reading abort <- __fortify_fatal <- __FD_SET_chk <- _BRPeerOpenSocket.
#
# The abort fires at descriptor number 1024 while the device rlimit is 32768, so the app dies from
# descriptor pressure with no EMFILE and no warning of any kind.
#
# The KAT occupies every descriptor below FD_SETSIZE so the one under test is guaranteed to be
# above it. DETERMINISTIC -- no race, no timing, fails 100% of the time when unfixed.
#
# The KAT main #includes BRPeer.c directly to reach the file-static _BRPeerWaitConnect, so
# BRPeer.c is deliberately NOT ALSO passed as a separate compilation unit.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DFDSET_UNFIXED): the select()/FD_SET shape is restored. ASan must report
#     stack-buffer-overflow on the fd_set. This build MUST FAIL.
#   * FIXED (no flag): poll(). MUST PASS.
#
# The red arm must fail on the SPECIFIC evidence -- an ASan report or one of the KAT's own
# assertions -- not merely a nonzero exit, since a process that dies for an unrelated reason also
# exits nonzero.
#
# A "SKIP:" result is NOT a pass and NOT a failure: it means the environment could not hand out a
# descriptor above FD_SETSIZE (RLIMIT_NOFILE too low). The gate treats a skipped RED arm as a gate
# failure, because a red arm that never ran proves nothing.
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
        "$SCRIPT_DIR/peer_fdset_overflow_kat_main.c" \
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
if ! grep -q '^#ifdef FDSET_UNFIXED' "$CORE_DIR/BRPeer.c"; then
    echo "GATE FAILURE: BRPeer.c has no '#ifdef FDSET_UNFIXED' seam."
    echo "Without it -DFDSET_UNFIXED is inert and the red arm would pass for the wrong reason."
    exit 1
fi

# ---- RED ARM -----------------------------------------------------------------------------
echo "=== RED ARM (-DFDSET_UNFIXED) -- must FAIL ==="
if ! build_arm "$BUILD_DIR/red" -DFDSET_UNFIXED; then
    echo "GATE FAILURE: the UNFIXED arm did not compile. A build error is not evidence of the defect."
    exit 1
fi

# The race is nondeterministic; retry a bounded number of times before declaring the gate weak.
RED_TRIPPED=0
for attempt in 1 2 3 4 5; do
    red_out="$("$BUILD_DIR/red" 2>&1)"; red_rc=$?
    if echo "$red_out" | grep -q "^SKIP:"; then
        echo "  attempt $attempt: SKIPPED (environment cannot allocate above FD_SETSIZE)"
        echo "$red_out" | sed 's/^/    /'
        break
    fi
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
echo "peer_fdset_overflow_kat: GATE PASSED (red tripped, green clean x3)"
exit 0
