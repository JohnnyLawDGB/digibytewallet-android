// Host KAT: a confirmation height a server reports is never the proof (4.1.2 row 1, N1/K4 step B).
//
// THE DEFECT
// ----------
// The confirmation reconcile asks a server for the height of each pending wallet transaction and, before 4.1.2,
// confirmed the transaction at whatever height came back (registerRawTransaction / confirmTransaction ->
// BRWalletUpdateTransactionsUnproven). A server that names a height for a transaction that is not in that block made
// a replaceable receive spendable and released a withheld DigiDollar credit. Step A refused heights the wallet's
// chain does not vouch for (judged_confirmation_kat, SERVER_HEIGHT_UNFIXED); this suite pins step B: where the wallet
// can check, the server's height is only a hint.
//
// WHAT IT ASSERTS (BRPeerManagerApplyServerHeight)
//   A  RED   a height on a block whose header and filter header the wallet holds is re-checked over P2P: one
//            getcfilters for exactly that block goes to a filter peer, and the transaction stays pending
//            (-DSERVER_HEIGHT_HINT_UNFIXED confirms it on the server's word: this case must fail there)
//   B  GUARD the peer's answer confirms it through the normal path: the filter matches, the block is solicited,
//            and its delivery confirms the transaction at H
//   C  GUARD a height above the tip is refused: nothing is sent and the transaction stays pending
//   D  GUARD with no filter peer connected the transaction stays pending and nothing is sent (the reconcile asks again)
//   F  RED   a payment the peers confirmed keeps its height when a server later reports another
//            (-DSERVER_HEIGHT_OVERWRITE_UNFIXED replaces it: this case must fail there)
//   E  GUARD a height below everything the wallet holds, pinned by a checkpoint at or below its tip, is taken as
//            reported (the residual: there is no block the wallet could re-check there)
//   G  RED   a height on a held header the filter-header chain has not reached yet (the chain trails the header tip,
//            as during sync and for a moment after every block) stays pending: the scan reaches it and decides
//   H  RED   a height on a held header before the filter-header chain exists stays pending the same way
//   I  GUARD a height on a held header below the filter chain's start is taken as reported, like E: no filter can
//            ever re-check it (a restore whose birth height came too late), and refusing it left a real receive
//            pending for good
//            (-DSERVER_HEIGHT_HELD_UNFIXED takes G and H on the server's word: G must fail there; review R2)
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// ---- call interception (the BRPeerManager.c included above calls these through -D renames in run.sh) ----
static BRPeer  *g_livePeer = NULL;          // the one peer that counts as connected with an open socket
static int      g_getcfiltersCount = 0, g_getdataCount = 0;
static uint32_t g_getcfiltersStart = 0;
static UInt256  g_getcfiltersStop, g_getdataHash;

BRPeerStatus __wrap_BRPeerConnectStatus(BRPeer *peer)
{
    return (peer && peer == g_livePeer) ? BRPeerStatusConnected : BRPeerStatusDisconnected;
}
int __wrap_BRPeerIsSocketOpen(BRPeer *peer) { return peer && peer == g_livePeer; }

void __wrap_BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)peer; (void)filterType;
    g_getcfiltersCount++;
    g_getcfiltersStart = startHeight;
    g_getcfiltersStop = stopHash;
}

void __wrap_BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount)
{
    (void)peer;
    g_getdataCount += (int)blockCount;
    if (blockCount > 0) g_getdataHash = blockHashes[0];
}

// ---- fixture ----------------------------------------------------------------------------------------------------
static const uint8_t kPlaceholder[1] = {0};
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

