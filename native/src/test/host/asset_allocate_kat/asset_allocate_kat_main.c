//
//  asset_allocate_kat_main.c
//
//  Host KAT for BRAssetAllocate.h and BRAssetId.h: where the units of a DigiAsset transaction land,
//  what the wallet may believe about one of its outputs, what its inputs carried, and the id of the
//  asset an issuance creates. Moved from Android's Kotlin (core/.../asset/AssetTransferAllocator.kt,
//  AssetCredit.kt, and in AssetManager.kt resolveInputAssetUnits, backedInputStack,
//  transferOutputCredits and issuanceOutputCredits) and from the bridge
//  (native/src/main/jni/bridge/jni_asset.c, deriveIssuanceAssetId). The headers must answer exactly
//  what those answer and refuse exactly what they refuse, so every expectation here is fixed OUTSIDE
//  the headers, two ways:
//
//    1. BY HAND. The inputs and the expected answers of the Kotlin tests, value for value:
//       AssetTransferAllocatorTest (allocator_*), AssetHoldCoversCreditedOutputsTest's allocations
//       (hold_*), UnbackedAssetCreditTest (credit_*), AssetInputUnitsTest (units_*),
//       and AssetArithmeticNeverWrapsTest's input total (never_wraps_*). The ids of id_* were computed outside this repository from the
//       published rule (SHA-256, RIPEMD-160, base58check); the first of them is the id of an asset
//       issued on mainnet. The cases without one of those prefixes are this suite's own, for rules
//       the Kotlin tests reach only through their random streams or through the wallet's database.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C, with lists that
//       shift where the Kotlin's do, its own overflow test, and a flag where the Kotlin throws; the
//       id's model formats with snprintf and has its own base58check. model_sweep holds the headers
//       to it over a fixed pseudo-random corpus, and allocator_reference_model holds the allocator
//       to the Kotlin test tree's own reference (DigiAssetTransferModel) and to conservation, as
//       AssetTransferAllocatorTest's random test does (its kotlin.random stream cannot be replayed
//       in C; its shape and its invariants are used).
//
//  One case per process: `asset_allocate_kat <case>` prints PASS:/FAIL: lines and `RESULT <case>
//  pass|fail`; `list` prints the case names. run.sh builds one binary per ASSET_ALLOCATE_UNFIXED and
//  ASSET_ID_UNFIXED arm and holds each comparison arm to failing exactly its cases.
//

#include "BRAssetAllocate.h"
#include "BRAssetId.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int asset_allocate_kat_tu2(void);   // run.sh's second translation unit, which includes both headers twice

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

// `hex` (spaces allowed) into out[0, cap); the byte count. A stray character, an odd digit count or
// too many bytes is a broken vector, and fails the case.
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

// ---- the ids of the Kotlin tests, and a short name for each ----------------------------------

#define X  "Laxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"   // "La" + "x".repeat(36)
#define Y  "Layyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"
#define A  "Laaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
#define B  "Labbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
#define Z  "Lazzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
#define HY "Lhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh"   // a hybrid asset
#define DI "Lddddddddddddddddddddddddddddddddddddd"   // a dispersed asset
#define PT "unresolved:t"                             // a placeholder for an issuance not yet named
#define PC "unresolved:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

typedef struct { const char *name, *id; } Named;
static const Named NAMES[] = {
    { "X", X }, { "Y", Y }, { "A", A }, { "B", B }, { "Z", Z }, { "H", HY }, { "D", DI }, { "PT", PT }, { "PC", PC },
    { "Ua", "Ua1" }, { "Uh", "Uh7" }, { "Ud", "Ud9" }, { "La", "La" }, { "Lx", "Lx1" }, { "E", "" },
};

static const char *nameOf(const char *id)
{
    size_t i;

    if (! id) return "(null)";
    for (i = 0; i < N(NAMES); i++) if (strcmp(NAMES[i].id, id) == 0) return NAMES[i].name;
    return id;
}

static const char *idOf(const char *name, size_t len)
{
    size_t i;

    for (i = 0; i < N(NAMES); i++) if (strlen(NAMES[i].name) == len && strncmp(NAMES[i].name, name, len) == 0) return NAMES[i].id;
    ck(0, "the vector names an id \"%.*s\" this suite does not know", (int)len, name);
    return X;
}

// ---- the model: the Kotlin, line for line ---------------------------------------------------

#define M_STACKS 8     // inputs of one transaction here
#define M_ENTRIES 8    // entries of one input
#define M_OUTS 48      // outputs an allocation delivers to
#define M_HELD 16      // assets on one output

typedef struct { const char *id; int64_t count; } MUnits;               // AssetUnits
typedef struct { int known; int n; MUnits u[M_ENTRIES]; } MStack;       // List<AssetUnits>?
typedef struct { int n; MStack s[M_STACKS]; } MStacks;
typedef struct { int vout; int n; MUnits u[M_HELD]; } MOut;             // one entry of the LinkedHashMap

typedef struct {
    int kind;                      // 0 Indeterminate, 1 NotAnAssetTransfer, 2 Allocated
    const char *reason;
    int voided;
    int n; MOut o[M_OUTS];         // in the order the map was put to
} MResult;

static int mThrew;                 // ArithmeticException
static int mBroke;                 // a vector this model's fixed lists cannot hold: fails the case

static int64_t mAddExact(int64_t a, int64_t b)   // Math.addExact
{
    if ((b > 0 && a > INT64_MAX - b) || (b < 0 && a < INT64_MIN - b)) { mThrew = 1; return 0; }
    return a + b;
}

static int64_t mMultiplyExact(int64_t a, int64_t b)   // Math.multiplyExact
{
    int over;

    if (a == 0 || b == 0) return 0;
    if (a > 0) over = (b > 0) ? (a > INT64_MAX / b) : (b < INT64_MIN / a);
    else over = (b > 0) ? (a < INT64_MIN / b) : (b < INT64_MAX / a);
    if (over) { mThrew = 1; return 0; }
    return a * b;
}

// AssetTransferAllocator.aggregationOf: 0 AGGREGATABLE, 1 HYBRID, 2 DISPERSED, -1 null.
static int mAggregationOf(const char *id)
{
    if (strlen(id) < 3 || (id[0] != 'L' && id[0] != 'U')) return -1;

    switch (id[1]) {
        case 'a': return 0;
        case 'h': return 1;
        case 'd': return 2;
        default: return -1;
    }
}

static void mIndeterminate(MResult *r, const char *reason) { r->kind = 0; r->reason = reason; r->voided = 0; r->n = 0; }
static void mNotAnAssetTransfer(MResult *r) { r->kind = 1; r->reason = NULL; r->voided = 0; r->n = 0; }

// distribute's add(vout, units).
static void mAdd(MResult *r, int vout, const char *id, int64_t count)
{
    int i, j;

    for (i = 0; i < r->n && r->o[i].vout != vout; i++) { }
    if (i == r->n) {   // getOrPut
        if (r->n >= M_OUTS) { mBroke = 1; return; }
        r->o[i].vout = vout;
        r->o[i].n = 0;
        r->n++;
    }

    for (j = 0; j < r->o[i].n; j++) {
        if (strcmp(r->o[i].u[j].id, id) != 0) continue;
        r->o[i].u[j].count = mAddExact(r->o[i].u[j].count, count);
        return;
    }
    if (j >= M_HELD) { mBroke = 1; return; }
    r->o[i].u[j].id = id;
    r->o[i].u[j].count = count;
    r->o[i].n++;
}

// distribute's freshStacks(): known.filter { it.isNotEmpty() }.map { it.toMutableList() }.
static void mFresh(const MStack *known, int knownCount, MStacks *stacks)
{
    int i;

    stacks->n = 0;
    for (i = 0; i < knownCount; i++) if (known[i].n > 0) stacks->s[stacks->n++] = known[i];
}

// AssetTransferAllocator.distribute. headerAggregation is -2 for `::aggregationOf`, or the issuance
// header's own aggregation for `{ header.aggregation }`.
static void mDistribute(MResult *r, const BRAssetTransferInstruction *insts, size_t instCount,
                        const MStack *known, int knownCount, int outputCount, int burnType,
                        int legacyIgnoresInstructions, int headerAggregation)
{
    MStacks stacks;
    int voided = 0, index = 0, allowSkip = 1, valid, last, s, e, vout;
    size_t k;

    mFresh(known, knownCount, &stacks);
    r->n = 0;
    mThrew = 0;

    if (! legacyIgnoresInstructions) {
        for (k = 0; k < instCount; k++) {
            const BRAssetTransferInstruction *inst = &insts[k];
            int output = inst->outputIndex;
            int64_t amount = inst->amount, total, left;
            const char *removed;

            if (output < 0 || amount < 0) { mIndeterminate(r, "malformed instruction"); return; }
            total = inst->range ? mMultiplyExact((int64_t)output + 1, amount) : amount;
            if (mThrew) { mIndeterminate(r, "overflow"); return; }
            valid = 1;

            do {   // run { ... }
                if (index >= stacks.n || stacks.s[index].n == 0) { valid = 0; break; }
                left = total;
                removed = stacks.s[index].u[0].id;

                while (left > 0) {
                    MUnits top;

                    if (index >= stacks.n || stacks.s[index].n == 0) { valid = 0; break; }
                    top = stacks.s[index].u[0];
                    if (strcmp(top.id, removed) != 0) { valid = 0; break; }
                    allowSkip = 1;

                    if (top.count < left) {
                        int more = stacks.s[index].n > 1 || index + 1 < stacks.n;
                        int aggregation;

                        if (! more) { valid = 0; break; }
                        aggregation = (headerAggregation != -2) ? headerAggregation : mAggregationOf(top.id);
                        if (aggregation == 0) { }
                        else if (aggregation == 1) { valid = 0; break; }
                        else { mIndeterminate(r, "draw across entries of a non-aggregable asset"); return; }
                    }

                    if (top.count <= left) {
                        left -= top.count;
                        for (e = 1; e < stacks.s[index].n; e++) stacks.s[index].u[e - 1] = stacks.s[index].u[e];   // removeAt(0)
                        stacks.s[index].n--;
                        if (stacks.s[index].n == 0) { index++; allowSkip = 0; }
                    }
                    else {
                        stacks.s[index].u[0].count = top.count - left;
                        left = 0;
                    }
                }
                if (! valid) break;

                if (total > 0) {
                    int destroyed = burnType && ! inst->range && output == 31;

                    if (! destroyed) {
                        if (output >= outputCount) { valid = 0; break; }

                        for (vout = inst->range ? 0 : output; vout <= output; vout++) {
                            mAdd(r, vout, removed, amount);
                            if (mThrew) { mIndeterminate(r, "overflow"); return; }
                        }
                    }
                }

                if (inst->skip) {
                    if (allowSkip) index++;
                    allowSkip = 1;
                }
            } while (0);

            if (! valid) {
                r->n = 0;                               // outputs.clear()
                mFresh(known, knownCount, &stacks);
                voided = 1;
                break;
            }
        }
    }

    last = outputCount - 1;

    for (s = 0; s < stacks.n; s++) {
        for (e = 0; e < stacks.s[s].n; e++) {
            if (stacks.s[s].u[e].count <= 0) continue;
            mAdd(r, last, stacks.s[s].u[e].id, stacks.s[s].u[e].count);
            if (mThrew) { mIndeterminate(r, "overflow"); return; }
        }
    }
    r->kind = 2;
    r->reason = NULL;
    r->voided = voided;
}

static int mAnyPercent(const BRAssetTransferInstruction *insts, size_t instCount)
{
    size_t k;

    for (k = 0; k < instCount; k++) if (insts[k].percent) return 1;
    return 0;
}

// AssetTransferAllocator.allocate. `ruleFree` is the Kotlin's lambda over an asset id.
static void mAllocate(MResult *r, const BRAssetHeader *header, const BRAssetTransferInstruction *insts, size_t instCount,
                      const MStack *inputs, int inputCount, int outputCount, int (*ruleFree)(const char *))
{
    MStack known[M_STACKS];
    int burnType, i, e, any = 0;

    switch (header->operation) {
        case DA_TRANSFER: burnType = 0; break;
        case DA_BURN: burnType = 1; break;
        case DA_ISSUANCE: mIndeterminate(r, "issuance"); return;
        default: mIndeterminate(r, "unclassifiable carrier"); return;
    }
    if (outputCount < 1) { mIndeterminate(r, "no outputs"); return; }
    if (inputCount == 0) { mIndeterminate(r, "no inputs"); return; }
    for (i = 0; i < inputCount; i++) if (! inputs[i].known) { mIndeterminate(r, "an input's contents are unknown"); return; }

    for (i = 0; i < inputCount; i++) {   // it!!.filter { u -> u.count > 0 }
        known[i].known = 1;
        known[i].n = 0;
        for (e = 0; e < inputs[i].n; e++) if (inputs[i].u[e].count > 0) known[i].u[known[i].n++] = inputs[i].u[e];
        if (known[i].n > 0) any = 1;
    }
    if (! any) { mNotAnAssetTransfer(r); return; }
    if (mAnyPercent(insts, instCount)) { mIndeterminate(r, "percent instruction"); return; }

    for (i = 0; i < inputCount; i++) {
        for (e = 0; e < known[i].n; e++) {
            if (! ruleFree(known[i].u[e].id)) { mIndeterminate(r, "an input asset may carry transfer rules"); return; }
        }
    }
    mDistribute(r, insts, instCount, known, inputCount, outputCount, burnType,
                header->version < 3 && known[0].n == 0, -2);
}

// AssetTransferAllocator.allocateIssuance.
static void mAllocateIssuance(MResult *r, const BRAssetHeader *header, const BRAssetTransferInstruction *insts,
                              size_t instCount, const char *assetId, int outputCount, int payloadExact)
{
    MStack issued;

    if (header->operation != DA_ISSUANCE) { mIndeterminate(r, "not an issuance"); return; }
    if (! payloadExact) { mIndeterminate(r, "payload not read exactly"); return; }
    if (outputCount < 1) { mIndeterminate(r, "no outputs"); return; }
    if (! header->hasTotalQuantity) { mIndeterminate(r, "no issued amount"); return; }
    if (header->totalQuantity <= 0 || header->totalQuantity > ((int64_t)1 << 54) - 1) { mNotAnAssetTransfer(r); return; }
    if (mAnyPercent(insts, instCount)) { mIndeterminate(r, "percent instruction"); return; }
    issued.known = 1;
    issued.n = 1;
    issued.u[0].id = assetId;
    issued.u[0].count = header->totalQuantity;
    mDistribute(r, insts, instCount, &issued, 1, outputCount, 0, 0, (int)header->aggregation);
}

// OutputCredit: 0 Unknown, 1 Holds(units, label), 2 Mixed.
typedef struct { int kind; int hasUnits; const char *id; int64_t count; const char *label; } MCredit;

static MCredit mCredit(int kind) { MCredit c; c.kind = kind; c.hasUnits = 0; c.id = NULL; c.count = 0; c.label = NULL; return c; }

static int mNamedByAnyInstruction(const BRAssetTransferInstruction *insts, size_t instCount, int vout)
{
    size_t k;

    for (k = 0; k < instCount; k++) {
        if (insts[k].range ? (vout <= insts[k].outputIndex) : (insts[k].outputIndex == vout)) return 1;
    }
    return 0;
}

// AssetCreditRules.forOutput.
static MCredit mForOutput(const MResult *r, const BRAssetTransferInstruction *insts, size_t instCount, int vout,
                          int outputCount)
{
    MCredit c = mCredit(1);
    const MOut *held = NULL;
    const char *moved = NULL;
    int i, e, distinct = 0;

    if (r->kind == 1) return c;

    if (r->kind == 2) {
        for (i = 0; i < r->n; i++) {   // assetsMoved: outputs.values.flatten().map { it.assetId }.distinct()
            for (e = 0; e < r->o[i].n; e++) {
                if (distinct == 0) { moved = r->o[i].u[e].id; distinct = 1; }
                else if (strcmp(moved, r->o[i].u[e].id) != 0) distinct = 2;
            }
            if (r->o[i].vout == vout) held = &r->o[i];
        }
        if (! held || held->n == 0) { c.label = (distinct == 1) ? moved : NULL; return c; }
        if (held->n == 1) { c.hasUnits = 1; c.id = held->u[0].id; c.count = held->u[0].count; c.label = held->u[0].id; return c; }
        return mCredit(2);
    }
    if ((int32_t)((uint32_t)outputCount - 1u) != vout && ! mNamedByAnyInstruction(insts, instCount, vout)) return c;
    return mCredit(0);
}

// AssetTxQuantity.consumptionUncountable.
static int mConsumptionUncountable(const BRAssetHeader *header, const BRAssetTransferInstruction *insts, size_t instCount)
{
    int64_t assigned = 0, consumed;
    size_t k;

    if (header->operation != DA_ISSUANCE && header->operation != DA_TRANSFER && header->operation != DA_BURN) return 0;
    mThrew = 0;

    for (k = 0; k < instCount; k++) {
        if (insts[k].percent) continue;
        if (insts[k].amount < 0 || insts[k].outputIndex < 0) return 1;
        consumed = insts[k].range ? mMultiplyExact((int64_t)insts[k].outputIndex + 1, insts[k].amount) : insts[k].amount;
        if (mThrew) return 1;
        assigned = mAddExact(assigned, consumed);
        if (mThrew) return 1;
    }
    return 0;
}

