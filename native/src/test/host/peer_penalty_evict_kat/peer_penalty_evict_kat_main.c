// peer_penalty_evict_kat — a short redial cooldown must never evict a live ban.
//
// THE BUG THIS GATES (measured on a Note 8, 2026-08-02, during a deep restore).
// The penalty table was 32 entries with `idx = penaltyCount % PEER_PENALTY_MAX` — evict the
// OLDEST INSERT, unconditionally. So a live 10-minute "doesn't support SPV mode" ban was
// routinely discarded to make room for an unrelated 30-second redial cooldown. The un-banned
// peer immediately reconnected, was rejected again, and was re-penalised, evicting someone
// else in turn. Measured: 41 distinct non-SPV peers producing 3,520 disconnects in about ONE
// MINUTE (~86 redials each), consuming every connection slot.
//
// The 30s redial cooldown added the SAME DAY made it strictly worse: it inserts on every clean
// disconnect, so ordinary churn pumped the eviction rate and flushed the bans that mattered.
//
// The existing peer_penalty_kat covers only BRPeerPenaltyContains (the pure lookup helper) and
// has no red arm — it never touched the insert/evict policy at all, which is exactly how this
// survived. This gate covers the policy.
//
// NO CONSTANT SCALING: runs at the real PEER_PENALTY_MAX. (A -D would not work anyway --
// BRPeerManager.c declares it with a plain #define, which wins over the command line; see
// cf_prune_amortize_kat for the version of that lesson that cost an afternoon.)

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

// _penalizeFor and BRPeerManagerStruct are file-static/opaque; include the .c directly.
// BRPeerManager.c must therefore NOT also be a separate compilation unit in run.sh.
#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int cond, const char *what) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail = 1;
}

static UInt128 peerAddr(unsigned i) {
    UInt128 a = UINT128_ZERO;
    a.u32[0] = 0x0A000000u | i;   // 10.x.x.x, distinct per i
    return a;
}
static const uint16_t PORT = 12024;

// Production-faithful query: both real call sites clamp the count this way.
static int isPenalized(BRPeerManager *m, UInt128 a, uint16_t p, time_t now) {
    size_t n = m->penaltyCount < PEER_PENALTY_MAX ? m->penaltyCount : PEER_PENALTY_MAX;
    return BRPeerPenaltyContains(m->penaltyAddr, m->penaltyPort, m->penaltyUntil, n, a, p, now);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("peer_penalty_evict_kat\n");
    printf("  PEER_PENALTY_MAX             = %u\n", (unsigned)PEER_PENALTY_MAX);
    printf("  PEER_PENALTY_SECONDS         = %u\n", (unsigned)PEER_PENALTY_SECONDS);
    printf("  PEER_REDIAL_COOLDOWN_SECONDS = %u\n", (unsigned)PEER_REDIAL_COOLDOWN_SECONDS);

    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    check(w != NULL, "setup: wallet created");
    if (! w) return 1;

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    check(m != NULL, "setup: manager created");
    if (! m) { BRWalletFree(w); return 1; }

    const time_t NOW = 1785700000;

    // ---- Case 1: fill the table with LIVE long bans, then add short cooldowns --------
    // Every slot holds a 10-minute "isn't synced / doesn't support SPV" ban.
    for (unsigned i = 0; i < PEER_PENALTY_MAX; i++)
        _penalizeFor(m, peerAddr(i), PORT, NOW, PEER_PENALTY_SECONDS);

    check(m->penaltyCount == PEER_PENALTY_MAX, "setup: table filled exactly once per peer");

    unsigned liveBefore = 0;
    for (unsigned i = 0; i < PEER_PENALTY_MAX; i++)
        if (isPenalized(m, peerAddr(i), PORT, NOW)) liveBefore++;
    check(liveBefore == PEER_PENALTY_MAX, "setup: all long bans are live before the churn");

    // Now churn short redial cooldowns for NEW peers, exactly as a disconnect storm would.
    const unsigned CHURN = PEER_PENALTY_MAX * 2u;
    for (unsigned i = 0; i < CHURN; i++)
        _penalizeFor(m, peerAddr(1000 + i), PORT, NOW, PEER_REDIAL_COOLDOWN_SECONDS);

    unsigned survived = 0;
    for (unsigned i = 0; i < PEER_PENALTY_MAX; i++)
        if (isPenalized(m, peerAddr(i), PORT, NOW)) survived++;

    printf("  after %u short-cooldown inserts: %u/%u long bans survived\n",
           CHURN, survived, (unsigned)PEER_PENALTY_MAX);

    char msg[192];
    snprintf(msg, sizeof(msg),
             "BANS HELD: %u/%u live long bans survived %u short-cooldown inserts",
             survived, (unsigned)PEER_PENALTY_MAX, CHURN);
    check(survived == PEER_PENALTY_MAX, msg);

    // ---- Case 2: an EXPIRED slot is reused before any live one is touched ------------
    BRPeerManager *m2 = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (m2) {
        // One slot expires almost immediately; the rest are long.
        _penalizeFor(m2, peerAddr(0), PORT, NOW, 1);
        for (unsigned i = 1; i < PEER_PENALTY_MAX; i++)
            _penalizeFor(m2, peerAddr(i), PORT, NOW, PEER_PENALTY_SECONDS);

        // Later, after slot 0 lapsed: a new peer must land in it, disturbing nobody.
        const time_t LATER = NOW + 60;
        _penalizeFor(m2, peerAddr(2000), PORT, LATER, PEER_PENALTY_SECONDS);

        unsigned stillLive = 0;
        for (unsigned i = 1; i < PEER_PENALTY_MAX; i++)
            if (isPenalized(m2, peerAddr(i), PORT, LATER)) stillLive++;
        check(stillLive == PEER_PENALTY_MAX - 1,
              "expired slot is reused: every other live ban untouched");
        check(isPenalized(m2, peerAddr(2000), PORT, LATER),
              "expired slot is reused: the new penalty was actually recorded");
        BRPeerManagerFree(m2);
    }

    // ---- Case 3: a LONGER penalty may still evict the soonest-expiring --------------
    // Guards against over-correcting into "full table never accepts anything again".
    BRPeerManager *m3 = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (m3) {
        for (unsigned i = 0; i < PEER_PENALTY_MAX; i++)
            _penalizeFor(m3, peerAddr(i), PORT, NOW, PEER_REDIAL_COOLDOWN_SECONDS);  // all short
        _penalizeFor(m3, peerAddr(3000), PORT, NOW, PEER_PENALTY_SECONDS);           // long
        check(isPenalized(m3, peerAddr(3000), PORT, NOW),
              "a long ban DOES evict a soonest-expiring short cooldown (table stays useful)");
        BRPeerManagerFree(m3);
    }

    // ---- Case 4: refresh must never SHORTEN an existing penalty ---------------------
    BRPeerManager *m4 = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    if (m4) {
        _penalizeFor(m4, peerAddr(7), PORT, NOW, PEER_PENALTY_SECONDS);
        _penalizeFor(m4, peerAddr(7), PORT, NOW, PEER_REDIAL_COOLDOWN_SECONDS);
        check(isPenalized(m4, peerAddr(7), PORT, NOW + PEER_REDIAL_COOLDOWN_SECONDS + 5),
              "a clean-disconnect cooldown does not downgrade a live 10-minute ban");
        BRPeerManagerFree(m4);
    }

    BRPeerManagerFree(m);
    BRWalletFree(w);

    printf(g_fail ? "peer_penalty_evict_kat: FAIL\n" : "peer_penalty_evict_kat: ALL PASS\n");
    return g_fail;
}
