// Host KAT: the coin reservation and the spend gates, moved from the JNI bridge into the core (BRCoinReservation.{h,c},
// BRSpendGate.h), give the bridge's answers.
//
// Every scenario is run twice from a cleared table: once on the bridge's files as they ship (bridge/coin_reservation.c,
// bridge/spend_guard.h, bridge/signed_spend_gate.h, compiled here, not copied) and once on the core's. Each records
// every answer it gets as a string of 0/1. A scenario passes only when the bridge's string, the core's string and the
// scenario's expected string (written out below, not derived from either side) are all the same.
//
// Carried case for case:
//   coin_reservation_kat   cr_A..cr_F
//   spend_guard_kat        sg_A..sg_C
//   recovery_spend_gate_kat rg_A..rg_E (rg_B on a wallet built as spend_guard_kat builds it: a registered send spends
//                          the coin)
// Added: the TTL boundary, renewal and re-claim after expiry; a backward step of now (held claims keep their expiry; a
// renewal expires TTL after the stepped now) and a NaN now (the table is dropped); the capacity at 512/513, with
// expired entries and with an outpoint listed twice; double release, release of an unknown owner, release of one owner
// only; NULL and empty input lists; clear; the gate's second spent-set check (a send registering between the first
// check and the claim, through a recorder on the claim), including a re-check that fails on a renewal, which releases
// the txid's earlier claim too; and two concurrency scenarios: two threads claiming one coin, round after round
// (exactly one must win each round), and four threads claiming and releasing overlapping coins (no coin may be held by
// two at once).
//
// Comparison arms (run.sh): each -D reaches the core's side only and must fail exactly its scenarios.
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "coin_reservation.h"     // the bridge's
#include "BRCoinReservation.h"    // the core's

// The bridge's constants are the core's.
_Static_assert(COIN_RESERVATION_MAX == BR_COIN_RESERVATION_MAX, "the table bound differs from the bridge's");
_Static_assert(COIN_RESERVATION_TTL_S == BR_COIN_RESERVATION_TTL_S, "the TTL differs from the bridge's");

// ---- the two sides --------------------------------------------------------------------------------------------------
int kat_bridge_guard(BRWallet *w, const BRTransaction *tx);
int kat_core_guard(BRWallet *w, const BRTransaction *tx);
int kat_bridge_gate(BRWallet *w, BRTransaction *tx, double now);
int kat_core_gate(BRWallet *w, BRTransaction *tx, double now);

typedef struct {
    const char *name;
    int (*claim)(UInt256, const BRTxInput *, size_t, double);
    void (*release)(UInt256);
    void (*clear)(void);
    int (*guard)(BRWallet *, const BRTransaction *);
    int (*gate)(BRWallet *, BRTransaction *, double);
} Impl;

static const Impl kBridge = { "bridge", coin_reservation_claim, coin_reservation_release, coin_reservation_clear,
                              kat_bridge_guard, kat_bridge_gate };
static const Impl kCore   = { "core", BRCoinReservationClaim, BRCoinReservationRelease, BRCoinReservationClear,
                              kat_core_guard, kat_core_gate };

// ---- the recorder on the gates' claim (run.sh renames the claim in the gates' unit to these) -----------------------
static void (*g_afterClaim)(void *) = NULL;   // fired once, after the real claim, then cleared
static void *g_afterClaimArg = NULL;
static int g_claimCalls = 0;

static void fireAfterClaim(void)
{
    void (*f)(void *) = g_afterClaim;
    g_afterClaim = NULL;
    if (f) f(g_afterClaimArg);
}
int kat_bridge_claim(UInt256 o, const BRTxInput *in, size_t n, double now)
{ g_claimCalls++; int r = coin_reservation_claim(o, in, n, now); fireAfterClaim(); return r; }
int kat_core_claim(UInt256 o, const BRTxInput *in, size_t n, double now)
{ g_claimCalls++; int r = BRCoinReservationClaim(o, in, n, now); fireAfterClaim(); return r; }