// AssetCreditRules.maxDeliverable: 1 and *max, or 0 for null.
static int mMaxDeliverable(const BRAssetHeader *header, const BRAssetTransferInstruction *insts, size_t instCount,
                           int vout, int outputCount, int64_t *max)
{
    int burnMarker = (header->operation == DA_BURN);
    size_t k;

    *max = 0;
    if (mConsumptionUncountable(header, insts, instCount)) return 1;
    if (vout == (int32_t)((uint32_t)outputCount - 1u)) return 0;
    mThrew = 0;

    for (k = 0; k < instCount; k++) {
        int named = insts[k].range ? (vout <= insts[k].outputIndex) : (insts[k].outputIndex == vout);

        if (! named || (burnMarker && ! insts[k].range && insts[k].outputIndex == 31)) continue;
        if (insts[k].percent) return 0;
        *max = mAddExact(*max, insts[k].amount);
        if (mThrew) { *max = 0; return 1; }
    }
    return 1;
}

// AssetCreditRules.fromIndexer: 1 and *c, or 0 for null. lookup: 0 Unavailable, 1 NotUnspent, 2 Found.
static int mFromIndexer(int lookup, const MUnits *entries, int entryCount, MCredit *c)
{
    const char *first = NULL;
    int64_t sum = 0;
    int i, distinct = 0;

    *c = mCredit(0);
    if (lookup != 2) return 0;

    for (i = 0; i < entryCount; i++) {   // entries.filter { it.count > 0 }, ids = ... .distinct()
        if (entries[i].count <= 0) continue;
        if (distinct == 0) { first = entries[i].id; distinct = 1; }
        else if (strcmp(first, entries[i].id) != 0) distinct = 2;
    }
    if (distinct == 0) { *c = mCredit(1); return 1; }
    if (distinct > 1) { *c = mCredit(2); return 1; }
    mThrew = 0;

    for (i = 0; i < entryCount; i++) {
        if (entries[i].count <= 0) continue;
        sum = mAddExact(sum, entries[i].count);
        if (mThrew) return 0;
    }
    *c = mCredit(1);
    c->hasUnits = 1;
    c->id = first;
    c->count = sum;
    c->label = first;
    return 1;
}

// resolveInputAssetUnits. The row's quantity is facts->quantity when facts->hasRow, else null; the
// three-valued answers are BRAssetFact's. `unresolved` is the Kotlin's ZeroRowInputs, or absent.
typedef struct { int known; int64_t total; int rowCount; int rows[M_STACKS * 4]; int64_t otherUnits; } MResolved;

static MResolved mResolve(const BRAssetInputFacts *inputs, int inputCount, int unresolved)
{
    MResolved r;
    int64_t total = 0;
    int zeroRows[M_STACKS * 4], zeroRowCount = 0, i;

    memset(&r, 0, sizeof(r));
    mThrew = 0;

    for (i = 0; i < inputCount; i++) {
        if (inputs[i].hasRow && inputs[i].quantity > 0) {
            total = mAddExact(total, inputs[i].quantity);
            if (mThrew) return r;
            continue;
        }
        if (inputs[i].hasRow) {
            if (inputs[i].rowIsTargeted == BR_ASSET_FACT_NO) continue;
            if (! unresolved) return r;
            zeroRows[zeroRowCount++] = i;
            continue;
        }
        if (inputs[i].fundingIsAssetTx != BR_ASSET_FACT_NO) return r;
    }
    if (zeroRowCount == 0) { r.known = 1; r.total = total; return r; }
    r.rowCount = zeroRowCount;
    memcpy(r.rows, zeroRows, sizeof(zeroRows));
    r.otherUnits = total;
    return r;
}

// backedInputStack.
static MStack mBacked(const BRAssetInputFacts *f)
{
    MStack unknown, nothing, one;

    unknown.known = 0; unknown.n = 0;
    nothing.known = 1; nothing.n = 0;
    one.known = 1; one.n = 1;

    if (f->hasRow) {
        if (f->credit == BR_ASSET_CREDIT_BACKED || f->credit == BR_ASSET_CREDIT_VERIFIED) {   // AssetCredit.counts
            if (f->quantity <= 0) return nothing;
            if (! f->assetId || f->assetId[0] == '\0') return unknown;
            one.u[0].id = f->assetId;
            one.u[0].count = f->quantity;
            return one;
        }
        if (f->credit == BR_ASSET_CREDIT_UNCHECKED && f->quantity == 0 && f->rowIsTargeted == BR_ASSET_FACT_NO) return nothing;
        return unknown;
    }
    return (f->fundingHasDataOutput == BR_ASSET_FACT_NO) ? nothing : unknown;
}

// ---- the id's model: the bridge's deriveIssuanceAssetId, with its own base58check ---------------

