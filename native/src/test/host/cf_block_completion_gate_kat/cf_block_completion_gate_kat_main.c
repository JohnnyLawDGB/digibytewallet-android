// Host KAT: the CF scan-height COMPLETION GATE (finding C1 — remote receive-hiding).
//
// THE DEFECT THIS GATE EXISTS FOR
// -------------------------------
// A compact-filter scan retires a height when the full block for that height is delivered:
// _peerRelayedBlockTxns (BRPeerManager.c) calls BRCFScanLedgerMarkEvaluated, which removes the
// height from `outstanding`, lets scannedThrough advance past it, and persists the ledger
// immediately. That handler is fed by BRPeer.c's `block` message path, which proves NEITHER of
// the two things the completion silently assumes:
//
//   1. NOT REQUEST-GATED. BRPeer.c dispatches MSG_BLOCK unconditionally and wires
//      relayedBlockTxns on EVERY connected peer, so any peer the wallet dials can send an
//      unsolicited `block` (a real, public 80-byte header + one parseable tx) and erase a hole
//      another peer was asked to fill. Because BRCFScanLedgerRecordRequested puts an ENTIRE
//      requested range into `outstanding` at getcfilters time, a peer that withholds its
//      cfilters and sprays blocks can drive scannedThrough all the way to requestedThrough
//      with ZERO filters ever evaluated.
//
//   2. NOT COMMITMENT-CHECKED. _BRPeerAcceptBlockMessage hashes only msg[0..80] for the block
//      hash and never checks the delivered transaction list against the header's committed
//      merkleRoot. So the peer that IS serving the height can answer our own getdata with the
//      genuine header (correct hash, resolves in manager->blocks, passes the main-chain walk)
//      and a tx list with the wallet's incoming payment REMOVED.
//
// Either way the height is marked scanned with the receive uncredited, the poisoned
// scannedThrough is written to disk on the spot, and nothing ever re-requests that height —
// the payment is invisible until a manual rescan. That is a fund-safety defect, not a sync bug.
//
// WHAT THIS KAT ASSERTS
// ---------------------
//   CRUX-A  an UNSOLICITED merkle-valid block does NOT complete an outstanding height
//           (while the tx-confirmation half is deliberately left working — the gate is
//           surgical, not a blanket bail-out)
//   CRUX-B  a SOLICITED block whose tx list fails the merkle commitment (a stripped tx) does
//           NOT complete the height, and does NOT burn the solicitation either, so the honest
//           block that follows still completes it
//   CRUX-C  an honest, solicited, merkle-valid block with NO wallet transactions at all STILL
//           completes the height — the gate is "did I ask for this and does it verify", never
//           "did it pay me"
//   CRUX-D  end-to-end: a cfilter that verifies against the committed cfheader chain and
//           matches the wallet records the solicitation at its getdata, and the full block
//           that answers it completes the height (proves the two halves are actually wired)
//   CRUX-E  a solicitation does not survive the scan it belongs to (re-arm / re-anchor /
//           disconnect clear the table)
//   CRUX-F  the table is BOUNDED: it cannot be grown without limit, and eviction degrades to
//           a re-request, never to a silent completion
//   CRUX-G  LIVENESS: verification uses the root committed by the DELIVERED header, so a
//           resident hardcoded-checkpoint STUB (which carries no merkleRoot of its own, and is
//           exactly what sits at the scan floor) does not make every honest delivery there
//           unverifiable and wedge the scan on the wallet's own birth block
//
// RED-BEFORE-GREEN: run.sh builds this twice. -DCF_BLOCK_COMPLETION_UNGATED_UNFIXED restores
// the pre-fix shape (unconditional MarkEvaluated + persist, exactly what shipped in the lab
// series) and MUST fail at CRUX-A and CRUX-B. The production shape must pass everything.
//
// Approach: #include "BRPeerManager.c" directly to reach the file-static
// _peerRelayedBlockTxns / _peerRelayedCFilter and the otherwise-opaque BRPeerManagerStruct /
// BRPeerCallbackInfo / BRCFSolicitedBlock definitions — the same #include-a-.c-for-statics
// pattern cf_confirm_kat and cf_scan_ledger_drive_kat use. BRPeerManager.c is therefore
// deliberately NOT also passed as a separate compilation unit in run.sh.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// --- getdata capture (link --wrap) ------------------------------------------
// CRUX-D drives the real _peerRelayedCFilter, which dispatches through BRPeer.c's public
// BRPeerSendGetdataBlocks. Intercepting it at link time keeps the KAT off any socket and makes
// "a getdata was actually put on the wire for this hash" directly observable. Signature
// verified against BRPeer.h — a mismatched --wrap shim silently fails to bind.
static int     g_getdataCount = 0;
static UInt256 g_getdataHash;