// ---- fixtures -------------------------------------------------------------------------------------------------------
static UInt256 h(uint8_t b) { UInt256 x = UINT256_ZERO; x.u8[0] = b; x.u8[31] = 0x5a; return x; }
static BRTxInput in(uint8_t b, uint32_t n) { BRTxInput i; memset(&i, 0, sizeof(i)); i.txHash = h(b); i.index = n; return i; }
static BRTxInput nth(size_t i) { return in((uint8_t)(i & 0xff), (uint32_t)(i >> 8) + 7); }   // coin_reservation_kat's

static const double T = 1000.0;
static const UInt256 *own(uint8_t b) { static UInt256 o[256]; o[b] = h(b); o[b].u8[1] = 0xee; return &o[b]; }
#define A (*own(0xa1))
#define B (*own(0xb2))
#define C (*own(0xc3))
#define D (*own(0xd4))

static BRTxInput XY[2], YZ[2], X1[1], Y1[1], Z1[1], XX[2], XZ[2], XYZ[3];
static BRTxInput MANY[BR_COIN_RESERVATION_MAX + 1];
static void initInputs(void)
{
    BRTxInput x = in(1, 0), y = in(2, 1), z = in(3, 0);
    XY[0] = x; XY[1] = y; YZ[0] = y; YZ[1] = z; X1[0] = x; Y1[0] = y; Z1[0] = z;
    XX[0] = x; XX[1] = x; XZ[0] = x; XZ[1] = z; XYZ[0] = x; XYZ[1] = y; XYZ[2] = z;
    for (size_t i = 0; i <= BR_COIN_RESERVATION_MAX; i++) MANY[i] = nth(i);
}

static const uint8_t kSig[2] = { 0x01, 0x51 };
static uint8_t g_spk[64];
static size_t g_spkLen;

static void fin(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *d = malloc(n);
    n = BRTransactionSerialize(tx, d, n);
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    else { printf("FAIL: setup: a fixture transaction does not parse\n"); exit(3); }
    free(d);
}

// A transaction spending (prev, n) to a script derived from tag, with a txid of its own.
static BRTransaction *spend(UInt256 prev, uint32_t n, uint8_t tag)
{
    BRTransaction *tx = BRTransactionNew();
    uint8_t dest[22] = { 0x00, 20 };
    memset(dest + 2, tag, 20);
    BRTransactionAddInput(tx, prev, n, 100000000ULL, g_spk, g_spkLen, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 99000000ULL, dest, sizeof(dest));
    fin(tx);
    return tx;
}

typedef struct { BRWallet *w; UInt256 r, r2; } Fixture;

