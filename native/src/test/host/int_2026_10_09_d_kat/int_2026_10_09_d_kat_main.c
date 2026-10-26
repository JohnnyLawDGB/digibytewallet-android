// Host KAT for INT-2026-10-09-D: a transaction counts as trusted only when the block that delivered it is the
// manager's own main-chain block it asked for; and a filter is evaluated (a match asks for the block, a miss marks the
// height scanned) only for the block the main chain holds at its height.
//
// WHAT IT PROVES.
//   RED (fail in the -DINT_2026_10_09_D_UNFIXED build, pass in the shipped build):
//     fork_block_unsigned_spend          a block the wallet asked for, whose tx list hashes to its own header, that
//                                        is resident but NOT on the main chain, carrying an unsigned spend of the
//                                        wallet's confirmed coin C: C stays unspent and the balance is unchanged. A
//                                        payment to the wallet in the same block still registers, unconfirmed.
//     unsolicited_main_block_unsigned_spend  the main-chain block, asked for by the peer layer but not in the
//                                        manager's solicitation table: the unsigned spend is not trusted.
//     unknown_block_unsigned_spend       a block whose header is not resident: the unsigned spend is not trusted.
//     fork_cfilter_no_request            a filter that verifies against the filter header at the height and matches
//                                        the wallet, keyed by a resident block NOT on the main chain: no full block
//                                        is asked for, nothing is recorded, the height stays outstanding.
//     fork_buffered_filter_no_request    the same through the buffered-filter drain (_cfBufEval).
//     fork_cfilter_miss_leaves_height    a filter read with the hash of a block beside the main chain MISSES: the
//                                        height is not marked scanned, the main block's filter is still evaluated,
//                                        and the payment in the main block is found and confirmed.
//     fork_buffered_filter_miss_leaves_height  the same through the buffered-filter drain.
//   GUARD (pass in both builds):
//     main_block_unsigned_spend_confirms the manager's own main-chain block it asked for: its transactions are
//                                        the block's and are not second-guessed (registered and confirmed).
//     main_cfilter_requests              a matching filter for the main-chain block asks for the block and
//                                        records the solicitation.
//     main_buffered_filter_requests      the same through the buffered-filter drain.
//     main_cfilter_miss_marks_scanned    a miss on the main-chain block still marks the height scanned (live and
//                                        buffered).
//     main_chain_index_matches_walk      the index the checks read for "the main-chain block at this height" gives
//                                        exactly the answer of the walk down from lastBlock, across tip
//                                        extensions, a reorg, a rewind and a prune.
//
// ENTRY LAYER. Block messages go through the real BRPeer.c dispatch (_BRPeerAcceptMessage) into the manager's own
// _peerRelayedTx / _peerRelayedBlockTxns, wired as BRPeerManager.c wires them. Filters go into the real
// _peerRelayedCFilter and _cfBufEval. Requests are made with the production sender on a socketless peer: the
// request is recorded and the send finds no socket. Only the two connection-state reads the drain uses to pick a
// filter peer are answered by this file, for the one test peer (see kat_BRPeerConnectStatus).
//
// FIXTURE. The real, prevBlock-linked mainnet headers 24278140..24278143 (as in block_delivery_gate_kat) are
// passed to the production constructor as persisted blocks, with a header at 24278144 on top whose merkle root
// commits to transactions built here. A second header at 24278143, a sibling of the real one, is made resident
// beside the main chain. A third, at 24278145, is never resident. The wallet starts with one confirmed coin C.
//
// One case per process: `int_2026_10_09_d_kat <case>` prints "RESULT <case> pass|fail".
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

// The drain picks a filter peer by its connection state. A socketless test peer has none, so inside
// BRPeerManager.c those two reads go through this file (defined below), for the test peer only.
BRPeerStatus kat_BRPeerConnectStatus(BRPeer *peer);
int kat_BRPeerIsSocketOpen(BRPeer *peer);
#define BRPeerConnectStatus kat_BRPeerConnectStatus
#define BRPeerIsSocketOpen kat_BRPeerIsSocketOpen
#include "BRPeerManager.c"
#undef BRPeerConnectStatus
#undef BRPeerIsSocketOpen

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static BRPeer *g_connectedPeer = NULL;

BRPeerStatus kat_BRPeerConnectStatus(BRPeer *peer)
{
    return (peer && peer == g_connectedPeer) ? BRPeerStatusConnected : BRPeerConnectStatus(peer);
}

int kat_BRPeerIsSocketOpen(BRPeer *peer)
{
    return (peer && peer == g_connectedPeer) ? 1 : BRPeerIsSocketOpen(peer);
}

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

#define DGB_SAT 100000000ULL
#define AMOUNT  (1000000ULL * DGB_SAT)
#define AMOUNT2 (2000ULL * DGB_SAT)

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

// Real mainnet headers, wire order (same bytes as ../block_delivery_gate_kat).
static const char *H24278140 = "02060020194e2d72afdcc7936237e3af92c38d8247c3d2301b0a2673eda01db425829bb87b9c4e47d2ad9dab7089b860da50a6d6f0d039836ecf9a20bc2aa1d66f019fec4588b76a2c44011a102ba854";
static const char *H24278141 = "020600208a38c2bba385fcd06bc43159cdd43700b7baea57a38d17bda50871aff1da191d8c6d21c148e2e068f05b9649f90ead1510cda8f95da8c0abfc599036fc5f69486088b76af81d011a055ab110";
static const char *H24278142 = "020e0020ac454593a4a47975f27d489f76c875b2f5d128f08599c3518230fd769dce942342b040dbb23ce026f862a3b5b7ee2fceb356995841fb5173ebad52430e37198b7587b76acc26251a011ed277";
static const char *H24278143 = "020e0020f5273bd8ca5049fc9470474855902e87b03caba461749dbfb324d62e745110071b1eed8ffd450da8ca68c6454e19d341c8ea0bb2182d1ab9a4511e76cf0dbdd98f87b76a8be6201aedbe7421";

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

