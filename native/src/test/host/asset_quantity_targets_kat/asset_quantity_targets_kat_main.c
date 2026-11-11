//
//  asset_quantity_targets_kat_main.c
//
//  Host KAT for the part of BRAssetQuantity.h that says which outputs a transaction may put asset
//  units on: BRAssetTargetsOutput, BRAssetIssuanceMayCreditLastOutput, BRAssetMayVoid,
//  BRAssetDestroys, BRAssetAssignedUnits and BRAssetConsumptionUncountable, moved from Android's
//  Kotlin (core/.../asset/AssetTxQuantity.kt: targetsOutput, issuanceMayCreditLastOutput, mayVoid,
//  destroys, assignedUnits, consumptionUncountable). Each must answer what its Kotlin answers on
//  every input, so every expectation here is fixed OUTSIDE the header, two ways:
//
//    1. BY HAND. The inputs and the expected answers of the Kotlin tests, value for value:
//       AssetHoldCoversCreditedOutputsTest (hold_*), AssetInputTargetingTest (targeting_*),
//       AssetArithmeticNeverWrapsTest's assertions on targetsOutput and assignedUnits
//       (never_wraps_*), AssetImplicitChangeTest's remainders (implicit_change_*: the Kotlin test
//       asserts implicitChange, which targetsOutput reads; the case holds the model's implicitChange
//       to the literal and the last output's answer to what follows from it), and the assertions on
//       these functions in DigiAssetDecoderTest (decoder_unclassifiable), AssetInputUnitsTest
//       (input_units_last_output) and UnbackedAssetCreditTest (uncountable_bound). Where a Kotlin
//       test decodes a script, the script is decoded here with BRAssetDecode.h. The cases without
//       one of those prefixes are this suite's own, for rules the Kotlin tests reach only through
//       their random streams; their expectations are read off the Kotlin source and held to the
//       model as well.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C: AssetTxQuantity's
//       functions and the AssetTransferAllocator they call (allocateIssuance, allocate and the
//       instruction loop with its input stacks, its void and its remainder), with the compiler's
//       overflow builtins where the Kotlin uses Math.addExact / multiplyExact (the header tests by
//       division). The header's issuance answer follows one supply through the instructions; the
//       model runs the allocator and looks at what the last output holds. The sweep_* cases hold
//       the header to the model over fixed pseudo-random inputs (the Kotlin property test's
//       kotlin.random stream cannot be replayed in C; its shapes and its invariant are used).
//
//  One case per process: `asset_quantity_targets_kat <case>` prints PASS:/FAIL: lines and
//  `RESULT <case> pass|fail`; `list` prints the case names. run.sh builds one binary per
//  ASSET_TARGETS_UNFIXED arm and holds each comparison arm to failing exactly its cases.
//

#include "BRAssetDecode.h"
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

int asset_quantity_targets_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice

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
#define ICAP 130       // this suite's instruction buffer: more than a carrier can hold (125)
#define SMAX 100       // and its script buffer

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

// ---- the model's types: what the Kotlin functions take ----------------------------------------

enum { M_ISSUANCE, M_TRANSFER, M_BURN, M_UNCLASSIFIABLE };            // AssetOperation, by name
enum { AGG_AGGREGATABLE, AGG_HYBRID, AGG_DISPERSED, AGG_NONE };       // Aggregation; AGG_NONE is null

static const char *OP_NAME[] = { "ISSUANCE", "TRANSFER", "BURN", "UNCLASSIFIABLE" };

typedef struct { int skip, range, percent, burn; int32_t out; int64_t amount; } Inst;   // TransferInstruction

typedef struct {                   // DecodedAssetHeader, the fields these functions read
    int operation, version, opcode;
    int hasQty; int64_t qty;       // totalQuantity: Long?
    int aggregation;
    size_t n; Inst insts[ICAP];    // transferInstructions
} Header;

typedef struct { int has; int64_t v; } OptLong;   // Long?
typedef struct { int has; int32_t v; } OptInt;    // Int?

static OptLong some(int64_t v) { OptLong o; o.has = 1; o.v = v; return o; }
static OptLong none(void) { OptLong o; o.has = 0; o.v = 0; return o; }
static OptInt someInt(int32_t v) { OptInt o; o.has = 1; o.v = v; return o; }
static OptInt noInt(void) { OptInt o; o.has = 0; o.v = 0; return o; }

static const char *showLong(OptLong o, char *buf, size_t cap)
{
    if (o.has) snprintf(buf, cap, "%lld", (long long)o.v);
    else snprintf(buf, cap, "null");
    return buf;
}

// The Kotlin tests' `ti(outputIndex, amount, ...)`.
static Inst ti(int32_t out, int64_t amount) { Inst i; memset(&i, 0, sizeof(i)); i.out = out; i.amount = amount; return i; }
static Inst tiRange(int32_t out, int64_t amount) { Inst i = ti(out, amount); i.range = 1; return i; }
static Inst tiPercent(int32_t out, int64_t amount) { Inst i = ti(out, amount); i.percent = 1; return i; }
static Inst tiBurn(int32_t out, int64_t amount) { Inst i = ti(out, amount); i.burn = 1; return i; }
static Inst tiSkip(int32_t out, int64_t amount) { Inst i = ti(out, amount); i.skip = 1; return i; }

// The Kotlin tests' header builders: version 3, aggregatable, no metadata.
static Header hdr(int operation, int opcode, OptLong qty, const Inst *insts, size_t n)
{
    Header h;
    size_t i;

    memset(&h, 0, sizeof(h));
    h.operation = operation; h.version = 3; h.opcode = opcode;
    h.hasQty = qty.has; h.qty = qty.v;
    h.aggregation = AGG_AGGREGATABLE;
    if (n > ICAP) { ck(0, "a test header of %zu instructions does not fit the buffer", n); n = 0; }
    h.n = n;
    for (i = 0; i < n; i++) h.insts[i] = insts[i];
    return h;
}

static Header transfer(const Inst *insts, size_t n) { return hdr(M_TRANSFER, 0x15, none(), insts, n); }
static Header burn(const Inst *insts, size_t n) { return hdr(M_BURN, 0x25, none(), insts, n); }
static Header issuance(int64_t total, const Inst *insts, size_t n) { return hdr(M_ISSUANCE, 0x01, some(total), insts, n); }

// A script as the Kotlin's DigiAssetDecoder().decode() gives it, read here with BRAssetDecode.h. 0, and a
// failed check, for a script that is not an asset carrier at all (the Kotlin's null).
static int decode(const char *hex, Header *h)
{
    uint8_t script[SMAX];
    size_t len = hx(hex, script, sizeof(script)), i;
    BRAssetHeader bh;
    BRAssetTransferInstruction insts[ICAP];
    BRAssetScriptKind kind;

    memset(h, 0, sizeof(*h));
    h->operation = M_UNCLASSIFIABLE;
    h->aggregation = AGG_AGGREGATABLE;
    if (len == 0) return 0;
    kind = BRAssetDecodeInstructions(script, len, &bh, insts, ICAP);
    if (kind == BR_ASSET_SCRIPT_NOT_ASSET) { ck(0, "%s is not an asset carrier", hex); return 0; }
    if (kind == BR_ASSET_SCRIPT_UNCLASSIFIABLE) return 1;   // DecodedAssetHeader.unclassifiable(): no instructions
    if (bh.instructionCount > ICAP) { ck(0, "%s carries more instructions than the buffer holds", hex); return 0; }

    h->operation = (bh.operation == DA_ISSUANCE) ? M_ISSUANCE : (bh.operation == DA_TRANSFER) ? M_TRANSFER :
                   (bh.operation == DA_BURN) ? M_BURN : M_UNCLASSIFIABLE;
    h->version = bh.version; h->opcode = bh.opcode;
    h->hasQty = bh.hasTotalQuantity; h->qty = bh.totalQuantity;
    h->aggregation = (bh.aggregation == BR_ASSET_AGGREGATABLE) ? AGG_AGGREGATABLE :
                     (bh.aggregation == BR_ASSET_HYBRID) ? AGG_HYBRID : AGG_DISPERSED;
    h->n = bh.instructionCount;

    for (i = 0; i < h->n; i++) {
        h->insts[i].skip = insts[i].skip; h->insts[i].range = insts[i].range; h->insts[i].percent = insts[i].percent;
        h->insts[i].burn = insts[i].isBurn; h->insts[i].out = insts[i].outputIndex; h->insts[i].amount = insts[i].amount;
    }

    return 1;
}

// ---- the model: AssetTxQuantity.kt, line for line ---------------------------------------------

// DecodedAssetHeader.hasRules
static int mHasRules(const Header *h) { return h->opcode == 3 || h->opcode == 4; }

// private fun destroys(header, inst) = header.operation == BURN && !inst.range && inst.outputIndex == 31
static int mDestroys(const Header *h, const Inst *inst)
{
    return h->operation == M_BURN && ! inst->range && inst->out == 31;
}

// private fun mayVoid(header, outputCount) = header.transferInstructions.any { inst ->
//     (!destroys(header, inst) && inst.outputIndex >= outputCount) || inst.amount == 0L }
static int mMayVoid(const Header *h, int32_t outputCount)
{
    size_t i;

    for (i = 0; i < h->n; i++) {
        const Inst *inst = &h->insts[i];

        if ((! mDestroys(h, inst) && inst->out >= outputCount) || inst->amount == 0) return 1;
    }

    return 0;
}

// fun assignedUnits(header): Long?
static OptLong mAssignedUnits(const Header *h)
{
    int64_t assigned = 0;
    size_t i;

    if (h->operation == M_UNCLASSIFIABLE) return none();

    for (i = 0; i < h->n; i++) {
        const Inst *inst = &h->insts[i];
        int64_t consumed;

        if (inst->percent) return none();
        if (inst->amount < 0 || inst->out < 0) return none();

        if (inst->range) {
            if (__builtin_mul_overflow((int64_t)inst->out + 1, inst->amount, &consumed)) return none();   // multiplyExact
        }
        else consumed = inst->amount;

        if (__builtin_add_overflow(assigned, consumed, &assigned)) return none();   // addExact
    }

    return some(assigned);
}

// fun consumptionUncountable(header): Boolean
static int mConsumptionUncountable(const Header *h)
{
    int64_t assigned = 0;
    size_t i;

    if (h->operation == M_UNCLASSIFIABLE) return 0;

    for (i = 0; i < h->n; i++) {
        const Inst *inst = &h->insts[i];
        int64_t consumed;

        if (inst->percent) continue;
        if (inst->amount < 0 || inst->out < 0) return 1;

        if (inst->range) {
            if (__builtin_mul_overflow((int64_t)inst->out + 1, inst->amount, &consumed)) return 1;
        }
        else consumed = inst->amount;

        if (__builtin_add_overflow(assigned, consumed, &assigned)) return 1;
    }

    return 0;
}

// fun implicitChangeVout(outputCount): Int = outputCount - 1   (a Kotlin Int wraps)
static int32_t mImplicitChangeVout(int32_t outputCount)
{
    return (int32_t)(uint32_t)((uint32_t)outputCount - 1u);
}

// fun implicitChange(header, inputUnits, outputCount): Long?
static OptLong mImplicitChange(const Header *h, OptLong inputUnits, int32_t outputCount)
{
    OptLong assigned;

    (void)outputCount;   // the Kotlin takes it and does not read it
    if (h->operation == M_ISSUANCE) return some(0);
    if (h->operation == M_UNCLASSIFIABLE) return none();
    if (! inputUnits.has || inputUnits.v < 0) return none();
    assigned = mAssignedUnits(h);
    if (! assigned.has) return none();
    if (assigned.v > inputUnits.v) return some(inputUnits.v);
    return some(inputUnits.v - assigned.v);
}

// ---- the model: AssetTransferAllocator.kt, line for line --------------------------------------
//
// An asset id is a small number here; its low two bits are what the Kotlin's aggregationOf reads
// from the id's prefix ("La"/"Ua" aggregatable, 'h' hybrid, 'd' dispersed, anything else null).

#define X_ASSET 4          // the Kotlin tests' X = "La" + 36 x: aggregatable
#define ISSUED_ASSET 7     // AssetTxQuantity's ISSUED_ASSET = "issued": aggregationOf gives null

static int mAggregationOf(int assetId) { return assetId & 3; }

typedef struct { int assetId; int64_t count; } Units;           // AssetUnits
#define ECAP 6
#define SCAP 6
typedef struct { size_t n; Units e[ECAP]; } Stack;              // List<AssetUnits>
typedef struct { int isNull; Stack s; } Input;                   // List<AssetUnits>?

enum { R_NOT_AN_ASSET_TRANSFER, R_ALLOCATED, R_INDETERMINATE };
typedef struct { int kind; int voided; } Result;                 // Allocated's outputs are in OUTS

// Result.Allocated.outputs: vout -> the units it holds, in the order they were added.
typedef struct { int32_t vout; int assetId; int64_t count; } OutEnt;
#define OCAP 16384
static OutEnt OUTS[OCAP];
static size_t OUTN = 0;
static int MODEL_LIMIT = 0;   // a test input the model's buffers cannot hold: a broken case, never an answer

