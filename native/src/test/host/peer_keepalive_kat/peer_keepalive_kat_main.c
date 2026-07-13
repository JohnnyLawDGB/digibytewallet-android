// Host KAT for ANR fix #2 (native peer-manager keepalive lock-starvation).
// See .superpowers/sdd/anr-fix2-native-design.md for the full design and the
// use-after-free / lock-order / double-free analysis this fix must preserve.
//
// White-box test: this file #includes BRPeer.c directly (the same amalgamation
// idiom BRKey.c already uses for secp256k1 -- `#include "secp256k1/src/secp256k1.c"`)
// so it gets real access to the private BRPeerContext struct and can wire up a
// BRPeer whose ctx->socket is a real socketpair() fd, without needing a full
// bitcoin-protocol handshake. A true BRPeerManagerKeepAlive-level test (driving a
// live BRPeerManager with fake connected peers) is NOT reachable from a host KAT:
// BRPeerManagerStruct is intentionally opaque outside BRPeerManager.c (no test
// seam, by design -- see the design doc's UAF analysis on why BRPeer has no
// refcount / test backdoor). This KAT instead exercises the real, unmodified
// BRPeer-level primitives BRPeerManagerKeepAlive calls (BRPeerSendPingProbe,
// BRPeerSendMessage, BRPeerScheduleDisconnect, BRPeerIsSocketOpen,
// BRPeerLastRecvTime) against a genuinely wedged socket, and separately recreates
// the exact inline idle-predicate BRPeerManagerKeepAlive uses (same constant, same
// fields, same disconnect call) since that arithmetic lives inline in
// BRPeerManager.c and isn't reachable through BRPeer.c's public surface. The
// manager-level orchestration (looping many connectedPeers, the tick-budget break
// spanning multiple peers) is left to on-device/emulator verification per the
// design's step 3 -- noted in the report.
//
// Covers (numbering matches the task's test list):
//  (i)   ANR regression guard: BRPeerSendPingProbe on a wedged socket returns
//        within a small bound, nowhere near the old MESSAGE_TIMEOUT (10s).
//  (ii)  wedged peer ends up socket<0 (BRPeerIsSocketOpen false) after the probe
//        -- the existing (unchanged) BRPeerDisconnect error path.
//  (iii) a healthy peer survives: fast return, socket stays open, status stays
//        Connected.
//  (iv)  idle predicate: an old-lastRecvTime peer gets disconnectTime set to
//        ~now (not DBL_MAX) by the real BRPeerScheduleDisconnect; a
//        recent-lastRecvTime peer is left untouched.
//  (v)   BRPeerSendMessage (normal callers) is unaffected: on the same kind of
//        wedge, it still blocks for ~MESSAGE_TIMEOUT (10s), confirming the
//        _BRPeerSendMessageTimeout extraction is behavior-preserving.
//
// Exit code 0 = all checks passed, 1 = at least one check failed (or build
// error -- e.g. BRPeerSendPingProbe / lastRecvTime / KEEPALIVE_* don't exist yet,
// the RED state before the fix lands).

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <fcntl.h>
#include <errno.h>

// Pull in the real, unmodified BRPeer.c implementation, including the private
// BRPeerContext struct.
#include "BRPeer.c"
// Header-only, for PEER_INBOUND_IDLE_LIMIT. Not linking BRPeerManager.c --
// BRPeerManagerStruct is opaque outside it and this KAT never constructs one.
#include "BRPeerManager.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) {
        printf("PASS: %s\n", desc);
    } else {
        printf("FAIL: %s\n", desc);
        g_failures++;
    }
}

static double now_secs(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec + (double)tv.tv_usec / 1000000;
}

// Builds a BRPeer whose ctx->socket is the client end of a fresh AF_UNIX
// socketpair, already in BRPeerStatusConnected with disconnectTime == DBL_MAX --
// the same "idle synced peer" state the real _BRPeerDidConnect leaves a peer in
// (BRPeer.c). No background read thread is started (BRPeerConnect is never
// called): BRPeerManagerKeepAlive actually calls BRPeerSendPing/Probe from the
// MANAGER's own thread while a peer's read thread (if any) only ever reads, so
// this single-threaded setup is representative of what the send path sees.
static BRPeer *make_fake_connected_peer(int *outServerFd)
{
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) { perror("socketpair"); return NULL; }

    BRPeer *peer = BRPeerNew(0x12345678);
    BRPeerContext *ctx = (BRPeerContext *)peer;
    ctx->socket = fds[0];
    ctx->status = BRPeerStatusConnected;
    ctx->disconnectTime = DBL_MAX;
    ctx->lastRecvTime = now_secs();
    *outServerFd = fds[1];
    return peer;
}

