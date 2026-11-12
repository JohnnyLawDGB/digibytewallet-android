//
//  asset_hold_kat_main.c
//
//  Host KAT for BRAssetHold.h: which of the wallet's outputs are held out of every plain DGB spend
//  because they may carry DigiAsset units (BRAssetHoldCombine, BRAssetHoldOutputs, BRAssetHoldBounds and
//  BRWalletHoldAssetOutputs in BRWallet.c), moved from Android's Kotlin (core/.../asset/AssetManager.kt:
//  holdAssetOutputsBeforeSpendImpl, the TargetingWalk, resolveInputAssetUnits as those call it,
//  replayAssetOutpointExclusions, resolveRowTargetsImpl, replayAnswerForAbsentTransaction,
//  provenInputUnits). Every expectation is fixed OUTSIDE the header, three ways:
//
//    1. BY HAND. The Kotlin tests' assertions on the pass, value for value: AssetPreSpendPassTest
//       (prespend_*), the hold assertions of AssetHoldCoversCreditedOutputsTest asked of the pass
//       (covers_*: each `held(h, vout, inputUnits)` is a transaction carrying that payload whose inputs
//       carry those units), and asset_implicit_change_kat's wallet checks run through
//       BRWalletHoldAssetOutputs instead of a hand registration (implicit_change_*). The other cases
//       are this suite's own (the walk, the replay, the union, refusals, capacity, edges), their
//       expectations read off the Kotlin source.
//       Not carried, because they have no form in a pass over one snapshot of BRTransaction structs:
//       AssetPreSpendPassTest's script that is not hex, a set that does not settle, a registration or a
//       row lookup that throws, the two cancellations, the second pass waiting for the first (the
//       wallet pass holds the wallet's lock), what a later pass reads (its held answer is carried),
//       a transaction arriving while the pass runs, and the wiring (SpendPreflight, the asset send).
//       AssetHoldCoversCreditedOutputsTest's allocations and payload re-reads are asset_allocate_kat's
//       and asset_decode_kat's.
//    2. THE UNION. BRAssetHoldCombine over every pair of verdicts, values outside the enum included;
//       and the pass with the classifier half given as abstract verdicts (BRAssetHoldClassifier), so
//       that each place the pass asks both readers is seen without a script on which they disagree.
//       On the ordinary transactions every other case uses, the two readers agree.
//    3. BY A MODEL. `m...` below is the Kotlin written out line for line in C: the pre-spend pass with
//       its plain-transaction set and its per-pass record, the TargetingWalk with its maps, its Step
//       stack and its restsOn lists, resolveInputAssetUnits, assetCarrier, assetTxShape,
//       hasAssetPayload, the replay. The Kotlin's decoder is BRAssetDecode.h and its targetsOutput is
//       BRAssetTargetsOutput (asset_decode_kat and asset_quantity_targets_kat hold those to the
//       Kotlin). sweep_decoder holds the decoder's reading alone (_BRAssetHoldOutputsReading), with a
//       classifier that says NO everywhere, to the model over fixed pseudo-random wallets with any
//       scripts at all (exact: that reading is the Kotlin), and the pass to at least the model;
//       sweep_agree holds the pass, with the core's classifier, to the model over wallets whose scripts
//       the two readers read alike; sweep_union holds the pass to "at least the model and at least
//       what the classifier holds" over any scripts.
//
//  One case per process: `asset_hold_kat <case>` prints PASS:/FAIL: lines and `RESULT <case> pass|fail`;
//  `list` prints the case names. run.sh builds one binary per ASSET_HOLD_UNFIXED arm and holds each
//  comparison arm to failing exactly its cases. This file is built with ASSET_HOLD_TEST_SEAMS=1, which
//  honours the view's classifier and a single reading; run.sh's third unit is built without it, as what
//  ships, and seams_off holds it to refusing both.
//

#include "BRAssetHold.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRWallet.h"
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_hold_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice
// run.sh's third translation unit, built WITHOUT ASSET_HOLD_TEST_SEAMS, as what ships: `v` as given, then
// naming `c`, then each reading alone, then both, into out[0..4]. Returns its ASSET_HOLD_TEST_SEAMS.
int asset_hold_kat_seams_off(const BRAssetHoldView *v, const BRAssetHoldClassifier *c, BRAssetHoldWork *work, size_t workCap,
                             BRUTXO *held, size_t heldCap, BRAssetHoldResult out[5]);

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

// ---- hex ------------------------------------------------------------------------------------

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
    int hi = -1, bad = 0;
    const char *p;

    for (p = hex; *p; p++) {
        int d = hexDigit(*p);

        if (*p == ' ') continue;
        if (d < 0) { bad = 1; break; }
        if (hi < 0) hi = d;
        else {
            if (n >= cap) { bad = 1; break; }
            out[n++] = (uint8_t)(hi * 16 + d);
            hi = -1;
        }
    }

    if (bad || hi >= 0) { ck(0, "the vector \"%s\" is not whole bytes of hex that fit the buffer", hex); return 0; }
    return n;
}

// ---- a fixed pseudo-random stream -----------------------------------------------------------

static uint64_t RNG = 0x9e3779b97f4a7c15ULL;

static uint64_t rnd(void)   // xorshift64
{
    RNG ^= RNG << 13;
    RNG ^= RNG >> 7;
    RNG ^= RNG << 17;
    return RNG;
}

static uint64_t below(uint64_t n) { return n ? rnd() % n : 0; }

// ---- transactions built by hand -------------------------------------------------------------
//
// A transaction here is a BRTransaction with its outputs, inputs and scripts in this file's pools,
// and a flag per output saying whether it is the wallet's own. The txid is a tag: tid(7) is 07 a5 a5 ...

#define TMAX 2100        // transactions in one wallet (walk_long_run uses 2001)
#define OMAX 34          // outputs of one transaction (covers_output_31 uses 33)
#define IMAX 4           // inputs of one transaction
#define ARENA (1 << 20)  // script bytes
#define RMAX 4200        // rows
#define PMAX 64          // proofs
#define HMAX 70000       // held outpoints

typedef struct {
    BRTransaction tx;    // first: a BRTransaction pointer from this pool is an Fx pointer
    BRTxOutput outs[OMAX];
    BRTxInput ins[IMAX];
    int owned[OMAX];
} Fx;

static Fx POOL[TMAX];
static size_t POOLN = 0;
static uint8_t BYTES[ARENA];
static size_t BYTESN = 0;
static int BROKEN = 0;   // a fixture this file's pools cannot hold: a broken case, never an answer

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

static void resetPool(void)
{
    POOLN = 0;
    BYTESN = 0;
}

static Fx *fx(uint32_t tag)
{
    Fx *f;

    if (POOLN >= TMAX) { BROKEN = 1; POOLN = 0; }
    f = &POOL[POOLN++];
    memset(f, 0, sizeof(*f));
    f->tx.txHash = tid(tag);
    f->tx.outputs = f->outs;
    f->tx.inputs = f->ins;
    f->tx.version = 1;
    return f;
}

static uint8_t *arena(size_t n)
{
    uint8_t *p;

    if (BYTESN + n > ARENA) { BROKEN = 1; BYTESN = 0; }
    p = &BYTES[BYTESN];
    BYTESN += n;
    return p;
}

static void outBytes(Fx *f, const uint8_t *script, size_t len, int owned)
{
    BRTxOutput *o;

    if (f->tx.outCount >= OMAX) { BROKEN = 1; return; }
    o = &f->outs[f->tx.outCount];
    o->script = arena(len ? len : 1);
    if (len) memcpy(o->script, script, len);
    o->scriptLen = len;
    o->amount = 1000;
    f->owned[f->tx.outCount] = owned;
    f->tx.outCount++;
}

static void out(Fx *f, const char *hex, int owned)
{
    uint8_t b[300];
    size_t n = hx(hex, b, sizeof(b));

    outBytes(f, b, n, owned);
}

#define OURS   "0014111111111111111111111111111111111111111111"
#define THEIRS "0014222222222222222222222222222222222222222222"

static void ours(Fx *f) { out(f, OURS, 1); }
static void theirs(Fx *f) { out(f, THEIRS, 0); }

static void in(Fx *f, UInt256 funding, uint32_t index)
{
    if (f->tx.inCount >= IMAX) { BROKEN = 1; return; }
    f->ins[f->tx.inCount].txHash = funding;
    f->ins[f->tx.inCount].index = index;
    f->ins[f->tx.inCount].sequence = 0xffffffff;
    f->tx.inCount++;
}

// ---- a wallet, as the pass reads it ----------------------------------------------------------

typedef struct {
    const BRTransaction *txs[TMAX];
    size_t n;
    BRAssetHoldRow rows[RMAX];
    size_t rowN;
    UInt256 proven[PMAX];
    size_t provenN;
    int everyOutputOwned;                    // owned == NULL (Kotlin: an empty owned set)
    const BRAssetHoldClassifier *classifier; // NULL: the core's
} W;

static W WAL;
static BRAssetHoldWork WORK[RMAX];   // the most of transactions, rows and proofs any case builds
static BRUTXO HELD[HMAX];

static void wreset(W *w)
{
    memset(w, 0, sizeof(*w));
}

static void wadd(W *w, Fx *f)
{
    if (w->n >= TMAX) { BROKEN = 1; return; }
    w->txs[w->n++] = &f->tx;
}

static void wremove(W *w, const Fx *f)
{
    size_t i, j = 0;

    for (i = 0; i < w->n; i++) if (w->txs[i] != &f->tx) w->txs[j++] = w->txs[i];
    w->n = j;
}

// A row for an outpoint a transaction of the wallet spends is spent (1): the replay passes over it, and
// the pass still reads its quantity for the spender's inputs.
static void row(W *w, UInt256 txHash, uint32_t n, int64_t quantity, int spent)
{
    if (w->rowN >= RMAX) { BROKEN = 1; return; }
    w->rows[w->rowN].txHash = txHash;
    w->rows[w->rowN].n = n;
    w->rows[w->rowN].quantity = quantity;
    w->rows[w->rowN].spent = spent;
    w->rowN++;
}

static void prove(W *w, UInt256 txHash)
{
    if (w->provenN >= PMAX) { BROKEN = 1; return; }
    w->proven[w->provenN++] = txHash;
}

// pass() hands the header copies of the wallet's transactions whose outputs, inputs and scripts are
// heap blocks of exactly their size, so that AddressSanitizer sees a read one past any of them. A copy
// points back at its Fx for the owned flags.
typedef struct {
    BRTransaction tx;    // first
    const Fx *of;
} Copy;

static int copyOwned(void *info, const BRTransaction *tx, size_t vout)
{
    (void)info;
    return ((const Copy *)(const void *)tx)->of->owned[vout];
}

static int fxOwned(void *info, const BRTransaction *tx, size_t vout)
{
    (void)info;
    return ((const Fx *)(const void *)tx)->owned[vout];
}

static BRAssetHoldView view(const W *w)
{
    BRAssetHoldView v;

    memset(&v, 0, sizeof(v));
    v.txs = w->txs;
    v.txCount = w->n;
    v.owned = w->everyOutputOwned ? NULL : fxOwned;
    v.rows = w->rows;
    v.rowCount = w->rowN;
    v.proven = w->proven;
    v.provenCount = w->provenN;
    v.classifier = w->classifier;
    return v;
}

static Copy *COPIES[TMAX];
static const BRTransaction *COPY_TXS[TMAX];

static Copy *copyOf(const BRTransaction *tx)
{
    Copy *c = calloc(1, sizeof(Copy));
    size_t j;

    if (! c) { BROKEN = 1; return NULL; }
    c->tx = *tx;
    c->of = (const Fx *)(const void *)tx;
    if (tx->outputs && tx->outCount > 0 && tx->outCount <= OMAX) {
        c->tx.outputs = malloc(tx->outCount * sizeof(BRTxOutput));
        for (j = 0; c->tx.outputs && j < tx->outCount; j++) {
            c->tx.outputs[j] = tx->outputs[j];
            if (tx->outputs[j].script && tx->outputs[j].scriptLen > 0) {
                c->tx.outputs[j].script = malloc(tx->outputs[j].scriptLen);
                if (c->tx.outputs[j].script) memcpy(c->tx.outputs[j].script, tx->outputs[j].script, tx->outputs[j].scriptLen);
            }
        }
    }
    if (tx->inputs && tx->inCount > 0 && tx->inCount <= IMAX) {
        c->tx.inputs = malloc(tx->inCount * sizeof(BRTxInput));
        if (c->tx.inputs) memcpy(c->tx.inputs, tx->inputs, tx->inCount * sizeof(BRTxInput));
    }
    return c;
}

static void freeCopy(Copy *c)
{
    size_t j;

    if (! c) return;
    if (c->tx.outputs != c->of->tx.outputs) {
        for (j = 0; j < c->tx.outCount; j++) if (c->tx.outputs[j].script != c->of->tx.outputs[j].script) free(c->tx.outputs[j].script);
        free(c->tx.outputs);
    }
    if (c->tx.inputs != c->of->tx.inputs) free(c->tx.inputs);
    free(c);
}

// `readings` 0: BRAssetHoldOutputs; 1 or 2: the header's _BRAssetHoldOutputsReading, the decoder's reading
// or the other one alone. The rows, the proofs and the work buffer are heap blocks of exactly their size
// too (the work buffer: what BRAssetHoldBounds asks for), so a read one past any of them is seen.
// `heldCap` 0: all of HELD.
static BRAssetHoldResult passReadingIn(const W *w, int readings, size_t heldCap)
{
    BRAssetHoldView v = view(w);
    BRAssetHoldResult r;
    BRAssetHoldRow *rows = malloc((w->rowN ? w->rowN : 1) * sizeof(BRAssetHoldRow));
    UInt256 *proven = malloc((w->provenN ? w->provenN : 1) * sizeof(UInt256));
    BRAssetHoldWork *work;
    size_t i, workNeeded = 0;

    if (! rows || ! proven) { BROKEN = 1; memset(&r, 0, sizeof(r)); free(rows); free(proven); return r; }
    for (i = 0; i < w->n; i++) {
        COPIES[i] = w->txs[i] ? copyOf(w->txs[i]) : NULL;
        COPY_TXS[i] = COPIES[i] ? &COPIES[i]->tx : NULL;
    }
    v.txs = COPY_TXS;
    if (v.owned) v.owned = copyOwned;
    if (w->rowN) memcpy(rows, w->rows, w->rowN * sizeof(BRAssetHoldRow));
    if (w->provenN) memcpy(proven, w->proven, w->provenN * sizeof(UInt256));
    v.rows = rows;
    v.proven = proven;
    if (! BRAssetHoldBounds(&v, &workNeeded, NULL)) workNeeded = N(WORK);
    work = malloc((workNeeded ? workNeeded : 1) * sizeof(BRAssetHoldWork));
    if (! work) BROKEN = 1;
    if (heldCap == 0 || heldCap > N(HELD)) heldCap = N(HELD);
    r = readings ? _BRAssetHoldOutputsReading(&v, work, workNeeded, HELD, heldCap, readings)
                 : BRAssetHoldOutputs(&v, work, workNeeded, HELD, heldCap);
    for (i = 0; i < w->n; i++) freeCopy(COPIES[i]);
    free(work);
    free(rows);
    free(proven);
    return r;
}

static BRAssetHoldResult passReading(const W *w, int readings) { return passReadingIn(w, readings, 0); }

static BRAssetHoldResult pass(const W *w) { return passReading(w, 0); }

static int heldIn(BRAssetHoldResult r, UInt256 txHash, uint32_t n)
{
    size_t i;

    for (i = 0; i < r.heldCount; i++) if (HELD[i].n == n && UInt256Eq(HELD[i].hash, txHash)) return 1;
    return 0;
}