// distribute's `add`: 0 where Math.addExact throws.
static int mAdd(int32_t vout, Units units)
{
    size_t i;

    for (i = 0; i < OUTN; i++) {
        if (OUTS[i].vout == vout && OUTS[i].assetId == units.assetId) {
            return ! __builtin_add_overflow(OUTS[i].count, units.count, &OUTS[i].count);
        }
    }

    if (OUTN >= OCAP) { MODEL_LIMIT = 1; return 1; }
    OUTS[OUTN].vout = vout; OUTS[OUTN].assetId = units.assetId; OUTS[OUTN].count = units.count;
    OUTN++;
    return 1;
}

static Result mResult(int kind, int voided) { Result r; r.kind = kind; r.voided = voided; return r; }

// freshStacks(): known.filter { it.isNotEmpty() }, copied
static size_t mFreshStacks(const Stack *known, size_t knownN, Stack *stacks)
{
    size_t i, n = 0;

    for (i = 0; i < knownN; i++) if (known[i].n > 0) stacks[n++] = known[i];
    return n;
}

static void mRemoveFirst(Stack *s)   // removeAt(0)
{
    size_t i;

    for (i = 1; i < s->n; i++) s->e[i - 1] = s->e[i];
    s->n--;
}

// private fun distribute(header, known, outputCount, burnType, legacyIgnoresInstructions, aggregation).
// `aggregationFromHeader`: the issuance's `{ header.aggregation }`; otherwise ::aggregationOf.
static Result mDistribute(const Header *h, const Stack *known, size_t knownN, int32_t outputCount, int burnType,
                          int legacyIgnoresInstructions, int aggregationFromHeader)
{
    Stack stacks[SCAP];
    size_t stacksN = mFreshStacks(known, knownN, stacks), index = 0, k, s, e;
    int voided = 0, allowSkip = 1;
    int32_t last;

    OUTN = 0;

    if (! legacyIgnoresInstructions) for (k = 0; k < h->n; k++) {
        const Inst *inst = &h->insts[k];
        int32_t output = inst->out;
        int64_t amount = inst->amount, total, left;
        int valid = 1, removed = 0;

        if (output < 0 || amount < 0) return mResult(R_INDETERMINATE, 0);   // "malformed instruction"

        if (inst->range) {
            if (__builtin_mul_overflow((int64_t)output + 1, amount, &total)) return mResult(R_INDETERMINATE, 0);   // overflow
        }
        else total = amount;

        do {   // val valid = run apply@{ ... }
            if (index >= stacksN || stacks[index].n == 0) { valid = 0; break; }
            left = total;
            removed = stacks[index].e[0].assetId;

            while (left > 0) {
                Units top;

                if (index >= stacksN || stacks[index].n == 0) { valid = 0; break; }
                top = stacks[index].e[0];
                if (top.assetId != removed) { valid = 0; break; }
                allowSkip = 1;

                if (top.count < left) {
                    int more = stacks[index].n > 1 || index + 1 < stacksN;
                    int aggregation;

                    if (! more) { valid = 0; break; }
                    aggregation = aggregationFromHeader ? h->aggregation : mAggregationOf(top.assetId);
                    if (aggregation == AGG_AGGREGATABLE) { /* Unit */ }
                    else if (aggregation == AGG_HYBRID) { valid = 0; break; }
                    else return mResult(R_INDETERMINATE, 0);   // "draw across entries of a non-aggregable asset"
                }

                if (top.count <= left) {
                    left -= top.count;
                    mRemoveFirst(&stacks[index]);
                    if (stacks[index].n == 0) {
                        index++;
                        allowSkip = 0;
                    }
                }
                else {
                    stacks[index].e[0].count = top.count - left;
                    left = 0;
                }
            }

            if (! valid) break;

            if (total > 0) {
                int destroyed = burnType && ! inst->range && output == 31;   // BURN_OUTPUT

                if (! destroyed) {
                    int32_t start, vout;
                    Units placed;

                    if (output >= outputCount) { valid = 0; break; }
                    start = inst->range ? 0 : output;
                    placed.assetId = removed; placed.count = amount;
                    if ((int64_t)output - start >= OCAP) { MODEL_LIMIT = 1; return mResult(R_INDETERMINATE, 0); }

                    for (vout = start; vout <= output; vout++) {
                        if (! mAdd(vout, placed)) return mResult(R_INDETERMINATE, 0);   // overflow
                    }
                }
            }

            if (inst->skip) {
                if (allowSkip) index++;
                allowSkip = 1;
            }
        } while (0);

        if (! valid) {   // take back what was applied, restore the inputs, stop reading
            OUTN = 0;
            stacksN = mFreshStacks(known, knownN, stacks);
            voided = 1;
            break;
        }
    }

    last = outputCount - 1;   // outputCount >= 1: both callers have checked

    for (s = 0; s < stacksN; s++) {
        for (e = 0; e < stacks[s].n; e++) {
            if (stacks[s].e[e].count > 0 && ! mAdd(last, stacks[s].e[e])) return mResult(R_INDETERMINATE, 0);
        }
    }

    return mResult(R_ALLOCATED, voided);
}

// fun allocate(header, inputs, outputCount, ruleFree). `ruleFree` is one answer for every asset, as in
// the Kotlin tests ({ true }).
static Result mAllocate(const Header *h, const Input *inputs, size_t inputsN, int32_t outputCount, int ruleFree)
{
    Stack known[SCAP];
    int burnType, allEmpty = 1, legacyIgnoresInstructions;
    size_t i, e;

    OUTN = 0;
    if (h->operation == M_TRANSFER) burnType = 0;
    else if (h->operation == M_BURN) burnType = 1;
    else return mResult(R_INDETERMINATE, 0);   // "issuance", "unclassifiable carrier"

    if (outputCount < 1) return mResult(R_INDETERMINATE, 0);
    if (inputsN == 0) return mResult(R_INDETERMINATE, 0);
    if (inputsN > SCAP) { MODEL_LIMIT = 1; return mResult(R_INDETERMINATE, 0); }
    for (i = 0; i < inputsN; i++) if (inputs[i].isNull) return mResult(R_INDETERMINATE, 0);

    for (i = 0; i < inputsN; i++) {   // known = inputs.map { stack -> stack!!.filter { it.count > 0L } }
        known[i].n = 0;
        for (e = 0; e < inputs[i].s.n; e++) if (inputs[i].s.e[e].count > 0) known[i].e[known[i].n++] = inputs[i].s.e[e];
    }

    // `known.any { stack -> stack.any { it.count < 0L } }`: read after the filter, it finds nothing.
    for (i = 0; i < inputsN; i++) for (e = 0; e < known[i].n; e++) if (known[i].e[e].count < 0) return mResult(R_INDETERMINATE, 0);

    for (i = 0; i < inputsN; i++) if (known[i].n > 0) allEmpty = 0;
    if (allEmpty) return mResult(R_NOT_AN_ASSET_TRANSFER, 0);

    for (i = 0; i < h->n; i++) if (h->insts[i].percent) return mResult(R_INDETERMINATE, 0);
    if (! ruleFree) return mResult(R_INDETERMINATE, 0);

    legacyIgnoresInstructions = h->version < 3 && known[0].n == 0;
    return mDistribute(h, known, inputsN, outputCount, burnType, legacyIgnoresInstructions, 0);
}

// fun allocateIssuance(header, assetId, outputCount, payloadExact)
static Result mAllocateIssuance(const Header *h, int assetId, int32_t outputCount, int payloadExact)
{
    Stack known[1];
    size_t i;

    OUTN = 0;
    if (h->operation != M_ISSUANCE) return mResult(R_INDETERMINATE, 0);
    if (! payloadExact) return mResult(R_INDETERMINATE, 0);
    if (outputCount < 1) return mResult(R_INDETERMINATE, 0);
    if (! h->hasQty) return mResult(R_INDETERMINATE, 0);
    if (h->qty <= 0 || h->qty > ((int64_t)1 << 54) - 1) return mResult(R_NOT_AN_ASSET_TRANSFER, 0);   // MAX_ISSUANCE
    for (i = 0; i < h->n; i++) if (h->insts[i].percent) return mResult(R_INDETERMINATE, 0);

    known[0].n = 1; known[0].e[0].assetId = assetId; known[0].e[0].count = h->qty;
    return mDistribute(h, known, 1, outputCount, 0, 0, 1);
}

// What the last allocation left on `vout`: `outputs[vout].orEmpty().any { it.count != 0L }`.
static int mOutputHolds(int32_t vout)
{
    size_t i;

    for (i = 0; i < OUTN; i++) if (OUTS[i].vout == vout && OUTS[i].count != 0) return 1;
    return 0;
}

// What the last allocation left on `vout` is exactly one entry: `outputs[vout] == listOf(AssetUnits(asset, count))`.
static int mOutputIs(int32_t vout, int assetId, int64_t count)
{
    size_t i, entries = 0, match = 0;

    for (i = 0; i < OUTN; i++) {
        if (OUTS[i].vout != vout) continue;
        entries++;
        if (OUTS[i].assetId == assetId && OUTS[i].count == count) match++;
    }

    return entries == 1 && match == 1;
}

// The last allocation's outputs are exactly one entry: `outputs == mapOf(vout to listOf(AssetUnits(asset, count)))`.
static int mOutputsAre(int32_t vout, int assetId, int64_t count)
{
    return OUTN == 1 && OUTS[0].vout == vout && OUTS[0].assetId == assetId && OUTS[0].count == count;
}

// ---- the model: targetsOutput and its issuance helper -----------------------------------------

// private fun issuanceMayCreditLastOutput(header, outputCount)
static int mIssuanceMayCreditLastOutput(const Header *h, int32_t outputCount)
{
    Result r;

    if (mHasRules(h) || h->version < 3) return 1;
    r = mAllocateIssuance(h, ISSUED_ASSET, outputCount, 1);
    if (r.kind == R_ALLOCATED) return mOutputHolds(outputCount - 1);
    if (r.kind == R_INDETERMINATE) return 1;
    return 0;
}

// fun targetsOutput(header, vout, firstNonOpReturnVout, inputUnits, outputCount)
static int mTargetsOutput(const Header *h, int32_t vout, OptInt firstNonOpReturnVout, OptLong inputUnits, int32_t outputCount)
{
    int instructionTargets = 0;
    size_t i;

    if (h->operation == M_UNCLASSIFIABLE) return 1;

    for (i = 0; i < h->n && ! instructionTargets; i++) {
        const Inst *inst = &h->insts[i];

        if (mDestroys(h, inst)) instructionTargets = 0;
        else if (inst->range) instructionTargets = vout <= inst->out;
        else instructionTargets = inst->out == vout;
    }

    if (instructionTargets) return 1;
    if (h->operation == M_ISSUANCE && firstNonOpReturnVout.has && vout == firstNonOpReturnVout.v) return 1;

    if (vout == mImplicitChangeVout(outputCount)) {
        OptLong change;

        if (h->operation == M_ISSUANCE) return mIssuanceMayCreditLastOutput(h, outputCount);
        if (! (inputUnits.has && inputUnits.v == 0) && mMayVoid(h, outputCount)) return 1;   // inputUnits != 0L
        change = mImplicitChange(h, inputUnits, outputCount);
        return ! change.has || change.v > 0;
    }

    return 0;
}

// ---- the header, called with what the Kotlin function is called with -------------------------

static BRAssetOperation coreOp(int operation)
{
    return (operation == M_ISSUANCE) ? DA_ISSUANCE : (operation == M_TRANSFER) ? DA_TRANSFER :
           (operation == M_BURN) ? DA_BURN : DA_UNDEFINED;
}

static BRAssetTransferInstruction coreInst(const Inst *inst)
{
    BRAssetTransferInstruction c;

    memset(&c, 0, sizeof(c));
    c.skip = inst->skip; c.range = inst->range; c.percent = inst->percent; c.isBurn = inst->burn;
    c.outputIndex = inst->out; c.amount = inst->amount;
    return c;
}