static size_t mBase58Check(const uint8_t *data, size_t len, char *out, size_t cap)
{
    static const char alphabet[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    uint8_t buf[64], sha[32], digits[128];
    size_t i, j, zeros = 0, n = 0, total = len + 4;

    if (total > sizeof(buf)) return 0;
    memcpy(buf, data, len);
    BRSHA256(sha, data, len);
    BRSHA256(sha, sha, sizeof(sha));
    memcpy(buf + len, sha, 4);
    while (zeros < total && buf[zeros] == 0) zeros++;

    for (i = zeros; i < total; i++) {   // digits = digits * 256 + buf[i], least significant first
        unsigned carry = buf[i];

        for (j = 0; j < n; j++) {
            carry += (unsigned)digits[j] << 8;
            digits[j] = (uint8_t)(carry % 58);
            carry /= 58;
        }
        while (carry > 0) { digits[n++] = (uint8_t)(carry % 58); carry /= 58; }
    }
    if (zeros + n + 1 > cap) return 0;
    for (i = 0; i < zeros; i++) out[i] = '1';
    for (i = 0; i < n; i++) out[zeros + i] = alphabet[digits[n - 1 - i]];
    out[zeros + n] = '\0';
    return zeros + n + 1;
}

// 1 and the id in out[0, 65), or 0 where the bridge returns NULL.
static int mId(const char *txidHex, int firstInputVout, int locked, int aggregation, int divisibility, char *out)
{
    static const uint8_t prefixes[3][2] = { { 0x20, 0xce }, { 0x21, 0x02 }, { 0x20, 0xe4 } };
    char input[64 + 1 + 10 + 1];
    uint8_t sha[32], payload[24];
    size_t outLen;
    int inputLen;

    out[0] = '\0';
    if (! txidHex) return 0;
    if (firstInputVout < 0) return 0;
    if (aggregation < 0 || aggregation > 2) return 0;
    if (divisibility < 0 || divisibility > 7) return 0;
    if (! locked) return 0;
    if (strlen(txidHex) != 64) return 0;
    inputLen = snprintf(input, sizeof(input), "%s:%d", txidHex, firstInputVout);
    if (inputLen <= 0 || (size_t)inputLen >= sizeof(input)) return 0;
    BRSHA256(sha, input, (size_t)inputLen);
    payload[0] = prefixes[aggregation][0];
    payload[1] = prefixes[aggregation][1];
    BRRMD160(payload + 2, sha, sizeof(sha));
    payload[22] = 0x00;
    payload[23] = (uint8_t)(divisibility & 0xFF);
    outLen = mBase58Check(payload, sizeof(payload), out, 65);
    if (outLen == 0 || outLen > 64) { out[0] = '\0'; return 0; }
    return 1;
}

// ---- answers as text: the headers' and the model's, in one form ------------------------------

static const char *whyText(BRAssetAllocationWhy why)   // the Kotlin's reason, word for word
{
    switch (why) {
        case BR_ASSET_WHY_NONE: return "(none)";
        case BR_ASSET_WHY_ARGUMENTS: return "arguments";
        case BR_ASSET_WHY_CAPACITY: return "capacity";
        case BR_ASSET_WHY_ISSUANCE: return "issuance";
        case BR_ASSET_WHY_UNCLASSIFIABLE: return "unclassifiable carrier";
        case BR_ASSET_WHY_NO_OUTPUTS: return "no outputs";
        case BR_ASSET_WHY_NO_INPUTS: return "no inputs";
        case BR_ASSET_WHY_INPUT_UNKNOWN: return "an input's contents are unknown";
        case BR_ASSET_WHY_PERCENT: return "percent instruction";
        case BR_ASSET_WHY_RULES: return "an input asset may carry transfer rules";
        case BR_ASSET_WHY_NOT_ISSUANCE: return "not an issuance";
        case BR_ASSET_WHY_PAYLOAD_NOT_EXACT: return "payload not read exactly";
        case BR_ASSET_WHY_NO_ISSUED_AMOUNT: return "no issued amount";
        case BR_ASSET_WHY_MALFORMED_INSTRUCTION: return "malformed instruction";
        case BR_ASSET_WHY_NON_AGGREGABLE_DRAW: return "draw across entries of a non-aggregable asset";
        case BR_ASSET_WHY_OVERFLOW: return "overflow";
    }
    return "(a reason this suite does not know)";
}

#define TEXT 4096

static size_t put(char *buf, size_t at, const char *fmt, ...)
{
    va_list ap;
    int n;

    if (at >= TEXT) return at;
    va_start(ap, fmt);
    n = vsnprintf(buf + at, TEXT - at, fmt, ap);
    va_end(ap);
    return (n < 0) ? at : at + (size_t)n;
}

// "indeterminate: <reason>" | "none" | "allocated[ voided]: <vout>:<id>:<count> ..." in the order given.
static void showAllocation(char *buf, BRAssetAllocation r, const BRAssetOutputUnits *out)
{
    size_t at = 0, i;

    buf[0] = '\0';
    if (r.status == BR_ASSET_ALLOCATION_INDETERMINATE) at = put(buf, at, "indeterminate: %s", whyText(r.why));
    else if (r.status == BR_ASSET_ALLOCATION_NOT_AN_ASSET_TRANSFER) at = put(buf, at, "none");
    else if (r.status == BR_ASSET_ALLOCATION_ALLOCATED) {
        at = put(buf, at, r.instructionsVoided ? "allocated voided:" : "allocated kept:");
        for (i = 0; i < r.count; i++) at = put(buf, at, " %d:%s:%lld", (int)out[i].vout, nameOf(out[i].assetId), (long long)out[i].count);
    }
    else at = put(buf, at, "(status %d)", (int)r.status);

    if (r.status != BR_ASSET_ALLOCATION_ALLOCATED && (r.count != 0 || r.instructionsVoided != 0)) put(buf, at, " !count or voided set");
    else if (r.status != BR_ASSET_ALLOCATION_INDETERMINATE && r.why != BR_ASSET_WHY_NONE) put(buf, at, " !a reason set");
    else if (r.instructionsVoided != 0 && r.instructionsVoided != 1) put(buf, at, " !voided is %d", r.instructionsVoided);
}

static void showModel(char *buf, const MResult *r)
{
    size_t at = 0;
    int i, e;

    buf[0] = '\0';
    if (r->kind == 0) put(buf, at, "indeterminate: %s", r->reason);
    else if (r->kind == 1) put(buf, at, "none");
    else {
        at = put(buf, at, r->voided ? "allocated voided:" : "allocated kept:");
        for (i = 0; i < r->n; i++) {
            for (e = 0; e < r->o[i].n; e++) {
                at = put(buf, at, " %d:%s:%lld", r->o[i].vout, nameOf(r->o[i].u[e].id), (long long)r->o[i].u[e].count);
            }
        }
    }
}

// "unknown" | "mixed" | "holds nothing[ label=<id>]" | "holds <id>:<count> label=<id>".
static void showCreditFields(char *buf, int kind, int hasUnits, const char *id, int64_t count, const char *label)
{
    size_t at = 0;

    buf[0] = '\0';
    if (kind == 0) at = put(buf, at, "unknown");
    else if (kind == 2) at = put(buf, at, "mixed");
    else if (kind == 1) {
        if (hasUnits) at = put(buf, at, "holds %s:%lld", nameOf(id), (long long)count);
        else at = put(buf, at, "holds nothing");
        if (label) at = put(buf, at, " label=%s", nameOf(label));
    }
    else at = put(buf, at, "(kind %d)", kind);

    if (kind != 1 && (hasUnits || id || count || label)) put(buf, at, " !fields set");
    else if (kind == 1 && ! hasUnits && (id || count)) put(buf, at, " !units set");
}

static void showCredit(char *buf, BRAssetOutputCredit c)
{
    showCreditFields(buf, (int)c.kind, c.hasUnits, c.assetId, c.count, c.label);
}

static void showMCredit(char *buf, MCredit c) { showCreditFields(buf, c.kind, c.hasUnits, c.id, c.count, c.label); }

// "?" | "-" | "<id>:<count>:<ruleFree>,..."
static void showStack(char *buf, BRAssetInputStack s)
{
    size_t at = 0, i;

    buf[0] = '\0';
    if (! s.known) { at = put(buf, at, "?"); if (s.unitCount || s.units) put(buf, at, " !fields set"); return; }
    if (s.unitCount == 0) { at = put(buf, at, "-"); if (s.units) put(buf, at, " !units set"); return; }
    for (i = 0; i < s.unitCount; i++) {
        at = put(buf, at, "%s%s:%lld:%d", i ? "," : "", nameOf(s.units[i].assetId), (long long)s.units[i].count, s.units[i].ruleFree);
    }
}

// "known <units>" | "unknown" | "unknown rows=<i>,<i> other=<units>".
static void showResolved(char *buf, BRAssetInputUnits r, const size_t *rows)
{
    size_t at = 0, i;

    buf[0] = '\0';
    if (r.known) {
        at = put(buf, at, "known %lld", (long long)r.units);
        if (r.zeroRowCount || r.otherUnits) put(buf, at, " !rows set");
        return;
    }
    at = put(buf, at, "unknown");
    if (r.units) at = put(buf, at, " !units set");
    if (r.zeroRowCount == 0) { if (r.otherUnits) put(buf, at, " !other set"); return; }
    at = put(buf, at, " rows=");
    for (i = 0; i < r.zeroRowCount; i++) at = put(buf, at, "%s%d", i ? "," : "", (int)rows[i]);
    put(buf, at, " other=%lld", (long long)r.otherUnits);
}

static void showMResolved(char *buf, const MResolved *r)
{
    size_t at = 0;
    int i;

    buf[0] = '\0';
    if (r->known) { put(buf, at, "known %lld", (long long)r->total); return; }
    at = put(buf, at, "unknown");
    if (r->rowCount == 0) return;
    at = put(buf, at, " rows=");
    for (i = 0; i < r->rowCount; i++) at = put(buf, at, "%s%d", i ? "," : "", r->rows[i]);
    put(buf, at, " other=%lld", (long long)r->otherUnits);
}

// ---- the vectors' notation --------------------------------------------------------------------
//
// A number: decimal (a leading '-' allowed), or MAX (INT64_MAX), HALF (INT64_MAX / 2), HALF1
// (INT64_MAX / 2 + 1), MAXISSUE (2^54 - 1). Instructions: "<outputIndex>:<amount>[:<flags>]" separated
// by spaces, flags s(kip) r(ange) p(ercent). Stacks: inputs separated by '/', each "-" (no entry),
// "?" (not known) or entries "<id>:<count>" separated by ',', a '!' after the count when the asset
// may carry transfer rules.

static int64_t num(const char **p)
{
    static const struct { const char *word; int64_t v; } words[] = {
        { "MAXISSUE", 18014398509481983LL }, { "MIN", INT64_MIN }, { "MAX", INT64_MAX }, { "HALF1", INT64_MAX / 2 + 1 }, { "HALF", INT64_MAX / 2 },
    };
    int64_t v = 0;
    int neg = 0, digits = 0;
    size_t i;

    for (i = 0; i < N(words); i++) {
        size_t n = strlen(words[i].word);

        if (strncmp(*p, words[i].word, n) == 0) { *p += n; return words[i].v; }
    }
    if (**p == '-') { neg = 1; (*p)++; }
    while (**p >= '0' && **p <= '9') { v = v * 10 + (**p - '0'); (*p)++; digits++; }
    if (! digits) ck(0, "the vector has no number at \"%s\"", *p);
    return neg ? -v : v;
}

static uint8_t S[256];
static size_t SL;
static BRAssetHeader H;
static BRAssetTransferInstruction L[BR_ASSET_MAX_INSTRUCTIONS];
static size_t LN;
static int EXACT;

static void parseInsts(const char *spec)
{
    const char *p = spec;

    LN = 0;
    while (*p) {
        BRAssetTransferInstruction *t;

        if (*p == ' ') { p++; continue; }
        if (LN >= N(L)) { ck(0, "too many instructions in \"%s\"", spec); return; }
        t = &L[LN++];
        memset(t, 0, sizeof(*t));
        t->outputIndex = (int32_t)num(&p);
        if (*p == ':') p++;
        t->amount = num(&p);
        if (*p == ':') {
            for (p++; *p && *p != ' '; p++) {
                if (*p == 's') t->skip = 1;
                else if (*p == 'r') t->range = 1;
                else if (*p == 'p') t->percent = 1;
                else ck(0, "unknown flag %c in \"%s\"", *p, spec);
            }
        }
        t->isBurn = ! t->range && t->outputIndex == 31;   // the Kotlin test's ti()
    }
    H.instructionCount = LN;
}

// The Kotlin tests' header(operation, instructions, version): opcode 0x15, aggregatable, locked, no
// issued amount, divisibility 0. Read exactly.
static void hdr(BRAssetOperation op, int version, const char *insts)
{
    memset(&H, 0, sizeof(H));
    H.kind = BR_ASSET_SCRIPT_ASSET;
    H.version = version;
    H.opcode = 0x15;
    H.operation = op;
    H.locked = 1;
    H.aggregation = BR_ASSET_AGGREGATABLE;
    parseInsts(insts);
    EXACT = 1;
}

// An issuance header with `total` and no instruction (UnbackedAssetCreditTest's h(q)).
static void issuanceHdr(int opcode, int64_t total)
{
    hdr(DA_ISSUANCE, 3, "");
    H.opcode = opcode;
    H.hasTotalQuantity = 1;
    H.totalQuantity = total;
}

// `hex` decoded as the decoder reads it, and whether the payload reads exactly.
static void script(const char *hex)
{
    BRAssetScriptKind k;

    memset(&H, 0, sizeof(H));
    SL = hx(hex, S, sizeof(S));
    k = BRAssetDecodeInstructions(S, SL, &H, L, N(L));
    ck(k == BR_ASSET_SCRIPT_ASSET, "%s decodes as a DigiAsset carrier", hex);
    LN = (k == BR_ASSET_SCRIPT_ASSET) ? H.instructionCount : 0;
    EXACT = BRAssetPayloadReadsExactly(S, SL, &H, L, LN);
}

static BRAssetUnits U[M_STACKS][M_ENTRIES];
static BRAssetInputStack IN[M_STACKS];
static MStack MIN[M_STACKS];
static size_t INN;
static const char *RULED[M_STACKS * M_ENTRIES];   // ids marked '!' on a counted entry
static size_t RULEDN;

static int mRuleFree(const char *id)
{
    size_t i;

    for (i = 0; i < RULEDN; i++) if (strcmp(RULED[i], id) == 0) return 0;
    return 1;
}

static void parseStacks(const char *spec)
{
    const char *p = spec;

    INN = 0;
    RULEDN = 0;
    for (;;) {
        BRAssetInputStack *s;
        MStack *m;

        if (INN >= M_STACKS) { ck(0, "too many inputs in \"%s\"", spec); return; }
        s = &IN[INN];
        m = &MIN[INN];
        INN++;
        s->known = 1; s->units = U[INN - 1]; s->unitCount = 0;
        m->known = 1; m->n = 0;

        if (*p == '?') { s->known = 0; s->units = NULL; m->known = 0; p++; }
        else if (*p == '-') p++;
        else {
            for (;;) {
                const char *name = p;
                BRAssetUnits *u;

                while (*p && *p != ':') p++;
                if (s->unitCount >= M_ENTRIES || ! *p) { ck(0, "a broken stack \"%s\"", spec); return; }
                u = &U[INN - 1][s->unitCount++];
                u->assetId = idOf(name, (size_t)(p - name));
                p++;
                u->count = num(&p);
                u->ruleFree = 1;
                if (*p == '!') { u->ruleFree = 0; p++; if (u->count > 0) RULED[RULEDN++] = u->assetId; }
                m->u[m->n].id = u->assetId;
                m->u[m->n].count = u->count;
                m->n++;
                if (*p != ',') break;
                p++;
            }
        }
        if (*p != '/') break;
        p++;
    }
    if (*p) ck(0, "a broken stack \"%s\"", spec);
}

// ---- comparing ------------------------------------------------------------------------------

// Does the answer `got` say what `want` says? Equal, or `want` and then more words, none of them a
// remark (" !...") about a field that should not be set.
static int says(const char *got, const char *want)
{
    size_t n = strlen(want);

    if (strcmp(got, want) == 0) return 1;
    return strncmp(got, want, n) == 0 && got[n] == ' ' && ! strchr(got, '!');
}

// An allocation's text with its deliveries in output order (a stable sort: each output keeps the
// order of its assets). Kotlin's map equality does not see the order of its keys; a list's does.
static void byOutput(const char *in, char *out)
{
    char head[64], tok[64][80];
    int vouts[64], n = 0, i, j;
    const char *p = strchr(in, ':');
    size_t at;

    if (strncmp(in, "allocated", 9) != 0 || ! p) { strcpy(out, in); return; }
    at = (size_t)(p - in) + 1;
    memcpy(head, in, at); head[at] = '\0';
    p++;
    while (*p == ' ') {
        const char *e = strchr(p + 1, ' ');
        size_t len = e ? (size_t)(e - p - 1) : strlen(p + 1);

        if (n >= 64 || len >= sizeof(tok[0])) { strcpy(out, in); return; }
        memcpy(tok[n], p + 1, len); tok[n][len] = '\0';
        vouts[n] = atoi(tok[n]);
        n++;
        p += len + 1;
    }
    for (i = 1; i < n; i++) {
        for (j = i; j > 0 && vouts[j - 1] > vouts[j]; j--) {
            char t[80]; int v = vouts[j];

            memcpy(t, tok[j], sizeof(t)); memcpy(tok[j], tok[j - 1], sizeof(t)); memcpy(tok[j - 1], t, sizeof(t));
            vouts[j] = vouts[j - 1]; vouts[j - 1] = v;
        }
    }
    strcpy(out, head);
    for (i = 0; i < n; i++) { strcat(out, " "); strcat(out, tok[i]); }
}

// An allocation `want`: "none", "indeterminate[: <reason>]", "allocated" (any allocation),
// "allocated: ..." (whether the instructions were voided is not said), "allocated kept: ..." or
// "allocated voided: ...". Deliveries in any order of outputs.
static int saysAllocation(const char *got, const char *want)
{
    char g[TEXT], w[TEXT];

    if (strcmp(want, "allocated") == 0) return strncmp(got, "allocated", 9) == 0 && ! strchr(got, '!');
    if (strcmp(want, "indeterminate") == 0) return strncmp(got, "indeterminate: ", 15) == 0 && ! strchr(got, '!');
    byOutput(got, g);
    byOutput(want, w);
    if (strncmp(w, "allocated:", 10) == 0) {
        const char *c = strchr(g, ':');

        if (strncmp(g, "allocated", 9) != 0 || ! c) return 0;
        memmove(g + 9, c, strlen(c) + 1);
    }
    return strcmp(g, w) == 0;
}

static void both(const char *what, const char *got, const char *model, int match, const char *want)
{
    ck(match, "%s: %s (want %s)", what, got, want);
    ck(! mBroke && strcmp(got, model) == 0, "%s: the model says the same (%s)", what, model);
    mBroke = 0;
}

// ---- the calls --------------------------------------------------------------------------------

static BRAssetAllocation R;
static BRAssetOutputUnits O[512];
static int64_t W[64];
static MResult MR;

// BRAssetAllocate over the inputs `spec`, held to `want` and to the Kotlin's allocate (after
// transferOutputCredits' first step: a payload not read exactly decides nothing).
static void alloc(const char *spec, int32_t outputCount, const char *want)
{
    char got[TEXT], model[TEXT], what[TEXT];

    parseStacks(spec);
    R = BRAssetAllocate(&H, L, LN, EXACT, IN, INN, outputCount, W, N(W), O, N(O));
    showAllocation(got, R, O);
    if (! EXACT) mIndeterminate(&MR, "payload not read exactly");
    else mAllocate(&MR, &H, L, LN, MIN, (int)INN, outputCount, mRuleFree);
    showModel(model, &MR);
    snprintf(what, sizeof(what), "allocate [%s] to %d outputs", spec, (int)outputCount);
    both(what, got, model, saysAllocation(got, want), want);
}

static void issue(const char *assetId, int32_t outputCount, int payloadExact, const char *want)
{
    char got[TEXT], model[TEXT], what[TEXT];

    R = BRAssetAllocateIssuance(&H, L, LN, assetId, outputCount, payloadExact, O, N(O));
    showAllocation(got, R, O);
    mAllocateIssuance(&MR, &H, L, LN, assetId, outputCount, payloadExact);
    showModel(model, &MR);
    snprintf(what, sizeof(what), "issue %s to %d outputs (read exactly %d)", nameOf(assetId), (int)outputCount, payloadExact);
    both(what, got, model, saysAllocation(got, want), want);
}

// What the last allocation delivered to `vout`, in order: "X:6,Y:1", or "-" for nothing.
static void delivered(int32_t vout, const char *want)
{
    char got[TEXT];
    size_t i, at = 0;

    got[0] = '\0';
    if (R.status == BR_ASSET_ALLOCATION_ALLOCATED) {
        for (i = 0; i < R.count; i++) {
            if (O[i].vout == vout) at = put(got, at, "%s%s:%lld", at ? "," : "", nameOf(O[i].assetId), (long long)O[i].count);
        }
        if (at == 0) put(got, at, "-");
    }
    else put(got, at, "(not allocated)");
    ck(strcmp(got, want) == 0, "output %d was delivered %s (want %s)", (int)vout, got, want);
}

// The Kotlin's receive(): every input's backedInputStack, the asset free of transfer rules, then the
// allocation. Inputs '/'-separated: "<id>:<count>" a VERIFIED row of the wallet's own, "p" a plain
// coin (no row, its funding transaction has no data output), "f" someone else's coin (no row, the
// wallet cannot say).
static void receive(const char *spec, int32_t outputCount)
{
    BRAssetInputFacts f;
    const char *p = spec;
    char got[TEXT], model[TEXT], what[TEXT];

    INN = 0;
    RULEDN = 0;
    for (;;) {
        memset(&f, 0, sizeof(f));
        if (INN >= M_STACKS) { ck(0, "too many inputs in \"%s\"", spec); return; }

        if (*p == 'p') { f.fundingHasDataOutput = BR_ASSET_FACT_NO; f.fundingIsAssetTx = BR_ASSET_FACT_NO; p++; }
        else if (*p == 'f') p++;
        else {
            const char *name = p;

            while (*p && *p != ':') p++;
            if (! *p) { ck(0, "a broken input \"%s\"", spec); return; }
            f.hasRow = 1;
            f.credit = BR_ASSET_CREDIT_VERIFIED;
            f.assetId = idOf(name, (size_t)(p - name));
            p++;
            f.quantity = num(&p);
        }
        IN[INN] = BRAssetBackedInputStack(&f, &U[INN][0]);
        if (IN[INN].known && IN[INN].unitCount == 1) U[INN][0].ruleFree = 1;
        MIN[INN] = mBacked(&f);
        INN++;
        if (*p != '/') break;
        p++;
    }
    R = BRAssetAllocate(&H, L, LN, EXACT, IN, INN, outputCount, W, N(W), O, N(O));
    showAllocation(got, R, O);
    if (! EXACT) mIndeterminate(&MR, "payload not read exactly");
    else mAllocate(&MR, &H, L, LN, MIN, (int)INN, outputCount, mRuleFree);
    showModel(model, &MR);
    snprintf(what, sizeof(what), "receive [%s] in %d outputs", spec, (int)outputCount);
    both(what, got, model, 1, "(the model's answer)");
}

// What output `vout` of the last allocation holds.
static void credit(int32_t vout, int32_t outputCount, const char *want)
{
    char got[TEXT], model[TEXT], what[TEXT];
    BRAssetOutputCredit c = BRAssetCreditForOutput(R, O, L, LN, vout, outputCount);

    showCredit(got, c);
    showMCredit(model, mForOutput(&MR, L, LN, vout, outputCount));
    snprintf(what, sizeof(what), "output %d of %d", (int)vout, (int)outputCount);
    both(what, got, model, says(got, want), want);
}

// The most `vout` can be delivered: "unbounded" or a number.
static void maxd(int32_t vout, int32_t outputCount, const char *want)
{
    char got[64], model[64], what[TEXT];
    BRAssetDeliverable d = BRAssetMaxDeliverable(&H, L, LN, vout, outputCount);
    int64_t m;

    if (d.unbounded) snprintf(got, sizeof(got), d.max ? "unbounded !max set" : "unbounded");
    else snprintf(got, sizeof(got), "%lld", (long long)d.max);
    if (mMaxDeliverable(&H, L, LN, vout, outputCount, &m)) snprintf(model, sizeof(model), "%lld", (long long)m);
    else snprintf(model, sizeof(model), "unbounded");
    snprintf(what, sizeof(what), "the most output %d of %d can be delivered", (int)vout, (int)outputCount);
    both(what, got, model, strcmp(got, want) == 0, want);
}

// The indexer's answer: lookup 0 Unavailable, 1 NotUnspent, 2 Found with entries "<id>:<count>,...".
static void indexer(BRAssetStackLookup lookup, const char *spec, const char *want)
{
    BRAssetOutputCredit c;
    MCredit mc;
    MUnits mu[M_ENTRIES];
    char got[TEXT], model[TEXT], what[TEXT];
    size_t i;
    int ok, mok;

    parseStacks(spec);
    for (i = 0; i < IN[0].unitCount; i++) { mu[i].id = U[0][i].assetId; mu[i].count = U[0][i].count; }
    ok = BRAssetCreditFromIndexer(lookup, IN[0].units, IN[0].unitCount, &c);
    mok = mFromIndexer((int)lookup, mu, (int)IN[0].unitCount, &mc);
    if (ok) showCredit(got, c);
    else snprintf(got, sizeof(got), c.kind == BR_ASSET_OUTPUT_UNKNOWN && ! c.hasUnits && ! c.assetId && ! c.label ? "null" : "null !credit set");
    if (mok) showMCredit(model, mc);
    else snprintf(model, sizeof(model), "null");
    snprintf(what, sizeof(what), "the indexer's %s [%s]", lookup == BR_ASSET_STACK_FOUND ? "Found" :
             lookup == BR_ASSET_STACK_NOT_UNSPENT ? "NotUnspent" : "Unavailable", spec);
    both(what, got, model, says(got, want), want);
}

// One input's facts: "r<quantity>[y|n|?]" a row storing that quantity (whether its output is
// targeted), "n[Y|N|?]" no row (whether the funding transaction carries a DigiAsset payload).
static size_t parseFacts(const char *spec, BRAssetInputFacts *f, size_t cap)
{
    const char *p = spec;
    size_t n = 0;

    if (! *spec) return 0;
    for (;;) {
        if (n >= cap) { ck(0, "too many inputs in \"%s\"", spec); return n; }
        memset(&f[n], 0, sizeof(f[n]));
        if (*p == 'r') {
            p++;
            f[n].hasRow = 1;
            f[n].quantity = num(&p);
            if (*p == 'y') { f[n].rowIsTargeted = BR_ASSET_FACT_YES; p++; }
            else if (*p == 'n') { f[n].rowIsTargeted = BR_ASSET_FACT_NO; p++; }
            else if (*p == '?') p++;
        }
        else if (*p == 'n') {
            p++;
            if (*p == 'Y') { f[n].fundingIsAssetTx = BR_ASSET_FACT_YES; p++; }
            else if (*p == 'N') { f[n].fundingIsAssetTx = BR_ASSET_FACT_NO; p++; }
            else if (*p == '?') p++;
        }
        else { ck(0, "a broken input \"%s\"", spec); return n; }
        n++;
        if (*p != '/') break;
        p++;
    }
    if (*p) ck(0, "a broken input \"%s\"", spec);
    return n;
}

// BRAssetResolveInputUnits: `why` 1 when the caller asks which rows are the reason (ZeroRowInputs).
static void units(const char *spec, int why, const char *want)
{
    BRAssetInputFacts f[M_STACKS * 4];
    size_t rows[M_STACKS * 4], n = parseFacts(spec, f, N(f));
    BRAssetInputUnits r = BRAssetResolveInputUnits(f, n, why ? rows : NULL, why ? N(rows) : 0);
    MResolved m = mResolve(f, (int)n, why);
    char got[TEXT], model[TEXT], what[TEXT];

    showResolved(got, r, rows);
    showMResolved(model, &m);
    snprintf(what, sizeof(what), "the inputs [%s]%s carried", spec, why ? ", asking why," : "");
    both(what, got, model, strcmp(got, want) == 0, want);
}

// BRAssetBackedInputStack of one row or none.
static void stack(int hasRow, BRAssetCredit cr, int64_t quantity, const char *assetId, BRAssetFact targeted,
                  BRAssetFact dataOutput, const char *want)
{
    BRAssetInputFacts f;
    BRAssetUnits slot;
    BRAssetInputStack s;
    MStack m;
    char got[TEXT], model[TEXT], what[TEXT];

    memset(&f, 0, sizeof(f));
    f.hasRow = hasRow; f.credit = cr; f.quantity = quantity; f.assetId = assetId;
    f.rowIsTargeted = targeted; f.fundingHasDataOutput = dataOutput;
    f.fundingIsAssetTx = BR_ASSET_FACT_YES;   // not read here
    memset(&slot, 0xa5, sizeof(slot));
    s = BRAssetBackedInputStack(&f, &slot);
    m = mBacked(&f);
    showStack(got, s);
    if (! m.known) snprintf(model, sizeof(model), "?");
    else if (m.n == 0) snprintf(model, sizeof(model), "-");
    else snprintf(model, sizeof(model), "%s:%lld:0", nameOf(m.u[0].id), (long long)m.u[0].count);
    snprintf(what, sizeof(what), "the stack of %s (credit %d, quantity %lld, %s, targeted %d, data output %d)",
             hasRow ? "a row" : "no row", (int)cr, (long long)quantity, nameOf(assetId), (int)targeted, (int)dataOutput);
    both(what, got, model, strcmp(got, want) == 0, want);
}

// BRAssetIdForIssuance with a full buffer, held to `want` ("null" for a refusal) and to the bridge.
static void id(const char *txid, int32_t vout, int locked, int aggregation, int32_t divisibility, const char *want)
{
    char got[BR_ASSET_ID_BUFFER + 8], model[80];
    int ok, mok;

    memset(got, 'x', sizeof(got));
    ok = BRAssetIdForIssuance(txid, vout, locked, (BRAssetAggregation)aggregation, divisibility, got, BR_ASSET_ID_BUFFER);
    mok = mId(txid, vout, locked, aggregation, divisibility, model);
    if (! ok) { if (got[0] != '\0') { got[0] = '!'; got[1] = '\0'; } else strcpy(got, "null"); }
    if (! mok) strcpy(model, "null");
    ck(strcmp(got, want) == 0, "the id of %.12s...:%d (locked %d, aggregation %d, divisibility %d): %s (want %s)",
       txid ? txid : "(null)", (int)vout, locked, aggregation, (int)divisibility, got, want);
    ck(strcmp(got, model) == 0, "the bridge says the same (%s)", model);
    if (ok) ck(strlen(got) == BR_ASSET_ID_LENGTH, "the id is %d characters", BR_ASSET_ID_LENGTH);
}

// ---- AssetTransferAllocatorTest ---------------------------------------------------------------

static void allocator_over_assignment(void)   // over_assignment_from_a_one_unit_carrier_...
{
    script("6a0b4441031500018060f423f0");
    alloc("X:1/-", 3, "allocated voided: 2:X:1");
}

static void allocator_no_asset_input(void)   // a_transfer_with_no_asset_input_..., a_million_units_from_no_asset_input_...
{
    script("6a0744410315012012");
    alloc("-", 3, "none");
    script("6a0744410315002016");
    alloc("-/-", 3, "none");
}

static void allocator_million_from_one(void)   // a_million_declared_from_one_unit_is_voided
{
    script("6a0744410315002016");
    alloc("X:1", 3, "allocated: 2:X:1");
}

static void allocator_honest_control(void)   // honest_five_of_nine_...
{
    script("6a06444103150005");
    alloc("X:9/-", 3, "allocated kept: 0:X:5 2:X:4");
}

static void allocator_second_input_asset(void)   // output_zero_receives_the_second_inputs_asset
{
    script("6a09444103158201002644");
    alloc("X:1/Y:1000000", 3, "allocated: 0:Y:1000000 2:X:1");
}

static void allocator_skip_past_carrier(void)   // a_skip_past_the_first_carrier_...
{
    script("6a0844410315810a000a");
    alloc("B:10/A:10", 3, "allocated: 1:B:10 0:A:10");
}

static void allocator_plain_first_input(void)   // a_plain_first_input_is_not_in_the_draw_order
{
    script("6a06444103150005");
    alloc("-/Y:9", 3, "allocated: 0:Y:5 2:Y:4");
}

static void allocator_mixed_input(void)   // a_mixed_input_is_drawn_from_the_top_..., an_instruction_that_runs_into_another_asset_...
{
    hdr(DA_TRANSFER, 3, "0:3 1:4");
    alloc("X:3,Y:4", 3, "allocated: 0:X:3 1:Y:4");
    hdr(DA_TRANSFER, 3, "0:5");
    alloc("X:3,Y:4", 3, "allocated: 2:X:3 2:Y:4");
}

static void allocator_missing_output(void)   // an_instruction_naming_a_missing_output_voids_the_transfer
{
    hdr(DA_TRANSFER, 3, "5:2");
    alloc("X:9", 3, "allocated: 2:X:9");
}

static void allocator_range(void)   // range_credits_every_output_and_consumes_the_product
{
    hdr(DA_TRANSFER, 3, "1:3:r");
    alloc("X:10", 3, "allocated: 0:X:3 1:X:3 2:X:4");
}

static void allocator_output_31(void)   // output_31_destroys_only_in_a_burn
{
    hdr(DA_BURN, 3, "31:4");
    alloc("X:10", 3, "allocated: 2:X:6");
    hdr(DA_TRANSFER, 3, "31:4");
    alloc("X:10", 3, "allocated: 2:X:10");
    alloc("X:10", 33, "allocated: 31:X:4 32:X:6");
}

static void allocator_skip_after_emptied(void)   // a_skip_after_an_input_is_emptied_...
{
    hdr(DA_TRANSFER, 3, "0:2:s 1:3");
    alloc("X:2/Y:3", 3, "allocated: 0:X:2 1:Y:3");
}

static void allocator_legacy(void)   // legacy_versions_ignore_instructions_when_the_first_input_is_empty
{
    hdr(DA_TRANSFER, 2, "0:2");
    alloc("-/X:5", 3, "allocated: 2:X:5");
}

static void allocator_fails_closed(void)   // an_unknown_input_..., a_percent_instruction_..., an_asset_that_may_carry_rules_...
{
    script("6a06444103150005");
    alloc("X:9/?", 3, "indeterminate");
    hdr(DA_TRANSFER, 3, "0:128:p");
    alloc("X:9", 3, "indeterminate");
    hdr(DA_TRANSFER, 3, "0:1");
    alloc("X:9!", 3, "indeterminate");
}

static void allocator_draw_across_entries(void)   // drawing_across_entries_voids_for_hybrid_...
{
    hdr(DA_TRANSFER, 3, "0:5");
    alloc("H:3/H:3", 3, "allocated: 2:H:6");
    alloc("D:3/D:3", 3, "indeterminate");
    alloc("PT:3/PT:3", 3, "indeterminate");
    alloc("PT:3", 3, "allocated: 2:PT:3");
    alloc("H:6", 3, "allocated: 0:H:5 2:H:1");
}

static void allocator_issuance(void)   // an_issuance_follows_its_instructions_..., ..._no_instruction_..., ..._hybrid_..., ..._not_read_exactly_...
{
    script("6a08" "44410305" "0a" "00" "04" "10");
    ck(H.operation == DA_ISSUANCE && EXACT, "an issuance, read exactly");
    issue(X, 3, 1, "allocated: 0:X:4 2:X:6");
    script("6a06" "44410305" "0a" "10");
    issue(X, 3, 1, "allocated: 2:X:10");
    issue(X, 3, 0, "indeterminate");
    script("6a08" "44410305" "0a" "00" "14" "14");
    ck(H.aggregation == BR_ASSET_HYBRID, "the header says hybrid");
    issue(PT, 3, 1, "allocated: 2:PT:10");
}

static void allocator_reads_exactly(void)   // the_reported_transfer_vectors_read_exactly
{
    static const char *const scripts[] = { "6a0b4441031500018060f423f0", "6a0744410315012012", "6a0744410315002016",
                                           "6a09444103158201002644", "6a0844410315810a000a", "6a06444103150005" };
    size_t i;

    for (i = 0; i < N(scripts); i++) { script(scripts[i]); ck(EXACT, "%s reads exactly", scripts[i]); }
}

static void allocator_issuance_not_here(void)   // issuance_is_not_allocated_here, overflow_is_indeterminate
{
    hdr(DA_ISSUANCE, 3, "");
    alloc("-", 2, "indeterminate");
    hdr(DA_TRANSFER, 3, "8000:HALF:r");
    alloc("X:9", 3, "indeterminate");
}

// ---- AssetHoldCoversCreditedOutputsTest's allocations -------------------------------------------

static void hold_issuance(void)
{
    script("6a08" "44410305" "0a" "00" "04" "10");
    issue(X, 3, 1, "allocated");
    delivered(2, "X:6");
    script("6a08" "44410305" "0a" "05" "04" "10");
    issue(X, 3, 1, "allocated: 2:X:10");
    script("6a08" "44410305" "0a" "00" "0a" "10");
    issue(X, 3, 1, "allocated: 0:X:10");
}

static void hold_transfer(void)
{
    script("6a06" "44410315" "05" "09");
    ck(H.operation == DA_TRANSFER, "a transfer");
    alloc("X:9", 3, "allocated voided: 2:X:9");
    alloc("-", 3, "none");
    hdr(DA_TRANSFER, 3, "0:5 1:0");
    alloc("X:5", 3, "allocated voided: 2:X:5");
    hdr(DA_TRANSFER, 3, "3:3:r");
    alloc("X:12", 3, "allocated: 2:X:12");
    script("6a06" "44410325" "1f" "09");
    ck(H.operation == DA_BURN, "a burn");
    alloc("X:9", 3, "allocated kept:");
}

// ---- UnbackedAssetCreditTest ------------------------------------------------------------------

typedef struct { const char *script, *inputs; int named; } UnbackedClaim;
static const UnbackedClaim UNBACKED_CLAIMS[] = {
    { "6a0b4441031500018060f423f0", "X:1/p", 0 },
    { "6a0744410315012012", "p", 1 },
    { "6a0744410315002016", "p/p", 0 },
    { "6a0744410315002016", "X:1", 0 },
    { "6a0d444103155fffffffffffffffff", "p", 0 },
};

static void credit_unbacked_claim_unknown_inputs(void)
{
    size_t i;

    for (i = 0; i < N(UNBACKED_CLAIMS); i++) {
        script(UNBACKED_CLAIMS[i].script);
        receive("f/f", 3);
        credit(UNBACKED_CLAIMS[i].named, 3, "unknown");
    }
}

static void credit_unbacked_claim_known_inputs(void)
{
    size_t i;

    for (i = 0; i < N(UNBACKED_CLAIMS); i++) {
        script(UNBACKED_CLAIMS[i].script);
        receive(UNBACKED_CLAIMS[i].inputs, 3);
        credit(UNBACKED_CLAIMS[i].named, 3, "holds nothing");
    }
}

static void credit_voided_transfer(void)
{
    script("6a0b4441031500018060f423f0");
    receive("X:1/p", 3);
    credit(0, 3, "holds nothing");
    credit(2, 3, "holds X:1");
}

static void credit_honest_control(void)
{
    script("6a06444103150005");
    receive("X:9/p", 3);
    credit(0, 3, "holds X:5");
    credit(2, 3, "holds X:4");
}

static void credit_second_input_asset(void)
{
    script("6a09444103158201002644");
    receive("X:1/Y:1000000", 3);
    credit(0, 3, "holds Y:1000000");
    script("6a0844410315810a000a");
    receive("B:10/A:10", 3);
    credit(0, 3, "holds A:10");
    credit(1, 3, "holds B:10");
}

static void credit_mixed_input(void)
{
    hdr(DA_TRANSFER, 3, "0:3 1:4");
    alloc("X:3,Y:4", 3, "allocated");
    credit(0, 3, "holds X:3 label=X");
    credit(1, 3, "holds Y:4 label=Y");
    hdr(DA_TRANSFER, 3, "0:3 0:4");
    alloc("X:3,Y:4", 3, "allocated");
    credit(0, 3, "mixed");
}

static void credit_indexer(void)
{
    indexer(BR_ASSET_STACK_FOUND, "X:3,Y:4", "mixed");
    indexer(BR_ASSET_STACK_FOUND, "-", "holds nothing");
    indexer(BR_ASSET_STACK_FOUND, "X:5", "holds X:5");
    indexer(BR_ASSET_STACK_FOUND, "X:1000000", "holds X:1000000");
    indexer(BR_ASSET_STACK_FOUND, "X:1000", "holds X:1000");
    indexer(BR_ASSET_STACK_FOUND, "Y:1000000", "holds Y:1000000");
    indexer(BR_ASSET_STACK_NOT_UNSPENT, "-", "null");
    indexer(BR_ASSET_STACK_UNAVAILABLE, "-", "null");
}

static void credit_issuance(void)
{
    script("6a09" "44410305" "2016" "01" "2016" "10");   // the push one byte short: not read exactly
    issue(Z, 3, EXACT, "indeterminate");
    credit(1, 3, "unknown");
    credit(0, 3, "holds nothing");
    script("6a0a" "44410305" "2016" "01" "2016" "10");
    ck(EXACT, "read exactly");
    issue(Z, 3, EXACT, "allocated");
    credit(1, 3, "holds Z:1000000 label=Z");
    credit(0, 3, "holds nothing");
    script("6a07" "44410305" "2016" "10");
    issue(Z, 2, EXACT, "allocated");
    credit(0, 2, "holds nothing");
    issue(Z, 3, EXACT, "allocated");
    credit(0, 3, "holds nothing");
    credit(2, 3, "holds Z:1000000");
    script("6a0a" "44410305" "2016" "00" "2016" "10");
    issue(Z, 2, EXACT, "allocated");
    credit(0, 2, "holds Z:1000000");
    script("6a08" "44410305" "0a" "00" "14" "10");
    issue(Z, 3, EXACT, "allocated");
    credit(0, 3, "holds nothing");
    credit(2, 3, "holds Z:10");
}

static void credit_issuance_not_exact(void)
{
    script("6a09" "44410305" "2016" "01" "20" "10");
    ck(! EXACT, "an amount cut off mid-field does not read exactly");
    issue(Z, 3, EXACT, "indeterminate");
    credit(1, 3, "unknown");
    issuanceHdr(3, 5);   // a rule-bearing issuance
    issue(Z, 2, 0, "indeterminate");
    credit(1, 2, "unknown");
}

static void credit_issuance_core_rejects(void)
{
    issuanceHdr(5, 0);
    issue(Z, 2, 1, "none");
    issuanceHdr(5, 18014398509481983LL + 1);
    issue(Z, 2, 1, "none");
    issuanceHdr(5, 18014398509481983LL);
    issue(Z, 2, 1, "allocated");
}

static void credit_bounds(void)
{
    script("6a06444103150005");
    maxd(0, 3, "5");
    maxd(2, 3, "unbounded");
    maxd(1, 4, "0");
    hdr(DA_TRANSFER, 3, "3:7:r");
    maxd(0, 5, "7");
    hdr(DA_TRANSFER, 3, "0:128:p");
    maxd(0, 3, "unbounded");
    script("6a0d444103155fffffffffffffffff");
    maxd(0, 3, "0");
    maxd(2, 3, "0");
    hdr(DA_TRANSFER, 3, "0:MAX 0:1");
    maxd(0, 3, "0");
}

static void credit_unnamed_output(void)
{
    script("6a06444103150005");
    receive("f", 4);
    credit(1, 4, "holds nothing");
}

// ---- AssetInputUnitsTest, AssetArithmeticNeverWrapsTest -----------------------------------------

static void units_rows_and_plain_inputs(void)
{
    units("r100/r0n", 0, "known 100");
    units("r40/nN", 0, "known 40");
    units("r40/nY", 0, "unknown");
    units("r40/n?", 0, "unknown");
    units("r40/r2", 0, "known 42");
}

static void units_zero_rows(void)
{
    units("r40/r0y", 0, "unknown");
    units("r40/r0?", 0, "unknown");
    units("r40/r0n", 0, "known 40");
}

static void units_zero_rows_why(void)
{
    units("r40/r0y/nN/r0y", 1, "unknown rows=1,3 other=40");
    units("r0y/nY", 1, "unknown");
    units("nY/r0y", 1, "unknown");
    units("r40/r0n", 1, "known 40");
}

static void never_wraps_input_total(void)
{
    units("rHALF1y/rHALF1y", 0, "unknown");
}

// ---- this suite's own: rules the Kotlin tests reach through the database or not at all ----------

static void backed_stack_rules(void)
{
    const BRAssetFact U_ = BR_ASSET_FACT_UNKNOWN, NO = BR_ASSET_FACT_NO, YES = BR_ASSET_FACT_YES;

    stack(1, BR_ASSET_CREDIT_VERIFIED, 5, X, U_, U_, "X:5:0");
    stack(1, BR_ASSET_CREDIT_BACKED, 5, X, YES, YES, "X:5:0");
    stack(1, BR_ASSET_CREDIT_VERIFIED, 0, X, U_, U_, "-");
    stack(1, BR_ASSET_CREDIT_BACKED, -1, X, U_, U_, "-");
    stack(1, BR_ASSET_CREDIT_VERIFIED, 5, NULL, U_, U_, "?");
    stack(1, BR_ASSET_CREDIT_VERIFIED, 5, "", U_, U_, "?");
    stack(1, BR_ASSET_CREDIT_UNCHECKED, 0, X, NO, U_, "-");
    stack(1, BR_ASSET_CREDIT_UNCHECKED, 0, X, YES, NO, "?");
    stack(1, BR_ASSET_CREDIT_UNCHECKED, 0, X, U_, NO, "?");
    stack(1, BR_ASSET_CREDIT_UNCHECKED, 5, X, NO, NO, "?");
    stack(1, BR_ASSET_CREDIT_UNCHECKED, -1, X, NO, NO, "?");
    stack(1, BR_ASSET_CREDIT_MIXED, 0, X, NO, NO, "?");
    stack(1, BR_ASSET_CREDIT_MIXED, 5, X, NO, NO, "?");
    stack(1, (BRAssetCredit)7, 5, X, NO, NO, "?");
    stack(0, BR_ASSET_CREDIT_VERIFIED, 5, X, NO, NO, "-");
    stack(0, BR_ASSET_CREDIT_UNCHECKED, 0, NULL, U_, YES, "?");
    stack(0, BR_ASSET_CREDIT_UNCHECKED, 0, NULL, U_, U_, "?");
}

static void credit_states(void)
{
    static const struct { BRAssetCredit c; int counts, settled; } t[] = {
        { BR_ASSET_CREDIT_UNCHECKED, 0, 0 }, { BR_ASSET_CREDIT_BACKED, 1, 1 }, { BR_ASSET_CREDIT_VERIFIED, 1, 1 },
        { BR_ASSET_CREDIT_MIXED, 0, 1 }, { (BRAssetCredit)4, 0, 0 }, { (BRAssetCredit)-1, 0, 0 },
    };
    size_t i;

    for (i = 0; i < N(t); i++) {
        ck(BRAssetCreditCounts(t[i].c) == t[i].counts && BRAssetCreditIsSettled(t[i].c) == t[i].settled,
           "credit state %d counts %d, is settled %d", (int)t[i].c, t[i].counts, t[i].settled);
    }
}

static void aggregation_of_id(void)
{
    static const struct { const char *id; int says; BRAssetAggregation a; } t[] = {
        { X, 1, BR_ASSET_AGGREGATABLE }, { HY, 1, BR_ASSET_HYBRID }, { DI, 1, BR_ASSET_DISPERSED },
        { "Ua1", 1, BR_ASSET_AGGREGATABLE }, { "Uh7", 1, BR_ASSET_HYBRID }, { "Ud9", 1, BR_ASSET_DISPERSED },
        { "La", 0, BR_ASSET_AGGREGATABLE }, { "Lx1", 0, BR_ASSET_AGGREGATABLE }, { "Ma1", 0, BR_ASSET_AGGREGATABLE },
        { "lad", 0, BR_ASSET_AGGREGATABLE }, { "LA1", 0, BR_ASSET_AGGREGATABLE }, { "", 0, BR_ASSET_AGGREGATABLE },
        { PT, 0, BR_ASSET_AGGREGATABLE }, { NULL, 0, BR_ASSET_AGGREGATABLE },
    };
    size_t i;

    for (i = 0; i < N(t); i++) {
        BRAssetAggregation a = (BRAssetAggregation)9;
        int says = BRAssetAggregationOfId(t[i].id, &a);

        ck(says == t[i].says && (says ? a == t[i].a : a == (BRAssetAggregation)9), "the id \"%s\" says %d (%d)",
           t[i].id ? t[i].id : "(null)", says, (int)a);
        if (t[i].id) ck(says == (mAggregationOf(t[i].id) >= 0) && (! says || (int)a == mAggregationOf(t[i].id)),
                        "the model says the same of \"%s\"", t[i].id);
    }
    ck(BRAssetAggregationOfId(X, NULL) == 1, "the aggregation may be left unread");
}

// Instructions and refusals the Kotlin tests do not state: each is a rule of THE RULES.
static void allocate_rules(void)
{
    hdr(DA_TRANSFER, 3, "0:-1");
    alloc("X:9", 3, "indeterminate: malformed instruction");
    hdr(DA_TRANSFER, 3, "-1:1");
    alloc("X:9", 3, "indeterminate: malformed instruction");
    hdr(DA_TRANSFER, 3, "0:1 5:1 0:-1");   // the invalid instruction ends the reading first
    alloc("X:9", 3, "allocated voided: 2:X:9");
    hdr(DA_TRANSFER, 3, "0:1");
    alloc("X:9", 0, "indeterminate: no outputs");
    alloc("X:9", -1, "indeterminate: no outputs");
    alloc("X:9!", 0, "indeterminate: no outputs");
    alloc("X:0/X:-3", 3, "none");
    alloc("X:0!/Y:2", 3, "allocated kept: 0:Y:1 2:Y:1");   // a rule-bearing entry that is not counted
    alloc("X:2/Y:2!", 3, "indeterminate: an input asset may carry transfer rules");
    alloc("?/-", 3, "indeterminate: an input's contents are unknown");
    hdr(DA_TRANSFER, 3, "0:1:p");
    alloc("-", 3, "none");   // nothing carried comes before the percent instruction
    alloc("?", 3, "indeterminate: an input's contents are unknown");
    hdr(DA_UNDEFINED, 3, "0:1");
    alloc("X:9", 3, "indeterminate: unclassifiable carrier");
    hdr(DA_ISSUANCE, 3, "0:1");
    alloc("X:9", 3, "indeterminate: issuance");
    hdr(DA_TRANSFER, 3, "0:1");
    EXACT = 0;
    alloc("X:9", 3, "indeterminate: payload not read exactly");
    alloc("?", 0, "indeterminate: payload not read exactly");
    EXACT = 1;
    H.operation = DA_ISSUANCE;
    alloc("X:9", 0, "indeterminate: issuance");
    H.operation = DA_TRANSFER;
    alloc("X:9", 0, "indeterminate: no outputs");
    parseStacks("X:1");
    R = BRAssetAllocate(&H, L, LN, 1, IN, 0, 3, W, N(W), O, N(O));
    ck(R.status == BR_ASSET_ALLOCATION_INDETERMINATE && R.why == BR_ASSET_WHY_NO_INPUTS, "no input is not decided");
}

static void allocate_draws(void)
{
    hdr(DA_TRANSFER, 3, "0:5");
    alloc("X:3,X:4", 3, "allocated kept: 0:X:5 2:X:2");      // aggregatable: on to the next entry
    alloc("X:3/X:4", 3, "allocated kept: 0:X:5 2:X:2");      // and to the next stack
    alloc("X:3,Y:4/X:9", 3, "allocated voided: 2:X:12 2:Y:4");
    alloc("X:3,X:2", 3, "allocated kept: 0:X:5");
    alloc("X:2,X:2", 3, "allocated voided: 2:X:4");
    alloc("H:3,H:3", 3, "allocated voided: 2:H:6");
    alloc("D:5/D:3", 3, "allocated kept: 0:D:5 2:D:3");      // exactly one entry: no draw goes on
    alloc("Ua:3/Ua:3", 3, "allocated kept: 0:Ua:5 2:Ua:1");
    alloc("Uh:3/Uh:3", 3, "allocated voided: 2:Uh:6");
    alloc("Ud:3/Ud:3", 3, "indeterminate: draw across entries of a non-aggregable asset");
    alloc("La:3/La:3", 3, "indeterminate: draw across entries of a non-aggregable asset");
    alloc("Lx:3/Lx:3", 3, "indeterminate: draw across entries of a non-aggregable asset");
    alloc("X:3,X:0,X:-1,X:2", 3, "allocated kept: 0:X:5");    // entries that are not counted are passed over
    alloc("-/X:0/X:5", 3, "allocated kept: 0:X:5");
    hdr(DA_TRANSFER, 3, "0:0");
    alloc("X:5", 3, "allocated kept: 2:X:5");               // a zero amount draws and delivers nothing
    hdr(DA_TRANSFER, 3, "0:5 0:0");
    alloc("X:5/-", 3, "allocated voided: 2:X:5");           // after the last stack it is INVALID
    hdr(DA_TRANSFER, 3, "0:0:s 1:2");
    alloc("X:2/Y:2", 3, "allocated kept: 1:Y:2 2:X:2");     // a skip on its own moves on
    hdr(DA_TRANSFER, 3, "0:1:s 0:1:s 1:1");
    alloc("X:2/Y:2/A:2", 3, "allocated kept: 0:X:1 0:Y:1 1:A:1 2:X:1 2:Y:1 2:A:1");
    hdr(DA_TRANSFER, 3, "0:2:s 1:1:s 2:1");
    alloc("X:2/Y:2/A:2", 3, "allocated kept: 0:X:2 1:Y:1 2:A:2 2:Y:1");
    hdr(DA_TRANSFER, 3, "0:2:s 1:0:s 2:2");
    alloc("X:2/D:1/A:3", 3, "allocated kept: 0:X:2 2:A:3 2:D:1");   // a skip after a used-up stack moves on next time
    hdr(DA_TRANSFER, 3, "0:2:s 1:1");
    alloc("X:2/-/Y:2", 3, "allocated kept: 0:X:2 1:Y:1 2:Y:1");
    hdr(DA_TRANSFER, 3, "0:3 1:1");
    alloc("X:2/X:2", 3, "allocated kept: 0:X:3 1:X:1");     // the stack used up mid-draw
    hdr(DA_TRANSFER, 3, "2:1 0:1 2:1 1:1");
    alloc("X:9", 3, "allocated kept: 2:X:7 0:X:1 1:X:1");   // one output, one sum; outputs in first-delivery order
    hdr(DA_TRANSFER, 3, "1:1 0:1");
    alloc("X:1,Y:1", 3, "allocated kept: 1:X:1 0:Y:1");
    hdr(DA_TRANSFER, 3, "1:1:r");
    alloc("X:1,Y:3", 3, "allocated voided: 2:X:1 2:Y:3");   // a range consumes outputIndex + 1 times
    hdr(DA_TRANSFER, 3, "2:1:r");
    alloc("X:3", 3, "allocated kept: 0:X:1 1:X:1 2:X:1");
    hdr(DA_TRANSFER, 3, "3:1:r");
    alloc("X:4", 3, "allocated voided: 2:X:4");
    hdr(DA_TRANSFER, 3, "0:0:r 0:0:rs 1:1");
    alloc("X:1/Y:1", 3, "allocated kept: 1:Y:1 2:X:1");
}

static void allocate_burns_and_legacy(void)
{
    hdr(DA_BURN, 3, "31:4:r");
    alloc("X:200", 40, "allocated kept: 0:X:4 1:X:4 2:X:4 3:X:4 4:X:4 5:X:4 6:X:4 7:X:4 8:X:4 9:X:4 10:X:4 11:X:4 "
                       "12:X:4 13:X:4 14:X:4 15:X:4 16:X:4 17:X:4 18:X:4 19:X:4 20:X:4 21:X:4 22:X:4 23:X:4 24:X:4 "
                       "25:X:4 26:X:4 27:X:4 28:X:4 29:X:4 30:X:4 31:X:4 39:X:72");
    hdr(DA_BURN, 3, "31:4 30:1 31:5");
    alloc("X:10", 3, "allocated voided: 2:X:10");
    hdr(DA_BURN, 3, "31:4 0:1 31:5");
    alloc("X:10", 3, "allocated kept: 0:X:1");
    alloc("X:10", 33, "allocated kept: 0:X:1");
    hdr(DA_BURN, 3, "31:0 32:3");
    alloc("X:3", 40, "allocated kept: 32:X:3");
    hdr(DA_TRANSFER, 2, "0:2");
    alloc("X:5", 3, "allocated kept: 0:X:2 2:X:3");         // the first input carries: the instructions are read
    hdr(DA_TRANSFER, 1, "0:2");
    alloc("X:0/X:5", 3, "allocated kept: 2:X:5");
    hdr(DA_BURN, 2, "5:2");
    alloc("-/X:5", 3, "allocated kept: 2:X:5");
    hdr(DA_TRANSFER, 3, "0:2");
    alloc("-/X:5", 3, "allocated kept: 0:X:2 2:X:3");
    hdr(DA_TRANSFER, 0, "0:2");
    alloc("-/X:5", 3, "allocated kept: 2:X:5");
}

static void allocate_overflow(void)
{
    hdr(DA_TRANSFER, 3, "0:HALF1 0:HALF1");
    alloc("X:MAX,X:MAX", 3, "indeterminate: overflow");     // the delivered sum
    hdr(DA_TRANSFER, 3, "");
    alloc("X:MAX/X:1", 3, "indeterminate: overflow");          // what is left, summed on the last output
    alloc("X:HALF/X:HALF/X:1", 3, "allocated kept: 2:X:9223372036854775807");
    hdr(DA_TRANSFER, 3, "1:HALF1:r");
    alloc("X:9", 3, "indeterminate: overflow");             // the range product
    hdr(DA_TRANSFER, 3, "1:HALF:r");
    alloc("X:MAX", 3, "allocated kept: 0:X:4611686018427387903 1:X:4611686018427387903 2:X:1");
    hdr(DA_TRANSFER, 3, "1:HALF:r 5:1:r");
    alloc("X:MAX", 3, "allocated voided: 2:X:9223372036854775807");
    hdr(DA_TRANSFER, 3, "5:1 1:HALF1:r");
    alloc("X:MAX", 3, "allocated voided: 2:X:9223372036854775807");         // the invalid instruction ends the reading first
}

static void issuance_rules(void)
{
    script("6a06" "44410305" "0a" "10");
    issue(X, 0, 1, "indeterminate: no outputs");
    issue(X, 0, 0, "indeterminate: payload not read exactly");
    H.operation = DA_TRANSFER;
    issue(X, 0, 0, "indeterminate: not an issuance");
    H.operation = DA_ISSUANCE;
    H.hasTotalQuantity = 0;
    issue(X, 3, 1, "indeterminate: no issued amount");
    issue(X, 0, 1, "indeterminate: no outputs");
    issuanceHdr(5, -1);
    issue(X, 2, 1, "none");
    issuanceHdr(5, 1);
    issue(X, 1, 1, "allocated kept: 0:X:1");
    hdr(DA_ISSUANCE, 3, "0:1:p");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 5;
    issue(X, 2, 1, "indeterminate: percent instruction");
    H.totalQuantity = 0;
    issue(X, 2, 1, "none");
    hdr(DA_ISSUANCE, 3, "31:4 0:-1");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    issue(X, 3, 1, "allocated voided: 2:X:10");                 // output 31 is an ordinary output
    issue(X, 32, 1, "indeterminate: malformed instruction");
    hdr(DA_ISSUANCE, 3, "0:4 0:4 1:3:s 1:2");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    H.aggregation = BR_ASSET_DISPERSED;
    issue(X, 3, 1, "allocated voided: 2:X:10");                 // more than is left in the one entry
    hdr(DA_ISSUANCE, 3, "0:4 1:6:s 1:0");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    issue(X, 3, 1, "allocated voided: 2:X:10");                 // a zero amount once the one entry is used up
    hdr(DA_ISSUANCE, 3, "0:4 0:4 1:3");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    H.aggregation = BR_ASSET_DISPERSED;
    issue(PT, 3, 1, "allocated voided: 2:PT:10");               // more than is left: INVALID, whatever the aggregation
    hdr(DA_ISSUANCE, 3, "2:3:r");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    issue(X, 3, 1, "allocated kept: 0:X:3 1:X:3 2:X:4");
    H.totalQuantity = 18014398509481983LL;
    issue(X, 3, 1, "allocated kept: 0:X:3 1:X:3 2:X:18014398509481977");
    hdr(DA_ISSUANCE, 3, "3:3:r");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 12;
    issue(X, 3, 1, "allocated voided: 2:X:12");
}

static void credit_rules(void)
{
    hdr(DA_TRANSFER, 3, "0:1:p 4:1:r");
    alloc("X:9", 6, "indeterminate: percent instruction");
    credit(0, 6, "unknown");      // a percent instruction names its output
    credit(4, 6, "unknown");      // a range names 0..outputIndex
    credit(5, 6, "unknown");      // the last output
    credit(6, 6, "holds nothing");
    credit(-1, 6, "unknown");     // a range names every index up to its own
    hdr(DA_BURN, 3, "31:1");
    alloc("X:9/?", 3, "indeterminate: an input's contents are unknown");
    credit(31, 3, "unknown");     // a burn's output 31 names too
    credit(1, 3, "holds nothing");
    credit(0, 0, "holds nothing");
    credit(-1, 0, "unknown");     // -1 is outputCount - 1
    credit(INT32_MAX, INT32_MIN, "unknown");   // and wraps where the Kotlin's Int does
    hdr(DA_TRANSFER, 3, "0:1 1:1");
    alloc("X:1,Y:1", 3, "allocated");
    credit(2, 3, "holds nothing");             // two assets moved: no label
    alloc("X:1,X:1", 3, "allocated");
    credit(2, 3, "holds nothing label=X");     // one asset moved: its label
    credit(0, 3, "holds X:1 label=X");
    credit(-5, 3, "holds nothing label=X");
    alloc("-", 3, "none");
    credit(0, 3, "holds nothing");
    credit(2, 3, "holds nothing");
    hdr(DA_TRANSFER, 3, "");
    alloc("X:1,Y:1", 3, "allocated");
    credit(2, 3, "mixed");
}

static void bound_rules(void)
{
    hdr(DA_TRANSFER, 3, "0:3 0:4 1:9 2:5:r");
    maxd(0, 4, "12");
    maxd(1, 4, "14");
    maxd(2, 4, "5");
    maxd(3, 4, "unbounded");
    maxd(4, 4, "0");
    maxd(-1, 4, "5");           // a range names every index up to its own
    maxd(-1, 0, "unbounded");
    hdr(DA_BURN, 3, "31:4 31:2:r");
    maxd(31, 40, "2");          // a burn's output 31 aside, its range is counted
    maxd(0, 40, "2");
    H.operation = DA_TRANSFER;
    maxd(31, 40, "6");
    hdr(DA_TRANSFER, 3, "0:3:p 5:1 0:-1");
    maxd(0, 9, "0");            // a negative amount: not a count
    hdr(DA_TRANSFER, 3, "0:3:p 0:2");
    maxd(0, 9, "unbounded");
    hdr(DA_TRANSFER, 3, "1:3:p 0:-1:p 0:2");
    maxd(0, 9, "unbounded");    // a percent instruction's amount is not counted
    hdr(DA_BURN, 3, "31:2:p 0:1");
    maxd(31, 40, "0");          // the burn marker is passed over before the percent test
    hdr(DA_TRANSFER, 3, "1:HALF1:r");
    maxd(0, 9, "0");
    hdr(DA_TRANSFER, 3, "0:HALF 0:HALF 1:1");
    maxd(0, 9, "9223372036854775806");
    hdr(DA_TRANSFER, 3, "0:HALF 0:HALF 0:1");
    maxd(0, 9, "9223372036854775807");
    hdr(DA_TRANSFER, 3, "-1:1");
    maxd(0, 9, "0");
    hdr(DA_UNDEFINED, 3, "0:-3 0:-4");
    maxd(0, 9, "-7");           // an unclassified header is not asked whether its sums count
    hdr(DA_UNDEFINED, 3, "0:-3 0:MIN");
    maxd(0, 9, "0");
    hdr(DA_UNDEFINED, 3, "0:MIN");
    maxd(0, 9, "-9223372036854775808");   // a sum of exactly the least int64 fits
    hdr(DA_UNDEFINED, 3, "0:MAX 0:1");
    maxd(0, 9, "0");
    hdr(DA_ISSUANCE, 3, "0:3 0:3:p");
    maxd(0, 9, "unbounded");
}

static void resolve_rules(void)
{
    units("", 0, "known 0");    // no input
    units("nN", 0, "known 0");
    units("r-5n", 0, "known 0");
    units("r-5y", 0, "unknown");
    units("r-5y", 1, "unknown rows=0 other=0");
    units("r0y/rHALF1/rHALF1", 1, "unknown");   // past INT64_MAX: no row is reported
    units("r0y/nY", 1, "unknown");
    units("rHALF1/rHALF", 0, "known 9223372036854775807");
    units("rHALF1/rHALF/r1", 0, "unknown");
    units("r0y/r5/r0?/r0n/r7", 1, "unknown rows=0,2 other=12");
}


// ---- the id of an issuance ----------------------------------------------------------------------

#define T "8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b728825178"

// Mainnet: the issuance ee47fe8b...7e83de2 (OP_RETURN 6a0a44410105201200201210) spends output 1 of T
// first; its asset's id is La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo.
static void id_mainnet(void)
{
    script("6a0a44410105201200201210");
    ck(H.operation == DA_ISSUANCE && H.locked && H.aggregation == BR_ASSET_AGGREGATABLE && H.divisibility == 0,
       "the issuance is locked, aggregatable, divisibility 0");
    id(T, 1, H.locked, (int)H.aggregation, H.divisibility, "La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo");
}

static void id_vectors(void)
{
    id(T, 1, 1, 1, 0, "Lh68XCK6xzs3QPGvBLgvoQkDEPEkhhus7n77gU");
    id(T, 1, 1, 2, 0, "Ld2xhkxW1RvNwNk2krhdUJbexNJwe9EPFjB8qU");
    id(T, 1, 1, 0, 7, "La4WAqZfAwtxbZxBSuNoxptactZcbXfaUubPyC");
    id(T, 1, 1, 1, 3, "Lh68XCK6xzs3QPGvBLgvoQkDEPEkhhusRtgXuS");
    id(T, 1, 1, 2, 1, "Ld2xhkxW1RvNwNk2krhdUJbexNJwe9EPNDe35x");
    id(T, 0, 1, 0, 0, "La47gBb6ieY4pk4rJSjv36eQMfz7NsXoo8JVbB");
    id(T, 10, 1, 0, 0, "La6AhDSYsM78VokzcBxSHNu2HmrSAbMfroLpn7");
    id(T, 2147483647, 1, 0, 0, "La3jpdD5KUTCFmCwqnAaQeDivWJz36a9pAYXXG");
    id(T, 109, 1, 2, 5, "Ld4BZdYfSChZtziCPkWV6zFqgez1KKuNEXM4u5");
    id("8A88DFB009C6A824823BF3C310101F91340611E1F8A93BA2642E66B728825178", 1, 1, 0, 0,
       "La8HEugD4HRajJZF5pPndHwLYJV4VrLSTtQnYS");   // the txid as given: its case is not changed
    id("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", 0, 1, 0, 0,
       "La9XNXbVL41J2JwBzWHX7bReWxkTHSySmRbtx2");   // nor its characters checked
    id("0000000000000000000000000000000000000000000000000000000000000000", 0, 1, 1, 7,
       "Lh7uS5Tr6gAvozz7a8aVMqJmmf2bjh1x2S5ZB6");
    id("ee47fe8bacfceba55e8f99717e7133d991a74dc29fc1ee48ab370ee4c7e83de2", 4, 1, 0, 2,
       "La7HGsMEoGdBdKDuiigLC2w494yq4VuJYPhRwF");
}

static void id_refusals(void)
{
    id(NULL, 1, 1, 0, 0, "null");
    id(T, -1, 1, 0, 0, "null");
    id(T, INT32_MIN, 1, 0, 0, "null");
    id(T, 1, 0, 0, 0, "null");      // unlocked: not derivable here
    id(T, 1, 0, 2, 7, "null");
    id(T, 1, 1, -1, 0, "null");
    id(T, 1, 1, 3, 0, "null");
    id(T, 1, 1, 0, -1, "null");
    id(T, 1, 1, 0, 8, "null");
    id("", 1, 1, 0, 0, "null");
    id("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b72882517", 1, 1, 0, 0, "null");     // 63
    id("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b7288251780", 1, 1, 0, 0, "null");   // 65
    id("8a88dfb009c6a824823bf3c310101f91340611e1f8a93ba2642e66b728825178" "8a88dfb009c6a824823bf3c310101f91", 1, 1, 0, 0, "null");
}

static void id_capacity(void)
{
    char out[64];
    int ok;

    memset(out, 'x', sizeof(out));
    ok = BRAssetIdForIssuance(T, 1, 1, BR_ASSET_AGGREGATABLE, 0, out, BR_ASSET_ID_LENGTH);
    ck(! ok && out[0] == '\0' && out[1] == 'x', "38 bytes cannot hold the id and its NUL: refused, an empty string");
    memset(out, 'x', sizeof(out));
    ok = BRAssetIdForIssuance(T, 1, 1, BR_ASSET_AGGREGATABLE, 0, out, BR_ASSET_ID_BUFFER);
    ck(ok && strcmp(out, "La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo") == 0 && out[40] == 'x', "39 bytes hold it, and nothing past them is written");
    memset(out, 'x', sizeof(out));
    ok = BRAssetIdForIssuance(T, 1, 1, BR_ASSET_AGGREGATABLE, 0, out, 0);
    ck(! ok && out[0] == 'x', "no room: refused, nothing written");
    memset(out, 'x', sizeof(out));
    ok = BRAssetIdForIssuance(T, 1, 1, BR_ASSET_AGGREGATABLE, 0, out, 1);
    ck(! ok && out[0] == '\0' && out[1] == 'x', "one byte: an empty string");
    memset(out, 'x', sizeof(out));
    ok = BRAssetIdForIssuance(T, 1, 0, BR_ASSET_AGGREGATABLE, 0, out, sizeof(out));
    ck(! ok && out[0] == '\0', "a refusal leaves an empty string");
    ok = BRAssetIdForIssuance(T, 1, 1, BR_ASSET_AGGREGATABLE, 0, NULL, 39);
    ck(! ok, "no buffer: refused");
}

// ---- C only: arguments, capacity, structs nobody filled ----------------------------------------

static void arguments(void)
{
    BRAssetInputStack st[2];
    BRAssetUnits u[2];
    BRAssetInputFacts f[2];
    BRAssetOutputCredit c;
    BRAssetDeliverable d;
    BRAssetInputUnits r;
    BRAssetAllocation a;
    size_t rows[1];

    hdr(DA_TRANSFER, 3, "0:1");
    parseStacks("X:9");
    a = BRAssetAllocate(NULL, L, LN, 1, IN, 1, 3, W, N(W), O, N(O));
    ck(a.status == BR_ASSET_ALLOCATION_INDETERMINATE && a.why == BR_ASSET_WHY_ARGUMENTS, "no header");
    a = BRAssetAllocate(&H, NULL, 1, 1, IN, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "no instructions where one is counted");
    a = BRAssetAllocate(&H, NULL, 0, 1, IN, 1, 3, W, N(W), O, N(O));
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 1, "no instructions, none counted");
    a = BRAssetAllocate(&H, L, LN, 1, NULL, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "no inputs where one is counted");
    a = BRAssetAllocate(&H, L, LN, 1, NULL, 0, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_NO_INPUTS, "no inputs, none counted");
    st[0].known = 1; st[0].units = NULL; st[0].unitCount = 1;
    a = BRAssetAllocate(&H, L, LN, 1, st, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "a known stack without its entries");
    u[0].assetId = NULL; u[0].count = 0; u[0].ruleFree = 1;
    st[0].units = u;
    a = BRAssetAllocate(&H, L, LN, 1, st, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "an entry without an id, even one not counted");
    st[0].known = 0; st[0].units = NULL; st[0].unitCount = 3;
    a = BRAssetAllocate(&H, L, LN, 1, st, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_INPUT_UNKNOWN, "an unknown stack's entries are not read");
    st[1].known = 1; st[1].units = NULL; st[1].unitCount = 1;
    a = BRAssetAllocate(&H, L, LN, 1, st, 2, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "a known second stack without its entries, after an unknown one");
    u[0].assetId = X; u[0].count = 9; u[0].ruleFree = 1;
    u[1].assetId = NULL; u[1].count = 0; u[1].ruleFree = 1;
    st[0].known = 1; st[0].units = u; st[0].unitCount = 1;
    a = BRAssetAllocate(&H, L, LN, 1, st, 2, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "a known second stack without its entries, after a known one");
    st[0].unitCount = 2;
    a = BRAssetAllocate(&H, L, LN, 1, st, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "a second entry without an id");
    a = BRAssetAllocate(&H, L, LN, 0, NULL, 1, 3, W, N(W), O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "arguments are checked first");

    issuanceHdr(5, 10);
    a = BRAssetAllocateIssuance(NULL, L, 0, X, 3, 1, O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "issuance: no header");
    a = BRAssetAllocateIssuance(&H, NULL, 1, X, 3, 1, O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "issuance: no instructions where one is counted");
    a = BRAssetAllocateIssuance(&H, L, 0, NULL, 3, 1, O, N(O));
    ck(a.why == BR_ASSET_WHY_ARGUMENTS, "issuance: no id");
    a = BRAssetAllocateIssuance(&H, NULL, 0, X, 3, 1, O, N(O));
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED, "issuance: no instructions, none counted");

    a.status = BR_ASSET_ALLOCATION_ALLOCATED; a.why = BR_ASSET_WHY_NONE; a.instructionsVoided = 0; a.count = 1;
    c = BRAssetCreditForOutput(a, NULL, NULL, 0, 0, 3);
    ck(c.kind == BR_ASSET_OUTPUT_UNKNOWN, "credit: an allocation without its outputs");
    a.count = 0;
    c = BRAssetCreditForOutput(a, NULL, NULL, 0, 0, 3);
    ck(c.kind == BR_ASSET_OUTPUT_HOLDS && ! c.hasUnits && ! c.label, "credit: an allocation of nothing");
    a.status = BR_ASSET_ALLOCATION_INDETERMINATE;
    c = BRAssetCreditForOutput(a, NULL, NULL, 1, 0, 3);
    ck(c.kind == BR_ASSET_OUTPUT_UNKNOWN, "credit: no instructions where one is counted");
    c = BRAssetCreditForOutput(a, NULL, NULL, 0, 0, 3);
    ck(c.kind == BR_ASSET_OUTPUT_HOLDS, "credit: no instructions, none counted");
    a.status = (BRAssetAllocationStatus)7;
    c = BRAssetCreditForOutput(a, NULL, NULL, 0, 2, 3);
    ck(c.kind == BR_ASSET_OUTPUT_UNKNOWN, "credit: a status this header does not define is not decided");

    hdr(DA_TRANSFER, 3, "0:1");
    d = BRAssetMaxDeliverable(NULL, L, LN, 0, 3);
    ck(! d.unbounded && d.max == 0, "bound: no header");
    d = BRAssetMaxDeliverable(&H, NULL, 1, 0, 3);
    ck(! d.unbounded && d.max == 0, "bound: no instructions where one is counted");
    d = BRAssetMaxDeliverable(&H, NULL, 0, 2, 3);
    ck(d.unbounded, "bound: no instructions, none counted");

    r = BRAssetResolveInputUnits(NULL, 1, NULL, 0);
    ck(! r.known && r.zeroRowCount == 0, "units: no inputs where one is counted");
    r = BRAssetResolveInputUnits(NULL, 0, NULL, 0);
    ck(r.known && r.units == 0, "units: no inputs, none counted");
    memset(f, 0, sizeof(f));
    f[0].hasRow = 1; f[0].rowIsTargeted = BR_ASSET_FACT_YES;
    f[1] = f[0];
    r = BRAssetResolveInputUnits(f, 2, rows, 1);
    ck(! r.known && r.zeroRowCount == 0 && r.otherUnits == 0, "units: more rows storing zero than room: none reported");
    r = BRAssetResolveInputUnits(f, 1, rows, 0);
    ck(! r.known && r.zeroRowCount == 0, "units: no room at all");
    r = BRAssetResolveInputUnits(f, 1, rows, 1);
    ck(! r.known && r.zeroRowCount == 1 && rows[0] == 0, "units: room for one");

    st[0] = BRAssetBackedInputStack(NULL, u);
    ck(! st[0].known, "stack: no facts");
    memset(f, 0, sizeof(f));
    f[0].hasRow = 1; f[0].credit = BR_ASSET_CREDIT_VERIFIED; f[0].quantity = 5; f[0].assetId = X;
    st[0] = BRAssetBackedInputStack(&f[0], NULL);
    ck(! st[0].known, "stack: no slot for the entry");
    f[0].quantity = 0;
    st[0] = BRAssetBackedInputStack(&f[0], NULL);
    ck(st[0].known && st[0].unitCount == 0, "stack: no slot needed");

    ck(BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, NULL, 0, NULL) == 0, "indexer: no credit to write");
    ck(BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, NULL, 1, &c) == 0 && c.kind == BR_ASSET_OUTPUT_UNKNOWN, "indexer: no entries where one is counted");
    ck(BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, NULL, 0, &c) == 1 && c.kind == BR_ASSET_OUTPUT_HOLDS && ! c.hasUnits, "indexer: none counted");
    u[0].assetId = NULL; u[0].count = 1;
    ck(BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, u, 1, &c) == 0 && c.kind == BR_ASSET_OUTPUT_UNKNOWN, "indexer: a counted entry without an id");
    u[0].count = 0; u[1].assetId = X; u[1].count = 2;
    ck(BRAssetCreditFromIndexer(BR_ASSET_STACK_FOUND, u, 2, &c) == 1 && c.hasUnits && c.count == 2, "indexer: an entry not counted is not read");
    ck(BRAssetCreditFromIndexer((BRAssetStackLookup)9, u, 2, &c) == 0, "indexer: an answer this header does not define");
    indexer(BR_ASSET_STACK_FOUND, "X:MAX,X:1", "null");
    indexer(BR_ASSET_STACK_FOUND, "X:MAX,Y:1,X:1", "mixed");
    indexer(BR_ASSET_STACK_FOUND, "X:HALF1,X:HALF,X:0,Y:-4", "holds X:9223372036854775807 label=X");
    indexer(BR_ASSET_STACK_NOT_UNSPENT, "X:5", "null");
    indexer(BR_ASSET_STACK_UNAVAILABLE, "X:5", "null");

    ck(asset_allocate_kat_tu2() == 1, "the second translation unit, which includes both headers twice, links and answers");
}

