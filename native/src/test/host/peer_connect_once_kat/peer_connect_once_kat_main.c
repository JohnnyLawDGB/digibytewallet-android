/* peer_connect_once_kat -- a peer gets one peer thread for its life.
 *
 * A peer thread marks its peer disconnected as soon as its connection ends, and only then runs its
 * teardown: the pending pong and mempool callbacks, then the disconnected callback, after which the
 * manager is done with the peer (_peerDisconnected). For that stretch the peer reads "disconnected"
 * with its thread still running. BRPeerManagerConnect calls BRPeerConnect for every peer that reads
 * "connecting" (to resume one that was waiting for the network), and BRPeerConnect used to take
 * "disconnected" to mean "no thread". A peer whose thread marked it disconnected between those two
 * reads was given a second thread.
 *
 * WHAT IT PROVES. The status read the manager makes before it calls BRPeerConnect is interposed
 * (BRPeerConnectStatus is renamed for the included manager source only). The dialed peer is a
 * loopback listener that accepts and never answers, so the peer stays "connecting".
 *   exiting  the interposed read ends the remote side, waits until the peer thread has marked the
 *            peer disconnected, and returns "connecting" (the read taken just before that, made
 *            deterministic). Shipped: BRPeerConnect starts nothing, no second connection reaches
 *            the remote, the disconnect path runs once, the thread count returns to 0, and the
 *            manager frees at once. Comparison (-DPEER_CONNECT_ONCE_UNFIXED): a second thread
 *            dials the remote and outlives the peer (an AddressSanitizer report).
 *   waiting  the resume the re-dial exists for: a peer dialed while the network is unreachable
 *            has no thread, and BRPeerManagerConnect starts one once the network is back. Both
 *            arms: one connection reaches the remote, the disconnect path runs once, the count
 *            returns to 0.
 * No other peer is dialed (maxConnectCount 0) and nothing leaves the host. Exit 0 = the case held.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include <poll.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define BRPeerConnectStatus kat_BRPeerConnectStatus
#include "BRPeerManager.c"
#undef BRPeerConnectStatus
BRPeerStatus BRPeerConnectStatus(BRPeer *peer);

static pthread_t g_main;
static BRPeer *g_target;
static atomic_int g_armed;          /* 1: the next main-thread status read of g_target is the stale one */
static atomic_int g_flipped;        /* the peer thread marked the peer disconnected under the read */
static int g_remote = -1;           /* the accepted end of the first connection */
static atomic_int g_reachable = 1;

BRPeerStatus kat_BRPeerConnectStatus(BRPeer *peer)
{
    BRPeerStatus s = BRPeerConnectStatus(peer);

    if (peer != g_target || ! pthread_equal(pthread_self(), g_main) || s != BRPeerStatusConnecting ||
        ! atomic_exchange(&g_armed, 0)) return s;
    /* End the connection, then wait (bounded at 5 s) for the peer thread to mark the peer disconnected. */
    close(g_remote);
    g_remote = -1;
    struct timespec ts = { 0, 1000000 };
    for (int i = 0; i < 5000; i++) {
        if (BRPeerConnectStatus(peer) == BRPeerStatusDisconnected) { atomic_store(&g_flipped, 1); break; }
        nanosleep(&ts, NULL);
    }
    return s;
}

static int katReachable(void *info) { (void)info; return atomic_load(&g_reachable); }

static int listener(uint16_t *port)
{
    int s = socket(AF_INET, SOCK_STREAM, 0), on = 1;
    struct sockaddr_in sa; socklen_t l = sizeof(sa);
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET; sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK); sa.sin_port = 0;
    if (bind(s, (struct sockaddr *)&sa, sizeof(sa)) != 0 || listen(s, 4) != 0) return -1;
    getsockname(s, (struct sockaddr *)&sa, &l);
    *port = ntohs(sa.sin_port);
    return s;
}

static int acceptWithin(int ls, int ms)
{
    struct pollfd pf = { ls, POLLIN, 0 };
    return (poll(&pf, 1, ms) > 0) ? accept(ls, NULL, NULL) : -1;
}

