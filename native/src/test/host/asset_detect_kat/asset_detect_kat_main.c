//
//  asset_detect_kat_main.c
//
//  Host KAT for BRAssetDetect.h: DigiAsset detection, what the wallet writes about its own outputs of a
//  transaction that carries a DigiAsset payload (BRAssetDetect, BRAssetDetectBounds, the claim
//  BRAssetClaimForOutput / BRAssetClaimRemainder / BRAssetClaimForOutputTotal, BRAssetDetectRuleFree,
//  BRAssetDetectRename, BRAssetDetectFactsWalk), and the two entry points BRAssetHold.h gained for it
//  (BRAssetHoldCarrierOf, BRAssetHoldInputFacts). Moved from Android's Kotlin (core/.../asset/
//  AssetManager.kt: processIncomingAssetTx and what it calls; AssetTxQuantity.kt: forOutput,
//  implicitChange, forOutputTotal). Every expectation is fixed OUTSIDE the header, two ways:
//
//    1. BY HAND. The Kotlin tests' assertions on this path, value for value: AssetTxQuantityTest,
//       AssetImplicitChangeTest and the claim assertions of AssetArithmeticNeverWrapsTest (claim_*);
//       UnbackedAssetCreditTest (recv_*) and the receive cases of AssetArithmeticNeverWrapsTest (arith_*),
//       each driven through BRAssetDetect from a real transaction, its writes applied to a table, the
//       indexer asked as the platform asks it (BRAssetIndexerAskDecide, BRAssetStackAnswerAccept) and the
//       balance read by BRAssetHeldBalances; AssetPersistOnDetectTest's protect cases and
//       AssetZeroQuantityInputTest's detection case asked of `targeted` (protect_*). The other cases are
//       this suite's own (which outputs get a row, the placeholder, all settled, the rules step, the
//       walks, the rename, refusals, capacity), their expectations read off the Kotlin source.
//       Not carried, because they have no form here: the bridge reads and their failures, the metadata
//       fetch, the history label (transactionDao.updateAssetId, receivedBackedUnits), the persist-after-
//       detect snapshot (AssetPersistOnDetectTest's first three), log lines, and the allocator's and the
//       decoder's own tests (asset_allocate_kat, asset_decode_kat).
//    2. BY A MODEL. `m...` below is processIncomingAssetTx written out line for line in C: assetCarrier,
//       the derivation, resolveInputAssetUnits with the TargetingWalk (a fresh walk per question, as
//       detection's calls make it), backedInputStack, nativeParentHasDataOutput, the ownership gate, all
//       settled, transferOutputCredits / issuanceOutputCredits, AssetTxQuantity.forOutputTotal,
//       persistDetectedAssetOutput with settledRow, the indexer condition and the walk condition. The
//       ported pieces it calls are the ones other suites hold to the Kotlin: the decoder (asset_decode_kat),
//       BRAssetTargetsOutput and BRAssetAssignedUnits (asset_quantity_targets_kat), the allocator,
//       BRAssetCreditForOutput and BRAssetMaxDeliverable (asset_allocate_kat), BRAssetIdForIssuance
//       (asset_allocate_kat). model_sweep holds BRAssetDetect to it over fixed pseudo-random wallets, the
//       rules step included.
//
//  One case per process: `asset_detect_kat <case>` prints PASS:/FAIL: lines and `RESULT <case> pass|fail`;
//  `list` prints the case names. run.sh builds one binary per arm and holds each comparison arm to failing
//  exactly its cases.
//

#include "BRAssetDetect.h"
#include "BRAssetEncode.h"
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_detect_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice

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

// ---- hex, a seeded stream -------------------------------------------------------------------

static int BROKEN = 0;   // a fixture this file's pools cannot hold: a broken case, never an answer

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
    const char *p;

    for (p = hex; *p; p++) {
        int d = hexDigit(*p);

        if (d < 0 || (hi >= 0 && n >= cap)) { BROKEN = 1; return 0; }
        if (hi < 0) hi = d;
        else {
            out[n++] = (uint8_t)(hi * 16 + d);
            hi = -1;
        }
    }
    if (hi >= 0) BROKEN = 1;
    return n;
}

static uint64_t RNG = 0x9e3779b97f4a7c15ULL;

static uint64_t rnd(void)   // xorshift64
{
    RNG ^= RNG << 13;
    RNG ^= RNG >> 7;
    RNG ^= RNG << 17;
    return RNG;
}

static uint64_t below(uint64_t n) { return n ? rnd() % n : 0; }

// ---- transactions -----------------------------------------------------------------------------
//
// A transaction is a BRTransaction in this file's pools. Its txid is a tag: tid(7) is 07 00 00 00 a5 ...
// in BRTransaction's order. Outputs are appended to one arena, so a transaction's outputs are built
// before the next transaction is started.

#define TMAX 64
#define IMAX 6
#define OARENA 4096
#define ARENA (1 << 18)
#define RMAX 512
#define PMAX 32
#define QMAX 16
#define AMAX 16
#define DMAX 20000

typedef struct {
    BRTransaction tx;
    BRTxInput ins[IMAX];
} Fx;

static Fx POOL[TMAX];
static size_t POOLN = 0;
static BRTxOutput OUTS[OARENA];
static size_t OUTSN = 0;
static uint8_t BYTES[ARENA];
static size_t BYTESN = 0;

static UInt256 tid(uint32_t tag)
{
    UInt256 h;

    memset(h.u8, 0xa5, sizeof(h.u8));
    h.u8[0] = (uint8_t)tag;
    h.u8[1] = (uint8_t)(tag >> 8);
    h.u8[2] = (uint8_t)(tag >> 16);
    h.u8[3] = (uint8_t)(tag >> 24);
    return h;
}

// The txid as rows and the bridge write it: display order, lower case.
static void hexOf(UInt256 h, char *out)
{
    static const char d[] = "0123456789abcdef";
    int i;

    for (i = 0; i < 32; i++) {
        out[2 * i] = d[h.u8[31 - i] >> 4];
        out[2 * i + 1] = d[h.u8[31 - i] & 15];
    }
    out[64] = '\0';
}

static uint8_t *arena(size_t n)
{
    uint8_t *p;

    if (BYTESN + n > ARENA) { BROKEN = 1; BYTESN = 0; }
    p = &BYTES[BYTESN];
    BYTESN += n;
    return p;
}

static Fx *fx(uint32_t tag)
{
    Fx *f;

    if (POOLN >= TMAX) { BROKEN = 1; POOLN = 0; }
    f = &POOL[POOLN++];
    memset(f, 0, sizeof(*f));
    f->tx.txHash = tid(tag);
    f->tx.inputs = f->ins;
    f->tx.outputs = &OUTS[OUTSN];
    f->tx.version = 1;
    return f;
}

static void outBytes(Fx *f, const uint8_t *script, size_t len, uint64_t sats)
{
    BRTxOutput *o;

    if (OUTSN >= OARENA || f->tx.outputs + f->tx.outCount != &OUTS[OUTSN]) { BROKEN = 1; return; }
    o = &OUTS[OUTSN++];
    memset(o, 0, sizeof(*o));
    o->script = arena(len ? len : 1);
    if (len) memcpy(o->script, script, len);
    o->scriptLen = len;
    o->amount = sats;
    f->tx.outCount++;
}

static void out(Fx *f, const char *hex)
{
    uint8_t b[300];
    size_t n = hx(hex, b, sizeof(b));

    outBytes(f, b, n, 600);
}

#define OURS   "0014111111111111111111111111111111111111111111"
#define OURS2  "0014333333333333333333333333333333333333333333"
#define THEIRS "0014222222222222222222222222222222222222222222"
#define PLAIN_NOTE "6a0568656c6c6f"                    // OP_RETURN "hello": not a carrier
#define UNCLASSIFIABLE_PD2 "6a4d060044410215000a"       // a carrier framed with OP_PUSHDATA2

static void ours(Fx *f) { out(f, OURS); }
static void theirs(Fx *f) { out(f, THEIRS); }

static void in(Fx *f, UInt256 funding, uint32_t index)
{
    if (f->tx.inCount >= IMAX) { BROKEN = 1; return; }
    f->ins[f->tx.inCount].txHash = funding;
    f->ins[f->tx.inCount].index = index;
    f->ins[f->tx.inCount].sequence = 0xffffffff;
    f->tx.inCount++;
}

// ---- carrier scripts ----------------------------------------------------------------------------

typedef struct { uint8_t b[200]; size_t bits; } Bits;

static void put(Bits *w, uint64_t v, int n)
{
    int i;

    for (i = n - 1; i >= 0; i--) {
        size_t byte = w->bits / 8;

        if (byte >= sizeof(w->b)) { BROKEN = 1; return; }
        if (w->bits % 8 == 0) w->b[byte] = 0;
        if ((v >> i) & 1) w->b[byte] |= (uint8_t)(0x80 >> (w->bits % 8));
        w->bits++;
    }
}

static void fixedAmount(Bits *w, uint64_t a)   // the 1-byte form below 32, the 2-byte form below 512
{
    if (a < 32) { put(w, 0, 3); put(w, a, 5); }
    else { put(w, 1, 3); put(w, a & 511, 9); put(w, 0, 4); }
}

typedef struct { int skip, range, percent; uint32_t out; uint64_t amount; } GInst;

static void instruction(Bits *w, const GInst *g)
{
    put(w, (uint64_t)g->skip, 1);
    put(w, (uint64_t)g->range, 1);
    put(w, (uint64_t)g->percent, 1);
    put(w, g->out, g->range ? 13 : 5);
    if (g->percent) put(w, g->amount & 255, 8);
    else fixedAmount(w, g->amount);
}

// OP_RETURN and one direct push of a carrier: version, opcode (0x05 an issuance of `total` with `flags`,
// 0x15 a transfer, 0x25 a burn), the instructions, and `extra` junk bytes after them.
static size_t carrier(int version, int opcode, uint64_t total, int flags, const GInst *insts, size_t n, int extra,
                      uint8_t *script)
{
    Bits w;
    size_t i, len, k = 0;

    memset(&w, 0, sizeof(w));
    put(&w, 0x4441, 16);
    put(&w, (uint64_t)version, 8);
    put(&w, (uint64_t)opcode, 8);
    if (opcode == 0x05) fixedAmount(&w, total);
    for (i = 0; i < n; i++) instruction(&w, &insts[i]);
    while (w.bits % 8) put(&w, 0, 1);
    for (i = 0; (int)i < extra; i++) put(&w, 0x5a, 8);
    if (opcode == 0x05) put(&w, (uint64_t)flags, 8);
    len = w.bits / 8;
    script[k++] = 0x6a;
    script[k++] = (uint8_t)len;
    memcpy(script + k, w.b, len);
    return k + len;
}

static void outCarrier(Fx *f, int version, int opcode, uint64_t total, int flags, const GInst *insts, size_t n, int extra)
{
    uint8_t script[220];
    size_t len = carrier(version, opcode, total, flags, insts, n, extra, script);

    outBytes(f, script, len, 0);
}

// BRAssetEncodeTransferScript of `n` instructions (Kotlin: DigiAssetEncoder.encodeTransferScript), as hex.
static const char *encodeTransfer(const BRAssetTransferInstruction *insts, size_t n)
{
    static char hex[2 * BR_ASSET_ENCODE_MAX_SCRIPT + 1];
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
    size_t len = 0, i;

    if (BRAssetEncodeTransferScript(3, insts, n, s, sizeof(s), &len, NULL) != BR_ASSET_ENCODE_OK) { BROKEN = 1; return ""; }
    for (i = 0; i < len; i++) snprintf(hex + 2 * i, 3, "%02x", s[i]);
    hex[2 * len] = '\0';
    return hex;
}

static BRAssetTransferInstruction ti(int32_t outputIndex, int64_t amount, int range, int percent)
{
    BRAssetTransferInstruction t;

    memset(&t, 0, sizeof(t));
    t.outputIndex = outputIndex;
    t.amount = amount;
    t.range = range;
    t.percent = percent;
    return t;
}

// ---- the table of rows ----------------------------------------------------------------------------

typedef struct {
    char txid[80];
    UInt256 h;                       // txid parsed, when it is one
    int32_t vout;
    uint8_t script[64];
    size_t scriptLen;
    int64_t sats, height;
    int isAsset;
    int hasId;
    char id[96];
    int64_t qty;
    int spent;
    BRAssetSource source;
    BRAssetCredit credit;
    int hasAsked;                    // the platform's record of when the indexer was last asked
    int64_t askedAt;
} KRow;

static KRow ROWS[RMAX];
static size_t ROWN = 0;
static BRAssetRow VROWS[RMAX];

#define X "Laxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"   // "La" + "x".repeat(36)
#define Y "Layyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"
#define A "Laaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
#define B "Labbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

static void syncView(void)
{
    size_t i;

    for (i = 0; i < ROWN; i++) {
        KRow *k = &ROWS[i];
        BRAssetRow *r = &VROWS[i];

        memset(r, 0, sizeof(*r));
        r->txid = k->txid;
        r->vout = k->vout;
        r->script = k->scriptLen ? k->script : NULL;
        r->scriptLen = k->scriptLen;
        r->satoshis = k->sats;
        r->blockHeight = k->height;
        r->isAsset = k->isAsset;
        r->assetId = k->hasId ? k->id : NULL;
        r->assetQuantity = k->qty;
        r->spent = k->spent;
        r->source = k->source;
        r->credit = k->credit;
    }
}

static KRow *rowAt(const char *txid, int32_t vout)   // Kotlin: utxoDao.getAssetUtxoAt
{
    size_t i;

    for (i = 0; i < ROWN; i++) if (ROWS[i].isAsset && ROWS[i].vout == vout && strcmp(ROWS[i].txid, txid) == 0) return &ROWS[i];
    return NULL;
}

static KRow *rowOf(UInt256 h, int32_t vout)
{
    char t[65];

    hexOf(h, t);
    return rowAt(t, vout);
}

static void setId(KRow *k, const char *id)
{
    k->hasId = id != NULL;
    k->id[0] = '\0';
    if (id) {
        if (strlen(id) >= sizeof(k->id)) { BROKEN = 1; return; }
        strcpy(k->id, id);
    }
}

static KRow *addRowText(const char *txid, int32_t vout, const char *scriptHex, BRAssetCredit credit, int64_t qty,
                        const char *id, int spent)
{
    KRow *k;
    size_t i;

    if (ROWN >= RMAX) { BROKEN = 1; ROWN = 0; }
    k = &ROWS[ROWN++];
    memset(k, 0, sizeof(*k));
    if (strlen(txid) >= sizeof(k->txid)) { BROKEN = 1; return k; }
    strcpy(k->txid, txid);
    for (i = 0; i < 32 && strlen(txid) == 64; i++) k->h.u8[31 - i] = (uint8_t)(hexDigit(txid[2 * i]) * 16 + hexDigit(txid[2 * i + 1]));
    k->vout = vout;
    k->scriptLen = scriptHex ? hx(scriptHex, k->script, sizeof(k->script)) : 0;
    k->sats = 600;
    k->height = 1;
    k->isAsset = 1;
    setId(k, id);
    k->qty = qty;
    k->spent = spent;
    k->source = BR_ASSET_SOURCE_NATIVE;
    k->credit = credit;
    return k;
}

static KRow *addRow(UInt256 h, int32_t vout, const char *scriptHex, BRAssetCredit credit, int64_t qty, const char *id, int spent)
{
    char t[65];

    hexOf(h, t);
    return addRowText(t, vout, scriptHex, credit, qty, id, spent);
}

// ---- the wallet -----------------------------------------------------------------------------------

typedef enum { R_FREE, R_NOFACTS, R_UNLOCKED, R_BOUND, R_API, R_OTHER } RuleKind;

typedef struct {
    char id[96];
    RuleKind kind;
    BRAssetResolvedFacts facts;
} KRule;

static struct {
    const BRTransaction *txs[TMAX];
    size_t n;
    UInt256 proven[PMAX];
    size_t provenN;
    int ownedN;                      // 0: no owned set; 1: OURS; 2: OURS and OURS2
    KRule rules[QMAX];
    size_t ruleN;
    int64_t height;
    int outgoing;
    int walked;
    int indexerOn;
    RuleKind autoKind;               // what the platform answers for an id asked about
} W;

static BRAssetScript OWNED[2];
static uint8_t OWNED_BYTES[2][32];
static BRAssetDetectRule VRULES[QMAX];

typedef struct {
    char txid[65];
    int32_t vout;
    BRAssetStackLookup lookup;
    BRAssetUnits e[4];
    char ids[4][48];
    size_t n;
} KAnswer;

static KAnswer ANS[AMAX];
static size_t ANSN = 0;
static int LOOKUPS = 0;

static void wreset(void)
{
    memset(&W, 0, sizeof(W));
    POOLN = 0;
    OUTSN = 0;
    BYTESN = 0;
    ROWN = 0;
    ANSN = 0;
    LOOKUPS = 0;
    W.ownedN = 1;
    W.indexerOn = 1;
    W.autoKind = R_FREE;
    W.walked = 1;
    OWNED[0].bytes = OWNED_BYTES[0]; OWNED[0].len = hx(OURS, OWNED_BYTES[0], sizeof(OWNED_BYTES[0]));
    OWNED[1].bytes = OWNED_BYTES[1]; OWNED[1].len = hx(OURS2, OWNED_BYTES[1], sizeof(OWNED_BYTES[1]));
}

static void wadd(Fx *f)
{
    if (W.n >= TMAX) { BROKEN = 1; return; }
    W.txs[W.n++] = &f->tx;
}

static void prove(UInt256 h)
{
    if (W.provenN >= PMAX) { BROKEN = 1; return; }
    W.proven[W.provenN++] = h;
}

