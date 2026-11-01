// Host KAT: the unrelayed-transaction request's tables do not live on the stack (4.1.3 G2).
//
// _BRPeerManagerRequestUnrelayedTx sizes two tables by the number of unconfirmed records the wallet holds -- a number
// a peer can raise by relaying transactions that pay the wallet. As stack VLAs they ran past a small thread stack.
//   RED   -DUNRELAYED_TX_STACK_UNFIXED, the call on a thread with a 256 KiB stack and N unconfirmed records: the
//         stack overflows (signal)
//   GREEN the shipped core on the same thread stack: every record is asked for in one getdata, and the call returns
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#define N 20000u    // 20000 * (8 + 32) bytes = 800 KB of tables, against a 256 KiB thread stack

static size_t g_getdataTx = 0;
static int g_pings = 0;
static volatile uint8_t g_hashFold = 0;   // read every hash sent: the table must exist (an unread one is optimised away)

void __wrap_BRPeerSendGetdata(BRPeer *peer, const UInt256 txHashes[], size_t txCount, const UInt256 blockHashes[],
                              size_t blockCount)
{
    (void)peer; (void)blockHashes; (void)blockCount;
    for (size_t i = 0; i < txCount; i++) g_hashFold ^= txHashes[i].u8[0];
    g_getdataTx += txCount;
}

void __wrap_BRPeerSendPing(BRPeer *peer, void *info, void (*pongCallback)(void *info, int success))
{
    (void)peer; (void)pongCallback;
    g_pings++;
    free(info);
}

static const uint8_t kPlaceholder[1] = {0};

static BRTransaction *payTx(const uint8_t *spk, size_t spkLen, uint32_t i)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;

    memset(prevHash.u8, 0x5a, sizeof(prevHash.u8));
    UInt32SetLE(prevHash.u8, i);
    BRTransactionAddInput(tx, prevHash, 0, 0, spk, spkLen, kPlaceholder, sizeof(kPlaceholder),
                          kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    BRTransactionAddOutput(tx, 10000, spk, spkLen);
    uint8_t data[BR_VLA_LEN(BRTransactionSerialize(tx, NULL, 0))];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    tx->blockHeight = TX_UNCONFIRMED;
    return tx;
}

static BRPeerManager *g_m;
static BRPeer *g_peer;

static void *requestOnSmallStack(void *arg)
{
    (void)arg;
    MGR_LOCK(g_m);
    _BRPeerManagerRequestUnrelayedTx(g_m, g_peer);
    MGR_UNLOCK(g_m);
    return NULL;
}

int main(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    BRSetNetwork(0);
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    // the receive address first, from a scratch wallet, then a wallet loaded with N unconfirmed payments to it
    BRWallet *scratch = BRWalletNew(NULL, 0, mpk);
    BRAddress addr = BRWalletReceiveAddress(scratch, 1);
    BRWalletFree(scratch);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr.s);
    BRTransaction **txs = calloc(N, sizeof(*txs));
    for (uint32_t i = 0; i < N; i++) txs[i] = payTx(spk, spkLen, i);
    BRWallet *w = BRWalletNew(txs, N, mpk);
    free(txs);
    size_t held = BRWalletTxUnconfirmedBefore(w, NULL, 0, TX_UNCONFIRMED);
    printf("NOTE: the wallet holds %zu unconfirmed records\n", held);
    if (held < N) { printf("FAIL: setup: fewer unconfirmed records than built\n"); return 1; }

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);
    peer->address.u8[15] = 0x01;
    peer->port = 10001;
    peer->flags |= PEER_FLAG_SYNCED;   // no ping path

    // the peer thread's stack is small and fixed; the main thread's grows, so it would prove nothing
    g_m = m; g_peer = peer;
    pthread_attr_t attr;
    pthread_t t;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 256 * 1024);
    if (pthread_create(&t, &attr, requestOnSmallStack, NULL) != 0) { printf("FAIL: setup: no thread\n"); return 1; }
    pthread_join(t, NULL);
    pthread_attr_destroy(&attr);

    printf("NOTE: getdata asked for %zu transaction(s)\n", g_getdataTx);
    int ok = g_getdataTx == held;
    printf(ok ? "PASS: unrelayed_tx_heap_kat\n" : "FAIL: unrelayed_tx_heap_kat (getdata count)\n");
    BRPeerManagerFree(m);
    BRPeerFree(peer);
    BRWalletFree(w);
    return ok ? 0 : 1;
}
