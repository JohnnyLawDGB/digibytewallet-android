// Host KAT: a transaction reaches the wallet only as the answer to a request this wallet made of
// that peer, or inside a full block this wallet asked that peer for whose transactions hash to the
// block header's committed merkle root. Nothing from a block delivery is registered or confirmed
// before both hold.
//
// ENTRY LAYER. Every "block", "tx", "dandeliontx" and "notfound" message below goes through the
// real BRPeer.c dispatch (_BRPeerAcceptMessage, the function BRPeerAcceptMessageTest wraps and the
// socket read loop calls after the frame checksum). The handlers hand what they accept to the
// manager's own _peerRelayedTx / _peerRelayedBlockTxns, wired exactly as BRPeerManager.c wires
// them at connect time. The only thing interposed is a counter in front of relayedTx,
// relayedBlockTxns and relayedBlock, so a case can say how many deliveries the peer layer made.
// Requests are made with the production senders (BRPeerSendGetdata / BRPeerSendGetdataBlocks) on a
// socketless peer: the message is built and the request recorded, and the send itself finds no
// socket.
//
// FIXTURE. Resident headers come only from the production constructor: the real, prevBlock-linked
// mainnet headers 24278140..24278143 (checked against a local node, see ../merkleblock_pow_kat/
// headers.inc) are passed in as persisted blocks, plus one header on top of them at 24278144 whose
// merkle root commits to two transactions built here. A header carrying a payment to this wallet
// cannot be a real mainnet header (no real block pays this test mnemonic), so the case that must
// confirm uses that one; every case that needs a resident main-chain header and no payment uses
// the real ones. The real, complete block 24278140 (3 transactions, segwit serialization) is in
// block_24278140.inc.
//
// SEAM. -DBLOCK_DELIVERY_GATE_UNFIXED builds the earlier shape (a block's transactions handed on
// before any request or merkle check, a tx accepted once the peer was sent any getdata, the
// confirmation made before the request and merkle checks). run.sh requires the cases it lists as
// red to fail in that build and every case to pass in the shipped build.
//
// One case per process: `block_delivery_gate_kat <case>` prints "RESULT <case> pass|fail".
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

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#include "block_24278140.inc"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

#define DGB_SAT 100000000ULL
#define AMOUNT  (1000000ULL * DGB_SAT)

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

// Real mainnet headers, wire order (node: getblockheader <hash> false; same bytes as
// ../merkleblock_pow_kat/headers.inc).
static const char *H24278140 = "02060020194e2d72afdcc7936237e3af92c38d8247c3d2301b0a2673eda01db425829bb87b9c4e47d2ad9dab7089b860da50a6d6f0d039836ecf9a20bc2aa1d66f019fec4588b76a2c44011a102ba854";
static const char *H24278141 = "020600208a38c2bba385fcd06bc43159cdd43700b7baea57a38d17bda50871aff1da191d8c6d21c148e2e068f05b9649f90ead1510cda8f95da8c0abfc599036fc5f69486088b76af81d011a055ab110";
static const char *H24278142 = "020e0020ac454593a4a47975f27d489f76c875b2f5d128f08599c3518230fd769dce942342b040dbb23ce026f862a3b5b7ee2fceb356995841fb5173ebad52430e37198b7587b76acc26251a011ed277";
static const char *H24278143 = "020e0020f5273bd8ca5049fc9470474855902e87b03caba461749dbfb324d62e745110071b1eed8ffd450da8ca68c6454e19d341c8ea0bb2182d1ab9a4511e76cf0dbdd98f87b76a8be6201aedbe7421";
static const char *H24278145 = "02080020d9e81e70d03db6b2bc149df0f10590f328d78cc9ccfb303c0b1ff11c3ea94725c534542a1049bfa1d0e94b08d1aaa36f1aab8a9a92fdd8db3fb653301b859f4fb887b76a27931c1a3739ed41";

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

// ---- transactions -----------------------------------------------------------
static const uint8_t kSig[8] = { 0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58 };
static const uint8_t kWit[1] = { 0x00 };
static const uint8_t kOtherSpk[22] = { 0x00,0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 };

// An unsigned-in-substance transaction paying `amount` to `spk`, spending a prevout nobody has
// seen. Parsed back from its own bytes so txHash is exactly what the wire gives.
static BRTransaction *makeTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t seed)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;

    for (size_t i = 0; i < 32; i++) prev.u8[i] = (uint8_t)(seed*29u + i*11u + 1u);
    BRTransactionAddInput(tx, prev, seed, 0, NULL, 0, kSig, sizeof(kSig), kWit, sizeof(kWit), 0xffffffff);
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

