// Host KAT: the penalty persistence buffer holds every penalty the peer manager keeps.
//
// The bridge (jni_peer.c, serializePeerPenalties) serializes the manager's live penalty set into a
// buffer of BR_PEER_PENALTY_BLOB_MAX bytes and persists it; a 0-byte answer means "keep the stored
// set" (nothing is written). The manager keeps up to PEER_PENALTY_MAX (256) entries, so a buffer
// sized for fewer silently stops persisting once the live set outgrows it.
//
// WHAT IT PROVES, in a buffer of exactly BR_PEER_PENALTY_BLOB_MAX bytes:
//   [1] 40 live penalties serialize (4 + 40*26 bytes) and restore into a fresh manager: 40.
//   [2] a full table (300 penalized, 256 kept) serializes all 256 and restores 256.
//   [3] 10 live penalties serialize and restore (a set the earlier buffer also held: control).
// Comparison arm (-DPEER_PENALTY_BLOB_UNFIXED: the earlier 4 + 32*26 buffer): [1] and [2]
// serialize 0 bytes; [3] passes.
// run.sh also checks that the bridge sizes its buffer with BR_PEER_PENALTY_BLOB_MAX (a GUARD: the
// bridge cannot be built on the host).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>

#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int c, const char *what) { printf("  [%s] %s\n", c ? "PASS" : "FAIL", what); if (! c) g_fail = 1; }

static UInt128 peerAddr(unsigned i) {
    UInt128 a = UINT128_ZERO;
    a.u16[5] = 0xffff;
    a.u32[3] = 0x0A000000u | i;   // ::ffff:10.x.x.x, distinct per i
    return a;
}

static void roundTrip(BRWallet *w, unsigned penalized, unsigned want) {
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    BRPeerManager *r = BRPeerManagerNew(&BRMainNetParams, w, 0, NULL, 0, NULL, 0);
    time_t now = time(NULL);
    uint8_t buf[BR_PEER_PENALTY_BLOB_MAX];
    char what[160];

    pthread_mutex_lock(&m->lock);
    for (unsigned i = 0; i < penalized; i++) _penalizeFor(m, peerAddr(i + 1), 12024, now, 600);
    pthread_mutex_unlock(&m->lock);

    size_t written = BRPeerManagerSerializePenalties(m, buf, sizeof(buf));
    printf("  %u penalized, buffer %zu bytes -> %zu bytes written\n", penalized, sizeof(buf), written);
    snprintf(what, sizeof(what), "all %u live penalties serialize (%u bytes)", want,
             (unsigned)(BR_PEER_PENALTY_HEADER_BYTES + want * BR_PEER_PENALTY_ENTRY_BYTES));
    check(written == BR_PEER_PENALTY_HEADER_BYTES + want * BR_PEER_PENALTY_ENTRY_BYTES, what);
    size_t loaded = written ? BRPeerManagerLoadPenalties(r, buf, written) : 0;
    snprintf(what, sizeof(what), "a fresh manager restores %u", want);
    check(loaded == want, what);

    BRPeerManagerFree(m);
    BRPeerManagerFree(r);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("BR_PEER_PENALTY_BLOB_MAX = %u, PEER_PENALTY_MAX = %u\n",
           (unsigned)BR_PEER_PENALTY_BLOB_MAX, (unsigned)PEER_PENALTY_MAX);
    BRMasterPubKey mpk; memset(&mpk, 0, sizeof(mpk)); mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);

    printf("[1] 40 live penalties\n");
    roundTrip(w, 40, 40);
    printf("[2] a full table\n");
    roundTrip(w, 300, PEER_PENALTY_MAX);
    printf("[3] 10 live penalties (control)\n");
    roundTrip(w, 10, 10);

    BRWalletFree(w);
    printf(g_fail ? "peer_penalty_blob_kat: FAIL\n" : "peer_penalty_blob_kat: ALL PASS\n");
    return g_fail;
}
