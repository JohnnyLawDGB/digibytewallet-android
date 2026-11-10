// raw_tx_binding_kat -- BRRawTxBinding.h (digibytewallet-core): the id of raw transaction bytes, moved from
// the JNI bridge (bridge/asset_tx_checks.h raw_tx_id), and the parent binding, the admit gate's binding part
// and the fee read-back, moved from Kotlin (core/.../recovery/RawTxBinding.kt, LegacySweepService.admit).
//
// What is compared:
//   THE ID    the bridge's raw_tx_id is compiled into this unit beside the core's BRRawTxId (both against the
//             live core parser) and the two must give the same answer -- reported or not, and the same id --
//             on fixed vectors, generated transactions (with and without witness data) and hostile bytes:
//             trailing bytes, every truncation, non-canonical varints, byte flips and random bytes.
//   THE RULES a model of RawTxBinding.kt written here as the Kotlin reads (txids and scripts as text, the
//             parent map looked up by key, the bridge's raw_tx_id as the id function, the bridge's output and
//             input listings parsed back as Kotlin parses them) is compared with the header on fuzzed
//             profiles and fuzzed signed transactions: same verdict, same coin, same reason, same proven
//             amounts and scripts, same held-back list, same fee.
//   THE TESTS RawTxBindingTest, AmountProvenanceGateTest's parent cases (plus the order of the amount
//             refusals before them) and SweepSignsProvenInputsSourceGateTest's intent, case for case, on real
//             transactions built with the core.
//   COST      large profiles (100k coins, 50k parents, a parent of 20k outputs, a fee over 20k inputs) must
//             finish well inside what a quadratic loop would take.
//
// Usage: raw_tx_binding_kat list | <case>. Prints PASS:/FAIL: lines and "RESULT <case> pass|fail".

#include "asset_tx_checks.h"   // the bridge's raw_tx_id, compiled as it ships
#include "BRRawTxBinding.h"
#include "BRRawTxBinding.h"    // twice: the include guard holds
#include "BRCrypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...) do { if (cond) g_pass++; else { g_fail++; printf("FAIL: "); printf(__VA_ARGS__); printf("\n"); } } while (0)

// ---- small helpers ---------------------------------------------------------------------------

static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;
static uint32_t rnd(void)
{
    g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17;
    return (uint32_t)(g_rng >> 16);
}

static size_t unhex(const char *hex, uint8_t *out)
{
    size_t n = strlen(hex) / 2, i;
    for (i = 0; i < n; i++) {
        unsigned v; sscanf(hex + 2 * i, "%2x", &v); out[i] = (uint8_t)v;
    }
    return n;
}

static void tohex(const uint8_t *b, size_t n, char *out)
{
    static const char d[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) { out[2 * i] = d[b[i] >> 4]; out[2 * i + 1] = d[b[i] & 15]; }
    out[2 * n] = 0;
}

static void display_hex(UInt256 h, char out[65])
{
    uint8_t r[32];
    for (int i = 0; i < 32; i++) r[i] = h.u8[31 - i];
    tohex(r, 32, out);
}

static UInt256 from_display(const char *hex)
{
    UInt256 u = UINT256_ZERO;
    if (!BRRawTxIdFromDisplayHex(hex, strlen(hex), &u)) { printf("FAIL: setup: bad txid %s\n", hex); g_fail++; }
    return u;
}

// ---- transactions built with the core --------------------------------------------------------

typedef struct { uint64_t amount; uint8_t script[64]; size_t scriptLen; } OutSpec;
typedef struct { UInt256 prev; uint32_t index; size_t sigLen; size_t witItems; } InSpec;

static uint32_t g_serial = 1;

// A signed-format transaction: the given inputs (dummy scriptSigs, optional witness items) and outputs.
// Returns malloc'd bytes and *len; *id is the parser's id (the bridge's raw_tx_id on them).
static uint8_t *build_tx(const InSpec *ins, size_t nin, const OutSpec *outs, size_t nout, size_t *len)
{
    BRTransaction *tx = BRTransactionNew();
    uint8_t sig[600], wit[700];
    for (size_t i = 0; i < nin; i++) {
        size_t wl = 0;
        for (size_t k = 0; k < ins[i].sigLen && k < sizeof(sig); k++) sig[k] = (uint8_t)(rnd() | 1);
        for (size_t w = 0; w < ins[i].witItems; w++) {
            size_t il = 1 + rnd() % 40;
            wit[wl++] = (uint8_t)il;
            for (size_t k = 0; k < il; k++) wit[wl++] = (uint8_t)rnd();
        }
        BRTransactionAddInput(tx, ins[i].prev, ins[i].index, 0, NULL, 0, sig, ins[i].sigLen,
                              ins[i].witItems ? wit : NULL, wl, 0xffffffff);
    }
    for (size_t i = 0; i < nout; i++) BRTransactionAddOutput(tx, outs[i].amount, outs[i].script, outs[i].scriptLen);
    tx->lockTime = g_serial++;   // every built transaction is distinct
    *len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *b = malloc(*len);
    BRTransactionSerialize(tx, b, *len);
    BRTransactionFree(tx);
    return b;
}

static UInt256 rand_hash(void)
{
    UInt256 u;
    for (int i = 0; i < 32; i++) u.u8[i] = (uint8_t)rnd();
    u.u8[0] |= 1;
    return u;
}

static uint8_t *rand_tx(size_t *len, int witness)
{
    InSpec ins[5]; OutSpec outs[5];
    size_t nin = 1 + rnd() % 4, nout = rnd() % 5;
    for (size_t i = 0; i < nin; i++) {
        ins[i].prev = rand_hash(); ins[i].index = rnd() % 4;
        ins[i].sigLen = (rnd() % 8 == 0) ? 253 + rnd() % 300 : rnd() % 120;
        ins[i].witItems = witness ? rnd() % 3 : 0;
    }
    if (witness && ins[0].witItems == 0) ins[0].witItems = 1;
    for (size_t i = 0; i < nout; i++) {
        outs[i].amount = (rnd() % 16 == 0) ? (UINT64_MAX - rnd()) : (uint64_t)(rnd() % 100000000);
        outs[i].scriptLen = rnd() % 40;
        for (size_t k = 0; k < outs[i].scriptLen; k++) outs[i].script[k] = (uint8_t)rnd();
    }
    return build_tx(ins, nin, outs, nout, len);
}

static UInt256 must_id(const uint8_t *b, size_t n)
{
    UInt256 id = UINT256_ZERO;
    if (!raw_tx_id(b, n, &id)) { printf("FAIL: setup: a built transaction has no id\n"); g_fail++; }
    return id;
}

// ---- fixed vectors ---------------------------------------------------------------------------

static const char *GENESIS_HEX =
    "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d"
    "0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f"
    "66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0"
    "fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c38"
    "4df7ba0b8d578a4c702b6bf11d5fac00000000";
static const char *GENESIS_TXID = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b";
static const char *GENESIS_SCRIPT =
    "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51e"
    "c112de5c384df7ba0b8d578a4c702b6bf11d5fac";

static const char *SEGWIT_HEX =
    "01000000000102fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000049483045"
    "0221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337f7a55d114c8e5cdd30be022040529b194ba3f9281a"
    "99f2b1c0a19c0489bc22ede944ccf4ecbab4cc618ef3ed01eeffffffef51e1b804cc89d182d279655c3aa89e815b"
    "1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f"
    "85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac"
    "000247304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c45"
    "18331561406f90300e8f3358f51928d43c212a8caed02de67eebee0121025476c2e83188368da1ff3e292e7acafc"
    "db3566bb0ad253f62fc70f07aeee635711000000";
static const char *SEGWIT_TXID = "e8151a2af31c368a35053ddd4bdb285a8595c769a3ad83e0fa02314a602d4609";
static const char *SEGWIT_SCRIPT0 = "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac";
static const char *SEGWIT_SCRIPT1 = "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac";

static uint8_t g_genesis[400], g_segwit[600];
static size_t g_genesisLen, g_segwitLen;

static void load_vectors(void)
{
    g_genesisLen = unhex(GENESIS_HEX, g_genesis);
    g_segwitLen = unhex(SEGWIT_HEX, g_segwit);
}

// ---- THE ID: bridge vs core ------------------------------------------------------------------

// Both answer the same for these bytes. Returns 1 when they agree.
static int same_id(const uint8_t *b, size_t n, const char *what)
{
    UInt256 x = UINT256_ZERO, y = UINT256_ZERO;
    int a = raw_tx_id(b, n, &x), c = BRRawTxId(b, n, &y);
    int ok = (a == c) && (!a || UInt256Eq(x, y));
    if (!ok) {
        char hx[65], hy[65]; display_hex(x, hx); display_hex(y, hy);
        printf("FAIL: %s (len %zu): bridge %d %s, core %d %s\n", what, n, a, a ? hx : "-", c, c ? hy : "-");
        g_fail++;
    } else g_pass++;
    return ok;
}

