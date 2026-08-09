#!/usr/bin/env bash
# Host KAT runner for P1: checkpoint-stub replacement frees the displaced block on a path where
# manager->checkpoints was NOT repointed (the only guard was assert(), a no-op under NDEBUG).
#
# _BRPeerManagerInstallSavedBlock and the equal-hash relay site in _peerRelayedBlock both verify
# membership in manager->blocks by HASH but repoint manager->checkpoints by HEIGHT. When the
# incoming block's height disagrees with the resident checkpoint stub's height (a corrupt
# persisted height -- saved_blocks_deserialize.h assigns block->height straight from the blob,
# no cross-check), BRSetAdd(manager->checkpoints, block) inserts under a DIFFERENT key instead of
# displacing the stub, and BRMerkleBlockFree(replaced) then frees a block manager->checkpoints
# STILL holds -- a dangling pointer in a set nothing ever removes from, read on every relayed
# header via _BRPeerManagerVerifyBlock's BRSetGet -> _BRBlockHeightEq. Same 192-byte
# (sizeof(BRMerkleBlock)) checkpoints-set UAF class as CHECKPOINT_ALIAS_UNFIXED, different trigger.
#
# THE FIX: gate the free on the repoint having actually landed (replaced->height == block->height
# AND the checkpoints BRSetAdd genuinely returned `replaced`), at runtime -- not just via assert().
# Worst case degrades to the pre-diff LEAK (the stub is never freed, only ever resident in
# manager->checkpoints) instead of a dangling pointer. A leak is safe; a dangling pointer read on
# every relayed header is not.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
#   * UNFIXED (-DCHECKPOINT_STUB_FREE_UNGUARDED_UNFIXED): both sites repoint-by-height and free
#     unconditionally. ASan MUST report heap-use-after-free when the checkpoints entry is read
#     back. This build MUST FAIL.
#   * FIXED (no flag): the runtime repoint-success guard is live at both sites. MUST PASS.
#
# DETERMINISTIC -- no threads, no timing, fails 100% of the time when unfixed.
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
        "$SCRIPT_DIR/checkpoint_stub_free_guard_kat_main.c" \
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

# LeakSanitizer OFF for both arms. The FIXED arm deliberately degrades a height-mismatched
# checkpoint-stub replacement to a LEAK (the stub is spared but never freed -- it's only resident
# in manager->checkpoints, and BRSetFree releases just the hash table, never the items). That leak
# is the correct, intended behavior under test, not a bug this gate should catch.
export ASAN_OPTIONS=detect_leaks=0

# -DNDEBUG on BOTH arms: this mirrors the shipped APK (AGP release => -O2 -DNDEBUG, see
# native/CMakeLists.txt) where assert() compiles to nothing. The UNFIXED arm needs this to reach
# the actual free (with asserts live, assert(checkpoint == replaced) aborts BEFORE the free —
# a real safety net in a debug host build, but not what ships, and not the ASan UAF this gate
# exists to catch). The FIXED arm's guard is a runtime `if`, not an assert, so it is correct with
# or without NDEBUG — building it the same way just keeps both arms comparable.
echo "=== building UNFIXED (red arm: repoint-success guard compiled out at both sites) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DCHECKPOINT_STUB_FREE_UNGUARDED_UNFIXED -DNDEBUG; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed" -DNDEBUG; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must FAIL, and for the RIGHT REASON ----"
RED_OUT="$BUILD_DIR/red.out"
if "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build PASSED. A height-mismatched checkpoint-stub replacement"
    echo "freed a block manager->checkpoints still holds and nothing noticed — this gate is not"
    echo "exercising the defect it exists for."
    exit 1
fi
# A nonzero exit alone is not sufficient (a kill, an unrelated abort, or a segfault in setup would
# all exit nonzero and fake a red arm). Require the SPECIFIC failure mode.
if ! grep -qE "ERROR: AddressSanitizer: heap-use-after-free|\[FAIL\] checkpoints\[stubHeight\]" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build exited nonzero WITHOUT an ASan use-after-free report or"
    echo "the checkpoints-identity assertion failing. Killed, or died in setup — not a red arm."
    exit 1
fi
grep -E "heap-use-after-free|\[FAIL\]" "$RED_OUT" | head -5 | sed 's/^/  red: /'
echo "(red arm reported use-after-free on a freed checkpoint stub, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "checkpoint_stub_free_guard_kat: RED-BEFORE-GREEN OK"
exit 0