static void rule(const char *id, RuleKind kind)
{
    KRule *r;

    if (W.ruleN >= QMAX) { BROKEN = 1; return; }
    r = &W.rules[W.ruleN++];
    memset(r, 0, sizeof(*r));
    strcpy(r->id, id);
    r->kind = kind;
    if (strlen(id) <= BR_ASSET_ID_LENGTH) strcpy(r->facts.assetId, (kind == R_OTHER) ? (strcmp(id, Y) ? Y : X) : id);
    r->facts.hasIssuanceOpcode = 1;
    r->facts.issuanceOpcode = (kind == R_BOUND) ? 3 : (kind == R_UNLOCKED) ? 1 : 5;
    r->facts.hasIssuanceLocked = 1;
    r->facts.issuanceLocked = (kind == R_UNLOCKED) ? 0 : 1;
}

static void viewFor(BRAssetDetectView *v, const BRTransaction *tx)
{
    size_t i;

    syncView();
    for (i = 0; i < W.ruleN; i++) {
        KRule *r = &W.rules[i];

        memset(&VRULES[i], 0, sizeof(VRULES[i]));
        VRULES[i].assetId = r->id;
        VRULES[i].issuance = (r->kind == R_NOFACTS || strlen(r->id) > BR_ASSET_ID_LENGTH) ? NULL : &r->facts;
        VRULES[i].hasApiRules = (r->kind == R_API) ? 1 : (r->kind == R_FREE) ? 1 : 0;
        VRULES[i].apiRules = (r->kind == R_API) ? 1 : 0;
    }
    memset(v, 0, sizeof(*v));
    v->tx = tx;
    v->blockHeight = W.height;
    v->isOutgoingUnconfirmed = W.outgoing;
    v->txs = W.txs;
    v->txCount = W.n;
    v->owned = W.ownedN ? OWNED : NULL;
    v->ownedCount = (size_t)W.ownedN;
    v->rows = VROWS;
    v->rowCount = ROWN;
    v->proven = W.proven;
    v->provenCount = W.provenN;
    v->rules = VRULES;
    v->ruleCount = W.ruleN;
    v->walkedInSession = W.walked;
}

// ---- the buffers, sized as BRAssetDetectBounds says ------------------------------------------------

static BRAssetHoldWork BWORK[RMAX + TMAX];
static BRAssetHoldRow BHOLD[RMAX];
static BRAssetInputFacts BIN[IMAX];
static size_t BINROWS[IMAX];
static BRAssetUnits BSLOTS[IMAX];
static BRAssetInputStack BSTACKS[IMAX];
static int64_t BALLOC[IMAX];
static const char *BASKS[IMAX];
static size_t BOUTROWS[OARENA];
static BRAssetDetectOutput BOUTS[OARENA];
static BRAssetOutputUnits BDELIV[DMAX];
static BRAssetDetection DET;
static BRAssetDetectBuffers BUF;

static void buffersFor(const BRAssetDetectNeeds *n)
{
    memset(&BUF, 0, sizeof(BUF));
    if (n->work > N(BWORK) || n->holdRows > N(BHOLD) || n->inputs > IMAX || n->outputs > OARENA || n->delivered > DMAX) {
        BROKEN = 1;
        return;
    }
    BUF.work = BWORK; BUF.workCap = n->work;
    BUF.holdRows = BHOLD; BUF.holdRowCap = n->holdRows;
    BUF.inputs = BIN; BUF.inputRows = BINROWS; BUF.slots = BSLOTS; BUF.stacks = BSTACKS; BUF.allocWork = BALLOC;
    BUF.asks = BASKS; BUF.inputCap = n->inputs;
    BUF.outputRows = BOUTROWS; BUF.outputs = BOUTS; BUF.outputCap = n->outputs;
    BUF.delivered = BDELIV; BUF.deliveredCap = n->delivered;
}

static BRAssetDetectStatus detectOnce(const BRTransaction *tx)
{
    BRAssetDetectView v;
    BRAssetDetectNeeds needs;

    viewFor(&v, tx);
    if (! BRAssetDetectBounds(&v, &needs)) memset(&needs, 0, sizeof(needs));
    buffersFor(&needs);
    return BRAssetDetect(&v, &BUF, &DET);
}

// BRAssetDetect, answering what it asks about with W.autoKind, as the platform would.
static BRAssetDetectStatus detect(const BRTransaction *tx)
{
    BRAssetDetectStatus st = detectOnce(tx);
    size_t i;

    if (st == BR_ASSET_DETECT_NEEDS_RULES) {
        size_t asks = DET.askCount;
        char ids[IMAX][96];

        for (i = 0; i < asks && i < IMAX; i++) snprintf(ids[i], sizeof(ids[i]), "%s", BASKS[i]);
        for (i = 0; i < asks && i < IMAX; i++) rule(ids[i], W.autoKind);
        st = detectOnce(tx);
        ck(st != BR_ASSET_DETECT_NEEDS_RULES, "once every asked id is answered, the detection asks nothing more");
    }
    return st;
}

// The answer the indexer gives for output `vout` of `tx`: lookup, then `entries` "<id>:<count>,...".
static void indexer(const BRTransaction *tx, int32_t vout, BRAssetStackLookup lookup, const char *entries)
{
    KAnswer *a;
    const char *p = entries;
    size_t i;

    char t[65];

    hexOf(tx->txHash, t);
    for (i = 0; i < ANSN; i++) if (ANS[i].vout == vout && strcmp(ANS[i].txid, t) == 0) break;
    if (i == ANSN) {
        if (ANSN >= AMAX) { BROKEN = 1; return; }
        ANSN++;
    }
    a = &ANS[i];
    memset(a, 0, sizeof(*a));
    hexOf(tx->txHash, a->txid);
    a->vout = vout;
    a->lookup = lookup;
    while (p && *p && a->n < 4) {
        const char *colon = strchr(p, ':'), *comma;

        if (! colon) { BROKEN = 1; return; }
        memcpy(a->ids[a->n], p, (size_t)(colon - p));
        a->ids[a->n][colon - p] = '\0';
        a->e[a->n].assetId = a->ids[a->n];
        a->e[a->n].count = strtoll(colon + 1, NULL, 10);
        a->n++;
        comma = strchr(colon, ',');
        p = comma ? comma + 1 : NULL;
    }
}

static const KAnswer *answerFor(const char *txid, int32_t vout)
{
    size_t i;

    for (i = 0; i < ANSN; i++) if (ANS[i].vout == vout && strcmp(ANS[i].txid, txid) == 0) return &ANS[i];
    return NULL;
}

// Kotlin: settleFromIndexer for one output of the last detection, at time `now`.
static void settle(const BRAssetDetectOutput *o, int64_t now)
{
    KRow *k = rowAt(DET.txid, o->vout);
    const KAnswer *a;
    BRAssetStackAcceptance acc;
    BRAssetRow r;
    char id[96];

    if (! W.indexerOn || ! k) return;
    syncView();
    r = VROWS[k - ROWS];
    if (BRAssetIndexerAskDecide(&r, k->hasAsked, k->askedAt, now) != BR_ASSET_ASK_NOW) return;
    k->hasAsked = 1;
    k->askedAt = now;
    LOOKUPS++;
    a = answerFor(DET.txid, o->vout);
    acc = BRAssetStackAnswerAccept(a ? a->lookup : BR_ASSET_STACK_UNAVAILABLE, a ? a->e : NULL, a ? a->n : 0, o->bound,
                                   DET.placeholder);
    if (acc.verdict != BR_ASSET_ACCEPT_SETTLED) return;
    snprintf(id, sizeof(id), "%s", acc.row.assetId);
    setId(k, id);
    k->qty = acc.row.quantity;
    k->credit = acc.row.credit;
    k->hasAsked = 0;
}

static void settleAll(int64_t now)
{
    size_t i;

    for (i = 0; i < DET.outputCount; i++) if (BOUTS[i].askIndexer) settle(&BOUTS[i], now);
}

// Writes what the last detection says, output by output, and asks the indexer after each write.
static void apply(int64_t now)
{
    size_t i;

    if (DET.status != BR_ASSET_DETECT_DONE) return;
    for (i = 0; i < DET.outputCount; i++) {
        const BRAssetDetectOutput *o = &BOUTS[i];
        const BRAssetRow *r = &o->persist.row;
        KRow *k = NULL, tmp;

        if (o->persist.action == BR_ASSET_PERSIST_INSERT) {
            k = addRowText(r->txid, r->vout, NULL, r->credit, r->assetQuantity, NULL, r->spent);
        }
        else if (o->persist.action == BR_ASSET_PERSIST_UPDATE) k = rowAt(r->txid, r->vout);
        if (k) {
            memset(&tmp, 0, sizeof(tmp));
            tmp = *k;
            if (r->scriptLen > sizeof(tmp.script)) { BROKEN = 1; return; }
            memcpy(tmp.script, r->script ? r->script : (const uint8_t *)"", r->scriptLen);
            tmp.scriptLen = r->scriptLen;
            tmp.sats = r->satoshis;
            tmp.height = r->blockHeight;
            tmp.isAsset = r->isAsset;
            setId(&tmp, r->assetId);
            tmp.qty = r->assetQuantity;
            tmp.spent = r->spent;
            tmp.source = r->source;
            tmp.credit = r->credit;
            *k = tmp;
        }
        if (o->askIndexer) settle(o, now);
    }
}

static const BRAssetDetectOutput *outAt(int32_t vout)
{
    size_t i;

    for (i = 0; i < DET.outputCount; i++) if (BOUTS[i].vout == vout) return &BOUTS[i];
    return NULL;
}

static KRow *detectedRow(int32_t vout) { return rowAt(DET.txid, vout); }

// The balance the Assets screen shows: every asset with a quantity above zero, "<id>:<qty>" sorted, or "-".
static const char *balances(void)
{
    static char text[512];
    BRAssetHeldBalance bal[16];
    int states[RMAX];
    size_t i, j, n = 0, at = 0;
    const char *ids[16];
    int64_t q[16];

    syncView();
    for (i = 0; i < ROWN; i++) states[i] = BR_ASSET_STATE_HELD;
    if (BRAssetHeldBalances(VROWS, ROWN, states, OWNED, 2, bal, 16, &n) != BR_ASSET_HOLDINGS_OK) return "refused";
    for (i = 0, j = 0; i < n; i++) if (bal[i].quantity > 0) { ids[j] = bal[i].assetId; q[j] = bal[i].quantity; j++; }
    n = j;
    for (i = 0; i < n; i++) for (j = i + 1; j < n; j++) if (strcmp(ids[j], ids[i]) < 0) {
        const char *t = ids[i]; int64_t u = q[i];
        ids[i] = ids[j]; q[i] = q[j]; ids[j] = t; q[j] = u;
    }
    text[0] = '\0';
    for (i = 0; i < n; i++) at += (size_t)snprintf(text + at, sizeof(text) - at, "%s%.3s:%lld", i ? "," : "", ids[i], (long long)q[i]);
    return n ? text : "-";
}

static const char *STATUS[] = { "REFUSED", "DONE", "NOT_ASSET", "UNCLASSIFIABLE", "NEEDS_RULES", "ARGUMENTS",
                                "UNREADABLE", "ROWS", "CAPACITY" };

static const char *st(BRAssetDetectStatus s) { return ((unsigned)s < N(STATUS)) ? STATUS[s] : "?"; }

static void statusIs(const char *what, BRAssetDetectStatus got, BRAssetDetectStatus want)
{
    ck(got == want, "%s: %s (want %s)", what, st(got), st(want));
}

// ================================================================================================
// THE MODEL: processIncomingAssetTx, line for line
// ================================================================================================

typedef struct { int has; int64_t v; } OL;   // Long?

static OL some(int64_t v) { OL o; o.has = 1; o.v = v; return o; }
static OL none(void) { OL o; o.has = 0; o.v = 0; return o; }

typedef struct {
    BRAssetHeader h;
    BRAssetTransferInstruction insts[BR_ASSET_MAX_INSTRUCTIONS];
} MHeader;

typedef struct {
    MHeader header;
    int hasFirst; int32_t first;
    int32_t outputCount;
} MShape;

typedef struct { UInt256 h; uint32_t v; } MInput;

static const BRTransaction *mOutputsOf(UInt256 h)   // the bridge: the wallet's own transactions only
{
    size_t i;

    for (i = 0; i < W.n; i++) if (UInt256Eq(W.txs[i]->txHash, h)) return W.txs[i];
    return NULL;
}

static int mIsOpReturn(const BRTxOutput *o) { return o->scriptLen > 0 && o->script[0] == 0x6a; }

static BRAssetScriptKind mDecode(const BRTxOutput *o, MHeader *out)   // decoder.decode: null is NOT_ASSET
{
    return BRAssetDecodeInstructions(o->script, o->scriptLen, &out->h, out->insts, BR_ASSET_MAX_INSTRUCTIONS);
}

// assetCarrier(scripts): the first UNCLASSIFIABLE, else the last decoded. Its output index, or -1.
static long mAssetCarrier(const BRTransaction *tx, MHeader *out)
{
    size_t j;
    long last = -1;
    MHeader tmp;

    for (j = 0; j < tx->outCount; j++) {
        BRAssetScriptKind k;

        if (! mIsOpReturn(&tx->outputs[j])) continue;
        k = mDecode(&tx->outputs[j], &tmp);
        if (k == BR_ASSET_SCRIPT_NOT_ASSET) continue;
        if (k == BR_ASSET_SCRIPT_UNCLASSIFIABLE) { *out = tmp; return (long)j; }
        last = (long)j;
    }
    if (last < 0) return -1;
    mDecode(&tx->outputs[last], out);
    return last;
}

static int mFirstNonOpReturn(const BRTransaction *tx, int32_t *first)
{
    size_t j;

    for (j = 0; j < tx->outCount; j++) if (! mIsOpReturn(&tx->outputs[j])) { *first = (int32_t)j; return 1; }
    *first = 0;
    return 0;
}

static int mShape(UInt256 h, MShape *s)   // assetTxShape(heldOutputLines(funding))
{
    const BRTransaction *tx = mOutputsOf(h);
    long payload = -1;
    size_t j;

    if (! tx || tx->outCount == 0) return 0;
    for (j = 0; j < tx->outCount; j++) if (mIsOpReturn(&tx->outputs[j]) && payload < 0) payload = (long)j;
    s->hasFirst = mFirstNonOpReturn(tx, &s->first);
    if (payload < 0) return 0;
    if (mDecode(&tx->outputs[payload], &s->header) == BR_ASSET_SCRIPT_NOT_ASSET) return 0;
    s->outputCount = (int32_t)tx->outCount;
    return 1;
}

static int mHasAssetPayload(UInt256 h)   // txHasAssetPayload: -1 null, 0, 1
{
    const BRTransaction *tx = mOutputsOf(h);
    size_t j;

    if (! tx || tx->outCount == 0) return -1;
    for (j = 0; j < tx->outCount; j++) {
        MHeader tmp;

        if (! mIsOpReturn(&tx->outputs[j])) continue;
        return mDecode(&tx->outputs[j], &tmp) != BR_ASSET_SCRIPT_NOT_ASSET;
    }
    return 0;
}

static int mParentHasDataOutput(UInt256 h)   // nativeParentHasDataOutput: -1 null, 0, 1
{
    const BRTransaction *tx = mOutputsOf(h);
    size_t j;

    if (! tx || tx->outCount == 0) return -1;
    for (j = 0; j < tx->outCount; j++) if (tx->outputs[j].scriptLen > 0 && tx->outputs[j].script[0] == 0x6a) return 1;
    return 0;
}

static int mTargets(const MHeader *h, int32_t vout, int hasFirst, int32_t first, OL units, int32_t outputCount)
{
    return BRAssetTargetsOutput(h->h.operation, h->h.version, BRAssetHeaderHasRules(&h->h), h->h.hasTotalQuantity,
                                h->h.totalQuantity, vout, hasFirst, first, units.has, units.v, outputCount, h->insts,
                                h->h.instructionCount);
}

static OL mRow(UInt256 h, uint32_t v)   // utxoDao.getAssetUtxoAt(txid, vout)?.assetQuantity
{
    KRow *k = (v > (uint32_t)INT32_MAX) ? NULL : rowOf(h, (int32_t)v);

    return k ? some(k->qty) : none();
}

static OL mProven(UInt256 h, const MHeader *header)   // provenInputUnits
{
    size_t i;
    int64_t a;

    for (i = 0; i < W.provenN; i++) {
        if (UInt256Eq(W.proven[i], h)) {
            return BRAssetAssignedUnits(header->h.operation, header->insts, header->h.instructionCount, &a) ? some(a) : none();
        }
    }
    return none();
}

typedef struct { UInt256 h; OL v; } MEnt;
typedef struct { MEnt e[TMAX]; size_t n; } MMap;

static long mapAt(const MMap *m, UInt256 h)
{
    size_t i;

    for (i = 0; i < m->n; i++) if (UInt256Eq(m->e[i].h, h)) return (long)i;
    return -1;
}

static int mapHas(const MMap *m, UInt256 h) { return mapAt(m, h) >= 0; }
static OL mapGet(const MMap *m, UInt256 h) { long i = mapAt(m, h); return i >= 0 ? m->e[i].v : none(); }

static void mapPut(MMap *m, UInt256 h, OL v)
{
    long i = mapAt(m, h);

    if (i >= 0) { m->e[i].v = v; return; }
    if (m->n >= TMAX) { BROKEN = 1; return; }
    m->e[m->n].h = h;
    m->e[m->n].v = v;
    m->n++;
}

static void mapRemove(MMap *m, UInt256 h) { long i = mapAt(m, h); if (i >= 0) m->e[i] = m->e[--m->n]; }

typedef int (*MRowIsTargeted)(UInt256 h, uint32_t v);   // Boolean?: -1 null