// Every variant of one serialization: exact, trailing bytes, every truncation, a non-canonical input
// count, and byte flips. Returns the number of disagreements.
static int id_variants(const uint8_t *b, size_t n, int flips)
{
    int bad = 0;
    uint8_t *v = malloc(n + 16);
    bad += !same_id(b, n, "exact");
    for (size_t t = 1; t <= 3; t++) {
        memcpy(v, b, n);
        for (size_t k = 0; k < t; k++) v[n + k] = (uint8_t)rnd();
        bad += !same_id(v, n + t, "trailing bytes");
    }
    for (size_t cut = 1; cut < n; cut++) bad += !same_id(b, cut, "truncated");
    // non-canonical input count: the 1-byte count (after the marker when there is one) as fd xx 00
    size_t at = (n > 6 && b[4] == 0 && b[5] == 1) ? 6 : 4;
    if (n > at && b[at] < 0xfd) {
        memcpy(v, b, at); v[at] = 0xfd; v[at + 1] = b[at]; v[at + 2] = 0; memcpy(v + at + 3, b + at + 1, n - at - 1);
        bad += !same_id(v, n + 2, "non-canonical input count");
        v[at] = 0xfe; v[at + 1] = b[at]; v[at + 2] = v[at + 3] = v[at + 4] = 0; memcpy(v + at + 5, b + at + 1, n - at - 1);
        bad += !same_id(v, n + 4, "non-canonical input count (fe)");
    }
    for (int f = 0; f < flips; f++) {
        memcpy(v, b, n);
        v[rnd() % n] ^= (uint8_t)(1u << (rnd() % 8));
        bad += !same_id(v, n, "byte flip");
    }
    free(v);
    return bad;
}

static void case_id_fixed_vectors(void)
{
    UInt256 id;
    char h[65];
    CHECK(BRRawTxId(g_genesis, g_genesisLen, &id) == 1, "genesis has an id");
    display_hex(id, h);
    CHECK(strcmp(h, GENESIS_TXID) == 0, "genesis id is %s", h);
    // RawTxBindingTest: the witness-free id of a serialization without witness data is its double SHA-256.
    UInt256 d; BRSHA256_2(&d, g_genesis, g_genesisLen);
    CHECK(UInt256Eq(d, id), "genesis id is the double SHA-256 of its bytes");
    CHECK(BRRawTxId(g_segwit, g_segwitLen, &id) == 1, "the BIP 143 segwit example has an id");
    display_hex(id, h);
    CHECK(strcmp(h, SEGWIT_TXID) == 0, "segwit id is the witness-excluded %s (got %s)", SEGWIT_TXID, h);
    BRSHA256_2(&d, g_segwit, g_segwitLen);
    CHECK(!UInt256Eq(d, id), "the segwit bytes as they are hash to something else; that is never its id");
    same_id(g_genesis, g_genesisLen, "genesis");
    same_id(g_segwit, g_segwitLen, "segwit");
    CHECK(BRRawTxId(NULL, 0, &id) == 0 && raw_tx_id(NULL, 0, &id) == 0, "no bytes: no id");
    CHECK(BRRawTxId(g_genesis, 0, &id) == 0, "zero length: no id");
    CHECK(BRRawTxId(g_genesis, g_genesisLen, NULL) == 0, "no out pointer: no id");
}

static void case_id_hostile_vectors(void)
{
    int bad = id_variants(g_genesis, g_genesisLen, 64) + id_variants(g_segwit, g_segwitLen, 64);
    CHECK(bad == 0, "%d disagreement(s) on variants of the fixed vectors", bad);
    UInt256 id;
    uint8_t v[700];
    memcpy(v, g_genesis, g_genesisLen); v[g_genesisLen] = 0;
    CHECK(BRRawTxId(v, g_genesisLen + 1, &id) == 0, "a trailing byte gets no id");
    CHECK(BRRawTxId(g_genesis, g_genesisLen - 1, &id) == 0, "a truncation gets no id");
    // the stripped form of the segwit example (no marker, flag or witness) is the same transaction
    BRTransaction *tx = BRTransactionParseSigned(g_segwit, g_segwitLen);
    for (size_t i = 0; tx && i < tx->inCount; i++) BRTxInputSetWitness(&tx->inputs[i], NULL, 0);
    size_t n = tx ? BRTransactionSerialize(tx, v, sizeof(v)) : 0;
    if (tx) BRTransactionFree(tx);
    char h[65];
    CHECK(n > 0 && BRRawTxId(v, n, &id) == 1, "the segwit example without its witness has an id");
    display_hex(id, h);
    CHECK(strcmp(h, SEGWIT_TXID) == 0, "and it is the same id");
    same_id(v, n, "segwit stripped");
}

static void case_id_fuzz(void)
{
    int bad = 0;
    for (int i = 0; i < 400; i++) {
        size_t n; uint8_t *b = rand_tx(&n, i % 2);
        bad += id_variants(b, n, 8);
        free(b);
    }
    uint8_t r[300];
    for (int i = 0; i < 4000; i++) {
        size_t n = 1 + rnd() % sizeof(r);
        for (size_t k = 0; k < n; k++) r[k] = (uint8_t)rnd();
        if (i % 3 == 0) { r[0] = 1; if (n > 4) { r[1] = r[2] = r[3] = 0; } }
        if (i % 6 == 0 && n > 6) { r[4] = 0; r[5] = 1; }
        bad += !same_id(r, n, "random bytes");
    }
    CHECK(bad == 0, "%d disagreement(s) between the bridge and the core on fuzzed bytes", bad);
}

// ---- THE RULES: a model of RawTxBinding.kt ---------------------------------------------------

typedef struct { char txid[80]; int vout; int64_t amount; const char *script; } KUtxo;     // script NULL = none
typedef struct { char key[80]; char *hex; } KParent;                                         // a rawTxs entry, in map order

typedef struct {
    int contradicted;          // Verdict.Contradicted
    size_t coin;               // its coin
    int reason;                // BR_RAW_TX_CONTRADICTION_*
    char other[65];            // OTHER_TX: the id
    int64_t parentAmount;
    // Checked: per coin, proven amount/script (lowercase hex) or not; the unproven list, in order
    int proven[256]; int64_t amt[256]; char *script[256];
    char unproven[256][90]; size_t nUnproven;
} KVerdict;

static int ci_eq(const char *a, const char *b)
{
    if (strlen(a) != strlen(b)) return 0;
    for (; *a; a++, b++) {
        char x = *a, y = *b;
        if (x >= 'A' && x <= 'Z') x = (char)(x - 'A' + 'a');
        if (y >= 'A' && y <= 'Z') y = (char)(y - 'A' + 'a');
        if (x != y) return 0;
    }
    return 1;
}

// hexToBytesOrNull
static uint8_t *k_hex(const char *hex, size_t *n)
{
    size_t l = strlen(hex);
    if (l == 0 || l % 2) return NULL;
    uint8_t *b = malloc(l / 2);
    for (size_t i = 0; i < l / 2; i++) {
        int hi = _BRRawTxHexDigit(hex[2 * i]), lo = _BRRawTxHexDigit(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) { free(b); return NULL; }
        b[i] = (uint8_t)((hi << 4) | lo);
    }
    *n = l / 2;
    return b;
}

// The production txidOf: NativeBridge.rawTransactionId, i.e. the bridge's raw_tx_id, display hex.
static int k_txid(const uint8_t *b, size_t n, char out[65])
{
    UInt256 id;
    if (!raw_tx_id(b, n, &id)) return 0;
    display_hex(id, out);
    return 1;
}

// nativeOutputs: getRawTransactionOutputs ("i|amount|script"), parsed back with toInt/toLong. NULL = null
// or a throw (both are unreadable to the caller's runCatching).
typedef struct { int64_t amount; char *script; } KOut;
static KOut *k_outputs(const uint8_t *b, size_t n, size_t *count)
{
    if (n == 0) return NULL;
    BRTransaction *tx = BRTransactionParseSigned(b, n);
    if (!tx) return NULL;
    KOut *o = calloc(tx->outCount + 1, sizeof(KOut));
    for (size_t i = 0; i < tx->outCount; i++) {
        char line[64];
        snprintf(line, sizeof(line), "%llu", (unsigned long long)tx->outputs[i].amount);
        if (tx->outputs[i].amount > (uint64_t)INT64_MAX) {   // Long.parseLong throws
            for (size_t k = 0; k < i; k++) free(o[k].script);
            free(o); BRTransactionFree(tx); return NULL;
        }
        o[i].amount = (int64_t)strtoll(line, NULL, 10);
        o[i].script = malloc(2 * tx->outputs[i].scriptLen + 1);
        tohex(tx->outputs[i].script, tx->outputs[i].scriptLen, o[i].script);
    }
    *count = tx->outCount;
    BRTransactionFree(tx);
    return o;
}

typedef struct { int state; char other[65]; KOut *outs; size_t nout; char lower[80]; } KParsed;   // 1 Read 2 Other 3 Unreadable

static void k_read(const char *txid, const KParent *ps, size_t np, KParsed *r)
{
    const KParent *e = NULL;
    for (size_t i = 0; i < np && !e; i++) if (strcmp(ps[i].key, txid) == 0) e = &ps[i];
    for (size_t i = 0; i < np && !e; i++) if (ci_eq(ps[i].key, txid)) e = &ps[i];
    r->state = 3;
    if (!e) return;
    size_t n; uint8_t *b = k_hex(e->hex, &n);
    if (!b) return;
    char id[65];
    if (!k_txid(b, n, id)) { free(b); return; }
    if (!ci_eq(id, txid)) { r->state = 2; strcpy(r->other, id); free(b); return; }
    r->outs = k_outputs(b, n, &r->nout);
    free(b);
    if (r->outs) r->state = 1;
}

