/* peer_penalty_shotgun_kat -- PEER-PENALTY-SHOTGUN: the fallback dial honours the peer penalty set.
 *
 * INVARIANT. No pass of BRPeerManagerConnect dials a peer inside its penalty window. The shotgun
 * fallback (which runs in COMPACT_FILTERS_ONLY only once the known filter set is exhausted) keeps
 * the same rule as the filter-first pass. Two peers are exempt so the wallet is never starved of
 * them: the pinned own-node (the reserved slot) and the fixed peer (BRPeerManagerSetFixedPeer,
 * the rescan's pinned priority peer).
 *
 * HOW. The KAT #include-s BRPeerManager.c and links with --wrap=pthread_create. Every peer-thread
 * create made on the main thread is a dial: the wrapper records the dialed port and refuses
 * (EAGAIN), so no socket is ever opened and the manager releases the dial itself (the "nothread"
 * path peer_dial_disconnect_once_kat proves clean). DNS discovery threads are refused too. One
 * BRPeerManagerConnect call per case; the reconnects a failed dial makes (until MAX_CONNECT_FAILURES)
 * run as shipped and are counted with it.
 *
 * CASES (each port is unique, so a dial names its peer):
 *   penalized  40 filter-capable candidates, every one inside a 10-minute penalty window (half)
 *              or a 30-second redial cooldown (half). Shipped: nothing is dialed.
 *   crowd      120 penalized filter-capable candidates ahead of 4 un-penalized plain ones. Shipped:
 *              no penalized peer is dialed and an un-penalized one is (penalized peers neither get
 *              redialed nor crowd dialable ones out of the 100-entry list).
 *   pinned     the pinned own-node (under a redial cooldown) beside 20 penalized filter peers.
 *              Shipped: the own-node is dialed, no penalized peer is.
 *   fixed      a fixed peer under a redial cooldown, as after a rescan peer's disconnect. Shipped:
 *              the fixed peer is dialed (a rescan is never left with nothing to dial).
 * Comparison arms: -DPEER_PENALTY_SHOTGUN_UNFIXED (the shotgun never reads the penalty set) fails
 * penalized, crowd and pinned; -DPEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT (no fixed-peer exemption)
 * fails fixed. Exit 0 = the case held.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>
#include <errno.h>

#include "BRPeerManager.c"

#define MAX_DIALS 1024
static pthread_t g_main;
static uint16_t g_dialed[MAX_DIALS];
static size_t g_dialCount;
static size_t g_dnsRefused;

int __real_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg);

int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg)
{
    if (! pthread_equal(pthread_self(), g_main)) return __real_pthread_create(t, a, fn, arg);
    if (fn == _findPeersThreadRoutine) { g_dnsRefused++; return EAGAIN; }
    if (g_dialCount < MAX_DIALS) g_dialed[g_dialCount++] = ((BRPeer *)arg)->port;
    return EAGAIN;
}

static int dialed(uint16_t port)
{
    for (size_t i = 0; i < g_dialCount; i++) if (g_dialed[i] == port) return 1;
    return 0;
}

static UInt128 addr4(uint8_t a, uint8_t b, uint8_t c, uint8_t d)
{
    return (UInt128) { .u8 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, a, b, c, d } };
}

static BRPeer peerAt(uint16_t i, uint64_t services, time_t now)
{
    BRPeer p = BR_PEER_NONE;
    p.address = addr4(10, 7, (uint8_t)(i >> 8), (uint8_t)(i & 0xff));
    p.port = (uint16_t)(20000 + i);
    p.services = services;
    p.timestamp = (uint64_t)now;
    return p;
}

#define CF (SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS)

static int g_ok = 1;
static void check(int cond, const char *what)
{
    printf("  %s %s\n", cond ? "ok  " : "FAIL", what);
    if (! cond) g_ok = 0;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: %s penalized|crowd|pinned|fixed\n", argv[0]); return 2; }
    const char *kase = argv[1];
    g_main = pthread_self();

    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    time_t now = time(NULL);

    if (strcmp(kase, "fixed") == 0) {
        UInt128 fa = addr4(10, 9, 0, 1);
        uint16_t fp = 31001;
        BRPeerManagerSetFixedPeer(m, fa, fp);
        MGR_LOCK(m);
        m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
        _penalizeFor(m, fa, fp, now, PEER_REDIAL_COOLDOWN_SECONDS);
        MGR_UNLOCK(m);
        printf("[fixed] fixed peer :%u under a %ds redial cooldown\n", fp, PEER_REDIAL_COOLDOWN_SECONDS);
        BRPeerManagerConnect(m);
        printf("  dials %zu, DNS threads refused %zu\n", g_dialCount, g_dnsRefused);
        check(dialed(fp), "the fixed peer is dialed (a rescan is never left with nothing to dial)");
    }
    else {
        size_t nPen = 0, nFree = 0;
        int pinned = 0;
        if (strcmp(kase, "penalized") == 0) nPen = 40;
        else if (strcmp(kase, "crowd") == 0) { nPen = 120; nFree = 4; }
        else if (strcmp(kase, "pinned") == 0) { nPen = 20; pinned = 1; }
        else { printf("unknown case\n"); return 2; }

        MGR_LOCK(m);
        m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
        m->maxConnectCount = 2;                              /* candidate pool >= 4x: no DNS discovery */
        for (size_t i = 0; i < nPen; i++) {
            BRPeer p = peerAt((uint16_t)i, CF, now);
            array_add(m->peers, p);
            _penalizeFor(m, p.address, p.port, now, (i % 2) ? PEER_REDIAL_COOLDOWN_SECONDS : PEER_PENALTY_SECONDS);
        }
        for (size_t i = 0; i < nFree; i++) array_add(m->peers, peerAt((uint16_t)(500 + i), SERVICES_NODE_NETWORK, now));
        BRPeer own = peerAt(900, CF, now);
        if (pinned) {
            array_add(m->peers, own);
            m->pinnedAddr = own.address;
            m->pinnedPort = own.port;
            _penalizeFor(m, own.address, own.port, now, PEER_REDIAL_COOLDOWN_SECONDS);
        }
        MGR_UNLOCK(m);
        printf("[%s] %zu penalized filter peers, %zu un-penalized plain peers%s\n", kase, nPen, nFree,
               pinned ? ", pinned own-node under a redial cooldown" : "");

        BRPeerManagerConnect(m);
        printf("  dials %zu, DNS threads refused %zu\n", g_dialCount, g_dnsRefused);

        size_t penDialed = 0;
        for (size_t i = 0; i < nPen; i++) penDialed += dialed((uint16_t)(20000 + i));
        printf("  penalized peers dialed: %zu\n", penDialed);
        check(penDialed == 0, "no peer inside its penalty window is dialed");
        if (nFree) {
            size_t freeDialed = 0;
            for (size_t i = 0; i < nFree; i++) freeDialed += dialed((uint16_t)(20500 + i));
            check(freeDialed > 0, "an un-penalized candidate is dialed (penalized peers do not crowd it out)");
        }
        if (pinned) check(dialed(own.port), "the pinned own-node is dialed");
        if (! nFree && ! pinned) check(g_dialCount == 0, "nothing at all is dialed");
    }

    int freed = BRPeerManagerFree(m);
    BRWalletFree(w);
    if (freed != 1) { printf("  FAIL the manager was not freed at once (%d)\n", freed); g_ok = 0; }
    printf(g_ok ? "CASE PASS %s\n" : "CASE FAIL %s\n", kase);
    return g_ok ? 0 : 1;
}
