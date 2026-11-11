//
//  asset_decode_kat_main.c
//
//  Host KAT for BRAssetDecode.h: the DigiAssets carrier decoder, moved from Android's Kotlin
//  (core/.../asset/DigiAssetDecoder.kt, BitReader.kt, AssetPayloadCheck.kt). The header must decode
//  exactly what the Kotlin decodes and refuse exactly what it refuses, so every expectation here is
//  fixed OUTSIDE the header, two ways:
//
//    1. BY HAND. The scripts and the expected fields of the Kotlin tests, value for value:
//       DigiAssetDecoderTest (decoder_*), DigiAssetDecoderFuzzTest's fixed cases (fuzz_*),
//       BitReaderTest (bits_*), AssetArithmeticNeverWrapsTest (never_wraps_*), the readsExactly
//       assertions of AssetTransferAllocatorTest, AssetHoldCoversCreditedOutputsTest and
//       UnbackedAssetCreditTest (exact_*), and the scripts of the eight asset_*_kat suites beside
//       this one (kat_*). The cases without one of those prefixes are this suite's own, for rules
//       the Kotlin tests reach only through their random streams.
//    2. BY A MODEL. `m...` below is the Kotlin written out line for line in C, with its own
//       bit-at-a-time reader (the header's takes up to a byte at a time), its own overflow test,
//       and a flag where the Kotlin throws. Every script a case decodes is also put through the
//       model, and model_sweep holds the header to it over a fixed pseudo-random corpus (the
//       Kotlin fuzz test's kotlin.random streams cannot be replayed in C; its seeds and its
//       invariants are used).
//
//  One case per process: `asset_decode_kat <case>` prints PASS:/FAIL: lines and `RESULT <case>
//  pass|fail`; `list` prints the case names. run.sh builds one binary per ASSET_DECODE_UNFIXED arm
//  and holds each comparison arm to failing exactly its cases.
//

#include "BRAssetDecode.h"
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

int asset_decode_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice

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
#define CAP 300        // this suite's instruction buffer: more than any script here can carry
#define SMAX 700       // and its script buffer

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

// ---- the model: the Kotlin, line for line ---------------------------------------------------

typedef struct { int skip, range, percent, burn; int32_t out; int64_t amount; } Inst;

typedef struct {
    int kind;                      // 0 DecodedAssetHeader.unclassifiable(), 1 null, 2 decoded
    int version, opcode, operation;
    int hasHash; uint8_t hash[32];
    int hasCid; char cid[64];
    int hasQty; int64_t qty;
    int divisibility, locked, aggregation;
    size_t n; Inst insts[CAP + 60];
} Model;

// BitReader. `threw` stands for a Kotlin exception: once set, nothing more is read.
typedef struct { const uint8_t *d; size_t total, pos; int threw; } MBits;

static MBits mBits(const uint8_t *d, size_t len) { MBits r; r.d = d; r.total = len * 8; r.pos = 0; r.threw = 0; return r; }
static size_t mRemaining(const MBits *r) { return r->total - r->pos; }

static uint64_t mReadBits(MBits *r, int count)
{
    uint64_t v = 0;
    int i;

    if (r->threw) return 0;
    if (count < 1 || count > 64 || mRemaining(r) < (size_t)count) { r->threw = 1; return 0; }   // require / check

    for (i = 0; i < count; i++) {   // one bit at a time, most significant first
        v = (v << 1) | (uint64_t)((r->d[r->pos / 8] >> (7 - (int)(r->pos % 8))) & 1u);
        r->pos++;
    }

    return v;
}

static void mSkip(MBits *r, size_t count)
{
    if (r->threw) return;
    if (mRemaining(r) < count) { r->threw = 1; return; }
    r->pos += count;
}

// BitReader.pow10: require(exponent in 0..18). By multiplication here, not from a table.
static int64_t mPow10(MBits *r, int exponent)
{
    int64_t v = 1;
    int i;

    if (exponent < 0 || exponent > 18) { r->threw = 1; return 0; }
    for (i = 0; i < exponent; i++) v *= 10;
    return v;
}

// Math.multiplyExact for two numbers that are not negative.
static int64_t mMultiplyExact(MBits *r, int64_t a, int64_t b)
{
    if (r->threw) return 0;
    if (a != 0 && b > INT64_MAX / a) { r->threw = 1; return 0; }
    return a * b;
}

// BitReader.readFixedPrecision
static int64_t mFixedA(MBits *r)
{
    int length, exponent;
    int64_t mantissa;

    if (r->threw) return 0;
    if (mRemaining(r) < 3) { r->threw = 1; return 0; }
    length = (int)mReadBits(r, 3) + 1;

    if (length >= 7) {
        r->pos -= 1;
        length = 7;
    }

    if (length == 1) { mantissa = (int64_t)mReadBits(r, 5); exponent = 0; }
    else if (length < 5) { mantissa = (int64_t)mReadBits(r, length * 8 - 7); exponent = (int)mReadBits(r, 4); }
    else if (length < 7) { mantissa = (int64_t)mReadBits(r, length * 8 - 6); exponent = (int)mReadBits(r, 3); }
    else { mantissa = (int64_t)mReadBits(r, 54); exponent = 0; }

    if (r->threw) return 0;
    return mMultiplyExact(r, mantissa, mPow10(r, exponent));
}

// AssetPayloadCheck.fixedPrecision
static int64_t mFixedB(MBits *r)
{
    int length = (int)mReadBits(r, 3) + 1, exponent = 0, i;
    int64_t value;

    if (r->threw) return 0;

    if (length >= 7) {
        int64_t high = (int64_t)(length - 7);

        return (int64_t)(((uint64_t)high << 53) | mReadBits(r, 53));
    }

    if (length == 1) value = (int64_t)mReadBits(r, 5);
    else if (length < 5) { value = (int64_t)mReadBits(r, length * 8 - 7); exponent = (int)mReadBits(r, 4); }
    else { value = (int64_t)mReadBits(r, length * 8 - 6); exponent = (int)mReadBits(r, 3); }

    for (i = 0; i < exponent; i++) value = mMultiplyExact(r, value, 10);
    return value;
}

// DigiAssetDecoder.locateCarrier: 0 null, 1 Carrier(null), 2 Carrier(payload)
static int mLocate(const uint8_t *s, size_t size, size_t *off, size_t *len)
{
    uint64_t pushLen = 0;
    size_t dataOff, i;
    int pushByte;

    *off = 0; *len = 0;
    if (size < 2 || s[0] != 0x6a) return 0;
    pushByte = s[1];

    if (pushByte <= 75) { pushLen = (uint64_t)pushByte; dataOff = 2; }
    else if (pushByte == 0x4c) { if (size < 3) return 0; pushLen = s[2]; dataOff = 3; }
    else if (pushByte == 0x4d) { if (size < 4) return 0; for (i = 2; i > 0; i--) pushLen = (pushLen << 8) | s[1 + i]; dataOff = 4; }
    else if (pushByte == 0x4e) { if (size < 6) return 0; for (i = 4; i > 0; i--) pushLen = (pushLen << 8) | s[1 + i]; dataOff = 6; }
    else return 0;

    if (pushLen < 2 || size < dataOff + 2) return 0;
    if (((s[dataOff] << 8) | s[dataOff + 1]) != 0x4441) return 0;
    if (pushByte == 0x4d || pushByte == 0x4e) return 1;
    if (pushLen < 4 || dataOff + pushLen > size) return 1;
    *off = dataOff; *len = (size_t)pushLen;
    return 2;
}

// DigiAssetDecoder.sha256ToCidV1: here the 288 bits are cut five at a time, with no carry buffer.
static void mCid(const uint8_t hash[32], char out[64])
{
    static const char alphabet[] = "abcdefghijklmnopqrstuvwxyz234567";
    uint8_t cid[36] = { 0x01, 0x55, 0x12, 0x20 };
    size_t bit, n = 0;

    memcpy(&cid[4], hash, 32);
    out[n++] = 'b';

    for (bit = 0; bit < 36 * 8; bit += 5) {
        unsigned v = 0;
        size_t k;

        for (k = bit; k < bit + 5; k++) v = (v << 1) | ((k < 36 * 8) ? ((cid[k / 8] >> (7 - k % 8)) & 1u) : 0u);
        out[n++] = alphabet[v];
    }

    out[n] = '\0';
}

// DigiAssetDecoder.parseTransferInstructions. A throw sets r->threw; `lenient` breaks as the Kotlin does.
static void mInstructions(MBits *r, int operation, Model *m)
{
    size_t footerBits = (operation == DA_ISSUANCE) ? 8 : 0;
    int lenient = (operation == DA_ISSUANCE);

    m->n = 0;

    while (mRemaining(r) > footerBits) {
        Inst in;

        if (mRemaining(r) < 8 + footerBits) {
            if (lenient) break;
            r->threw = 1; return;
        }

        in.skip = (mReadBits(r, 1) == 1);
        in.range = (mReadBits(r, 1) == 1);
        in.percent = (mReadBits(r, 1) == 1);
        in.out = (int32_t)(in.range ? mReadBits(r, 13) : mReadBits(r, 5));
        if (r->threw) return;

        if (in.percent) {
            in.amount = (int64_t)mReadBits(r, 8);
            if (r->threw) return;
        }
        else {
            in.amount = mFixedA(r);
            if (r->threw) { if (lenient) { r->threw = 0; break; } return; }   // catch: break, or rethrow
        }

        in.burn = (! in.range && in.out == 31 && operation == DA_BURN);
        if (m->n < N(m->insts)) m->insts[m->n] = in;
        m->n++;
    }
}

// DigiAssetDecoder.parseHeader: 1 a header, 0 null or a throw (decode() answers unclassifiable for both)
static int mParseHeader(const uint8_t *payload, size_t len, Model *m)
{
    MBits r = mBits(payload, len);
    int version, opcode, operation, i, allZero = 1;

    mSkip(&r, 16);
    version = (int)mReadBits(&r, 8);
    if (r.threw) return 0;
    if (version == 0) return 0;
    opcode = (int)mReadBits(&r, 8);
    if (r.threw) return 0;

    if ((opcode >= 1 && opcode <= 4) || opcode == 5) operation = DA_ISSUANCE;
    else if (opcode == 0x15) operation = DA_TRANSFER;
    else if (opcode == 0x25) operation = DA_BURN;
    else return 0;

    if (version < 3 && opcode < 3) {
        if (mRemaining(&r) < 160) return 0;
        mSkip(&r, 160);
    }

    if (opcode == 1 || opcode == 3 || opcode == 4) {
        if (mRemaining(&r) < 256) return 0;
        for (i = 0; i < 32; i++) m->hash[i] = (uint8_t)mReadBits(&r, 8);
        m->hasHash = 1;
        for (i = 0; i < 32; i++) if (m->hash[i] != 0) allZero = 0;
        if (! allZero) { m->hasCid = 1; mCid(m->hash, m->cid); }
    }

    if (operation == DA_ISSUANCE) {
        int flagsByte, a;

        m->qty = mFixedA(&r);
        if (r.threw) return 0;
        m->hasQty = 1;
        flagsByte = payload[len - 1];
        m->divisibility = flagsByte >> 5;
        m->locked = ((flagsByte & 0x10) != 0);
        a = (flagsByte & 0x0c) >> 2;
        if (a == 3) return 0;
        m->aggregation = a;

        if (version == 1 && m->divisibility > 0) {
            int64_t pow10 = 1;

            for (i = 0; i < m->divisibility; i++) pow10 *= 10;
            m->qty = m->qty / pow10;
        }

        mInstructions(&r, operation, m);
        if (r.threw) m->n = 0;   // try { ... } catch (_: Exception) { emptyList() }
    }
    else {
        mInstructions(&r, operation, m);
        if (r.threw) return 0;
    }

    m->version = version; m->opcode = opcode; m->operation = operation;
    return 1;
}