static void k_check(const KUtxo *us, size_t nu, const KParent *ps, size_t np, KVerdict *v)
{
    KParsed *parsed = calloc(nu + 1, sizeof(KParsed));
    size_t nparsed = 0;
    memset(v, 0, sizeof(*v));
    for (size_t i = 0; i < nu; i++) {
        const KUtxo *u = &us[i];
        char op[90], lower[80];
        snprintf(op, sizeof(op), "%s:%d", u->txid, u->vout);
        size_t L = strlen(u->txid);
        for (size_t k = 0; k <= L; k++) lower[k] = (u->txid[k] >= 'A' && u->txid[k] <= 'Z') ? (char)(u->txid[k] + 32) : u->txid[k];
        KParsed *p = NULL;
        for (size_t k = 0; k < nparsed && !p; k++) if (strcmp(parsed[k].lower, lower) == 0) p = &parsed[k];
        if (!p) { p = &parsed[nparsed++]; strcpy(p->lower, lower); k_read(u->txid, ps, np, p); }
        if (p->state == 3) {
            int seen = 0;
            for (size_t k = 0; k < v->nUnproven; k++) if (strcmp(v->unproven[k], op) == 0) seen = 1;
            if (!seen) strcpy(v->unproven[v->nUnproven++], op);
        } else if (p->state == 2) {
            v->contradicted = 1; v->coin = i; v->reason = BR_RAW_TX_CONTRADICTION_OTHER_TX; strcpy(v->other, p->other); break;
        } else {
            if (u->vout < 0 || (size_t)u->vout >= p->nout) { v->contradicted = 1; v->coin = i; v->reason = BR_RAW_TX_CONTRADICTION_NO_SUCH_OUTPUT; break; }
            KOut *o = &p->outs[u->vout];
            if (o->amount != u->amount) { v->contradicted = 1; v->coin = i; v->reason = BR_RAW_TX_CONTRADICTION_AMOUNT; v->parentAmount = o->amount; break; }
            if (u->script && !ci_eq(u->script, o->script)) { v->contradicted = 1; v->coin = i; v->reason = BR_RAW_TX_CONTRADICTION_SCRIPT; break; }
            // stated[op]; proven(u) for every coin of the same outpoint text gives it
            for (size_t k = 0; k <= i; k++) {
                char op2[90]; snprintf(op2, sizeof(op2), "%s:%d", us[k].txid, us[k].vout);
                if (strcmp(op2, op) == 0) { v->proven[k] = 1; v->amt[k] = o->amount; v->script[k] = o->script; }
            }
        }
    }
    // proven(u) is looked up after the loop: a coin is proven iff stated has its outpoint text
    if (!v->contradicted) {
        for (size_t i = 0; i < nu; i++) if (!v->proven[i]) {
            char op[90]; snprintf(op, sizeof(op), "%s:%d", us[i].txid, us[i].vout);
            for (size_t k = 0; k < nu; k++) if (v->proven[k]) {
                char op2[90]; snprintf(op2, sizeof(op2), "%s:%d", us[k].txid, us[k].vout);
                if (strcmp(op, op2) == 0) { v->proven[i] = 1; v->amt[i] = v->amt[k]; v->script[i] = v->script[k]; }
            }
        }
    }
    // keep the parsed parents (scripts referenced) until the caller frees with k_free
    v->script[255] = (char *)parsed;   // stash; k_free releases
    (void)nparsed;
}

static void k_free(KVerdict *v, size_t nu)
{
    KParsed *parsed = (KParsed *)v->script[255];
    for (size_t i = 0; i < nu + 1; i++) {
        if (parsed[i].outs) { for (size_t k = 0; k < parsed[i].nout; k++) free(parsed[i].outs[k].script); free(parsed[i].outs); }
    }
    free(parsed);
}

// The adapter a platform writes: text to the header's types. Txids here are always 64 hex digits and the
// parent keys lowercase and distinct, the domain where the two must agree exactly.
static size_t g_tally[8];   // 0..4 contradiction reasons (0 = checked), 5 proven coins, 6 unproven coins

static int compare_with_core(const KUtxo *us, size_t nu, const KParent *ps, size_t np, const char *what)
{
    KVerdict kv;
    BRRawTxBindingUtxo cu[256];
    BRRawTxBindingParent cp[256];
    uint8_t *bytes[256];
    BRRawTxProof proofs[256];
    BRRawTxBindingVerdict cv;
    int bad = 0;

    k_check(us, nu, ps, np, &kv);
    for (size_t i = 0; i < nu; i++) {
        cu[i].txid = from_display(us[i].txid);
        cu[i].vout = us[i].vout;
        cu[i].amount = us[i].amount;
        cu[i].scriptHex = us[i].script;
        cu[i].scriptHexLen = us[i].script ? strlen(us[i].script) : 0;
    }
    for (size_t i = 0; i < np; i++) {
        size_t n = 0;
        cp[i].txid = from_display(ps[i].key);
        bytes[i] = k_hex(ps[i].hex, &n);
        cp[i].bytes = bytes[i];
        cp[i].len = bytes[i] ? n : 0;
    }
    BRRawTxBindingOutcome oc = BRRawTxBindingCheck(cu, nu, cp, np, proofs, &cv);
    g_tally[oc == BR_RAW_TX_BINDING_CONTRADICTED ? (size_t)cv.reason : 0]++;
    for (size_t i = 0; i < nu; i++) {
        if (proofs[i].state == BR_RAW_TX_PROOF_PROVEN) g_tally[5]++;
        if (proofs[i].state == BR_RAW_TX_PROOF_UNPROVEN) g_tally[6]++;
    }
    if (kv.contradicted) {
        if (oc != BR_RAW_TX_BINDING_CONTRADICTED || cv.utxo != kv.coin || (int)cv.reason != kv.reason) {
            printf("FAIL: %s: Kotlin contradicts at coin %zu (reason %d), core outcome %d coin %zu reason %d\n",
                   what, kv.coin, kv.reason, oc, cv.utxo, cv.reason);
            bad++;
        } else {
            if (kv.reason == BR_RAW_TX_CONTRADICTION_OTHER_TX) {
                char h[65]; display_hex(cv.otherTxid, h);
                if (strcmp(h, kv.other) != 0) { printf("FAIL: %s: other txid %s vs %s\n", what, h, kv.other); bad++; }
            }
            if (kv.reason == BR_RAW_TX_CONTRADICTION_AMOUNT && cv.parentAmount != kv.parentAmount) {
                printf("FAIL: %s: parent amount %lld vs %lld\n", what, (long long)cv.parentAmount, (long long)kv.parentAmount); bad++;
            }
            for (size_t i = 0; i < nu; i++) if (proofs[i].state != BR_RAW_TX_PROOF_REFUSED) {
                printf("FAIL: %s: coin %zu not refused on a contradiction\n", what, i); bad++; break;
            }
        }
    } else if (oc != BR_RAW_TX_BINDING_CHECKED) {
        printf("FAIL: %s: Kotlin checks, core outcome %d (coin %zu reason %d)\n", what, oc, cv.utxo, cv.reason); bad++;
    } else {
        size_t listed = 0;
        for (size_t i = 0; i < nu; i++) {
            int cpv = proofs[i].state == BR_RAW_TX_PROOF_PROVEN;
            if (cpv != kv.proven[i]) { printf("FAIL: %s: coin %zu proven Kotlin %d core %d\n", what, i, kv.proven[i], cpv); bad++; continue; }
            if (!cpv) {
                if (proofs[i].state != BR_RAW_TX_PROOF_UNPROVEN) { printf("FAIL: %s: coin %zu state %d\n", what, i, proofs[i].state); bad++; }
                if (proofs[i].listed) {
                    char op[90]; snprintf(op, sizeof(op), "%s:%d", us[i].txid, us[i].vout);
                    if (listed >= kv.nUnproven || strcmp(kv.unproven[listed], op) != 0) {
                        printf("FAIL: %s: listed %s out of Kotlin's order\n", what, op); bad++;
                    }
                    listed++;
                }
                continue;
            }
            char h[200] = "";
            if (proofs[i].scriptLen < 90) tohex(cp[proofs[i].parent].bytes + proofs[i].scriptOffset, proofs[i].scriptLen, h);
            if (proofs[i].amount != kv.amt[i] || strcmp(h, kv.script[i]) != 0) {
                printf("FAIL: %s: coin %zu proven at %lld/%s, Kotlin %lld/%s\n", what, i, (long long)proofs[i].amount, h,
                       (long long)kv.amt[i], kv.script[i]);
                bad++;
            }
        }
        if (listed != kv.nUnproven || cv.unprovenListed != kv.nUnproven) {
            printf("FAIL: %s: %zu listed, Kotlin %zu\n", what, listed, kv.nUnproven); bad++;
        }
    }
    for (size_t i = 0; i < np; i++) free(bytes[i]);
    k_free(&kv, nu);
    return bad;
}

// A pool of parents for the fuzzer: real transactions with short scripts.
typedef struct { uint8_t *b; size_t n; char id[65]; size_t nout; uint64_t amt[6]; char script[6][90]; } Pool;

