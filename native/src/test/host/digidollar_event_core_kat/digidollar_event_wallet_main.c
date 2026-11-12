// Host KAT: the wallet's own answers to the DigiDollar event rule (BRDigiDollarEvent.h:
// BRWalletDigiDollarEvent, BRWalletDigiDollarEventForHash), against a real BRWallet.
//
// digidollar_event_core_kat_main.c runs the rule (BRDigiDollarEventOf) over a fake wallet. The two answers
// the rule asks for -- does this output pay the wallet, does this input spend one of its outputs and how
// many cents did that output hold -- were Android bridge code (jni_transaction.c: _ddEventPaysWallet,
// _ddEventSpendsWallet, digiDollarEvent) that no host test could reach. They moved into the core with the
// rule; this main runs them against a wallet that holds one confirmed $100.00 DigiDollar coin and one DGB
// coin (the fixture is digidollar_send_core_kat's).
//
//   wallet_received        the transaction that paid the wallet its $100.00 is RECEIVED $100.00, read
//                          directly and by its hash.
//   wallet_sent            a $40.00 transfer the wallet builds to another key is SENT $40.00 with $60.00
//                          change -- unsigned, and again by its hash once signed and registered.
//   wallet_self_transfer   a $50.00 transfer to the wallet's own DigiDollar key is SELF_TRANSFER $50.00
//                          with $50.00 change.
//   wallet_plain_dgb       the transaction that paid the wallet its DGB coin is no DigiDollar event.
//   wallet_no_answer       a hash the wallet does not hold, a NULL wallet and a NULL out answer 0 and leave
//                          out untouched; a NULL wallet or tx is NONE.
//
// The four below pin what the wallet answers for an input: whether it spends one of the wallet's outputs,
// and the cents that output held. A redemption is the one row that shows those cents (burned = spent less
// change), so the amount is read through one; a transfer that pays the wallet is RECEIVED only while none
// of its inputs is taken for the wallet's, so the ownership is read through one.
//
//   wallet_redeemed        a redemption that spends the $100.00 coin and brings $40.00 back is REDEEMED
//                          $60.00 with $40.00 change: the cents are the spent output's, and the DGB coin
//                          beside it adds none.
//   wallet_redeemed_second_output
//                          the same for a coin held at output 1 ($75.00), beside another key's $25.00 at
//                          output 0 of the same transaction: REDEEMED $65.00 with $10.00 change. The cents
//                          are those of the output the input names.
//   wallet_received_others_output
//                          a transfer that spends that other key's output 0 and pays the wallet $25.00 is
//                          RECEIVED $25.00: an output of a transaction the wallet holds is the wallet's
//                          only when its address is.
//   wallet_received_missing_output
//                          a transfer whose inputs name outputs 2, 7 and 0xffffffff of a held transaction
//                          that has outputs 0 and 1, and that pays the wallet $40.00, is RECEIVED $40.00:
//                          an output the transaction does not have is not read and is nobody's.
//   wallet_redeemed_two_coins
//                          a redemption that spends both of the wallet's coins, $100.00 and $75.00, and
//                          brings $40.00 back is REDEEMED $135.00 with $40.00 change: every input the
//                          wallet's answers take for its own adds its cents, not the first or the last.
//   wallet_redeemed_others_coin
//                          a redemption that spends only the other key's $25.00 and pays the wallet $10.00
//                          change is REDEEMED $0.00 with $10.00 change: the wallet burned none of its own,
//                          and burned is never below zero.
//
// RED (comparison arm, -DDIGIDOLLAR_EVENT_UNFIXED=1): the rule before read the direction from the DGB
// movement, which the wallet's answers do not carry: the sent transfer reads "received $60.00", the
// transfer to self "received $100.00" and the four redemptions "received $40.00", "received $10.00",
// "received $40.00" and "received $10.00". wallet_sent, wallet_self_transfer and the four wallet_redeemed*
// cases must fail in that arm; the other five are guards and hold in both.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>

#include "BRArray.h"
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRDigiDollar.h"
#include "BRDigiDollarEvent.h"

static int g_failed = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_failed = 1;
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