// resolveInputAssetUnits(inputs, rowQuantity, isAssetTx = txHasAssetPayload, rowIsTargeted, unresolved)
static OL mResolveInputAssetUnits(const MInput *inputs, size_t n, MRowIsTargeted rowIsTargeted, int unresolvedGiven)
{
    int64_t total = 0;
    int zeroRows = 0;
    size_t i;

    for (i = 0; i < n; i++) {
        OL stored = mRow(inputs[i].h, inputs[i].v);

        if (stored.has && stored.v > 0) {
            if (__builtin_add_overflow(total, stored.v, &total)) return none();
            continue;
        }
        if (stored.has) {
            if (rowIsTargeted(inputs[i].h, inputs[i].v) == 0) continue;
            if (! unresolvedGiven) return none();
            zeroRows = 1;
            continue;
        }
        if (mHasAssetPayload(inputs[i].h) != 0) return none();
    }
    return zeroRows ? none() : some(total);
}

// ---- TargetingWalk (settled = null: detection's walks keep no record between questions) ----

typedef struct { UInt256 txid; UInt256 below[IMAX]; size_t n, next; } MStep;

static struct {
    MMap totals;
    MMap onTheWay;
    MStep stack[TMAX];
    size_t depth;
} MWALK;

static int mFromPayload(const MShape *tx, int32_t vout)   // -1 null
{
    if (! mTargets(&tx->header, vout, tx->hasFirst, tx->first, none(), tx->outputCount)) return 0;
    if (mTargets(&tx->header, vout, tx->hasFirst, tx->first, some(0), tx->outputCount)) return 1;
    return -1;
}

static int mInputsOf(UInt256 h, MInput *list, size_t *n)   // inputsOf: 0 = null
{
    const BRTransaction *tx = mOutputsOf(h);
    size_t k;

    *n = 0;
    if (! tx) return 0;
    for (k = 0; k < tx->inCount; k++) {
        if (tx->inputs[k].index > (uint32_t)INT32_MAX) return 0;
        list[k].h = tx->inputs[k].txHash;
        list[k].v = tx->inputs[k].index;
    }
    *n = tx->inCount;
    return 1;
}

static void mRestsOn(UInt256 h, MStep *step)
{
    MShape tx, fundingTx;
    MInput inputs[IMAX];
    size_t n, i, j;

    step->n = 0;
    if (! mShape(h, &tx)) return;
    if (! mInputsOf(h, inputs, &n)) return;
    for (i = 0; i < n; i++) {
        OL stored = mRow(inputs[i].h, inputs[i].v);
        int dup = 0;

        if (! stored.has || stored.v > 0) continue;
        if (! mShape(inputs[i].h, &fundingTx)) continue;
        if (mFromPayload(&fundingTx, (int32_t)inputs[i].v) != -1) continue;
        for (j = 0; j < step->n; j++) if (UInt256Eq(step->below[j], inputs[i].h)) dup = 1;
        if (! dup) step->below[step->n++] = inputs[i].h;
    }
}

static int mAnswerFromTotals(UInt256 h, uint32_t vout)
{
    MShape tx;
    int p;

    if (! mShape(h, &tx)) return -1;
    p = mFromPayload(&tx, (int32_t)vout);
    if (p != -1) return p;
    if (! mapHas(&MWALK.totals, h)) return -1;
    return mTargets(&tx.header, (int32_t)vout, tx.hasFirst, tx.first, mapGet(&MWALK.totals, h), tx.outputCount);
}

static OL mInputTotalOf(UInt256 h)
{
    MShape tx;
    MInput inputs[IMAX];
    size_t n;
    OL r;

    if (! mShape(h, &tx)) return none();
    if (! mInputsOf(h, inputs, &n)) return none();
    r = mResolveInputAssetUnits(inputs, n, mAnswerFromTotals, 0);
    return r.has ? r : mProven(h, &tx.header);
}

static void mResolveTotals(UInt256 root)
{
    if (mapHas(&MWALK.totals, root)) return;
    MWALK.stack[0].txid = root;
    mRestsOn(root, &MWALK.stack[0]);
    MWALK.stack[0].next = 0;
    MWALK.depth = 1;
    mapPut(&MWALK.onTheWay, root, none());

    while (MWALK.depth > 0) {
        MStep *step = &MWALK.stack[MWALK.depth - 1];

        if (step->next < step->n) {
            UInt256 b = step->below[step->next++];

            if (mapHas(&MWALK.totals, b)) continue;
            if (mapHas(&MWALK.onTheWay, b)) continue;
            mapPut(&MWALK.onTheWay, b, none());
            if (MWALK.depth >= TMAX) { BROKEN = 1; return; }
            MWALK.stack[MWALK.depth].txid = b;
            mRestsOn(b, &MWALK.stack[MWALK.depth]);
            MWALK.stack[MWALK.depth].next = 0;
            MWALK.depth++;
            continue;
        }
        MWALK.depth--;
        mapRemove(&MWALK.onTheWay, step->txid);
        mapPut(&MWALK.totals, step->txid, mInputTotalOf(step->txid));
    }
}

// inputRowIsTargeted -> outputIsTargeted(txid, vout, shapeOf, rowQuantity, isAssetTx, settled = null)
static int mOutputIsTargeted(UInt256 h, uint32_t vout)
{
    MShape tx;
    int p;

    MWALK.totals.n = 0;
    MWALK.onTheWay.n = 0;
    if (! mShape(h, &tx)) return -1;
    p = mFromPayload(&tx, (int32_t)vout);
    if (p != -1) return p;
    mResolveTotals(h);
    return mAnswerFromTotals(h, vout);
}

// ---- AssetTxQuantity ----

static int64_t mForOutput(const MHeader *m, int32_t vout, int hasFirst, int32_t first)
{
    const BRAssetHeader *h = &m->h;
    int64_t sum = 0;
    size_t i;

    if (h->kind != BR_ASSET_SCRIPT_ASSET) return 0;
    if (h->operation == DA_ISSUANCE) {
        if (! hasFirst || vout != first) return 0;
        return (h->hasTotalQuantity && h->totalQuantity > 0) ? h->totalQuantity : 0;
    }
    for (i = 0; i < h->instructionCount; i++) {
        const BRAssetTransferInstruction *in = &m->insts[i];
        int destroys = h->operation == DA_BURN && ! in->range && in->outputIndex == 31;
        int named;

        if (in->percent || destroys) continue;
        if (in->amount < 0 || in->outputIndex < 0) return 0;
        named = in->range ? vout <= in->outputIndex : in->outputIndex == vout;
        if (! named) continue;
        if (__builtin_add_overflow(sum, in->amount, &sum)) return 0;
    }
    return sum;
}

static OL mAssignedUnits(const MHeader *m)
{
    int64_t assigned = 0, consumed;
    size_t i;

    if (m->h.kind != BR_ASSET_SCRIPT_ASSET) return none();
    for (i = 0; i < m->h.instructionCount; i++) {
        const BRAssetTransferInstruction *in = &m->insts[i];

        if (in->percent) return none();
        if (in->amount < 0 || in->outputIndex < 0) return none();
        if (in->range) { if (__builtin_mul_overflow((int64_t)in->outputIndex + 1, in->amount, &consumed)) return none(); }
        else consumed = in->amount;
        if (__builtin_add_overflow(assigned, consumed, &assigned)) return none();
    }
    return some(assigned);
}

static OL mImplicitChange(const MHeader *m, OL inputUnits)
{
    OL assigned;

    if (m->h.kind == BR_ASSET_SCRIPT_ASSET && m->h.operation == DA_ISSUANCE) return some(0);
    if (m->h.kind != BR_ASSET_SCRIPT_ASSET) return none();
    if (! inputUnits.has || inputUnits.v < 0) return none();
    assigned = mAssignedUnits(m);
    if (! assigned.has) return none();
    if (assigned.v > inputUnits.v) return inputUnits;
    return some(inputUnits.v - assigned.v);
}

static int64_t mForOutputTotal(const MHeader *m, int32_t vout, int hasFirst, int32_t first, OL inputUnits, int32_t outputCount)
{
    int64_t explicitUnits = mForOutput(m, vout, hasFirst, first), total;
    OL change;

    if (vout != outputCount - 1) return explicitUnits;
    change = mImplicitChange(m, inputUnits);
    if (! change.has) return explicitUnits;
    return __builtin_add_overflow(explicitUnits, change.v, &total) ? 0 : total;
}

// ---- processIncomingAssetTx ----

typedef struct {
    int32_t vout;
    int targeted;
    int64_t claimed;
    BRAssetOutputCredit credit;
    BRAssetPersistAction action;
    KRow row;                        // the row written
    int unresolved;
    int ask;
    BRAssetDeliverable bound;
} MOut;

typedef struct {
    int isNull;                      // processIncomingAssetTx returned null before its writes
    int unclassifiable;              // ... after holding (an unclassifiable carrier)
    int needsRules;                  // the C asks first: the ids not answered, in order
    char asks[IMAX][96];
    size_t askN;
    char placeholder[96];
    MOut outs[OARENA];
    size_t n;
    int walkDue;
    int anyUnresolved;
} MResult;

static MResult MR;
static BRAssetOutputUnits MDELIV[DMAX];
static BRAssetUnits MSLOTS[IMAX];
static BRAssetInputStack MSTACKS[IMAX];
static int64_t MALLOC[IMAX];

static int mOwned(const BRTxOutput *o)   // out.script.toHex() in owned
{
    int i;

    for (i = 0; i < W.ownedN; i++) if (o->scriptLen == OWNED[i].len && memcmp(o->script, OWNED[i].bytes, o->scriptLen) == 0) return 1;
    return 0;
}

// The rules answer for an id, or -1 when the platform has said nothing of it.
static int mRuleFree(const char *id)
{
    size_t i;

    for (i = 0; i < W.ruleN; i++) if (strcmp(W.rules[i].id, id) == 0) return W.rules[i].kind == R_FREE;
    return -1;
}

// backedInputStack(txid, vout, row, parentHasDataOutput, rowIsTargeted): 0 null; 1 known (*slot used when
// *count is 1)
static int mBacked(UInt256 h, uint32_t vout, BRAssetUnits *slot, size_t *count)
{
    KRow *r = (vout > (uint32_t)INT32_MAX) ? NULL : rowOf(h, (int32_t)vout);

    *count = 0;
    if (r) {
        if (r->credit == BR_ASSET_CREDIT_BACKED || r->credit == BR_ASSET_CREDIT_VERIFIED) {
            if (r->qty <= 0) return 1;
            if (! r->hasId || r->id[0] == '\0') return 0;
            slot->assetId = r->id;
            slot->count = r->qty;
            slot->ruleFree = 0;
            *count = 1;
            return 1;
        }
        if (r->credit == BR_ASSET_CREDIT_UNCHECKED && r->qty == 0 && mOutputIsTargeted(h, vout) == 0) return 1;
        return 0;
    }
    return mParentHasDataOutput(h) == 0;
}

// settledRow(credit, placeholderAssetId, source)
static int mSettledRow(const BRAssetOutputCredit *c, const char *placeholder, BRAssetCredit source, const char **id,
                       int64_t *qty, BRAssetCredit *credit)
{
    if (c->kind == BR_ASSET_OUTPUT_HOLDS) {
        if (c->hasUnits) { *id = c->assetId; *qty = c->count; }
        else { *id = c->label ? c->label : placeholder; *qty = 0; }
        *credit = source;
        return 1;
    }
    if (c->kind == BR_ASSET_OUTPUT_MIXED) { *id = placeholder; *qty = 0; *credit = BR_ASSET_CREDIT_MIXED; return 1; }
    return 0;
}

static int mIsSettled(BRAssetCredit c) { return c == BR_ASSET_CREDIT_BACKED || c == BR_ASSET_CREDIT_VERIFIED || c == BR_ASSET_CREDIT_MIXED; }
static int mUnresolved(int hasId, const char *id) { return ! hasId || strncmp(id, "unresolved:", 11) == 0; }

// persistDetectedAssetOutput, into o
static void mPersist(const char *txid, const BRTxOutput *txo, int32_t vout, const char *placeholder, int64_t computedQty,
                     MOut *o)
{
    KRow *existing = rowAt(txid, vout);
    const char *sid = NULL;
    int64_t sqty = 0;
    BRAssetCredit scr = BR_ASSET_CREDIT_UNCHECKED;
    int settled;

    o->action = BR_ASSET_PERSIST_NOTHING;
    o->unresolved = 0;
    if (W.outgoing) return;
    settled = mSettledRow(&o->credit, placeholder, BR_ASSET_CREDIT_BACKED, &sid, &sqty, &scr);
    if (existing) {
        o->action = BR_ASSET_PERSIST_UPDATE;
        o->row = *existing;
        o->row.source = BR_ASSET_SOURCE_NATIVE;
        if (! mIsSettled(existing->credit)) {
            if (settled) { setId(&o->row, sid); o->row.qty = sqty; o->row.credit = scr; }
            else if (computedQty > existing->qty) o->row.qty = computedQty;
        }
        o->unresolved = mUnresolved(o->row.hasId, o->row.id);
        return;
    }
    o->action = BR_ASSET_PERSIST_INSERT;
    memset(&o->row, 0, sizeof(o->row));
    snprintf(o->row.txid, sizeof(o->row.txid), "%s", txid);
    o->row.vout = vout;
    if (txo->scriptLen > sizeof(o->row.script)) { BROKEN = 1; return; }
    memcpy(o->row.script, txo->script, txo->scriptLen);
    o->row.scriptLen = txo->scriptLen;
    o->row.sats = (int64_t)txo->amount;
    o->row.height = W.height;
    o->row.isAsset = 1;
    setId(&o->row, settled ? sid : placeholder);
    o->row.qty = settled ? sqty : computedQty;
    o->row.spent = 0;
    o->row.source = BR_ASSET_SOURCE_NATIVE;
    o->row.credit = settled ? scr : BR_ASSET_CREDIT_UNCHECKED;
    o->unresolved = mUnresolved(1, o->row.id);
}

static void mDetect(const BRTransaction *tx)
{
    MHeader header;
    long carrierAt;
    char txid[65];
    int derived = 0, hasFirst, isIssuance, payloadExact, allSettled, anyNull = 0;
    int32_t first, outputCount = (int32_t)tx->outCount;
    MInput inputs[IMAX];
    size_t nIn = 0, k, j, stackN = 0;
    OL inputUnits;
    BRAssetAllocation allocation;
    int haveAllocation = 0;

    memset(&MR, 0, sizeof(MR));
    hexOf(tx->txHash, txid);
    if (tx->outCount == 0) { MR.isNull = 1; return; }
    carrierAt = mAssetCarrier(tx, &header);
    if (carrierAt < 0) { MR.isNull = 1; return; }

    if (header.h.kind == BR_ASSET_SCRIPT_ASSET && header.h.operation == DA_ISSUANCE && header.h.locked && tx->inCount > 0) {
        int32_t prevVout = (tx->inputs[0].index <= (uint32_t)INT32_MAX) ? (int32_t)tx->inputs[0].index : -1;
        char prev[65];

        if (prevVout >= 0) {
            hexOf(tx->inputs[0].txHash, prev);
            derived = BRAssetIdForIssuance(prev, prevVout, 1, header.h.aggregation, header.h.divisibility, MR.placeholder,
                                           sizeof(MR.placeholder));
        }
    }
    if (! derived) snprintf(MR.placeholder, sizeof(MR.placeholder), "unresolved:%s", txid);

    hasFirst = mFirstNonOpReturn(tx, &first);
    for (k = 0; k < tx->inCount; k++) {
        if (tx->inputs[k].index > (uint32_t)INT32_MAX) { anyNull = 1; continue; }
        inputs[nIn].h = tx->inputs[k].txHash;
        inputs[nIn].v = tx->inputs[k].index;
        nIn++;
    }
    inputUnits = mResolveInputAssetUnits(inputs, nIn, mOutputIsTargeted, 1);
    if (! inputUnits.has) inputUnits = mProven(tx->txHash, &header);

    for (j = 0; j < tx->outCount; j++) {
        const BRTxOutput *o = &tx->outputs[j];

        if (mIsOpReturn(o) || (W.ownedN != 0 && ! mOwned(o))) continue;
        MR.outs[MR.n].vout = (int32_t)j;
        MR.outs[MR.n].targeted = mTargets(&header, (int32_t)j, hasFirst, first, inputUnits, outputCount);
        MR.outs[MR.n].credit.kind = BR_ASSET_OUTPUT_UNKNOWN;
        MR.n++;
    }
    if (header.h.kind != BR_ASSET_SCRIPT_ASSET) { MR.unclassifiable = 1; return; }

    isIssuance = header.h.operation == DA_ISSUANCE;
    payloadExact = BRAssetPayloadReadsExactly(tx->outputs[carrierAt].script, tx->outputs[carrierAt].scriptLen, &header.h,
                                              header.insts, header.h.instructionCount);
    allSettled = MR.n > 0;
    for (j = 0; j < MR.n; j++) {
        KRow *r = rowAt(txid, MR.outs[j].vout);

        if (! r || ! mIsSettled(r->credit)) allSettled = 0;
    }

    if (allSettled) haveAllocation = 0;
    else if (isIssuance) {
        allocation = BRAssetAllocateIssuance(&header.h, header.insts, header.h.instructionCount, MR.placeholder, outputCount,
                                             payloadExact, MDELIV, DMAX);
        haveAllocation = 1;
    }
    else if (! payloadExact) {
        allocation = BRAssetAllocate(&header.h, header.insts, header.h.instructionCount, 0, NULL, 0, outputCount, NULL, 0, NULL, 0);
        haveAllocation = 1;
    }
    else {
        if (anyNull) {
            MSTACKS[0].known = 0; MSTACKS[0].units = NULL; MSTACKS[0].unitCount = 0;
            stackN = 1;
        }
        else {
            for (k = 0; k < nIn; k++) {
                size_t count = 0;
                int known = mBacked(inputs[k].h, inputs[k].v, &MSLOTS[k], &count);

                MSTACKS[k].known = known;
                MSTACKS[k].units = count ? &MSLOTS[k] : NULL;
                MSTACKS[k].unitCount = count;
            }
            stackN = nIn;
        }
        for (k = 0; k < stackN; k++) {   // ruleFree for each distinct id
            int free;
            size_t m;

            if (! MSTACKS[k].known || MSTACKS[k].unitCount != 1) continue;
            free = mRuleFree(MSLOTS[k].assetId);
            if (free < 0) {
                for (m = 0; m < MR.askN && strcmp(MR.asks[m], MSLOTS[k].assetId) != 0; m++) {}
                if (m == MR.askN) snprintf(MR.asks[MR.askN++], sizeof(MR.asks[0]), "%s", MSLOTS[k].assetId);
                continue;
            }
            MSLOTS[k].ruleFree = free;
        }
        if (MR.askN > 0) { MR.needsRules = 1; return; }
        allocation = BRAssetAllocate(&header.h, header.insts, header.h.instructionCount, 1, MSTACKS, stackN, outputCount,
                                     MALLOC, IMAX, MDELIV, DMAX);
        haveAllocation = 1;
    }

    for (j = 0; j < MR.n; j++) {
        MOut *o = &MR.outs[j];

        o->claimed = mForOutputTotal(&header, o->vout, hasFirst, first, inputUnits, outputCount);
        if (haveAllocation) o->credit = BRAssetCreditForOutput(allocation, MDELIV, header.insts, header.h.instructionCount,
                                                               o->vout, outputCount);
        mPersist(txid, &tx->outputs[o->vout], o->vout, MR.placeholder, o->claimed, o);
        if (o->unresolved) MR.anyUnresolved = 1;
        o->ask = o->credit.kind == BR_ASSET_OUTPUT_UNKNOWN && ! W.outgoing && W.ownedN != 0;
        o->bound = BRAssetMaxDeliverable(&header.h, header.insts, header.h.instructionCount, o->vout, outputCount);
    }
    MR.walkDue = isIssuance && ((MR.anyUnresolved && strncmp(MR.placeholder, "unresolved:", 11) == 0) || ! W.walked);
}

