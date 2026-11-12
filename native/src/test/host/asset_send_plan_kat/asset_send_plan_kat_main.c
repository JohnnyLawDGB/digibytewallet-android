//
//  asset_send_plan_kat_main.c
//
//  Host KAT for BRAssetEncode.h and BRAssetSendPlan.h: the DigiAsset transfer carrier, and what an asset
//  send spends and pays. Moved from this repository's Kotlin (core/.../asset/DigiAssetEncoder.kt, BitWriter.kt,
//  core/.../asset/send/AssetCoinSelector.kt, AssetFeeEstimator.kt, AssetInputCheck.kt,
//  AssetSendSelection.kt, AssetTransferPlan.kt, core/.../model/AssetQuantity.kt, and the rule check and
//  argument checks of AssetManager.planAssetTransfer). The headers must answer exactly what those answer
//  and refuse exactly what they refuse, so every expectation here is fixed OUTSIDE the headers, two ways:
//
//    1. BY HAND. The inputs and expected answers of the Kotlin tests, value for value:
//       DigiAssetEncoderTest (enc_*), AssetCoinSelectorTest (sel_*), AssetCoinSelectorSecurityTest
//       (sec_*), AssetDustPolicyTest (dust_*), AssetFeeEstimatorTest (fee_*), AssetTransferPlanTest
//       (plan_*), AssetSendSelectionTest (choose_*), AssetSendSourceAddressTest (src_*, with the Kotlin
//       test tree's DigiAssetTransferModel written out below) and AssetQuantityTest (qty_*). The cases
//       without one of those prefixes are this suite's own: the selection ORDER pinned case by case,
//       the gate, the step protocol of the choice, the sums the C checks where Kotlin's Long wraps, and
//       the C-only refusals.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C: its lists, its string keys
//       ("txid:vout" in lower case), its stable sorts (as repeated picks of the first largest), its
//       messages, its BitWriter, and its Long arithmetic, which WRAPS (the model flags every wrap; the
//       headers answer OVERFLOW exactly there). model_sweep holds the headers to it over a fixed
//       pseudo-random corpus; the choice is driven through its step protocol by a fake indexer that both
//       sides ask, and the asks are compared one by one.
//
//  One case per process: `asset_send_plan_kat <case>` prints PASS:/FAIL: lines and `RESULT <case>
//  pass|fail`; `list` prints the case names. run.sh builds one binary per ASSET_ENCODE_UNFIXED and
//  ASSET_SEND_PLAN_UNFIXED arm and holds each comparison arm to failing exactly its cases.
//

#include "BRAssetSendPlan.h"
#include "BRAssetDecode.h"
#include "BRWallet.h"            // MIN_FEE_PER_KB, held to BR_ASSET_MIN_RELAY_FEE_PER_KB
#include <locale.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_send_plan_kat_tu2(void);   // run.sh's second translation unit, which includes both headers twice

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
#define MAXR 64

static const char *hexOf(const uint8_t *b, size_t n)
{
    static char buf[4][1024];
    static int at;
    char *o = buf[at++ & 3];
    size_t i;

    for (i = 0; i < n && i < 500; i++) snprintf(o + 2 * i, 3, "%02x", b[i]);
    o[2 * (n < 500 ? n : 500)] = '\0';
    return o;
}

// ---- rows ------------------------------------------------------------------------------------

static const uint8_t EMPTY[1] = { 0 };

static BRAssetRow row(const char *txid, int32_t vout, const uint8_t *script, size_t scriptLen, int64_t sats, int64_t qty)
{
    BRAssetRow r;

    memset(&r, 0, sizeof(r));
    r.txid = txid;
    r.vout = vout;
    r.script = script ? script : EMPTY;
    r.scriptLen = script ? scriptLen : 0;
    r.satoshis = sats;
    r.blockHeight = 100;
    r.isAsset = qty != 0;
    r.assetId = qty != 0 ? "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch" : NULL;
    r.assetQuantity = qty;
    return r;
}

// ---- Kotlin's Long: wrapping arithmetic, every wrap flagged ------------------------------------

static int WRAPPED;

static int64_t wadd(int64_t a, int64_t b)
{
    int64_t r = (int64_t)((uint64_t)a + (uint64_t)b);

    if (((a ^ r) & (b ^ r)) < 0) WRAPPED = 1;
    return r;
}

static int64_t wsub(int64_t a, int64_t b)
{
    int64_t r = (int64_t)((uint64_t)a - (uint64_t)b);

    if (((a ^ b) & (a ^ r)) < 0) WRAPPED = 1;
    return r;
}

static int64_t wmul(int64_t a, int64_t b)
{
    int64_t r = (int64_t)((uint64_t)a * (uint64_t)b);

    if (a != 0 && (r / a != b || (a == -1 && b == INT64_MIN))) WRAPPED = 1;
    return r;
}

// AssetSums.add: the Kotlin as written (the xor test of the wrapped sum).
static int64_t sumsAdd(int64_t a, int64_t b)
{
    int64_t r = (int64_t)((uint64_t)a + (uint64_t)b);

    return (((a ^ r) & (b ^ r)) < 0) ? (a < 0 ? INT64_MIN : INT64_MAX) : r;
}

// Math.addExact: 0 when it throws.
static int addExact(int64_t a, int64_t b, int64_t *out)
{
    int64_t r = (int64_t)((uint64_t)a + (uint64_t)b);

    if (((a ^ r) & (b ^ r)) < 0) return 0;
    *out = r;
    return 1;
}

// "txid:vout" with the txid in lower case (Kotlin: outpointKey / outpoint).
static void outpointKey(const BRAssetRow *r, char *out, size_t cap)
{
    char low[256];
    size_t i;
    const char *t = r->txid ? r->txid : "";

    for (i = 0; t[i] && i + 1 < sizeof(low); i++) low[i] = (char)((t[i] >= 'A' && t[i] <= 'Z') ? t[i] + 32 : t[i]);
    low[i] = '\0';
    snprintf(out, cap, "%s:%d", low, (int)r->vout);
}

typedef struct { char k[MAXR * 4][300]; size_t n; } StrSet;

static int setHas(const StrSet *s, const char *k)
{
    size_t i;

    for (i = 0; i < s->n; i++) if (strcmp(s->k[i], k) == 0) return 1;
    return 0;
}

static void setAdd(StrSet *s, const char *k)
{
    if (setHas(s, k) || s->n >= N(s->k)) return;
    snprintf(s->k[s->n++], sizeof(s->k[0]), "%s", k);
}

// sortedByDescending: a stable sort, written as repeated picks of the first largest that remains.
static void mSortDesc(const BRAssetRow **list, size_t n, int bySats)
{
    const BRAssetRow *src[MAXR];
    int used[MAXR];
    size_t i, k;

    memcpy(src, list, n * sizeof(*list));
    memset(used, 0, sizeof(used));
    for (k = 0; k < n; k++) {
        size_t best = n;

        for (i = 0; i < n; i++) {
            int64_t v, bv;

            if (used[i]) continue;
            if (best == n) { best = i; continue; }
            v = bySats ? src[i]->satoshis : src[i]->assetQuantity;
            bv = bySats ? src[best]->satoshis : src[best]->assetQuantity;
            if (v > bv) best = i;
        }
        used[best] = 1;
        list[k] = src[best];
    }
}

static int sameBytes(const BRAssetRow *a, const uint8_t *s, size_t n)
{
    return a->scriptLen == n && (n == 0 || memcmp(a->script, s, n) == 0);
}

// ---- the Kotlin: BitWriter and DigiAssetEncoder ---------------------------------------------------

typedef struct { int skip, range, percent; int32_t idx; int64_t amount; } MInst;

typedef struct { uint8_t buf[4096]; int bitPos; } MWriter;

static void mWriteBits(MWriter *w, int64_t value, int count)
{
    int remaining = count;

    while (remaining > 0) {
        int byteIndex = w->bitPos >> 3, bitOffset = w->bitPos & 7, avail = 8 - bitOffset;
        int bitsToWrite = remaining < avail ? remaining : avail, shift = remaining - bitsToWrite;
        int64_t mask = bitsToWrite == 64 ? -1 : (((int64_t)1 << bitsToWrite) - 1);
        int chunk = (int)(((uint64_t)value >> shift) & (uint64_t)mask);
        int shiftIntoByte = avail - bitsToWrite;

        w->buf[byteIndex] = (uint8_t)(w->buf[byteIndex] | (chunk << shiftIntoByte));
        w->bitPos += bitsToWrite;
        remaining -= bitsToWrite;
    }
}

// writeFixedPrecision: 0 when it throws.
static int mWriteFixedPrecision(MWriter *w, int64_t value)
{
    static const int B[5][3] = { { 1, 9, 4 }, { 2, 17, 4 }, { 3, 25, 4 }, { 4, 34, 3 }, { 5, 42, 3 } };
    static const int64_t P10[19] = { 1LL, 10LL, 100LL, 1000LL, 10000LL, 100000LL, 1000000LL, 10000000LL, 100000000LL,
                                     1000000000LL, 10000000000LL, 100000000000LL, 1000000000000LL, 10000000000000LL,
                                     100000000000000LL, 1000000000000000LL, 10000000000000000LL, 100000000000000000LL,
                                     1000000000000000000LL };
    int64_t m = value, raw;
    int e = 0, i;

    if (value < 0) return 0;
    if (value <= 31) { mWriteBits(w, 0, 3); mWriteBits(w, value, 5); return 1; }
    while (m % 10 == 0) { m /= 10; e++; }
    for (i = 0; i < 5; i++) {
        int64_t mMax = ((int64_t)1 << B[i][1]) - 1;
        int eMax = (1 << B[i][2]) - 1;

        if (m <= mMax && e <= eMax) {
            mWriteBits(w, B[i][0], 3);
            mWriteBits(w, m, B[i][1]);
            mWriteBits(w, e, B[i][2]);
            return 1;
        }
    }
    raw = m * P10[e];   // BitReader.pow10: e is at most 18 here
    if (! (raw >= 0 && raw < ((int64_t)1 << 54))) return 0;
    mWriteBits(w, 3, 2);
    mWriteBits(w, raw, 54);
    return 1;
}

// TransferInstruction's init.
static int mInstValid(const MInst *in)
{
    if (in->idx < 0) return 0;
    if (in->range ? in->idx > 8191 : in->idx > 31) return 0;
    if (in->percent) return in->amount >= 0 && in->amount <= 100;
    return in->amount >= 0;
}

// encodeTransferPayload / encodeTransferScript: 1 and the bytes, or 0 when it throws.
static int mEncodePayload(int version, const MInst *l, size_t n, uint8_t *out, size_t *len)
{
    MWriter w;
    size_t i;

    for (i = 0; i < n; i++) if (! mInstValid(&l[i])) return 0;   // the list could not have been built
    if (version < 2 || version > 3 || n == 0) return 0;
    memset(&w, 0, sizeof(w));
    mWriteBits(&w, 0x44, 8); mWriteBits(&w, 0x41, 8); mWriteBits(&w, version, 8); mWriteBits(&w, 0x15, 8);
    for (i = 0; i < n; i++) {
        mWriteBits(&w, l[i].skip ? 1 : 0, 1);
        mWriteBits(&w, l[i].range ? 1 : 0, 1);
        mWriteBits(&w, l[i].percent ? 1 : 0, 1);
        mWriteBits(&w, l[i].idx, l[i].range ? 13 : 5);
        if (l[i].percent) mWriteBits(&w, l[i].amount, 8);
        else if (! mWriteFixedPrecision(&w, l[i].amount)) return 0;
        if (w.bitPos > 8 * 3000) return 0;   // far past any script: the Kotlin would still throw at the cap
    }
    *len = (size_t)((w.bitPos + 7) >> 3);
    memcpy(out, w.buf, *len);
    return 1;
}

static int mEncodeScript(int version, const MInst *l, size_t n, uint8_t *out, size_t *len)
{
    uint8_t p[4096];
    size_t pl;

    if (! mEncodePayload(version, l, n, p, &pl)) return 0;
    if (pl > 80) return 0;
    out[0] = 0x6a;
    if (pl <= 75) { out[1] = (uint8_t)pl; memcpy(out + 2, p, pl); *len = pl + 2; }
    else { out[1] = 0x4c; out[2] = (uint8_t)pl; memcpy(out + 3, p, pl); *len = pl + 3; }
    return 1;
}

// ---- the Kotlin: AssetFeeEstimator -----------------------------------------------------------------

static int64_t ceilDivK(int64_t n, int64_t d)
{
    return d <= 0 ? 0 : wadd(n, d - 1) / d;
}

static int64_t mFee(int32_t a, int32_t d, int32_t o, int32_t op, int64_t rate)
{
    int64_t sa = a > 0 ? a : 0, sd = d > 0 ? d : 0, so = o > 0 ? o : 0, sop = op > 0 ? op : 0;
    int64_t totalInputs = sa + sd + 1;
    int64_t vsize = 12 + totalInputs * 150 + so * 34 + 9 + sop;
    int64_t r = rate > 0 ? rate : 0;
    int64_t requested = ceilDivK(wmul(vsize, r), 1000);
    int64_t floor = ceilDivK(wmul(vsize, 100000), 1000);

    return requested > floor ? requested : floor;
}

// ---- the Kotlin: AssetCoinSelector.select ------------------------------------------------------

enum { M_OK, M_INSUFFICIENT_ASSET, M_INSUFFICIENT_DGB, M_THREW };

typedef struct {
    int kind;
    int64_t required, available;
    const BRAssetRow *asset[MAXR];
    size_t na;
    const BRAssetRow *dgb[MAXR];
    size_t nd;
    int64_t assetChangeQty, dgbChangeSats;
} MSel;

static MSel mSelect(const BRAssetRow *const *assetUtxos, size_t na, const BRAssetRow *const *dgbUtxos, size_t nd,
                    int64_t assetNeeded, int64_t feeSats, int64_t markerOutputSats, int64_t assetChangeMarkerSats)
{
    MSel s;
    const BRAssetRow *sorted[MAXR], *feeCoins[MAXR];
    StrSet *assetOutpoints = calloc(1, sizeof(StrSet)), *seen = calloc(1, sizeof(StrSet));
    int64_t assetSum = 0, change, markersEmitted, assetInputSats = 0, dgbRequired, dgbSum = 0, totalIn;
    size_t i, nf = 0;
    char key[300];

    memset(&s, 0, sizeof(s));
    if (assetNeeded <= 0 || feeSats < 0 || markerOutputSats < 0 || assetChangeMarkerSats < 0) {
        s.kind = M_THREW;
        goto done;
    }

    memcpy(sorted, assetUtxos, na * sizeof(*sorted));
    mSortDesc(sorted, na, 0);
    for (i = 0; i < na; i++) {
        if (assetSum >= assetNeeded) break;
        s.asset[s.na++] = sorted[i];
        assetSum = sumsAdd(assetSum, sorted[i]->assetQuantity);
    }
    if (assetSum < assetNeeded) {
        s.kind = M_INSUFFICIENT_ASSET; s.required = assetNeeded; s.available = assetSum; s.na = 0;
        goto done;
    }

    change = wsub(assetSum, assetNeeded);
    markersEmitted = wadd(markerOutputSats, change > 0 ? assetChangeMarkerSats : 0);
    for (i = 0; i < s.na; i++) assetInputSats = wadd(assetInputSats, s.asset[i]->satoshis);
    dgbRequired = wsub(wadd(feeSats, markersEmitted), assetInputSats);
    if (dgbRequired < 0) dgbRequired = 0;

    for (i = 0; i < na; i++) { outpointKey(assetUtxos[i], key, sizeof(key)); setAdd(assetOutpoints, key); }
    for (i = 0; i < nd; i++) {
        outpointKey(dgbUtxos[i], key, sizeof(key));
        if (setHas(assetOutpoints, key) || setHas(seen, key)) continue;   // filter, then distinctBy
        setAdd(seen, key);
        feeCoins[nf++] = dgbUtxos[i];
    }
    if (dgbRequired > 0) {
        mSortDesc(feeCoins, nf, 1);
        for (i = 0; i < nf; i++) {
            if (dgbSum >= dgbRequired) break;
            s.dgb[s.nd++] = feeCoins[i];
            dgbSum = wadd(dgbSum, feeCoins[i]->satoshis);
        }
        if (dgbSum < dgbRequired) {
            MSel e;
            memset(&e, 0, sizeof(e));
            e.kind = M_INSUFFICIENT_DGB; e.required = dgbRequired; e.available = dgbSum;
            s = e;
            goto done;
        }
    }
    totalIn = wadd(assetInputSats, dgbSum);
    s.kind = M_OK;
    s.assetChangeQty = change;
    s.dgbChangeSats = wsub(wsub(totalIn, feeSats), markersEmitted);
done:
    free(assetOutpoints);
    free(seen);
    return s;
}

// ---- the Kotlin: AssetTransferPlanner --------------------------------------------------------------

typedef struct {
    int threw;                       // an exception left plan()
    int ready;
    char msg[200];                   // Refused: the message
    const BRAssetRow *asset[MAXR];
    size_t na;
    const BRAssetRow *dgb[MAXR];
    size_t nd;
    int roles[4];
    int64_t sats[4];
    size_t no;
    uint8_t script[100];
    size_t scriptLen;
    int64_t est, paid;
    const uint8_t *source;
    size_t sourceLen;
} MPlan;

// buildTransferInstructions + encodeTransferScript(version 3), or 0 (null).
static int mEncodeInstructions(const MSel *sel, int64_t quantity, int32_t recipientVout, int32_t changeVout, uint8_t *out,
                               size_t *len)
{
    MInst l[2 * MAXR];
    size_t n = 0, i;
    int64_t totalIn = 0, totalOut = 0, qtyRemaining = quantity, changeRemaining = sel->assetChangeQty;

    for (i = 0; i < sel->na; i++) if (! addExact(totalIn, sel->asset[i]->assetQuantity, &totalIn)) return 0;
    if (! addExact(totalOut, quantity, &totalOut) || ! addExact(totalOut, sel->assetChangeQty, &totalOut)) return 0;
    if (totalIn != totalOut) return 0;
    for (i = 0; i < sel->na; i++) {
        int isLast = i == sel->na - 1;
        int64_t inputRemaining = sel->asset[i]->assetQuantity;

        if (inputRemaining > 0 && qtyRemaining > 0) {
            int64_t take = inputRemaining < qtyRemaining ? inputRemaining : qtyRemaining;
            MInst t = { 0, 0, 0, recipientVout, take };
            l[n++] = t;
            qtyRemaining -= take;
            inputRemaining -= take;
        }
        if (inputRemaining > 0 && changeRemaining > 0 && changeVout >= 0) {
            int64_t take = inputRemaining < changeRemaining ? inputRemaining : changeRemaining;
            MInst t = { 0, 0, 0, changeVout, take };
            l[n++] = t;
            changeRemaining -= take;
            inputRemaining -= take;
        }
        if (! isLast && n > 0) l[n - 1].skip = 1;
    }
    return mEncodeScript(3, l, n, out, len);
}

static MPlan mPlan(const BRAssetRow *const *assetUtxos, size_t na, const BRAssetRow *const *dgbUtxos, size_t nd,
                   int64_t quantity, int64_t feePerKb)
{
    MPlan p;
    MSel ok0, ok;
    uint8_t provisional[100], final[100];
    size_t provLen = 0, finalLen = 0, i, iter, maxIter;
    int64_t bootstrapFee, feeSats, inSum = 0, outSum = 0, beyond;
    size_t estimatedForDgbInputs;

    memset(&p, 0, sizeof(p));
    if (na == 0) { snprintf(p.msg, sizeof(p.msg), "No UTXOs for this asset"); return p; }
    p.source = assetUtxos[0]->script;
    p.sourceLen = assetUtxos[0]->scriptLen;
    for (i = 0; i < na; i++) {
        if (! sameBytes(assetUtxos[i], p.source, p.sourceLen)) {
            snprintf(p.msg, sizeof(p.msg), "Asset inputs must come from one address");
            return p;
        }
    }
    bootstrapFee = mFee(1, 1, 3, 80, feePerKb);
    ok0 = mSelect(assetUtxos, na, dgbUtxos, nd, quantity, bootstrapFee, 2 * 6000, 0);
    if (ok0.kind == M_THREW) { p.threw = 1; return p; }
    if (ok0.kind == M_INSUFFICIENT_ASSET) {
        snprintf(p.msg, sizeof(p.msg), "Not enough asset: need %lld, have %lld", (long long)ok0.required, (long long)ok0.available);
        return p;
    }
    if (ok0.kind == M_INSUFFICIENT_DGB) {
        snprintf(p.msg, sizeof(p.msg), "Not enough DGB for fee: need %lld, have %lld", (long long)ok0.required, (long long)ok0.available);
        return p;
    }
    if (! mEncodeInstructions(&ok0, quantity, 0, 3, provisional, &provLen)) {
        snprintf(p.msg, sizeof(p.msg), "Could not build transfer instructions");
        return p;
    }
    maxIter = nd + 2;
    estimatedForDgbInputs = ok0.nd;
    feeSats = bootstrapFee;
    ok = ok0;
    for (iter = 0; iter < maxIter; iter++) {
        feeSats = mFee((int32_t)ok0.na, (int32_t)estimatedForDgbInputs, 3, (int32_t)provLen, feePerKb);
        ok = mSelect(assetUtxos, na, dgbUtxos, nd, quantity, feeSats, 2 * 6000, 0);
        if (ok.kind == M_THREW) { p.threw = 1; return p; }
        if (ok.kind == M_INSUFFICIENT_ASSET) {
            snprintf(p.msg, sizeof(p.msg), "Not enough asset: need %lld, have %lld", (long long)ok.required, (long long)ok.available);
            return p;
        }
        if (ok.kind == M_INSUFFICIENT_DGB) {
            snprintf(p.msg, sizeof(p.msg), "Not enough DGB for fee: need %lld, have %lld", (long long)ok.required, (long long)ok.available);
            return p;
        }
        if (ok.nd <= estimatedForDgbInputs) break;
        estimatedForDgbInputs = ok.nd;
    }
    p.roles[p.no] = 0; p.sats[p.no++] = 6000;
    p.roles[p.no] = 1; p.sats[p.no++] = 0;
    if (ok.dgbChangeSats > 5460) { p.roles[p.no] = 2; p.sats[p.no++] = ok.dgbChangeSats; }
    p.roles[p.no] = 3; p.sats[p.no++] = 6000;
    if (! mEncodeInstructions(&ok0, quantity, 0, (int32_t)p.no - 1, final, &finalLen)) {
        snprintf(p.msg, sizeof(p.msg), "Could not build transfer instructions");
        return p;
    }
    if (finalLen != provLen) { snprintf(p.msg, sizeof(p.msg), "Transfer instructions changed size"); return p; }
    memcpy(p.asset, ok.asset, ok.na * sizeof(*ok.asset)); p.na = ok.na;
    memcpy(p.dgb, ok.dgb, ok.nd * sizeof(*ok.dgb)); p.nd = ok.nd;
    memcpy(p.script, final, finalLen); p.scriptLen = finalLen;
    p.est = feeSats;
    for (i = 0; i < p.na; i++) inSum = wadd(inSum, p.asset[i]->satoshis);
    for (i = 0; i < p.nd; i++) inSum = wadd(inSum, p.dgb[i]->satoshis);
    for (i = 0; i < p.no; i++) outSum = wadd(outSum, p.sats[i]);
    p.paid = wsub(inSum, outSum);
    beyond = wsub(p.paid, p.est);
    if (! (beyond >= 0 && beyond <= 5460)) {
        snprintf(p.msg, sizeof(p.msg), "Fee does not match the transaction: pays %lld, estimated %lld", (long long)p.paid,
                 (long long)p.est);
        return p;
    }
    p.ready = 1;
    return p;
}

// sourceGroups / largestSingleSource: groupBy (first-appearance order), filter, stable sort.
typedef struct { const BRAssetRow *rows[MAXR]; size_t n; int64_t total; } MGroup;

static size_t mGroupBy(const BRAssetRow *const *rows, size_t n, MGroup *groups)
{
    size_t g = 0, i, j;

    for (i = 0; i < n; i++) {
        for (j = 0; j < g; j++) if (sameBytes(groups[j].rows[0], rows[i]->script, rows[i]->scriptLen)) break;
        if (j == g) { groups[g].n = 0; groups[g].total = 0; g++; }
        groups[j].rows[groups[j].n++] = rows[i];
        groups[j].total = sumsAdd(groups[j].total, rows[i]->assetQuantity);
    }
    return g;
}

