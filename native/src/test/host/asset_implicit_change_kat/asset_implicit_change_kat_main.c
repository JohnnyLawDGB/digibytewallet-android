// Host KAT: an asset output the wallet cannot classify from the transaction alone must
// still be kept OUT of the spendable DGB set.
//
// THE BUG. DigiAssets credits every input unit the explicit transfer instructions do NOT
// assign to the transaction's LAST output (DigiAsset_Core DigiByteTransaction.cpp,
// decodeAssetTransfer: `lastOutput = _outputs.size() - 1`). Bread-era wallets and
// digiasset-core rely on it — one instruction for the recipient, the change rides
// implicitly. BRTxOutputIsAsset only recognises outputs an instruction explicitly targets,
// so that implicit-change output is filed in wallet->utxos as ordinary spendable DGB
// (BRWallet.c:319). A plain DGB send can then select it and the asset is destroyed —
// silently, because nothing invalid happened as far as the chain is concerned.
//
// Live instance at the time of writing: mainnet tx
// 6aa6d5c92b2bf0d2368aaf718e596e84764a52ba7eaabbcd336b17a483d5a04f (OP_RETURN
// 6a0644410115000a = one instruction, 10 units -> vout 0) leaves 90 units of
// La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo riding on vout 3 — 10,000 dsat, unspent, and
// spendable as DGB.
//
// WHY NATIVE CANNOT DECIDE THIS ALONE. Knowing there IS a leftover requires knowing what
// the inputs carried, which means walking the transfer chain back to issuance and holding
// a per-outpoint quantity store. Kotlin already does both (M3 walk + the Room utxos
// table). So native gains a registration primitive and Kotlin drives it, fail-closed:
// whenever the leftover is positive OR unknown, the outpoint is registered and can no
// longer be reached by a DGB spend.
//
// This KAT pins the primitive: registration survives a balance rebuild, is idempotent,
// removes the value from the spendable balance, and actually stops coin selection.
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
#include "BRDigiAsset.h"

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

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    check(w != NULL, "wallet created"); if (!w) { printf("\nFATAL\n"); return 1; }

    BRAddress a0 = BRWalletReceiveAddress(w, 0);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    check(spkLen > 0, "our scriptPubKey resolves");

    // A foreign script: the recipient marker of the transfer, which we do NOT own.
    uint8_t foreign[64];
    size_t foreignLen = BRAddressScriptPubKey(foreign, sizeof(foreign),
                                              "dgb1qynux5qngw4hstuzpp3lgxw8ym5rst8xdyesndt");
    check(foreignLen > 0, "foreign recipient scriptPubKey resolves");

    // ---- 1. a plain DGB receive: 0.2 DGB, unambiguously spendable --------------------
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *plain = BRTransactionNew();
    BRTransactionAddInput(plain, h1, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(plain, 20000000, spk, spkLen);
    finalizeTxHash(plain);
    check(BRWalletRegisterTransaction(w, plain) != 0, "plain DGB receive registered");
    BRWalletUpdateTransactions(w, &plain->txHash, 1, 700000, 1784980000);

    // ---- 2. a DigiAsset transfer whose change rides IMPLICITLY on the last output -----
    // Shape mirrors 6aa6d5c9…: [0] foreign recipient marker, [1] OP_RETURN assigning 10
    // units to vout 0 only, [2] OURS — carrying the unassigned remainder.
    //
    // That last output is deliberately an ordinary-sized 0.05 DGB change output, not a dust
    // marker: the protocol credits the remainder to the LAST output whatever its value, so
    // the units can land on a large, obviously-spendable-looking output. That is the case
    // that loses the most.
    UInt256 h2; memset(h2.u8, 0x22, 32);
    BRTransaction *xfer = BRTransactionNew();
    BRTransactionAddInput(xfer, h2, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(xfer, 10000, foreign, foreignLen);
    static const uint8_t kOpReturn[8] = {0x6a,0x06,0x44,0x41,0x01,0x15,0x00,0x0a};
    BRTransactionAddOutput(xfer, 0, kOpReturn, sizeof(kOpReturn));
    BRTransactionAddOutput(xfer, 5000000, spk, spkLen);
    finalizeTxHash(xfer);
    check(BRWalletRegisterTransaction(w, xfer) != 0, "asset transfer registered");
    BRWalletUpdateTransactions(w, &xfer->txHash, 1, 700001, 1784980100);

    // The gap itself: no instruction targets vout 2, so the tx-local classifier says
    // "not an asset output". This stays true after the fix — native is not being taught
    // to resolve input quantities, it is being taught to accept an answer.
    check(BRTxOutputIsAsset(xfer, &xfer->outputs[2]) == 0,
          "tx-local classifier cannot see the implicit-change output (the gap)");
    check(BRTxOutputIsAsset(xfer, &xfer->outputs[0]) == 1,
          "tx-local classifier DOES see an explicitly-instructed output");

    BRUTXO changeOut = { xfer->txHash, 2 };
    check(BRWalletUtxoIsAsset(w, &changeOut) == 0,
          "before registration the implicit-change output is spendable DGB (the bug)");
    check(BRWalletBalance(w) == 25000000,
          "before registration its 5,000,000 dsat counts toward the spendable balance");

    // ---- 3. register it, fail-closed, on Kotlin's say-so ------------------------------
    check(BRWalletRegisterAssetOutpoint(w, xfer->txHash, 2) == 1,
          "registering the outpoint reports that it moved");
    check(BRWalletUtxoIsAsset(w, &changeOut) == 1,
          "the implicit-change output is now held as an asset outpoint");
    check(BRWalletBalance(w) == 20000000,
          "its value is no longer part of the spendable DGB balance");

    // Idempotent: the periodic sweep re-registers the same outpoint every pass.
    check(BRWalletRegisterAssetOutpoint(w, xfer->txHash, 2) == 0,
          "re-registering the same outpoint is a no-op");
    check(BRWalletBalance(w) == 20000000, "balance unchanged by the repeat registration");

    // ---- 4. it must SURVIVE a balance rebuild ----------------------------------------
    // _BRWalletUpdateBalance clears and rebuilds utxos/assetUtxos from the transaction set
    // on every registration, reorg and wallet load. A one-shot array edit would be undone
    // by the next incoming transaction.
    UInt256 h3; memset(h3.u8, 0x33, 32);
    BRTransaction *later = BRTransactionNew();
    BRTransactionAddInput(later, h3, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(later, 10000000, spk, spkLen);
    finalizeTxHash(later);
    check(BRWalletRegisterTransaction(w, later) != 0, "a later receive registered");
    BRWalletUpdateTransactions(w, &later->txHash, 1, 700002, 1784980200);
    check(BRWalletUtxoIsAsset(w, &changeOut) == 1,
          "registration survives the balance rebuild a later transaction forces");
    check(BRWalletBalance(w) == 30000000, "balance rebuild still excludes the asset outpoint");

    // ---- 5. the property that matters: coin selection cannot reach it -----------------
    // 30,000,000 spendable, plus the 5,000,000 excluded. A 32,000,000 send is only
    // satisfiable by spending the asset-bearing output, so it must fail rather than destroy
    // the asset. (Both amounts clear the fee by ~1,000,000, so no fee edge decides this.)
    BRTransaction *tooBig = BRWalletCreateTransaction(w, 32000000, a0.s);
    check(tooBig == NULL,
          "a DGB send that would need the asset-bearing output fails instead of burning it");
    if (tooBig) BRTransactionFree(tooBig);

    BRTransaction *ok = BRWalletCreateTransaction(w, 29000000, a0.s);
    check(ok != NULL, "a DGB send within the genuinely spendable balance still succeeds");
    if (ok) BRTransactionFree(ok);

    // ---- 6. registration must not depend on arrival order ----------------------------
    // The asset layer can resolve an outpoint before the native wallet has the transaction
    // (a sweep pass racing a fresh receive). Registering ahead of time must be remembered
    // and applied when the transaction lands, or the window between them is exactly when a
    // DGB send can eat the asset.
    UInt256 h4; memset(h4.u8, 0x44, 32);
    BRTransaction *early = BRTransactionNew();
    BRTransactionAddInput(early, h4, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(early, 10000, foreign, foreignLen);
    BRTransactionAddOutput(early, 0, kOpReturn, sizeof(kOpReturn));
    BRTransactionAddOutput(early, 4000000, spk, spkLen);
    finalizeTxHash(early);

    check(BRWalletRegisterAssetOutpoint(w, early->txHash, 2) == 1,
          "an outpoint can be registered before its transaction arrives");
    check(BRWalletRegisterTransaction(w, early) != 0, "the transaction then arrives");
    BRWalletUpdateTransactions(w, &early->txHash, 1, 700003, 1784980300);

    BRUTXO earlyOut = { early->txHash, 2 };
    check(BRWalletUtxoIsAsset(w, &earlyOut) == 1,
          "the pre-registered outpoint is excluded the moment its transaction lands");
    check(BRWalletBalance(w) == 30000000,
          "a pre-registered outpoint never counts toward the spendable balance");

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