// The header's instruction list for `h`: a local array of exactly h->n entries, so a read past the end
// is a sanitizer report, and no array at all (NULL) for an empty list.
#define CORE_LIST(h, name) \
    BRAssetTransferInstruction name##_buf[(h)->n ? (h)->n : 1]; \
    const BRAssetTransferInstruction *name = (h)->n ? name##_buf : NULL; \
    { size_t i_; for (i_ = 0; i_ < (h)->n; i_++) name##_buf[i_] = coreInst(&(h)->insts[i_]); }

static int cDestroys(const Header *h, const Inst *inst)
{
    BRAssetTransferInstruction c = coreInst(inst);

    return BRAssetDestroys(coreOp(h->operation), &c);
}

static int cMayVoid(const Header *h, int32_t outputCount)
{
    CORE_LIST(h, l)
    return BRAssetMayVoid(coreOp(h->operation), outputCount, l, h->n);
}

static OptLong cAssignedUnits(const Header *h)
{
    int64_t v = -77;   // a value no answer leaves behind
    OptLong o;
    CORE_LIST(h, l)

    o.has = BRAssetAssignedUnits(coreOp(h->operation), l, h->n, &v);
    o.v = v;
    return o;
}

static int cConsumptionUncountable(const Header *h)
{
    CORE_LIST(h, l)
    return BRAssetConsumptionUncountable(coreOp(h->operation), l, h->n);
}

static int cIssuanceMayCreditLastOutput(const Header *h, int32_t outputCount)
{
    CORE_LIST(h, l)
    return BRAssetIssuanceMayCreditLastOutput(h->version, mHasRules(h), h->hasQty, h->qty, outputCount, l, h->n);
}

static int cTargetsOutput(const Header *h, int32_t vout, OptInt first, OptLong inputUnits, int32_t outputCount)
{
    CORE_LIST(h, l)
    return BRAssetTargetsOutput(coreOp(h->operation), h->version, mHasRules(h), h->hasQty, h->qty, vout, first.has, first.v,
                                inputUnits.has, inputUnits.v, outputCount, l, h->n);
}

// An answer of the header's that is not 0 or 1, or a null with a value left behind, is not an answer.
static int sameOpt(OptLong a, OptLong b) { return a.has == b.has && (! a.has || a.v == b.v); }

// ---- the checks the cases are written in -----------------------------------------------------

// targetsOutput three ways: the expected answer, the header, the model.
static void targets(const char *what, const Header *h, int32_t vout, OptInt first, OptLong inputUnits, int32_t outputCount, int want)
{
    char buf[32];
    int got = cTargetsOutput(h, vout, first, inputUnits, outputCount);
    int model = mTargetsOutput(h, vout, first, inputUnits, outputCount);

    ck(got == want && model == want, "%s: output %d of %d, units in %s: %s (header %d, model %d)", what, (int)vout,
       (int)outputCount, showLong(inputUnits, buf, sizeof(buf)), want ? "may carry units" : "carries none", got, model);
}

// AssetHoldCoversCreditedOutputsTest's held(h, vout, inputUnits, outputCount = 3, first = 0).
static void held(const char *what, const Header *h, int32_t vout, OptLong inputUnits, int want)
{
    targets(what, h, vout, someInt(0), inputUnits, 3, want);
}

// assignedUnits two ways against the expected answer.
static void assigned(const char *what, const Header *h, OptLong want)
{
    char b1[32], b2[32], b3[32];
    OptLong got = cAssignedUnits(h), model = mAssignedUnits(h);

    ck(sameOpt(got, want) && (got.has || got.v == 0) && sameOpt(model, want), "%s: the instructions assign %s (header %s, model %s)",
       what, showLong(want, b1, sizeof(b1)), showLong(got, b2, sizeof(b2)), showLong(model, b3, sizeof(b3)));
}

// The Kotlin's implicitChange is the remainder targetsOutput reads for a transfer's or burn's last output.
// `want` is the Kotlin test's literal, held against the model. `wantLast` is what targetsOutput then
// gives the transaction's last output (first non-OP_RETURN output 0), held against the header and the
// model: for a transfer or burn whose instructions do not name the last output and cannot be voided, 1
// exactly when the remainder is null or above zero.
static void remainder(const char *what, const Header *h, OptLong inputUnits, int32_t outputCount, OptLong want, int wantLast)
{
    char b1[32], b2[32], b3[32];
    OptLong model = mImplicitChange(h, inputUnits, outputCount);

    ck(sameOpt(model, want), "%s: units in %s, %d outputs: the remainder is %s (model %s)", what,
       showLong(inputUnits, b1, sizeof(b1)), (int)outputCount, showLong(want, b2, sizeof(b2)), showLong(model, b3, sizeof(b3)));
    targets(what, h, mImplicitChangeVout(outputCount), someInt(0), inputUnits, outputCount, wantLast);
}

// ---- AssetHoldCoversCreditedOutputsTest -------------------------------------------------------
//
// The allocation assertions of the Kotlin test are held against the model allocator (the header has
// no allocator); the targetsOutput assertions against the header and the model. `payloadExact = true`
// is the Kotlin test's own argument; that these scripts re-read exactly is asset_decode_kat's
// exact_hold_covers.

static const OptLong NULL_0_10[] = { { 0, 0 }, { 1, 0 }, { 1, 10 } };   // listOf(null, 0L, 10L)

// qty 10, one instruction -> output 0 for 4: 6 are left on the last output.
static void hold_issuance_remainder(void)
{
    Header h;
    Result r;
    size_t i;

    if (! decode("6a08" "44410305" "0a" "00" "04" "10", &h)) return;
    ck(h.operation == M_ISSUANCE, "the carrier is an %s", OP_NAME[h.operation]);
    r = mAllocateIssuance(&h, X_ASSET, 3, 1);
    ck(r.kind == R_ALLOCATED && mOutputIs(2, X_ASSET, 6), "the allocation leaves [X:6] on output 2 (kind %d, %zu entries)", r.kind, OUTN);
    for (i = 0; i < N(NULL_0_10); i++) held("an issuance remainder", &h, 2, NULL_0_10[i], 1);
}

// No instruction at all: the whole supply lands on the last output.
static void hold_issuance_no_instruction(void)
{
    Header h;
    size_t i;

    if (! decode("6a06" "44410305" "0a" "10", &h)) return;
    for (i = 0; i < N(NULL_0_10); i++) held("an issuance with no instruction", &h, 2, NULL_0_10[i], 1);
}

// An issuance whose instruction names an output the transaction does not have is void.
static void hold_issuance_missing_output(void)
{
    Header h;
    Result r;

    if (! decode("6a08" "44410305" "0a" "05" "04" "10", &h)) return;
    r = mAllocateIssuance(&h, X_ASSET, 3, 1);
    ck(r.kind == R_ALLOCATED && mOutputsAre(2, X_ASSET, 10), "the allocation is {2: [X:10]} (kind %d, %zu entries)", r.kind, OUTN);
    held("an issuance naming a missing output", &h, 2, none(), 1);
    held("an issuance naming a missing output", &h, 2, some(0), 1);
}

// Assigning more than is issued voids the instructions.
static void hold_issuance_over_assigned(void)
{
    static const char *SCRIPTS[] = { "6a08" "44410305" "0a" "00" "14" "10", "6a08" "44410305" "0a" "00" "14" "14" };   // aggregatable, hybrid
    size_t i;

    for (i = 0; i < N(SCRIPTS); i++) {
        Header h;

        if (! decode(SCRIPTS[i], &h)) return;
        held(i == 0 ? "an issuance assigning 20 of 10, aggregatable" : "an issuance assigning 20 of 10, hybrid", &h, 2, none(), 1);
    }
}

// A payload that is not read exactly (an earlier version, or a rules block) leaves the remainder unknown.
static void hold_issuance_not_read_exactly(void)
{
    Header v2, rules;
    Inst one = ti(0, 10);

    if (! decode("6a07" "4441020526" "4000", &v2)) return;
    ck(v2.operation == M_ISSUANCE, "the version 2 carrier is an %s", OP_NAME[v2.operation]);
    held("a version 2 issuance", &v2, 2, none(), 1);

    rules = v2;   // v2.copy(version = 3, opcode = 3, totalQuantity = 10L, transferInstructions = listOf(ti(0, 10)))
    rules.version = 3; rules.opcode = 3; rules.hasQty = 1; rules.qty = 10; rules.n = 1; rules.insts[0] = one;
    ck(mHasRules(&rules), "opcode 3 has rules");
    held("an issuance with rules", &rules, 2, some(0), 1);
}

// GUARD. An issuance that hands out its whole supply leaves no remainder.
static void hold_issuance_whole_supply(void)
{
    Header h;
    Result r;
    size_t i;

    if (! decode("6a08" "44410305" "0a" "00" "0a" "10", &h)) return;
    r = mAllocateIssuance(&h, X_ASSET, 3, 1);
    ck(r.kind == R_ALLOCATED && mOutputsAre(0, X_ASSET, 10), "the allocation is {0: [X:10]} (kind %d, %zu entries)", r.kind, OUTN);
    for (i = 0; i < N(NULL_0_10); i++) held("an issuance assigning its whole supply", &h, 2, NULL_0_10[i], 0);
    held("an issuance assigning its whole supply", &h, 0, none(), 1);
}

static Input oneInput(int assetId, int64_t count)   // listOf(AssetUnits(assetId, count))
{
    Input in;

    memset(&in, 0, sizeof(in));
    in.s.n = 1; in.s.e[0].assetId = assetId; in.s.e[0].count = count;
    return in;
}

// One instruction to output 5 of a three-output transaction: void, so all 9 input units land on the last output.
static void hold_transfer_missing_output(void)
{
    Header h;
    Input in = oneInput(X_ASSET, 9);
    Result r;

    if (! decode("6a06" "44410315" "05" "09", &h)) return;
    ck(h.operation == M_TRANSFER, "the carrier is a %s", OP_NAME[h.operation]);
    r = mAllocate(&h, &in, 1, 3, 1);
    ck(r.kind == R_ALLOCATED && r.voided == 1 && mOutputsAre(2, X_ASSET, 9), "the allocation is {2: [X:9]}, voided (kind %d, voided %d)",
       r.kind, r.voided);
    held("inputs carried exactly what was assigned", &h, 2, some(9), 1);
    held("inputs carried more", &h, 2, some(12), 1);
    held("inputs unknown", &h, 2, none(), 1);
}

// An instruction that moves zero units still reads an input; after the inputs are used up that read
// voids every instruction, though the instructions assign exactly what came in.
static void hold_zero_amount_after_inputs_used(void)
{
    Header h;
    Input in = oneInput(X_ASSET, 5);
    Result r;

    if (! decode("6a06" "44410315" "05" "09", &h)) return;
    h.n = 2; h.insts[0] = ti(0, 5); h.insts[1] = ti(1, 0);
    r = mAllocate(&h, &in, 1, 3, 1);
    ck(r.kind == R_ALLOCATED && r.voided == 1 && mOutputsAre(2, X_ASSET, 5), "the allocation is {2: [X:5]}, voided (kind %d, voided %d)",
       r.kind, r.voided);
    held("a zero-amount instruction after the inputs are used", &h, 2, some(5), 1);
}

// GUARD: a range instruction whose end is past the outputs is void, and it already names the last output.
static void hold_range_past_outputs(void)
{
    Header h;
    Input in = oneInput(X_ASSET, 12);
    Result r;

    if (! decode("6a06" "44410315" "05" "09", &h)) return;
    h.n = 1; h.insts[0] = tiRange(3, 3);
    r = mAllocate(&h, &in, 1, 3, 1);
    ck(r.kind == R_ALLOCATED && mOutputsAre(2, X_ASSET, 12), "the allocation is {2: [X:12]} (kind %d)", r.kind);
    held("a range transfer past the outputs", &h, 2, some(12), 1);
}

// GUARD. A void transfer whose inputs carried nothing is not an asset transfer.
static void hold_void_transfer_no_units(void)
{
    Header h;
    Input in;
    Result r;

    memset(&in, 0, sizeof(in));   // emptyList()
    if (! decode("6a06" "44410315" "05" "09", &h)) return;
    r = mAllocate(&h, &in, 1, 3, 1);
    ck(r.kind == R_NOT_AN_ASSET_TRANSFER, "the allocation is NotAnAssetTransfer (kind %d)", r.kind);
    held("a void transfer with no units in", &h, 2, some(0), 0);
}

// GUARD. A burn to the destroy marker in a transaction of fewer than 32 outputs is not void.
static void hold_burn_marker(void)
{
    Header h;
    Input in = oneInput(X_ASSET, 9);
    Result r;

    if (! decode("6a06" "44410325" "1f" "09", &h)) return;
    ck(h.operation == M_BURN, "the carrier is a %s", OP_NAME[h.operation]);
    r = mAllocate(&h, &in, 1, 3, 1);
    ck(r.kind == R_ALLOCATED && r.voided == 0 && OUTN == 0, "the allocation is {}, not voided (kind %d, voided %d, %zu entries)",
       r.kind, r.voided, OUTN);
    held("a burn to the destroy marker", &h, 2, some(9), 0);
}

// Generated transfers, burns and issuances (version 3, fixed and range instructions, one aggregatable
// asset, no skip): every output the allocation credits, the OP_RETURN aside, is held, for the input
// total the allocation drew from and when that total is unknown. The Kotlin test's shapes on this
// suite's stream; the allocation is the model allocator's.
static void hold_every_credited_output(void)
{
    int draw, checked = 0, wrong = 0;

    RNG = 0x5eedULL * 0x9e3779b97f4a7c15ULL + 1;

    for (draw = 0; draw < 20000; draw++) {
        int32_t outputCount = 1 + (int32_t)below(6), opReturn = (int32_t)below((uint64_t)outputCount), v;
        OptInt first = noInt();
        int op = (int[]){ M_TRANSFER, M_BURN, M_ISSUANCE }[below(3)];
        Inst insts[3];
        size_t n = (size_t)below(4), i, e;
        Header h;
        int64_t inputUnits;
        Result r;
        OutEnt credited[64];
        size_t creditedN;

        for (v = 0; v < outputCount; v++) if (v != opReturn) { first = someInt(v); break; }

        for (i = 0; i < n; i++) {
            int range = below(4) == 0;
            int32_t out = (op == M_BURN && below(4) == 0) ? 31 : (int32_t)below((uint64_t)outputCount + 2);

            insts[i] = ti(out, (int64_t)below(8));
            insts[i].range = range;
            insts[i].burn = ! range && out == 31 && op == M_BURN;
        }

        h = hdr(op, op == M_ISSUANCE ? 0x05 : op == M_BURN ? 0x25 : 0x15, op == M_ISSUANCE ? some(1 + (int64_t)below(19)) : none(), insts, n);
        inputUnits = (int64_t)below(20);

        if (op == M_ISSUANCE) r = mAllocateIssuance(&h, X_ASSET, outputCount, 1);
        else {
            Input in = oneInput(X_ASSET, inputUnits);

            r = mAllocate(&h, &in, 1, outputCount, 1);
        }

        if (r.kind != R_ALLOCATED) continue;
        creditedN = OUTN < N(credited) ? OUTN : N(credited);
        memcpy(credited, OUTS, creditedN * sizeof(credited[0]));   // the model's targetsOutput allocates again

        for (e = 0; e < creditedN; e++) {
            int a, b, ma, mb;

            if (credited[e].vout == opReturn || credited[e].count <= 0) continue;
            checked++;
            a = cTargetsOutput(&h, credited[e].vout, first, some(inputUnits), outputCount);
            b = cTargetsOutput(&h, credited[e].vout, first, none(), outputCount);
            ma = mTargetsOutput(&h, credited[e].vout, first, some(inputUnits), outputCount);
            mb = mTargetsOutput(&h, credited[e].vout, first, none(), outputCount);

            if ((a != 1 || b != 1 || ma != 1 || mb != 1) && wrong++ < 5) {
                ck(0, "draw %d: %s, %zu instructions, %lld in, %d outputs: credited output %d is held (header %d/%d, model %d/%d)", draw,
                   OP_NAME[op], n, (long long)inputUnits, (int)outputCount, (int)credited[e].vout, a, b, ma, mb);
            }
        }
    }

    ck(wrong == 0, "every credited output is held, for the input total and for an unknown one (%d not held)", wrong);
    ck(checked > 1000, "the generator exercised the rule (%d credited outputs)", checked);
}

// ---- AssetInputTargetingTest ------------------------------------------------------------------
//
// The Kotlin test asks AssetManager's walk, which asks targetsOutput with the units it resolved: null
// where the inputs are not known, the total where they are. The header there is version 3, opcode 0x15
// (0x01 with 100 units for the issuance), and the first non-OP_RETURN output is 0.

static void targeting_named_outputs(void)
{
    Inst percent[] = { tiPercent(1, 50) }, fixed[] = { ti(2, 0) }, ranged[] = { tiRange(3, 5) };
    Header carrier = transfer(percent, 1), fixedTx = transfer(fixed, 1), rangedTx = transfer(ranged, 1);

    targets("the output a percent instruction names", &carrier, 1, someInt(0), none(), 4, 1);
    targets("the output a percent instruction names", &carrier, 1, someInt(0), some(0), 4, 1);
    targets("the output a fixed instruction names", &fixedTx, 2, someInt(0), none(), 4, 1);
    targets("the output a fixed instruction names", &fixedTx, 2, someInt(0), some(0), 4, 1);
    targets("an output inside a range", &rangedTx, 1, someInt(0), none(), 6, 1);
    targets("an output inside a range", &rangedTx, 1, someInt(0), some(0), 6, 1);
}

// A percent instruction leaves the remainder unknown whatever the inputs carried.
static void targeting_last_output_beside_percent(void)
{
    Inst percent[] = { tiPercent(1, 50) };
    Header carrier = transfer(percent, 1);

    targets("the last output beside a percent instruction", &carrier, 3, someInt(0), none(), 4, 1);
    targets("the last output beside a percent instruction", &carrier, 3, someInt(0), some(0), 4, 1);
    targets("the last output beside a percent instruction", &carrier, 3, someInt(0), some(100), 4, 1);
}

// GUARD. An issuance's vout 2 is the issuer's DGB change: it hands out what it issues and leaves no remainder.
static void targeting_issuance_change(void)
{
    Inst all[] = { ti(0, 100) };
    Header h = issuance(100, all, 1);

    targets("the change of an issuance", &h, 2, someInt(0), none(), 3, 0);
    targets("the issuer's output", &h, 0, someInt(0), none(), 3, 1);
    targets("the issuer's output", &h, 0, someInt(0), some(0), 3, 1);
}

// GUARD. An output in the middle that no instruction names is change, whatever came in.
static void targeting_unnamed_middle_output(void)
{
    Inst send[] = { ti(0, 10) };
    Header h = transfer(send, 1);

    targets("an output nothing names", &h, 2, someInt(0), none(), 4, 0);
}

// Decided by the remainder: a fully assigned send, one that leaves a remainder, one whose inputs are unknown.
static void targeting_remainder(void)
{
    Inst full[] = { ti(0, 5), ti(2, 95) }, part[] = { ti(0, 10) };
    Header fullTx = transfer(full, 2), partTx = transfer(part, 1);

    targets("the last output of a fully assigned transfer", &fullTx, 3, someInt(0), some(100), 4, 0);
    targets("the last output of a transfer that leaves a remainder", &partTx, 3, someInt(0), some(100), 4, 1);
    targets("the last output when the inputs cannot be read", &fullTx, 3, someInt(0), none(), 4, 1);
}

// A total already settled is used: 100 in leaves nothing, 130 leaves 30, a settled "unknown" is an answer too.
static void targeting_settled_totals(void)
{
    Inst full[] = { ti(0, 5), ti(2, 95) };
    Header h = transfer(full, 2);

    targets("settled at 100", &h, 3, someInt(0), some(100), 4, 0);
    targets("settled at 130", &h, 3, someInt(0), some(130), 4, 1);
    targets("settled as unknown", &h, 3, someInt(0), none(), 4, 1);
}

// ---- AssetArithmeticNeverWrapsTest: its assertions on targetsOutput and assignedUnits ---------
//
// The Kotlin test's `header(...)` is a version 3 transfer (opcode 0x15, aggregatable). Its implicitChange
// assertions are carried as remainders (see `remainder`): the literal against the model, the last
// output's answer against the header.

// A carrier whose amounts cannot be counted is unclassifiable: every output is held.
static void never_wraps_unclassifiable(void)
{
    static const char *SCRIPTS[] = { "6a0b4441031500bfffffffffff", "6a0944410315007fffffff" };
    size_t i;
    int32_t vout;

    for (i = 0; i < N(SCRIPTS); i++) {
        Header h;

        if (! decode(SCRIPTS[i], &h)) return;
        ck(h.operation == M_UNCLASSIFIABLE, "%s is %s", SCRIPTS[i], OP_NAME[h.operation]);
        for (vout = 0; vout <= 2; vout++) targets(SCRIPTS[i], &h, vout, someInt(0), some(0), 3, 1);
    }
}

// A range's consumption past the signed range is unknown.
static void never_wraps_range_consumption(void)
{
    Header h, wide;

    if (! decode("6a0c444103154001a5d21dba0007", &h)) return;
    ck(h.operation == M_TRANSFER, "the carrier is a %s", OP_NAME[h.operation]);
    ck(h.n == 1 && h.insts[0].amount == 8000000000000000000LL, "its single instruction moves 8000000000000000000 (%zu instructions, %lld)",
       h.n, (long long)(h.n ? h.insts[0].amount : 0));
    assigned("a range of two outputs of 8000000000000000000", &h, none());
    remainder("a range of two outputs of 8000000000000000000", &h, some(0), 3, none(), 1);

    if (! decode("6a0d444103154200ffffffffffffff", &wide)) return;
    assigned("a range of 513 outputs", &wide, none());
    remainder("a range of 513 outputs", &wide, some(0), 514, none(), 1);
}

// A remainder is never more than the inputs carried.
static void never_wraps_remainder_bounded(void)
{
    Inst over[] = { ti(0, 150) }, exact[] = { ti(0, 100) };
    Header overTx = transfer(over, 1), exactTx = transfer(exact, 1);

    remainder("150 assigned", &overTx, some(100), 3, some(100), 1);
    remainder("100 assigned", &exactTx, some(100), 3, some(0), 0);
    remainder("100 assigned", &exactTx, some(-1), 3, none(), 1);
}

// A negative amount (only a hand-built header can carry one) is not a count.
static void never_wraps_negative_amount(void)
{
    Inst neg[] = { ti(0, -5) };
    Header h = transfer(neg, 1);

    assigned("an amount of -5", &h, none());
    remainder("an amount of -5", &h, some(1), 3, none(), 1);
}

// Two amounts whose sum is past the signed range are not a count.
static void never_wraps_sum(void)
{
    int64_t big = INT64_MAX / 2 + 1;
    Inst two[] = { ti(0, big), ti(0, big) };
    Header h = transfer(two, 2);

    assigned("two amounts of Long.MAX_VALUE / 2 + 1", &h, none());
}

// A range of 513 outputs of 35958565450000000 units: the product is past 2^64, and 2140448384 if it wraps.
// The Kotlin test encodes and decodes this one instruction; here it is the decoded header.
static void never_wraps_wrapping_range(void)
{
    Inst wrap[] = { tiRange(512, 35958565450000000LL) };
    Header h = transfer(wrap, 1);

    assigned("a range of 513 outputs of 35958565450000000", &h, none());
}

// ---- DigiAssetDecoderTest: every output of an UNCLASSIFIABLE carrier is targeted --------------

static void decoder_unclassifiable(void)
{
    Header h;
    int32_t vout;

    if (! decode("6a4d060044410215000a", &h)) return;   // transferPushdata2
    ck(h.operation == M_UNCLASSIFIABLE, "the OP_PUSHDATA2 carrier is %s", OP_NAME[h.operation]);

    for (vout = 0; vout <= 40; vout++) {
        targets("held whatever came in", &h, vout, someInt(0), some(0), 41, 1);
        targets("held when the inputs are unknown", &h, vout, someInt(0), none(), 41, 1);
    }

    remainder("no remainder is known", &h, some(100), 41, none(), 1);
    assigned("nothing is known to be consumed", &h, none());
}

// ---- AssetInputUnitsTest: the last output beside inputs whose total is not known ---------------

static void input_units_last_output(void)
{
    Inst send[] = { ti(0, 10) };
    Header h = transfer(send, 1);

    // `units` there is what resolveInputAssetUnits gives for an input that is itself targeted: null.
    targets("the last output may carry what the zero row held", &h, 3, someInt(0), none(), 4, 1);
    targets("an output in between, which nothing names", &h, 2, someInt(0), none(), 4, 0);
}

// ---- AssetImplicitChangeTest: the remainders ---------------------------------------------------
//
// transfer(): version 3, opcode 0x15, aggregatable. The Kotlin literal is the remainder; the last
// output's answer and the assigned units beside it are what that remainder follows from.

static void implicit_change_leftover(void)
{
    Inst send[] = { ti(0, 10) };
    Header h = transfer(send, 1);

    remainder("10 of 100 assigned", &h, some(100), 4, some(90), 1);
    assigned("10 to output 0", &h, some(10));
}

static void implicit_change_fully_assigned(void)
{
    Inst send[] = { ti(0, 5), ti(2, 95) };
    Header h = transfer(send, 2);

    remainder("5 + 95 of 100 assigned", &h, some(100), 4, some(0), 0);
    assigned("5 to output 0 and 95 to output 2", &h, some(100));
}

static void implicit_change_range(void)
{
    Inst send[] = { tiRange(2, 10) };
    Header h = transfer(send, 1);

    remainder("a range of three outputs of 10", &h, some(100), 4, some(70), 1);
    assigned("a range of three outputs of 10", &h, some(30));
}

// An instruction to output 31 with the burn flag, in a TRANSFER, consumes its units.
static void implicit_change_burn_flag_in_transfer(void)
{
    Inst send[] = { tiBurn(31, 40) };
    Header h = transfer(send, 1);

    remainder("40 to output 31 of a transfer", &h, some(100), 4, some(60), 1);
    assigned("40 to output 31 of a transfer", &h, some(40));
}

static void implicit_change_percent(void)
{
    Inst send[] = { tiPercent(0, 128) };
    Header h = transfer(send, 1);

    remainder("a percent instruction", &h, some(100), 4, none(), 1);
    assigned("a percent instruction", &h, none());
}

static void implicit_change_unknown_inputs(void)
{
    Inst send[] = { ti(0, 10) };
    Header h = transfer(send, 1);

    remainder("unknown input units", &h, none(), 4, none(), 1);
}

// Over-assignment: the last output receives everything the inputs carried.
static void implicit_change_over_assigned(void)
{
    Inst send[] = { ti(0, 150) };
    Header h = transfer(send, 1);

    remainder("150 assigned", &h, some(100), 4, some(100), 1);
    remainder("150 assigned", &h, some(0), 4, some(0), 0);
    assigned("150 to output 0", &h, some(150));
}

static void implicit_change_issuance(void)
{
    Inst all[] = { ti(0, 100) };
    Header h = issuance(100, all, 1);

    remainder("an issuance", &h, some(0), 3, some(0), 0);
}

static void implicit_change_burn(void)
{
    Inst marker[] = { tiBurn(31, 25) };
    Header h = burn(marker, 1);

    remainder("a burn of 25", &h, some(100), 3, some(75), 1);
    assigned("25 to the destroy marker", &h, some(25));
}

// implicitChangeVout: the last output.
static void implicit_change_vout(void)
{
    ck(mImplicitChangeVout(4) == 3 && _BRAssetLastVout(4) == 3, "the last of 4 outputs is 3 (header %d, model %d)", (int)_BRAssetLastVout(4),
       (int)mImplicitChangeVout(4));
    ck(mImplicitChangeVout(1) == 0 && _BRAssetLastVout(1) == 0, "the last of 1 output is 0 (header %d, model %d)", (int)_BRAssetLastVout(1),
       (int)mImplicitChangeVout(1));
}

// ---- UnbackedAssetCreditTest: what maxDeliverable shows of consumptionUncountable --------------
//
// maxDeliverable answers 0 for every output when consumptionUncountable is true, and for the last
// output it answers null otherwise. So a bound of 7, a null bound and a counted last output say
// "countable"; a bound of 0 on the last output says "uncountable".

static void uncountable(const char *what, const Header *h, int want)
{
    int got = cConsumptionUncountable(h), model = mConsumptionUncountable(h);

    ck(got == want && model == want, "%s: the consumption is %s (header %d, model %d)", what, want ? "not a count" : "a count", got, model);
}

static void uncountable_bound(void)
{
    Inst range[] = { tiRange(3, 7) }, percent[] = { tiPercent(0, 128) }, sum[] = { ti(0, INT64_MAX), ti(0, 1) };
    Header rangeTx = transfer(range, 1), percentTx = transfer(percent, 1), sumTx = transfer(sum, 2), control, wide;

    uncountable("a range to 3 of 7 (a bound of 7 on output 0)", &rangeTx, 0);
    uncountable("a percent instruction (no bound)", &percentTx, 0);
    if (! decode("6a06444103150005", &control)) return;   // CONTROL: 5 to output 0; the last output's count is believed
    uncountable("the control transfer", &control, 0);
    if (! decode("6a0d444103155fffffffffffffffff", &wide)) return;   // a range to 8191 of 2^54 - 1: a bound of 0 on the last output
    uncountable("a range to 8191 of 2^54 - 1", &wide, 1);
    uncountable("Long.MAX_VALUE and 1 (a bound of 0)", &sumTx, 1);
}

// ---- this suite's own cases --------------------------------------------------------------------
//
// One rule of the Kotlin source at a time, with the answer the source gives written out and the model
// held to it as well. The Kotlin tests reach these lines through targetsOutput and their random
// streams; here each line has an input that only it decides.

static void destroys(const char *what, int operation, Inst inst, int want)
{
    Header h = hdr(operation, 0, none(), NULL, 0);
    int got = cDestroys(&h, &inst), model = mDestroys(&h, &inst);

    ck(got == want && model == want, "%s: %s (header %d, model %d)", what, want ? "destroys its units" : "places its units", got, model);
}

// destroys: a BURN's non-range instruction to output 31, by the operation and the index alone.
static void destroys_rule(void)
{
    Inst flagOnly = ti(30, 5), noFlag = ti(31, 5);

    flagOnly.burn = 1;
    destroys("a burn's instruction to output 31", M_BURN, tiBurn(31, 5), 1);
    destroys("a burn's instruction to output 31 without the burn flag", M_BURN, noFlag, 1);
    destroys("a burn's instruction to output 31 of zero units", M_BURN, ti(31, 0), 1);
    destroys("a burn's percent instruction to output 31", M_BURN, tiPercent(31, 50), 1);
    destroys("a burn's range to output 31", M_BURN, tiRange(31, 5), 0);
    destroys("a burn's instruction to output 30", M_BURN, ti(30, 5), 0);
    destroys("a burn's instruction to output 32", M_BURN, ti(32, 5), 0);
    destroys("a burn's instruction to output 30 with the burn flag", M_BURN, flagOnly, 0);
    destroys("a transfer's instruction to output 31", M_TRANSFER, ti(31, 5), 0);
    destroys("a transfer's instruction to output 31 with the burn flag", M_TRANSFER, tiBurn(31, 5), 0);
    destroys("an issuance's instruction to output 31", M_ISSUANCE, ti(31, 5), 0);
    destroys("an unclassifiable carrier's instruction to output 31", M_UNCLASSIFIABLE, ti(31, 5), 0);
}

static void voids(const char *what, const Header *h, int32_t outputCount, int want)
{
    int got = cMayVoid(h, outputCount), model = mMayVoid(h, outputCount);

    ck(got == want && model == want, "%s, %d outputs: %s (header %d, model %d)", what, (int)outputCount,
       want ? "may be voided" : "cannot be voided", got, model);
}

// mayVoid: an instruction to an output the transaction lacks (unless it destroys), or of zero units.
static void may_void_rule(void)
{
    Inst missing[] = { ti(3, 5) }, zero[] = { ti(2, 0) }, marker[] = { ti(31, 5) }, markerRange[] = { tiRange(31, 5) },
         markerZero[] = { ti(31, 0) }, second[] = { ti(0, 5), ti(1, 0) }, secondMissing[] = { ti(0, 5), ti(4, 1) },
         percentZero[] = { tiPercent(0, 0) }, percentMissing[] = { tiPercent(5, 50) }, negative[] = { ti(-1, 1) }, one[] = { ti(0, 1) },
         range[] = { tiRange(3, 1) };
    Header h;

    h = transfer(NULL, 0);          voids("no instruction", &h, 3, 0);
    h = transfer(missing, 1);       voids("an instruction to output 3", &h, 3, 1);
                                    voids("an instruction to output 3", &h, 4, 0);
    h = transfer(range, 1);         voids("a range to output 3", &h, 3, 1);
                                    voids("a range to output 3", &h, 4, 0);
    h = transfer(zero, 1);          voids("an instruction of zero units", &h, 4, 1);
    h = burn(marker, 1);            voids("a burn's instruction to output 31", &h, 3, 0);
    h = burn(markerRange, 1);       voids("a burn's range to output 31", &h, 3, 1);
    h = burn(markerZero, 1);        voids("a burn's instruction of zero units to output 31", &h, 3, 1);
    h = transfer(marker, 1);        voids("a transfer's instruction to output 31", &h, 3, 1);
                                    voids("a transfer's instruction to output 31", &h, 32, 0);
    h = hdr(M_ISSUANCE, 1, some(9), marker, 1);
                                    voids("an issuance's instruction to output 31", &h, 3, 1);
    h = transfer(second, 2);        voids("a second instruction of zero units", &h, 3, 1);
    h = transfer(secondMissing, 2); voids("a second instruction to output 4", &h, 4, 1);
                                    voids("a second instruction to output 4", &h, 5, 0);
    h = transfer(percentZero, 1);   voids("a percent instruction of zero", &h, 3, 1);
    h = transfer(percentMissing, 1); voids("a percent instruction to output 5", &h, 3, 1);
                                    voids("a percent instruction to output 5", &h, 6, 0);
    h = transfer(one, 1);           voids("an instruction to output 0", &h, 1, 0);
                                    voids("an instruction to output 0", &h, 0, 1);
                                    voids("an instruction to output 0", &h, -1, 1);
                                    voids("an instruction to output 0", &h, INT32_MIN, 1);
    h = transfer(negative, 1);      voids("an instruction to output -1", &h, 0, 0);
                                    voids("an instruction to output -1", &h, -1, 1);
}

// assignedUnits: the sum of what the instructions draw, or null.
static void assigned_units_rule(void)
{
    const int64_t max = INT64_MAX;
    Inst one[] = { ti(0, 10) }, mixed[] = { ti(0, 5), tiRange(1, 7), ti(31, 2) }, burnTwo[] = { tiBurn(31, 25), ti(0, 5) },
         skip[] = { tiSkip(0, 5), ti(1, 6) }, zeroRange[] = { tiRange(8191, 0) }, negIndex[] = { ti(-1, 5) }, negRange[] = { tiRange(-1, 5) },
         negAfter[] = { ti(0, 5), ti(1, -1) }, percentAfter[] = { ti(0, 5), tiPercent(1, 50) }, percentNeg[] = { tiPercent(0, -1) },
         rangeMax[] = { tiRange(0, max) }, rangeFits[] = { tiRange(1, max / 2) }, rangeOver[] = { tiRange(1, max / 2 + 1) },
         sumFits[] = { ti(0, max), ti(1, 0) }, sumOver[] = { ti(0, max), ti(1, 1) }, sumRange[] = { ti(0, max - 13), tiRange(1, 7) },
         sumRangeFits[] = { ti(0, max - 14), tiRange(1, 7) }, wideOne[] = { tiRange(INT32_MAX, 1) },
         wideFits[] = { tiRange(INT32_MAX, 4294967295LL) }, wideOver[] = { tiRange(INT32_MAX, 4294967296LL) };
    Header h;

    h = transfer(NULL, 0);            assigned("no instruction", &h, some(0));
    h = transfer(one, 1);             assigned("10 to output 0", &h, some(10));
    h = transfer(mixed, 3);           assigned("5, a range of two of 7, and 2 to output 31 of a transfer", &h, some(21));
    h = burn(burnTwo, 2);             assigned("a burn of 25 and 5 to output 0", &h, some(30));
    h = issuance(100, one, 1);        assigned("an issuance's instruction of 10", &h, some(10));
    h = hdr(M_UNCLASSIFIABLE, 0x15, none(), one, 1);
                                      assigned("an unclassifiable carrier", &h, none());
    h = hdr(M_UNCLASSIFIABLE, 0x15, none(), NULL, 0);
                                      assigned("an unclassifiable carrier with no instruction", &h, none());
    h = transfer(skip, 2);            assigned("5 with skip set and 6", &h, some(11));
    h = transfer(zeroRange, 1);       assigned("a range of 8192 outputs of zero", &h, some(0));
    h = transfer(negIndex, 1);        assigned("an instruction to output -1", &h, none());
    h = transfer(negRange, 1);        assigned("a range to output -1", &h, none());
    h = transfer(negAfter, 2);        assigned("a second amount of -1", &h, none());
    h = transfer(percentAfter, 2);    assigned("a percent instruction after a fixed one", &h, none());
    h = transfer(percentNeg, 1);      assigned("a percent instruction of -1", &h, none());
    h = transfer(rangeMax, 1);        assigned("a range of one output of Long.MAX_VALUE", &h, some(max));
    h = transfer(rangeFits, 1);       assigned("a range of two outputs of Long.MAX_VALUE / 2", &h, some(max - 1));
    h = transfer(rangeOver, 1);       assigned("a range of two outputs of Long.MAX_VALUE / 2 + 1", &h, none());
    h = transfer(sumFits, 2);         assigned("Long.MAX_VALUE and 0", &h, some(max));
    h = transfer(sumOver, 2);         assigned("Long.MAX_VALUE and 1", &h, none());
    h = transfer(sumRangeFits, 2);    assigned("Long.MAX_VALUE - 14 and a range of two of 7", &h, some(max));
    h = transfer(sumRange, 2);        assigned("Long.MAX_VALUE - 13 and a range of two of 7", &h, none());
    h = transfer(wideOne, 1);         assigned("a range to output Int.MAX_VALUE of 1", &h, some(2147483648LL));
    h = transfer(wideFits, 1);        assigned("a range to output Int.MAX_VALUE of 2^32 - 1", &h, some(9223372034707292160LL));
    h = transfer(wideOver, 1);        assigned("a range to output Int.MAX_VALUE of 2^32", &h, none());
}

// consumptionUncountable: percent instructions are passed over; the rest must be a count.
static void uncountable_rule(void)
{
    const int64_t max = INT64_MAX;
    Inst percentThenNeg[] = { tiPercent(0, 128), ti(0, -1) }, percentNeg[] = { tiPercent(0, -5) }, percentNegIndex[] = { tiPercent(-1, 5) },
         negIndex[] = { ti(-1, 0) }, neg[] = { ti(0, -1) }, rangeOver[] = { tiRange(1, max / 2 + 1) }, rangeFits[] = { tiRange(1, max / 2) },
         sumOver[] = { ti(0, max), ti(1, 1) }, sumFits[] = { ti(0, max - 1), ti(1, 1) }, percentBetween[] = { ti(0, max), tiPercent(1, 50), ti(2, 1) };
    Header h;

    h = transfer(NULL, 0);             uncountable("no instruction", &h, 0);
    h = transfer(percentThenNeg, 2);   uncountable("a percent instruction, then an amount of -1", &h, 1);
    h = transfer(percentNeg, 1);       uncountable("a percent instruction of -5", &h, 0);
    h = transfer(percentNegIndex, 1);  uncountable("a percent instruction to output -1", &h, 0);
    h = transfer(negIndex, 1);         uncountable("an instruction to output -1", &h, 1);
    h = transfer(neg, 1);              uncountable("an amount of -1", &h, 1);
    h = burn(neg, 1);                  uncountable("a burn's amount of -1", &h, 1);
    h = issuance(5, neg, 1);           uncountable("an issuance's amount of -1", &h, 1);
    h = hdr(M_UNCLASSIFIABLE, 0x15, none(), neg, 1);
                                       uncountable("an unclassifiable carrier's amount of -1", &h, 0);
    h = transfer(rangeOver, 1);        uncountable("a range of two outputs of Long.MAX_VALUE / 2 + 1", &h, 1);
    h = transfer(rangeFits, 1);        uncountable("a range of two outputs of Long.MAX_VALUE / 2", &h, 0);
    h = transfer(sumOver, 2);          uncountable("Long.MAX_VALUE and 1", &h, 1);
    h = transfer(sumFits, 2);          uncountable("Long.MAX_VALUE - 1 and 1", &h, 0);
    h = transfer(percentBetween, 3);   uncountable("Long.MAX_VALUE, a percent instruction, and 1", &h, 1);
}

// issuanceMayCreditLastOutput three ways. The model's answer is the allocator's, which reads the
// header's aggregation; one supply never draws across two entries, so the answer is the same for each.
static void issues(const char *what, const Header *h, int32_t outputCount, int want)
{
    Header copy = *h;
    int got = cIssuanceMayCreditLastOutput(h, outputCount), aggregation, models = 0;

    for (aggregation = AGG_AGGREGATABLE; aggregation <= AGG_NONE; aggregation++) {
        copy.aggregation = aggregation;
        if (mIssuanceMayCreditLastOutput(&copy, outputCount) == want) models++;
    }

    ck(got == want && models == 4, "%s, %d outputs: the last output %s (header %d, model agrees for %d of 4 aggregations)", what,
       (int)outputCount, want ? "may be credited" : "is not credited", got, models);
}

#define ISSUE(total, list) issuance(total, list, N(list))

// What decides an issuance before its instructions are read.
static void issuance_not_decided(void)
{
    Inst all[] = { ti(0, 10) }, percent[] = { tiPercent(0, 10) }, percentLater[] = { ti(0, 10), tiPercent(0, 0) };
    Header h = ISSUE(10, all);

    issues("the whole supply to output 0", &h, 3, 0);
    h.opcode = 3;  issues("the same with rules (opcode 3)", &h, 3, 1);
    h.opcode = 4;  issues("the same with rules (opcode 4)", &h, 3, 1);
    h.opcode = 2;  issues("the same with opcode 2", &h, 3, 0);
    h.opcode = 5;  issues("the same with opcode 5", &h, 3, 0);
    h.opcode = 1; h.version = 2;  issues("the same in version 2", &h, 3, 1);
    h.version = 4;  issues("the same in version 4", &h, 3, 0);
    h.version = 3;
    issues("the same with no outputs", &h, 0, 1);
    issues("the same with -1 outputs", &h, -1, 1);
    issues("the same with the lowest output count", &h, INT32_MIN, 1);
    issues("the same with one output", &h, 1, 1);
    h.hasQty = 0;  issues("the same with no total", &h, 3, 1);

    h = ISSUE(10, percent);         issues("a percent instruction", &h, 3, 1);
    h = ISSUE(10, percentLater);    issues("a percent instruction after the supply is used", &h, 3, 1);
    h = ISSUE(0, percent);          issues("a percent instruction in an issuance of zero", &h, 3, 0);
}

// The count an issuance may create: 1 to 2^54 - 1.
static void issuance_total_range(void)
{
    const int64_t most = 18014398509481983LL;   // 2^54 - 1
    Inst allMost[] = { ti(0, most) };
    Header h;

    h = issuance(0, NULL, 0);          issues("an issuance of zero", &h, 3, 0);
    h = issuance(-1, NULL, 0);         issues("an issuance of -1", &h, 3, 0);
    h = issuance(INT64_MIN, NULL, 0);  issues("an issuance of the lowest count", &h, 3, 0);
    h = issuance(1, NULL, 0);          issues("an issuance of 1 with no instruction", &h, 3, 1);
    h = issuance(most, NULL, 0);       issues("an issuance of 2^54 - 1 with no instruction", &h, 3, 1);
    h = issuance(most + 1, NULL, 0);   issues("an issuance of 2^54 with no instruction", &h, 3, 0);
    h = issuance(INT64_MAX, NULL, 0);  issues("an issuance of Long.MAX_VALUE with no instruction", &h, 3, 0);
    h = ISSUE(most, allMost);          issues("an issuance of 2^54 - 1, all to output 0", &h, 3, 0);
    h = issuance(0, NULL, 0); h.hasQty = 0; h.qty = 0;
                                       issues("an issuance with no total", &h, 3, 1);
    h = issuance(0, NULL, 0);          issues("an issuance of zero with no outputs", &h, 0, 1);
    h.opcode = 3;                      issues("an issuance of zero with rules", &h, 3, 1);
}

// The instructions of an issuance: the supply, the void, the remainder.
static void issuance_instructions(void)
{
    Inst part[] = { ti(0, 4) }, toLast[] = { ti(2, 10) }, middle[] = { ti(1, 10) }, missing[] = { ti(3, 10) }, over[] = { ti(0, 11) },
         split[] = { ti(0, 6), ti(1, 4) }, splitOver[] = { ti(0, 6), ti(1, 5) }, splitLast[] = { ti(0, 6), ti(2, 4) },
         after[] = { ti(0, 10), ti(1, 0) }, zeroFirst[] = { ti(1, 0), ti(0, 10) }, zeroMissing[] = { ti(9, 0), ti(0, 10) },
         zeroLast[] = { ti(2, 0), ti(0, 10) }, negIndex[] = { ti(-1, 0), ti(0, 10) }, negAmount[] = { ti(0, -1), ti(0, 10) },
         range[] = { tiRange(1, 5) }, rangeLast[] = { tiRange(2, 3) }, rangePast[] = { tiRange(3, 3) }, rangeOver[] = { tiRange(1, 6) },
         rangeHuge[] = { tiRange(1, INT64_MAX) }, wraps[] = { tiRange(512, 35958565450000000LL) }, marker[] = { ti(31, 10) };
    Header h;

    h = ISSUE(10, part);         issues("4 of 10 to output 0", &h, 3, 1);
    h = ISSUE(10, toLast);       issues("10 of 10 to output 2", &h, 3, 1);
                                 issues("10 of 10 to output 2", &h, 4, 0);
    h = ISSUE(10, middle);       issues("10 of 10 to output 1", &h, 3, 0);
                                 issues("10 of 10 to output 1", &h, 2, 1);
    h = ISSUE(10, missing);      issues("10 of 10 to output 3", &h, 3, 1);
                                 issues("10 of 10 to output 3", &h, 5, 0);
    h = ISSUE(10, over);         issues("11 of 10 to output 0", &h, 3, 1);
    h = ISSUE(10, split);        issues("6 and 4 of 10", &h, 3, 0);
    h = ISSUE(10, splitOver);    issues("6 and 5 of 10", &h, 3, 1);
    h = ISSUE(10, splitLast);    issues("6 to output 0 and 4 to output 2 of 10", &h, 3, 1);
    h = ISSUE(10, after);        issues("an instruction of zero after the supply is used", &h, 3, 1);
    h = ISSUE(10, zeroFirst);    issues("an instruction of zero before the supply is used", &h, 3, 0);
    h = ISSUE(10, zeroMissing);  issues("an instruction of zero to an output the transaction lacks", &h, 3, 0);
    h = ISSUE(10, zeroLast);     issues("an instruction of zero to the last output", &h, 3, 0);
    h = ISSUE(10, negIndex);     issues("an instruction to output -1", &h, 3, 1);
    h = ISSUE(10, negAmount);    issues("an amount of -1", &h, 3, 1);
    h = ISSUE(10, range);        issues("a range of two outputs of 5 of 10", &h, 3, 0);
    h = ISSUE(9, rangeLast);     issues("a range of three outputs of 3 of 9", &h, 3, 1);
                                 issues("a range of three outputs of 3 of 9", &h, 4, 0);
    h = ISSUE(12, rangePast);    issues("a range of four outputs of 3 of 12", &h, 3, 1);
    h = ISSUE(10, rangeOver);    issues("a range of two outputs of 6 of 10", &h, 3, 1);
    h = ISSUE(10, rangeHuge);    issues("a range of two outputs of Long.MAX_VALUE", &h, 3, 1);
    h = ISSUE(2140448384LL, wraps);
                                 issues("a range of 513 outputs of 35958565450000000, of 2140448384 issued", &h, 600, 1);
    h = ISSUE(10, marker);       issues("10 of 10 to output 31", &h, 40, 0);
                                 issues("10 of 10 to output 31", &h, 3, 1);
}

// `skip` in an issuance: the one supply is passed over, so any instruction after it is void.
static void issuance_skip(void)
{
    Inst skipAll[] = { tiSkip(0, 10) }, skipPart[] = { tiSkip(0, 4) }, skipThen[] = { tiSkip(0, 4), ti(1, 0) },
         skipThenDraw[] = { tiSkip(0, 4), ti(1, 6) }, skipAllThen[] = { tiSkip(0, 10), ti(0, 0) }, skipZero[] = { tiSkip(0, 0) },
         skipZeroThen[] = { tiSkip(0, 0), ti(0, 10) }, plain[] = { ti(0, 4), ti(1, 6) };
    Header h;

    h = ISSUE(10, skipAll);       issues("10 of 10 with skip set", &h, 3, 0);
    h = ISSUE(10, skipPart);      issues("4 of 10 with skip set", &h, 3, 1);
    h = ISSUE(10, plain);         issues("4 and 6 of 10", &h, 3, 0);
    h = ISSUE(10, skipThenDraw);  issues("4 with skip set, then 6", &h, 3, 1);
    h = ISSUE(10, skipThen);      issues("4 with skip set, then zero", &h, 3, 1);
    h = ISSUE(10, skipAllThen);   issues("10 with skip set, then zero", &h, 3, 1);
    h = ISSUE(10, skipZero);      issues("zero with skip set", &h, 3, 1);
    h = ISSUE(10, skipZeroThen);  issues("zero with skip set, then 10", &h, 3, 1);
}

// targetsOutput: which output an instruction names.
static void targets_named(void)
{
    Inst marker[] = { tiBurn(31, 5) }, plain31[] = { ti(31, 5) }, range31[] = { tiRange(31, 5) }, range2[] = { tiRange(2, 1) },
         one[] = { ti(0, 1) }, percent31[] = { tiPercent(31, 50) }, second[] = { ti(0, 1), ti(2, 1) }, toLast[] = { ti(3, 100) };
    Header h;

    h = burn(marker, 1);       targets("a burn's destroy marker", &h, 31, someInt(0), some(5), 40, 0);
    h = burn(percent31, 1);    targets("a burn's percent instruction to output 31", &h, 31, someInt(0), some(5), 40, 0);
    h = transfer(plain31, 1);  targets("a transfer's instruction to output 31", &h, 31, someInt(0), some(5), 40, 1);
    h = transfer(percent31, 1); targets("a transfer's percent instruction to output 31", &h, 31, someInt(0), some(5), 40, 1);
    h = burn(range31, 1);      targets("a burn's range to output 31", &h, 31, someInt(0), some(160), 40, 1);
                               targets("a burn's range to output 31", &h, 0, someInt(0), some(160), 40, 1);
                               targets("a burn's range to output 31", &h, 32, someInt(0), some(160), 40, 0);
    h = transfer(range2, 1);   targets("a range to output 2", &h, 2, someInt(0), some(3), 5, 1);
                               targets("a range to output 2", &h, 3, someInt(0), some(3), 5, 0);
                               targets("a range to output 2, asked about output -1", &h, -1, someInt(0), some(3), 5, 1);
    h = transfer(one, 1);      targets("an instruction to output 0, asked about output -1", &h, -1, someInt(0), some(1), 5, 0);
                               targets("an instruction to output 0", &h, 1, someInt(0), some(1), 5, 0);
    h = transfer(second, 2);   targets("a second instruction to output 2", &h, 2, someInt(0), some(2), 5, 1);
                               targets("a second instruction to output 2", &h, 1, someInt(0), some(2), 5, 0);
    h = transfer(toLast, 1);   targets("an instruction to the last output that assigns everything", &h, 3, someInt(0), some(100), 4, 1);
}

// targetsOutput: an issuance's first non-OP_RETURN output, and its last output.
static void targets_issuance(void)
{
    Inst all[] = { ti(0, 100) }, part[] = { ti(0, 4) }, send[] = { ti(0, 10) };
    OptInt unset = { 0, 1 };   // null, with the value an output index
    Header h;

    h = ISSUE(100, all);
    targets("an issuance's first non-OP_RETURN output 1", &h, 1, someInt(1), none(), 3, 1);
    targets("an issuance with no first non-OP_RETURN output", &h, 1, noInt(), none(), 3, 0);
    targets("an issuance with no first non-OP_RETURN output", &h, 1, unset, none(), 3, 0);
    targets("an issuance whose first non-OP_RETURN output is the last", &h, 2, someInt(2), some(0), 3, 1);
    targets("an issuance whose first non-OP_RETURN output is -1", &h, -1, someInt(-1), some(0), 3, 1);
    h = transfer(send, 1);
    targets("a transfer's first non-OP_RETURN output 1", &h, 1, someInt(1), some(10), 4, 0);
    h = burn(send, 1);
    targets("a burn's first non-OP_RETURN output 1", &h, 1, someInt(1), some(10), 4, 0);

    h = ISSUE(10, part);
    targets("the last output of an issuance of 10 that assigns 4", &h, 2, someInt(0), some(0), 3, 1);
    targets("the last output of an issuance of 10 that assigns 4", &h, 2, someInt(0), some(4), 3, 1);
    targets("the middle output of an issuance of 10 that assigns 4", &h, 1, someInt(0), none(), 3, 0);
    h = ISSUE(4, part);
    targets("the last output of an issuance of 4 that assigns 4", &h, 2, someInt(0), some(100), 3, 0);
    targets("the last output of an issuance of 4 that assigns 4", &h, 2, someInt(0), none(), 3, 0);
    h.opcode = 3;
    targets("the last output of the same issuance with rules", &h, 2, someInt(0), some(100), 3, 1);
    targets("the middle output of the same issuance with rules", &h, 1, someInt(0), some(100), 3, 0);
}

// targetsOutput: the last output of a transfer or burn.
static void targets_last_output(void)
{
    Inst five[] = { ti(0, 5) }, zero[] = { ti(0, 0) }, marker[] = { tiBurn(31, 25) }, missing[] = { ti(5, 9) }, one[] = { ti(0, 1) },
         percentZero[] = { tiPercent(0, 0) };
    Header h;

    h = transfer(five, 1);
    targets("5 assigned", &h, 2, someInt(0), some(5), 3, 0);
    targets("5 assigned", &h, 2, someInt(0), some(6), 3, 1);
    targets("5 assigned", &h, 2, someInt(0), some(4), 3, 1);
    targets("5 assigned", &h, 2, someInt(0), some(0), 3, 0);
    targets("5 assigned", &h, 2, someInt(0), some(-1), 3, 1);
    targets("5 assigned", &h, 2, someInt(0), some(INT64_MIN), 3, 1);
    targets("5 assigned", &h, 2, someInt(0), some(INT64_MAX), 3, 1);
    targets("5 assigned", &h, 2, someInt(0), none(), 3, 1);
    targets("5 assigned, the output before the last", &h, 1, someInt(0), some(6), 3, 0);
    targets("5 assigned, the output after the last", &h, 3, someInt(0), some(6), 3, 0);

    h = transfer(NULL, 0);
    targets("no instruction", &h, 2, someInt(0), some(1), 3, 1);
    targets("no instruction", &h, 2, someInt(0), some(0), 3, 0);
    targets("no instruction, the lowest output count", &h, INT32_MAX, someInt(0), some(5), INT32_MIN, 1);
    targets("no instruction, the lowest output count", &h, INT32_MAX, someInt(0), some(0), INT32_MIN, 0);
    targets("no instruction, five outputs", &h, INT32_MAX, someInt(0), some(5), 5, 0);
    targets("no instruction, no outputs", &h, -1, someInt(0), some(5), 0, 1);
    targets("no instruction, no outputs", &h, 0, someInt(0), some(5), 0, 0);

    h = transfer(zero, 1);
    targets("an instruction of zero units", &h, 2, someInt(0), some(0), 3, 0);
    targets("an instruction of zero units", &h, 2, someInt(0), none(), 3, 1);
    targets("an instruction of zero units", &h, 2, someInt(0), some(-1), 3, 1);

    h = transfer(missing, 1);
    targets("9 to an output the transaction lacks", &h, 2, someInt(0), some(1), 3, 1);
    targets("9 to an output the transaction lacks", &h, 2, someInt(0), some(0), 3, 0);
    targets("9 to an output the transaction lacks", &h, 1, someInt(0), some(9), 3, 0);

    h = transfer(percentZero, 1);
    targets("a percent instruction of zero", &h, 2, someInt(0), some(0), 3, 1);

    h = transfer(one, 1);
    targets("1 assigned, no outputs", &h, -1, someInt(0), none(), 0, 1);
    targets("1 assigned, no outputs", &h, -1, someInt(0), some(5), 0, 1);
    targets("1 assigned, no outputs", &h, -1, someInt(0), some(0), 0, 0);

    h = burn(marker, 1);
    targets("a burn of 25", &h, 2, someInt(0), some(25), 3, 0);
    targets("a burn of 25", &h, 2, someInt(0), some(26), 3, 1);
    targets("a burn of 25", &h, 2, someInt(0), some(0), 3, 0);
    targets("a burn of 25", &h, 2, someInt(0), none(), 3, 1);

    h = hdr(M_UNCLASSIFIABLE, 0x15, none(), one, 1);
    targets("an unclassifiable carrier", &h, -5, noInt(), some(0), 3, 1);
    targets("an unclassifiable carrier", &h, 7, noInt(), none(), 3, 1);
}

// What the Kotlin has no form of, and the header's own argument forms.
static void arguments(void)
{
    static const BRAssetOperation OTHER[] = { DA_UNDEFINED, (BRAssetOperation)4, (BRAssetOperation)99, (BRAssetOperation)-1 };
    BRAssetTransferInstruction marker[1], neg[1], all[1], missing[1], truthy[2];
    int64_t v;
    size_t i;

    memset(marker, 0, sizeof(marker)); marker[0].outputIndex = 31; marker[0].amount = 5;
    memset(neg, 0, sizeof(neg)); neg[0].amount = -1;
    memset(all, 0, sizeof(all)); all[0].amount = 10;
    memset(missing, 0, sizeof(missing)); missing[0].outputIndex = 5; missing[0].amount = 9;

    ck(DA_UNDEFINED == 0 && DA_ISSUANCE == 1 && DA_TRANSFER == 2 && DA_BURN == 3, "the operations are 0 undefined, 1 issuance, 2 transfer, 3 burn");
    ck(asset_quantity_targets_kat_tu2() == 1, "a second translation unit that includes the header twice gives the same answers");

    // An instruction count with no array: instructions that cannot be read.
    ck(BRAssetDestroys(DA_BURN, NULL) == 0, "no instruction destroys nothing");
    ck(BRAssetMayVoid(DA_TRANSFER, 3, NULL, 1) == 1 && BRAssetMayVoid(DA_TRANSFER, 3, NULL, 0) == 0,
       "an instruction with no array may be voided; none with no array cannot");
    v = -77;
    ck(BRAssetAssignedUnits(DA_TRANSFER, NULL, 1, &v) == 0 && v == 0, "an instruction with no array assigns no count (%lld left behind)", (long long)v);
    v = -77;
    ck(BRAssetAssignedUnits(DA_TRANSFER, NULL, 0, &v) == 1 && v == 0, "no instruction and no array assign 0 (%lld)", (long long)v);
    ck(BRAssetAssignedUnits(DA_TRANSFER, all, 1, NULL) == 0, "assigned units with nowhere to write them is no answer");
    v = -77;
    ck(BRAssetAssignedUnits(DA_UNDEFINED, all, 1, &v) == 0 && v == 0, "an unclassified carrier leaves 0 behind (%lld)", (long long)v);
    v = -77;
    ck(BRAssetAssignedUnits(DA_TRANSFER, neg, 1, &v) == 0 && v == 0, "a negative amount leaves 0 behind (%lld)", (long long)v);
    ck(BRAssetConsumptionUncountable(DA_TRANSFER, NULL, 1) == 1 && BRAssetConsumptionUncountable(DA_TRANSFER, NULL, 0) == 0 &&
       BRAssetConsumptionUncountable(DA_UNDEFINED, NULL, 1) == 0, "an instruction with no array is not a count, unless the carrier is unclassified");
    ck(BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 10, 3, NULL, 1) == 1 && BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 0, 3, NULL, 1) == 0 &&
       BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 10, 3, NULL, 0) == 1,
       "an issuance of 10 whose one instruction has no array may credit the last output; an issuance of zero does not");
    ck(BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 1, 1, 0, 1, 0, 3, NULL, 1) == 1 &&
       BRAssetTargetsOutput(DA_ISSUANCE, 3, 0, 1, 10, 1, 1, 0, 1, 0, 3, NULL, 1) == 1 &&
       BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 1, 1, 0, 1, 0, 3, NULL, 0) == 0,
       "an instruction with no array targets every output; none target the middle one");

    // Every operation value other than the three classified ones is UNCLASSIFIABLE.
    for (i = 0; i < N(OTHER); i++) {
        v = -77;
        ck(BRAssetTargetsOutput(OTHER[i], 3, 0, 0, 0, 1, 1, 0, 1, 0, 3, all, 1) == 1 && BRAssetAssignedUnits(OTHER[i], all, 1, &v) == 0 && v == 0 &&
           BRAssetConsumptionUncountable(OTHER[i], neg, 1) == 0 && BRAssetDestroys(OTHER[i], marker) == 0 &&
           BRAssetMayVoid(OTHER[i], 3, marker, 1) == 1,
           "operation %d: every output targeted, no assigned count, countable, nothing destroyed", (int)OTHER[i]);
    }

    // A flag is any value other than 0.
    ck(BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, 2, 0, 3, missing, 1) == 0 &&
       BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, -1, 0, 3, missing, 1) == 0 &&
       BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 2, 1, 0, 0, 0, 3, missing, 1) == 1,
       "hasInputUnits 2 and -1 are known units; 0 is unknown, whatever the value beside it");
    ck(BRAssetTargetsOutput(DA_ISSUANCE, 3, 0, 2, 10, 1, -1, 1, 0, 0, 3, all, 1) == 1 &&
       BRAssetTargetsOutput(DA_ISSUANCE, 3, 0, 2, 10, 1, 0, 1, 0, 0, 3, all, 1) == 0,
       "hasFirstNonOpReturnVout -1 is an output; 0 is none, whatever the value beside it");
    ck(BRAssetTargetsOutput(DA_ISSUANCE, 3, 0, 2, 10, 2, 1, 0, 0, 0, 3, all, 1) == 0 &&
       BRAssetTargetsOutput(DA_ISSUANCE, 3, 0, 0, 10, 2, 1, 0, 0, 0, 3, all, 1) == 1 &&
       BRAssetTargetsOutput(DA_ISSUANCE, 3, 2, 2, 10, 2, 1, 0, 0, 0, 3, all, 1) == 1 &&
       BRAssetTargetsOutput(DA_ISSUANCE, 3, -1, 2, 10, 2, 1, 0, 0, 0, 3, all, 1) == 1,
       "hasTotalQuantity 2 is a total and 0 is none; hasRules 2 and -1 are rules");

    memset(truthy, 0, sizeof(truthy));
    truthy[0].range = 2; truthy[0].outputIndex = 2; truthy[0].amount = 3;      // a range of three outputs of 3
    truthy[1].percent = -1; truthy[1].outputIndex = 4; truthy[1].amount = 50;  // a percent instruction
    v = -77;
    ck(BRAssetTargetsOutput(DA_TRANSFER, 3, 0, 0, 0, 1, 1, 0, 1, 9, 6, truthy, 1) == 1 && BRAssetAssignedUnits(DA_TRANSFER, truthy, 1, &v) == 1 && v == 9 &&
       BRAssetDestroys(DA_BURN, truthy) == 0, "range 2 is a range: it names output 1 and draws 9 (%lld)", (long long)v);
    v = -77;
    ck(BRAssetAssignedUnits(DA_TRANSFER, truthy, 2, &v) == 0 && v == 0 && BRAssetConsumptionUncountable(DA_TRANSFER, truthy, 2) == 0 &&
       BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 9, 6, truthy, 2) == 1, "percent -1 is a percent instruction");
    truthy[0].skip = 4; truthy[1].percent = 0; truthy[1].amount = 0;
    ck(BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 20, 6, truthy, 1) == 1 && BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 20, 6, truthy, 2) == 1 &&
       BRAssetIssuanceMayCreditLastOutput(3, 0, 1, 9, 6, truthy, 1) == 0, "skip 4 is a skip: an instruction after it voids the issuance");
}