static void capacity(void)
{
    BRAssetAllocation a;

    hdr(DA_TRANSFER, 3, "0:1 1:1");
    parseStacks("X:1,Y:1,Z:0/-/A:1");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 2, O, N(O));
    ck(a.why == BR_ASSET_WHY_CAPACITY, "work for 2 of 4 entries: refused");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 3, O, N(O));
    ck(a.why == BR_ASSET_WHY_CAPACITY, "work for 3 of 4 entries (one not counted): refused");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 4, O, N(O));
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 3, "work for 4: allocated");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, NULL, 4, O, N(O));
    ck(a.why == BR_ASSET_WHY_CAPACITY, "no work buffer: refused");
    parseStacks("X:1");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, NULL, 4, O, N(O));
    ck(a.why == BR_ASSET_WHY_CAPACITY, "no work buffer for one entry: refused");
    parseStacks("X:1,Y:1,Z:0/-/A:1");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 4, O, 2);
    ck(a.why == BR_ASSET_WHY_CAPACITY, "out for 2 of 3 deliveries: refused");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 4, O, 3);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 3, "out for 3: allocated");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 4, NULL, 3);
    ck(a.why == BR_ASSET_WHY_CAPACITY, "no out buffer: refused");
    hdr(DA_BURN, 3, "31:1");
    parseStacks("X:1");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 1, NULL, 0);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 0, "nothing delivered: no out buffer needed");
    parseStacks("-");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, NULL, 0, NULL, 0);
    ck(a.status == BR_ASSET_ALLOCATION_NOT_AN_ASSET_TRANSFER, "nothing carried: no buffer needed");
    hdr(DA_TRANSFER, 3, "1:1 0:1 2:1 1:1 0:1");
    parseStacks("X:2,Y:2,A:9");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 3, O, 3 * 3);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 6, "outputCount entries for each counted entry suffice (%d)", (int)a.count);
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 3, O, 3 + 5);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED, "and so do one per entry and one per instruction");
    hdr(DA_TRANSFER, 3, "2:1:r");
    parseStacks("X:3");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 3, W, 1, O, 2);
    ck(a.why == BR_ASSET_WHY_CAPACITY, "out for 2 of a range's 3 deliveries: refused, not voided");
    hdr(DA_TRANSFER, 3, "7:1:r");
    parseStacks("X:9");
    a = BRAssetAllocate(&H, L, LN, 1, IN, INN, 9, W, 1, O, 1 + 8);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 9, "a range takes outputIndex + 1");

    hdr(DA_ISSUANCE, 3, "0:1 1:1");
    H.hasTotalQuantity = 1;
    H.totalQuantity = 10;
    a = BRAssetAllocateIssuance(&H, L, LN, X, 3, 1, O, 2);
    ck(a.why == BR_ASSET_WHY_CAPACITY, "issuance: out for 2 of 3: refused");
    a = BRAssetAllocateIssuance(&H, L, LN, X, 3, 1, O, 3);
    ck(a.status == BR_ASSET_ALLOCATION_ALLOCATED && a.count == 3, "issuance: one per instruction and one more");
    a = BRAssetAllocateIssuance(&H, L, LN, X, 3, 1, NULL, 3);
    ck(a.why == BR_ASSET_WHY_CAPACITY, "issuance: no out buffer");
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
    BRAssetAllocation a;
    BRAssetOutputCredit c;
    BRAssetInputUnits r;
    BRAssetDeliverable d;
    BRAssetInputFacts f;
    BRAssetInputStack s;
    BRAssetUnits slot;

    memset(&a, 0, sizeof(a)); memset(&c, 0, sizeof(c)); memset(&r, 0, sizeof(r)); memset(&d, 0, sizeof(d));
    memset(&f, 0, sizeof(f)); memset(&s, 0, sizeof(s));
    ck(a.status == BR_ASSET_ALLOCATION_INDETERMINATE && a.why == BR_ASSET_WHY_NONE, "an allocation nobody filled is not decided");
    ck(BRAssetCreditForOutput(a, NULL, NULL, 0, 0, 1).kind == BR_ASSET_OUTPUT_UNKNOWN, "and its only output is UNKNOWN");
    ck(c.kind == BR_ASSET_OUTPUT_UNKNOWN, "a credit nobody filled is UNKNOWN");
    ck(! r.known, "input units nobody filled are UNKNOWN");
    ck(! d.unbounded && d.max == 0, "a bound nobody filled delivers nothing");
    ck(! s.known, "a stack nobody filled is not known");
    ck(f.credit == BR_ASSET_CREDIT_UNCHECKED && ! BRAssetCreditCounts(f.credit), "facts nobody filled count for nothing");
    ck(f.rowIsTargeted == BR_ASSET_FACT_UNKNOWN && f.fundingIsAssetTx == BR_ASSET_FACT_UNKNOWN &&
       f.fundingHasDataOutput == BR_ASSET_FACT_UNKNOWN, "and say nothing");
    ck(! BRAssetBackedInputStack(&f, &slot).known, "their stack is not known");
    ck(! BRAssetResolveInputUnits(&f, 1, NULL, 0).known, "nor what they carried");
    ck((int)BR_ASSET_STACK_UNAVAILABLE == 0 && BRAssetCreditFromIndexer((BRAssetStackLookup)0, NULL, 0, &c) == 0,
       "an indexer answer nobody filled says nothing");
    ck(BR_ASSET_MAX_ISSUANCE == ((int64_t)1 << 54) - 1 && BR_ASSET_BURN_OUTPUT == 31, "the limits: 2^54 - 1, output 31");
    ck(BR_ASSET_ID_LENGTH == 38 && BR_ASSET_ID_BUFFER == 39, "an id: 38 characters, 39 bytes");
    a = BRAssetAllocate(&H, NULL, 0, 1, NULL, 0, 1, NULL, 0, NULL, 0);
    ck(a.instructionsVoided == 0 && a.count == 0, "a refusal sets no count");
    memset(&slot, 0, sizeof(slot));
    ck(isZero(&slot, sizeof(slot)), "a slot nobody filled is zero");
}

