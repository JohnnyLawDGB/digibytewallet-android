// Golden vector: BRDigiDollar.c decodes a REAL testnet26 DigiDollar TRANSFER -- and the activity list's
// DigiDollar event (digidollar_event.h) says what each kind of DigiDollar transaction is.
//
// The real transaction: DigiByte testnet26, block 83946, txid
//   3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346
// (captured 2026-07-05 from a v9.26.3 node). version 0x02000770; OP_RETURN
//   6a 02 4444 01 02 02c409 024c1d  => "DD" type2 amounts [2500, 7500] cents.
// vout0/vout1 zero-value P2TR (the two DD tokens), vout2 is 2.88 DGB change
// (non-zero, must be skipped), vout3 the OP_RETURN. vout1 and vout2 pay the same key: the sender's
// DigiDollar change and DGB change; vout0 ($25.00) is the payment.
//
// THE EVENT INVARIANT. A DigiDollar row says what the transaction is -- minted, sent, received, moved
// to the wallet itself, redeemed -- not the direction of its DGB movement. The real corpus holds only
// transfers, so the other shapes are synthesised from the wire format (docs/superpowers/specs/
// 2026-07-04-digidollar-wire-format.md §2.5-2.7): a mint (type 1: DGB locked as collateral, dollars
// created to the wallet), a transfer to the wallet's own address, a redemption that burns every
// dollar it spends (type 3 with no DigiDollar OP_RETURN at all), and a redemption with change.
//
// RED (comparison arm, -DDIGIDOLLAR_EVENT_UNFIXED=1): the rule the activity list used -- "sent" when
// the DGB movement is negative, counting the dollars paid to others; else "received", counting the
// dollars paid to the wallet. It labels the mint "sent $0", the transfer to self "sent $0", the full
// redemption "received $0" and the redemption with change "received $40". Each of those cases must
// fail in that arm, or this KAT has lost sight of the defect. A part sent to the wallet's own address
// reads as that part moved, with the rest as change.
// GUARDS (both arms): the real transfer decodes; seen from its receiver it is received, from its
// sender it is sent; a plain DGB transaction is no DigiDollar event.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRTransaction.h"
#include "BRDigiDollar.h"
#include "digidollar_event.h"

static int g = 0;
static void ck(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g++; }

static const char *HEX = "70070002000102c831f28e22826503174ad58ad774e8210b4148c09879c48a68280a308a3c61ea0100000000fffffffff18057367c04eacb4f4cc762e9a1808d3732e473cdac1621cef3447b1055dca50000000000ffffffff040000000000000000225120effc01464e19f45d27d3fd670e62f4b21a441e894bc9cd1726b99ca8c02000cd000000000000000022512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300882a110000000022512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300000000000000000c6a024444010202c409024c1d0140254bd6882c3308f50d10f435c71df63bdad5c44bb56168a2ca031b5d45cf742114b53d4c51fac01cf29a3c5567861cd2ef85395b0188c0b0b438af9a813c281b0140d74bfc3623042619dc236ed0144b69c0484410175f02bd9592573428e4b483024215e194215f43ba7aefcffb54832bfde53cd44e0ff9af802bb8029922df228400000000";

static BRTransaction *realTx(void)
{
    size_t n = strlen(HEX) / 2;
    uint8_t *b = malloc(n);
    for (size_t i = 0; i < n; i++) { unsigned x; sscanf(HEX + 2 * i, "%2x", &x); b[i] = (uint8_t)x; }
    BRTransaction *tx = BRTransactionParse(b, n);
    free(b);
    return tx;
}

// ---- a wallet, as the event asks it: which scripts it owns, which outpoints it spent -------------

typedef struct { UInt256 hash; uint32_t n; int64_t ddCents; uint64_t sats; } Prevout;
typedef struct {
    const uint8_t *mine[4]; size_t mineLen[4]; size_t nMine;   // scripts the wallet owns
    Prevout prev[6]; size_t nPrev;                              // the wallet's outputs these inputs spend
} FakeWallet;

static int fwPays(void *ctx, const BRTxOutput *o)
{
    FakeWallet *w = ctx;
    for (size_t i = 0; i < w->nMine; i++)
        if (o->script && o->scriptLen == w->mineLen[i] && memcmp(o->script, w->mine[i], o->scriptLen) == 0) return 1;
    return 0;
}

