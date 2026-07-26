// Host KAT: an UNCONFIRMED DigiDollar receive must not be erased by the dust gate.
//
// THE BUG (root cause of the Ultra "missed DigiDollar receive" that v4.0.20 failed to fix):
//
// A DigiDollar token output is ZERO-VALUE by protocol — the dollar amount lives in the
// OP_RETURN, not the satoshi value. _BRWalletUpdateBalance's pending gate marks any
// UNCONFIRMED tx pending if ANY output is below TX_MIN_OUTPUT_AMOUNT (54,600 dsat), and
// then `continue`s past the ENTIRE output loop (BRWallet.c:230-249). A 0-value DD output
// always trips it, so for an unconfirmed DD receive the wallet:
//   - never records the receiving address as used (so the Receive address never rotates),
//   - never credits ddUtxos/ddBalance,
//   - never credits any DGB output in the same tx.
// The receive is invisible rather than merely "pending". A plain DGB receive IS credited
// at 0-conf, which is exactly why this bug is DigiDollar-specific.
//
// "Scan for missing transactions" recovers it because registerRawTransaction sets
// tx->blockHeight from the node BEFORE BRWalletRegisterTransaction (jni_transaction.c:421),
// so the gate is never evaluated. That live-vs-reconcile asymmetry is the whole bug.
//
// THE FIX (U1): a consensus-legal 0-value output — DD token, asset marker, or OP_RETURN —
// no longer trips the dust check, so the output loop runs and the receive is visible. The
// DD/asset CREDIT is still withheld until the tx confirms, so this does not silently
// introduce 0-conf DigiDollar credit.
//
// Exit code 0 = all checks passed, 1 = check failed / ASan fault.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks that each
// input's signature/witness pointers are non-NULL. These synthetic txs aren't really signed.
static const uint8_t kPlaceholder[1] = {0};

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A DD transfer paying `spk` (zero-value token output) with `cents`, plus the "DD" OP_RETURN.
static BRTransaction *ddTx(const uint8_t *spk, size_t spkLen, uint16_t centsLE,
                           UInt256 prevHash, uint32_t prevN) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, prevHash, prevN, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);              // vout0: DD token (ours), zero-value
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,
                      (uint8_t)(centsLE & 0xff), (uint8_t)(centsLE >> 8)};
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));         // vout1: OP_RETURN, zero-value
    finalizeTxHash(tx);
    return tx;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    check(w != NULL, "wallet created"); if (!w) { printf("\nFATAL\n"); return 1; }
    BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));

    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    check(spkLen == 34 && spk[0] == 0x51, "taproot scriptPubKey resolves");

    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *tx = ddTx(spk, spkLen, 5000, h1, 0);      // $50.00 to us
    UInt256 ddHash = tx->txHash;

    // Register it as the LIVE path does: no confirming height attached.
    check(tx->blockHeight == TX_UNCONFIRMED, "tx registered as the live path does (unconfirmed)");
    check(BRWalletRegisterTransaction(w, tx) != 0, "DD tx is recognised as ours and registered");
    check(BRWalletTransactionForHash(w, ddHash) != NULL, "DD tx is in the wallet");

    // ---- while UNCONFIRMED --------------------------------------------------------
    // The receive must be VISIBLE: the wallet knows the address was paid, so the Receive
    // address rotates and the tx participates in normal accounting.
    check(BRWalletAddressIsUsed(w, ta.s) == 1,
          "receiving address is marked USED while still unconfirmed");

    // ...but the DD credit is deliberately WITHHELD until it confirms.
    check(BRWalletDigiDollarBalance(w) == 0,
          "DigiDollar balance is NOT credited at 0-conf (credit withheld until confirmed)");

    // This is the predicate BRPeerManager.c:534 uses to decide a tx is worth keeping.
    // Both are pure-DGB sums, so a DD receive scores ZERO STAKE and is eligible for
    // BRWalletRemoveTransaction. Documented here; the fix belongs to the peer-manager
    // sequence, not this branch.
    check(BRWalletAmountReceivedFromTx(w, tx) == 0 && BRWalletAmountSentByTx(w, tx) == 0,
          "DD receive scores zero DGB stake (the peer-manager deletion hazard, filed)");

    // ---- once CONFIRMED -----------------------------------------------------------
    BRWalletUpdateTransactions(w, &ddHash, 1, 700000, 1784980000);
    check(BRWalletTransactionForHash(w, ddHash)->blockHeight == 700000, "tx now confirmed");
    check(BRWalletDigiDollarBalance(w) == 5000,
          "DigiDollar balance credited once the confirming height lands ($50.00)");

    // ---- a genuinely dusty DGB output must STILL mark the tx pending ---------------
    // The exemption is narrow: protocol 0-value token/OP_RETURN outputs only. Ordinary
    // sub-dust DGB outputs keep the original anti-dust-spam behaviour.
    {
        BRAddress da = BRWalletReceiveAddress(w, 1);
        uint8_t dspk[64];
        size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
        UInt256 h2; memset(h2.u8, 0x22, 32);
        BRTransaction *dust = BRTransactionNew();
        BRTransactionAddInput(dust, h2, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
        BRTransactionAddOutput(dust, 1000, dspk, dspkLen);    // 1000 dsat: real dust, not a token
        finalizeTxHash(dust);
        check(BRWalletRegisterTransaction(w, dust) != 0, "dusty DGB tx registered");
        check(BRWalletTransactionIsPending(w, dust) == 1,
              "a genuinely dusty DGB output still marks the tx pending");
    }

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
