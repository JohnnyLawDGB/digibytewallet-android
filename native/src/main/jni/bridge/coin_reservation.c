/* coin_reservation.c — see coin_reservation.h (4.1.2 row 3, N8). */
#include "coin_reservation.h"

#include <pthread.h>
#include <time.h>

#ifndef COIN_RESERVATION_UNFIXED
#define COIN_RESERVATION_UNFIXED 0
#endif

typedef struct {
    UInt256 owner;     /* the txid that holds it */
    UInt256 hash;      /* the outpoint */
    uint32_t n;
    double expires;
} CoinClaim;

static CoinClaim g_claims[COIN_RESERVATION_MAX];
static size_t g_claimCount = 0;
static pthread_mutex_t g_claimLock = PTHREAD_MUTEX_INITIALIZER;

/* Caller holds g_claimLock. */
static void _dropExpired(double now)
{
    size_t j = 0;
    for (size_t i = 0; i < g_claimCount; i++) {
        if (g_claims[i].expires > now) g_claims[j++] = g_claims[i];
    }
    g_claimCount = j;
}

/* Caller holds g_claimLock. The index of the claim on (hash, n), or -1. */
static long _find(UInt256 hash, uint32_t n)
{
    for (size_t i = 0; i < g_claimCount; i++) {
        if (g_claims[i].n == n && UInt256Eq(g_claims[i].hash, hash)) return (long)i;
    }
    return -1;
}

int coin_reservation_claim(UInt256 owner, const BRTxInput *inputs, size_t inCount, double now)
{
#if COIN_RESERVATION_UNFIXED
    (void)owner; (void)inputs; (void)inCount; (void)now;
    return 1;
#else
    int granted = 1;
    size_t fresh = 0;

    if (! inputs && inCount > 0) return 0;
    pthread_mutex_lock(&g_claimLock);
    _dropExpired(now);
    /* First pass: refuse before taking anything. */
    for (size_t i = 0; i < inCount && granted; i++) {
        long at = _find(inputs[i].txHash, inputs[i].index);
        if (at >= 0 && ! UInt256Eq(g_claims[at].owner, owner)) granted = 0;
        else if (at < 0) fresh++;
    }
    if (granted && g_claimCount + fresh > COIN_RESERVATION_MAX) granted = 0;
    /* Second pass: take or renew. Duplicate inputs in one transaction find their own claim. */
    for (size_t i = 0; i < inCount && granted; i++) {
        long at = _find(inputs[i].txHash, inputs[i].index);
        if (at < 0) {
            at = (long)g_claimCount++;
            g_claims[at].owner = owner;
            g_claims[at].hash = inputs[i].txHash;
            g_claims[at].n = inputs[i].index;
        }
        g_claims[at].expires = now + COIN_RESERVATION_TTL_S;
    }
    pthread_mutex_unlock(&g_claimLock);
    return granted;
#endif
}

void coin_reservation_release(UInt256 owner)
{
    size_t j = 0;
    pthread_mutex_lock(&g_claimLock);
    for (size_t i = 0; i < g_claimCount; i++) {
        if (! UInt256Eq(g_claims[i].owner, owner)) g_claims[j++] = g_claims[i];
    }
    g_claimCount = j;
    pthread_mutex_unlock(&g_claimLock);
}

void coin_reservation_clear(void)
{
    pthread_mutex_lock(&g_claimLock);
    g_claimCount = 0;
    pthread_mutex_unlock(&g_claimLock);
}

double coin_reservation_now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}