static size_t heldOf(BRAssetHoldResult r, UInt256 txHash)   // how many outputs of txHash are held
{
    size_t i, k = 0;

    for (i = 0; i < r.heldCount; i++) if (UInt256Eq(HELD[i].hash, txHash)) k++;
    return k;
}

static const char *STATUS[] = { "REFUSED", "COMPLETE", "ARGUMENTS", "UNREADABLE", "CAPACITY", "ROWS" };

static const char *st(BRAssetHoldStatus s) { return ((unsigned)s < N(STATUS)) ? STATUS[s] : "?"; }

static void complete(const char *what, BRAssetHoldResult r)
{
    ck(r.status == BR_ASSET_HOLD_COMPLETE, "%s: the pass completes (%s)", what, st(r.status));
}

static void heldExactly(const char *what, BRAssetHoldResult r, const UInt256 *h, const uint32_t *n, size_t k)
{
    size_t i;
    int all = 1;

    for (i = 0; i < k; i++) if (! heldIn(r, h[i], n[i])) all = 0;
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == k && all, "%s: held is exactly the %zu outpoint(s) named (%s, %zu held)",
       what, k, st(r.status), r.heldCount);
}

static void refused(const char *what, BRAssetHoldResult r, BRAssetHoldStatus want)
{
    ck(r.status == want && r.heldCount == 0, "%s: refused %s with nothing held (%s, %zu)", what, st(want), st(r.status), r.heldCount);
}

// ---- abstract classifier verdicts -------------------------------------------------------------
//
// The classifier half as a list of verdicts: the outputs it says may carry units, and the
// transactions it says carry a carrier. Nothing else.

typedef struct { UInt256 h; uint32_t n; } Op;

static struct {
    Op yes[16]; size_t yesN;
    UInt256 contains[16]; size_t containsN;
} ABS;

static int absOutput(void *info, const BRTransaction *tx, size_t vout)
{
    size_t i;

    (void)info;
    for (i = 0; i < ABS.yesN; i++) if (ABS.yes[i].n == vout && UInt256Eq(ABS.yes[i].h, tx->txHash)) return 1;
    return 0;
}

static int absContains(void *info, const BRTransaction *tx)
{
    size_t i;

    (void)info;
    for (i = 0; i < ABS.containsN; i++) if (UInt256Eq(ABS.contains[i], tx->txHash)) return 1;
    return 0;
}

static const BRAssetHoldClassifier ABSTRACT = { absOutput, absContains, NULL };

static int neverOutput(void *info, const BRTransaction *tx, size_t vout) { (void)info; (void)tx; (void)vout; return 0; }
static int neverContains(void *info, const BRTransaction *tx) { (void)info; (void)tx; return 0; }

static const BRAssetHoldClassifier NEVER = { neverOutput, neverContains, NULL };   // the decoder alone

static void absReset(void) { memset(&ABS, 0, sizeof(ABS)); }
static void absYes(UInt256 h, uint32_t n) { ABS.yes[ABS.yesN].h = h; ABS.yes[ABS.yesN].n = n; ABS.yesN++; }
static void absContainsTx(UInt256 h) { ABS.contains[ABS.containsN++] = h; }

// ================================================================================================
// THE MODEL: the Kotlin, line for line
// ================================================================================================

typedef struct { int has; int64_t v; } OL;   // Long?

static OL some(int64_t v) { OL o; o.has = 1; o.v = v; return o; }
static OL none(void) { OL o; o.has = 0; o.v = 0; return o; }

typedef struct {   // DecodedAssetHeader and what targetsOutput reads beside it
    BRAssetHeader h;
    BRAssetTransferInstruction insts[BR_ASSET_MAX_INSTRUCTIONS];
} MHeader;

typedef struct {   // AssetTxShape
    MHeader header;
    int hasFirst; int32_t first;
    int32_t outputCount;
    int inputsReadable;   // inputs(): null when a line cannot be read
} MShape;

typedef struct { UInt256 h; uint32_t v; } MInput;   // Pair<String, Int>

// The model's bridge: outputsOf / inputsOf answer for the wallet's own transactions only.
static const W *MW_WALLET;

static const BRTransaction *mOutputsOf(UInt256 h)
{
    size_t i;

    for (i = 0; i < MW_WALLET->n; i++) if (UInt256Eq(MW_WALLET->txs[i]->txHash, h)) return MW_WALLET->txs[i];
    return NULL;
}

static int mIsOpReturn(const BRTxOutput *o) { return o->scriptLen > 0 && o->script[0] == 0x6a; }

// decoder.decode(script): null is NOT_ASSET
static BRAssetScriptKind mDecode(const BRTxOutput *o, MHeader *out)
{
    return BRAssetDecodeInstructions(o->script, o->scriptLen, &out->h, out->insts, BR_ASSET_MAX_INSTRUCTIONS);
}

// assetCarrier(scripts): the first UNCLASSIFIABLE, else the last decoded
static int mAssetCarrier(const BRTransaction *tx, MHeader *out)
{
    size_t j;
    long last = -1;
    MHeader tmp;

    for (j = 0; j < tx->outCount; j++) {
        BRAssetScriptKind k;

        if (! mIsOpReturn(&tx->outputs[j])) continue;
        k = mDecode(&tx->outputs[j], &tmp);
        if (k == BR_ASSET_SCRIPT_NOT_ASSET) continue;
        if (k == BR_ASSET_SCRIPT_UNCLASSIFIABLE) { *out = tmp; return 1; }
        last = (long)j;
    }

    if (last < 0) return 0;
    mDecode(&tx->outputs[last], out);
    return 1;
}

static int mFirstNonOpReturn(const BRTransaction *tx, int32_t *first)
{
    size_t j;

    for (j = 0; j < tx->outCount; j++) if (! mIsOpReturn(&tx->outputs[j])) { *first = (int32_t)j; return 1; }
    *first = 0;
    return 0;
}

// assetTxShape(outputsOf(funding)) { inputsOf(funding) }
static int mShape(UInt256 h, MShape *s)
{
    const BRTransaction *tx = mOutputsOf(h);
    long payload = -1;
    size_t j, k;

    if (! tx || tx->outCount == 0) return 0;
    for (j = 0; j < tx->outCount; j++) if (mIsOpReturn(&tx->outputs[j]) && payload < 0) payload = (long)j;
    s->hasFirst = mFirstNonOpReturn(tx, &s->first);
    if (payload < 0) return 0;
    if (mDecode(&tx->outputs[payload], &s->header) == BR_ASSET_SCRIPT_NOT_ASSET) return 0;
    s->outputCount = (int32_t)tx->outCount;
    s->inputsReadable = 1;
    for (k = 0; k < tx->inCount; k++) if (tx->inputs[k].index > (uint32_t)INT32_MAX) s->inputsReadable = 0;
    return 1;
}

// hasAssetPayload(outputLines): null when there is nothing to judge from
static int mHasAssetPayload(const BRTransaction *tx)   // -1 null, 0, 1
{
    size_t j;

    if (! tx || tx->outCount == 0) return -1;
    for (j = 0; j < tx->outCount; j++) {
        MHeader tmp;

        if (! mIsOpReturn(&tx->outputs[j])) continue;
        return mDecode(&tx->outputs[j], &tmp) != BR_ASSET_SCRIPT_NOT_ASSET;
    }
    return 0;
}

static int mTargets(const MHeader *h, int32_t vout, int hasFirst, int32_t first, OL units, int32_t outputCount)
{
    return BRAssetTargetsOutput(h->h.operation, h->h.version, BRAssetHeaderHasRules(&h->h), h->h.hasTotalQuantity,
                                h->h.totalQuantity, vout, hasFirst, first, units.has, units.v, outputCount, h->insts,
                                h->h.instructionCount);
}

// utxoDao.getAssetUtxoAt(txid, vout)?.assetQuantity
static OL mRow(UInt256 h, uint32_t v)
{
    size_t i;

    for (i = 0; i < MW_WALLET->rowN; i++)
        if (MW_WALLET->rows[i].n == v && UInt256Eq(MW_WALLET->rows[i].txHash, h)) return some(MW_WALLET->rows[i].quantity);
    return none();
}

// provenInputUnits(txid, header)
static OL mProven(UInt256 h, const MHeader *header)
{
    size_t i;
    int64_t a;

    for (i = 0; i < MW_WALLET->provenN; i++) {
        if (UInt256Eq(MW_WALLET->proven[i], h)) {
            return BRAssetAssignedUnits(header->h.operation, header->insts, header->h.instructionCount, &a) ? some(a) : none();
        }
    }
    return none();
}

// A map String -> Long? (and a set, with `v` unused)
typedef struct { UInt256 h; OL v; } MEnt;
typedef struct { MEnt e[TMAX]; size_t n; } MMap;

static long mapAt(const MMap *m, UInt256 h)
{
    size_t i;

    for (i = 0; i < m->n; i++) if (UInt256Eq(m->e[i].h, h)) return (long)i;
    return -1;
}

static int mapHas(const MMap *m, UInt256 h) { return mapAt(m, h) >= 0; }

static OL mapGet(const MMap *m, UInt256 h)
{
    long i = mapAt(m, h);

    return i >= 0 ? m->e[i].v : none();
}

static void mapPut(MMap *m, UInt256 h, OL v)
{
    long i = mapAt(m, h);

    if (i >= 0) { m->e[i].v = v; return; }
    if (m->n >= TMAX) { BROKEN = 1; return; }
    m->e[m->n].h = h;
    m->e[m->n].v = v;
    m->n++;
}

static void mapRemove(MMap *m, UInt256 h)
{
    long i = mapAt(m, h);

    if (i >= 0) m->e[i] = m->e[--m->n];
}

// The lambdas resolveInputAssetUnits is given.
typedef int (*MIsAssetTx)(UInt256 h);                 // Boolean?: -1 null
typedef int (*MRowIsTargeted)(UInt256 h, uint32_t v); // Boolean?: -1 null

// resolveInputAssetUnits(inputs, rowQuantity, isAssetTx, rowIsTargeted, unresolved)
static OL mResolveInputAssetUnits(const MInput *inputs, size_t n, MIsAssetTx isAssetTx, MRowIsTargeted rowIsTargeted,
                                  int unresolvedGiven)
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
        if (isAssetTx(inputs[i].h) != 0) return none();
    }

    return zeroRows ? none() : some(total);
}

// ---- TargetingWalk ----

typedef struct { UInt256 txid; UInt256 below[IMAX]; size_t n, next; } MStep;

static struct {
    MMap *settled;
    MIsAssetTx isAssetTx;
    MMap totals;
    MMap onTheWay;
    MStep stack[TMAX];
    size_t depth;
    int every;
} MWALK;

static int mFromPayload(const MShape *tx, int32_t vout)   // -1 null
{
    if (! mTargets(&tx->header, vout, tx->hasFirst, tx->first, none(), tx->outputCount)) return 0;
    if (mTargets(&tx->header, vout, tx->hasFirst, tx->first, some(0), tx->outputCount)) return 1;
    return -1;
}

static int mTotalResolved(UInt256 h) { return mapHas(&MWALK.totals, h) || (MWALK.settled && mapHas(MWALK.settled, h)); }

static OL mTotalOf(UInt256 h) { return mapHas(&MWALK.totals, h) ? mapGet(&MWALK.totals, h) : (MWALK.settled ? mapGet(MWALK.settled, h) : none()); }

static int mInputsOf(UInt256 h, MInput *list, size_t *n)   // inputsOf(txid, tx): 0 = null
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

        if (! stored.has) continue;
        if (stored.v > 0) continue;
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
    if (! mTotalResolved(h)) {
        MWALK.every = 0;
        return -1;
    }
    return mTargets(&tx.header, (int32_t)vout, tx.hasFirst, tx.first, mTotalOf(h), tx.outputCount);
}

static OL mInputTotalOf(UInt256 h)
{
    MShape tx;
    MInput inputs[IMAX];
    size_t n;
    OL r;

    if (! mShape(h, &tx)) return none();
    if (! mInputsOf(h, inputs, &n)) return none();
    r = mResolveInputAssetUnits(inputs, n, MWALK.isAssetTx, mAnswerFromTotals, 0);
    return r.has ? r : mProven(h, &tx.header);
}

