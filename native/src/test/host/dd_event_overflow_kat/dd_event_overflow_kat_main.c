// Host KAT: a DigiDollar activity amount cannot overflow (4.1.2 row 10, N5).
//   [A] a transfer paying the wallet five outputs of 2^62 cents: dd_event_of makes no activity row
//       (DD_EVENT_NONE), rather than a RECEIVED row with a wrapped amount -- RED under
//       DD_EVENT_OVERFLOW_UNFIXED=1 (UBSan: signed integer overflow);
//   [B] guard: an ordinary transfer paying the wallet 5 x $1.00 is RECEIVED $5.00.
// The fake wallet is digidollar_realtx_kat's.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRTransaction.h"
#include "BRDigiDollar.h"
#include "digidollar_event.h"

static int g = 0;
static void ck(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g++; }

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

// A transfer from someone else paying the wallet `n` DigiDollar outputs of `cents` each.
static BRTransaction *receiveTx(FakeWallet *w, int n, int64_t cents)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 h; memset(h.u8, 0xC1, sizeof(h.u8));
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, h, 0, 0, NULL, 0, NULL, 0, NULL, 0, 0xffffffff);   // not the wallet's
    for (int i = 0; i < n; i++) BRTransactionAddOutput(tx, 0, OWN_DD, sizeof(OWN_DD));
    uint8_t orr[128]; size_t ol = 0;
    const uint8_t head[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02 };   // OP_RETURN 'DD' v1 TRANSFER
    memcpy(orr, head, sizeof(head)); ol = sizeof(head);
    for (int i = 0; i < n; i++) {
        uint8_t num[9]; size_t nl = BRDigiDollarWriteScriptNum(cents, num);
        orr[ol++] = (uint8_t)nl; memcpy(orr + ol, num, nl); ol += nl;
    }
    BRTransactionAddOutput(tx, 0, orr, ol);
    (void)w;
    return tx;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    initScripts();
    FakeWallet w; memset(&w, 0, sizeof(w));
    w.mine[0] = OWN_DD; w.mineLen[0] = sizeof(OWN_DD); w.mine[1] = OWN_DGB; w.mineLen[1] = sizeof(OWN_DGB); w.nMine = 2;

    printf("[A] five outputs of 2^62 cents\n");
    BRTransaction *big = receiveTx(&w, 5, (int64_t)1 << 62);
    ck(BRDigiDollarOutputAmount(big, 0) == ((int64_t)1 << 62), "setup: each output decodes to 2^62 cents");
    DdEvent ev = eventFor(big, &w);
    if (ev.kind != DD_EVENT_NONE) { printf("FAIL: A: an activity row was made from an amount that does not fit (%s %lld c)\n", kindName(ev.kind), (long long)ev.cents); g++; }
    else ck(1, "no activity row for amounts whose sum does not fit");
    BRTransactionFree(big);

    printf("[B] five outputs of $1.00\n");
    BRTransaction *small = receiveTx(&w, 5, 100);
    expect(eventFor(small, &w), DD_EVENT_RECEIVED, 500, 0, "an ordinary receive is received");
    BRTransactionFree(small);

    printf(g ? "\n%d FAIL\n" : "\nPASS: dd_event_overflow_kat\n", g);
    return g ? 1 : 0;
}