// ---- transactions -----------------------------------------------------------
static const uint8_t kSig[8] = { 0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58 };   // not a signature
static const uint8_t kWit[1] = { 0x00 };
static const uint8_t kOtherSpk[22] = { 0x00,0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 };

// a transaction spending (prev, index) with a scriptSig and witness that sign nothing, paying `amount` to `spk`;
// parsed back from its own bytes so txHash is exactly what the wire gives
static BRTransaction *spendTx(UInt256 prev, uint32_t index, const uint8_t *spk, size_t spkLen, uint64_t amount)
{
    BRTransaction *tx = BRTransactionNew();

    BRTransactionAddInput(tx, prev, index, 0, NULL, 0, kSig, sizeof(kSig), kWit, sizeof(kWit), 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(len);
    len = BRTransactionSerialize(tx, d, len);
    BRTransaction *p = BRTransactionParse(d, len);
    free(d);
    BRTransactionFree(tx);
    assert(p != NULL);
    return p;
}

static BRTransaction *seededTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t seed)
{
    UInt256 prev;

    for (size_t i = 0; i < 32; i++) prev.u8[i] = (uint8_t)(seed*29u + i*11u + 1u);
    return spendTx(prev, seed, spk, spkLen, amount);
}

static uint8_t *txBytes(const BRTransaction *tx, size_t *outLen)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(len);
    *outLen = BRTransactionSerialize(tx, d, len);
    return d;
}

// header(80) | CompactSize(n) | each serialized tx
static uint8_t *txBlockMsg(const uint8_t hdr[80], BRTransaction *const txs[], size_t n, size_t *outLen)
{
    uint8_t vi[9], *bufs[8];
    size_t viLen = BRVarIntSet(vi, sizeof(vi), n), lens[8], total = 80 + viLen, off = 0;

    assert(n <= 8);
    for (size_t i = 0; i < n; i++) { bufs[i] = txBytes(txs[i], &lens[i]); total += lens[i]; }
    uint8_t *m = malloc(total);
    memcpy(m, hdr, 80); off = 80;
    memcpy(&m[off], vi, viLen); off += viLen;
    for (size_t i = 0; i < n; i++) { memcpy(&m[off], bufs[i], lens[i]); off += lens[i]; free(bufs[i]); }
    *outLen = total;
    return m;
}

static void buildHeader(uint8_t hdr[80], UInt256 prevBlock, BRTransaction *const txs[], size_t n, uint32_t time,
                        uint32_t bits, uint32_t nonce)
{
    UInt256 ids[8], root;

    assert(n <= 8);
    for (size_t i = 0; i < n; i++) ids[i] = txs[i]->txHash;
    assert(BRMerkleRootFromTxHashes(&root, ids, n));
    UInt32SetLE(&hdr[0], 0x20000002);
    UInt256Set(&hdr[4], prevBlock);
    UInt256Set(&hdr[36], root);
    UInt32SetLE(&hdr[68], time);
    UInt32SetLE(&hdr[72], bits);
    UInt32SetLE(&hdr[76], nonce);
}

// ---- BIP158 single-element filter (as in ../cf_block_completion_gate_kat) ---
#define TEST_ROTL64(x, b) (((x) << (b)) | ((x) >> (64 - (b))))
#define TEST_SIP_ROUND(v0, v1, v2, v3) \
    do { v0 += v1; v1 = TEST_ROTL64(v1, 13); v1 ^= v0; v0 = TEST_ROTL64(v0, 32); \
         v2 += v3; v3 = TEST_ROTL64(v3, 16); v3 ^= v2; \
         v0 += v3; v3 = TEST_ROTL64(v3, 21); v3 ^= v0; \
         v2 += v1; v1 = TEST_ROTL64(v1, 17); v1 ^= v2; v2 = TEST_ROTL64(v2, 32); } while (0)

static uint64_t test_siphash24(uint64_t k0, uint64_t k1, const uint8_t *data, size_t len)
{
    uint64_t v0 = 0x736f6d6570736575ULL ^ k0, v1 = 0x646f72616e646f6dULL ^ k1;
    uint64_t v2 = 0x6c7967656e657261ULL ^ k0, v3 = 0x7465646279746573ULL ^ k1;
    uint64_t m = 0;
    size_t i = 0;

    for (; i + 8 <= len; i += 8) {
        m = ((uint64_t)data[i]) | ((uint64_t)data[i+1] << 8) | ((uint64_t)data[i+2] << 16) |
            ((uint64_t)data[i+3] << 24) | ((uint64_t)data[i+4] << 32) | ((uint64_t)data[i+5] << 40) |
            ((uint64_t)data[i+6] << 48) | ((uint64_t)data[i+7] << 56);
        v3 ^= m;
        TEST_SIP_ROUND(v0, v1, v2, v3);
        TEST_SIP_ROUND(v0, v1, v2, v3);
        v0 ^= m;
    }

    m = ((uint64_t)(len & 0xff)) << 56;
    for (size_t j = 0; i + j < len; j++) m |= ((uint64_t)data[i + j]) << (8*j);
    v3 ^= m;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    v0 ^= m;
    v2 ^= 0xff;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    return v0 ^ v1 ^ v2 ^ v3;
}

// high 64 bits of hash*F, without a 128-bit type (the 32-bit build has none)
static uint64_t test_fastrange64(uint64_t hash, uint64_t F)
{
    uint64_t aL = hash & 0xffffffffu, aH = hash >> 32, bL = F & 0xffffffffu, bH = F >> 32;
    uint64_t ll = aL*bL, lh = aL*bH, hl = aH*bL, hh = aH*bH;
    uint64_t mid = (ll >> 32) + (lh & 0xffffffffu) + (hl & 0xffffffffu);

    return hh + (lh >> 32) + (hl >> 32) + (mid >> 32);
}