static int fwSpends(void *ctx, const BRTxInput *in, int64_t *ddCents)
{
    FakeWallet *w = ctx;
    *ddCents = -1;
    for (size_t i = 0; i < w->nPrev; i++) {
        if (w->prev[i].n == in->index && UInt256Eq(w->prev[i].hash, in->txHash)) { *ddCents = w->prev[i].ddCents; return 1; }
    }
    return 0;
}

// The transaction's net DGB movement for the wallet: what it pays the wallet less what it spends of it.
static int64_t fwDgbNet(void *ctx, const BRTransaction *tx)
{
    FakeWallet *w = ctx;
    int64_t net = 0;
    for (size_t i = 0; i < tx->outCount; i++) if (fwPays(ctx, &tx->outputs[i])) net += (int64_t)tx->outputs[i].amount;
    for (size_t i = 0; i < tx->inCount; i++)
        for (size_t j = 0; j < w->nPrev; j++)
            if (w->prev[j].n == tx->inputs[i].index && UInt256Eq(w->prev[j].hash, tx->inputs[i].txHash)) net -= (int64_t)w->prev[j].sats;
    return net;
}

static DdEvent eventFor(const BRTransaction *tx, FakeWallet *w)
{
    DdEventWallet ew = { w, fwPays, fwSpends, fwDgbNet };
    return dd_event_of(tx, &ew);
}

static const char *kindName(DdEventKind k)
{
    switch (k) {
    case DD_EVENT_NONE: return "NONE";
    case DD_EVENT_MINTED: return "MINTED";
    case DD_EVENT_SENT: return "SENT";
    case DD_EVENT_RECEIVED: return "RECEIVED";
    case DD_EVENT_SELF_TRANSFER: return "SELF_TRANSFER";
    case DD_EVENT_REDEEMED: return "REDEEMED";
    }
    return "?";
}

static void expect(DdEvent ev, DdEventKind kind, int64_t cents, int64_t change, const char *what)
{
    char line[256];
    snprintf(line, sizeof(line), "%s: %s $%lld.%02lld (change %lld c) -- got %s %lld c (change %lld c)", what,
             kindName(kind), (long long)(cents / 100), (long long)(cents % 100), (long long)change,
             kindName(ev.kind), (long long)ev.cents, (long long)ev.changeCents);
    ck(ev.kind == kind && ev.cents == cents && ev.changeCents == change, line);
}

// ---- synthesised shapes ---------------------------------------------------------------------------

static uint8_t OWN_DD[34], OTHER_DD[34], VAULT[34], OWN_DGB[22];

static void initScripts(void)
{
    OWN_DD[0] = OTHER_DD[0] = VAULT[0] = 0x51;
    OWN_DD[1] = OTHER_DD[1] = VAULT[1] = 0x20;
    memset(OWN_DD + 2, 0x0d, 32); memset(OTHER_DD + 2, 0x0e, 32); memset(VAULT + 2, 0xee, 32);
    OWN_DGB[0] = 0x00; OWN_DGB[1] = 0x14; memset(OWN_DGB + 2, 0x11, 20);
}

static FakeWallet ownsScripts(void)
{
    FakeWallet w;
    memset(&w, 0, sizeof(w));
    w.mine[0] = OWN_DD; w.mineLen[0] = sizeof(OWN_DD);
    w.mine[1] = OWN_DGB; w.mineLen[1] = sizeof(OWN_DGB);
    w.nMine = 2;
    return w;
}

// An input spending (tag-filled hash, n); recorded in the wallet as its own when `own`.
static void spend(BRTransaction *tx, FakeWallet *w, uint8_t tag, uint32_t n, int own, int64_t ddCents, uint64_t sats)
{
    UInt256 h;
    memset(h.u8, tag, sizeof(h.u8));
    BRTransactionAddInput(tx, h, n, sats, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);
    if (own) {
        Prevout *p = &w->prev[w->nPrev++];
        p->hash = h; p->n = n; p->ddCents = ddCents; p->sats = sats;
    }
}