// DigiAssetDecoder.decode (and containsAsset: kind != 1)
static void mDecode(const uint8_t *script, size_t size, Model *m, size_t *off, size_t *len)
{
    int c = mLocate(script, size, off, len);

    memset(m, 0, sizeof(*m));
    if (c == 0) { m->kind = 1; return; }
    if (c == 1) { m->kind = 0; return; }

    if (! mParseHeader(script + *off, *len, m)) {
        memset(m, 0, sizeof(*m));   // DecodedAssetHeader.unclassifiable()
        m->kind = 0;
        return;
    }

    m->kind = 2;
}

// AssetPayloadCheck.readsExactly, for the header and the list the C side holds.
static int mExact(const uint8_t *script, size_t size, const BRAssetHeader *h,
                  const BRAssetTransferInstruction *insts, size_t count)
{
    MBits r;
    const uint8_t *payload;
    size_t payloadLen, footer, n = 0;
    int push, version, opcode, issuance, same = 1;

    if (size < 2 || script[0] != 0x6a) return 0;   // payloadOf
    push = script[1];

    if (push >= 1 && push <= 75) { if (size != 2 + (size_t)push) return 0; payload = script + 2; payloadLen = size - 2; }
    else if (push == 0x4c) { if (size < 3) return 0; if (size != 3 + (size_t)script[2]) return 0; payload = script + 3; payloadLen = size - 3; }
    else return 0;

    r = mBits(payload, payloadLen);
    if (mReadBits(&r, 16) != 0x4441 || r.threw) return 0;
    version = (int)mReadBits(&r, 8);
    opcode = (int)mReadBits(&r, 8);
    if (r.threw) return 0;
    if (version != h->version || opcode != h->opcode) return 0;
    issuance = (h->operation == DA_ISSUANCE);
    if (issuance && (h->opcode == 3 || h->opcode == 4 || version < 3)) return 0;
    if (version < 3 && opcode < 3) mSkip(&r, 160);
    if (opcode == 1 || opcode == 3 || opcode == 4) mSkip(&r, 256);
    if (r.threw) return 0;

    if (issuance) {
        int64_t q = mFixedB(&r);

        if (r.threw) return 0;
        if (! h->hasTotalQuantity || q != h->totalQuantity) return 0;
    }

    footer = issuance ? 8 : 0;

    while (mRemaining(&r) > footer) {
        Inst in;

        in.skip = (mReadBits(&r, 1) == 1);
        in.range = (mReadBits(&r, 1) == 1);
        in.percent = (mReadBits(&r, 1) == 1);
        in.out = (int32_t)mReadBits(&r, in.range ? 13 : 5);
        in.amount = in.percent ? (int64_t)mReadBits(&r, 8) : mFixedB(&r);
        if (r.threw) return 0;
        in.burn = (! in.range && in.out == 31 && h->operation == DA_BURN);

        // read == header.transferInstructions: kept as "the same so far", decided at the end
        if (n >= count || (insts[n].skip != 0) != in.skip || (insts[n].range != 0) != in.range ||
            (insts[n].percent != 0) != in.percent || (insts[n].isBurn != 0) != in.burn ||
            insts[n].outputIndex != in.out || insts[n].amount != in.amount) same = 0;
        n++;
    }

    if (mRemaining(&r) != footer) return 0;
    return (same && n == count) ? 1 : 0;
}

// ---- the header against the model -----------------------------------------------------------

static uint8_t S[SMAX];                       // the script under test
static size_t SL;
static BRAssetHeader H;                       // what the header decoded from it
static BRAssetTransferInstruction L[CAP];
static BRAssetScriptKind K;
static Model M;

static int isZero(const void *p, size_t len)
{
    const uint8_t *b = (const uint8_t *)p;
    size_t i;

    for (i = 0; i < len; i++) if (b[i] != 0) return 0;
    return 1;
}

// Decodes S[0, SL) with the header into K, H, L and with the model into M. NULL when they agree on
// everything the Kotlin answers; otherwise what differs.
static const char *decodeBoth(void)
{
    static const BRAssetScriptKind kindOf[3] = { BR_ASSET_SCRIPT_UNCLASSIFIABLE, BR_ASSET_SCRIPT_NOT_ASSET, BR_ASSET_SCRIPT_ASSET };
    BRAssetHeader h2;
    BRAssetScriptKind k2, kc;
    size_t off = 99, len = 99, moff, mlen, i, cidLen;
    char cid[BR_ASSET_CID_BUFFER];

    memset(L, 0x5a, sizeof(L));
    K = BRAssetDecodeInstructions(S, SL, &H, L, CAP);
    k2 = BRAssetDecodeHeader(S, SL, &h2);
    kc = BRAssetDecodeCarrier(S, SL, &off, &len);
    mDecode(S, SL, &M, &moff, &mlen);

    if (K != H.kind) return "the kind returned is not the header's";
    if (k2 != K || h2.kind != K) return "BRAssetDecodeHeader's kind is not BRAssetDecodeInstructions'";
    if (h2.version != H.version || h2.opcode != H.opcode || h2.operation != H.operation ||
        h2.hasMetadataHash != H.hasMetadataHash || memcmp(h2.metadataHash, H.metadataHash, 32) != 0 ||
        h2.hasTotalQuantity != H.hasTotalQuantity || h2.totalQuantity != H.totalQuantity ||
        h2.divisibility != H.divisibility || h2.locked != H.locked || h2.aggregation != H.aggregation ||
        h2.instructionCount != H.instructionCount) return "BRAssetDecodeHeader's fields are not BRAssetDecodeInstructions'";
    if (K != kindOf[M.kind]) return "kind (decode)";
    if ((kc == BR_ASSET_SCRIPT_NOT_ASSET) != (M.kind == 1)) return "containsAsset";
    if (kc == BR_ASSET_SCRIPT_ASSET ? (off != moff || len != mlen) : (off != 0 || len != 0)) return "the payload's place";

    if (K != BR_ASSET_SCRIPT_ASSET) {   // null, or unclassifiable(): nothing else is set
        if (H.version != 0 || H.opcode != 0 || H.operation != DA_UNDEFINED || H.hasMetadataHash != 0 ||
            ! isZero(H.metadataHash, 32) || H.hasTotalQuantity != 0 || H.totalQuantity != 0 || H.divisibility != 0 ||
            H.locked != 0 || H.aggregation != BR_ASSET_AGGREGATABLE || H.instructionCount != 0) return "a field is set in a header that is not an asset";
        if (BRAssetMetadataCid(&H, cid, sizeof(cid)) != 0 || cid[0] != '\0') return "a CID for a header that is not an asset";
        return NULL;
    }

    if (H.version != M.version) return "version";
    if (H.opcode != M.opcode) return "opcode";
    if ((int)H.operation != M.operation) return "operation";
    if (BRAssetHeaderHasRules(&H) != (M.opcode == 3 || M.opcode == 4)) return "hasRules";
    if ((H.hasMetadataHash != 0) != M.hasHash || memcmp(H.metadataHash, M.hash, 32) != 0) return "metadataHash";
    cidLen = BRAssetMetadataCid(&H, cid, sizeof(cid));
    if (M.hasCid ? (cidLen != BR_ASSET_CID_LENGTH || strcmp(cid, M.cid) != 0) : (cidLen != 0 || cid[0] != '\0')) return "metadataCid";
    if ((H.hasTotalQuantity != 0) != M.hasQty || H.totalQuantity != M.qty) return "totalQuantity";
    if (H.divisibility != M.divisibility) return "divisibility";
    if ((H.locked != 0) != M.locked) return "locked";
    if ((int)H.aggregation != M.aggregation) return "aggregation";
    if (H.instructionCount != M.n) return "the number of instructions";

    for (i = 0; i < M.n && i < CAP; i++) {
        if ((L[i].skip != 0) != M.insts[i].skip || (L[i].range != 0) != M.insts[i].range ||
            (L[i].percent != 0) != M.insts[i].percent || (L[i].isBurn != 0) != M.insts[i].burn ||
            L[i].outputIndex != M.insts[i].out || L[i].amount != M.insts[i].amount) return "an instruction";
    }

    return NULL;
}

// ---- expectations by hand -------------------------------------------------------------------

// Puts `hex` in S, decodes it both ways and says so when the model reads it differently.
static void dec(const char *hex, const char *what)
{
    const char *diff;

    SL = hx(hex, S, sizeof(S));
    diff = decodeBoth();
    if (diff) ck(0, "%s: the header and the Kotlin model differ in %s", what, diff);
}

// Kotlin: decode(script) == null, containsAsset(script) == false
static void wantNot(const char *hex, const char *what)
{
    dec(hex, what);
    ck(K == BR_ASSET_SCRIPT_NOT_ASSET && BRAssetDecodeCarrier(S, SL, NULL, NULL) == BR_ASSET_SCRIPT_NOT_ASSET,
       "%s: not an asset (kind %d)", what, (int)K);
}

// Kotlin: decode(script)!!.operation == UNCLASSIFIABLE, no instruction, no quantity; containsAsset == true
static void wantUncl(const char *hex, const char *what)
{
    dec(hex, what);
    ck(K == BR_ASSET_SCRIPT_UNCLASSIFIABLE && H.operation == DA_UNDEFINED && H.instructionCount == 0 &&
       ! H.hasTotalQuantity && BRAssetDecodeCarrier(S, SL, NULL, NULL) != BR_ASSET_SCRIPT_NOT_ASSET,
       "%s: unclassifiable, and still an asset carrier (kind %d)", what, (int)K);
}

static void wantAsset(const char *hex, const char *what, int version, int opcode, BRAssetOperation op, size_t count)
{
    dec(hex, what);
    ck(K == BR_ASSET_SCRIPT_ASSET && H.version == version && H.opcode == opcode && H.operation == op &&
       H.instructionCount == count,
       "%s: version %d opcode 0x%02x operation %d with %u instruction(s) (kind %d: %d, 0x%02x, %d, %u)", what,
       version, (unsigned)opcode, (int)op, (unsigned)count, (int)K, (int)H.version, (unsigned)H.opcode,
       (int)H.operation, (unsigned)H.instructionCount);
}