static void mResolveTotals(UInt256 root)
{
    if (mTotalResolved(root)) return;
    MWALK.depth = 0;
    MWALK.stack[0].txid = root;
    mRestsOn(root, &MWALK.stack[0]);
    MWALK.stack[0].next = 0;
    MWALK.depth = 1;
    mapPut(&MWALK.onTheWay, root, none());

    while (MWALK.depth > 0) {
        MStep *step = &MWALK.stack[MWALK.depth - 1];

        if (step->next < step->n) {
            UInt256 b = step->below[step->next++];

            if (mTotalResolved(b)) continue;
            if (mapHas(&MWALK.onTheWay, b)) {
                MWALK.every = 0;
                continue;
            }
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

// outputIsTargeted(txid, vout, shapeOf, rowQuantity, isAssetTx, settled, provenInputUnits)
static int mOutputIsTargeted(UInt256 h, uint32_t vout, MMap *settled, MIsAssetTx isAssetTx)
{
    MShape tx;
    int p, answer;
    size_t i;

    MWALK.settled = settled;
    MWALK.isAssetTx = isAssetTx;
    MWALK.totals.n = 0;
    MWALK.onTheWay.n = 0;
    MWALK.every = 1;

    if (! mShape(h, &tx)) return -1;
    p = mFromPayload(&tx, (int32_t)vout);
    if (p != -1) return p;
    mResolveTotals(h);
    answer = mAnswerFromTotals(h, vout);
    if (MWALK.every && settled) for (i = 0; i < MWALK.totals.n; i++) mapPut(settled, MWALK.totals.e[i].h, MWALK.totals.e[i].v);
    return answer;
}

// ---- holdAssetOutputsBeforeSpendImpl, one pass ----

static MMap M_PLAIN;          // plainTxs
static MMap M_THIS_PASS;      // inputUnitsThisPass
static MMap M_REPLAY;         // the replay's settledInputUnits
static Op M_HELD[HMAX];
static size_t M_HELDN;

static void mRegister(UInt256 h, uint32_t n)
{
    size_t i;

    for (i = 0; i < M_HELDN; i++) if (M_HELD[i].n == n && UInt256Eq(M_HELD[i].h, h)) return;
    if (M_HELDN >= HMAX) { BROKEN = 1; return; }
    M_HELD[M_HELDN].h = h;
    M_HELD[M_HELDN].n = n;
    M_HELDN++;
}

static int mPassIsAssetTx(UInt256 h) { return mapHas(&M_PLAIN, h) ? 0 : mHasAssetPayload(mOutputsOf(h)); }

static int mPassRowIsTargeted(UInt256 h, uint32_t v) { return mOutputIsTargeted(h, v, &M_THIS_PASS, mPassIsAssetTx); }

static void mPreSpendPass(const W *w)
{
    size_t i, j, k;

    M_PLAIN.n = 0;
    M_THIS_PASS.n = 0;

    for (i = 0; i < w->n; i++) {
        const BRTransaction *tx = w->txs[i];
        MHeader header;
        MInput inputs[IMAX];
        int anyNull = 0, hasFirst;
        int32_t first;
        OL inputUnits;

        if (! mAssetCarrier(tx, &header)) {
            mapPut(&M_PLAIN, tx->txHash, none());
            continue;
        }

        for (k = 0; k < tx->inCount; k++) {
            if (tx->inputs[k].index > (uint32_t)INT32_MAX) anyNull = 1;
            inputs[k].h = tx->inputs[k].txHash;
            inputs[k].v = tx->inputs[k].index;
        }
        inputUnits = anyNull ? none() : mResolveInputAssetUnits(inputs, tx->inCount, mPassIsAssetTx, mPassRowIsTargeted, 1);
        if (! inputUnits.has) inputUnits = mProven(tx->txHash, &header);
        mapPut(&M_THIS_PASS, tx->txHash, inputUnits);

        hasFirst = mFirstNonOpReturn(tx, &first);
        for (j = 0; j < tx->outCount; j++) {
            if (mIsOpReturn(&tx->outputs[j])) continue;
            if (! w->everyOutputOwned && ! ((const Fx *)(const void *)tx)->owned[j]) continue;
            if (! mTargets(&header, (int32_t)j, hasFirst, first, inputUnits, (int32_t)tx->outCount)) continue;
            mRegister(tx->txHash, (uint32_t)j);
        }
    }
}

// ---- replayAssetOutpointExclusions ----

static int mReplayIsAssetTx(UInt256 h) { return mHasAssetPayload(mOutputsOf(h)); }

static int mReplayRowIsTargeted(UInt256 h, uint32_t v) { return mOutputIsTargeted(h, v, &M_REPLAY, mReplayIsAssetTx); }

static int mResolveRowTargets(UInt256 h, uint32_t vout)   // -1 null
{
    const BRTransaction *tx = mOutputsOf(h);
    MHeader header;
    MInput inputs[IMAX];
    size_t k, n = 0;
    int hasFirst;
    int32_t first;
    OL inputUnits;

    if (! tx) return 0;   // replayAnswerForAbsentTransaction(walletLoaded, txidWellFormed, bridgeAnsweredCleanly)
    if (tx->outCount == 0) return -1;
    if (! mAssetCarrier(tx, &header)) return -1;
    hasFirst = mFirstNonOpReturn(tx, &first);

    for (k = 0; k < tx->inCount; k++) {
        if (tx->inputs[k].index > (uint32_t)INT32_MAX) continue;   // mapNotNull
        inputs[n].h = tx->inputs[k].txHash;
        inputs[n].v = tx->inputs[k].index;
        n++;
    }
    inputUnits = mResolveInputAssetUnits(inputs, n, mReplayIsAssetTx, mReplayRowIsTargeted, 1);
    if (! inputUnits.has) inputUnits = mProven(h, &header);
    return mTargets(&header, (int32_t)vout, hasFirst, first, inputUnits, (int32_t)tx->outCount);
}

static void mReplay(const W *w)
{
    size_t i;

    M_REPLAY.n = 0;
    for (i = 0; i < w->rowN; i++) {
        const BRAssetHoldRow *r = &w->rows[i];
        int protect;

        if (r->spent) continue;
        if (r->quantity > 0) protect = 1;
        else {
            int targeted = mResolveRowTargets(r->txHash, r->n);

            protect = (targeted == -1) ? 1 : targeted;
        }
        if (protect) mRegister(r->txHash, r->n);
    }
}

// What the Kotlin registers: the replay at start, then a pre-spend pass.
static void model(const W *w)
{
    MW_WALLET = w;
    M_HELDN = 0;
    mReplay(w);
    mPreSpendPass(w);
}

// Does the header's answer equal the model's, as sets?
static int sameAsModel(BRAssetHoldResult r)
{
    size_t i;

    if (r.status != BR_ASSET_HOLD_COMPLETE || r.heldCount != M_HELDN) return 0;
    for (i = 0; i < M_HELDN; i++) if (! heldIn(r, M_HELD[i].h, M_HELD[i].n)) return 0;
    return 1;
}

// Does the answer hold at least the model's?
static int coversModel(BRAssetHoldResult r)
{
    size_t i;

    if (r.status != BR_ASSET_HOLD_COMPLETE) return 0;
    for (i = 0; i < M_HELDN; i++) if (! heldIn(r, M_HELD[i].h, M_HELD[i].n)) return 0;
    return 1;
}

// ================================================================================================
// scripts
// ================================================================================================

typedef struct { uint8_t b[96]; size_t bits; } Bits;

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
    else { put(w, 1, 3); put(w, a, 9); put(w, 0, 4); }
}

typedef struct { int skip, range, percent; uint32_t out; uint64_t amount; } GInst;

static void instruction(Bits *w, const GInst *g)
{
    put(w, (uint64_t)g->skip, 1);
    put(w, (uint64_t)g->range, 1);
    put(w, (uint64_t)g->percent, 1);
    put(w, g->out, g->range ? 13 : 5);
    if (g->percent) put(w, g->amount, 8);
    else fixedAmount(w, g->amount);
}

// OP_RETURN, a direct push of the payload in `w` (or OP_PUSHDATA2 when pd2), into script[]; its length
static size_t frame(const Bits *w, int pd2, uint8_t *script)
{
    size_t len = w->bits / 8, n = 0;

    script[n++] = 0x6a;
    if (pd2) { script[n++] = 0x4d; script[n++] = (uint8_t)len; script[n++] = 0; }
    else script[n++] = (uint8_t)len;
    memcpy(script + n, w->b, len);
    return n + len;
}

// A well-formed carrier: opcode 0x05 (issuance), 0x15 (transfer) or 0x25 (burn), version 3.
static size_t carrier(int opcode, uint64_t total, const GInst *insts, size_t n, int pd2, uint8_t *script)
{
    Bits w;
    size_t i;

    memset(&w, 0, sizeof(w));
    put(&w, 0x4441, 16);
    put(&w, 3, 8);
    put(&w, (uint64_t)opcode, 8);
    if (opcode == 0x05) fixedAmount(&w, total);
    for (i = 0; i < n; i++) instruction(&w, &insts[i]);
    if (opcode == 0x05) put(&w, 0x10, 8);   // the flags: locked, aggregatable
    return frame(&w, pd2, script);
}

static void outCarrier(Fx *f, int opcode, uint64_t total, const GInst *insts, size_t n, int pd2)
{
    uint8_t script[120];
    size_t len = carrier(opcode, total, insts, n, pd2, script);

    outBytes(f, script, len, 0);
}

// ================================================================================================
// the cases
// ================================================================================================

// ---- the union -----------------------------------------------------------------------------------

static void combine_exhaustive(void)
{
    // decoder x classifier -> the union; 0 UNKNOWN, 1 NO, 2 YES; 3 and -1 are values the enum does not define
    static const int table[5][5] = {
        //            c=UNKNOWN NO YES  3  -1
        /* UNKNOWN */ { 0,      0, 2,  0,  0 },
        /* NO      */ { 0,      1, 2,  0,  0 },
        /* YES     */ { 2,      2, 2,  2,  2 },
        /* 3       */ { 0,      0, 2,  0,  0 },
        /* -1      */ { 0,      0, 2,  0,  0 },
    };
    static const int value[5] = { 0, 1, 2, 3, -1 };
    static const char *name[5] = { "UNKNOWN", "NO", "YES", "3", "-1" };
    int d, c;

    for (d = 0; d < 5; d++) {
        for (c = 0; c < 5; c++) {
            BRAssetFact got = BRAssetHoldCombine((BRAssetFact)value[d], (BRAssetFact)value[c]);

            ck((int)got == table[d][c], "decoder %s, classifier %s -> %d (want %d)", name[d], name[c], (int)got, table[d][c]);
        }
    }
    ck(BRAssetHoldCombine(BR_ASSET_FACT_YES, BR_ASSET_FACT_NO) == BR_ASSET_FACT_YES &&
       BRAssetHoldCombine(BR_ASSET_FACT_NO, BR_ASSET_FACT_YES) == BR_ASSET_FACT_YES,
       "either reader alone holds an output: never weaker than either");
}

// ---- AssetPreSpendPassTest -------------------------------------------------------------------------
//
// Its wallet: plain1, plain2 ([0 ours, 1 theirs]) and transfer ([0 theirs, 1 the payload, 2 theirs,
// 3 ours]) spending funding:1, which the wallet does not hold. No rows unless a test adds one.

#define TRANSFER_TEN_TO_OUTPUT_ZERO "6a0644410115000a"
#define TRANSFER_PUSHDATA2 "6a4d060044410215000a"
#define NOTE_PUSHDATA2 "6a4d060068656c6c6f21"

enum { T_PLAIN1 = 0xa1, T_PLAIN2 = 0xa2, T_PLAIN3 = 0xa3, T_TRANSFER = 0xb1, T_FUNDING = 0xf1, T_ARRIVED = 0xb2,
       T_SHADOWED = 0xc1, T_MIXED = 0xc2, T_CARRIER = 0xc3, T_NOTE = 0xc4 };

static Fx *P1, *P2, *XFER;

static Fx *plainTx(uint32_t tag)
{
    Fx *f = fx(tag);

    ours(f);
    theirs(f);
    in(f, tid(tag + 0x1000), 0);
    return f;
}

static Fx *transferTx(uint32_t tag, UInt256 funding, uint32_t index)
{
    Fx *f = fx(tag);

    theirs(f);
    out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0);
    theirs(f);
    ours(f);
    in(f, funding, index);
    return f;
}

static void preSpendWallet(void)
{
    resetPool();
    wreset(&WAL);
    P1 = plainTx(T_PLAIN1);
    P2 = plainTx(T_PLAIN2);
    XFER = transferTx(T_TRANSFER, tid(T_FUNDING), 1);
    wadd(&WAL, P1);
    wadd(&WAL, P2);
    wadd(&WAL, XFER);
}

static void prespend_vector(void)
{
    uint8_t s[20];
    size_t n = hx(TRANSFER_TEN_TO_OUTPUT_ZERO, s, sizeof(s));
    BRAssetHeader h;
    BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS];

    ck(BRAssetDecodeInstructions(s, n, &h, l, N(l)) == BR_ASSET_SCRIPT_ASSET && h.operation == DA_TRANSFER &&
       h.instructionCount == 1 && l[0].outputIndex == 0 && l[0].amount == 10,
       "the vector is a transfer of ten units to output zero");
}

static void prespend_targeted_held(void)
{
    UInt256 h[1];
    uint32_t n[1] = { 3 };

    preSpendWallet();
    h[0] = XFER->tx.txHash;
    heldExactly("inputs unknown: our last output", pass(&WAL), h, n, 1);
}

static void prespend_untargeted_spendable(void)
{
    BRAssetHoldResult r;

    preSpendWallet();
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    r = pass(&WAL);
    complete("the inputs carry exactly what is handed out", r);
    ck(r.heldCount == 0, "an owned output nothing targets stays in the plain-coin set (%zu held)", r.heldCount);
    // GUARD (the Kotlin: "the transfer was never looked at"): without the row it is held.
    WAL.rowN = 0;
    ck(heldIn(pass(&WAL), XFER->tx.txHash, 3), "without the row the same output is held: the pass looked at it");
}

static void prespend_not_ours(void)
{
    BRAssetHoldResult r;
    size_t i;
    int zero = 0;

    preSpendWallet();
    r = pass(&WAL);
    for (i = 0; i < r.heldCount; i++) if (HELD[i].n == 0) zero = 1;
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! zero, "output 0 is targeted but is not ours: not held");
    ck(r.heldCount > 0, "something was held: the test is not blind");
}

static void prespend_plain_opreturn_first(void)
{
    Fx *f;
    UInt256 h[1];
    uint32_t n[1] = { 3 };

    resetPool();
    wreset(&WAL);
    f = fx(T_SHADOWED);
    out(f, "6a04deadbeef", 0);
    out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0);
    ours(f);
    ours(f);
    in(f, tid(T_FUNDING), 1);
    wadd(&WAL, f);
    h[0] = f->tx.txHash;
    heldExactly("a plain OP_RETURN before the carrier does not hide the carrier", pass(&WAL), h, n, 1);
}

static void prespend_unclassifiable_beside_readable(void)
{
    Fx *f;
    UInt256 h[2];
    uint32_t n[2] = { 2, 3 };

    resetPool();
    wreset(&WAL);
    f = fx(T_MIXED);
    out(f, TRANSFER_PUSHDATA2, 0);
    out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0);
    ours(f);
    ours(f);
    in(f, tid(T_FUNDING), 1);
    wadd(&WAL, f);
    h[0] = h[1] = f->tx.txHash;
    heldExactly("an unclassifiable carrier beside a readable one holds every owned output", pass(&WAL), h, n, 2);
}

static void prespend_unclassifiable_every_owned(void)
{
    Fx *f;
    BRAssetHoldResult r;

    preSpendWallet();
    f = fx(T_CARRIER);
    ours(f);
    out(f, TRANSFER_PUSHDATA2, 0);
    theirs(f);
    ours(f);
    in(f, tid(T_FUNDING), 2);
    wadd(&WAL, f);
    row(&WAL, tid(T_FUNDING), 2, 10, 1);
    r = pass(&WAL);
    complete("an unclassifiable carrier", r);
    ck(heldOf(r, f->tx.txHash) == 2 && heldIn(r, f->tx.txHash, 0) && heldIn(r, f->tx.txHash, 3),
       "every owned output of an unclassifiable carrier is held, with the inputs known to carry ten");
}

static void prespend_plain_pushdata2(void)
{
    Fx *f;
    BRAssetHoldResult r;

    preSpendWallet();
    wremove(&WAL, XFER);
    f = fx(T_NOTE);
    ours(f);
    out(f, NOTE_PUSHDATA2, 0);
    wadd(&WAL, f);
    r = pass(&WAL);
    complete("a plain PUSHDATA2 OP_RETURN", r);
    ck(r.heldCount == 0, "an OP_RETURN not tagged DA holds nothing, however framed (%zu held)", r.heldCount);
    // GUARD (the Kotlin: "the note was never looked at"): the same framing tagged DA holds our output.
    ck(hx(TRANSFER_PUSHDATA2, f->outs[1].script, f->outs[1].scriptLen) == f->outs[1].scriptLen, "the carrier fits the note's place");
    ck(heldIn(pass(&WAL), f->tx.txHash, 0), "the same transaction with the payload tagged DA: our output is held");
}

// Two readable carriers: the last decides (as BRDigiAsset.c chooses), not the first. [the first carrier,
// naming output 2; the second, naming output 3; ours 2, 3 and 4]; the inputs carried exactly ten.
static void prespend_two_carriers(void)
{
    Fx *f;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    f = fx(0xc5);
    out(f, "6a0644410115020a", 0);
    out(f, "6a0644410115030a", 0);
    ours(f);
    ours(f);
    ours(f);
    in(f, tid(T_FUNDING), 1);
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    wadd(&WAL, f);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, f->tx.txHash, 3) && ! heldIn(r, f->tx.txHash, 2) && r.heldCount == 1,
       "two readable carriers: the last one's output is held, the first one's is not (%zu held)", r.heldCount);
    ck(BRTxOutputIsAsset(&f->tx, &f->outs[3]) == 1 && BRTxOutputIsAsset(&f->tx, &f->outs[2]) == 0,
       "the classifier chooses the same carrier");
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");
}

static void prespend_refusals(void)
{
    BRAssetHoldView v;
    BRAssetHoldResult r;
    uint8_t *saved;

    // the wallet's transactions could not be listed
    preSpendWallet();
    v = view(&WAL);
    v.txs = NULL;
    r = BRAssetHoldOutputs(&v, WORK, N(WORK), HELD, N(HELD));
    refused("the transactions cannot be listed", r, BR_ASSET_HOLD_REFUSED_ARGUMENTS);

    // a listed transaction has no id
    preSpendWallet();
    WAL.txs[1] = NULL;
    refused("a listed transaction is not there", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);

    // a listed transaction cannot be read
    preSpendWallet();
    XFER->tx.outputs = NULL;
    refused("a listed transaction's outputs cannot be read", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);

    // a listed transaction's inputs cannot be read: and nothing is held
    preSpendWallet();
    XFER->tx.inputs = NULL;
    refused("a listed transaction's inputs cannot be read", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);

    // an output line cannot be parsed: a script the counts say is there and is not
    preSpendWallet();
    saved = P2->outs[1].script;
    P2->outs[1].script = NULL;
    refused("an output script cannot be read", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);
    P2->outs[1].script = saved;
}

