//
//  asset_provenance_rules_kat_main.c
//
//  Host KAT for BRAssetProvenance.h (the provenance walk, its hop classifier and the search for a named
//  asset's facts) and BRAssetRuleGate.h (may an asset move), moved from Android's Kotlin
//  (core/.../asset/AssetProvenanceWalker.kt; AssetManager.kt classifyProvenanceHopFrom, factsForNamedAsset
//  and verifyTransferRules' identity check; asset/rules/TransferRuleState.kt). Each must answer what its
//  Kotlin answers on every input, so every expectation here is fixed OUTSIDE the headers, two ways:
//
//    1. BY HAND. The inputs and the expected answers of the Kotlin tests, value for value:
//       AssetProvenanceWalkerTest (walker_*), AssetProvenanceTxidBindingTest (binding_*: real
//       transactions, their ids computed by the core's BRRawTxId, the platform's fetch written out here
//       as a small transport that takes the wallet's own copy first and an endpoint's answer only when its
//       id is the one asked for), AssetTransferRuleGateTest (gate_*) and AssetSendRuleGateTest (send_*:
//       the platform's two store reads written out here, the verdict from the header). Where a Kotlin
//       test reads a JSON rules object, the case passes what the platform reads from it (an answer with
//       or without a rules object, or no answer). Cases without one of those prefixes are this suite's
//       own: edges, refusals, capacity, and the rules the Kotlin tests reach only through other tests.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C: resolve() over the same
//       fake store and hop table the header's executor uses (the Kotlin tests' FakeStore and Counting),
//       classifyProvenanceHopFrom with Android's getOpReturnData (jni_asset.c) and its own first-input
//       reader (firstInputOfRawTx, readVarInt), factsForNamedAsset over tables, and the gate's stateOf.
//       The sweep_* cases hold the headers to the model over fixed pseudo-random inputs, comparing every
//       store read and write and every hop asked for, in order, and the answers. The hop is compared on
//       the bytes the walk is given in production: bytes that are exactly one transaction (the wallet's
//       own serialization, or an endpoint's answer whose id BRRawTxId confirms).
//
//  LEFT TO THE PLATFORM. Three Kotlin assertions test what the platform does around the headers, not what
//  the headers answer, so no case here carries them; the iOS executor's own tests must:
//    - AssetProvenanceTxidBindingTest, a_wrong_answer_counts_as_a_failure_of_that_endpoint:
//      `first.asked.size == 2`. An endpoint that answers with the wrong transaction counts as failing, and
//      past the failure threshold it is not asked again for the cool-down period (binding_wrong_answer_refused
//      holds only the refusal and the second endpoint's four answers).
//    - AssetSendRuleGateTest, a rule-bound asset is refused before any UTXO is read:
//      `coVerify(exactly = 0) { utxoDao.getAssetUtxosByIdNow }`. The send asks the gate before it reads a
//      UTXO (send_rule_bound holds only the refusal).
//    - AssetSendRuleGateTest, transferRuleState reads the two stores and nothing else:
//      `coVerify(exactly = 0) { refreshRules }`. Asking for the state makes no network call
//      (send_two_stores holds only the two verdicts).
//
//  One case per process: `asset_provenance_rules_kat <case>` prints PASS:/FAIL: lines and
//  `RESULT <case> pass|fail`; `list` prints the case names. run.sh builds one binary per comparison arm and
//  holds each arm to failing exactly its cases.
//

#include "BRAssetProvenance.h"
#include "BRAssetRuleGate.h"
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_provenance_rules_kat_tu2(void);   // run.sh's second translation unit, which includes the headers twice

static int FAILED = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (! cond) FAILED = 1;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

#define N(a) (sizeof(a) / sizeof((a)[0]))

// ---- a fixed pseudo-random stream -----------------------------------------------------------

static uint64_t RNG = 0x9e3779b97f4a7c15ULL;

static uint64_t rnd(void)   // xorshift64
{
    RNG ^= RNG << 13;
    RNG ^= RNG >> 7;
    RNG ^= RNG << 17;
    return RNG;
}

static uint64_t below(uint64_t n) { return rnd() % n; }

// ---- a log of what a walk did ---------------------------------------------------------------

#define LOGCAP 65536

typedef struct { char s[LOGCAP]; size_t n; int over; } Log;

static void lg(Log *l, const char *fmt, ...)
{
    va_list ap;
    int k;

    if (! l || l->over) return;
    va_start(ap, fmt);
    k = vsnprintf(l->s + l->n, LOGCAP - l->n, fmt, ap);
    va_end(ap);
    if (k < 0 || (size_t)k >= LOGCAP - l->n) { l->over = 1; return; }
    l->n += (size_t)k;
}

static const char *fstr(const BRAssetResolvedFacts *f, char *buf, size_t cap)
{
    char op[16], lk[16];

    if (! f) { snprintf(buf, cap, "none"); return buf; }
    if (f->hasIssuanceOpcode) snprintf(op, sizeof(op), "%d", (int)f->issuanceOpcode); else snprintf(op, sizeof(op), "-");
    if (f->hasIssuanceLocked) snprintf(lk, sizeof(lk), "%d", f->issuanceLocked ? 1 : 0); else snprintf(lk, sizeof(lk), "-");
    snprintf(buf, cap, "{%s,%lld,%d,%s,%s,%s}", f->assetId, (long long)f->totalSupply, (int)f->divisibility,
             f->hasMetadataCid ? f->metadataCid : "-", op, lk);
    return buf;
}

static int factsEq(const BRAssetResolvedFacts *a, const BRAssetResolvedFacts *b)
{
    char x[256], y[256];

    if (! a || ! b) return a == b;
    return strcmp(fstr(a, x, sizeof(x)), fstr(b, y, sizeof(y))) == 0;
}

static BRAssetResolvedFacts mkFacts(const char *id, int64_t supply, int32_t div, const char *cid, int hasOp, int32_t op,
                                    int hasLock, int lock)
{
    BRAssetResolvedFacts f;

    memset(&f, 0, sizeof(f));
    snprintf(f.assetId, sizeof(f.assetId), "%s", id);
    f.totalSupply = supply;
    f.divisibility = div;
    if (cid) { f.hasMetadataCid = 1; snprintf(f.metadataCid, sizeof(f.metadataCid), "%s", cid); }
    f.hasIssuanceOpcode = hasOp;
    f.issuanceOpcode = hasOp ? op : 0;
    f.hasIssuanceLocked = hasLock;
    f.issuanceLocked = hasLock ? lock : 0;
    return f;
}

// Asset ids that agree on a long prefix. Real ids are BR_ASSET_ID_LENGTH (38) characters and all begin La,
// Lh or Ld, so every comparison of two ids must read them to the end: ID_Q1 and ID_Q2 differ only in their
// last character, ID_Q37 is a proper prefix of both, and "LaA" is a proper prefix of "LaAB".
#define ID_Q37 "LaQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"
#define ID_Q1 ID_Q37 "1"
#define ID_Q2 ID_Q37 "2"

// { the id the facts name, an id asked about that is not it }
static const char *const ID_PAIRS[][2] = {
    { ID_Q1, ID_Q2 }, { ID_Q2, ID_Q1 }, { ID_Q37, ID_Q1 }, { ID_Q1, ID_Q37 }, { "LaA", "LaAB" }, { "LaAB", "LaA" },
};

static int idFixturesHold(void)
{
    return strlen(ID_Q1) == BR_ASSET_ID_LENGTH && strlen(ID_Q2) == BR_ASSET_ID_LENGTH && strlen(ID_Q37) == BR_ASSET_ID_LENGTH - 1 &&
           strncmp(ID_Q1, ID_Q2, BR_ASSET_ID_LENGTH - 1) == 0 && strcmp(ID_Q1, ID_Q2) != 0;
}

// ---- the fake store (the Kotlin tests' FakeStore, InMemoryProvenanceStore, RecordingStore) ----

#define MAXROWS 512
#define MAXFR 64
#define MAXWRITES 16

typedef struct { char txid[BR_ASSET_TXID_BUFFER]; BRAssetResolvedFacts f; } Row;
typedef struct { char start[BR_ASSET_TXID_BUFFER]; char resume[BR_ASSET_TXID_BUFFER]; int32_t hops; } Front;

typedef struct {
    Row rows[MAXROWS]; size_t nrows;       // a LinkedHashMap: an overwrite keeps the row's place
    Front fr[MAXFR]; size_t nfr;
    int recording;                         // RecordingStore: assetFor answers none, putAssets is recorded
    size_t nwrites;
    char writePaths[MAXWRITES][512];
    BRAssetResolvedFacts writeFacts[MAXWRITES];
    Log *log;
} Store;

static const BRAssetResolvedFacts *stAssetFor(Store *st, const char *txid)
{
    size_t i;

    lg(st->log, "assetFor(%s)", txid);
    if (! st->recording) {
        for (i = 0; i < st->nrows; i++) if (strcmp(st->rows[i].txid, txid) == 0) { lg(st->log, "=hit;"); return &st->rows[i].f; }
    }
    lg(st->log, "=none;");
    return NULL;
}

static void stSet(Store *st, const char *txid, const BRAssetResolvedFacts *f)
{
    size_t i;

    for (i = 0; i < st->nrows; i++) if (strcmp(st->rows[i].txid, txid) == 0) { st->rows[i].f = *f; return; }
    if (st->nrows < MAXROWS) {
        snprintf(st->rows[st->nrows].txid, sizeof(st->rows[0].txid), "%s", txid);
        st->rows[st->nrows++].f = *f;
    }
}

static void stPutAssets(Store *st, const char *const *txids, size_t n, const BRAssetResolvedFacts *f)
{
    char b[256];
    size_t i, k = 0;

    lg(st->log, "putAssets([");
    for (i = 0; i < n; i++) lg(st->log, "%s%s", i ? "," : "", txids[i]);
    lg(st->log, "],%s);", fstr(f, b, sizeof(b)));
    if (st->recording) {
        if (st->nwrites < MAXWRITES) {
            st->writePaths[st->nwrites][0] = '\0';
            for (i = 0; i < n; i++) {
                int w = snprintf(st->writePaths[st->nwrites] + k, sizeof(st->writePaths[0]) - k, "%s%s", i ? "," : "", txids[i]);
                if (w > 0 && (size_t)w < sizeof(st->writePaths[0]) - k) k += (size_t)w;
            }
            st->writeFacts[st->nwrites++] = *f;
        }
        return;
    }
    for (i = 0; i < n; i++) stSet(st, txids[i], f);
}

// issuanceFactsFor, as the Kotlin store answers it (the first row, in insertion order, naming the asset
// with an opcode). `useHeader` asks BRAssetIssuanceFactsMatch instead of the Kotlin predicate.
static const BRAssetResolvedFacts *stIssuanceFactsFor(Store *st, const char *assetId, int useHeader)
{
    size_t i;

    if (st->recording) return NULL;
    for (i = 0; i < st->nrows; i++) {
        const BRAssetResolvedFacts *f = &st->rows[i].f;
        int match = useHeader ? BRAssetIssuanceFactsMatch(f, assetId)
                              : (strcmp(f->assetId, assetId) == 0 && f->hasIssuanceOpcode);
        if (match) return f;
    }
    return NULL;
}

static const Front *stFrontierFor(Store *st, const char *start)
{
    size_t i;

    lg(st->log, "frontierFor(%s)", start);
    for (i = 0; i < st->nfr; i++) if (strcmp(st->fr[i].start, start) == 0) {
        lg(st->log, "=(%s,%d);", st->fr[i].resume, (int)st->fr[i].hops);
        return &st->fr[i];
    }
    lg(st->log, "=none;");
    return NULL;
}

static void stPutFrontier(Store *st, const char *start, const char *resume, int32_t hops)
{
    size_t i;

    lg(st->log, "putFrontier(%s,%s,%d);", start, resume, (int)hops);
    for (i = 0; i < st->nfr; i++) if (strcmp(st->fr[i].start, start) == 0) break;
    if (i == st->nfr) { if (st->nfr >= MAXFR) return; st->nfr++; }
    snprintf(st->fr[i].start, sizeof(st->fr[i].start), "%s", start);
    snprintf(st->fr[i].resume, sizeof(st->fr[i].resume), "%s", resume);
    st->fr[i].hops = hops;
}

static void stClearFrontier(Store *st, const char *start)
{
    size_t i;

    lg(st->log, "clearFrontier(%s);", start);
    for (i = 0; i < st->nfr; i++) if (strcmp(st->fr[i].start, start) == 0) {
        st->fr[i] = st->fr[st->nfr - 1];
        st->nfr--;
        return;
    }
}

static const Front *stFront(Store *st, const char *start)   // read without logging
{
    size_t i;

    for (i = 0; i < st->nfr; i++) if (strcmp(st->fr[i].start, start) == 0) return &st->fr[i];
    return NULL;
}

static const BRAssetResolvedFacts *stRow(Store *st, const char *txid)
{
    size_t i;

    for (i = 0; i < st->nrows; i++) if (strcmp(st->rows[i].txid, txid) == 0) return &st->rows[i].f;
    return NULL;
}

// The store's whole state as text, for comparing two stores.
static void stDump(const Store *st, char *out, size_t cap)
{
    size_t i, k = 0;
    char b[256];
    int w;

    out[0] = '\0';
    for (i = 0; i < st->nrows; i++) {
        w = snprintf(out + k, cap - k, "%s=%s;", st->rows[i].txid, fstr(&st->rows[i].f, b, sizeof(b)));
        if (w < 0 || (size_t)w >= cap - k) return;
        k += (size_t)w;
    }
    {
        // frontiers sorted by start: they are looked up by key, so their order is not state
        size_t order[MAXFR], m = st->nfr, a, b2;
        for (a = 0; a < m; a++) order[a] = a;
        for (a = 0; a < m; a++) for (b2 = a + 1; b2 < m; b2++)
            if (strcmp(st->fr[order[b2]].start, st->fr[order[a]].start) < 0) { size_t t = order[a]; order[a] = order[b2]; order[b2] = t; }
        for (a = 0; a < m; a++) {
            const Front *f = &st->fr[order[a]];
            w = snprintf(out + k, cap - k, "F(%s,%s,%d);", f->start, f->resume, (int)f->hops);
            if (w < 0 || (size_t)w >= cap - k) return;
            k += (size_t)w;
        }
    }
}

// ---- the fake hop source (the Kotlin tests' Counting) ----------------------------------------

#define MAXHOPS 64

typedef struct { char txid[BR_ASSET_TXID_BUFFER]; BRAssetHop hop; } HopRow;

typedef struct {
    HopRow rows[MAXHOPS]; size_t n;
    int fetches;
    Log *log;
} Hops;

static BRAssetHop hopTransfer(const char *parent)
{
    BRAssetHop h;

    memset(&h, 0, sizeof(h));
    h.kind = BR_ASSET_HOP_TRANSFER;
    snprintf(h.parentTxid, sizeof(h.parentTxid), "%s", parent);
    return h;
}

static BRAssetHop hopIssuance(const BRAssetResolvedFacts *f)
{
    BRAssetHop h;

    memset(&h, 0, sizeof(h));
    h.kind = BR_ASSET_HOP_ISSUANCE;
    h.facts = *f;
    return h;
}

static BRAssetHop hopKind(BRAssetHopKind k)
{
    BRAssetHop h;

    memset(&h, 0, sizeof(h));
    h.kind = k;
    return h;
}

static void hopsSet(Hops *hs, const char *txid, BRAssetHop h)
{
    size_t i;

    for (i = 0; i < hs->n; i++) if (strcmp(hs->rows[i].txid, txid) == 0) { hs->rows[i].hop = h; return; }
    if (hs->n < MAXHOPS) { snprintf(hs->rows[hs->n].txid, sizeof(hs->rows[0].txid), "%s", txid); hs->rows[hs->n++].hop = h; }
}

static void hopsRemove(Hops *hs, const char *txid)
{
    size_t i;

    for (i = 0; i < hs->n; i++) if (strcmp(hs->rows[i].txid, txid) == 0) { hs->rows[i] = hs->rows[--hs->n]; return; }
}