static void wantInst(size_t i, int skip, int range, int percent, int burn, int32_t out, int64_t amount)
{
    int ok = (K == BR_ASSET_SCRIPT_ASSET && i < H.instructionCount && i < CAP && L[i].skip == skip &&
              L[i].range == range && L[i].percent == percent && L[i].isBurn == burn && L[i].outputIndex == out &&
              L[i].amount == amount);

    if (ok) ck(1, "  instruction %u: output %d amount %lld%s%s%s%s", (unsigned)i, (int)out, (long long)amount,
               skip ? " skip" : "", range ? " range" : "", percent ? " percent" : "", burn ? " burn" : "");
    else if (K == BR_ASSET_SCRIPT_ASSET && i < H.instructionCount && i < CAP)
        ck(0, "  instruction %u: want (%d %d %d %d, %d, %lld), read (%d %d %d %d, %d, %lld)", (unsigned)i, skip, range,
           percent, burn, (int)out, (long long)amount, L[i].skip, L[i].range, L[i].percent, L[i].isBurn,
           (int)L[i].outputIndex, (long long)L[i].amount);
    else ck(0, "  instruction %u: want (%d %d %d %d, %d, %lld), and there is none", (unsigned)i, skip, range, percent,
            burn, (int)out, (long long)amount);
}

static void wantIssue(int64_t quantity, int divisibility, int locked, BRAssetAggregation aggregation)
{
    ck(H.hasTotalQuantity == 1 && H.totalQuantity == quantity && H.divisibility == divisibility &&
       H.locked == locked && H.aggregation == aggregation,
       "  issues %lld, divisibility %d, locked %d, aggregation %d (read %d: %lld, %d, %d, %d)", (long long)quantity,
       divisibility, locked, (int)aggregation, H.hasTotalQuantity, (long long)H.totalQuantity, (int)H.divisibility,
       H.locked, (int)H.aggregation);
}

static void wantNoIssue(void)   // Kotlin: totalQuantity == null, and the defaults
{
    ck(H.hasTotalQuantity == 0 && H.totalQuantity == 0 && H.divisibility == 0 && H.locked == 0 &&
       H.aggregation == BR_ASSET_AGGREGATABLE, "  no quantity, divisibility 0, unlocked, aggregatable");
}

// Kotlin: metadataHash / metadataCid; NULL for none
static void wantCid(int hasHash, const char *cid)
{
    char got[BR_ASSET_CID_BUFFER];
    size_t n = BRAssetMetadataCid(&H, got, sizeof(got));

    ck(H.hasMetadataHash == hasHash, "  metadata hash %s", hasHash ? "present" : "absent");
    if (cid) ck(n == BR_ASSET_CID_LENGTH && strlen(cid) == BR_ASSET_CID_LENGTH && strcmp(got, cid) == 0, "  CID %s (read \"%s\")", cid, got);
    else ck(n == 0 && got[0] == '\0', "  no CID (read \"%s\")", got);
}

// Kotlin: AssetPayloadCheck.readsExactly(script, decode(script)!!)
static void wantExact(int expected)
{
    int got = BRAssetPayloadReadsExactly(S, SL, &H, L, (size_t)H.instructionCount);
    int model = mExact(S, SL, &H, L, (size_t)H.instructionCount);

    ck(got == expected, "  re-read exactly: %d (header answers %d)", expected, got);
    if (model != expected) ck(0, "  re-read exactly: the Kotlin model answers %d, the vector says %d", model, expected);
}

// ---- DigiAssetDecoderTest -------------------------------------------------------------------

static void decoder_transfer_v3(void)
{
    wantAsset("6a0744410315002052", "TRANSFER_V3", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 500);
    wantNoIssue();
    wantCid(0, NULL);
}

static void decoder_issuance_locked(void)
{
    wantAsset("6a2a44410301d66f1117234767d6892700d575511e1effcd6471013dda0e832dbb67e629d674215500215550",
              "ISSUANCE_V3_LOCKED", 3, 1, DA_ISSUANCE, 1);
    wantIssue(2100000, 2, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 2100000);
    wantCid(1, "bafkreigwn4iroi2hm7lisjya2v2vchq677gwi4ibhxna5aznxnt6mkowoq");
    ck(BRAssetHeaderHasRules(&H) == 0, "  opcode 1 has no rules");
    wantExact(1);
}

static void decoder_burn_v3(void)
{
    wantAsset("6a0a444103250020221f2032", "BURN_V3", 3, 0x25, DA_BURN, 2);
    wantInst(0, 0, 0, 0, 0, 0, 200);
    wantInst(1, 0, 0, 0, 1, 31, 300);
    wantNoIssue();
}

static void decoder_issuance_dispersed(void)
{
    wantAsset("6a084441030501000108", "ISSUANCE_V3_UNLOCKED_DISPERSED", 3, 5, DA_ISSUANCE, 1);
    wantIssue(1, 0, 0, BR_ASSET_DISPERSED);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantCid(0, NULL);
}

static void decoder_transfer_skip(void)
{
    wantAsset("6a0a44410315802012012051", "TRANSFER_V3_SKIP", 3, 0x15, DA_TRANSFER, 2);
    wantInst(0, 1, 0, 0, 0, 0, 100);
    wantInst(1, 0, 0, 0, 0, 1, 50);
}

static void decoder_issuance_rules(void)
{
    // An issuance with rules and a metadata hash of 32 zero bytes: a hash, and no CID. The bytes after
    // the amount are a rules block; the decoder reads them as instructions, as the Kotlin does.
    wantAsset("6a2b4441030300000000000000000000000000000000000000000000000000000000000000002013ff00201300",
              "ISSUANCE_V3_RULES", 3, 3, DA_ISSUANCE, 2);
    wantIssue(1000, 0, 0, BR_ASSET_AGGREGATABLE);
    wantCid(1, NULL);
    ck(BRAssetHeaderHasRules(&H) == 1, "  opcode 3 has rules");
    wantInst(0, 1, 1, 1, 0, 7936, 32);
    wantInst(1, 0, 0, 0, 0, 19, 0);
}

static void decoder_non_da(void)
{
    wantNot("6a24aa21a9ed6fb29ce476d46dce43e21190d6c2", "a witness commitment (NON_DA)");
    wantNot("", "an empty script");
    wantNot("6a02", "OP_RETURN and a push length, nothing pushed");
    wantNot("6a024442", "a two-byte push that is not the tag");
    wantNot("6a4d060068656c6c6f21", "OP_PUSHDATA2 of plain text");
}

static void decoder_mainnet_vectors(void)
{
    wantAsset("6a2b444103041a343da7c7a2b6d25667e63e24980cb56ddf65970bbd8315e99e97c0ec847d9e0510101f000510",
              "ISSUANCE_V3_RULED_5401", 3, 4, DA_ISSUANCE, 3);
    wantIssue(5, 0, 1, BR_ASSET_AGGREGATABLE);
    ck(BRAssetHeaderHasRules(&H) == 1, "  opcode 4 has rules");
    wantCid(1, "bafkreia2gq62pr5cw3jfmz7ghysjqdfvnxpwlfylxwbrl2m6s7aozbd5ty");
    wantInst(0, 0, 0, 0, 0, 16, 16);
    wantInst(1, 0, 0, 0, 0, 31, 0);
    wantInst(2, 0, 0, 0, 0, 5, 16);

    wantAsset("6a2b444103031a343da7c7a2b6d25667e63e24980cb56ddf65970bbd8315e99e97c0ec847d9e0510101f000510",
              "the same with opcode 3", 3, 3, DA_ISSUANCE, 3);
    ck(BRAssetHeaderHasRules(&H) == 1, "  opcode 3 has rules");

    wantAsset("6a0744410215002013", "REAL_MAINNET_TRANSFER_V2", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 1000);

    // "Mobile Tester", version 1 opcode 1: tag, version, opcode, 20 bytes of SHA1, the 32-byte hash, the
    // amount 0f and the flags 10. The Kotlin literal carries one more hex digit, which its hexToBytes
    // drops; the 62 bytes it decodes are these. The two bytes after the push are ignored.
    wantAsset("6a3a4441010134fdfc1f913ab9aae04fa1daf92f389248ff21f1"
              "be4e676d3cfc8a454fcc38c94b192b8ef916fbbc8f0903c558568b21cafd2a620f1098fa",
              "Mobile Tester (v1 opcode 1)", 1, 1, DA_ISSUANCE, 0);
    wantIssue(15, 0, 1, BR_ASSET_AGGREGATABLE);
    wantCid(1, "bafkreif6jztw2ph4rjcu7tbyzffrsk4o7elpxpepbeb4kwcwrmq4v7jkmi");

    wantAsset("6a0644410215000a", "a version 2 transfer of 10 to output 0", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 10);
}

static void decoder_tag_only_unclassifiable(void)
{
    wantUncl("6a024441", "the tag and nothing after it");
}

static void decoder_pushdata_framing(void)
{
    char big[2 * 261 + 1];
    size_t i, n;

    wantUncl("6a4d060044410215000a", "the transfer behind OP_PUSHDATA2");
    wantUncl("6a4e0600000044410215000a", "the transfer behind OP_PUSHDATA4");

    // 6a 4d 0101 and a 257-byte payload: 44410215, the instruction 00 2001, then 125 of 00 01.
    n = (size_t)sprintf(big, "6a4d010144410215002001");
    for (i = 0; i < 125; i++) n += (size_t)sprintf(big + n, "0001");
    wantUncl(big, "a 257-byte transfer behind OP_PUSHDATA2");
    wantNot("6a4d060068656c6c6f21", "plain text behind OP_PUSHDATA2");
}

static void decoder_burn_marker(void)
{
    wantAsset("6a06444102151f01", "a TRANSFER to output 31", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 31, 1);
    wantAsset("6a06444102251f01", "a BURN of output 31", 2, 0x25, DA_BURN, 1);
    wantInst(0, 0, 0, 0, 1, 31, 1);
}

// ---- DigiAssetDecoderFuzzTest, its fixed cases ----------------------------------------------

static void fuzz_not_asset(void)
{
    wantNot("", "empty");
    wantNot("6a", "OP_RETURN alone");
    wantNot("0014 abababababababababababababababababababab", "a P2WPKH script");
    wantNot("6a1e44444444", "a push that is not tagged");
    wantNot("6a4c80444444444444", "OP_PUSHDATA1, not tagged");
    wantNot("6a4d020044420315", "OP_PUSHDATA2, another tag");
    wantNot("6a4e0200000044420315", "OP_PUSHDATA4, another tag (and two bytes more)");
    wantNot("6a4e020000004442", "OP_PUSHDATA4, another tag");
    wantNot("6a4d01004441", "OP_PUSHDATA2 of one byte");
    wantNot("6a4e0200", "OP_PUSHDATA4 without its length");
    wantNot("6a0c 44420315 0000000000000000", "a direct push with another tag");
}

static void fuzz_unclassifiable(void)
{
    wantUncl("6a024441", "the tag alone");
    wantUncl("6a1e44410315000a", "a push that says 30 bytes and has 6");
    wantUncl("6a4d020044410315", "OP_PUSHDATA2, tagged");
    wantUncl("6a4e0200000044410315", "OP_PUSHDATA4, tagged");
    wantUncl("6a0c 44410015 0000000000000000", "version 0");
    wantUncl("6a0c 444103ff 0000000000000000", "an opcode nobody defines");
    wantUncl("6a08 44410301 aaaaaaaa", "opcode 1 without its 32-byte hash");
    wantUncl("6a0e 44410201 aaaaaaaaaaaaaaaaaaaa", "version 2 opcode 1 without its 20 bytes of SHA1");
    wantUncl("6a05 44410205 e0", "an issuance whose amount is cut");
}