static void make_pool(Pool *p, int i)
{
    InSpec in = { rand_hash(), 0, 10 + rnd() % 60, (size_t)(i % 3 == 0 ? 2 : 0) };
    OutSpec outs[6];
    p->nout = 1 + rnd() % 5;
    for (size_t k = 0; k < p->nout; k++) {
        outs[k].amount = (rnd() % 23 == 0) ? (uint64_t)INT64_MAX + 1 + rnd() : 1 + rnd() % 1000000;
        outs[k].scriptLen = 1 + rnd() % 30;
        for (size_t j = 0; j < outs[k].scriptLen; j++) outs[k].script[j] = (uint8_t)rnd();
        p->amt[k] = outs[k].amount;
        tohex(outs[k].script, outs[k].scriptLen, p->script[k]);
    }
    p->b = build_tx(&in, 1, outs, p->nout, &p->n);
    display_hex(must_id(p->b, p->n), p->id);
}

static void upper(char *s) { for (; *s; s++) if (*s >= 'a' && *s <= 'f') *s = (char)(*s - 32); }

static void case_rules_fuzz(void)
{
    Pool pool[24];
    char sbuf[256][100];
    char hexbuf[64][1400];
    int bad = 0;
    for (int i = 0; i < 24; i++) make_pool(&pool[i], i);
    for (int round = 0; round < 3000; round++) {
        KUtxo us[40]; KParent ps[40];
        size_t nu = 1 + rnd() % 30, np = 0;
        int used[24] = {0}, caps[24];
        // a parent's coins are all written in one case per round: Kotlin lists outpoints by their text, the
        // core by their bytes, and the two agree when one outpoint has one spelling (see the header's TXIDS)
        for (int k = 0; k < 24; k++) caps[k] = rnd() % 9 == 0;
        for (size_t i = 0; i < nu; i++) {
            Pool *p = &pool[rnd() % 24];
            KUtxo *u = &us[i];
            strcpy(u->txid, p->id);
            if (caps[p - pool]) upper(u->txid);
            u->vout = (rnd() % 60 == 0) ? (int)p->nout + (int)(rnd() % 2) : (int)(rnd() % p->nout);
            if (rnd() % 120 == 0) u->vout = -1 - (int)(rnd() % 3);
            size_t v = (u->vout >= 0 && (size_t)u->vout < p->nout) ? (size_t)u->vout : 0;
            u->amount = (int64_t)p->amt[v];
            if (rnd() % 25 == 0) u->amount += 1;
            strcpy(sbuf[i], p->script[v]);
            int sm = rnd() % 30;
            if (sm == 0) u->script = NULL;
            else {
                if (sm == 1) upper(sbuf[i]);
                if (sm == 2) sbuf[i][0] = sbuf[i][0] == '0' ? '1' : '0';
                if (sm == 3) strcat(sbuf[i], "00");
                if (sm == 4) sbuf[i][strlen(sbuf[i]) - 1] = 'g';
                u->script = sbuf[i];
            }
            if (rnd() % 5) used[p - pool] = 1;
            if (i > 0 && rnd() % 12 == 0) us[i] = us[rnd() % i];   // a repeated outpoint
        }
        for (int k = 0; k < 24 && np < 40; k++) {
            if (!used[k]) continue;
            KParent *e = &ps[np];
            strcpy(e->key, pool[k].id);
            int m = rnd() % 14;
            const Pool *src = (m == 0) ? &pool[(k + 1 + rnd() % 23) % 24] : &pool[k];   // another tx's bytes
            tohex(src->b, src->n, hexbuf[np]);
            if (m == 1) strcpy(hexbuf[np], "zz");
            if (m == 2) hexbuf[np][0] = 0;
            if (m == 3) strcat(hexbuf[np], "00");                     // trailing byte
            if (m == 4) hexbuf[np][strlen(hexbuf[np]) - 2] = 0;       // truncated
            if (m == 5) strcpy(hexbuf[np], "0100");
            e->hex = hexbuf[np];
            np++;
        }
        // shuffle the parent order: the lookup is by key, not position
        for (size_t k = np; k > 1; k--) { size_t j = rnd() % k; KParent t = ps[k - 1]; ps[k - 1] = ps[j]; ps[j] = t; }
        char what[40]; snprintf(what, sizeof(what), "fuzz round %d", round);
        bad += compare_with_core(us, nu, ps, np, what);
    }
    for (int i = 0; i < 24; i++) free(pool[i].b);
    CHECK(bad == 0, "%d disagreement(s) between the Kotlin model and the core on fuzzed profiles", bad);
    printf("INFO: profiles checked %zu, contradicted other-tx %zu / no-output %zu / amount %zu / script %zu; coins proven %zu, unproven %zu\n",
           g_tally[0], g_tally[1], g_tally[2], g_tally[3], g_tally[4], g_tally[5], g_tally[6]);
    CHECK(g_tally[0] && g_tally[1] && g_tally[2] && g_tally[3] && g_tally[4] && g_tally[5] && g_tally[6],
          "the fuzzer reaches every verdict");
}

// feePaid as RawTxBinding.kt reads it, with the bridge's input and output listings.
static int k_fee(const uint8_t *b, size_t n, const char (*keys)[90], const int64_t *vals, size_t nk, int64_t *fee)
{
    if (n == 0) return 0;
    BRTransaction *tx = BRTransactionParseSigned(b, n);
    if (!tx) return 0;
    int ok = 0;
    char (*ins)[90] = calloc(tx->inCount + 1, 90);
    size_t nout = 0;
    KOut *outs = NULL;
    for (size_t i = 0; i < tx->inCount; i++) {
        if (tx->inputs[i].index > (uint32_t)INT32_MAX) goto out;   // toInt throws
        char h[65]; display_hex(tx->inputs[i].txHash, h);
        snprintf(ins[i], 90, "%s:%u", h, (unsigned)tx->inputs[i].index);
    }
    outs = k_outputs(b, n, &nout);
    if (!outs) goto out;
    // normalized.size != expected.size || toSet() != toSet()
    if (tx->inCount != nk) goto out;
    for (size_t i = 0; i < tx->inCount; i++) {
        int f = 0; for (size_t k = 0; k < nk; k++) if (ci_eq(ins[i], keys[k])) f = 1;
        if (!f) goto out;
    }
    for (size_t k = 0; k < nk; k++) {
        int f = 0; for (size_t i = 0; i < tx->inCount; i++) if (ci_eq(ins[i], keys[k])) f = 1;
        if (!f) goto out;
    }
    if (nout == 0) goto out;
    for (size_t k = 0; k < nk; k++) if (vals[k] <= 0) goto out;
    int64_t tin = 0, tout = 0;
    for (size_t k = 0; k < nk; k++) { if (__builtin_add_overflow(tin, vals[k], &tin)) goto out; }
    for (size_t i = 0; i < nout; i++) { if (__builtin_add_overflow(tout, outs[i].amount, &tout)) goto out; }
    if (tin - tout < 0) goto out;
    *fee = tin - tout; ok = 1;
out:
    if (outs) { for (size_t i = 0; i < nout; i++) free(outs[i].script); free(outs); }
    free(ins);
    BRTransactionFree(tx);
    return ok;
}

static void case_fee_fuzz(void)
{
    int bad = 0, answered = 0;
    for (int round = 0; round < 3000; round++) {
        InSpec ins[8]; OutSpec outs[4];
        size_t nin = rnd() % 7, nout = rnd() % 4;
        UInt256 base[3] = { rand_hash(), rand_hash(), rand_hash() };
        for (size_t i = 0; i < nin; i++) {
            ins[i].prev = base[rnd() % 3];
            ins[i].index = (rnd() % 30 == 0) ? 0x80000000u + rnd() % 5 : rnd() % 3;
            ins[i].sigLen = rnd() % 20; ins[i].witItems = rnd() % 2;
        }
        for (size_t i = 0; i < nout; i++) {
            int big = rnd() % 12;
            outs[i].amount = big == 0 ? (uint64_t)INT64_MAX + 1 : big == 1 ? (uint64_t)INT64_MAX - rnd() % 3 : rnd() % 500000;
            outs[i].scriptLen = 1; outs[i].script[0] = 0x51;
        }
        size_t n; uint8_t *b = nin ? build_tx(ins, nin, outs, nout, &n) : NULL;
        if (!b) { b = malloc(16); n = 10; for (int k = 0; k < 10; k++) b[k] = (uint8_t)rnd(); }
        // the spent map: the inputs, then mutations (drop, add, change a vout, an amount)
        char keys[10][90]; int64_t vals[10]; BRRawTxBindingSpent sp[10]; size_t nk = 0;
        for (size_t i = 0; i < nin && nk < 10; i++) {
            char h[65]; display_hex(ins[i].prev, h);
            char k[90]; snprintf(k, sizeof(k), "%s:%u", h, (unsigned)ins[i].index);
            int dup = 0; for (size_t j = 0; j < nk; j++) if (strcmp(keys[j], k) == 0) dup = 1;
            if (dup) continue;                      // a Map has each key once
            strcpy(keys[nk], k);
            sp[nk].txid = ins[i].prev; sp[nk].vout = ins[i].index;
            vals[nk] = sp[nk].amount = (rnd() % 20 == 0) ? 0 : (rnd() % 30 == 0) ? INT64_MAX - rnd() % 3 : 1 + rnd() % 400000;
            nk++;
        }
        int m = rnd() % 6;
        if (m == 0 && nk > 0) nk--;
        if (m == 1 && nk < 10) {
            UInt256 x = rand_hash(); char h[65]; display_hex(x, h);
            snprintf(keys[nk], 90, "%s:0", h); sp[nk].txid = x; sp[nk].vout = 0; vals[nk] = sp[nk].amount = 5; nk++;
        }
        if (m == 2 && nk > 0) {
            char h[65]; display_hex(sp[0].txid, h); sp[0].vout += 7; snprintf(keys[0], 90, "%s:%lld", h, (long long)sp[0].vout);
        }
        int64_t kf = -1, cf = -1;
        int ka = k_fee(b, n, (const char (*)[90])keys, vals, nk, &kf);
        int ca = BRRawTxBindingFeePaid(b, n, sp, nk, &cf);
        answered += ka;
        if (ka != ca || (ka && kf != cf)) {
            printf("FAIL: fee round %d: Kotlin %d %lld, core %d %lld\n", round, ka, (long long)kf, ca, (long long)cf); bad++;
        }
        free(b);
    }
    CHECK(bad == 0, "%d disagreement(s) between the Kotlin model and the core on fuzzed fees", bad);
    printf("INFO: %d of 3000 fees answered\n", answered);
    CHECK(answered > 100 && answered < 2900, "the fuzzer reaches both answers");
}

