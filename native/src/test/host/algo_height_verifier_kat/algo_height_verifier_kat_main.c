// algo_height_verifier_kat -- a relayed header is accepted only if it names a proof-of-work algorithm the
// chain allows at the header's height.
//
// THE INVARIANT. _BRPeerManagerVerifyBlock (BRPeerManager.c) checks BRChainParamsAlgoAllowed(params,
// block->height, BRMerkleBlockAlgo(block)) before the difficulty check, at the level DGB_HEADER_POW_CHECK
// selects: 2 rejects the header (the peer is then treated as misbehaving), 1 logs "algo-by-height" only,
// 0 compiles the check out.
//
// HOW IT IS PROVED. This file #includes BRPeerManager.c and drives the REAL _peerRelayedBlock with
// synthetic headers (as the other manager KATs do; such headers never pass through BRMerkleBlockIsValid,
// so only the verifier's height rule is under test here). For each case a fresh manager is seeded with a
// hand-built tip at height H-1 and one header at height H with the version bits of the algorithm named.
// A header is "accepted" when it became the tip, "rejected" when the tip did not move and the peer was
// counted as misbehaving. The cases sit on every boundary of both chains, with the heights the real
// chains carry: mainnet 145,000 scrypt-only / 145,001 sha256d, 9,112,319 / 9,112,320 Odocrypt activation,
// the grandfathered groestl band up to 23,807,999 and groestl refused from 23,808,000; testnet26 groestl
// through 500 (the reference client keys on the previous height), Odocrypt from 501 (first real one 519),
// groestl refused from 501, unknown algorithm bits refused everywhere.
//
// OUTPUT. One line per case, "CASE <name> <accepted|rejected> want-at-level-2 <accepted|rejected>", each
// "algo-by-height" log line the verifier emits (prefixed "log:"), then a summary
// "RESULT <verdicts-as-level-2|all-accepted|other> level2-rejections=<n> algo-by-height-logged=<n>". run.sh reads it per arm:
// level 2 -> verdicts-as-level-2; level 1 -> all-accepted with logged == the number of level-2 rejections;
// level 0 -> all-accepted with logged == 0.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <time.h>

// The host build's peer_log is silent; route it here so the "algo-by-height" lines can be counted.
// BRPeer.h is include-guarded, so BRPeerManager.c below sees this definition.
#include "BRPeer.h"
#undef _peer_log
#define _peer_log(...) kat_peer_log(__VA_ARGS__)
static int g_algoHeightLogs = 0;
static void kat_peer_log(const char *fmt, ...)
{
    char line[512];
    va_list ap;
    va_start(ap, fmt); vsnprintf(line, sizeof(line), fmt, ap); va_end(ap);
    if (strstr(line, "algo-by-height")) { g_algoHeightLogs++; printf("  log: %s", line); }
}

#include "BRPeerManager.c"

typedef struct { const char *name; int chain; uint32_t height; uint32_t versionBits; int rejectAtLevel2; } Case;

static const Case cases[] = {
    // mainnet
    { "main_145000_scrypt",        0, 145000,   BLOCK_VERSION_SCRYPT,  0 },
    { "main_145000_sha256d",       0, 145000,   BLOCK_VERSION_SHA256D, 1 },
    { "main_145001_sha256d",       0, 145001,   BLOCK_VERSION_SHA256D, 0 },
    { "main_145001_groestl",       0, 145001,   BLOCK_VERSION_GROESTL, 0 },
    { "main_145001_odo",           0, 145001,   BLOCK_VERSION_ODO,     1 },
    { "main_9112319_odo",          0, 9112319,  BLOCK_VERSION_ODO,     1 },
    { "main_9112319_groestl",      0, 9112319,  BLOCK_VERSION_GROESTL, 0 },
    { "main_9112320_odo",          0, 9112320,  BLOCK_VERSION_ODO,     0 },
    { "main_9112320_groestl",      0, 9112320,  BLOCK_VERSION_GROESTL, 0 },
    { "main_23807995_groestl",     0, 23807995, BLOCK_VERSION_GROESTL, 0 },
    { "main_23807999_groestl",     0, 23807999, BLOCK_VERSION_GROESTL, 0 },
    { "main_23808000_groestl",     0, 23808000, BLOCK_VERSION_GROESTL, 1 },
    { "main_23808000_sha256d",     0, 23808000, BLOCK_VERSION_SHA256D, 0 },
    { "main_23808000_odo",         0, 23808000, BLOCK_VERSION_ODO,     0 },
    { "main_23808001_groestl",     0, 23808001, BLOCK_VERSION_GROESTL, 1 },
    { "main_23808001_skein",       0, 23808001, BLOCK_VERSION_SKEIN,   0 },
    { "main_23807995_unknown_300", 0, 23807995, 0x300u,                1 },
    { "main_23808000_unknown_a00", 0, 23808000, 0xA00u,                1 },
    { "main_24278140_unknown_100", 0, 24278140, 0x100u,                1 },
    { "main_24278140_qubit",       0, 24278140, BLOCK_VERSION_QUBIT,   0 },
    // testnet26
    { "test_1_groestl",            1, 1,        BLOCK_VERSION_GROESTL, 0 },
    { "test_1_odo",                1, 1,        BLOCK_VERSION_ODO,     1 },
    { "test_500_groestl",          1, 500,      BLOCK_VERSION_GROESTL, 0 },
    { "test_500_odo",              1, 500,      BLOCK_VERSION_ODO,     1 },
    { "test_501_sha256d",          1, 501,      BLOCK_VERSION_SHA256D, 0 },
    { "test_501_groestl",          1, 501,      BLOCK_VERSION_GROESTL, 1 },
    { "test_501_odo",              1, 501,      BLOCK_VERSION_ODO,     0 },
    { "test_519_odo",              1, 519,      BLOCK_VERSION_ODO,     0 },
    { "test_519_unknown_100",      1, 519,      0x100u,                1 },
    { "test_435500_groestl",       1, 435500,   BLOCK_VERSION_GROESTL, 1 },
    { "test_435500_odo",           1, 435500,   BLOCK_VERSION_ODO,     0 },
};
#define CASE_COUNT (sizeof(cases)/sizeof(cases[0]))