static size_t mSourceGroups(const BRAssetRow *const *rows, size_t n, int64_t quantity, MGroup *out)
{
    MGroup all[MAXR];
    int used[MAXR];
    size_t g = mGroupBy(rows, n, all), k = 0, i, m = 0;

    memset(used, 0, sizeof(used));
    for (i = 0; i < g; i++) if (all[i].total < quantity) used[i] = 1;   // filter
    for (i = 0; i < g; i++) if (! used[i]) m++;
    for (k = 0; k < m; k++) {
        size_t best = g;

        for (i = 0; i < g; i++) {
            if (used[i]) continue;
            if (best == g || all[i].total > all[best].total) best = i;
        }
        used[best] = 1;
        out[k] = all[best];
    }
    return m;
}

static int64_t mLargestSingleSource(const BRAssetRow *const *rows, size_t n)
{
    MGroup all[MAXR];
    size_t g = mGroupBy(rows, n, all), i;
    int64_t best = 0;

    for (i = 0; i < g; i++) if (i == 0 || all[i].total > best) best = all[i].total;
    return best;
}

static int mFeeCoinEligible(const BRAssetRow *coin, const uint8_t *source, size_t sourceLen, BRAssetFact parent)
{
    return sameBytes(coin, source, sourceLen) || parent == BR_ASSET_FACT_NO;
}

// ---- the Kotlin: AssetInputCheck, and a fake indexer -----------------------------------------------

typedef struct {
    const char *txid;                // the coin's txid, as asked
    int32_t vout;                    // -1: any vout (the Kotlin tests' FakeSource keys by txid)
    BRAssetStackLookup lookup;
    BRAssetUnits e[4];
    size_t n;
} Stack;

typedef struct {
    Stack s[MAXR];
    size_t n;
    char asked[MAXR * 4][80];        // every ask, "txid:vout" as asked
    size_t nAsked;
} Source;

static const Stack *sourceFind(const Source *src, const char *txid, int32_t vout)
{
    size_t i;

    for (i = 0; i < src->n; i++) {
        if (strcmp(src->s[i].txid, txid) == 0 && (src->s[i].vout < 0 || src->s[i].vout == vout)) return &src->s[i];
    }
    return NULL;
}

static void sourceLog(Source *src, const char *txid, int32_t vout)
{
    if (src->nAsked < N(src->asked)) snprintf(src->asked[src->nAsked++], sizeof(src->asked[0]), "%s:%d", txid, (int)vout);
}

enum { MV_VERIFIED, MV_MISMATCH, MV_UNAVAILABLE };

static int mJudge(BRAssetStackLookup lookup, const BRAssetUnits *e, size_t n, const char *expected, int64_t recorded)
{
    if (lookup == BR_ASSET_STACK_UNAVAILABLE) return MV_UNAVAILABLE;
    if (lookup == BR_ASSET_STACK_NOT_UNSPENT) return MV_MISMATCH;
    if (n == 1 && recorded > 0 && strcmp(e[0].assetId, expected) == 0 && e[0].count == recorded) return MV_VERIFIED;
    return MV_MISMATCH;
}

// ---- the Kotlin: AssetSendSelection.choose, and planAssetTransfer's checks before it ------------------

enum { MK_PLANNED, MK_UNVERIFIED, MK_UNCONFIRMED, MK_SPANS, MK_NOT_PLANNED, MK_RULE_BOUND, MK_RULES_UNKNOWN, MK_THREW };

typedef struct {
    int kind;
    char msg[300];
    int64_t largest;
    MPlan plan;
} MOutcome;

typedef struct {                     // the Kotlin's process-lifetime sets
    StrSet verified, setAside;
} MProc;

typedef BRAssetFact (*ParentFn)(const char *txid);

static MOutcome mChoose(MProc *proc, int hasSource, Source *src, const char *assetId, const BRAssetRow *const *rows,
                        size_t n, const BRAssetRow *const *dgb, size_t nd, ParentFn parent, int64_t quantity,
                        int64_t feePerKb)
{
    MOutcome o;
    StrSet *notYet = calloc(1, sizeof(StrSet));
    int64_t total = 0;
    size_t round, i;
    char key[300];

    memset(&o, 0, sizeof(o));
    if (! hasSource) { o.kind = MK_UNVERIFIED; goto done; }
    for (i = 0; i < n; i++) total = sumsAdd(total, rows[i]->assetQuantity);
    if (total < quantity) {
        o.kind = MK_NOT_PLANNED;
        snprintf(o.msg, sizeof(o.msg), "Not enough asset: need %lld, have %lld", (long long)quantity, (long long)total);
        goto done;
    }
    for (round = 0; round <= n; round++) {
        const BRAssetRow *cand[MAXR], *fees[MAXR];
        MGroup groups[MAXR];
        size_t nc = 0, ng, nf = 0, t;
        int allConfirmed = 1;

        for (i = 0; i < n; i++) {
            outpointKey(rows[i], key, sizeof(key));
            if (! setHas(&proc->setAside, key) && ! setHas(notYet, key)) cand[nc++] = rows[i];
        }
        ng = mSourceGroups(cand, nc, quantity, groups);
        if (ng == 0) {
            int64_t ct = 0;

            for (i = 0; i < nc; i++) ct = sumsAdd(ct, cand[i]->assetQuantity);
            if (ct >= quantity) { o.kind = MK_SPANS; o.largest = mLargestSingleSource(cand, nc); }
            else o.kind = MK_UNCONFIRMED;
            goto done;
        }
        for (i = 0; i < nd; i++) {
            if (mFeeCoinEligible(dgb[i], groups[0].rows[0]->script, groups[0].rows[0]->scriptLen, parent(dgb[i]->txid)))
                fees[nf++] = dgb[i];
        }
        o.plan = mPlan(groups[0].rows, groups[0].n, fees, nf, quantity, feePerKb);
        if (o.plan.threw) { o.kind = MK_THREW; goto done; }
        if (! o.plan.ready) {
            o.kind = MK_NOT_PLANNED;
            if (strncmp(o.plan.msg, "Not enough DGB", 14) == 0 && nf < nd)
                snprintf(o.msg, sizeof(o.msg), "%s (an asset send's fee is paid only from coins received in ordinary payments or held at the asset's own address)", o.plan.msg);
            else snprintf(o.msg, sizeof(o.msg), "%s", o.plan.msg);
            goto done;
        }
        for (t = 0; t < o.plan.na; t++) {
            const BRAssetRow *in = o.plan.asset[t];
            const Stack *st;
            char ck2[300];
            int v;

            outpointKey(in, key, sizeof(key));
            snprintf(ck2, sizeof(ck2), "%s:%s:%lld", key, assetId, (long long)in->assetQuantity);
            if (setHas(&proc->verified, ck2)) continue;
            sourceLog(src, in->txid, in->vout);
            st = sourceFind(src, in->txid, in->vout);
            v = st ? mJudge(st->lookup, st->e, st->n, assetId, in->assetQuantity) : MV_UNAVAILABLE;
            if (v == MV_VERIFIED) setAdd(&proc->verified, ck2);
            else if (v == MV_MISMATCH) {
                if (st->lookup == BR_ASSET_STACK_FOUND && st->n > 0) setAdd(&proc->setAside, key);
                else setAdd(notYet, key);
                allConfirmed = 0;
            }
            else { o.kind = MK_UNVERIFIED; goto done; }
        }
        if (allConfirmed) { o.kind = MK_PLANNED; goto done; }
    }
    o.kind = MK_UNCONFIRMED;
done:
    free(notYet);
    return o;
}

// planAssetTransfer up to choose: the rule check, then the argument checks (the hold pass and the address
// check between them are the platform's), then the asset's rows.
static MOutcome mPlanAssetTransfer(MProc *proc, BRSweepRuleState rule, int hasSource, Source *src, const char *assetId,
                                   const BRAssetRow *const *rows, size_t n, const BRAssetRow *const *dgb, size_t nd,
                                   ParentFn parent, int64_t quantity, int64_t feePerKb)
{
    MOutcome o;

    memset(&o, 0, sizeof(o));
    if (rule == BR_SWEEP_RULE_BOUND) { o.kind = MK_RULE_BOUND; return o; }
    if (rule != BR_SWEEP_RULE_NONE) { o.kind = MK_RULES_UNKNOWN; return o; }
    if (quantity <= 0) { o.kind = MK_NOT_PLANNED; snprintf(o.msg, sizeof(o.msg), "Quantity must be positive"); return o; }
    if (feePerKb < 0) { o.kind = MK_NOT_PLANNED; snprintf(o.msg, sizeof(o.msg), "Fee rate must be non-negative"); return o; }
    if (n == 0) { o.kind = MK_NOT_PLANNED; snprintf(o.msg, sizeof(o.msg), "No UTXOs for asset %s", assetId); return o; }
    return mChoose(proc, hasSource, src, assetId, rows, n, dgb, nd, parent, quantity, feePerKb);
}

// ---- the Kotlin: AssetQuantity ------------------------------------------------------------------

// Kotlin's Char.isWhitespace, for the code points an ASCII or two-to-three-byte UTF-8 text can hold.
static int mIsWhitespace(uint32_t c)
{
    if (c == 0x09 || c == 0x0a || c == 0x0b || c == 0x0c || c == 0x0d || c == 0x1c || c == 0x1d || c == 0x1e || c == 0x1f) return 1;
    switch (c) {
    case 0x20: case 0xa0: case 0x1680: case 0x2028: case 0x2029: case 0x202f: case 0x205f: case 0x3000: return 1;
    default: return c >= 0x2000 && c <= 0x200a;
    }
}

// The text as code points (valid UTF-8 only; 0 for any other byte string, which this suite never gives).
static size_t mDecode(const char *s, size_t len, uint32_t *cp, size_t cap)
{
    size_t i = 0, n = 0;

    while (i < len && n < cap) {
        unsigned char c = (unsigned char)s[i];

        if (c < 0x80) { cp[n++] = c; i += 1; }
        else if ((c & 0xe0) == 0xc0 && i + 1 < len) { cp[n++] = ((uint32_t)(c & 0x1f) << 6) | (s[i + 1] & 0x3f); i += 2; }
        else if ((c & 0xf0) == 0xe0 && i + 2 < len) {
            cp[n++] = ((uint32_t)(c & 0x0f) << 12) | ((uint32_t)(s[i + 1] & 0x3f) << 6) | (s[i + 2] & 0x3f);
            i += 3;
        }
        else return 0;
    }
    return n;
}

// parse: 1 and *units, or 0 (null).
static int mParse(const char *input, size_t len, int divisibility, int64_t *units)
{
    static uint32_t cp[200000], text[200000];
    size_t n, a = 0, b, i, commas = 0, tn = 0, ip = 0, dots = 0, scale;
    char digits[200000];
    size_t nd = 0;
    int hasDot = 0;
    const char *max = "9223372036854775807";

    if (divisibility < 0 || divisibility > 8) return 0;
    n = mDecode(input, len, cp, N(cp));
    if (n == 0 && len > 0) return 0;
    b = n;
    while (a < b && mIsWhitespace(cp[a])) a++;          // trim()
    while (b > a && mIsWhitespace(cp[b - 1])) b--;
    for (i = a; i < b; i++) { if (cp[i] == ',') commas++; if (cp[i] == '.') hasDot = 1; }
    // normalizeSeparators
    if (commas == 0) { for (i = a; i < b; i++) text[tn++] = cp[i]; }
    else if (hasDot) { for (i = a; i < b; i++) if (cp[i] != ',') text[tn++] = cp[i]; }
    else if (commas == 1) { for (i = a; i < b; i++) text[tn++] = cp[i] == ',' ? '.' : cp[i]; }
    else return 0;
    if (tn == 0 || tn > 32) return 0;
    // all { isDigit || '.' }, then toBigDecimalOrNull: ASCII digits, at most one point, a digit
    for (i = 0; i < tn; i++) {
        if (text[i] == '.') { dots++; continue; }
        if (text[i] < '0' || text[i] > '9') return 0;
        digits[nd++] = (char)text[i];
        if (dots == 0) ip++;
    }
    if (dots > 1 || nd == 0) return 0;
    scale = nd - ip;
    if ((int)scale > divisibility) return 0;
    // movePointRight(divisibility).longValueExact(), > 0
    for (i = scale; (int)i < divisibility; i++) digits[nd++] = '0';
    digits[nd] = '\0';
    {
        const char *d = digits;
        size_t dl;

        while (*d == '0' && d[1]) d++;
        dl = strlen(d);
        if (dl > 19 || (dl == 19 && strcmp(d, max) > 0)) return 0;
        *units = 0;
        for (i = 0; i < dl; i++) *units = *units * 10 + (d[i] - '0');
    }
    return *units > 0;
}

// format: BigDecimal.valueOf(units, max(divisibility, 0)).stripTrailingZeros().toPlainString().
static void mFormat(int64_t units, int divisibility, char *out, size_t cap)
{
    char d[32];
    int scale = divisibility > 0 ? divisibility : 0, len;
    unsigned long long mag = units < 0 ? (unsigned long long)(-(units + 1)) + 1ULL : (unsigned long long)units;

    snprintf(d, sizeof(d), "%llu", mag);
    len = (int)strlen(d);
    if (units == 0) { snprintf(out, cap, "0"); return; }
    while (scale > 0 && d[len - 1] == '0') { d[--len] = '\0'; scale--; }
    if (scale == 0) snprintf(out, cap, "%s%s", units < 0 ? "-" : "", d);
    else if (len > scale) snprintf(out, cap, "%s%.*s.%s", units < 0 ? "-" : "", len - scale, d, d + len - scale);
    else {
        size_t at = 0;
        int z;

        if (units < 0) out[at++] = '-';
        out[at++] = '0';
        out[at++] = '.';
        for (z = 0; z < scale - len && at + 1 < cap; z++) out[at++] = '0';
        snprintf(out + at, cap - at, "%s", d);
    }
}

// ---- the Kotlin test tree: DigiAssetTransferModel -------------------------------------------------

typedef struct { const char *id; int64_t n; } Ent;
typedef struct { const char *address; Ent e[8]; size_t n; } TInput;
typedef struct { int skip; int32_t output; int64_t amount; } TInst;
typedef struct { Ent out[8][8]; size_t n[8]; size_t outputs; int ruleCheckFailed; } TResult;

static void tAdd(TResult *r, size_t o, const char *id, int64_t n)
{
    size_t i;

    for (i = 0; i < r->n[o]; i++) if (strcmp(r->out[o][i].id, id) == 0) { r->out[o][i].n += n; return; }
    r->out[o][r->n[o]].id = id;
    r->out[o][r->n[o]++].n = n;
}

static TResult tApply(const TInput *inputs, size_t ni, const char *const *addresses, size_t no, const TInst *insts, size_t nins,
                      const char *const *ruleBearing, size_t nr)
{
    TResult r;
    Ent stacks[8][8];
    size_t sn[8], ns = 0, i, j, index = 0, k;
    int allowSkip = 1;

    memset(&r, 0, sizeof(r));
    r.outputs = no;
#define FRESH() do { ns = 0; for (i = 0; i < ni; i++) if (inputs[i].n > 0) { memcpy(stacks[ns], inputs[i].e, sizeof(inputs[i].e)); sn[ns++] = inputs[i].n; } } while (0)
    FRESH();
    for (k = 0; k < nins; k++) {
        const TInst *in = &insts[k];
        int64_t left = in->amount;
        const char *removed;
        int fail = 0;

        if (index >= ns || sn[index] == 0) fail = 1;
        if (! fail) {
            removed = stacks[index][0].id;
            while (left > 0) {
                if (index >= ns || sn[index] == 0) { fail = 1; break; }
                if (strcmp(stacks[index][0].id, removed) != 0) { fail = 1; break; }
                allowSkip = 1;
                if (stacks[index][0].n <= left) {
                    left -= stacks[index][0].n;
                    memmove(stacks[index], stacks[index] + 1, (sn[index] - 1) * sizeof(Ent));
                    sn[index]--;
                    if (sn[index] == 0) { index++; allowSkip = 0; }
                }
                else { stacks[index][0].n -= left; left = 0; }
            }
            if (! fail && in->amount > 0) {
                if ((size_t)in->output >= no) fail = 1;
                else tAdd(&r, (size_t)in->output, removed, in->amount);
            }
            if (! fail && in->skip) { if (allowSkip) index++; allowSkip = 1; }
        }
        if (fail) {
            for (i = 0; i < no; i++) r.n[i] = 0;
            FRESH();
            break;
        }
    }
#undef FRESH
    for (i = 0; i < ns; i++) for (j = 0; j < sn[i]; j++) if (stacks[i][j].n > 0) tAdd(&r, no - 1, stacks[i][j].id, stacks[i][j].n);

    for (k = 0; k < nr; k++) {
        const char *asset = ruleBearing[k];
        const char *addr[32];
        int64_t change[32];
        size_t na = 0, a;
        int onInputs = 0;

        for (i = 0; i < ni; i++) for (j = 0; j < inputs[i].n; j++) if (strcmp(inputs[i].e[j].id, asset) == 0) onInputs = 1;
        if (! onInputs) continue;
#define MERGE(ad, v) do { for (a = 0; a < na; a++) if (strcmp(addr[a], (ad)) == 0) break; if (a == na) { addr[na] = (ad); change[na++] = 0; } change[a] += (v); } while (0)
        for (i = 0; i < ni; i++) for (j = 0; j < inputs[i].n; j++) if (strcmp(inputs[i].e[j].id, asset) == 0) MERGE(inputs[i].address, -inputs[i].e[j].n);
        for (i = 0; i < no; i++) for (j = 0; j < r.n[i]; j++) if (strcmp(r.out[i][j].id, asset) == 0) MERGE(addresses[i], r.out[i][j].n);
#undef MERGE
        for (a = 0; a < na; a++) {
            if (change[a] > 0) {
                for (i = 0; i < no; i++) r.n[i] = 0;
                r.ruleCheckFailed = 1;
                return r;
            }
        }
    }
    for (i = 0; i < no; i++) if (addresses[i][0] == '\0') r.n[i] = 0;   // a data output burns what it holds
    return r;
}

// ---- driving the headers ---------------------------------------------------------------------

#define ASSET "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"
#define OTHER "Ua7Q1otherAssetIdForTheTests0000000000"

// The plan's answer as the Kotlin's: "ready", "throws", "overflow" (C only), or the Refused message.
static void planMessage(const BRAssetTransferPlan *p, char *out, size_t cap)
{
    if (p->status == BR_ASSET_PLAN_READY) { snprintf(out, cap, "ready"); return; }
    if (p->status == BR_ASSET_PLAN_CAPACITY) { snprintf(out, cap, "capacity"); return; }
    switch (p->why) {
    case BR_ASSET_PLAN_WHY_ARGUMENTS: snprintf(out, cap, "throws"); break;
    case BR_ASSET_PLAN_WHY_NO_ASSET_UTXOS: snprintf(out, cap, "No UTXOs for this asset"); break;
    case BR_ASSET_PLAN_WHY_MIXED_ADDRESSES: snprintf(out, cap, "Asset inputs must come from one address"); break;
    case BR_ASSET_PLAN_WHY_NOT_ENOUGH_ASSET:
        snprintf(out, cap, "Not enough asset: need %lld, have %lld", (long long)p->required, (long long)p->available); break;
    case BR_ASSET_PLAN_WHY_NOT_ENOUGH_DGB:
        snprintf(out, cap, "Not enough DGB for fee: need %lld, have %lld", (long long)p->required, (long long)p->available); break;
    case BR_ASSET_PLAN_WHY_INSTRUCTIONS: snprintf(out, cap, "Could not build transfer instructions"); break;
    case BR_ASSET_PLAN_WHY_RESIZED: snprintf(out, cap, "Transfer instructions changed size"); break;
    case BR_ASSET_PLAN_WHY_FEE_MISMATCH:
        snprintf(out, cap, "Fee does not match the transaction: pays %lld, estimated %lld", (long long)p->paidFeeSats,
                 (long long)p->estimatedFeeSats); break;
    case BR_ASSET_PLAN_WHY_OVERFLOW: snprintf(out, cap, "overflow"); break;
    case BR_ASSET_PLAN_WHY_QUANTITY: snprintf(out, cap, "Quantity must be positive"); break;
    case BR_ASSET_PLAN_WHY_FEE_RATE: snprintf(out, cap, "Fee rate must be non-negative"); break;
    default: snprintf(out, cap, "why %d", (int)p->why); break;
    }
}

static void chooseMessage(const BRAssetSendOutcome *o, const char *assetId, char *out, size_t cap)
{
    char m[300];

    if (o->kind != BR_ASSET_SEND_NOT_PLANNED) { out[0] = '\0'; return; }
    if (o->plan.why == BR_ASSET_PLAN_WHY_NO_ASSET_UTXOS) { snprintf(out, cap, "No UTXOs for asset %s", assetId); return; }
    planMessage(&o->plan, m, sizeof(m));
    if (o->feeCoinRule) snprintf(out, cap, "%s (an asset send's fee is paid only from coins received in ordinary payments or held at the asset's own address)", m);
    else snprintf(out, cap, "%s", m);
}

typedef struct {
    BRAssetSendKey verified[512];
    size_t nv;
    BRAssetSendKey aside[512];
    size_t na;
    int lookups;                     // LOOKUP answers in the last choice
} HProc;

typedef struct {
    size_t work[4 * MAXR];
    uint8_t marks[MAXR];
} HBuf;

static BRAssetSendOutcome hChoose(HProc *p, HBuf *b, BRSweepRuleState rule, int hasSource, Source *src, const char *assetId,
                                  const BRAssetRow *rows, size_t n, const BRAssetRow *dgb, size_t nd, ParentFn parent,
                                  int64_t quantity, int64_t feePerKb)
{
    static BRAssetSendLookup answers[512];
    BRAssetFact parents[MAXR];
    BRAssetSendOutcome o;
    size_t na = 0, i;

    for (i = 0; i < nd; i++) parents[i] = parent(dgb[i].txid);
    p->lookups = 0;
    for (;;) {
        o = BRAssetSendChoose(rule, hasSource, assetId, rows, n, dgb, parents, nd, quantity, feePerKb, p->verified, p->nv,
                              p->aside, p->na, answers, na, b->marks, sizeof(b->marks), b->work, N(b->work));
        if (o.kind != BR_ASSET_SEND_LOOKUP) break;
        if (o.lookupRow >= n || na >= N(answers)) { ck(0, "a LOOKUP names row %zu of %zu", o.lookupRow, n); break; }
        {
            const BRAssetRow *r = &rows[o.lookupRow];
            const Stack *st;

            p->lookups++;
            sourceLog(src, r->txid, r->vout);
            st = sourceFind(src, r->txid, r->vout);
            answers[na].txid = r->txid;
            answers[na].vout = r->vout;
            answers[na].lookup = st ? st->lookup : BR_ASSET_STACK_UNAVAILABLE;
            answers[na].entries = st ? st->e : NULL;
            answers[na].entryCount = st ? st->n : 0;
            na++;
        }
    }
    for (i = 0; i < n && o.kind != BR_ASSET_SEND_LOOKUP && o.kind != BR_ASSET_SEND_CAPACITY; i++) {   // keep what marks say
        if ((b->marks[i] & BR_ASSET_SEND_MARK_VERIFIED) && p->nv < N(p->verified)) {
            BRAssetSendKey k = { rows[i].txid, rows[i].vout, assetId, rows[i].assetQuantity };
            p->verified[p->nv++] = k;
        }
        if ((b->marks[i] & BR_ASSET_SEND_MARK_SET_ASIDE) && p->na < N(p->aside)) {
            BRAssetSendKey k = { rows[i].txid, rows[i].vout, NULL, 0 };
            p->aside[p->na++] = k;
        }
    }
    return o;
}

static BRAssetFact parentNo(const char *txid) { (void)txid; return BR_ASSET_FACT_NO; }
static BRAssetFact parentYes(const char *txid) { (void)txid; return BR_ASSET_FACT_YES; }
static BRAssetFact parentFromAssetTx(const char *txid) { return strcmp(txid, "fromAssetTx") == 0 ? BR_ASSET_FACT_YES : BR_ASSET_FACT_NO; }