// The smallest amounts and counts each rule reads.
static void edges(void)
{
    Inst zero[] = { ti(0, 0) }, zeroRange[] = { tiRange(2, 0) }, all[] = { ti(0, 10) }, tenAndOne[] = { ti(0, 10), ti(1, 1) },
         markerThen[] = { tiBurn(31, 5), ti(1, 5) };
    Header h;

    h = transfer(zero, 1);           uncountable("an instruction of zero units", &h, 0);
    h = transfer(zeroRange, 1);      uncountable("a range of zero units", &h, 0);
    h = ISSUE(10, all);              issues("10 of 10 to output 0, one output", &h, 1, 1);
    h = issuance(0, all, 1);         issues("an issuance of zero, one output", &h, 1, 0);
    h = issuance(0, all, 1);         issues("an issuance of zero, two outputs", &h, 2, 0);
    h = ISSUE(11, tenAndOne);        issues("10 to output 0 and 1 to output 1 of 11", &h, 3, 0);
    h = ISSUE(12, tenAndOne);        issues("10 to output 0 and 1 to output 1 of 12", &h, 3, 1);
    h = burn(markerThen, 2);
    targets("a burn's output 1, named after the destroy marker", &h, 1, someInt(0), some(10), 3, 1);
    targets("a burn's output 0, which nothing names", &h, 0, someInt(0), some(10), 3, 0);
}