static const char *kindName(BRDigiDollarEventKind k)
{
    switch (k) {
    case BR_DIGIDOLLAR_EVENT_NONE: return "NONE";
    case BR_DIGIDOLLAR_EVENT_MINTED: return "MINTED";
    case BR_DIGIDOLLAR_EVENT_SENT: return "SENT";
    case BR_DIGIDOLLAR_EVENT_RECEIVED: return "RECEIVED";
    case BR_DIGIDOLLAR_EVENT_SELF_TRANSFER: return "SELF_TRANSFER";
    case BR_DIGIDOLLAR_EVENT_REDEEMED: return "REDEEMED";
    }
    return "?";
}

// The expectation is written here, as literals: a kind by its number in the header's table, and cents.
static void expect(BRDigiDollarEvent ev, int kind, int64_t cents, int64_t change, const char *what)
{
    ck((int)ev.kind == kind && ev.cents == cents && ev.changeCents == change,
       "%s: want kind %d, %lld c, change %lld c -- got %s (%d), %lld c, change %lld c", what, kind,
       (long long)cents, (long long)change, kindName(ev.kind), (int)ev.kind, (long long)ev.cents,
       (long long)ev.changeCents);
}

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A wallet holding one confirmed $100.00 DigiDollar coin and the DGB coin `sats`. *ddHash and *dgbHash are
// the hashes of the two transactions that paid it.
static BRWallet *ddWallet(uint64_t sats, UInt256 *ddHash, UInt256 *dgbHash)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    BRTransaction *dd = BRTransactionNew();
    UInt256 ph;
    memset(ph.u8, 0x11, sizeof(ph.u8));
    dd->version = 0x02000770;
    BRTransactionAddInput(dd, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dd, 0, spk, spkLen);
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x10, 0x27 };   // "DD" type 2 [10000 c]
    BRTransactionAddOutput(dd, 0, orr, sizeof(orr));
    dd->blockHeight = 23700000;
    finalizeTxHash(dd);
    *ddHash = dd->txHash;
    BRWalletRegisterTransaction(w, dd);

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
    *dgbHash = c->txHash;
    BRWalletRegisterTransaction(w, c);
    return w;
}

// The wallet's own DigiDollar key: the taproot output key of its first taproot receive address.
static int ownKey(BRWallet *w, uint8_t key[32])
{
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t n = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    if (n != 34 || spk[0] != 0x51 || spk[1] != 0x20) return 0;
    memcpy(key, spk + 2, 32);
    return 1;
}

static void wallet_received(void)
{
    UInt256 ddHash, dgbHash;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    BRTransaction *held = BRWalletCopyTransactionForHash(w, ddHash);
    ck(held != NULL, "setup: the wallet holds the transaction that paid it $100.00");
    if (held) {
        expect(BRWalletDigiDollarEvent(w, held), 3, 10000, 0, "the transaction that paid the wallet $100.00, read directly");
        BRTransactionFree(held);
    }
    BRDigiDollarEvent ev = { BR_DIGIDOLLAR_EVENT_NONE, -7, -7 };
    ck(BRWalletDigiDollarEventForHash(w, ddHash, &ev) == 1, "the wallet answers for that transaction by its hash");
    expect(ev, 3, 10000, 0, "the same transaction, by its hash");
    BRWalletFree(w);
}

static void wallet_sent(void)
{
    UInt256 ddHash, dgbHash;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);   // $40.00 of $100.00
    ck(tx != NULL, "setup: a $40.00 transfer to another key builds");
    if (!tx) { BRWalletFree(w); return; }
    expect(BRWalletDigiDollarEvent(w, tx), 2, 4000, 6000, "the built transfer, unsigned");
    int kept = BRWalletSignTransaction(w, tx, 0, g_seed, sizeof(g_seed)) && BRTransactionIsSigned(tx);
    UInt256 sentHash = tx->txHash;
    kept = kept && BRWalletRegisterTransaction(w, tx);   // the wallet's now
    ck(kept, "setup: the wallet signs and registers the transfer");
    if (!kept) { BRTransactionFree(tx); BRWalletFree(w); return; }
    BRDigiDollarEvent ev = { BR_DIGIDOLLAR_EVENT_NONE, -7, -7 };
    ck(BRWalletDigiDollarEventForHash(w, sentHash, &ev) == 1, "the wallet answers for the registered transfer by its hash");
    expect(ev, 2, 4000, 6000, "the registered transfer, by its hash");
    BRWalletFree(w);
}