// ---- the Kotlin test tree's reference model (DigiAssetTransferModel), and conservation ----------

typedef struct { int n; MUnits u[M_HELD]; } RefOut;

static int refModel(const MStack *inputs, int inputCount, int outputCount, const BRAssetTransferInstruction *insts,
                    size_t instCount, RefOut *outs)
{
    MStacks stacks;
    int index = 0, allowSkip = 1, o, e, s, failed;
    size_t k;

    mFresh(inputs, inputCount, &stacks);
    for (o = 0; o < outputCount; o++) outs[o].n = 0;

    for (k = 0; k < instCount; k++) {
        int64_t left = insts[k].amount;
        const char *removed;

        failed = 0;
        do {
            if (index >= stacks.n || stacks.s[index].n == 0) { failed = 1; break; }
            removed = stacks.s[index].u[0].id;
            while (left > 0) {
                MUnits top;

                if (index >= stacks.n || stacks.s[index].n == 0) { failed = 1; break; }
                top = stacks.s[index].u[0];
                if (strcmp(top.id, removed) != 0) { failed = 1; break; }
                allowSkip = 1;
                if (top.count <= left) {
                    left -= top.count;
                    for (e = 1; e < stacks.s[index].n; e++) stacks.s[index].u[e - 1] = stacks.s[index].u[e];
                    stacks.s[index].n--;
                    if (stacks.s[index].n == 0) { index++; allowSkip = 0; }
                }
                else { stacks.s[index].u[0].count = top.count - left; left = 0; }
            }
            if (failed) break;
            if (insts[k].amount > 0) {
                RefOut *t;

                if (insts[k].outputIndex >= outputCount) { failed = 1; break; }
                t = &outs[insts[k].outputIndex];
                for (e = 0; e < t->n && strcmp(t->u[e].id, removed) != 0; e++) { }
                if (e < t->n) t->u[e].count += insts[k].amount;
                else { t->u[e].id = removed; t->u[e].count = insts[k].amount; t->n++; }
            }
            if (insts[k].skip) { if (allowSkip) index++; allowSkip = 1; }
        } while (0);
        if (failed) {
            for (o = 0; o < outputCount; o++) outs[o].n = 0;
            mFresh(inputs, inputCount, &stacks);
            break;
        }
    }
    for (s = 0; s < stacks.n; s++) {
        for (e = 0; e < stacks.s[s].n; e++) {
            RefOut *t = &outs[outputCount - 1];
            int j;

            if (stacks.s[s].u[e].count <= 0) continue;
            for (j = 0; j < t->n && strcmp(t->u[j].id, stacks.s[s].u[e].id) != 0; j++) { }
            if (j < t->n) t->u[j].count += stacks.s[s].u[e].count;
            else { t->u[j] = stacks.s[s].u[e]; t->n++; }
        }
    }
    return 1;
}