static uint8_t *txBytes(const BRTransaction *tx, size_t *outLen)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(len);
    *outLen = BRTransactionSerialize(tx, d, len);
    return d;
}

// ---- block messages ---------------------------------------------------------
// header(80) | CompactSize(n) | each tx chunk. Chunks are raw serialized transactions.
typedef struct { const uint8_t *p; size_t len; } Chunk;

static uint8_t *blockMsg(const uint8_t hdr[80], const Chunk *chunks, size_t n, size_t *outLen)
{
    uint8_t vi[9];
    size_t viLen = BRVarIntSet(vi, sizeof(vi), n), total = 80 + viLen, off = 0;

    for (size_t i = 0; i < n; i++) total += chunks[i].len;
    uint8_t *m = malloc(total);
    memcpy(m, hdr, 80); off = 80;
    memcpy(&m[off], vi, viLen); off += viLen;
    for (size_t i = 0; i < n; i++) { memcpy(&m[off], chunks[i].p, chunks[i].len); off += chunks[i].len; }
    *outLen = total;
    return m;
}

static uint8_t *txBlockMsg(const uint8_t hdr[80], BRTransaction *const txs[], size_t n, size_t *outLen)
{
    Chunk c[8];
    uint8_t *bufs[8];

    assert(n <= 8);
    for (size_t i = 0; i < n; i++) { bufs[i] = txBytes(txs[i], &c[i].len); c[i].p = bufs[i]; }
    uint8_t *m = blockMsg(hdr, c, n, outLen);
    for (size_t i = 0; i < n; i++) free(bufs[i]);
    return m;
}

// The real block 24278140, and its transactions split into chunks the way the handler walks them.
static uint8_t g_realBlock[4096];
static size_t  g_realBlockLen;
static Chunk   g_realTx[3];

static void loadRealBlock(void)
{
    size_t hexLen = strlen(kBlock24278140Hex), off = 80, vl = 0;

    g_realBlockLen = hexLen/2;
    assert(g_realBlockLen <= sizeof(g_realBlock));
    hexBytes(g_realBlock, kBlock24278140Hex, g_realBlockLen);
    size_t n = (size_t)BRVarInt(&g_realBlock[off], g_realBlockLen - off, &vl);
    assert(n == 3);
    off += vl;
    for (size_t i = 0; i < 3; i++) {
        BRTransaction *t = BRTransactionParse(&g_realBlock[off], g_realBlockLen - off);
        assert(t != NULL);
        g_realTx[i].p = &g_realBlock[off];
        g_realTx[i].len = BRTransactionSerialize(t, NULL, 0);
        off += g_realTx[i].len;
        BRTransactionFree(t);
    }
    assert(off == g_realBlockLen);
}

// ---- counting callbacks in front of the manager's ---------------------------
static int g_relayedTx = 0, g_blockTxns = 0, g_relayedBlock = 0;

static void katRelayedTx(void *info, BRTransaction *tx) { g_relayedTx++; _peerRelayedTx(info, tx); }
static void katRelayedBlockTxns(void *info, UInt256 blockHash, UInt256 merkleRoot, const UInt256 txHashes[],
                                size_t txCount)
{
    g_blockTxns++;
    _peerRelayedBlockTxns(info, blockHash, merkleRoot, txHashes, txCount);
}
static void katRelayedBlock(void *info, BRMerkleBlock *block) { (void)info; g_relayedBlock++; BRMerkleBlockFree(block); }

// ---- fixture ----------------------------------------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    uint8_t spk[64];
    size_t spkLen;
    BRTransaction *tip[2];     // the two transactions header 24278144 commits to; tip[1] pays the wallet
    uint8_t tipHdr[80];
    UInt256 tipHash;
    BRTransaction *next[2];    // the two transactions header 24278145' commits to (never resident)
    uint8_t nextHdr[80];
    UInt256 nextHash;
    BRPeerCallbackInfo *infos[8];
    size_t infoCount;
} Fx;