static void wallet_self_transfer(void)
{
    UInt256 ddHash, dgbHash;
    uint8_t own[32];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, own, 5000);   // $50.00 of $100.00, to itself
    ck(tx != NULL, "setup: a $50.00 transfer to the wallet's own key builds");
    if (tx) {
        expect(BRWalletDigiDollarEvent(w, tx), 4, 5000, 5000, "the transfer to the wallet's own key");
        BRTransactionFree(tx);
    }
    BRWalletFree(w);
}

static void wallet_plain_dgb(void)
{
    UInt256 ddHash, dgbHash;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    BRDigiDollarEvent ev = { BR_DIGIDOLLAR_EVENT_SENT, -7, -7 };
    ck(BRWalletDigiDollarEventForHash(w, dgbHash, &ev) == 1, "the wallet answers for the transaction that paid it DGB");
    expect(ev, 0, 0, 0, "a plain DGB transaction");
    BRWalletFree(w);
}

static void wallet_no_answer(void)
{
    UInt256 ddHash, dgbHash, unknown;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    BRDigiDollarEvent ev = { BR_DIGIDOLLAR_EVENT_REDEEMED, -7, -9 };
    memset(unknown.u8, 0xEE, sizeof(unknown.u8));
    ck(BRWalletDigiDollarEventForHash(w, unknown, &ev) == 0, "a hash the wallet does not hold has no answer");
    ck(BRWalletDigiDollarEventForHash(NULL, ddHash, &ev) == 0, "a NULL wallet has no answer");
    ck(BRWalletDigiDollarEventForHash(w, ddHash, NULL) == 0, "a NULL out has no answer");
    ck((int)ev.kind == 5 && ev.cents == -7 && ev.changeCents == -9, "and out is left as it was");
    BRTransaction *held = BRWalletCopyTransactionForHash(w, ddHash);
    ck(held != NULL, "setup: the wallet holds the transaction that paid it $100.00");
    if (held) {
        expect(BRWalletDigiDollarEvent(NULL, held), 0, 0, 0, "a NULL wallet");
        BRTransactionFree(held);
    }
    expect(BRWalletDigiDollarEvent(w, NULL), 0, 0, 0, "a NULL transaction");
    BRWalletFree(w);
}

// The DigiDollar token script of `key`: OP_1 <32-byte key>.
static void ddScript(uint8_t spk[34], const uint8_t key[32])
{
    spk[0] = 0x51; spk[1] = 0x20;
    memcpy(spk + 2, key, 32);
}

// A confirmed transfer the wallet did not pay for, registered with it: output 0 pays another key $25.00,
// output 1 the wallet's key `own` $75.00, output 2 is the amount list. 1 with *hash set once the wallet
// holds it.
static int holdSplit(BRWallet *w, const uint8_t own[32], UInt256 *hash)
{
    uint8_t spk[34];
    BRTransaction *tx = BRTransactionNew();
    UInt256 ph;
    memset(ph.u8, 0x22, sizeof(ph.u8));
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, ph, 0, 0, NULL, 0, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    ddScript(spk, kRecip);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    ddScript(spk, own);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    // "DD" type 2 [c4 09 = 0x09c4 = 2500 c, 4c 1d = 0x1d4c = 7500 c]
    const uint8_t orr[12] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0xc4, 0x09, 0x02, 0x4c, 0x1d };
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    tx->blockHeight = 23700001;
    finalizeTxHash(tx);
    *hash = tx->txHash;
    if (BRWalletRegisterTransaction(w, tx)) return 1;   // the wallet's now
    BRTransactionFree(tx);
    return 0;
}

// A redemption, not registered: it spends a collateral vault the wallet does not hold, the DigiDollar coin
// `coin`:`n` and the DGB coin `dgb`:0; the collateral's DGB comes back at output 0, DigiDollar change to
// the wallet's key `own` at output 1, and output 2 is `orr`, the "DD" type 3 list with that change.
static BRTransaction *redemption(BRWallet *w, UInt256 coin, uint32_t n, UInt256 dgb, const uint8_t own[32],
                                 const uint8_t *orr, size_t orrLen)
{
    uint8_t spk[34], dspk[64];
    BRAddress da = BRWalletReceiveAddress(w, 1);
    size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    BRTransaction *tx = BRTransactionNew();
    UInt256 vault;
    memset(vault.u8, 0xD0, sizeof(vault.u8));
    tx->version = 0x03000770;
    BRTransactionAddInput(tx, vault, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, coin, n, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, dgb, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 6010000000ULL, dspk, dspkLen);
    ddScript(spk, own);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    BRTransactionAddOutput(tx, 0, orr, orrLen);
    return tx;
}

