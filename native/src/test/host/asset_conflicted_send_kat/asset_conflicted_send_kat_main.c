// Host KAT: the change output of a STUCK, re-sent transaction must not read as held.
//
// THE BUG. A send that gets stuck and is re-sent leaves two transactions in the wallet: the
// abandoned attempt and the replacement, both spending the same input. Once the replacement
// wins, the abandoned attempt sits in wallet->invalidTx — still in allTx, never removed.
//
// The asset layer asks "is this outpoint held?" through two native predicates:
//   BRWalletOutpointSpent(hash, n)      -> is it in spentOutputs?
//   BRWalletTransactionForHash(hash)    -> do we know the funding tx?
// Nothing ever spends the abandoned attempt's OWN change output, so the first answers "no"
// forever, and the second answers "yes" because the tx is still there. Held. So the asset
// change of the stuck attempt is counted alongside the change of the send that actually
// happened, and one send's change is counted twice.
//
// Measured live on an S25 Ultra: asset La3t7Jdv... ("Chang Pablo Escobar", supply 10). One
// unit sent, the attempt stuck, the send repeated. DigiAsset Core says the wallet holds 9.
// The wallet reported 18 across 4 rows -- 9 + 9 + the two zero-quantity DGB-change rows.
//
// THE SIGNAL. BRWalletTransactionIsValid already distinguishes them: it returns 0 for an
// unconfirmed tx in invalidTx. This KAT pins that it stays 0 for the abandoned attempt and
// 1 for the replacement, and that the spentOutputs test alone cannot tell them apart --
// which is exactly why the JNI probe has to consult validity too (CONFLICTED = -2).
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

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static const uint8_t kPlaceholder[1] = {0};

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// An asset send: recipient marker to `foreign`, the DA OP_RETURN, asset change back to us,
// DGB change back to us. `fee` shifts the DGB change so the two attempts differ and get
// distinct txids, the way a re-send with a bumped fee does.
static BRTransaction *assetSend(const uint8_t *spk, size_t spkLen,
                                const uint8_t *foreign, size_t foreignLen,
                                UInt256 prevHash, uint32_t prevN, uint64_t fee) {
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prevHash, prevN, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 6000, foreign, foreignLen);          // vout0: recipient marker
    static const uint8_t kOpReturn[10] = {0x6a,0x08,0x44,0x41,0x03,0x15,0x00,0x01,0x02,0x09};
    BRTransactionAddOutput(tx, 0, kOpReturn, sizeof(kOpReturn));    // vout1: 1 -> vout0, 9 -> vout2
    BRTransactionAddOutput(tx, 6000, spk, spkLen);                  // vout2: asset change (9 units)
    BRTransactionAddOutput(tx, 5000000 - fee, spk, spkLen);         // vout3: DGB change
    finalizeTxHash(tx);
    return tx;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    check(w != NULL, "wallet created"); if (!w) { printf("\nFATAL\n"); return 1; }

    BRAddress a0 = BRWalletReceiveAddress(w, 0);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    uint8_t foreign[64];
    size_t foreignLen = BRAddressScriptPubKey(foreign, sizeof(foreign),
                                              "dgb1qynux5qngw4hstuzpp3lgxw8ym5rst8xdyesndt");
    check(spkLen > 0 && foreignLen > 0, "scriptPubKeys resolve");

    // Funding: the outpoint both attempts will spend.
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *funding = BRTransactionNew();
    BRTransactionAddInput(funding, h1, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(funding, 5020000, spk, spkLen);
    finalizeTxHash(funding);
    check(BRWalletRegisterTransaction(w, funding) != 0, "funding tx registered");
    BRWalletUpdateTransactions(w, &funding->txHash, 1, 700000, 1784980000);

    // Attempt #1 — broadcast, then it sticks. Unconfirmed.
    BRTransaction *stuck = assetSend(spk, spkLen, foreign, foreignLen, funding->txHash, 0, 10000);
    check(BRWalletRegisterTransaction(w, stuck) != 0, "the stuck attempt registered");
    check(stuck->blockHeight == TX_UNCONFIRMED, "the stuck attempt is unconfirmed");
    check(BRWalletTransactionIsValid(w, stuck) == 1,
          "while it is the only spender of the input, the stuck attempt is valid");

    // Attempt #2 — the user sends again. Same input, different fee, different txid.
    BRTransaction *resend = assetSend(spk, spkLen, foreign, foreignLen, funding->txHash, 0, 20000);
    check(! UInt256Eq(stuck->txHash, resend->txHash), "the re-send is a distinct transaction");
    check(BRWalletRegisterTransaction(w, resend) != 0, "the re-send registered");
    BRWalletUpdateTransactions(w, &resend->txHash, 1, 700001, 1784980100);

    // ---- what the old probe sees -----------------------------------------------------
    // Both predicates the asset layer used answer "held" for the abandoned attempt's change.
    // This is the bug, stated as an assertion: it is TRUE both before and after the fix,
    // which is precisely why validity has to be consulted as well.
    check(BRWalletOutpointSpent(w, stuck->txHash, 2) == 0,
          "nothing ever spends the stuck attempt's change, so spentOutputs cannot see it");
    check(BRWalletTransactionForHash(w, stuck->txHash) != NULL,
          "the stuck attempt is still in the wallet's transaction set");

    // ---- the signal that does separate them ------------------------------------------
    check(BRWalletTransactionIsValid(w, stuck) == 0,
          "the stuck attempt is INVALID once the re-send spends the same input");
    check(BRWalletTransactionIsValid(w, resend) == 1, "the re-send is valid");

    // And the replacement's own change must stay held — the fix must not blanket-exclude.
    check(BRWalletOutpointSpent(w, resend->txHash, 2) == 0, "the re-send's change is unspent");
    check(BRWalletTransactionForHash(w, resend->txHash) != NULL, "the re-send is in the wallet");

    // A plain confirmed receive is unaffected.
    check(BRWalletTransactionIsValid(w, funding) == 1, "an ordinary confirmed tx stays valid");

    // ---- the composed answer the asset layer actually consumes ------------------------
    // BRWalletOutpointAssetState folds the three predicates into the one value the JNI
    // probe returns, so the decision is testable here instead of only across the bridge:
    //   0 SPENT   1 HELD   -1 UNDETECTED   -2 CONFLICTED
    check(BRWalletOutpointAssetState(w, stuck->txHash, 2) == -2,
          "the stuck attempt's change reads CONFLICTED, not HELD");
    check(BRWalletOutpointAssetState(w, resend->txHash, 2) == 1,
          "the re-send's change still reads HELD");
    check(BRWalletOutpointAssetState(w, funding->txHash, 0) == 0,
          "an outpoint the wallet has spent still reads SPENT");

    UInt256 unknown; memset(unknown.u8, 0xEE, 32);
    check(BRWalletOutpointAssetState(w, unknown, 0) == -1,
          "an outpoint from a transaction we have never seen still reads UNDETECTED");

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