static Stack found(const char *txid, const char *id1, int64_t n1, const char *id2, int64_t n2)
{
    Stack s;

    memset(&s, 0, sizeof(s));
    s.txid = txid;
    s.vout = -1;
    s.lookup = BR_ASSET_STACK_FOUND;
    if (id1) { s.e[s.n].assetId = id1; s.e[s.n++].count = n1; }
    if (id2) { s.e[s.n].assetId = id2; s.e[s.n++].count = n2; }
    return s;
}

static Stack notUnspent(const char *txid)
{
    Stack s;

    memset(&s, 0, sizeof(s));
    s.txid = txid;
    s.vout = -1;
    s.lookup = BR_ASSET_STACK_NOT_UNSPENT;
    return s;
}

// The plan (_BRAssetPlanTransfer: the planner without the gate, which only the gated BRAssetSendChoose
// reaches in a send; called here to hold it to the Kotlin's AssetTransferPlanner) over arrays of rows,
// with buffers that always suffice.
static size_t AO[MAXR], DO_[MAXR];

static BRAssetTransferPlan plan(const BRAssetRow *a, size_t na, const BRAssetRow *d, size_t nd, int64_t qty, int64_t rate)
{
    return _BRAssetPlanTransfer(a, na, d, nd, qty, rate, AO, N(AO), DO_, N(DO_));
}

static BRAssetSelection sel(const BRAssetRow *a, size_t na, const BRAssetRow *d, size_t nd, int64_t need, int64_t fee,
                            int64_t marker, int64_t changeMarker)
{
    return BRAssetCoinSelect(a, na, d, nd, need, fee, marker, changeMarker, AO, N(AO), DO_, N(DO_));
}

static int64_t sumInSats(const BRAssetTransferPlan *p, const BRAssetRow *a, const BRAssetRow *d)
{
    int64_t s = 0;
    size_t i;

    for (i = 0; i < p->assetInputCount; i++) s += a[p->assetInputs[i]].satoshis;
    for (i = 0; i < p->dgbInputCount; i++) s += d[p->dgbInputs[i]].satoshis;
    return s;
}

static int roleCount(const BRAssetTransferPlan *p, BRAssetOutputRole role)
{
    size_t i;
    int n = 0;

    for (i = 0; i < p->outputCount; i++) if (p->outputs[i].role == role) n++;
    return n;
}

static int64_t roleSats(const BRAssetTransferPlan *p, BRAssetOutputRole role)
{
    size_t i;

    for (i = 0; i < p->outputCount; i++) if (p->outputs[i].role == role) return p->outputs[i].sats;
    return -1;
}

static int rolesAre(const BRAssetTransferPlan *p, const int *roles, size_t n)
{
    size_t i;

    if (p->outputCount != n) return 0;
    for (i = 0; i < n; i++) if ((int)p->outputs[i].role != roles[i]) return 0;
    return 1;
}

// Decode a script into its header and list; 1 when it is a classified asset carrier.
static int decode(const uint8_t *s, size_t n, BRAssetHeader *h, BRAssetTransferInstruction *l)
{
    return BRAssetDecodeInstructions(s, n, h, l, BR_ASSET_MAX_INSTRUCTIONS) == BR_ASSET_SCRIPT_ASSET;
}

static BRAssetTransferInstruction inst(int skip, int range, int percent, int32_t idx, int64_t amount)
{
    BRAssetTransferInstruction t;

    memset(&t, 0, sizeof(t));
    t.skip = skip; t.range = range; t.percent = percent; t.outputIndex = idx; t.amount = amount;
    return t;
}

// ---- DigiAssetEncoderTest ------------------------------------------------------------------------

static void enc_bitwriter(void)
{
    uint8_t buf[8];
    BRAssetBitWriter w = BRAssetBitWriterMake(buf, sizeof(buf));
    BRAssetBitReader r;
    uint64_t v[4] = { 9, 9, 9, 9 };

    BRAssetBitsWrite(&w, 1, 1); BRAssetBitsWrite(&w, 0, 1); BRAssetBitsWrite(&w, 1, 1); BRAssetBitsWrite(&w, 1, 1);
    r = BRAssetBitReaderMake(buf, BRAssetBitWriterLength(&w));
    BRAssetBitsRead(&r, 1, &v[0]); BRAssetBitsRead(&r, 1, &v[1]); BRAssetBitsRead(&r, 1, &v[2]); BRAssetBitsRead(&r, 1, &v[3]);
    ck(v[0] == 1 && v[1] == 0 && v[2] == 1 && v[3] == 1, "bitwriter round-trips single bits");

    memset(buf, 0xee, sizeof(buf));
    w = BRAssetBitWriterMake(buf, sizeof(buf));
    BRAssetBitsWriteByte(&w, 0xab);
    ck(strcmp(hexOf(buf, BRAssetBitWriterLength(&w)), "ab") == 0, "bitwriter round-trips a byte: %s", hexOf(buf, BRAssetBitWriterLength(&w)));

    memset(buf, 0xee, sizeof(buf));
    w = BRAssetBitWriterMake(buf, sizeof(buf));
    BRAssetBitsWrite(&w, 1, 1);
    ck(strcmp(hexOf(buf, BRAssetBitWriterLength(&w)), "80") == 0, "bitwriter pads last byte with zeros: %s",
       hexOf(buf, BRAssetBitWriterLength(&w)));
}

static int64_t sffcRoundTrip(int64_t v, size_t *len)
{
    uint8_t buf[16];
    BRAssetBitWriter w = BRAssetBitWriterMake(buf, sizeof(buf));
    BRAssetBitReader r;
    int64_t out = -1;

    if (! BRAssetBitsWriteFixedPrecision(&w, v)) return -2;
    *len = BRAssetBitWriterLength(&w);
    r = BRAssetBitReaderMake(buf, *len);
    if (! BRAssetBitsReadFixedPrecision(&r, &out)) return -3;
    return out;
}

static void enc_sffc(void)
{
    static const int64_t more[] = { 100, 1000, 10000, 100000, 12345, 999999, 1234567890, 2100000 };
    int64_t v;
    size_t len = 0, i;
    int ok = 1;

    for (v = 0; v <= 31; v++) if (sffcRoundTrip(v, &len) != v) ok = 0;
    ck(ok, "sffc round-trips 1-byte bucket values 0 through 31");
    ck(sffcRoundTrip(500, &len) == 500 && len == 2, "sffc round-trips value 500 in bucket 1, 2 bytes (%zu)", len);
    for (i = 0; i < N(more); i++) {
        ck(sffcRoundTrip(more[i], &len) == more[i], "round-trip %lld", (long long)more[i]);
    }
}