static void fuzz_truncated_transfer(void)
{
    wantUncl("6a05 44410215 03", "a transfer instruction with no amount");
    wantUncl("6a06 44410215 0020", "a transfer amount cut in the middle");
    wantUncl("6a05 44410215 40", "a range instruction without its index");
}

// ---- BitReaderTest --------------------------------------------------------------------------

static uint8_t B[16];

static BRAssetBitReader bitsOf(const char *hex) { size_t n = hx(hex, B, sizeof(B)); return BRAssetBitReaderMake(B, n); }

static void wantBits(BRAssetBitReader *r, int count, uint64_t want, const char *what)
{
    uint64_t v = 0xdeadbeef;
    int ok = BRAssetBitsRead(r, count, &v);

    ck(ok == 1 && v == want, "%s: %d bit(s) read 0x%llx (ok %d, 0x%llx)", what, count, (unsigned long long)want, ok,
       (unsigned long long)v);
}

static void bits_read(void)
{
    BRAssetBitReader r = bitsOf("a5");
    uint8_t out[3] = { 0, 0, 0 };

    wantBits(&r, 1, 1, "a5"); wantBits(&r, 1, 0, "a5"); wantBits(&r, 2, 2, "a5"); wantBits(&r, 4, 5, "a5");
    r = bitsOf("ff00");
    wantBits(&r, 4, 0xf, "ff00"); wantBits(&r, 8, 0xf0, "ff00, across the byte boundary");
    r = bitsOf("4441");
    wantBits(&r, 8, 0x44, "4441"); wantBits(&r, 8, 0x41, "4441");
    r = bitsOf("4441");
    wantBits(&r, 16, 0x4441, "4441");
    r = bitsOf("ff00");
    ck(BRAssetBitsRemaining(&r) == 16, "ff00: 16 bits remain");
    wantBits(&r, 5, 0x1f, "ff00");
    ck(BRAssetBitsRemaining(&r) == 11, "  then 11 remain");
    wantBits(&r, 11, 0x700, "ff00");
    ck(BRAssetBitsRemaining(&r) == 0, "  then none");
    r = bitsOf("abcd");
    ck(BRAssetBitsSkip(&r, 8) == 1, "abcd: skip 8");
    wantBits(&r, 8, 0xcd, "abcd after the skip");
    r = bitsOf("444103");
    ck(BRAssetBitsReadBytes(&r, out, 3) == 1 && out[0] == 0x44 && out[1] == 0x41 && out[2] == 0x03, "444103: three bytes");
    r = bitsOf("ffffffffffffffff");
    wantBits(&r, 64, UINT64_MAX, "64 bits at once");
}

static void wantFixed(const char *hex, int64_t want)
{
    BRAssetBitReader r = bitsOf(hex);
    int64_t v = -1;
    int ok = BRAssetBitsReadFixedPrecision(&r, &v);

    ck(ok == 1 && v == want && BRAssetBitsRemaining(&r) == 0, "fixed precision %s = %lld (ok %d, %lld, %u bits left)", hex,
       (long long)want, ok, (long long)v, (unsigned)BRAssetBitsRemaining(&r));
}

static void wantFixedRefused(const char *hex, const char *why)
{
    BRAssetBitReader r = bitsOf(hex);
    int64_t v = -1;
    int ok = BRAssetBitsReadFixedPrecision(&r, &v);

    ck(ok == 0, "fixed precision %s is not read: %s (ok %d, %lld)", hex, why, ok, (long long)v);
}

static void bits_fixed_precision(void)
{
    BRAssetBitReader r;
    int64_t a = -1, b = -1;

    wantFixed("00", 0); wantFixed("01", 1); wantFixed("05", 5); wantFixed("1f", 31); wantFixed("2200", 32);
    wantFixed("2012", 100); wantFixed("2052", 500); wantFixed("2013", 1000); wantFixed("2014", 10000);
    wantFixed("2155", 2100000); wantFixed("2018", 100000000);
    r = bitsOf("20522022");
    ck(BRAssetBitsReadFixedPrecision(&r, &a) == 1 && BRAssetBitsReadFixedPrecision(&r, &b) == 1 && a == 500 && b == 200,
       "two amounts in one stream: 500 then 200 (%lld, %lld)", (long long)a, (long long)b);
}

static void bits_fixed_precision_unaligned(void)
{
    BRAssetBitReader r = bitsOf("00a0");
    int64_t v = -1;
    int ok;

    ck(BRAssetBitsSkip(&r, 3) == 1, "00a0: skip 3 bits");
    ok = BRAssetBitsReadFixedPrecision(&r, &v);
    ck(ok == 1 && v == 5 && BRAssetBitsRemaining(&r) == 5, "  the amount that starts inside the byte is 5 (ok %d, %lld, %u bits left)",
       ok, (long long)v, (unsigned)BRAssetBitsRemaining(&r));
}

static void bits_pow10(void)
{
    static const int exps[] = { 0, 1, 2, 3, 9, 18 };
    static const int64_t want[] = { 1, 10, 100, 1000, 1000000000LL, 1000000000000000000LL };
    int64_t v, p = 1;
    size_t i;
    int e, all = 1;

    for (i = 0; i < N(exps); i++) {
        v = -1;
        ck(BRAssetPow10(exps[i], &v) == 1 && v == want[i], "pow10(%d) = %lld", exps[i], (long long)want[i]);
    }

    v = -1;
    ck(BRAssetPow10(19, &v) == 0 && v == -1, "pow10(19) is refused");
    ck(BRAssetPow10(-1, &v) == 0 && v == -1, "pow10(-1) is refused");
    for (e = 0; e <= 18; e++) { v = -1; if (! BRAssetPow10(e, &v) || v != p) all = 0; if (e < 18) p *= 10; }
    ck(all, "pow10(0..18) is 1, 10, ... by multiplication");
}

static void bits_short_reads(void)
{
    BRAssetBitReader r = bitsOf("a5");
    uint64_t v = 7;
    uint8_t out[2] = { 0, 0 };
    int64_t f = -1;

    ck(BRAssetBitsRead(&r, 0, &v) == 0 && BRAssetBitsRead(&r, 65, &v) == 0 && BRAssetBitsRead(&r, -1, &v) == 0 && v == 7 &&
       r.position == 0, "a count outside 1..64 is refused, and nothing is read");
    ck(BRAssetBitsRead(&r, 9, &v) == 0 && v == 7 && r.position == 0, "more bits than are left: refused, nothing read");
    ck(BRAssetBitsSkip(&r, 9) == 0 && r.position == 0 && BRAssetBitsSkip(&r, 8) == 1 && BRAssetBitsRemaining(&r) == 0,
       "a skip past the end is refused; a skip to the end is not");
    ck(BRAssetBitsReadFixedPrecision(&r, &f) == 0 && f == -1, "an amount with no bits left is not read");
    r = bitsOf("a5");
    ck(BRAssetBitsReadBytes(&r, out, 2) == 0 && out[0] == 0xa5, "two bytes from one: refused after the first");
    wantFixedRefused("20", "its second byte is missing");
    wantFixedRefused("c000000000", "the 7-byte form with 5 bytes");
    r = bitsOf("ff");
    ck(BRAssetBitsSkip(&r, 6) == 1 && BRAssetBitsReadFixedPrecision(&r, &f) == 0 && f == -1, "an amount with 2 bits left is not read");
    r = BRAssetBitReaderMake(NULL, 4);
    ck(BRAssetBitsRemaining(&r) == 0 && BRAssetBitsRead(&r, 1, &v) == 0, "a reader over no data has no bits");
    r = BRAssetBitReaderMake(B, SIZE_MAX);
    ck(BRAssetBitsRemaining(&r) == 0, "a length whose bit count does not fit a size_t: no bits");
}

// ---- AssetArithmeticNeverWrapsTest ----------------------------------------------------------

static void never_wraps_fixed_precision(void)
{
    char hex[13];
    uint64_t m = 922337203685ULL;   // INT64_MAX / 10^7, rounded down
    uint64_t enc;

    wantFixedRefused("bfffffffffff", "(2^42 - 1) * 10^7 is past INT64_MAX");
    wantFixed("ffffffffffffff", 18014398509481983LL);   // 2^54 - 1, the largest 7-byte amount
    // the 6-byte form: 101, a 42-bit mantissa, a 3-bit exponent
    enc = (5ULL << 45) | (m << 3) | 7ULL;
    sprintf(hex, "%012llx", (unsigned long long)enc);
    wantFixed(hex, 9223372036850000000LL);              // the largest multiple of 10^7 an int64 holds
    enc = (5ULL << 45) | ((m + 1) << 3) | 7ULL;
    sprintf(hex, "%012llx", (unsigned long long)enc);
    wantFixedRefused(hex, "one more in the mantissa is past INT64_MAX");
    wantFixedRefused("7fffffff", "(2^25 - 1) * 10^15 is past INT64_MAX");
    wantFixed("60000003", 0);                           // a mantissa of 0 with exponent 3 is 0
    wantFixed("6000000f", 0);                           // and with exponent 15, a power of ten the form cannot reach otherwise
    wantFixed("60000013", 1000);
}