static void buildHeader(uint8_t hdr[80], UInt256 prevBlock, UInt256 root, uint32_t time, uint32_t bits,
                        uint32_t nonce)
{
    UInt32SetLE(&hdr[0], 0x20000002);
    UInt256Set(&hdr[4], prevBlock);
    UInt256Set(&hdr[36], root);
    UInt32SetLE(&hdr[68], time);
    UInt32SetLE(&hdr[72], bits);
    UInt32SetLE(&hdr[76], nonce);
}

static void fxInit(Fx *f)
{
    memset(f, 0, sizeof(*f));

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, mpk);
    assert(f->w != NULL);
    BRAddress a = BRWalletReceiveAddress(f->w, 1);
    f->spkLen = BRAddressScriptPubKey(f->spk, sizeof(f->spk), a.s);
    assert(f->spkLen > 0);

    const char *run[4] = { H24278140, H24278141, H24278142, H24278143 };
    BRMerkleBlock *blocks[5];

    for (int i = 0; i < 4; i++) {
        uint8_t h[80];
        hexBytes(h, run[i], 80);
        blocks[i] = BRMerkleBlockParse(h, 80);
        blocks[i]->height = 24278140u + (uint32_t)i;
    }

    // 24278144: on top of the real run, committing to tip[0] (pays elsewhere) and tip[1] (pays the wallet)
    f->tip[0] = makeTx(kOtherSpk, sizeof(kOtherSpk), 5*DGB_SAT, 0x60);
    f->tip[1] = makeTx(f->spk, f->spkLen, AMOUNT, 0x61);
    UInt256 ids[2] = { f->tip[0]->txHash, f->tip[1]->txHash }, root;
    assert(BRMerkleRootFromTxHashes(&root, ids, 2));
    buildHeader(f->tipHdr, blocks[3]->blockHash, root, blocks[3]->timestamp + 15, blocks[3]->target, 7);
    blocks[4] = BRMerkleBlockParse(f->tipHdr, 80);
    blocks[4]->height = 24278144u;
    f->tipHash = blocks[4]->blockHash;

    // 24278145': above the tip, never handed to the constructor
    f->next[0] = makeTx(kOtherSpk, sizeof(kOtherSpk), 6*DGB_SAT, 0x70);
    f->next[1] = makeTx(f->spk, f->spkLen, AMOUNT, 0x71);
    UInt256 ids2[2] = { f->next[0]->txHash, f->next[1]->txHash };
    assert(BRMerkleRootFromTxHashes(&root, ids2, 2));
    buildHeader(f->nextHdr, f->tipHash, root, blocks[3]->timestamp + 30, blocks[3]->target, 9);
    BRSHA256_2(&f->nextHash, f->nextHdr, 80);

    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 1600000000u, blocks, 5, NULL, 0);
    assert(f->m != NULL);
    assert(f->m->lastBlock && f->m->lastBlock->height == 24278144u);
    assert(f->m->syncStartHeight == 0);
    loadRealBlock();
}

static BRPeer *fxPeer(Fx *f)
{
    BRPeerCallbackInfo *info = calloc(1, sizeof(*info));

    info->manager = f->m;
    info->peer = BRPeerNew(f->m->params->magicNumber);
    BRPeerSetCallbacks(info->peer, info, _peerConnected, _peerDisconnected, _peerRelayedPeers,
                       katRelayedTx, _peerHasTx, _peerRejectedTx, katRelayedBlock, katRelayedBlockTxns,
                       _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                       _peerNetworkIsReachable, _peerThreadCleanup);
    assert(f->infoCount < 8);
    f->infos[f->infoCount++] = info;
    return info->peer;
}

static void fxFree(Fx *f)
{
    for (size_t i = 0; i < f->infoCount; i++) { BRPeerFree(f->infos[i]->peer); free(f->infos[i]); }
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    for (int i = 0; i < 2; i++) { BRTransactionFree(f->tip[i]); BRTransactionFree(f->next[i]); }
}

// The dispatch BRPeerAcceptMessageTest wraps; its return value is the peer layer's verdict
// (0 would end the connection).
static int deliver(BRPeer *peer, const uint8_t *msg, size_t len, const char *type)
{
    return _BRPeerAcceptMessage(peer, msg, len, type);
}

static int deliverTx(BRPeer *peer, const BRTransaction *tx, const char *type)
{
    size_t len;
    uint8_t *d = txBytes(tx, &len);
    int r = deliver(peer, d, len, type);
    free(d);
    return r;
}

