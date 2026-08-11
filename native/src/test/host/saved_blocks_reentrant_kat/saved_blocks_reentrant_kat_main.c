// Host KAT proving the g_savedBlocks re-entrant free is safe across a
// startSync ownership-transfer-to-peer-manager cycle -- Task 1 of the
// recreate-resumes-near-tip plan
// (.superpowers/sdd/2026-08-11-recreate-resumes-near-tip/task-1-brief.md).
//
// It mirrors (does NOT #include) two real jni_peer.c call sites -- jni_peer.c
// pulls in <jni.h>/<android/log.h>, which don't exist on a host build; that's
// why the sibling saved_blocks_kat already drives the extracted
// saved_blocks_deserialize.h core instead of jni_peer.c itself (see that
// KAT's run.sh). This KAT does the same, plus mirrors the two sites' control
// flow verbatim so a regression in either one is caught:
//
//   - startSync's ownership-transfer free (jni_peer.c:862-891): after
//     BRPeerManagerNewEx adopts g_savedBlocks' BRMerkleBlock* elements (they
//     become manager->blocks/orphans entries, freed individually as the
//     manager prunes), the bridge frees ONLY the array and -- on the
//     production shape -- NULLs g_savedBlocks / zeroes g_savedBlocksCount so
//     the array is never dereferenced again.
//   - loadSavedBlocks' re-entrant "free any previously loaded blocks" loop
//     (jni_peer.c:1179-1195): safe ONLY because startSync already NULLed the
//     pointer. If it left g_savedBlocks dangling (non-NULL, stale count) after
//     a transfer whose elements the manager already freed, this loop would
//     double-free every one of them.
//
// The exact defect class ASan caught on-device 2026-08-05: 21
// heap-buffer-overflows + 4 heap-use-after-frees, all on 192-byte regions
// (== sizeof(BRMerkleBlock)).
//
// This KAT drives: load -> simulated manager ownership transfer -> load AGAIN
// (a second in-process recreate, e.g. the 0-peer/network-loss recovery path
// this plan is about) -- and proves:
//
//   production (default build):        transfer NULLs g_savedBlocks (mirrors
//                                       jni_peer.c:889-891) -> the second
//                                       load's reentrant free is a no-op ->
//                                       ASan-clean, fresh blocks load.
//   -DSAVED_BLOCKS_REENTRANT_UNFIXED:   transfer omits the NULL-out (the
//                                       regression this KAT guards against)
//                                       -> the second load's reentrant free
//                                       walks the same, already manager-freed
//                                       elements again -> ASan double-free.
//
// simulateManagerOwnershipTransfer() frees every adopted element immediately,
// standing in for "the manager eventually frees everything it adopted" (real
// BRPeerManager prunes/frees incrementally as new blocks arrive, and at
// teardown) -- an immediate free is the deterministic worst case: it is what
// makes the double-free reproduce on every run instead of depending on
// prune timing, and is exactly the shape needed to prove the invariant this
// task establishes (the array must never be touched again after transfer,
// full stop, regardless of when the manager gets around to freeing what it
// adopted).
//
// Compiler: clang, -include stdint.h, -fsanitize=address (same conventions as
// the sibling saveblocks_race_kat and cf_confirm_kat's red-arm builds).
// run.sh builds BOTH shapes and requires UNFIXED=red (ASan double-free) AND
// production=green -- the red-before-green heuristic, enforced structurally.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "BRMerkleBlock.h"
#include "saved_blocks_deserialize.h"

// Mirrors jni_peer.c's globals (g_savedBlocks / g_savedBlocksCount).
static BRMerkleBlock **g_savedBlocks = NULL;
static size_t g_savedBlocksCount = 0;

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static void putLE32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v);       p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

// Builds a well-formed saved_blocks blob with `n` 80-byte-header blocks,
// heights 1000+i (same wire shape as saved_blocks_kat_main.c's fixtures).
static size_t makeBuf(uint8_t *buf, size_t cap, uint32_t n) {
    size_t pos = 0;
    putLE32(&buf[pos], n); pos += 4;
    for (uint32_t i = 0; i < n; i++) {
        putLE32(&buf[pos], 80); pos += 4;         // blockLen
        putLE32(&buf[pos], 1000 + i); pos += 4;   // height
        memset(&buf[pos], (int)(0x10 + i), 80);
        pos += 80;
    }
    if (pos > cap) abort();
    return pos;
}