static BRAssetHop hopsAsk(Hops *hs, const char *txid)
{
    size_t i;

    hs->fetches++;
    lg(hs->log, "hop(%s);", txid);
    for (i = 0; i < hs->n; i++) if (strcmp(hs->rows[i].txid, txid) == 0) return hs->rows[i].hop;
    return hopKind(BR_ASSET_HOP_UNAVAILABLE);
}

// chain(length): tx0 -> tx1 -> ... -> tx<length>, the issuance
static void chain(Hops *hs, int length, const BRAssetResolvedFacts *f)
{
    char a[16], b[16];
    int i;

    hs->n = 0;
    for (i = 0; i < length; i++) {
        snprintf(a, sizeof(a), "tx%d", i);
        snprintf(b, sizeof(b), "tx%d", i + 1);
        hopsSet(hs, a, hopTransfer(b));
    }
    snprintf(a, sizeof(a), "tx%d", length);
    hopsSet(hs, a, hopIssuance(f));
}

// ---- the walk, through the header: the platform's executor ------------------------------------

typedef BRAssetHop (*HopFn)(void *ctx, const char *txid);

static BRAssetHop hopsFn(void *ctx, const char *txid) { return hopsAsk((Hops *)ctx, txid); }

// Runs one walk the way a platform would: asks the header, does the one thing, feeds the answer back, and
// applies the writes the walk ends with. Returns the final need; *answer is set when DONE with an answer.
static BRAssetWalkNeed hWalk(Store *st, HopFn hop, void *hopCtx, const char *start, int force, int32_t maxHops,
                             int *hasAnswer, BRAssetResolvedFacts *answer)
{
    size_t cap = BR_ASSET_WALK_SLOTS(maxHops), i;
    BRAssetTxidSlot *slots;
    BRAssetWalk w;
    BRAssetWalkFeed feed;
    BRAssetWalkNeed need;
    const char *path[MAXHOPS + 2];

    *hasAnswer = 0;
    if (cap > 4096) cap = 4096;
    slots = (BRAssetTxidSlot *)calloc(cap, sizeof(*slots));
    if (! slots) return BR_ASSET_WALK_REFUSED;
    need = BRAssetWalkBegin(&w, start, force, maxHops, slots, cap);
    while (need == BR_ASSET_WALK_NEEDS_KNOWN || need == BR_ASSET_WALK_NEEDS_FRONTIER || need == BR_ASSET_WALK_NEEDS_HOP) {
        memset(&feed, 0, sizeof(feed));
        if (need == BR_ASSET_WALK_NEEDS_KNOWN) {
            const BRAssetResolvedFacts *f = stAssetFor(st, w.ask);
            if (f) { feed.has = 1; feed.facts = *f; }
        }
        else if (need == BR_ASSET_WALK_NEEDS_FRONTIER) {
            const Front *f = stFrontierFor(st, w.ask);
            if (f) { feed.has = 1; feed.resumeTxid = f->resume; feed.hopsWalked = f->hops; }
        }
        else feed.hop = hop(hopCtx, w.ask);
        need = BRAssetWalkStep(&w, &feed);
    }
    if (need == BR_ASSET_WALK_DONE) {
        if (w.pathCount > 0 && w.pathCount <= N(path)) {
            for (i = 0; i < w.pathCount; i++) path[i] = slots[i].txid;
            stPutAssets(st, path, w.pathCount, &w.answer);
        }
        if (w.frontier == BR_ASSET_FRONTIER_CLEAR) stClearFrontier(st, start);
        else if (w.frontier == BR_ASSET_FRONTIER_PUT) stPutFrontier(st, start, w.frontierResume, w.frontierHops);
        if (w.hasAnswer) { *hasAnswer = 1; *answer = w.answer; }
    }
    free(slots);
    return need;
}

// ---- the walk, as the Kotlin has it (AssetProvenanceWalker.resolve), line for line --------------

#define MSET 128

typedef struct { char v[MSET][BR_ASSET_TXID_BUFFER]; size_t n; } StrSet;   // linkedSetOf / mutableSetOf

static int ssHas(const StrSet *s, const char *x)
{
    size_t i;

    for (i = 0; i < s->n; i++) if (strcmp(s->v[i], x) == 0) return 1;
    return 0;
}

static int ssAdd(StrSet *s, const char *x)   // Set.add: false when present
{
    if (ssHas(s, x)) return 0;
    if (s->n < MSET) snprintf(s->v[s->n++], BR_ASSET_TXID_BUFFER, "%s", x);
    return 1;
}

static int32_t mIntPlus(int32_t a, int32_t b)   // Kotlin Int +
{
    return (int32_t)(uint32_t)((uint32_t)a + (uint32_t)b);
}

static int mResolve(Store *st, HopFn hop, void *hopCtx, const char *startTxid, int forceToIssuance, int32_t maxHopsPerAttempt,
                    BRAssetResolvedFacts *out)
{
    const BRAssetResolvedFacts *known;
    const Front *resumed;
    char current[BR_ASSET_TXID_BUFFER];
    int32_t priorHops, step;
    StrSet proven, seen;
    const char *list[MSET + 1];
    size_t i;

    if (! forceToIssuance) { known = stAssetFor(st, startTxid); if (known) { *out = *known; return 1; } }

    resumed = stFrontierFor(st, startTxid);
    snprintf(current, sizeof(current), "%s", resumed ? resumed->resume : startTxid);
    priorHops = resumed ? resumed->hops : 0;

    proven.n = 0; ssAdd(&proven, startTxid);
    seen.n = 0;

    for (step = 0; step < maxHopsPerAttempt; step++) {
        BRAssetHop step2;

        if (! ssAdd(&seen, current)) {
            stClearFrontier(st, startTxid);
            return 0;
        }

        if (! forceToIssuance) {
            known = stAssetFor(st, current);
            if (known) {
                BRAssetResolvedFacts k = *known;
                for (i = 0; i < proven.n; i++) list[i] = proven.v[i];
                stPutAssets(st, list, proven.n, &k);
                stClearFrontier(st, startTxid);
                *out = k;
                return 1;
            }
        }

        step2 = hop(hopCtx, current);
        switch (step2.kind) {
        case BR_ASSET_HOP_ISSUANCE: {
            StrSet plus = proven;
            ssAdd(&plus, current);
            for (i = 0; i < plus.n; i++) list[i] = plus.v[i];
            stPutAssets(st, list, plus.n, &step2.facts);
            stClearFrontier(st, startTxid);
            *out = step2.facts;
            return 1;
        }
        case BR_ASSET_HOP_TRANSFER:
            ssAdd(&proven, current);
            snprintf(current, sizeof(current), "%s", step2.parentTxid);
            break;
        case BR_ASSET_HOP_UNAVAILABLE:
            stPutFrontier(st, startTxid, current, mIntPlus(priorHops, step));
            return 0;
        default:   // DeadEnd
            stClearFrontier(st, startTxid);
            return 0;
        }
    }

    stPutFrontier(st, startTxid, current, mIntPlus(priorHops, maxHopsPerAttempt));
    return 0;
}

// ---- the walker tests (AssetProvenanceWalkerTest) -------------------------------------------

static BRAssetResolvedFacts FACTS, RULED;

static void initFacts(void)
{
    FACTS = mkFacts("La3t7JdvChangPabloEscobar", 21, 0, "bafkreiexample", 0, 0, 0, 0);
    RULED = mkFacts("La3t7JdvChangPabloEscobar", 21, 0, "bafkreiexample", 1, 4, 1, 1);
}

static void freshWorld(Store *st, Hops *hs)
{
    memset(st, 0, sizeof(*st));
    memset(hs, 0, sizeof(*hs));
}

// one resolve through the header, ckecking it against the model on a copy of the same world
static int resolveBoth(Store *st, Hops *hs, const char *start, int force, int32_t maxHops, BRAssetResolvedFacts *out,
                       const char *what)
{
    static Store mst;
    static Hops mhs;
    static Log l1, l2;
    BRAssetResolvedFacts mout;
    char d1[16384], d2[16384];
    int has = 0, mhas;
    BRAssetWalkNeed need;

    mst = *st; mhs = *hs;
    memset(&l1, 0, sizeof(l1)); memset(&l2, 0, sizeof(l2));
    st->log = &l1; hs->log = &l1; mst.log = &l2; mhs.log = &l2;
    need = hWalk(st, hopsFn, hs, start, force, maxHops, &has, out);
    mhas = mResolve(&mst, hopsFn, &mhs, start, force, maxHops, &mout);
    st->log = NULL; hs->log = NULL;
    stDump(st, d1, sizeof(d1)); stDump(&mst, d2, sizeof(d2));
    if (need != BR_ASSET_WALK_DONE || has != mhas || (has && ! factsEq(out, &mout)) || strcmp(l1.s, l2.s) != 0 ||
        strcmp(d1, d2) != 0 || hs->fetches != mhs.fetches)
        ck(0, "%s: the header's walk is not the Kotlin's (need %d, header \"%s\", model \"%s\")", what, (int)need,
           l1.s, l2.s);
    return has;
}

static void walker_short_chain(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    chain(&hs, 2, &FACTS);
    ck(resolveBoth(&st, &hs, "tx0", 0, 12, &out, "short chain") && factsEq(&out, &FACTS), "resolves a short chain");
}

static void walker_deep_chain(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    int result = 0, attempts = 0;

    freshWorld(&st, &hs);
    chain(&hs, 30, &FACTS);
    while (! result && attempts < 10) {
        result = resolveBoth(&st, &hs, "tx0", 0, 10, &out, "deep chain");
        attempts++;
    }
    ck(result && factsEq(&out, &FACTS), "the deep chain must resolve");
    ck(attempts == 4, "and take the expected number of attempts, not spin (%d, want 4)", attempts);
}

static void walker_resume_no_rewalk(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    int afterFirst, second;

    freshWorld(&st, &hs);
    chain(&hs, 30, &FACTS);
    resolveBoth(&st, &hs, "tx0", 0, 10, &out, "first attempt");
    afterFirst = hs.fetches;
    resolveBoth(&st, &hs, "tx0", 0, 10, &out, "second attempt");
    second = hs.fetches - afterFirst;
    ck(afterFirst == 10, "first attempt spends its whole budget (%d)", afterFirst);
    ck(second == 10, "the second attempt must continue, not restart (%d)", second);
    ck(stFront(&st, "tx0") && strcmp(stFront(&st, "tx0")->resume, "tx20") == 0 && stFront(&st, "tx0")->hops == 20,
       "after two attempts the frontier is tx20, 20 hops walked");
}

static void walker_memory(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    int afterFirst;

    freshWorld(&st, &hs);
    chain(&hs, 3, &FACTS);
    resolveBoth(&st, &hs, "tx0", 0, 12, &out, "first");
    afterFirst = hs.fetches;
    ck(resolveBoth(&st, &hs, "tx0", 0, 12, &out, "again") && factsEq(&out, &FACTS), "a resolved asset is answered from memory");
    ck(afterFirst == hs.fetches, "a repeat resolve must touch the network zero times (%d -> %d)", afterFirst, hs.fetches);
    ck(stRow(&st, "tx0") && stRow(&st, "tx1") && stRow(&st, "tx2") && stRow(&st, "tx3"),
       "the whole path, the issuance included, is memoised");
}

static void walker_known_ancestor(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    chain(&hs, 30, &FACTS);
    stSet(&st, "tx1", &FACTS);
    ck(resolveBoth(&st, &hs, "tx0", 0, 10, &out, "ancestor") && factsEq(&out, &FACTS), "a known ancestor answers");
    ck(hs.fetches == 1, "one hop to reach the ancestor we already knew (%d)", hs.fetches);
}

static void walker_transient_keeps_frontier(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    const Front *f;

    freshWorld(&st, &hs);
    chain(&hs, 30, &FACTS);
    hopsSet(&hs, "tx5", hopKind(BR_ASSET_HOP_UNAVAILABLE));
    ck(! resolveBoth(&st, &hs, "tx0", 0, 10, &out, "transient"), "a transient failure answers nothing");
    f = stFront(&st, "tx0");
    ck(f != NULL, "a transient failure must leave a resume point");
    ck(f && strcmp(f->resume, "tx5") == 0, "resuming at the tx that was unreachable (%s)", f ? f->resume : "none");
    ck(f && f->hops == 5, "having walked 5 hops (%d)", f ? (int)f->hops : -1);
}

static void walker_dead_end_clears(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    chain(&hs, 30, &FACTS);
    hopsSet(&hs, "tx5", hopKind(BR_ASSET_HOP_DEAD_END));
    stPutFrontier(&st, "tx0", "tx0", 0);
    ck(! resolveBoth(&st, &hs, "tx0", 0, 10, &out, "dead end"), "a dead end answers nothing");
    ck(stFront(&st, "tx0") == NULL, "a terminal result must not leave a resume point");
}

static void walker_cycle(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    hopsSet(&hs, "tx0", hopTransfer("tx1"));
    hopsSet(&hs, "tx1", hopTransfer("tx0"));
    stPutFrontier(&st, "tx0", "tx0", 0);
    ck(! resolveBoth(&st, &hs, "tx0", 0, 50, &out, "cycle"), "a cycle answers nothing");
    ck(hs.fetches == 2, "stops at the repeat rather than burning the budget (%d)", hs.fetches);
    ck(stFront(&st, "tx0") == NULL, "a cycle leaves no resume point");
}

static void walker_default_mode_cache(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    stSet(&st, "tx0", &FACTS);
    chain(&hs, 3, &FACTS);
    ck(resolveBoth(&st, &hs, "tx0", 0, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &out, "default") && factsEq(&out, &FACTS),
       "default mode answers from the cache");
    ck(hs.fetches == 0, "without fetching (%d)", hs.fetches);
}

static void walker_force_past_start(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    const BRAssetResolvedFacts *r;

    freshWorld(&st, &hs);
    stSet(&st, "tx0", &FACTS);
    chain(&hs, 3, &FACTS);
    hopsSet(&hs, "tx3", hopIssuance(&RULED));
    ck(resolveBoth(&st, &hs, "tx0", 1, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &out, "force") && factsEq(&out, &RULED),
       "forceToIssuance walks past a cached start txid to the issuance");
    ck(hs.fetches == 4, "tx0, tx1, tx2, tx3 (%d)", hs.fetches);
    ck(stRow(&st, "tx0") && stRow(&st, "tx0")->hasIssuanceOpcode && stRow(&st, "tx0")->issuanceOpcode == 4, "tx0 learns opcode 4");
    ck(stRow(&st, "tx3") && stRow(&st, "tx3")->hasIssuanceOpcode && stRow(&st, "tx3")->issuanceOpcode == 4, "tx3 has opcode 4");
    r = stIssuanceFactsFor(&st, FACTS.assetId, 1);
    ck(r && factsEq(r, &RULED), "issuanceFactsFor answers the ruled facts");
}

static void walker_force_past_ancestor(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;

    freshWorld(&st, &hs);
    stSet(&st, "tx2", &FACTS);
    chain(&hs, 3, &FACTS);
    hopsSet(&hs, "tx3", hopIssuance(&RULED));
    ck(resolveBoth(&st, &hs, "tx0", 1, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &out, "force ancestor") && factsEq(&out, &RULED),
       "forceToIssuance walks past a cached ancestor too");
    ck(hs.fetches == 4, "four fetches (%d)", hs.fetches);
    ck(stRow(&st, "tx2") && stRow(&st, "tx2")->hasIssuanceOpcode && stRow(&st, "tx2")->issuanceOpcode == 4, "tx2 learns opcode 4");
}

static void walker_force_unreachable(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    const Front *f;

    freshWorld(&st, &hs);
    stSet(&st, "tx0", &FACTS);
    chain(&hs, 3, &FACTS);
    hopsRemove(&hs, "tx2");
    ck(! resolveBoth(&st, &hs, "tx0", 1, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &out, "force unreachable"),
       "forceToIssuance still returns null when the chain is unreachable");
    f = stFront(&st, "tx0");
    ck(f && strcmp(f->resume, "tx2") == 0, "and keeps a frontier at tx2 (%s)", f ? f->resume : "none");
    ck(stIssuanceFactsFor(&st, FACTS.assetId, 1) == NULL, "issuanceFactsFor still answers nothing");
}