void __wrap_BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount)
{
    (void)peer;
    g_getdataCount += (int)blockCount;
    if (blockCount > 0) g_getdataHash = blockHashes[0];
}

// non-NULL, zero-length signature/witness placeholder — satisfies BRTransactionIsSigned's
// pointer-only check (BRWalletRegisterTransaction asserts it). Same trick cf_confirm_kat and
// digidollar_wallet_kat use; these synthetic txs are never cryptographically verified here.
static const uint8_t kPlaceholder[1] = {0};

// tx->txHash is only ever computed by BRTransactionParse or BRTransactionSign's post-sign round
// trip — never by AddInput/AddOutput alone. Round-trip to populate it before it is used as a
// merkle leaf or a lookup key.
static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// tx paying `spk` (a wallet scriptPubKey when spk is ours, a foreign one otherwise), spending a
// fabricated prevout — the completion gate never validates prevouts.
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

// A resident header. BRMerkleBlockHash/Eq only compare ->blockHash, so a hand-set height plus a
// distinct hash stands in for a real header — EXCEPT for merkleRoot, which the completion gate
// reads for real, so it is committed to the exact tx list this block will deliver.
static BRMerkleBlock *blockCommitting(uint32_t height, uint8_t hashSeed, uint32_t timestamp,
                                      const UInt256 *txHashes, size_t txCount)
{
    BRMerkleBlock *b = BRMerkleBlockNew();

    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = timestamp;
    if (txCount > 0 && ! BRMerkleRootFromTxHashes(&b->merkleRoot, txHashes, txCount)) {
        printf("FATAL: could not compute a merkle root for the fixture block at %u\n", height);
        exit(1);
    }
    return b;
}

static void txStatusUpdateCb(void *info) { *(int *)info = 1; }

static size_t solicitedCount(const BRPeerManager *m)
{
    size_t n = 0;

    for (size_t i = 0; i < CF_SOLICITED_BLOCKS_MAX; i++) if (m->cfSolicitedBlocks[i].used) n++;
    return n;
}

// ---- BIP158 filter builder (verbatim from cf_scan_ledger_drive_kat) --------
// CRUX-D needs a filter that both VERIFIES against the cfheader chain and MATCHES the wallet,
// because the solicitation is only recorded on that exact path.
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
static void test_unsolicited_block_cannot_complete(BRWallet *wallet, const uint8_t *spk, size_t spkLen)
{
    printf("\n=== CRUX-A: unsolicited full block must not complete a scan height ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    int txStatusFired = 0;
    uint32_t H = 5000000;

    BRPeerManagerSetCallbacks(m, &txStatusFired, NULL, NULL, txStatusUpdateCb, NULL, NULL, NULL, NULL);

    BRTransaction *tx = payTx(spk, spkLen, 100000, 0xA1);
    BRWalletRegisterTransaction(wallet, tx);
    UInt256 delivered[1] = { tx->txHash };

    // A genuine block: the header commits to exactly the tx list the attacker replays. This is
    // the STRONGEST form of the attack — nothing about the message is malformed, the peer simply
    // was not asked for it.
    BRMerkleBlock *b = blockCommitting(H, 0xA0, 1700000000, delivered, 1);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b; // trivially the main chain

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1, "setup: H is outstanding");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) < H, "setup: frontier is below H");
    check(solicitedCount(m) == 0, "setup: this wallet solicited nothing");

    BRPeerCallbackInfo info = { .peer = NULL, .manager = m, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, delivered, 1);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "CRUX-A: unsolicited full block does NOT complete an outstanding scan height");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) < H,
          "CRUX-A: scannedThrough does not advance past a height nobody was asked to scan");

    // The gate is surgical: relaying a block we did not ask for still CONFIRMS wallet txs it
    // carries, exactly as before. A blanket bail-out would also pass the two checks above, so
    // this is what stops the fix from being over-broad.
    BRTransaction *after = BRWalletTransactionForHash(wallet, tx->txHash);
    check(after != NULL && after->blockHeight == H,
          "CRUX-A: the tx-confirmation half is untouched — the tx still confirms at H");
    check(txStatusFired == 1, "CRUX-A: txStatusUpdate still fires for the confirmation");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-B: a solicited block whose tx list does not hash to the header's committed merkle root