static void prespend_amount_unused(void)
{
    UInt256 h[1];
    uint32_t n[1] = { 3 };

    preSpendWallet();
    XFER->outs[2].amount = UINT64_MAX;
    XFER->outs[3].amount = 9223372036854775808ULL;
    h[0] = XFER->tx.txHash;
    heldExactly("an amount the pass does not use is not a reason to stop", pass(&WAL), h, n, 1);
}

static void prespend_unreadable_input(void)
{
    UInt256 h[1];
    uint32_t n[1] = { 3 };

    preSpendWallet();
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    in(XFER, tid(T_FUNDING), 4294967295u);
    h[0] = XFER->tx.txHash;
    heldExactly("an input line that cannot be read makes the inputs unknown", pass(&WAL), h, n, 1);
}

static void prespend_later_pass(void)
{
    UInt256 h[1];
    uint32_t n[1] = { 3 };

    preSpendWallet();
    h[0] = XFER->tx.txHash;
    heldExactly("the first pass", pass(&WAL), h, n, 1);
    wadd(&WAL, plainTx(T_PLAIN3));
    heldExactly("a later pass holds the asset transaction again", pass(&WAL), h, n, 1);
}

static void prespend_arrived(void)
{
    Fx *arrived;

    preSpendWallet();
    complete("the first pass", pass(&WAL));
    arrived = transferTx(T_ARRIVED, tid(0xf2), 0);
    wadd(&WAL, arrived);
    ck(heldIn(pass(&WAL), arrived->tx.txHash, 3), "a transaction that arrived since the last pass is held by the next one");
}

// ---- AssetHoldCoversCreditedOutputsTest, asked of the pass ----------------------------------------
//
// held(h, vout, inputUnits, outputCount = 3, first = 0): a transaction [0 ours, 1 the payload, 2 ours]
// whose one input carries `inputUnits`: a row of that quantity (units above zero), the output of a
// plain transaction the wallet holds (0), or the output of a transaction it does not hold (null).

enum { U_NULL = -1 };

static int coversHeld(const char *payload, uint32_t vout, int64_t units)
{
    Fx *t, *plain;

    resetPool();
    wreset(&WAL);
    plain = fx(0x51);
    ours(plain);
    t = fx(0x52);
    ours(t);
    out(t, payload, 0);
    ours(t);
    if (units == U_NULL) in(t, tid(0x53), 0);
    else if (units == 0) in(t, plain->tx.txHash, 0);
    else {
        in(t, tid(0x54), 0);
        row(&WAL, tid(0x54), 0, units, 1);
    }
    wadd(&WAL, plain);
    wadd(&WAL, t);
    // the plain transaction's own output is ours and not an asset: never held
    {
        BRAssetHoldResult r = pass(&WAL);

        if (r.status != BR_ASSET_HOLD_COMPLETE || heldIn(r, plain->tx.txHash, 0)) return -1;
        return heldIn(r, t->tx.txHash, vout);
    }
}

static void coversCase(const char *what, const char *payload, uint32_t vout, int64_t units, int want)
{
    int got = coversHeld(payload, vout, units);
    char u[24];

    if (units == U_NULL) snprintf(u, sizeof(u), "null");
    else snprintf(u, sizeof(u), "%lld", (long long)units);
    ck(got == want, "%s: output %u with inputUnits=%s is %sheld (got %d)", what, (unsigned)vout, u, want ? "" : "not ", got);
}

static const int64_t NULL_0_10[] = { U_NULL, 0, 10 };

static void covers_issuance(void)
{
    size_t i;

    for (i = 0; i < N(NULL_0_10); i++) coversCase("issuance remainder on the last output", "6a08" "44410305" "0a" "00" "04" "10", 2, NULL_0_10[i], 1);
    for (i = 0; i < N(NULL_0_10); i++) coversCase("issuance with no instruction", "6a06" "44410305" "0a" "10", 2, NULL_0_10[i], 1);
    coversCase("issuance naming a missing output", "6a08" "44410305" "0a" "05" "04" "10", 2, U_NULL, 1);
    coversCase("issuance naming a missing output", "6a08" "44410305" "0a" "05" "04" "10", 2, 0, 1);
    coversCase("issuance assigning more than it issues (aggregatable)", "6a08" "44410305" "0a" "00" "14" "10", 2, U_NULL, 1);
    coversCase("issuance assigning more than it issues (hybrid)", "6a08" "44410305" "0a" "00" "14" "14", 2, U_NULL, 1);
    coversCase("an earlier version", "6a07" "4441020526" "4000", 2, U_NULL, 1);
    // the Kotlin copies that header to version 3, opcode 3 (rules), quantity 10, one instruction 10 -> 0:
    // here the same fields from a script (opcode 3 carries a metadata hash)
    coversCase("an issuance with rules", "6a28" "44410303" "1111111111111111111111111111111111111111111111111111111111111111"
               "0a" "000a" "10", 2, 0, 1);
    for (i = 0; i < N(NULL_0_10); i++) coversCase("GUARD issuance of its whole supply", "6a08" "44410305" "0a" "00" "0a" "10", 2, NULL_0_10[i], 0);
    coversCase("GUARD issuance of its whole supply: the issuer's output", "6a08" "44410305" "0a" "00" "0a" "10", 0, U_NULL, 1);
}

static void covers_transfer(void)
{
    coversCase("a transfer naming a missing output", "6a06" "44410315" "05" "09", 2, 9, 1);
    coversCase("a transfer naming a missing output", "6a06" "44410315" "05" "09", 2, 12, 1);
    coversCase("a transfer naming a missing output", "6a06" "44410315" "05" "09", 2, U_NULL, 1);
    // instructions [(0, 5), (1, 0)]
    coversCase("a zero-amount instruction after the inputs are used", "6a08" "44410315" "0005" "0100", 2, 5, 1);
    // one range instruction to output 3 of 3 units
    coversCase("GUARD a range past the outputs", "6a07" "44410315" "4003" "03", 2, 12, 1);
    coversCase("GUARD a void transfer with no units in", "6a06" "44410315" "05" "09", 2, 0, 0);
    coversCase("GUARD a burn to the destroy marker", "6a06" "44410325" "1f" "09", 2, 9, 0);
}

// An instruction to output 31 of a transfer names a real output: held whatever came in (33 outputs).
static void covers_output_31(void)
{
    size_t k;

    for (k = 0; k < N(NULL_0_10); k++) {
        int64_t units = (k == 2) ? 1 : NULL_0_10[k];   // listOf(null, 0L, 1L)
        Fx *t, *plain;
        BRAssetHoldResult r;
        int j;

        resetPool();
        wreset(&WAL);
        plain = fx(0x61);
        ours(plain);
        t = fx(0x62);
        for (j = 0; j < 32; j++) ours(t);
        out(t, "6a06444103151f01", 0);   // the 33rd output, the last
        if (units == U_NULL) in(t, tid(0x63), 0);
        else if (units == 0) in(t, plain->tx.txHash, 0);
        else { in(t, tid(0x64), 0); row(&WAL, tid(0x64), 0, units, 1); }
        wadd(&WAL, plain);
        wadd(&WAL, t);
        r = pass(&WAL);
        ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 31) && heldOf(r, t->tx.txHash) == 1,
           "output 31 of a transfer, inputs %lld: held, and nothing else of it", (long long)units);
    }
}

// Generated transfers, burns and issuances: every output BRAssetAllocate / BRAssetAllocateIssuance
// credits, the OP_RETURN aside, is held by the pass, for the input total drawn from and for an unknown one.
static void covers_every_credited_output(void)
{
    int draw, checked = 0, wrong = 0;

    RNG = 0x5eedULL * 0x9e3779b97f4a7c15ULL + 1;

    for (draw = 0; draw < 4000; draw++) {
        uint32_t outputCount = 1 + (uint32_t)below(6), opReturn = (uint32_t)below(outputCount), v;
        int opcode = (int[]){ 0x15, 0x25, 0x05 }[below(3)];
        GInst g[3];
        size_t n = (size_t)below(4), i, e;
        uint64_t total = 1 + below(19);
        int64_t inputUnits = (int64_t)below(20);
        uint8_t script[120];
        size_t len;
        BRAssetHeader h;
        BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS];
        BRAssetOutputUnits credited[64];
        int64_t work[4];
        BRAssetAllocation a;
        int known;

        for (i = 0; i < n; i++) {
            memset(&g[i], 0, sizeof(g[i]));
            g[i].range = below(4) == 0;
            g[i].out = (opcode == 0x25 && below(4) == 0) ? 31 : (uint32_t)below(outputCount + 2);
            g[i].amount = below(8);
        }
        len = carrier(opcode, total, g, n, 0, script);
        if (BRAssetDecodeInstructions(script, len, &h, l, N(l)) != BR_ASSET_SCRIPT_ASSET || h.instructionCount != n) {
            ck(0, "draw %d: the generated carrier does not decode as written", draw);
            continue;
        }

        if (opcode == 0x05) a = BRAssetAllocateIssuance(&h, l, n, "Laxx", (int32_t)outputCount, 1, credited, N(credited));
        else {
            BRAssetUnits u = { "Laxx", inputUnits, 1 };
            BRAssetInputStack s = { 1, &u, 1 };

            a = BRAssetAllocate(&h, l, n, 1, &s, 1, (int32_t)outputCount, work, N(work), credited, N(credited));
        }
        if (a.status != BR_ASSET_ALLOCATION_ALLOCATED) continue;

        for (known = 0; known < 2; known++) {
            Fx *t;
            BRAssetHoldResult r;

            resetPool();
            wreset(&WAL);
            t = fx(0x71);
            for (v = 0; v < outputCount; v++) {
                if (v == opReturn) outBytes(t, script, len, 0);
                else ours(t);
            }
            in(t, tid(0x72), 0);
            if (known) row(&WAL, tid(0x72), 0, inputUnits, 1);
            wadd(&WAL, t);
            r = pass(&WAL);

            for (e = 0; e < a.count; e++) {
                if ((uint32_t)credited[e].vout == opReturn || credited[e].count <= 0) continue;
                checked++;
                if ((r.status != BR_ASSET_HOLD_COMPLETE || ! heldIn(r, t->tx.txHash, (uint32_t)credited[e].vout)) && wrong++ < 5)
                    ck(0, "draw %d: opcode %02x, %zu instructions, inputs %s, %u outputs: credited output %d is held", draw,
                       opcode, n, known ? "known" : "unknown", (unsigned)outputCount, (int)credited[e].vout);
            }
        }
    }

    ck(wrong == 0, "every credited output is held, for the input total and for an unknown one (%d not held)", wrong);
    ck(checked > 1000, "the generator exercised the rule (%d credited outputs)", checked);
}

// ---- the walk ---------------------------------------------------------------------------------------
//
// A run of sends, each paying its fee with the change of the one before: T_i = [0 theirs, 1 transfer
// ten to output 0, 2 ours], inputs (asset_i, 0) with a row of 10 and (T_{i-1}, 2) with a row of 0.
// T_0 spends only its asset input. Every change output carries nothing, at any depth.

static Fx *runTx(uint32_t tag, int withChange, UInt256 prev)
{
    Fx *f = fx(tag);

    theirs(f);
    out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0);
    ours(f);
    in(f, tid(tag + 0x100000), 0);
    row(&WAL, tid(tag + 0x100000), 0, 10, 1);
    if (withChange) {
        in(f, prev, 2);
        row(&WAL, prev, 2, 0, 1);
    }
    return f;
}

static void buildRun(size_t len, int newestFirst)
{
    Fx *list[TMAX];
    size_t i;

    resetPool();
    wreset(&WAL);
    for (i = 0; i < len; i++) list[i] = runTx(0x200000 + (uint32_t)i, i > 0, i > 0 ? list[i - 1]->tx.txHash : tid(0));
    for (i = 0; i < len; i++) wadd(&WAL, list[newestFirst ? len - 1 - i : i]);
}

static void walk_long_run(void)
{
    BRAssetHoldResult r;
    size_t lens[] = { 1, 2, 3, 50, 2001 };
    size_t k;

    for (k = 0; k < N(lens); k++) {
        buildRun(lens[k], 1);
        r = pass(&WAL);
        complete("a run of sends", r);
        ck(r.heldCount == 0, "a run of %zu sends, newest listed first: every change output stays spendable (%zu held)", lens[k], r.heldCount);
        model(&WAL);
        ck(sameAsModel(r), "and the model agrees");
    }

    // the oldest send's asset input unknown: every change output after it is held
    buildRun(50, 1);
    WAL.rows[0].quantity = 0;   // T_0's asset input row stores zero; its funding transaction is not held
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 50 && sameAsModel(r),
       "one unknown input at the start of the run holds every change output after it (%zu held)", r.heldCount);
}

// Stored-zero rows: plain change of an earlier asset send counts nothing; the remainder of one does.
static void walk_zero_rows(void)
{
    Fx *a, *b;
    BRAssetHoldResult r;

    // A: transfer ten to 0, inputs (x, 0) row 10: its change (A, 2) carries nothing.
    // B: transfer ten to 0, inputs (A, 2) row 0, (y, 0) row 10: known 10, its change carries nothing.
    // A also spends a plain transaction's output, with no row: nothing.
    resetPool();
    wreset(&WAL);
    a = fx(0x81); theirs(a); out(a, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a);
    in(a, tid(0x82), 0); row(&WAL, tid(0x82), 0, 10, 1);
    b = fx(0x83); theirs(b); out(b, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(b);
    in(b, a->tx.txHash, 2); row(&WAL, a->tx.txHash, 2, 0, 1);
    in(b, tid(0x84), 0); row(&WAL, tid(0x84), 0, 10, 1);
    {
        Fx *p = fx(0x80);

        theirs(p);
        in(a, p->tx.txHash, 0);
        wadd(&WAL, b);
        wadd(&WAL, a);
        wadd(&WAL, p);
    }
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0, "plain change of an earlier send counts nothing (%zu held)", r.heldCount);

    // A carried 15: its change (A, 2) holds the remainder 5, so B's inputs are unknown and B's change is
    // held; so is the change of C, which spends (A, 2) as well and is listed before A: the walk for B
    // recorded A's total, and C reads it.
    {
        Fx *c = fx(0x85);

        theirs(c); out(c, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(c);
        in(c, a->tx.txHash, 2);
        in(c, tid(0x86), 0); row(&WAL, tid(0x86), 0, 10, 1);
        WAL.rows[0].quantity = 15;
        WAL.n = 0;
        wadd(&WAL, b);
        wadd(&WAL, c);
        wadd(&WAL, a);
        r = pass(&WAL);
        ck(heldIn(r, a->tx.txHash, 2) && heldIn(r, b->tx.txHash, 2) && heldIn(r, c->tx.txHash, 2) && r.heldCount == 3,
           "a remainder on an earlier change output holds it, and the change of each send that spent it (%zu held)", r.heldCount);
        model(&WAL);
        ck(sameAsModel(r), "and the model agrees");
    }

    // A stored-zero row on the NAMED output of A (output 0 is theirs: the row says the wallet held it)
    resetPool();
    wreset(&WAL);
    a = fx(0x81); ours(a); out(a, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a);
    in(a, tid(0x82), 0); row(&WAL, tid(0x82), 0, 10, 1);
    b = fx(0x83); theirs(b); out(b, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(b);
    in(b, a->tx.txHash, 0); row(&WAL, a->tx.txHash, 0, 0, 1);
    wadd(&WAL, a);
    wadd(&WAL, b);
    r = pass(&WAL);
    ck(heldIn(r, b->tx.txHash, 2) && heldIn(r, a->tx.txHash, 0),
       "a stored-zero row on an output an instruction names is not plain change: the spender's last output is held");
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");

    // the same, one transaction further back: T spends (B, 2), B spends the named (A, 0) and ten
    {
        Fx *t = fx(0x87);

        in(b, tid(0x88), 0); row(&WAL, tid(0x88), 0, 10, 1);
        theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
        in(t, b->tx.txHash, 2); row(&WAL, b->tx.txHash, 2, 0, 1);
        in(t, tid(0x89), 0); row(&WAL, tid(0x89), 0, 10, 1);
        a->owned[0] = 0;
        b->owned[2] = 0;
        WAL.n = 0;
        wadd(&WAL, t);
        wadd(&WAL, b);
        wadd(&WAL, a);
        r = pass(&WAL);
        model(&WAL);
        ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 2) && sameAsModel(r),
           "a named output two transactions back: the remainder in between is unknown, the change after it held");
        WAL.classifier = &NEVER;
        r = pass(&WAL);
        ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 2), "the decoder's reading alone holds it too");
    }
}