// ---- the sweeps: the header against the model over fixed pseudo-random inputs -----------------

static int64_t rndAmount(void)
{
    static const int64_t EDGE[] = { 0, 1, -1, INT64_MAX, INT64_MAX / 2, INT64_MAX / 2 + 1, 18014398509481983LL, 18014398509481984LL,
                                    35958565450000000LL, INT64_MIN, 4294967296LL, 9007199254740992LL };

    if (below(7) == 0) return EDGE[below(N(EDGE))];
    return (int64_t)below(8);
}

static int32_t rndOutputCount(void)
{
    static const int32_t EDGE[] = { 0, -1, INT32_MIN, INT32_MAX, 32, 33, 40 };

    if (below(10) == 0) return EDGE[below(N(EDGE))];
    return 1 + (int32_t)below(6);
}

static int32_t rndOut(int32_t outputCount)
{
    static const int32_t EDGE[] = { -1, 30, 32, 512, 8191, INT32_MAX, INT32_MIN };

    if (below(6) == 0) return 31;
    if (below(9) == 0) return EDGE[below(N(EDGE))];
    return (int32_t)below((uint64_t)((outputCount > 0 && outputCount < 64) ? outputCount : 3) + 2);
}

// A header of up to four instructions. `operation` < 0 draws one, UNCLASSIFIABLE among them.
static Header rndHeader(int operation, int32_t outputCount)
{
    Header h;
    size_t i, prefix;
    int64_t sum = 0, drawn;
    int countable = 1;

    memset(&h, 0, sizeof(h));
    h.operation = (operation >= 0) ? operation : (int)below(4);
    h.version = (below(9) == 0) ? (int)below(5) : 3;
    h.opcode = (h.operation == M_ISSUANCE) ? ((below(9) == 0) ? 3 + (int)below(2) : (int[]){ 1, 2, 5 }[below(3)]) :
               (h.operation == M_BURN) ? 0x25 : 0x15;
    h.aggregation = (int)below(4);
    h.n = (size_t)below(5);
    prefix = h.n ? (size_t)below(h.n + 1) : 0;

    for (i = 0; i < h.n; i++) {
        Inst *inst = &h.insts[i];

        inst->out = rndOut(outputCount);
        inst->amount = rndAmount();
        inst->range = below(4) == 0;
        inst->percent = below(14) == 0;
        inst->skip = below(8) == 0;
        inst->burn = below(4) == 0;

        // what the first `prefix` instructions draw, where that is a count: a supply some sweeps issue exactly
        if (i < prefix && countable) {
            if (inst->percent || inst->amount < 0 || inst->out < 0) countable = 0;
            else if (inst->range) countable = ! __builtin_mul_overflow((int64_t)inst->out + 1, inst->amount, &drawn);
            else drawn = inst->amount;
            if (countable) countable = ! __builtin_add_overflow(sum, drawn, &sum);
        }
    }

    if (h.operation == M_ISSUANCE) {
        h.hasQty = below(12) != 0;
        h.qty = (countable && below(2) == 0) ? sum : (below(6) == 0) ? rndAmount() : 1 + (int64_t)below(20);
    }

    return h;
}

