// Host KAT: BRDigiDollarTransferLayout.h and the wallet's own builder agree.
//
// WHY. The layout header states the shape of a DigiDollar TRANSFER for a caller that assembles one outside
// the wallet (the recovery transfer). The wallet's own send is built by BRWalletCreateDigiDollarTransfer,
// which keeps its own private copies of the same values in BRWallet.c. Two copies of a consensus value
// drift; this main holds them together by what the builder DOES, not by its text: a transfer it builds
// carries the header's version, the header's recipient script, the header's OP_RETURN bytes in the header's
// place (last), and pays the header's fee floor; and it accepts and refuses an amount exactly where
// BRDigiDollarCentsInRange does.
//
// Each expectation is also written here as a literal, so that the two copies moving together would still
// be seen. No comparison arm: neither copy has a seam, and the builder is not this KAT's to change.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRDigiDollar.h"
#include "BRDigiDollarTransferLayout.h"

static int failures = 0;

static void ok(const char *what, int cond) {
    printf("  %s %s\n", cond ? "ok  " : "FAIL", what);
    if (!cond) failures++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;
static const uint8_t kPlaceholder[1] = {0};
static const uint8_t kRecip[32] = {
    0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
    0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A wallet holding one confirmed DigiDollar coin for each of the n OP_RETURNs given (each a whole
// "DD" type 2 marker naming one amount), and one DGB coin of `sats`.
static BRWallet *ddWallet(const uint8_t *const *markers, const size_t *markerLens, size_t n, uint64_t sats)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    for (size_t i = 0; i < n; i++) {
        BRTransaction *dd = BRTransactionNew();
        UInt256 ph;
        memset(ph.u8, (int)(0x11 + i), sizeof(ph.u8));
        dd->version = 0x02000770;
        BRTransactionAddInput(dd, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
        BRTransactionAddOutput(dd, 0, spk, spkLen);
        BRTransactionAddOutput(dd, 0, markers[i], markerLens[i]);
        dd->blockHeight = 23700000;
        finalizeTxHash(dd);
        BRWalletRegisterTransaction(w, dd);
    }
    BRAddress da = BRWalletReceiveAddress(w, 1);
    uint8_t dspk[64];
    size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    BRTransaction *c = BRTransactionNew();
    UInt256 h;
    memset(h.u8, 0xC1, sizeof(h.u8));
    c->version = 1;
    BRTransactionAddInput(c, h, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(c, sats, dspk, dspkLen);
    c->blockHeight = 23700000;
    c->timestamp = 1700000000;
    finalizeTxHash(c);
    BRWalletRegisterTransaction(w, c);
    return w;
}

static const uint8_t kHundred[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x10, 0x27 };               // $100.00: 10000 c
static const uint8_t kHundredK[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x04, 0x80, 0x96, 0x98, 0x00 };  // $100,000.00: 10000000 c

int main(void) {
    printf("digidollar_transfer_layout_core_kat (builder)\n");
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));

    /* ---- a $40.00 transfer out of $100.00, the fee from a 0.5 DGB coin ------------------- */
    {
        const uint8_t *m[1] = { kHundred };
        const size_t ml[1] = { sizeof(kHundred) };
        BRWallet *w = ddWallet(m, ml, 1, 50000000);
        BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);
        ok("setup: the wallet builds a $40.00 transfer out of $100.00", tx != NULL && tx->outCount >= 2);
        if (tx && tx->outCount >= 2) {
            ok("the built transfer's nVersion is 0x02000770", tx->version == 0x02000770u);
            ok("and that is the header's", tx->version == BR_DIGIDOLLAR_TX_VERSION_TRANSFER);

            uint8_t spk[34];
            size_t sl = BRDigiDollarRecipientScript(kRecip, spk);
            const BRTxOutput *pay = &tx->outputs[0];
            ok("output 0 pays zero DGB to OP_1 <the recipient's key>",
               pay->amount == 0 && pay->scriptLen == 34 && pay->script[0] == 0x51 && pay->script[1] == 0x20 &&
               memcmp(pay->script + 2, kRecip, 32) == 0);
            ok("and that script is the header's", pay->scriptLen == sl && memcmp(pay->script, spk, sl) == 0);

            const uint8_t expect[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0xa0, 0x0f, 0x02, 0x70, 0x17 };
            uint8_t orr[32];
            size_t ol = BRDigiDollarOpReturnScript(4000, 6000, orr, sizeof(orr));
            const BRTxOutput *last = &tx->outputs[tx->outCount - 1];
            size_t opReturns = 0;
            for (size_t i = 0; i < tx->outCount; i++)
                if (tx->outputs[i].scriptLen > 0 && tx->outputs[i].script[0] == 0x6a) opReturns++;
            ok("the last output is the OP_RETURN, and the only one",
               opReturns == 1 && last->amount == 0 && last->scriptLen > 0 && last->script[0] == 0x6a);
            ok("it reads 'DD' type 2 [4000 c, 6000 c]",
               last->scriptLen == sizeof(expect) && memcmp(last->script, expect, sizeof(expect)) == 0);
            ok("and those bytes are the header's", last->scriptLen == ol && memcmp(last->script, orr, ol) == 0);

            uint64_t in = 0, out = 0;
            for (size_t i = 0; i < tx->inCount; i++) in += tx->inputs[i].amount;
            for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
            ok("the built transfer pays 0.1 DGB", in - out == 10000000ULL);
            ok("and that is the header's fee floor", in - out == BR_DIGIDOLLAR_MIN_FEE);
        }
        if (tx) BRTransactionFree(tx);

        /* ---- the lower bound ---------------------------------------------------------------- */
        BRTransaction *low = BRWalletCreateDigiDollarTransfer(w, kRecip, 99);
        ok("the builder refuses $0.99", low == NULL);
        ok("and so does the header", !BRDigiDollarCentsInRange(99));
        if (low) BRTransactionFree(low);
        BRTransaction *min = BRWalletCreateDigiDollarTransfer(w, kRecip, 100);
        ok("the builder builds $1.00", min != NULL);
        ok("and the header accepts it", BRDigiDollarCentsInRange(100));
        if (min) BRTransactionFree(min);
        BRWalletFree(w);
    }

    /* ---- the upper bound: a wallet holding $100,100.00, so that a refusal is not a shortfall -- */
    {
        const uint8_t *m[2] = { kHundredK, kHundred };
        const size_t ml[2] = { sizeof(kHundredK), sizeof(kHundred) };
        BRWallet *w = ddWallet(m, ml, 2, 50000000);
        BRTransaction *max = BRWalletCreateDigiDollarTransfer(w, kRecip, 10000000);
        ok("the builder builds $100,000.00", max != NULL);
        ok("and the header accepts it", BRDigiDollarCentsInRange(10000000));
        if (max) BRTransactionFree(max);
        BRTransaction *over = BRWalletCreateDigiDollarTransfer(w, kRecip, 10000001);
        ok("the builder refuses a cent more, with the dollars to cover it", over == NULL);
        ok("and so does the header", !BRDigiDollarCentsInRange(10000001));
        if (over) BRTransactionFree(over);
        BRWalletFree(w);
    }

    printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