// The wallet of spend_guard_kat: R:0 and R2:0 received, R:0 spent by a registered send S.
static Fixture fixture(void)
{
    Fixture f;
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    f.w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    BRAddress a = BRWalletReceiveAddress(f.w, 1);
    g_spkLen = BRAddressScriptPubKey(g_spk, sizeof(g_spk), a.s);
    BRTransaction *r[2];
    for (int k = 0; k < 2; k++) {
        UInt256 foreign; memset(foreign.u8, 0x5a + k, 32);
        r[k] = BRTransactionNew();
        BRTransactionAddInput(r[k], foreign, 0, 0, g_spk, g_spkLen, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
        BRTransactionAddOutput(r[k], 100000000ULL, g_spk, g_spkLen);
        r[k]->blockHeight = 100; r[k]->timestamp = 1700000000;
        fin(r[k]);
        BRWalletRegisterTransactionTrusted(f.w, r[k]);
    }
    f.r = r[0]->txHash; f.r2 = r[1]->txHash;
    BRTransaction *s = spend(f.r, 0, 0x11);
    s->blockHeight = TX_UNCONFIRMED; s->timestamp = 1700000100;
    BRWalletRegisterTransactionTrusted(f.w, s);
    if (BRWalletOutpointSpent(f.w, f.r, 0) != 1 || BRWalletOutpointSpent(f.w, f.r2, 0) != 0) {
        printf("FAIL: setup: the fixture wallet does not hold R:0 spent and R2:0 unspent\n"); exit(3);
    }
    return f;
}

// ---- the trace ------------------------------------------------------------------------------------------------------
typedef struct { char s[64]; size_t n; } Trace;
static void put(Trace *t, int v) { if (t->n + 1 < sizeof(t->s)) { t->s[t->n++] = v ? '1' : '0'; t->s[t->n] = 0; } }

// ---- reservation scenarios ------------------------------------------------------------------------------------------
static void cr_A(const Impl *I, Trace *t)   // a second send on a held coin
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(B, YZ, 2, T)); }
static void cr_B(const Impl *I, Trace *t)   // a refused claim takes nothing
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(B, YZ, 2, T)); put(t, I->claim(C, Z1, 1, T)); }
static void cr_C(const Impl *I, Trace *t)   // the same send signed again renews its own claim
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(A, XY, 2, T + 5)); }
static void cr_D(const Impl *I, Trace *t)   // once A is published (released), B is granted
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(B, YZ, 2, T)); I->release(A); put(t, I->claim(B, YZ, 2, T + 6)); }
static void cr_E(const Impl *I, Trace *t)   // never published: lapses after the TTL, and not before
{
    put(t, I->claim(A, XY, 2, T));
    put(t, I->claim(B, YZ, 2, T + BR_COIN_RESERVATION_TTL_S - 1));
    put(t, I->claim(B, YZ, 2, T + BR_COIN_RESERVATION_TTL_S + 1));
}
static void cr_F(const Impl *I, Trace *t)   // bounded: over the bound refused whole; at the bound granted
{ put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX + 1, T)); put(t, I->claim(B, MANY, BR_COIN_RESERVATION_MAX, T)); }

static void ttl_boundary(const Impl *I, Trace *t)   // held just before now == expires; lapsed at it
{
    put(t, I->claim(A, XY, 2, T));
    put(t, I->claim(B, YZ, 2, nextafter(T + 60.0, 0.0)));
    put(t, I->claim(B, YZ, 2, T + 60.0));
}
static void ttl_renew_extends(const Impl *I, Trace *t)   // a renewal moves the expiry to its own now + TTL
{
    put(t, I->claim(A, XY, 2, T)); put(t, I->claim(A, XY, 2, T + 30));
    put(t, I->claim(B, YZ, 2, T + 60)); put(t, I->claim(B, YZ, 2, T + 90));
}
static void reclaim_after_expiry(const Impl *I, Trace *t)   // the owner re-claims at its expiry: a fresh 60 s
{
    put(t, I->claim(A, XY, 2, T)); put(t, I->claim(A, XY, 2, T + 60));
    put(t, I->claim(B, YZ, 2, nextafter(T + 120.0, 0.0))); put(t, I->claim(B, YZ, 2, T + 120));
}
static void reclaim_by_other_after_expiry(const Impl *I, Trace *t)   // another owner takes it; the first is refused
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(B, YZ, 2, T + 60)); put(t, I->claim(A, XY, 2, T + 60)); }

static void backwards_now(const Impl *I, Trace *t)   // a backward step keeps a held claim to its own expiry
{
    put(t, I->claim(A, XY, 2, T + 100));
    put(t, I->claim(B, YZ, 2, T));                                  // stepped back: A still holds Y
    put(t, I->claim(B, YZ, 2, nextafter(T + 160.0, 0.0)));          // A's expiry is where it was
    put(t, I->claim(B, YZ, 2, T + 160));
}
static void backwards_renew(const Impl *I, Trace *t)   // a renewal after the step expires TTL after the stepped now
{
    put(t, I->claim(A, XY, 2, T + 100)); put(t, I->claim(A, XY, 2, T));
    put(t, I->claim(B, YZ, 2, nextafter(T + 60.0, 0.0))); put(t, I->claim(B, YZ, 2, T + 60));
}
static void nan_now(const Impl *I, Trace *t)   // a NaN now drops the table; its own entries lapse at the next claim
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(B, YZ, 2, (double)NAN)); put(t, I->claim(C, YZ, 2, T)); put(t, I->claim(D, X1, 1, T)); }

