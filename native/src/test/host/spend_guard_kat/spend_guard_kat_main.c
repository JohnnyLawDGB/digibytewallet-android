// Host KAT: a coin the wallet already holds as spent is never signed into another send (4.1.2 row 3, review R3).
//   [A] RED  a registered send S spends coin R:0; a second send T built from the same coin (the asset send signs the
//            outpoints it is handed) is refused by spend_guard_inputs_unspent -- granted under SPEND_GUARD_UNFIXED=1
//   [B] GUARD T built from a coin nothing spends is allowed
//   [C] GUARD with no wallet or no transaction the guard refuses (it cannot tell, so nothing is signed)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "spend_guard.h"

static int g_fail = 0;
static void ck(int c, const char *d) { printf(c ? "  ok   %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }
static const uint8_t kPlaceholder[1] = {0};
static uint8_t g_spk[64]; static size_t g_spkLen;

static void finalizeTxHash(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(n);
    size_t len = BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

static BRWallet *wallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    BRAddress a = BRWalletReceiveAddress(w, 1);
    g_spkLen = BRAddressScriptPubKey(g_spk, sizeof(g_spk), a.s);
    return w;
}

static BRTransaction *receive(uint8_t seed)
{
    BRTransaction *r = BRTransactionNew();
    UInt256 foreign; memset(foreign.u8, seed, 32);
    BRTransactionAddInput(r, foreign, 0, 0, g_spk, g_spkLen, kPlaceholder, 1, kPlaceholder, 1, 0xffffffff);
    BRTransactionAddOutput(r, 100000000ULL, g_spk, g_spkLen);
    r->blockHeight = 100; r->timestamp = 1700000000;
    finalizeTxHash(r);
    return r;
}

/* a send of coin (hash:0) to a foreign script, unsigned, unregistered */
static BRTransaction *spend(UInt256 hash, uint8_t tag)
{
    uint8_t dest[22] = { 0x00, 0x14 };
    memset(dest + 2, tag, 20);
    BRTransaction *t = BRTransactionNew();
    BRTransactionAddInput(t, hash, 0, 100000000ULL, g_spk, g_spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(t, 99000000ULL, dest, sizeof(dest));
    return t;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    BRWallet *w = wallet();
    BRTransaction *r = receive(0x5a), *r2 = receive(0x5b);
    BRWalletRegisterTransactionTrusted(w, r);
    BRWalletRegisterTransactionTrusted(w, r2);

    // S: the DGB send that registered during the asset send's round-trip
    BRTransaction *s = BRTransactionNew();
    BRTransactionAddInput(s, r->txHash, 0, 100000000ULL, g_spk, g_spkLen, kPlaceholder, 1, kPlaceholder, 1, 0xffffffff);
    uint8_t d1[22] = { 0x00, 0x14 }; memset(d1 + 2, 0x11, 20);
    BRTransactionAddOutput(s, 99000000ULL, d1, sizeof(d1));
    s->blockHeight = TX_UNCONFIRMED; s->timestamp = 1700000100;
    finalizeTxHash(s);
    BRWalletRegisterTransactionTrusted(w, s);
    ck(BRWalletOutpointSpent(w, r->txHash, 0) == 1, "setup: the wallet holds R:0 as spent by S");

    printf("[A] a second send of the same coin\n");
    BRTransaction *t = spend(r->txHash, 0x22);
    if (spend_guard_inputs_unspent(w, t) != 0) {
        printf("FAIL: A: a coin a registered send already spends was allowed into another send\n");
        g_fail++;
    } else ck(1, "refused: R:0 is already spent");
    BRTransactionFree(t);

    printf("[B] a coin nothing spends\n");
    t = spend(r2->txHash, 0x33);
    ck(spend_guard_inputs_unspent(w, t) == 1, "allowed: R2:0 is unspent");

    printf("[C] nothing to ask\n");
#if ! SPEND_GUARD_UNFIXED
    ck(spend_guard_inputs_unspent(NULL, t) == 0 && spend_guard_inputs_unspent(w, NULL) == 0, "refused without a wallet or a transaction");
#endif
    BRTransactionFree(t);
    BRWalletFree(w);

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: spend_guard_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