// Mirrors loadSavedBlocks' "free any previously loaded blocks" reentrant free
// (jni_peer.c:1179-1195) followed by the deserialize call, verbatim shape.
static void reentrantFreeAndReload(const uint8_t *buf, size_t len) {
    if (g_savedBlocks) {
        for (size_t i = 0; i < g_savedBlocksCount; i++) {
            if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);
        }
        free(g_savedBlocks);
        g_savedBlocks = NULL;
        g_savedBlocksCount = 0;
    }
    g_savedBlocksCount = deserialize_saved_blocks_guarded(buf, len, &g_savedBlocks);
}

// Mirrors startSync's ownership-transfer (jni_peer.c:862-891): the peer
// manager adopts the elements (and -- modeled here as immediate, see file
// header -- frees them itself, exactly as BRPeerManager's prune path
// eventually does), while the bridge frees ONLY the array it malloc'd.
static void simulateManagerOwnershipTransfer(void) {
    if (!g_savedBlocks) return;
    for (size_t i = 0; i < g_savedBlocksCount; i++) {
        if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);   // "manager" adopts + frees
    }
    free(g_savedBlocks);            /* array only -- mirrors jni_peer.c:889 */
#ifndef SAVED_BLOCKS_REENTRANT_UNFIXED
    g_savedBlocks = NULL;           /* jni_peer.c:890 -- the guard under test */
    g_savedBlocksCount = 0;         /* jni_peer.c:891 */
#endif
    // UNFIXED: deliberately omit the NULL-out above, leaving g_savedBlocks
    // dangling (non-NULL, g_savedBlocksCount unchanged) -- the regression this
    // KAT guards against.
}

int main(void) {
    // Unbuffered stdout: run.sh greps the redirected-to-file log to confirm
    // WHERE a fault happened (must be at/after test3, not before test1) --
    // stdout is fully buffered (not line-buffered) when it's a file rather
    // than a tty, so without this an ASan abort() can discard already-printed
    // PASS lines that never made it out of the libc buffer.
    setvbuf(stdout, NULL, _IONBF, 0);

    uint8_t buf1[4 + 3 * (4 + 4 + 80)];
    size_t len1 = makeBuf(buf1, sizeof buf1, 3);

    // ---- test1: first load populates 3 blocks from a clean state ----
    reentrantFreeAndReload(buf1, len1);
    check(g_savedBlocksCount == 3, "test1: first load: 3 blocks loaded");
    check(g_savedBlocks != NULL, "test1: first load: g_savedBlocks non-NULL");

    // ---- test2: simulated startSync ownership transfer to the peer manager ----
    simulateManagerOwnershipTransfer();
#ifndef SAVED_BLOCKS_REENTRANT_UNFIXED
    check(g_savedBlocks == NULL, "test2: after transfer, g_savedBlocks is NULL (production shape)");
    check(g_savedBlocksCount == 0, "test2: after transfer, g_savedBlocksCount is 0 (production shape)");
#endif

    // ---- test3: reentrant load (a second in-process recreate) must NOT
    // double-free the manager-owned elements, and must repopulate fresh
    // blocks from a second buffer. On the UNFIXED build this is where ASan
    // catches the double-free (g_savedBlocks[i] were already freed by
    // simulateManagerOwnershipTransfer above). ----
    uint8_t buf2[4 + 2 * (4 + 4 + 80)];
    size_t len2 = makeBuf(buf2, sizeof buf2, 2);
    reentrantFreeAndReload(buf2, len2);
    check(g_savedBlocksCount == 2, "test3: reentrant load: 2 fresh blocks loaded, no double-free");
    check(g_savedBlocks != NULL, "test3: reentrant load: g_savedBlocks non-NULL");
    if (g_savedBlocks && g_savedBlocks[0]) {
        check(g_savedBlocks[0]->height == 1000, "test3: reentrant load: fresh block height round-trips");
    }

    // Tidy up the final load (ASAN_OPTIONS in run.sh disables leak detection
    // for this KAT -- only double-free/UAF is under test -- but clean up
    // anyway rather than relying on that).
    if (g_savedBlocks) {
        for (size_t i = 0; i < g_savedBlocksCount; i++) {
            if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);
        }
        free(g_savedBlocks);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