static int sameText(const char *a, const char *b) { return (! a || ! b) ? a == b : strcmp(a, b) == 0; }

static int sameCredit(const BRAssetOutputCredit *a, const BRAssetOutputCredit *b)
{
    return a->kind == b->kind && a->hasUnits == b->hasUnits && (! a->hasUnits || (sameText(a->assetId, b->assetId) && a->count == b->count)) &&
           sameText(a->label, b->label);
}

// Does the last detection say what the model says? 0 with *why on the first difference.
static int agreesWithModel(const char **why)
{
    size_t i;

    if (MR.isNull) { *why = "not an asset"; return DET.status == BR_ASSET_DETECT_NOT_ASSET; }
    if (MR.needsRules) {
        *why = "the ids asked about";
        if (DET.status != BR_ASSET_DETECT_NEEDS_RULES || DET.askCount != MR.askN) return 0;
        for (i = 0; i < MR.askN; i++) if (strcmp(BASKS[i], MR.asks[i]) != 0) return 0;
        return 1;
    }
    *why = "the status";
    if (DET.status != (MR.unclassifiable ? BR_ASSET_DETECT_UNCLASSIFIABLE : BR_ASSET_DETECT_DONE)) return 0;
    *why = "the outputs that get a row";
    if (DET.outputCount != MR.n) return 0;
    for (i = 0; i < MR.n; i++) {
        const BRAssetDetectOutput *d = &BOUTS[i];
        const MOut *m = &MR.outs[i];

        *why = "an output's index or targeting";
        if (d->vout != m->vout || d->targeted != m->targeted) return 0;
        if (MR.unclassifiable) {
            *why = "an unclassifiable carrier's output";
            if (d->persist.action != BR_ASSET_PERSIST_NOTHING || d->askIndexer) return 0;
            continue;
        }
        *why = "a claim";
        if (d->claimed != m->claimed) return 0;
        *why = "a credit";
        if (! sameCredit(&d->credit, &m->credit)) return 0;
        *why = "a write";
        if (d->persist.action != m->action) return 0;
        if (m->action != BR_ASSET_PERSIST_NOTHING) {
            const BRAssetRow *r = &d->persist.row;

            if (strcmp(r->txid, m->row.txid) != 0 || r->vout != m->row.vout || r->scriptLen != m->row.scriptLen ||
                (r->scriptLen && memcmp(r->script, m->row.script, r->scriptLen) != 0) || r->satoshis != m->row.sats ||
                r->blockHeight != m->row.height || r->isAsset != m->row.isAsset ||
                ! sameText(r->assetId, m->row.hasId ? m->row.id : NULL) || r->assetQuantity != m->row.qty ||
                (r->spent != 0) != (m->row.spent != 0) || r->source != m->row.source || r->credit != m->row.credit ||
                d->persist.unresolved != m->unresolved) return 0;
        }
        *why = "the indexer question or its bound";
        if (d->askIndexer != m->ask || d->bound.unbounded != m->bound.unbounded || d->bound.max != m->bound.max) return 0;
    }
    if (MR.unclassifiable) return 1;
    *why = "the placeholder";
    if (strcmp(DET.placeholder, MR.placeholder) != 0) return 0;
    *why = "the walk";
    return DET.issuanceWalkDue == MR.walkDue && DET.anyUnresolved == MR.anyUnresolved;
}

// ================================================================================================
// the claim: AssetTxQuantityTest, AssetImplicitChangeTest, AssetArithmeticNeverWrapsTest
// ================================================================================================

static int64_t claim(BRAssetOperation op, int64_t total, const BRAssetTransferInstruction *l, size_t n, int32_t vout,
                     int hasFirst, int32_t first, int hasUnits, int64_t units, int32_t outputCount)
{
    return BRAssetClaimForOutputTotal(op, total >= 0, total, l, n, vout, hasFirst, first, hasUnits, units, outputCount);
}

static int64_t explicitOf(BRAssetOperation op, int64_t total, const BRAssetTransferInstruction *l, size_t n, int32_t vout, int hasFirst,
                          int32_t first)
{
    return BRAssetClaimForOutput(op, total >= 0, total, l, n, vout, hasFirst, first);
}

// "null", or the remainder in decimal
static const char *remainderOf(BRAssetOperation op, const BRAssetTransferInstruction *l, size_t n, int hasUnits, int64_t units)
{
    static char text[32];
    int64_t r = 0;

    if (! BRAssetClaimRemainder(op, l, n, hasUnits, units, &r)) return "null";
    snprintf(text, sizeof(text), "%lld", (long long)r);
    return text;
}

static void claim_quantity(void)   // AssetTxQuantityTest
{
    BRAssetTransferInstruction one[1], three[3];

    ck(explicitOf(DA_ISSUANCE, 1000, NULL, 0, 0, 1, 0) == 1000 && explicitOf(DA_ISSUANCE, 1000, NULL, 0, 2, 1, 0) == 0,
       "issuance_lands_total_on_first_non_opreturn: 1000 on output 0, 0 on output 2");
    one[0] = ti(0, 20, 0, 0);
    ck(explicitOf(DA_TRANSFER, -1, one, 1, 0, 0, 0) == 20 && explicitOf(DA_TRANSFER, -1, one, 1, 1, 0, 0) == 0,
       "fixed_transfer_only_target_output");
    one[0] = ti(2, 5, 1, 0);
    ck(explicitOf(DA_TRANSFER, -1, one, 1, 0, 0, 0) == 5 && explicitOf(DA_TRANSFER, -1, one, 1, 1, 0, 0) == 5 &&
       explicitOf(DA_TRANSFER, -1, one, 1, 2, 0, 0) == 5 && explicitOf(DA_TRANSFER, -1, one, 1, 3, 0, 0) == 0,
       "range_transfer_hits_every_output_0_to_N");
    one[0] = ti(31, 100, 0, 0);
    one[0].isBurn = 1;
    ck(explicitOf(DA_BURN, -1, one, 1, 0, 0, 0) == 0 && explicitOf(DA_BURN, -1, one, 1, 31, 0, 0) == 0,
       "burn_instruction_counts_zero: the destroy marker credits nobody");
    one[0].isBurn = 0;
    ck(explicitOf(DA_TRANSFER, -1, one, 1, 31, 0, 0) == 100 && explicitOf(DA_TRANSFER, -1, one, 1, 30, 0, 0) == 0,
       "transfer_to_output_31_is_credited (isBurn false)");
    one[0].isBurn = 1;
    ck(explicitOf(DA_TRANSFER, -1, one, 1, 31, 0, 0) == 100 && explicitOf(DA_TRANSFER, -1, one, 1, 30, 0, 0) == 0,
       "transfer_to_output_31_is_credited (isBurn true)");
    three[0] = ti(31, 60, 0, 0); three[0].isBurn = 1;
    three[1] = ti(1, 40, 0, 0);
    three[2] = ti(2, 5, 1, 0);
    ck(explicitOf(DA_BURN, -1, three, 3, 0, 0, 0) == 5 && explicitOf(DA_BURN, -1, three, 3, 1, 0, 0) == 45 &&
       explicitOf(DA_BURN, -1, three, 3, 2, 0, 0) == 5 && explicitOf(DA_BURN, -1, three, 3, 31, 0, 0) == 0,
       "burn_operation_credits_its_non_burn_instructions: 5, 45, 5, 0");
    ck(claim(DA_BURN, -1, three, 3, 1, 0, 0, 1, 130, 4) == 45 && claim(DA_BURN, -1, three, 3, 0, 0, 0, 1, 130, 4) == 5,
       "burn_operation_credits_its_non_burn_instructions: the claim of outputs 1 and 0 is what they are delivered, 45 and 5");
    ck(claim(DA_BURN, -1, three, 3, 3, 0, 0, 1, 130, 4) == 15,
       "burn_operation_credits_its_non_burn_instructions: 15 of 130 ride to the last output (%lld)",
       (long long)claim(DA_BURN, -1, three, 3, 3, 0, 0, 1, 130, 4));
    one[0] = ti(0, 50, 0, 1);
    ck(explicitOf(DA_TRANSFER, -1, one, 1, 0, 0, 0) == 0, "percent_instruction_skipped");
    three[0] = ti(0, 10, 0, 0);
    three[1] = ti(1, 5, 1, 0);
    three[2] = ti(1, 3, 0, 0);
    ck(explicitOf(DA_TRANSFER, -1, three, 3, 0, 0, 0) == 15 && explicitOf(DA_TRANSFER, -1, three, 3, 1, 0, 0) == 8,
       "multiple_instructions_sum_per_output: 15 and 8");
}

static void claim_remainder(void)   // AssetImplicitChangeTest
{
    BRAssetTransferInstruction l[2];

    l[0] = ti(0, 10, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "90") == 0, "leftover_units_are_credited_to_the_last_output: 90");
    l[0] = ti(0, 5, 0, 0); l[1] = ti(2, 95, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 2, 1, 100), "0") == 0, "fully_assigned_transfer_leaves_no_implicit_change");
    l[0] = ti(2, 10, 1, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "70") == 0, "range_instruction_consumes_amount_per_covered_output: 70");
    l[0] = ti(31, 40, 0, 0); l[0].isBurn = 1;
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "60") == 0, "burn_instruction_consumes_its_units: 60");
    l[0] = ti(0, 128, 0, 1);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "null") == 0, "percent_instruction_yields_unknown");
    l[0] = ti(0, 10, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 0, 0), "null") == 0, "unknown_input_units_yield_unknown");
    l[0] = ti(0, 150, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "100") == 0 && strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 0), "0") == 0,
       "over_assignment_leaves_everything_to_the_last_output: 100, and 0 of 0");
    ck(claim(DA_TRANSFER, -1, l, 1, 3, 1, 0, 1, 100, 4) == 100,
       "over_assignment_leaves_everything_to_the_last_output: the last output claims all 100 (%lld)",
       (long long)claim(DA_TRANSFER, -1, l, 1, 3, 1, 0, 1, 100, 4));
    l[0] = ti(0, 100, 0, 0);
    ck(strcmp(remainderOf(DA_ISSUANCE, l, 1, 1, 0), "0") == 0, "issuance_has_no_implicit_change");
    l[0] = ti(31, 25, 0, 0); l[0].isBurn = 1;
    ck(strcmp(remainderOf(DA_BURN, l, 1, 1, 100), "75") == 0, "burn_opcode_tx_still_credits_its_leftover: 75");
    l[0] = ti(0, 10, 0, 0);
    ck(claim(DA_TRANSFER, -1, l, 1, 0, 1, 0, 1, 100, 4) == 10 && claim(DA_TRANSFER, -1, l, 1, 2, 1, 0, 1, 100, 4) == 0 &&
       claim(DA_TRANSFER, -1, l, 1, 3, 1, 0, 1, 100, 4) == 90,
       "total_for_output_adds_the_implicit_remainder_to_the_last_output: 10, 0, 90");
    ck(claim(DA_TRANSFER, -1, l, 1, 3, 1, 0, 0, 0, 4) == 0, "total_for_output_credits_nothing_when_input_units_are_unknown");
    ck(strcmp(remainderOf(DA_UNDEFINED, NULL, 0, 1, 5), "null") == 0 &&
       claim(DA_UNDEFINED, -1, NULL, 0, 0, 1, 0, 1, 0, 3) == 0,
       "an unclassifiable carrier: no remainder, nothing claimed");
}

static void claim_arithmetic(void)   // AssetArithmeticNeverWrapsTest, the claim
{
    BRAssetTransferInstruction l[2];
    int64_t big = INT64_MAX / 2 + 1;
    int32_t v;

    for (v = 0; v <= 2; v++) ck(claim(DA_UNDEFINED, -1, NULL, 0, v, 1, 0, 1, 0, 3) == 0,
                                "a_transfer_whose_amount_cannot_be_counted_is_unclassifiable: output %d claims 0", (int)v);
    l[0] = ti(1, 8000000000000000000LL, 1, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 0), "null") == 0 && claim(DA_TRANSFER, -1, l, 1, 2, 1, 0, 1, 0, 3) == 0,
       "a_range_consumption_past_the_signed_range_is_unknown: no remainder, the last output claims 0");
    l[0] = ti(512, (1LL << 54) - 1, 1, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 0), "null") == 0 && claim(DA_TRANSFER, -1, l, 1, 513, 1, 1, 1, 0, 514) == 0,
       "a_range_consumption_past_the_signed_range_is_unknown: the wide range, output 513 claims 0");
    l[0] = ti(0, 150, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "100") == 0, "a_remainder_is_never_more_than_the_inputs_carried: 100");
    l[0] = ti(0, 100, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 100), "0") == 0 && strcmp(remainderOf(DA_TRANSFER, l, 1, 1, -1), "null") == 0,
       "a_remainder_is_never_more_than_the_inputs_carried: 0, and null for -1 in");
    l[0] = ti(0, -5, 0, 0);
    ck(strcmp(remainderOf(DA_TRANSFER, l, 1, 1, 1), "null") == 0 && explicitOf(DA_TRANSFER, -1, l, 1, 0, 1, 0) == 0 &&
       claim(DA_TRANSFER, -1, l, 1, 2, 1, 0, 1, 1, 3) == 0, "a_negative_amount_is_not_a_count");
    l[0] = ti(0, big, 0, 0); l[1] = ti(0, big, 0, 0);
    ck(explicitOf(DA_TRANSFER, -1, l, 2, 0, 1, 0) == 0, "a_sum_of_amounts_past_the_signed_range_credits_nothing");
    l[0] = ti(0, INT64_MAX - 5, 0, 0);
    ck(claim(DA_TRANSFER, -1, l, 1, 0, 1, 0, 1, 10, 1) == 0,
       "an over-drawn claim (its remainder is all 10 in) whose sum with the explicit amount is past INT64_MAX claims 0");
    ck(claim(DA_TRANSFER, -1, l, 1, 0, 1, 0, 1, INT64_MAX, 1) == INT64_MAX, "the same with INT64_MAX in: exactly INT64_MAX");
    ck(explicitOf(DA_ISSUANCE, -1, NULL, 0, 0, 1, 0) == 0 && explicitOf(DA_ISSUANCE, -7, NULL, 0, 0, 1, 0) == 0 &&
       explicitOf(DA_ISSUANCE, 5, NULL, 0, 0, 0, 0) == 0,
       "an issuance with no amount, a negative one, or no output that is not OP_RETURN claims 0");
}

// ================================================================================================
// the receive path: UnbackedAssetCreditTest, AssetArithmeticNeverWrapsTest
// ================================================================================================

typedef enum { IN_FOREIGN, IN_PLAIN, IN_OWN } InKind;
typedef struct { InKind k; const char *id; int64_t count; } In;

static const In FOREIGN = { IN_FOREIGN, NULL, 0 };
static const In PLAIN = { IN_PLAIN, NULL, 0 };
static In own(const char *id, int64_t count) { In i; i.k = IN_OWN; i.id = id; i.count = count; return i; }

#define RECV_TAG 0xc0
static Fx *RX;

static int ownedIn(int32_t v, const int *owned, size_t n)
{
    size_t i;

    for (i = 0; i < n; i++) if (owned[i] == v) return 1;
    return 0;
}