static void recordSolicited(Fx *f, UInt256 blockHash, uint32_t height)
{
    MGR_LOCK(f->m);
    _BRPeerManagerRecordSolicitedBlockLocked(f->m, blockHash, height);
    MGR_UNLOCK(f->m);
}

static int solicitedOutstanding(Fx *f, UInt256 blockHash, uint32_t height)
{
    MGR_LOCK(f->m);
    int i = _BRPeerManagerFindSolicitedBlockLocked(f->m, blockHash, height);
    MGR_UNLOCK(f->m);
    return i >= 0;
}

static UInt256 hdrHash(const char *hex)
{
    uint8_t h[80];
    UInt256 r;
    hexBytes(h, hex, 80);
    BRSHA256_2(&r, h, 80);
    return r;
}

static uint8_t *notfoundMsg(uint32_t type, UInt256 hash, size_t *outLen)
{
    uint8_t *m = malloc(1 + 36);
    m[0] = 1;
    UInt32SetLE(&m[1], type);
    UInt256Set(&m[5], hash);
    *outLen = 37;
    return m;
}

// =============================================================================
// Cases
// =============================================================================

// A block nobody asked this peer for, carrying the real header of a resident main-chain block
// (24278141, below the tip) and a transaction paying the wallet: nothing reaches the wallet.
static void case_unsolicited_block_resident_header(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    uint8_t hdr[80]; hexBytes(hdr, H24278141, 80);
    BRTransaction *pay = makeTx(f->spk, f->spkLen, AMOUNT, 0x11);
    size_t len; uint8_t *msg = txBlockMsg(hdr, &pay, 1, &len);

    int r = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, pay->txHash);
    printf("NOTE: r=%d relayedTx=%d record=%s height=%u balance=%" PRIu64 "\n", r, g_relayedTx,
           rec ? "yes" : "no", rec ? rec->blockHeight : 0u, BRWalletBalance(f->w));
    check(r == 1, "the peer is kept (message ignored, not a protocol error)");
    check(g_relayedTx == 0 && g_blockTxns == 0, "no transaction handed on from an unrequested block");
    check(rec == NULL, "no wallet record");
    check(BRWalletBalance(f->w) == 0, "balance unchanged");
    free(msg); BRTransactionFree(pay);
}

// Same, with a real header that is not resident (24278145).
static void case_unsolicited_block_unknown_header(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    uint8_t hdr[80]; hexBytes(hdr, H24278145, 80);
    BRTransaction *pay = makeTx(f->spk, f->spkLen, AMOUNT, 0x12);
    size_t len; uint8_t *msg = txBlockMsg(hdr, &pay, 1, &len);

    int r = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, pay->txHash);
    printf("NOTE: r=%d relayedTx=%d record=%s balance=%" PRIu64 "\n", r, g_relayedTx, rec ? "yes" : "no",
           BRWalletBalance(f->w));
    check(r == 1, "the peer is kept");
    check(g_relayedTx == 0 && g_blockTxns == 0, "no transaction handed on");
    check(rec == NULL && BRWalletBalance(f->w) == 0, "no wallet record, balance unchanged");
    free(msg); BRTransactionFree(pay);
}

// The wallet asked this peer for block 24278140 (and the manager recorded the solicitation). The
// answer carries the real header but a tx list that does not hash to its merkle root (the real
// third transaction replaced by one paying the wallet): nothing reaches the wallet, and the request
// stays open, so the real block from the same peer is still taken afterwards.
static void case_solicited_block_root_mismatch(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    UInt256 h140 = hdrHash(H24278140);
    BRTransaction *pay = makeTx(f->spk, f->spkLen, AMOUNT, 0x13);
    size_t payLen; uint8_t *payBytes = txBytes(pay, &payLen);
    Chunk c[3] = { g_realTx[0], g_realTx[1], { payBytes, payLen } };
    size_t len; uint8_t *msg = blockMsg(g_realBlock, c, 3, &len);

    recordSolicited(f, h140, 24278140u);
    BRPeerSendGetdataBlocks(peer, &h140, 1);

    int r = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, pay->txHash);
    printf("NOTE: altered list: r=%d relayedTx=%d blockTxns=%d record=%s height=%u balance=%" PRIu64 "\n", r,
           g_relayedTx, g_blockTxns, rec ? "yes" : "no", rec ? rec->blockHeight : 0u, BRWalletBalance(f->w));
    check(r == 1, "the peer is kept");
    check(g_relayedTx == 0 && g_blockTxns == 0, "no transaction handed on from a list that does not match the root");
    check(rec == NULL && BRWalletBalance(f->w) == 0, "no wallet record, balance unchanged");
    check(solicitedOutstanding(f, h140, 24278140u), "the height's solicitation is still outstanding");

    // the real block, same peer: still accepted
    g_relayedTx = g_blockTxns = 0;
    r = deliver(peer, g_realBlock, g_realBlockLen, MSG_BLOCK);
    printf("NOTE: real block after: r=%d relayedTx=%d blockTxns=%d\n", r, g_relayedTx, g_blockTxns);
    check(r == 1 && g_relayedTx == 3 && g_blockTxns == 1, "the real block that follows is still taken");
    free(msg); free(payBytes); BRTransactionFree(pay);
}