static void enc_fixtures(void)
{
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
    size_t n = 0;
    BRAssetHeader h;
    BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS], two[2];

    ck(BRAssetEncodeSimpleTransfer(3, 0, 500, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK &&
       strcmp(hexOf(s, n), "6a0744410315002052") == 0, "transfer v3 out=0 amount=500 matches TRANSFER_V3: %s", hexOf(s, n));
    ck(decode(s, n, &h, l) && h.version == 3 && h.opcode == 0x15 && h.instructionCount == 1 && l[0].outputIndex == 0 &&
       l[0].amount == 500, "and the decoder sees exactly what it expects");

    ck(BRAssetEncodeSimpleTransfer(2, 0, 1000, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK &&
       strcmp(hexOf(s, n), "6a0744410215002013") == 0, "transfer v2 out=0 amount=1000 matches REAL_MAINNET_TRANSFER_V2: %s", hexOf(s, n));

    two[0] = inst(1, 0, 0, 0, 100);
    two[1] = inst(0, 0, 0, 1, 50);
    ck(BRAssetEncodeTransferScript(3, two, 2, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && decode(s, n, &h, l) &&
       h.instructionCount == 2 && l[0].skip && l[0].outputIndex == 0 && l[0].amount == 100 && ! l[1].skip &&
       l[1].outputIndex == 1 && l[1].amount == 50, "transfer with skip bit round-trips");
    ck(strcmp(hexOf(s, n), "6a0a44410315802012012051") == 0, "and is the TRANSFER_V3_SKIP fixture's bytes: %s", hexOf(s, n));

    two[0] = inst(0, 0, 0, 31, 300);
    ck(BRAssetEncodeTransferScript(3, two, 1, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && decode(s, n, &h, l) &&
       ! l[0].range && ! l[0].isBurn && l[0].outputIndex == 31 && l[0].amount == 300,
       "an output 31 instruction encodes range=false and decodes as a transfer target");

    two[0] = inst(0, 1, 0, 500, 50);
    ck(BRAssetEncodeTransferScript(3, two, 1, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && decode(s, n, &h, l) &&
       l[0].range && l[0].outputIndex == 500 && l[0].amount == 50, "range transfer encodes 13-bit output index");

    two[0] = inst(0, 0, 1, 0, 75);
    ck(BRAssetEncodeTransferScript(3, two, 1, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && decode(s, n, &h, l) &&
       l[0].percent && l[0].amount == 75, "percent transfer encodes 8-bit amount");
}

static void enc_validation(void)
{
    BRAssetTransferInstruction t;
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
    size_t n = 7;
    BRAssetEncodeWhy why = BR_ASSET_ENCODE_WHY_NONE;

    t = inst(0, 0, 0, 32, 1);
    ck(! BRAssetTransferInstructionValid(&t) &&
       BRAssetEncodeTransferScript(3, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_INSTRUCTION &&
       n == 0, "rejects non-range outputIndex over 31");
    t = inst(0, 1, 0, 8192, 1);
    ck(! BRAssetTransferInstructionValid(&t), "rejects range outputIndex over 8191");
    t = inst(0, 0, 1, 0, 101);
    ck(! BRAssetTransferInstructionValid(&t), "rejects percent amount over 100");
    t = inst(0, 0, 0, 0, 1);
    ck(BRAssetEncodeTransferScript(1, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_VERSION,
       "rejects invalid version");
    ck(BRAssetEncodeTransferPayload(3, NULL, 0, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_EMPTY,
       "rejects empty instruction list");
}

static void simple(BRAssetTransferInstruction *l, size_t n)
{
    size_t i;

    for (i = 0; i < n; i++) l[i] = inst(0, 0, 0, 0, 1);
}

static void enc_push_framing(void)
{
    BRAssetTransferInstruction l[50];
    uint8_t s[400];
    size_t n = 0, pl = 0;
    BRAssetHeader h;
    BRAssetTransferInstruction d[BR_ASSET_MAX_INSTRUCTIONS];
    BRAssetEncodeWhy why;

    simple(l, 35);
    BRAssetEncodeTransferPayload(3, l, 35, s, sizeof(s), &pl, NULL);
    ck(pl == 74, "35 instructions are a 74-byte payload (%zu)", pl);
    ck(BRAssetEncodeTransferScript(3, l, 35, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && s[1] == 74 && decode(s, n, &h, d) &&
       h.instructionCount == 35, "74-byte payload stays single-push and round-trips");

    simple(l, 34);
    l[34] = inst(0, 0, 0, 0, 500);
    BRAssetEncodeTransferPayload(3, l, 35, s, sizeof(s), &pl, NULL);
    ck(pl == 75, "34 two-byte instructions and one of three are a 75-byte payload (%zu)", pl);
    ck(BRAssetEncodeTransferScript(3, l, 35, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && s[1] == 75 && decode(s, n, &h, d) &&
       h.instructionCount == 35, "75-byte payload stays single-push and round-trips");

    simple(l, 36);
    BRAssetEncodeTransferPayload(3, l, 36, s, sizeof(s), &pl, NULL);
    ck(pl == 76, "36 instructions are a 76-byte payload (%zu)", pl);
    ck(BRAssetEncodeTransferScript(3, l, 36, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && s[1] == 0x4c && s[2] == 76 &&
       n == 3 + 76 && decode(s, n, &h, d) && h.instructionCount == 36, "76-byte payload uses PUSHDATA1 and round-trips");

    simple(l, 38);
    BRAssetEncodeTransferPayload(3, l, 38, s, sizeof(s), &pl, NULL);
    ck(pl == 80, "38 instructions are an 80-byte payload (%zu)", pl);
    ck(BRAssetEncodeTransferScript(3, l, 38, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK && s[1] == 0x4c && s[2] == 80 &&
       n == 3 + 80 && decode(s, n, &h, d) && h.instructionCount == 38, "80-byte payload uses PUSHDATA1 and round-trips");

    simple(l, 50);
    for (n = 0; n < 50; n++) l[n].skip = 1;
    ck(BRAssetEncodeTransferScript(3, l, 50, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_TOO_LONG,
       "rejects payload over 80 bytes");
}

// ---- AssetCoinSelectorTest -------------------------------------------------------------------------

static void sel_basic(void)
{
    BRAssetRow a[1], d[1];
    BRAssetSelection r;

    a[0] = row("a", 0, NULL, 0, 600, 100); d[0] = row("b", 0, NULL, 0, 10000, 0);
    r = sel(a, 1, d, 1, 50, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 1 && r.dgbInputCount == 1 && r.assetChangeQty == 50 &&
       r.dgbChangeSats == 9500, "single asset utxo covers transfer with dgb change (%lld)", (long long)r.dgbChangeSats);

    a[0] = row("a", 0, NULL, 0, 5000, 100);
    r = sel(a, 1, d, 1, 100, 700, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 0 && r.assetChangeQty == 0 && r.dgbChangeSats == 3700,
       "asset utxo sats offset dgb requirement");

    a[0] = row("a", 0, NULL, 0, 600, 100); d[0] = row("d", 0, NULL, 0, 10000, 0);
    r = sel(a, 1, d, 1, 100, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetChangeQty == 0, "asset change is zero when inputs match exactly");

    r = sel(a, 1, d, 1, 50, 500, 600, 600);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetChangeQty == 50 && r.dgbChangeSats == 8900, "the change marker is budgeted when units come back (%lld)",
       (long long)r.dgbChangeSats);
    r = sel(a, 1, d, 1, 100, 500, 600, 600);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetChangeQty == 0 && r.dgbChangeSats == 9500, "guard no change marker is budgeted for an exact send");
}

static void sel_order(void)
{
    BRAssetRow a[3], d[3];
    BRAssetSelection r;

    a[0] = row("small", 0, NULL, 0, 600, 10); a[1] = row("big", 0, NULL, 0, 600, 100); a[2] = row("med", 0, NULL, 0, 600, 50);
    d[0] = row("d", 0, NULL, 0, 10000, 0);
    r = sel(a, 3, d, 1, 80, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 1 && strcmp(a[AO[0]].txid, "big") == 0 && r.assetChangeQty == 20,
       "picks largest asset utxos first");

    a[0] = row("a1", 0, NULL, 0, 600, 60); a[1] = row("a2", 0, NULL, 0, 600, 50);
    r = sel(a, 2, d, 1, 100, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 2 && r.assetChangeQty == 10, "combines multiple asset utxos when single isn't enough");

    a[0] = row("a", 0, NULL, 0, 600, 100);
    d[0] = row("small", 0, NULL, 0, 500, 0); d[1] = row("big", 0, NULL, 0, 100000, 0); d[2] = row("med", 0, NULL, 0, 5000, 0);
    r = sel(a, 1, d, 3, 50, 2000, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 1 && strcmp(d[DO_[0]].txid, "big") == 0, "picks largest dgb utxos first");
}

static void sel_insufficient(void)
{
    BRAssetRow a[1], d[1];
    BRAssetSelection r;

    a[0] = row("a", 0, NULL, 0, 600, 10); d[0] = row("d", 0, NULL, 0, 10000, 0);
    r = sel(a, 1, d, 1, 100, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_INSUFFICIENT_ASSET && r.required == 100 && r.available == 10, "insufficient asset returns error");

    a[0] = row("a", 0, NULL, 0, 600, 100); d[0] = row("d", 0, NULL, 0, 100, 0);
    r = sel(a, 1, d, 1, 50, 1000, 600, 0);
    ck(r.status == BR_ASSET_SELECT_INSUFFICIENT_DGB && r.required == 1000 && r.available == 100, "insufficient dgb fee returns error");
}

// ---- AssetCoinSelectorSecurityTest ---------------------------------------------------------------

static char SECA[8][20], SECD[16][20];

static BRAssetRow secAsset(int i, int64_t sats, int64_t qty) { snprintf(SECA[i], 20, "a%015x", i); return row(SECA[i], 0, NULL, 0, sats, qty); }
static BRAssetRow secDgb(int i, int64_t sats) { snprintf(SECD[i], 20, "d%015x", i); return row(SECD[i], 0, NULL, 0, sats, 0); }

static void sec_preconditions(void)
{
    BRAssetRow a[1], d[1];

    a[0] = secAsset(1, 700, 100); d[0] = secDgb(1, 10000);
    ck(sel(a, 1, d, 1, -1, 1000, 700, 0).status == BR_ASSET_SELECT_REFUSED, "negative assetNeeded rejected by precondition");
    ck(sel(a, 1, d, 1, 0, 1000, 700, 0).status == BR_ASSET_SELECT_REFUSED, "zero assetNeeded rejected by precondition");
    ck(sel(a, 1, d, 1, 50, -100, 700, 0).status == BR_ASSET_SELECT_REFUSED, "negative feeSats rejected by precondition");
    ck(sel(a, 1, d, 1, 50, 1000, -1, 0).status == BR_ASSET_SELECT_REFUSED, "negative markerOutputSats rejected by precondition");
    ck(sel(a, 1, d, 1, 50, 1000, 700, -1).status == BR_ASSET_SELECT_REFUSED, "negative assetChangeMarkerSats rejected by precondition");
}

static void sec_empty_lists(void)
{
    BRAssetRow a[1], d[1];

    d[0] = secDgb(1, 10000);
    ck(sel(NULL, 0, d, 1, 50, 1000, 700, 0).status == BR_ASSET_SELECT_INSUFFICIENT_ASSET, "no asset utxos returns InsufficientAsset");
    a[0] = secAsset(1, 700, 50);
    ck(sel(a, 1, NULL, 0, 50, 1000, 700, 0).status == BR_ASSET_SELECT_INSUFFICIENT_DGB, "enough asset but no DGB returns InsufficientDgb");
}

static void sec_overflow(void)
{
    BRAssetRow a[2], d[1];
    BRAssetSelection r;
    int64_t half = INT64_MAX / 2 + 1;

    a[0] = secAsset(1, 700, half); a[1] = secAsset(2, 700, half); d[0] = secDgb(1, 100000);
    r = sel(a, 2, d, 1, 1, 1000, 700, 0);
    ck(r.status != BR_ASSET_SELECT_REFUSED && (r.status != BR_ASSET_SELECT_OK || (r.assetChangeQty >= 0 && r.dgbChangeSats >= 0)),
       "huge asset quantity sum survives without overflow (status %d)", (int)r.status);
    a[0] = secAsset(1, 700, 100);
    ck(sel(a, 1, d, 1, INT64_MAX, 1000, 700, 0).status == BR_ASSET_SELECT_INSUFFICIENT_ASSET,
       "Long_MAX assetNeeded with insufficient asset returns InsufficientAsset");
}

static void sec_largest_first(void)
{
    BRAssetRow a[3], d[1];
    BRAssetSelection r;

    a[0] = secAsset(1, 700, 30); a[1] = secAsset(2, 700, 50); a[2] = secAsset(3, 700, 100); d[0] = secDgb(1, 100000);
    r = sel(a, 3, d, 1, 60, 1000, 700, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 1 && a[AO[0]].assetQuantity == 100 && r.assetChangeQty == 40,
       "selection picks largest-first to minimize input count");
}

static void sec_conservation(void)
{
    static const int64_t cases[][3] = { { 1, 1, 0 }, { 1, 2, 1 }, { 50, 100, 50 }, { 99, 100, 1 }, { 100, 100, 0 }, { 1, 1000000000, 999999999 } };
    size_t i;

    for (i = 0; i < N(cases); i++) {
        BRAssetRow a[1], d[1];
        BRAssetSelection r;
        int64_t totalIn = 0;
        size_t k;

        a[0] = secAsset(1, 700, cases[i][1]); d[0] = secDgb(1, 1000000);
        r = sel(a, 1, d, 1, cases[i][0], 1000, 1400, 0);
        for (k = 0; k < r.assetInputCount; k++) totalIn += a[AO[k]].assetQuantity;
        ck(r.status == BR_ASSET_SELECT_OK && cases[i][0] + r.assetChangeQty == totalIn && r.assetChangeQty == cases[i][2],
           "need=%lld bal=%lld: conserved, change %lld", (long long)cases[i][0], (long long)cases[i][1], (long long)r.assetChangeQty);
    }
}

static const char *SHARED = "5555555555555555555555555555555555555555555555555555555555555555";
static const char *SHARED_UPPER_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
static const char *SHARED_LOWER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static const char *D64 = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
static const char *E64 = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

static int combinedDistinct(const BRAssetSelection *r, const BRAssetRow *a, const BRAssetRow *d)
{
    BRAssetRow all[2 * MAXR];
    size_t n = 0, i;

    for (i = 0; i < r->assetInputCount; i++) all[n++] = a[AO[i]];
    for (i = 0; i < r->dgbInputCount; i++) all[n++] = d[DO_[i]];
    return BRAssetOutpointsDistinct(all, n);
}

static void sec_distinct(void)
{
    BRAssetRow a[1], d[13], three[3];
    BRAssetSelection r;
    BRAssetTransferPlan p;
    static char tx[12][65];
    size_t i;
    int shared = 0;

    a[0] = row(SHARED, 0, NULL, 0, 6000, 100);
    d[0] = row(SHARED, 0, NULL, 0, 6000, 0); d[1] = row(D64, 0, NULL, 0, 5000, 0); d[2] = row(E64, 0, NULL, 0, 4000, 0);
    r = sel(a, 1, d, 3, 100, 9000, 6000, 0);
    for (i = 0; i < r.dgbInputCount; i++) if (strcmp(d[DO_[i]].txid, SHARED) == 0) shared++;
    ck(r.status == BR_ASSET_SELECT_OK && shared == 0 && combinedDistinct(&r, a, d), "an outpoint in both pools appears once in the combined list");

    a[0] = row(SHARED_UPPER_A, 0, NULL, 0, 6000, 100);
    d[0] = row(SHARED_LOWER_A, 0, NULL, 0, 6000, 0);
    r = sel(a, 1, d, 3, 100, 9000, 6000, 0);
    ck(r.status == BR_ASSET_SELECT_OK && combinedDistinct(&r, a, d), "an outpoint spelled in another case is still the same outpoint");

    a[0] = row(SHARED, 0, NULL, 0, 30000, 100);
    d[0] = row(SHARED, 0, NULL, 0, 30000, 0);
    for (i = 0; i < 12; i++) { snprintf(tx[i], 65, "%064zx", i + 1); d[i + 1] = row(tx[i], 0, NULL, 0, 20000, 0); }
    p = plan(a, 1, d, 13, 100, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && BRAssetPlanInputsDistinct(&p, a, d), "the inputs a send signs name every outpoint once");

    three[0] = row(SHARED, 0, NULL, 0, 6000, 100); three[1] = row(D64, 0, NULL, 0, 5000, 0); three[2] = row(SHARED, 0, NULL, 0, 6000, 0);
    ck(! BRAssetOutpointsDistinct(three, 3), "the same outpoint twice was not called distinct");
    three[0] = row(SHARED_LOWER_A, 0, NULL, 0, 6000, 100);
    three[1] = row(SHARED_UPPER_A, 0, NULL, 0, 6000, 0);
    ck(! BRAssetOutpointsDistinct(three, 2), "the same outpoint in two spellings was not called distinct");

    a[0] = row(SHARED, 0, NULL, 0, 6000, 100);
    d[0] = row(SHARED, 1, NULL, 0, 50000, 0);
    r = sel(a, 1, d, 1, 100, 9000, 6000, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 1 && d[DO_[0]].vout == 1 && combinedDistinct(&r, a, d),
       "guard the same txid at another index is another outpoint");
}

// ---- AssetDustPolicyTest -----------------------------------------------------------------------

static void dust_marker(void)
{
    ck(BR_ASSET_MARKER_SATS > 5460 && BR_ASSET_MARKER_SATS > BR_DUST_P2PKH_SATS,
       "daMarker clears the legacy dust floor, worst-case recipient (%d > 5460)", BR_ASSET_MARKER_SATS);
    ck(BR_ASSET_MARKER_SATS > 2940 && BR_ASSET_MARKER_SATS > 3300, "daMarker clears segwit and taproot dust floors");
    ck(BR_ASSET_MARKER_SATS < 100000, "daMarker stays negligibly small");
    ck(BR_ASSET_MARKER_SATS == 6000 && BR_ASSET_CHANGE_DUST_SATS == 5460, "the values: 6,000 and 5,460");
}

// ---- AssetFeeEstimatorTest ---------------------------------------------------------------------

static int64_t vsizeFor(int64_t a, int64_t d, int64_t o, int64_t op) { return 12 + (a + d + 1) * 150 + o * 34 + 9 + op; }
static int64_t cdiv(int64_t n, int64_t d) { return (n + d - 1) / d; }
static int64_t fee(int32_t a, int32_t d, int32_t o, int32_t op, int64_t rate)
{
    int64_t f = -1;

    if (! BRAssetFeeEstimate(a, d, o, op, rate, &f)) return -1;
    return f;
}

static void fee_default(void)
{
    int64_t f = fee(1, 1, 3, 40, 100000), floor = cdiv(vsizeFor(1, 1, 3, 40) * 100000, 1000);

    ck(f == floor && f >= floor, "default fee for typical shape is the min relay floor (%lld)", (long long)f);
    ck(f >= 40000 && f <= 90000, "a plausible asset-tx fee (%lld)", (long long)f);
}

static void fee_linear(void)
{
    int64_t base = fee(1, 1, 3, 40, 100000);

    ck(fee(1, 1, 3, 40, 200000) == base * 2 && fee(1, 1, 3, 40, 300000) == base * 3, "fee scales linearly with fee rate");
}

static void fee_more(void)
{
    int64_t small = fee(1, 1, 2, 40, 100000);

    ck(fee(2, 3, 2, 40, 100000) > small, "more inputs must cost more");
    ck(fee(1, 1, 4, 40, 100000) > small, "more outputs must cost more");
    ck(fee(1, 1, 2, 120, 100000) > small, "larger OP_RETURN must cost more");
}

static void fee_floor(void)
{
    static const int64_t rates[] = { 0, 1000, 5000, 20000, 99999 };
    int64_t floor = cdiv(vsizeFor(1, 1, 3, 40) * 100000, 1000);
    size_t i;

    for (i = 0; i < N(rates); i++) ck(fee(1, 1, 3, 40, rates[i]) == floor, "rate %lld (< min relay) floors to min-relay fee", (long long)rates[i]);
    ck(fee(1, 1, 3, 40, 100000) == floor, "at exactly the min-relay rate, requested == floor");
}

static void fee_covers(void)
{
    int64_t trueVsize = 12 + (2 + 1) * 150 + 3 * 34 + 9 + 40, trueFloor = cdiv(trueVsize * 100000, 1000);

    ck(fee(2, 1, 3, 40, 100000) >= trueFloor, "estimate for N inputs covers the true fee for N inputs");
}

// ---- AssetTransferPlanTest ----------------------------------------------------------------------

static const int ROLES4[4] = { BR_ASSET_ROLE_RECIPIENT_MARKER, BR_ASSET_ROLE_ASSET_DATA, BR_ASSET_ROLE_DGB_CHANGE, BR_ASSET_ROLE_ASSET_CHANGE_MARKER };
static const int ROLES3[3] = { BR_ASSET_ROLE_RECIPIENT_MARKER, BR_ASSET_ROLE_ASSET_DATA, BR_ASSET_ROLE_ASSET_CHANGE_MARKER };

static void plan_exact(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;

    a[0] = row("a", 0, NULL, 0, BR_ASSET_MARKER_SATS, 100); d[0] = row("d", 0, NULL, 0, 1000000, 0);
    p = plan(a, 1, d, 1, 100, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && rolesAre(&p, ROLES4, 4), "an exact send emits both markers and the change");
    ck(p.estimatedFeeSats == sumInSats(&p, a, d) - 2 * BR_ASSET_MARKER_SATS - roleSats(&p, BR_ASSET_ROLE_DGB_CHANGE),
       "inputs - two markers - change is the fee");
    ck(p.estimatedFeeSats == p.paidFeeSats, "an exact send pays the fee it estimated (%lld)", (long long)p.paidFeeSats);
}

static void plan_exact_no_fee_input(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;

    a[0] = row("a", 0, NULL, 0, 200000, 100); d[0] = row("d", 0, NULL, 0, 1000000, 0);
    p = plan(a, 1, d, 1, 100, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && p.dgbInputCount == 0, "no fee input is needed");
    ck(p.estimatedFeeSats == p.paidFeeSats, "an exact send with no fee input pays the fee it estimated");
}

static void plan_dust_remainder(void)
{
    BRAssetRow a[1], d[2];
    BRAssetTransferPlan p;
    int64_t total;
    int found = 0;

    a[0] = row("a", 0, NULL, 0, BR_ASSET_MARKER_SATS, 100);
    for (total = 50000; total <= 120000 && ! found; total += 50) {
        d[0] = row("d1", 0, NULL, 0, total - 10000, 0); d[1] = row("d2", 0, NULL, 0, 10000, 0);
        p = plan(a, 1, d, 2, 100, 100000);
        if (p.status == BR_ASSET_PLAN_READY && roleCount(&p, BR_ASSET_ROLE_DGB_CHANGE) == 0) found = 1;
    }
    ck(found, "a coin value gives a dust remainder (total %lld)", (long long)(total - 50));
    if (! found) return;
    ck(rolesAre(&p, ROLES3, 3), "the outputs are recipient, data, last marker");
    ck(p.paidFeeSats - p.estimatedFeeSats >= 0 && p.paidFeeSats - p.estimatedFeeSats <= BR_ASSET_CHANGE_DUST_SATS,
       "remainder %lld", (long long)(p.paidFeeSats - p.estimatedFeeSats));
}

static void plan_partial(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;

    a[0] = row("a", 0, NULL, 0, BR_ASSET_MARKER_SATS, 100); d[0] = row("d", 0, NULL, 0, 1000000, 0);
    p = plan(a, 1, d, 1, 40, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && rolesAre(&p, ROLES4, 4), "a partial send emits both markers and the change");
    ck(roleSats(&p, BR_ASSET_ROLE_ASSET_CHANGE_MARKER) == BR_ASSET_MARKER_SATS, "the last marker is DA_MARKER_SATS");
    ck(p.estimatedFeeSats == p.paidFeeSats, "and pays the fee it estimated");
}

static void plan_consolidating(void)
{
    BRAssetRow a[8], d[1];
    BRAssetTransferPlan p;
    static char tx[8][4];
    int i;

    for (i = 0; i < 8; i++) { snprintf(tx[i], 4, "a%d", i + 1); a[i] = row(tx[i], 0, NULL, 0, 600, 1); }
    d[0] = row("f", 0, NULL, 0, 5000000, 0);
    p = plan(a, 8, d, 1, 8, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && p.paidFeeSats > 2 * (613LL * 100000 / 1000), "paid %lld vs flat 61300", (long long)p.paidFeeSats);
    ck(p.estimatedFeeSats == p.paidFeeSats, "a consolidating send pays the fee it estimated");
}

static void plan_approval(void)
{
    ck(BRAssetFeeWithinApproval(161200, 161200), "paid at the approval is signed");
    ck(BRAssetFeeWithinApproval(61300, 161200), "paid under the approval is signed");
    ck(! BRAssetFeeWithinApproval(161200, 61300), "paid above the approval is not");
    ck(! BRAssetFeeWithinApproval(1, 0), "a zero approval approves nothing");
    ck(! BRAssetFeeWithinApproval(-1, 100), "a negative fee is not signed");
}

// ---- AssetSendSelectionTest -------------------------------------------------------------------

static const uint8_t SX[2] = { 1, 1 }, SY[2] = { 2, 2 };

static BRAssetRow srow(const char *txid, int64_t qty, const uint8_t *script)
{
    BRAssetRow r = row(txid, 0, script ? script : SX, 2, BR_ASSET_MARKER_SATS, qty);

    r.blockHeight = 1;
    return r;
}

static BRAssetRow sfee(const char *txid, const uint8_t *script)
{
    BRAssetRow r = row(txid, 0, script ? script : SX, 2, 1000000, 0);

    r.blockHeight = 1;
    return r;
}

typedef struct { HProc p; HBuf b; Source s; } World;

static World *world(void)
{
    static World w;

    memset(&w, 0, sizeof(w));
    return &w;
}

static BRAssetSendOutcome chooseIn(World *w, const BRAssetRow *rows, size_t n, int64_t qty, const BRAssetRow *fees, size_t nf,
                                   ParentFn parent, int hasSource)
{
    return hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, hasSource, &w->s, ASSET, rows, n, fees, nf, parent, qty, 100000);
}

static BRAssetSendOutcome choose1(World *w, const BRAssetRow *rows, size_t n, int64_t qty)
{
    BRAssetRow f[1];

    f[0] = sfee("f", NULL);
    return chooseIn(w, rows, n, qty, f, 1, parentNo, 1);
}

static void choose_judge(void)
{
    BRAssetUnits e[2];

#define J(lk, n, q) BRAssetInputJudge(lk, e, n, ASSET, q)
    e[0].assetId = ASSET; e[0].count = 5;
    ck(J(BR_ASSET_STACK_FOUND, 1, 5) == BR_ASSET_INPUT_VERIFIED, "exactly the asset at the recorded quantity is VERIFIED");
    ck(J(BR_ASSET_STACK_FOUND, 1, 4) == BR_ASSET_INPUT_MISMATCH, "another quantity is MISMATCH");
    e[1].assetId = OTHER; e[1].count = 1;
    ck(J(BR_ASSET_STACK_FOUND, 2, 5) == BR_ASSET_INPUT_MISMATCH, "a second entry after it is MISMATCH");
    e[0].assetId = OTHER; e[0].count = 1; e[1].assetId = ASSET; e[1].count = 5;
    ck(J(BR_ASSET_STACK_FOUND, 2, 5) == BR_ASSET_INPUT_MISMATCH, "a second entry before it is MISMATCH");
    e[0].assetId = OTHER; e[0].count = 5;
    ck(J(BR_ASSET_STACK_FOUND, 1, 5) == BR_ASSET_INPUT_MISMATCH, "another asset is MISMATCH");
    ck(J(BR_ASSET_STACK_FOUND, 0, 5) == BR_ASSET_INPUT_MISMATCH, "an empty stack is MISMATCH");
    e[0].assetId = ASSET; e[0].count = 0;
    ck(J(BR_ASSET_STACK_FOUND, 1, 0) == BR_ASSET_INPUT_MISMATCH, "a recorded quantity of zero is MISMATCH");
    ck(J(BR_ASSET_STACK_NOT_UNSPENT, 0, 5) == BR_ASSET_INPUT_MISMATCH, "not unspent is MISMATCH");
    ck(J(BR_ASSET_STACK_UNAVAILABLE, 0, 5) == BR_ASSET_INPUT_UNAVAILABLE, "no answer is UNAVAILABLE");
#undef J
}

static int plannedTxids(const BRAssetSendOutcome *o, const BRAssetRow *rows, const char *const *want, size_t n)
{
    size_t i;

    if (o->kind != BR_ASSET_SEND_PLANNED || o->plan.assetInputCount != n) return 0;
    for (i = 0; i < n; i++) if (strcmp(rows[o->plan.assetInputs[i]].txid, want[i]) != 0) return 0;
    return 1;
}

static void choose_confirmed_planned(void)
{
    World *w = world();
    BRAssetRow r[1];
    static const char *const want[] = { "a" };
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
    r[0] = srow("a", 5, NULL);
    o = choose1(w, r, 1, 3);
    ck(plannedTxids(&o, r, want, 1), "confirmed inputs are planned (kind %d)", (int)o.kind);
}

static void choose_overflow_rows(void)
{
    World *w = world();
    BRAssetRow r[2];
    int64_t big = 5000000000000000000LL;
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = found("a", ASSET, big, NULL, 0);
    w->s.s[w->s.n++] = found("b", ASSET, big, NULL, 0);
    r[0] = srow("a", big, NULL); r[1] = srow("b", big, SY);
    o = choose1(w, r, 2, 4996000000000000000LL);
    ck(o.kind == BR_ASSET_SEND_PLANNED, "rows whose sum overflows do not block the send (kind %d)", (int)o.kind);
}

static void choose_overflow_transfer(void)
{
    World *w = world();
    BRAssetRow r[2];
    int64_t big = 5000000000000000000LL;
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = found("a", ASSET, big, NULL, 0);
    w->s.s[w->s.n++] = found("b", ASSET, big, NULL, 0);
    r[0] = srow("a", big, NULL); r[1] = srow("b", big, NULL);
    o = choose1(w, r, 2, big + 1);
    ck(o.kind != BR_ASSET_SEND_PLANNED, "a transfer whose inputs overflow is never planned (kind %d, why %d)", (int)o.kind, (int)o.plan.why);
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_INSTRUCTIONS, "it could not build its instructions");
}

static void choose_set_aside(void)
{
    World *w = world();
    BRAssetRow r[2];
    static const char *const want[] = { "small" };
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = found("big", OTHER, 9, NULL, 0);
    w->s.s[w->s.n++] = found("small", ASSET, 4, NULL, 0);
    r[0] = srow("big", 9, NULL); r[1] = srow("small", 4, NULL);
    o = choose1(w, r, 2, 3);
    ck(plannedTxids(&o, r, want, 1), "an input holding something else is set aside and the send uses another");
}

static void choose_unconfirmed(void)
{
    World *w = world();
    BRAssetRow r[1];

    w->s.s[w->s.n++] = found("a", OTHER, 5, NULL, 0);
    r[0] = srow("a", 5, NULL);
    ck(choose1(w, r, 1, 3).kind == BR_ASSET_SEND_UNCONFIRMED, "when no confirmed inputs cover the amount nothing is planned");
}

static void choose_no_answer(void)
{
    World *w = world();
    BRAssetRow r[1], f[1];

    r[0] = srow("a", 5, NULL);
    f[0] = sfee("f", NULL);
    ck(choose1(w, r, 1, 3).kind == BR_ASSET_SEND_UNVERIFIED, "no answer stops the send");
    w = world();
    ck(chooseIn(w, r, 1, 3, f, 1, parentNo, 0).kind == BR_ASSET_SEND_UNVERIFIED, "no indexer stops the send");
}

static void choose_spent_set_aside(void)
{
    World *w = world();
    BRAssetRow r[2];
    static const char *const want[] = { "b" };
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = notUnspent("a");
    w->s.s[w->s.n++] = found("b", ASSET, 5, NULL, 0);
    r[0] = srow("a", 9, NULL); r[1] = srow("b", 5, NULL);
    o = choose1(w, r, 2, 3);
    ck(plannedTxids(&o, r, want, 1), "an output the indexer reports spent is left out");
}

static void choose_spans(void)
{
    World *w = world();
    BRAssetRow r[2];
    BRAssetSendOutcome o;

    w->s.s[w->s.n++] = found("a", ASSET, 3, NULL, 0);
    w->s.s[w->s.n++] = found("b", ASSET, 2, NULL, 0);
    r[0] = srow("a", 3, SX); r[1] = srow("b", 2, SY);
    o = choose1(w, r, 2, 4);
    ck(o.kind == BR_ASSET_SEND_SPANS_ADDRESSES && o.largestSingleSource == 3, "an amount spread over two addresses is refused with the largest single amount");
    ck(w->s.nAsked == 0, "nothing is asked before a plan exists");
}

static void choose_one_address(void)
{
    World *w = world();
    BRAssetRow r[3];
    BRAssetSendOutcome o;
    size_t i;
    int ok = 1;

    w->s.s[w->s.n++] = found("a1", ASSET, 2, NULL, 0);
    w->s.s[w->s.n++] = found("a2", ASSET, 2, NULL, 0);
    w->s.s[w->s.n++] = found("b", ASSET, 1, NULL, 0);
    r[0] = srow("a1", 2, SX); r[1] = srow("a2", 2, SX); r[2] = srow("b", 1, SY);
    o = choose1(w, r, 3, 3);
    ck(o.kind == BR_ASSET_SEND_PLANNED, "planned");
    for (i = 0; i < o.plan.assetInputCount; i++) if (memcmp(r[o.plan.assetInputs[i]].script, SX, 2) != 0) ok = 0;
    ck(ok && o.plan.sourceScriptLen == 2 && memcmp(o.plan.sourceScript, SX, 2) == 0, "inputs come from one address and change returns there");
}

static void choose_fee_coins_data(void)
{
    World *w = world();
    BRAssetRow r[1], f[2];
    BRAssetSendOutcome o;
    size_t i;
    int ok = 1;

    w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
    r[0] = srow("a", 5, NULL);
    f[0] = sfee("plain", SY); f[1] = sfee("fromAssetTx", SY);
    o = chooseIn(w, r, 1, 3, f, 2, parentFromAssetTx, 1);
    ck(o.kind == BR_ASSET_SEND_PLANNED, "planned");
    for (i = 0; i < o.plan.dgbInputCount; i++) if (strcmp(f[o.plan.dgbInputs[i]].txid, "fromAssetTx") == 0) ok = 0;
    ck(ok, "fee coins from elsewhere must come from transactions without data");
}

static void choose_fee_rule_note(void)
{
    World *w = world();
    BRAssetRow r[1], f[1];
    BRAssetSendOutcome o;
    char m[400];

    w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
    r[0] = srow("a", 5, NULL);
    f[0] = sfee("fromAssetTx", SY);
    o = chooseIn(w, r, 1, 3, f, 1, parentYes, 1);
    chooseMessage(&o, ASSET, m, sizeof(m));
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_NOT_ENOUGH_DGB && o.feeCoinRule, "a shortfall caused by the fee coin rule says so");
    ck(strstr(m, "ordinary payments") != NULL, "its message names ordinary payments: %s", m);
}

static void choose_not_asked_again(void)
{
    World *w = world();
    BRAssetRow r[1];

    w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
    r[0] = srow("a", 5, NULL);
    choose1(w, r, 1, 3);
    choose1(w, r, 1, 2);
    ck(w->s.nAsked == 1 && strcmp(w->s.asked[0], "a:0") == 0, "a confirmed output is not asked again in the same process (%zu asks)", w->s.nAsked);
}

static void choose_not_yet_asked_later(void)
{
    World *w = world();
    BRAssetRow r[1];
    size_t i;
    int wrongAside = 0, newAside = 0;

    w->s.s[w->s.n++] = notUnspent("new");
    w->s.s[w->s.n++] = found("wrong", OTHER, 9, NULL, 0);
    r[0] = srow("new", 5, NULL);
    ck(choose1(w, r, 1, 3).kind == BR_ASSET_SEND_UNCONFIRMED, "an output the indexer has not seen yet is left out");
    w->s.s[0] = found("new", ASSET, 5, NULL, 0);
    ck(choose1(w, r, 1, 3).kind == BR_ASSET_SEND_PLANNED, "once the indexer reports it, a later send uses it");
    r[0] = srow("wrong", 9, NULL);
    ck(choose1(w, r, 1, 3).kind == BR_ASSET_SEND_UNCONFIRMED, "an output holding something else is not used");
    for (i = 0; i < w->p.na; i++) {
        if (strcmp(w->p.aside[i].txid, "wrong") == 0) wrongAside = 1;
        if (strcmp(w->p.aside[i].txid, "new") == 0) newAside = 1;
    }
    ck(wrongAside, "held as set aside");
    ck(! newAside, "not held as set aside");
}

// ---- AssetSendSourceAddressTest -----------------------------------------------------------------

static const uint8_t SOURCE[5] = { 0x00, 0x14, 1, 2, 3 }, ELSEWHERE[5] = { 0x00, 0x14, 9, 9, 9 }, SEVEN[1] = { 7 };
#define OTHER_MODEL "Ua7Q1otherAssetIdForTheModel000000000"

static BRAssetRow arow(const char *txid, int64_t qty, const uint8_t *script)
{
    return row(txid, 0, script ? script : SOURCE, script == SEVEN ? 1 : 5, BR_ASSET_MARKER_SATS, qty);
}

static BRAssetRow fcoin(const char *txid, const uint8_t *script)
{
    return row(txid, 0, script, 5, 1000000, 0);
}

typedef struct { const char *txid; Ent e[4]; size_t n; } Holding;

// The plan's inputs, outputs and instructions run through DigiAssetTransferModel, as the Kotlin test does.
static TResult runModel(const BRAssetTransferPlan *p, const BRAssetRow *a, const BRAssetRow *d, const Holding *stacks, size_t ns,
                        const char *const *ruleBearing, size_t nr)
{
    static char addr[8][32];
    const char *outs[8];
    TInput ins[16];
    TInst l[BR_ASSET_MAX_INSTRUCTIONS];
    BRAssetTransferInstruction dl[BR_ASSET_MAX_INSTRUCTIONS];
    BRAssetHeader h;
    size_t i, j, k, ni = 0;

    snprintf(addr[0], 32, "%s", hexOf(p->sourceScript, p->sourceScriptLen));
    for (i = 0; i < p->outputCount; i++) {
        if (p->outputs[i].role == BR_ASSET_ROLE_RECIPIENT_MARKER) outs[i] = "recipient";
        else if (p->outputs[i].role == BR_ASSET_ROLE_ASSET_DATA) outs[i] = "";
        else outs[i] = addr[0];
    }
    for (i = 0; i < p->assetInputCount + p->dgbInputCount; i++) {
        const BRAssetRow *r = i < p->assetInputCount ? &a[p->assetInputs[i]] : &d[p->dgbInputs[i - p->assetInputCount]];

        snprintf(addr[1 + ni], 32, "%s", hexOf(r->script, r->scriptLen));
        ins[ni].address = addr[1 + ni];
        ins[ni].n = 0;
        for (j = 0; j < ns; j++) {
            if (strcmp(stacks[j].txid, r->txid) == 0) {
                for (k = 0; k < stacks[j].n; k++) ins[ni].e[k] = stacks[j].e[k];
                ins[ni].n = stacks[j].n;
            }
        }
        ni++;
    }
    if (! decode(p->opReturnScript, p->opReturnLen, &h, dl)) { TResult z; memset(&z, 0, sizeof(z)); ck(0, "the plan's carrier decodes"); return z; }
    for (i = 0; i < h.instructionCount; i++) { l[i].skip = dl[i].skip; l[i].output = dl[i].outputIndex; l[i].amount = dl[i].amount; }
    return tApply(ins, ni, outs, p->outputCount, l, h.instructionCount, ruleBearing, nr);
}

static int outIs(const TResult *r, size_t o, const char *id1, int64_t n1, const char *id2, int64_t n2)
{
    size_t want = (id1 ? 1 : 0) + (id2 ? 1 : 0);

    if (r->n[o] != want) return 0;
    if (id1 && (strcmp(r->out[o][0].id, id1) != 0 || r->out[o][0].n != n1)) return 0;
    if (id2 && (strcmp(r->out[o][1].id, id2) != 0 || r->out[o][1].n != n2)) return 0;
    return 1;
}

static const char *const RULE_BEARING[] = { OTHER_MODEL };

static void src_unassigned_stay(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    Holding h[1] = { { "p", { { ASSET, 5 }, { OTHER_MODEL, 3 } }, 2 } };
    TResult r;

    a[0] = arow("p", 5, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 2, 100000);
    r = runModel(&p, a, d, h, 1, RULE_BEARING, 1);
    ck(p.status == BR_ASSET_PLAN_READY && ! r.ruleCheckFailed, "no rule check fails");
    ck(outIs(&r, 0, ASSET, 2, NULL, 0), "the recipient gets 2");
    ck(outIs(&r, p.outputCount - 1, ASSET, 3, OTHER_MODEL, 3), "units the instructions do not assign stay at the source");
    ck(p.outputs[p.outputCount - 1].role == BR_ASSET_ROLE_ASSET_CHANGE_MARKER && p.sourceScriptLen == 5 &&
       memcmp(p.sourceScript, SOURCE, 5) == 0, "the last output pays the source");
}

static void src_red_previous_layout(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    TInput ins[2];
    static const char *const oldAddresses[] = { "recipient", "", "asset-change-address", "dgb-change-address" };
    TInst old[2] = { { 0, 0, 2 }, { 0, 2, 3 } };
    TResult r;
    size_t i;
    static char ad[2][32];

    a[0] = arow("p", 5, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 2, 100000);
    for (i = 0; i < p.assetInputCount + p.dgbInputCount && i < 2; i++) {
        const BRAssetRow *rw = i < p.assetInputCount ? &a[p.assetInputs[i]] : &d[p.dgbInputs[i - p.assetInputCount]];

        snprintf(ad[i], 32, "%s", hexOf(rw->script, rw->scriptLen));
        ins[i].address = ad[i];
        ins[i].n = 0;
        if (strcmp(rw->txid, "p") == 0) {
            ins[i].e[0].id = ASSET; ins[i].e[0].n = 5; ins[i].e[1].id = OTHER_MODEL; ins[i].e[1].n = 3; ins[i].n = 2;
        }
    }
    r = tApply(ins, i, oldAddresses, 4, old, 2, RULE_BEARING, 1);
    ck(r.ruleCheckFailed, "red: the previous layout moves unassigned units away from the source");
}

static void src_whole_holding(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    Holding h[1] = { { "p", { { ASSET, 5 }, { OTHER_MODEL, 3 } }, 2 } };
    TResult r;

    a[0] = arow("p", 5, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 5, 100000);
    r = runModel(&p, a, d, h, 1, RULE_BEARING, 1);
    ck(! r.ruleCheckFailed && outIs(&r, 0, ASSET, 5, NULL, 0) && outIs(&r, p.outputCount - 1, OTHER_MODEL, 3, NULL, 0),
       "sending the whole holding keeps other entries at the source");
}

static void src_quantity_above_entry(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    Holding h[1] = { { "p", { { ASSET, 5 }, { OTHER_MODEL, 3 } }, 2 } };
    TResult r;

    a[0] = arow("p", 8, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 8, 100000);
    r = runModel(&p, a, d, h, 1, RULE_BEARING, 1);
    ck(! r.ruleCheckFailed && r.n[0] == 0 && outIs(&r, p.outputCount - 1, ASSET, 5, OTHER_MODEL, 3),
       "a quantity above the first entry returns everything to the source");
}

static void src_first_entry_other(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    Holding h[1] = { { "p", { { OTHER_MODEL, 3 } }, 1 } };
    BRAssetUnits e[1] = { { OTHER_MODEL, 3, 0 } };
    TResult r;

    a[0] = arow("p", 3, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 3, 100000);
    r = runModel(&p, a, d, h, 1, RULE_BEARING, 1);
    ck(r.ruleCheckFailed, "the layout alone does not keep this input's entry at the source");
    ck(BRAssetInputJudge(BR_ASSET_STACK_FOUND, e, 1, ASSET, 3) == BR_ASSET_INPUT_MISMATCH, "and the input check refuses exactly this input");
}

static void src_fee_coin_elsewhere(void)
{
    BRAssetRow a[1], d[1], z, x;
    BRAssetTransferPlan p;
    Holding h[2] = { { "p", { { ASSET, 5 } }, 1 }, { "z", { { OTHER_MODEL, 1 } }, 1 } };
    TResult r;

    a[0] = arow("p", 5, NULL); d[0] = fcoin("z", ELSEWHERE);
    p = plan(a, 1, d, 1, 2, 100000);
    r = runModel(&p, a, d, h, 2, RULE_BEARING, 1);
    ck(r.ruleCheckFailed, "an entry on a fee coin from elsewhere arrives at the source");
    z = fcoin("z", ELSEWHERE); x = fcoin("x", SOURCE);
    ck(! BRAssetFeeCoinEligible(z.script, z.scriptLen, SOURCE, 5, BR_ASSET_FACT_YES), "from elsewhere, parent with data: not eligible");
    ck(! BRAssetFeeCoinEligible(z.script, z.scriptLen, SOURCE, 5, BR_ASSET_FACT_UNKNOWN), "from elsewhere, parent unread: not eligible");
    ck(BRAssetFeeCoinEligible(z.script, z.scriptLen, SOURCE, 5, BR_ASSET_FACT_NO), "from elsewhere, parent without data: eligible");
    ck(BRAssetFeeCoinEligible(x.script, x.scriptLen, SOURCE, 5, BR_ASSET_FACT_YES), "at the source: eligible");
}

static void src_fee_coin_at_source(void)
{
    BRAssetRow a[1], d[1];
    BRAssetTransferPlan p;
    Holding h[2] = { { "p", { { ASSET, 5 } }, 1 }, { "f", { { OTHER_MODEL, 1 } }, 1 } };
    TResult r;
    size_t i;
    int has = 0;

    a[0] = arow("p", 5, NULL); d[0] = fcoin("f", SOURCE);
    p = plan(a, 1, d, 1, 2, 100000);
    r = runModel(&p, a, d, h, 2, RULE_BEARING, 1);
    for (i = 0; i < r.n[p.outputCount - 1]; i++) if (strcmp(r.out[p.outputCount - 1][i].id, OTHER_MODEL) == 0 && r.out[p.outputCount - 1][i].n == 1) has = 1;
    ck(! r.ruleCheckFailed && has, "a fee coin at the source keeps its entries there");
}

static void src_last_output(void)
{
    static const int64_t qtys[2] = { 2, 5 };
    size_t q, i;

    for (q = 0; q < 2; q++) {
        BRAssetRow a[1], d[1];
        BRAssetTransferPlan p;
        BRAssetTransferInstruction dl[BR_ASSET_MAX_INSTRUCTIONS];
        BRAssetHeader h;
        int ok = 1;

        a[0] = arow("p", 5, NULL); d[0] = fcoin("f", SOURCE);
        p = plan(a, 1, d, 1, qtys[q], 100000);
        ck(p.outputs[p.outputCount - 1].role == BR_ASSET_ROLE_ASSET_CHANGE_MARKER, "qty %lld: the last output is the source's marker", (long long)qtys[q]);
        if (! decode(p.opReturnScript, p.opReturnLen, &h, dl)) ok = 0;
        for (i = 0; ok && i < h.instructionCount; i++) if (dl[i].outputIndex != 0 && dl[i].outputIndex != (int32_t)p.outputCount - 1) ok = 0;
        ck(ok, "qty %lld: the change instruction names the last output", (long long)qtys[q]);
    }
}

static void src_two_addresses(void)
{
    BRAssetRow a[2], d[1];
    BRAssetTransferPlan p;

    a[0] = arow("p", 2, NULL); a[1] = arow("q", 2, ELSEWHERE); d[0] = fcoin("f", SOURCE);
    p = plan(a, 2, d, 1, 3, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_MIXED_ADDRESSES, "asset inputs from two addresses are refused");
}

static void src_groups(void)
{
    BRAssetRow r[4];
    BRAssetSourceGroup g[4];
    size_t n = 0, i;
    int ok;

    r[0] = arow("a", 2, NULL); r[1] = arow("b", 3, NULL); r[2] = arow("c", 4, ELSEWHERE); r[3] = arow("d", 1, SEVEN);
    ok = BRAssetSourceGroups(r, 4, 4, g, 4, &n);
    ck(ok && n == 2 && g[0].total == 5 && g[0].rowCount == 2 && g[1].total == 4 && g[1].rowCount == 1, "two groups cover 4: {a, b} then {c}");
    if (n == 2) {
        int a0 = BRAssetSourceGroupHas(r, &g[0], 0), b0 = BRAssetSourceGroupHas(r, &g[0], 1), c1 = BRAssetSourceGroupHas(r, &g[1], 2);
        ck(a0 && b0 && ! BRAssetSourceGroupHas(r, &g[0], 2) && c1 && ! BRAssetSourceGroupHas(r, &g[1], 3), "their rows");
    }
    ck(BRAssetLargestSingleSource(r, 4) == 5, "the largest single source is 5");
    ck(BRAssetSourceGroups(r, 4, 6, g, 4, &n) && n == 0, "no group covers 6");
    (void)i;
}

// ---- AssetQuantityTest --------------------------------------------------------------------------

static int64_t qp(const char *t, int div)
{
    int64_t u = -1;

    return BRAssetAmountParse(t, strlen(t), div, &u) ? u : -1;   // -1: null
}

#define QEQ(text, div, want) ck(qp(text, div) == (want), "parse(\"%s\", %d) = %lld (%lld)", text, div, (long long)(want), (long long)qp(text, div))
#define QNULL(text, div) ck(qp(text, div) == -1, "parse(\"%.40s\", %d) is null", text, div)

static void qty_div0(void) { QEQ("1", 0, 1); QEQ("100", 0, 100); QEQ("0100", 0, 100); QEQ(" 3 ", 0, 3); QEQ("1234567", 0, 1234567); }

static void qty_div2(void)
{
    QEQ("1", 2, 100); QEQ("1.5", 2, 150); QEQ("1.50", 2, 150); QEQ("01.5", 2, 150); QEQ("0.01", 2, 1); QEQ("0.29", 2, 29);
    QEQ(".5", 2, 50); QEQ(" 3 ", 2, 300);
}

static void qty_div8(void)
{
    QEQ("1", 8, 100000000); QEQ("0.00000001", 8, 1); QEQ("0.29", 8, 29000000); QEQ("1234.5", 8, 123450000000LL); QEQ(" 3 ", 8, 300000000);
}

static void qty_separators(void)
{
    QEQ("1,5", 2, 150); QEQ("1,5", 1, 15); QEQ("1,5", 8, 150000000); QEQ("1,234.5", 2, 123450); QEQ("1,234,567.5", 2, 123456750);
    QEQ("1,234", 3, 1234); QNULL("1,234", 2); QNULL("1,5", 0); QNULL("1,234", 0); QNULL("1,2,3", 2); QNULL("1,234,567", 0);
}

static void qty_refused_forms(void)
{
    static const char *const texts[] = { "1e2", "1E2", "1e0", "1e-2", "NaN", "Infinity", "+1", "-1", "-0", "1.5f", "0x10", "1_000" };
    static const int divs[] = { 0, 2, 8 };
    size_t i, j;

    for (j = 0; j < N(divs); j++) for (i = 0; i < N(texts); i++) QNULL(texts[i], divs[j]);
}

static void qty_zero(void)
{
    QNULL("0", 0); QNULL("00", 0); QNULL("0", 2); QNULL("0.0", 2); QNULL("0.00", 2); QNULL("0,00", 2); QNULL("0.00000000", 8);
}

static void qty_decimals(void)
{
    QNULL("1.5", 0); QNULL("1.0", 0); QNULL("1.234", 2); QNULL("1.500", 2); QNULL("0.001", 2); QNULL("0.000000001", 8); QNULL("1.000000000", 8);
}

static void qty_empty(void)
{
    static const char *const texts[] = { "", "   ", ".", ",", "abc", "1.2.3", "1 000", "5 tokens", "#5" };
    static const int divs[] = { 0, 2, 8 };
    size_t i, j;

    for (j = 0; j < N(divs); j++) for (i = 0; i < N(texts); i++) QNULL(texts[i], divs[j]);
}

static char BIG[100002];

static void qty_range(void)
{
    QEQ("9223372036854775807", 0, INT64_MAX); QNULL("9223372036854775808", 0);
    QEQ("92233720368547758.07", 2, INT64_MAX); QNULL("92233720368547758.08", 2);
    QEQ("92233720368.54775807", 8, INT64_MAX); QNULL("92233720368.54775808", 8);
    QNULL("99999999999999999999", 0);
    memset(BIG, '9', 100000); BIG[100000] = '\0';
    QNULL(BIG, 0); QNULL(BIG, 8);
}

static void qty_length(void)
{
    static const int divs[] = { 0, 2, 8 };
    char t[1100];
    size_t i;

    memset(t, '0', 31); t[31] = '1'; t[32] = '\0';
    ck(strlen(t) == 32, "the longest text is 32");
    QEQ(t, 0, 1);
    memset(t, '0', 32); t[32] = '1'; t[33] = '\0';
    QNULL(t, 0);
    memset(t, '0', 29); strcpy(t + 29, "1.5"); QEQ(t, 2, 150);
    memset(t, '0', 30); strcpy(t + 30, "1.5"); QNULL(t, 2);
    memset(t, '0', 23); strcpy(t + 23, "1.0000000"); QEQ(t, 8, 100000000);
    memset(t, '0', 24); strcpy(t + 24, "1.0000000"); QNULL(t, 8);
    // The limit is counted after the grouping commas are dropped (a dot is typed), however many there are.
    strcpy(t, "00,000,000,000,000,000,000,000,000,001.50");   // 9 commas: 41 typed, 32 read
    QEQ(t, 2, 150);
    strcpy(t, "000,000,000,000,000,000,000,000,000,001.50");  // one more leading zero: 33 read
    QNULL(t, 2);
    strcpy(t, "00,000,000,000,000,000,000,001.50000000");     // 7 commas: 39 typed, 32 read
    QEQ(t, 8, 150000000);
    strcpy(t, "000,000,000,000,000,000,000,001.50000000");
    QNULL(t, 8);
    memset(t, '0', 1000); strcpy(t + 1000, "1");
    for (i = 0; i < N(divs); i++) QNULL(t, divs[i]);
}

// c as UTF-8 into out (room for 4 bytes): its length.
static size_t utf8Of(uint32_t c, char *out)
{
    if (c < 0x80) { out[0] = (char)c; return 1; }
    if (c < 0x800) { out[0] = (char)(0xc0 | (c >> 6)); out[1] = (char)(0x80 | (c & 0x3f)); return 2; }
    out[0] = (char)(0xe0 | (c >> 12)); out[1] = (char)(0x80 | ((c >> 6) & 0x3f)); out[2] = (char)(0x80 | (c & 0x3f));
    return 3;
}

// Every code point Kotlin's Char.isWhitespace takes (Character.isWhitespace or isSpaceChar), and three
// it does not: U+180E (a format character since Unicode 6.3), U+200B and U+FEFF.
static const uint32_t TRIMMED[] = { 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa0, 0x1680, 0x2000, 0x2001,
                                    0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029,
                                    0x202f, 0x205f, 0x3000 };
static const uint32_t NOT_TRIMMED[] = { 0x180e, 0x200b, 0xfeff };

// Each code point of the trim, before and after a digit, is trimmed; each of the others refuses the text.
static void qty_whitespace(void)
{
    char t[16], h[16];
    size_t i, n, k;
    int64_t u;

    ck(N(TRIMMED) == 28, "the trim takes 28 code points");
    for (i = 0; i < N(TRIMMED) + N(NOT_TRIMMED); i++) {
        uint32_t c = i < N(TRIMMED) ? TRIMMED[i] : NOT_TRIMMED[i - N(TRIMMED)];
        int trims = i < N(TRIMMED);
        char w[4];

        n = utf8Of(c, w);
        ck(mIsWhitespace(c) == trims, "the model's trim and this list agree on U+%04X", (unsigned)c);
        for (k = 0; k < 2; k++) {   // leading, then trailing
            size_t len = 0, j;

            if (k == 0) { memcpy(t, w, n); len = n; t[len++] = '5'; }
            else { t[len++] = '5'; memcpy(t + len, w, n); len += n; }
            t[len] = '\0';
            for (j = 0; j < len; j++) snprintf(h + 2 * j, sizeof(h) - 2 * j, "%02x", (unsigned char)t[j]);
            u = -1;
            if (trims) ck(BRAssetAmountParse(t, len, 0, &u) && u == 5, "%s U+%04X: %s reads 5 (%lld)", k ? "trailing" : "leading",
                          (unsigned)c, h, (long long)u);
            else ck(! BRAssetAmountParse(t, len, 0, &u), "%s U+%04X: %s is null", k ? "trailing" : "leading", (unsigned)c, h);
        }
    }
}

static void qty_divisibility(void) { QNULL("1", -1); QNULL("1", INT32_MIN); QNULL("1", 9); QNULL("0.000000001", 9); QNULL("1", 18); }

static const char *qf(int64_t u, int div)
{
    static char b[4][64];
    static int at;
    char *o = b[at++ & 3];

    if (! BRAssetAmountFormat(u, div, o, 64)) snprintf(o, 64, "(none)");
    return o;
}

#define QF(u, div, want) ck(strcmp(qf(u, div), want) == 0, "format(%lld, %d) = %s (%s)", (long long)(u), div, want, qf(u, div))

static void qty_format(void)
{
    QF(5, 0, "5"); QF(100, 0, "100"); QF(1000000, 0, "1000000"); QF(150, 2, "1.5"); QF(100, 2, "1"); QF(100000, 2, "1000");
    QF(1, 2, "0.01"); QF(123456, 2, "1234.56"); QF(1, 8, "0.00000001"); QF(100000000, 8, "1");
    QF(INT64_MAX, 8, "92233720368.54775807"); QF(INT64_MAX, 0, "9223372036854775807");
}

static void qty_locale(void)
{
    const char *was = setlocale(LC_ALL, NULL);
    char saved[128];

    snprintf(saved, sizeof(saved), "%s", was ? was : "C");
    setlocale(LC_ALL, "de_DE.UTF-8");
    QF(150, 2, "1.5"); QF(123456, 2, "1234.56");
    setlocale(LC_ALL, saved);
}

static void qty_roundtrip(void)
{
    static const int64_t more[] = { 100000000LL, 123456789012LL, INT64_MAX - 1, INT64_MAX };
    int div;
    int64_t u;
    size_t i;

    for (div = 0; div <= 8; div++) {
        int ok = 1;

        for (u = 1; u <= 20000; u++) {
            char b[64];

            if (! BRAssetAmountFormat(u, div, b, sizeof(b)) || qp(b, div) != u) { ok = 0; break; }
        }
        for (i = 0; i < N(more); i++) {
            char b[64];

            if (! BRAssetAmountFormat(more[i], div, b, sizeof(b)) || qp(b, div) != more[i]) ok = 0;
        }
        ck(ok, "every quantity survives the round trip at divisibility %d", div);
    }
}

// ---- this suite's own: the encoder's edges ---------------------------------------------------------

static void enc_rules(void)
{
    BRAssetTransferInstruction t;
    uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT], small[9];
    size_t n = 99;
    BRAssetEncodeWhy why = BR_ASSET_ENCODE_WHY_NONE;
    BRAssetHeader h;
    BRAssetTransferInstruction d[BR_ASSET_MAX_INSTRUCTIONS];
    uint8_t buf[2];
    BRAssetBitWriter w;

    ck(BRAssetFixedPrecisionLength(0) == 1 && BRAssetFixedPrecisionLength(31) == 1 && BRAssetFixedPrecisionLength(32) == 2 &&
       BRAssetFixedPrecisionLength(511) == 2 && BRAssetFixedPrecisionLength(512) == 3 && BRAssetFixedPrecisionLength(1000) == 2,
       "the fixed-precision lengths at the first boundaries");
    ck(BRAssetFixedPrecisionLength(10000000000000000LL) == 7 && BRAssetFixedPrecisionLength(1000000000000000LL) == 2,
       "every trailing zero goes to the exponent: 10^15 takes 2 bytes, 10^16 the 7-byte form");
    ck(BRAssetFixedPrecisionLength(BR_ASSET_FIXED_PRECISION_MAX) == 7 && BRAssetFixedPrecisionLength(BR_ASSET_FIXED_PRECISION_MAX + 1) == 0 &&
       BRAssetFixedPrecisionLength(-1) == 0, "2^54 - 1 is the largest amount; 2^54 and -1 have no form");
    ck(BRAssetFixedPrecisionLength(4996000000000000000LL) == 3 && BRAssetFixedPrecisionLength(4000000000000000LL) == 2,
       "an amount past 2^54 whose exponent is at most 15 has a short form");
    ck(BRAssetFixedPrecisionLength(9000000000000000000LL) == 0 && BRAssetFixedPrecisionLength(5000000000000000000LL) == 0,
       "and one whose exponent is 18 has none");

    t = inst(0, 0, 0, 0, BR_ASSET_FIXED_PRECISION_MAX + 1);
    ck(BRAssetEncodeTransferScript(3, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_AMOUNT && n == 0,
       "an amount with no form is refused (AMOUNT)");
    t = inst(0, 0, 0, 0, BR_ASSET_FIXED_PRECISION_MAX);
    ck(BRAssetEncodeTransferScript(3, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_OK && decode(s, n, &h, d) &&
       d[0].amount == BR_ASSET_FIXED_PRECISION_MAX && BRAssetPayloadReadsExactly(s, n, &h, d, 1), "2^54 - 1 is written and read back exactly");
    t = inst(0, 0, 0, 0, -1);
    ck(BRAssetEncodeTransferScript(3, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_INSTRUCTION,
       "a negative amount is an invalid instruction");
    t = inst(0, 0, 0, 31, 1);
    t.isBurn = 1;
    ck(! BRAssetTransferInstructionValid(&t) && BRAssetEncodeTransferScript(3, &t, 1, s, sizeof(s), &n, &why) == BR_ASSET_ENCODE_REFUSED,
       "isBurn is refused: a transfer carries no burn (C only)");
    t = inst(0, 0, 1, 0, 100);
    ck(BRAssetTransferInstructionValid(&t), "percent 100 is valid");
    t = inst(0, 1, 0, 8191, 1);
    ck(BRAssetTransferInstructionValid(&t) && BRAssetEncodeTransferScript(2, &t, 1, s, sizeof(s), &n, NULL) == BR_ASSET_ENCODE_OK &&
       decode(s, n, &h, d) && h.version == 2 && d[0].range && d[0].outputIndex == 8191, "range 8191 at version 2");
    t = inst(0, 0, 0, -1, 1);
    ck(! BRAssetTransferInstructionValid(&t), "a negative output index is invalid");
    t = inst(0, 0, 0, 0, 500);
    ck(BRAssetEncodeTransferScript(3, &t, 1, small, 8, &n, &why) == BR_ASSET_ENCODE_CAPACITY && n == 0 && why == BR_ASSET_ENCODE_WHY_NONE,
       "a buffer one byte short is CAPACITY, nothing written");
    ck(BRAssetEncodeTransferScript(3, &t, 1, small, 9, NULL, NULL) == BR_ASSET_ENCODE_OK, "an exact buffer is enough");
    ck(BRAssetEncodeTransferPayload(3, &t, 1, small, 6, &n, NULL) == BR_ASSET_ENCODE_CAPACITY &&
       BRAssetEncodeTransferPayload(3, &t, 1, small, 7, &n, NULL) == BR_ASSET_ENCODE_OK && n == 7, "the payload alone");
    ck(BRAssetEncodeTransferScript(4, &t, 1, s, sizeof(s), NULL, &why) == BR_ASSET_ENCODE_REFUSED && why == BR_ASSET_ENCODE_WHY_VERSION,
       "version 4 is refused");
    w = BRAssetBitWriterMake(buf, 2);
    ck(BRAssetBitsWrite(&w, 0x3ff, 10) && ! BRAssetBitsWrite(&w, 0x7f, 7) && w.bitPos == 10 && BRAssetBitsWrite(&w, 0x3f, 6) &&
       ! BRAssetBitsWrite(&w, 1, 1) && buf[0] == 0xff && buf[1] == 0xff, "a write that does not fit writes nothing");
    w = BRAssetBitWriterMake(buf, 2);
    ck(! BRAssetBitsWrite(&w, 0, 0) && ! BRAssetBitsWrite(&w, 0, 65) && ! BRAssetBitsWriteFixedPrecision(&w, -5), "counts outside 1..64 are refused");
}

static uint64_t RNG;

static uint32_t rnd(void)   // xorshift64: the same stream on every machine
{
    RNG ^= RNG << 13; RNG ^= RNG >> 7; RNG ^= RNG << 17;
    return (uint32_t)(RNG >> 24);
}

static uint32_t below(uint32_t n) { return rnd() % n; }

static int64_t rndAmount(void)
{
    static const int64_t pow10[] = { 1, 10, 100, 1000, 1000000, 1000000000LL, 1000000000000LL, 1000000000000000LL, 1000000000000000000LL };

    switch (below(8)) {
    case 0: return below(32);
    case 1: return 32 + below(5000);
    case 2: {
        int64_t m = 1 + (int64_t)below(999), p = pow10[below(N(pow10))];

        if (m > INT64_MAX / p) m = INT64_MAX / p;
        return m * p;
    }
    case 3: return BR_ASSET_FIXED_PRECISION_MAX - below(3);
    case 4: return BR_ASSET_FIXED_PRECISION_MAX + 1 + below(3);
    case 5: return (int64_t)(((uint64_t)rnd() << 32) | rnd()) & INT64_MAX;
    case 6: return -(int64_t)below(3) - 1;
    default: return (int64_t)rnd() * 1000;
    }
}

static void rndInst(BRAssetTransferInstruction *t, MInst *m, int valid)
{
    memset(t, 0, sizeof(*t));
    t->skip = (int)below(2);
    t->range = below(5) == 0;
    t->percent = below(6) == 0;
    t->outputIndex = t->range ? (int32_t)below(8192) : (int32_t)below(32);
    t->amount = t->percent ? below(101) : rndAmount();
    if (! valid) {
        switch (below(4)) {
        case 0: t->outputIndex = t->range ? 8192 + (int32_t)below(10) : 32 + (int32_t)below(10); break;
        case 1: t->outputIndex = -1 - (int32_t)below(3); break;
        case 2: if (t->percent) t->amount = 101 + below(200); else t->amount = -1; break;
        default: t->amount = -(int64_t)below(1000) - 1; break;
        }
    }
    m->skip = t->skip; m->range = t->range; m->percent = t->percent; m->idx = t->outputIndex; m->amount = t->amount;
}

static int REACH[64];
static int DIFFS;
enum { R_ENC_OK, R_ENC_REFUSED, R_ENC_PUSHDATA, R_ENC_LONG, R_SEL_OK, R_SEL_ASSET, R_SEL_DGB, R_SEL_REFUSED, R_SEL_OVERFLOW,
       R_SEL_CHANGE, R_SEL_SHARED, R_PLAN_READY, R_PLAN_CHANGE_OUT, R_PLAN_NO_CHANGE, R_PLAN_OVERFLOW, R_PLAN_THREW, R_PLAN_REFUSED,
       R_PLAN_MULTI, R_CH_PLANNED, R_CH_UNVERIFIED, R_CH_UNCONFIRMED, R_CH_SPANS, R_CH_NOT_PLANNED, R_CH_GATE, R_CH_ASKS,
       R_CH_ASIDE, R_CH_NOTE, R_CH_REMEMBERED, R_FEE_WRAP, R_PARSE_OK, R_PARSE_NULL, R_PARSE_WS, R_GROUPS, R_JUDGE_V,
       R_CH_ROUNDS, R_COUNT };

static void diff(const char *part, int round, const char *what)
{
    if (DIFFS++ < 6) ck(0, "%s round %d: %s", part, round, what);
}

static void sweepEncode(void)
{
    int round;

    RNG = 0x9e3779b97f4a7c15ULL;
    for (round = 0; round < 10000; round++) {
        BRAssetTransferInstruction l[48], d[BR_ASSET_MAX_INSTRUCTIONS];
        MInst m[48];
        uint8_t hs[300], ms[300];   // room past any script, so an arm that writes one too long is seen
        size_t n = 1 + below(round % 7 == 0 ? 44 : 6), hl = 0, ml = 0, i;
        int32_t version = below(10) == 0 ? (int32_t)below(6) : 2 + (int32_t)below(2);
        int invalid = below(12) == 0, mok, hok;
        BRAssetHeader h;
        char what[200];

        if (round % 5 == 0) n = 30 + below(12);
        for (i = 0; i < n; i++) {
            rndInst(&l[i], &m[i], ! (invalid && below(3) == 0));
            if (round % 3 == 0) { l[i].range = 0; l[i].percent = 0; l[i].outputIndex = (int32_t)below(32); l[i].amount = below(40); }
            m[i].range = l[i].range; m[i].percent = l[i].percent; m[i].idx = l[i].outputIndex; m[i].amount = l[i].amount;
        }
        if (below(40) == 0) n = 0;
        mok = mEncodeScript(version, m, n, ms, &ml);
        hok = BRAssetEncodeTransferScript(version, l, n, hs, sizeof(hs), &hl, NULL) == BR_ASSET_ENCODE_OK;
        REACH[hok ? R_ENC_OK : R_ENC_REFUSED]++;
        if (hok && hs[1] == 0x4c) REACH[R_ENC_PUSHDATA]++;
        if (hok && hl > 40) REACH[R_ENC_LONG]++;
        if (mok != hok || (hok && (ml != hl || memcmp(ms, hs, hl) != 0))) {
            snprintf(what, sizeof(what), "the header %s %s, the model %s %s", hok ? "wrote" : "refused", hexOf(hs, hok ? hl : 0),
                     mok ? "wrote" : "refused", hexOf(ms, mok ? ml : 0));
            diff("encode", round, what);
            continue;
        }
        if (! hok) continue;
        // THE INVERSE: the decoder reads back the list, field for field, and reads it exactly.
        if (! decode(hs, hl, &h, d) || h.operation != DA_TRANSFER || h.version != version || h.instructionCount != n) {
            diff("encode", round, "the decoder does not read back a transfer of the list's length");
            continue;
        }
        for (i = 0; i < n; i++) {
            if ((d[i].skip != 0) != (l[i].skip != 0) || (d[i].range != 0) != (l[i].range != 0) || (d[i].percent != 0) != (l[i].percent != 0) ||
                d[i].isBurn || d[i].outputIndex != l[i].outputIndex || d[i].amount != l[i].amount) {
                diff("encode", round, "an instruction decodes to other fields");
                break;
            }
        }
        if (! BRAssetPayloadReadsExactly(hs, hl, &h, d, n)) diff("encode", round, "the script does not read exactly");
    }
}

static void sweepFee(void)
{
    int round;

    RNG = 0x1234567887654321ULL;
    for (round = 0; round < 4000; round++) {
        int32_t a = (int32_t)below(10) - 2, d = (int32_t)below(10) - 2, o = (int32_t)below(6) - 1, op = (int32_t)below(100) - 5;
        int64_t rate, hf = 0, mf;
        int hok;

        switch (below(6)) {
        case 0: rate = 100000; break;
        case 1: rate = -(int64_t)below(1000); break;
        case 2: rate = INT64_MAX - below(1000); break;
        case 3: rate = (int64_t)(((uint64_t)rnd() << 32) | rnd()) & INT64_MAX; break;
        case 4: rate = (int64_t)below(400000); break;
        default: rate = (INT64_MAX - 999) / (12 + 150 * 1) + below(3) - 1; a = d = -1; o = 0; op = 0; break;
        }
        if (round % 50 == 0) { a = INT32_MAX; d = INT32_MAX; o = INT32_MAX; op = INT32_MAX; }
        WRAPPED = 0;
        mf = mFee(a, d, o, op, rate);
        hok = BRAssetFeeEstimate(a, d, o, op, rate, &hf);
        if (WRAPPED) REACH[R_FEE_WRAP]++;
        if (hok == WRAPPED || (hok && hf != mf)) {
            char what[200];

            snprintf(what, sizeof(what), "fee(%d,%d,%d,%d,%lld): header %d %lld, model wrapped %d %lld", a, d, o, op, (long long)rate, hok,
                     (long long)hf, WRAPPED, (long long)mf);
            diff("fee", round, what);
        }
    }
}

// ---- the corpus of rows ------------------------------------------------------------------------

static const char *const TXIDS[] = { "aa", "AA", "Aa", "bb", "BB", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj", "kk", "ll", "mm" };
static const uint8_t S3[3] = { 3, 3, 3 };

static void rndScript(BRAssetRow *r, int mostlyOne)
{
    uint32_t k = mostlyOne ? (below(10) == 0 ? 1 + below(3) : 0) : below(4);

    if (k == 0) { r->script = SX; r->scriptLen = 2; }
    else if (k == 1) { r->script = SY; r->scriptLen = 2; }
    else if (k == 2) { r->script = S3; r->scriptLen = 3; }
    else { r->script = EMPTY; r->scriptLen = 0; }
}

static BRAssetRow rndAssetRow(int mostlyOne)
{
    BRAssetRow r = row(TXIDS[below(N(TXIDS))], (int32_t)below(3), SX, 2, 6000, 1);

    rndScript(&r, mostlyOne);
    switch (below(12)) {
    case 0: r.satoshis = (int64_t)below(20000); break;
    case 1: r.satoshis = 200000 + (int64_t)below(100000); break;
    case 2: if (below(4) == 0) r.satoshis = INT64_MAX - (int64_t)below(5); break;
    case 3: if (below(4) == 0) r.satoshis = -(int64_t)below(10000); break;
    default: break;
    }
    switch (below(14)) {
    case 0: r.assetQuantity = 0; break;
    case 1: r.assetQuantity = -(int64_t)below(5) - 1; break;
    case 2: r.assetQuantity = 5000000000000000000LL; break;
    case 3: r.assetQuantity = INT64_MAX; break;
    case 4: r.assetQuantity = 1000 * (int64_t)(1 + below(20)); break;
    default: r.assetQuantity = 1 + (int64_t)below(20); break;
    }
    return r;
}

static BRAssetRow rndDgbRow(void)
{
    BRAssetRow r = row(TXIDS[below(N(TXIDS))], (int32_t)below(3), SX, 2, 0, 0);

    rndScript(&r, 0);
    switch (below(10)) {
    case 0: r.satoshis = 1000000; break;
    case 1: r.satoshis = below(40) == 0 ? INT64_MAX - (int64_t)below(5) : 5000; break;
    case 2: r.satoshis = below(10) == 0 ? -(int64_t)below(20000) : 75000; break;
    default: r.satoshis = (int64_t)below(150000); break;
    }
    return r;
}

static void ptrs(const BRAssetRow *rows, size_t n, const BRAssetRow **out)
{
    size_t i;

    for (i = 0; i < n; i++) out[i] = &rows[i];
}

static int sameSelection(const BRAssetSelection *h, const BRAssetRow *a, const BRAssetRow *d, const MSel *m, char *what, size_t cap)
{
    size_t i;
    int hk = h->status == BR_ASSET_SELECT_OK ? M_OK : h->status == BR_ASSET_SELECT_INSUFFICIENT_ASSET ? M_INSUFFICIENT_ASSET :
             h->status == BR_ASSET_SELECT_INSUFFICIENT_DGB ? M_INSUFFICIENT_DGB : h->status == BR_ASSET_SELECT_REFUSED ? M_THREW : -1;

    if (h->status == BR_ASSET_SELECT_OVERFLOW || WRAPPED) {
        if (h->status == BR_ASSET_SELECT_OVERFLOW && WRAPPED) return 1;
        snprintf(what, cap, "header status %d, model wrapped %d", (int)h->status, WRAPPED);
        return 0;
    }
    if (hk != m->kind) { snprintf(what, cap, "header status %d, model %d", (int)h->status, m->kind); return 0; }
    if (hk == M_THREW) return 1;
    if (hk != M_OK) {
        if (h->required != m->required || h->available != m->available) { snprintf(what, cap, "required/available differ"); return 0; }
        return 1;
    }
    if (h->assetInputCount != m->na || h->dgbInputCount != m->nd || h->assetChangeQty != m->assetChangeQty ||
        h->dgbChangeSats != m->dgbChangeSats) {
        snprintf(what, cap, "counts %zu/%zu vs %zu/%zu, change %lld/%lld vs %lld/%lld", h->assetInputCount, h->dgbInputCount, m->na, m->nd,
                 (long long)h->assetChangeQty, (long long)h->dgbChangeSats, (long long)m->assetChangeQty, (long long)m->dgbChangeSats);
        return 0;
    }
    for (i = 0; i < m->na; i++) if (&a[AO[i]] != m->asset[i]) { snprintf(what, cap, "asset coin %zu differs", i); return 0; }
    for (i = 0; i < m->nd; i++) if (&d[DO_[i]] != m->dgb[i]) { snprintf(what, cap, "fee coin %zu differs", i); return 0; }
    return 1;
}

static void sweepSelect(void)
{
    int round;

    RNG = 0x0f1e2d3c4b5a6978ULL;
    for (round = 0; round < 5000; round++) {
        BRAssetRow a[8], d[8];
        const BRAssetRow *pa[8], *pd[8];
        size_t na = below(7), nd = below(7), i;
        int64_t need = below(15) == 0 ? -(int64_t)below(3) : 1 + (int64_t)below(40);
        int64_t feeS = below(15) == 0 ? -1 : (int64_t)below(100000), marker = below(20) == 0 ? -1 : (int64_t)below(20000);
        int64_t changeMarker = below(20) == 0 ? -1 : (below(2) ? 6000 : 0);
        BRAssetSelection h;
        MSel m;
        char what[300];

        if (below(30) == 0) feeS = INT64_MAX - (int64_t)below(100000);
        if (below(30) == 0) marker = INT64_MAX - (int64_t)below(10);
        if (below(25) == 0) need = 5000000000000000000LL;
        for (i = 0; i < na; i++) a[i] = rndAssetRow(0);
        for (i = 0; i < nd; i++) d[i] = rndDgbRow();
        if (na > 0 && nd > 0 && below(4) == 0) { d[0].txid = a[0].txid; d[0].vout = a[0].vout; REACH[R_SEL_SHARED]++; }
        ptrs(a, na, pa); ptrs(d, nd, pd);
        WRAPPED = 0;
        m = mSelect(pa, na, pd, nd, need, feeS, marker, changeMarker);
        h = sel(a, na, d, nd, need, feeS, marker, changeMarker);
        if (h.status == BR_ASSET_SELECT_OK) REACH[R_SEL_OK]++;
        if (h.status == BR_ASSET_SELECT_OK && h.assetChangeQty > 0) REACH[R_SEL_CHANGE]++;
        if (h.status == BR_ASSET_SELECT_INSUFFICIENT_ASSET) REACH[R_SEL_ASSET]++;
        if (h.status == BR_ASSET_SELECT_INSUFFICIENT_DGB) REACH[R_SEL_DGB]++;
        if (h.status == BR_ASSET_SELECT_REFUSED) REACH[R_SEL_REFUSED]++;
        if (h.status == BR_ASSET_SELECT_OVERFLOW) REACH[R_SEL_OVERFLOW]++;
        if (! sameSelection(&h, a, d, &m, what, sizeof(what))) diff("select", round, what);
    }
}

static int samePlan(const BRAssetTransferPlan *h, const BRAssetRow *a, const BRAssetRow *d, const MPlan *m, char *what, size_t cap)
{
    char hm[300];
    size_t i;

    planMessage(h, hm, sizeof(hm));
    if (h->why == BR_ASSET_PLAN_WHY_OVERFLOW || WRAPPED) {
        if (h->why == BR_ASSET_PLAN_WHY_OVERFLOW && WRAPPED) return 1;
        snprintf(what, cap, "header %s, model wrapped %d", hm, WRAPPED);
        return 0;
    }
    if (m->threw) {
        if (strcmp(hm, "throws") == 0) return 1;
        snprintf(what, cap, "header %s, the model throws", hm);
        return 0;
    }
    if (! m->ready) {
        if (strcmp(hm, m->msg) == 0) return 1;
        snprintf(what, cap, "header \"%s\", model \"%s\"", hm, m->msg);
        return 0;
    }
    if (h->status != BR_ASSET_PLAN_READY) { snprintf(what, cap, "header \"%s\", the model is ready", hm); return 0; }
    if (h->assetInputCount != m->na || h->dgbInputCount != m->nd || h->outputCount != m->no || h->opReturnLen != m->scriptLen ||
        memcmp(h->opReturnScript, m->script, m->scriptLen) != 0 || h->estimatedFeeSats != m->est || h->paidFeeSats != m->paid ||
        h->sourceScript != m->source || h->sourceScriptLen != m->sourceLen) {
        snprintf(what, cap, "ready plans differ: inputs %zu/%zu vs %zu/%zu, outputs %zu vs %zu, %s vs %s, fee %lld/%lld vs %lld/%lld",
                 h->assetInputCount, h->dgbInputCount, m->na, m->nd, h->outputCount, m->no, hexOf(h->opReturnScript, h->opReturnLen),
                 hexOf(m->script, m->scriptLen), (long long)h->estimatedFeeSats, (long long)h->paidFeeSats, (long long)m->est,
                 (long long)m->paid);
        return 0;
    }
    for (i = 0; i < m->na; i++) if (&a[h->assetInputs[i]] != m->asset[i]) { snprintf(what, cap, "asset input %zu differs", i); return 0; }
    for (i = 0; i < m->nd; i++) if (&d[h->dgbInputs[i]] != m->dgb[i]) { snprintf(what, cap, "fee input %zu differs", i); return 0; }
    for (i = 0; i < m->no; i++) {
        if ((int)h->outputs[i].role != m->roles[i] || h->outputs[i].sats != m->sats[i]) { snprintf(what, cap, "output %zu differs", i); return 0; }
    }
    return 1;
}

static int64_t rndRate(void)
{
    switch (below(10)) {
    case 0: return (int64_t)below(100000);
    case 1: return 100000 + (int64_t)below(400000);
    case 2: return below(10) == 0 ? INT64_MAX / 1000 : 1000000;
    case 3: return below(10) == 0 ? -(int64_t)below(1000) - 1 : 100000;
    default: return 100000;
    }
}

static void sweepPlan(void)
{
    int round;

    RNG = 0x5a5a5a5aa5a5a5a5ULL;
    for (round = 0; round < 8000; round++) {
        BRAssetRow a[MAXR], d[12];
        const BRAssetRow *pa[MAXR], *pd[12];
        size_t na = below(6), nd = below(8), i;
        int64_t qty = below(20) == 0 ? -(int64_t)below(2) : 1 + (int64_t)below(30), rate = rndRate();
        BRAssetTransferPlan h;
        MPlan m;
        char what[600];

        if (round % 40 == 0) na = 36 + below(6);
        for (i = 0; i < na; i++) a[i] = rndAssetRow(1);
        if (round % 40 == 0) { for (i = 0; i < na; i++) { a[i].script = SX; a[i].scriptLen = 2; a[i].assetQuantity = 1; } qty = (int64_t)na; }
        for (i = 0; i < nd; i++) d[i] = rndDgbRow();
        if (below(5) == 0) qty = 5000000000000000000LL - (int64_t)below(3);
        if (round % 10 == 1) {   // change of 0..5,461 sats: the remainder goes to the fee, or just becomes an output
            na = 1; nd = 2;
            a[0] = row(TXIDS[below(N(TXIDS))], 0, SX, 2, 6000, 2);
            d[0] = row("dd", 0, SX, 2, 59200 + (int64_t)below(5462), 0);
            d[1] = row("ee", 0, SX, 2, 20000, 0);
            qty = 1 + (int64_t)below(2);
            rate = 100000;
        }
        ptrs(a, na, pa); ptrs(d, nd, pd);
        WRAPPED = 0;
        m = mPlan(pa, na, pd, nd, qty, rate);
        h = plan(a, na, d, nd, qty, rate);
        if (h.status == BR_ASSET_PLAN_READY) {
            REACH[R_PLAN_READY]++;
            REACH[roleCount(&h, BR_ASSET_ROLE_DGB_CHANGE) ? R_PLAN_CHANGE_OUT : R_PLAN_NO_CHANGE]++;
            if (h.assetInputCount > 1) REACH[R_PLAN_MULTI]++;
        }
        else if (h.why == BR_ASSET_PLAN_WHY_OVERFLOW) REACH[R_PLAN_OVERFLOW]++;
        else if (h.why == BR_ASSET_PLAN_WHY_ARGUMENTS) REACH[R_PLAN_THREW]++;
        else REACH[R_PLAN_REFUSED]++;
        if (! samePlan(&h, a, d, &m, what, sizeof(what))) diff("plan", round, what);
    }
}

static BRAssetFact PARENTS[N(TXIDS)];

static BRAssetFact parentTable(const char *txid)
{
    size_t i;

    for (i = 0; i < N(TXIDS); i++) if (strcmp(TXIDS[i], txid) == 0) return PARENTS[i];
    return BR_ASSET_FACT_UNKNOWN;
}

static void rndStack(Stack *s, const char *txid, int32_t vout)
{
    memset(s, 0, sizeof(*s));
    s->txid = txid;
    s->vout = vout;
    switch (below(10)) {
    case 0: s->lookup = BR_ASSET_STACK_NOT_UNSPENT; break;
    case 1: s->lookup = BR_ASSET_STACK_UNAVAILABLE; break;
    case 2: s->lookup = BR_ASSET_STACK_FOUND; break;   // empty
    case 3: s->lookup = BR_ASSET_STACK_FOUND; s->e[0].assetId = OTHER; s->e[0].count = 1 + below(9); s->n = 1; break;
    case 4: s->lookup = BR_ASSET_STACK_FOUND; s->e[0].assetId = ASSET; s->e[0].count = 1 + below(20);
            s->e[1].assetId = OTHER; s->e[1].count = 1; s->n = 2; break;
    default: s->lookup = BR_ASSET_STACK_FOUND; s->e[0].assetId = ASSET; s->e[0].count = -1; s->n = 1; break;   // set to the row below
    }
}

static int sameKinds(const BRAssetSendOutcome *h, const MOutcome *m)
{
    switch (h->kind) {
    case BR_ASSET_SEND_PLANNED: return m->kind == MK_PLANNED;
    case BR_ASSET_SEND_UNVERIFIED: return m->kind == MK_UNVERIFIED;
    case BR_ASSET_SEND_UNCONFIRMED: return m->kind == MK_UNCONFIRMED;
    case BR_ASSET_SEND_SPANS_ADDRESSES: return m->kind == MK_SPANS && h->largestSingleSource == m->largest;
    case BR_ASSET_SEND_NOT_PLANNED: return m->kind == MK_NOT_PLANNED || (m->kind == MK_THREW && h->plan.why == BR_ASSET_PLAN_WHY_ARGUMENTS);
    case BR_ASSET_SEND_RULE_BOUND: return m->kind == MK_RULE_BOUND;
    case BR_ASSET_SEND_RULES_UNKNOWN: return m->kind == MK_RULES_UNKNOWN;
    default: return 0;
    }
}

// The kept sets, as the Kotlin's strings, are equal.
static int sameMemory(const HProc *h, const MProc *m, const char *assetId)
{
    StrSet *hv = calloc(1, sizeof(StrSet)), *ha = calloc(1, sizeof(StrSet));
    size_t i;
    int same = 1;

    for (i = 0; i < h->nv; i++) {
        BRAssetRow r = row(h->verified[i].txid, h->verified[i].vout, NULL, 0, 0, 0);
        char k[300], k2[340];

        outpointKey(&r, k, sizeof(k));
        snprintf(k2, sizeof(k2), "%s:%s:%lld", k, assetId, (long long)h->verified[i].quantity);
        setAdd(hv, k2);
    }
    for (i = 0; i < h->na; i++) {
        BRAssetRow r = row(h->aside[i].txid, h->aside[i].vout, NULL, 0, 0, 0);
        char k[300];

        outpointKey(&r, k, sizeof(k));
        setAdd(ha, k);
    }
    if (hv->n != m->verified.n || ha->n != m->setAside.n) same = 0;
    for (i = 0; same && i < hv->n; i++) if (! setHas(&m->verified, hv->k[i])) same = 0;
    for (i = 0; same && i < ha->n; i++) if (! setHas(&m->setAside, ha->k[i])) same = 0;
    free(hv);
    free(ha);
    return same;
}

static void sweepChoose(void)
{
    static World hw;
    static MProc mp;
    static Source ms;
    int round;

    RNG = 0x00c0ffee00c0ffeeULL;
    for (round = 0; round < 5000; round++) {
        BRAssetRow a[10], d[8];
        const BRAssetRow *pa[10], *pd[8];
        size_t na = below(8), nd = below(5), i, calls = 1 + below(3), c;
        int hasSource = below(25) != 0;

        memset(&hw, 0, sizeof(hw));
        memset(&mp, 0, sizeof(mp));
        for (i = 0; i < N(PARENTS); i++) PARENTS[i] = (BRAssetFact)below(3);
        for (i = 0; i < na; i++) {
            size_t j;
            int again;

            // Unique by txid (as written) and vout, as Room's primary key on the asset rows makes them.
            do {
                a[i] = rndAssetRow(below(2));
                again = 0;
                for (j = 0; j < i; j++) if (strcmp(a[j].txid, a[i].txid) == 0 && a[j].vout == a[i].vout) again = 1;
            } while (again);
        }
        if (below(3) == 0) for (i = 0; i < na; i++) if (a[i].assetQuantity > 20 || a[i].assetQuantity < 0) a[i].assetQuantity = 1 + below(9);
        for (i = 0; i < nd; i++) { d[i] = rndDgbRow(); if (below(2)) d[i].satoshis = 1000000; }
        for (i = 0; i < na; i++) {
            Stack *s = &hw.s.s[hw.s.n++];

            rndStack(s, a[i].txid, below(6) == 0 ? -1 : a[i].vout);
            if (s->n == 1 && s->e[0].count == -1) s->e[0].count = (below(6) == 0 && a[i].assetQuantity < INT64_MAX) ? a[i].assetQuantity + 1 : a[i].assetQuantity;
        }
        ptrs(a, na, pa); ptrs(d, nd, pd);
        for (c = 0; c < calls; c++) {
            BRSweepRuleState rule = below(15) == 0 ? (BRSweepRuleState)below(4) : BR_SWEEP_RULE_NONE;
            int64_t qty = below(25) == 0 ? -(int64_t)below(2) : 1 + (int64_t)below(25), rate = below(25) == 0 ? -1 : rndRate();
            BRAssetSendOutcome h;
            MOutcome m;
            char hmsg[400], what[700];
            size_t asks0;

            if (rate == -1 && below(2)) rate = 100000;
            ms = hw.s;
            ms.nAsked = 0;
            asks0 = hw.s.nAsked;
            WRAPPED = 0;
            m = mPlanAssetTransfer(&mp, rule, hasSource, &ms, ASSET, pa, na, pd, nd, parentTable, qty, rate);
            {
                int wrappedModel = WRAPPED;

                h = hChoose(&hw.p, &hw.b, rule, hasSource, &hw.s, ASSET, a, na, d, nd, parentTable, qty, rate);
                WRAPPED = wrappedModel;
            }
            switch (h.kind) {
            case BR_ASSET_SEND_PLANNED: REACH[R_CH_PLANNED]++; break;
            case BR_ASSET_SEND_UNVERIFIED: REACH[R_CH_UNVERIFIED]++; break;
            case BR_ASSET_SEND_UNCONFIRMED: REACH[R_CH_UNCONFIRMED]++; break;
            case BR_ASSET_SEND_SPANS_ADDRESSES: REACH[R_CH_SPANS]++; break;
            case BR_ASSET_SEND_NOT_PLANNED: REACH[R_CH_NOT_PLANNED]++; if (h.feeCoinRule) REACH[R_CH_NOTE]++; break;
            default: REACH[R_CH_GATE]++; break;
            }
            if (hw.s.nAsked > asks0) REACH[R_CH_ASKS]++;
            if (hw.s.nAsked - asks0 > 1) REACH[R_CH_ROUNDS]++;
            if (hw.p.na > 0) REACH[R_CH_ASIDE]++;
            if (c > 0 && hw.p.nv > 0) REACH[R_CH_REMEMBERED]++;

            chooseMessage(&h, ASSET, hmsg, sizeof(hmsg));
            if (h.kind == BR_ASSET_SEND_NOT_PLANNED && h.plan.why == BR_ASSET_PLAN_WHY_OVERFLOW) {
                if (! WRAPPED) diff("choose", round, "the header says overflow; the model did not wrap");
                break;   // the Kotlin goes on with a wrapped number; the two worlds part here
            }
            if (WRAPPED) { diff("choose", round, "the model wrapped; the header did not refuse"); break; }
            if (! sameKinds(&h, &m)) {
                snprintf(what, sizeof(what), "call %zu: header kind %d \"%s\", model kind %d \"%s\"", c, (int)h.kind, hmsg, m.kind, m.msg);
                diff("choose", round, what);
                break;
            }
            if (h.kind == BR_ASSET_SEND_NOT_PLANNED && m.kind == MK_NOT_PLANNED && strcmp(hmsg, m.msg) != 0) {
                snprintf(what, sizeof(what), "call %zu: header \"%s\", model \"%s\"", c, hmsg, m.msg);
                diff("choose", round, what);
            }
            if (h.kind == BR_ASSET_SEND_PLANNED && ! samePlan(&h.plan, a, d, &m.plan, what, sizeof(what))) diff("choose", round, what);
            if (hw.s.nAsked - asks0 != ms.nAsked) {
                snprintf(what, sizeof(what), "call %zu: the header asked %zu times, the model %zu", c, hw.s.nAsked - asks0, ms.nAsked);
                diff("choose", round, what);
            }
            else {
                for (i = 0; i < ms.nAsked; i++) {
                    if (strcmp(ms.asked[i], hw.s.asked[asks0 + i]) != 0) { diff("choose", round, "the asks differ"); break; }
                }
            }
            if (! sameMemory(&hw.p, &mp, ASSET)) diff("choose", round, "what is kept between choices differs");
        }
    }
}

static void sweepJudge(void)
{
    int round;

    RNG = 0x7777777733333333ULL;
    for (round = 0; round < 3000; round++) {
        BRAssetUnits e[3];
        size_t n = below(3) == 0 ? below(4) : 1, i;
        BRAssetStackLookup lk = below(3) == 0 ? (BRAssetStackLookup)below(3) : BR_ASSET_STACK_FOUND;
        int64_t q = (int64_t)below(6) - 1;
        int hv, mv;

        for (i = 0; i < n; i++) { e[i].assetId = below(3) ? ASSET : OTHER; e[i].count = below(2) ? q : (int64_t)below(6) - 1; e[i].ruleFree = 0; }
        hv = BRAssetInputJudge(lk, e, n, ASSET, q);
        mv = mJudge(lk, e, n, ASSET, q);
        if (hv == BR_ASSET_INPUT_VERIFIED) REACH[R_JUDGE_V]++;
        if ((hv == BR_ASSET_INPUT_VERIFIED) != (mv == MV_VERIFIED) || (hv == BR_ASSET_INPUT_MISMATCH) != (mv == MV_MISMATCH) ||
            (hv == BR_ASSET_INPUT_UNAVAILABLE) != (mv == MV_UNAVAILABLE)) diff("judge", round, "the verdicts differ");
    }
}

static void sweepGroups(void)
{
    int round;

    RNG = 0x2468ace013579bdfULL;
    for (round = 0; round < 3000; round++) {
        BRAssetRow r[10];
        const BRAssetRow *pr[10];
        BRAssetSourceGroup g[10];
        MGroup mg[MAXR];
        size_t n = below(9), i, hn = 0, mn;
        int64_t qty = (int64_t)below(40) - 2;
        int ok = 1;

        for (i = 0; i < n; i++) r[i] = rndAssetRow(0);
        ptrs(r, n, pr);
        mn = mSourceGroups(pr, n, qty, mg);
        if (! BRAssetSourceGroups(r, n, qty, g, N(g), &hn) || hn != mn) ok = 0;
        for (i = 0; ok && i < mn; i++) {
            if (&r[g[i].first] != mg[i].rows[0] || g[i].rowCount != mg[i].n || g[i].total != mg[i].total) ok = 0;
        }
        if (mn > 0) REACH[R_GROUPS]++;
        if (BRAssetLargestSingleSource(r, n) != mLargestSingleSource(pr, n)) ok = 0;
        if (! ok) diff("groups", round, "the source groups differ");
    }
}

static void sweepParse(void)
{
    static const char *const base[] = { "0", "1", "5", "9", "00", ".", ",", " ", "\t", "\xc2\xa0", "\xe3\x80\x80", "\xe2\x80\xa8",
                                        "e", "-", "+", "x", "\xd9\xa5", "\x1c", "\n", "12", "345", "999999999" };
    // The alphabet: base, then every code point of TRIMMED and NOT_TRIMMED as UTF-8.
    static char spelled[N(TRIMMED) + N(NOT_TRIMMED)][4];
    static const char *bits[N(base) + N(TRIMMED) + N(NOT_TRIMMED)];
    int round;
    size_t q;

    for (q = 0; q < N(base); q++) bits[q] = base[q];
    for (q = 0; q < N(TRIMMED) + N(NOT_TRIMMED); q++) {
        size_t n = utf8Of(q < N(TRIMMED) ? TRIMMED[q] : NOT_TRIMMED[q - N(TRIMMED)], spelled[q]);

        spelled[q][n] = '\0';
        bits[N(base) + q] = spelled[q];
    }

    RNG = 0x1357924680ace0bdULL;
    for (round = 0; round < 20000; round++) {
        char t[400];
        size_t len = 0, k = below(round % 11 == 0 ? 40 : 9), i;
        int div = (int)below(11) - 1, hok, mok;
        int64_t hu = 0, mu = 0;

        for (i = 0; i < k; i++) {
            const char *b = bits[below(round % 3 == 0 ? 7 : N(bits))];
            size_t bl = strlen(b);

            if (len + bl >= sizeof(t)) break;
            memcpy(t + len, b, bl);
            len += bl;
        }
        t[len] = '\0';
        hok = BRAssetAmountParse(t, len, div, &hu);
        mok = mParse(t, len, div, &mu);
        REACH[hok ? R_PARSE_OK : R_PARSE_NULL]++;
        if (hok && (strchr(t, ' ') || strstr(t, "\xc2\xa0") || strstr(t, "\xe3\x80\x80"))) REACH[R_PARSE_WS]++;
        if (hok != mok || (hok && hu != mu)) {
            char what[300];

            snprintf(what, sizeof(what), "\"%s\" at %d: header %d %lld, model %d %lld", t, div, hok, (long long)hu, mok, (long long)mu);
            diff("parse", round, what);
        }
    }
    for (round = 0; round < 20000; round++) {
        int64_t u;
        int div = (int)below(14) - 3;
        char h[64], m[64];

        switch (below(4)) {
        case 0: u = (int64_t)below(100000); break;
        case 1: u = (int64_t)(((uint64_t)rnd() << 32) | rnd()); break;
        case 2: u = -(int64_t)below(100000); break;
        default: u = (int64_t)(1 + below(9)) * 1000000; break;
        }
        if (round == 0) u = INT64_MIN;
        if (! BRAssetAmountFormat(u, div, h, sizeof(h))) { diff("format", round, "no text"); continue; }
        mFormat(u, div, m, sizeof(m));
        if (strcmp(h, m) != 0) {
            char what[200];

            snprintf(what, sizeof(what), "format(%lld, %d): header %s, model %s", (long long)u, div, h, m);
            diff("format", round, what);
        }
        if (u > 0 && div >= 0 && div <= 8 && qp(h, div) != u) diff("format", round, "parse(format(u)) is not u");
    }
}

static void model_sweep(void)
{
    static const struct { int at; int min; const char *what; } reach[] = {
        { R_ENC_OK, 2000, "scripts written" }, { R_ENC_REFUSED, 1000, "lists refused" }, { R_ENC_PUSHDATA, 100, "PUSHDATA1 scripts" },
        { R_ENC_LONG, 300, "scripts past 40 bytes" }, { R_FEE_WRAP, 300, "fees past the int64 range" },
        { R_SEL_OK, 800, "selections" }, { R_SEL_CHANGE, 300, "selections with asset change" },
        { R_SEL_ASSET, 300, "selections short of asset" }, { R_SEL_DGB, 300, "selections short of DGB" },
        { R_SEL_REFUSED, 300, "selector arguments refused" }, { R_SEL_OVERFLOW, 100, "selector sums past the range" },
        { R_SEL_SHARED, 500, "outpoints in both pools" }, { R_PLAN_READY, 800, "plans" }, { R_PLAN_CHANGE_OUT, 500, "plans with DGB change" },
        { R_PLAN_NO_CHANGE, 30, "plans whose change goes to the fee" }, { R_PLAN_MULTI, 200, "plans of several asset inputs" },
        { R_PLAN_OVERFLOW, 40, "plans past the range" }, { R_PLAN_THREW, 100, "plans the Kotlin throws for" },
        { R_PLAN_REFUSED, 800, "plans refused" }, { R_CH_PLANNED, 300, "choices planned" }, { R_CH_UNVERIFIED, 200, "choices unverified" },
        { R_CH_UNCONFIRMED, 200, "choices unconfirmed" }, { R_CH_SPANS, 100, "choices spanning addresses" },
        { R_CH_NOT_PLANNED, 300, "choices not planned" }, { R_CH_GATE, 100, "choices refused at the gate" },
        { R_CH_ASKS, 1000, "choices that ask" }, { R_CH_ROUNDS, 200, "choices that ask more than once" },
        { R_CH_ASIDE, 300, "choices that set a coin aside" }, { R_CH_NOTE, 20, "shortfalls of the fee-coin rule" },
        { R_CH_REMEMBERED, 300, "later choices with confirmed coins kept" }, { R_JUDGE_V, 100, "inputs verified" },
        { R_GROUPS, 1000, "source groups" }, { R_PARSE_OK, 2000, "quantities read" }, { R_PARSE_NULL, 5000, "texts refused" },
        { R_PARSE_WS, 300, "quantities read inside whitespace" },
    };
    size_t i;

    DIFFS = 0;
    memset(REACH, 0, sizeof(REACH));
    sweepEncode();
    sweepFee();
    sweepSelect();
    sweepPlan();
    sweepChoose();
    sweepJudge();
    sweepGroups();
    sweepParse();
    for (i = 0; i < N(reach); i++) ck(REACH[reach[i].at] >= reach[i].min, "the corpus reaches %s: %d", reach[i].what, REACH[reach[i].at]);
    ck(DIFFS == 0, "the headers agree with the model over the corpus: %d differences", DIFFS);
}

// ---- this suite's own: the gate, the order, the step protocol, the sums, the C-only answers ---------

static void gate_rules(void)
{
    BRAssetSendKind k = BR_ASSET_SEND_PLANNED;
    World *w;
    BRAssetRow r[1], f[1];
    BRSweepRuleState states[3];
    size_t i;

    ck(BRAssetSendGate(BR_SWEEP_RULE_NONE, &k), "NONE may be sent");
    ck(! BRAssetSendGate(BR_SWEEP_RULE_BOUND, &k) && k == BR_ASSET_SEND_RULE_BOUND, "BOUND is RULE_BOUND");
    ck(! BRAssetSendGate(BR_SWEEP_RULE_UNKNOWN, &k) && k == BR_ASSET_SEND_RULES_UNKNOWN, "UNKNOWN is RULES_UNKNOWN");
    ck(! BRAssetSendGate((BRSweepRuleState)7, &k) && k == BR_ASSET_SEND_RULES_UNKNOWN, "a value the enum does not define is RULES_UNKNOWN");

    // The states as BRAssetRuleGate.h derives them: a locked rule-free issuance, an unlocked one, a rules block.
    states[0] = BRAssetRuleStateOf(1, 1, 1, 1, 1, 0);
    states[1] = BRAssetRuleStateOf(1, 1, 1, 0, 1, 0);
    states[2] = BRAssetRuleStateOf(1, 3, 1, 1, 0, 0);
    ck(states[0] == BR_SWEEP_RULE_NONE && states[1] == BR_SWEEP_RULE_UNKNOWN && states[2] == BR_SWEEP_RULE_BOUND, "the gate's inputs");

    for (i = 0; i < 3; i++) {
        BRAssetSendOutcome o;

        w = world();
        w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
        r[0] = srow("a", 5, NULL);
        f[0] = sfee("f", NULL);
        o = hChoose(&w->p, &w->b, states[i], 1, &w->s, ASSET, r, 1, f, 1, parentNo, 3, 100000);
        ck(o.kind == (i == 0 ? BR_ASSET_SEND_PLANNED : i == 1 ? BR_ASSET_SEND_RULES_UNKNOWN : BR_ASSET_SEND_RULE_BOUND),
           "state %d: the choice is %d", (int)states[i], (int)o.kind);
        ck(i == 0 || w->s.nAsked == 0, "a refused asset asks nothing");
    }
    // Before every other check: a quantity of 0, no rows, no indexer.
    w = world();
    ck(hChoose(&w->p, &w->b, BR_SWEEP_RULE_UNKNOWN, 0, &w->s, ASSET, NULL, 0, NULL, 0, parentNo, 0, -1).kind == BR_ASSET_SEND_RULES_UNKNOWN,
       "the gate comes first");
    ck(hChoose(&w->p, &w->b, BR_SWEEP_RULE_BOUND, 0, &w->s, ASSET, NULL, 0, NULL, 0, parentNo, 0, -1).kind == BR_ASSET_SEND_RULE_BOUND,
       "for BOUND too");
}

static void choose_prechecks(void)
{
    World *w = world();
    BRAssetRow r[1], f[1];
    BRAssetSendOutcome o;
    char m[400];

    r[0] = srow("a", 5, NULL);
    f[0] = sfee("f", NULL);
    o = hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, 0, &w->s, ASSET, NULL, 0, f, 1, parentNo, 0, -1);
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_QUANTITY, "a quantity of 0 first");
    o = hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, 0, &w->s, ASSET, NULL, 0, f, 1, parentNo, 3, -1);
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_FEE_RATE, "then a negative fee rate");
    o = hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, 0, &w->s, ASSET, NULL, 0, f, 1, parentNo, 3, 0);
    chooseMessage(&o, ASSET, m, sizeof(m));
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_NO_ASSET_UTXOS && strcmp(m, "No UTXOs for asset " ASSET) == 0,
       "then no rows: %s", m);
    o = hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, 0, &w->s, ASSET, r, 1, f, 1, parentNo, 3, 0);
    ck(o.kind == BR_ASSET_SEND_UNVERIFIED, "then no indexer");
    o = hChoose(&w->p, &w->b, BR_SWEEP_RULE_NONE, 1, &w->s, ASSET, r, 1, f, 1, parentNo, 6, 0);
    chooseMessage(&o, ASSET, m, sizeof(m));
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && strcmp(m, "Not enough asset: need 6, have 5") == 0 && w->s.nAsked == 0,
       "then the rows' sum, before anything is asked: %s", m);
}

// Two rows naming one outpoint (txid as written, vout) at two quantities, which Room's primary key keeps
// out of the Kotlin's table: the Kotlin asks the indexer once a row, the step protocol once an outpoint, and
// both judge each row against the one stack and come to the same outcome.
static void choose_repeated_outpoint(void)
{
    World *w = world();
    static MProc mp;
    static Source ms;
    BRAssetRow r[2], f[1];
    const BRAssetRow *pr[2], *pf[1];
    BRAssetSendOutcome o;
    MOutcome m;

    w->s.s[w->s.n++] = found("a", ASSET, 5, NULL, 0);
    r[0] = srow("a", 5, NULL); r[1] = srow("a", 4, NULL); f[0] = sfee("f", NULL);
    ptrs(r, 2, pr); ptrs(f, 1, pf);
    memset(&mp, 0, sizeof(mp));
    ms = w->s;
    m = mChoose(&mp, 1, &ms, ASSET, pr, 2, pf, 1, parentNo, 9, 100000);
    o = chooseIn(w, r, 2, 9, f, 1, parentNo, 1);
    ck(o.kind == BR_ASSET_SEND_UNCONFIRMED && m.kind == MK_UNCONFIRMED, "both: UNCONFIRMED (the second row's quantity is not the stack's)");
    ck(w->s.nAsked == 1 && ms.nAsked == 2, "the header asks once (%zu), the Kotlin twice (%zu)", w->s.nAsked, ms.nAsked);
    ck(sameMemory(&w->p, &mp, ASSET), "and both keep the same");
}

// An answer whose entries are NULL while it counts entries (C only: the Kotlin cannot write one) is a
// platform's bug, not the indexer's word: the choice refuses it as ARGUMENTS and marks nothing, so the coin
// is not set aside for the life of the process.
static void choose_malformed_answer(void)
{
    BRAssetRow r[1], f[1];
    BRAssetFact parents[1] = { BR_ASSET_FACT_NO };
    BRAssetUnits mine[1] = { { ASSET, 5, 0 } };
    BRAssetSendLookup answers[1];
    BRAssetSendOutcome o;
    HBuf b;
    static const BRAssetStackLookup lookups[2] = { BR_ASSET_STACK_FOUND, BR_ASSET_STACK_NOT_UNSPENT };
    size_t i;

#define CH(n) BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r, 1, f, parents, 1, 5, 100000, NULL, 0, NULL, 0, answers, n, \
                                b.marks, 1, b.work, N(b.work))
    r[0] = srow("a", 5, NULL); f[0] = sfee("f", NULL);
    for (i = 0; i < 2; i++) {
        memset(b.marks, 0xff, sizeof(b.marks));
        answers[0].txid = "a"; answers[0].vout = 0; answers[0].lookup = lookups[i]; answers[0].entries = NULL; answers[0].entryCount = 1;
        o = CH(1);
        ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_ARGUMENTS && o.plan.status == BR_ASSET_PLAN_REFUSED,
           "lookup %d, entries NULL, one entry counted: NOT_PLANNED, ARGUMENTS (%d, why %d)", (int)lookups[i], (int)o.kind, (int)o.plan.why);
        ck(b.marks[0] == 0, "and nothing is marked to keep (%d): the coin is not set aside", (int)b.marks[0]);
    }
    answers[0].txid = "zz"; answers[0].lookup = BR_ASSET_STACK_FOUND;
    o = CH(1);
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_ARGUMENTS, "so is one given for another coin");
    answers[0].txid = "a"; answers[0].entryCount = 0;
    o = CH(1);
    ck(o.kind == BR_ASSET_SEND_UNCONFIRMED && b.marks[0] == BR_ASSET_SEND_MARK_NOT_YET,
       "entries NULL and none counted is an empty stack: left out of this send only (%d, mark %d)", (int)o.kind, (int)b.marks[0]);
    answers[0].entries = mine; answers[0].entryCount = 1;
    o = CH(1);
    ck(o.kind == BR_ASSET_SEND_PLANNED && b.marks[0] == BR_ASSET_SEND_MARK_VERIFIED, "and the coin, given its stack, is planned");
#undef CH
}