static void test_gcsWriteBit(uint8_t *out, size_t off, size_t *bitPos, unsigned bit)
{
    out[off + (*bitPos)/8] |= (uint8_t)((bit & 1u) << (7 - (*bitPos % 8)));
    (*bitPos)++;
}

static size_t buildSingleElementFilter(UInt256 blockHash, const uint8_t *elem, size_t elemLen, uint8_t *out,
                                       size_t outCap)
{
    uint64_t k0 = UInt64GetLE(&blockHash.u8[0]);
    uint64_t k1 = UInt64GetLE(&blockHash.u8[8]);
    uint64_t F  = (uint64_t)BR_GCS_BASIC_FILTER_M; // N=1 -> F = N*M = M
    uint64_t val = test_fastrange64(test_siphash24(k0, k1, elem, elemLen), F);
    const uint8_t P = BR_GCS_BASIC_FILTER_P;
    uint64_t q = val >> P, r = val & ((((uint64_t)1) << P) - 1);
    size_t off = 1, bitPos = 0;

    memset(out, 0, outCap);
    out[0] = 0x01; // CompactSize N=1
    for (uint64_t i = 0; i < q; i++) test_gcsWriteBit(out, off, &bitPos, 1);
    test_gcsWriteBit(out, off, &bitPos, 0);
    for (int i = (int)P - 1; i >= 0; i--) test_gcsWriteBit(out, off, &bitPos, (unsigned)((r >> i) & 1));
    return off + (bitPos + 7)/8;
}

// ---- fixture ----------------------------------------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    uint8_t spk[64];
    size_t spkLen;
    UInt256 coinHash;            // C: the wallet's confirmed coin (output 0, AMOUNT), confirmed at 24278141
    BRTransaction *spend;        // spends C with a scriptSig and witness that sign nothing, pays elsewhere
    BRTransaction *other;        // pays elsewhere
    BRTransaction *pay;          // pays the wallet AMOUNT2
    uint8_t tipHdr[80];          // 24278144, main chain, commits to { other, spend }
    UInt256 tipHash;
    uint8_t forkHdr[80];         // 24278143', resident beside the main chain, commits to { pay, spend }
    UInt256 forkHash;
    uint8_t nextHdr[80];         // 24278145, never resident, commits to { spend }
    UInt256 nextHash;
    UInt256 mainHash143;         // the real 24278143
    BRPeerCallbackInfo *infos[4];
    size_t infoCount;
} Fx;

static void fxInit(Fx *f)
{
    memset(f, 0, sizeof(*f));

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    // the address the coin pays: the wallet's first receive address, from a scratch wallet on the same key
    BRWallet *scratch = BRWalletNew(NULL, 0, mpk);
    BRAddress a = BRWalletReceiveAddress(scratch, 1);
    f->spkLen = BRAddressScriptPubKey(f->spk, sizeof(f->spk), a.s);
    BRWalletFree(scratch);
    assert(f->spkLen > 0);

    BRTransaction *coin = seededTx(f->spk, f->spkLen, AMOUNT, 0x41);
    coin->blockHeight = 24278141u;
    coin->timestamp = 1784980000u;
    f->coinHash = coin->txHash;
    BRTransaction *txs[1] = { coin };
    f->w = BRWalletNew(txs, 1, mpk);   // takes the coin
    assert(f->w != NULL);

    f->spend = spendTx(f->coinHash, 0, kOtherSpk, sizeof(kOtherSpk), AMOUNT - 10000);
    f->other = seededTx(kOtherSpk, sizeof(kOtherSpk), 5*DGB_SAT, 0x60);
    f->pay   = seededTx(f->spk, f->spkLen, AMOUNT2, 0x61);

    const char *run[4] = { H24278140, H24278141, H24278142, H24278143 };
    BRMerkleBlock *blocks[5];

    for (int i = 0; i < 4; i++) {
        uint8_t h[80];
        hexBytes(h, run[i], 80);
        blocks[i] = BRMerkleBlockParse(h, 80);
        blocks[i]->height = 24278140u + (uint32_t)i;
    }
    f->mainHash143 = blocks[3]->blockHash;

    BRTransaction *tipTxs[2] = { f->other, f->spend };
    buildHeader(f->tipHdr, blocks[3]->blockHash, tipTxs, 2, blocks[3]->timestamp + 15, blocks[3]->target, 7);
    blocks[4] = BRMerkleBlockParse(f->tipHdr, 80);
    blocks[4]->height = 24278144u;
    f->tipHash = blocks[4]->blockHash;

    BRTransaction *forkTxs[2] = { f->pay, f->spend };
    buildHeader(f->forkHdr, blocks[2]->blockHash, forkTxs, 2, blocks[2]->timestamp + 20, blocks[2]->target, 11);
    BRMerkleBlock *fork = BRMerkleBlockParse(f->forkHdr, 80);
    fork->height = 24278143u;
    f->forkHash = fork->blockHash;

    BRTransaction *nextTxs[1] = { f->spend };
    buildHeader(f->nextHdr, f->tipHash, nextTxs, 1, blocks[3]->timestamp + 30, blocks[3]->target, 9);
    BRSHA256_2(&f->nextHash, f->nextHdr, 80);

    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 1600000000u, blocks, 5, NULL, 0);
    assert(f->m != NULL);
    assert(f->m->lastBlock && f->m->lastBlock->height == 24278144u);
    assert(f->m->syncStartHeight == 0);

    // the sibling header at 24278143: resident, not on the chain that ends at lastBlock
    MGR_LOCK(f->m);
    BRSetAdd(f->m->blocks, fork);
    assert(UInt256Eq(_BRPeerManagerBlockHashAtHeight(f->m, 24278143u), f->mainHash143));
    MGR_UNLOCK(f->m);

    assert(BRWalletBalance(f->w) == AMOUNT);
}