static uint64_t RNG;

static uint32_t rnd(void)   // xorshift64: the same stream on every machine
{
    RNG ^= RNG << 13; RNG ^= RNG >> 7; RNG ^= RNG << 17;
    return (uint32_t)(RNG >> 24);
}

static uint32_t below(uint32_t n) { return rnd() % n; }

// AssetTransferAllocatorTest's random test: its shape (seed 20261008, 4000 rounds, up to four inputs
// of one or two aggregatable assets, up to four outputs, up to three fixed instructions) with this
// suite's stream.
static void allocator_reference_model(void)
{
    static const char *const ids[] = { X, Y, A };
    int round, diffs = 0, none = 0, checked = 0;

    RNG = 20261008u;
    for (round = 0; round < 4000; round++) {
        RefOut outs[4];
        int inputCount = 1 + (int)below(4), outputCount, i, e, o, ok = 1, all = 1;
        size_t k, count;
        char spec[TEXT];
        size_t at = 0;

        for (i = 0; i < inputCount; i++) {
            const char *first = NULL;
            int n, j;

            if (i) at = put(spec, at, "/");
            if (below(3) == 0) { at = put(spec, at, "-"); continue; }
            n = 1 + (int)below(2);
            for (j = 0, e = 0; j < n; j++) {
                const char *idj = ids[below(3)];
                int64_t c = 1 + (int64_t)below(20);

                if (idj == first) continue;   // a stack holds each asset once
                if (! first) first = idj;
                at = put(spec, at, "%s%s:%lld", e ? "," : "", nameOf(idj), (long long)c);
                e++;
                all = 0;
            }
        }
        outputCount = 1 + (int)below(4);
        count = below(4);
        hdr(DA_TRANSFER, 3, "");
        for (k = 0; k < count; k++) {
            memset(&L[k], 0, sizeof(L[k]));
            L[k].outputIndex = (int32_t)below((uint32_t)outputCount + 1);
            L[k].amount = (int64_t)below(25);
            L[k].skip = (int)below(2);
        }
        LN = count;
        H.instructionCount = count;
        parseStacks(spec);
        R = BRAssetAllocate(&H, L, LN, 1, IN, INN, outputCount, W, N(W), O, N(O));

        if (all) {
            if (R.status != BR_ASSET_ALLOCATION_NOT_AN_ASSET_TRANSFER) ok = 0;
            none++;
        }
        else {
            int64_t carried[3] = { 0, 0, 0 }, gave[3] = { 0, 0, 0 };
            size_t q;

            refModel(MIN, (int)INN, outputCount, L, LN, outs);
            if (R.status != BR_ASSET_ALLOCATION_ALLOCATED) ok = 0;
            for (o = 0; ok && o < outputCount; o++) {   // the model's outputs, each list in order
                int n = 0;

                for (q = 0; q < R.count; q++) {
                    if (O[q].vout != o) continue;
                    if (n >= outs[o].n || strcmp(O[q].assetId, outs[o].u[n].id) != 0 || O[q].count != outs[o].u[n].count) ok = 0;
                    n++;
                }
                if (n != outs[o].n) ok = 0;
            }
            for (q = 0; q < R.count; q++) if (O[q].vout < 0 || O[q].vout >= outputCount) ok = 0;
            for (i = 0; i < (int)INN; i++) {
                for (e = 0; e < MIN[i].n; e++) {
                    int w = strcmp(MIN[i].u[e].id, X) == 0 ? 0 : strcmp(MIN[i].u[e].id, Y) == 0 ? 1 : 2;

                    carried[w] += MIN[i].u[e].count;
                }
            }
            for (q = 0; q < R.count; q++) {
                int w = strcmp(O[q].assetId, X) == 0 ? 0 : strcmp(O[q].assetId, Y) == 0 ? 1 : 2;

                gave[w] += O[q].count;
            }
            if (carried[0] != gave[0] || carried[1] != gave[1] || carried[2] != gave[2]) ok = 0;
            checked++;
        }
        if (! ok && diffs++ < 3) {
            char got[TEXT];

            showAllocation(got, R, O);
            ck(0, "round %d: stacks [%s], %d outputs, %d instructions: %s", round, spec, outputCount, (int)count, got);
        }
    }
    ck(checked > 3000 && none > 50, "the corpus: %d allocations held to the reference and to conservation, %d with nothing carried", checked, none);
    ck(diffs == 0, "the allocator agrees with the reference model and conserves units: %d differences", diffs);
}