static BRTransaction *payTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t prevoutSeed)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;

    memset(prevHash.u8, prevoutSeed, sizeof(prevHash.u8));
    BRTransactionAddInput(tx, prevHash, 0, 0, spk, spkLen, kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

static BRMerkleBlock *blockCommitting(uint32_t height, uint8_t hashSeed, uint32_t timestamp,
                                      const UInt256 *txHashes, size_t txCount)
{
    BRMerkleBlock *b = BRMerkleBlockNew();

    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = timestamp;
    if (txCount > 0 && ! BRMerkleRootFromTxHashes(&b->merkleRoot, txHashes, txCount)) {
        printf("FATAL: no merkle root for the fixture block at %u\n", height);
        exit(1);
    }
    return b;
}

// BIP 158 basic filter with one element: copied verbatim from cf_block_completion_gate_kat
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

static uint64_t test_fastrange64(uint64_t hash, uint64_t F)
{
    return (uint64_t)(((__uint128_t)hash * (__uint128_t)F) >> 64);
}

static void test_gcsWriteBit(uint8_t *out, size_t off, size_t *bitPos, unsigned bit)
{
    out[off + (*bitPos)/8] |= (uint8_t)((bit & 1u) << (7 - (*bitPos % 8)));
    (*bitPos)++;
}

static size_t buildSingleElementFilter(UInt256 blockHash, const uint8_t *elem, size_t elemLen,
                                       uint8_t *out, size_t outCap)
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

// ---------------------------------------------------------------------------
// CRUX-A: an unsolicited, otherwise perfectly valid full block must not retire a scan height.
//
// This is the third-party erase: peer Q, which was never asked for anything, deletes the hole
// peer P is being asked to fill. Nothing re-requests a cleared height, so a receive inside it is
// gone until a manual rescan.

typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeer *peer;
    BRPeerCallbackInfo info;
    BRMerkleBlock *b0, *b;      // H0 and H: H commits to the payment
    BRTransaction *payment;
    uint8_t filter[16];
    size_t filterLen;
} Fx;

#define H0 5300000u          // a mainnet checkpoint height: the pinned case (E) sits under it
#define H  (H0 + 1)

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    assert(w != NULL);
    return w;
}

static void fxInit(Fx *f, int livePeer)
{
    memset(f, 0, sizeof(*f));
    f->w = makeWallet();
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    // the wallet's pending payment, which the scan matched earlier but never saw confirmed
    BRAddress addr = BRWalletReceiveAddress(f->w, 1);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr.s);
    f->payment = payTx(spk, spkLen, 400000, 0xD2);
    check(BRWalletRegisterTransaction(f->w, f->payment) == 1, "setup: the payment is registered");
    check(f->payment->blockHeight == TX_UNCONFIRMED, "setup: the payment is pending");

    UInt256 delivered[1] = { f->payment->txHash };
    f->b0 = blockCommitting(H0, 0xD0, 1700300000, NULL, 0);
    f->b  = blockCommitting(H,  0xD1, 1700300015, delivered, 1);
    f->b->prevBlock = f->b0->blockHash;
    BRSetAdd(f->m->blocks, f->b0);
    BRSetAdd(f->m->blocks, f->b);
    f->m->lastBlock = f->b;

    // filter-header chain H0..H with the real filter for H, so a delivered filter verifies
    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H0, UINT256_ZERO);
    UInt256 dummy;
    memset(dummy.u8, 0x77, sizeof(dummy.u8));
    BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain), &dummy, 1);
    BRWalletFilterElements *fe = BRWalletGetFilterElements(f->w);
    // the element the payment's output script contributes
    size_t k = 0;
    for (; k < fe->count; k++) if (fe->elementLens[k] == spkLen && memcmp(fe->elements[k], spk, spkLen) == 0) break;
    check(k < fe->count, "setup: the payment's script is a wallet filter element");
    f->filterLen = buildSingleElementFilter(f->b->blockHash, fe->elements[k], fe->elementLens[k], f->filter, sizeof(f->filter));
    BRWalletFilterElementsFree(fe);
    UInt256 fh;
    BRSHA256_2(fh.u8, f->filter, f->filterLen);
    BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain), &fh, 1);
    check(BRCompactFilterChainVerifyFilter(f->m->compactFilterChain, H, f->filter, f->filterLen) == 1,
          "setup: the filter for H verifies against the filter-header chain");

    // the scan has already passed H (the case the reconcile exists for)
    BRCFScanLedgerInit(&f->m->cfLedger, H0);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, H0, H, UINT128_ZERO, 0, 1);
    BRCFScanLedgerMarkEvaluated(&f->m->cfLedger, H0);
    BRCFScanLedgerMarkEvaluated(&f->m->cfLedger, H);

    f->peer = BRPeerNew(BRMainNetParams.magicNumber);
    f->peer->address.u8[15] = 0x01;
    f->peer->port = 10001;
    f->peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(f->m->connectedPeers, f->peer);
    g_livePeer = livePeer ? f->peer : NULL;
    f->info = (BRPeerCallbackInfo){ .peer = f->peer, .manager = f->m, .hash = UINT256_ZERO };
    g_getcfiltersCount = g_getdataCount = 0;
    g_getcfiltersStart = 0;
}

