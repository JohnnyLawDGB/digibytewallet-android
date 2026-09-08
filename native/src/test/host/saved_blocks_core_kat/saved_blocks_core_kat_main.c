// Host KAT: proves BRSavedBlocks.h -- parsing the persisted saved-blocks blob
// back into BRMerkleBlocks, and the guard that stands between a corrupt blob
// and an unrecoverable boot loop.
//
// The defect: a corrupt or truncated blob carries an absurd leading 4-byte
// count. Sizing `malloc(count * sizeof(BRMerkleBlock *))` from it and not
// checking the result means that on a memory-constrained device malloc returns
// NULL and the first `blocks[loaded++] = block` write is a NULL dereference --
// on EVERY launch, because the same blob is reloaded each time. That is a boot
// loop only clearable by wiping app data, i.e. by losing a wallet.
//
// This supersedes saved_blocks_kat, which proved the same guard by running the
// binary under `ulimit -v` so an absurd allocation would fail. That is
// Linux-only -- macOS does not implement the virtual-memory ceiling, and the
// KAT has been failing on this machine for exactly that reason. Intercepting
// malloc is both portable AND a stronger claim: it shows the absurd size is
// never REQUESTED, not merely that requesting it fails.
//
// The interception uses the repo's per-TU -D seam (core 68abf333): the build
// renames this TU's malloc call sites -- including the ones inside the
// header-only function under test -- to __wrap_malloc, while the genuine
// definition keeps its name and is reached through an asm label.
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "BRSavedBlocks.h"

// ---- malloc seam ---------------------------------------------------------

#if defined(__APPLE__)
#  define KAT_REAL_SYM(s) __asm__("_" s)
#else
#  define KAT_REAL_SYM(s) __asm__(s)
#endif
extern void *__real_malloc(size_t n) KAT_REAL_SYM("malloc");

static size_t g_mallocCalls = 0;
static size_t g_lastMallocSize = 0;
static int    g_starveMalloc = 0;

void *__wrap_malloc(size_t n)
{
    g_mallocCalls++;
    g_lastMallocSize = n;
    if (g_starveMalloc) return NULL;
    return __real_malloc(n);
}

static void reset(void) { g_mallocCalls = 0; g_lastMallocSize = 0; g_starveMalloc = 0; }

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// ---- blob construction ---------------------------------------------------

// A minimal serialized block. BRMerkleBlockParse needs a full 80-byte header
// plus the merkle fields; rather than hand-roll one, serialize a real block.
static size_t make_block_bytes(uint8_t *out, size_t cap, uint32_t nonce)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    size_t len;

    b->version = 4;
    b->timestamp = 1500000000 + nonce;
    b->target = 0x1d00ffff;
    b->nonce = nonce;
    b->totalTx = 0;
    b->hashesCount = 0;
    b->flagsLen = 0;
    len = BRMerkleBlockSerialize(b, out, cap);
    BRMerkleBlockFree(b);
    return len;
}

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xff);        p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff); p[3] = (uint8_t)((v >> 24) & 0xff);
}

// [count][ (blockLen, height, bytes) x n ]
static size_t build_blob(uint8_t *out, size_t cap, uint32_t declaredCount, uint32_t realCount,
                         uint32_t firstHeight)
{
    size_t pos = 0;
    uint32_t i;

    put_u32(out + pos, declaredCount); pos += 4;
    for (i = 0; i < realCount; i++) {
        uint8_t block[256];
        size_t blen = make_block_bytes(block, sizeof(block), i + 1);
        if (pos + 8 + blen > cap) break;
        put_u32(out + pos, (uint32_t)blen);      pos += 4;
        put_u32(out + pos, firstHeight + i);     pos += 4;
        memcpy(out + pos, block, blen);          pos += blen;
    }
    return pos;
}