// (the wallet's payment stripped out) must not retire the height — and must not consume the
// solicitation, or an attacker could lock the honest block out by racing it.
static void test_stripped_block_cannot_complete(BRWallet *wallet, const uint8_t *spk, size_t spkLen)
{
    printf("\n=== CRUX-B: solicited-but-stripped block must not complete the height ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    int txStatusFired = 0;
    uint32_t H = 5100000;

    BRPeerManagerSetCallbacks(m, &txStatusFired, NULL, NULL, txStatusUpdateCb, NULL, NULL, NULL, NULL);

    uint8_t foreignSpk[] = { 0x76, 0xa9, 0x14, 0x0b, 0xad, 0x0b, 0xad, 0x0b, 0xad, 0x0b, 0xad,
                             0x0b, 0xad, 0x0b, 0xad, 0x0b, 0xad, 0x0b, 0xad, 0x0b, 0xad, 0x88, 0xac };
    BRTransaction *coinbaseish = payTx(foreignSpk, sizeof(foreignSpk), 900000, 0xB1);
    BRTransaction *payment     = payTx(spk, spkLen, 250000, 0xB2); // OUR incoming receive

    // The honest block holds both, in order. The header commits to both.
    UInt256 honest[2] = { coinbaseish->txHash, payment->txHash };
    UInt256 stripped[1] = { coinbaseish->txHash }; // ...the receive removed

    BRMerkleBlock *b = blockCommitting(H, 0xB0, 1700100000, honest, 2);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b;

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);

    MGR_LOCK(m);
    _BRPeerManagerRecordSolicitedBlockLocked(m, b->blockHash, H); // we DID ask for this block
    MGR_UNLOCK(m);
    check(solicitedCount(m) == 1, "setup: the getdata for H is recorded as solicited");

    BRPeerCallbackInfo info = { .peer = NULL, .manager = m, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, stripped, 1); // real header, stripped list

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "CRUX-B: solicited block with a stripped tx list does NOT complete the height");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) < H,
          "CRUX-B: the frontier stays below H so the height is re-requested, not skipped");
    check(solicitedCount(m) == 1,
          "CRUX-B: a failed delivery does NOT burn the solicitation (no lock-out race)");

    // ...and the honest block that follows completes it. Without this the gate could be
    // satisfied by simply never completing anything.
    //
    // The receive is registered only now, on purpose: the stripped delivery above is exactly
    // the case where the wallet does NOT yet hold the payment (that is what the attack hides),
    // so it must be the RE-REQUESTED honest block that both credits it and retires the height.
    BRWalletRegisterTransaction(wallet, payment);
    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, honest, 2);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0,
          "CRUX-B: the honest, complete tx list DOES complete the height");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == H,
          "CRUX-B: scannedThrough advances to H only on the verified delivery");
    check(solicitedCount(m) == 0, "CRUX-B: the solicitation is consumed on success");

    BRTransaction *after = BRWalletTransactionForHash(wallet, payment->txHash);
    check(after != NULL && after->blockHeight == H, "CRUX-B: the recovered receive is confirmed at H");

    BRTransactionFree(coinbaseish);
    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-C: a solicited, merkle-valid block containing NO wallet transactions still completes the