static const char *show(const Header *h, char *buf, size_t cap)
{
    size_t i, used;

    snprintf(buf, cap, "%s v%d opcode %d total %s%lld [", OP_NAME[h->operation], h->version, h->opcode, h->hasQty ? "" : "null/",
             (long long)h->qty);

    for (i = 0; i < h->n; i++) {
        used = strlen(buf);
        snprintf(buf + used, cap - used, "%s%s%s%s%d:%lld", i ? ", " : "", h->insts[i].skip ? "skip " : "", h->insts[i].range ? "range " : "",
                 h->insts[i].percent ? "percent " : "", (int)h->insts[i].out, (long long)h->insts[i].amount);
    }

    used = strlen(buf);
    snprintf(buf + used, cap - used, "]");
    return buf;
}

#define SWEEP 150000

// destroys and mayVoid.
static void sweep_destroys_may_void(void)
{
    char buf[400];
    long draw, destroyed = 0, placed = 0, voidable = 0, firm = 0, differ = 0;

    RNG = 0x243f6a8885a308d3ULL;

    for (draw = 0; draw < SWEEP; draw++) {
        int32_t outputCount = rndOutputCount();
        Header h = rndHeader(-1, outputCount);
        size_t i;
        int got = cMayVoid(&h, outputCount), model = mMayVoid(&h, outputCount);

        if (got != model) { if (differ++ < 4) ck(0, "%s, %d outputs: mayVoid is %d, the header says %d", show(&h, buf, sizeof(buf)), (int)outputCount, model, got); }
        else if (model) voidable++;
        else firm++;

        for (i = 0; i < h.n; i++) {
            got = cDestroys(&h, &h.insts[i]); model = mDestroys(&h, &h.insts[i]);
            if (got != model) { if (differ++ < 4) ck(0, "%s, instruction %zu: destroys is %d, the header says %d", show(&h, buf, sizeof(buf)), i, model, got); }
            else if (model) destroyed++;
            else placed++;
        }
    }

    ck(differ == 0, "%d headers: mayVoid and destroys as the model has them (%ld differ)", SWEEP, differ);
    ck(voidable > 1000 && firm > 1000 && destroyed > 1000 && placed > 1000,
       "the stream reaches both answers of each: %ld may be voided, %ld cannot; %ld instructions destroy, %ld place", voidable, firm, destroyed, placed);
}