/* Wait (bounded at 5 s) until the disconnect path has run `runs` times and no peer thread is counted. */
static void settle(BRPeerManager *m, uint32_t before, uint32_t runs)
{
    struct timespec ts = { 0, 10000000 };
    for (int i = 0; i < 500; i++) {
        MGR_LOCK(m);
        int done = (m->closeTotal - before >= runs && m->peerThreadCount <= 0);
        MGR_UNLOCK(m);
        if (done) break;
        nanosleep(&ts, NULL);
    }
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2 || (strcmp(argv[1], "exiting") != 0 && strcmp(argv[1], "waiting") != 0)) {
        printf("usage: %s exiting|waiting\n", argv[0]);
        return 2;
    }
    int waiting = (strcmp(argv[1], "waiting") == 0);
    g_main = pthread_self();

    uint16_t port;
    int ls = listener(&port);
    if (ls < 0) { printf("SETUP: no loopback listener\n"); return 3; }

    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    m->networkIsReachable = katReachable;

    BRPeer tmpl = BR_PEER_NONE;
    tmpl.address = (UInt128) { .u8 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1 } };
    tmpl.port = port;
    tmpl.services = SERVICES_NODE_NETWORK;
    printf("[%s] dial 127.0.0.1:%u (accepts, never answers)\n", argv[1], port);

    MGR_LOCK(m);
    m->maxConnectCount = 0;                     /* BRPeerManagerConnect dials nothing new */
    uint32_t before = m->closeTotal;
    if (waiting) atomic_store(&g_reachable, 0);
    _BRPeerManagerBeginConnect(m, &tmpl);
    g_target = m->connectedPeers[0];
    MGR_UNLOCK(m);

    int ok = 1;
    if (waiting) {
        int early = acceptWithin(ls, 300);
        if (early >= 0) { printf("SETUP: the peer dialed while the network was unreachable\n"); return 3; }
        atomic_store(&g_reachable, 1);
        BRPeerManagerConnect(m);                /* the resume: the waiting peer is dialed now */
        g_remote = acceptWithin(ls, 5000);
        ok &= (g_remote >= 0);
        printf("  %s the waiting peer was dialed once the network was back\n", g_remote >= 0 ? "ok  " : "FAIL");
        if (g_remote >= 0) { close(g_remote); g_remote = -1; }
    }
    else {
        g_remote = acceptWithin(ls, 5000);
        if (g_remote < 0) { printf("SETUP: the peer never reached the listener\n"); return 3; }
        atomic_store(&g_armed, 1);
        BRPeerManagerConnect(m);                /* the re-dial walk; its status read is the stale one */
        if (! atomic_load(&g_flipped)) { printf("SETUP: the peer thread did not mark the peer disconnected\n"); return 3; }
    }

    settle(m, before, 1);

    /* A second thread, if one was started, has its own connection to the remote: end it. */
    int second = acceptWithin(ls, 500);
    if (second >= 0) {
        printf("  a second peer thread dialed the remote\n");
        close(second);
        struct timespec ts = { 0, 300000000 };
        nanosleep(&ts, NULL);                   /* let that thread end */
        settle(m, before, 2);
    }

    MGR_LOCK(m);
    int threads = m->peerThreadCount;
    uint32_t runs = m->closeTotal - before;
    size_t connected = array_count(m->connectedPeers);
    MGR_UNLOCK(m);

    printf("  disconnect path ran %u time(s); peer threads %d; connected peers %zu\n", runs, threads, connected);
    ok &= (second < 0);
    printf("  %s no second connection reached the remote\n", second < 0 ? "ok  " : "FAIL");
    ok &= (runs == 1 && threads == 0 && connected == 0);
    printf("  %s the disconnect path ran once and the thread count returned to 0\n",
           (runs == 1 && threads == 0 && connected == 0) ? "ok  " : "FAIL");

    int freed = BRPeerManagerFree(m);
    BRWalletFree(w);
    close(ls);
    if (freed != 1) { printf("  FAIL the manager was not freed at once (%d)\n", freed); ok = 0; }
    printf(ok ? "CASE PASS %s\n" : "CASE FAIL %s\n", argv[1]);
    return ok ? 0 : 1;
}