// The transaction RECV_TAG: OP_RETURN `script` at the first output the wallet does not own, the wallet's
// own `owned` outputs, someone else's at the rest; spending `ins` (input i spends output i of
// transaction 0x1000 + i: a row of the wallet's own, a plain transaction it holds, or nothing it knows).
static Fx *receiveTx(const char *script, const In *ins, size_t nIn, const int *owned, size_t nOwned, int32_t outputCount)
{
    Fx *f;
    size_t i;
    int32_t v, op = -1;

    for (i = 0; i < nIn; i++) {
        UInt256 h = tid(0x1000 + (uint32_t)i);

        if (ins[i].k == IN_OWN) addRow(h, (int32_t)i, OURS, BR_ASSET_CREDIT_VERIFIED, ins[i].count, ins[i].id, 1);
        else if (ins[i].k == IN_PLAIN) {
            Fx *p = fx(0x1000 + (uint32_t)i);
            size_t j;

            for (j = 0; j <= i; j++) theirs(p);
            wadd(p);
        }
    }
    f = fx(RECV_TAG);
    for (i = 0; i < nIn; i++) in(f, tid(0x1000 + (uint32_t)i), (uint32_t)i);
    for (v = 0; v < outputCount; v++) {
        if (ownedIn(v, owned, nOwned)) ours(f);
        else if (op < 0) { out(f, script); op = v; }
        else theirs(f);
    }
    wadd(f);
    RX = f;
    return f;
}

static BRAssetDetectStatus receive(const char *script, const In *ins, size_t nIn, const int *owned, size_t nOwned,
                                   int32_t outputCount)
{
    BRAssetDetectStatus s;

    receiveTx(script, ins, nIn, owned, nOwned, outputCount);
    s = detect(&RX->tx);
    apply(0);
    return s;
}

static int heldNothing(const BRAssetOutputCredit *c) { return c->kind == BR_ASSET_OUTPUT_HOLDS && ! c->hasUnits; }
static int holds(const BRAssetOutputCredit *c, const char *id, int64_t n)
{
    return c->kind == BR_ASSET_OUTPUT_HOLDS && c->hasUnits && strcmp(c->assetId, id) == 0 && c->count == n;
}

static const char *CONTROL = "6a06444103150005";   // 5 units to output 0

typedef struct { const char *script; In ins[2]; size_t n; int32_t named; } Vec;

static Vec VECS[5];

static void vectors(void)
{
    VECS[0].script = "6a0b4441031500018060f423f0"; VECS[0].ins[0] = own(X, 1); VECS[0].ins[1] = PLAIN; VECS[0].n = 2; VECS[0].named = 0;
    VECS[1].script = "6a0744410315012012"; VECS[1].ins[0] = PLAIN; VECS[1].n = 1; VECS[1].named = 1;
    VECS[2].script = "6a0744410315002016"; VECS[2].ins[0] = PLAIN; VECS[2].ins[1] = PLAIN; VECS[2].n = 2; VECS[2].named = 0;
    VECS[3].script = "6a0744410315002016"; VECS[3].ins[0] = own(X, 1); VECS[3].n = 1; VECS[3].named = 0;
    VECS[4].script = "6a0d444103155fffffffffffffffff"; VECS[4].ins[0] = PLAIN; VECS[4].n = 1; VECS[4].named = 0;
}

// UnbackedAssetCreditTest's first case: the test's five payloads, each naming one output of the wallet's,
// from inputs the wallet knows nothing of.
static void recv_unknown_inputs(void)
{
    size_t i;
    In foreign[2] = { FOREIGN, FOREIGN };

    vectors();
    for (i = 0; i < N(VECS); i++) {
        int owned[1];
        const BRAssetDetectOutput *o;
        KRow *r;

        wreset();
        owned[0] = VECS[i].named;
        receiveTx(VECS[i].script, foreign, 2, owned, 1, 3);
        indexer(&RX->tx, VECS[i].named, BR_ASSET_STACK_NOT_UNSPENT, "");
        statusIs(VECS[i].script, detect(&RX->tx), BR_ASSET_DETECT_DONE);
        apply(0);
        o = outAt(VECS[i].named);
        r = detectedRow(VECS[i].named);
        ck(o && o->credit.kind == BR_ASSET_OUTPUT_UNKNOWN, "%s: the output's credit is UNKNOWN", VECS[i].script);
        ck(r && r->credit == BR_ASSET_CREDIT_UNCHECKED && r->qty > 0,
           "%s: the row is UNCHECKED and keeps the claim for the hold (%lld)", VECS[i].script, r ? (long long)r->qty : -1LL);
        ck(o && o->targeted, "%s: the output is targeted (held)", VECS[i].script);
        ck(strcmp(balances(), "-") == 0, "%s: nothing counts (%s)", VECS[i].script, balances());

        indexer(&RX->tx, VECS[i].named, BR_ASSET_STACK_FOUND, "");
        settleAll(600000);
        r = detectedRow(VECS[i].named);
        ck(r && r->credit == BR_ASSET_CREDIT_VERIFIED && r->qty == 0 && r->hasId && strcmp(r->id, DET.placeholder) == 0,
           "%s: confirmed empty, the row settles VERIFIED at 0 under the placeholder", VECS[i].script);
        ck(strcmp(balances(), "-") == 0, "%s: still nothing counts", VECS[i].script);
    }
}

// UnbackedAssetCreditTest's second case: the same payloads, from inputs the wallet knows.
static void recv_known_inputs(void)
{
    size_t i;

    vectors();
    for (i = 0; i < N(VECS); i++) {
        int owned[1];
        const BRAssetDetectOutput *o;
        KRow *r;

        wreset();
        owned[0] = VECS[i].named;
        statusIs(VECS[i].script, receive(VECS[i].script, VECS[i].ins, VECS[i].n, owned, 1, 3), BR_ASSET_DETECT_DONE);
        o = outAt(VECS[i].named);
        r = detectedRow(VECS[i].named);
        ck(o && heldNothing(&o->credit), "%s: Core's rules on the device: the output holds nothing", VECS[i].script);
        ck(r && r->credit == BR_ASSET_CREDIT_BACKED && r->qty == 0, "%s: the row is BACKED at 0", VECS[i].script);
        ck(strcmp(balances(), "-") == 0, "%s: nothing counts", VECS[i].script);
        ck(LOOKUPS == 0, "%s: no indexer lookup was needed", VECS[i].script);
    }
}

static void recv_voided_to_last(void)   // a_voided_transfer_delivers_only_the_carried_units_to_the_last_output
{
    In ins[2];
    int owned[2] = { 0, 2 };
    KRow *r0, *r2;

    wreset();
    ins[0] = own(X, 1); ins[1] = PLAIN;
    statusIs("voided", receive("6a0b4441031500018060f423f0", ins, 2, owned, 2, 3), BR_ASSET_DETECT_DONE);
    r0 = detectedRow(0);
    r2 = detectedRow(2);
    ck(r0 && r0->qty == 0, "output 0 holds nothing");
    ck(r2 && r2->hasId && strcmp(r2->id, X) == 0 && r2->qty == 1, "output 2, the last, holds X:1");
    ck(strcmp(balances(), "Lax:1") == 0, "the balance is X:1 (%s)", balances());
}

static void recv_honest_known(void)   // the_honest_control_is_credited_exactly_as_core_from_known_inputs
{
    In ins[2];
    int owned[2] = { 0, 2 };
    KRow *r0, *r2;

    wreset();
    ins[0] = own(X, 9); ins[1] = PLAIN;
    statusIs("control", receive(CONTROL, ins, 2, owned, 2, 3), BR_ASSET_DETECT_DONE);
    r0 = detectedRow(0);
    r2 = detectedRow(2);
    ck(r0 && r0->credit == BR_ASSET_CREDIT_BACKED && r0->hasId && strcmp(r0->id, X) == 0 && r0->qty == 5, "output 0: X:5 BACKED");
    ck(r2 && r2->hasId && strcmp(r2->id, X) == 0 && r2->qty == 4, "output 2: X:4");
    ck(strcmp(balances(), "Lax:9") == 0, "the balance is X:9 (%s)", balances());
    ck(LOOKUPS == 0, "no lookup");
}

static void recv_honest_unknown(void)   // the_honest_control_from_unknown_inputs_counts_once_the_indexer_confirms_it
{
    In ins[2] = { FOREIGN, FOREIGN };
    int owned[1] = { 0 };
    KRow *r;

    wreset();
    receiveTx(CONTROL, ins, 2, owned, 1, 3);
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":5");
    statusIs("control", detect(&RX->tx), BR_ASSET_DETECT_DONE);
    apply(0);
    r = detectedRow(0);
    ck(r && r->credit == BR_ASSET_CREDIT_VERIFIED && r->hasId && strcmp(r->id, X) == 0 && r->qty == 5, "output 0: X:5 VERIFIED");
    ck(strcmp(balances(), "Lax:5") == 0, "the balance is X:5 (%s)", balances());
}

static void recv_second_input(void)   // a_second_inputs_asset_is_named_as_itself_from_known_inputs
{
    In ins[2];
    int owned[1] = { 0 };
    KRow *r;

    wreset();
    ins[0] = own(X, 1); ins[1] = own(Y, 1000000);
    statusIs("[X:1][Y:1000000]", receive("6a09444103158201002644", ins, 2, owned, 1, 3), BR_ASSET_DETECT_DONE);
    r = detectedRow(0);
    ck(r && r->hasId && strcmp(r->id, Y) == 0 && r->qty == 1000000, "output 0 holds Y:1000000, named Y");
    ck(strcmp(balances(), "Lay:1000000") == 0, "the balance is Y:1000000 (%s)", balances());
}

static void recv_skip(void)   // a_skip_past_the_first_carrier_names_the_second_carriers_asset
{
    In ins[2];
    int owned[2] = { 0, 1 };
    KRow *r0, *r1;

    wreset();
    ins[0] = own(B, 10); ins[1] = own(A, 10);
    statusIs("[B:10][A:10]", receive("6a0844410315810a000a", ins, 2, owned, 2, 3), BR_ASSET_DETECT_DONE);
    r0 = detectedRow(0);
    r1 = detectedRow(1);
    ck(r0 && r0->hasId && strcmp(r0->id, A) == 0 && r0->qty == 10, "output 0 holds A:10");
    ck(r1 && r1->hasId && strcmp(r1->id, B) == 0 && r1->qty == 10, "output 1 holds B:10");
}

static void recv_indexer_names(void)   // the_indexer_names_the_asset_and_replaces_a_first_input_label
{
    In ins[2] = { FOREIGN, FOREIGN };
    int owned[1] = { 0 };
    KRow *r;

    wreset();
    receiveTx("6a09444103158201002644", ins, 2, owned, 1, 3);
    addRow(RX->tx.txHash, 0, OURS, BR_ASSET_CREDIT_UNCHECKED, 1000000, X, 0);   // an earlier label, unchecked
    ck(strcmp(balances(), "-") == 0, "a row that is not backed counts for nothing");
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, Y ":1000000");
    statusIs("re-detected", detect(&RX->tx), BR_ASSET_DETECT_DONE);
    ck(BOUTS[0].persist.action == BR_ASSET_PERSIST_UPDATE, "the row the table holds is updated, not inserted again");
    apply(0);
    r = detectedRow(0);
    ck(r && r->hasId && strcmp(r->id, Y) == 0 && r->qty == 1000000 && r->credit == BR_ASSET_CREDIT_VERIFIED,
       "the indexer's asset and count replace the label");
    ck(strcmp(balances(), "Lay:1000000") == 0, "the balance is Y:1000000 (%s)", balances());
}

static void recv_mixed(void)   // a_mixed_input_delivers_each_asset_to_its_own_output, the indexer's half
{
    BRAssetTransferInstruction l[2];
    In ins[1] = { FOREIGN };
    int owned[1] = { 0 };
    KRow *r;

    wreset();
    l[0] = ti(0, 3, 0, 0); l[1] = ti(1, 4, 0, 0);
    receiveTx(encodeTransfer(l, 2), ins, 1, owned, 1, 3);
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":3," Y ":4");
    statusIs("mixed", detect(&RX->tx), BR_ASSET_DETECT_DONE);
    apply(0);
    r = detectedRow(0);
    ck(r && r->credit == BR_ASSET_CREDIT_MIXED && r->hasId && strcmp(r->id, DET.placeholder) == 0,
       "an output the indexer reports two assets on is MIXED under the placeholder");
    ck(strcmp(balances(), "-") == 0, "and counts for nothing");
}

// The issuance helper: one plain input (it names the asset), OP_RETURN at the first output not owned.
static BRAssetDetectStatus issue(const char *script, const int *owned, size_t nOwned, int32_t outputCount)
{
    In ins[1] = { PLAIN };

    return receive(script, ins, 1, owned, nOwned, outputCount);
}

static void derivedPlaceholder(const char *what)
{
    char prev[65], want[BR_ASSET_ID_BUFFER];

    hexOf(RX->tx.inputs[0].txHash, prev);
    ck(BRAssetIdForIssuance(prev, (int32_t)RX->tx.inputs[0].index, 1, DET.header.aggregation, DET.header.divisibility, want,
                            sizeof(want)) &&
       DET.placeholderDerived && strcmp(DET.placeholder, want) == 0,
       "%s: the placeholder is the id derived from the first input (%s)", what, DET.placeholder);
}

static void recv_issuance(void)   // an_issuance_credits_only_where_its_instructions_put_the_units, and the others
{
    static const char *review[2] = { "6a09" "44410305" "2016" "01" "2016" "10", "6a0a" "44410305" "2016" "01" "2016" "10" };
    int owned01[2] = { 0, 1 }, owned0[1] = { 0 }, owned02[2] = { 0, 2 };
    size_t i;
    KRow *r;
    const BRAssetDetectOutput *o;

    for (i = 0; i < 2; i++) {
        wreset();
        statusIs(review[i], issue(review[i], owned01, 2, 3), BR_ASSET_DETECT_DONE);
        derivedPlaceholder(review[i]);
        r = detectedRow(0);
        ck(r && r->credit == BR_ASSET_CREDIT_BACKED && r->qty == 0, "%s: output 0 is BACKED at 0", review[i]);
        o = outAt(1);
        if (i == 1) {
            ck(o && holds(&o->credit, DET.placeholder, 1000000), "%s: read exactly, output 1 holds the 1000000", review[i]);
            ck(strcmp(balances(), "-") != 0 && detectedRow(1) && detectedRow(1)->qty == 1000000 &&
               detectedRow(1)->credit == BR_ASSET_CREDIT_BACKED, "%s: and counts", review[i]);
        }
        else {
            ck(o && o->credit.kind == BR_ASSET_OUTPUT_UNKNOWN, "%s: not read exactly, output 1 waits for the indexer", review[i]);
            ck(strcmp(balances(), "-") == 0, "%s: nothing counts", review[i]);
        }
    }

    wreset();   // an_issuance_with_no_instruction_lands_on_the_last_output_or_nowhere
    issue("6a07" "44410305" "2016" "10", owned0, 1, 2);
    r = detectedRow(0);
    ck(r && r->qty == 0 && r->credit == BR_ASSET_CREDIT_BACKED, "no instruction, [ours, OP_RETURN]: output 0 holds nothing, BACKED");
    wreset();
    issue("6a07" "44410305" "2016" "10", owned02, 2, 3);
    ck(detectedRow(0) && detectedRow(0)->qty == 0, "no instruction, [a, OP_RETURN, last]: output 0 holds nothing");
    r = detectedRow(2);
    ck(r && r->hasId && strcmp(r->id, DET.placeholder) == 0 && r->qty == 1000000, "and the last output holds the whole issue");

    wreset();   // an_honest_issuance_to_output_zero_is_credited_in_full
    issue("6a0a" "44410305" "2016" "00" "2016" "10", owned0, 1, 2);
    r = detectedRow(0);
    ck(r && r->credit == BR_ASSET_CREDIT_BACKED && r->qty == 1000000, "an honest issuance to output 0 is credited in full");

    wreset();   // an_issuance_instruction_beyond_the_issue_voids_to_the_last_output
    issue("6a08" "44410305" "0a" "00" "14" "10", owned02, 2, 3);
    ck(detectedRow(0) && detectedRow(0)->qty == 0 && detectedRow(2) && detectedRow(2)->qty == 10,
       "an instruction beyond the issue voids them all: 0 on output 0, 10 on the last");

    wreset();   // an_issuance_payload_not_read_exactly_is_left_unchecked
    issue("6a09" "44410305" "2016" "01" "20" "10", owned01, 2, 3);
    ck(! DET.payloadExact, "the cut-off amount is not read exactly");
    o = outAt(1);
    r = detectedRow(1);
    ck(o && o->credit.kind == BR_ASSET_OUTPUT_UNKNOWN && r && r->credit == BR_ASSET_CREDIT_UNCHECKED,
       "the output the cut-off instruction names is UNKNOWN, its row UNCHECKED");
    ck(strcmp(balances(), "-") == 0, "nothing counts");
}