static BRPeer *fxPeer(Fx *f)
{
    BRPeerCallbackInfo *info = calloc(1, sizeof(*info));

    info->manager = f->m;
    info->peer = BRPeerNew(f->m->params->magicNumber);
    info->peer->address.u8[15] = (uint8_t)(f->infoCount + 1);
    info->peer->port = 12024;
    BRPeerSetCallbacks(info->peer, info, _peerConnected, _peerDisconnected, _peerRelayedPeers,
                       _peerRelayedTx, _peerHasTx, _peerRejectedTx, _peerRelayedBlock, _peerRelayedBlockTxns,
                       _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                       _peerNetworkIsReachable, _peerThreadCleanup);
    assert(f->infoCount < 4);
    f->infos[f->infoCount++] = info;
    return info->peer;
}

static void fxFree(Fx *f)
{
    MGR_LOCK(f->m);
    array_clear(f->m->connectedPeers);
    MGR_UNLOCK(f->m);
    g_connectedPeer = NULL;
    for (size_t i = 0; i < f->infoCount; i++) { BRPeerFree(f->infos[i]->peer); free(f->infos[i]); }
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    BRTransactionFree(f->spend);
    BRTransactionFree(f->other);
    BRTransactionFree(f->pay);
}

static void recordSolicited(Fx *f, UInt256 blockHash, uint32_t height)
{
    MGR_LOCK(f->m);
    _BRPeerManagerRecordSolicitedBlockLocked(f->m, blockHash, height);
    MGR_UNLOCK(f->m);
}

static int solicited(Fx *f, UInt256 blockHash, uint32_t height)
{
    MGR_LOCK(f->m);
    int i = _BRPeerManagerFindSolicitedBlockLocked(f->m, blockHash, height);
    MGR_UNLOCK(f->m);
    return i >= 0;
}

static int peerAsked(BRPeer *peer, UInt256 blockHash)
{
    return _BRPeerWasRequested((BRPeerContext *)peer, &((BRPeerContext *)peer)->requestedBlockHashes, blockHash);
}

// is C still an unspent output of the wallet?
static int coinUnspent(Fx *f)
{
    BRUTXO u[16];
    size_t n = BRWalletUTXOs(f->w, u, 16);

    for (size_t i = 0; i < n; i++) if (UInt256Eq(u[i].hash, f->coinHash) && u[i].n == 0) return 1;
    return 0;
}

static int deliverBlock(BRPeer *peer, const uint8_t hdr[80], BRTransaction *const txs[], size_t n)
{
    size_t len;
    uint8_t *msg = txBlockMsg(hdr, txs, n, &len);
    int r = _BRPeerAcceptMessage(peer, msg, len, MSG_BLOCK);
    free(msg);
    return r;
}

static void note(Fx *f, const char *what, int r)
{
    BRTransaction *s = BRWalletTransactionForHash(f->w, f->spend->txHash);
    printf("NOTE: %s: r=%d spend held=%s height=%u coin unspent=%d balance=%" PRIu64 "\n", what, r,
           s ? "yes" : "no", s ? s->blockHeight : 0u, coinUnspent(f), BRWalletBalance(f->w));
}

// the filter header chain over [height - 1 .. height], with the cfheader at `height` committing to a filter keyed by
// `keyHash` that holds the element `elem` (the wallet's first filter element when NULL); the bytes are written to
// out/outLen, and the scan ledger has `height` outstanding
static void setupFilterAt(Fx *f, uint32_t height, UInt256 keyHash, const uint8_t *elem, size_t elemLen, uint8_t *out,
                          size_t outCap, size_t *outLen)
{
    BRWalletFilterElements *fe = BRWalletGetFilterElements(f->w);
    UInt256 dummy, fh;

    assert(fe != NULL && fe->count > 0);
    if (! elem) { elem = fe->elements[0]; elemLen = fe->elementLens[0]; }
    *outLen = buildSingleElementFilter(keyHash, elem, elemLen, out, outCap);
    BRWalletFilterElementsFree(fe);
    BRSHA256_2(fh.u8, out, *outLen);
    memset(dummy.u8, 0x77, sizeof(dummy.u8));

    MGR_LOCK(f->m);
    if (f->m->compactFilterChain) BRCompactFilterChainFree(f->m->compactFilterChain);
    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, height - 1, UINT256_ZERO);
    assert(BRCompactFilterChainAppend(f->m->compactFilterChain,
                                      BRCompactFilterChainTipHeader(f->m->compactFilterChain), &dummy, 1) == 1);
    assert(BRCompactFilterChainAppend(f->m->compactFilterChain,
                                      BRCompactFilterChainTipHeader(f->m->compactFilterChain), &fh, 1) == 1);
    assert(BRCompactFilterChainVerifyFilter(f->m->compactFilterChain, height, out, *outLen) == 1);
    BRCFScanLedgerInit(&f->m->cfLedger, height - 1);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, height, height, UINT128_ZERO, 0, 1);
    MGR_UNLOCK(f->m);
}

static void setupFilter(Fx *f, UInt256 keyHash, uint8_t *out, size_t outCap, size_t *outLen)
{
    setupFilterAt(f, 24278143u, keyHash, NULL, 0, out, outCap, outLen);
}

// would these filter bytes, read with `keyHash`, match the wallet?
static int filterMatches(Fx *f, UInt256 keyHash, const uint8_t *bytes, size_t len)
{
    BRWalletFilterElements *fe = BRWalletGetFilterElements(f->w);
    BRGCSFilter *gcs = BRGCSFilterBasicParse(bytes, len, keyHash);
    int hit = (gcs && fe) ? BRGCSFilterMatchAny(gcs, fe->elements, fe->elementLens, fe->count) : 0;

    if (gcs) BRGCSFilterFree(gcs);
    if (fe) BRWalletFilterElementsFree(fe);
    return hit;
}

