#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

// Pull in the real source so the file-static _BRTransactionTaprootSighash is
// visible for an independent sighash recompute (same pattern as
// bip341_signtx_kat_main.c). Do NOT also compile BRTransaction.c on the clang
// line (it would define its symbols twice) -- run.sh omits it accordingly.
// BRWallet.c IS compiled separately and references BRTransaction's (now
// in-this-TU) non-static symbols; the linker resolves them.
#include "BRTransaction.c"

#include "BRDigiDollar.h"
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "secp256k1.h"
#include "secp256k1_extrakeys.h"
#include "secp256k1_schnorrsig.h"
static int g=0; static void ck(int c,const char*d){printf(c?"PASS: %s\n":"FAIL: %s\n",d); if(!c)g++;}

// --- Task 2 (BRWalletCreateDigiDollarTransfer) KAT wallet setup helpers ---
// Copied from the confirmed-DD-credit pattern in
// native/src/test/host/digidollar_wallet_kat/digidollar_wallet_kat_main.c.

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks
// that each input's signature/witness pointers are non-NULL (not that they are
// cryptographically valid). These synthetic KAT txs aren't signed with real keys,
// so pass a non-NULL, zero-length placeholder to satisfy the "is signed" check.
static const uint8_t kPlaceholder[1] = {0};

// tx->txHash is only ever computed by BRTransactionParse or BRTransactionSign's
// post-sign round-trip. These synthetic txs skip real signing, so round-trip
// serialize->parse to populate txHash/wtxHash before registering with the wallet.
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