static void realtx_decodes(void)
{
    BRTransaction *tx = realTx();
    ck(tx != NULL, "parse real testnet26 DD transfer");
    if (!tx) return;
    ck(BRDigiDollarTxType(tx) == 2, "type == TRANSFER(2)");
    int64_t amt[8]; int c = BRDigiDollarDecodeAmounts(tx, amt, 8);
    ck(c == 2 && amt[0] == 2500 && amt[1] == 7500, "amounts == [2500,7500] cents");
    ck(BRDigiDollarOutputAmount(tx, 0) == 2500, "vout0 (0-val P2TR) -> ordinal0 2500");
    ck(BRDigiDollarOutputAmount(tx, 1) == 7500, "vout1 (0-val P2TR) -> ordinal1 7500");
    ck(BRDigiDollarOutputAmount(tx, 2) == -1, "vout2 (2.88 DGB change) -> not DD");
    ck(BRDigiDollarOutputAmount(tx, 3) == -1, "vout3 (OP_RETURN) -> not DD");
    BRTransactionFree(tx);
}

static void event_realtx_received(void)
{
    BRTransaction *tx = realTx();
    if (!tx) { ck(0, "parse"); return; }
    FakeWallet w;
    memset(&w, 0, sizeof(w));
    w.mine[0] = tx->outputs[0].script; w.mineLen[0] = tx->outputs[0].scriptLen; w.nMine = 1;   // the payee
    expect(eventFor(tx, &w), DD_EVENT_RECEIVED, 2500, 0, "the real transfer, seen by its payee, is received");
    BRTransactionFree(tx);
}

static void event_realtx_sent(void)
{
    BRTransaction *tx = realTx();
    if (!tx) { ck(0, "parse"); return; }
    FakeWallet w;
    memset(&w, 0, sizeof(w));
    w.mine[0] = tx->outputs[1].script; w.mineLen[0] = tx->outputs[1].scriptLen; w.nMine = 1;   // the sender
    // The sender's two coins: its $100.00 of DigiDollar, then the DGB that pays the fee (2.88 DGB comes back).
    w.prev[0].hash = tx->inputs[0].txHash; w.prev[0].n = tx->inputs[0].index; w.prev[0].ddCents = 10000; w.prev[0].sats = 0;
    w.prev[1].hash = tx->inputs[1].txHash; w.prev[1].n = tx->inputs[1].index; w.prev[1].ddCents = -1; w.prev[1].sats = 298000000;
    w.nPrev = 2;
    DdEvent ev = eventFor(tx, &w);
    char line[160];
    snprintf(line, sizeof(line), "the real transfer, seen by its sender, is sent: $25.00 -- got %s %lld c", kindName(ev.kind), (long long)ev.cents);
    ck(ev.kind == DD_EVENT_SENT && ev.cents == 2500, line);
    BRTransactionFree(tx);
}

static void event_mint_created(void)
{
    // A mint: 200 DGB in; 100 DGB locked in the collateral vault, $100.00 created to the wallet, change back.
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x01000770;
    spend(tx, &w, 0xA1, 0, 1, -1, 20000000000ULL);
    BRTransactionAddOutput(tx, 10000000000ULL, VAULT, sizeof(VAULT));
    BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    BRTransactionAddOutput(tx, 9990000000ULL, OWN_DGB, sizeof(OWN_DGB));
    // 6a 02 'DD' 01 01 <10000> <lockHeight 22,500,000> <tier 0> 20 <owner x-only key>
    uint8_t orr[64]; size_t ol = 0;
    const uint8_t head[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x01, 0x02, 0x10, 0x27, 0x04, 0xa0, 0x52, 0x57, 0x01, 0x00, 0x20 };
    memcpy(orr, head, sizeof(head)); ol = sizeof(head);
    memcpy(orr + ol, OWN_DD + 2, 32); ol += 32;
    BRTransactionAddOutput(tx, 0, orr, ol);
    ck(BRDigiDollarTxType(tx) == DD_TYPE_MINT && BRDigiDollarOutputAmount(tx, 1) == 10000, "the mint decodes: type 1, $100.00 at its token output");
    expect(eventFor(tx, &w), DD_EVENT_MINTED, 10000, 0, "a mint is minted");
    BRTransactionFree(tx);
}

