/* peer_dial_disconnect_once_kat -- the peer manager runs a dialed peer's disconnect path exactly once.
 *
 * _BRPeerManagerBeginConnect dials a peer with BRPeerConnect, which starts the peer thread. When no
 * thread could be started, nothing else will run the peer's disconnect path or release what the dial
 * took (the thread count, the callback info), so the manager runs it itself. The question is how it
 * knows no thread started. The peer thread marks the peer disconnected as soon as its dial fails,
 * before it runs the disconnect path, so a peer whose dial fails fast can already read
 * "disconnected" when BRPeerConnect returns -- with its thread alive and about to run the same path.
 *
 * WHAT IT PROVES. The KAT links with --wrap=pthread_create; the wrapper acts only on the one
 * pthread_create the dial makes (main thread, armed by the KAT). The dialed peer is 127.0.0.1 on a
 * port nothing listens on, so its connect is refused at once.
 *   fast   the thread is started, and the wrapper returns only once the thread has marked the peer
 *          disconnected (the fast-failure order, made deterministic). Shipped: the disconnect path
 *          runs once (the thread's), the thread count returns to 0, LeakSanitizer clean.
 *          Comparison (-DPEER_DIAL_DISCONNECT_ONCE_UNFIXED): the manager reads the status, runs the
 *          path as well, and the second run touches a freed peer (an AddressSanitizer report).
 *   nothread  the wrapper refuses (EAGAIN), as pthread_create can. Shipped: the manager runs the
 *          path once, the thread count returns to 0, and the callback info is released
 *          (LeakSanitizer clean). Comparison: the path runs once but the callback info is never
 *          released (a LeakSanitizer report).
 * Reconnects are switched off (connectFailureCount past MAX_CONNECT_FAILURES), so the disconnect
 * path dials nothing else. Exit 0 = the case held.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "BRPeerManager.c"

static atomic_int g_armed;          /* 1: the next main-thread pthread_create is the dial's */
static int g_mode;                  /* 0 fast, 1 nothread */
static pthread_t g_main;
static atomic_int g_reachedDisconnected;

int __real_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg);

int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg)
{
    if (! pthread_equal(pthread_self(), g_main) || ! atomic_exchange(&g_armed, 0))
        return __real_pthread_create(t, a, fn, arg);
    if (g_mode == 1) return EAGAIN;

    int r = __real_pthread_create(t, a, fn, arg);
    if (r != 0) return r;
    /* Return only once the thread has marked the peer disconnected (bounded at 5 s). */
    struct timespec ts = { 0, 1000000 };
    for (int i = 0; i < 5000; i++) {
        if (BRPeerConnectStatus((BRPeer *)arg) == BRPeerStatusDisconnected) {
            atomic_store(&g_reachedDisconnected, 1);
            break;
        }
        nanosleep(&ts, NULL);
    }
    return 0;
}

/* A loopback port nothing listens on. */
static uint16_t closedPort(void)
{
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in sa; socklen_t l = sizeof(sa);
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET; sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK); sa.sin_port = 0;
    bind(s, (struct sockaddr *)&sa, sizeof(sa));
    getsockname(s, (struct sockaddr *)&sa, &l);
    close(s);
    return ntohs(sa.sin_port);
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: %s fast|nothread\n", argv[0]); return 2; }
    if (strcmp(argv[1], "fast") == 0) g_mode = 0;
    else if (strcmp(argv[1], "nothread") == 0) g_mode = 1;
    else { printf("unknown case\n"); return 2; }
    g_main = pthread_self();

    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);

    BRPeer tmpl = BR_PEER_NONE;
    tmpl.address = (UInt128) { .u8 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1 } };
    tmpl.port = closedPort();
    tmpl.services = SERVICES_NODE_NETWORK;
    printf("[%s] dial 127.0.0.1:%u (refused)\n", argv[1], tmpl.port);

    MGR_LOCK(m);
    m->connectFailureCount = MAX_CONNECT_FAILURES + 1;   /* no reconnect from the disconnect path */
    uint32_t before = m->closeTotal;
    atomic_store(&g_armed, 1);
    _BRPeerManagerBeginConnect(m, &tmpl);
    MGR_UNLOCK(m);

    if (g_mode == 0 && ! atomic_load(&g_reachedDisconnected)) {
        printf("SETUP: the peer thread did not mark the peer disconnected within 5 s\n");
        return 3;
    }

    /* Wait (bounded) for every peer thread to finish. */
    int threads = -1;
    struct timespec ts = { 0, 10000000 };
    for (int i = 0; i < 500; i++) {
        MGR_LOCK(m); threads = m->peerThreadCount; MGR_UNLOCK(m);
        if (threads <= 0) break;
        nanosleep(&ts, NULL);
    }
    nanosleep(&ts, NULL);
    MGR_LOCK(m);
    threads = m->peerThreadCount;
    uint32_t runs = m->closeTotal - before;
    size_t connected = array_count(m->connectedPeers);
    MGR_UNLOCK(m);

    printf("  disconnect path ran %u time(s); peer threads %d; connected peers %zu\n", runs, threads, connected);
    int ok = (runs == 1 && threads == 0 && connected == 0);
    printf("  %s the disconnect path ran once, and the dial released what it took\n", ok ? "ok  " : "FAIL");

    int freed = BRPeerManagerFree(m);
    BRWalletFree(w);
    if (freed != 1) { printf("  FAIL the manager was not freed at once (%d)\n", freed); ok = 0; }
    printf(ok ? "CASE PASS %s\n" : "CASE FAIL %s\n", argv[1]);
    return ok ? 0 : 1;
}