static void choose_step(void)
{
    BRAssetRow r[2], f[1];
    BRAssetFact parents[1] = { BR_ASSET_FACT_NO };
    BRAssetUnits other[1] = { { OTHER, 9, 0 } }, mine[1] = { { ASSET, 4, 0 } };
    BRAssetSendLookup answers[2];
    BRAssetSendKey kept[1];
    BRAssetSendOutcome o;
    HBuf b;

    r[0] = srow("big", 9, NULL); r[1] = srow("small", 4, NULL); f[0] = sfee("f", NULL);
    memset(b.marks, 0xff, sizeof(b.marks));
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r, 2, f, parents, 1, 3, 100000, NULL, 0, NULL, 0, NULL, 0, b.marks, 2, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP && o.lookupRow == 0 && b.marks[0] == 0 && b.marks[1] == 0, "with no answer: ask about the plan's first coin, keep nothing");
    answers[0].txid = "big"; answers[0].vout = 0; answers[0].lookup = BR_ASSET_STACK_FOUND; answers[0].entries = other; answers[0].entryCount = 1;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r, 2, f, parents, 1, 3, 100000, NULL, 0, NULL, 0, answers, 1, b.marks, 2, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP && o.lookupRow == 1, "with its answer: the next round asks about the other coin");
    answers[1].txid = "small"; answers[1].vout = 0; answers[1].lookup = BR_ASSET_STACK_FOUND; answers[1].entries = mine; answers[1].entryCount = 1;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r, 2, f, parents, 1, 3, 100000, NULL, 0, NULL, 0, answers, 2, b.marks, 2, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_PLANNED && o.plan.assetInputCount == 1 && o.plan.assetInputs[0] == 1, "with both: planned from the second");
    ck(b.marks[0] == BR_ASSET_SEND_MARK_SET_ASIDE && b.marks[1] == BR_ASSET_SEND_MARK_VERIFIED, "and the marks say what to keep");
    kept[0].txid = "BIG"; kept[0].vout = 0; kept[0].assetId = NULL; kept[0].quantity = 0;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r, 2, f, parents, 1, 3, 100000, NULL, 0, kept, 1, NULL, 0, b.marks, 2, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP && o.lookupRow == 1, "a coin kept as set aside (any spelling) is not planned again");
    kept[0].txid = "small"; kept[0].assetId = ASSET; kept[0].quantity = 4;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r + 1, 1, f, parents, 1, 3, 100000, kept, 1, NULL, 0, NULL, 0, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_PLANNED, "a coin kept as confirmed, at its quantity, is not asked again");
    kept[0].quantity = 5;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r + 1, 1, f, parents, 1, 3, 100000, kept, 1, NULL, 0, NULL, 0, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP, "but one kept at another quantity is");
    kept[0].quantity = 4; kept[0].assetId = OTHER;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r + 1, 1, f, parents, 1, 3, 100000, kept, 1, NULL, 0, NULL, 0, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP, "and one kept for another asset is");
    answers[0].txid = "SMALL"; answers[0].vout = 0;
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, r + 1, 1, f, parents, 1, 3, 100000, NULL, 0, NULL, 0, answers, 1, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_LOOKUP, "an answer is the answer for the txid as written");
}

