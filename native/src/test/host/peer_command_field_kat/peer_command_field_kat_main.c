// Host KAT: a 12-character P2P command (legal, and carrying no NUL terminator) is never read past
// its 12 bytes, on any path of the peer read loop.
//
// The REAL _peerThreadRoutine is driven over loopback: a fake remote accepts the peer's connection
// and sends one 24-byte message header whose command field is twelve printable characters, then:
//   [A] a length over MAX_MSG_LENGTH        -> the "message length is too long" log and close;
//   [B] an empty payload with a bad checksum -> the "invalid checksum" log and close;
//   [C] an empty payload, correct checksum   -> dispatch: "dropping ..., not implemented" and the
//                                                command copied into ctx->acceptType;
//   [D] the same through BRPeerAcceptMessageTest with the command at the very end of a 12-byte
//       heap buffer, so any read past it is a heap overflow ASan reports.
// The checks are AddressSanitizer's: before the fix [A] faults with a stack-buffer-overflow READ
// past the 24-byte `header` array (recorded red, against core 91000fd). Each case must also end the
// way the protocol says (closed, or still connected after [C]), so the harness is seen to reach
// the code it means to.
//
// BRPeer.c is #included (same idiom as peer_keepalive_kat), so it is NOT on the clang line.
// Bounded: every wait has a deadline. Exit 0 = all passed.
#include "BRPeer.c"
#include "BRChainParams.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <unistd.h>
#include <poll.h>

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static const char kCmd[12] = { 'A','B','C','D','E','F','G','H','I','J','K','L' };   // no NUL

static double now(void) { struct timeval tv; gettimeofday(&tv, NULL); return tv.tv_sec + tv.tv_usec / 1e6; }

// Drain whatever the peer sent (its version message) for up to `secs`, without blocking past it.
static void drain(int c, double secs) {
    uint8_t buf[4096]; double end = now() + secs;
    while (now() < end) {
        struct pollfd pf = { c, POLLIN, 0 };
        if (poll(&pf, 1, 50) > 0) { if (read(c, buf, sizeof(buf)) <= 0) return; }
    }
}

static int waitStatus(BRPeer *p, BRPeerStatus want, double secs) {
    double end = now() + secs;
    while (now() < end) { if (BRPeerConnectStatus(p) == want) return 1; usleep(20000); }
    return BRPeerConnectStatus(p) == want;
}

// Connect a fresh peer to a loopback listener, send `header`, and report the peer's status after.
static int runCase(uint32_t msgLen, uint32_t checksum, int expectClosed, const char *name) {
    int ls = socket(AF_INET, SOCK_STREAM, 0), on = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    struct sockaddr_in sa = {0}; sa.sin_family = AF_INET; sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(ls, (struct sockaddr *)&sa, sizeof(sa)); listen(ls, 1);
    socklen_t sl = sizeof(sa); getsockname(ls, (struct sockaddr *)&sa, &sl);

    uint32_t magic = BRMainNetParams.magicNumber;
    BRPeer *p = BRPeerNew(magic);
    UInt128 a = UINT128_ZERO; a.u16[5] = 0xffff; a.u8[12] = 127; a.u8[15] = 1;
    p->address = a; p->port = ntohs(sa.sin_port);
    BRPeerConnect(p);

    struct pollfd pf = { ls, POLLIN, 0 };
    int c = (poll(&pf, 1, 5000) > 0) ? accept(ls, NULL, NULL) : -1;
    ck(c >= 0, "the peer connected to the fake remote");
    int ok = 0;
    if (c >= 0) {
        drain(c, 0.2);
        uint8_t h[24]; UInt32SetLE(h, magic); memcpy(&h[4], kCmd, 12);
        UInt32SetLE(&h[16], msgLen); UInt32SetLE(&h[20], checksum);
        ck(write(c, h, sizeof(h)) == (ssize_t)sizeof(h), "the 24-byte header was sent");
        if (expectClosed) {
            ok = waitStatus(p, BRPeerStatusDisconnected, 10);
        } else {
            drain(c, 0.5);                                   // the message is dispatched and dropped
            ok = (BRPeerConnectStatus(p) != BRPeerStatusDisconnected);
            BRPeerContext *ctx = (BRPeerContext *)p;
            char seen[16]; for (int i = 0; i < 16; i++) seen[i] = ctx->acceptType[i];
            ck(memcmp(seen, kCmd, 12) == 0 && seen[12] == '\0', "acceptType holds exactly the 12-character command");
        }
        close(c);
    }
    ck(ok, name);
    waitStatus(p, BRPeerStatusDisconnected, 10);
    usleep(200000);                                          // let the detached thread finish
    close(ls);
    BRPeerFree(p);
    return ok;
}

int main(void) {
    UInt256 empty; BRSHA256_2(&empty, NULL, 0);
    uint32_t goodSum = UInt32GetLE(&empty);

    printf("[A] over-long message length\n");
    runCase(0x41414141u, 0x42424242u, 1, "the peer closed on the over-long length");
    printf("[B] bad checksum\n");
    runCase(0, goodSum ^ 0xffffffffu, 1, "the peer closed on the bad checksum");
    printf("[C] unknown command, valid empty payload\n");
    runCase(0, goodSum, 0, "the peer dropped the unknown command and stayed connected");

    printf("[D] dispatch with the command at the end of its buffer\n");
    {
        BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
        char *cmd = malloc(12); memcpy(cmd, kCmd, 12);
        BRPeerAcceptMessageTest(p, NULL, 0, cmd);
        ck(1, "an unterminated command was dispatched without reading past it");
        free(cmd);
        BRPeerFree(p);
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