static void fxFree(Fx *f)
{
    array_clear(f->m->connectedPeers);
    BRPeerManagerFree(f->m);
    BRPeerFree(f->peer);
    BRWalletFree(f->w);
    g_livePeer = NULL;
}

static uint32_t paymentHeight(Fx *f)
{
    BRTransaction *t = BRWalletTransactionForHash(f->w, f->payment->txHash);
    return t ? t->blockHeight : 0;
}

// ---- cases ----------------------------------------------------------------------------------------------------------
static void test_A_and_B(void)
{
    printf("\n=== A: a height the wallet can check is re-checked over P2P, the tx stays pending ===\n");
    Fx f;
    fxInit(&f, 1);
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H, 1700300015);
    printf("NOTE: apply(H) -> %d, getcfilters %d (start %u), payment height %u\n", r, g_getcfiltersCount,
           g_getcfiltersStart, paymentHeight(&f));
    check(r == BR_SERVER_HEIGHT_RECHECKING, "A: the result is RECHECKING");
    check(g_getcfiltersCount == 1 && g_getcfiltersStart == H && UInt256Eq(g_getcfiltersStop, f.b->blockHash),
          "A: exactly one getcfilters for block H went to the filter peer");
    check(paymentHeight(&f) == TX_UNCONFIRMED, "A: the payment is still pending (the server's height is not the proof)");

    printf("\n=== B: the peer's answer confirms it through the normal solicited path ===\n");
    _peerRelayedCFilter(&f.info, FILTER_TYPE_BASIC, f.b->blockHash, f.filter, f.filterLen);
    check(g_getdataCount == 1 && UInt256Eq(g_getdataHash, f.b->blockHash), "B: the matching filter solicited block H");
    UInt256 delivered[1] = { f.payment->txHash };
    _peerRelayedBlockTxns(&f.info, f.b->blockHash, f.b->merkleRoot, delivered, 1);
    printf("NOTE: after the solicited block: payment height %u\n", paymentHeight(&f));
    check(paymentHeight(&f) == H, "B: the payment is confirmed at H by the delivered block");

    printf("\n=== F: a height the peers proved is never replaced by a server's ===\n");
    BRServerHeightResult r2 = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H0 - 1000, 1700200000);
    printf("NOTE: apply(%u) on the P2P-confirmed payment -> %d, payment height %u\n", H0 - 1000, r2, paymentHeight(&f));
    check(r2 == BR_SERVER_HEIGHT_REFUSED, "F: the result is REFUSED");
    check(paymentHeight(&f) == H, "F: the payment keeps the height its block proved");
    fxFree(&f);
}

static void test_C(void)
{
    printf("\n=== C: a height above the tip is refused ===\n");
    Fx f;
    fxInit(&f, 1);
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H + 50, 1700300015);
    check(r == BR_SERVER_HEIGHT_REFUSED, "C: the result is REFUSED");
    check(g_getcfiltersCount == 0, "C: nothing is sent");
    check(paymentHeight(&f) == TX_UNCONFIRMED, "C: the payment stays pending");
    fxFree(&f);
}