// ---- RawTxBindingTest, case for case ---------------------------------------------------------

static BRRawTxBindingUtxo coin(const char *txid, int64_t vout, int64_t amount, const char *script)
{
    BRRawTxBindingUtxo u;
    u.txid = from_display(txid); u.vout = vout; u.amount = amount;
    u.scriptHex = script; u.scriptHexLen = script ? strlen(script) : 0;
    return u;
}

static BRRawTxBindingParent parent(const char *txid, const uint8_t *b, size_t n)
{
    BRRawTxBindingParent p;
    p.txid = from_display(txid); p.bytes = b; p.len = n;
    return p;
}

static int proof_script_is(const BRRawTxProof *pf, const BRRawTxBindingParent *ps, const char *hex)
{
    char h[400];
    if (pf->state != BR_RAW_TX_PROOF_PROVEN || pf->scriptLen * 2 >= sizeof(h)) return 0;
    tohex(ps[pf->parent].bytes + pf->scriptOffset, pf->scriptLen, h);
    return strcmp(h, hex) == 0;
}

static void case_genesis_parent_proves(void)
{
    BRRawTxBindingUtxo u = coin(GENESIS_TXID, 0, 5000000000LL, GENESIS_SCRIPT);
    BRRawTxBindingParent p = parent(GENESIS_TXID, g_genesis, g_genesisLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CHECKED, "checked");
    CHECK(pf.state == BR_RAW_TX_PROOF_PROVEN && pf.amount == 5000000000LL, "the genesis output is proven at 5e9");
    CHECK(v.unprovenListed == 0, "nothing unproven");
}

static void case_segwit_parent_proves(void)
{
    BRRawTxBindingUtxo u = coin(SEGWIT_TXID, 1, 223450000LL, SEGWIT_SCRIPT1);
    BRRawTxBindingParent p = parent(SEGWIT_TXID, g_segwit, g_segwitLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v);
    CHECK(pf.state == BR_RAW_TX_PROOF_PROVEN && pf.amount == 223450000LL, "a segwit parent is proven by its witness-excluded id");
    CHECK(proof_script_is(&pf, &p, SEGWIT_SCRIPT1), "with its own script");
    BRRawTxBindingUtxo u0 = coin(SEGWIT_TXID, 0, 112340000LL, NULL);
    BRRawTxBindingCheck(&u0, 1, &p, 1, &pf, &v);
    CHECK(pf.amount == 112340000LL && proof_script_is(&pf, &p, SEGWIT_SCRIPT0), "and its output 0 too");
    // with the witness-free id function a segwit parent was unproven; with the parser's id it is proven, and
    // never contradicted: the same holds for the stripped serialization of the same transaction
    BRTransaction *tx = BRTransactionParseSigned(g_segwit, g_segwitLen);
    for (size_t i = 0; i < tx->inCount; i++) BRTxInputSetWitness(&tx->inputs[i], NULL, 0);
    uint8_t s[600]; size_t n = BRTransactionSerialize(tx, s, sizeof(s)); BRTransactionFree(tx);
    BRRawTxBindingParent q = parent(SEGWIT_TXID, s, n);
    BRRawTxBindingCheck(&u, 1, &q, 1, &pf, &v);
    CHECK(pf.state == BR_RAW_TX_PROOF_PROVEN, "the stripped serialization proves it too");
}

static void case_witness_hashing_id_contradicts(void)
{
    // A parent filed under the hash of its bytes as they are (what an id function that hashed the witness
    // would compute) is another transaction: the id computed from the bytes is the witness-excluded one.
    UInt256 d; char wkey[65];
    BRSHA256_2(&d, g_segwit, g_segwitLen); display_hex(d, wkey);
    BRRawTxBindingUtxo u = coin(wkey, 1, 223450000LL, SEGWIT_SCRIPT1);
    BRRawTxBindingParent p = parent(wkey, g_segwit, g_segwitLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED, "contradicted");
    char h[65]; display_hex(v.otherTxid, h);
    CHECK(v.reason == BR_RAW_TX_CONTRADICTION_OTHER_TX && strcmp(h, SEGWIT_TXID) == 0, "the bytes are %s", SEGWIT_TXID);
}

static void case_other_tx_contradicts(void)
{
    BRRawTxBindingUtxo u = coin(SEGWIT_TXID, 0, 5000000000LL, GENESIS_SCRIPT);
    BRRawTxBindingParent p = parent(SEGWIT_TXID, g_genesis, g_genesisLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED
          && v.reason == BR_RAW_TX_CONTRADICTION_OTHER_TX && pf.state == BR_RAW_TX_PROOF_REFUSED,
          "bytes of another transaction contradict");
}

static void case_amount_index_script_contradict(void)
{
    BRRawTxBindingParent p = parent(GENESIS_TXID, g_genesis, g_genesisLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    BRRawTxBindingUtxo bad[3] = {
        coin(GENESIS_TXID, 0, 4999999999LL, GENESIS_SCRIPT),
        coin(GENESIS_TXID, 1, 5000000000LL, GENESIS_SCRIPT),
        coin(GENESIS_TXID, 0, 5000000000LL, "76a914000000000000000000000000000000000000000088ac"),
    };
    BRRawTxContradiction want[3] = { BR_RAW_TX_CONTRADICTION_AMOUNT, BR_RAW_TX_CONTRADICTION_NO_SUCH_OUTPUT, BR_RAW_TX_CONTRADICTION_SCRIPT };
    for (int i = 0; i < 3; i++) {
        CHECK(BRRawTxBindingCheck(&bad[i], 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED && v.reason == want[i],
              "case %d contradicts with reason %d (got %d)", i, want[i], v.reason);
    }
    CHECK(BRRawTxBindingCheck(&bad[0], 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED && v.parentAmount == 5000000000LL,
          "the amount contradiction reports what the parent states");
}

static void case_one_contradiction_refuses_profile(void)
{
    BRRawTxBindingUtxo u[2] = { coin(GENESIS_TXID, 0, 5000000000LL, GENESIS_SCRIPT),
                                coin(SEGWIT_TXID, 0, 1, "76a914000000000000000000000000000000000000000088ac") };
    BRRawTxBindingParent p[2] = { parent(GENESIS_TXID, g_genesis, g_genesisLen), parent(SEGWIT_TXID, g_genesis, g_genesisLen) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(u, 2, p, 2, pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED && v.utxo == 1,
          "the contradicted coin is %s:0", SEGWIT_TXID);
    CHECK(pf[0].state == BR_RAW_TX_PROOF_REFUSED && pf[1].state == BR_RAW_TX_PROOF_REFUSED, "the proven sibling is refused too");
}

static void case_no_parent_or_unreadable_unproven(void)
{
    BRRawTxBindingUtxo u = coin(GENESIS_TXID, 0, 5000000000LL, GENESIS_SCRIPT);
    uint8_t two[2] = { 0x01, 0x00 };
    BRRawTxBindingParent ps[3] = { parent(GENESIS_TXID, NULL, 0),      // "xyz": did not decode
                                   parent(GENESIS_TXID, g_genesis, 0), // "": empty
                                   parent(GENESIS_TXID, two, 2) };     // "0100"
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(&u, 1, NULL, 0, &pf, &v) == BR_RAW_TX_BINDING_CHECKED && pf.state == BR_RAW_TX_PROOF_UNPROVEN
          && pf.listed && v.unprovenListed == 1, "no parent: unproven and listed");
    for (int i = 0; i < 3; i++) {
        CHECK(BRRawTxBindingCheck(&u, 1, &ps[i], 1, &pf, &v) == BR_RAW_TX_BINDING_CHECKED && pf.state == BR_RAW_TX_PROOF_UNPROVEN
              && pf.amount == 0 && pf.listed && v.unprovenListed == 1, "unreadable parent %d: unproven, no amount", i);
    }
}

static void case_proven_carries_parent_script(void)
{
    BRRawTxBindingUtxo u = coin(GENESIS_TXID, 0, 5000000000LL, NULL);
    BRRawTxBindingParent p = parent(GENESIS_TXID, g_genesis, g_genesisLen);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v);
    CHECK(proof_script_is(&pf, &p, GENESIS_SCRIPT), "a proven input carries the parent's script when the lookup gave none");
}

// A signed transaction spending these outpoints, one output per amount.
static uint8_t *spend(const UInt256 *txids, const uint32_t *vouts, size_t n, const uint64_t *pays, size_t np, size_t *len)
{
    InSpec ins[64]; OutSpec outs[8];
    for (size_t i = 0; i < n; i++) { ins[i].prev = txids[i]; ins[i].index = vouts[i]; ins[i].sigLen = 106; ins[i].witItems = 0; }
    for (size_t i = 0; i < np; i++) { outs[i].amount = pays[i]; outs[i].scriptLen = 1; outs[i].script[0] = 0x51; }
    return build_tx(ins, n, outs, np, len);
}

static void case_fee_is_inputs_less_outputs(void)
{
    UInt256 t[2] = { from_display("00000000000000000000000000000000000000000000000000000000000000aa"),
                     from_display("00000000000000000000000000000000000000000000000000000000000000bb") };
    uint32_t vo[2] = { 0, 1 }; uint64_t pay = 850000; size_t n; int64_t fee;
    uint8_t *b = spend(t, vo, 2, &pay, 1, &n);
    BRRawTxBindingSpent sp[2] = { { t[0], 0, 600000 }, { t[1], 1, 300000 } };
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 1 && fee == 50000, "the fee is 50000 (got %lld)", (long long)fee);
    free(b);
}

static void case_fee_needs_exact_outpoints(void)
{
    UInt256 a = from_display("00000000000000000000000000000000000000000000000000000000000000aa");
    UInt256 bb = from_display("00000000000000000000000000000000000000000000000000000000000000bb");
    UInt256 c = from_display("00000000000000000000000000000000000000000000000000000000000000cc");
    BRRawTxBindingSpent sp[2] = { { a, 0, 600000 }, { bb, 1, 300000 } };
    uint64_t pay = 850000; size_t n; int64_t fee; uint8_t *b;
    UInt256 t1[1] = { a }; uint32_t v1[1] = { 0 };
    b = spend(t1, v1, 1, &pay, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "a transaction spending fewer outpoints answers no fee"); free(b);
    UInt256 t3[3] = { a, bb, c }; uint32_t v3[3] = { 0, 1, 2 };
    b = spend(t3, v3, 3, &pay, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "one spending more outpoints answers no fee"); free(b);
    UInt256 t2[2] = { a, a }; uint32_t v2[2] = { 0, 0 };
    b = spend(t2, v2, 2, &pay, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "one spending an outpoint twice answers no fee"); free(b);
    UInt256 tt[2] = { a, bb }; uint32_t vv[2] = { 0, 1 }; uint64_t over = 950000;
    b = spend(tt, vv, 2, &over, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "one paying out more than it spends answers no fee"); free(b);
    b = spend(tt, vv, 2, NULL, 0, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "one with no outputs answers no fee"); free(b);
    uint64_t huge[2] = { (uint64_t)INT64_MAX, 10 };
    BRRawTxBindingSpent big[2] = { { a, 0, INT64_MAX }, { bb, 1, 5 } };
    b = spend(tt, vv, 2, huge, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, big, 2, &fee) == 0, "spent amounts whose sum overflows answer no fee"); free(b);
    b = spend(tt, vv, 2, huge, 2, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, &fee) == 0, "outputs whose sum overflows answer no fee"); free(b);
    BRRawTxBindingSpent zero[2] = { { a, 0, 0 }, { bb, 1, 900000 } };
    b = spend(tt, vv, 2, &pay, 1, &n);
    CHECK(BRRawTxBindingFeePaid(b, n, zero, 2, &fee) == 0, "a zero spent amount answers no fee");
    CHECK(BRRawTxBindingFeePaid(b, n, sp, 2, NULL) == 0 && BRRawTxBindingFeePaid(b, 0, sp, 2, &fee) == 0
          && BRRawTxBindingFeePaid(b, n, NULL, 2, &fee) == 0, "missing arguments answer no fee");
    free(b);
}