static void walker_issuance_rows(void)
{
    Store st; Hops hs;

    freshWorld(&st, &hs);
    stSet(&st, "tx0", &FACTS);
    ck(stIssuanceFactsFor(&st, FACTS.assetId, 1) == NULL, "issuanceFactsFor ignores rows that carry no opcode");
    stSet(&st, "tx9", &RULED);
    ck(stIssuanceFactsFor(&st, FACTS.assetId, 1) && factsEq(stIssuanceFactsFor(&st, FACTS.assetId, 1), &RULED),
       "and answers the row that does");
    ck(! BRAssetIssuanceFactsMatch(&RULED, "LaOther"), "a row naming another asset does not answer");
    ck(! BRAssetIssuanceFactsMatch(NULL, FACTS.assetId) && ! BRAssetIssuanceFactsMatch(&RULED, NULL), "NULL arguments do not match");

    // ids that share a prefix: the whole id decides
    ck(idFixturesHold(), "the id fixtures are full-length ids that share all but their last character");
    {
        static Store s2;
        size_t i;

        for (i = 0; i < N(ID_PAIRS); i++) {
            BRAssetResolvedFacts held = mkFacts(ID_PAIRS[i][0], 1, 0, NULL, 1, 1, 1, 1);

            ck(BRAssetIssuanceFactsMatch(&held, ID_PAIRS[i][0]) && ! BRAssetIssuanceFactsMatch(&held, ID_PAIRS[i][1]),
               "a row naming %s answers for it and not for %s", ID_PAIRS[i][0], ID_PAIRS[i][1]);
            memset(&s2, 0, sizeof(s2));
            stSet(&s2, "tx0", &held);
            ck(stIssuanceFactsFor(&s2, ID_PAIRS[i][1], 1) == NULL && stIssuanceFactsFor(&s2, ID_PAIRS[i][1], 0) == NULL &&
               stIssuanceFactsFor(&s2, ID_PAIRS[i][0], 1) == &s2.rows[0].f, "and the store answers %s with nothing", ID_PAIRS[i][1]);
        }
    }

    // a row whose id does not end in its buffer is never read past it: the whole row, and the id asked
    // about, are 'L' bytes (so the opcode is nonzero), and the row is exactly its own size on the heap,
    // where ASan sees a read past it
    {
        BRAssetResolvedFacts *open = malloc(sizeof(*open));
        char *asked = malloc(201);

        if (! open || ! asked) ck(0, "allocation");
        else {
            memset(open, 'L', sizeof(*open));
            memset(asked, 'L', 200);
            asked[200] = '\0';
            ck(open->hasIssuanceOpcode != 0 && ! BRAssetIssuanceFactsMatch(open, asked),
               "facts whose id does not end in its buffer match nothing, and are not read past");
        }
        free(open);
        free(asked);
    }
}

// ---- real transactions: a builder ------------------------------------------------------------

#define TXCAP 4096

typedef struct { uint8_t b[TXCAP]; size_t n; int bad; } Buf;

static void put8(Buf *o, uint8_t v) { if (o->n < TXCAP) o->b[o->n++] = v; else o->bad = 1; }
static void putLE32(Buf *o, uint32_t v) { int i; for (i = 0; i < 4; i++) put8(o, (uint8_t)(v >> (8 * i))); }
static void putBytes(Buf *o, const uint8_t *p, size_t n) { size_t i; for (i = 0; i < n; i++) put8(o, p[i]); }
static void putVar(Buf *o, uint64_t v)
{
    if (v < 0xfd) put8(o, (uint8_t)v);
    else if (v <= 0xffff) { put8(o, 0xfd); put8(o, (uint8_t)v); put8(o, (uint8_t)(v >> 8)); }
    else { put8(o, 0xfe); putLE32(o, (uint32_t)v); }
}

static int hexDigit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static size_t hx(const char *hex, uint8_t *out, size_t cap)
{
    size_t n = 0;
    int hi = -1;

    for (; *hex; hex++) {
        int d = hexDigit(*hex);
        if (*hex == ' ') continue;
        if (d < 0) { ck(0, "bad hex vector"); return 0; }
        if (hi < 0) hi = d;
        else { if (n >= cap) { ck(0, "hex vector too long"); return 0; } out[n++] = (uint8_t)(hi * 16 + d); hi = -1; }
    }
    if (hi >= 0) ck(0, "odd hex vector");
    return n;
}

typedef struct {
    uint8_t prev[32];      // internal byte order
    uint32_t vout;
    uint8_t sig[8]; size_t sigLen;
    uint8_t wit[8]; size_t witLen; int witItems;
} TIn;

typedef struct { uint64_t amount; uint8_t script[300]; size_t len; } TOut;

static void txBuild(Buf *o, const TIn *ins, size_t nIn, const TOut *outs, size_t nOut, uint32_t lock, int witness)
{
    size_t i;

    memset(o, 0, sizeof(*o));
    putLE32(o, 1);
    if (witness) { put8(o, 0x00); put8(o, 0x01); }
    putVar(o, nIn);
    for (i = 0; i < nIn; i++) {
        putBytes(o, ins[i].prev, 32);
        putLE32(o, ins[i].vout);
        putVar(o, ins[i].sigLen);
        putBytes(o, ins[i].sig, ins[i].sigLen);
        putLE32(o, 0xffffffffu);
    }
    putVar(o, nOut);
    for (i = 0; i < nOut; i++) {
        int k;
        for (k = 0; k < 8; k++) put8(o, (uint8_t)(outs[i].amount >> (8 * k)));
        putVar(o, outs[i].len);
        putBytes(o, outs[i].script, outs[i].len);
    }
    if (witness) {
        for (i = 0; i < nIn; i++) {
            if (ins[i].witItems == 0) put8(o, 0);
            else { put8(o, 1); putVar(o, ins[i].witLen); putBytes(o, ins[i].wit, ins[i].witLen); }
        }
    }
    putLE32(o, lock);
}

static void displayToInternal(const char *hex, uint8_t out[32])
{
    UInt256 u;

    if (! BRRawTxIdFromDisplayHex(hex, strlen(hex), &u)) { ck(0, "not a display txid: %s", hex); memset(out, 0, 32); return; }
    memcpy(out, u.u8, 32);
}

static void idHex(const UInt256 *u, char out[65])
{
    static const char d[] = "0123456789abcdef";
    int i;

    for (i = 0; i < 32; i++) { out[2 * i] = d[u->u8[31 - i] >> 4]; out[2 * i + 1] = d[u->u8[31 - i] & 15]; }
    out[64] = '\0';
}

// The id of `b` as display hex: 1, or 0 when BRRawTxId reports none (the bytes are not exactly a tx).
static int txId(const Buf *b, char out[65])
{
    UInt256 u;

    if (! BRRawTxId(b->b, b->n, &u)) { out[0] = '\0'; return 0; }
    idHex(&u, out);
    return 1;
}

static TOut outOf(const char *scriptHex)
{
    TOut t;

    memset(&t, 0, sizeof(t));
    t.len = hx(scriptHex, t.script, sizeof(t.script));
    return t;
}

// one input spending prevHex:vout, one output: the script
static void simpleTx(Buf *o, const char *prevHex, uint32_t vout, const char *scriptHex, uint32_t tag)
{
    TIn in;
    TOut out = outOf(scriptHex);

    memset(&in, 0, sizeof(in));
    displayToInternal(prevHex, in.prev);
    in.vout = vout;
    txBuild(o, &in, 1, &out, 1, tag, 0);
}

// The header's hop on bytes: the core's parser, then BRAssetHopClassify.
static BRAssetHop hHopOfBytes(const uint8_t *raw, size_t len)
{
    BRTransaction *tx = raw ? BRTransactionParseSigned(raw, len) : NULL;
    BRAssetHop h = BRAssetHopClassify(raw != NULL, tx);

    if (tx) BRTransactionFree(tx);
    return h;
}

// ---- the hop, as the Kotlin has it (classifyProvenanceHopFrom), line for line --------------------

// NativeBridge.getOpReturnData (jni_asset.c): the first output whose script starts 0x6A, from
// BRTransactionParseSigned; NULL for no bytes, an unparseable tx or no such output.
static int mOpReturnOf(const uint8_t *rawTx, size_t txLen, uint8_t *out, size_t cap, size_t *outLen)
{
    BRTransaction *tx;
    size_t i;
    int found = 0;

    if (! rawTx) return 0;
    if (txLen <= 0) return 0;
    tx = BRTransactionParseSigned(rawTx, txLen);
    if (! tx) return 0;
    for (i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];
        if (o->scriptLen > 0 && o->script != NULL && o->script[0] == 0x6A) {
            if (o->scriptLen <= cap) { memcpy(out, o->script, o->scriptLen); *outLen = o->scriptLen; found = 1; }
            break;
        }
    }
    BRTransactionFree(tx);
    return found;
}

typedef struct { uint64_t value; size_t nextOffset; } MVarInt;

static int mReadVarInt(const uint8_t *data, size_t size, size_t offset, MVarInt *v)
{
    int b;

    if (offset >= size) return 0;
    b = data[offset] & 0xFF;
    if (b < 0xFD) { v->value = (uint64_t)b; v->nextOffset = offset + 1; return 1; }
    if (b == 0xFD) {
        if (offset + 3 <= size) { v->value = (uint64_t)(data[offset + 1] | (data[offset + 2] << 8)); v->nextOffset = offset + 3; return 1; }
        return 0;
    }
    if (b == 0xFE) {
        if (offset + 5 <= size) {
            v->value = (uint64_t)data[offset + 1] | ((uint64_t)data[offset + 2] << 8) | ((uint64_t)data[offset + 3] << 16) |
                       ((uint64_t)data[offset + 4] << 24);
            v->nextOffset = offset + 5;
            return 1;
        }
        return 0;
    }
    return 0;   // 0xFF
}

// firstInputOfRawTx
static int mFirstInput(const uint8_t *rawTx, size_t size, char prevTxidHex[65], int32_t *prevVout)
{
    static const char d[] = "0123456789abcdef";
    size_t p;
    MVarInt inputCount;
    int i;

    if (size < 4 + 1 + 32 + 4) return 0;
    p = 4;
    if (size > 5 && rawTx[p] == 0x00 && rawTx[p + 1] == 0x01) p += 2;
    if (! mReadVarInt(rawTx, size, p, &inputCount)) return 0;
    p = inputCount.nextOffset;
    if (inputCount.value < 1) return 0;
    if (p + 36 > size) return 0;
    *prevVout = (int32_t)((uint32_t)rawTx[p + 32] | ((uint32_t)rawTx[p + 33] << 8) | ((uint32_t)rawTx[p + 34] << 16) |
                          ((uint32_t)rawTx[p + 35] << 24));
    for (i = 31; i >= 0; i--) { prevTxidHex[2 * (31 - i)] = d[rawTx[p + i] >> 4]; prevTxidHex[2 * (31 - i) + 1] = d[rawTx[p + i] & 15]; }
    prevTxidHex[64] = '\0';
    return 1;
}

static BRAssetHop mHop(const uint8_t *rawTx, size_t len)
{
    uint8_t opReturn[TXCAP];
    size_t opLen = 0;
    BRAssetHeader header;
    char prev[65];
    int32_t vout = 0;
    BRAssetHop h;

    if (! rawTx) return hopKind(BR_ASSET_HOP_UNAVAILABLE);
    if (! mOpReturnOf(rawTx, len, opReturn, sizeof(opReturn), &opLen)) return hopKind(BR_ASSET_HOP_DEAD_END);
    // decoder.decode: null (NOT_ASSET) and UNCLASSIFIABLE are both a dead end
    if (BRAssetDecodeHeader(opReturn, opLen, &header) != BR_ASSET_SCRIPT_ASSET) return hopKind(BR_ASSET_HOP_DEAD_END);

    switch (header.operation) {
    case DA_ISSUANCE: {
        int aggregationCode;
        if (! header.locked) return hopKind(BR_ASSET_HOP_DEAD_END);
        if (! mFirstInput(rawTx, len, prev, &vout)) return hopKind(BR_ASSET_HOP_DEAD_END);
        aggregationCode = header.aggregation == BR_ASSET_AGGREGATABLE ? 0 : header.aggregation == BR_ASSET_HYBRID ? 1 : 2;
        h = hopKind(BR_ASSET_HOP_ISSUANCE);
        // deriveIssuanceAssetId(..., locked = true, ...): the bridge's derivation, BRAssetIdForIssuance (asset_allocate_kat)
        if (! BRAssetIdForIssuance(prev, vout, 1, (BRAssetAggregation)aggregationCode, header.divisibility,
                                   h.facts.assetId, sizeof(h.facts.assetId)))
            return hopKind(BR_ASSET_HOP_DEAD_END);
        h.facts.totalSupply = header.hasTotalQuantity ? header.totalQuantity : 0;
        h.facts.divisibility = header.divisibility;
        // header.metadataCid (asset_decode_kat holds BRAssetMetadataCid to it)
        h.facts.hasMetadataCid = BRAssetMetadataCid(&header, h.facts.metadataCid, sizeof(h.facts.metadataCid)) != 0;
        h.facts.hasIssuanceOpcode = 1; h.facts.issuanceOpcode = header.opcode;
        h.facts.hasIssuanceLocked = 1; h.facts.issuanceLocked = header.locked ? 1 : 0;
        return h;
    }
    case DA_TRANSFER:
        if (! mFirstInput(rawTx, len, prev, &vout)) return hopKind(BR_ASSET_HOP_DEAD_END);
        return hopTransfer(prev);
    case DA_BURN:
        return hopKind(BR_ASSET_HOP_DEAD_END);
    default:
        return hopKind(BR_ASSET_HOP_DEAD_END);
    }
}

static int hopEq(const BRAssetHop *a, const BRAssetHop *b)
{
    if (a->kind != b->kind) return 0;
    if (a->kind == BR_ASSET_HOP_TRANSFER) return strcmp(a->parentTxid, b->parentTxid) == 0;
    if (a->kind == BR_ASSET_HOP_ISSUANCE) return factsEq(&a->facts, &b->facts);
    return 1;
}

static const char *hopStr(const BRAssetHop *h, char *buf, size_t cap)
{
    char f[256];

    switch (h->kind) {
    case BR_ASSET_HOP_UNAVAILABLE: snprintf(buf, cap, "Unavailable"); break;
    case BR_ASSET_HOP_DEAD_END: snprintf(buf, cap, "DeadEnd"); break;
    case BR_ASSET_HOP_TRANSFER: snprintf(buf, cap, "Transfer(%s)", h->parentTxid); break;
    case BR_ASSET_HOP_ISSUANCE: snprintf(buf, cap, "Issuance%s", fstr(&h->facts, f, sizeof(f))); break;
    default: snprintf(buf, cap, "kind %d", (int)h->kind); break;
    }
    return buf;
}

// ---- carrier scripts --------------------------------------------------------------------------

// Locked issuance, 10 units, divisibility 0 (AssetProvenanceTxidBindingTest)
#define ISSUE_LOCKED     "6a06444103050a10"
// Transfer, one instruction: 10 units to output 0 (AssetProvenanceTxidBindingTest)
#define TRANSFER_SCRIPT  "6a0644410115000a"
#define ISSUE_UNLOCKED   "6a06444103050a00"
#define ISSUE_HYBRID     "6a06444103050a14"
#define ISSUE_DISPERSED  "6a06444103050a18"
#define ISSUE_AGG3       "6a06444103050a1c"
#define ISSUE_DIV1       "6a06444103050a30"
#define ISSUE_DIV7       "6a06444103050af0"
#define HASH32           "1111111111111111111111111111111111111111111111111111111111111111"
#define ZERO32           "0000000000000000000000000000000000000000000000000000000000000000"
#define ISSUE_OP1_CID    "6a26444103" "01" HASH32 "0a10"
#define ISSUE_OP1_ZERO   "6a26444103" "01" ZERO32 "0a10"
#define ISSUE_OP3_RULES  "6a26444103" "03" HASH32 "0a10"
#define ISSUE_OP4_RULES  "6a26444103" "04" HASH32 "0a10"
#define BURN_SCRIPT      "6a0644410325000a"
#define UNCLASSIFIABLE   "6a03444103"
#define PLAIN_OPRETURN   "6a0401020304"
#define P2PKH            "76a914" "2222222222222222222222222222222222222222" "88ac"