// B spends a plain non-last output of A0 (decided by the payload), then the change of A1 (recorded by the
// pass, A1 is listed first), then the change of A2 (resolved by the walk); each carries nothing. T spends
// B's change. Nothing is held.
static void walk_two_below(void)
{
    Fx *a0, *a1, *a2, *b, *t;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    a0 = fx(0x8a); theirs(a0); ours(a0); out(a0, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a0);
    in(a0, tid(0x8b), 0); row(&WAL, tid(0x8b), 0, 10, 1);
    a1 = fx(0x8c); theirs(a1); out(a1, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a1);
    in(a1, tid(0x8d), 0); row(&WAL, tid(0x8d), 0, 10, 1);
    a2 = fx(0x8e); theirs(a2); out(a2, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a2);
    in(a2, tid(0x8f), 0); row(&WAL, tid(0x8f), 0, 10, 1);
    b = fx(0x90); theirs(b); out(b, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(b);
    in(b, a0->tx.txHash, 1); row(&WAL, a0->tx.txHash, 1, 0, 1);
    in(b, a1->tx.txHash, 2); row(&WAL, a1->tx.txHash, 2, 0, 1);
    in(b, a2->tx.txHash, 2); row(&WAL, a2->tx.txHash, 2, 0, 1);
    in(b, tid(0x96), 0); row(&WAL, tid(0x96), 0, 10, 1);
    t = fx(0x97); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(t, b->tx.txHash, 2); row(&WAL, b->tx.txHash, 2, 0, 1);
    in(t, tid(0x98), 0); row(&WAL, tid(0x98), 0, 10, 1);
    wadd(&WAL, a1);
    wadd(&WAL, t);
    wadd(&WAL, b);
    wadd(&WAL, a0);
    wadd(&WAL, a2);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0 && sameAsModel(r),
       "three inputs back, one decided, one recorded, one walked: nothing held (%zu)", r.heldCount);
}

// F's inputs cannot all be read (an index past INT32_MAX): the walk has no total for it, so the change
// of T, which spends F's change, is held, whatever F's readable inputs carry.
static void walk_unreadable_funding(void)
{
    Fx *p, *f, *t;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    p = fx(0x99); ours(p);
    f = fx(0x9a); theirs(f); out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); theirs(f);
    in(f, tid(0x9b), 0); row(&WAL, tid(0x9b), 0, 10, 1);
    in(f, p->tx.txHash, 0x80000000u);
    t = fx(0x9c); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(t, f->tx.txHash, 2); row(&WAL, f->tx.txHash, 2, 0, 1);
    in(t, tid(0x9d), 0); row(&WAL, tid(0x9d), 0, 10, 1);
    wadd(&WAL, t);
    wadd(&WAL, f);
    wadd(&WAL, p);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 2) && sameAsModel(r),
       "a funding transaction whose inputs cannot all be read has no total: the spender's change is held");
}

// The pass records each carrier transaction's own total, proof included. T's inputs cannot all be read
// and T is proven: the pass records ten for it, and U, which spends T's change and is listed after it,
// reads that record. The replay keeps its own record: there the walk has no total for T.
static void walk_pass_record(void)
{
    Fx *p, *t, *u;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    p = fx(0xa6); ours(p);
    t = fx(0xa7); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); theirs(t);
    in(t, tid(0xa8), 0); row(&WAL, tid(0xa8), 0, 10, 1);
    in(t, p->tx.txHash, 0x80000000u);
    prove(&WAL, t->tx.txHash);
    u = fx(0xa9); theirs(u); out(u, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(u);
    in(u, t->tx.txHash, 2); row(&WAL, t->tx.txHash, 2, 0, 1);
    in(u, tid(0xaa), 0); row(&WAL, tid(0xaa), 0, 10, 1);
    wadd(&WAL, p);
    wadd(&WAL, t);
    wadd(&WAL, u);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, u->tx.txHash, 2) && sameAsModel(r),
       "the pass's record of a proven total: the spender's change is free (the model agrees)");
    row(&WAL, u->tx.txHash, 2, 0, 0);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, u->tx.txHash, 2) && sameAsModel(r),
       "the replay's own record has no total for it: the row on the spender's change is held (the model agrees)");

    // one further: V spends U's change and is listed before U; the walk for V finds T recorded
    {
        Fx *v = fx(0xab);

        WAL.rowN--;   // the row on U's change again spent
        u->owned[2] = 0;
        theirs(v); out(v, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(v);
        in(v, u->tx.txHash, 2); row(&WAL, u->tx.txHash, 2, 0, 1);
        in(v, tid(0xac), 0); row(&WAL, tid(0xac), 0, 10, 1);
        WAL.n = 0;
        wadd(&WAL, p);
        wadd(&WAL, t);
        wadd(&WAL, v);
        wadd(&WAL, u);
        r = pass(&WAL);
        model(&WAL);
        ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, v->tx.txHash, 2) && sameAsModel(r),
           "a walk that meets a recorded total on the way reads the record: V's change is free (the model agrees)");
    }
}

// A walk records the total it resolved, units and all. A carries two carriers: the walk reads the
// first (three units to output 0), the pass the last (ten). A's inputs are unknown and A is proven, so
// the walk's total is three and the pass's record ten. B and C each spend A's change and are listed
// before A: B's walk records its total and C's walk reads the record. The decoder's reading is held to
// the model exactly. The other reading reads a funding transaction by the pass's carrier or not at all,
// and the pass holds the union.
static void walk_record_units(void)
{
    Fx *a, *b, *c;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    a = fx(0xb3); theirs(a); out(a, "6a06444101150003", 0); out(a, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); theirs(a);
    in(a, tid(0xb4), 0);
    prove(&WAL, a->tx.txHash);
    b = fx(0xb5); theirs(b); out(b, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); theirs(b);
    in(b, a->tx.txHash, 3); row(&WAL, a->tx.txHash, 3, 0, 1);
    in(b, tid(0xb6), 0); row(&WAL, tid(0xb6), 0, 10, 1);
    c = fx(0xb7); theirs(c); out(c, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(c);
    in(c, a->tx.txHash, 3);
    in(c, tid(0xb8), 0); row(&WAL, tid(0xb8), 0, 10, 1);
    wadd(&WAL, b);
    wadd(&WAL, c);
    wadd(&WAL, a);
    r = passReading(&WAL, 1);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && sameAsModel(r),
       "the walk's recorded total is what it resolved: the decoder's reading is the model's");
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, c->tx.txHash, 2) && coversModel(r),
       "the other reading reads a funding transaction by the pass's carrier or not at all, and the pass holds the union");
}

// A cycle (only a forged txid makes one): A spends (B, 2), B spends (A, 2), both stored zero, B's
// remainder proven zero. T spends (B, 2), (A, 2) and a row of ten; listed first. Each walk resolves
// its root without recording what it took on the way; asked fresh, (A, 2) and (B, 2) carry nothing.
static void walk_cycle_proven(void)
{
    Fx *a, *b, *t;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    a = fx(0x91); theirs(a); out(a, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(a);
    b = fx(0x92); theirs(b); out(b, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(b);
    t = fx(0x93); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(a, b->tx.txHash, 2); in(a, tid(0x94), 0);
    in(b, a->tx.txHash, 2);
    in(t, b->tx.txHash, 2); in(t, a->tx.txHash, 2); in(t, tid(0x95), 0);
    row(&WAL, a->tx.txHash, 2, 0, 1);
    row(&WAL, b->tx.txHash, 2, 0, 1);
    row(&WAL, tid(0x94), 0, 10, 1);
    row(&WAL, tid(0x95), 0, 10, 1);
    prove(&WAL, b->tx.txHash);
    wadd(&WAL, t);
    wadd(&WAL, a);
    wadd(&WAL, b);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 2), "the spender of a proven cycle: its change is not held");
    ck(sameAsModel(r), "and the model agrees (%zu held, model %zu)", r.heldCount, M_HELDN);

    // without the proof the cycle is unknown all round
    WAL.provenN = 0;
    r = pass(&WAL);
    model(&WAL);
    ck(heldIn(r, t->tx.txHash, 2) && heldIn(r, a->tx.txHash, 2) && heldIn(r, b->tx.txHash, 2) && sameAsModel(r),
       "an unproven cycle holds every change output in it and the spender's");
}

// A remainder proof releases the last output.
static void walk_proof(void)
{
    Fx *t;
    BRAssetHoldResult r;

    preSpendWallet();
    t = XFER;
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 3), "inputs unknown: the last output is held");
    prove(&WAL, t->tx.txHash);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 3), "a remainder proven zero releases it");
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");
}

// ---- the replay ---------------------------------------------------------------------------------

static void replay_rows(void)
{
    Fx *t, *p;
    BRAssetHoldResult r;

    preSpendWallet();
    t = XFER;
    p = P1;
    row(&WAL, tid(T_FUNDING), 1, 10, 1);   // the transfer's inputs carried exactly ten: its last output is free
    row(&WAL, t->tx.txHash, 3, 0, 0);       // a stored-zero row on it: not targeted, not held
    row(&WAL, p->tx.txHash, 0, 5, 0);       // a row with units on a plain transaction: held
    row(&WAL, p->tx.txHash, 1, 0, 0);       // a stored-zero row on a transaction with no carrier: no answer, held
    row(&WAL, tid(0xdd), 0, 0, 0);          // a stored-zero row on a transaction the wallet does not hold: not held
    row(&WAL, tid(0xde), 1, 7, 0);          // units on a transaction the wallet does not hold: held ahead of it
    row(&WAL, tid(0xdf), 0, 7, 1);          // spent: passed over
    r = pass(&WAL);
    complete("the replay", r);
    ck(! heldIn(r, t->tx.txHash, 3), "a stored-zero row nothing targets is not held");
    ck(heldIn(r, p->tx.txHash, 0), "a row with units is held");
    ck(heldIn(r, p->tx.txHash, 1), "a stored-zero row whose transaction has no carrier has no answer: held");
    ck(! heldIn(r, tid(0xdd), 0), "a stored-zero row whose transaction the wallet does not hold is not held");
    ck(heldIn(r, tid(0xde), 1), "a row with units whose transaction the wallet does not hold is held ahead of it");
    ck(! heldIn(r, tid(0xdf), 0), "a spent row is passed over");
    ck(r.heldCount == 3, "nothing else (%zu held)", r.heldCount);
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");
}

// The replay passes over an input it cannot read; the pass makes the total unknown.
static void replay_unreadable_input(void)
{
    Fx *t;
    BRAssetHoldResult r;

    preSpendWallet();
    t = XFER;
    t->owned[3] = 0;                          // the pass leaves the last output alone: not ours
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    in(t, tid(T_FUNDING), 4294967295u);
    row(&WAL, t->tx.txHash, 3, 0, 0);         // the replay asks about it all the same
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 3) && sameAsModel(r),
       "the replay reads the inputs it can: ten in, ten out, the row is not held");
    t->owned[3] = 1;
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 3), "the pass does not: the same output, ours, is held");
}

// ---- the union, through the pass, with abstract classifier verdicts -------------------------------

static void union_output(void)
{
    Fx *p;
    BRAssetHoldResult r;

    preSpendWallet();
    p = P1;
    absReset();
    WAL.classifier = &ABSTRACT;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, p->tx.txHash, 0) && heldIn(r, XFER->tx.txHash, 3),
       "the classifier says NO everywhere: the decoder's answer stands");
    absYes(p->tx.txHash, 0);
    r = pass(&WAL);
    ck(heldIn(r, p->tx.txHash, 0), "the classifier says YES for an output the decoder does not target: held");
    absYes(p->tx.txHash, 1);
    r = pass(&WAL);
    ck(! heldIn(r, p->tx.txHash, 1), "an output that is not the wallet's own is not held, whoever says YES");
    // the replay: a stored-zero row on output 2 of the transfer (theirs, neither named nor last)
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    row(&WAL, XFER->tx.txHash, 2, 0, 0);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, XFER->tx.txHash, 2), "the replay: a stored-zero row nothing targets is not held");
    absYes(XFER->tx.txHash, 2);
    r = pass(&WAL);
    ck(heldIn(r, XFER->tx.txHash, 2), "the replay: the classifier says YES for it: held");
}

// (F, 1) is the second output of F = [0 theirs, 1 ours, 2 the payload: transfer ten to 0, 3 ours]: no
// instruction names it and it is not last, so the decoder says it carries nothing.
static void unionFunding(Fx **pf, Fx **pt)
{
    Fx *f, *t;

    resetPool();
    wreset(&WAL);
    f = fx(0xe1); theirs(f); ours(f); out(f, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(f);
    in(f, tid(0xe2), 0); row(&WAL, tid(0xe2), 0, 10, 1);
    t = fx(0xe3); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(t, tid(0xe4), 0); row(&WAL, tid(0xe4), 0, 10, 1);
    wadd(&WAL, f);
    wadd(&WAL, t);
    *pf = f;
    *pt = t;
}

static void union_funding_output(void)
{
    Fx *f, *t;
    BRAssetHoldResult r;

    unionFunding(&f, &t);
    in(t, f->tx.txHash, 1);
    row(&WAL, f->tx.txHash, 1, 0, 1);
    absReset();
    WAL.classifier = &ABSTRACT;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 2), "a stored-zero input both readers call plain: the spender's change is free");
    absYes(f->tx.txHash, 1);
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 2), "the classifier says the funding output may carry units: the spender's inputs are unknown, its change held");
}

static void union_funding_tx(void)
{
    Fx *f, *t;
    BRAssetHoldResult r;

    unionFunding(&f, &t);
    // a plain funding transaction the wallet holds, spent with no row
    f->outs[2].script[0] = 0x00;   // the payload is now an ordinary script: f carries no carrier
    in(t, f->tx.txHash, 1);
    absReset();
    WAL.classifier = &ABSTRACT;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 2), "a no-row input from a plain transaction carries nothing");
    absContainsTx(f->tx.txHash);
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 2), "the classifier says the funding transaction carries a carrier: unknown, held");
}