// THE ORDER, pinned: both apps must take the same coins.
static void select_order(void)
{
    BRAssetRow a[4], d[4];
    BRAssetSelection r;

    a[0] = row("t1", 0, NULL, 0, 600, 5); a[1] = row("t2", 0, NULL, 0, 600, 5); a[2] = row("t3", 0, NULL, 0, 600, 5);
    d[0] = row("d", 0, NULL, 0, 100000, 0);
    r = sel(a, 3, d, 1, 6, 0, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 2 && AO[0] == 0 && AO[1] == 1, "coins of equal quantity are taken in the order given");
    a[0] = row("t1", 0, NULL, 0, 600, 3); a[1] = row("t2", 0, NULL, 0, 600, 7); a[2] = row("t3", 0, NULL, 0, 600, 7); a[3] = row("t4", 0, NULL, 0, 600, 9);
    r = sel(a, 4, d, 1, 20, 0, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 3 && AO[0] == 3 && AO[1] == 1 && AO[2] == 2, "largest first, ties in order: t4, t2, t3");
    a[0] = row("z", 0, NULL, 0, 600, 0); a[1] = row("n", 0, NULL, 0, 600, -2); a[2] = row("p", 0, NULL, 0, 600, 5);
    r = sel(a, 3, d, 1, 5, 0, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 1 && AO[0] == 2, "zero and negative quantities sort last and are not needed");
    r = sel(a, 3, d, 1, 6, 0, 0, 0);
    ck(r.status == BR_ASSET_SELECT_INSUFFICIENT_ASSET && r.available == 3, "taken while short: 5, then 0, then -2 (%lld)", (long long)r.available);

    a[0] = row("a", 0, NULL, 0, 0, 1);
    d[0] = row("f1", 0, NULL, 0, 4000, 0); d[1] = row("F1", 0, NULL, 0, 9000, 0); d[2] = row("f2", 0, NULL, 0, 4000, 0); d[3] = row("f3", 0, NULL, 0, 8000, 0);
    r = sel(a, 1, d, 4, 1, 10000, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 2 && DO_[0] == 3 && DO_[1] == 0,
       "a fee coin given twice is taken once, as first given (f1 at 4000, not F1 at 9000); then f3, f1");
    d[0] = row("x", 0, NULL, 0, 500, 0); d[1] = row("y", 0, NULL, 0, 100, 0);
    a[0] = row("a", 0, NULL, 0, 600, 100);
    r = sel(a, 1, d, 2, 100, 500, 600, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 1 && DO_[0] == 0 && r.dgbChangeSats == 0,
       "a fee coin that meets the need exactly is the last taken");
    a[0] = row("a", 0, NULL, 0, 0, 1);
    d[0] = row("f1", 0, NULL, 0, 4000, 0); d[1] = row("F1", 0, NULL, 0, 9000, 0);
    a[1] = row("f3", 0, NULL, 0, 0, 1);
    r = sel(a, 2, d, 4, 1, 7000, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.assetInputCount == 1 && AO[0] == 0 && r.dgbInputCount == 2 && DO_[0] == 0 && DO_[1] == 2,
       "a fee coin that is any asset coin given, taken or not, is not a fee coin; ties in order: f1, f2");
}