static BRMerkleBlock *make_header(uint32_t tag, UInt256 prevBlock, uint32_t timestamp, uint32_t version)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, 0, sizeof(b->blockHash.u8));
    b->blockHash.u32[0] = tag + 1;     // distinct per tag, never all-zero
    b->blockHash.u32[7] = 0xA1600u;
    b->prevBlock = prevBlock;
    b->timestamp = timestamp;
    b->version = version | BLOCK_VERSION_DEFAULT;
    b->height = BLOCK_UNKNOWN_HEIGHT;  // the relay path stamps it from the parent
    return b;
}

static int g_shapeFail = 0;

// returns 1 accepted, 0 rejected
static int run_case(const Case *c, uint32_t idx)
{
    BRSetNetwork(c->chain);
    const BRChainParams *params = c->chain ? &BRTestNetParams : &BRMainNetParams;

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    BRPeerManager *m = BRPeerManagerNew(params, wallet, 0, NULL, 0, NULL, 0);
    if (! wallet || ! m) { printf("setup failed\n"); exit(1); }
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;   // headers are not skipped by the earliestKeyTime rule

    uint32_t now = (uint32_t)time(NULL);
    BRMerkleBlock *tip = make_header(0x0F000000u + idx, UINT256_ZERO, now - 60, 0);
    tip->height = c->height - 1;
    BRSetAdd(m->blocks, tip);
    m->lastBlock = tip;
    m->lastSpanClampLog = tip->height;

    BRPeer *peer = BRPeerNew(params->magicNumber);
    peer->address.u8[15] = 0x51; peer->port = 12051;
    array_add(m->connectedPeers, peer);   // owned (and freed) by the manager from here

    BRPeerCallbackInfo info = { peer, m, UINT256_ZERO };
    BRMerkleBlock *hdr = make_header(0x30000000u + idx, tip->blockHash, now, c->versionBits);
    UInt256 identity = hdr->blockHash;
    int misbehavinBefore = m->misbehavinCount;

    _peerRelayedBlock(&info, hdr);   // the manager owns hdr from here (kept as the tip or freed)

    BRMerkleBlock *resident = BRSetGet(m->blocks, &identity);
    int accepted = (m->lastBlock == resident && resident != NULL && resident->height == c->height);
    int rejected = (m->lastBlock == tip && resident == NULL && m->misbehavinCount == misbehavinBefore + 1);
    if (! accepted && ! rejected) {
        printf("  shape: case %s neither accepted nor rejected (tip moved=%d resident=%d misbehavin %d->%d)\n",
               c->name, m->lastBlock != tip, resident != NULL, misbehavinBefore, m->misbehavinCount);
        g_shapeFail = 1;
    }

    BRPeerManagerFree(m);
    BRWalletFree(wallet);
    return accepted;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("algo_height_verifier_kat: level %d, %zu cases\n", DGB_HEADER_POW_CHECK, (size_t)CASE_COUNT);

    int asLevel2 = 1, allAccepted = 1, level2Rejections = 0;
    for (size_t i = 0; i < CASE_COUNT; i++) {
        int acc = run_case(&cases[i], (uint32_t)i);
        printf("CASE %s %s want-at-level-2 %s\n", cases[i].name, acc ? "accepted" : "rejected",
               cases[i].rejectAtLevel2 ? "rejected" : "accepted");
        if (acc == cases[i].rejectAtLevel2) asLevel2 = 0;
        if (! acc) allAccepted = 0;
        level2Rejections += cases[i].rejectAtLevel2;
    }

    const char *v = g_shapeFail ? "other" : asLevel2 ? "verdicts-as-level-2" : allAccepted ? "all-accepted" : "other";
    printf("RESULT %s level2-rejections=%d algo-by-height-logged=%d\n", v, level2Rejections, g_algoHeightLogs);
    return (g_shapeFail || (! asLevel2 && ! allAccepted)) ? 1 : 0;
}
