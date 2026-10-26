// Host KAT: with Tor required, a peer is reached through the SOCKS proxy or not at all (4.1.2 row 2, N3).
//
// The REAL _peerThreadRoutine / _BRPeerOpenSocket / _BRPeerSendMessageTimeout are driven over loopback:
//   [A] Tor required, no proxy (Tor still bootstrapping, or a refresh before the proxy is wired):
//       no TCP connection reaches the peer at all;
//   [B] a peer connected directly before Tor was required: once it is, nothing more is sent on that
//       connection (a ping is refused) and the peer is disconnected;
//   [C] Tor required with the proxy set: the dial goes to the proxy, never to the peer -- the guard
//       that the gate refuses only what it should.
// Before the fix (-DTOR_GATE_UNFIXED) [A] connects directly and [B] sends the ping: both must FAIL there.
//
// BRPeer.c is #included (same idiom as peer_command_field_kat). Every wait is bounded. Exit 0 = all passed.
#include "BRPeer.c"
#include "BRChainParams.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <unistd.h>
#include <poll.h>

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static double now(void) { struct timeval tv; gettimeofday(&tv, NULL); return tv.tv_sec + tv.tv_usec / 1e6; }

static void noPong(void *info, int success) { (void)info; (void)success; }

static int listener(uint16_t *port) {
    int ls = socket(AF_INET, SOCK_STREAM, 0), on = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    struct sockaddr_in sa = {0}; sa.sin_family = AF_INET; sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(ls, (struct sockaddr *)&sa, sizeof(sa)); listen(ls, 4);
    socklen_t sl = sizeof(sa); getsockname(ls, (struct sockaddr *)&sa, &sl);
    *port = ntohs(sa.sin_port);
    return ls;
}

static int acceptWithin(int ls, int ms) {
    struct pollfd pf = { ls, POLLIN, 0 };
    return (poll(&pf, 1, ms) > 0) ? accept(ls, NULL, NULL) : -1;
}

// Bytes the remote receives in `secs`; stops at EOF.
static size_t received(int c, double secs) {
    uint8_t buf[4096]; size_t n = 0; double end = now() + secs;
    while (now() < end) {
        struct pollfd pf = { c, POLLIN, 0 };
        if (poll(&pf, 1, 50) > 0) { ssize_t r = read(c, buf, sizeof(buf)); if (r <= 0) break; n += (size_t)r; }
    }
    return n;
}

static int waitStatus(BRPeer *p, BRPeerStatus want, double secs) {
    double end = now() + secs;
    while (now() < end) { if (BRPeerConnectStatus(p) == want) return 1; usleep(20000); }
    return BRPeerConnectStatus(p) == want;
}

static BRPeer *peerTo(uint16_t port) {
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    UInt128 a = UINT128_ZERO; a.u16[5] = 0xffff; a.u8[12] = 127; a.u8[15] = 1;
    p->address = a; p->port = port;
    return p;
}

static void finish(BRPeer *p) {
    BRPeerDisconnect(p);
    waitStatus(p, BRPeerStatusDisconnected, 10);
    usleep(200000);                                   // let the detached thread finish
    BRPeerFree(p);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);

    printf("[A] Tor required, no proxy\n");
    {
        uint16_t port; int ls = listener(&port);
        BRPeerClearSocksProxy(); BRPeerSetTorRequired(1);
        BRPeer *p = peerTo(port);
        BRPeerConnect(p);
        int c = acceptWithin(ls, 2500);
        if (c >= 0) { printf("FAIL: A: the peer was dialled directly with Tor required\n"); g_fail++; close(c); }
        else ck(1, "no connection reached the peer");
        ck(waitStatus(p, BRPeerStatusDisconnected, 5), "the peer ended disconnected");
        finish(p); close(ls);
    }

    printf("[B] a direct connection made before Tor was required\n");
    {
        uint16_t port; int ls = listener(&port);
        BRPeerClearSocksProxy(); BRPeerSetTorRequired(0);
        BRPeer *p = peerTo(port);
        BRPeerConnect(p);
        int c = acceptWithin(ls, 5000);
        ck(c >= 0, "with Tor not required the peer connects directly");
        if (c >= 0) {
            ck(received(c, 0.5) > 0, "and sends its version message");
            BRPeerSetTorRequired(1);
            BRPeerSendPing(p, NULL, noPong);
            size_t n = received(c, 1.5);
            if (n > 0) { printf("FAIL: B: %zu bytes were sent on the direct connection with Tor required\n", n); g_fail++; }
            else ck(1, "nothing was sent on it once Tor was required");
            ck(waitStatus(p, BRPeerStatusDisconnected, 5), "the peer was disconnected");
            close(c);
        }
        finish(p); close(ls);
    }

    printf("[C] Tor required, proxy set\n");
    {
        uint16_t peerPort, proxyPort; int ls = listener(&peerPort), ps = listener(&proxyPort);
        BRPeerSetSocksProxy("127.0.0.1", proxyPort); BRPeerSetTorRequired(1);
        BRPeer *p = peerTo(peerPort);
        BRPeerConnect(p);
        int pc = acceptWithin(ps, 5000);
        ck(pc >= 0, "the dial went to the proxy");
        int c = acceptWithin(ls, 500);
        ck(c < 0, "and never to the peer");
        if (c >= 0) close(c);
        if (pc >= 0) close(pc);                       // no SOCKS reply: the handshake fails and the peer closes
        ck(waitStatus(p, BRPeerStatusDisconnected, 10), "the peer ended disconnected");
        finish(p); close(ps); close(ls);
        BRPeerClearSocksProxy(); BRPeerSetTorRequired(0);
    }

    if (g_fail == 0) printf("\nPASS: tor_gate_kat\n");
    else printf("\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
