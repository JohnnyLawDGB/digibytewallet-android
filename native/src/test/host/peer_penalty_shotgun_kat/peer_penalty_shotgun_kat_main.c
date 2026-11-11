/* peer_penalty_shotgun_kat -- PEER-PENALTY-SHOTGUN: the fallback dial honours the peer penalty set.
 *
 * INVARIANT. No pass of BRPeerManagerConnect dials a peer inside a penalty window that pass must
 * honour, and no penalty excludes a peer once its window has lapsed. The shotgun fallback (which
 * runs in COMPACT_FILTERS_ONLY only once the known filter set is exhausted) honours every window
 * for non-filter peers, and for filter peers every ban that outlasts the 30-second redial
 * cooldown, so filter peers that merely dropped (the canon/priority set after a mass disconnect)
 * are redialed ahead of the non-filter pool and the canon is never starved. Two peers are exempt
 * from every penalty: the pinned own-node (the reserved slot) and the fixed peer
 * (BRPeerManagerSetFixedPeer, the rescan's pinned priority peer).
 *
 * HOW. The KAT #include-s BRPeerManager.c and links with --wrap=pthread_create. Every peer-thread
 * create made on the main thread is a dial: the wrapper records the dialed port and refuses
 * (EAGAIN), so no socket is ever opened and the manager releases the dial itself (the "nothread"
 * path peer_dial_disconnect_once_kat proves clean). DNS discovery threads are refused too. One
 * BRPeerManagerConnect call per case; the reconnects a failed dial makes (until MAX_CONNECT_FAILURES)
 * run as shipped and are counted with it. syncStopped is recorded (count and last error).
 *
 * CASES (each port is unique, so a dial names its peer; "banned" = a 10-minute penalty):
 *   penalized  40 banned filter peers and nothing else. Shipped: nothing is dialed, and the pass
 *              reports syncStopped(ENETUNREACH) once (the retry signal the Kotlin poll loop reads).
 *   crowd      120 banned filter peers ahead of 4 un-penalized plain ones. Shipped: no banned peer
 *              is dialed and a plain one is (banned peers do not crowd it out of the 100-entry list).
 *   canon      15 filter peers (the priority set's size) each under a 30-second redial cooldown, as
 *              after a mass disconnect, beside 8 un-penalized plain peers. Shipped: the FIRST dial is
 *              a canon peer (filter peers that merely dropped come back ahead of the non-filter pool).
 *   expired    20 banned filter peers and 2 plain peers whose bans lapsed 100 s ago. Shipped: a
 *              lapsed peer is dialed (a penalty is a window, never a permanent exclusion).
 *   pinned     the pinned own-node under a ban beside 20 banned filter peers. Shipped: the
 *              own-node is dialed, no banned peer is. (The own-node is never _penalize'd, but a ban
 *              it took before it was pinned can still be in the set; a redial cooldown alone would
 *              not test the exemption, since a filter peer's cooldown is not honoured anyway.)
 *   exclusive  pinnedExclusive with the own-node under a ban, beside banned filter peers
 *              and un-penalized plain peers. Shipped: the own-node is dialed, and nothing else is.
 *   The reserved slot dials the own-node before the shotgun runs, so pinned/exclusive are also run
 *   with -DPEER_PENALTY_SHOTGUN_NO_RESERVED_SLOT: the shotgun's own exemption must then dial it,
 *   and with -DPEER_PENALTY_SHOTGUN_NO_PINNED_EXEMPT added as well both cases must fail.
 *   fixed      a fixed peer under a ban (the fixed peer takes the manager's filter services, so a
 *              cooldown alone would not test its exemption). Shipped: the fixed peer is dialed (a
 *              rescan is never left with nothing to dial).
 * Comparison arms (see run.sh for which case each must fail): -DPEER_PENALTY_SHOTGUN_UNFIXED (the
 * shotgun never reads the penalty set), -DPEER_PENALTY_SHOTGUN_HONOUR_COOLDOWN (filter peers'
 * cooldowns honoured too, the shape that starved the canon), -DPEER_PENALTY_SHOTGUN_STALE_CLOCK
 * (windows read against time 0), -DPEER_PENALTY_SHOTGUN_NO_FIXED_EXEMPT, and the pinned pair above. Exit 0 = the case held.
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
static size_t g_stopCount;
static int g_stopLastError = -1;

static void onSyncStopped(void *info, int error) { (void)info; g_stopCount++; g_stopLastError = error; }

int __real_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg);

int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg)
{
    if (! pthread_equal(pthread_self(), g_main)) return __real_pthread_create(t, a, fn, arg);
    /* A refused discovery thread's BRFindPeersInfo is the thread's to free, so the harness frees
     * it here (the dial storm in the canon/expired cases empties the pool and starts discovery). */
    if (fn == _findPeersThreadRoutine) { g_dnsRefused++; free(arg); return EAGAIN; }
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