static void cap_fill(const Impl *I, Trace *t)   // full: a fresh coin refused; the owner's renewal granted
{
    put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX, T));
    put(t, I->claim(B, &MANY[BR_COIN_RESERVATION_MAX], 1, T));
    put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX, T + 1));
    I->release(A);
    put(t, I->claim(B, &MANY[BR_COIN_RESERVATION_MAX], 1, T + 1));
}
static void cap_511_plus_1(const Impl *I, Trace *t)   // 511 + 1 = 512 fits; one more does not
{
    put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX - 1, T));
    put(t, I->claim(B, &MANY[BR_COIN_RESERVATION_MAX - 1], 1, T));
    put(t, I->claim(C, &MANY[BR_COIN_RESERVATION_MAX], 1, T));
}
static void cap_expiry_frees(const Impl *I, Trace *t)   // expired entries are dropped before the bound is checked
{ put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX, T)); put(t, I->claim(B, &MANY[BR_COIN_RESERVATION_MAX], 1, T + 60)); }
static void cap_dup_inputs(const Impl *I, Trace *t)   // an outpoint listed twice and not yet held counts twice
{
    put(t, I->claim(A, MANY, BR_COIN_RESERVATION_MAX - 1, T));
    put(t, I->claim(B, XX, 2, T));
    put(t, I->claim(B, X1, 1, T));
}
static void dup_inputs_one_entry(const Impl *I, Trace *t)   // taken once: the second finds its own claim
{ put(t, I->claim(B, XX, 2, T)); put(t, I->claim(C, X1, 1, T)); I->release(B); put(t, I->claim(C, X1, 1, T)); }

static void double_release(const Impl *I, Trace *t)
{
    put(t, I->claim(A, XY, 2, T)); I->release(A); I->release(A);
    put(t, I->claim(B, YZ, 2, T)); put(t, I->claim(C, XY, 2, T));
}
static void release_unknown(const Impl *I, Trace *t)
{
    I->release(D);   // on an empty table
    put(t, I->claim(A, XY, 2, T)); I->release(D);
    put(t, I->claim(B, YZ, 2, T)); put(t, I->claim(A, XY, 2, T));
}
static void release_one_owner(const Impl *I, Trace *t)
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(C, Z1, 1, T)); I->release(A); put(t, I->claim(B, YZ, 2, T)); put(t, I->claim(B, Y1, 1, T)); }
static void partial_refusal(const Impl *I, Trace *t)   // B wants X, Y, Z; A holds Y: X and Z stay free
{ put(t, I->claim(A, Y1, 1, T)); put(t, I->claim(B, XYZ, 3, T)); put(t, I->claim(C, X1, 1, T)); put(t, I->claim(D, Z1, 1, T)); }
static void own_plus_fresh(const Impl *I, Trace *t)
{ put(t, I->claim(A, X1, 1, T)); put(t, I->claim(A, XZ, 2, T)); put(t, I->claim(B, Z1, 1, T)); }
static void null_and_empty(const Impl *I, Trace *t)
{
    put(t, I->claim(A, NULL, 1, T)); put(t, I->claim(A, NULL, 0, T)); put(t, I->claim(A, XY, 0, T));
    put(t, I->claim(B, XY, 2, T));
}
static void clear_drops_all(const Impl *I, Trace *t)
{ put(t, I->claim(A, XY, 2, T)); put(t, I->claim(C, Z1, 1, T)); I->clear(); put(t, I->claim(B, YZ, 2, T)); }