// assignedUnits and consumptionUncountable.
static void sweep_assigned(void)
{
    char buf[400], b1[32], b2[32];
    long draw, counted = 0, unknown = 0, uncountable_ = 0, countable = 0, differ = 0;

    RNG = 0x13198a2e03707344ULL;

    for (draw = 0; draw < SWEEP; draw++) {
        Header h = rndHeader(-1, rndOutputCount());
        OptLong got = cAssignedUnits(&h), model = mAssignedUnits(&h);
        int gotU = cConsumptionUncountable(&h), modelU = mConsumptionUncountable(&h);

        if (! sameOpt(got, model) || (! got.has && got.v != 0)) {
            if (differ++ < 4) ck(0, "%s: assignedUnits is %s, the header says %s", show(&h, buf, sizeof(buf)), showLong(model, b1, sizeof(b1)),
                                 showLong(got, b2, sizeof(b2)));
        }
        else if (model.has) counted++;
        else unknown++;

        if (gotU != modelU) { if (differ++ < 4) ck(0, "%s: consumptionUncountable is %d, the header says %d", show(&h, buf, sizeof(buf)), modelU, gotU); }
        else if (modelU) uncountable_++;
        else countable++;
    }

    ck(differ == 0, "%d headers: assignedUnits and consumptionUncountable as the model has them (%ld differ)", SWEEP, differ);
    ck(counted > 1000 && unknown > 1000 && uncountable_ > 1000 && countable > 1000,
       "the stream reaches both answers of each: %ld counts, %ld unknown; %ld uncountable, %ld countable", counted, unknown, uncountable_, countable);
}