static void recv_offline_and_retries(void)
{
    In ins[1] = { FOREIGN };
    int owned[1] = { 0 }, owned1[1] = { 1 }, owned02[2] = { 0, 2 };
    KRow *r;

    wreset();   // with_no_indexer_an_unknown_receipt_stays_unchecked_and_uncounted
    W.indexerOn = 0;
    receive(CONTROL, ins, 1, owned, 1, 3);
    ck(detectedRow(0) && detectedRow(0)->credit == BR_ASSET_CREDIT_UNCHECKED && strcmp(balances(), "-") == 0,
       "with no indexer, UNCHECKED and uncounted");

    wreset();   // no_answer_is_asked_again_only_after_the_recheck_interval
    receive(CONTROL, ins, 1, owned, 1, 3);
    ck(LOOKUPS == 1, "asked once (%d)", LOOKUPS);
    settleAll(1000);
    ck(LOOKUPS == 1, "not asked again too soon");
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":5");
    settleAll(600000);
    ck(LOOKUPS == 2 && strcmp(balances(), "Lax:5") == 0, "asked again later, and X:5 counts (%s)", balances());

    wreset();   // an_indexer_count_above_what_the_instructions_deliver_is_not_believed
    receiveTx(CONTROL, ins, 1, owned, 1, 3);
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":1000000");
    detect(&RX->tx);
    ck(! BOUTS[0].bound.unbounded && BOUTS[0].bound.max == 5, "output 0 is bounded at 5");
    apply(0);
    ck(detectedRow(0) && detectedRow(0)->credit == BR_ASSET_CREDIT_UNCHECKED && strcmp(balances(), "-") == 0,
       "a count above the bound is not believed");
    wreset();
    receiveTx(CONTROL, ins, 1, owned02, 2, 3);
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":5");
    indexer(&RX->tx, 2, BR_ASSET_STACK_FOUND, X ":1000");
    detect(&RX->tx);
    ck(outAt(2) && outAt(2)->bound.unbounded, "the last output has no bound");
    apply(0);
    ck(strcmp(balances(), "Lax:1005") == 0, "exactly the bound is believed, and the last output is not capped (%s)", balances());

    wreset();   // a_settled_row_is_final
    receiveTx(CONTROL, ins, 1, owned, 1, 3);
    indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":5");
    detect(&RX->tx);
    apply(0);
    {
        int asked = LOOKUPS;

        statusIs("re-detected", detect(&RX->tx), BR_ASSET_DETECT_DONE);
        ck(DET.allSettled && ! DET.allocated, "every row settled: nothing is allocated again");
        apply(600000);
        ck(LOOKUPS == asked, "nobody is asked");
        r = detectedRow(0);
        ck(r && r->hasId && strcmp(r->id, X) == 0 && r->qty == 5, "the row is X:5 still");
    }

    wreset();   // an_output_no_instruction_names_and_not_last_holds_nothing_whatever_the_inputs
    receive(CONTROL, ins, 1, owned1, 1, 4);
    r = detectedRow(1);
    ck(r && r->credit == BR_ASSET_CREDIT_BACKED && r->qty == 0 && LOOKUPS == 0,
       "an output nothing can reach holds nothing, BACKED, with no lookup");
}

// AssetArithmeticNeverWrapsTest, the receive path
static void arith_receive(void)
{
    static const char *pastRange = "6a0c444103154001a5d21dba0007", *wide = "6a0d444103154200ffffffffffffff";
    static const char *uncountable[2] = { "6a0b4441031500bfffffffffff", "6a0944410315007fffffff" };
    const char *scripts[2];
    int32_t counts[2] = { 3, 514 };
    size_t i, j;

    scripts[0] = pastRange; scripts[1] = wide;
    for (i = 0; i < 2; i++) {
        for (j = 0; j < 2; j++) {
            In ins[1];
            int owned[1];
            KRow *r;

            wreset();
            ins[0] = j ? FOREIGN : PLAIN;
            owned[0] = counts[i] - 1;
            receiveTx(scripts[i], ins, 1, owned, 1, counts[i]);
            indexer(&RX->tx, counts[i] - 1, BR_ASSET_STACK_FOUND, "");
            detect(&RX->tx);
            apply(0);
            r = detectedRow(counts[i] - 1);
            ck(strcmp(balances(), "-") == 0 && r && r->qty == 0,
               "a_range_consumption_past_the_signed_range_credits_nothing_on_receive (%zu outputs, %s): nothing stored or counted",
               (size_t)counts[i], j ? "foreign" : "plain");
        }
    }
    for (i = 0; i < 2; i++) {
        for (j = 0; j < 2; j++) {
            In ins[1];
            int owned[2] = { 0, 2 };

            wreset();
            ins[0] = j ? own(X, 1) : PLAIN;
            statusIs("an_uncountable_amount_credits_nothing_on_receive", receive(uncountable[i], ins, 1, owned, 2, 3),
                     BR_ASSET_DETECT_UNCLASSIFIABLE);
            ck(DET.outputCount == 2 && BOUTS[0].targeted && BOUTS[1].targeted &&
               BOUTS[0].persist.action == BR_ASSET_PERSIST_NOTHING && ! detectedRow(0) && ! detectedRow(2),
               "%s: every owned output targeted, no row written", uncountable[i]);
            ck(strcmp(balances(), j ? "-" : "-") == 0, "%s: nothing counts", uncountable[i]);
        }
    }
    {   // an_indexer_count_for_uncountable_instructions_is_not_believed
        BRAssetTransferInstruction l[1];
        In ins[1] = { FOREIGN };
        int owned[1] = { 0 };

        wreset();
        l[0] = ti(512, 35958565450000000LL, 1, 0);
        receiveTx(encodeTransfer(l, 1), ins, 1, owned, 1, 514);
        indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, X ":35958565450000000");
        detect(&RX->tx);
        apply(0);
        ck(strcmp(balances(), "-") == 0 && detectedRow(0) && detectedRow(0)->credit != BR_ASSET_CREDIT_VERIFIED,
           "an_indexer_count_for_uncountable_instructions_is_not_believed");
    }
    {   // an_instruction_past_the_outputs_voids_the_transfer
        BRAssetTransferInstruction l[2];
        In ins[1];
        int owned[2] = { 0, 2 }, owned0[1] = { 0 };
        const char *s;

        wreset();
        l[0] = ti(0, 10, 0, 0); l[1] = ti(5, 5, 0, 0);
        s = encodeTransfer(l, 2);
        ins[0] = own(X, 15);
        receive(s, ins, 1, owned, 2, 3);
        ck(detectedRow(0) && detectedRow(0)->qty == 0 && detectedRow(2) && detectedRow(2)->qty == 15 &&
           strcmp(balances(), "Lax:15") == 0, "an instruction past the outputs voids the transfer: X:15 on the last (%s)", balances());
        wreset();
        ins[0] = FOREIGN;
        receiveTx(s, ins, 1, owned0, 1, 3);
        indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, "");
        detect(&RX->tx);
        apply(0);
        ck(strcmp(balances(), "-") == 0 && detectedRow(0) && detectedRow(0)->qty <= 15, "from someone else's inputs: nothing counts");
    }
    {   // a_rule_bound_input_is_not_allocated_on_the_device
        In ins[1];
        int owned[1] = { 0 };

        wreset();
        W.autoKind = R_BOUND;
        ins[0] = own(X, 9);
        receiveTx(CONTROL, ins, 1, owned, 1, 3);
        indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, "");
        detect(&RX->tx);
        ck(outAt(0) && outAt(0)->credit.kind == BR_ASSET_OUTPUT_UNKNOWN, "a rule-bound input's transfer: the output is UNKNOWN");
        apply(0);
        ck(strcmp(balances(), "-") == 0, "and nothing counts");
    }
    {   // an_unbacked_fixed_instruction_counts_for_nothing
        for (j = 0; j < 2; j++) {
            In ins[1];
            int owned[1] = { 0 };

            wreset();
            ins[0] = j ? FOREIGN : PLAIN;
            receiveTx("6a094441031500698967f0", ins, 1, owned, 1, 3);
            indexer(&RX->tx, 0, BR_ASSET_STACK_FOUND, "");
            detect(&RX->tx);
            apply(0);
            ck(strcmp(balances(), "-") == 0, "an_unbacked_fixed_instruction_counts_for_nothing (%s)", j ? "foreign" : "plain");
        }
    }
}

// ================================================================================================
// protect: AssetPersistOnDetectTest, AssetZeroQuantityInputTest, asked of `targeted`
// ================================================================================================

// The owned outputs of the last detection that are targeted, as "0,2,..".
static const char *targetedList(void)
{
    static char text[256];
    size_t i, at = 0;

    text[0] = '\0';
    for (i = 0; i < DET.outputCount; i++) {
        if (BOUTS[i].targeted) at += (size_t)snprintf(text + at, sizeof(text) - at, "%s%d", at ? "," : "", (int)BOUTS[i].vout);
    }
    return at ? text : "-";
}

static void protect_targets(void)
{
    GInst g[3];
    Fx *f, *p;
    int32_t v;

    // protects_every_targeted_owned_output_and_not_plain_change: explicit 0, range 0..3, percent 5; 100 units
    // in (one row of the wallet's), so only the percent makes the remainder unknown. Outputs 0..7 are ours and
    // the carrier is output 8, the last: output 7 is not the last here, and the next layout makes it so.
    memset(g, 0, sizeof(g));
    g[0].out = 0; g[0].amount = 10;
    g[1].range = 1; g[1].out = 3; g[1].amount = 5;
    g[2].percent = 1; g[2].out = 5; g[2].amount = 50;
    wreset();
    addRow(tid(0x20), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 100, X, 1);
    f = fx(0x22);
    in(f, tid(0x20), 0);
    for (v = 0; v < 8; v++) ours(f);
    outCarrier(f, 3, 0x15, 0, 0, g, 3, 0);
    wadd(f);
    detect(&f->tx);
    ck(strcmp(targetedList(), "0,1,2,3,5") == 0,
       "explicit, range and percent targets are held, plain change at 4, 6 and 7 is not: %s", targetedList());

    // the implicit change on the last output: the Kotlin case's output 7 is the last of 8
    wreset();
    addRow(tid(0x20), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 100, X, 1);
    f = fx(0x23);
    in(f, tid(0x20), 0);
    for (v = 0; v < 7; v++) ours(f);
    outCarrier(f, 3, 0x15, 0, 0, g, 3, 0);   // output 7
    ours(f);                                  // output 8, the last
    wadd(f);
    detect(&f->tx);
    ck(strcmp(targetedList(), "0,1,2,3,5,8") == 0, "the unknown remainder holds the last output too: %s", targetedList());

    // protects_every_owned_output_of_an_unclassifiable_carrier
    wreset();
    f = fx(0x24);
    ours(f); out(f, UNCLASSIFIABLE_PD2); ours(f); ours(f);
    wadd(f);
    statusIs("unclassifiable", detect(&f->tx), BR_ASSET_DETECT_UNCLASSIFIABLE);
    ck(strcmp(targetedList(), "0,2,3") == 0, "every owned output of an unclassifiable carrier: %s", targetedList());

    // protects_the_outputs_an_issuance_instruction_targets: owned 0..3 of 5, the instruction names 2
    wreset();
    p = fx(0x25); theirs(p); wadd(p);
    f = fx(0x26);
    in(f, tid(0x25), 0);
    memset(g, 0, sizeof(g));
    g[0].out = 2; g[0].amount = 100;
    for (v = 0; v < 4; v++) ours(f);
    outCarrier(f, 3, 0x05, 100, 0x10, g, 1, 0);
    wadd(f);
    detect(&f->tx);
    ck(strcmp(targetedList(), "0,2") == 0, "an issuance: the issuer's output and its instruction's: %s", targetedList());

    // protects_the_outputs_a_burn_transaction_still_sends_units_to: 60 burned, 40 to output 1, 100 in
    wreset();
    addRow(tid(0x20), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 100, X, 1);
    f = fx(0x27);
    in(f, tid(0x20), 0);
    memset(g, 0, sizeof(g));
    g[0].out = 31; g[0].amount = 60;
    g[1].out = 1; g[1].amount = 40;
    for (v = 0; v < 4; v++) ours(f);
    outCarrier(f, 3, 0x25, 0, 0, g, 2, 0);
    wadd(f);
    detect(&f->tx);
    ck(strcmp(targetedList(), "1") == 0, "a burn: only the output its other instruction names: %s", targetedList());

    // output_31_is_a_target_unless_the_transaction_is_a_burn: owned 30, 31, 32 of 33, 10 in
    {
        int burn;

        for (burn = 0; burn < 2; burn++) {
            wreset();
            addRow(tid(0x20), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 10, X, 1);
            f = fx(0x28);
            in(f, tid(0x20), 0);
            memset(g, 0, sizeof(g));
            g[0].out = 31; g[0].amount = 10;
            outCarrier(f, 3, burn ? 0x25 : 0x15, 0, 0, g, 1, 0);
            for (v = 1; v < 30; v++) theirs(f);
            for (v = 30; v < 33; v++) ours(f);
            wadd(f);
            detect(&f->tx);
            ck(strcmp(targetedList(), burn ? "-" : "31") == 0, "output 31 %s: %s", burn ? "of a burn is the destroy marker" : "of a transfer is a target",
               targetedList());
        }
    }

    // detection_hears_of_each_output_it_newly_holds: owned 0, 2, 3 of 4, inputs unknown; 10 to output 0
    wreset();
    f = fx(0x29);
    in(f, tid(0x2a), 0);   // a transaction the wallet does not hold
    ours(f); out(f, "6a0644410115000a"); ours(f); ours(f);
    wadd(f);
    detect(&f->tx);
    ck(strcmp(targetedList(), "0,3") == 0, "output 0 (named) and output 3 (the unknown remainder) are targeted: %s", targetedList());
}

// ================================================================================================
// this suite's own
// ================================================================================================

// Which outputs get a row: owned, not OP_RETURN; every output when there is no owned set.
static void rows_outputs(void)
{
    Fx *f;
    In ins[1];
    int owned[1] = { 0 };

    wreset();
    ins[0] = own(X, 9);
    receiveTx(CONTROL, ins, 1, owned, 1, 3);   // [ours, OP_RETURN, theirs]
    detect(&RX->tx);
    ck(DET.outputCount == 1 && BOUTS[0].vout == 0, "the wallet's own output gets a row; OP_RETURN and someone else's do not");
    W.ownedN = 0;
    detect(&RX->tx);
    ck(DET.outputCount == 2 && BOUTS[0].vout == 0 && BOUTS[1].vout == 2, "no owned set: every output but the OP_RETURN");
    ck(! BOUTS[0].askIndexer && ! BOUTS[1].askIndexer, "and the indexer is told about none of them");
    W.ownedN = 2;
    wreset();
    W.ownedN = 2;
    f = fx(0x30);
    in(f, tid(0x31), 0);
    out(f, OURS2); out(f, CONTROL); ours(f); theirs(f); out(f, PLAIN_NOTE);
    wadd(f);
    detect(&f->tx);
    ck(DET.outputCount == 2 && BOUTS[0].vout == 0 && BOUTS[1].vout == 2, "both owned scripts count; a plain OP_RETURN never does");
    ck(DET.carrierAt == 1 && DET.carrierIsFirstOpReturn, "the carrier is output 1, the first OP_RETURN");
}

static void rows_carrier(void)   // the carrier: none, a plain OP_RETURN before it, two carriers, unclassifiable beside one
{
    Fx *f;
    BRAssetHeader h;
    size_t at = 99;

    wreset();
    f = fx(0x40); ours(f); out(f, PLAIN_NOTE); wadd(f);
    statusIs("a plain OP_RETURN only", detect(&f->tx), BR_ASSET_DETECT_NOT_ASSET);
    ck(DET.outputCount == 0, "nothing to write");
    ck(BRAssetHoldCarrierOf(&f->tx, &at, &h, NULL) == BR_ASSET_CARRIER_NONE && at == 99, "BRAssetHoldCarrierOf: none, nothing written");
    f = fx(0x41); wadd(f);
    statusIs("no outputs", detect(&f->tx), BR_ASSET_DETECT_NOT_ASSET);

    wreset();
    f = fx(0x42); in(f, tid(0x43), 0); ours(f); out(f, PLAIN_NOTE); out(f, CONTROL); theirs(f); wadd(f);
    statusIs("a plain OP_RETURN before the carrier", detect(&f->tx), BR_ASSET_DETECT_DONE);
    ck(DET.carrierAt == 2 && ! DET.carrierIsFirstOpReturn, "the carrier is output 2, not the first OP_RETURN");

    wreset();
    f = fx(0x44); in(f, tid(0x43), 0); ours(f); out(f, "6a0644410315000a"); out(f, "6a06444103150105"); ours(f); wadd(f);
    detect(&f->tx);
    ck(DET.carrierAt == 2 && outAt(0) && outAt(0)->claimed == 0 && DET.header.instructionCount == 1 && DET.insts[0].outputIndex == 1,
       "two carriers: the last one is read (5 to output 1, nothing claimed on output 0)");
    ck(BRAssetHoldCarrierOf(&f->tx, &at, &h, NULL) == BR_ASSET_CARRIER_FOUND && at == 2 && h.kind == BR_ASSET_SCRIPT_ASSET,
       "BRAssetHoldCarrierOf says the same");

    wreset();
    f = fx(0x45); in(f, tid(0x43), 0); ours(f); out(f, UNCLASSIFIABLE_PD2); out(f, CONTROL); ours(f); wadd(f);
    statusIs("an unclassifiable carrier beside a readable one", detect(&f->tx), BR_ASSET_DETECT_UNCLASSIFIABLE);
    ck(DET.carrierAt == 1 && strcmp(targetedList(), "0,3") == 0, "it decides: every owned output targeted (%s)", targetedList());
}

static void placeholder_rules(void)
{
    Fx *p, *f;
    GInst g[1];
    char want[96], t[65];
    int flags;

    for (flags = 0; flags < 4; flags++) {
        // 0: locked aggregatable div 0; 1: locked hybrid div 2; 2: unlocked; 3: locked, first input past INT32_MAX
        wreset();
        p = fx(0x50); theirs(p); theirs(p); wadd(p);
        f = fx(0x51);
        in(f, tid(0x50), flags == 3 ? 0x80000000u : 1);
        memset(g, 0, sizeof(g));
        g[0].out = 0; g[0].amount = 7;
        ours(f);
        outCarrier(f, 3, 0x05, 7, flags == 0 ? 0x10 : flags == 1 ? (0x10 | 0x04 | (2 << 5)) : flags == 2 ? 0x00 : 0x10, g, 1, 0);
        wadd(f);
        detect(&f->tx);
        hexOf(f->tx.txHash, t);
        if (flags <= 1) {
            char prev[65];

            hexOf(tid(0x50), prev);
            BRAssetIdForIssuance(prev, 1, 1, flags ? BR_ASSET_HYBRID : BR_ASSET_AGGREGATABLE, flags ? 2 : 0, want, sizeof(want));
            ck(DET.placeholderDerived && strcmp(DET.placeholder, want) == 0 && strncmp(want, flags ? "Lh" : "La", 2) == 0,
               "a locked issuance is named from its first input, %s (%s)", flags ? "hybrid, divisibility 2" : "aggregatable", DET.placeholder);
        }
        else {
            snprintf(want, sizeof(want), "unresolved:%s", t);
            ck(! DET.placeholderDerived && strcmp(DET.placeholder, want) == 0, "%s: the placeholder is unresolved:<txid>",
               flags == 2 ? "an unlocked issuance" : "a first input past INT32_MAX");
        }
    }
    wreset();   // a transfer is never named here
    f = fx(0x52); in(f, tid(0x50), 0); ours(f); out(f, CONTROL); wadd(f);
    detect(&f->tx);
    hexOf(f->tx.txHash, t);
    snprintf(want, sizeof(want), "unresolved:%s", t);
    ck(strcmp(DET.placeholder, want) == 0 && strcmp(DET.txid, t) == 0, "a transfer: unresolved:<txid>, the txid in display order");
}