static size_t outstanding(Fx *f)
{
    MGR_LOCK(f->m);
    size_t n = BRCFScanLedgerOutstandingCount(&f->m->cfLedger);
    MGR_UNLOCK(f->m);
    return n;
}

// a filter peer the drain can pick
static BRPeer *fxFilterPeer(Fx *f)
{
    BRPeer *peer = fxPeer(f);

    peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    g_connectedPeer = peer;
    MGR_LOCK(f->m);
    array_add(f->m->connectedPeers, peer);
    MGR_UNLOCK(f->m);
    return peer;
}

static int drainEvalAt(Fx *f, uint32_t height, UInt256 blockHash, const uint8_t *bytes, size_t len)
{
    MGR_LOCK(f->m);
    struct _cfDrainCtx c = { f->m, _BRPeerManagerFilterElementsLocked(f->m) };
    int r = _cfBufEval(&c, height, blockHash, bytes, len);
    MGR_UNLOCK(f->m);
    return r;
}

static int drainEval(Fx *f, UInt256 blockHash, const uint8_t *bytes, size_t len)
{
    return drainEvalAt(f, 24278143u, blockHash, bytes, len);
}

static uint32_t scannedThrough(Fx *f)
{
    MGR_LOCK(f->m);
    uint32_t h = BRCFScanLedgerScannedThrough(&f->m->cfLedger);
    MGR_UNLOCK(f->m);
    return h;
}

// =============================================================================
// Cases
// =============================================================================

// RED. The block at 24278143 beside the main chain, asked for by the manager and the peer, its tx list hashing to
// its own header: an unsigned spend of C inside it does not spend C. The payment in the same block registers
// through the checked path, unconfirmed (the block is not on the main chain).
static void case_fork_block_unsigned_spend(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *txs[2] = { f->pay, f->spend };

    recordSolicited(f, f->forkHash, 24278143u);
    BRPeerSendGetdataBlocks(peer, &f->forkHash, 1);
    int r = deliverBlock(peer, f->forkHdr, txs, 2);
    note(f, "fork block", r);

    BRTransaction *p = BRWalletTransactionForHash(f->w, f->pay->txHash);
    check(r == 1, "the peer is kept");
    check(BRWalletTransactionForHash(f->w, f->spend->txHash) == NULL, "the unsigned spend is not held");
    check(coinUnspent(f), "C stays unspent");
    check(BRWalletBalance(f->w) == AMOUNT + AMOUNT2, "balance: C plus the payment, nothing spent");
    check(p != NULL && p->blockHeight == TX_UNCONFIRMED, "the payment registers, unconfirmed");
}

// RED. The main-chain tip, asked for by the peer layer but absent from the manager's solicitation table.
static void case_unsolicited_main_block_unsigned_spend(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *txs[2] = { f->other, f->spend };

    BRPeerSendGetdataBlocks(peer, &f->tipHash, 1);
    int r = deliverBlock(peer, f->tipHdr, txs, 2);
    note(f, "main block, not solicited by the manager", r);
    check(r == 1, "the peer is kept");
    check(BRWalletTransactionForHash(f->w, f->spend->txHash) == NULL, "the unsigned spend is not held");
    check(coinUnspent(f) && BRWalletBalance(f->w) == AMOUNT, "C stays unspent, balance unchanged");
}

// RED. A block whose header is not resident (24278145), asked for by both.
static void case_unknown_block_unsigned_spend(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *txs[1] = { f->spend };

    recordSolicited(f, f->nextHash, 24278145u);
    BRPeerSendGetdataBlocks(peer, &f->nextHash, 1);
    int r = deliverBlock(peer, f->nextHdr, txs, 1);
    note(f, "block not resident", r);
    check(r == 1, "the peer is kept");
    check(BRWalletTransactionForHash(f->w, f->spend->txHash) == NULL, "the unsigned spend is not held");
    check(coinUnspent(f) && BRWalletBalance(f->w) == AMOUNT, "C stays unspent, balance unchanged");
}

// GUARD. The manager's own main-chain block it asked for: its transactions are the block's. The spend registers
// and is confirmed at 24278144, and C is spent.
static void case_main_block_unsigned_spend_confirms(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *txs[2] = { f->other, f->spend };

    recordSolicited(f, f->tipHash, 24278144u);
    BRPeerSendGetdataBlocks(peer, &f->tipHash, 1);
    int r = deliverBlock(peer, f->tipHdr, txs, 2);
    note(f, "main block, solicited", r);

    BRTransaction *s = BRWalletTransactionForHash(f->w, f->spend->txHash);
    check(r == 1, "the peer is kept");
    check(s != NULL && s->blockHeight == 24278144u, "the spend registers and is confirmed at 24278144");
    check(! coinUnspent(f) && BRWalletBalance(f->w) == 0, "C is spent");
    check(! solicited(f, f->tipHash, 24278144u), "the solicitation is retired");
}

// RED. A filter keyed by the block beside the main chain at 24278143, verifying against the filter header at that
// height and matching the wallet: no block is asked for, no solicitation, the height stays outstanding.
static void case_fork_cfilter_no_request(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRPeerCallbackInfo *info = f->infos[f->infoCount - 1];
    uint8_t enc[16];
    size_t len;

    setupFilter(f, f->forkHash, enc, sizeof(enc), &len);
    _peerRelayedCFilter(info, FILTER_TYPE_BASIC, f->forkHash, enc, len);
    printf("NOTE: fork filter: peer asked=%d solicited=%d outstanding=%zu\n", peerAsked(peer, f->forkHash),
           solicited(f, f->forkHash, 24278143u), outstanding(f));
    check(! peerAsked(peer, f->forkHash), "no full block is asked for");
    check(! solicited(f, f->forkHash, 24278143u), "no solicitation is recorded");
    check(outstanding(f) == 1, "the height stays outstanding");
}