static void hop_carriers(void)
{
    static const struct { const char *script; BRAssetScriptKind kind; BRAssetOperation op; int locked; } want[] = {
        { ISSUE_LOCKED, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 }, { TRANSFER_SCRIPT, BR_ASSET_SCRIPT_ASSET, DA_TRANSFER, 0 },
        { ISSUE_UNLOCKED, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 0 }, { ISSUE_HYBRID, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 },
        { ISSUE_DISPERSED, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 }, { ISSUE_AGG3, BR_ASSET_SCRIPT_UNCLASSIFIABLE, DA_UNDEFINED, 0 },
        { ISSUE_DIV1, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 }, { ISSUE_DIV7, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 },
        { ISSUE_OP1_CID, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 }, { ISSUE_OP3_RULES, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 },
        { ISSUE_OP4_RULES, BR_ASSET_SCRIPT_ASSET, DA_ISSUANCE, 1 }, { BURN_SCRIPT, BR_ASSET_SCRIPT_ASSET, DA_BURN, 0 },
        { UNCLASSIFIABLE, BR_ASSET_SCRIPT_UNCLASSIFIABLE, DA_UNDEFINED, 0 }, { PLAIN_OPRETURN, BR_ASSET_SCRIPT_NOT_ASSET, DA_UNDEFINED, 0 },
        { P2PKH, BR_ASSET_SCRIPT_NOT_ASSET, DA_UNDEFINED, 0 },
    };
    size_t i;

    // the fixtures this suite builds transactions from decode as it says (BRAssetDecode.h, asset_decode_kat)
    for (i = 0; i < N(want); i++) {
        uint8_t s[300];
        size_t n = hx(want[i].script, s, sizeof(s));
        BRAssetHeader h;
        BRAssetScriptKind k = BRAssetDecodeHeader(s, n, &h);
        ck(k == want[i].kind && h.operation == want[i].op && (k != BR_ASSET_SCRIPT_ASSET || h.locked == want[i].locked),
           "fixture %s decodes as kind %d op %d locked %d", want[i].script, (int)want[i].kind, (int)want[i].op, want[i].locked);
    }
}

// The hop on one built transaction, by the header and by the model, which must agree; returns the header's.
static BRAssetHop hopBoth(const Buf *b, const char *what)
{
    char x[300], y[300];
    BRAssetHop h = hHopOfBytes(b->b, b->n), m = mHop(b->b, b->n);

    if (! hopEq(&h, &m)) ck(0, "%s: header %s, Kotlin %s", what, hopStr(&h, x, sizeof(x)), hopStr(&m, y, sizeof(y)));
    return h;
}

static void hop_rules(void)
{
    static const char *P = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    Buf b;
    BRAssetHop h;
    char id[BR_ASSET_ID_BUFFER];
    TIn ins[2];
    TOut outs[3];

    h = BRAssetHopClassify(0, NULL);
    ck(h.kind == BR_ASSET_HOP_UNAVAILABLE, "no bytes is UNAVAILABLE: the walk keeps its progress");
    h = mHop(NULL, 0);
    ck(h.kind == BR_ASSET_HOP_UNAVAILABLE, "(the Kotlin agrees)");
    h = BRAssetHopClassify(1, NULL);
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "bytes that do not parse are a DEAD END");
    { uint8_t junk[3] = { 1, 2, 3 }; BRAssetHop m = mHop(junk, 3); ck(m.kind == BR_ASSET_HOP_DEAD_END, "(the Kotlin agrees)"); }

    simpleTx(&b, P, 1, ISSUE_LOCKED, 2);
    h = hopBoth(&b, "locked issuance");
    ck(h.parentTxid[0] == '\0', "an ISSUANCE hop leaves parentTxid empty");
    BRAssetIdForIssuance(P, 1, 1, BR_ASSET_AGGREGATABLE, 0, id, sizeof(id));
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && strcmp(h.facts.assetId, id) == 0 && h.facts.totalSupply == 10 &&
       h.facts.divisibility == 0 && ! h.facts.hasMetadataCid && h.facts.hasIssuanceOpcode && h.facts.issuanceOpcode == 5 &&
       h.facts.hasIssuanceLocked && h.facts.issuanceLocked == 1,
       "a locked issuance is ISSUANCE, named from its first input (%s)", h.facts.assetId);

    simpleTx(&b, P, 1, ISSUE_UNLOCKED, 2);
    h = hopBoth(&b, "unlocked issuance");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "an unlocked issuance is a DEAD END");

    simpleTx(&b, P, 1, ISSUE_HYBRID, 2);
    h = hopBoth(&b, "hybrid");
    BRAssetIdForIssuance(P, 1, 1, BR_ASSET_HYBRID, 0, id, sizeof(id));
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && strcmp(h.facts.assetId, id) == 0 && h.facts.assetId[1] == 'h', "a hybrid issuance is named Lh (%s)", h.facts.assetId);
    simpleTx(&b, P, 1, ISSUE_DISPERSED, 2);
    h = hopBoth(&b, "dispersed");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && h.facts.assetId[1] == 'd', "a dispersed issuance is named Ld (%s)", h.facts.assetId);
    simpleTx(&b, P, 1, ISSUE_DIV7, 2);
    h = hopBoth(&b, "div 7");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && h.facts.divisibility == 7, "divisibility 7 is carried");
    simpleTx(&b, P, 1, ISSUE_AGG3, 2);
    h = hopBoth(&b, "agg 3");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "an issuance the decoder cannot classify is a DEAD END");

    simpleTx(&b, P, 1, ISSUE_OP1_CID, 2);
    h = hopBoth(&b, "op1 cid");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && h.facts.hasMetadataCid && strlen(h.facts.metadataCid) == BR_ASSET_CID_LENGTH &&
       h.facts.metadataCid[0] == 'b' && h.facts.issuanceOpcode == 1, "an opcode-1 issuance carries its metadata CID (%s)", h.facts.metadataCid);
    simpleTx(&b, P, 1, ISSUE_OP1_ZERO, 2);
    h = hopBoth(&b, "op1 zero hash");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && ! h.facts.hasMetadataCid, "a zero hash has no CID");
    simpleTx(&b, P, 1, ISSUE_OP3_RULES, 2);
    h = hopBoth(&b, "op3");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && h.facts.issuanceOpcode == 3, "an issuance with rules is named, opcode 3 recorded");
    simpleTx(&b, P, 1, ISSUE_OP4_RULES, 2);
    h = hopBoth(&b, "op4");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE && h.facts.issuanceOpcode == 4, "opcode 4 recorded");

    simpleTx(&b, P, 7, TRANSFER_SCRIPT, 2);
    h = hopBoth(&b, "transfer");
    ck(h.kind == BR_ASSET_HOP_TRANSFER && strcmp(h.parentTxid, P) == 0, "a transfer walks to its first input's transaction");
    ck(h.facts.assetId[0] == '\0' && h.facts.totalSupply == 0 && ! h.facts.hasIssuanceOpcode, "a TRANSFER hop leaves the facts zero");
    simpleTx(&b, "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789", 0, TRANSFER_SCRIPT, 2);
    h = hopBoth(&b, "transfer case");
    ck(h.kind == BR_ASSET_HOP_TRANSFER && strcmp(h.parentTxid, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789") == 0,
       "the parent txid is display-order lower-case hex");
    simpleTx(&b, P, 0, BURN_SCRIPT, 2);
    h = hopBoth(&b, "burn");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "a burn is a DEAD END");
    ck(h.parentTxid[0] == '\0' && h.facts.assetId[0] == '\0', "a DEAD END hop is zero beyond its kind");
    simpleTx(&b, P, 0, UNCLASSIFIABLE, 2);
    h = hopBoth(&b, "unclassifiable");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "a carrier that cannot be classified is a DEAD END");
    simpleTx(&b, P, 0, PLAIN_OPRETURN, 2);
    h = hopBoth(&b, "plain");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "an OP_RETURN that is not a carrier is a DEAD END");
    simpleTx(&b, P, 0, P2PKH, 2);
    h = hopBoth(&b, "no op_return");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "no OP_RETURN is a DEAD END");

    // the FIRST OP_RETURN decides: a plain one before a carrier
    memset(ins, 0, sizeof(ins));
    displayToInternal(P, ins[0].prev);
    ins[0].vout = 1;
    outs[0] = outOf(P2PKH); outs[1] = outOf(PLAIN_OPRETURN); outs[2] = outOf(ISSUE_LOCKED);
    txBuild(&b, ins, 1, outs, 3, 0, 0);
    h = hopBoth(&b, "plain first");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "the first OP_RETURN output is the one decoded: a plain one before a carrier");
    outs[1] = outOf(ISSUE_LOCKED); outs[2] = outOf(PLAIN_OPRETURN);
    txBuild(&b, ins, 1, outs, 3, 0, 0);
    h = hopBoth(&b, "carrier first");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE, "a carrier before a plain OP_RETURN decides");
    outs[0] = outOf(""); outs[1] = outOf(TRANSFER_SCRIPT);
    txBuild(&b, ins, 1, outs, 2, 0, 0);
    h = hopBoth(&b, "empty script first");
    ck(h.kind == BR_ASSET_HOP_TRANSFER, "an empty script is no OP_RETURN");

    // the first input decides, of two
    displayToInternal("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", ins[1].prev);
    ins[1].vout = 3;
    outs[0] = outOf(TRANSFER_SCRIPT);
    txBuild(&b, ins, 2, outs, 1, 0, 0);
    h = hopBoth(&b, "two inputs");
    ck(h.kind == BR_ASSET_HOP_TRANSFER && strcmp(h.parentTxid, P) == 0, "the FIRST input names the parent");

    // a witness transaction
    ins[0].witItems = 1; ins[0].witLen = 2; ins[0].wit[0] = 0xab; ins[0].wit[1] = 0xcd;
    txBuild(&b, ins, 2, outs, 1, 0, 1);
    {
        char tid[65];
        ck(txId(&b, tid), "the witness fixture is exactly a transaction");
    }
    h = hopBoth(&b, "witness");
    ck(h.kind == BR_ASSET_HOP_TRANSFER && strcmp(h.parentTxid, P) == 0, "a witness transaction's first input names the parent");
}

static void hop_edges(void)
{
    static const char *P = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    Buf b;
    BRAssetHop h;
    BRTransaction tx;
    BRTxOutput outs[2];
    BRTxInput in;
    uint8_t s[16];
    size_t n;

    // output index at the int32 edges: read signed, so 0x80000000 and up are refused by the id
    simpleTx(&b, P, 0x7fffffffu, ISSUE_LOCKED, 2);
    h = hopBoth(&b, "vout INT32_MAX");
    ck(h.kind == BR_ASSET_HOP_ISSUANCE, "an issuance whose first input spends output INT32_MAX is named");
    simpleTx(&b, P, 0x80000000u, ISSUE_LOCKED, 2);
    h = hopBoth(&b, "vout 2^31");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "output 2^31 reads as a negative index: the id is refused, a DEAD END");
    simpleTx(&b, P, 0xffffffffu, ISSUE_LOCKED, 2);
    h = hopBoth(&b, "vout UINT32_MAX");
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "output 0xffffffff likewise");
    simpleTx(&b, P, 0xffffffffu, TRANSFER_SCRIPT, 2);
    h = hopBoth(&b, "transfer vout UINT32_MAX");
    ck(h.kind == BR_ASSET_HOP_TRANSFER, "a transfer's parent index is not read");

    // hand-built transactions the parser never makes
    memset(&tx, 0, sizeof(tx));
    memset(outs, 0, sizeof(outs));
    memset(&in, 0, sizeof(in));
    n = hx(TRANSFER_SCRIPT, s, sizeof(s));
    tx.outputs = outs; tx.outCount = 2;
    outs[0].script = NULL; outs[0].scriptLen = 4;                // a length without a script: skipped
    outs[1].script = s; outs[1].scriptLen = n;
    tx.inputs = NULL; tx.inCount = 1;
    h = BRAssetHopClassify(1, &tx);
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "no input array is a DEAD END");
    tx.inputs = &in; tx.inCount = 0;
    h = BRAssetHopClassify(1, &tx);
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "no input is a DEAD END");
    tx.inCount = 1;
    h = BRAssetHopClassify(1, &tx);
    ck(h.kind == BR_ASSET_HOP_TRANSFER && strcmp(h.parentTxid, "0000000000000000000000000000000000000000000000000000000000000000") == 0,
       "a script pointer of NULL is skipped; the next OP_RETURN decides");
    {
        // a zero-length script whose pointer is not NULL is no OP_RETURN, whatever byte it points at
        static const uint8_t opret[1] = { 0x6a };
        outs[0].script = (uint8_t *)opret; outs[0].scriptLen = 0;
        h = BRAssetHopClassify(1, &tx);
        ck(h.kind == BR_ASSET_HOP_TRANSFER, "a zero-length script is skipped; the next OP_RETURN decides");
        outs[0].script = NULL; outs[0].scriptLen = 4;
    }
    {
        // an issuance with no first input, built by hand (the parser makes none)
        uint8_t is[16];
        size_t isn = hx(ISSUE_LOCKED, is, sizeof(is));
        outs[1].script = is; outs[1].scriptLen = isn;
        tx.inputs = NULL; tx.inCount = 1;
        h = BRAssetHopClassify(1, &tx);
        ck(h.kind == BR_ASSET_HOP_DEAD_END, "an issuance with no input array is a DEAD END");
        tx.inputs = &in; tx.inCount = 0;
        h = BRAssetHopClassify(1, &tx);
        ck(h.kind == BR_ASSET_HOP_DEAD_END, "an issuance with no input is a DEAD END");
        tx.inCount = 1;
        h = BRAssetHopClassify(1, &tx);
        ck(h.kind == BR_ASSET_HOP_ISSUANCE, "with one, it is named");
        outs[1].script = s; outs[1].scriptLen = n;
    }
    tx.outputs = NULL;
    h = BRAssetHopClassify(1, &tx);
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "no output array is a DEAD END");
    tx.outputs = outs; tx.outCount = 0;
    h = BRAssetHopClassify(1, &tx);
    ck(h.kind == BR_ASSET_HOP_DEAD_END, "no output is a DEAD END");

    // a transaction with no outputs, built and parsed
    {
        TIn ins[1];
        memset(ins, 0, sizeof(ins));
        txBuild(&b, ins, 1, NULL, 0, 0, 0);
        h = hopBoth(&b, "no outputs");
        ck(h.kind == BR_ASSET_HOP_DEAD_END, "a parsed transaction without outputs is a DEAD END");
    }
    // empty bytes
    {
        uint8_t z[1] = { 0 };
        BRAssetHop m = mHop(z, 0);
        h = hHopOfBytes(z, 0);
        ck(h.kind == BR_ASSET_HOP_DEAD_END && m.kind == BR_ASSET_HOP_DEAD_END, "an empty body is a DEAD END");
    }
}

// ---- the binding tests (AssetProvenanceTxidBindingTest) ---------------------------------------

// An endpoint: answers `bytes` when asked for `forId` (or for any id when forId is NULL), else nothing.
typedef struct { const char *forId; const Buf *bytes; int asked; char askedIds[8][65]; } Endpoint;

typedef struct {
    Endpoint *eps; size_t nEps;
    const char *walletId; const Buf *walletBytes;
    const Buf *parsedBufs[8];   // each byte string the hop classified, in order
    size_t nParsed;
} Transport;

// fetchRawTransactionBytesFrom + the multi-endpoint client's id check: the wallet's own copy first; an
// endpoint's answer only when BRRawTxId says its id is the one asked for, compared by value.
static const Buf *fetchBound(Transport *t, const char *txid)
{
    size_t i;
    UInt256 want, got;

    if (t->walletId && strcmp(txid, t->walletId) == 0) return t->walletBytes;
    if (! BRRawTxIdFromDisplayHex(txid, strlen(txid), &want)) return NULL;
    for (i = 0; i < t->nEps; i++) {
        Endpoint *e = &t->eps[i];
        const Buf *ans;
        if (e->asked < 8) snprintf(e->askedIds[e->asked], 65, "%s", txid);
        e->asked++;
        ans = (! e->forId || strcmp(e->forId, txid) == 0) ? e->bytes : NULL;
        if (! ans) continue;
        if (BRRawTxId(ans->b, ans->n, &got) && UInt256Eq(got, want)) return ans;
    }
    return NULL;
}

