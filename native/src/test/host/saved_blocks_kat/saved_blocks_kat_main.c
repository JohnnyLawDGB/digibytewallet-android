// Host KAT for deserialize_saved_blocks_guarded (saved_blocks_deserialize.h),
// extracted from loadSavedBlocks (jni_peer.c, Task 1 of the Pixel-startup-
// hardening plan, .superpowers/sdd/task-1-brief.md).
//
// The suspected top crash-on-every-launch bug: a corrupt/truncated persisted
// `saved_blocks` blob carries an absurd leading 4-byte block count (e.g.
// 0xFFFFFFFF). The pre-fix code called
// `malloc(count * sizeof(BRMerkleBlock *))` unconditionally and never checked
// the result -- on a memory-constrained device that malloc call fails
// (returns NULL), and the very first `blocks[loaded++] = block` write is a
// NULL-pointer dereference (SIGSEGV). Because the same corrupt blob is
// reloaded on every app launch, this crashes the app permanently.
//
// This KAT reproduces the failure condition deterministically on ANY host
// (regardless of how much free RAM it has) by capping the process's virtual
// memory with `ulimit -v` in run.sh before invoking the binary, so a
// multi-gigabyte malloc() request reliably returns NULL here exactly as it
// would on a real memory-constrained device. See run.sh for the historical
// RED (crash, pre-guard) / GREEN (safe return 0, post-guard) demonstration
// this test was built against.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRMerkleBlock.h"
#include "saved_blocks_deserialize.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static void putLE32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v);       p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

int main(void) {
    // ---- Test 1: corrupt count (0xFFFFFFFF) + one real trailing 80-byte
    // block record. The trailing record matters: with NO trailing data the
    // pre-existing `pos + 8 <= len` loop bound would skip the loop body
    // entirely regardless of count, masking the bug. With one real record
    // present, pre-fix code enters the loop and writes into the (failed)
    // allocation -- this is the exact shape of a real corrupted
    // saved_blocks blob (a genuine trailing block, just a mangled count
    // header).
    {
        uint8_t buf[4 + 4 + 4 + 80];
        putLE32(&buf[0], 0xFFFFFFFFu);      // corrupt count
        putLE32(&buf[4], 80);               // blockLen
        putLE32(&buf[8], 12345);            // height
        memset(&buf[12], 0x42, 80);         // 80-byte header, content irrelevant to parse

        BRMerkleBlock **blocks = (BRMerkleBlock **)0x1; // sentinel, must be reset to NULL
        size_t loaded = deserialize_saved_blocks_guarded(buf, sizeof(buf), &blocks);
        check(loaded == 0, "corrupt count (0xFFFFFFFF) rejected: 0 blocks loaded");
        check(blocks == NULL, "corrupt count (0xFFFFFFFF) rejected: outBlocks is NULL");
    }

    // ---- Test 2: count == 0 is also rejected (explicit guard branch).
    {
        uint8_t buf[4];
        putLE32(&buf[0], 0);
        BRMerkleBlock **blocks = (BRMerkleBlock **)0x1;
        size_t loaded = deserialize_saved_blocks_guarded(buf, sizeof(buf), &blocks);
        check(loaded == 0, "count == 0 rejected: 0 blocks loaded");
        check(blocks == NULL, "count == 0 rejected: outBlocks is NULL");
    }

    // ---- Test 3: well-formed buffer still loads correctly (guard doesn't
    // break the happy path).
    {
        uint8_t buf[4 + 4 + 4 + 80];
        putLE32(&buf[0], 1);                // count
        putLE32(&buf[4], 80);               // blockLen
        putLE32(&buf[8], 777);              // height
        memset(&buf[12], 0x11, 80);

        BRMerkleBlock **blocks = NULL;
        size_t loaded = deserialize_saved_blocks_guarded(buf, sizeof(buf), &blocks);
        check(loaded == 1, "well-formed buffer: 1 block loaded");
        check(blocks != NULL && blocks[0] != NULL, "well-formed buffer: block pointer non-NULL");
        if (blocks && blocks[0]) {
            check(blocks[0]->height == 777, "well-formed buffer: height round-trips");
            BRMerkleBlockFree(blocks[0]);
        }
        free(blocks);
    }

    // ---- Test 4: declared count (5) exceeds available trailing data (only
    // 1 full record fits) -- the pre-existing per-iteration `pos + 8 <= len`
    // bound (preserved by this extraction) must still stop the loop early
    // and return the partial count, not run off the end of the buffer.
    {
        uint8_t buf[4 + 4 + 4 + 80];
        putLE32(&buf[0], 5);                // count says 5, only 1 fits
        putLE32(&buf[4], 80);
        putLE32(&buf[8], 42);
        memset(&buf[12], 0x22, 80);

        BRMerkleBlock **blocks = NULL;
        size_t loaded = deserialize_saved_blocks_guarded(buf, sizeof(buf), &blocks);
        check(loaded == 1, "declared count (5) > available data: bounded to 1 actually-loaded block");
        if (blocks && blocks[0]) BRMerkleBlockFree(blocks[0]);
        free(blocks);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