static size_t countDialed(uint16_t firstPort, size_t n)
{
    size_t c = 0;
    for (size_t i = 0; i < n; i++) c += dialed((uint16_t)(firstPort + i));
    return c;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: %s penalized|crowd|canon|expired|pinned|exclusive|fixed\n", argv[0]); return 2; }
    const char *kase = argv[1];
    g_main = pthread_self();

    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetCallbacks(m, NULL, NULL, onSyncStopped, NULL, NULL, NULL, NULL, NULL);
    time_t now = time(NULL);

    if (strcmp(kase, "fixed") == 0) {
        UInt128 fa = addr4(10, 9, 0, 1);
        uint16_t fp = 31001;
        BRPeerManagerSetFixedPeer(m, fa, fp);
        MGR_LOCK(m);
        m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
        _penalizeFor(m, fa, fp, now, PEER_PENALTY_SECONDS);
        MGR_UNLOCK(m);
        printf("[fixed] fixed peer :%u under a %ds ban\n", fp, PEER_PENALTY_SECONDS);
        BRPeerManagerConnect(m);
        printf("  dials %zu, DNS threads refused %zu\n", g_dialCount, g_dnsRefused);
        check(dialed(fp), "the fixed peer is dialed (a rescan is never left with nothing to dial)");
    }
    else {
        /* Ports: banned filter peers 20000+, canon (cooldown) filter peers 20300+, plain peers
         * 20500+, own-node 20900. */
        size_t nBan = 0, nCanon = 0, nFree = 0, nLapsed = 0;
        int pinned = 0, exclusive = 0;
        if (strcmp(kase, "penalized") == 0) nBan = 40;
        else if (strcmp(kase, "crowd") == 0) { nBan = 120; nFree = 4; }
        else if (strcmp(kase, "canon") == 0) { nCanon = 15; nFree = 8; }
        else if (strcmp(kase, "expired") == 0) { nBan = 20; nLapsed = 2; }
        else if (strcmp(kase, "pinned") == 0) { nBan = 20; pinned = 1; }
        else if (strcmp(kase, "exclusive") == 0) { nBan = 20; nFree = 4; pinned = 1; exclusive = 1; }
        else { printf("unknown case\n"); return 2; }

        MGR_LOCK(m);
        m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
        m->maxConnectCount = 2;                              /* candidate pool >= 4x: no DNS discovery */
        for (size_t i = 0; i < nBan; i++) {
            BRPeer p = peerAt((uint16_t)i, CF, now);
            array_add(m->peers, p);
            _penalizeFor(m, p.address, p.port, now, PEER_PENALTY_SECONDS);
        }
        for (size_t i = 0; i < nCanon; i++) {
            BRPeer p = peerAt((uint16_t)(300 + i), CF, now);
            array_add(m->peers, p);
            _penalizeFor(m, p.address, p.port, now, PEER_REDIAL_COOLDOWN_SECONDS);
        }
        for (size_t i = 0; i < nFree; i++) array_add(m->peers, peerAt((uint16_t)(500 + i), SERVICES_NODE_NETWORK, now));
        for (size_t i = 0; i < nLapsed; i++) {
            BRPeer p = peerAt((uint16_t)(500 + i), SERVICES_NODE_NETWORK, now);
            array_add(m->peers, p);
            _penalizeFor(m, p.address, p.port, now - PEER_PENALTY_SECONDS - 100, PEER_PENALTY_SECONDS);
        }
        BRPeer own = peerAt(900, CF, now);
        if (pinned) {
            array_add(m->peers, own);
            m->pinnedAddr = own.address;
            m->pinnedPort = own.port;
            m->pinnedExclusive = exclusive;
            _penalizeFor(m, own.address, own.port, now, PEER_PENALTY_SECONDS);
        }
        MGR_UNLOCK(m);
        printf("[%s] %zu banned filter peers, %zu canon filter peers under a redial cooldown, "
               "%zu un-penalized plain peers, %zu plain peers with lapsed bans%s\n", kase, nBan, nCanon,
               nFree, nLapsed, pinned ? (exclusive ? ", exclusive own-node under a ban"
                                                   : ", pinned own-node under a ban") : "");

        BRPeerManagerConnect(m);
        printf("  dials %zu, DNS threads refused %zu, syncStopped x%zu (last error %d)\n",
               g_dialCount, g_dnsRefused, g_stopCount, g_stopLastError);

        size_t banDialed = countDialed(20000, nBan);
        printf("  banned peers dialed: %zu\n", banDialed);
        check(banDialed == 0, "no peer inside its ban is dialed");

        if (strcmp(kase, "penalized") == 0) {
            check(g_dialCount == 0, "nothing at all is dialed");
            check(g_stopCount == 1 && g_stopLastError == ENETUNREACH,
                  "the pass reports syncStopped(ENETUNREACH) exactly once");
        }
        if (strcmp(kase, "crowd") == 0)
            check(countDialed(20500, nFree) > 0, "an un-penalized plain peer is dialed (banned peers do not crowd it out)");
        if (strcmp(kase, "canon") == 0) {
            size_t canonDialed = countDialed(20300, nCanon);
            printf("  canon peers dialed: %zu, first dial :%u\n", canonDialed, g_dialCount ? g_dialed[0] : 0);
            check(g_dialCount > 0 && g_dialed[0] >= 20300 && g_dialed[0] < 20300 + nCanon,
                  "the first dial is a canon filter peer, not a non-filter one");
            check(canonDialed > 0, "a canon peer under only a redial cooldown is dialed");
        }
        if (strcmp(kase, "expired") == 0)
            check(countDialed(20500, nLapsed) > 0, "a peer whose ban has lapsed is dialed again");
        if (pinned) {
            size_t ownDials = 0;
            for (size_t i = 0; i < g_dialCount; i++) ownDials += (g_dialed[i] == own.port);
            printf("  own-node dials: %zu\n", ownDials);
            check(ownDials > 0, "the pinned own-node is dialed");
            if (exclusive) check(ownDials == g_dialCount, "in exclusive mode only the own-node is dialed");
        }
    }

    int freed = BRPeerManagerFree(m);
    BRWalletFree(w);
    if (freed != 1) { printf("  FAIL the manager was not freed at once (%d)\n", freed); g_ok = 0; }
    printf(g_ok ? "CASE PASS %s\n" : "CASE FAIL %s\n", kase);
    return g_ok ? 0 : 1;
}
