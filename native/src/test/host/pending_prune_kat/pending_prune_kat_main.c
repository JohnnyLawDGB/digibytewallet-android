// Host KAT: a coin a pending send spends is neither in the balance nor selectable (4.1.2 row 21).
//   [A] a confirmed receive R, then a pending own send S that spends it (one output below the dust floor,
//       as every DigiDollar transfer has), registered last: R is out of BRWalletUTXOs and the balance --
//       RED under PENDING_PRUNE_UNFIXED, where nothing after S pruned it;
//   [B] guard: with no send, R is the one coin and the whole balance.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void ck(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (!c) g_fail++; }
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

static BRTransaction *receive(void)
{
    BRTransaction *r = BRTransactionNew();
    UInt256 foreign; memset(foreign.u8, 0x5a, 32);
    BRTransactionAddInput(r, foreign, 0, 0, g_spk, g_spkLen, kPlaceholder, 1, kPlaceholder, 1, 0xffffffff);
    BRTransactionAddOutput(r, 100000000ULL, g_spk, g_spkLen);
    r->blockHeight = 100; r->timestamp = 1700000000;
    finalizeTxHash(r);
    return r;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);

    printf("[B] a confirmed receive alone\n");
    {
        BRWallet *w = wallet();
        BRTransaction *r = receive();
        BRWalletRegisterTransactionTrusted(w, r);
        ck(BRWalletUTXOs(w, NULL, 0) == 1 && BRWalletBalance(w) == 100000000ULL, "one coin, the whole balance");
        BRWalletFree(w);
    }

    printf("[A] a pending send spends it\n");
    {
        BRWallet *w = wallet();
        BRTransaction *r = receive();
        BRWalletRegisterTransactionTrusted(w, r);
        BRTransaction *s = BRTransactionNew();
        BRTransactionAddInput(s, r->txHash, 0, 100000000ULL, g_spk, g_spkLen, kPlaceholder, 1, kPlaceholder, 1, 0xffffffff);
        BRTransactionAddOutput(s, 0, g_spk, g_spkLen);                  // below the dust floor: pending
        BRTransactionAddOutput(s, 99000000ULL, (const uint8_t *)"\x00\x14" "abcdefghijklmnopqrst", 22);
        s->blockHeight = TX_UNCONFIRMED; s->timestamp = 1700000100;
        finalizeTxHash(s);
        BRWalletRegisterTransactionTrusted(w, s);
        size_t coins = BRWalletUTXOs(w, NULL, 0);
        uint64_t bal = BRWalletBalance(w);
        if (coins != 0 || bal != 0) {
            printf("FAIL: A: the coin the pending send spends is still counted (%zu coin(s), balance %llu)\n",
                   coins, (unsigned long long)bal);
            g_fail++;
        } else ck(1, "the spent coin is out of the coins and the balance");
        BRWalletFree(w);
    }

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: pending_prune_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