static void test_D(void)
{
    printf("\n=== D: no filter peer connected: the tx stays pending, nothing is sent ===\n");
    Fx f;
    fxInit(&f, 0);
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H, 1700300015);
    check(r == BR_SERVER_HEIGHT_RECHECKING, "D: the result is RECHECKING (the next reconcile asks again)");
    check(g_getcfiltersCount == 0, "D: nothing is sent");
    check(paymentHeight(&f) == TX_UNCONFIRMED, "D: the payment stays pending");
    fxFree(&f);
}

static void test_E(void)
{
    printf("\n=== E: a checkpoint-pinned height below everything held is taken as reported ===\n");
    Fx f;
    fxInit(&f, 1);
    uint32_t deep = H0 - 1000;
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, deep, 1700200000);
    printf("NOTE: apply(%u) -> %d, payment height %u\n", deep, r, paymentHeight(&f));
    check(r == BR_SERVER_HEIGHT_TAKEN, "E: the result is TAKEN");
    check(g_getcfiltersCount == 0, "E: nothing is sent (there is no held block to re-check)");
    check(paymentHeight(&f) == deep, "E: the payment is confirmed at the reported height (signature-checked)");
    fxFree(&f);
}

// The filter-header chain of fxInit, rebuilt to cover only [start, next): the header chain itself is unchanged.
static void fxChainOnly(Fx *f, uint32_t start, int appends)
{
    BRCompactFilterChainFree(f->m->compactFilterChain);
    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, start, UINT256_ZERO);
    UInt256 dummy;
    memset(dummy.u8, 0x78, sizeof(dummy.u8));
    for (int i = 0; i < appends; i++)
        BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain), &dummy, 1);
}

static void test_G(void)
{
    printf("\n=== G: a held header the filter chain has not reached: the tx stays pending ===\n");
    Fx f;
    fxInit(&f, 1);
    fxChainOnly(&f, H0, 1);                 // filter headers for H0 only: next height is H
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H, 1700300015);
    printf("NOTE: apply(H) with the filter chain at %u -> %d, payment height %u\n",
           BRCompactFilterChainNextHeight(f.m->compactFilterChain), r, paymentHeight(&f));
    check(r == BR_SERVER_HEIGHT_RECHECKING, "G: the result is RECHECKING (the scan reaches H and decides)");
    check(paymentHeight(&f) == TX_UNCONFIRMED, "G: the payment is still pending (the chain trails, the server is not the proof)");
    fxFree(&f);
}

static void test_H(void)
{
    printf("\n=== H: a held header before the filter chain exists: the tx stays pending ===\n");
    Fx f;
    fxInit(&f, 1);
    BRCompactFilterChainFree(f.m->compactFilterChain);
    f.m->compactFilterChain = NULL;
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H, 1700300015);
    printf("NOTE: apply(H) with no filter chain -> %d, payment height %u\n", r, paymentHeight(&f));
    check(r == BR_SERVER_HEIGHT_RECHECKING, "H: the result is RECHECKING");
    check(paymentHeight(&f) == TX_UNCONFIRMED, "H: the payment is still pending (no chain yet)");
    fxFree(&f);
}

static void test_I(void)
{
    printf("\n=== I: a held header below the filter chain's start is taken as reported (the residual) ===\n");
    Fx f;
    fxInit(&f, 1);
    fxChainOnly(&f, H, 1);                  // the filter chain starts at H; H0's header is held below it
    BRServerHeightResult r = BRPeerManagerApplyServerHeight(f.m, f.payment->txHash, H0, 1700300000);
    printf("NOTE: apply(H0) below the filter start %u -> %d, payment height %u\n",
           BRCompactFilterChainStartHeight(f.m->compactFilterChain), r, paymentHeight(&f));
    check(r == BR_SERVER_HEIGHT_TAKEN, "I: the result is TAKEN");
    check(paymentHeight(&f) == H0, "I: the payment is confirmed at the reported height (signature-checked)");
    fxFree(&f);
}

int main(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    BRSetNetwork(0);
    test_A_and_B();
    test_C();
    test_D();
    test_E();
    test_G();
    test_H();
    test_I();
    printf("\n%s: server_height_recheck_kat (%d failure%s)\n", g_fail ? "FAIL" : "PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