// GUARD. The same for the main-chain block at 24278143: the block is asked for and the solicitation recorded.
static void case_main_cfilter_requests(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRPeerCallbackInfo *info = f->infos[f->infoCount - 1];
    uint8_t enc[16];
    size_t len;

    setupFilter(f, f->mainHash143, enc, sizeof(enc), &len);
    _peerRelayedCFilter(info, FILTER_TYPE_BASIC, f->mainHash143, enc, len);
    printf("NOTE: main filter: peer asked=%d solicited=%d outstanding=%zu\n", peerAsked(peer, f->mainHash143),
           solicited(f, f->mainHash143, 24278143u), outstanding(f));
    check(peerAsked(peer, f->mainHash143), "the full block is asked for");
    check(solicited(f, f->mainHash143, 24278143u), "the solicitation is recorded");
    check(outstanding(f) == 1, "the height stays outstanding until the block arrives");
}

// RED. The buffered-filter drain, for the block beside the main chain.
static void case_fork_buffered_filter_no_request(Fx *f)
{
    BRPeer *peer = fxFilterPeer(f);
    uint8_t enc[16];
    size_t len;

    setupFilter(f, f->forkHash, enc, sizeof(enc), &len);
    int r = drainEval(f, f->forkHash, enc, len);
    printf("NOTE: fork drain: r=%d peer asked=%d solicited=%d outstanding=%zu\n", r, peerAsked(peer, f->forkHash),
           solicited(f, f->forkHash, 24278143u), outstanding(f));
    check(r == 1, "the bytes leave the buffer");
    check(! peerAsked(peer, f->forkHash), "no full block is asked for");
    check(! solicited(f, f->forkHash, 24278143u), "no solicitation is recorded");
    check(outstanding(f) == 1, "the height stays outstanding");
}

// GUARD. The buffered-filter drain, for the main-chain block.
static void case_main_buffered_filter_requests(Fx *f)
{
    BRPeer *peer = fxFilterPeer(f);
    uint8_t enc[16];
    size_t len;

    setupFilter(f, f->mainHash143, enc, sizeof(enc), &len);
    int r = drainEval(f, f->mainHash143, enc, len);
    printf("NOTE: main drain: r=%d peer asked=%d solicited=%d outstanding=%zu\n", r,
           peerAsked(peer, f->mainHash143), solicited(f, f->mainHash143, 24278143u), outstanding(f));
    check(r == 1, "the request is made and the bytes leave the buffer");
    check(peerAsked(peer, f->mainHash143), "the full block is asked for");
    check(solicited(f, f->mainHash143, 24278143u), "the solicitation is recorded");
    check(outstanding(f) == 1, "the height stays outstanding until the block arrives");
}

// ---- a filter for a block the main chain does not hold ----------------------
// Two blocks at 24278145 on top of the tip: M, which the main chain now holds (it is the new lastBlock) and which pays
// the wallet AMOUNT2, and S beside it. The filter header at 24278145 commits to M's filter, keyed by M, holding the
// wallet's element; read with S's hash as the key the same bytes miss.
typedef struct {
    BRTransaction *recv;
    uint8_t mainHdr[80], sideHdr[80];
    UInt256 mainHash, sideHash;
    uint8_t enc[16];
    size_t encLen;
} Pair145;

static void setupPair145(Fx *f, Pair145 *p)
{
    BRTransaction *mtx[1], *stx[1];

    p->recv = seededTx(f->spk, f->spkLen, AMOUNT2, 0x62);
    mtx[0] = p->recv;
    stx[0] = f->other;
    MGR_LOCK(f->m);
    BRMerkleBlock *tip = f->m->lastBlock;
    buildHeader(p->mainHdr, tip->blockHash, mtx, 1, tip->timestamp + 15, tip->target, 21);
    buildHeader(p->sideHdr, tip->blockHash, stx, 1, tip->timestamp + 17, tip->target, 22);
    BRMerkleBlock *mb = BRMerkleBlockParse(p->mainHdr, 80), *sb = BRMerkleBlockParse(p->sideHdr, 80);
    mb->height = sb->height = 24278145u;
    p->mainHash = mb->blockHash;
    p->sideHash = sb->blockHash;
    BRSetAdd(f->m->blocks, mb);
    BRSetAdd(f->m->blocks, sb);
    f->m->lastBlock = mb;
    MGR_UNLOCK(f->m);
    setupFilterAt(f, 24278145u, p->mainHash, NULL, 0, p->enc, sizeof(p->enc), &p->encLen);
    assert(filterMatches(f, p->mainHash, p->enc, p->encLen));
    assert(! filterMatches(f, p->sideHash, p->enc, p->encLen));
}