// GUARD. A real mainnet block (segwit transactions, odd row count) asked for and delivered intact
// passes the peer gate and the manager's own merkle check, which retires the solicitation.
static void case_real_block_verified(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    UInt256 h140 = hdrHash(H24278140);

    recordSolicited(f, h140, 24278140u);
    BRPeerSendGetdataBlocks(peer, &h140, 1);
    int r = deliver(peer, g_realBlock, g_realBlockLen, MSG_BLOCK);
    printf("NOTE: r=%d relayedTx=%d blockTxns=%d\n", r, g_relayedTx, g_blockTxns);
    check(r == 1 && g_relayedTx == 3 && g_blockTxns == 1, "every transaction of the real block is handed on");
    check(! solicitedOutstanding(f, h140, 24278140u), "the manager verified it and retired the solicitation");
}

// GUARD. A requested, verified block at the resident tip (24278144) paying the wallet: the
// receive is registered and confirmed at that height.
static void case_solicited_block_verified_confirms(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    size_t len; uint8_t *msg = txBlockMsg(f->tipHdr, f->tip, 2, &len);

    recordSolicited(f, f->tipHash, 24278144u);
    BRPeerSendGetdataBlocks(peer, &f->tipHash, 1);
    int r = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, f->tip[1]->txHash);
    printf("NOTE: r=%d relayedTx=%d record=%s height=%u balance=%" PRIu64 "\n", r, g_relayedTx, rec ? "yes" : "no",
           rec ? rec->blockHeight : 0u, BRWalletBalance(f->w));
    check(r == 1 && g_relayedTx == 2 && g_blockTxns == 1, "both transactions handed on");
    check(rec != NULL && rec->blockHeight == 24278144u, "the receive is registered and confirmed at 24278144");
    check(BRWalletBalance(f->w) == AMOUNT, "balance credited");
    check(! solicitedOutstanding(f, f->tipHash, 24278144u), "the solicitation is retired");
    free(msg);
}

// GUARD. A requested block whose header is not resident yet, whose tx list matches its own
// header's root: the receive is registered unconfirmed and the pending-confirm record is kept.
static void case_solicited_block_header_not_resident(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    size_t len; uint8_t *msg = txBlockMsg(f->nextHdr, f->next, 2, &len);
    UInt256 pending[4];

    BRPeerSendGetdataBlocks(peer, &f->nextHash, 1);
    int r = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rec = BRWalletTransactionForHash(f->w, f->next[1]->txHash);
    MGR_LOCK(f->m);
    size_t pend = BRCFScanLedgerTakePending(&f->m->cfLedger, f->nextHash, pending, 4);
    MGR_UNLOCK(f->m);
    printf("NOTE: r=%d relayedTx=%d record=%s height=%u pending=%zu\n", r, g_relayedTx, rec ? "yes" : "no",
           rec ? rec->blockHeight : 0u, pend);
    check(r == 1 && g_relayedTx == 2 && g_blockTxns == 1, "both transactions handed on");
    check(rec != NULL && rec->blockHeight == TX_UNCONFIRMED, "the receive is registered, unconfirmed");
    check(BRWalletBalance(f->w) == AMOUNT, "the unconfirmed receive is counted");
    check(pend > 0, "the pending-confirm record for the not-yet-resident block exists");
    free(msg);
}