// ---- concurrency ----------------------------------------------------------------------------------------------------
typedef struct { const Impl *I; UInt256 owner; atomic_int *go; int result; } RaceArg;
static void *raceClaim(void *p)
{
    RaceArg *a = p;
    while (! atomic_load(a->go)) ;
    a->result = a->I->claim(a->owner, X1, 1, T);
    return NULL;
}
static void conc_two_threads(const Impl *I, Trace *t)   // two signers claim one coin: exactly one wins, every round
{
    int ok = 1, wins[2] = { 0, 0 };
    for (int round = 0; round < 400; round++) {
        I->clear();
        atomic_int go = 0;
        RaceArg a[2] = { { I, A, &go, -1 }, { I, B, &go, -1 } };
        pthread_t th[2];
        for (int k = 0; k < 2; k++) pthread_create(&th[k], NULL, raceClaim, &a[k]);
        atomic_store(&go, 1);
        for (int k = 0; k < 2; k++) pthread_join(th[k], NULL);
        if (a[0].result + a[1].result != 1) ok = 0;
        for (int k = 0; k < 2; k++) wins[k] += a[k].result == 1;
        // the loser is refused afterwards too; the winner's renewal is granted
        int w = a[0].result == 1 ? 0 : 1;
        if (I->claim(a[1 - w].owner, X1, 1, T) != 0 || I->claim(a[w].owner, X1, 1, T) != 1) ok = 0;
    }
    put(t, ok);
    put(t, wins[0] + wins[1] == 400);
}

#define STRESS_THREADS 4
#define STRESS_ITERS 3000
#define STRESS_COINS 6
static atomic_int g_holders[STRESS_COINS];
static atomic_int g_violations;
typedef struct { const Impl *I; int k; int granted; } StressArg;
static void *stress(void *p)
{
    StressArg *a = p;
    UInt256 owner = h((uint8_t)(0x40 + a->k));
    owner.u8[2] = 0x77;
    for (int i = 0; i < STRESS_ITERS; i++) {
        int c0 = (i + a->k) % STRESS_COINS, c1 = (i + a->k + 1) % STRESS_COINS;
        BRTxInput two[2] = { in((uint8_t)(0x90 + c0), 0), in((uint8_t)(0x90 + c1), 0) };
        if (! a->I->claim(owner, two, 2, T)) continue;
        a->granted++;
        if (atomic_fetch_add(&g_holders[c0], 1) != 0) atomic_fetch_add(&g_violations, 1);
        if (atomic_fetch_add(&g_holders[c1], 1) != 0) atomic_fetch_add(&g_violations, 1);
        atomic_fetch_sub(&g_holders[c0], 1);
        atomic_fetch_sub(&g_holders[c1], 1);
        a->I->release(owner);
    }
    return NULL;
}
static void conc_stress(const Impl *I, Trace *t)   // overlapping claims and releases: no coin held twice
{
    atomic_store(&g_violations, 0);
    for (int c = 0; c < STRESS_COINS; c++) atomic_store(&g_holders[c], 0);
    pthread_t th[STRESS_THREADS];
    StressArg a[STRESS_THREADS];
    for (int k = 0; k < STRESS_THREADS; k++) { a[k] = (StressArg){ I, k, 0 }; pthread_create(&th[k], NULL, stress, &a[k]); }
    int granted = 0;
    for (int k = 0; k < STRESS_THREADS; k++) { pthread_join(th[k], NULL); granted += a[k].granted; }
    put(t, atomic_load(&g_violations) == 0);
    put(t, granted > 0);
    // everything was released: the table is empty, so every coin is free
    BRTxInput all[STRESS_COINS];
    for (int c = 0; c < STRESS_COINS; c++) all[c] = in((uint8_t)(0x90 + c), 0);
    put(t, I->claim(D, all, STRESS_COINS, T));
}

// ---- gate scenarios -------------------------------------------------------------------------------------------------
static void sg_A(const Impl *I, Trace *t)   // a coin a registered send already spends
{ Fixture f = fixture(); BRTransaction *x = spend(f.r, 0, 0x22); put(t, I->guard(f.w, x)); BRTransactionFree(x); BRWalletFree(f.w); }
static void sg_B(const Impl *I, Trace *t)   // a coin nothing spends
{ Fixture f = fixture(); BRTransaction *x = spend(f.r2, 0, 0x33); put(t, I->guard(f.w, x)); BRTransactionFree(x); BRWalletFree(f.w); }
static void sg_C(const Impl *I, Trace *t)   // nothing to ask
{
    Fixture f = fixture(); BRTransaction *x = spend(f.r2, 0, 0x33);
    put(t, I->guard(NULL, x)); put(t, I->guard(f.w, NULL));
    BRTransactionFree(x); BRWalletFree(f.w);
}