static void never_wraps_carriers(void)
{
    wantUncl("6a0b4441031500bfffffffffff", "a transfer whose amount is past INT64_MAX (6-byte form)");
    wantUncl("6a0944410315007fffffff", "a transfer whose amount is past INT64_MAX (4-byte form)");
    wantNot("6a0a03154001a5d21dba0007", "the same shape without the tag");
    wantAsset("6a0c444103154001a5d21dba0007", "a range transfer of 8 * 10^18", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 1, 0, 0, 1, 8000000000000000000LL);
    wantAsset("6a0d444103154200ffffffffffffff", "a range transfer of 2^54 - 1", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 1, 0, 0, 512, 18014398509481983LL);
    // an issuance keeps what it read before an amount that is not a count
    wantAsset("6a0d 44410305 0a 0001 01 7fffffff 10", "an issuance whose second amount is past INT64_MAX", 3, 5, DA_ISSUANCE, 1);
    wantIssue(10, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantUncl("6a09 44410305 7fffffff 10", "an issuance of an amount past INT64_MAX");
}

// ---- AssetPayloadCheck.readsExactly, as the Kotlin tests assert it ---------------------------

static void exact_allocator_vectors(void)   // AssetTransferAllocatorTest
{
    wantAsset("6a08 44410305 0a 00 04 10", "an issuance of 10, 4 to output 0", 3, 5, DA_ISSUANCE, 1);
    wantIssue(10, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 4);
    wantExact(1);
    wantAsset("6a06 44410305 0a 10", "an issuance with no instruction", 3, 5, DA_ISSUANCE, 0);
    wantIssue(10, 0, 1, BR_ASSET_AGGREGATABLE);
    wantExact(1);
    wantAsset("6a08 44410305 0a 00 14 14", "a hybrid issuance", 3, 5, DA_ISSUANCE, 1);
    wantIssue(10, 0, 1, BR_ASSET_HYBRID);
    wantInst(0, 0, 0, 0, 0, 0, 20);
    wantExact(1);

    wantAsset("6a0b4441031500018060f423f0", "1 to output 0, then a skip of 999999", 3, 0x15, DA_TRANSFER, 2);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantInst(1, 1, 0, 0, 0, 0, 999999);
    wantExact(1);
    wantAsset("6a0744410315012012", "100 to output 1", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 1, 100);
    wantExact(1);
    wantAsset("6a0744410315002016", "1000000 to output 0", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 1000000);
    wantExact(1);
    wantAsset("6a09444103158201002644", "a skip of 1, then 1000000 to output 0", 3, 0x15, DA_TRANSFER, 2);
    wantInst(0, 1, 0, 0, 0, 2, 1);
    wantInst(1, 0, 0, 0, 0, 0, 1000000);
    wantExact(1);
    wantAsset("6a0844410315810a000a", "a skip of 10, then 10 to output 0", 3, 0x15, DA_TRANSFER, 2);
    wantInst(0, 1, 0, 0, 0, 1, 10);
    wantInst(1, 0, 0, 0, 0, 0, 10);
    wantExact(1);
    wantAsset("6a06444103150005", "5 to output 0", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 5);
    wantExact(1);
}

static void exact_refuses_trailing(void)
{
    wantAsset("6a0644410315000500", "5 to output 0, and a byte after the push", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 5);
    wantExact(0);
    wantAsset("6a0644410215000a0301", "asset_script_framing_kat's trailing bytes", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 10);
    wantExact(0);
    wantAsset("6a4c0644410215000a00", "the same behind OP_PUSHDATA1, and a byte after", 2, 0x15, DA_TRANSFER, 1);
    wantExact(0);
}

static void exact_truncated_transfer(void)
{
    wantUncl("6a05 44410315 00", "a transfer instruction with no amount");
}

// AssetHoldCoversCreditedOutputsTest: every output an instruction can name, range or not, in each
// operation, decodes to one instruction of 1 and reads exactly.
static void exact_hold_covers(void)
{
    static const char *const five[] = { "6a06444103151f01", "6a06444103151e01", "6a06444103251f01",
                                        "6a0844410305011f0100", "6a0844410305011e0100" };
    static const int32_t outs[] = { 31, 30, 31, 31, 30 };
    char hex[40];
    size_t i;
    int out, range, op, bad = 0, count = 0;

    for (i = 0; i < N(five); i++) {
        dec(five[i], five[i]);
        ck(K == BR_ASSET_SCRIPT_ASSET && H.instructionCount == 1 && L[0].outputIndex == outs[i] && L[0].amount == 1 &&
           L[0].isBurn == (i == 2), "%s: one instruction, 1 to output %d%s", five[i], (int)outs[i], (i == 2) ? ", a burn" : "");
        wantExact(1);
    }

    for (out = 0; out <= 31; out++) {
        for (range = 0; range <= 1; range++) {
            for (op = 0; op < 3; op++) {
                char inst[8];
                int n = range ? 2 : 1, burn = (op == 1 && ! range && out == 31);

                if (range) sprintf(inst, "%02x%02x", 0x40 | (out >> 8), out & 0xff);
                else sprintf(inst, "%02x", out);
                if (op == 0) sprintf(hex, "6a%02x44410315%s01", 5 + n, inst);
                else if (op == 1) sprintf(hex, "6a%02x44410325%s01", 5 + n, inst);
                else sprintf(hex, "6a%02x4441030501%s0100", 7 + n, inst);
                SL = hx(hex, S, sizeof(S));
                count++;

                if (decodeBoth() != NULL || K != BR_ASSET_SCRIPT_ASSET || H.instructionCount != 1 || L[0].outputIndex != out ||
                    L[0].amount != 1 || L[0].range != range || L[0].isBurn != burn || L[0].skip || L[0].percent ||
                    BRAssetPayloadReadsExactly(S, SL, &H, L, 1) != 1 || mExact(S, SL, &H, L, 1) != 1) {
                    if (! bad) ck(0, "%s: want one instruction (range %d, output %d, amount 1, burn %d) that reads exactly", hex, range, out, burn);
                    bad++;
                }
            }
        }
    }

    ck(bad == 0, "%d scripts (outputs 0..31, range or not, transfer / burn / issuance): one instruction each, read exactly (%d not)", count, bad);
}

static void exact_unbacked(void)   // UnbackedAssetCreditTest
{
    BRAssetHeader h;

    // The instruction's amount is read through the flags byte: the decoder credits it, the re-read does not.
    wantAsset("6a09 44410305 2016 01 20 10", "an issuance whose instruction ends in the flags byte", 3, 5, DA_ISSUANCE, 1);
    wantIssue(1000000, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 1, 1);
    wantExact(0);

    // The test's hand-built header: version 3, opcode 3, an issuance of 5, locked, no instruction.
    memset(&h, 0, sizeof(h));
    h.kind = BR_ASSET_SCRIPT_ASSET; h.version = 3; h.opcode = 3; h.operation = DA_ISSUANCE;
    h.hasTotalQuantity = 1; h.totalQuantity = 5; h.locked = 1; h.aggregation = BR_ASSET_AGGREGATABLE;
    SL = hx("6a07 44410303 05 0010", S, sizeof(S));
    ck(BRAssetPayloadReadsExactly(S, SL, &h, NULL, 0) == 0 && mExact(S, SL, &h, NULL, 0) == 0,
       "a rules issuance built by hand does not read exactly");
}

static void exact_rules_and_pre_v3(void)
{
    wantAsset("6a26 44410303 1111111111111111111111111111111111111111111111111111111111111111 05 10",
              "an issuance with rules (opcode 3)", 3, 3, DA_ISSUANCE, 0);
    wantIssue(5, 0, 1, BR_ASSET_AGGREGATABLE);
    wantExact(0);
    wantAsset("6a26 44410304 1111111111111111111111111111111111111111111111111111111111111111 05 10",
              "an issuance with rules (opcode 4)", 3, 4, DA_ISSUANCE, 0);
    wantExact(0);
    wantAsset("6a06 44410205 05 10", "a version 2 issuance", 2, 5, DA_ISSUANCE, 0);
    wantIssue(5, 0, 1, BR_ASSET_AGGREGATABLE);
    wantExact(0);
    wantAsset("6a06 44410105 05 10", "a version 1 issuance", 1, 5, DA_ISSUANCE, 0);
    wantExact(0);
    wantAsset("6a06 44410305 05 10", "the version 3 issuance without rules", 3, 5, DA_ISSUANCE, 0);
    wantExact(1);
    wantAsset("6a06 4441021500 05", "a version 2 transfer is re-read: only issuances are held to version 3", 2, 0x15, DA_TRANSFER, 1);
    wantExact(1);
}

static void exact_header_mismatch(void)
{
    BRAssetHeader h;
    BRAssetTransferInstruction l[3];
    uint8_t other[16];
    size_t n;

    wantAsset("6a0844410315810a000a", "a skip of 10, then 10 to output 0", 3, 0x15, DA_TRANSFER, 2);
    wantExact(1);
    h = H; h.version = 2;
    ck(BRAssetPayloadReadsExactly(S, SL, &h, L, 2) == 0, "  another version in the header: 0");
    h = H; h.opcode = 0x25;
    ck(BRAssetPayloadReadsExactly(S, SL, &h, L, 2) == 0, "  another opcode: 0");
    ck(BRAssetPayloadReadsExactly(S, SL, &H, L, 1) == 0, "  a list one shorter: 0");
    ck(BRAssetPayloadReadsExactly(S, SL, &H, L, 0) == 0 && BRAssetPayloadReadsExactly(S, SL, &H, NULL, 0) == 0, "  an empty list: 0");
    l[0] = L[0]; l[1] = L[1]; l[2] = L[1];
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 3) == 0, "  a list one longer: 0");
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 1, "  a copy of the list: 1");
    l[1].amount = 11;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  another amount: 0");
    l[1] = L[1]; l[1].outputIndex = 1;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  another output: 0");
    l[1] = L[1]; l[0].skip = 0;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  a skip read as a transfer: 0");
    l[0] = L[0]; l[1].range = 1;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  a range flag the script does not have: 0");
    l[1] = L[1]; l[1].percent = 1;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  a percent flag the script does not have: 0");
    l[1] = L[1]; l[1].isBurn = 1;
    ck(BRAssetPayloadReadsExactly(S, SL, &H, l, 2) == 0, "  a burn flag the script does not have: 0");
    ck(BRAssetPayloadReadsExactly(NULL, SL, &H, L, 2) == 0 && BRAssetPayloadReadsExactly(S, SL, NULL, L, 2) == 0 &&
       BRAssetPayloadReadsExactly(S, SL, &H, NULL, 2) == 0, "  no script, no header, or no list where one is counted: 0");
    ck(BRAssetPayloadReadsExactly(S, SL - 1, &H, L, 2) == 0 && BRAssetPayloadReadsExactly(S, 1, &H, L, 2) == 0 &&
       BRAssetPayloadReadsExactly(S, 0, &H, L, 2) == 0, "  the script cut short: 0");

    n = hx("6a0644420315000a", other, sizeof(other));
    h = H;
    ck(BRAssetPayloadReadsExactly(other, n, &h, L, 0) == 0, "  another tag: 0");
    n = hx("6a00", other, sizeof(other));
    ck(BRAssetPayloadReadsExactly(other, n, &h, L, 0) == 0, "  an empty push: 0");
    n = hx("6a4d060044410315000a", other, sizeof(other));
    ck(BRAssetPayloadReadsExactly(other, n, &h, L, 0) == 0, "  OP_PUSHDATA2: 0");
    n = hx("6a4c", other, sizeof(other));
    ck(BRAssetPayloadReadsExactly(other, n, &h, L, 0) == 0, "  OP_PUSHDATA1 without its length: 0");
    n = hx("510644410315000a", other, sizeof(other));
    ck(BRAssetPayloadReadsExactly(other, n, &h, L, 0) == 0, "  not OP_RETURN: 0");

    wantAsset("6a4c0644410215000a", "asset_unclassifiable_carrier_kat's OP_PUSHDATA1 transfer", 2, 0x15, DA_TRANSFER, 1);
    wantExact(1);

    wantAsset("6a08 44410305 0a 00 04 10", "an issuance of 10", 3, 5, DA_ISSUANCE, 1);
    h = H; h.totalQuantity = 11;
    ck(BRAssetPayloadReadsExactly(S, SL, &h, L, 1) == 0, "  another quantity in the header: 0");
    h = H; h.hasTotalQuantity = 0;
    ck(BRAssetPayloadReadsExactly(S, SL, &h, L, 1) == 0, "  a header with no quantity: 0");
    h = H; h.operation = DA_TRANSFER;   // then the amount byte is read as an instruction, and nothing lines up
    ck(BRAssetPayloadReadsExactly(S, SL, &h, L, 1) == 0 && mExact(S, SL, &h, L, 1) == 0, "  the issuance read as a transfer: 0");
}

// ---- the scripts of the asset_*_kat suites beside this one -----------------------------------

static void kat_burn_marker(void)   // asset_burn_marker_kat
{
    wantAsset("6a06444102151f01", "kTransfer31", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 31, 1);
    wantAsset("6a0844410205011f0100", "kIssuance31", 2, 5, DA_ISSUANCE, 1);
    wantIssue(1, 0, 0, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 31, 1);
    wantAsset("6a06444102251f01", "kBurn31", 2, 0x25, DA_BURN, 1);
    wantInst(0, 0, 0, 0, 1, 31, 1);
    wantAsset("6a08444102251f010101", "kBurnAndSend", 2, 0x25, DA_BURN, 2);
    wantInst(0, 0, 0, 0, 1, 31, 1);
    wantInst(1, 0, 0, 0, 0, 1, 1);
}