static void all_settled(void)
{
    In ins[2] = { FOREIGN, FOREIGN };
    int owned[2] = { 0, 2 };
    KRow *r;

    wreset();
    receiveTx(CONTROL, ins, 2, owned, 2, 3);
    addRow(RX->tx.txHash, 0, OURS, BR_ASSET_CREDIT_VERIFIED, 5, X, 0);
    addRow(RX->tx.txHash, 2, OURS, BR_ASSET_CREDIT_MIXED, 0, "unresolved:z", 0);
    detect(&RX->tx);
    ck(DET.allSettled && ! DET.allocated, "every row settled: nothing allocated");
    ck(BOUTS[0].credit.kind == BR_ASSET_OUTPUT_UNKNOWN && BOUTS[1].credit.kind == BR_ASSET_OUTPUT_UNKNOWN, "every credit UNKNOWN");
    ck(BOUTS[0].persist.action == BR_ASSET_PERSIST_UPDATE && BOUTS[0].persist.row.assetQuantity == 5 &&
       BOUTS[0].persist.row.credit == BR_ASSET_CREDIT_VERIFIED && BOUTS[0].persist.row.source == BR_ASSET_SOURCE_NATIVE,
       "a settled row is re-tagged NATIVE and nothing else");
    ck(BOUTS[1].persist.unresolved && DET.anyUnresolved, "a MIXED row under a placeholder is still unresolved");
    r = detectedRow(2);
    r->credit = BR_ASSET_CREDIT_UNCHECKED;
    detect(&RX->tx);
    ck(! DET.allSettled && DET.allocated, "one row not settled: the transaction is allocated");
    ck(DET.askCount == 0, "and asks nothing (its inputs are unknown)");
}

static void rules_step(void)
{
    In ins[2];
    int owned[1] = { 0 };
    BRAssetDetectStatus s;
    RuleKind kinds[] = { R_FREE, R_NOFACTS, R_UNLOCKED, R_BOUND, R_API, R_OTHER };
    size_t i;

    wreset();
    ins[0] = own(X, 9); ins[1] = own(X, 1);
    receiveTx(CONTROL, ins, 2, owned, 1, 3);
    s = detectOnce(&RX->tx);
    statusIs("the first call", s, BR_ASSET_DETECT_NEEDS_RULES);
    ck(DET.askCount == 1 && strcmp(BASKS[0], X) == 0 && DET.outputCount == 0, "X is asked about once, and nothing is written yet");
    for (i = 0; i < N(kinds); i++) {
        W.ruleN = 0;
        rule(X, kinds[i]);
        s = detectOnce(&RX->tx);
        statusIs("answered", s, BR_ASSET_DETECT_DONE);
        ck(kinds[i] == R_FREE ? holds(&BOUTS[0].credit, X, 5) : BOUTS[0].credit.kind == BR_ASSET_OUTPUT_UNKNOWN,
           "rule kind %d: %s", (int)kinds[i], kinds[i] == R_FREE ? "free, X:5 allocated" : "not provably free, UNKNOWN");
    }
    ck(! BRAssetDetectRuleFree(NULL), "no answer is not free");

    wreset();   // two assets: both asked, in input order, once each; an answer given is not asked again
    ins[0] = own(Y, 2); ins[1] = own(X, 9);
    receiveTx(CONTROL, ins, 2, owned, 1, 3);
    rule(X, R_FREE);
    detectOnce(&RX->tx);
    ck(DET.status == BR_ASSET_DETECT_NEEDS_RULES && DET.askCount == 1 && strcmp(BASKS[0], Y) == 0, "only Y, which was not answered");
    W.ruleN = 0;
    detectOnce(&RX->tx);
    ck(DET.askCount == 2 && strcmp(BASKS[0], Y) == 0 && strcmp(BASKS[1], X) == 0, "with no answers: Y then X");

    wreset();   // a payload not read exactly asks nothing
    ins[0] = own(X, 9);
    receiveTx("6a0644410315000500", ins, 1, owned, 1, 3);
    s = detectOnce(&RX->tx);
    ck(s == BR_ASSET_DETECT_DONE && ! DET.payloadExact && DET.askCount == 0, "a payload not read exactly is not allocated and asks nothing");
}

static void inputs_read(void)
{
    Fx *p, *q, *f;
    int owned[2] = { 0, 2 };

    // a zero row on an output its transaction does not target is plain change: units known
    wreset();
    p = fx(0x60); in(p, tid(0x5f), 0); ours(p); out(p, CONTROL); ours(p); ours(p); wadd(p);   // 5 to 0; 1 is the carrier
    addRow(p->tx.txHash, 2, OURS, BR_ASSET_CREDIT_UNCHECKED, 0, NULL, 1);   // output 2 of 4: nothing names it
    addRow(tid(0x5f), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 5, X, 1);
    f = fx(0x61); in(f, tid(0x60), 2); in(f, tid(0x62), 0); ours(f); out(f, "6a0644410315000a"); ours(f); wadd(f);
    addRow(tid(0x62), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 12, X, 1);
    detect(&f->tx);
    ck(DET.hasInputUnits && DET.inputUnits == 12, "a zero row nothing targets carries nothing: 12 in (%lld)", (long long)DET.inputUnits);
    ck(outAt(2) && outAt(2)->claimed == 2, "the last output claims the remainder, 2");
    ck(BIN[0].hasRow && BIN[0].rowIsTargeted == BR_ASSET_FACT_NO && BINROWS[0] != (size_t)-1, "the input facts: a row, not targeted");

    // the same zero row on the LAST output of a transaction whose inputs are not known: unknown
    wreset();
    p = fx(0x60); in(p, tid(0x5e), 0); ours(p); out(p, CONTROL); ours(p); wadd(p);
    addRow(p->tx.txHash, 2, OURS, BR_ASSET_CREDIT_UNCHECKED, 0, NULL, 1);
    f = fx(0x61); in(f, tid(0x60), 2); ours(f); out(f, "6a0644410315000a"); ours(f); wadd(f);
    detect(&f->tx);
    ck(! DET.hasInputUnits, "a zero row on a last output whose remainder is not known: the units are not known");
    ck(outAt(2) && outAt(2)->claimed == 0 && outAt(2)->targeted, "the last output claims nothing and is targeted");
    ck(BIN[0].rowIsTargeted == BR_ASSET_FACT_YES, "the input's row may be targeted (YES)");
    // ... and walked back: the funding transaction's own inputs are known and assign it all, so it is not
    q = fx(0x5e); theirs(q); wadd(q);   // plain: carries nothing
    addRow(tid(0x5d), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 5, X, 1);
    W.n = 0; wadd(q); wadd(p); wadd(f);
    p->ins[0].txHash = tid(0x5d); p->ins[0].index = 0;   // 0x60 now spends X:5, and gives all 5 to output 0
    detect(&f->tx);
    ck(DET.hasInputUnits && DET.inputUnits == 0 && BIN[0].rowIsTargeted == BR_ASSET_FACT_NO,
       "the walk back proves the zero row is plain change: 0 in");

    // a remainder proof stands in for unknown units
    wreset();
    f = fx(0x63); in(f, tid(0x64), 0); ours(f); out(f, "6a0644410315000a"); ours(f); wadd(f);
    detect(&f->tx);
    ck(! DET.hasInputUnits && outAt(2)->targeted, "unknown inputs: the last output is targeted");
    prove(f->tx.txHash);
    detect(&f->tx);
    ck(DET.hasInputUnits && DET.inputUnits == 10 && ! outAt(2)->targeted, "proven: 10 in (what the instruction assigns), not targeted");

    // an input past INT32_MAX: passed over in the total, an unknown input to the allocation
    wreset();
    addRow(tid(0x66), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 9, X, 1);
    f = fx(0x65); in(f, tid(0x66), 0); in(f, tid(0x67), 0x80000000u); ours(f); out(f, CONTROL); ours(f); wadd(f);
    detect(&f->tx);
    ck(DET.hasInputUnits && DET.inputUnits == 9, "an input past INT32_MAX is passed over: 9 in");
    ck(DET.allocated && DET.allocation.why == BR_ASSET_WHY_INPUT_UNKNOWN && outAt(0)->credit.kind == BR_ASSET_OUTPUT_UNKNOWN,
       "but the allocation reads it as an input it does not know: UNKNOWN");
    ck(! BIN[1].hasRow && BIN[1].fundingIsAssetTx == BR_ASSET_FACT_UNKNOWN && BINROWS[1] == (size_t)-1, "its facts are UNKNOWN");
    (void)owned;
}

static void outgoing_and_asks(void)
{
    In ins[1] = { FOREIGN };
    int owned[1] = { 0 };

    wreset();
    W.outgoing = 1;
    receiveTx(CONTROL, ins, 1, owned, 1, 3);
    detect(&RX->tx);
    ck(DET.outputCount == 1 && BOUTS[0].persist.action == BR_ASSET_PERSIST_NOTHING && ! BOUTS[0].askIndexer,
       "our own unconfirmed send: nothing written, nobody asked");
    ck(BOUTS[0].targeted, "its output is still targeted");
    W.outgoing = 0;
    detect(&RX->tx);
    ck(BOUTS[0].persist.action == BR_ASSET_PERSIST_INSERT && BOUTS[0].askIndexer, "once it is not: written, and asked about");
    W.ownedN = 0;
    detect(&RX->tx);
    ck(BOUTS[0].persist.action == BR_ASSET_PERSIST_INSERT && ! BOUTS[0].askIndexer, "no owned set: written, not asked about");
}

static void issuance_walk(void)
{
    int owned[1] = { 0 };
    In ins[1] = { PLAIN };
    KRow *r;

    wreset();   // a locked issuance, named at once
    W.walked = 1;
    receive("6a0a" "44410305" "2016" "00" "2016" "10", ins, 1, owned, 1, 2);
    ck(! DET.issuanceWalkDue, "named, walked this session: no walk");
    W.walked = 0;
    detect(&RX->tx);
    ck(DET.issuanceWalkDue, "named, not walked this session: walk (for its facts)");

    wreset();   // an unlocked issuance under a placeholder
    W.walked = 1;
    receive("6a0a" "44410305" "2016" "00" "2016" "00", ins, 1, owned, 1, 2);
    ck(DET.anyUnresolved && DET.issuanceWalkDue, "an unresolved row: walk, even after a walk this session");
    r = detectedRow(0);
    ck(r && r->hasId && strncmp(r->id, "unresolved:", 11) == 0, "the row carries the placeholder");

    wreset();   // a transfer: never
    W.walked = 0;
    receive(CONTROL, ins, 1, owned, 1, 3);
    ck(! DET.issuanceWalkDue, "a transfer never takes the issuance walk");
}

static void rename_rows(void)
{
    int owned[2] = { 0, 2 };
    In ins[1] = { PLAIN };
    BRAssetResolvedFacts named;
    size_t got[8], n = 99;
    BRAssetHoldingsStatus s;

    memset(&named, 0, sizeof(named));
    strcpy(named.assetId, Y);
    wreset();   // an unlocked issuance whose carrier is its first OP_RETURN
    receive("6a0a" "44410305" "2016" "02" "2016" "00", ins, 1, owned, 2, 3);
    addRowText("ab", 5, NULL, BR_ASSET_CREDIT_UNCHECKED, 0, DET.placeholder, 0);   // another row under it
    addRowText("cd", 6, NULL, BR_ASSET_CREDIT_UNCHECKED, 0, X, 0);
    syncView();
    s = BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, N(got), &n);
    ck(s == BR_ASSET_HOLDINGS_OK && n == 3, "every row under the placeholder is renamed (%zu)", n);
    s = BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, 2, &n);
    ck(s == BR_ASSET_HOLDINGS_CAPACITY && n == 3, "a list that cannot hold them: CAPACITY, and how many");
    {
        BRAssetResolvedFacts empty;

        memset(&empty, 0, sizeof(empty));
        s = BRAssetDetectRename(&DET, &empty, VROWS, ROWN, got, N(got), &n);
        ck(s == BR_ASSET_HOLDINGS_OK && n == 0, "an empty id renames nothing");
    }

    wreset();   // a derived id is never renamed
    receive("6a0a" "44410305" "2016" "02" "2016" "10", ins, 1, owned, 2, 3);
    syncView();
    s = BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, N(got), &n);
    ck(s == BR_ASSET_HOLDINGS_OK && n == 0, "a locked issuance's derived id is never renamed");

    wreset();   // a carrier that is not the first OP_RETURN: the walk read another output
    {
        Fx *p = fx(0x70), *f;

        theirs(p); wadd(p);
        f = fx(0x71);
        in(f, tid(0x70), 0);
        ours(f); out(f, "6a0a" "44410305" "2016" "02" "2016" "10"); out(f, "6a0a" "44410305" "2016" "02" "2016" "00"); ours(f);
        wadd(f);
        detect(&f->tx);
        apply(0);
        ck(DET.carrierAt == 2 && ! DET.carrierIsFirstOpReturn && BRAssetIdIsUnresolved(DET.placeholder),
           "the last carrier (unlocked) is read: a placeholder");
        syncView();
        s = BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, N(got), &n);
        ck(s == BR_ASSET_HOLDINGS_OK && n == 0, "its rows are not renamed by a walk that read the first OP_RETURN");
    }

    wreset();   // a transfer is not renamed
    receive(CONTROL, ins, 1, owned, 2, 3);
    syncView();
    s = BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, N(got), &n);
    ck(s == BR_ASSET_HOLDINGS_OK && n == 0, "a transfer's placeholder is not renamed by the issuance walk");
    ck(BRAssetDetectRename(NULL, &named, VROWS, ROWN, got, N(got), &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetDetectRename(&DET, NULL, VROWS, ROWN, got, N(got), &n) == BR_ASSET_HOLDINGS_REFUSED &&
       BRAssetDetectRename(&DET, &named, VROWS, ROWN, got, N(got), NULL) == BR_ASSET_HOLDINGS_REFUSED, "NULLs refuse");
}

static void facts_walk(void)
{
    In ins[2];
    int owned[2] = { 0, 2 };
    const char *ids[4];
    size_t n = 99;
    BRAssetFactsWalkDue d;

    wreset();
    ins[0] = own(X, 9); ins[1] = own(Y, 3);
    receive(CONTROL, ins, 2, owned, 2, 3);   // 5 of X to 0; X:4 and Y:3 to the last: MIXED
    addRowText("ef", 0, NULL, BR_ASSET_CREDIT_UNCHECKED, 7, A, 0);   // another transaction's row
    detectedRow(0)->credit = BR_ASSET_CREDIT_BACKED;
    addRow(RX->tx.txHash, 1, OURS, BR_ASSET_CREDIT_UNCHECKED, 7, B, 0);   // unchecked: does not count
    syncView();
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 0, 0, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NOW && n == 1 && strcmp(ids[0], X) == 0, "NOW, for X alone (its row counts, the others do not)");
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 1, 1000, 1000 + BR_ASSET_FACTS_WALK_RETRY_MS - 1, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_LATER, "within two minutes of the last attempt: LATER");
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 1, 1000, 1000 + BR_ASSET_FACTS_WALK_RETRY_MS, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NOW, "two minutes after: NOW");
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 1, 5000, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_LATER, "a clock that went back: LATER, as the Kotlin's signed difference reads it");
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 1, 0, 0, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NO, "walked this session: NO");
    detectedRow(1)->credit = BR_ASSET_CREDIT_VERIFIED;
    addRow(RX->tx.txHash, 3, OURS, BR_ASSET_CREDIT_VERIFIED, 2, X, 0);   // X again: once
    syncView();
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 0, 0, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NOW && n == 2 && strcmp(ids[0], X) == 0 && strcmp(ids[1], B) == 0, "X then B, each once");
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 0, 0, 1000, ids, 1, &n);
    ck(d == BR_ASSET_FACTS_WALK_REFUSED && n == 2, "a list that cannot hold them: REFUSED, and how many");
    detectedRow(1)->qty = 0;
    detectedRow(3)->hasId = 0;
    detectedRow(0)->credit = BR_ASSET_CREDIT_MIXED;
    syncView();
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 0, 0, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NO, "a quantity of 0, no id, a MIXED row: nothing to walk");

    wreset();   // an issuance is not walked this way
    ins[0] = PLAIN;
    receive("6a0a" "44410305" "2016" "00" "2016" "10", ins, 1, owned, 1, 2);
    syncView();
    d = BRAssetDetectFactsWalk(&DET, VROWS, ROWN, 0, 0, 0, 1000, ids, N(ids), &n);
    ck(d == BR_ASSET_FACTS_WALK_NO, "an issuance: NO (it takes the issuance walk)");
}