// issuanceMayCreditLastOutput: the header's one supply against the model's allocator.
static void sweep_issuance(void)
{
    char buf[400];
    long draw, credited = 0, clear = 0, differ = 0;

    RNG = 0xa4093822299f31d0ULL;

    for (draw = 0; draw < SWEEP; draw++) {
        int32_t outputCount = rndOutputCount();
        Header h = rndHeader(M_ISSUANCE, outputCount);
        int got = cIssuanceMayCreditLastOutput(&h, outputCount), model = mIssuanceMayCreditLastOutput(&h, outputCount);

        if (got != model) { if (differ++ < 4) ck(0, "%s, %d outputs: the allocator says %d, the header says %d", show(&h, buf, sizeof(buf)), (int)outputCount, model, got); }
        else if (model) credited++;
        else clear++;
    }

    ck(differ == 0, "%d issuances: the last output as the model's allocator has it (%ld differ)", SWEEP, differ);
    ck(credited > 1000 && clear > 1000, "the stream reaches both answers: %ld may credit the last output, %ld do not", credited, clear);
}

// targetsOutput.
static void sweep_targets(void)
{
    char buf[400], b1[32];
    long draw, held_[4] = { 0, 0, 0, 0 }, open[4] = { 0, 0, 0, 0 }, differ = 0;

    RNG = 0x082efa98ec4e6c89ULL;

    for (draw = 0; draw < 2 * SWEEP; draw++) {
        int32_t outputCount = rndOutputCount(), vout;
        Header h = rndHeader(-1, outputCount);
        OptInt first = (below(5) == 0) ? noInt() : someInt((int32_t)below(3));
        OptLong units, sum = mAssignedUnits(&h);
        int got, model;

        switch (below(8)) {
            case 0: vout = -1; break;
            case 1: vout = 31; break;
            case 2: case 3: case 4: vout = mImplicitChangeVout(outputCount); break;
            case 5: vout = h.n ? h.insts[below(h.n)].out : 0; break;
            default: vout = (int32_t)below(8); break;
        }

        switch (below(8)) {
            case 0: units = none(); break;
            case 1: units = some(0); break;
            case 2: units = sum.has ? sum : some(1); break;                     // exactly what the instructions assign
            case 3: units = (sum.has && sum.v < INT64_MAX) ? some(sum.v + 1) : some(2); break;
            case 4: units = some(rndAmount()); break;
            default: units = some((int64_t)below(20)); break;
        }

        got = cTargetsOutput(&h, vout, first, units, outputCount);
        model = mTargetsOutput(&h, vout, first, units, outputCount);

        if (got != model) {
            if (differ++ < 4) ck(0, "%s, output %d of %d, units in %s: targetsOutput is %d, the header says %d", show(&h, buf, sizeof(buf)), (int)vout,
                                 (int)outputCount, showLong(units, b1, sizeof(b1)), model, got);
        }
        else if (model) held_[h.operation]++;
        else open[h.operation]++;
    }

    ck(differ == 0, "%d questions: targetsOutput as the model has it (%ld differ)", 2 * SWEEP, differ);
    ck(held_[M_ISSUANCE] > 1000 && open[M_ISSUANCE] > 1000 && held_[M_TRANSFER] > 1000 && open[M_TRANSFER] > 1000 && held_[M_BURN] > 1000 &&
       open[M_BURN] > 1000 && held_[M_UNCLASSIFIABLE] > 1000 && open[M_UNCLASSIFIABLE] == 0,
       "the stream reaches both answers for each operation: issuance %ld/%ld, transfer %ld/%ld, burn %ld/%ld, unclassifiable %ld/%ld",
       held_[M_ISSUANCE], open[M_ISSUANCE], held_[M_TRANSFER], open[M_TRANSFER], held_[M_BURN], open[M_BURN], held_[M_UNCLASSIFIABLE],
       open[M_UNCLASSIFIABLE]);
}

// ---- main -----------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "hold_issuance_remainder", hold_issuance_remainder }, { "hold_issuance_no_instruction", hold_issuance_no_instruction },
    { "hold_issuance_missing_output", hold_issuance_missing_output }, { "hold_issuance_over_assigned", hold_issuance_over_assigned },
    { "hold_issuance_not_read_exactly", hold_issuance_not_read_exactly }, { "hold_issuance_whole_supply", hold_issuance_whole_supply },
    { "hold_transfer_missing_output", hold_transfer_missing_output },
    { "hold_zero_amount_after_inputs_used", hold_zero_amount_after_inputs_used },
    { "hold_range_past_outputs", hold_range_past_outputs }, { "hold_void_transfer_no_units", hold_void_transfer_no_units },
    { "hold_burn_marker", hold_burn_marker }, { "hold_every_credited_output", hold_every_credited_output },
    { "targeting_named_outputs", targeting_named_outputs },
    { "targeting_last_output_beside_percent", targeting_last_output_beside_percent },
    { "targeting_issuance_change", targeting_issuance_change }, { "targeting_unnamed_middle_output", targeting_unnamed_middle_output },
    { "targeting_remainder", targeting_remainder }, { "targeting_settled_totals", targeting_settled_totals },
    { "never_wraps_unclassifiable", never_wraps_unclassifiable }, { "never_wraps_range_consumption", never_wraps_range_consumption },
    { "never_wraps_remainder_bounded", never_wraps_remainder_bounded }, { "never_wraps_negative_amount", never_wraps_negative_amount },
    { "never_wraps_sum", never_wraps_sum }, { "never_wraps_wrapping_range", never_wraps_wrapping_range },
    { "decoder_unclassifiable", decoder_unclassifiable }, { "input_units_last_output", input_units_last_output },
    { "implicit_change_leftover", implicit_change_leftover }, { "implicit_change_fully_assigned", implicit_change_fully_assigned },
    { "implicit_change_range", implicit_change_range },
    { "implicit_change_burn_flag_in_transfer", implicit_change_burn_flag_in_transfer },
    { "implicit_change_percent", implicit_change_percent }, { "implicit_change_unknown_inputs", implicit_change_unknown_inputs },
    { "implicit_change_over_assigned", implicit_change_over_assigned }, { "implicit_change_issuance", implicit_change_issuance },
    { "implicit_change_burn", implicit_change_burn }, { "implicit_change_vout", implicit_change_vout },
    { "uncountable_bound", uncountable_bound },
    { "destroys_rule", destroys_rule }, { "may_void_rule", may_void_rule }, { "assigned_units_rule", assigned_units_rule },
    { "uncountable_rule", uncountable_rule },
    { "issuance_not_decided", issuance_not_decided }, { "issuance_total_range", issuance_total_range },
    { "issuance_instructions", issuance_instructions }, { "issuance_skip", issuance_skip },
    { "targets_named", targets_named }, { "targets_issuance", targets_issuance }, { "targets_last_output", targets_last_output },
    { "edges", edges }, { "arguments", arguments },
    { "sweep_destroys_may_void", sweep_destroys_may_void }, { "sweep_assigned", sweep_assigned },
    { "sweep_issuance", sweep_issuance }, { "sweep_targets", sweep_targets },
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
        if (MODEL_LIMIT) ck(0, "an input of this case does not fit the model's buffers: the case is broken");
        printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
        return FAILED ? 1 : 0;
    }

    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
