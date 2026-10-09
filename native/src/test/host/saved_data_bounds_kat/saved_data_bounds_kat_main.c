// Host KAT, BB-2026-10-09-harley (F1-F4): a parser of the wallet's OWN saved data
// never reads or writes outside the blob, on 32-bit as on 64-bit; a malformed blob
// is rejected or treated as empty, and a well-formed one round-trips.
//
// THE FOUR PARSERS, reached through the same headers production compiles:
//   F1  BRPeerPenaltyDeserialize       core BRPeerPenalty.h       saved_peer_penalties
//   F2  deserialize_saved_peers_guarded bridge saved_peers_deserialize.h (loadSavedPeers)
//   F3  deserialize_saved_blocks_guarded bridge saved_blocks_deserialize.h (loadSavedBlocks)
//   F4  deserialize_saved_transactions_guarded bridge saved_transactions_deserialize.h
//                                                       (loadSerializedTransactions)
//   +   BRSavedBlocksDeserialize       core BRSavedBlocks.h (not built into the app;
//                                       the same guard, fixed with the same edit)
//
// THE DEFECTS. F1, F3, F4 and BRSavedBlocks.h checked a record length as
// `pos + n > len` or `len < 4 + n * 26`. Both wrap on a 32-bit size_t (the APK ships
// armeabi-v7a), so a count or length read off disk near 2^32 passed the check and
// the walk ran past the blob. F2 sized calloc straight from the 4-byte count with no
// ceiling and no NULL check, then wrote the first record through the result.
//
// MACRO CONVENTION (presence): the comparison arm is -DBB_2026_10_09_HARLEY_UNFIXED,
// which restores the earlier line at each site; the shipped arm defines nothing.
//
// Every blob is copied into a heap buffer of its EXACT length, so AddressSanitizer's
// redzones bracket it and any read past the last byte is a report, not luck.
//
// F2's failed allocation is made deterministic two ways: run.sh caps ASan's
// allocator (max_allocation_size_mb, allocator_may_return_null=1), so a huge count
// gets NULL back exactly as a phone's libc would; and peers_calloc_null routes the
// parser's one calloc through a hook that declines it, for a count that is in range.
//
// Usage: saved_data_bounds_kat <case> | list
// Prints "RESULT <case> pass" or "RESULT <case> fail".
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>

#include "BRInt.h"
#include "BRPeer.h"
#include "BRMerkleBlock.h"
#include "BRTransaction.h"
#include "BRPeerPenalty.h"
#include "BRSavedBlocks.h"
#include "saved_blocks_deserialize.h"
#include "saved_transactions_deserialize.h"

// The peers parser's only allocation goes through this hook (the headers it needs are
// already included above, so nothing else in them is rewritten).
static int g_declineCalloc = 0;
static void *kat_calloc(size_t n, size_t s) { return g_declineCalloc ? NULL : calloc(n, s); }
#define calloc(n, s) kat_calloc(n, s)
#include "saved_peers_deserialize.h"
#undef calloc

static int g_fail = 0;
static void check(int c, const char *d) {
    printf(c ? "PASS: %s\n" : "FAIL: %s\n", d);
    fflush(stdout);
    if (!c) g_fail++;
}

static void putLE16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void putLE32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
static void putLE64(uint8_t *p, uint64_t v) { putLE32(p, (uint32_t)v); putLE32(p + 4, (uint32_t)(v >> 32)); }

// A heap copy of exactly len bytes.
static uint8_t *exact(const uint8_t *src, size_t len) {
    uint8_t *b = malloc(len ? len : 1);
    if (len) memcpy(b, src, len);
    return b;
}

static UInt128 ip4(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    UInt128 r = UINT128_ZERO;
    r.u16[5] = 0xffff;
    r.u8[12] = a; r.u8[13] = b; r.u8[14] = c; r.u8[15] = d;
    return r;
}

// ============================ F1: peer penalties ============================