// ---- model_sweep: the headers against the model over a fixed corpus -----------------------------

static const char *const POOL[] = { X, X, X, Y, Y, A, HY, DI, PT, "Ua1", "Uh7", "Ud9", "La", "Lx1" };
static int FREE[N(POOL)];
static int DIFFS;

static int64_t rndCount(void)
{
    uint32_t k = below(20);

    if (k == 0) return 0;
    if (k == 1) return -1 - (int64_t)below(3);
    if (k == 2) return INT64_MAX / 2 + (int64_t)below(3);
    if (k == 3) return INT64_MAX - (int64_t)below(2);
    return 1 + (int64_t)below(12);
}

static int64_t rndAmount(void)
{
    uint32_t k = below(24);

    if (k < 3) return 0;
    if (k == 3) return -1;
    if (k == 4) return INT64_MAX / 2 + (int64_t)below(2);
    if (k == 5) return INT64_MAX;
    if (k == 6) return 18014398509481983LL;
    return 1 + (int64_t)below(12);
}

static void rndInsts(int32_t outputCount)
{
    size_t k, count = below(6);

    LN = count;
    for (k = 0; k < count; k++) {
        BRAssetTransferInstruction *t = &L[k];
        uint32_t where = below(12);

        memset(t, 0, sizeof(*t));
        if (where == 0) t->outputIndex = 31;
        else if (where == 1) t->outputIndex = -1;
        else if (where == 2) t->outputIndex = (int32_t)below(64);
        else t->outputIndex = (int32_t)below((uint32_t)(outputCount > 0 ? outputCount : 1) + 2);
        t->amount = rndAmount();
        t->range = (below(6) == 0);
        t->percent = (below(14) == 0);
        t->skip = (below(3) == 0);
        t->isBurn = ! t->range && t->outputIndex == 31;
    }
    H.instructionCount = LN;
}

static int32_t rndOutputCount(void)
{
    uint32_t k = below(12);

    if (k == 0) return (int32_t)below(3) - 1;
    if (k == 1) return 30 + (int32_t)below(11);
    return 1 + (int32_t)below(6);
}

// Random stacks into IN/U and the model's MIN. An unknown stack keeps entries the header must not read.
static void rndStacks(void)
{
    size_t i, j, n;

    INN = (below(14) == 0) ? 0 : 1 + below(4);
    for (i = 0; i < INN; i++) {
        uint32_t k = below(12);

        n = (k < 3) ? 0 : 1 + below(3);
        IN[i].known = (k != 0);
        IN[i].units = U[i];
        IN[i].unitCount = n;
        MIN[i].known = IN[i].known;
        MIN[i].n = 0;
        for (j = 0; j < n; j++) {
            size_t which = below((uint32_t)N(POOL));

            U[i][j].assetId = POOL[which];
            U[i][j].count = rndCount();
            U[i][j].ruleFree = FREE[which];
            MIN[i].u[j].id = POOL[which];
            MIN[i].u[j].count = U[i][j].count;
            MIN[i].n++;
        }
    }
}

static int sweepRuleFree(const char *id)
{
    size_t i;

    for (i = 0; i < N(POOL); i++) if (strcmp(POOL[i], id) == 0) return FREE[i];
    return 0;
}

static void diff(const char *part, int round, const char *what, const char *got, const char *model)
{
    if (DIFFS++ < 4) ck(0, "%s round %d: %s: the header says %s, the model %s", part, round, what, got, model);
}

static int REACH[64];
enum { R_ALLOCATED, R_VOIDED, R_NONE, R_LEGACY, R_DESTROYED, R_LAST_SUM, R_CREDIT_UNKNOWN, R_CREDIT_MIXED,
       R_CREDIT_UNITS, R_CREDIT_LABEL, R_BOUND_UNBOUNDED, R_BOUND_COUNT, R_ISSUED, R_ISSUE_NONE, R_KNOWN,
       R_ZERO_ROWS, R_STACK_ONE, R_STACK_EMPTY, R_STACK_UNKNOWN, R_INDEXER_HOLDS, R_INDEXER_MIXED,
       R_INDEXER_NULL, R_ID, R_ID_REFUSED, R_WHY = 32, R_OTHER = 60 };