// Two transactions back: T spends (F1, 2) stored zero, F1's remainder decides; F1 spends (F2, 1)
// stored zero, which the decoder calls plain and the classifier may say YES for.
static void union_walk(void)
{
    Fx *f2, *f1, *t;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    f2 = fx(0xe5); theirs(f2); ours(f2); out(f2, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(f2);
    in(f2, tid(0xe6), 0); row(&WAL, tid(0xe6), 0, 10, 1);
    f1 = fx(0xe7); theirs(f1); out(f1, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(f1);
    in(f1, f2->tx.txHash, 1); row(&WAL, f2->tx.txHash, 1, 0, 1);
    in(f1, tid(0xe8), 0); row(&WAL, tid(0xe8), 0, 10, 1);
    t = fx(0xe9); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(t, f1->tx.txHash, 2); row(&WAL, f1->tx.txHash, 2, 0, 1);
    in(t, tid(0xea), 0); row(&WAL, tid(0xea), 0, 10, 1);
    wadd(&WAL, t);
    wadd(&WAL, f1);
    wadd(&WAL, f2);
    absReset();
    WAL.classifier = &ABSTRACT;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0, "the walk two back, both readers saying plain: nothing held (%zu)", r.heldCount);
    absYes(f2->tx.txHash, 1);
    f2->owned[1] = 0;   // so that only the walk can see the verdict
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 2) && heldIn(r, f1->tx.txHash, 2),
       "the classifier's YES two transactions back reaches the remainder of each spender after it");

    // the same, with V spending (F1, 2) too and a row on V's change for the replay: the decoder's reading
    // records F1's total in its replay, and the other reading starts from a record of its own
    {
        Fx *v = fx(0xf0);

        theirs(v); out(v, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); theirs(v);
        in(v, f1->tx.txHash, 2);
        in(v, tid(0xf3), 0); row(&WAL, tid(0xf3), 0, 10, 1);
        row(&WAL, v->tx.txHash, 2, 0, 0);
        wadd(&WAL, v);
        r = pass(&WAL);
        ck(heldIn(r, t->tx.txHash, 2) && heldIn(r, v->tx.txHash, 2), "each reading reads with its own record of totals");
    }
}

// The decoder's reading is never given up: T spends F (plain to the decoder, a carrier to the abstract
// classifier) with no row, and five units; T assigns ten and is proven. The decoder's reading has five
// in, all of it riding to the last output: held. The other reading has the inputs unknown, so the proof
// stands and the last output carries nothing. The pass holds it.
static void union_proof(void)
{
    Fx *f, *t;
    BRAssetHoldResult r;

    resetPool();
    wreset(&WAL);
    f = fx(0xeb); ours(f);
    t = fx(0xec); theirs(t); out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0); ours(t);
    in(t, f->tx.txHash, 0);
    in(t, tid(0xed), 0); row(&WAL, tid(0xed), 0, 5, 1);
    prove(&WAL, t->tx.txHash);
    wadd(&WAL, t);
    wadd(&WAL, f);
    absReset();
    absContainsTx(f->tx.txHash);
    WAL.classifier = &ABSTRACT;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 2),
       "a proof the other reading would stand on does not release what the decoder's reading holds");
    // and an unspent row listed twice beside it (whose outpoint sorts first) leaves it held
    row(&WAL, tid(0x05), 1, 3, 0);
    row(&WAL, tid(0x05), 1, 3, 0);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 2) && heldIn(r, tid(0x05), 1) && r.heldCount == 2,
       "with a row listed twice: both held, once each (%zu)", r.heldCount);
}

// On ordinary transactions the two readers agree: each says YES for a named output.
static void union_agree(void)
{
    uint8_t s[20];
    size_t n = hx(TRANSFER_TEN_TO_OUTPUT_ZERO, s, sizeof(s));
    Fx *t;
    BRAssetHoldResult r;

    (void)n;
    preSpendWallet();
    t = XFER;
    t->owned[0] = 1;
    ck(BRTxOutputIsAsset(&t->tx, &t->outs[0]) == 1 && BRTxOutputIsAsset(&t->tx, &t->outs[3]) == 0,
       "the classifier names output 0 and not the last");
    r = pass(&WAL);
    ck(heldIn(r, t->tx.txHash, 0) && heldIn(r, t->tx.txHash, 3), "the pass holds both: the named output and the unknown remainder");
    WAL.classifier = &NEVER;
    ck(heldIn(pass(&WAL), t->tx.txHash, 0), "with the classifier silent the decoder holds the named output by itself");
    // the unknown remainder, as BRAssetOutpointMustBeExcluded has it
    ck(BRAssetOutpointMustBeExcluded(DA_TRANSFER, 3, 4, 0, 0, NULL, 0) == 1, "BRAssetOutpointMustBeExcluded excludes the last output when the input units are unknown");
}

// Two carriers in a funding transaction F, whose input is unknown; S spends F's last output (a row
// storing zero) and a row of five. The other reading reads a funding transaction by the pass's carrier
// or not at all, and the pass holds the union. Here that reading is asked alone, with the classifier
// silent, and its answer is held to the one the pass's carrier gives; beside it, the same wallet with
// F's second carrier a plain OP_RETURN, as a control.
static void union_two_carriers(void)
{
    static const GInst issue[1] = { { 0, 0, 0, 2, 10 } }, xferOne[1] = { { 0, 0, 0, 2, 1 } }, xferFive[1] = { { 0, 0, 0, 1, 5 } };
    Fx *f, *x, *t;
    BRAssetHoldResult r;
    int twoCarriers;

    for (twoCarriers = 1; twoCarriers >= 0; twoCarriers--) {
        resetPool();
        wreset(&WAL);
        f = fx(0xa01);
        outCarrier(f, 0x05, 10, issue, 1, 0);
        if (twoCarriers) outCarrier(f, 0x15, 0, xferOne, 1, 0);
        else out(f, "6a03aabbcc", 0);   // an OP_RETURN that is no carrier, so the outputs keep their places
        ours(f);
        ours(f);
        in(f, tid(0xa02), 0);
        x = fx(0xa03); ours(x); ours(x);
        in(x, tid(0xa04), 0);
        t = fx(0xa05);
        outCarrier(t, 0x15, 0, xferFive, 1, 0);
        ours(t);
        ours(t);
        in(t, f->tx.txHash, f->tx.outCount - 1); row(&WAL, f->tx.txHash, (uint32_t)f->tx.outCount - 1, 0, 1);
        in(t, x->tx.txHash, 0); row(&WAL, x->tx.txHash, 0, 5, 1);
        wadd(&WAL, f);
        wadd(&WAL, x);
        wadd(&WAL, t);
        WAL.classifier = &NEVER;
        r = passReading(&WAL, 2);
        if (twoCarriers) {
            UInt256 h[4] = { f->tx.txHash, f->tx.txHash, t->tx.txHash, t->tx.txHash };
            uint32_t n[4] = { 2, 3, 1, 2 };

            heldExactly("two carriers: the other reading reads F by the pass's carrier or not at all", r, h, n, 4);
        }
        else {
            UInt256 h[2] = { f->tx.txHash, t->tx.txHash };
            uint32_t n[2] = { 2, 1 };

            heldExactly("one carrier: the other reading reads F by that carrier", r, h, n, 2);
        }
    }
}

// Rows for one outpoint must agree, or the pass refuses with ROWS (its own status, apart from ARGUMENTS).
// T spends (F, 1) and sends ten to its output 0; F is not held.
static void rows_disagree(void)
{
    static const int64_t qa[] = { 0, 10, 10 }, qb[] = { 10, 10, 10 };
    static const int sa[] = { 1, 0, 1 }, sb[] = { 1, 1, 1 };
    BRAssetHoldResult r;
    int k;

    for (k = 0; k < 2; k++) {
        preSpendWallet();
        row(&WAL, tid(T_FUNDING), 1, qa[k], sa[k]);   // a stale row first
        row(&WAL, tid(T_FUNDING), 1, qb[k], sb[k]);
        r = pass(&WAL);
        refused(k == 0 ? "two rows for one outpoint, quantity 0 and 10" : "two rows for one outpoint, one spent and one not", r,
                BR_ASSET_HOLD_REFUSED_ROWS);
        WAL.rows[0] = WAL.rows[1];   // the same row twice
        r = pass(&WAL);
        ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0,
           "the same row twice is read as one: ten in, all ten to output 0 (theirs), nothing held (%s, %zu)", st(r.status), r.heldCount);
    }
    // the pair that disagrees is neither first in the view nor first in the sorted order
    preSpendWallet();
    row(&WAL, tid(0x01), 0, 4, 1);
    row(&WAL, tid(0x02), 0, 4, 1);
    row(&WAL, tid(T_FUNDING), 0, 0, 1);
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    row(&WAL, tid(0x03), 0, 4, 1);
    row(&WAL, tid(T_FUNDING), 1, 0, 1);
    row(&WAL, tid(0xfe), 0, 4, 1);
    refused("two rows for one outpoint among others", pass(&WAL), BR_ASSET_HOLD_REFUSED_ROWS);
    WAL.rows[5].quantity = 10;
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0, "and once they agree, the pass reads them (%s, %zu)", st(r.status), r.heldCount);

    // the same unspent row twice, of a transaction the wallet does not hold: held once
    preSpendWallet();
    row(&WAL, tid(0xa10), 4, qa[2], 0);
    row(&WAL, tid(0xa11), 0, 3, 0);
    row(&WAL, tid(0xa10), 4, qa[2], 0);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 3 && heldIn(r, tid(0xa10), 4) && heldIn(r, tid(0xa11), 0),
       "an unspent row listed twice is held once (%s, %zu)", st(r.status), r.heldCount);
}

// ---- refusals, capacity, edges ------------------------------------------------------------------

static void refusal_arguments(void)
{
    BRAssetHoldView v;

    preSpendWallet();
    v = view(&WAL);
    refused("no view", BRAssetHoldOutputs(NULL, WORK, N(WORK), HELD, N(HELD)), BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    refused("no work buffer", BRAssetHoldOutputs(&v, NULL, 5, HELD, N(HELD)), BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    refused("no held buffer", BRAssetHoldOutputs(&v, WORK, N(WORK), NULL, 3), BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    v.rows = NULL;
    v.rowCount = 1;
    refused("no rows where the count says there are", BRAssetHoldOutputs(&v, WORK, N(WORK), HELD, N(HELD)), BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    v = view(&WAL);
    v.proven = NULL;
    v.provenCount = 1;
    refused("no proofs where the count says there are", BRAssetHoldOutputs(&v, WORK, N(WORK), HELD, N(HELD)), BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    v = view(&WAL);
    {   // a choice of readings that makes no reading refuses, in a test build too
        static const int none[] = { 0, 4, 8, -1 };
        size_t k;

        for (k = 0; k < N(none); k++) {
            BRAssetHoldResult r = _BRAssetHoldOutputsReading(&v, WORK, N(WORK), HELD, N(HELD), none[k]);

            ck(r.status == BR_ASSET_HOLD_REFUSED_ARGUMENTS && r.heldCount == 0, "readings %d: refused ARGUMENTS with nothing held (%s, %zu)",
               none[k], st(r.status), r.heldCount);
        }
    }
    {
        BRAssetHoldResult unfilled;

        memset(&unfilled, 0, sizeof(unfilled));
        ck(unfilled.status == BR_ASSET_HOLD_REFUSED && unfilled.status != BR_ASSET_HOLD_COMPLETE, "a result nobody filled is a refusal");
    }
}

static void refusal_unreadable(void)
{
    BRAssetHoldResult r;

    preSpendWallet();
    P1->tx.outCount = (size_t)INT32_MAX + 1;   // never read: refused on the count
    refused("more outputs than an Int holds", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);
    preSpendWallet();
    P1->tx.inputs = NULL;                       // a plain transaction: refused all the same
    refused("a plain transaction's inputs cannot be read", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);
    preSpendWallet();
    P1->tx.inputs = NULL;
    P1->tx.inCount = 0;                         // no inputs, no pointer: nothing to read
    r = pass(&WAL);
    complete("no inputs and no pointer", r);
    preSpendWallet();
    P1->tx.outputs = NULL;
    P1->tx.outCount = 0;                        // no outputs, no pointer
    complete("no outputs and no pointer", pass(&WAL));
    preSpendWallet();
    P1->outs[0].script = NULL;
    P1->outs[0].scriptLen = 0;                  // an empty script with no pointer
    complete("an empty script with no pointer", pass(&WAL));
    preSpendWallet();
    P1->outs[0].script = NULL;                  // output 0's script is not there
    refused("the first output's script cannot be read", pass(&WAL), BR_ASSET_HOLD_REFUSED_UNREADABLE);
}

static void capacity(void)
{
    BRAssetHoldView v;
    BRAssetHoldResult r;
    size_t work = 0, held = 0;

    preSpendWallet();
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, WORK, 2, HELD, N(HELD));
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.heldCount == 0 && r.workNeeded == 3 && r.heldNeeded == 8,
       "two work entries for three transactions: CAPACITY, 3 and 8 needed (%s, %zu, %zu)", st(r.status), r.workNeeded, r.heldNeeded);
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, N(HELD));
    complete("three work entries", r);
    r = BRAssetHoldOutputs(&v, WORK, 3, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.heldCount == 0, "no room for the one held outpoint: CAPACITY, never shortened");
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, 1);
    complete("room for exactly one", r);
    ck(BRAssetHoldBounds(&v, &work, &held) == 1 && work == 3 && held == 8, "BRAssetHoldBounds: one per transaction, every output");
    row(&WAL, tid(0xde), 1, 7, 0);
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, 1);
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.heldCount == 0 && r.heldNeeded == 9,
       "the replay's outpoint does not fit: CAPACITY, never shortened");
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, 2);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 2, "room for both");
    row(&WAL, tid(0xde), 1, 7, 0);   // the same unspent row listed twice
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, 2);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 2 && heldIn(r, tid(0xde), 1),
       "the same unspent row listed twice: room for the answer's two is enough (%s, %zu)", st(r.status), r.heldCount);
    WAL.rowN--;
    WAL.rows[0].txHash = XFER->tx.txHash;   // the row now on the outpoint the pass holds
    WAL.rows[0].n = 3;
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, 1);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && heldIn(r, XFER->tx.txHash, 3),
       "the replay offers what the pass holds: room for one is enough (%s, %zu)", st(r.status), r.heldCount);
    WAL.rows[0].txHash = tid(0xde);
    WAL.rows[0].n = 1;
    // the work buffer sorts the rows and the proofs too: one entry per row or proof when they are more
    row(&WAL, tid(0xdf), 0, 0, 1);
    row(&WAL, tid(0xdf), 1, 0, 1);
    row(&WAL, tid(0xdf), 2, 0, 1);
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, WORK, 3, HELD, N(HELD));
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.heldCount == 0 && r.workNeeded == 4 && r.heldNeeded == 12,
       "four rows, three transactions, three work entries: CAPACITY, 4 and 12 needed (%s, %zu, %zu)", st(r.status), r.workNeeded,
       r.heldNeeded);
    ck(BRAssetHoldOutputs(&v, WORK, 4, HELD, N(HELD)).status == BR_ASSET_HOLD_COMPLETE, "four work entries");
    {
        size_t k;

        for (k = 0; k < 5; k++) prove(&WAL, tid(0xe0 + (uint32_t)k));
        v = view(&WAL);
        r = BRAssetHoldOutputs(&v, WORK, 4, HELD, N(HELD));
        ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.workNeeded == 5, "five proofs: five work entries needed (%s, %zu)",
           st(r.status), r.workNeeded);
        ck(BRAssetHoldBounds(&v, &work, &held) == 1 && work == 5 && held == 12, "BRAssetHoldBounds: the most of transactions, rows, proofs");
        ck(BRAssetHoldOutputs(&v, WORK, 5, HELD, N(HELD)).status == BR_ASSET_HOLD_COMPLETE, "five work entries");
    }
    ck(BRAssetHoldBounds(NULL, &work, &held) == 0 && work == 0 && held == 0, "no view: no bounds");
    resetPool();
    wreset(&WAL);
    v = view(&WAL);
    r = BRAssetHoldOutputs(&v, NULL, 0, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0 && r.newlyHeld == 0 && r.workNeeded == 0 && r.heldNeeded == 0,
       "an empty wallet needs no buffer, and its answer says nothing else");
    {
        Fx *big = fx(0xd4);
        const BRTransaction *one[1];
        BRAssetHoldView bv;

        big->tx.outCount = SIZE_MAX - 2;   // read for the count only
        one[0] = &big->tx;
        memset(&bv, 0, sizeof(bv));
        bv.txs = one;
        bv.txCount = 1;
        bv.rowCount = 5;
        bv.rows = WAL.rows;
        ck(BRAssetHoldBounds(&bv, &work, &held) == 1 && work == 5 && held == SIZE_MAX,
           "the bound saturates at SIZE_MAX; five rows need five work entries");
        one[0] = NULL;
        ck(BRAssetHoldBounds(&bv, &work, &held) == 0 && work == 0 && held == 0, "a transaction that is not there: no bounds");
    }
}

