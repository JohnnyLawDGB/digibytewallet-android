// Host KAT: the merkle tree depth is defined for every 32-bit transaction count.
//
// A merkle block carries its transaction count (totalTx) as a u32, from a peer or from the wallet's
// saved blocks. The partial-tree walks (BRMerkleBlockTxHashes, BRMerkleBlockIsValid) compare each
// node's depth with the depth of a tree over that many leaves. Taken as a signed int, a count of
// 2^31 or more is negative: the depth computation then has signed overflow, and its shift loop never
// ends (an arithmetic shift of a negative value stops at -1, never 0).
//
// CASES (one per process, chosen by argv[1]; each block carries one hash and one flag byte 0x01):
//   top    totalTx = 0x80000000   shipped: both walks return. comparison: a sanitizer report
//                                 (signed overflow), and the walk does not return.
//   all    totalTx = 0xffffffff   shipped: both walks return. comparison: the walk does not return.
//   ctl    totalTx = 1            the honest one-transaction block: one matched hash, valid root,
//                                 in both arms.
// Exit 0 = the case held.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <time.h>

#include "BRMerkleBlock.h"
#include "BRInt.h"

static uint8_t g_buf[256];

// header(80) | totalTx(4) | 0x01 | hash(32) | 0x01 | flags(1)
static size_t blockBytes(uint32_t totalTx, UInt256 leaf) {
    size_t off = 0;
    memset(g_buf, 0, sizeof(g_buf));
    UInt32SetLE(&g_buf[off], 2); off += 4;                 // version
    off += 32;                                             // previous block
    memcpy(&g_buf[off], leaf.u8, 32); off += 32;           // merkle root = the single leaf
    UInt32SetLE(&g_buf[off], 1700000000); off += 4;        // timestamp
    UInt32SetLE(&g_buf[off], 0x1e0fffff); off += 4;        // target
    off += 4;                                              // nonce
    UInt32SetLE(&g_buf[off], totalTx); off += 4;
    g_buf[off++] = 1;
    memcpy(&g_buf[off], leaf.u8, 32); off += 32;
    g_buf[off++] = 1;
    g_buf[off++] = 0x01;
    return off;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: %s top|all|ctl\n", argv[0]); return 2; }
    uint32_t totalTx;
    if (strcmp(argv[1], "top") == 0) totalTx = 0x80000000u;
    else if (strcmp(argv[1], "all") == 0) totalTx = 0xffffffffu;
    else if (strcmp(argv[1], "ctl") == 0) totalTx = 1;
    else { printf("unknown case\n"); return 2; }

    UInt256 leaf; memset(leaf.u8, 0x42, 32);
    size_t len = blockBytes(totalTx, leaf);
    BRMerkleBlock *b = BRMerkleBlockParse(g_buf, len);
    if (! b) { printf("CASE FAIL %s: parse refused the block\n", argv[1]); return 1; }
    printf("  parsed: totalTx=%u hashes=%zu flagsLen=%zu\n", b->totalTx, b->hashesCount, b->flagsLen);

    size_t matched = BRMerkleBlockTxHashes(b, NULL, 0);
    printf("  matched hashes: %zu\n", matched);
    int valid = BRMerkleBlockIsValid(b, (uint32_t)time(NULL));
    printf("  merkle walk returned (valid=%d)\n", valid);

    int ok = 1;
    if (totalTx == 1) {
        UInt256 out[2];
        ok = (matched == 1 && BRMerkleBlockTxHashes(b, out, 2) == 1 && UInt256Eq(out[0], leaf));
        printf("  %s the honest block yields its one hash\n", ok ? "ok  " : "FAIL");
    }
    else {
        ok = (matched <= b->hashesCount);
        printf("  %s the walks returned, matching no more hashes than the block carries\n", ok ? "ok  " : "FAIL");
    }
    BRMerkleBlockFree(b);
    printf(ok ? "CASE PASS %s\n" : "CASE FAIL %s\n", argv[1]);
    return ok ? 0 : 1;
}