static void refusals(void)
{
    Fx *f;
    BRAssetDetectView v;
    BRAssetDetectNeeds needs;
    BRAssetDetectStatus s;
    In ins[1] = { FOREIGN };
    int owned[1] = { 0 };
    KRow *k;

    wreset();
    receiveTx(CONTROL, ins, 1, owned, 1, 3);
    viewFor(&v, &RX->tx);
    BRAssetDetectBounds(&v, &needs);
    buffersFor(&needs);
    ck(BRAssetDetect(NULL, &BUF, &DET) == BR_ASSET_DETECT_REFUSED_ARGUMENTS && DET.status == BR_ASSET_DETECT_REFUSED_ARGUMENTS,
       "no view: ARGUMENTS");
    ck(BRAssetDetect(&v, NULL, &DET) == BR_ASSET_DETECT_REFUSED_ARGUMENTS, "no buffers: ARGUMENTS");
    ck(BRAssetDetect(&v, &BUF, NULL) == BR_ASSET_DETECT_REFUSED_ARGUMENTS, "no answer: ARGUMENTS");
    v.rows = NULL; v.rowCount = 1;
    ck(BRAssetDetect(&v, &BUF, &DET) == BR_ASSET_DETECT_REFUSED_ARGUMENTS, "rows NULL with a count: ARGUMENTS");
    viewFor(&v, &RX->tx);
    BUF.inputs = NULL;
    ck(BRAssetDetect(&v, &BUF, &DET) == BR_ASSET_DETECT_REFUSED_ARGUMENTS, "a buffer NULL with a capacity: ARGUMENTS");
    buffersFor(&needs);
    OWNED[0].bytes = NULL;
    ck(BRAssetDetect(&v, &BUF, &DET) == BR_ASSET_DETECT_REFUSED_ARGUMENTS, "an owned script NULL with a length: ARGUMENTS");
    OWNED[0].bytes = OWNED_BYTES[0];

    // unreadable
    {
        uint8_t *keep = RX->tx.outputs[0].script;

        RX->tx.outputs[0].script = NULL;
        statusIs("a script NULL with a length", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_UNREADABLE);
        RX->tx.outputs[0].script = keep;
    }
    wreset();
    receiveTx(CONTROL, ins, 1, owned, 1, 3);
    RX->tx.outputs[2].amount = (uint64_t)INT64_MAX + 1;
    statusIs("a value past INT64_MAX", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_UNREADABLE);
    RX->tx.outputs[2].amount = 600;
    f = fx(0x80); ours(f); wadd(f); f->tx.inCount = 1; f->tx.inputs = NULL;
    statusIs("a wallet transaction with inputs NULL", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_UNREADABLE);
    W.n--;
    statusIs("readable again", detectOnce(&RX->tx), BR_ASSET_DETECT_DONE);

    // rows
    k = addRowText("ABCDEF", 0, OURS, BR_ASSET_CREDIT_VERIFIED, 1, X, 0);
    statusIs("a txid that is not 64 hex digits", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    {
        char upper[65];
        size_t i;

        hexOf(tid(0x81), upper);
        for (i = 0; i < 64; i++) if (upper[i] >= 'a') upper[i] = (char)(upper[i] - 32);
        strcpy(k->txid, upper);
        statusIs("a txid in upper case", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
        hexOf(tid(0x81), k->txid);
        statusIs("lower case", detectOnce(&RX->tx), BR_ASSET_DETECT_DONE);
    }
    k->vout = -1;
    statusIs("a negative vout", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    k->vout = 0;
    k->isAsset = 0;
    statusIs("a row that is not an asset row", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    k->isAsset = 1;
    addRow(tid(0x81), 0, OURS, BR_ASSET_CREDIT_VERIFIED, 1, X, 0);
    statusIs("the same row twice", detectOnce(&RX->tx), BR_ASSET_DETECT_DONE);
    ROWS[ROWN - 1].qty = 2;
    statusIs("two rows that differ in quantity", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    ROWS[ROWN - 1].qty = 1;
    ROWS[ROWN - 1].credit = BR_ASSET_CREDIT_BACKED;
    statusIs("two rows that differ in credit", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    ROWS[ROWN - 1].credit = BR_ASSET_CREDIT_VERIFIED;
    setId(&ROWS[ROWN - 1], Y);
    statusIs("two rows that differ in asset id", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    setId(&ROWS[ROWN - 1], NULL);
    statusIs("two rows, one with no id", detectOnce(&RX->tx), BR_ASSET_DETECT_REFUSED_ROWS);
    setId(&ROWS[ROWN - 1], X);
    statusIs("agreeing again", detectOnce(&RX->tx), BR_ASSET_DETECT_DONE);

    // a refusal is not "nothing to write", and an answer nobody filled is a refusal
    memset(&DET, 0, sizeof(DET));
    ck(DET.status == BR_ASSET_DETECT_REFUSED && DET.outputCount == 0, "a zeroed answer reads as REFUSED");
    s = BR_ASSET_DETECT_REFUSED_ROWS;
    ck(s != BR_ASSET_DETECT_NOT_ASSET, "ROWS is not NOT_ASSET");
}

static void capacity(void)
{
    BRAssetDetectView v;
    BRAssetDetectNeeds needs, n2;
    In ins[2];
    int owned[2] = { 0, 2 };
    BRAssetTransferInstruction l[1];
    size_t field;

    wreset();
    ins[0] = own(X, 9); ins[1] = PLAIN;
    rule(X, R_FREE);
    l[0] = ti(4, 1, 1, 0);   // a range over five outputs: four of them
    receiveTx(encodeTransfer(l, 1), ins, 2, owned, 2, 4);
    viewFor(&v, &RX->tx);
    ck(BRAssetDetectBounds(&v, &needs), "bounds");
    ck(needs.work == 2 && needs.holdRows == 1 && needs.inputs == 2 && needs.outputs == 4 && needs.delivered == 2 + 4,
       "work = the most of 2 transactions, 1 row, 0 proofs; inputs 2; outputs 4; delivered 2 + 4 (%zu %zu %zu %zu %zu)",
       needs.work, needs.holdRows, needs.inputs, needs.outputs, needs.delivered);
    buffersFor(&needs);
    statusIs("exactly the bounds", BRAssetDetect(&v, &BUF, &DET), BR_ASSET_DETECT_DONE);
    for (field = 0; field < 5; field++) {
        buffersFor(&needs);
        if (field == 0) BUF.workCap--;
        if (field == 1) BUF.holdRowCap--;
        if (field == 2) BUF.inputCap--;
        if (field == 3) BUF.outputCap--;
        if (field == 4) BUF.deliveredCap--;
        statusIs("one entry short", BRAssetDetect(&v, &BUF, &DET), BR_ASSET_DETECT_REFUSED_CAPACITY);
        ck(DET.outputCount == 0 && DET.needs.work == needs.work && DET.needs.delivered == needs.delivered,
           "buffer %zu: CAPACITY, nothing to write, and what suffices", field);
    }
    ck(! BRAssetDetectBounds(NULL, &n2) && ! BRAssetDetectBounds(&v, NULL), "bounds of nothing: 0");
    v.tx = NULL;
    ck(! BRAssetDetectBounds(&v, &n2) && n2.work == 0, "bounds with no transaction: 0, zeroed");
}

static void input_facts_direct(void)   // BRAssetHoldInputFacts on its own
{
    Fx *p, *f;
    BRAssetHoldView hv;
    BRAssetHoldRow rows[1];
    BRAssetHoldInputs r;
    BRAssetInputFacts facts[3];
    size_t ri[3], orow[3];

    wreset();
    p = fx(0x90); ours(p); out(p, PLAIN_NOTE); wadd(p);   // has a data output, no payload
    f = fx(0x91); in(f, tid(0x90), 0); in(f, tid(0x92), 1); in(f, tid(0x93), 0); ours(f); out(f, CONTROL); ours(f); wadd(f);
    memset(rows, 0, sizeof(rows));
    rows[0].txHash = tid(0x93); rows[0].n = 0; rows[0].quantity = 4;
    memset(&hv, 0, sizeof(hv));
    hv.txs = W.txs; hv.txCount = W.n; hv.rows = rows; hv.rowCount = 1;
    r = BRAssetHoldInputFacts(&hv, BWORK, 3, &f->tx, facts, ri, 3, orow, 3);
    ck(r.status == BR_ASSET_HOLD_COMPLETE, "COMPLETE");
    ck(! facts[0].hasRow && facts[0].fundingIsAssetTx == BR_ASSET_FACT_NO && facts[0].fundingHasDataOutput == BR_ASSET_FACT_YES,
       "a plain funding transaction with a data output: no payload, a data output");
    ck(! facts[1].hasRow && facts[1].fundingIsAssetTx == BR_ASSET_FACT_UNKNOWN && facts[1].fundingHasDataOutput == BR_ASSET_FACT_UNKNOWN,
       "a funding transaction the wallet does not hold: UNKNOWN");
    ck(facts[2].hasRow && facts[2].quantity == 4 && ri[2] == 0 && ri[0] == (size_t)-1, "a row of 4, at place 0");
    ck(! r.hasUnits, "the unknown funding transaction makes the total unknown");
    ck(orow[0] == (size_t)-1 && orow[1] == (size_t)-1 && orow[2] == (size_t)-1, "no row for its own outputs");
    r = BRAssetHoldInputFacts(&hv, BWORK, 3, &f->tx, facts, ri, 2, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY, "facts for two of three inputs: CAPACITY");
    r = BRAssetHoldInputFacts(&hv, BWORK, 1, &f->tx, facts, ri, 3, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.workNeeded == 2, "work for one of two: CAPACITY, 2 suffice");
    r = BRAssetHoldInputFacts(&hv, BWORK, 3, &f->tx, NULL, NULL, 3, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_REFUSED_ARGUMENTS, "no facts buffer: ARGUMENTS");
    r = BRAssetHoldInputFacts(&hv, BWORK, 3, NULL, facts, ri, 3, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_REFUSED_ARGUMENTS, "no transaction: ARGUMENTS");
    ck(BRAssetHoldCarrierOf(NULL, NULL, NULL, NULL) == BR_ASSET_CARRIER_UNREADABLE, "BRAssetHoldCarrierOf(NULL): UNREADABLE");
}

static void second_unit(void)
{
    ck(asset_detect_kat_tu2(), "a second translation unit that includes the header twice links and answers");
}

// ================================================================================================
// the model sweep
// ================================================================================================

static const char *SWEEP_IDS[3] = { X, Y, "" };

static void randomCarrier(Fx *f, int nOut)
{
    GInst g[3];
    int ops[3] = { 0x05, 0x15, 0x25 }, op = ops[below(3)], n = (int)below(4), i;
    int version = below(5) == 0 ? 2 : 3, flags = (int)(below(2) ? 0x10 : 0) | (int)(below(3) << 2) | (int)(below(3) << 5);

    memset(g, 0, sizeof(g));
    for (i = 0; i < n && i < 3; i++) {
        g[i].skip = below(5) == 0;
        g[i].range = below(5) == 0;
        g[i].percent = below(10) == 0;
        g[i].out = (uint32_t)(below(6) == 0 ? 31 : below((uint64_t)nOut + 1));
        g[i].amount = below(4) == 0 ? 0 : below(40);
    }
    if (flags >> 2 == 3) flags &= ~0x0c;
    outCarrier(f, version, op, below(6) == 0 ? 0 : below(60), flags, g, (size_t)(n > 3 ? 3 : n), below(10) == 0 ? 1 : 0);
}

static void sweepWorld(void)
{
    size_t nTx = 2 + below(7), t, j, i;

    wreset();
    for (t = 0; t < nTx; t++) {
        Fx *f = fx(0x100 + (uint32_t)t);
        size_t nIn = below(4), nOut = 1 + below(5), at = below(6) == 0 ? 99 : below(nOut);

        for (i = 0; i < nIn; i++) {
            uint64_t r = below(12);

            if (r < 7 && t > 0) {
                size_t p = below(t);

                in(f, POOL[p].tx.txHash, (uint32_t)below(POOL[p].tx.outCount + 1));
            }
            else if (r < 11) in(f, tid(0x900 + (uint32_t)below(3)), (uint32_t)below(3));
            else in(f, tid(0x950), 0x80000000u + (uint32_t)below(2));
        }
        for (j = 0; j < nOut; j++) {
            uint64_t r = below(12);

            if (j == at) randomCarrier(f, (int)nOut);
            else if (r < 4) ours(f);
            else if (r < 6) out(f, OURS2);
            else if (r < 9) theirs(f);
            else if (r == 9) out(f, PLAIN_NOTE);
            else if (r == 10) randomCarrier(f, (int)nOut);
            else out(f, UNCLASSIFIABLE_PD2);
        }
        wadd(f);
    }
    for (t = 0; t < nTx; t++) {
        for (j = 0; j < POOL[t].tx.outCount; j++) {
            KRow *k;
            const char *id;
            char ph[96], t64[65];

            if (below(10) >= 4) continue;
            switch (below(6)) {
                case 0: id = NULL; break;
                case 1: id = SWEEP_IDS[2]; break;
                case 2:
                    hexOf(POOL[below(nTx)].tx.txHash, t64);
                    snprintf(ph, sizeof(ph), "unresolved:%s", t64);
                    id = ph;
                    break;
                case 3: id = Y; break;
                default: id = X; break;
            }
            k = addRow(POOL[t].tx.txHash, (int32_t)j, NULL, (BRAssetCredit)(below(3) == 0 ? below(4) : 1 + below(2)),
                       below(8) == 0 ? -(int64_t)below(3) : below(3) == 0 ? 0 : (int64_t)below(30), id, below(3) == 0);
            k->scriptLen = POOL[t].tx.outputs[j].scriptLen <= sizeof(k->script) ? POOL[t].tx.outputs[j].scriptLen : 0;
            memcpy(k->script, POOL[t].tx.outputs[j].script, k->scriptLen);
        }
        if (below(7) == 0) prove(POOL[t].tx.txHash);
    }
    W.ownedN = below(8) == 0 ? 0 : 2;
    W.height = below(2) ? 0 : 100;
    W.outgoing = below(10) == 0;
    W.walked = (int)below(2);
    {
        RuleKind kinds[] = { R_FREE, R_FREE, R_NOFACTS, R_UNLOCKED, R_BOUND, R_API, R_OTHER };

        if (below(3) == 0) rule(X, kinds[below(N(kinds))]);
        if (below(3) == 0) rule(Y, kinds[below(N(kinds))]);
    }
}

static void answerAll(void)   // every id any row names, answered (placeholders as nothing known)
{
    size_t i;

    for (i = 0; i < ROWN; i++) {
        if (ROWS[i].hasId && ROWS[i].id[0] && mRuleFree(ROWS[i].id) < 0) rule(ROWS[i].id, R_NOFACTS);
    }
}

static void model_sweep(void)
{
    size_t world, t, compared = 0, done = 0, asked = 0, allocated = 0, decided = 0, unclassifiable = 0, wrong = 0;
    const char *why = "";

    RNG = 0x5eed0001ULL;
    for (world = 0; world < 3000; world++) {
        int pass;

        sweepWorld();
        for (pass = 0; pass < 2; pass++) {
            if (pass == 1) answerAll();
            for (t = 0; t < W.n; t++) {
                const BRTransaction *tx = W.txs[t];
                BRAssetDetectStatus s = detectOnce(tx);

                mDetect(tx);
                compared++;
                if (s == BR_ASSET_DETECT_DONE) done++;
                if (s == BR_ASSET_DETECT_NEEDS_RULES) asked++;
                if (s == BR_ASSET_DETECT_UNCLASSIFIABLE) unclassifiable++;
                if (s == BR_ASSET_DETECT_DONE && DET.allocated && DET.allocation.status == BR_ASSET_ALLOCATION_ALLOCATED) allocated++;
                if (s == BR_ASSET_DETECT_DONE && DET.outputCount > 0 && BOUTS[0].credit.kind == BR_ASSET_OUTPUT_HOLDS) decided++;
                if (! agreesWithModel(&why) && wrong++ < 5) {
                    ck(0, "world %zu, transaction %zu, pass %d: the detection (%s) and the model differ in %s", world, t, pass, st(s), why);
                }
            }
        }
    }
    ck(wrong == 0, "the detection is the model on %zu transactions (%zu differ)", compared, wrong);
    ck(done > 2000 && asked > 200 && allocated > 300 && decided > 500 && unclassifiable > 200,
       "the sweep reaches every answer: %zu done, %zu asked about rules, %zu allocated, %zu with a decided first output, %zu unclassifiable",
       done, asked, allocated, decided, unclassifiable);
}

// ================================================================================================

static const struct { const char *name; void (*fn)(void); } CASES[] = {
    { "claim_quantity", claim_quantity },
    { "claim_remainder", claim_remainder },
    { "claim_arithmetic", claim_arithmetic },
    { "recv_unknown_inputs", recv_unknown_inputs },
    { "recv_known_inputs", recv_known_inputs },
    { "recv_voided_to_last", recv_voided_to_last },
    { "recv_honest_known", recv_honest_known },
    { "recv_honest_unknown", recv_honest_unknown },
    { "recv_second_input", recv_second_input },
    { "recv_skip", recv_skip },
    { "recv_indexer_names", recv_indexer_names },
    { "recv_mixed", recv_mixed },
    { "recv_issuance", recv_issuance },
    { "recv_offline_and_retries", recv_offline_and_retries },
    { "arith_receive", arith_receive },
    { "protect_targets", protect_targets },
    { "rows_outputs", rows_outputs },
    { "rows_carrier", rows_carrier },
    { "placeholder_rules", placeholder_rules },
    { "all_settled", all_settled },
    { "rules_step", rules_step },
    { "inputs_read", inputs_read },
    { "outgoing_and_asks", outgoing_and_asks },
    { "issuance_walk", issuance_walk },
    { "rename_rows", rename_rows },
    { "facts_walk", facts_walk },
    { "refusals", refusals },
    { "capacity", capacity },
    { "input_facts_direct", input_facts_direct },
    { "second_unit", second_unit },
    { "model_sweep", model_sweep },
};

int main(int argc, char **argv)
{
    size_t i;

    if (argc != 2) {
        fprintf(stderr, "usage: %s <case>|list\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            if (BROKEN) ck(0, "the case built a fixture this file's pools cannot hold");
            printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
            return FAILED ? 1 : 0;
        }
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