static void event_self_transfer(void)
{
    // A transfer of the wallet's $100.00 to its own address; 0.1 DGB fee, the rest of the DGB back.
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    spend(tx, &w, 0xB1, 0, 1, 10000, 0);
    spend(tx, &w, 0xB2, 0, 1, -1, 50000000);
    BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    BRTransactionAddOutput(tx, 40000000, OWN_DGB, sizeof(OWN_DGB));
    const uint8_t orr[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x10, 0x27 };
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    expect(eventFor(tx, &w), DD_EVENT_SELF_TRANSFER, 10000, 0, "a transfer to the wallet's own address moves $100.00 to itself");
    BRTransactionFree(tx);
}

static void event_self_transfer_with_change(void)
{
    // $40.00 of the wallet's $100.00 coin sent to its own address: the payment at output 0, $60.00 of
    // change at output 1, both the wallet's.
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    spend(tx, &w, 0xB5, 0, 1, 10000, 0);
    spend(tx, &w, 0xB6, 0, 1, -1, 50000000);
    BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    BRTransactionAddOutput(tx, 40000000, OWN_DGB, sizeof(OWN_DGB));
    const uint8_t orr[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0xa0, 0x0f, 0x02, 0x70, 0x17 };   // [4000, 6000]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    expect(eventFor(tx, &w), DD_EVENT_SELF_TRANSFER, 4000, 6000, "a part sent to the wallet's own address moves $40.00 to itself, $60.00 change");
    BRTransactionFree(tx);
}

static void event_redeem_full(void)
{
    // A redemption that burns all $100.00: no DigiDollar OP_RETURN at all (wire format §2.6), the
    // collateral's 100 DGB comes back less the fee.
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x03000770;
    spend(tx, &w, 0xC0, 0, 0, -1, 10000000000ULL);   // the vault: not an address of the wallet
    spend(tx, &w, 0xC1, 1, 1, 10000, 0);
    spend(tx, &w, 0xC2, 0, 1, -1, 20000000);
    BRTransactionAddOutput(tx, 10010000000ULL, OWN_DGB, sizeof(OWN_DGB));
    ck(BRDigiDollarTxType(tx) == DD_TYPE_REDEEM, "the redemption is a DigiDollar transaction by its version alone");
    expect(eventFor(tx, &w), DD_EVENT_REDEEMED, 10000, 0, "a redemption burning every dollar it spends is redeemed");
    BRTransactionFree(tx);
}

static void event_redeem_with_change(void)
{
    // A redemption of $60.00 of the wallet's $100.00 coin: $40.00 comes back as DigiDollar change.
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x03000770;
    spend(tx, &w, 0xD0, 0, 0, -1, 6000000000ULL);
    spend(tx, &w, 0xD1, 1, 1, 10000, 0);
    spend(tx, &w, 0xD2, 0, 1, -1, 20000000);
    BRTransactionAddOutput(tx, 6010000000ULL, OWN_DGB, sizeof(OWN_DGB));
    BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    const uint8_t orr[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x03, 0x02, 0xa0, 0x0f };   // 'DD' type 3 [4000]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    ck(BRDigiDollarOutputAmount(tx, 1) == 4000, "the redemption's change decodes: $40.00 at its token output");
    expect(eventFor(tx, &w), DD_EVENT_REDEEMED, 6000, 4000, "a redemption with change is redeemed: $60.00 burned");
    BRTransactionFree(tx);
}

static void event_plain_dgb(void)
{
    FakeWallet w = ownsScripts();
    BRTransaction *tx = BRTransactionNew();
    tx->version = 1;
    spend(tx, &w, 0xE1, 0, 1, -1, 50000000);
    BRTransactionAddOutput(tx, 40000000, OWN_DGB, sizeof(OWN_DGB));
    DdEvent ev = eventFor(tx, &w);
    ck(ev.kind == DD_EVENT_NONE && ev.cents == 0, "a plain DGB transaction is no DigiDollar event");
    BRTransactionFree(tx);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "realtx_decodes", realtx_decodes },
    { "event_realtx_received", event_realtx_received },
    { "event_realtx_sent", event_realtx_sent },
    { "event_mint_created", event_mint_created },
    { "event_self_transfer", event_self_transfer },
    { "event_self_transfer_with_change", event_self_transfer_with_change },
    { "event_redeem_full", event_redeem_full },
    { "event_redeem_with_change", event_redeem_with_change },
    { "event_plain_dgb", event_plain_dgb },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    initScripts();
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", DIGIDOLLAR_EVENT_UNFIXED ? "comparison (DIGIDOLLAR_EVENT_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g ? "fail" : "pass");
        return g ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