static void plan_rules(void)
{
    BRAssetRow a[40], d[2];
    BRAssetTransferPlan p;
    BRAssetHeader h;
    BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS];
    static char tx[40][4];
    int64_t x;
    int at5460 = 0, at5461 = 0;
    size_t i;

    a[0] = row("i1", 0, SX, 2, 6000, 2); a[1] = row("i2", 0, SX, 2, 6000, 2); a[2] = row("i3", 0, SX, 2, 6000, 2);
    d[0] = row("f", 0, SX, 2, 1000000, 0);
    p = plan(a, 3, d, 1, 5, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && decode(p.opReturnScript, p.opReturnLen, &h, l) && h.instructionCount == 4, "three inputs, four instructions");
    ck(l[0].skip && l[0].outputIndex == 0 && l[0].amount == 2 && l[1].skip && l[1].outputIndex == 0 && l[1].amount == 2 &&
       ! l[2].skip && l[2].outputIndex == 0 && l[2].amount == 1 && ! l[3].skip && l[3].outputIndex == 3 && l[3].amount == 1,
       "the last instruction from every input but the last skips; the change goes to the last output");

    for (i = 0; i < 40; i++) { snprintf(tx[i], 4, "c%zu", i); a[i] = row(tx[i], 0, SX, 2, 6000, 1); }
    p = plan(a, 38, d, 1, 38, 100000);
    ck(p.status == BR_ASSET_PLAN_READY && p.opReturnLen == 83, "38 one-unit inputs fill an 80-byte carrier");
    p = plan(a, 39, d, 1, 39, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_INSTRUCTIONS, "39 do not fit: the instructions cannot be built");
    a[0] = row("i1", 0, SX, 2, 6000, 2);
    p = plan(a, 1, d, 1, 0, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_ARGUMENTS, "a quantity of 0 is refused (Kotlin: require throws)");
    p = plan(NULL, 0, d, 1, 1, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_NO_ASSET_UTXOS, "no asset coin");
    p = plan(a, 1, d, 1, 3, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_NOT_ENOUGH_ASSET && p.required == 3 && p.available == 2, "not enough asset");
    p = plan(a, 1, NULL, 0, 2, 100000);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_NOT_ENOUGH_DGB && p.required == 65300 + 12000 - 6000 && p.available == 0,
       "not enough DGB, at the bootstrap fee (%lld)", (long long)p.required);

    // The change line: change of exactly 5,460 goes to the fee; 5,461 is an output.
    for (x = 50000; x <= 90000; x++) {
        d[0] = row("f", 0, SX, 2, x, 0); d[1] = row("g", 0, SX, 2, 20000, 0);
        p = plan(a, 1, d, 2, 2, 100000);
        if (p.status != BR_ASSET_PLAN_READY) continue;
        if (roleCount(&p, BR_ASSET_ROLE_DGB_CHANGE) == 0 && p.paidFeeSats - p.estimatedFeeSats == 5460) at5460 = 1;
        if (roleSats(&p, BR_ASSET_ROLE_DGB_CHANGE) == 5461 && p.paidFeeSats == p.estimatedFeeSats) at5461 = 1;
    }
    ck(! BRAssetFeeWithinApproval(0, 0) && ! BRAssetFeeWithinApproval(0, -1) && BRAssetFeeWithinApproval(0, 1),
       "an approval of 0 approves nothing, not even a fee of 0");
    ck(at5460, "a remainder of exactly 5,460 is left to the fee");
    ck(at5461, "a remainder of 5,461 is a change output");
}