// claimed = 0x80000000: 0x80000000 * 26 = 0xD00000000, which is 0 mod 2^32, so the earlier
// guard read 4 + 0 <= bufLen and walked 26-byte records from buf + 4. One live record is
// present; the second read is past the blob.
static void case_penalty_wrap(void) {
    uint8_t raw[4 + 26];
    UInt128 a = ip4(10, 0, 0, 1);
    time_t now = (time_t)1700000000;

    putLE32(raw, 0x80000000u);
    memcpy(&raw[4], &a, 16);
    putLE16(&raw[20], 12024);
    putLE64(&raw[22], (uint64_t)(now + 600));

    uint8_t *buf = exact(raw, sizeof(raw));
    UInt128 addrs[32]; uint16_t ports[32]; time_t until[32];
    size_t n = BRPeerPenaltyDeserialize(buf, sizeof(raw), now, addrs, ports, until, 32);
    check(n == 0, "F1: a blob claiming 0x80000000 penalties (26 bytes present) restores none");
    free(buf);
}

// GUARD: the real serializer's output round-trips, an expired entry is dropped, and a blob
// one byte short of its claim restores nothing.
static void case_penalty_roundtrip(void) {
    time_t now = (time_t)1700000000;
    UInt128 addrs[3] = { ip4(10, 0, 0, 1), ip4(10, 0, 0, 2), ip4(10, 0, 0, 3) };
    uint16_t ports[3] = { 12024, 12025, 12026 };
    time_t until[3] = { now + 600, now - 1, now + 1200 };
    uint8_t raw[4 + 3 * 26];

    size_t w = BRPeerPenaltySerialize(addrs, ports, until, 3, now, raw, sizeof(raw));
    check(w == 4 + 2 * 26, "F1 guard: the serializer writes the two live entries");

    uint8_t *buf = exact(raw, w);
    UInt128 oa[32]; uint16_t op[32]; time_t ou[32];
    size_t n = BRPeerPenaltyDeserialize(buf, w, now, oa, op, ou, 32);
    check(n == 2, "F1 guard: both live entries restore");
    check(n == 2 && UInt128Eq(oa[0], addrs[0]) && op[0] == 12024 && ou[0] == now + 600 &&
          UInt128Eq(oa[1], addrs[2]) && op[1] == 12026 && ou[1] == now + 1200,
          "F1 guard: address, port and deadline round-trip");
    free(buf);

    buf = exact(raw, w - 1);
    n = BRPeerPenaltyDeserialize(buf, w - 1, now, oa, op, ou, 32);
    check(n == 0, "F1 guard: a blob one byte short of its claim restores nothing");
    free(buf);

    buf = exact(raw, 4);
    putLE32(buf, 0);
    n = BRPeerPenaltyDeserialize(buf, 4, now, oa, op, ou, 32);
    check(n == 0, "F1 guard: the empty set (count 0, header only) restores nothing");
    free(buf);
}

// ============================ F2: saved peers ============================

// The writer's format (bridge_savePeers): [count][addr 16][port 2][timestamp 8][services 8].
static size_t write_peers(uint8_t *out, uint32_t count, const BRPeer *p, size_t n) {
    size_t pos = 0;
    putLE32(&out[pos], count); pos += 4;
    for (size_t i = 0; i < n; i++) {
        memcpy(&out[pos], &p[i].address, 16); pos += 16;
        putLE16(&out[pos], p[i].port); pos += 2;
        putLE64(&out[pos], p[i].timestamp); pos += 8;
        putLE64(&out[pos], p[i].services); pos += 8;
    }
    return pos;
}

static const BRPeer *sample_peers(void) {
    static BRPeer p[3];
    p[0] = (BRPeer){ ip4(10, 0, 0, 1), 12024, 0x449, 1700000000, 0 };
    p[1] = (BRPeer){ ip4(10, 0, 0, 2), 12025, 0x041, 1700000100, 0 };
    p[2] = (BRPeer){ ip4(10, 0, 0, 3), 12026, 0x009, 1700000200, 0 };
    return p;
}

// count = 0x40000000 with one real record. calloc(0x40000000, sizeof(BRPeer)) cannot be met
// (on 32-bit the product overflows size_t); the earlier loader wrote the record through NULL.
static void case_peers_count_huge(void) {
    uint8_t raw[4 + 34];
    size_t len = write_peers(raw, 0x40000000u, sample_peers(), 1);
    uint8_t *buf = exact(raw, len);
    BRPeer *peers = (BRPeer *)0x1;
    size_t n = deserialize_saved_peers_guarded(buf, len, &peers);
    check(n == 0 && peers == NULL, "F2: a count of 0x40000000 is rejected: no peers, no allocation");
    free(peers);
    free(buf);
}