// RED. A filter that verifies at 24278145, read with the hash of the block beside the main chain there, misses. The
// miss does not mark the height scanned. The main block's filter at that height is then still evaluated, matches, its
// block is asked for, and the payment in it is found and confirmed.
static void case_fork_cfilter_miss_leaves_height(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRPeerCallbackInfo *info = f->infos[f->infoCount - 1];
    Pair145 p;

    setupPair145(f, &p);
    _peerRelayedCFilter(info, FILTER_TYPE_BASIC, p.sideHash, p.enc, p.encLen);
    size_t out1 = outstanding(f);
    uint32_t scan1 = scannedThrough(f);
    printf("NOTE: side-block filter (a miss): outstanding=%zu scannedThrough=%u\n", out1, scan1);
    check(out1 == 1 && scan1 < 24278145u, "a miss read with another block's hash does not mark the height scanned");
    check(! peerAsked(peer, p.sideHash), "nothing is asked for");

    _peerRelayedCFilter(info, FILTER_TYPE_BASIC, p.mainHash, p.enc, p.encLen);
    check(peerAsked(peer, p.mainHash) && solicited(f, p.mainHash, 24278145u),
          "the main block's filter is evaluated: it matches and the block is asked for");

    BRTransaction *txs[1] = { p.recv };
    int r = deliverBlock(peer, p.mainHdr, txs, 1);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, p.recv->txHash);
    printf("NOTE: main block delivered: r=%d receive=%s height=%u outstanding=%zu scannedThrough=%u\n", r,
           rec ? "yes" : "no", rec ? rec->blockHeight : 0u, outstanding(f), scannedThrough(f));
    check(rec != NULL && rec->blockHeight == 24278145u, "the payment is found and confirmed at 24278145");
    check(outstanding(f) == 0 && scannedThrough(f) == 24278145u, "only then is the height scanned");
    BRTransactionFree(p.recv);
}

// RED. The same through the buffered-filter drain: a miss read with the side block's hash leaves the height
// outstanding; the main block's bytes then match and its block is asked for.
static void case_fork_buffered_filter_miss_leaves_height(Fx *f)
{
    BRPeer *peer = fxFilterPeer(f);
    Pair145 p;

    setupPair145(f, &p);
    int r = drainEvalAt(f, 24278145u, p.sideHash, p.enc, p.encLen);
    printf("NOTE: buffered side-block filter (a miss): r=%d outstanding=%zu scannedThrough=%u\n", r, outstanding(f),
           scannedThrough(f));
    check(r == 1, "the bytes leave the buffer");
    check(outstanding(f) == 1 && scannedThrough(f) < 24278145u,
          "a miss read with another block's hash does not mark the height scanned");
    r = drainEvalAt(f, 24278145u, p.mainHash, p.enc, p.encLen);
    check(r == 1 && peerAsked(peer, p.mainHash) && solicited(f, p.mainHash, 24278145u),
          "the main block's filter is evaluated: it matches and the block is asked for");
    BRTransactionFree(p.recv);
}

// GUARD. A miss on the main block at 24278143 still marks the height scanned, live and buffered.
static void case_main_cfilter_miss_marks_scanned(Fx *f)
{
    BRPeer *peer = fxFilterPeer(f);
    BRPeerCallbackInfo *info = f->infos[f->infoCount - 1];
    uint8_t enc[16];
    size_t len;

    setupFilterAt(f, 24278143u, f->mainHash143, kOtherSpk, sizeof(kOtherSpk), enc, sizeof(enc), &len);
    check(! filterMatches(f, f->mainHash143, enc, len), "setup: the filter holds no wallet element");
    _peerRelayedCFilter(info, FILTER_TYPE_BASIC, f->mainHash143, enc, len);
    printf("NOTE: main-block miss: outstanding=%zu scannedThrough=%u\n", outstanding(f), scannedThrough(f));
    check(outstanding(f) == 0 && scannedThrough(f) == 24278143u, "live: the height is scanned");
    check(! peerAsked(peer, f->mainHash143), "live: nothing is asked for");

    setupFilterAt(f, 24278143u, f->mainHash143, kOtherSpk, sizeof(kOtherSpk), enc, sizeof(enc), &len);
    int r = drainEval(f, f->mainHash143, enc, len);
    check(r == 1 && outstanding(f) == 0 && scannedThrough(f) == 24278143u, "buffered: the height is scanned");
}

// ---- the main-chain index ---------------------------------------------------
static BRMerkleBlock *synthBlock(uint32_t height, uint8_t tag, UInt256 prev)
{
    uint8_t seed[5] = { tag, (uint8_t)height, (uint8_t)(height >> 8), (uint8_t)(height >> 16), (uint8_t)(height >> 24) };
    BRMerkleBlock *b = BRMerkleBlockNew();

    BRSHA256_2(&b->blockHash, seed, sizeof(seed));
    b->prevBlock = prev;
    b->height = height;
    b->timestamp = 1700000000u + height;
    return b;
}

// a linked run [from..to] with `tag` on top of `below` (whose hash it links to), added to m->blocks; returns the top
static BRMerkleBlock *synthRun(BRPeerManager *m, BRMerkleBlock *below, uint32_t from, uint32_t to, uint8_t tag)
{
    BRMerkleBlock *b = below;

    for (uint32_t h = from; h <= to; h++) {
        BRMerkleBlock *n = synthBlock(h, tag, b ? b->blockHash : UINT256_ZERO);
        BRSetAdd(m->blocks, n);
        b = n;
    }
    return b;
}

static BRMerkleBlock *synthAt(BRPeerManager *m, BRMerkleBlock *tip, uint32_t height)
{
    BRMerkleBlock *b = tip;
    while (b && b->height > height) b = BRSetGet(m->blocks, &b->prevBlock);
    return b;
}

static uint32_t g_rng = 12345;
static uint32_t rnd(void) { g_rng = g_rng*1103515245u + 12345u; return g_rng >> 8; }

