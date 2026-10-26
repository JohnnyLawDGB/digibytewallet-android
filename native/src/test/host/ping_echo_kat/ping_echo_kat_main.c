// Host KAT: a peer cannot make the wallet echo an oversized ping (4.1.2 row 31).
//   [A] a 32 KiB ping is refused and nothing is sent -- RED under PING_ECHO_UNFIXED, where the whole
//       message went back as the pong;
//   [B] guard: an 8-byte ping (BIP 31) gets its 8-byte nonce back as the pong.
// BRPeer.c is #included; the peer's socket is one end of a socketpair, so what it sends can be counted.
#include "BRPeer.c"
#include "BRChainParams.h"
#include <sys/socket.h>
#include <poll.h>

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static size_t drain(int fd)
{
    uint8_t buf[65536]; size_t n = 0; ssize_t r;
    struct pollfd pf = { fd, POLLIN, 0 };
    while (poll(&pf, 1, 200) > 0 && (r = read(fd, buf, sizeof(buf))) > 0) n += (size_t)r;
    return n;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    BRPeerContext *ctx = (BRPeerContext *)p;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) { printf("FAIL: setup\n"); return 2; }
    ctx->socket = sv[0];
    ctx->status = BRPeerStatusConnected;

    printf("[A] a 32 KiB ping\n");
    static uint8_t big[32768];
    memset(big, 0x5a, sizeof(big));
    int r = _BRPeerAcceptPingMessage(p, big, sizeof(big));
    size_t sent = drain(sv[1]);
    if (r != 0 || sent != 0) { printf("FAIL: A: the ping was answered (verdict %d, %zu bytes sent)\n", r, sent); g_fail++; }
    else ck(1, "refused, nothing sent");

    printf("[B] an 8-byte ping\n");
    uint8_t nonce[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    r = _BRPeerAcceptPingMessage(p, nonce, sizeof(nonce));
    sent = drain(sv[1]);
    ck(r == 1 && sent == HEADER_LENGTH + 8, "answered with an 8-byte pong");

    ctx->socket = -1;
    close(sv[0]); close(sv[1]);
    BRPeerFree(p);
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: ping_echo_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
