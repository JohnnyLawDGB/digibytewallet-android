#!/usr/bin/env bash
# Host KAT runner for the CHECKPOINT ALIASING use-after-free.
#
# BRPeerManagerNewEx adds the SAME BRMerkleBlock* to manager->checkpoints AND manager->blocks, but
# only `blocks` governs its lifetime: _BRPeerManagerClearMemory frees what it prunes without
# consulting `checkpoints`. Safe while pruning trailed the chain tip -- FALSE under CF-era pruning,
# where the retention floor follows the compact-filter SCAN frontier. Once the scan passes a
# checkpoint height, that checkpoint is freed and manager->checkpoints dangles; the next relayed
# header dereferences it via BRSetGet -> _BRBlockHeightEq.
#
# Found by ASan on-device 2026-08-06, on a FRESH install, three minutes into first sync:
#   _peerThreadRoutine -> _BRPeerAcceptMessage -> _BRPeerAcceptHeadersMessage -> _peerRelayedBlock
#   -> _BRPeerManagerVerifyBlock (:2114) -> BRSetGet -> _BRBlockHeightEq (:227)
# NOT restore-specific: ordinary header processing, every user.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DCHECKPOINT_ALIAS_UNFIXED): frees unconditionally. ASan MUST report
#     heap-use-after-free. This build MUST FAIL.
#   * FIXED (no flag): the checkpoint-identity guard is live. MUST PASS.
#
# DETERMINISTIC -- no threads, no timing, fails 100% of the time when unfixed. The red arm must
# fail on an ASan report or one of the KAT's own assertions, never merely on a nonzero exit.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)


build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/checkpoint_alias_uaf_kat_main.c" \
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

# LeakSanitizer OFF for both arms. This KAT deliberately does not tear the manager down, so LSan
# reports ~900 KB and exits nonzero on its own — which masks the result completely: the red arm
# "failed" for the wrong reason and the gate correctly rejected it. Leaks are not what this gate
# measures.
export ASAN_OPTIONS=detect_leaks=0

echo "=== building UNFIXED (red arm: checkpoint-identity guard compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DCHECKPOINT_ALIAS_UNFIXED; then
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
    echo "GATE FAILURE: the UNFIXED build PASSED. Pruning freed a checkpoint block and nothing"
    echo "noticed — this gate is not exercising the defect it exists for."
    exit 1
fi
# A nonzero exit is NOT sufficient. During development this arm was killed by hand and the
# gate happily proceeded, reporting success — a kill, an ASan abort, a timeout or a segfault
# all exit nonzero and would fake a red arm. Require the SPECIFIC assertion to have failed.
if ! grep -qE "ERROR: AddressSanitizer: heap-use-after-free|\[FAIL\] every checkpoint still readable" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build exited nonzero WITHOUT an ASan use-after-free report"
    echo "or the checkpoint-readability assertion failing. Killed, or died in setup — not a red arm."
    exit 1
fi
grep -E "heap-use-after-free|\[FAIL\]" "$RED_OUT" | head -3 | sed 's/^/  red: /'
echo "(red arm reported use-after-free on a freed checkpoint, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "checkpoint_alias_uaf_kat: RED-BEFORE-GREEN OK"
exit 0
