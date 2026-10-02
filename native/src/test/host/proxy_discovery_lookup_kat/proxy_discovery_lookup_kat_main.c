/* proxy_discovery_lookup_kat — while a SOCKS proxy is set, peer discovery makes no local name lookup.
 *
 * THE INVARIANT THIS PINS
 * -----------------------
 * When the peer manager runs out of known peers it falls back to discovery: it resolves every
 * name in the chain's seed list with the system resolver. While the wallet routes its peer
 * connections through a SOCKS proxy (Tor), that lookup would go to the device's resolver and the
 * network beside the proxy, so with a proxy set discovery does no name lookup at all. The peers
 * the wallet already holds (the seeder list fetched through the proxy, the compiled-in priority
 * peers, saved peers) stay exactly as they were, and a pinned peer (the user's own node) is still
 * the only peer when it is set. With no proxy, discovery resolves every seed name, as before.
 *
 * HOW
 * ---
 * This file #includes BRPeer.c and BRPeerManager.c (same shape as download_peer_promote_kat) and
 * calls the REAL _BRPeerManagerFindPeers. The resolver is replaced for BRPeerManager.c only, by a
 * preprocessor substitution scoped to that #include: kat_getaddrinfo counts each call and answers
 * "no such name", so nothing leaves the host and every lookup thread finishes at once.
 *
 * Each scenario prints "RESULT <name> held" or "RESULT <name> broken"; run.sh decides which answer
 * each arm must give. The comparison arm is -DPROXY_DISCOVERY_LOOKUP_UNFIXED (the seam in
 * BRPeerManager.c); the shipped arm is built with no -D.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <netdb.h>

#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

static int g_lookups = 0;
static pthread_mutex_t g_lookupMutex = PTHREAD_MUTEX_INITIALIZER;

static int kat_getaddrinfo(const char *node, const char *service, const struct addrinfo *hints,
                           struct addrinfo **res)
{
    (void)node; (void)service; (void)hints;
    pthread_mutex_lock(&g_lookupMutex);
    g_lookups++;
    pthread_mutex_unlock(&g_lookupMutex);
    if (res) *res = NULL;
    return EAI_NONAME;
}

static int lookups(void)
{
    pthread_mutex_lock(&g_lookupMutex);
    int n = g_lookups;
    pthread_mutex_unlock(&g_lookupMutex);
    return n;
}

static void resetLookups(void)
{
    pthread_mutex_lock(&g_lookupMutex);
    g_lookups = 0;
    pthread_mutex_unlock(&g_lookupMutex);
}

#define getaddrinfo kat_getaddrinfo
#include "BRPeerManager.c"
#undef getaddrinfo

static int g_fail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (w) BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));
    return w;
}

static size_t seedCount(const BRChainParams *params)
{
    size_t n = 0;
    while (params->dnsSeeds[n]) n++;
    return n;
}

/* Two peers the wallet already knows (as the seeder list or the priority peers would leave them). */
static void knownPeers(BRPeer out[2])
{
    memset(out, 0, 2 * sizeof(BRPeer));
    for (int i = 0; i < 2; i++) {
        out[i].address.u16[5] = 0xffff;
        out[i].address.u8[12] = 10;
        out[i].address.u8[15] = (uint8_t)(i + 1);
        out[i].port = 12024;
        out[i].services = SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS;
        out[i].timestamp = time(NULL) - 60;
    }
}

static int holdsPeer(BRPeerManager *m, const BRPeer *p)
{
    for (size_t i = 0; i < array_count(m->peers); i++) {
        if (UInt128Eq(m->peers[i].address, p->address) && m->peers[i].port == p->port) return 1;
    }
    return 0;
}

/* Runs discovery the way the connect path does: with the manager lock held. */
static void discover(BRPeerManager *m)
{
    MGR_LOCK(m);
    _BRPeerManagerFindPeers(m);
    MGR_UNLOCK(m);
}

static void result(const char *name, int failuresBefore)
{
    printf("RESULT %s %s\n", name, g_fail == failuresBefore ? "held" : "broken");
}

/* With a proxy set: no lookup, and the known peers are untouched. */
static void scenarioProxy(const char *name, const BRChainParams *params)
{
    int before = g_fail;
    printf("\n-- %s: a SOCKS proxy is set, the manager has run out of peers to try --\n", name);
    BRWallet *w = makeWallet();
    BRPeer known[2];
    knownPeers(known);
    BRPeerManager *m = BRPeerManagerNew(params, w, 0, NULL, 0, known, 2);

    BRPeerSetSocksProxy("127.0.0.1", 9050);
    resetLookups();
    discover(m);
    check(lookups() == 0, "discovery made no local name lookup");
    check(m->dnsThreadCount == 0, "no lookup thread is left running");
    check(holdsPeer(m, &known[0]) && holdsPeer(m, &known[1]), "the peers the wallet already knew are still there");
    BRPeerClearSocksProxy();

    BRPeerManagerFree(m);
    BRWalletFree(w);
    result(name, before);
}

/* Control: with no proxy, discovery resolves every seed name, as before. */
static void scenarioDirect(const char *name, const BRChainParams *params)
{
    int before = g_fail;
    printf("\n-- %s: no proxy (the user's Tor setting is off) --\n", name);
    BRWallet *w = makeWallet();
    BRPeer known[2];
    knownPeers(known);
    BRPeerManager *m = BRPeerManagerNew(params, w, 0, NULL, 0, known, 2);

    BRPeerClearSocksProxy();
    resetLookups();
    discover(m);
    check(seedCount(params) > 0, "(fixture) the chain has seed names");
    check(lookups() == (int)seedCount(params), "every seed name was resolved");
    check(m->dnsThreadCount == 0, "every lookup thread has finished");
    check(holdsPeer(m, &known[0]) && holdsPeer(m, &known[1]), "the peers the wallet already knew are still there");

    BRPeerManagerFree(m);
    BRWalletFree(w);
    result(name, before);
}

/* A pinned peer (the user's own node) with a proxy set: it is the only peer, nothing is resolved. */
static void scenarioPinned(const char *name)
{
    int before = g_fail;
    printf("\n-- %s: a pinned peer and a SOCKS proxy --\n", name);
    BRWallet *w = makeWallet();
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    BRPeer pair[2];
    knownPeers(pair);
    BRPeer fixed = pair[0];
    m->fixedPeer = fixed;

    BRPeerSetSocksProxy("127.0.0.1", 9050);
    resetLookups();
    discover(m);
    check(lookups() == 0, "no local name lookup");
    check(array_count(m->peers) == 1 && UInt128Eq(m->peers[0].address, fixed.address),
          "the pinned peer is the only peer");
    BRPeerClearSocksProxy();

    UInt128 zero = UINT128_ZERO;
    m->fixedPeer.address = zero;
    BRPeerManagerFree(m);
    BRWalletFree(w);
    result(name, before);
}

int main(int argc, char *argv[])
{
    const char *only = argc > 1 ? argv[1] : NULL;
    if (! only || ! strcmp(only, "proxy_mainnet"))  scenarioProxy("proxy_mainnet", &BRMainNetParams);
    if (! only || ! strcmp(only, "proxy_testnet"))  scenarioProxy("proxy_testnet", &BRTestNetParams);
    if (! only || ! strcmp(only, "direct_mainnet")) scenarioDirect("direct_mainnet", &BRMainNetParams);
    if (! only || ! strcmp(only, "direct_testnet")) scenarioDirect("direct_testnet", &BRTestNetParams);
    if (! only || ! strcmp(only, "pinned_proxy"))   scenarioPinned("pinned_proxy");

    printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