static void sweepAllocations(void)
{
    int round, i;
    int32_t vout;

    RNG = 0x5eed2026a110ca7eull;
    for (round = 0; round < 30000; round++) {
        char got[TEXT], model[TEXT];
        int32_t outputCount = rndOutputCount();
        static const BRAssetOperation ops[] = { DA_TRANSFER, DA_TRANSFER, DA_TRANSFER, DA_BURN, DA_BURN, DA_ISSUANCE, DA_UNDEFINED };
        int exact;

        for (i = 0; i < (int)N(POOL); i++) FREE[i] = (below(12) != 0);
        for (i = 0; i < (int)N(POOL); i++) {   // one answer an id: the Kotlin asks of the id
            int j;

            for (j = 0; j < i; j++) if (strcmp(POOL[j], POOL[i]) == 0) { FREE[i] = FREE[j]; break; }
        }
        memset(&H, 0, sizeof(H));
        H.kind = BR_ASSET_SCRIPT_ASSET;
        H.version = (below(4) == 0) ? (int32_t)below(3) : 3;
        H.operation = ops[below((uint32_t)N(ops))];
        rndInsts(outputCount);
        rndStacks();
        exact = (below(10) != 0);

        R = BRAssetAllocate(&H, L, LN, exact, IN, INN, outputCount, W, N(W), O, N(O));
        showAllocation(got, R, O);
        if (! exact) mIndeterminate(&MR, "payload not read exactly");
        else mAllocate(&MR, &H, L, LN, MIN, (int)INN, outputCount, sweepRuleFree);
        showModel(model, &MR);
        if (mBroke) { mBroke = 0; continue; }
        if (strcmp(got, model) != 0) { diff("allocate", round, "the allocation", got, model); continue; }

        if (R.status == BR_ASSET_ALLOCATION_ALLOCATED) REACH[R.instructionsVoided ? R_VOIDED : R_ALLOCATED]++;
        else if (R.status == BR_ASSET_ALLOCATION_NOT_AN_ASSET_TRANSFER) REACH[R_NONE]++;
        else REACH[R_WHY + (int)R.why]++;
        if (R.status == BR_ASSET_ALLOCATION_ALLOCATED && H.version < 3 && LN > 0 && INN > 0 && ! _BRAssetAllocCarries(&IN[0])) REACH[R_LEGACY]++;
        if (R.status == BR_ASSET_ALLOCATION_ALLOCATED && H.operation == DA_BURN) {
            for (i = 0; i < (int)LN; i++) if (L[i].outputIndex == 31 && ! L[i].range && L[i].amount > 0 && ! R.instructionsVoided) REACH[R_DESTROYED]++;
        }

        for (vout = -1; vout <= (outputCount > 0 ? outputCount : 1); vout++) {
            BRAssetOutputCredit c = BRAssetCreditForOutput(R, O, L, LN, vout, outputCount);
            BRAssetDeliverable d = BRAssetMaxDeliverable(&H, L, LN, vout, outputCount);
            int64_t m;
            int bounded = mMaxDeliverable(&H, L, LN, vout, outputCount, &m);

            showCredit(got, c);
            showMCredit(model, mForOutput(&MR, L, LN, vout, outputCount));
            if (strcmp(got, model) != 0) { diff("allocate", round, "an output's credit", got, model); break; }
            REACH[c.kind == BR_ASSET_OUTPUT_UNKNOWN ? R_CREDIT_UNKNOWN : c.kind == BR_ASSET_OUTPUT_MIXED ? R_CREDIT_MIXED :
                  c.hasUnits ? R_CREDIT_UNITS : c.label ? R_CREDIT_LABEL : R_OTHER]++;

            if (d.unbounded ? (bounded || d.max != 0) : (! bounded || d.max != m)) {
                char a[64], b[64];

                snprintf(a, sizeof(a), d.unbounded ? "unbounded" : "%lld", (long long)d.max);
                snprintf(b, sizeof(b), bounded ? "%lld" : "unbounded", (long long)m);
                diff("allocate", round, "an output's bound", a, b);
                break;
            }
            REACH[d.unbounded ? R_BOUND_UNBOUNDED : R_BOUND_COUNT]++;
        }

        // The same instructions as an issuance.
        {
            static const int64_t totals[] = { 0, -1, 1, 5, 10, 30, 18014398509481983LL, 18014398509481983LL + 1, INT64_MAX };
            const char *assetId = POOL[below((uint32_t)N(POOL))];
            uint32_t pick = below(12);

            H.operation = (below(8) == 0) ? DA_TRANSFER : DA_ISSUANCE;
            H.hasTotalQuantity = (below(10) != 0);
            H.totalQuantity = (pick < N(totals)) ? totals[pick] : 1 + (int64_t)below(40);
            H.aggregation = (BRAssetAggregation)below(3);
            R = BRAssetAllocateIssuance(&H, L, LN, assetId, outputCount, exact, O, N(O));
            showAllocation(got, R, O);
            mAllocateIssuance(&MR, &H, L, LN, assetId, outputCount, exact);
            showModel(model, &MR);
            if (mBroke) { mBroke = 0; continue; }
            if (strcmp(got, model) != 0) { diff("issue", round, "the issuance", got, model); continue; }
            if (R.status == BR_ASSET_ALLOCATION_ALLOCATED) REACH[R_ISSUED]++;
            else if (R.status == BR_ASSET_ALLOCATION_NOT_AN_ASSET_TRANSFER) REACH[R_ISSUE_NONE]++;
            else REACH[R_WHY + (int)R.why]++;

            for (vout = -1; vout <= (outputCount > 0 ? outputCount : 1); vout++) {
                showCredit(got, BRAssetCreditForOutput(R, O, L, LN, vout, outputCount));
                showMCredit(model, mForOutput(&MR, L, LN, vout, outputCount));
                if (strcmp(got, model) != 0) { diff("issue", round, "an output's credit", got, model); break; }
            }
        }
    }
}

static BRAssetFact rndFact(void) { return (BRAssetFact)below(3); }

static void sweepFacts(void)
{
    static const char *const ids[] = { X, Y, "", NULL };
    BRAssetInputFacts f[6];
    BRAssetUnits slot, entries[5];
    MUnits mu[5];
    size_t rows[6];
    int round;

    RNG = 0xfac75eed00c0ffeeull;
    for (round = 0; round < 30000; round++) {
        char got[TEXT], model[TEXT];
        size_t n = below(7), i;
        int why = (int)below(2);
        BRAssetInputUnits r;
        MResolved m;
        MCredit mc;
        BRAssetOutputCredit c;
        int ok, mok, lookup;
        size_t entryCount;

        for (i = 0; i < n; i++) {
            uint32_t q = below(8);

            f[i].hasRow = (below(3) != 0);
            f[i].credit = (BRAssetCredit)below(5);
            f[i].quantity = (q == 0) ? 0 : (q == 1) ? -1 : (q == 2) ? INT64_MAX / 2 + 1 : (q == 3) ? 0 : 1 + (int64_t)below(50);
            f[i].assetId = ids[below(4)];
            f[i].rowIsTargeted = rndFact();
            f[i].fundingIsAssetTx = rndFact();
            f[i].fundingHasDataOutput = rndFact();
        }
        r = BRAssetResolveInputUnits(f, n, why ? rows : NULL, why ? n : 0);
        m = mResolve(f, (int)n, why);
        showResolved(got, r, rows);
        showMResolved(model, &m);
        if (strcmp(got, model) != 0) diff("facts", round, "what the inputs carried", got, model);
        REACH[r.known ? R_KNOWN : r.zeroRowCount ? R_ZERO_ROWS : R_OTHER]++;

        for (i = 0; i < n; i++) {
            BRAssetInputStack s = BRAssetBackedInputStack(&f[i], &slot);
            MStack ms = mBacked(&f[i]);

            showStack(got, s);
            if (! ms.known) snprintf(model, sizeof(model), "?");
            else if (ms.n == 0) snprintf(model, sizeof(model), "-");
            else snprintf(model, sizeof(model), "%s:%lld:0", nameOf(ms.u[0].id), (long long)ms.u[0].count);
            if (strcmp(got, model) != 0) { diff("facts", round, "an input's stack", got, model); break; }
            REACH[! s.known ? R_STACK_UNKNOWN : s.unitCount ? R_STACK_ONE : R_STACK_EMPTY]++;
        }

        lookup = (int)below(4);
        entryCount = below(6);
        for (i = 0; i < entryCount; i++) {
            uint32_t q = below(6);

            entries[i].assetId = (below(4) == 0) ? Y : X;
            entries[i].count = (q == 0) ? 0 : (q == 1) ? -2 : (q == 2) ? INT64_MAX / 2 + 1 : 1 + (int64_t)below(9);
            entries[i].ruleFree = 0;
            mu[i].id = entries[i].assetId;
            mu[i].count = entries[i].count;
        }
        if (lookup == 3) lookup = 2;
        ok = BRAssetCreditFromIndexer((BRAssetStackLookup)lookup, entries, entryCount, &c);
        mok = mFromIndexer(lookup, mu, (int)entryCount, &mc);
        if (ok) showCredit(got, c);
        else snprintf(got, sizeof(got), "null");
        if (mok) showMCredit(model, mc);
        else snprintf(model, sizeof(model), "null");
        if (strcmp(got, model) != 0) diff("facts", round, "the indexer's answer", got, model);
        REACH[! ok ? R_INDEXER_NULL : c.kind == BR_ASSET_OUTPUT_MIXED ? R_INDEXER_MIXED : R_INDEXER_HOLDS]++;
    }
}

static void sweepIds(void)
{
    static const char hexChars[] = "0123456789abcdef";
    int round;

    RNG = 0x1d5eed0fa55e7000ull;
    for (round = 0; round < 3000; round++) {
        char txid[80], got[64], model[80];
        size_t len = 64, i;
        uint32_t k = below(10);
        int32_t vout;
        int locked = (below(6) != 0), aggregation = (int)below(5) - 1, divisibility = (int)below(10) - 1, ok, mok;

        if (k == 0) len = 63;
        else if (k == 1) len = 65;
        else if (k == 2) len = below(64);
        for (i = 0; i < len; i++) {
            uint32_t c = below(40);

            txid[i] = (c < 16) ? hexChars[c] : (c < 32) ? hexChars[c - 16] : (c < 38) ? (char)('A' + c - 32) : 'z';
        }
        txid[len] = '\0';
        k = below(8);
        if (k == 0) vout = -1 - (int32_t)below(4);
        else if (k == 1) vout = (int32_t)(rnd() & 0x7fffffffu);
        else if (k == 2) vout = INT32_MAX;
        else vout = (int32_t)below(300);

        ok = BRAssetIdForIssuance(txid, vout, locked, (BRAssetAggregation)aggregation, divisibility, got, sizeof(got));
        mok = mId(txid, vout, locked, aggregation, divisibility, model);
        if (! ok) strcpy(got, got[0] ? "!" : "null");
        if (! mok) strcpy(model, "null");
        if (strcmp(got, model) != 0) diff("id", round, "the id", got, model);
        else if (ok && strlen(got) != BR_ASSET_ID_LENGTH) diff("id", round, "the id's length", got, "38 characters");
        REACH[ok ? R_ID : R_ID_REFUSED]++;
    }
}

static void model_sweep(void)
{
    static const struct { int at; int min; const char *what; } reach[] = {
        { R_ALLOCATED, 2000, "allocations kept" }, { R_VOIDED, 1000, "allocations voided" }, { R_NONE, 500, "transactions moving no asset" },
        { R_LEGACY, 100, "pre-v3 allocations with an empty first input" }, { R_DESTROYED, 40, "burns to output 31" },
        { R_WHY + BR_ASSET_WHY_ISSUANCE, 100, "issuances refused by the transfer allocator" },
        { R_WHY + BR_ASSET_WHY_UNCLASSIFIABLE, 100, "unclassified operations" },
        { R_WHY + BR_ASSET_WHY_NO_OUTPUTS, 100, "no outputs" }, { R_WHY + BR_ASSET_WHY_NO_INPUTS, 100, "no inputs" },
        { R_WHY + BR_ASSET_WHY_INPUT_UNKNOWN, 100, "unknown inputs" }, { R_WHY + BR_ASSET_WHY_PERCENT, 100, "percent instructions" },
        { R_WHY + BR_ASSET_WHY_RULES, 100, "rule-bearing assets" }, { R_WHY + BR_ASSET_WHY_NOT_ISSUANCE, 100, "not an issuance" },
        { R_WHY + BR_ASSET_WHY_PAYLOAD_NOT_EXACT, 100, "payloads not read exactly" },
        { R_WHY + BR_ASSET_WHY_NO_ISSUED_AMOUNT, 100, "issuances without an amount" },
        { R_WHY + BR_ASSET_WHY_MALFORMED_INSTRUCTION, 100, "malformed instructions" },
        { R_WHY + BR_ASSET_WHY_NON_AGGREGABLE_DRAW, 30, "draws across entries of a non-aggregable asset" },
        { R_WHY + BR_ASSET_WHY_OVERFLOW, 30, "sums past INT64_MAX" },
        { R_CREDIT_UNKNOWN, 1000, "UNKNOWN credits" }, { R_CREDIT_MIXED, 30, "MIXED credits" }, { R_CREDIT_UNITS, 1000, "credits with units" },
        { R_CREDIT_LABEL, 1000, "credits of nothing with a label" }, { R_BOUND_UNBOUNDED, 1000, "unbounded outputs" },
        { R_BOUND_COUNT, 1000, "bounded outputs" }, { R_ISSUED, 1000, "issuances allocated" }, { R_ISSUE_NONE, 500, "issuances of nothing" },
        { R_KNOWN, 1000, "input totals known" }, { R_ZERO_ROWS, 500, "input totals unknown for rows storing zero" },
        { R_STACK_ONE, 1000, "stacks of one entry" }, { R_STACK_EMPTY, 1000, "empty stacks" }, { R_STACK_UNKNOWN, 1000, "unknown stacks" },
        { R_INDEXER_HOLDS, 1000, "indexer answers held" }, { R_INDEXER_MIXED, 300, "indexer answers mixed" },
        { R_INDEXER_NULL, 1000, "indexer answers saying nothing" }, { R_ID, 300, "ids derived" }, { R_ID_REFUSED, 300, "ids refused" },
    };
    size_t i;

    DIFFS = 0;
    memset(REACH, 0, sizeof(REACH));
    sweepAllocations();
    sweepFacts();
    sweepIds();
    for (i = 0; i < N(reach); i++) ck(REACH[reach[i].at] >= reach[i].min, "the corpus reaches %s: %d", reach[i].what, REACH[reach[i].at]);
    ck(DIFFS == 0, "the headers agree with the model over the corpus: %d differences", DIFFS);
}

// ---- main -------------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "allocator_over_assignment", allocator_over_assignment },
    { "allocator_no_asset_input", allocator_no_asset_input },
    { "allocator_million_from_one", allocator_million_from_one },
    { "allocator_honest_control", allocator_honest_control },
    { "allocator_second_input_asset", allocator_second_input_asset },
    { "allocator_skip_past_carrier", allocator_skip_past_carrier },
    { "allocator_plain_first_input", allocator_plain_first_input },
    { "allocator_mixed_input", allocator_mixed_input },
    { "allocator_missing_output", allocator_missing_output },
    { "allocator_range", allocator_range },
    { "allocator_output_31", allocator_output_31 },
    { "allocator_skip_after_emptied", allocator_skip_after_emptied },
    { "allocator_legacy", allocator_legacy },
    { "allocator_fails_closed", allocator_fails_closed },
    { "allocator_draw_across_entries", allocator_draw_across_entries },
    { "allocator_issuance", allocator_issuance },
    { "allocator_reads_exactly", allocator_reads_exactly },
    { "allocator_issuance_not_here", allocator_issuance_not_here },
    { "allocator_reference_model", allocator_reference_model },
    { "hold_issuance", hold_issuance },
    { "hold_transfer", hold_transfer },
    { "credit_unbacked_claim_unknown_inputs", credit_unbacked_claim_unknown_inputs },
    { "credit_unbacked_claim_known_inputs", credit_unbacked_claim_known_inputs },
    { "credit_voided_transfer", credit_voided_transfer },
    { "credit_honest_control", credit_honest_control },
    { "credit_second_input_asset", credit_second_input_asset },
    { "credit_mixed_input", credit_mixed_input },
    { "credit_indexer", credit_indexer },
    { "credit_issuance", credit_issuance },
    { "credit_issuance_not_exact", credit_issuance_not_exact },
    { "credit_issuance_core_rejects", credit_issuance_core_rejects },
    { "credit_bounds", credit_bounds },
    { "credit_unnamed_output", credit_unnamed_output },
    { "units_rows_and_plain_inputs", units_rows_and_plain_inputs },
    { "units_zero_rows", units_zero_rows },
    { "units_zero_rows_why", units_zero_rows_why },
    { "never_wraps_input_total", never_wraps_input_total },
    { "backed_stack_rules", backed_stack_rules },
    { "credit_states", credit_states },
    { "aggregation_of_id", aggregation_of_id },
    { "allocate_rules", allocate_rules },
    { "allocate_draws", allocate_draws },
    { "allocate_burns_and_legacy", allocate_burns_and_legacy },
    { "allocate_overflow", allocate_overflow },
    { "issuance_rules", issuance_rules },
    { "credit_rules", credit_rules },
    { "bound_rules", bound_rules },
    { "resolve_rules", resolve_rules },
    { "id_mainnet", id_mainnet },
    { "id_vectors", id_vectors },
    { "id_refusals", id_refusals },
    { "id_capacity", id_capacity },
    { "arguments", arguments },
    { "capacity", capacity },
    { "zero_structs", zero_structs },
    { "model_sweep", model_sweep },
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