static void overflow_rules(void)
{
    BRAssetRow a[2], d[2];
    const BRAssetRow *pa[2], *pd[2];
    BRAssetSelection r;
    BRAssetTransferPlan p;
    MSel m;
    int64_t f = 7;

    a[0] = row("a", 0, NULL, 0, INT64_MAX, 1); a[1] = row("b", 0, NULL, 0, 1, 1);
    r = sel(a, 2, NULL, 0, 2, 0, 0, 0);
    ptrs(a, 2, pa);
    WRAPPED = 0;
    m = mSelect(pa, 2, NULL, 0, 2, 0, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OVERFLOW && WRAPPED && m.kind == M_OK, "asset sats past the range: OVERFLOW, where Kotlin wraps and answers Ok");

    a[0] = row("a", 0, NULL, 0, 6000, 1);
    d[0] = row("x", 0, NULL, 0, INT64_MAX, 0); d[1] = row("y", 0, NULL, 0, 2, 0);
    r = sel(a, 1, d, 2, 1, INT64_MAX - 10, 0, 0);
    ptrs(d, 2, pd);
    ck(r.status == BR_ASSET_SELECT_OVERFLOW, "fee coin sats past the range: OVERFLOW");
    ck(! BRAssetFeeEstimate(1, 1, 3, 80, INT64_MAX, &f) && f == 0, "a fee rate whose fee is past the range: no fee");
    ck(BRAssetFeeEstimate(1, 1, 3, 80, INT64_MAX / 1000, &f) && f > 0, "a large one that fits: a fee");
    p = plan(a, 1, d, 1, 1, INT64_MAX);
    ck(p.status == BR_ASSET_PLAN_REFUSED && p.why == BR_ASSET_PLAN_WHY_OVERFLOW, "a plan at such a rate is refused, OVERFLOW");
    d[0] = row("x", 0, NULL, 0, -500, 0); d[1] = row("y", 0, NULL, 0, 100000, 0);
    r = sel(a, 1, d, 2, 1, 50000, 0, 0);
    ck(r.status == BR_ASSET_SELECT_OK && r.dgbInputCount == 1 && DO_[0] == 1 && r.dgbChangeSats == 56000,
       "a negative fee coin sorts last and is not needed");
    (void)pd;
}

static void parse_rules(void)
{
    QEQ("\xc2\xa0" "5" "\xe3\x80\x80", 0, 5);
    QEQ("\xe2\x80\xa8" "7" "\xe2\x80\x89", 0, 7);
    QEQ("\x1c" "8" "\x1f", 0, 8);
    QEQ("\t\n9\r\v\f", 0, 9);
    QNULL("\xd9\xa5", 0);                    // ARABIC-INDIC FIVE: Kotlin's filter takes it, its BigDecimal read does not
    QNULL("\xef\xbc\x95", 0);                // FULLWIDTH FIVE
    QNULL("5\xc2\x85", 0);                   // U+0085 is not whitespace to Kotlin
    QNULL("\xef\xbb\xbf" "5", 0);            // nor U+FEFF
    QNULL("\xc2" "5", 0);                    // not UTF-8
    QEQ("1,000", 3, 1000);                   // a lone comma is the point, three digits or not (DGB amounts refuse this)
    QNULL("1,000", 0);
    QEQ("1.", 0, 1);
    QEQ("1.", 2, 100);
    QEQ("0001.00", 2, 100);
    QEQ("1,2.5", 1, 125);
    QNULL("1 .5", 1);
    QNULL(",5", 0);
    QEQ(",5", 1, 5);
    {
        int64_t u = -7;
        ck(! BRAssetAmountParse("5\0" "5", 3, 0, &u) && u == 0, "a NUL inside the text is refused");
        ck(BRAssetAmountParse("55", 1, 0, &u) && u == 5, "only `len` bytes are read");
        ck(! BRAssetAmountParse(NULL, 0, 0, &u) && ! BRAssetAmountParse("5", 1, 0, NULL), "NULL is refused");
    }
}

static void format_rules(void)
{
    char b[64];

    QF(-150, 2, "-1.5"); QF(-1, 8, "-0.00000001"); QF(INT64_MIN, 0, "-9223372036854775808"); QF(150, -3, "150"); QF(0, 5, "0");
    QF(1, 20, "0.00000000000000000001"); QF(1000, 3, "1"); QF(1010, 3, "1.01");
    ck(BRAssetAmountFormat(150, 2, b, 4) == 3 && strcmp(b, "1.5") == 0, "room for the text and its terminator is enough");
    ck(BRAssetAmountFormat(12, 2, b, 4) == 0 && BRAssetAmountFormat(12, 2, b, 5) == 4 && strcmp(b, "0.12") == 0,
       "digits that fill the decimals take a leading 0. and need its room");
    ck(BRAssetAmountFormat(-12, 2, b, 5) == 0 && BRAssetAmountFormat(-12, 2, b, 6) == 5 && strcmp(b, "-0.12") == 0, "and with a sign");
    ck(BRAssetAmountFormat(123, 2, b, 5) == 4 && strcmp(b, "1.23") == 0 && BRAssetAmountFormat(123, 2, b, 4) == 0, "one more digit than decimals");
    ck(BRAssetAmountFormat(150, 2, b, 3) == 0 && b[0] == '\0', "one byte less is not");
    ck(BRAssetAmountFormat(0, 2, b, 1) == 0 && BRAssetAmountFormat(5, 0, NULL, 9) == 0, "nor no room at all");
}

static void arguments(void)
{
    BRAssetRow a[1], d[1];
    BRAssetSourceGroup g[1];
    size_t n = 5;
    BRAssetSendOutcome o;
    HBuf b;
    BRAssetFact pf[1] = { BR_ASSET_FACT_NO };

    a[0] = row("a", 0, NULL, 0, 600, 5); d[0] = row("d", 0, NULL, 0, 100000, 0);
    ck(BRAssetCoinSelect(NULL, 1, d, 1, 1, 0, 0, 0, AO, N(AO), DO_, N(DO_)).status == BR_ASSET_SELECT_REFUSED, "NULL asset rows");
    ck(BRAssetCoinSelect(a, 1, NULL, 1, 1, 0, 0, 0, AO, N(AO), DO_, N(DO_)).status == BR_ASSET_SELECT_REFUSED, "NULL plain rows");
    ck(_BRAssetPlanTransfer(NULL, 1, d, 1, 1, 0, AO, N(AO), DO_, N(DO_)).why == BR_ASSET_PLAN_WHY_ARGUMENTS, "a plan with NULL rows");
    ck(! BRAssetSourceGroups(NULL, 1, 1, g, 1, &n) && n == 0 && ! BRAssetSourceGroups(a, 1, 1, g, 1, NULL), "groups with NULLs");
    ck(BRAssetLargestSingleSource(NULL, 0) == 0 && BRAssetLargestSingleSource(NULL, 3) == 0, "no rows, no source");
    ck(! BRAssetOutpointsDistinct(NULL, 1) && BRAssetOutpointsDistinct(NULL, 0), "distinct with no rows");
    ck(! BRAssetPlanInputsDistinct(NULL, a, d), "a NULL plan's inputs are not called distinct");
    ck(BRAssetInputJudge(BR_ASSET_STACK_FOUND, NULL, 1, ASSET, 5) == BR_ASSET_INPUT_MISMATCH &&
       BRAssetInputJudge((BRAssetStackLookup)9, NULL, 0, ASSET, 5) == BR_ASSET_INPUT_UNAVAILABLE, "judge with NULL entries, an undefined lookup");
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, NULL, a, 1, d, pf, 1, 1, 0, NULL, 0, NULL, 0, NULL, 0, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_ARGUMENTS, "a choice with no asset id");
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, a, 1, d, NULL, 1, 1, 0, NULL, 0, NULL, 0, NULL, 0, b.marks, 1, b.work, N(b.work));
    ck(o.kind == BR_ASSET_SEND_NOT_PLANNED && o.plan.why == BR_ASSET_PLAN_WHY_ARGUMENTS, "a choice with no parent facts");
    ck(BRAssetEncodeTransferScript(3, NULL, 2, NULL, 0, NULL, NULL) == BR_ASSET_ENCODE_REFUSED, "an encode of NULL instructions");
    ck(BRAssetBitsWriteBytes(NULL, NULL, 1) == 0, "a write with no writer");
}

static void capacity(void)
{
    BRAssetRow a[2], d[2];
    BRAssetSendOutcome o;
    size_t work[16];
    uint8_t marks[2];
    BRAssetFact pf[2] = { BR_ASSET_FACT_NO, BR_ASSET_FACT_NO };
    BRAssetSourceGroup g[1];
    size_t n = 0;

    a[0] = row("a", 0, SX, 2, 600, 5); a[1] = row("b", 0, SY, 2, 600, 5);
    d[0] = row("d", 0, SX, 2, 100000, 0); d[1] = row("e", 0, SX, 2, 100000, 0);
    ck(BRAssetCoinSelect(a, 2, d, 2, 1, 0, 0, 0, AO, 1, DO_, 2).status == BR_ASSET_SELECT_CAPACITY, "an asset order short of the rows");
    ck(BRAssetCoinSelect(a, 2, d, 2, 1, 0, 0, 0, AO, 2, DO_, 1).status == BR_ASSET_SELECT_CAPACITY, "a fee order short of the rows");
    ck(BRAssetCoinSelect(a, 2, d, 2, 1, -1, 0, 0, AO, 1, DO_, 1).status == BR_ASSET_SELECT_REFUSED, "an argument refused before capacity");
    ck(_BRAssetPlanTransfer(a, 1, d, 2, 1, 0, AO, 1, DO_, 1).status == BR_ASSET_PLAN_CAPACITY, "a plan short of room");
    ck(BRAssetSendWorkLength(2, 2) == 8 && BRAssetSendWorkLength(SIZE_MAX / 2, 1) == SIZE_MAX && BRAssetSendWorkLength(1, SIZE_MAX) == SIZE_MAX,
       "the work length, and its overflow");
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, a, 2, d, pf, 2, 1, 0, NULL, 0, NULL, 0, NULL, 0, marks, 2, work, 7);
    ck(o.kind == BR_ASSET_SEND_CAPACITY, "work one short");
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, a, 2, d, pf, 2, 1, 0, NULL, 0, NULL, 0, NULL, 0, marks, 1, work, 8);
    ck(o.kind == BR_ASSET_SEND_CAPACITY, "marks one short");
    o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, ASSET, a, 2, d, pf, 2, 1, 0, NULL, 0, NULL, 0, NULL, 0, marks, 2, work, 8);
    ck(o.kind == BR_ASSET_SEND_LOOKUP, "exact room is enough");
    ck(! BRAssetSourceGroups(a, 2, 1, g, 1, &n) && n == 2, "groups short of room say how many there are");
    ck(BRAssetSourceGroups(a, 2, 1, NULL, 0, &n) == 0 && n == 2 && BRAssetSourceGroups(a, 2, 99, NULL, 0, &n) && n == 0, "or with no buffer");
}

static int isZero(const void *p, size_t len)
{
    const uint8_t *b = (const uint8_t *)p;
    size_t i;

    for (i = 0; i < len; i++) if (b[i]) return 0;
    return 1;
}

static void zero_structs(void)
{
    BRAssetSelection s;
    BRAssetTransferPlan p;
    BRAssetSendOutcome o;
    BRAssetSendKey k;

    memset(&s, 0, sizeof(s)); memset(&p, 0, sizeof(p)); memset(&o, 0, sizeof(o)); memset(&k, 0, sizeof(k));
    ck(s.status == BR_ASSET_SELECT_REFUSED, "a selection nobody filled is refused");
    ck(p.status == BR_ASSET_PLAN_REFUSED && ! BRAssetPlanInputsDistinct(&p, NULL, NULL), "a plan nobody filled is refused, and signs nothing");
    ck(o.kind == BR_ASSET_SEND_UNVERIFIED, "a choice nobody filled sends nothing");
    ck((int)BR_ASSET_INPUT_UNAVAILABLE == 0 && (int)BR_ASSET_ENCODE_REFUSED == 0, "the verdict and the encode nobody filled refuse");
    ck(isZero(&k, sizeof(k)), "a key nobody filled is zero");
}

static void constants(void)
{
    ck(BR_ASSET_MIN_RELAY_FEE_PER_KB == MIN_FEE_PER_KB && BR_ASSET_MIN_RELAY_FEE_PER_KB == DEFAULT_FEE_PER_KB,
       "the fee floor is DigiByte's minimum relay fee (BRWallet.h)");
    ck(BR_ASSET_CHANGE_DUST_SATS == BR_DUST_P2PKH_SATS, "the change line is the P2PKH dust line (BRDustPolicy.h)");
    ck(BR_ASSET_FIXED_PRECISION_MAX == BR_ASSET_MAX_ISSUANCE, "the fixed-precision maximum is BRAssetAllocate.h's issuance maximum");
    ck(BR_ASSET_ENCODE_MAX_SCRIPT == 83 && BR_ASSET_ENCODE_MAX_PAYLOAD == 80 && BR_ASSET_PLAN_MAX_INSTRUCTIONS == 38 &&
       BR_ASSET_OPCODE_TRANSFER == 0x15, "the carrier's limits");
    ck(BR_ASSET_PLAN_MAX_INSTRUCTIONS <= BR_ASSET_MAX_INSTRUCTIONS, "a planned list fits the decoder's buffer");
    ck(asset_send_plan_kat_tu2(), "a second translation unit, both headers included twice");
}

// ---- main ---------------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "enc_bitwriter", enc_bitwriter }, { "enc_sffc", enc_sffc }, { "enc_fixtures", enc_fixtures },
    { "enc_validation", enc_validation }, { "enc_push_framing", enc_push_framing },
    { "sel_basic", sel_basic }, { "sel_order", sel_order }, { "sel_insufficient", sel_insufficient },
    { "sec_preconditions", sec_preconditions }, { "sec_empty_lists", sec_empty_lists }, { "sec_overflow", sec_overflow },
    { "sec_largest_first", sec_largest_first }, { "sec_conservation", sec_conservation }, { "sec_distinct", sec_distinct },
    { "dust_marker", dust_marker },
    { "fee_default", fee_default }, { "fee_linear", fee_linear }, { "fee_more", fee_more }, { "fee_floor", fee_floor },
    { "fee_covers", fee_covers },
    { "plan_exact", plan_exact }, { "plan_exact_no_fee_input", plan_exact_no_fee_input }, { "plan_dust_remainder", plan_dust_remainder },
    { "plan_partial", plan_partial }, { "plan_consolidating", plan_consolidating }, { "plan_approval", plan_approval },
    { "choose_judge", choose_judge }, { "choose_confirmed_planned", choose_confirmed_planned },
    { "choose_overflow_rows", choose_overflow_rows }, { "choose_overflow_transfer", choose_overflow_transfer },
    { "choose_set_aside", choose_set_aside }, { "choose_unconfirmed", choose_unconfirmed }, { "choose_no_answer", choose_no_answer },
    { "choose_spent_set_aside", choose_spent_set_aside }, { "choose_spans", choose_spans }, { "choose_one_address", choose_one_address },
    { "choose_fee_coins_data", choose_fee_coins_data }, { "choose_fee_rule_note", choose_fee_rule_note },
    { "choose_not_asked_again", choose_not_asked_again }, { "choose_not_yet_asked_later", choose_not_yet_asked_later },
    { "src_unassigned_stay", src_unassigned_stay }, { "src_red_previous_layout", src_red_previous_layout },
    { "src_whole_holding", src_whole_holding }, { "src_quantity_above_entry", src_quantity_above_entry },
    { "src_first_entry_other", src_first_entry_other }, { "src_fee_coin_elsewhere", src_fee_coin_elsewhere },
    { "src_fee_coin_at_source", src_fee_coin_at_source }, { "src_last_output", src_last_output },
    { "src_two_addresses", src_two_addresses }, { "src_groups", src_groups },
    { "qty_div0", qty_div0 }, { "qty_div2", qty_div2 }, { "qty_div8", qty_div8 }, { "qty_separators", qty_separators },
    { "qty_refused_forms", qty_refused_forms }, { "qty_zero", qty_zero }, { "qty_decimals", qty_decimals }, { "qty_empty", qty_empty },
    { "qty_range", qty_range }, { "qty_length", qty_length }, { "qty_whitespace", qty_whitespace },
    { "qty_divisibility", qty_divisibility }, { "qty_format", qty_format },
    { "qty_locale", qty_locale }, { "qty_roundtrip", qty_roundtrip },
    { "enc_rules", enc_rules }, { "gate_rules", gate_rules }, { "choose_prechecks", choose_prechecks }, { "choose_step", choose_step }, { "choose_repeated_outpoint", choose_repeated_outpoint },
    { "choose_malformed_answer", choose_malformed_answer },
    { "select_order", select_order }, { "plan_rules", plan_rules }, { "overflow_rules", overflow_rules },
    { "parse_rules", parse_rules }, { "format_rules", format_rules }, { "arguments", arguments }, { "capacity", capacity },
    { "zero_structs", zero_structs }, { "constants", constants }, { "model_sweep", model_sweep },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        CASES[i].run();
        printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
        return FAILED ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