// A standalone tx (and a dandeliontx) from a peer that was sent a getdata for something else.
static void case_tx_after_unrelated_getdata(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    UInt256 other; memset(other.u8, 0x77, sizeof(other.u8));
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x21), *b = makeTx(f->spk, f->spkLen, AMOUNT, 0x22);

    BRPeerSendGetdata(peer, &other, 1, NULL, 0);
    int r1 = deliverTx(peer, a, MSG_TX), r2 = deliverTx(peer, b, MSG_DANDELION_TX);
    BRTransaction *ra = BRWalletTransactionForHash(f->w, a->txHash), *rb = BRWalletTransactionForHash(f->w, b->txHash);
    printf("NOTE: r=%d/%d relayedTx=%d records=%s/%s balance=%" PRIu64 "\n", r1, r2, g_relayedTx, ra ? "yes" : "no",
           rb ? "yes" : "no", BRWalletBalance(f->w));
    check(r1 == 1 && r2 == 1, "the peer is kept");
    check(g_relayedTx == 0, "neither message handed on");
    check(ra == NULL && rb == NULL && BRWalletBalance(f->w) == 0, "no wallet record, balance unchanged");
    BRTransactionFree(a); BRTransactionFree(b);
}

// GUARD. A tx (and a dandeliontx) this peer was asked for is registered.
static void case_requested_tx_registered(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x23), *b = makeTx(f->spk, f->spkLen, AMOUNT, 0x24);
    UInt256 want[2] = { a->txHash, b->txHash };

    BRPeerSendGetdata(peer, want, 2, NULL, 0);
    int r1 = deliverTx(peer, a, MSG_TX), r2 = deliverTx(peer, b, MSG_DANDELION_TX);
    BRTransaction *ra = BRWalletTransactionForHash(f->w, a->txHash), *rb = BRWalletTransactionForHash(f->w, b->txHash);
    printf("NOTE: r=%d/%d relayedTx=%d records=%s/%s\n", r1, r2, g_relayedTx, ra ? "yes" : "no", rb ? "yes" : "no");
    check(r1 == 1 && r2 == 1 && g_relayedTx == 2, "both answers handed on");
    check(ra && ra->blockHeight == TX_UNCONFIRMED && rb && rb->blockHeight == TX_UNCONFIRMED,
          "both registered, unconfirmed");
    BRTransactionFree(a); BRTransactionFree(b);
}

// A requested tx answered twice: the second copy is not handed on.
static void case_tx_answered_twice(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x25);

    BRPeerSendGetdata(peer, &a->txHash, 1, NULL, 0);
    int r1 = deliverTx(peer, a, MSG_TX);
    int first = g_relayedTx;
    int r2 = deliverTx(peer, a, MSG_TX);
    printf("NOTE: r=%d/%d relayedTx after first=%d after second=%d\n", r1, r2, first, g_relayedTx);
    check(first == 1 && BRWalletTransactionForHash(f->w, a->txHash) != NULL, "the answer is taken");
    check(r2 == 1 && g_relayedTx == 1, "the second copy is not handed on; the peer is kept");
    BRTransactionFree(a);
}

// GUARD. While a merkleblock's matched transactions are being collected, a tx that block proved is
// taken even though this peer was never asked for it by hash. (No handler in this core starts such
// a collection today; the field is set directly.)
static void case_merkleblock_proven_tx(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRPeerContext *ctx = (BRPeerContext *)peer;
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x26);
    UInt256 other; memset(other.u8, 0x78, sizeof(other.u8));

    BRPeerSendGetdata(peer, &other, 1, NULL, 0);  // so the earlier shape's getdata precondition holds too
    ctx->currentBlock = BRMerkleBlockNew();
    array_add(ctx->currentBlockTxHashes, a->txHash);
    array_add(ctx->currentBlockTxHashes, other);   // one more still expected: the collection stays open

    int r = deliverTx(peer, a, MSG_TX);
    BRTransaction *ra = BRWalletTransactionForHash(f->w, a->txHash);
    printf("NOTE: r=%d relayedTx=%d record=%s left=%zu\n", r, g_relayedTx, ra ? "yes" : "no",
           array_count(ctx->currentBlockTxHashes));
    check(r == 1 && g_relayedTx == 1 && ra != NULL, "the proven transaction is taken");
    check(array_count(ctx->currentBlockTxHashes) == 1 && g_relayedBlock == 0, "the collection advanced by one");

    BRMerkleBlockFree(ctx->currentBlock);
    ctx->currentBlock = NULL;
    array_clear(ctx->currentBlockTxHashes);
    BRTransactionFree(a);
}