static BRTransaction *g_s1, *g_s2;
static void sweeps(void) { UInt256 foreign; memset(foreign.u8, 0x61, 32); g_s1 = spend(foreign, 0, 0x11); g_s2 = spend(foreign, 0, 0x22); }
static void unsweeps(void) { BRTransactionFree(g_s1); BRTransactionFree(g_s2); }
static void rg_A(const Impl *I, Trace *t)   // two sweeps of one foreign coin, no wallet
{ sweeps(); put(t, I->gate(NULL, g_s1, T)); put(t, I->gate(NULL, g_s2, T)); unsweeps(); }
static void rg_D(const Impl *I, Trace *t)   // the first sweep asked again is granted (its own claim)
{ sweeps(); put(t, I->gate(NULL, g_s1, T)); put(t, I->gate(NULL, g_s2, T)); put(t, I->gate(NULL, g_s1, T + 1)); unsweeps(); }
static void rg_C(const Impl *I, Trace *t)   // once the first claim is released the second may leave
{
    sweeps();
    put(t, I->gate(NULL, g_s1, T)); put(t, I->gate(NULL, g_s2, T)); put(t, I->gate(NULL, g_s1, T + 1));
    I->release(g_s1->txHash); put(t, I->gate(NULL, g_s2, T + 2));
    unsweeps();
}
static void rg_B(const Impl *I, Trace *t)   // a sweep of a coin the wallet holds as spent; it takes no claim
{
    Fixture f = fixture();
    BRTransaction *x = spend(f.r, 0, 0x44), *y = spend(f.r, 0, 0x45);
    put(t, I->gate(f.w, x, T)); put(t, I->gate(NULL, y, T));
    BRTransactionFree(x); BRTransactionFree(y); BRWalletFree(f.w);
}
static void rg_E(const Impl *I, Trace *t)   // no inputs; no transaction
{ BRTransaction *e = BRTransactionNew(); put(t, I->gate(NULL, e, T)); put(t, I->gate(NULL, NULL, T)); BRTransactionFree(e); }

static void gate_wallet_ok(const Impl *I, Trace *t)   // an unspent coin passes and is held
{
    Fixture f = fixture();
    BRTransaction *x = spend(f.r2, 0, 0x50), *y = spend(f.r2, 0, 0x51);
    put(t, I->gate(f.w, x, T)); put(t, I->gate(f.w, y, T));
    I->release(x->txHash); put(t, I->gate(f.w, y, T));
    BRTransactionFree(x); BRTransactionFree(y); BRWalletFree(f.w);
}

typedef struct { BRWallet *w; UInt256 coin; } Registrar;
static void registerConflict(void *p)   // a send of the same coin registers while the gate holds its claim
{
    Registrar *r = p;
    BRTransaction *s = spend(r->coin, 0, 0x66);
    s->blockHeight = TX_UNCONFIRMED; s->timestamp = 1700000200;
    BRWalletRegisterTransactionTrusted(r->w, s);
}
static void gate_recheck(const Impl *I, Trace *t)   // spent between the first check and the claim: refused, released
{
    Fixture f = fixture();
    Registrar reg = { f.w, f.r2 };
    BRTransaction *x = spend(f.r2, 0, 0x60), *y = spend(f.r2, 0, 0x61);
    g_claimCalls = 0; g_afterClaim = registerConflict; g_afterClaimArg = &reg;
    put(t, I->gate(f.w, x, T));
    put(t, g_claimCalls == 1 && BRWalletOutpointSpent(f.w, f.r2, 0) == 1);   // setup check: the conflict did register
    g_afterClaim = NULL;
    put(t, I->claim(y->txHash, y->inputs, y->inCount, T));                    // x's claim was released
    BRTransactionFree(x); BRTransactionFree(y); BRWalletFree(f.w);
}