static void edges(void)
{
    Fx *t, *z;
    BRAssetHoldResult r;

    // listed twice: read once, held once; and what comes after it is read
    preSpendWallet();
    wadd(&WAL, XFER);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && heldIn(r, XFER->tx.txHash, 3), "a transaction listed twice is held once");
    preSpendWallet();
    WAL.n = 0;
    wadd(&WAL, P1); wadd(&WAL, P1); wadd(&WAL, P2); wadd(&WAL, XFER);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && heldIn(r, XFER->tx.txHash, 3), "a transaction listed twice does not end the pass");

    // two different transactions with one txid (only a forged one): the first listed is the one read
    {
        int k;

        for (k = 0; k < 4; k++) {
            Fx *d1, *d2;
            size_t pos;

            preSpendWallet();
            d1 = transferTx(0xd1, tid(T_FUNDING), 1);
            d2 = plainTx(0xd1);
            WAL.n = 0;
            for (pos = 0; pos < (size_t)k; pos++) wadd(&WAL, plainTx(0x700 + (uint32_t)pos));
            wadd(&WAL, d1);
            wadd(&WAL, d2);
            r = pass(&WAL);
            ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, d1->tx.txHash, 3), "one txid twice, %d listed before: the first is read", k);
            WAL.txs[WAL.n - 2] = &d2->tx;
            WAL.txs[WAL.n - 1] = &d1->tx;
            r = pass(&WAL);
            ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, d1->tx.txHash, 3), "listed the other way round: the plain one is read");
        }
    }

    // an output with an empty script is not OP_RETURN, whatever its buffer holds
    preSpendWallet();
    t = fx(0xd2);
    outBytes(t, NULL, 0, 1);
    t->outs[0].script[0] = 0x6a;
    out(t, TRANSFER_TEN_TO_OUTPUT_ZERO, 0);
    ours(t);
    in(t, tid(T_FUNDING), 1);
    wadd(&WAL, t);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 0), "an empty script is an output like any other: named, held");
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");

    // an issuance whose outputs are all OP_RETURN has no issuer's output
    preSpendWallet();
    t = fx(0xd3);
    out(t, "6a08" "44410305" "0a" "010a" "10", 0);   // all ten to output 1
    out(t, "6a03aabbcc", 0);
    wadd(&WAL, t);
    row(&WAL, t->tx.txHash, 0, 0, 0);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 0) && sameAsModel(r),
       "an issuance with no output that is not OP_RETURN: a row on output 0 is not the issuer's");

    // an input index of exactly INT32_MAX is read: here a plain output carrying nothing
    preSpendWallet();
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    in(XFER, P1->tx.txHash, 0x7fffffffu);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, XFER->tx.txHash, 3), "an input index of INT32_MAX is read by the pass");
    // and by the replay: carrying five, which then ride to the last output
    row(&WAL, P1->tx.txHash, 0x7fffffffu, 5, 1);
    row(&WAL, XFER->tx.txHash, 3, 0, 0);
    XFER->owned[3] = 0;
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, XFER->tx.txHash, 3) && sameAsModel(r),
       "an input index of INT32_MAX is read by the replay");

    // a row at index INT32_MAX nothing names is not held
    preSpendWallet();
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    row(&WAL, XFER->tx.txHash, 0x7fffffffu, 0, 0);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, XFER->tx.txHash, 0x7fffffffu), "a stored-zero row at index INT32_MAX, nothing naming it: not held");

    // every output owned (the Kotlin's empty owned set): theirs too
    preSpendWallet();
    WAL.everyOutputOwned = 1;
    r = pass(&WAL);
    ck(heldIn(r, XFER->tx.txHash, 0) && heldIn(r, XFER->tx.txHash, 3) && r.heldCount == 2, "no owned set: every output an instruction targets");
    model(&WAL);
    ck(sameAsModel(r), "and the model agrees");

    // a transaction with no outputs, spent from with no row: unknown when the pass has not read it yet,
    // nothing when it has read it and found no carrier (the Kotlin's plainTxs)
    preSpendWallet();
    z = fx(0xf9);
    wadd(&WAL, z);
    t = XFER;
    t->ins[0].txHash = z->tx.txHash;
    t->ins[0].index = 0;
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 3) && sameAsModel(r),
       "an input from a transaction with no outputs, listed after its spender: unknown, held (the model agrees)");
    wremove(&WAL, z);
    WAL.txs[WAL.n++] = WAL.txs[0];
    WAL.txs[0] = &z->tx;
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, t->tx.txHash, 3) && sameAsModel(r),
       "listed before its spender: read as plain, nothing in, the change is free (the model agrees)");
    // the replay keeps no such set: a row on the change, the output not ours, is held there
    t->owned[3] = 0;
    row(&WAL, t->tx.txHash, 3, 0, 0);
    r = pass(&WAL);
    model(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && heldIn(r, t->tx.txHash, 3) && sameAsModel(r),
       "the replay reads a transaction with no outputs as unknown, wherever it is listed (the model agrees)");
    t->owned[3] = 1;
    WAL.rowN--;

    // a row on a transaction with no outputs: no answer, held
    row(&WAL, z->tx.txHash, 0, 0, 0);
    r = pass(&WAL);
    ck(heldIn(r, z->tx.txHash, 0), "a stored-zero row on a transaction with no outputs: no answer, held");

    // a row past INT32_MAX on a carrier transaction: no answer, held
    preSpendWallet();
    row(&WAL, XFER->tx.txHash, 0x80000000u, 0, 0);
    r = pass(&WAL);
    ck(heldIn(r, XFER->tx.txHash, 0x80000000u), "a stored-zero row past INT32_MAX: no answer, held");

    // an output past the transaction's last one: the classifier says NO; the decoder decides
    preSpendWallet();
    row(&WAL, tid(T_FUNDING), 1, 10, 1);
    row(&WAL, XFER->tx.txHash, 9, 0, 0);
    r = pass(&WAL);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && ! heldIn(r, XFER->tx.txHash, 9), "a stored-zero row past the outputs, nothing naming it: not held");
}

// ---- BRWalletHoldAssetOutputs: asset_implicit_change_kat's checks ----------------------------------

static const char *kMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = { 0 };

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[4096];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);

    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

static BRAssetHoldResult walletPass(BRWallet *w, const BRAssetHoldRow *rows, size_t rowCount)
{
    return BRWalletHoldAssetOutputs(w, rows, rowCount, NULL, 0, WORK, N(WORK), HELD, N(HELD));
}