// A requested, verified block answered twice: the second delivery is ignored.
static void case_block_answered_twice(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    size_t len; uint8_t *msg = txBlockMsg(f->tipHdr, f->tip, 2, &len);

    recordSolicited(f, f->tipHash, 24278144u);
    BRPeerSendGetdataBlocks(peer, &f->tipHash, 1);
    int r1 = deliver(peer, msg, len, MSG_BLOCK);
    int tx1 = g_relayedTx, bt1 = g_blockTxns;
    int r2 = deliver(peer, msg, len, MSG_BLOCK);
    printf("NOTE: r=%d/%d relayedTx %d->%d blockTxns %d->%d\n", r1, r2, tx1, g_relayedTx, bt1, g_blockTxns);
    check(r1 == 1 && tx1 == 2 && bt1 == 1, "the first delivery is taken");
    check(r2 == 1 && g_relayedTx == 2 && g_blockTxns == 1, "the second delivery is ignored; the peer is kept");
    free(msg);
}

// The per-peer record of requested tx hashes is bounded: requesting one more than the bound drops
// the oldest request (its answer is no longer taken) and the record does not grow past the bound.
static void case_request_set_bound(Fx *f)
{
    BRPeer *peer = fxPeer(f);
#ifdef PEER_REQUESTED_HASHES_MAX
    const size_t bound = PEER_REQUESTED_HASHES_MAX;
#else
    const size_t bound = MAX_GETDATA_HASHES; // the earlier shape has no record; the value only sizes the requests
#endif
    BRTransaction *oldest = makeTx(f->spk, f->spkLen, AMOUNT, 0x27), *newest = makeTx(f->spk, f->spkLen, AMOUNT, 0x28);
    UInt256 *hashes = calloc(MAX_GETDATA_HASHES, sizeof(*hashes));

    // first getdata: `oldest` then fillers, MAX_GETDATA_HASHES items in all (the most one getdata carries)
    hashes[0] = oldest->txHash;
    for (size_t i = 1; i < MAX_GETDATA_HASHES; i++) { hashes[i].u32[0] = (uint32_t)i; hashes[i].u8[31] = 0xA5; }
    BRPeerSendGetdata(peer, hashes, MAX_GETDATA_HASHES, NULL, 0);
    // then more fillers up to the bound, then `newest`: one past the bound in all
    size_t more = bound - MAX_GETDATA_HASHES;
    for (size_t i = 0; i < more; i++) {
        UInt256 h = UINT256_ZERO; h.u32[0] = (uint32_t)i; h.u8[31] = 0xA6;
        BRPeerSendGetdata(peer, &h, 1, NULL, 0);
    }
    BRPeerSendGetdata(peer, &newest->txHash, 1, NULL, 0);

#ifdef PEER_REQUESTED_HASHES_MAX
    BRPeerContext *ctx = (BRPeerContext *)peer;
    size_t held = _BRPeerRequestSetCount(ctx, &ctx->requestedTxHashes);
    printf("NOTE: bound=%zu held=%zu\n", bound, held);
    check(held == bound, "the record holds exactly the bound");
#else
    check(0, "the record holds exactly the bound (no bounded record in this build)");
#endif

    int r1 = deliverTx(peer, oldest, MSG_TX);
    BRTransaction *ro = BRWalletTransactionForHash(f->w, oldest->txHash);
    int r2 = deliverTx(peer, newest, MSG_TX);
    BRTransaction *rn = BRWalletTransactionForHash(f->w, newest->txHash);
    printf("NOTE: r=%d/%d oldest record=%s newest record=%s\n", r1, r2, ro ? "yes" : "no", rn ? "yes" : "no");
    check(r1 == 1 && ro == NULL, "the answer to the evicted (oldest) request is not taken");
    check(r2 == 1 && rn != NULL, "the answer to the newest request is taken");
    free(hashes); BRTransactionFree(oldest); BRTransactionFree(newest);
}