static int g_registerOnCall = 0;   // registerConflict fires on this claim call (1-based), counted by g_claimCalls
static void registerOnNthClaim(void *p) { if (g_claimCalls == g_registerOnCall) registerConflict(p); else g_afterClaim = registerOnNthClaim; }
static void gate_recheck_drops_earlier(const Impl *I, Trace *t)   // a failed re-check releases the txid's earlier claim too
{
    Fixture f = fixture();
    Registrar reg = { f.w, f.r2 };
    BRTransaction *x = spend(f.r2, 0, 0x70), *y = spend(f.r2, 0, 0x71);
    g_claimCalls = 0; g_registerOnCall = 2; g_afterClaim = registerOnNthClaim; g_afterClaimArg = &reg;
    put(t, I->gate(f.w, x, T));                                               // passes; x holds R2:0
    put(t, I->claim(y->txHash, y->inputs, y->inCount, T));                    // y refused: x holds it
    put(t, I->gate(f.w, x, T + 1));                                           // x again: renewed, then spent meanwhile
    put(t, g_claimCalls == 2 && BRWalletOutpointSpent(f.w, f.r2, 0) == 1);   // setup check: the conflict did register
    g_afterClaim = NULL;
    put(t, I->claim(y->txHash, y->inputs, y->inCount, T + 1));                // x's claim, earlier one included, is gone
    BRTransactionFree(x); BRTransactionFree(y); BRWalletFree(f.w);
}

// ---- runner ---------------------------------------------------------------------------------------------------------
typedef struct { const char *name; void (*run)(const Impl *, Trace *); const char *want; } Scenario;
#define S(n, w) { #n, n, w }
static const Scenario kScenarios[] = {
    S(cr_A, "10"), S(cr_B, "101"), S(cr_C, "11"), S(cr_D, "101"), S(cr_E, "101"), S(cr_F, "01"),
    S(ttl_boundary, "101"), S(ttl_renew_extends, "1101"), S(reclaim_after_expiry, "1101"),
    S(reclaim_by_other_after_expiry, "110"), S(backwards_now, "1001"), S(backwards_renew, "1101"), S(nan_now, "1111"),
    S(cap_fill, "1011"), S(cap_511_plus_1, "110"), S(cap_expiry_frees, "11"), S(cap_dup_inputs, "101"),
    S(dup_inputs_one_entry, "101"),
    S(double_release, "110"), S(release_unknown, "101"), S(release_one_owner, "1101"), S(partial_refusal, "1011"),
    S(own_plus_fresh, "110"), S(null_and_empty, "0111"), S(clear_drops_all, "111"),
    S(conc_two_threads, "11"), S(conc_stress, "111"),
    S(sg_A, "0"), S(sg_B, "1"), S(sg_C, "00"),
    S(rg_A, "10"), S(rg_D, "101"), S(rg_C, "1011"), S(rg_B, "01"), S(rg_E, "00"),
    S(gate_wallet_ok, "101"), S(gate_recheck, "011"), S(gate_recheck_drops_earlier, "10011"),
};

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    initInputs();
    int fails = 0;
    size_t n = sizeof(kScenarios) / sizeof(kScenarios[0]);
    for (size_t i = 0; i < n; i++) {
        const Scenario *s = &kScenarios[i];
        Trace tb = { "", 0 }, tc = { "", 0 };
        kBridge.clear(); kCore.clear();
        s->run(&kBridge, &tb);
        kBridge.clear(); kCore.clear();
        s->run(&kCore, &tc);
        int ok = strcmp(tb.s, tc.s) == 0 && strcmp(tb.s, s->want) == 0;
        if (ok) printf("SCENARIO-PASS: %s\n", s->name);
        else {
            printf("SCENARIO-FAIL: %s\n   FAIL: %s: bridge %s, core %s, expected %s\n", s->name, s->name, tb.s, tc.s, s->want);
            fails++;
        }
    }
    kBridge.clear(); kCore.clear();
    if (fails == 0) printf("\nPASS: coin_reservation_core_kat (%zu scenarios)\n", n);
    else printf("\n%d FAIL\n", fails);
    return fails ? 1 : 0;
}