static BRAssetHop transportHop(void *ctx, const char *txid)
{
    Transport *t = (Transport *)ctx;
    const Buf *b = fetchBound(t, txid);

    if (b && t->nParsed < 8) t->parsedBufs[t->nParsed++] = b;
    return b ? hHopOfBytes(b->b, b->n) : hHopOfBytes(NULL, 0);
}

typedef struct {
    Buf start, parent, other, unreadable;
    char startId[65], parentId[65], otherId[65];
    char parentAssetId[BR_ASSET_ID_BUFFER], otherAssetId[BR_ASSET_ID_BUFFER];
} Fixtures;

static void fixtures(Fixtures *fx)
{
    static const char *C64 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    static const char *D64 = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    simpleTx(&fx->parent, C64, 1, ISSUE_LOCKED, 2);   // the requested parent: an issuance whose first input is c..c:1
    simpleTx(&fx->other, D64, 0, ISSUE_LOCKED, 3);    // a valid issuance that is NOT the requested parent
    ck(txId(&fx->parent, fx->parentId) && txId(&fx->other, fx->otherId), "the fixtures have ids");
    simpleTx(&fx->start, fx->parentId, 0, TRANSFER_SCRIPT, 1);   // the received transfer: spends the parent
    ck(txId(&fx->start, fx->startId), "the start fixture has an id");
    memset(&fx->unreadable, 0, sizeof(fx->unreadable));
    fx->unreadable.b[0] = 0x00; fx->unreadable.n = 1;   // bytes no id can be computed for
    BRAssetIdForIssuance(C64, 1, 1, BR_ASSET_AGGREGATABLE, 0, fx->parentAssetId, sizeof(fx->parentAssetId));
    BRAssetIdForIssuance(D64, 0, 1, BR_ASSET_AGGREGATABLE, 0, fx->otherAssetId, sizeof(fx->otherAssetId));
}

static int parsedHas(const Transport *t, const Buf *b)
{
    size_t i;

    for (i = 0; i < t->nParsed; i++) if (t->parsedBufs[i] == b) return 1;
    return 0;
}

static int walkBound(Transport *t, Store *st, const char *start, BRAssetResolvedFacts *out)
{
    int has = 0;

    hWalk(st, transportHop, t, start, 0, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &has, out);
    return has;
}

static void binding_different_tx_unavailable(void)
{
    static Fixtures fx; static Store st;
    Endpoint eps[2];
    Transport t;
    BRAssetResolvedFacts out;
    const Front *f;

    fixtures(&fx);
    memset(&st, 0, sizeof(st)); st.recording = 1;
    memset(eps, 0, sizeof(eps));
    eps[0].bytes = &fx.other;            // answers the other transaction for anything
    eps[1].bytes = NULL;
    memset(&t, 0, sizeof(t)); t.eps = eps; t.nEps = 2; t.walletId = fx.startId; t.walletBytes = &fx.start;
    ck(! walkBound(&t, &st, fx.startId, &out), "a different transaction named the asset");
    ck(st.nwrites == 0, "no asset identity is written (%zu writes)", st.nwrites);
    ck(! parsedHas(&t, &fx.other), "the different transaction's bytes were not read");
    f = stFront(&st, fx.startId);
    ck(f && strcmp(f->resume, fx.parentId) == 0, "the walk resumes at the parent");
}

static void binding_next_endpoint(void)
{
    static Fixtures fx; static Store st;
    Endpoint eps[2];
    Transport t;
    BRAssetResolvedFacts out;
    char want[200];

    fixtures(&fx);
    memset(&st, 0, sizeof(st)); st.recording = 1;
    memset(eps, 0, sizeof(eps));
    eps[0].bytes = &fx.other;
    eps[1].forId = fx.parentId; eps[1].bytes = &fx.parent;
    memset(&t, 0, sizeof(t)); t.eps = eps; t.nEps = 2; t.walletId = fx.startId; t.walletBytes = &fx.start;
    ck(walkBound(&t, &st, fx.startId, &out) && strcmp(out.assetId, fx.parentAssetId) == 0,
       "the asset is named from the requested parent's own issuance (%s)", out.assetId);
    ck(eps[1].asked == 1 && strcmp(eps[1].askedIds[0], fx.parentId) == 0, "the second endpoint was asked for the parent only");
    ck(! parsedHas(&t, &fx.other), "the different transaction's bytes were not read");
    ck(t.nParsed == 2 && t.parsedBufs[0] == &fx.start && t.parsedBufs[1] == &fx.parent, "read: the start, then the parent");
    snprintf(want, sizeof(want), "%s,%s", fx.startId, fx.parentId);
    ck(st.nwrites == 1 && strcmp(st.writePaths[0], want) == 0, "one identity write, for [start, parent]");
    ck(st.nwrites == 1 && strcmp(st.writeFacts[0].assetId, fx.parentAssetId) == 0, "naming the parent's asset");
    ck(st.nwrites == 1 && strcmp(st.writeFacts[0].assetId, fx.otherAssetId) != 0, "never the other asset");
}

static void binding_no_id_no_answer(void)
{
    static Fixtures fx; static Store st;
    Endpoint eps[2];
    Transport t;
    BRAssetResolvedFacts out;

    fixtures(&fx);
    memset(&st, 0, sizeof(st)); st.recording = 1;
    memset(eps, 0, sizeof(eps));
    eps[0].bytes = &fx.unreadable;
    eps[1].forId = fx.parentId; eps[1].bytes = &fx.parent;
    memset(&t, 0, sizeof(t)); t.eps = eps; t.nEps = 2; t.walletId = fx.startId; t.walletBytes = &fx.start;
    ck(walkBound(&t, &st, fx.startId, &out) && strcmp(out.assetId, fx.parentAssetId) == 0, "the parent names the asset");
    ck(! parsedHas(&t, &fx.unreadable), "bytes with no id were not read");
}

static void binding_wrong_answer_refused(void)
{
    static Fixtures fx;
    Endpoint eps[2];
    Transport t;
    int i, ok = 1;

    fixtures(&fx);
    memset(eps, 0, sizeof(eps));
    eps[0].bytes = &fx.other;
    eps[1].forId = fx.parentId; eps[1].bytes = &fx.parent;
    memset(&t, 0, sizeof(t)); t.eps = eps; t.nEps = 2;
    for (i = 0; i < 4; i++) ok &= fetchBound(&t, fx.parentId) == &fx.parent;
    ck(ok, "each of four fetches refuses the wrong answer and takes the requested transaction");
    ck(eps[1].asked == 4, "the second endpoint answered all four");
}

static void binding_guard_requested(void)
{
    static Fixtures fx; static Store st;
    Endpoint eps[2];
    Transport t;
    BRAssetResolvedFacts out;

    fixtures(&fx);
    memset(&st, 0, sizeof(st)); st.recording = 1;
    memset(eps, 0, sizeof(eps));
    eps[0].forId = fx.parentId; eps[0].bytes = &fx.parent;
    eps[1].bytes = &fx.other;
    memset(&t, 0, sizeof(t)); t.eps = eps; t.nEps = 2; t.walletId = fx.startId; t.walletBytes = &fx.start;
    ck(walkBound(&t, &st, fx.startId, &out) && strcmp(out.assetId, fx.parentAssetId) == 0, "GUARD: the requested parent names the asset");
    ck(eps[1].asked == 0, "GUARD: the second endpoint is never asked");
}

static void binding_guard_upper_case(void)
{
    static Fixtures fx;
    char upper[65];
    UInt256 a, b;
    size_t i;

    fixtures(&fx);
    for (i = 0; i < 64; i++) upper[i] = (fx.parentId[i] >= 'a' && fx.parentId[i] <= 'f') ? (char)(fx.parentId[i] - 32) : fx.parentId[i];
    upper[64] = '\0';
    ck(BRRawTxIdFromDisplayHex(upper, 64, &a) && BRRawTxId(fx.parent.b, fx.parent.n, &b) && UInt256Eq(a, b),
       "GUARD: an upper-case request is compared by value");
}

// ---- the walk's edges and refusals ------------------------------------------------------------

static void walk_refusals(void)
{
    BRAssetTxidSlot slots[4];
    BRAssetWalk w;
    BRAssetWalkFeed feed;
    char long65[66], long64[65];

    memset(long65, 'a', 65); long65[65] = '\0';
    memset(long64, 'a', 64); long64[64] = '\0';
    ck(BRAssetWalkBegin(NULL, "a", 0, 2, slots, 4) == BR_ASSET_WALK_REFUSED, "a NULL walk is refused");
    ck(BRAssetWalkBegin(&w, NULL, 0, 2, slots, 4) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_ARGUMENT, "a NULL start");
    ck(BRAssetWalkBegin(&w, "a", 0, 2, NULL, 4) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_ARGUMENT, "NULL slots");
    ck(BRAssetWalkBegin(&w, long65, 0, 2, slots, 4) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_TXID, "a 65-character start txid");
    ck(BRAssetWalkBegin(&w, long64, 0, 2, slots, 4) == BR_ASSET_WALK_NEEDS_KNOWN && strcmp(w.ask, long64) == 0, "64 characters fit");
    ck(BRAssetWalkBegin(&w, "a", 0, 3, slots, 3) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_CAPACITY,
       "3 hops need 4 slots: 3 is refused, CAPACITY");
    ck(BRAssetWalkBegin(&w, "a", 0, 3, slots, 4) == BR_ASSET_WALK_NEEDS_KNOWN, "4 slots suffice");
    ck(BRAssetWalkBegin(&w, "a", 1, 3, slots, 4) == BR_ASSET_WALK_NEEDS_FRONTIER && strcmp(w.ask, "a") == 0, "forced: the frontier first");
    ck(BRAssetWalkBegin(&w, "", 0, 0, slots, 1) == BR_ASSET_WALK_NEEDS_KNOWN && w.ask[0] == '\0', "an empty txid is a txid");
    ck(BRAssetWalkBegin(&w, "a", 0, INT32_MAX, slots, 4) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_CAPACITY,
       "a budget of INT32_MAX needs INT32_MAX + 1 slots");
    ck(BR_ASSET_WALK_SLOTS(INT32_MAX) == (size_t)INT32_MAX + 1 && BR_ASSET_WALK_SLOTS(INT32_MIN) == 1 && BR_ASSET_WALK_SLOTS(0) == 1 &&
       BR_ASSET_WALK_SLOTS(50) == 51, "BR_ASSET_WALK_SLOTS");
    ck(BRAssetWalkBegin(&w, "a", 0, INT32_MIN, slots, 1) == BR_ASSET_WALK_NEEDS_KNOWN, "a budget of INT32_MIN needs one slot");

    // Begin sets every field a caller reads, whatever the struct held before
    memset(&w, 0xa5, sizeof(w));
    ck(BRAssetWalkBegin(&w, "a", 0, 3, slots, 4) == BR_ASSET_WALK_NEEDS_KNOWN && w.need == BR_ASSET_WALK_NEEDS_KNOWN &&
       w.why == BR_ASSET_WALK_WHY_NONE && ! w.hasAnswer && w.pathCount == 0 && w.frontier == BR_ASSET_FRONTIER_KEEP &&
       w.frontierResume[0] == '\0' && w.frontierHops == 0, "Begin over a used struct starts clean");

    // feeds
    memset(&feed, 0, sizeof(feed));
    BRAssetWalkBegin(&w, "a", 0, 3, slots, 4);
    ck(BRAssetWalkStep(&w, NULL) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_ARGUMENT, "a NULL feed is refused");
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED, "and the walk stays refused");
    ck(BRAssetWalkStep(NULL, &feed) == BR_ASSET_WALK_REFUSED, "a NULL walk steps to REFUSED");

    BRAssetWalkBegin(&w, "a", 0, 3, slots, 4);
    feed.has = 1; feed.facts = FACTS; memset(feed.facts.assetId, 'L', sizeof(feed.facts.assetId));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_FEED && ! w.hasAnswer,
       "fed facts whose id does not end in its buffer are refused");
    ck(w.need == BR_ASSET_WALK_REFUSED && w.ask[0] == '\0' && w.pathCount == 0 && w.frontier == BR_ASSET_FRONTIER_KEEP,
       "a refused walk asks nothing and writes nothing");
    BRAssetWalkBegin(&w, "a", 0, 3, slots, 4);
    { BRAssetWalkFeed none; memset(&none, 0, sizeof(none)); BRAssetWalkStep(&w, &none); BRAssetWalkStep(&w, &none); }
    ck(w.need == BR_ASSET_WALK_NEEDS_KNOWN && strcmp(w.ask, "a") == 0, "the loop asks the store about the current txid");
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_FEED && ! w.hasAnswer && w.pathCount == 0,
       "so are such facts for a txid on the path");
    BRAssetWalkBegin(&w, "a", 0, 3, slots, 4);
    feed.facts = FACTS; memset(feed.facts.metadataCid, 'b', sizeof(feed.facts.metadataCid));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_FEED, "so is an unterminated CID");
    feed.facts.hasMetadataCid = 0;
    BRAssetWalkBegin(&w, "a", 0, 3, slots, 4);
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_DONE && w.hasAnswer && w.pathCount == 0 && w.frontier == BR_ASSET_FRONTIER_KEEP,
       "an unterminated CID that is not there is not read");

    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    memset(&feed, 0, sizeof(feed)); feed.has = 1; feed.resumeTxid = long65;
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_TXID, "a resume txid that does not fit");
    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    feed.resumeTxid = NULL;
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_TXID, "a frontier without a resume txid");

    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    memset(&feed, 0, sizeof(feed));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_NEEDS_HOP && strcmp(w.ask, "a") == 0, "forced, no frontier: the hop of the start");
    feed.hop.kind = (BRAssetHopKind)7;
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_FEED, "a hop kind the header does not define");
    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    memset(&feed, 0, sizeof(feed));
    BRAssetWalkStep(&w, &feed);
    feed.hop.kind = BR_ASSET_HOP_TRANSFER; memset(feed.hop.parentTxid, 'b', sizeof(feed.hop.parentTxid));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_TXID, "a parent txid that does not end");
    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    memset(&feed, 0, sizeof(feed));
    BRAssetWalkStep(&w, &feed);
    feed.hop = hopIssuance(&FACTS); memset(feed.hop.facts.assetId, 'L', sizeof(feed.hop.facts.assetId));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && w.why == BR_ASSET_WALK_WHY_FEED, "an issuance whose id does not end");

    // DONE stays DONE
    BRAssetWalkBegin(&w, "a", 1, 3, slots, 4);
    memset(&feed, 0, sizeof(feed));
    BRAssetWalkStep(&w, &feed);
    feed.hop = hopKind(BR_ASSET_HOP_DEAD_END);
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_DONE && w.frontier == BR_ASSET_FRONTIER_CLEAR, "a dead end is DONE, clearing");
    ck(w.need == BR_ASSET_WALK_DONE && w.ask[0] == '\0' && w.frontierResume[0] == '\0' && w.frontierHops == 0 && w.pathCount == 0,
       "a DONE walk asks nothing; a cleared frontier carries no resume point");
    feed.hop = hopIssuance(&FACTS);
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_DONE && ! w.hasAnswer && w.frontier == BR_ASSET_FRONTIER_CLEAR, "and stays so");
}