// height. A cfilter match can be a false positive (GCS is probabilistic) and a matched block can
// legitimately hold nothing of ours — if that failed to complete, the scan would wedge forever
// on its own filter's false positive.
static void test_verified_block_with_no_wallet_tx_completes(BRWallet *wallet)
{
    printf("\n=== CRUX-C: verified solicited block with zero wallet txs still completes ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    int txStatusFired = 0;
    uint32_t H = 5200000;

    BRPeerManagerSetCallbacks(m, &txStatusFired, NULL, NULL, txStatusUpdateCb, NULL, NULL, NULL, NULL);

    uint8_t foreignSpk[] = { 0x76, 0xa9, 0x14, 0xfe, 0xed, 0xfa, 0xce, 0xfe, 0xed, 0xfa, 0xce,
                             0xfe, 0xed, 0xfa, 0xce, 0xfe, 0xed, 0xfa, 0xce, 0xfe, 0xed, 0x88, 0xac };
    BRTransaction *t1 = payTx(foreignSpk, sizeof(foreignSpk), 111, 0xC1);
    BRTransaction *t2 = payTx(foreignSpk, sizeof(foreignSpk), 222, 0xC2);
    BRTransaction *t3 = payTx(foreignSpk, sizeof(foreignSpk), 333, 0xC3);
    UInt256 delivered[3] = { t1->txHash, t2->txHash, t3->txHash }; // odd count -> exercises row duplication

    check(BRWalletTransactionForHash(wallet, t1->txHash) == NULL, "setup: none of these are wallet txs");

    BRMerkleBlock *b = blockCommitting(H, 0xC0, 1700200000, delivered, 3);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b;

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);
    MGR_LOCK(m);
    _BRPeerManagerRecordSolicitedBlockLocked(m, b->blockHash, H);
    MGR_UNLOCK(m);

    BRPeerCallbackInfo info = { .peer = NULL, .manager = m, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, delivered, 3);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0,
          "CRUX-C: walletCount==0 does NOT block completion — the gate is 'asked + verified'");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == H, "CRUX-C: scannedThrough advances to H");
    check(txStatusFired == 0, "CRUX-C: no confirmation fired (there was nothing of ours in it)");

    BRTransactionFree(t1); BRTransactionFree(t2); BRTransactionFree(t3);
    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-D (end-to-end): the ONLY thing allowed to record a solicitation is a cfilter that