// ---- AmountProvenanceGateTest's parent cases, with real parents ------------------------------

static const char *P2PKH = "76a914111111111111111111111111111111111111111188ac";

typedef struct { uint8_t *b; size_t n; char id[65]; } Parent;

// A parent stating `amount` at output `vout` with the P2PKH script; outputs below vout are filler.
static Parent honest(uint32_t vout, uint64_t amount)
{
    Parent p; OutSpec outs[8];
    InSpec in = { rand_hash(), 0, 72, 0 };
    for (uint32_t i = 0; i <= vout; i++) { outs[i].amount = 1000; outs[i].scriptLen = 1; outs[i].script[0] = 0x51; }
    outs[vout].amount = amount; outs[vout].scriptLen = unhex(P2PKH, outs[vout].script);
    p.b = build_tx(&in, 1, outs, vout + 1, &p.n);
    display_hex(must_id(p.b, p.n), p.id);
    return p;
}

// The sweep's admission as Android runs it, with the proofs; the signer's inputs are the PROVEN copies.
static BRRawTxAdmission admit(const BRRawTxBindingUtxo *u, size_t n, const BRRawTxBindingParent *p, size_t np,
                              BRRawTxProof *pf, BRRawTxBindingVerdict *v)
{
    return BRRawTxBindingAdmit(1, u, n, p, np, pf, v);
}

static void refused_profile(BRRawTxAdmission a, const BRRawTxProof *pf, size_t n, size_t sibling, const char *what)
{
    CHECK(a == BR_RAW_TX_REFUSE_CONTRADICTED, "%s: the profile is refused (got %d)", what, a);
    int signedAny = 0;
    for (size_t i = 0; i < n; i++) if (pf[i].state == BR_RAW_TX_PROOF_PROVEN) signedAny = 1;
    CHECK(!signedAny, "%s: nothing may be signed", what);
    CHECK(pf[sibling].state == BR_RAW_TX_PROOF_REFUSED, "%s: the honest sibling is not signed either", what);
}

static void case_gate_amount_lower(void)
{
    Parent a = honest(0, 500000), b = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(a.id, 0, 100000, P2PKH), coin(b.id, 0, 300000, P2PKH) };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(b.id, b.b, b.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    refused_profile(admit(u, 2, p, 2, pf, &v), pf, 2, 1, "an amount lower than the parent states");
    CHECK(v.reason == BR_RAW_TX_CONTRADICTION_AMOUNT && v.parentAmount == 500000, "reason: the parent states 500000");
    free(a.b); free(b.b);
}

static void case_gate_amount_higher(void)
{
    Parent a = honest(0, 500000), s = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(s.id, 0, 300000, P2PKH), coin(a.id, 0, 900000, P2PKH) };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(s.id, s.b, s.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    refused_profile(admit(u, 2, p, 2, pf, &v), pf, 2, 0, "an amount higher than the parent states");
    CHECK(v.utxo == 1, "the coin named is the over-reported one");
    free(a.b); free(s.b);
}

static void case_gate_other_tx(void)
{
    Parent a = honest(0, 500000), o = honest(0, 500000), s = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(s.id, 0, 300000, P2PKH), coin(a.id, 0, 500000, P2PKH) };
    BRRawTxBindingParent p[2] = { parent(a.id, o.b, o.n), parent(s.id, s.b, s.n) };   // filed under a, bytes of o
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    refused_profile(admit(u, 2, p, 2, pf, &v), pf, 2, 0, "a parent that is another transaction");
    char h[65]; display_hex(v.otherTxid, h);
    CHECK(v.reason == BR_RAW_TX_CONTRADICTION_OTHER_TX && strcmp(h, o.id) == 0, "the bytes are named as the other transaction");
    free(a.b); free(o.b); free(s.b);
}

static void case_gate_missing_index(void)
{
    Parent a = honest(0, 500000), s = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(s.id, 0, 300000, P2PKH), coin(a.id, 3, 500000, P2PKH) };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(s.id, s.b, s.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    refused_profile(admit(u, 2, p, 2, pf, &v), pf, 2, 0, "an output index the parent does not have");
    CHECK(v.reason == BR_RAW_TX_CONTRADICTION_NO_SUCH_OUTPUT, "reason: no such output");
    free(a.b); free(s.b);
}

static void case_gate_other_script(void)
{
    Parent a = honest(0, 500000), s = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(s.id, 0, 300000, P2PKH), coin(a.id, 0, 500000, "76a914999999999999999999999999999999999999999988ac") };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(s.id, s.b, s.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    refused_profile(admit(u, 2, p, 2, pf, &v), pf, 2, 0, "a script other than the parent's");
    CHECK(v.reason == BR_RAW_TX_CONTRADICTION_SCRIPT, "reason: script");
    free(a.b); free(s.b);
}

static void case_gate_no_parent_held_back(void)
{
    Parent a = honest(0, 500000), b = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(a.id, 0, 500000, P2PKH), coin(b.id, 0, 300000, P2PKH) };
    BRRawTxBindingParent p[1] = { parent(a.id, a.b, a.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    CHECK(admit(u, 2, p, 1, pf, &v) == BR_RAW_TX_ADMIT, "admitted");
    CHECK(pf[0].state == BR_RAW_TX_PROOF_PROVEN && pf[0].amount == 500000, "only the proven input is signed, at 500000");
    CHECK(pf[1].state == BR_RAW_TX_PROOF_UNPROVEN && pf[1].listed && v.unprovenListed == 1, "the held input is reported");
    free(a.b); free(b.b);
}

static void case_gate_unreadable_held_back(void)
{
    Parent a = honest(0, 500000), b = honest(0, 300000);
    BRRawTxBindingUtxo u[2] = { coin(a.id, 0, 500000, P2PKH), coin(b.id, 0, 300000, P2PKH) };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(b.id, NULL, 0) };   // "zz"
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    CHECK(admit(u, 2, p, 2, pf, &v) == BR_RAW_TX_ADMIT, "an unreadable parent does not refuse the profile");
    CHECK(pf[0].state == BR_RAW_TX_PROOF_PROVEN && pf[1].state == BR_RAW_TX_PROOF_UNPROVEN && pf[1].listed, "it holds that input back");
    free(a.b); free(b.b);
}