// every query in `n` random heights over [lo..hi], plus the edges, ascending and descending: the index's answer is
// the full walk's; returns the number of disagreements
static int compareIndex(BRPeerManager *m, uint32_t lo, uint32_t hi, int n)
{
    int bad = 0;
    uint32_t edges[] = { lo, lo + 1, hi, hi - 1, hi + 1, lo ? lo - 1 : 0, (hi/MAIN_INDEX_STRIDE)*MAIN_INDEX_STRIDE,
                         (hi/MAIN_INDEX_STRIDE)*MAIN_INDEX_STRIDE - 1, (hi/MAIN_INDEX_STRIDE)*MAIN_INDEX_STRIDE + 1 };

    for (size_t i = 0; i < sizeof(edges)/sizeof(*edges); i++)
        bad += ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, edges[i]), _BRPeerManagerBlockHashAtHeight(m, edges[i]));
    for (uint32_t h = lo; h <= hi; h += 97)   // ascending, as a scan asks
        bad += ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, h), _BRPeerManagerBlockHashAtHeight(m, h));
    for (uint32_t h = hi; h >= lo && h <= hi; h -= 89)   // descending
        bad += ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, h), _BRPeerManagerBlockHashAtHeight(m, h));
    for (int i = 0; i < n; i++) {
        uint32_t h = lo + rnd() % (hi - lo + 1);
        bad += ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, h), _BRPeerManagerBlockHashAtHeight(m, h));
    }
    return bad;
}

// GUARD. The main-chain index the checks read gives exactly the answer of _BRPeerManagerBlockHashAtHeight (the walk
// down from lastBlock), across tip extensions short and long, a reorg, a rewind, a prune from the bottom, and a
// missing entry.
static void case_main_chain_index_matches_walk(Fx *f)
{
    (void)f;
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    const uint32_t base = 23000000u;
    char s[160];
    int bad;

    MGR_LOCK(m);
    BRMerkleBlock *tip = synthRun(m, NULL, base, base + 5999, 0);
    m->lastBlock = tip;
    bad = compareIndex(m, base, tip->height, 400);
    snprintf(s, sizeof(s), "a 6,000-block chain: index and walk agree (%d disagreements, %zu entries)", bad,
             m->mainIndexCount);
    check(bad == 0 && m->mainIndexCount > 0, s);

    uint32_t steps[] = { 1, 300, 5000 };   // the tip extends by one, by more than a stride, by many strides
    for (size_t i = 0; i < 3; i++) {
        tip = synthRun(m, tip, tip->height + 1, tip->height + steps[i], 0);
        m->lastBlock = tip;
        bad = compareIndex(m, base, tip->height, 400);
        snprintf(s, sizeof(s), "the tip extends by %u: index and walk agree (%d disagreements)", steps[i], bad);
        check(bad == 0, s);
    }

    // a reorg: a branch from 600 below the tip (so it replaces indexed heights), 650 long, becomes the main chain
    BRMerkleBlock *join = synthAt(m, tip, tip->height - 600);
    BRMerkleBlock *fork = synthRun(m, join, join->height + 1, join->height + 650, 1);
    m->lastBlock = fork;
    bad = compareIndex(m, base, fork->height, 400);
    for (uint32_t h = join->height - 10; h <= fork->height; h++)   // every height the reorg touched
        bad += ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, h), _BRPeerManagerBlockHashAtHeight(m, h));
    snprintf(s, sizeof(s), "after a reorg: index and walk agree (%d disagreements)", bad);
    check(bad == 0, s);
    check(UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, tip->height), _BRPeerManagerBlockHashAtHeight(m, tip->height)) &&
          ! UInt256Eq(_BRPeerManagerMainHashAtHeightLocked(m, tip->height), tip->blockHash),
          "after a reorg: the replaced tip's height reads the branch's block");

    // a rewind: lastBlock moves 3,000 down (as a rescan does), the rest stays resident
    BRMerkleBlock *low = synthAt(m, fork, fork->height - 3000);
    m->lastBlock = low;
    bad = compareIndex(m, base, fork->height, 400);
    snprintf(s, sizeof(s), "after a rewind: index and walk agree (%d disagreements)", bad);
    check(bad == 0, s);

    // back on the branch tip, then the bottom 2,000 blocks are pruned
    m->lastBlock = fork;
    compareIndex(m, base, fork->height, 50);
    for (uint32_t h = base; h < base + 2000; h++) {
        BRMerkleBlock *b = synthAt(m, fork, h);
        if (b) { BRSetRemove(m->blocks, b); BRMerkleBlockFree(b); }
    }
    bad = compareIndex(m, base, fork->height, 400);
    snprintf(s, sizeof(s), "after a prune from the bottom: index and walk agree (%d disagreements)", bad);
    check(bad == 0, s);
    MGR_UNLOCK(m);
    BRPeerManagerFree(m);
}

typedef struct { const char *name; void (*fn)(Fx *); } Case;

static const Case kCases[] = {
    { "fork_block_unsigned_spend",             case_fork_block_unsigned_spend },
    { "unsolicited_main_block_unsigned_spend", case_unsolicited_main_block_unsigned_spend },
    { "unknown_block_unsigned_spend",          case_unknown_block_unsigned_spend },
    { "fork_cfilter_no_request",               case_fork_cfilter_no_request },
    { "fork_buffered_filter_no_request",       case_fork_buffered_filter_no_request },
    { "fork_cfilter_miss_leaves_height",       case_fork_cfilter_miss_leaves_height },
    { "fork_buffered_filter_miss_leaves_height", case_fork_buffered_filter_miss_leaves_height },
    { "main_cfilter_miss_marks_scanned",       case_main_cfilter_miss_marks_scanned },
    { "main_block_unsigned_spend_confirms",    case_main_block_unsigned_spend_confirms },
    { "main_cfilter_requests",                 case_main_cfilter_requests },
    { "main_buffered_filter_requests",         case_main_buffered_filter_requests },
    { "main_chain_index_matches_walk",         case_main_chain_index_matches_walk },
};

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc != 2) {
        fprintf(stderr, "usage: %s <case>|list\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < sizeof(kCases)/sizeof(kCases[0]); i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; i < sizeof(kCases)/sizeof(kCases[0]); i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        Fx f;
        fxInit(&f);
        printf("---- %s ----\n", kCases[i].name);
        kCases[i].fn(&f);
        fxFree(&f);
        printf("RESULT %s %s\n", kCases[i].name, g_fail ? "fail" : "pass");
        return g_fail ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