static void free_blocks(BRMerkleBlock **blocks, size_t n)
{
    size_t i;
    for (i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
    free(blocks);
}

int main(void)
{
    uint8_t blob[4096];
    BRMerkleBlock **blocks = NULL;
    size_t len, n;

    // test1 -- a well-formed blob round-trips, and heights are restored. The
    // height is NOT in the serialized block; it is carried alongside, and
    // losing it would resume the wallet at the wrong place.
    reset();
    len = build_blob(blob, sizeof(blob), 3, 3, 24050000);
    n = BRSavedBlocksDeserialize(blob, len, &blocks);
    check(n == 3, "test1: three blocks parse");
    check(blocks != NULL, "test1: array allocated");
    if (blocks && n == 3) {
        check(blocks[0]->height == 24050000 && blocks[2]->height == 24050002,
              "test1: heights restored from the sidecar, not the block bytes");
    } else {
        check(0, "test1: heights restored from the sidecar, not the block bytes");
    }
    free_blocks(blocks, n);

    // test2 -- a zero count is rejected WITHOUT allocating. THE load-bearing
    // case, together with test3: the guard must run before malloc is sized.
    reset();
    put_u32(blob, 0);
    n = BRSavedBlocksDeserialize(blob, 4, &blocks);
    check(n == 0 && blocks == NULL, "test2: a zero count is rejected");
    check(g_mallocCalls == 0, "test2: a zero count never reaches malloc");

    // test3 -- an absurd count is rejected WITHOUT allocating. This is the
    // boot-loop case: 0xFFFFFFFF * 8 is ~34 GB. RED GATE.
    reset();
    put_u32(blob, 0xFFFFFFFFu);
    n = BRSavedBlocksDeserialize(blob, 4, &blocks);
    check(n == 0 && blocks == NULL, "test3: an absurd count is rejected");
    check(g_mallocCalls == 0, "test3: an absurd count never reaches malloc");

    // test4 -- the ceiling is exact on both sides. A boundary that drifts turns
    // a legitimate long-history wallet's blob into a forced re-sync.
    reset();
    put_u32(blob, BR_SAVED_BLOCKS_MAX_COUNT + 1);
    n = BRSavedBlocksDeserialize(blob, 4, &blocks);
    check(n == 0 && blocks == NULL && g_mallocCalls == 0, "test4: MAX_COUNT + 1 is rejected");

    reset();
    put_u32(blob, BR_SAVED_BLOCKS_MAX_COUNT);
    n = BRSavedBlocksDeserialize(blob, 4, &blocks);
    // Accepted, so it allocates -- and then reads nothing, because the buffer
    // holds only the count. A short read, not an overrun.
    check(g_mallocCalls == 1, "test4: MAX_COUNT exactly is accepted and allocates");
    check(n == 0, "test4: ...and yields 0 blocks from a 4-byte buffer");
    free(blocks);

    // test5 -- the allocation null-check. Covered as a GREEN case rather than a
    // second RED gate: without it this line is a NULL dereference, so the
    // pre-fix build would crash rather than report, and a crash is a worse
    // gate signal than a failed assertion.
    reset();
    g_starveMalloc = 1;
    len = build_blob(blob, sizeof(blob), 3, 3, 24050000);
    n = BRSavedBlocksDeserialize(blob, len, &blocks);
    check(n == 0 && blocks == NULL, "test5: a failed allocation fails closed");

    // test6 -- a TRUNCATED blob yields a short read, never an overrun. The
    // count says 5; the bytes hold 2. ASan is what proves the second half.
    reset();
    len = build_blob(blob, sizeof(blob), 5, 2, 24060000);
    n = BRSavedBlocksDeserialize(blob, len, &blocks);
    check(n == 2, "test6: a truncated blob reads only what is there");
    free_blocks(blocks, n);

    // test7 -- degenerate inputs. A caller that lost its buffer must get 0,
    // not a parse of whatever was on the stack.
    reset();
    check(BRSavedBlocksDeserialize(NULL, 100, &blocks) == 0 && blocks == NULL,
          "test7: a NULL buffer yields nothing");
    check(BRSavedBlocksDeserialize(blob, 3, &blocks) == 0 && blocks == NULL,
          "test7: a buffer too short for the count yields nothing");
    check(BRSavedBlocksDeserialize(blob, 100, NULL) == 0,
          "test7: a NULL out-pointer is refused, not dereferenced");
    check(g_mallocCalls == 0, "test7: no degenerate input reaches malloc");

    printf(g_fail ? "\nsaved_blocks_core_kat: FAILED (%d)\n" : "\nsaved_blocks_core_kat: OK\n", g_fail);
    return g_fail ? 1 : 0;
}