// An in-range count whose allocation fails: the earlier loader did not check it.
static void case_peers_calloc_null(void) {
    uint8_t raw[4 + 3 * 34];
    size_t len = write_peers(raw, 3, sample_peers(), 3);
    uint8_t *buf = exact(raw, len);
    BRPeer *peers = (BRPeer *)0x1;
    g_declineCalloc = 1;
    size_t n = deserialize_saved_peers_guarded(buf, len, &peers);
    g_declineCalloc = 0;
    check(n == 0 && peers == NULL, "F2: a failed allocation leaves no saved peers");
    free(buf);
}

// GUARD: three peers round-trip; a truncated blob yields the whole records it holds (the
// loader's behaviour before this change); a count above the ceiling but within memory, and
// count 0, are empty.
static void case_peers_roundtrip(void) {
    const BRPeer *p = sample_peers();
    uint8_t raw[4 + 3 * 34];
    size_t len = write_peers(raw, 3, p, 3);
    uint8_t *buf = exact(raw, len);
    BRPeer *peers = NULL;
    size_t n = deserialize_saved_peers_guarded(buf, len, &peers);
    int same = (n == 3 && peers != NULL);
    for (size_t i = 0; same && i < 3; i++) {
        same = UInt128Eq(peers[i].address, p[i].address) && peers[i].port == p[i].port &&
               peers[i].timestamp == p[i].timestamp && peers[i].services == p[i].services;
    }
    check(same, "F2 guard: three saved peers round-trip (address, port, timestamp, services)");
    free(peers); free(buf);

    buf = exact(raw, len - 17);   // the third record cut in half
    n = deserialize_saved_peers_guarded(buf, len - 17, &peers);
    check(n == 2 && peers != NULL, "F2 guard: a truncated blob loads the two whole records it holds");
    free(peers); free(buf);

    // 999 is the most the writer emits (_peerRelayedPeers saves fewer than 1000).
    size_t bigLen = 4 + 999 * 34;
    uint8_t *big = calloc(1, bigLen);
    putLE32(big, 999);
    n = deserialize_saved_peers_guarded(big, bigLen, &peers);
    check(n == 999, "F2 guard: the largest blob the writer emits (999 peers) loads whole");
    free(peers); free(big);

    buf = exact(raw, 4);
    putLE32(buf, 0);
    n = deserialize_saved_peers_guarded(buf, 4, &peers);
    check(n == 0, "F2 guard: count 0 is empty");
    free(peers); free(buf);
}

// ============================ F3: saved blocks ============================

// [count 1][blockLen 0xFFFFFFF5][height][8 bytes]. pos = 12, and 12 + 0xFFFFFFF5 = 2^32 + 1,
// which is 1 on a 32-bit size_t: the earlier guard passed and BRMerkleBlockParse read an
// 80-byte header from the 8 bytes left.
static size_t blocks_wrap_blob(uint8_t raw[20]) {
    putLE32(&raw[0], 1);
    putLE32(&raw[4], 0xFFFFFFF5u);
    putLE32(&raw[8], 777);
    memset(&raw[12], 0x42, 8);
    return 20;
}