static void case_gate_honest_sign_and_fee(void)
{
    Parent a = honest(0, 400000), b = honest(1, 300000), c = honest(0, 200000);
    BRRawTxBindingUtxo u[3] = { coin(a.id, 0, 400000, P2PKH), coin(b.id, 1, 300000, P2PKH), coin(c.id, 0, 200000, P2PKH) };
    BRRawTxBindingParent p[3] = { parent(a.id, a.b, a.n), parent(b.id, b.b, b.n), parent(c.id, c.b, c.n) };
    BRRawTxProof pf[3]; BRRawTxBindingVerdict v;
    CHECK(admit(u, 3, p, 3, pf, &v) == BR_RAW_TX_ADMIT && v.provenCount == 3, "admitted, three proven");
    int64_t want[3] = { 400000, 300000, 200000 };
    for (int i = 0; i < 3; i++) {
        CHECK(pf[i].amount == want[i] && proof_script_is(&pf[i], p, P2PKH), "coin %d signs its parent's amount and script", i);
    }
    // the signer builds every input it is given, one output of their total less the fee
    UInt256 t[3]; uint32_t vo[3]; BRRawTxBindingSpent sp[3]; int64_t totalIn = 0;
    for (int i = 0; i < 3; i++) { t[i] = u[i].txid; vo[i] = (uint32_t)u[i].vout; sp[i].txid = t[i]; sp[i].vout = vo[i]; sp[i].amount = pf[i].amount; totalIn += pf[i].amount; }
    uint64_t dest = (uint64_t)(totalIn - 52400); size_t n; int64_t fee;
    uint8_t *s = spend(t, vo, 3, &dest, 1, &n);
    CHECK(BRRawTxBindingFeePaid(s, n, sp, 3, &fee) == 1 && fee == 52400, "the fee read back is 52400 (got %lld)", (long long)fee);
    CHECK((int64_t)dest + fee == 900000, "the inputs equal destination plus fee");
    free(s); free(a.b); free(b.b); free(c.b);
}

static void case_gate_signed_not_exact_not_sent(void)
{
    Parent a = honest(0, 400000);
    UInt256 t[2] = { from_display(a.id), from_display("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee") };
    uint32_t vo[2] = { 0, 0 }; uint64_t pay = 400000; size_t n; int64_t fee = 7;
    uint8_t *s = spend(t, vo, 2, &pay, 1, &n);
    BRRawTxBindingSpent sp[1] = { { t[0], 0, 400000 } };
    CHECK(BRRawTxBindingFeePaid(s, n, sp, 1, &fee) == 0 && fee == 0, "a transaction that does not spend exactly its proven inputs has no fee: not sent");
    free(s); free(a.b);
}

static void case_gate_zero_refused_before_parent(void)
{
    Parent a = honest(0, 0);
    BRRawTxBindingUtxo u = coin(a.id, 0, 0, P2PKH);
    BRRawTxBindingParent p = parent(a.id, a.b, a.n);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(admit(&u, 1, &p, 1, &pf, &v) == BR_RAW_TX_REFUSE_NON_POSITIVE && v.utxo == 0, "a zero amount is refused even when its parent states zero");
    CHECK(pf.state == BR_RAW_TX_PROOF_REFUSED, "and nothing is signed");
    free(a.b);
}

static void case_gate_order(void)
{
    // AmountProvenanceGateTest's amount cases, for the order: unreachable before anything, non-positive
    // before any parent is read (a contradicting parent beside a negative amount still reads NON_POSITIVE).
    Parent a = honest(0, 500000);
    BRRawTxBindingUtxo u[2] = { coin(a.id, 0, 1, P2PKH), coin(a.id, 0, -1, P2PKH) };
    BRRawTxBindingParent p = parent(a.id, a.b, a.n);
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingAdmit(0, u, 2, &p, 1, pf, &v) == BR_RAW_TX_REFUSE_UNREACHABLE, "unreachable first");
    CHECK(BRRawTxBindingAdmit(1, u, 2, &p, 1, pf, &v) == BR_RAW_TX_REFUSE_NON_POSITIVE && v.utxo == 1, "then the first non-positive coin");
    BRRawTxBindingUtxo ok[2] = { coin(a.id, 0, 500000, P2PKH), coin(a.id, 0, 500000, NULL) };
    CHECK(BRRawTxBindingAdmit(1, ok, 2, &p, 1, pf, &v) == BR_RAW_TX_ADMIT && v.provenCount == 2, "positive amounts with a reachable backend: admitted");
    CHECK(BRRawTxBindingAdmit(1, ok, 2, &p, 1, pf, NULL) == BR_RAW_TX_REFUSE_BAD_ARGUMENT, "no verdict: refused");
    free(a.b);
}

// ---- SweepSignsProvenInputsSourceGateTest's intent ---------------------------------------------

static void case_signer_values_from_parent_only(void)
{
    // The signer's inputs are assembled from PROVEN proofs only, and a proof carries the parent's values:
    // the script is a range of the parent's own bytes, whatever case the lookup reported it in, and a coin
    // that is not proven carries no amount and no script to assemble from.
    Parent a = honest(0, 500000), b = honest(0, 300000);
    char up[80]; strcpy(up, P2PKH); upper(up);
    BRRawTxBindingUtxo u[2] = { coin(a.id, 0, 500000, up), coin(b.id, 0, 300000, P2PKH) };
    BRRawTxBindingParent p[1] = { parent(a.id, a.b, a.n) };
    BRRawTxProof pf[2]; BRRawTxBindingVerdict v;
    CHECK(admit(u, 2, p, 1, pf, &v) == BR_RAW_TX_ADMIT, "admitted");
    CHECK(pf[0].state == BR_RAW_TX_PROOF_PROVEN && pf[0].parent == 0 && proof_script_is(&pf[0], p, P2PKH),
          "the proven script is the parent's bytes (lowercase hex), not the reported text");
    CHECK(pf[0].scriptOffset + pf[0].scriptLen <= a.n, "inside the parent's bytes");
    CHECK(pf[1].state == BR_RAW_TX_PROOF_UNPROVEN && pf[1].amount == 0 && pf[1].scriptLen == 0 && pf[1].parent == SIZE_MAX,
          "an unproven coin carries nothing a signer could use");
    free(a.b); free(b.b);
}

// ---- edge cases of the adaptation ------------------------------------------------------------

static void case_first_parent_and_listing(void)
{
    // A coin's parent is the first filed under its txid; an outpoint repeated among the unproven is listed
    // once, at its first coin; coins sharing a parent read it once and agree.
    Parent a = honest(1, 7000);
    BRRawTxBindingUtxo u[5] = { coin(a.id, 1, 7000, NULL), coin(GENESIS_TXID, 0, 1, NULL), coin(a.id, 0, 1000, "51"),
                                coin(GENESIS_TXID, 0, 1, NULL), coin(GENESIS_TXID, 3, 1, NULL) };
    BRRawTxBindingParent p[2] = { parent(a.id, a.b, a.n), parent(a.id, NULL, 0) };
    BRRawTxProof pf[5]; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(u, 5, p, 2, pf, &v) == BR_RAW_TX_BINDING_CHECKED && v.provenCount == 2, "two proven from the first parent");
    CHECK(pf[1].listed && !pf[3].listed && pf[4].listed && v.unprovenListed == 2, "a repeated unproven outpoint is listed once");
    BRRawTxBindingParent q[2] = { parent(a.id, NULL, 0), parent(a.id, a.b, a.n) };
    CHECK(BRRawTxBindingCheck(u, 5, q, 2, pf, &v) == BR_RAW_TX_BINDING_CHECKED && v.provenCount == 0, "the first filed entry is the parent, even when unreadable");
    CHECK(BRRawTxBindingCheck(u, 5, NULL, 2, pf, &v) == BR_RAW_TX_BINDING_BAD_ARGUMENT, "NULL parents with a count: bad argument");
    CHECK(BRRawTxBindingCheck(u, 5, p, 2, NULL, &v) == BR_RAW_TX_BINDING_BAD_ARGUMENT, "NULL proofs: bad argument");
    CHECK(BRRawTxBindingCheck(NULL, 0, NULL, 0, NULL, &v) == BR_RAW_TX_BINDING_CHECKED, "nothing to check: checked");
    BRRawTxBindingUtxo neg = coin(a.id, -1, 1000, NULL);
    CHECK(BRRawTxBindingCheck(&neg, 1, p, 2, pf, &v) == BR_RAW_TX_BINDING_CONTRADICTED && v.reason == BR_RAW_TX_CONTRADICTION_NO_SUCH_OUTPUT, "a negative vout names no output");
    UInt256 x;
    CHECK(!BRRawTxIdFromDisplayHex("zz", 2, &x) && !BRRawTxIdFromDisplayHex(GENESIS_TXID, 63, &x), "a txid is exactly 64 hex digits");
    free(a.b);
}