int main(void){
    // real testnet TD golden vector
    const char *TD = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC";
    uint8_t exp[32] = {
        0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
        0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };
    uint8_t key[32];
    ck(BRDigiDollarAddressDecode(key, TD, 1) == 1, "decode real TD address (testnet)");
    ck(memcmp(key, exp, 32) == 0, "decoded key == golden 32-byte key");
    // wrong network: TD is testnet, decoding as mainnet must fail (version mismatch)
    ck(BRDigiDollarAddressDecode(key, TD, 0) == 0, "TD rejected when isTestnet=0 (wrong version)");
    // corrupted checksum (flip last char) -> fail
    char bad[64]; strcpy(bad, TD); bad[strlen(bad)-1] = (bad[strlen(bad)-1]=='C'?'D':'C');
    ck(BRDigiDollarAddressDecode(key, bad, 1) == 0, "corrupted checksum -> fail");
    // a normal DGB address is not a DD address -> fail
    ck(BRDigiDollarAddressDecode(key, "dgb1q6hwtu62c3wmdmexdpgpwmcycc7htrhr0f5w62z", 1) == 0, "bech32 addr -> fail");
    // NULL-safe
    ck(BRDigiDollarAddressDecode(key, NULL, 1) == 0, "NULL addr -> fail");

    // ==================== Task 2: BRWalletCreateDigiDollarTransfer builder ====================
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk84);
    ck(w != NULL, "builder KAT: wallet created");
    BRWalletSetTaprootKey(w, mpk86);

    BRAddress ta = BRWalletReceiveAddress(w, 2);              // our taproot addr[0]
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    ck(spkLen == 34 && spk[0] == 0x51, "builder KAT: taproot scriptPubKey resolves");

    // fund the wallet with a 10000-cent ($100) confirmed DD UTXO
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *ddCredit = ddTx(spk, spkLen, 10000, h1, 0);
    ddCredit->blockHeight = 700000;                            // confirmed (see ddTx-adjacent comment upstream)
    finalizeTxHash(ddCredit);
    BRWalletRegisterTransaction(w, ddCredit);
    ck(BRWalletDigiDollarBalance(w) == 10000, "builder KAT setup: DD balance 10000 cents");

    // fund the wallet with a confirmed 1 DGB (100000000 sat) P2WPKH UTXO for the fee
    BRAddress da = BRWalletReceiveAddress(w, 1);              // our P2WPKH addr[0]
    uint8_t dspk[64]; size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    UInt256 h2; memset(h2.u8, 0x22, 32);
    BRTransaction *dgbCredit = BRTransactionNew();
    dgbCredit->version = 1;
    BRTransactionAddInput(dgbCredit, h2, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dgbCredit, 100000000, dspk, dspkLen);
    dgbCredit->blockHeight = 700000;
    finalizeTxHash(dgbCredit);
    BRWalletRegisterTransaction(w, dgbCredit);
    ck(BRWalletBalance(w) == 100000000, "builder KAT setup: DGB balance 1 DGB (100000000 sat)");

    // recipientKey32 = the golden TD key
    uint8_t rk[32] = {0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
                      0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b};
    BRTransaction *t = BRWalletCreateDigiDollarTransfer(w, rk, 4000); // send $40 of the $100 held
    ck(t != NULL, "builder returns a tx");
    ck(t->version == 0x02000770, "version 0x02000770");
    // vout0 recipient DD: 51 20 <rk>, value 0
    ck(t->outputs[0].amount==0 && t->outputs[0].scriptLen==34 && t->outputs[0].script[0]==0x51
       && memcmp(t->outputs[0].script+2, rk, 32)==0, "vout0 recipient DD verbatim, value 0");
    // vout1 DD change: 51 20 <ours>, value 0 (6000 cents change)
    ck(t->outputs[1].amount==0 && t->outputs[1].scriptLen==34 && t->outputs[1].script[0]==0x51,
       "vout1 DD change zero-value P2TR");
    // last output OP_RETURN with [4000,6000]: 4000 = 0x0fa0 -> LE a0 0f ; 6000 = 0x1770 -> 70 17
    BRTxOutput *op = &t->outputs[t->outCount-1];
    uint8_t exp_or[] = {0x6a,0x02,0x44,0x44,0x01,0x02, 0x02,0xa0,0x0f, 0x02,0x70,0x17};
    ck(op->scriptLen==sizeof(exp_or) && memcmp(op->script,exp_or,sizeof(exp_or))==0,
       "OP_RETURN == 6a 02 4444 0102 [4000] [6000]");
    // conservation: decode the built tx's own DD amounts, sum == selected input DD (10000)
    int64_t a[8]; int n=BRDigiDollarDecodeAmounts(t,a,8);
    ck(n==2 && a[0]+a[1]==10000, "strict conservation: out DD == in DD (10000c)");

    // ==================== Task 3: sign + verify (builder -> signed, spendable tx) ====================
    // Reuses the exact verify pattern from bip341_signtx_kat_main.c's VALIDITY block: sign
    // the built transfer through the real wallet path, then independently recompute the
    // BIP-341 key-path sighash for the DD (P2TR) input and assert the witness's 64-byte
    // Schnorr signature verifies under X(Q). This closes the loop: builder output is a
    // real, spendable, network-valid transaction.
    {
        int r = BRWalletSignTransaction(w, t, 0, seed, sizeof(seed));
        ck(r == 1, "BRWalletSignTransaction signs the DD transfer");
        ck(BRTransactionIsSigned(t) == 1, "transfer reports fully signed");

        // find the DD input: the one whose prevout scriptPubKey is the 34-byte P2TR
        // (51 20 <32>) we set up as our DD UTXO above.
        int ddIdx = -1;
        for (size_t i = 0; i < t->inCount; i++) {
            if (t->inputs[i].scriptLen == 34 && t->inputs[i].script[0] == 0x51 && t->inputs[i].script[1] == 0x20) {
                ddIdx = (int)i;
                break;
            }
        }
        ck(ddIdx >= 0, "DD input (P2TR prevout) located in the signed transfer");

        if (ddIdx >= 0) {
            BRTxInput *ddin = &t->inputs[ddIdx];
            ck(ddin->witness != NULL && ddin->witLen == 65 && ddin->witness[0] == 0x40,
               "DD input carries a 1-item 64-byte taproot witness");

            if (ddin->witness != NULL && ddin->witLen == 65) {
                secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
                uint8_t xq[32]; memcpy(xq, ddin->script + 2, 32);
                secp256k1_xonly_pubkey xoQ;
                ck(secp256k1_xonly_pubkey_parse(ctx, &xoQ, xq) == 1, "parse X(Q) from DD input's prevout script");

                uint8_t sig64[64]; memcpy(sig64, &ddin->witness[1], 64);
                UInt256 md = UINT256_ZERO;
                size_t shLen = _BRTransactionTaprootSighash(t, NULL, 0, (size_t)ddIdx, SIGHASH_DEFAULT, &md);
                ck(shLen > 0, "recompute BIP-341 key-path sighash for the DD input");
                ck(secp256k1_schnorrsig_verify(ctx, sig64, md.u8, 32, &xoQ) == 1,
                   "VALIDITY: DD input witness ACCEPTED by schnorr verify under X(Q) for the tx sighash");

                secp256k1_context_destroy(ctx);
            }
        }
    }

    // NULL cases:
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 0)==NULL, "cents==0 -> NULL");
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 999999)==NULL, "cents > DD balance -> NULL");
    BRTransactionFree(t);

    // ---- wDust: a single 4050-cent DD UTXO + a DGB fee UTXO. send 4000 -> ddChange 50 (sub-$1 dust) ----
    BRWallet *wDust = BRWalletNew(NULL, 0, mpk84);
    ck(wDust != NULL, "wDust created");
    BRWalletSetTaprootKey(wDust, mpk86);
    BRAddress taD = BRWalletReceiveAddress(wDust, 2);
    uint8_t spkD[64]; size_t spkDLen = BRAddressScriptPubKey(spkD, sizeof(spkD), taD.s);
    UInt256 h3; memset(h3.u8, 0x33, 32);
    BRTransaction *ddCreditDust = ddTx(spkD, spkDLen, 4050, h3, 0);
    ddCreditDust->blockHeight = 700000;
    finalizeTxHash(ddCreditDust);
    BRWalletRegisterTransaction(wDust, ddCreditDust);
    ck(BRWalletDigiDollarBalance(wDust) == 4050, "wDust setup: DD balance 4050 cents");
    BRAddress daD = BRWalletReceiveAddress(wDust, 1);
    uint8_t dspkD[64]; size_t dspkDLen = BRAddressScriptPubKey(dspkD, sizeof(dspkD), daD.s);
    UInt256 h4; memset(h4.u8, 0x44, 32);
    BRTransaction *dgbCreditDust = BRTransactionNew();
    dgbCreditDust->version = 1;
    BRTransactionAddInput(dgbCreditDust, h4, 0, 0, dspkD, dspkDLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dgbCreditDust, 100000000, dspkD, dspkDLen);
    dgbCreditDust->blockHeight = 700000;
    finalizeTxHash(dgbCreditDust);
    BRWalletRegisterTransaction(wDust, dgbCreditDust);
    ck(BRWalletBalance(wDust) == 100000000, "wDust setup: DGB balance 1 DGB");

    // sub-$1 (1..99c) DD change must fail closed (spec §5.4 top-priority): hold 4050c, send 4000 -> change 50
    ck(BRWalletCreateDigiDollarTransfer(wDust, rk, 4000) == NULL, "0<ddChange<100 (dust) -> NULL");
    // recipient below $1 minimum -> NULL
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 50) == NULL, "cents < 100 -> NULL");
    // recipient above $100k max -> NULL
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 10000001) == NULL, "cents > 10000000 -> NULL");

    // ---- wExact: a single 4000-cent DD UTXO + a DGB fee UTXO. send 4000 -> ddChange 0 (no DD change out) ----
    BRWallet *wExact = BRWalletNew(NULL, 0, mpk84);
    ck(wExact != NULL, "wExact created");
    BRWalletSetTaprootKey(wExact, mpk86);
    BRAddress taE = BRWalletReceiveAddress(wExact, 2);
    uint8_t spkE[64]; size_t spkELen = BRAddressScriptPubKey(spkE, sizeof(spkE), taE.s);
    UInt256 h5; memset(h5.u8, 0x55, 32);
    BRTransaction *ddCreditExact = ddTx(spkE, spkELen, 4000, h5, 0);
    ddCreditExact->blockHeight = 700000;
    finalizeTxHash(ddCreditExact);
    BRWalletRegisterTransaction(wExact, ddCreditExact);
    ck(BRWalletDigiDollarBalance(wExact) == 4000, "wExact setup: DD balance 4000 cents");
    BRAddress daE = BRWalletReceiveAddress(wExact, 1);
    uint8_t dspkE[64]; size_t dspkELen = BRAddressScriptPubKey(dspkE, sizeof(dspkE), daE.s);
    UInt256 h6; memset(h6.u8, 0x66, 32);
    BRTransaction *dgbCreditExact = BRTransactionNew();
    dgbCreditExact->version = 1;
    BRTransactionAddInput(dgbCreditExact, h6, 0, 0, dspkE, dspkELen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dgbCreditExact, 100000000, dspkE, dspkELen);
    dgbCreditExact->blockHeight = 700000;
    finalizeTxHash(dgbCreditExact);
    BRWalletRegisterTransaction(wExact, dgbCreditExact);
    ck(BRWalletBalance(wExact) == 100000000, "wExact setup: DGB balance 1 DGB");

    // exact-change: hold exactly 4000c, send 4000 -> ddChange 0 -> NO DD change output (vout1 is OP_RETURN or DGB change)
    BRTransaction *te = BRWalletCreateDigiDollarTransfer(wExact, rk, 4000);
    ck(te != NULL, "exact-amount transfer builds");
    ck(BRDigiDollarDecodeAmounts(te, a, 8) == 1 && a[0] == 4000, "exact: OP_RETURN has ONE amount [4000], no change");
    BRTransactionFree(te);

    BRWalletFree(wExact);
    BRWalletFree(wDust);
    BRWalletFree(w);
    printf(g==0?"\nALL PASS\n":"\n%d FAIL\n",g); return g?1:0;
}