// A notfound for a request closes it: a tx or block for that hash arriving afterwards is not taken.
static void case_notfound_consumes_request(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x29);
    size_t nlen; uint8_t *nf;

    BRPeerSendGetdata(peer, &a->txHash, 1, NULL, 0);
    nf = notfoundMsg(inv_tx, a->txHash, &nlen);
    int r0 = deliver(peer, nf, nlen, MSG_NOTFOUND);
    free(nf);
    int r1 = deliverTx(peer, a, MSG_TX);
    BRTransaction *ra = BRWalletTransactionForHash(f->w, a->txHash);

    size_t len; uint8_t *msg = txBlockMsg(f->tipHdr, f->tip, 2, &len);
    recordSolicited(f, f->tipHash, 24278144u);
    BRPeerSendGetdataBlocks(peer, &f->tipHash, 1);
    nf = notfoundMsg(inv_block, f->tipHash, &nlen);
    int r2 = deliver(peer, nf, nlen, MSG_NOTFOUND);
    free(nf);
    int r3 = deliver(peer, msg, len, MSG_BLOCK);
    BRTransaction *rb = BRWalletTransactionForHash(f->w, f->tip[1]->txHash);
    printf("NOTE: r=%d/%d/%d/%d relayedTx=%d tx record=%s block record=%s\n", r0, r1, r2, r3, g_relayedTx,
           ra ? "yes" : "no", rb ? "yes" : "no");
    check(r0 == 1 && r1 == 1 && r2 == 1 && r3 == 1, "the peer is kept");
    check(ra == NULL, "the tx that follows its own notfound is not taken");
    check(rb == NULL && g_blockTxns == 0, "the block that follows its own notfound is not taken");
    check(g_relayedTx == 0 && BRWalletBalance(f->w) == 0, "nothing handed on, balance unchanged");
    free(msg); BRTransactionFree(a);
}

// The manager side on its own (_peerRelayedBlockTxns): a wallet record is confirmed by a block
// delivery only when the manager solicited that block and the delivered list hashes to the root.
static void case_manager_confirm_needs_verified(Fx *f)
{
    BRPeer *peer = fxPeer(f);
    BRPeerCallbackInfo info = { .peer = peer, .manager = f->m };
    BRTransaction *a = makeTx(f->spk, f->spkLen, AMOUNT, 0x2a), *b = makeTx(f->spk, f->spkLen, AMOUNT, 0x2b);
    UInt256 h141 = hdrHash(H24278141), h142 = hdrHash(H24278142);
    uint8_t hdr[80];
    UInt256 ha = a->txHash, hb = b->txHash;

    check(BRWalletRegisterTransaction(f->w, a) && BRWalletRegisterTransaction(f->w, b), "two unconfirmed records");

    // (i) not solicited, resident main-chain header 24278141
    hexBytes(hdr, H24278141, 80);
    _peerRelayedBlockTxns(&info, h141, UInt256Get(&hdr[36]), &ha, 1);
    // (ii) solicited 24278142, but the list [b] does not hash to its root
    hexBytes(hdr, H24278142, 80);
    recordSolicited(f, h142, 24278142u);
    _peerRelayedBlockTxns(&info, h142, UInt256Get(&hdr[36]), &hb, 1);

    BRTransaction *ra = BRWalletTransactionForHash(f->w, ha), *rb = BRWalletTransactionForHash(f->w, hb);
    printf("NOTE: heights %u / %u\n", ra ? ra->blockHeight : 0u, rb ? rb->blockHeight : 0u);
    check(ra && ra->blockHeight == TX_UNCONFIRMED, "not confirmed by a block the manager never solicited");
    check(rb && rb->blockHeight == TX_UNCONFIRMED, "not confirmed by a list that does not hash to the root");
}

typedef struct { const char *name; void (*fn)(Fx *); } Case;

static const Case kCases[] = {
    { "unsolicited_block_resident_header",     case_unsolicited_block_resident_header },
    { "unsolicited_block_unknown_header",      case_unsolicited_block_unknown_header },
    { "solicited_block_root_mismatch",         case_solicited_block_root_mismatch },
    { "real_block_verified",                   case_real_block_verified },
    { "solicited_block_verified_confirms",     case_solicited_block_verified_confirms },
    { "solicited_block_header_not_resident",   case_solicited_block_header_not_resident },
    { "tx_after_unrelated_getdata",            case_tx_after_unrelated_getdata },
    { "requested_tx_registered",               case_requested_tx_registered },
    { "tx_answered_twice",                     case_tx_answered_twice },
    { "merkleblock_proven_tx",                 case_merkleblock_proven_tx },
    { "block_answered_twice",                  case_block_answered_twice },
    { "request_set_bound",                     case_request_set_bound },
    { "notfound_consumes_request",             case_notfound_consumes_request },
    { "manager_confirm_needs_verified",        case_manager_confirm_needs_verified },
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
