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
// RED (comparison arm, -DDIGIDOLLAR_EVENT_UNFIXED=1): the rule before read the direction from the DGB
// movement, which the wallet's answers do not carry: the sent transfer reads "received $60.00" and the
// transfer to self "received $100.00". wallet_sent and wallet_self_transfer must fail in that arm; the
// other three are guards and hold in both.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>

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

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "wallet_received", wallet_received },
    { "wallet_sent", wallet_sent },
    { "wallet_self_transfer", wallet_self_transfer },
    { "wallet_plain_dgb", wallet_plain_dgb },
    { "wallet_no_answer", wallet_no_answer },
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