// Deterministically wedges fd (the peer's outbound socket): fills its kernel
// send buffer via raw, temporarily-nonblocking write() calls until EWOULDBLOCK,
// with the "remote" end never draining it (the test never reads the other half
// of the socketpair). This is the socketpair()-backed fake dead-socket the
// design calls for -- fully deterministic, independent of real-network timing
// or default TCP buffer tuning. Crucially, filling never goes through
// BRPeerSendMessage/Probe itself, so setup can't accidentally trip the very
// timeout under test.
static void wedge_socket(int fd)
{
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    uint8_t junk[4096];
    memset(junk, 0xAA, sizeof(junk));
    for (int i = 0; i < 4096; i++) { // up to ~16MB of fill; bails out as soon as the buffer is full
        ssize_t n = write(fd, junk, sizeof(junk));
        if (n < 0) {
            if (errno == EWOULDBLOCK || errno == EAGAIN) break;
            perror("wedge_socket write");
            break;
        }
    }

    fcntl(fd, F_SETFL, flags); // restore blocking mode
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 }; // matches the real SO_SNDTIMEO=1s _BRPeerOpenSocket sets
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

int main(void)
{
    printf("=== (i)+(ii) wedged peer: BRPeerSendPingProbe is bounded, evicts ===\n");
    {
        int serverFd;
        BRPeer *peer = make_fake_connected_peer(&serverFd);
        check(peer != NULL, "wedge test: peer constructed");
        wedge_socket(((BRPeerContext *)peer)->socket);

        double t0 = now_secs();
        BRPeerSendPingProbe(peer, NULL, NULL);
        double elapsed = now_secs() - t0;

        printf("  BRPeerSendPingProbe on wedged socket took %.3fs (KEEPALIVE_SEND_TIMEOUT=%.1fs, MESSAGE_TIMEOUT=%.1fs)\n",
               elapsed, (double)KEEPALIVE_SEND_TIMEOUT, (double)MESSAGE_TIMEOUT);
        check(elapsed < KEEPALIVE_SEND_TIMEOUT + 1.5,
              "(i) ANR guard: BRPeerSendPingProbe on a wedged socket returns within KEEPALIVE_SEND_TIMEOUT + slack");
        check(elapsed < MESSAGE_TIMEOUT / 2.0,
              "(i) ANR guard: nowhere close to the old MESSAGE_TIMEOUT (10s) bound");
        check(! BRPeerIsSocketOpen(peer),
              "(ii) wedged peer's socket is closed (evicted) after the probe -- existing BRPeerDisconnect error path");

        close(serverFd);
        BRPeerFree(peer);
    }

    printf("\n=== (iii) healthy peer survives BRPeerSendPingProbe ===\n");
    {
        int serverFd;
        BRPeer *peer = make_fake_connected_peer(&serverFd);
        check(peer != NULL, "healthy test: peer constructed");

        double t0 = now_secs();
        BRPeerSendPingProbe(peer, NULL, NULL);
        double elapsed = now_secs() - t0;

        printf("  BRPeerSendPingProbe on a healthy socket took %.3fs\n", elapsed);
        check(elapsed < 0.5, "(iii) healthy peer: probe returns fast (no stall)");
        check(BRPeerIsSocketOpen(peer), "(iii) healthy peer: socket stays open (not evicted)");
        check(((BRPeerContext *)peer)->status == BRPeerStatusConnected,
              "(iii) healthy peer: status stays Connected");

        close(((BRPeerContext *)peer)->socket);
        close(serverFd);
        BRPeerFree(peer);
    }

    printf("\n=== (v) BRPeerSendMessage (normal callers) unaffected: still ~MESSAGE_TIMEOUT ===\n");
    {
        int serverFd;
        BRPeer *peer = make_fake_connected_peer(&serverFd);
        check(peer != NULL, "normal-caller test: peer constructed");
        wedge_socket(((BRPeerContext *)peer)->socket);

        uint8_t msg[sizeof(uint64_t)] = {0};
        double t0 = now_secs();
        BRPeerSendMessage(peer, msg, sizeof(msg), MSG_PING);
        double elapsed = now_secs() - t0;

        printf("  BRPeerSendMessage on wedged socket took %.3fs (MESSAGE_TIMEOUT=%.1fs)\n",
               elapsed, (double)MESSAGE_TIMEOUT);
        check(elapsed > MESSAGE_TIMEOUT - 1.5 && elapsed < MESSAGE_TIMEOUT + 2.5,
              "(v) plain BRPeerSendMessage on a wedged socket still uses the ~10s MESSAGE_TIMEOUT deadline "
              "(byte-identical to before the _BRPeerSendMessageTimeout extraction)");
        check(! BRPeerIsSocketOpen(peer), "(v) wedged peer evicted via the same unchanged error path");

        close(serverFd);
        BRPeerFree(peer);
    }

    printf("\n=== (iv) inbound-idle predicate (recreates BRPeerManagerKeepAlive's inline check) ===\n");
    {
        // Old lastRecvTime: predicate should fire, disconnectTime -> ~now.
        int serverFd;
        BRPeer *peer = make_fake_connected_peer(&serverFd);
        BRPeerContext *ctx = (BRPeerContext *)peer;
        ctx->lastRecvTime = now_secs() - (PEER_INBOUND_IDLE_LIMIT + 5.0);
        ctx->disconnectTime = DBL_MAX;

        double now = now_secs();
        if (now - BRPeerLastRecvTime(peer) > PEER_INBOUND_IDLE_LIMIT) {
            BRPeerScheduleDisconnect(peer, 0); // exactly what BRPeerManagerKeepAlive calls
        }

        printf("  old lastRecvTime: disconnectTime after predicate = %.3f (now = %.3f)\n", ctx->disconnectTime, now);
        check(ctx->disconnectTime < DBL_MAX / 2.0,
              "(iv) idle peer (old lastRecvTime): disconnectTime is set to a real deadline, not DBL_MAX");
        check(ctx->disconnectTime >= now - 1.0 && ctx->disconnectTime <= now + 1.0,
              "(iv) idle peer: the scheduled deadline is ~now (BRPeerScheduleDisconnect(p,0))");

        close(((BRPeerContext *)peer)->socket);
        close(serverFd);
        BRPeerFree(peer);
    }
    {
        // Recent lastRecvTime: predicate should NOT fire, disconnectTime untouched.
        int serverFd;
        BRPeer *peer = make_fake_connected_peer(&serverFd);
        BRPeerContext *ctx = (BRPeerContext *)peer;
        ctx->lastRecvTime = now_secs() - 5.0; // well under PEER_INBOUND_IDLE_LIMIT
        ctx->disconnectTime = DBL_MAX;

        double now = now_secs();
        if (now - BRPeerLastRecvTime(peer) > PEER_INBOUND_IDLE_LIMIT) {
            BRPeerScheduleDisconnect(peer, 0);
        }

        check(ctx->disconnectTime == DBL_MAX,
              "(iv) recently-active peer: disconnectTime is left untouched (DBL_MAX) -- NOT evicted");

        close(((BRPeerContext *)peer)->socket);
        close(serverFd);
        BRPeerFree(peer);
    }

    printf("\n=== budget-vs-deadline sanity (why the tick-budget bound matters) ===\n");
    check(KEEPALIVE_SEND_TIMEOUT > 0 && KEEPALIVE_SEND_TIMEOUT <= 3.0,
          "KEEPALIVE_SEND_TIMEOUT is a short (<=3s), not the old 10s, bound");
    check(KEEPALIVE_TICK_BUDGET > 0 && KEEPALIVE_TICK_BUDGET <= 3.0,
          "KEEPALIVE_TICK_BUDGET is a short (<=3s) per-tick wall-clock cap");
    check(MESSAGE_TIMEOUT >= 5.0 * KEEPALIVE_SEND_TIMEOUT,
          "MESSAGE_TIMEOUT is still >=5x KEEPALIVE_SEND_TIMEOUT -- the ping probe is deliberately much shorter");
    check(PEER_INBOUND_IDLE_LIMIT > 60.0,
          "PEER_INBOUND_IDLE_LIMIT is safely longer than the ~10s keepalive tick interval");

    if (g_failures == 0) {
        printf("\nALL PASSED (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