static void implicit_change_wallet(void)
{
    uint8_t seed[64], spk[64], foreign[64];
    static const uint8_t kOpReturn[8] = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a };
    BRWallet *w;
    BRAddress a0;
    size_t spkLen, foreignLen;
    UInt256 h1, h2, h3, h4;
    BRTransaction *plain, *xfer, *later, *early, *tooBig, *ok;
    BRUTXO changeOut, earlyOut, markerOut;
    BRAssetHoldResult r;
    BRAssetHoldRow ahead;

    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    ck(w != NULL, "wallet created");
    if (! w) return;

    a0 = BRWalletReceiveAddress(w, 0);
    spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    foreignLen = BRAddressScriptPubKey(foreign, sizeof(foreign), "dgb1qynux5qngw4hstuzpp3lgxw8ym5rst8xdyesndt");
    ck(spkLen > 0 && foreignLen > 0, "our and a foreign scriptPubKey resolve");

    memset(h1.u8, 0x11, 32);
    plain = BRTransactionNew();
    BRTransactionAddInput(plain, h1, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(plain, 20000000, spk, spkLen);
    finalizeTxHash(plain);
    ck(BRWalletRegisterTransaction(w, plain) != 0, "plain DGB receive registered");
    BRWalletUpdateTransactions(w, &plain->txHash, 1, 700000, 1784980000);

    memset(h2.u8, 0x22, 32);
    xfer = BRTransactionNew();
    BRTransactionAddInput(xfer, h2, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(xfer, 10000, foreign, foreignLen);
    BRTransactionAddOutput(xfer, 0, kOpReturn, sizeof(kOpReturn));
    BRTransactionAddOutput(xfer, 5000000, spk, spkLen);
    finalizeTxHash(xfer);
    ck(BRWalletRegisterTransaction(w, xfer) != 0, "asset transfer registered");
    BRWalletUpdateTransactions(w, &xfer->txHash, 1, 700001, 1784980100);

    ck(BRTxOutputIsAsset(xfer, &xfer->outputs[2]) == 0, "the classifier does not see the implicit-change output");
    ck(BRTxOutputIsAsset(xfer, &xfer->outputs[0]) == 1, "the classifier does see the named output");

    changeOut.hash = xfer->txHash; changeOut.n = 2;
    markerOut.hash = xfer->txHash; markerOut.n = 0;
    ck(BRWalletUtxoIsAsset(w, &changeOut) == 0, "before the pass the implicit-change output is a plain coin");
    ck(BRWalletBalance(w) == 25000000, "before the pass its 5,000,000 counts toward the spendable balance");

    r = walletPass(w, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && HELD[0].n == 2 && UInt256Eq(HELD[0].hash, xfer->txHash) &&
       r.newlyHeld == 1, "the pass holds the implicit-change output and nothing else of ours (%s, %zu held, %zu new)",
       st(r.status), r.heldCount, r.newlyHeld);
    ck(BRWalletUtxoIsAsset(w, &changeOut) == 1, "the implicit-change output is now held as an asset outpoint");
    ck(BRWalletBalance(w) == 20000000, "its value is no longer part of the spendable balance");
    ck(BRWalletUtxoIsAsset(w, &markerOut) == 0, "the foreign marker is not the wallet's: not held (and not a coin of ours)");

    r = walletPass(w, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && r.newlyHeld == 0, "a second pass is a no-op");
    ck(BRWalletBalance(w) == 20000000, "balance unchanged by the repeat");

    memset(h3.u8, 0x33, 32);
    later = BRTransactionNew();
    BRTransactionAddInput(later, h3, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(later, 10000000, spk, spkLen);
    finalizeTxHash(later);
    ck(BRWalletRegisterTransaction(w, later) != 0, "a later receive registered");
    BRWalletUpdateTransactions(w, &later->txHash, 1, 700002, 1784980200);
    ck(BRWalletUtxoIsAsset(w, &changeOut) == 1, "the hold survives the balance rebuild a later transaction forces");
    ck(BRWalletBalance(w) == 30000000, "the rebuild still excludes the asset outpoint");

    tooBig = BRWalletCreateTransaction(w, 32000000, a0.s);
    ck(tooBig == NULL, "a DGB send that would need the asset-bearing output fails instead of burning it");
    if (tooBig) BRTransactionFree(tooBig);
    ok = BRWalletCreateTransaction(w, 29000000, a0.s);
    ck(ok != NULL, "a DGB send within the genuinely spendable balance still succeeds");
    if (ok) BRTransactionFree(ok);

    // a row with units for a transaction the wallet does not hold yet: held ahead of it
    memset(h4.u8, 0x44, 32);
    early = BRTransactionNew();
    BRTransactionAddInput(early, h4, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(early, 10000, foreign, foreignLen);
    BRTransactionAddOutput(early, 0, kOpReturn, sizeof(kOpReturn));
    BRTransactionAddOutput(early, 4000000, spk, spkLen);
    finalizeTxHash(early);
    memset(&ahead, 0, sizeof(ahead));
    ahead.txHash = early->txHash;
    ahead.n = 2;
    ahead.quantity = 90;
    r = walletPass(w, &ahead, 1);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.newlyHeld == 1, "the replay holds an outpoint before its transaction arrives");
    ck(BRWalletRegisterTransaction(w, early) != 0, "the transaction then arrives");
    BRWalletUpdateTransactions(w, &early->txHash, 1, 700003, 1784980300);
    earlyOut.hash = early->txHash; earlyOut.n = 2;
    ck(BRWalletUtxoIsAsset(w, &earlyOut) == 1, "the outpoint held ahead is excluded the moment its transaction lands");
    ck(BRWalletBalance(w) == 30000000, "and never counts toward the spendable balance");

    // a refusal holds nothing new
    r = BRWalletHoldAssetOutputs(w, NULL, 0, NULL, 0, WORK, 1, HELD, N(HELD));
    ck(r.status == BR_ASSET_HOLD_REFUSED_CAPACITY && r.newlyHeld == 0 && r.workNeeded == 4, "a refused wallet pass holds nothing new (%zu needed)", r.workNeeded);
    r = BRWalletHoldAssetOutputs(NULL, NULL, 0, NULL, 0, WORK, N(WORK), HELD, N(HELD));
    ck(r.status == BR_ASSET_HOLD_REFUSED_ARGUMENTS, "no wallet: ARGUMENTS");

    BRWalletFree(w);
}

// A plain wallet and an asset wallet whose every asset output is named: the pass holds what the wallet
// already held, and spends of the rest are unchanged.
static void wallet_agree(void)
{
    uint8_t seed[64], spk[64];
    static const uint8_t kNamed[8] = { 0x6a, 0x06, 0x44, 0x41, 0x01, 0x15, 0x00, 0x0a };
    BRWallet *w;
    BRAddress a0;
    size_t spkLen;
    UInt256 h;
    BRTransaction *t;
    BRAssetHoldResult r;
    BRAssetHoldRow rw;
    uint64_t before;

    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    if (! w) { ck(0, "wallet created"); return; }
    a0 = BRWalletReceiveAddress(w, 0);
    spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);

    r = walletPass(w, NULL, 0);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 0, "an empty wallet: complete, nothing held");

    memset(h.u8, 0x55, 32);
    t = BRTransactionNew();
    BRTransactionAddInput(t, h, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(t, 700, spk, spkLen);        // ours, named: ten units
    BRTransactionAddOutput(t, 0, kNamed, sizeof(kNamed));
    BRTransactionAddOutput(t, 9000000, spk, spkLen);    // ours, last
    finalizeTxHash(t);
    BRWalletRegisterTransaction(w, t);
    BRWalletUpdateTransactions(w, &t->txHash, 1, 700010, 1784990000);
    before = BRWalletBalance(w);
    ck(before == 9000000, "the named output is held by the wallet's own classifier");

    memset(&rw, 0, sizeof(rw));
    rw.txHash = h;   // the transfer's input carried exactly ten
    rw.n = 0;
    rw.quantity = 10;
    rw.spent = 1;     // the transfer spent it
    r = walletPass(w, &rw, 1);
    ck(r.status == BR_ASSET_HOLD_COMPLETE && r.heldCount == 1 && HELD[0].n == 0 && r.newlyHeld == 1,
       "the named output is held by both readers; the last output carries nothing (%zu held)", r.heldCount);
    ck(BRWalletBalance(w) == before, "the balance is what it was: the pass agrees with the classifier");

    BRWalletFree(w);
}

// ---- the sweeps ---------------------------------------------------------------------------------------

// A pseudo-random wallet. `any`: any scripts (random tails after the tag, a plain OP_RETURN anywhere,
// two carriers, an index past INT32_MAX). Otherwise one well-formed carrier at most, a plain OP_RETURN
// only after it: scripts the decoder and the classifier read alike.
static void genWallet(int any)
{
    size_t nTx = 1 + (size_t)below(9), i, j, k;
    uint32_t base = 0x300000 + (uint32_t)below(1000) * 64;
    Fx *list[16];

    resetPool();
    wreset(&WAL);
    WAL.everyOutputOwned = below(8) == 0;

    for (i = 0; i < nTx; i++) {
        Fx *f = fx(base + (uint32_t)i);
        size_t outs = 1 + (size_t)below(5), at = (size_t)below(outs + 1), plainAt = (size_t)-1;
        int kind = (int)below(10);
        uint8_t script[120];
        size_t len = 0;

        if (kind >= 2 && kind <= 7) {   // a well-formed carrier
            int opcode = (kind <= 3) ? 0x15 : (kind == 4) ? 0x25 : 0x05;
            GInst g[3];
            size_t n = (size_t)below(4);

            for (k = 0; k < n; k++) {
                memset(&g[k], 0, sizeof(g[k]));
                g[k].skip = below(6) == 0;
                g[k].range = below(5) == 0;
                g[k].percent = ! g[k].range && below(6) == 0;
                g[k].out = (opcode == 0x25 && below(3) == 0) ? 31 : (uint32_t)below(outs + 2);
                g[k].amount = g[k].percent ? below(101) : (below(12) == 0 ? 0 : 1 + below(40));
            }
            len = carrier(opcode, 1 + below(40), g, n, kind == 7 && below(2), script);
        }
        else if (kind == 8 && any) {    // DA and random bytes
            size_t tail = (size_t)below(19);

            static const uint8_t opcodes[] = { 0x05, 0x15, 0x25, 0x01, 0x03 };

            script[0] = 0x6a;
            script[1] = (uint8_t)(2 + tail);
            script[2] = 0x44;
            script[3] = 0x41;
            for (k = 0; k < tail; k++) {
                uint8_t b;

                if (below(4) == 0) b = (uint8_t)below(256);
                else if (k == 0) b = (uint8_t)(1 + below(3));     // a version
                else if (k == 1) b = opcodes[below(N(opcodes))];
                else b = (uint8_t)below(64);
                script[4 + k] = b;
            }
            len = 4 + tail;
        }
        if (below(4) == 0) plainAt = any ? (size_t)below(outs + 1) : at + 1;

        for (j = 0; j <= outs; j++) {
            if (j == plainAt && (any || len == 0 || j > at)) out(f, "6a03aabbcc", 0);
            if (j == at && len > 0) outBytes(f, script, len, 0);
            if (j < outs) out(f, below(3) ? OURS : THEIRS, below(3) != 0);
        }
        if (any && len > 5 && below(6) == 0) {   // a second carrier: the same, one bit of its first instruction flipped
            script[len - 1] ^= 0x01;
            outBytes(f, script, len, 0);
        }
        list[i] = f;
    }

    for (i = 0; i < nTx; i++) {
        size_t ins = (size_t)below(4);

        for (k = 0; k < ins; k++) {
            uint64_t pick = below(10);
            UInt256 h;
            uint32_t idx;

            if (pick < 6 && i > 0) {        // an earlier transaction's output
                Fx *src = list[below(i)];

                h = src->tx.txHash;
                idx = (uint32_t)below(src->tx.outCount + 1);
            }
            else if (pick < 8) {            // any transaction of the wallet: later ones and cycles too
                Fx *src = list[below(nTx)];

                h = src->tx.txHash;
                idx = (uint32_t)below(src->tx.outCount + 1);
            }
            else {                          // one the wallet does not hold
                h = tid(base + 32 + (uint32_t)below(8));
                idx = (uint32_t)below(3);
            }
            if (any && below(20) == 0) idx = 0x80000000u + (uint32_t)below(3);
            in(list[i], h, idx);
            if (below(2) && idx <= (uint32_t)INT32_MAX) {   // a Kotlin row's index is an Int
                static const int64_t q[] = { 0, 0, 0, 5, 10, 40, -1, INT64_MAX };
                size_t r, dup = 0;

                for (r = 0; r < WAL.rowN; r++) if (WAL.rows[r].n == idx && UInt256Eq(WAL.rows[r].txHash, h)) dup = 1;
                if (! dup) row(&WAL, h, idx, q[below(N(q))], below(8) == 0);
            }
        }
    }

    for (i = 0; i < nTx; i++) {    // rows on the wallet's own outputs, as detection writes them
        for (j = 0; j < list[i]->tx.outCount; j++) {
            size_t r, dup = 0;

            if (below(4)) continue;
            for (r = 0; r < WAL.rowN; r++) if (WAL.rows[r].n == j && UInt256Eq(WAL.rows[r].txHash, list[i]->tx.txHash)) dup = 1;
            if (! dup) row(&WAL, list[i]->tx.txHash, (uint32_t)j, below(3) ? 0 : (int64_t)below(20), below(10) == 0);
        }
        if (below(6) == 0) prove(&WAL, list[i]->tx.txHash);
    }

    // the order the wallet lists them in
    for (i = 0; i < nTx; i++) {
        size_t s = (size_t)below(nTx);
        Fx *t = list[i];

        list[i] = list[s];
        list[s] = t;
    }
    for (i = 0; i < nTx; i++) wadd(&WAL, list[i]);
}

// Rows for one outpoint are never listed twice in a generated wallet, so the pass never needs more room
// than its answer: run it again with exactly that, and compare. 1 when that does NOT hold.
static int exactRoom(BRAssetHoldResult r, int readings)
{
    BRAssetHoldResult again;

    if (r.status != BR_ASSET_HOLD_COMPLETE || r.heldCount == 0) return 0;
    again = passReadingIn(&WAL, readings, r.heldCount);
    return (again.status != BR_ASSET_HOLD_COMPLETE || again.heldCount != r.heldCount) ? 1 : 0;
}

// `readings` as passReading's: 0 the pass, 1 the decoder's reading alone. With 1 the pass too must hold
// at least the model.
static void sweepCase(const char *what, uint64_t seed, int any, const BRAssetHoldClassifier *c, int rounds, int readings)
{
    int round, wrong = 0, held = 0;

    RNG = seed;
    for (round = 0; round < rounds; round++) {
        BRAssetHoldResult r;

        genWallet(any);
        WAL.classifier = c;
        r = passReading(&WAL, readings);
        model(&WAL);
        held += (int)r.heldCount;
        if (! sameAsModel(r) && wrong++ < 5)
            ck(0, "%s, wallet %d: the %s (%s, %zu held) is the model (%zu held)", what, round, readings ? "decoder's reading" : "pass",
               st(r.status), r.heldCount, M_HELDN);
        if (readings && ! coversModel(pass(&WAL)) && wrong++ < 5) ck(0, "%s, wallet %d: the pass holds at least the model", what, round);
        if (exactRoom(r, readings) && wrong++ < 5) ck(0, "%s, wallet %d: a held buffer of the answer's size suffices", what, round);
    }
    ck(wrong == 0, "%s: %d wallets, the %s is the model in every one (%d not)", what, rounds, readings ? "decoder's reading" : "pass", wrong);
    ck(held > rounds / 2, "%s: the wallets held outputs (%d)", what, held);
}

static void sweep_decoder(void) { sweepCase("any scripts, the decoder alone", 0x51ULL, 1, &NEVER, 3000, 1); }

static void sweep_agree(void) { sweepCase("scripts both readers read alike, the core's classifier", 0x52ULL, 0, NULL, 3000, 0); }

static void sweep_union(void)
{
    int round, wrong = 0, extra = 0;

    RNG = 0x53ULL;
    for (round = 0; round < 3000; round++) {
        BRAssetHoldResult r;
        size_t i, j;
        int ok;

        genWallet(1);
        r = pass(&WAL);
        model(&WAL);
        ok = r.status == BR_ASSET_HOLD_COMPLETE;
        for (i = 0; ok && i < M_HELDN; i++) if (! heldIn(r, M_HELD[i].h, M_HELD[i].n)) ok = 0;
        for (i = 0; ok && i < WAL.n; i++) {
            const BRTransaction *tx = WAL.txs[i];

            for (j = 0; j < tx->outCount; j++) {
                if (tx->outputs[j].scriptLen > 0 && tx->outputs[j].script[0] == 0x6a) continue;
                if (! WAL.everyOutputOwned && ! ((const Fx *)(const void *)tx)->owned[j]) continue;
                if (BRTxOutputIsAsset(tx, &tx->outputs[j]) && ! heldIn(r, tx->txHash, (uint32_t)j)) ok = 0;
            }
        }
        if (r.heldCount > M_HELDN) extra++;
        if (ok && exactRoom(r, 0)) ok = 0;
        if (! ok && wrong++ < 5) ck(0, "any scripts, wallet %d: the pass holds at least the model and the classifier", round);
    }
    ck(wrong == 0, "any scripts: 3000 wallets, the pass holds at least what the model and the classifier hold (%d not)", wrong);
    (void)extra;
}

// BRAssetOutpointMustBeExcluded: whenever it excludes a transfer's last output, the pass holds it.
static void sweep_must_be_excluded(void)
{
    int draw, wrong = 0, checked = 0;

    RNG = 0x54ULL;
    for (draw = 0; draw < 3000; draw++) {
        uint32_t outputCount = 2 + (uint32_t)below(4), v;
        int opcode = below(2) ? 0x15 : 0x25;
        GInst g[3];
        size_t n = (size_t)below(4), k;
        int64_t units = (int64_t)below(30);
        int known = (int)below(2);
        uint8_t script[120];
        size_t len;
        BRAssetHeader h;
        BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS];
        Fx *t;
        BRAssetHoldResult r;

        for (k = 0; k < n; k++) {
            memset(&g[k], 0, sizeof(g[k]));
            g[k].range = below(5) == 0;
            g[k].percent = ! g[k].range && below(6) == 0;
            g[k].out = (uint32_t)below(outputCount + 1);
            g[k].amount = g[k].percent ? below(101) : below(20);
        }
        len = carrier(opcode, 0, g, n, 0, script);
        if (BRAssetDecodeInstructions(script, len, &h, l, N(l)) != BR_ASSET_SCRIPT_ASSET) { ck(0, "draw %d does not decode", draw); continue; }
        if (! BRAssetOutpointMustBeExcluded(h.operation, (int32_t)outputCount - 1, (int32_t)outputCount, known, units, l, h.instructionCount)) continue;

        resetPool();
        wreset(&WAL);
        t = fx(0x400);
        outBytes(t, script, len, 0);
        for (v = 1; v < outputCount; v++) ours(t);
        in(t, tid(0x401), 0);
        if (known) row(&WAL, tid(0x401), 0, units, 1);
        wadd(&WAL, t);
        r = pass(&WAL);
        checked++;
        if (! heldIn(r, t->tx.txHash, outputCount - 1) && wrong++ < 5) ck(0, "draw %d: an output BRAssetOutpointMustBeExcluded excludes is held", draw);
    }
    ck(wrong == 0 && checked > 500, "the pass is never weaker than BRAssetOutpointMustBeExcluded (%d checked, %d not held)", checked, wrong);
}

static void second_unit(void)
{
    ck(asset_hold_kat_tu2(), "the header included twice in a second translation unit links and runs");
}

// What ships honours no test seam: built without ASSET_HOLD_TEST_SEAMS, a view that names a classifier
// and a single reading are refused (ARGUMENTS), and the pass with the core's classifier completes. (The
// third unit carries no comparison arm but 65536, so its answers are the shipped ones in every arm.)
static void seams_off(void)
{
    BRAssetHoldView v;
    BRAssetHoldResult on, out[5];
    int seams;

    preSpendWallet();
    v = view(&WAL);
    on = BRAssetHoldOutputs(&v, WORK, N(WORK), HELD, N(HELD));
    complete("the test build, the core's classifier", on);
    memset(out, 0, sizeof(out));
    seams = asset_hold_kat_seams_off(&v, &NEVER, WORK, N(WORK), HELD, N(HELD), out);
    ck(seams == 0, "the third unit is built as what ships: ASSET_HOLD_TEST_SEAMS is 0 there (%d)", seams);
    ck(out[0].status == BR_ASSET_HOLD_COMPLETE, "without the seams, the core's classifier: complete (%s)", st(out[0].status));
    refused("without the seams, a view that names a classifier", out[1], BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    refused("without the seams, the decoder's reading alone", out[2], BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    refused("without the seams, the other reading alone", out[3], BR_ASSET_HOLD_REFUSED_ARGUMENTS);
    ck(out[4].status == BR_ASSET_HOLD_COMPLETE && out[4].heldCount == out[0].heldCount,
       "without the seams, both readings: complete, the pass's answer (%s, %zu)", st(out[4].status), out[4].heldCount);
    v.classifier = &NEVER;
    complete("the test build honours the classifier seam", BRAssetHoldOutputs(&v, WORK, N(WORK), HELD, N(HELD)));
}

// ---- the registry -------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;

static const Case CASES[] = {
    { "combine_exhaustive", combine_exhaustive },
    { "prespend_vector", prespend_vector },
    { "prespend_targeted_held", prespend_targeted_held },
    { "prespend_untargeted_spendable", prespend_untargeted_spendable },
    { "prespend_not_ours", prespend_not_ours },
    { "prespend_plain_opreturn_first", prespend_plain_opreturn_first },
    { "prespend_unclassifiable_beside_readable", prespend_unclassifiable_beside_readable },
    { "prespend_unclassifiable_every_owned", prespend_unclassifiable_every_owned },
    { "prespend_plain_pushdata2", prespend_plain_pushdata2 },
    { "prespend_two_carriers", prespend_two_carriers },
    { "prespend_refusals", prespend_refusals },
    { "prespend_amount_unused", prespend_amount_unused },
    { "prespend_unreadable_input", prespend_unreadable_input },
    { "prespend_later_pass", prespend_later_pass },
    { "prespend_arrived", prespend_arrived },
    { "covers_issuance", covers_issuance },
    { "covers_transfer", covers_transfer },
    { "covers_output_31", covers_output_31 },
    { "covers_every_credited_output", covers_every_credited_output },
    { "walk_long_run", walk_long_run },
    { "walk_zero_rows", walk_zero_rows },
    { "walk_two_below", walk_two_below },
    { "walk_unreadable_funding", walk_unreadable_funding },
    { "walk_pass_record", walk_pass_record },
    { "walk_record_units", walk_record_units },
    { "walk_cycle_proven", walk_cycle_proven },
    { "walk_proof", walk_proof },
    { "replay_rows", replay_rows },
    { "replay_unreadable_input", replay_unreadable_input },
    { "union_output", union_output },
    { "union_funding_output", union_funding_output },
    { "union_funding_tx", union_funding_tx },
    { "union_walk", union_walk },
    { "union_proof", union_proof },
    { "union_agree", union_agree },
    { "union_two_carriers", union_two_carriers },
    { "rows_disagree", rows_disagree },
    { "refusal_arguments", refusal_arguments },
    { "refusal_unreadable", refusal_unreadable },
    { "capacity", capacity },
    { "edges", edges },
    { "implicit_change_wallet", implicit_change_wallet },
    { "wallet_agree", wallet_agree },
    { "sweep_decoder", sweep_decoder },
    { "sweep_agree", sweep_agree },
    { "sweep_union", sweep_union },
    { "sweep_must_be_excluded", sweep_must_be_excluded },
    { "second_unit", second_unit },
    { "seams_off", seams_off },
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