static void walk_hop_counts(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    const Front *f;

    // hop counts add modulo 2^32
    freshWorld(&st, &hs);
    hopsSet(&hs, "r", hopTransfer("s"));
    stPutFrontier(&st, "a", "r", INT32_MAX);
    resolveBoth(&st, &hs, "a", 0, 5, &out, "wrap unavailable");
    f = stFront(&st, "a");
    ck(f && strcmp(f->resume, "s") == 0 && f->hops == INT32_MIN, "INT32_MAX + 1 hop is INT32_MIN (%d)", f ? (int)f->hops : 0);

    freshWorld(&st, &hs);
    hopsSet(&hs, "r", hopTransfer("s"));
    hopsSet(&hs, "s", hopTransfer("t"));
    stPutFrontier(&st, "a", "r", INT32_MAX);
    resolveBoth(&st, &hs, "a", 0, 2, &out, "wrap budget");
    f = stFront(&st, "a");
    ck(f && strcmp(f->resume, "t") == 0 && f->hops == INT32_MIN + 1, "INT32_MAX + a budget of 2 is INT32_MIN + 1");

    freshWorld(&st, &hs);
    stPutFrontier(&st, "a", "r", INT32_MIN);
    resolveBoth(&st, &hs, "a", 1, INT32_MIN, &out, "budget INT32_MIN");
    f = stFront(&st, "a");
    ck(f && strcmp(f->resume, "r") == 0 && f->hops == 0 && hs.fetches == 0, "INT32_MIN + a budget of INT32_MIN is 0, no hop");

    freshWorld(&st, &hs);
    resolveBoth(&st, &hs, "a", 0, 0, &out, "budget 0");
    f = stFront(&st, "a");
    ck(f && strcmp(f->resume, "a") == 0 && f->hops == 0 && hs.fetches == 0, "a budget of 0 walks nothing and puts the frontier at the start");

    freshWorld(&st, &hs);
    stPutFrontier(&st, "a", "q", 3);
    resolveBoth(&st, &hs, "a", 0, -5, &out, "budget -5");
    f = stFront(&st, "a");
    ck(f && strcmp(f->resume, "q") == 0 && f->hops == -2, "a negative budget is added as it is (3 + -5 = %d)", f ? (int)f->hops : 0);

    freshWorld(&st, &hs);
    stPutFrontier(&st, "a", "q", -7);
    hopsSet(&hs, "q", hopKind(BR_ASSET_HOP_UNAVAILABLE));
    resolveBoth(&st, &hs, "a", 0, 3, &out, "negative prior");
    f = stFront(&st, "a");
    ck(f && f->hops == -7, "a negative count from the store is carried (%d)", f ? (int)f->hops : 0);
}

static void walk_path_rules(void)
{
    Store st; Hops hs; BRAssetResolvedFacts out;
    static Log l;

    // the start reappears on the path after a resume: listed once, first
    freshWorld(&st, &hs);
    stPutFrontier(&st, "s", "x", 1);
    hopsSet(&hs, "x", hopTransfer("s"));
    hopsSet(&hs, "s", hopTransfer("y"));
    hopsSet(&hs, "y", hopIssuance(&FACTS));
    memset(&l, 0, sizeof(l));
    ck(resolveBoth(&st, &hs, "s", 0, 10, &out, "start again"), "a resumed walk passing back through its start resolves");
    ck(stRow(&st, "s") && stRow(&st, "x") && stRow(&st, "y"), "start, the transfers and the issuance are memoised");

    // a known ancestor at the resume point: only the start is memoised
    freshWorld(&st, &hs);
    stPutFrontier(&st, "s", "x", 4);
    stSet(&st, "x", &RULED);
    ck(resolveBoth(&st, &hs, "s", 0, 10, &out, "known at resume") && factsEq(&out, &RULED) && hs.fetches == 0,
       "a known resume point answers without a hop");
    ck(stRow(&st, "s") && factsEq(stRow(&st, "s"), &RULED) && stFront(&st, "s") == NULL, "the start learns it; the frontier is cleared");

    // the issuance is the start itself
    freshWorld(&st, &hs);
    hopsSet(&hs, "s", hopIssuance(&FACTS));
    ck(resolveBoth(&st, &hs, "s", 1, 1, &out, "start is issuance") && hs.fetches == 1, "a start that is the issuance answers in one hop");
    ck(st.nrows == 1, "and memoises it once");

    // a cycle back to the start after a resume is caught on the second visit only
    freshWorld(&st, &hs);
    stPutFrontier(&st, "s", "x", 0);
    hopsSet(&hs, "x", hopTransfer("s"));
    hopsSet(&hs, "s", hopTransfer("x"));
    ck(! resolveBoth(&st, &hs, "s", 1, 10, &out, "resume cycle") && hs.fetches == 2 && stFront(&st, "s") == NULL,
       "x, s, then x again: a cycle");

    // a 64-character txid works end to end
    {
        const char *t64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        freshWorld(&st, &hs);
        hopsSet(&hs, "s", hopTransfer(t64));
        hopsSet(&hs, t64, hopIssuance(&FACTS));
        ck(resolveBoth(&st, &hs, "s", 0, 3, &out, "64 chars") && stRow(&st, t64), "64-character txids are kept whole");
    }
}

static void capacity(void)
{
    BRAssetTxidSlot slots[11];
    BRAssetWalk w;
    BRAssetWalkFeed feed;
    BRAssetWalkNeed need;
    char a[8], b[8];
    int i;

    // exactly BR_ASSET_WALK_SLOTS(10) slots carry a walk that uses every one: resumed at t0, ten hops
    need = BRAssetWalkBegin(&w, "s", 1, 10, slots, BR_ASSET_WALK_SLOTS(10));
    memset(&feed, 0, sizeof(feed));
    feed.has = 1; feed.resumeTxid = "t0"; feed.hopsWalked = 0;
    need = BRAssetWalkStep(&w, &feed);
    feed.has = 0;
    for (i = 0; i < 9 && need == BR_ASSET_WALK_NEEDS_HOP; i++) {
        snprintf(a, sizeof(a), "t%d", i);
        snprintf(b, sizeof(b), "t%d", i + 1);
        ck(strcmp(w.ask, a) == 0 && w.need == need, "hop %d asks %s", i, a);
        feed.hop = hopTransfer(b);
        need = BRAssetWalkStep(&w, &feed);
    }
    ck(need == BR_ASSET_WALK_NEEDS_HOP && strcmp(w.ask, "t9") == 0, "the tenth hop is asked");
    feed.hop = hopIssuance(&FACTS);
    need = BRAssetWalkStep(&w, &feed);
    ck(need == BR_ASSET_WALK_DONE && w.pathCount == 11, "the path is all eleven slots (%zu)", w.pathCount);
    ck(strcmp(slots[0].txid, "s") == 0, "slot 0 is the start");
    for (i = 1; i < 11 && i < (int)w.pathCount; i++) {
        snprintf(a, sizeof(a), "t%d", i - 1);
        ck(strcmp(slots[i].txid, a) == 0, "slot %d is %s", i, a);
    }
    ck(BRAssetWalkBegin(&w, "s", 1, 10, slots, BR_ASSET_WALK_SLOTS(10) - 1) == BR_ASSET_WALK_REFUSED &&
       w.why == BR_ASSET_WALK_WHY_CAPACITY && ! w.hasAnswer && w.pathCount == 0 && w.frontier == BR_ASSET_FRONTIER_KEEP,
       "one slot fewer is refused, with nothing to write");

    // BRAssetWalkSlots, the function Swift can import, is the macro's count for every budget
    {
        static const int32_t budgets[] = { INT32_MIN, -50, -1, 0, 1, 2, 10, BR_ASSET_WALK_DEFAULT_MAX_HOPS, INT32_MAX - 1, INT32_MAX };
        size_t k;
        int same = 1;

        for (k = 0; k < N(budgets); k++) same &= BRAssetWalkSlots(budgets[k]) == BR_ASSET_WALK_SLOTS(budgets[k]);
        ck(same, "BRAssetWalkSlots is BR_ASSET_WALK_SLOTS for every budget");
        ck(BRAssetWalkSlots(BR_ASSET_WALK_DEFAULT_MAX_HOPS) == 51 && BRAssetWalkSlots(10) == 11 && BRAssetWalkSlots(0) == 1 &&
           BRAssetWalkSlots(-1) == 1 && BRAssetWalkSlots(INT32_MIN) == 1 && BRAssetWalkSlots(INT32_MAX) == (size_t)INT32_MAX + 1,
           "51 for the default budget, one per hop and one for the start, and 1 for a budget of 0 or less");
        ck(BRAssetWalkBegin(&w, "s", 1, 10, slots, BRAssetWalkSlots(10)) == BR_ASSET_WALK_NEEDS_FRONTIER &&
           BRAssetWalkBegin(&w, "s", 1, 10, slots, BRAssetWalkSlots(10) - 1) == BR_ASSET_WALK_REFUSED &&
           w.why == BR_ASSET_WALK_WHY_CAPACITY, "and is the count Begin holds the caller to");
    }
}

static void zero_structs(void)
{
    BRAssetWalk w;
    BRAssetNamedSearch s;
    BRAssetWalkFeed feed;
    BRAssetNamedFeed nf;
    BRAssetHop h;
    BRAssetResolvedFacts f;

    memset(&w, 0, sizeof(w)); memset(&s, 0, sizeof(s)); memset(&feed, 0, sizeof(feed)); memset(&nf, 0, sizeof(nf));
    memset(&h, 0, sizeof(h)); memset(&f, 0, sizeof(f));
    ck(BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_REFUSED && ! w.hasAnswer && w.pathCount == 0 && w.frontier == BR_ASSET_FRONTIER_KEEP,
       "a walk nobody began is refused and writes nothing");
    ck(BRAssetFactsForNamedAsset(&s, &nf) == BR_ASSET_NAMED_REFUSED && ! s.hasAnswer, "a search nobody began is refused");
    ck(h.kind == BR_ASSET_HOP_UNAVAILABLE, "a hop nobody filled is UNAVAILABLE");
    ck(! f.hasIssuanceOpcode && ! BRAssetIssuanceFactsMatch(&f, ""), "facts nobody filled answer for no issuance");
    ck(BR_ASSET_WALK_REFUSED == 0 && BR_ASSET_NAMED_REFUSED == 0 && BR_ASSET_FRONTIER_KEEP == 0 && BR_ASSET_HOP_UNAVAILABLE == 0 &&
       BR_ASSET_WALK_WHY_NONE == 0, "every enum's no-answer is 0");
    ck(BR_ASSET_WALK_DEFAULT_MAX_HOPS == 50 && BR_ASSET_TXID_LENGTH == 64 && BR_ASSET_TXID_BUFFER == 65, "the constants");
}

// ---- the named asset (factsForNamedAsset) ----------------------------------------------------

typedef struct {
    const BRAssetResolvedFacts *knownFacts;                 // what known(assetId) answers
    const char *resolveTx[8]; const BRAssetResolvedFacts *resolveAns[8]; size_t nResolve;   // resolve(tx)
    const char *const *parents; size_t nParents;            // otherParents()
    Store *walkStore; Hops *walkHops; int force;            // resolve by a real walk when set
    Log *log;
} Named;

static int namedResolve(Named *n, const char *tx, BRAssetResolvedFacts *out)
{
    size_t i;

    lg(n->log, "resolve(%s);", tx);
    if (n->walkStore) {
        int has = 0;
        Log *keep = n->walkStore->log;
        n->walkStore->log = NULL;
        hWalk(n->walkStore, hopsFn, n->walkHops, tx, n->force, 10, &has, out);
        n->walkStore->log = keep;
        return has;
    }
    for (i = 0; i < n->nResolve; i++) if (strcmp(n->resolveTx[i], tx) == 0) {
        if (! n->resolveAns[i]) return 0;
        *out = *n->resolveAns[i];
        return 1;
    }
    return 0;
}

static BRAssetNamedNeed hNamed(Named *n, const char *assetId, const char *start, int *has, BRAssetResolvedFacts *out)
{
    BRAssetNamedSearch s;
    BRAssetNamedFeed feed;
    BRAssetNamedNeed need = BRAssetFactsForNamedAssetBegin(&s, assetId, start);
    BRAssetResolvedFacts r;

    *has = 0;
    while (need == BR_ASSET_NAMED_NEEDS_KNOWN || need == BR_ASSET_NAMED_NEEDS_RESOLVE || need == BR_ASSET_NAMED_NEEDS_PARENTS) {
        memset(&feed, 0, sizeof(feed));
        if (need == BR_ASSET_NAMED_NEEDS_KNOWN) { lg(n->log, "known(%s);", assetId); feed.facts = n->knownFacts; }
        else if (need == BR_ASSET_NAMED_NEEDS_RESOLVE) { if (namedResolve(n, s.ask, &r)) feed.facts = &r; }
        else { lg(n->log, "otherParents;"); feed.parents = n->parents; feed.parentCount = n->nParents; }
        need = BRAssetFactsForNamedAsset(&s, &feed);
    }
    if (need == BR_ASSET_NAMED_DONE && s.hasAnswer) { *has = 1; *out = s.answer; }
    return need;
}

// factsForNamedAsset, line for line
static int mNamed(Named *n, const char *assetId, const char *startTxid, BRAssetResolvedFacts *out)
{
    const char *distinct[64];
    size_t nd = 0, i, j;
    BRAssetResolvedFacts r;

    lg(n->log, "known(%s);", assetId);
    if (n->knownFacts && strcmp(n->knownFacts->assetId, assetId) == 0) { *out = *n->knownFacts; return 1; }
    if (namedResolve(n, startTxid, &r) && strcmp(r.assetId, assetId) == 0) { *out = r; return 1; }
    lg(n->log, "otherParents;");
    for (i = 0; i < n->nParents; i++) {   // .distinct()
        int dup = 0;
        for (j = 0; j < nd; j++) if (strcmp(distinct[j], n->parents[i]) == 0) dup = 1;
        if (! dup && nd < N(distinct)) distinct[nd++] = n->parents[i];
    }
    for (i = 0; i < nd; i++) {
        if (strcmp(distinct[i], startTxid) == 0) continue;
        if (namedResolve(n, distinct[i], &r) && strcmp(r.assetId, assetId) == 0) { *out = r; return 1; }
    }
    return 0;
}

static int namedBoth(Named *n, const char *assetId, const char *start, BRAssetResolvedFacts *out, const char *what)
{
    static Log l1, l2;
    BRAssetResolvedFacts m;
    int has = 0, mhas;
    BRAssetNamedNeed need;

    memset(&l1, 0, sizeof(l1)); memset(&l2, 0, sizeof(l2));
    n->log = &l1;
    need = hNamed(n, assetId, start, &has, out);
    n->log = &l2;
    mhas = mNamed(n, assetId, start, &m);
    n->log = NULL;
    if (need != BR_ASSET_NAMED_DONE || has != mhas || (has && ! factsEq(out, &m)) || strcmp(l1.s, l2.s) != 0)
        ck(0, "%s: the header's search is not the Kotlin's (header \"%s\", model \"%s\")", what, l1.s, l2.s);
    return has;
}

static void named_rules(void)
{
    BRAssetResolvedFacts a = mkFacts("LaA", 5, 0, NULL, 1, 1, 1, 1), b = mkFacts("LaB", 6, 1, NULL, 1, 2, 1, 1), out;
    const char *parents[] = { "p1", "s", "p1", "p2", "p3" };
    Named n;

    memset(&n, 0, sizeof(n));
    n.knownFacts = &a;
    ck(namedBoth(&n, "LaA", "s", &out, "known") && factsEq(&out, &a), "the store's issuance facts for the asset answer first");
    n.knownFacts = &b;
    n.resolveTx[0] = "s"; n.resolveAns[0] = &a; n.nResolve = 1;
    ck(namedBoth(&n, "LaA", "s", &out, "known other") && factsEq(&out, &a), "known facts for another asset are not taken; the walk from the start is");

    memset(&n, 0, sizeof(n));
    n.resolveTx[0] = "s"; n.resolveAns[0] = &b;
    n.resolveTx[1] = "p1"; n.resolveAns[1] = &b;
    n.resolveTx[2] = "p2"; n.resolveAns[2] = &a;
    n.resolveTx[3] = "p3"; n.resolveAns[3] = &a;
    n.nResolve = 4;
    n.parents = parents; n.nParents = N(parents);
    ck(namedBoth(&n, "LaA", "s", &out, "parents") && factsEq(&out, &a),
       "a start whose walk names another asset: the other inputs' transactions are walked until one names it");

    n.resolveAns[2] = NULL; n.resolveAns[3] = &b;
    ck(! namedBoth(&n, "LaA", "s", &out, "none"), "no walk naming the asset: none");
    n.parents = NULL; n.nParents = 0;
    ck(! namedBoth(&n, "LaA", "s", &out, "no parents"), "no other inputs: none");
    n.resolveAns[0] = NULL;
    ck(! namedBoth(&n, "LaB", "s", &out, "nothing"), "nothing answers: none");

    // ids that share a prefix: at each stage (the store's facts, the start's walk, another input's walk) an
    // answer for an id that agrees with the one asked on all but its end is another asset's
    {
        static const char *const p1[] = { "p1" };
        size_t i;

        for (i = 0; i < N(ID_PAIRS); i++) {
            const char *heldId = ID_PAIRS[i][0], *asked = ID_PAIRS[i][1];
            BRAssetResolvedFacts held = mkFacts(heldId, 7, 0, NULL, 1, 1, 1, 1);

            memset(&n, 0, sizeof(n));
            n.knownFacts = &held;
            ck(! namedBoth(&n, asked, "s", &out, "prefix known"), "the store's facts for %s do not answer %s", heldId, asked);
            ck(namedBoth(&n, heldId, "s", &out, "prefix known self") && factsEq(&out, &held), "and do answer %s", heldId);

            memset(&n, 0, sizeof(n));
            n.resolveTx[0] = "s"; n.resolveAns[0] = &held; n.nResolve = 1;
            ck(! namedBoth(&n, asked, "s", &out, "prefix start"), "the start's walk to %s does not answer %s", heldId, asked);

            memset(&n, 0, sizeof(n));
            n.resolveTx[0] = "p1"; n.resolveAns[0] = &held; n.nResolve = 1;
            n.parents = p1; n.nParents = 1;
            ck(! namedBoth(&n, asked, "s", &out, "prefix parent"), "another input's walk to %s does not answer %s", heldId, asked);
            ck(namedBoth(&n, heldId, "s", &out, "prefix parent self") && factsEq(&out, &held), "and does answer %s", heldId);
        }
    }
}