static void wallet_redeemed(void)
{
    UInt256 ddHash, dgbHash;
    uint8_t own[32];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x03, 0x02, 0xa0, 0x0f };   // type 3 [a0 0f = 0x0fa0 = 4000 c]
    BRTransaction *tx = redemption(w, ddHash, 0, dgbHash, own, orr, sizeof(orr));
    ck(BRDigiDollarOutputAmount(tx, 1) == 4000, "setup: the redemption's change decodes, $40.00 at output 1");
    // Spent: output 0 of the transaction that paid the wallet, 10000 c (its list is [10 27 = 0x2710]); the
    // vault and the DGB coin hold none. Paid back: 4000 c. Burned: 10000 - 4000 = 6000 c.
    expect(BRWalletDigiDollarEvent(w, tx), 5, 6000, 4000, "a redemption of $60.00 of the wallet's $100.00 coin");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void wallet_redeemed_second_output(void)
{
    UInt256 ddHash, dgbHash, splitHash;
    uint8_t own[32];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    int held = holdSplit(w, own, &splitHash);
    ck(held, "setup: the wallet holds a transfer that paid another key $25.00 at output 0 and it $75.00 at output 1");
    if (!held) { BRWalletFree(w); return; }
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x03, 0x02, 0xe8, 0x03 };   // type 3 [e8 03 = 0x03e8 = 1000 c]
    BRTransaction *tx = redemption(w, splitHash, 1, dgbHash, own, orr, sizeof(orr));
    ck(BRDigiDollarOutputAmount(tx, 1) == 1000, "setup: the redemption's change decodes, $10.00 at output 1");
    // Spent: output 1 of the held transfer, the second amount of its list, 7500 c (output 0, the first,
    // 2500 c, is another key's and is not spent). Paid back: 1000 c. Burned: 7500 - 1000 = 6500 c.
    expect(BRWalletDigiDollarEvent(w, tx), 5, 6500, 1000, "a redemption of $65.00 of the wallet's $75.00 coin at output 1");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void wallet_received_others_output(void)
{
    UInt256 ddHash, dgbHash, splitHash;
    uint8_t own[32], spk[34];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    int held = holdSplit(w, own, &splitHash);
    ck(held, "setup: the wallet holds a transfer that paid another key $25.00 at output 0 and it $75.00 at output 1");
    if (!held) { BRWalletFree(w); return; }
    // The other key spends its $25.00 (output 0 of the held transfer) and pays the wallet all of it. The
    // wallet holds the transaction the input names, but not that output: it paid for nothing here, and
    // its one DigiDollar output is 2500 c.
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, splitHash, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    ddScript(spk, own);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0xc4, 0x09 };   // type 2 [c4 09 = 0x09c4 = 2500 c]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    expect(BRWalletDigiDollarEvent(w, tx), 3, 2500, 0,
           "a payment of $25.00 that spends another key's output of a held transaction");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void wallet_received_missing_output(void)
{
    UInt256 ddHash, dgbHash;
    uint8_t own[32], spk[34];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    BRTransaction *held = BRWalletCopyTransactionForHash(w, ddHash);
    ck(held != NULL && held->outCount == 2, "setup: the transaction that paid the wallet $100.00 has outputs 0 and 1");
    // The copy the wallet hands out ends at its last output, with no spare slot behind it: a bound that let
    // index 2 through would read past the array, and AddressSanitizer would say so. Without this check that
    // catch would rest, unstated, on the capacity BRTransactionNew happens to give a new transaction.
    ck(held != NULL && array_capacity(held->outputs) == held->outCount,
       "setup: the copy's outputs array holds exactly its outputs (capacity == outCount)");
    if (held) BRTransactionFree(held);
    // Three inputs name that transaction, at the output just past its last, at 7 and at 0xffffffff. It has
    // none of them, so no input spends the wallet's: it paid for nothing here, and its one DigiDollar
    // output is 4000 c.
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, ddHash, 2, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, ddHash, 7, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, ddHash, 0xffffffffu, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    ddScript(spk, own);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0xa0, 0x0f };   // type 2 [a0 0f = 0x0fa0 = 4000 c]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    expect(BRWalletDigiDollarEvent(w, tx), 3, 4000, 0,
           "a payment of $40.00 whose inputs name outputs a held transaction does not have");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void wallet_redeemed_two_coins(void)
{
    UInt256 ddHash, dgbHash, splitHash, vault;
    uint8_t own[32], spk[34], dspk[64];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    int held = holdSplit(w, own, &splitHash);
    ck(held, "setup: the wallet holds a transfer that paid another key $25.00 at output 0 and it $75.00 at output 1");
    if (!held) { BRWalletFree(w); return; }
    // redemption()'s layout with a second DigiDollar coin: the vault, both of the wallet's coins and the
    // DGB coin in; the collateral's DGB, DigiDollar change and the type 3 list out.
    BRAddress da = BRWalletReceiveAddress(w, 1);
    size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    BRTransaction *tx = BRTransactionNew();
    memset(vault.u8, 0xD0, sizeof(vault.u8));
    tx->version = 0x03000770;
    BRTransactionAddInput(tx, vault, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, ddHash, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, splitHash, 1, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddInput(tx, dgbHash, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 6010000000ULL, dspk, dspkLen);
    ddScript(spk, own);
    BRTransactionAddOutput(tx, 0, spk, sizeof(spk));
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x03, 0x02, 0xa0, 0x0f };   // type 3 [a0 0f = 0x0fa0 = 4000 c]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    ck(BRDigiDollarOutputAmount(tx, 1) == 4000, "setup: the redemption's change decodes, $40.00 at output 1");
    // Spent: output 0 of the transaction that paid the wallet, 10000 c (its list is [10 27 = 0x2710]), and
    // output 1 of the held transfer, 7500 c (the second amount of its list, [4c 1d = 0x1d4c]); the vault and
    // the DGB coin hold none. Paid back: 4000 c. Burned: 10000 + 7500 - 4000 = 13500 c. Counting only the
    // last coin would give 7500 - 4000 = 3500 c, only the first 10000 - 4000 = 6000 c.
    expect(BRWalletDigiDollarEvent(w, tx), 5, 13500, 4000, "a redemption of $135.00 of the wallet's two coins, $100.00 and $75.00");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void wallet_redeemed_others_coin(void)
{
    UInt256 ddHash, dgbHash, splitHash;
    uint8_t own[32];
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    ck(ownKey(w, own), "setup: the wallet's own DigiDollar key");
    int held = holdSplit(w, own, &splitHash);
    ck(held, "setup: the wallet holds a transfer that paid another key $25.00 at output 0 and it $75.00 at output 1");
    if (!held) { BRWalletFree(w); return; }
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x03, 0x02, 0xe8, 0x03 };   // type 3 [e8 03 = 0x03e8 = 1000 c]
    BRTransaction *tx = redemption(w, splitHash, 0, dgbHash, own, orr, sizeof(orr));
    ck(BRDigiDollarOutputAmount(tx, 1) == 1000, "setup: the redemption's change decodes, $10.00 at output 1");
    // Spent: output 0 of the held transfer, 2500 c, is another key's, and the wallet's DGB coin holds no
    // DigiDollar: 0 c of the wallet's. Paid back: 1000 c. Burned: 0 - 1000 is below zero, so 0 c, never
    // -1000 c.
    expect(BRWalletDigiDollarEvent(w, tx), 5, 0, 1000, "a redemption of another key's $25.00 coin with $10.00 change to the wallet");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "wallet_received", wallet_received },
    { "wallet_sent", wallet_sent },
    { "wallet_self_transfer", wallet_self_transfer },
    { "wallet_plain_dgb", wallet_plain_dgb },
    { "wallet_no_answer", wallet_no_answer },
    { "wallet_redeemed", wallet_redeemed },
    { "wallet_redeemed_second_output", wallet_redeemed_second_output },
    { "wallet_received_others_output", wallet_received_others_output },
    { "wallet_received_missing_output", wallet_received_missing_output },
    { "wallet_redeemed_two_coins", wallet_redeemed_two_coins },
    { "wallet_redeemed_others_coin", wallet_redeemed_others_coin },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", DIGIDOLLAR_EVENT_UNFIXED ? "comparison (DIGIDOLLAR_EVENT_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