static void kat_classified(void)
{
    static const int ends[] = { 0, 1, 2, 31, 32, 255, 8191 };
    char hex[2 * 80 + 1];
    size_t i, n;
    int all = 1;

    wantAsset("6a084441031500010209", "asset_conflicted_send_kat", 3, 0x15, DA_TRANSFER, 2);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantInst(1, 0, 0, 0, 0, 2, 9);
    wantAsset("6a0644410115000a", "asset_implicit_change_kat (version 1)", 1, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 10);
    wantNot("6a04deadbeef", "asset_plain_opreturn_first_kat's plain OP_RETURN");
    wantAsset("6a0644410215020a", "asset_plain_opreturn_first_kat's carrier", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 2, 10);

    for (i = 0; i < N(ends); i++) {   // asset_range_targets_kat
        sprintf(hex, "6a0744410215%02x%02x01", 0x40 | (ends[i] >> 8), ends[i] & 0xff);
        wantAsset(hex, "asset_range_targets_kat", 2, 0x15, DA_TRANSFER, 1);
        wantInst(0, 0, 1, 0, 0, ends[i], 1);
    }

    wantAsset("6a0c4441021503e0000000000001", "asset_script_bounds_kat: 2^53 + 1 in the 7-byte form", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 3, 9007199254740993LL);
    wantAsset("6a0e4441021503e00000000000010201", "  and an instruction after it", 2, 0x15, DA_TRANSFER, 2);
    wantInst(0, 0, 0, 0, 0, 3, 9007199254740993LL);
    wantInst(1, 0, 0, 0, 0, 2, 1);
    wantAsset("6a0c4441021503c0000000000001", "  the 7-byte form whose third bit is 0", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 3, 1);

    // asset_script_framing_kat: a 76-byte payload (36 instructions of 1) behind OP_PUSHDATA1
    n = (size_t)sprintf(hex, "6a4c4c44410215");
    for (i = 0; i < 36; i++) n += (size_t)sprintf(hex + n, "%02x01", (i == 0) ? 3 : 7);
    wantAsset(hex, "asset_script_framing_kat: 76 bytes behind OP_PUSHDATA1", 2, 0x15, DA_TRANSFER, 36);
    for (i = 0; i < 36 && K == BR_ASSET_SCRIPT_ASSET; i++) if (L[i].outputIndex != ((i == 0) ? 3 : 7) || L[i].amount != 1 || L[i].isBurn) all = 0;
    ck(all, "  1 to output 3, then 35 times 1 to output 7");
    wantExact(1);
    // the same payload after a single byte 4c: that byte is OP_PUSHDATA1, and the tag is not where a push would start
    n = (size_t)sprintf(hex, "6a4c44410215");
    for (i = 0; i < 36; i++) n += (size_t)sprintf(hex + n, "%02x01", (i == 0) ? 3 : 7);
    wantNot(hex, "asset_script_framing_kat: 76 bytes behind one length byte");
    wantAsset("6a0644410215000a0301", "asset_script_framing_kat: bytes after the push are not read", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 10);

    wantAsset("6a4c0644410215000a", "asset_unclassifiable_carrier_kat: OP_PUSHDATA1", 2, 0x15, DA_TRANSFER, 1);
    wantInst(0, 0, 0, 0, 0, 0, 10);
    wantAsset("6a28 44410301 1111111111111111111111111111111111111111111111111111111111111111 01 0001 10",
              "asset_unclassifiable_carrier_kat: a version 3 issuance", 3, 1, DA_ISSUANCE, 1);
    wantIssue(1, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantCid(1, "bafkreiarceirceirceirceirceirceirceirceirceirceirceirceirce");
    wantExact(1);
}

static void kat_unclassifiable(void)
{
    char big[2 * 261 + 1];
    size_t i, n;

    wantUncl("6a4d060044410215000a", "kPushdata2");
    wantUncl("6a4e0600000044410215000a", "kPushdata4");
    wantUncl("6a4d060044410215020a", "asset_plain_opreturn_first_kat's OP_PUSHDATA2 carrier");
    wantNot("6a4d060068656c6c6f21", "kPlainPd2");
    n = (size_t)sprintf(big, "6a4d010144410215002001");
    for (i = 0; i < 125; i++) n += (size_t)sprintf(big + n, "0001");
    wantUncl(big, "a 257-byte OP_PUSHDATA2 carrier");
    wantUncl("6a024441", "kTagOnly");
    wantUncl("6a0844410215000a", "kOverrun: a push of 8 with 6 bytes");
    wantUncl("6a0644410216000a", "kBadOpcode");
    wantUncl("6a0644410015000a", "kVersion0");
    wantUncl("6a06444102150020", "kCutAmount");
    wantUncl("6a0544410205e0", "asset_script_bounds_kat: an issuance amount cut");
    wantUncl("6a054441020100", "asset_script_bounds_kat: metadata cut");
    wantUncl("6a054441021503", "asset_script_bounds_kat: an instruction with no amount");
}

// ---- this suite's own -----------------------------------------------------------------------

static void issuance_flags(void)
{
    wantUncl("6a06 44410305 01 0c", "aggregation 3");
    wantAsset("6a06 44410305 01 e0", "divisibility 7", 3, 5, DA_ISSUANCE, 0);
    wantIssue(1, 7, 0, BR_ASSET_AGGREGATABLE);
    wantAsset("6a06 44410305 01 f8", "every flag bit but the low two: divisibility 7, locked, dispersed", 3, 5, DA_ISSUANCE, 0);
    wantIssue(1, 7, 1, BR_ASSET_DISPERSED);
    wantAsset("6a06 44410305 01 03", "the low two bits mean nothing", 3, 5, DA_ISSUANCE, 0);
    wantIssue(1, 0, 0, BR_ASSET_AGGREGATABLE);

    // version 1 wrote the amount scaled by 10^divisibility; later versions did not
    wantAsset("6a07 44410105 2012 40", "version 1: 100 at divisibility 2", 1, 5, DA_ISSUANCE, 0);
    wantIssue(1, 2, 0, BR_ASSET_AGGREGATABLE);
    wantExact(0);
    wantAsset("6a07 44410105 2012 00", "version 1 at divisibility 0", 1, 5, DA_ISSUANCE, 0);
    wantIssue(100, 0, 0, BR_ASSET_AGGREGATABLE);
    wantAsset("6a07 44410205 2012 40", "version 2: not rescaled", 2, 5, DA_ISSUANCE, 0);
    wantIssue(100, 2, 0, BR_ASSET_AGGREGATABLE);
    wantAsset("6a06 44410105 05 e0", "version 1: 5 at divisibility 7 rounds down to 0", 1, 5, DA_ISSUANCE, 0);
    wantIssue(0, 7, 0, BR_ASSET_AGGREGATABLE);
    wantAsset("6a07 44410105 2155 f0", "version 1: 2100000 at divisibility 7", 1, 5, DA_ISSUANCE, 0);
    wantIssue(0, 7, 1, BR_ASSET_AGGREGATABLE);

    // version 2 opcode 2 steps over 20 bytes; opcode 2 has no metadata hash
    wantAsset("6a1a 44410202 0102030405060708090a0b0c0d0e0f1011121314 05 10", "version 2 opcode 2", 2, 2, DA_ISSUANCE, 0);
    wantIssue(5, 0, 1, BR_ASSET_AGGREGATABLE);
    wantCid(0, NULL);
    wantAsset("6a06 44410302 05 10", "version 3 opcode 2 has no 20 bytes", 3, 2, DA_ISSUANCE, 0);
    wantIssue(5, 0, 1, BR_ASSET_AGGREGATABLE);
    wantUncl("6a17 44410202 0102030405060708090a0b0c0d0e0f10111213", "version 2 opcode 2 with 19 of its 20 bytes");
    wantUncl("6a18 44410202 0102030405060708090a0b0c0d0e0f1011121314", "version 2 opcode 2 with its 20 bytes and no amount");
    // 19 bytes and two more: the 20 are stepped over wherever they end, and the last byte is amount and flags
    wantAsset("6a19 44410202 0102030405060708090a0b0c0d0e0f10111213 05 10", "version 2 opcode 2: the 20 bytes are not looked at", 2, 2, DA_ISSUANCE, 0);
    wantIssue(16, 0, 1, BR_ASSET_AGGREGATABLE);
}

static void issuance_lenient_stream(void)
{
    // The amount of the last instruction is read from the flags byte: 0x10 is 16.
    wantAsset("6a07 44410305 0a 00 10", "an instruction whose amount is the flags byte", 3, 5, DA_ISSUANCE, 1);
    wantIssue(10, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 16);
    wantExact(0);
    // The issued amount itself is the flags byte.
    wantAsset("6a05 44410305 10", "an issuance whose amount is the flags byte", 3, 5, DA_ISSUANCE, 0);
    wantIssue(16, 0, 1, BR_ASSET_AGGREGATABLE);
    wantExact(0);
    // An amount that does not read ends the list and keeps what was read.
    wantAsset("6a0a 44410305 0a 0001 01 60 10", "an amount cut short after one instruction", 3, 5, DA_ISSUANCE, 1);
    wantIssue(10, 0, 1, BR_ASSET_AGGREGATABLE);
    wantInst(0, 0, 0, 0, 0, 0, 1);
    wantExact(0);
    // A percent byte the payload does not have empties the list.
    wantAsset("6a09 44410305 01 0001 60 10", "a percent instruction without its byte", 3, 5, DA_ISSUANCE, 0);
    wantIssue(1, 0, 1, BR_ASSET_AGGREGATABLE);
    wantExact(0);
    // A percent instruction that is all there.
    wantAsset("6a0a 44410305 0a 2132 0001 10", "a percent instruction, then 1 to output 0", 3, 5, DA_ISSUANCE, 2);
    wantInst(0, 0, 0, 1, 0, 1, 50);
    wantInst(1, 0, 0, 0, 0, 0, 1);
    wantExact(1);
    wantAsset("6a04 44410315", "a transfer with no instruction", 3, 0x15, DA_TRANSFER, 0);
    wantExact(1);
    wantAsset("6a07 44410315 e00a ff", "a transfer: skip, range and percent set, 255 percent", 3, 0x15, DA_TRANSFER, 1);
    wantInst(0, 1, 1, 1, 0, 10, 255);
    wantExact(1);
}

static void list_capacity(void)
{
    static BRAssetTransferInstruction l[BR_ASSET_MAX_INSTRUCTIONS + 1];
    BRAssetHeader h;
    char hex[2 * 257 + 1];
    size_t i, n;
    int all = 1;

    n = (size_t)sprintf(hex, "6a4cfe44410315");   // the longest list a classified script carries
    for (i = 0; i < 125; i++) n += (size_t)sprintf(hex + n, "0001");
    wantAsset(hex, "254 bytes behind OP_PUSHDATA1", 3, 0x15, DA_TRANSFER, BR_ASSET_MAX_INSTRUCTIONS);
    wantExact(1);

    memset(l, 0x5a, sizeof(l));
    ck(BRAssetDecodeInstructions(S, SL, &h, l, BR_ASSET_MAX_INSTRUCTIONS) == BR_ASSET_SCRIPT_ASSET &&
       h.instructionCount == BR_ASSET_MAX_INSTRUCTIONS, "  BR_ASSET_MAX_INSTRUCTIONS entries hold it");
    for (i = 0; i < BR_ASSET_MAX_INSTRUCTIONS; i++) if (l[i].outputIndex != 0 || l[i].amount != 1 || l[i].skip || l[i].isBurn) all = 0;
    ck(all && l[BR_ASSET_MAX_INSTRUCTIONS].amount == 0x5a5a5a5a5a5a5a5aLL, "  125 times 1 to output 0, and nothing written past them");

    memset(l, 0x5a, sizeof(l));
    ck(BRAssetDecodeInstructions(S, SL, &h, l, BR_ASSET_MAX_INSTRUCTIONS - 1) == BR_ASSET_SCRIPT_UNCLASSIFIABLE &&
       h.kind == BR_ASSET_SCRIPT_UNCLASSIFIABLE && h.version == 0 && h.operation == DA_UNDEFINED && h.instructionCount == 0 &&
       l[BR_ASSET_MAX_INSTRUCTIONS - 1].amount == 0x5a5a5a5a5a5a5a5aLL,
       "  a buffer one entry short: unclassifiable, never a shortened list, and nothing written past the buffer");
    ck(BRAssetDecodeInstructions(S, SL, &h, NULL, 0) == BR_ASSET_SCRIPT_UNCLASSIFIABLE && h.instructionCount == 0 &&
       BRAssetDecodeInstructions(S, SL, &h, NULL, 99) == BR_ASSET_SCRIPT_UNCLASSIFIABLE, "  no buffer: unclassifiable");
    ck(BRAssetDecodeHeader(S, SL, &h) == BR_ASSET_SCRIPT_ASSET && h.instructionCount == BR_ASSET_MAX_INSTRUCTIONS,
       "  the header alone still counts 125");

    SL = hx("6a06 44410305 0a 10", S, sizeof(S));
    ck(BRAssetDecodeInstructions(S, SL, &h, NULL, 0) == BR_ASSET_SCRIPT_ASSET && h.instructionCount == 0 && h.totalQuantity == 10,
       "a script with no instruction needs no buffer");
}

static void carrier_offsets(void)
{
    size_t off = 77, len = 77;

    SL = hx("6a0744410315002052", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_ASSET && off == 2 && len == 7, "a direct push: the payload is 7 bytes from 2");
    SL = hx("6a4c0644410215000a", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_ASSET && off == 3 && len == 6, "OP_PUSHDATA1: 6 bytes from 3");
    SL = hx("6a0644410215000a0301", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_ASSET && off == 2 && len == 6, "bytes after the push are not payload");
    ck(BRAssetDecodeCarrier(S, SL, NULL, NULL) == BR_ASSET_SCRIPT_ASSET, "  and neither out pointer is needed");
    off = len = 77; SL = hx("6a0644410216000a", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_ASSET && off == 2 && len == 6, "a payload that does not decode is still a carrier with a payload");
    off = len = 77; SL = hx("6a034441ff", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_UNCLASSIFIABLE && off == 0 && len == 0, "a 3-byte tagged push: unclassifiable, no payload");
    off = len = 77; SL = hx("6a04deadbeef", S, sizeof(S));
    ck(BRAssetDecodeCarrier(S, SL, &off, &len) == BR_ASSET_SCRIPT_NOT_ASSET && off == 0 && len == 0, "not a carrier: no payload");
    wantUncl("6a4effffffff4441", "OP_PUSHDATA4 with the largest length");
    wantUncl("6a4c0344410315", "OP_PUSHDATA1 of 3 bytes, tagged");
    wantUncl("6a4cff44410315000a", "OP_PUSHDATA1 that says 255 bytes and has 6");
    wantNot("6a4c0144", "OP_PUSHDATA1 of one byte");
    wantNot("6a4c", "OP_PUSHDATA1 and nothing");
    wantNot("6a4f024441", "push opcode 0x4f");
    wantNot("6a0244", "a tag cut after its first byte");
    wantNot("6a014441", "a push of one byte, though the tag bytes follow");
    wantNot("6b0644410215000a", "not OP_RETURN");
    wantNot("6a4d0600444202150005", "OP_PUSHDATA2 with another tag");
}

static void arguments(void)
{
    BRAssetHeader h;
    BRAssetTransferInstruction l[2];
    BRAssetBitReader r;
    char cid[BR_ASSET_CID_BUFFER + 4];
    uint64_t v = 0;
    size_t off = 5, len = 5;

    memset(&h, 0x5a, sizeof(h));
    ck(BRAssetDecodeHeader(NULL, 9, &h) == BR_ASSET_SCRIPT_NOT_ASSET && h.kind == BR_ASSET_SCRIPT_NOT_ASSET && h.version == 0 &&
       h.instructionCount == 0, "no script: not an asset, and the header is cleared");
    ck(BRAssetDecodeInstructions(NULL, 9, &h, l, 2) == BR_ASSET_SCRIPT_NOT_ASSET &&
       BRAssetDecodeCarrier(NULL, 9, &off, &len) == BR_ASSET_SCRIPT_NOT_ASSET && off == 0 && len == 0, "  the same from every entry");
    SL = hx("6a0744410315002052", S, sizeof(S));
    ck(BRAssetDecodeHeader(S, SL, NULL) == BR_ASSET_SCRIPT_ASSET && BRAssetDecodeInstructions(S, SL, NULL, l, 2) == BR_ASSET_SCRIPT_ASSET &&
       l[0].amount == 500, "no header to fill: the kind is still returned");
    ck(BRAssetHeaderHasRules(NULL) == 0, "no header has no rules");

    dec("6a2a44410301d66f1117234767d6892700d575511e1effcd6471013dda0e832dbb67e629d674215500215550", "ISSUANCE_V3_LOCKED");
    memset(cid, 'x', sizeof(cid));
    ck(BRAssetMetadataCid(&H, cid, BR_ASSET_CID_BUFFER) == BR_ASSET_CID_LENGTH && cid[BR_ASSET_CID_LENGTH] == '\0' &&
       cid[BR_ASSET_CID_BUFFER] == 'x', "a CID fills exactly BR_ASSET_CID_BUFFER bytes");
    memset(cid, 'x', sizeof(cid));
    ck(BRAssetMetadataCid(&H, cid, BR_ASSET_CID_BUFFER - 1) == 0 && cid[0] == '\0' && cid[1] == 'x', "a buffer one byte short: no CID, an empty string");
    ck(BRAssetMetadataCid(&H, NULL, 0) == 0 && BRAssetMetadataCid(&H, NULL, 99) == 0 && BRAssetMetadataCid(NULL, cid, sizeof(cid)) == 0 &&
       cid[0] == '\0', "no buffer or no header: no CID");
    ck(BRAssetMetadataCid(&H, cid, 0) == 0, "a buffer of no bytes is not written");

    ck(BRAssetBitsRemaining(NULL) == 0 && BRAssetBitsRead(NULL, 1, &v) == 0 && BRAssetBitsSkip(NULL, 0) == 0 &&
       BRAssetBitsReadFixedPrecision(NULL, NULL) == 0, "no reader: nothing is read");
    r = BRAssetBitReaderMake(S, SL);
    ck(BRAssetBitsRead(&r, 16, NULL) == 1 && BRAssetBitsRemaining(&r) == SL * 8 - 16 && BRAssetBitsReadBytes(&r, NULL, 1) == 0 &&
       BRAssetBitsReadBytes(&r, NULL, 0) == 1 && BRAssetPow10(3, NULL) == 1, "an out pointer that is not wanted may be NULL");
    ck(asset_decode_kat_tu2() == 1, "the header included twice in a second translation unit answers the same");
}

// ---- model_sweep: the header against the model over a fixed corpus ---------------------------

static uint64_t RNG;

static uint32_t rnd(void)   // xorshift64: the same stream on every machine
{
    RNG ^= RNG << 13; RNG ^= RNG >> 7; RNG ^= RNG << 17;
    return (uint32_t)(RNG >> 24);
}

static uint32_t below(uint32_t n) { return rnd() % n; }

static BRAssetHeader PH;                        // the script before this one, as the header decoded it
static BRAssetTransferInstruction PL[CAP];
static unsigned long REACHED[3], EXACT[2], SWEPT, DIFFS;

static void showScript(const char *part, const char *what)
{
    char hex[2 * 96 + 1];
    size_t i;

    for (i = 0; i < SL && i < 96; i++) sprintf(hex + 2 * i, "%02x", S[i]);
    hex[2 * ((SL < 96) ? SL : 96)] = '\0';
    ck(0, "%s: %s; the script (%u bytes) is %s%s", part, what, (unsigned)SL, hex, (SL > 96) ? "..." : "");
}

// S[0, SL) through the header and the model: the decode, the re-read with its own header and list, and
// the re-read with the header and list of the script before it.
static void sweepOne(const char *part)
{
    const char *diff = decodeBoth();
    int own, other;

    SWEPT++;
    if (! diff && K == BR_ASSET_SCRIPT_ASSET && (H.version < 1 || H.version > 255 || H.divisibility < 0 || H.divisibility > 7 ||
                                                 H.totalQuantity < 0)) diff = "the fuzz test's invariants (version, divisibility, quantity)";
    if (! diff && H.instructionCount > BR_ASSET_MAX_INSTRUCTIONS) diff = "more instructions than BR_ASSET_MAX_INSTRUCTIONS";
    if (diff) { if (DIFFS++ < 3) showScript(part, diff); return; }
    REACHED[(int)K]++;

    own = BRAssetPayloadReadsExactly(S, SL, &H, L, (size_t)H.instructionCount);
    other = BRAssetPayloadReadsExactly(S, SL, &PH, PL, (size_t)PH.instructionCount);
    if (own != mExact(S, SL, &H, L, (size_t)H.instructionCount)) { if (DIFFS++ < 3) showScript(part, "the re-read with its own header differs from the model"); }
    else if (other != mExact(S, SL, &PH, PL, (size_t)PH.instructionCount)) { if (DIFFS++ < 3) showScript(part, "the re-read with another script's header differs from the model"); }
    if (K == BR_ASSET_SCRIPT_ASSET) EXACT[own ? 1 : 0]++;
    PH = H;
    memcpy(PL, L, sizeof(PL));
}

// One fixed precision amount of a random form into out; the byte count.
static size_t rndFixed(uint8_t *out)
{
    size_t len = 1 + below(7), i;

    for (i = 0; i < len; i++) out[i] = (uint8_t)rnd();
    if (len == 7) out[0] |= 0xc0;
    else out[0] = (uint8_t)((out[0] & 0x1f) | ((len - 1) << 5));
    if (len >= 2 && len <= 6 && below(2)) out[len - 1] &= (uint8_t)((len < 5) ? 0xf0 | below(4) : 0xf8 | below(3));   // a small exponent
    if (len >= 4 && below(3) == 0) for (i = 1; i + 1 < len; i++) out[i] = (i + 2 < len) ? 0 : out[i];                  // a small mantissa
    return len;
}

// A payload built the way an encoder would, of a random operation; its length.
static size_t rndPayload(uint8_t *p)
{
    static const uint8_t ops[] = { 1, 2, 3, 4, 5, 0x15, 0x15, 0x15, 0x25, 0x25 };
    size_t n = 0, i, count = below(7);
    uint8_t version = (uint8_t)(1 + below(3)), opcode = ops[below((uint32_t)N(ops))];
    int issuance = (opcode <= 5);

    p[n++] = 0x44; p[n++] = 0x41; p[n++] = version; p[n++] = opcode;
    if (version < 3 && opcode < 3) for (i = 0; i < 20; i++) p[n++] = (uint8_t)rnd();
    if (opcode == 1 || opcode == 3 || opcode == 4) { int zero = (below(4) == 0); for (i = 0; i < 32; i++) p[n++] = zero ? 0 : (uint8_t)rnd(); }
    if (issuance) n += rndFixed(p + n);

    for (i = 0; i < count; i++) {
        int range = (below(4) == 0), percent = (below(6) == 0), skip = (below(5) == 0);
        uint32_t out = range ? below(8192) : (below(3) == 0 ? 31 : below(32));

        if (range) { p[n++] = (uint8_t)((skip << 7) | 0x40 | (percent << 5) | (out >> 8)); p[n++] = (uint8_t)out; }
        else p[n++] = (uint8_t)((skip << 7) | (percent << 5) | out);
        if (percent) p[n++] = (uint8_t)rnd();
        else n += rndFixed(p + n);
    }

    if (issuance) p[n++] = (uint8_t)((below(8) == 0) ? rnd() | 0x0c : rnd() & ~0x0cu) | (uint8_t)(below(3) << 2);
    return n;
}

// The payload behind a push of the given kind (0 direct, 1 OP_PUSHDATA1, 2 OP_PUSHDATA2, 3 OP_PUSHDATA4)
// that declares `said` bytes, into S.
static void frame(const uint8_t *p, size_t len, int kind, size_t said)
{
    size_t n = 0;

    S[n++] = 0x6a;
    if (kind == 0) S[n++] = (uint8_t)said;
    else if (kind == 1) { S[n++] = 0x4c; S[n++] = (uint8_t)said; }
    else if (kind == 2) { S[n++] = 0x4d; S[n++] = (uint8_t)said; S[n++] = (uint8_t)(said >> 8); }
    else { S[n++] = 0x4e; S[n++] = (uint8_t)said; S[n++] = (uint8_t)(said >> 8); S[n++] = (uint8_t)(said >> 16); S[n++] = (uint8_t)(said >> 24); }
    memcpy(S + n, p, len);
    SL = n + len;
}

static void model_sweep(void)
{
    static const uint8_t versions[] = { 0, 1, 1, 2, 2, 3, 3, 3 };
    static const uint8_t opcodes[] = { 1, 2, 3, 4, 5, 0x15, 0x15, 0x25, 0, 6, 0x16 };
    uint8_t p[300];
    unsigned long i, before, fixedRead = 0, fixedRefused = 0, fixedDiffs = 0;
    size_t n, k;

    memset(&PH, 0, sizeof(PH));
    memset(PL, 0, sizeof(PL));

    // A: bytes of any kind (seed: DigiAssetDecoderFuzzTest's first)
    RNG = 0xDA1AF1A7u; before = DIFFS;
    for (i = 0; i < 20000; i++) {
        SL = below(41);
        for (k = 0; k < SL; k++) S[k] = (uint8_t)rnd();
        if (SL > 0 && below(4)) S[0] = 0x6a;
        if (SL > 3 && below(2)) { S[1] = (uint8_t)(SL - 2); S[2] = 0x44; S[3] = (uint8_t)(below(8) ? 0x41 : 0x42); }
        sweepOne("A (random bytes)");
    }
    ck(DIFFS == before, "A: 20000 scripts of random bytes read as the model reads them");

    // B: a tag, a version, an opcode and random bytes behind every kind of push (the fuzz test's second seed)
    RNG = 0xFA77E55Eu; before = DIFFS;
    for (i = 0; i < 60000; i++) {
        int kind = (int)below(8);
        size_t said;

        kind = (kind < 4) ? 0 : (kind < 6) ? 1 : kind - 4;
        n = 0;
        p[n++] = 0x44; p[n++] = (uint8_t)(below(16) ? 0x41 : 0x42);
        p[n++] = below(8) ? versions[below((uint32_t)N(versions))] : (uint8_t)rnd();
        p[n++] = below(8) ? opcodes[below((uint32_t)N(opcodes))] : (uint8_t)rnd();
        for (k = below(61); k > 0; k--) p[n++] = (uint8_t)rnd();
        if (below(16) == 0) n = below(5);                                    // sometimes not even a header
        said = below(8) ? n : (below(2) ? n + 1 + below(3) : (n > 3 ? n - 1 - below(3) : below(300)));
        if (kind == 0 && said > 75) kind = 1;
        frame(p, n, kind, said);
        if (below(8) == 0) for (k = 1 + below(3); k > 0; k--) S[SL++] = (uint8_t)rnd();   // bytes after the push
        sweepOne("B (random payloads)");
    }
    ck(DIFFS == before, "B: 60000 tagged payloads of random bytes behind direct, PUSHDATA1, PUSHDATA2 and PUSHDATA4 pushes");

    // C: payloads as an encoder writes them, then whole, cut, lengthened or with one byte changed (the third seed)
    RNG = 0xC0FFEEu; before = DIFFS;
    for (i = 0; i < 60000; i++) {
        uint32_t how = below(8);
        int kind;

        n = rndPayload(p);
        if (how == 4 && n > 5) n -= 1 + below(3);
        else if (how == 5) for (k = 1 + below(3); k > 0; k--) p[n++] = (uint8_t)rnd();
        else if (how == 6) p[below((uint32_t)n)] = (uint8_t)rnd();
        else if (how == 7) p[4 + below((uint32_t)(n - 4 > 0 ? n - 4 : 1)) % n] ^= (uint8_t)(1u << below(8));
        kind = (n > 75) ? 1 : (below(4) == 0);
        if (below(40) == 0) kind = 2 + (int)below(2);
        frame(p, n, kind, n);
        if (below(20) == 0) S[SL++] = (uint8_t)rnd();
        sweepOne("C (encoded payloads)");
    }
    ck(DIFFS == before, "C: 60000 encoded issuances, transfers and burns, whole and damaged");

    ck(SWEPT == 140000 && REACHED[BR_ASSET_SCRIPT_NOT_ASSET] > 1000 && REACHED[BR_ASSET_SCRIPT_UNCLASSIFIABLE] > 1000 &&
       REACHED[BR_ASSET_SCRIPT_ASSET] > 1000 && EXACT[0] > 1000 && EXACT[1] > 1000,
       "the corpus reaches every answer: %lu not an asset, %lu unclassifiable, %lu decoded (%lu of them read exactly, %lu not)",
       REACHED[BR_ASSET_SCRIPT_NOT_ASSET], REACHED[BR_ASSET_SCRIPT_UNCLASSIFIABLE], REACHED[BR_ASSET_SCRIPT_ASSET], EXACT[1], EXACT[0]);

    // D: one amount from any bit position, against both of the Kotlin's readers
    RNG = 0xB17B17u;
    for (i = 0; i < 40000; i++) {
        BRAssetBitReader r;
        MBits a, b;
        uint8_t d[10];
        size_t len = below(8) ? rndFixed(d) : 1 + below(9), skip = below(4) ? 0 : below(8);
        int64_t v = -1, va, vb;
        int ok;

        if (below(8) == 0) for (k = 0; k < len; k++) d[k] = (uint8_t)rnd();
        if (skip) { for (k = len; k > 0; k--) d[k] = (uint8_t)((d[k - 1] << (8 - skip)) | ((k < len) ? d[k] >> skip : 0)); d[0] = (uint8_t)(d[0] >> skip); len++; }
        if (below(6) == 0 && len > 1) len--;
        r = BRAssetBitReaderMake(d, len); a = mBits(d, len); b = mBits(d, len);
        (void)BRAssetBitsSkip(&r, skip); mSkip(&a, skip); mSkip(&b, skip);
        ok = BRAssetBitsReadFixedPrecision(&r, &v);
        va = mFixedA(&a); vb = mFixedB(&b);

        if (ok != ! a.threw || ok != ! b.threw || (ok && (v != va || v != vb || r.position != a.pos || r.position != b.pos)) || (! ok && v != -1)) {
            if (fixedDiffs++ < 3) ck(0, "D: an amount %u bits into %02x%02x%02x%02x... (%u bytes): header %d %lld, BitReader %d %lld, AssetPayloadCheck %d %lld",
                                     (unsigned)skip, d[0], d[1], d[2], d[3], (unsigned)len, ok, (long long)v, ! a.threw, (long long)va, ! b.threw, (long long)vb);
        }
        else if (ok) fixedRead++;
        else fixedRefused++;
    }
    ck(fixedDiffs == 0 && fixedRead > 1000 && fixedRefused > 1000,
       "D: 40000 amounts at every bit offset: %lu read, %lu refused, as BitReader and AssetPayloadCheck read them (%lu differ)",
       fixedRead, fixedRefused, fixedDiffs);
    ck(DIFFS == 0, "no script differs from the model (%lu do)", DIFFS);
}

// ---- main -----------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "decoder_transfer_v3", decoder_transfer_v3 }, { "decoder_issuance_locked", decoder_issuance_locked },
    { "decoder_burn_v3", decoder_burn_v3 }, { "decoder_issuance_dispersed", decoder_issuance_dispersed },
    { "decoder_transfer_skip", decoder_transfer_skip }, { "decoder_issuance_rules", decoder_issuance_rules },
    { "decoder_non_da", decoder_non_da }, { "decoder_mainnet_vectors", decoder_mainnet_vectors },
    { "decoder_tag_only_unclassifiable", decoder_tag_only_unclassifiable },
    { "decoder_pushdata_framing", decoder_pushdata_framing }, { "decoder_burn_marker", decoder_burn_marker },
    { "fuzz_not_asset", fuzz_not_asset }, { "fuzz_unclassifiable", fuzz_unclassifiable },
    { "fuzz_truncated_transfer", fuzz_truncated_transfer },
    { "bits_read", bits_read }, { "bits_fixed_precision", bits_fixed_precision },
    { "bits_fixed_precision_unaligned", bits_fixed_precision_unaligned }, { "bits_pow10", bits_pow10 },
    { "bits_short_reads", bits_short_reads },
    { "never_wraps_fixed_precision", never_wraps_fixed_precision }, { "never_wraps_carriers", never_wraps_carriers },
    { "exact_allocator_vectors", exact_allocator_vectors }, { "exact_refuses_trailing", exact_refuses_trailing },
    { "exact_truncated_transfer", exact_truncated_transfer }, { "exact_hold_covers", exact_hold_covers },
    { "exact_unbacked", exact_unbacked }, { "exact_rules_and_pre_v3", exact_rules_and_pre_v3 },
    { "exact_header_mismatch", exact_header_mismatch },
    { "kat_burn_marker", kat_burn_marker }, { "kat_classified", kat_classified }, { "kat_unclassifiable", kat_unclassifiable },
    { "issuance_flags", issuance_flags }, { "issuance_lenient_stream", issuance_lenient_stream },
    { "list_capacity", list_capacity }, { "carrier_offsets", carrier_offsets }, { "arguments", arguments },
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