static void named_through_walk(void)
{
    static Store st; static Hops hs;
    BRAssetResolvedFacts out, issA = mkFacts("LaAAA", 9, 0, NULL, 1, 1, 1, 1), issB = mkFacts("LaBBB", 9, 0, NULL, 1, 1, 1, 1);
    const char *parents[] = { "q0" };
    Named n;

    // the start's first input leads to asset B; its second input's lineage reaches A
    memset(&st, 0, sizeof(st)); memset(&hs, 0, sizeof(hs));
    hopsSet(&hs, "s", hopTransfer("b0"));
    hopsSet(&hs, "b0", hopIssuance(&issB));
    hopsSet(&hs, "q0", hopTransfer("q1"));
    hopsSet(&hs, "q1", hopIssuance(&issA));
    memset(&n, 0, sizeof(n));
    n.walkStore = &st; n.walkHops = &hs; n.parents = parents; n.nParents = 1;
    {
        int has = 0;
        BRAssetNamedNeed need = hNamed(&n, "LaAAA", "s", &has, &out);
        ck(need == BR_ASSET_NAMED_DONE && has && factsEq(&out, &issA), "a held row's asset is found through another input's lineage");
    }
    ck(stRow(&st, "s") && strcmp(stRow(&st, "s")->assetId, "LaBBB") == 0, "the start's own walk memoised what it reached (B): nothing renames it");
    ck(stRow(&st, "q0") && strcmp(stRow(&st, "q0")->assetId, "LaAAA") == 0, "the other lineage is memoised as A");
}

static void named_edges(void)
{
    BRAssetNamedSearch s;
    BRAssetNamedFeed feed;
    BRAssetResolvedFacts bad = mkFacts("LaA", 1, 0, NULL, 1, 1, 1, 1);
    BRAssetResolvedFacts named = mkFacts("LaA", 1, 0, NULL, 1, 1, 1, 1);
    const char *withNull[] = { "p", NULL }, *nullFirst[] = { NULL, "p" };

    memset(&feed, 0, sizeof(feed));
    ck(BRAssetFactsForNamedAssetBegin(NULL, "LaA", "s") == BR_ASSET_NAMED_REFUSED, "a NULL search is refused");
    ck(BRAssetFactsForNamedAssetBegin(&s, NULL, "s") == BR_ASSET_NAMED_REFUSED, "a NULL asset id");
    ck(BRAssetFactsForNamedAssetBegin(&s, "LaA", NULL) == BR_ASSET_NAMED_REFUSED, "a NULL start");
    ck(BRAssetFactsForNamedAsset(NULL, &feed) == BR_ASSET_NAMED_REFUSED, "a NULL search steps to REFUSED");
    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    ck(BRAssetFactsForNamedAsset(&s, NULL) == BR_ASSET_NAMED_REFUSED, "a NULL feed is refused");

    memset(&s, 0xa5, sizeof(s));
    ck(BRAssetFactsForNamedAssetBegin(&s, "LaA", "s") == BR_ASSET_NAMED_NEEDS_KNOWN && s.need == BR_ASSET_NAMED_NEEDS_KNOWN &&
       s.ask == NULL && ! s.hasAnswer, "Begin over a used struct starts clean");
    {
        BRAssetResolvedFacts good = mkFacts("LaA", 1, 0, NULL, 1, 1, 1, 1), other = mkFacts("LaB", 1, 0, NULL, 1, 1, 1, 1);
        feed.facts = &good;
        ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_DONE && s.hasAnswer && s.ask == NULL, "known facts answer: DONE");
        feed.facts = &other;
        ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_DONE && s.hasAnswer && strcmp(s.answer.assetId, "LaA") == 0,
           "and a DONE search stays DONE with its answer");
        feed.facts = NULL;
    }
    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    memset(bad.assetId, 'L', sizeof(bad.assetId));
    feed.facts = &bad;
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_REFUSED && ! s.hasAnswer, "known facts whose id does not end are refused");

    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    feed.facts = NULL;
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_NEEDS_RESOLVE && strcmp(s.ask, "s") == 0, "then the walk from the start");
    {
        BRAssetNamedSearch t = s;
        BRAssetNamedFeed bf = feed;
        bf.facts = &bad;
        ck(BRAssetFactsForNamedAsset(&t, &bf) == BR_ASSET_NAMED_REFUSED && ! t.hasAnswer, "a walk's answer whose id does not end is refused");
        t = s;
        bf.facts = &named;
        ck(BRAssetFactsForNamedAsset(&t, &bf) == BR_ASSET_NAMED_DONE && t.hasAnswer && t.ask == NULL,
           "a walk's answer naming the asset: DONE, asking nothing");
    }
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_NEEDS_PARENTS && s.ask == NULL, "then the other inputs");
    feed.parents = NULL; feed.parentCount = 1;
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_REFUSED, "a NULL list with a count is refused");

    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    feed.parents = NULL; feed.parentCount = 0;
    BRAssetFactsForNamedAsset(&s, &feed); BRAssetFactsForNamedAsset(&s, &feed);
    feed.parents = withNull; feed.parentCount = 2;
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_REFUSED, "a NULL entry is refused");
    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    feed.parents = NULL; feed.parentCount = 0;
    BRAssetFactsForNamedAsset(&s, &feed); BRAssetFactsForNamedAsset(&s, &feed);
    feed.parents = nullFirst; feed.parentCount = 2;
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_REFUSED, "a NULL first entry is refused");

    BRAssetFactsForNamedAssetBegin(&s, "LaA", "s");
    feed.parents = NULL; feed.parentCount = 0;
    BRAssetFactsForNamedAsset(&s, &feed); BRAssetFactsForNamedAsset(&s, &feed);
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_DONE && ! s.hasAnswer, "an empty list: DONE, none");
    ck(BRAssetFactsForNamedAsset(&s, &feed) == BR_ASSET_NAMED_DONE, "and stays DONE");
}

// ---- the gate (AssetTransferRuleGateTest) ------------------------------------------------------

#define ST(op, l, a) BRAssetRuleStateOf(1, (op), 1, (l), 1, (a))
// Kotlin stateOf(opcode?, locked?, api?), each null when its has is 0
static BRSweepRuleState stateOf(int hasOp, int32_t op, int hasL, int l, int hasA, int a)
{
    return BRAssetRuleStateOf(hasOp, op, hasL, l, hasA, a);
}

static void gate_opcode_3(void)
{
    ck(stateOf(1, 3, 1, 1, 0, 0) == BR_SWEEP_RULE_BOUND, "opcode 3, locked, no api: BOUND");
    ck(stateOf(1, 3, 1, 0, 1, 0) == BR_SWEEP_RULE_BOUND, "opcode 3, unlocked, api false: BOUND");
}

static void gate_opcode_4(void)
{
    ck(stateOf(1, 4, 1, 1, 1, 0) == BR_SWEEP_RULE_BOUND, "opcode 4, locked, api false: BOUND");
    ck(stateOf(1, 4, 0, 0, 0, 0) == BR_SWEEP_RULE_BOUND, "opcode 4, nothing else known: BOUND");
}

static void gate_api_rules(void)
{
    ck(stateOf(1, 1, 1, 1, 1, 1) == BR_SWEEP_RULE_BOUND, "a rules object is BOUND even when the chain says opcode 1");
    ck(stateOf(0, 0, 0, 0, 1, 1) == BR_SWEEP_RULE_BOUND, "and with nothing else known");
}

static void gate_locked_rule_free(void)
{
    int32_t ops[] = { 1, 2, 5 };
    size_t i;

    for (i = 0; i < N(ops); i++) {
        ck(stateOf(1, ops[i], 1, 1, 1, 0) == BR_SWEEP_RULE_NONE, "opcode %d / api false: NONE", (int)ops[i]);
        ck(stateOf(1, ops[i], 1, 1, 0, 0) == BR_SWEEP_RULE_NONE, "opcode %d / api null: NONE", (int)ops[i]);
    }
}