static void case_huge_amount_parent_unproven(void)
{
    // Android reads output amounts as a signed 64-bit integer; a parent with an output above INT64_MAX
    // could not be read, so its coins are unproven (not contradicted), whichever output they name.
    OutSpec outs[2] = { { 5000, { 0x51 }, 1 }, { (uint64_t)INT64_MAX + 9, { 0x51 }, 1 } };
    InSpec in = { rand_hash(), 0, 30, 0 };
    size_t n; uint8_t *b = build_tx(&in, 1, outs, 2, &n);
    char id[65]; display_hex(must_id(b, n), id);
    BRRawTxBindingUtxo u = coin(id, 0, 5000, "51");
    BRRawTxBindingParent p = parent(id, b, n);
    BRRawTxProof pf; BRRawTxBindingVerdict v;
    CHECK(BRRawTxBindingCheck(&u, 1, &p, 1, &pf, &v) == BR_RAW_TX_BINDING_CHECKED && pf.state == BR_RAW_TX_PROOF_UNPROVEN, "unproven");
    free(b);
}

// ---- COST -------------------------------------------------------------------------------------

static double now(void) { return (double)clock() / CLOCKS_PER_SEC; }

static void case_large_inputs(void)
{
    const size_t NP = 50000, NU = 100000, WIDE = 20000;
    BRRawTxBindingParent *p = malloc((NP + 1) * sizeof(*p));
    BRRawTxBindingUtxo *u = malloc((NU + WIDE) * sizeof(*u));
    BRRawTxProof *pf = malloc((NU + WIDE) * sizeof(*pf));
    uint8_t **bytes = malloc((NP + 1) * sizeof(uint8_t *));
    for (size_t i = 0; i < NP; i++) {
        OutSpec outs[2] = { { 1000 + i, { 0x51 }, 1 }, { 2000 + i, { 0x52 }, 1 } };
        InSpec in = { rand_hash(), 0, 8, 0 };
        size_t n; bytes[i] = build_tx(&in, 1, outs, 2, &n);
        p[i].txid = must_id(bytes[i], n); p[i].bytes = bytes[i]; p[i].len = n;
    }
    // one parent with WIDE outputs, named by WIDE coins from the last output down
    BRTransaction *tx = BRTransactionNew();
    UInt256 h = rand_hash(); uint8_t sig[8] = { 1 };
    BRTransactionAddInput(tx, h, 0, 0, NULL, 0, sig, 8, NULL, 0, 0xffffffff);
    uint8_t s51 = 0x51;
    for (size_t i = 0; i < WIDE; i++) BRTransactionAddOutput(tx, 10 + i, &s51, 1);
    size_t wn = BRTransactionSerialize(tx, NULL, 0);
    bytes[NP] = malloc(wn); BRTransactionSerialize(tx, bytes[NP], wn); BRTransactionFree(tx);
    p[NP].txid = must_id(bytes[NP], wn); p[NP].bytes = bytes[NP]; p[NP].len = wn;
    for (size_t i = 0; i < NU; i++) {
        size_t k = (i * 7919) % NP;
        if (i % 4 == 3) { u[i].txid = rand_hash(); u[i].vout = 0; u[i].amount = 1; }           // unproven
        else if (i % 4 == 2 && i > 2) { u[i] = u[i - 1]; continue; }                            // repeated
        else { u[i].txid = p[k].txid; u[i].vout = (int64_t)(i % 2); u[i].amount = (int64_t)((i % 2 ? 2000 : 1000) + k); }
        u[i].scriptHex = (i % 2) ? "52" : "51"; u[i].scriptHexLen = 2;
        if (i % 4 == 3) u[i].scriptHex = NULL, u[i].scriptHexLen = 0;
    }
    for (size_t i = 0; i < WIDE; i++) {
        BRRawTxBindingUtxo *w = &u[NU + i];
        w->txid = p[NP].txid; w->vout = (int64_t)(WIDE - 1 - i); w->amount = (int64_t)(10 + WIDE - 1 - i);
        w->scriptHex = "51"; w->scriptHexLen = 2;
    }
    // shuffle the parents: the lookup is by txid
    for (size_t i = NP + 1; i > 1; i--) { size_t j = rnd() % i; BRRawTxBindingParent t = p[i - 1]; p[i - 1] = p[j]; p[j] = t; }
    BRRawTxBindingVerdict v;
    double t0 = now();
    BRRawTxBindingOutcome oc = BRRawTxBindingCheck(u, NU + WIDE, p, NP + 1, pf, &v);
    double t1 = now();
    size_t proven = 0, unproven = 0;
    for (size_t i = 0; i < NU + WIDE; i++) { proven += pf[i].state == BR_RAW_TX_PROOF_PROVEN; unproven += pf[i].state == BR_RAW_TX_PROOF_UNPROVEN; }
    CHECK(oc == BR_RAW_TX_BINDING_CHECKED && proven == NU - NU / 4 + WIDE && unproven == NU / 4 && v.unprovenListed == NU / 4,
          "large profile: %zu proven, %zu unproven, %zu listed (outcome %d)", proven, unproven, v.unprovenListed, oc);
    CHECK(pf[NU].amount == (int64_t)(10 + WIDE - 1), "the wide parent's last output is proven at its amount");
    printf("INFO: %zu coins over %zu parents checked in %.2fs\n", NU + WIDE, NP + 1, t1 - t0);
    CHECK(t1 - t0 < 30.0, "the large check took %.2fs (a quadratic loop would take far longer)", t1 - t0);

    // a fee over WIDE inputs, spent[] in reverse order
    BRRawTxBindingSpent *sp = malloc(WIDE * sizeof(*sp));
    tx = BRTransactionNew();
    for (size_t i = 0; i < WIDE; i++) {
        UInt256 x = rand_hash();
        BRTransactionAddInput(tx, x, (uint32_t)(i % 3), 0, NULL, 0, sig, 8, NULL, 0, 0xffffffff);
        sp[WIDE - 1 - i].txid = x; sp[WIDE - 1 - i].vout = (int64_t)(i % 3); sp[WIDE - 1 - i].amount = 100;
    }
    BRTransactionAddOutput(tx, WIDE * 100 - 777, &s51, 1);
    size_t fn = BRTransactionSerialize(tx, NULL, 0); uint8_t *fb = malloc(fn); BRTransactionSerialize(tx, fb, fn); BRTransactionFree(tx);
    int64_t fee = 0;
    t0 = now();
    int ok = BRRawTxBindingFeePaid(fb, fn, sp, WIDE, &fee);
    t1 = now();
    CHECK(ok && fee == 777, "the fee over %zu inputs is 777 (got %d %lld)", WIDE, ok, (long long)fee);
    CHECK(t1 - t0 < 10.0, "the large fee took %.2fs", t1 - t0);
    printf("INFO: fee over %zu inputs in %.2fs\n", WIDE, t1 - t0);
    free(fb); free(sp);
    for (size_t i = 0; i <= NP; i++) free(bytes[i]);
    free(bytes); free(p); free(u); free(pf);
}

// ---- driver -----------------------------------------------------------------------------------

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "id_fixed_vectors", case_id_fixed_vectors },
    { "id_hostile_vectors", case_id_hostile_vectors },
    { "id_fuzz", case_id_fuzz },
    { "rules_fuzz", case_rules_fuzz },
    { "fee_fuzz", case_fee_fuzz },
    { "genesis_parent_proves", case_genesis_parent_proves },
    { "segwit_parent_proves", case_segwit_parent_proves },
    { "witness_hashing_id_contradicts", case_witness_hashing_id_contradicts },
    { "other_tx_contradicts", case_other_tx_contradicts },
    { "amount_index_script_contradict", case_amount_index_script_contradict },
    { "one_contradiction_refuses_profile", case_one_contradiction_refuses_profile },
    { "no_parent_or_unreadable_unproven", case_no_parent_or_unreadable_unproven },
    { "proven_carries_parent_script", case_proven_carries_parent_script },
    { "fee_is_inputs_less_outputs", case_fee_is_inputs_less_outputs },
    { "fee_needs_exact_outpoints", case_fee_needs_exact_outpoints },
    { "gate_amount_lower", case_gate_amount_lower },
    { "gate_amount_higher", case_gate_amount_higher },
    { "gate_other_tx", case_gate_other_tx },
    { "gate_missing_index", case_gate_missing_index },
    { "gate_other_script", case_gate_other_script },
    { "gate_no_parent_held_back", case_gate_no_parent_held_back },
    { "gate_unreadable_held_back", case_gate_unreadable_held_back },
    { "gate_honest_sign_and_fee", case_gate_honest_sign_and_fee },
    { "gate_signed_not_exact_not_sent", case_gate_signed_not_exact_not_sent },
    { "gate_zero_refused_before_parent", case_gate_zero_refused_before_parent },
    { "gate_order", case_gate_order },
    { "signer_values_from_parent_only", case_signer_values_from_parent_only },
    { "first_parent_and_listing", case_first_parent_and_listing },
    { "huge_amount_parent_unproven", case_huge_amount_parent_unproven },
    { "large_inputs", case_large_inputs },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(CASES) / sizeof(CASES[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s list | <case>\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) { for (size_t i = 0; i < n; i++) printf("%s\n", CASES[i].name); return 0; }
    load_vectors();
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            printf("PASS: %d checks\n", g_pass);
            printf("RESULT %s %s\n", CASES[i].name, g_fail ? "fail" : "pass");
            return g_fail ? 1 : 0;
        }
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