// verified against the committed cfheader chain and matched the wallet. This drives the real
// _peerRelayedCFilter and then the real delivery, so the two halves are proven wired together
// rather than each being individually plausible.
static void test_verified_cfilter_match_arms_completion(BRWallet *wallet, const uint8_t *spk, size_t spkLen)
{
    printf("\n=== CRUX-D: verified cfilter match arms the completion, block delivery closes it ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    uint32_t H0 = 5300000, H = H0 + 1;

    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    BRTransaction *payment = payTx(spk, spkLen, 400000, 0xD2);
    BRWalletRegisterTransaction(wallet, payment);
    UInt256 delivered[1] = { payment->txHash };

    BRMerkleBlock *b0 = blockCommitting(H0, 0xD0, 1700300000, NULL, 0);
    BRMerkleBlock *b  = blockCommitting(H,  0xD1, 1700300015, delivered, 1);
    b->prevBlock = b0->blockHash;
    BRSetAdd(m->blocks, b0);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b;

    // cfheader chain: a dummy header for H0, then the REAL hash of the filter we are about to
    // hand in for H — so BRCompactFilterChainVerifyFilter accepts it (and would reject anything
    // else, which is the whole point of the commitment chain).
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H0, UINT256_ZERO);
    UInt256 dummyFilterHash;
    memset(dummyFilterHash.u8, 0x77, sizeof(dummyFilterHash.u8));
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &dummyFilterHash, 1) == 1, "setup: dummy cfheader for H0");

    BRWalletFilterElements *fe = BRWalletGetFilterElements(wallet);
    check(fe != NULL && fe->count > 0, "setup: wallet has filter elements");
    uint8_t encoded[16];
    size_t encodedLen = buildSingleElementFilter(b->blockHash, fe->elements[0], fe->elementLens[0],
                                                 encoded, sizeof encoded);
    UInt256 filterHash;
    BRSHA256_2(filterHash.u8, encoded, encodedLen);
    BRWalletFilterElementsFree(fe);
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &filterHash, 1) == 1, "setup: real cfheader for H");
    check(BRCompactFilterChainVerifyFilter(m->compactFilterChain, H, encoded, encodedLen) == 1,
          "setup: the filter verifies against the committed chain");

    BRCFScanLedgerInit(&m->cfLedger, H0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);

    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);
    peer->address.u8[15] = 0x01;
    peer->port = 10001;
    peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, peer);

    BRPeerCallbackInfo info = { .peer = peer, .manager = m, .hash = UINT256_ZERO };
    g_getdataCount = 0;
    _peerRelayedCFilter(&info, FILTER_TYPE_BASIC, b->blockHash, encoded, encodedLen);

    check(g_getdataCount == 1, "CRUX-D: the verified match dispatched exactly one getdata");
    check(UInt256Eq(g_getdataHash, b->blockHash), "CRUX-D: the getdata targeted H's block hash");
    check(solicitedCount(m) == 1, "CRUX-D: the getdata recorded a solicitation");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "CRUX-D: the matched height stays outstanding until the block is delivered");

    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, delivered, 1);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0,
          "CRUX-D: the answering block completes the height it was solicited for");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == H, "CRUX-D: scannedThrough advances to H");

    BRTransaction *after = BRWalletTransactionForHash(wallet, payment->txHash);
    check(after != NULL && after->blockHeight == H, "CRUX-D: the receive is credited at H");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-E: a solicitation belongs to the scan that issued it. Re-arming the scan (the
// re-anchor/enable path) must drop it, so a block that arrives afterwards cannot retire a hole
// in a ledger that was rebuilt underneath it.
static void test_rearm_drops_solicitations(BRWallet *wallet, const uint8_t *spk, size_t spkLen)
{
    printf("\n=== CRUX-E: a re-armed scan does not honour the old scan's solicitations ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    uint32_t H = 5400000;

    BRTransaction *tx = payTx(spk, spkLen, 100000, 0xE1);
    BRWalletRegisterTransaction(wallet, tx);
    UInt256 delivered[1] = { tx->txHash };

    BRMerkleBlock *b = blockCommitting(H, 0xE0, 1700400000, delivered, 1);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b;

    MGR_LOCK(m);
    _BRPeerManagerRecordSolicitedBlockLocked(m, b->blockHash, H);
    MGR_UNLOCK(m);
    check(solicitedCount(m) == 1, "setup: a solicitation is in flight");

    BRPeerManagerDisableAutoCompactFilterFetch(m); // the scan this getdata belonged to is gone
    check(solicitedCount(m) == 0, "CRUX-E: disarming the scan cleared the solicitation table");

    // A fresh ledger with H outstanding again — the OLD solicitation must not complete it.
    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);

    BRPeerCallbackInfo info = { .peer = NULL, .manager = m, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info, b->blockHash, b->merkleRoot, delivered, 1);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "CRUX-E: a stale solicitation cannot complete a height in the rebuilt ledger");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-F: the table is bounded and manager-inline, so it can never grow without limit no matter
// how many matches are in flight. Eviction is oldest-first and costs a re-request — the newest
// solicitation, the one most likely still in flight, survives.
static void test_solicitation_table_is_bounded(BRWallet *wallet)
{
    printf("\n=== CRUX-F: the solicitation table is bounded, eviction is oldest-first ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    UInt256 first, last;
    const int extra = 8;

    MGR_LOCK(m);
    for (int i = 0; i < CF_SOLICITED_BLOCKS_MAX + extra; i++) {
        UInt256 h = UINT256_ZERO;

        h.u32[0] = (uint32_t)i + 1;
        h.u32[7] = 0xC0FFEE;
        if (i == 0) first = h;
        last = h;
        _BRPeerManagerRecordSolicitedBlockLocked(m, h, 6000000 + (uint32_t)i);
    }
    MGR_UNLOCK(m);

    check(solicitedCount(m) == CF_SOLICITED_BLOCKS_MAX,
          "CRUX-F: the table saturates at CF_SOLICITED_BLOCKS_MAX — no unbounded growth");
    check(_BRPeerManagerFindSolicitedBlockLocked(m, last, 6000000 + CF_SOLICITED_BLOCKS_MAX + extra - 1) >= 0,
          "CRUX-F: the newest solicitation survives");
    check(_BRPeerManagerFindSolicitedBlockLocked(m, first, 6000000) < 0,
          "CRUX-F: the oldest solicitation was the one evicted");

    // A re-request of a hash already in the table refreshes it in place rather than consuming a
    // second slot — otherwise a stubborn height would evict the whole table by itself.
    size_t before = solicitedCount(m);
    MGR_LOCK(m);
    _BRPeerManagerRecordSolicitedBlockLocked(m, last, 6000000 + CF_SOLICITED_BLOCKS_MAX + extra - 1);
    MGR_UNLOCK(m);
    check(solicitedCount(m) == before, "CRUX-F: re-requesting the same block does not consume a second slot");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// CRUX-G (liveness, and the reason the root comes from the DELIVERED header): every hardcoded
// checkpoint is seeded into manager->blocks as a STUB with no merkleRoot, and the CF scan floor
// sits on one — manager->lastBlock at arm time IS that stub, and a restored wallet's birth
// height is derived from its first transaction, so the floor block is precisely the block most
// likely to match the wallet. If verification checked the RESIDENT header's (zero) root, the
// honest block answering that match could never verify, the height would never retire, and the
// scan would wedge on the wallet's own birth block forever.
static void test_stub_header_does_not_wedge_the_floor(BRWallet *wallet, const uint8_t *spk, size_t spkLen)
{
    printf("\n=== CRUX-G: a resident checkpoint stub must not make an honest delivery unverifiable ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    uint32_t H = 5500000;

    BRTransaction *tx = payTx(spk, spkLen, 175000, 0xF1);
    BRWalletRegisterTransaction(wallet, tx);
    UInt256 delivered[1] = { tx->txHash };
    UInt256 deliveredRoot;

    check(BRMerkleRootFromTxHashes(&deliveredRoot, delivered, 1) == 1, "setup: the delivered list has a root");

    // A stub: hash + height only, exactly what BRPeerManagerNewEx seeds from params->checkpoints.
    BRMerkleBlock *stub = blockCommitting(H, 0xF0, 1700500000, NULL, 0);
    check(UInt256IsZero(stub->merkleRoot), "setup: the resident header is a stub with NO committed root");
    BRSetAdd(m->blocks, stub);
    m->lastBlock = stub;

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 1);
    MGR_LOCK(m);
    _BRPeerManagerRecordSolicitedBlockLocked(m, stub->blockHash, H);
    MGR_UNLOCK(m);

    BRPeerCallbackInfo info = { .peer = NULL, .manager = m, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info, stub->blockHash, deliveredRoot, delivered, 1);

    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0,
          "CRUX-G: an honest delivery still completes a height whose resident header is a stub");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == H, "CRUX-G: the floor does not wedge");

    // ...and the gate is still closed there: a stripped list at a stub height must NOT complete.
    BRPeerManager *m2 = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    uint32_t H2 = 5600000;
    BRTransaction *other = payTx(spk, spkLen, 42000, 0xF2);
    UInt256 pairList[2] = { tx->txHash, other->txHash };
    UInt256 pairRoot, strippedList[1] = { other->txHash };

    check(BRMerkleRootFromTxHashes(&pairRoot, pairList, 2) == 1, "setup: two-tx root");
    BRMerkleBlock *stub2 = blockCommitting(H2, 0xF3, 1700600000, NULL, 0);
    BRSetAdd(m2->blocks, stub2);
    m2->lastBlock = stub2;
    BRCFScanLedgerInit(&m2->cfLedger, H2);
    BRCFScanLedgerRecordRequested(&m2->cfLedger, H2, H2, UINT128_ZERO, 0, 1);
    MGR_LOCK(m2);
    _BRPeerManagerRecordSolicitedBlockLocked(m2, stub2->blockHash, H2);
    MGR_UNLOCK(m2);

    BRPeerCallbackInfo info2 = { .peer = NULL, .manager = m2, .hash = UINT256_ZERO };
    _peerRelayedBlockTxns(&info2, stub2->blockHash, pairRoot, strippedList, 1);

    check(BRCFScanLedgerOutstandingCount(&m2->cfLedger) == 1,
          "CRUX-G: a stripped list at a stub height is STILL refused (liveness fix is not a hole)");

    BRTransactionFree(other);
    BRPeerManagerFree(m2);
    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// The merkle primitive itself, in isolation: the completion gate is only as good as this.
static void test_merkle_root_primitive(void)
{
    printf("\n=== merkle primitive (BRMerkleRootFromTxHashes) ===\n");

    UInt256 a, b, c, root2, root3, rootDup, tmp;

    memset(a.u8, 0x01, sizeof(a.u8));
    memset(b.u8, 0x02, sizeof(b.u8));
    memset(c.u8, 0x03, sizeof(c.u8));

    UInt256 one[1] = { a };
    check(BRMerkleRootFromTxHashes(&tmp, one, 1) == 1 && UInt256Eq(tmp, a),
          "merkle: a single-tx block's root IS the txid");

    UInt256 two[2] = { a, b };
    UInt256 pair[2] = { a, b };
    UInt256 expect2;
    BRSHA256_2(&expect2, pair, sizeof(pair));
    check(BRMerkleRootFromTxHashes(&root2, two, 2) == 1 && UInt256Eq(root2, expect2),
          "merkle: two txs hash as SHA256d(txid0 || txid1)");

    UInt256 three[3] = { a, b, c };
    UInt256 lo[2] = { a, b }, hi[2] = { c, c }, top[2];
    BRSHA256_2(&top[0], lo, sizeof(lo));
    BRSHA256_2(&top[1], hi, sizeof(hi)); // odd row duplicates the last entry
    UInt256 expect3;
    BRSHA256_2(&expect3, top, sizeof(top));
    check(BRMerkleRootFromTxHashes(&root3, three, 3) == 1 && UInt256Eq(root3, expect3),
          "merkle: an odd row duplicates its last entry (Bitcoin/DigiByte rule)");

    UInt256 dup[2] = { a, a };
    check(BRMerkleRootFromTxHashes(&rootDup, dup, 2) == 0,
          "merkle: an adjacent duplicate pair is REJECTED (CVE-2012-2459 mutation)");

    check(BRMerkleRootFromTxHashes(&tmp, one, 0) == 0, "merkle: an empty tx list has no root");
    check(BRMerkleRootFromTxHashes(&tmp, NULL, 3) == 0, "merkle: a NULL tx list has no root");

    // The property the gate actually leans on: removing a transaction changes the root.
    check(! UInt256Eq(root2, root3), "merkle: dropping a tx from the list changes the root");
}

int main(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);

    check(wallet != NULL, "wallet created");
    if (! wallet) { printf("\nFATAL\n"); return 1; }

    BRAddress addr = BRWalletReceiveAddress(wallet, 1);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr.s);
    check(spkLen > 0, "wallet address scriptPubKey resolves");

    test_merkle_root_primitive();
    test_unsolicited_block_cannot_complete(wallet, spk, spkLen);
    test_stripped_block_cannot_complete(wallet, spk, spkLen);
    test_verified_block_with_no_wallet_tx_completes(wallet);
    test_verified_cfilter_match_arms_completion(wallet, spk, spkLen);
    test_rearm_drops_solicitations(wallet, spk, spkLen);
    test_solicitation_table_is_bounded(wallet);
    test_stub_header_does_not_wedge_the_floor(wallet, spk, spkLen);

    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