static void gate_unlocked_unknown(void)
{
    ck(stateOf(1, 1, 1, 0, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "an unlocked rule-free issuance is UNKNOWN");
    ck(stateOf(1, 5, 0, 0, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "a rule-free opcode with no lock known is UNKNOWN");
}

static void gate_no_opcode(void)
{
    ck(stateOf(0, 0, 1, 1, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "no opcode, locked, api false: UNKNOWN");
    ck(stateOf(0, 0, 0, 0, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "nothing known: UNKNOWN");
}

static void gate_outside_set(void)
{
    ck(stateOf(1, 0x15, 1, 1, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "opcode 0x15 is UNKNOWN");
    ck(stateOf(1, 0, 1, 1, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "opcode 0 is UNKNOWN");
}

static void gate_never_loosens(void)
{
    ck(stateOf(0, 0, 1, 1, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "api false does not turn an unknown asset into NONE");
    ck(stateOf(1, 1, 1, 0, 1, 0) == BR_SWEEP_RULE_UNKNOWN, "nor an unlocked one");
}

static void gate_send_state(void)
{
    // RuleCheckState: NONE -> NONE, RULE_BOUND -> RULE_BOUND, UNKNOWN -> UNVERIFIED, and only NONE allows send.
    // The screen's names and its transient CHECKING are the platform's; what may be sent is the header's.
    ck(BRAssetRuleAllowsSend(BR_SWEEP_RULE_NONE), "NONE allows send");
    ck(! BRAssetRuleAllowsSend(BR_SWEEP_RULE_BOUND), "RULE_BOUND does not");
    ck(! BRAssetRuleAllowsSend(BR_SWEEP_RULE_UNKNOWN), "UNKNOWN (UNVERIFIED) does not");
    ck(! BRAssetRuleAllowsSend((BRSweepRuleState)3) && ! BRAssetRuleAllowsSend((BRSweepRuleState)-1),
       "a value the header does not define does not (CHECKING has none)");
}

static void gate_enum_shared(void)
{
    BRSweepRuleState s;

    ck(BR_SWEEP_RULE_NONE == 0 && BR_SWEEP_RULE_BOUND == 1 && BR_SWEEP_RULE_UNKNOWN == 2, "one enum, Android's order");
    s = ST(1, 1, 0);
    ck(BRSweepRuleStateRead(1, (int)s) == BR_SWEEP_RULE_NONE, "the sweep's rule lookup reads the gate's NONE as NONE");
    s = ST(4, 1, 0);
    ck(BRSweepRuleStateRead(1, (int)s) == BR_SWEEP_RULE_BOUND, "BOUND as BOUND");
    s = ST(1, 0, 0);
    ck(BRSweepRuleStateRead(1, (int)s) == BR_SWEEP_RULE_UNKNOWN, "UNKNOWN as UNKNOWN");
}

static void gate_for_asset(void)
{
    ck(BRAssetRuleStateForAsset("LaA", "LaA", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_NONE, "facts for the asked asset: its verdict");
    ck(BRAssetRuleStateForAsset("LaA", "LaB", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "facts for another asset: UNKNOWN");
    ck(BRAssetRuleStateForAsset("LaA", "LaB", 1, 4, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "even when that asset is BOUND");
    ck(BRAssetRuleStateForAsset("LaA", NULL, 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "no facts: UNKNOWN");
    ck(BRAssetRuleStateForAsset(NULL, "LaA", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "no asset asked: UNKNOWN");
    ck(BRAssetRuleStateForAsset("LaA", "LaA", 1, 4, 1, 1, 0, 0) == BR_SWEEP_RULE_BOUND, "and BOUND for the asked asset when it is");
    ck(BRAssetRuleStateForAsset("LaA", "Laa", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN, "ids compare byte for byte");
    {
        size_t i;

        for (i = 0; i < N(ID_PAIRS); i++) {
            const char *named = ID_PAIRS[i][0], *asked = ID_PAIRS[i][1];
            ck(BRAssetRuleStateForAsset(asked, named, 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN &&
               BRAssetRuleStateForAsset(asked, named, 1, 4, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN &&
               BRAssetRuleStateForAsset(named, named, 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_NONE,
               "facts naming %s give %s no verdict, to the last character", named, asked);
        }
    }
}

// ---- the send gate (AssetSendRuleGateTest) -----------------------------------------------------

typedef enum { REF_NONE, REF_RULE_BOUND_ASSET, REF_RULES_UNKNOWN } Refusal;

// The proxy's stored rules JSON as the platform reads it: NULL no answer; "{}" an answer without rules;
// anything else here an object with members (the platform's JSON reading is not the header's).
static void apiOf(const char *json, int *has, int *val)
{
    *has = json != NULL;
    *val = json != NULL && strcmp(json, "{}") != 0;
}

// transferRuleState(assetId): the store's issuance facts and the proxy's stored answer, then the header.
static BRSweepRuleState transferRuleState(Store *st, const char *assetId, const char *rulesJson)
{
    const BRAssetResolvedFacts *f = stIssuanceFactsFor(st, assetId, 1);
    int has, val;

    apiOf(rulesJson, &has, &val);
    return BRAssetRuleStateForAsset(assetId, f ? f->assetId : NULL, f ? f->hasIssuanceOpcode : 0, f ? f->issuanceOpcode : 0,
                                    f ? f->hasIssuanceLocked : 0, f ? f->issuanceLocked : 0, has, val);
}

static Refusal planRefusal(BRSweepRuleState s)
{
    if (BRAssetRuleAllowsSend(s)) return REF_NONE;
    return s == BR_SWEEP_RULE_BOUND ? REF_RULE_BOUND_ASSET : REF_RULES_UNKNOWN;
}

static BRAssetResolvedFacts sendFacts(int hasOp, int32_t op, int hasL, int l)
{
    return mkFacts("LaRuled", 5, 0, NULL, hasOp, op, hasL, l);
}

static void send_rule_bound(void)
{
    static Store st;
    BRAssetResolvedFacts f = sendFacts(1, 4, 1, 1);

    memset(&st, 0, sizeof(st));
    stSet(&st, "iss", &f);
    ck(planRefusal(transferRuleState(&st, "LaRuled", NULL)) == REF_RULE_BOUND_ASSET, "a rule-bound asset is refused RULE_BOUND_ASSET");
}

static void send_never_walked(void)
{
    static Store st;

    memset(&st, 0, sizeof(st));
    ck(planRefusal(transferRuleState(&st, "LaRuled", NULL)) == REF_RULES_UNKNOWN, "an asset whose issuance was never walked: RULES_UNKNOWN");
}

static void send_proxy_rules(void)
{
    static Store st;
    BRAssetResolvedFacts f = sendFacts(1, 1, 1, 1);

    memset(&st, 0, sizeof(st));
    stSet(&st, "iss", &f);
    ck(planRefusal(transferRuleState(&st, "LaRuled", "{\"royalty\":{\"addresses\":{\"D1\":1}}}")) == REF_RULE_BOUND_ASSET,
       "the proxy's rules object refuses even a rule-free locked issuance");
}

static void send_two_stores(void)
{
    static Store st;
    BRAssetResolvedFacts f = sendFacts(1, 1, 1, 1);

    memset(&st, 0, sizeof(st));
    stSet(&st, "iss", &f);
    ck(transferRuleState(&st, "LaRuled", "{}") == BR_SWEEP_RULE_NONE, "facts(1, locked) and an empty rules object: NONE");
    ck(planRefusal(BR_SWEEP_RULE_NONE) == REF_NONE, "which is not refused");
    ck(transferRuleState(&st, "LaRuled", "{\"changeable\":false}") == BR_SWEEP_RULE_BOUND, "a rules object with a member: BOUND");
}

static void send_no_utxo(void)
{
    // verifyTransferRules: no held UTXO to walk from, so no facts
    ck(BRAssetRuleStateForAsset("LaRuled", NULL, 0, 0, 0, 0, 0, 0) == BR_SWEEP_RULE_UNKNOWN,
       "an asset with no held UTXO to walk from is UNKNOWN");
}

static void send_placeholder(void)
{
    static Store st;
    BRAssetResolvedFacts f = sendFacts(1, 1, 1, 1);

    memset(&st, 0, sizeof(st));
    stSet(&st, "u1", &f);   // the walk's answer would be "LaRuled"
    ck(BRAssetRuleStateForAsset("unresolved:u1", f.assetId, f.hasIssuanceOpcode, f.issuanceOpcode, f.hasIssuanceLocked,
                                f.issuanceLocked, 0, 0) == BR_SWEEP_RULE_UNKNOWN,
       "a placeholder asset id never takes a verdict from the id the walk resolves");
    ck(transferRuleState(&st, "unresolved:u1", NULL) == BR_SWEEP_RULE_UNKNOWN, "nor from the store");
}

// ---- model sweeps -----------------------------------------------------------------------------

static const char *POOL[] = { "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "",
                              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" };

static BRAssetResolvedFacts randFacts(void)
{
    static const char *ids[] = { "LaA", "LaB", "LhC", "", ID_Q1, ID_Q2, ID_Q37, "LaAB" };
    int32_t ops[] = { 1, 2, 3, 4, 5, 0x15, 0, -1, INT32_MAX };
    int hasOp = (int)below(3) != 0;

    return mkFacts(ids[below(N(ids))], (int64_t)below(1000) - 1, (int32_t)below(8), below(2) ? "bafkx" : NULL, hasOp,
                   ops[below(N(ops))], hasOp && below(4) != 0, (int)below(2));
}

static void sweep_walk(void)
{
    static Store st, mst;
    static Hops hs, mhs;
    static Log l1, l2;
    int32_t maxes[] = { -2, -1, 0, 1, 2, 3, 4, 5, 6, 8, 12, BR_ASSET_WALK_DEFAULT_MAX_HOPS };
    int32_t hopsVals[] = { 0, 1, 7, INT32_MAX, INT32_MIN, -1, INT32_MAX - 1 };
    int world, attempt, bad = 0, compared = 0, answered = 0, frontiered = 0;

    for (world = 0; world < 6000 && bad < 3; world++) {
        size_t i, nAttempts = 1 + below(4);

        memset(&st, 0, sizeof(st)); memset(&hs, 0, sizeof(hs));
        for (i = 0; i < N(POOL); i++) {
            switch (below(6)) {
            case 0: case 1: hopsSet(&hs, POOL[i], hopTransfer(POOL[below(N(POOL))])); break;
            case 2: { BRAssetResolvedFacts f = randFacts(); hopsSet(&hs, POOL[i], hopIssuance(&f)); break; }
            case 3: hopsSet(&hs, POOL[i], hopKind(BR_ASSET_HOP_DEAD_END)); break;
            case 4: hopsSet(&hs, POOL[i], hopKind(BR_ASSET_HOP_UNAVAILABLE)); break;
            default: break;   // missing: Unavailable
            }
            if (below(5) == 0) { BRAssetResolvedFacts f = randFacts(); stSet(&st, POOL[i], &f); }
            if (below(6) == 0) stPutFrontier(&st, POOL[i], POOL[below(N(POOL))], hopsVals[below(N(hopsVals))]);
        }
        mst = st; mhs = hs;
        for (attempt = 0; attempt < (int)nAttempts; attempt++) {
            const char *start = POOL[below(N(POOL))];
            int force = (int)below(2), has = 0, mhas;
            int32_t mx = maxes[below(N(maxes))];
            BRAssetResolvedFacts out, mout;
            char d1[16384], d2[16384];
            BRAssetWalkNeed need;

            memset(&l1, 0, sizeof(l1)); memset(&l2, 0, sizeof(l2));
            st.log = &l1; hs.log = &l1; mst.log = &l2; mhs.log = &l2;
            need = hWalk(&st, hopsFn, &hs, start, force, mx, &has, &out);
            mhas = mResolve(&mst, hopsFn, &mhs, start, force, mx, &mout);
            st.log = hs.log = mst.log = mhs.log = NULL;
            stDump(&st, d1, sizeof(d1)); stDump(&mst, d2, sizeof(d2));
            compared++;
            if (has) answered++;
            if (stFront(&st, start)) frontiered++;
            if (need != BR_ASSET_WALK_DONE || has != mhas || (has && ! factsEq(&out, &mout)) || strcmp(l1.s, l2.s) != 0 ||
                strcmp(d1, d2) != 0 || l1.over || l2.over) {
                bad++;
                ck(0, "world %d attempt %d (start \"%s\", force %d, max %d): header \"%.600s\" vs Kotlin \"%.600s\"", world, attempt,
                   start, force, (int)mx, l1.s, l2.s);
            }
        }
    }
    ck(bad == 0, "the header's walk is the Kotlin's over %d attempts (%d answered, %d left a frontier)", compared, answered, frontiered);
    ck(answered > compared / 10 && frontiered > compared / 10, "the sweep reaches answers and frontiers both");
}

static void sweep_hop(void)
{
    static const char *scripts[] = { ISSUE_LOCKED, TRANSFER_SCRIPT, ISSUE_UNLOCKED, ISSUE_HYBRID, ISSUE_DISPERSED, ISSUE_AGG3,
                                     ISSUE_DIV1, ISSUE_DIV7, ISSUE_OP1_CID, ISSUE_OP1_ZERO, ISSUE_OP3_RULES, ISSUE_OP4_RULES,
                                     BURN_SCRIPT, UNCLASSIFIABLE, PLAIN_OPRETURN, P2PKH, "", "6a", "6a0244" };
    uint32_t vouts[] = { 0, 1, 2, 0x7fffffffu, 0x80000000u, 0xffffffffu, 77 };
    int i, bad = 0, exact = 0, kinds[4] = { 0, 0, 0, 0 };
    Buf b;

    for (i = 0; i < 20000 && bad < 3; i++) {
        TIn ins[3];
        TOut outs[4];
        size_t nIn = 1 + below(3), nOut = below(5), k;
        int witness = below(4) == 0;
        char x[300], y[300], tid[65];
        BRAssetHop h, m;

        memset(ins, 0, sizeof(ins));
        for (k = 0; k < nIn; k++) {
            size_t j;
            for (j = 0; j < 32; j++) ins[k].prev[j] = (uint8_t)rnd();
            ins[k].vout = below(3) ? vouts[below(N(vouts))] : (uint32_t)rnd();
            ins[k].sigLen = below(3);
            for (j = 0; j < ins[k].sigLen; j++) ins[k].sig[j] = (uint8_t)rnd();
            if (witness) { ins[k].witItems = (k == 0) || below(2); ins[k].witLen = 1 + below(3); ins[k].wit[0] = 0x55; }
        }
        for (k = 0; k < nOut; k++) { outs[k] = outOf(scripts[below(N(scripts))]); outs[k].amount = below(3) ? 0 : rnd() % 100000; }
        txBuild(&b, ins, nIn, outs, nOut, (uint32_t)rnd(), witness);
        if (! txId(&b, tid)) continue;   // the walk is given only bytes that are exactly a transaction
        exact++;
        h = hHopOfBytes(b.b, b.n);
        m = mHop(b.b, b.n);
        if ((int)h.kind >= 0 && (int)h.kind < 4) kinds[h.kind]++;
        if (! hopEq(&h, &m)) { bad++; ck(0, "transaction %d: header %s, Kotlin %s", i, hopStr(&h, x, sizeof(x)), hopStr(&m, y, sizeof(y))); }
    }
    {
        BRAssetHop h = hHopOfBytes(NULL, 0), m = mHop(NULL, 0);
        if (! hopEq(&h, &m)) { bad++; ck(0, "no bytes differ"); }
    }
    ck(bad == 0, "the header's hop is the Kotlin's on %d exact transactions (dead end %d, transfer %d, issuance %d)", exact,
       kinds[BR_ASSET_HOP_DEAD_END], kinds[BR_ASSET_HOP_TRANSFER], kinds[BR_ASSET_HOP_ISSUANCE]);
    ck(exact > 15000 && kinds[BR_ASSET_HOP_TRANSFER] > 500 && kinds[BR_ASSET_HOP_ISSUANCE] > 500 && kinds[BR_ASSET_HOP_DEAD_END] > 500,
       "the sweep is mostly exact transactions and reaches every kind");
}

static void sweep_named(void)
{
    static const char *ids[] = { "LaA", "LaB", "LaC", ID_Q1, ID_Q2, ID_Q37, "LaAB" };
    static const char *txs[] = { "s", "p1", "p2", "p3", "p4" };
    BRAssetResolvedFacts facts[N(ids)], out;
    int i, bad = 0, answered = 0;

    for (i = 0; i < (int)N(ids); i++) facts[i] = mkFacts(ids[i], i, 0, NULL, 1, 1, 1, 1);
    for (i = 0; i < 20000 && bad < 3; i++) {
        Named n;
        const char *parents[6];
        size_t k;
        const char *asset = ids[below(N(ids))], *start = txs[below(N(txs))];

        memset(&n, 0, sizeof(n));
        n.knownFacts = below(3) ? NULL : &facts[below(N(ids))];
        for (k = 0; k < N(txs); k++) { n.resolveTx[k] = txs[k]; n.resolveAns[k] = below(3) ? &facts[below(N(ids))] : NULL; }
        n.nResolve = N(txs);
        n.nParents = below(7);
        for (k = 0; k < n.nParents; k++) parents[k] = txs[below(N(txs))];
        n.parents = parents;
        FAILED = 0;
        if (namedBoth(&n, asset, start, &out, "sweep")) answered++;
        if (FAILED) bad++;
    }
    FAILED = bad != 0;
    ck(bad == 0, "the header's search is the Kotlin's over 20000 tables (%d answered)", answered);
}

// Kotlin stateOf, line for line, with Boolean? as has/value
static BRSweepRuleState mStateOf(int hasOp, int32_t op, int hasL, int l, int hasA, int a)
{
    if (hasOp && (op == 3 || op == 4)) return BR_SWEEP_RULE_BOUND;
    if (hasA && a != 0) return BR_SWEEP_RULE_BOUND;
    if (hasOp && (op == 1 || op == 2 || op == 5) && hasL && l != 0) return BR_SWEEP_RULE_NONE;
    return BR_SWEEP_RULE_UNKNOWN;
}

static void sweep_gate(void)
{
    int32_t ops[] = { INT32_MIN, -1, 0, 1, 2, 3, 4, 5, 6, 0x15, 0x25, 255, INT32_MAX };
    int vals[] = { 0, 1, 2, -1, INT_MAX };
    static const char *ids[] = { "LaA", "LaB", "", ID_Q1, ID_Q2, ID_Q37, "LaAB" };
    size_t o, i;
    int hasOp, hasL, hasA, l, a, n = 0, bad = 0;

    for (o = 0; o < N(ops); o++) for (hasOp = 0; hasOp < 2; hasOp++) for (hasL = 0; hasL < 2; hasL++)
    for (l = 0; l < (int)N(vals); l++) for (hasA = 0; hasA < 2; hasA++) for (a = 0; a < (int)N(vals); a++) {
        BRSweepRuleState want = mStateOf(hasOp, ops[o], hasL, vals[l], hasA, vals[a]);
        n++;
        if (BRAssetRuleStateOf(hasOp, ops[o], hasL, vals[l], hasA, vals[a]) != want) bad++;
        for (i = 0; i < N(ids) * N(ids); i++) {
            const char *asked = ids[i / N(ids)], *named = ids[i % N(ids)];
            BRSweepRuleState w2 = strcmp(asked, named) == 0 ? want : BR_SWEEP_RULE_UNKNOWN;
            if (BRAssetRuleStateForAsset(asked, named, hasOp, ops[o], hasL, vals[l], hasA, vals[a]) != w2) bad++;
        }
        if (BRAssetRuleStateForAsset("LaA", NULL, hasOp, ops[o], hasL, vals[l], hasA, vals[a]) != BR_SWEEP_RULE_UNKNOWN) bad++;
    }
    ck(bad == 0, "the gate is the Kotlin's on every one of %d inputs (%d differ)", n, bad);
}

static void two_units(void)
{
    ck(asset_provenance_rules_kat_tu2() == 1, "a second translation unit, including both headers twice, links and answers");
}

// ---- driver -----------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;

static const Case CASES[] = {
    { "walker_short_chain", walker_short_chain },
    { "walker_deep_chain", walker_deep_chain },
    { "walker_resume_no_rewalk", walker_resume_no_rewalk },
    { "walker_memory", walker_memory },
    { "walker_known_ancestor", walker_known_ancestor },
    { "walker_transient_keeps_frontier", walker_transient_keeps_frontier },
    { "walker_dead_end_clears", walker_dead_end_clears },
    { "walker_cycle", walker_cycle },
    { "walker_default_mode_cache", walker_default_mode_cache },
    { "walker_force_past_start", walker_force_past_start },
    { "walker_force_past_ancestor", walker_force_past_ancestor },
    { "walker_force_unreachable", walker_force_unreachable },
    { "walker_issuance_rows", walker_issuance_rows },
    { "binding_different_tx_unavailable", binding_different_tx_unavailable },
    { "binding_next_endpoint", binding_next_endpoint },
    { "binding_no_id_no_answer", binding_no_id_no_answer },
    { "binding_wrong_answer_refused", binding_wrong_answer_refused },
    { "binding_guard_requested", binding_guard_requested },
    { "binding_guard_upper_case", binding_guard_upper_case },
    { "hop_carriers", hop_carriers },
    { "hop_rules", hop_rules },
    { "hop_edges", hop_edges },
    { "walk_refusals", walk_refusals },
    { "walk_hop_counts", walk_hop_counts },
    { "walk_path_rules", walk_path_rules },
    { "capacity", capacity },
    { "zero_structs", zero_structs },
    { "named_rules", named_rules },
    { "named_through_walk", named_through_walk },
    { "named_edges", named_edges },
    { "gate_opcode_3", gate_opcode_3 },
    { "gate_opcode_4", gate_opcode_4 },
    { "gate_api_rules", gate_api_rules },
    { "gate_locked_rule_free", gate_locked_rule_free },
    { "gate_unlocked_unknown", gate_unlocked_unknown },
    { "gate_no_opcode", gate_no_opcode },
    { "gate_outside_set", gate_outside_set },
    { "gate_never_loosens", gate_never_loosens },
    { "gate_send_state", gate_send_state },
    { "gate_enum_shared", gate_enum_shared },
    { "gate_for_asset", gate_for_asset },
    { "send_rule_bound", send_rule_bound },
    { "send_never_walked", send_never_walked },
    { "send_proxy_rules", send_proxy_rules },
    { "send_two_stores", send_two_stores },
    { "send_no_utxo", send_no_utxo },
    { "send_placeholder", send_placeholder },
    { "sweep_walk", sweep_walk },
    { "sweep_hop", sweep_hop },
    { "sweep_named", sweep_named },
    { "sweep_gate", sweep_gate },
    { "two_units", two_units },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IOLBF, 0);
    initFacts();
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name); return 0; }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
            return FAILED ? 1 : 0;
        }
    }
    fprintf(stderr, "no case %s\n", argv[1]);
    return 2;
}
