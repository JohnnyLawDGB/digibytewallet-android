// Host KAT: a peer's addr timestamp never sorts a peer above fresh ones (4.1.2 row 22, GHSA-cqxw).
//   [A] an addr entry with ts = 1: the relayed timestamp is in the past -- RED under ADDR_TIMESTAMP_UNFIXED,
//       where 1 - 2h wraps the uint64 to 2^64 - 7199 and the attacker's entry sorts first;
//   [B] guard: an honest recent timestamp keeps its value less the protocol's two hours.
// BRPeer.c is #included (same idiom as peer_command_field_kat); _BRPeerAcceptAddrMessage is called directly.
#include "BRPeer.c"
#include "BRChainParams.h"

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static uint64_t g_seen = 0;
static size_t g_count = 0;
static void relayed(void *info, const BRPeer peers[], size_t n) { (void)info; g_count = n; if (n) g_seen = peers[0].timestamp; }

static uint64_t relayOne(BRPeer *p, uint32_t ts)
{
    uint8_t m[1 + 30];
    size_t o = 0;
    m[o++] = 1;                                         // one address
    UInt32SetLE(&m[o], ts); o += 4;
    UInt64SetLE(&m[o], SERVICES_NODE_NETWORK); o += 8;
    memset(&m[o], 0, 10); m[o + 10] = 0xff; m[o + 11] = 0xff; m[o + 12] = 1; m[o + 13] = 2; m[o + 14] = 3; m[o + 15] = 4; o += 16;
    UInt16SetBE(&m[o], 12024); o += 2;
    g_seen = 0; g_count = 0;
    _BRPeerAcceptAddrMessage(p, m, o);
    return g_seen;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    BRPeerContext *ctx = (BRPeerContext *)p;
    ctx->sentGetaddr = 1;
    ctx->relayedPeers = relayed;
    uint64_t now = (uint64_t)time(NULL);

    printf("[A] ts = 1\n");
    uint64_t a = relayOne(p, 1);
    if (g_count != 1) { printf("FAIL: setup: the entry was not relayed\n"); return 2; }
    if (a > now) { printf("FAIL: A: the timestamp wrapped to %llu, above now\n", (unsigned long long)a); g_fail++; }
    else ck(1, "the relayed timestamp is in the past");

    printf("[B] an honest timestamp\n");
    uint64_t b = relayOne(p, (uint32_t)(now - 60));
    ck(b == now - 60 - 2*60*60, "an honest timestamp keeps its value less two hours");

    BRPeerFree(p);
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: addr_timestamp_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
