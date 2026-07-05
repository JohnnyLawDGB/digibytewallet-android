// Wallet-level KAT: DD outputs paid to our taproot address are detected, summed
// into a cents DD balance (never the DGB balance), and pruned when spent.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks
// that each input's signature/witness pointers are non-NULL (not that they are
// cryptographically valid). These synthetic KAT txs aren't signed with real keys
// (balance-accounting logic under test doesn't touch signatures), so pass a
// non-NULL, zero-length placeholder to satisfy the "is signed" pointer check —
// same trick BRTransactionParse uses for the witness-less legacy-tx path.
static const uint8_t kPlaceholder[1] = {0};

// tx->txHash is only ever computed by BRTransactionParse (from serialized bytes)
// or by BRTransactionSign's post-sign round-trip -- never by AddInput/AddOutput
// alone. These synthetic txs skip real signing, so round-trip serialize->parse
// to populate txHash/wtxHash before using tx->txHash as a prevout reference or
// registering with the wallet (mirrors BRTransaction.c's own post-sign pattern).
static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// canonical all-zeros mnemonic; its m/86'/20'/0'/0/0 P2TR addr is KAT-pinned elsewhere
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// build a DD transfer tx paying `spk` (zero-value) with `cents`, + a "DD" OP_RETURN
static BRTransaction *ddTx(const uint8_t *spk, size_t spkLen, uint16_t centsLE,
                           UInt256 prevHash, uint32_t prevN) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, prevHash, prevN, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);              // vout0: DD token (ours), zero-value
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,
                      (uint8_t)(centsLE & 0xff), (uint8_t)(centsLE >> 8)}; // "DD" type2 [cents]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));         // vout1: OP_RETURN
    return tx;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk84);
    check(w != NULL, "wallet created"); if (!w){printf("\nFATAL\n");return 1;}
    BRWalletSetTaprootKey(w, mpk86);

    BRAddress ta = BRWalletReceiveAddress(w, 2);             // our taproot addr[0]
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    check(spkLen == 34 && spk[0] == 0x51, "taproot scriptPubKey resolves");

    // credit 5000 cents ($50) to us
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *tx = ddTx(spk, spkLen, 5000, h1, 0);
    // _BRWalletUpdateBalance's pre-existing pending/dust gate marks any UNCONFIRMED
    // tx with an output below TX_MIN_OUTPUT_AMOUNT (~54,600 dsat) as pending and
    // skips the entire output loop -- so a mempool-relay (0-conf) DD transfer with
    // its zero-value token output would never reach the DD-detection branch until
    // it confirms. Give it a confirmed height here so this KAT exercises the
    // detection/accumulation logic directly, matching how a chain-scanned/confirmed
    // DD transfer reaches this code path in production.
    tx->blockHeight = 700000;
    finalizeTxHash(tx);
    BRWalletRegisterTransaction(w, tx);
    check(BRWalletDigiDollarBalance(w) == 5000, "DD balance credited: 5000 cents");
    check(BRWalletBalance(w) == 0, "DGB balance unaffected by DD (zero-value)");

    // spend it: a tx consuming (tx->txHash, 0) — pays a foreign DD output
    uint8_t fspk[34]; fspk[0]=0x51; fspk[1]=0x20; memset(fspk+2, 0xCD, 32);
    BRTransaction *sp = BRTransactionNew(); sp->version = 0x02000770;
    BRTransactionAddInput(sp, tx->txHash, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(sp, 0, fspk, 34);
    uint8_t orr2[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    BRTransactionAddOutput(sp, 0, orr2, sizeof(orr2));
    sp->blockHeight = 700001; // confirmed spend, same reasoning as tx above
    finalizeTxHash(sp);
    BRWalletRegisterTransaction(w, sp);
    check(BRWalletDigiDollarBalance(w) == 0, "DD balance 0 after spending the DD utxo");

    BRWalletFree(w);
    printf(g_fail==0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