static void case_blocks_wrap(void) {
    uint8_t raw[20];
    size_t len = blocks_wrap_blob(raw);
    uint8_t *buf = exact(raw, len);
    BRMerkleBlock **blocks = NULL;
    size_t n = deserialize_saved_blocks_guarded(buf, len, &blocks);
    check(n == 0, "F3: a block length of 0xFFFFFFF5 in a 20-byte blob parses nothing");
    for (size_t i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
    free(blocks); free(buf);
}

static void case_core_blocks_wrap(void) {
    uint8_t raw[20];
    size_t len = blocks_wrap_blob(raw);
    uint8_t *buf = exact(raw, len);
    BRMerkleBlock **blocks = NULL;
    size_t n = BRSavedBlocksDeserialize(buf, len, &blocks);
    check(n == 0, "BRSavedBlocks.h: the same blob parses nothing");
    for (size_t i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
    free(blocks); free(buf);
}

// GUARD: two blocks serialized by BRMerkleBlockSerialize round-trip through both parsers with
// their heights; a block whose length runs one byte past the blob ends the walk after the
// first.
static void case_blocks_roundtrip(void) {
    uint8_t hdr[2][80];
    uint8_t ser[2][80];
    size_t serLen[2];
    BRMerkleBlock *src[2];

    for (int k = 0; k < 2; k++) {
        memset(hdr[k], 0x11 + k, 80);
        src[k] = BRMerkleBlockParse(hdr[k], 80);
        serLen[k] = src[k] ? BRMerkleBlockSerialize(src[k], ser[k], sizeof(ser[k])) : 0;
    }
    check(serLen[0] == 80 && serLen[1] == 80, "F3 guard: two 80-byte blocks serialize");

    uint8_t raw[4 + 2 * (8 + 80)];
    size_t pos = 0;
    putLE32(&raw[pos], 2); pos += 4;
    for (int k = 0; k < 2; k++) {
        putLE32(&raw[pos], (uint32_t)serLen[k]); pos += 4;
        putLE32(&raw[pos], 1000u + (uint32_t)k); pos += 4;
        memcpy(&raw[pos], ser[k], serLen[k]); pos += serLen[k];
    }

    for (int which = 0; which < 2; which++) {
        uint8_t *buf = exact(raw, pos);
        BRMerkleBlock **blocks = NULL;
        size_t n = which == 0 ? deserialize_saved_blocks_guarded(buf, pos, &blocks)
                              : BRSavedBlocksDeserialize(buf, pos, &blocks);
        int same = (n == 2);
        for (size_t i = 0; same && i < 2; i++) {
            same = UInt256Eq(blocks[i]->blockHash, src[i]->blockHash) && blocks[i]->height == 1000u + i;
        }
        check(same, which == 0 ? "F3 guard: two saved blocks round-trip (hash, height)"
                               : "BRSavedBlocks.h guard: two saved blocks round-trip (hash, height)");
        for (size_t i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
        free(blocks); free(buf);

        buf = exact(raw, pos - 1);   // the second block one byte short
        n = which == 0 ? deserialize_saved_blocks_guarded(buf, pos - 1, &blocks)
                       : BRSavedBlocksDeserialize(buf, pos - 1, &blocks);
        check(n == 1, which == 0 ? "F3 guard: a block one byte past the blob ends the walk after the first"
                                 : "BRSavedBlocks.h guard: a block one byte past the blob ends the walk");
        for (size_t i = 0; i < n; i++) BRMerkleBlockFree(blocks[i]);
        free(blocks); free(buf);
    }

    BRMerkleBlockFree(src[0]); BRMerkleBlockFree(src[1]);
}

// ============================ F4: saved transactions ============================

// [count 1][txSize 0xFFFFFFF1][height][timestamp][4 bytes]. pos = 16, and 16 + 0xFFFFFFF1 =
// 2^32 + 1, which is 1 on a 32-bit size_t: the earlier guard passed and BRTransactionParse
// was told the 4 bytes left were 0xFFFFFFF1 long.
static void case_txs_wrap(void) {
    uint8_t raw[20];
    putLE32(&raw[0], 1);
    putLE32(&raw[4], 0xFFFFFFF1u);
    putLE32(&raw[8], 123);
    putLE32(&raw[12], 1700000000u);
    putLE32(&raw[16], 1);   // a version; the parser's next read is the input count
    uint8_t *buf = exact(raw, sizeof(raw));
    BRTransaction **txs = NULL;
    size_t n = deserialize_saved_transactions_guarded(buf, sizeof(raw), &txs);
    check(n == 0, "F4: a transaction length of 0xFFFFFFF1 in a 20-byte blob parses nothing");
    for (size_t i = 0; i < n; i++) BRTransactionFree(txs[i]);
    free(txs); free(buf);
}

static BRTransaction *sample_tx(uint8_t seed) {
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev = UINT256_ZERO;
    uint8_t script[25] = { 0x76, 0xa9, 0x14 }, sig[8] = { 0x07, 1, 2, 3, 4, 5, 6, 7 };

    memset(&script[3], seed, 20);
    script[23] = 0x88; script[24] = 0xac;
    prev.u8[0] = seed;
    BRTransactionAddInput(tx, prev, 1, 0, NULL, 0, sig, sizeof(sig), NULL, 0, 0xffffffffu);
    BRTransactionAddOutput(tx, 100000000ULL + seed, script, sizeof(script));
    return tx;
}

// GUARD: two transactions written in the saved layout round-trip byte for byte with their
// height and timestamp; a transaction whose length runs one byte past the blob ends the walk
// after the first.
static void case_txs_roundtrip(void) {
    BRTransaction *src[2] = { sample_tx(0x21), sample_tx(0x22) };
    uint8_t ser[2][256];
    size_t serLen[2];

    for (int k = 0; k < 2; k++) serLen[k] = BRTransactionSerialize(src[k], ser[k], sizeof(ser[k]));
    check(serLen[0] > 0 && serLen[0] <= sizeof(ser[0]) && serLen[1] > 0 && serLen[1] <= sizeof(ser[1]),
          "F4 guard: two transactions serialize");

    uint8_t raw[4 + 2 * (12 + 256)];
    size_t pos = 0;
    putLE32(&raw[pos], 2); pos += 4;
    for (int k = 0; k < 2; k++) {
        putLE32(&raw[pos], (uint32_t)serLen[k]); pos += 4;
        putLE32(&raw[pos], 5000u + (uint32_t)k); pos += 4;
        putLE32(&raw[pos], 1700000000u + (uint32_t)k); pos += 4;
        memcpy(&raw[pos], ser[k], serLen[k]); pos += serLen[k];
    }

    uint8_t *buf = exact(raw, pos);
    BRTransaction **txs = NULL;
    size_t n = deserialize_saved_transactions_guarded(buf, pos, &txs);
    int same = (n == 2);
    for (size_t i = 0; same && i < 2; i++) {
        uint8_t back[256];
        size_t bl = BRTransactionSerialize(txs[i], back, sizeof(back));
        same = bl == serLen[i] && memcmp(back, ser[i], bl) == 0 &&
               txs[i]->blockHeight == 5000u + i && txs[i]->timestamp == 1700000000u + i;
    }
    check(same, "F4 guard: two saved transactions round-trip (bytes, height, timestamp)");
    for (size_t i = 0; i < n; i++) BRTransactionFree(txs[i]);
    free(txs); free(buf);

    buf = exact(raw, pos - 1);
    n = deserialize_saved_transactions_guarded(buf, pos - 1, &txs);
    check(n == 1, "F4 guard: a transaction one byte past the blob ends the walk after the first");
    for (size_t i = 0; i < n; i++) BRTransactionFree(txs[i]);
    free(txs); free(buf);

    buf = exact(raw, 4);
    putLE32(buf, 10001);
    n = deserialize_saved_transactions_guarded(buf, 4, &txs);
    check(n == 0 && txs == NULL, "F4 guard: a count above 10000 is rejected");
    free(buf);

    BRTransactionFree(src[0]); BRTransactionFree(src[1]);
}

// ============================ driver ============================

static const struct { const char *name; void (*fn)(void); } CASES[] = {
    { "penalty_wrap",      case_penalty_wrap },
    { "penalty_roundtrip", case_penalty_roundtrip },
    { "peers_count_huge",  case_peers_count_huge },
    { "peers_calloc_null", case_peers_calloc_null },
    { "peers_roundtrip",   case_peers_roundtrip },
    { "blocks_wrap",       case_blocks_wrap },
    { "core_blocks_wrap",  case_core_blocks_wrap },
    { "blocks_roundtrip",  case_blocks_roundtrip },
    { "txs_wrap",          case_txs_wrap },
    { "txs_roundtrip",     case_txs_roundtrip },
};

int main(int argc, char **argv) {
    size_t nCases = sizeof(CASES) / sizeof(CASES[0]);

    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < nCases; i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (size_t i = 0; i < nCases; i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("NOTE: %zu-bit size_t\n", sizeof(size_t) * 8);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail == 0 ? "pass" : "fail");
        return g_fail == 0 ? 0 : 1;
    }
    fprintf(stderr, "unknown case: %s\n", argv[1]);
    return 2;
}
