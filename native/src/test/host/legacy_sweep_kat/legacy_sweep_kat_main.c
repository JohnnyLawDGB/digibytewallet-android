// Host KAT: the Recover-funds sweep signer, moved from the JNI bridge into the core (BRLegacySweep.h), signs exactly
// what the bridge signs.
//
// Each sweep is run twice: through the bridge's own NativeBridge_buildAndSignLegacySweep (jni_derive.c, compiled here
// over identity_sign_bytes_kat's host JNI stand-ins) and through BRLegacySweepBuildAndSign (legacy_sweep_kat_core.c).
// A sweep agrees only when both refuse, or both return the same signed transaction byte for byte (the bridge's hex
// against the core's serialization); a core refusal must also be the typed refusal the scenario names, and a
// signed transaction must be the one the bridge passed to its signed-spend gate.
//
// SCENARIOS
//   pinned_*      the Android device tests, case for case: LegacySweepSignedTxKatTest (P2PKH, its pinned hex),
//                 LegacySweepSegwitKatTest (P2WPKH, its pinned hex), LegacySweepAmountGuardTest (three cases),
//                 foreign_taproot_sign_kat (a P2TR key-path input from a foreign seed), plus a P2SH-P2WPKH input.
//   boundary_*    each fee rule at, below and above its boundary: the size estimate, the 1000 floor, the 5% refusal
//                 (at fee*20, one below and one above; a lone input exempt), dust at fee+546.
//   refuse_*      every typed refusal: no inputs, seed length, script length (0, 128 accepted, 129), destination,
//                 a script no derived key matches; uint64 wraparound of the fee and of totalIn handled alike.
//   gate_*        the bridge's signed-spend gate refusing: the bridge returns nothing, the core still signs (the gate
//                 is the platform's) and the bytes the bridge gated are the core's.
//   proof         amounts and scripts taken from BRRawTxBindingCheck proofs (BRLegacySweepInputFromProof).
//   fuzz          KAT_FUZZ sweeps (default 2000; KAT_FUZZ_SEED varies them) over every derivation profile and format, 1..12 inputs, random and
//                 boundary amounts and fee rates, mismatched and malformed scripts, bad destinations and seeds.
//   keys_wiped    no block the core frees holds a derived private key or the seed (the allocation recorder below);
//                 the caller's seed is unchanged.
//   stack_probe   informational only: the stack below the caller is searched for key material after a sweep, on
//                 both sides, and what is found is printed. Not a pass/fail case (frames differ between the sides).
//
// Comparison arms (BR_LEGACY_SWEEP_RED_*, on the core's unit only): run.sh requires each to fail exactly its
// scenarios and pass every other one.
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include <jni.h>
#include "BRLegacySweep.h"
#include "BRDerivationProfiles.h"
#include "BRBIP39Mnemonic.h"

// ---- the core side (legacy_sweep_kat_core.c) ---------------------------------------------------------------------
BRLegacySweepResult kat_core_sweep(const uint8_t *seed, size_t seedLen, const char *hmacKey,
                                   const uint32_t *prefix, size_t prefixLen,
                                   const BRLegacySweepInput *inputs, size_t count,
                                   const char *dest, uint64_t feePerKb, BRLegacySweepFee *fee,
                                   uint8_t **serOut, size_t *serLenOut, BRTransaction **txOut);
BRLegacySweepResult kat_core_sweep_null_out(const uint8_t *seed, size_t seedLen);
int kat_core_from_proof(BRLegacySweepInput *in, const BRRawTxProof *proof, const BRRawTxBindingParent *parents,
                        size_t parentCount);

jstring JNICALL NativeBridge_buildAndSignLegacySweep(JNIEnv *env, jobject thiz, jbyteArray seedBytes, jstring hmacKey,
                                                     jintArray prefixPath, jobjectArray txidsHex, jintArray vouts,
                                                     jlongArray amounts, jintArray chainIndices,
                                                     jintArray addressIndices, jobjectArray scriptPubKeysHex,
                                                     jstring destAddress, jlong feePerKb);

// ---- the allocation recorder (the core's unit only) --------------------------------------------------------------
// Every block the core's unit allocates carries its size in front; at free it is searched for the secrets the
// current sweep derives (g_secrets) and the seed, then released.
#define KAT_SECRETS_MAX 64
static uint8_t g_secrets[KAT_SECRETS_MAX][32];
static size_t g_secretCount = 0;
static uint8_t g_seedSecret[64];
static size_t g_seedSecretLen = 0;
static int g_freedHits = 0;
static int g_freedBlocks = 0;

void *kat_real_malloc(size_t n) { return malloc(n); }
void kat_real_free(void *p) { free(p); }

static int holds(const uint8_t *p, size_t n, const uint8_t *needle, size_t nlen)
{
    if (nlen == 0 || n < nlen) return 0;
    for (size_t i = 0; i + nlen <= n; i++) if (p[i] == needle[0] && memcmp(p + i, needle, nlen) == 0) return 1;
    return 0;
}

void *kat_malloc(size_t n)
{
    uint8_t *b = malloc(n + 16);
    if (! b) return NULL;
    memcpy(b, &n, sizeof(n));
    return b + 16;
}
void *kat_calloc(size_t c, size_t s)
{
    if (s && c > SIZE_MAX / s - 16) return NULL;
    uint8_t *p = kat_malloc(c * s);
    if (p) memset(p, 0, c * s);
    return p;
}
void kat_free(void *p)
{
    if (! p) return;
    uint8_t *b = (uint8_t *)p - 16;
    size_t n;
    memcpy(&n, b, sizeof(n));
    g_freedBlocks++;
    for (size_t i = 0; i < g_secretCount; i++) if (holds(p, n, g_secrets[i], 32)) g_freedHits++;
    if (g_seedSecretLen >= 16 && holds(p, n, g_seedSecret, g_seedSecretLen)) g_freedHits++;
    free(b);
}

// ---- the bridge's externals --------------------------------------------------------------------------------------
static int g_gateAnswer = 1, g_gateCalls = 0;
static uint8_t *g_gated = NULL;
static size_t g_gatedLen = 0;
int wallet_signed_spend_gate(BRTransaction *tx, const char *who)
{
    (void)who;
    g_gateCalls++;
    free(g_gated);
    g_gatedLen = BRTransactionSerialize(tx, NULL, 0);
    g_gated = malloc(g_gatedLen);
    BRTransactionSerialize(tx, g_gated, g_gatedLen);
    return g_gateAnswer;
}
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }

// ---- host JNI env --------------------------------------------------------------------------------------------------
typedef struct { jsize len; void *data; } HostArray;     // byte, int, long and object arrays
typedef struct { char *text; } HostString;

#define ARENA_MAX 4096
static void *g_arena[ARENA_MAX];
static size_t g_arenaCount = 0;
static void *keep(void *p) { if (g_arenaCount < ARENA_MAX) g_arena[g_arenaCount++] = p; return p; }
static void arena_free(void)
{
    while (g_arenaCount) free(g_arena[--g_arenaCount]);
}
static HostArray *hostArray(const void *data, size_t elem, jsize len)
{
    HostArray *a = keep(calloc(1, sizeof(*a)));
    a->len = len;
    a->data = keep(calloc((size_t)(len ? len : 1), elem));
    if (data && len) memcpy(a->data, data, elem * (size_t)len);
    return a;
}
static HostString *hostString(const char *s)
{
    HostString *h = keep(calloc(1, sizeof(*h)));
    h->text = keep(strdup(s));
    return h;
}

static jsize h_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HostArray *)a)->len; }
static jbyte *h_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *c)
{ (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }   // the live array: the bridge must not write it
static void h_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static jint *h_GetIntArrayElements(JNIEnv *e, jintArray a, jboolean *c)
{ (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseIntArrayElements(JNIEnv *e, jintArray a, jint *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static jlong *h_GetLongArrayElements(JNIEnv *e, jlongArray a, jboolean *c)
{ (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseLongArrayElements(JNIEnv *e, jlongArray a, jlong *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static jobject h_GetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i) { (void)e; return ((jobject *)((HostArray *)a)->data)[i]; }
static void h_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e; (void)o; }
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c)
{ (void)e; if (c) *c = JNI_TRUE; return strdup(((HostString *)s)->text); }
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; free((void *)c); }
static jstring h_NewStringUTF(JNIEnv *e, const char *b) { (void)e; return hostString(b); }

static const struct JNINativeInterface g_table = {
    .GetArrayLength = h_GetArrayLength,
    .GetByteArrayElements = h_GetByteArrayElements,
    .ReleaseByteArrayElements = h_ReleaseByteArrayElements,
    .GetIntArrayElements = h_GetIntArrayElements,
    .ReleaseIntArrayElements = h_ReleaseIntArrayElements,
    .GetLongArrayElements = h_GetLongArrayElements,
    .ReleaseLongArrayElements = h_ReleaseLongArrayElements,
    .GetObjectArrayElement = h_GetObjectArrayElement,
    .DeleteLocalRef = h_DeleteLocalRef,
    .GetStringUTFChars = h_GetStringUTFChars,
    .ReleaseStringUTFChars = h_ReleaseStringUTFChars,
    .NewStringUTF = h_NewStringUTF,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;

// ---- checks --------------------------------------------------------------------------------------------------------
static int g_fail = 0, g_scenarioFail = 0, g_verbose = 0;
static const char *g_scenario = "";
static void check(int c, const char *d)
{
    if (! c) { if (g_scenarioFail < 4 || g_verbose) printf("   FAIL: %s: %s\n", g_scenario, d); g_fail++; g_scenarioFail++; }
}
static void begin(const char *name) { g_scenario = name; g_scenarioFail = 0; }
static void end(void) { printf("%s: %s\n", g_scenarioFail ? "SCENARIO-FAIL" : "SCENARIO-PASS", g_scenario); }

static void to_hex(const uint8_t *b, size_t n, char *out)
{
    static const char H[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) { out[2 * i] = H[b[i] >> 4]; out[2 * i + 1] = H[b[i] & 15]; }
    out[2 * n] = 0;
}

// ---- one sweep, both sides ------------------------------------------------------------------------------------------
typedef struct {
    const uint8_t *seed; size_t seedLen;
    const char *hmac;
    const uint32_t *prefix; size_t prefixLen;
    const BRLegacySweepInput *in; size_t n;
    const char *dest;
    uint64_t feePerKb;
} Sweep;

typedef struct {
    BRLegacySweepResult r;
    BRLegacySweepFee fee;
    char *coreHex;      // malloc'd; NULL on refusal
    char *bridgeHex;    // malloc'd; NULL on refusal
    BRTransaction *tx;  // the core's, when signed
} Both;

// The bridge's arguments from the core's: txids as display hex (reversed), scripts as hex, amounts and feePerKb
// as the jlong bit patterns the bridge casts back to uint64_t.
static char *run_bridge(const Sweep *s)
{
    jsize n = (jsize)s->n;
    HostArray *seed = hostArray(s->seed, 1, (jsize)s->seedLen);
    jint *prefix = calloc(s->prefixLen ? s->prefixLen : 1, sizeof(jint));
    for (size_t i = 0; i < s->prefixLen; i++) prefix[i] = (jint)s->prefix[i];
    HostArray *pref = hostArray(prefix, sizeof(jint), (jsize)s->prefixLen);
    free(prefix);
    jobject *txids = calloc(n ? n : 1, sizeof(jobject)), *scripts = calloc(n ? n : 1, sizeof(jobject));
    jint *vouts = calloc(n ? n : 1, sizeof(jint)), *chains = calloc(n ? n : 1, sizeof(jint)), *idx = calloc(n ? n : 1, sizeof(jint));
    jlong *amts = calloc(n ? n : 1, sizeof(jlong));
    for (jsize i = 0; i < n; i++) {
        uint8_t disp[32];
        char h[65], *sh = malloc(s->in[i].scriptLen * 2 + 1);
        for (int k = 0; k < 32; k++) disp[k] = s->in[i].txHash.u8[31 - k];
        to_hex(disp, 32, h);
        txids[i] = hostString(h);
        if (s->in[i].script) to_hex(s->in[i].script, s->in[i].scriptLen, sh); else sh[0] = 0;
        scripts[i] = hostString(sh);
        free(sh);
        vouts[i] = (jint)s->in[i].vout;
        chains[i] = (jint)s->in[i].chain;
        idx[i] = (jint)s->in[i].index;
        amts[i] = (jlong)s->in[i].amount;
    }
    jstring r = NativeBridge_buildAndSignLegacySweep(env, NULL, seed, hostString(s->hmac), pref,
        hostArray(txids, sizeof(jobject), n), hostArray(vouts, sizeof(jint), n), hostArray(amts, sizeof(jlong), n),
        hostArray(chains, sizeof(jint), n), hostArray(idx, sizeof(jint), n), hostArray(scripts, sizeof(jobject), n),
        hostString(s->dest), (jlong)s->feePerKb);
    free(txids); free(scripts); free(vouts); free(chains); free(idx); free(amts);
    char *out = r ? strdup(((HostString *)r)->text) : NULL;
    if (memcmp(seed->data, s->seed, s->seedLen) != 0) check(0, "the bridge wrote the caller's seed");
    arena_free();
    return out;
}

static Both run_both(const Sweep *s, int keepTx)
{
    Both b;
    uint8_t *ser = NULL;
    size_t serLen = 0;
    memset(&b, 0, sizeof(b));
    g_gateCalls = 0;
    b.bridgeHex = run_bridge(s);
    b.r = kat_core_sweep(s->seed, s->seedLen, s->hmac, s->prefix, s->prefixLen, s->in, s->n, s->dest, s->feePerKb,
                         &b.fee, &ser, &serLen, keepTx ? &b.tx : NULL);
    if (ser) {
        b.coreHex = malloc(serLen * 2 + 1);
        to_hex(ser, serLen, b.coreHex);
        if (b.bridgeHex && g_gateAnswer) {
            check(g_gatedLen == serLen && memcmp(g_gated, ser, serLen) == 0,
                  "the transaction the bridge gated is not the core's");
        }
        free(ser);
    }
    return b;
}

static void both_free(Both *b) { free(b->coreHex); free(b->bridgeHex); if (b->tx) BRTransactionFree(b->tx); }

// Agreement, and the core's typed result.
static void agree(const Sweep *s, BRLegacySweepResult want, const char *what)
{
    char d[256];
    Both b = run_both(s, 0);
    if (g_gateAnswer) {
        snprintf(d, sizeof(d), "%s: bridge %s, core %s (result %d)", what, b.bridgeHex ? "signed" : "refused",
                 b.coreHex ? "signed" : "refused", (int)b.r);
        check((b.bridgeHex == NULL) == (b.coreHex == NULL), d);
        if (b.bridgeHex && b.coreHex) {
            snprintf(d, sizeof(d), "%s: the signed transactions differ", what);
            check(strcmp(b.bridgeHex, b.coreHex) == 0, d);
        }
    }
    snprintf(d, sizeof(d), "%s: core result %d, want %d", what, (int)b.r, (int)want);
    check(b.r == want, d);
    both_free(&b);
}

// ---- keys and scripts ---------------------------------------------------------------------------------------------
#define HARD 0x80000000u
static void derive(BRKey *k, const uint8_t *seed, size_t seedLen, const char *hmac, const uint32_t *prefix,
                   size_t prefixLen, uint32_t chain, uint32_t index)
{
    uint32_t p[8];
    memcpy(p, prefix, prefixLen * sizeof(uint32_t));
    p[prefixLen] = chain;
    p[prefixLen + 1] = index;
    BRBIP32PrivKeyArrayPath(k, seed, seedLen, hmac, p, prefixLen + 2);
}

// The scriptPubKey of k in format f (BR_DERIVATION_FORMAT_*); its length.
static size_t script_for(BRKey *k, int f, uint8_t *out)
{
    UInt160 h = BRKeyHash160(k);
    switch (f) {
    case BR_DERIVATION_FORMAT_P2PKH:
        out[0] = OP_DUP; out[1] = OP_HASH160; out[2] = 20; memcpy(out + 3, h.u8, 20); out[23] = OP_EQUALVERIFY;
        out[24] = OP_CHECKSIG; return 25;
    case BR_DERIVATION_FORMAT_P2WPKH:
        out[0] = OP_0; out[1] = 20; memcpy(out + 2, h.u8, 20); return 22;
    case BR_DERIVATION_FORMAT_P2SH_P2WPKH: {
        uint8_t w[22] = { OP_0, 20 };
        UInt160 sh;
        memcpy(w + 2, h.u8, 20);
        BRHash160(&sh, w, sizeof(w));
        out[0] = OP_HASH160; out[1] = 20; memcpy(out + 2, sh.u8, 20); out[22] = OP_EQUAL; return 23;
    }
    case BR_DERIVATION_FORMAT_P2TR:
        out[0] = OP_1; out[1] = 32; BRKeyTaprootOutputKey(k, out + 2); return 34;
    }
    return 0;
}

static const char *kMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *kDest = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe";
static uint8_t g_seed[64];

static UInt256 fill256(uint8_t b) { UInt256 u; memset(u.u8, b, 32); return u; }

// Inputs at prefix/chain/index paying the key's own script in format f.
typedef struct { BRLegacySweepInput in[16]; uint8_t scripts[16][40]; size_t n; } Inputs;
static void add_input(Inputs *x, const uint8_t *seed, size_t seedLen, const char *hmac, const uint32_t *prefix,
                      size_t prefixLen, uint32_t chain, uint32_t index, int f, UInt256 txid, uint32_t vout,
                      uint64_t amount)
{
    BRKey k;
    BRLegacySweepInput *in = &x->in[x->n];
    derive(&k, seed, seedLen, hmac, prefix, prefixLen, chain, index);
    in->scriptLen = script_for(&k, f, x->scripts[x->n]);
    in->script = x->scripts[x->n];
    BRKeyClean(&k);
    in->txHash = txid; in->vout = vout; in->amount = amount; in->chain = chain; in->index = index;
    x->n++;
}

// Every derived secret of a sweep, for the allocation recorder.
static void set_secrets(const Sweep *s)
{
    g_secretCount = 0;
    for (size_t i = 0; i < s->n && i < KAT_SECRETS_MAX; i++) {
        BRKey k;
        if (s->seedLen == 0 || s->seedLen > 64) break;
        derive(&k, s->seed, s->seedLen, s->hmac, s->prefix, s->prefixLen, s->in[i].chain, s->in[i].index);
        memcpy(g_secrets[g_secretCount++], k.secret.u8, 32);
        BRKeyClean(&k);
    }
    g_seedSecretLen = s->seedLen <= 64 ? s->seedLen : 0;
    if (g_seedSecretLen) memcpy(g_seedSecret, s->seed, g_seedSecretLen);
}

// ---- the stack probe (informational) ------------------------------------------------------------------------------
#define PROBE_BYTES 49152
__attribute__((noinline, no_sanitize("address", "undefined")))
static int probe_stack(void)
{
    volatile uint8_t probe[PROBE_BYTES];
    int hits = 0;
    uint8_t copy[PROBE_BYTES];
    for (size_t i = 0; i < PROBE_BYTES; i++) copy[i] = probe[i];
    for (size_t i = 0; i < g_secretCount; i++) hits += holds(copy, sizeof(copy), g_secrets[i], 32);
    if (g_seedSecretLen >= 16) hits += 100 * holds(copy, sizeof(copy), g_seedSecret, 32);
    memset(copy, 0, sizeof(copy));
    return hits;
}
__attribute__((noinline)) static char *bridge_only(const Sweep *s) { return run_bridge(s); }
__attribute__((noinline)) static BRLegacySweepResult core_only(const Sweep *s)
{
    uint8_t *ser = NULL; size_t n = 0;
    BRLegacySweepResult r = kat_core_sweep(s->seed, s->seedLen, s->hmac, s->prefix, s->prefixLen, s->in, s->n,
                                           s->dest, s->feePerKb, NULL, &ser, &n, NULL);
    free(ser);
    return r;
}

// ---- PRNG ---------------------------------------------------------------------------------------------------------
static uint64_t g_rng = 0x9E3779B97F4A7C15ull;
static uint64_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static uint64_t rndn(uint64_t n) { return n ? rnd() % n : 0; }

// The fee the rules give, computed here from the rules as written (not from the header).
static uint64_t model_fee(size_t n, uint64_t feePerKb)
{
    size_t est = 10 + n * 160 + 34;
    uint64_t fee = ((uint64_t)est * feePerKb) / 1000;
    return fee < 1000 ? 1000 : fee;
}

int main(int argc, char **argv)
{
    (void)argc; (void)argv;
    setvbuf(stdout, NULL, _IONBF, 0);
    g_verbose = getenv("KAT_VERBOSE") != NULL;
    // Another fuzz sequence (the red arms' required sets assume the default one).
    if (getenv("KAT_FUZZ_SEED")) g_rng ^= strtoull(getenv("KAT_FUZZ_SEED"), NULL, 0) * 0x2545F4914F6CDD1Dull;
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);

    const uint32_t m0[1] = { 0 | HARD };
    const uint32_t m84[3] = { 84 | HARD, 20 | HARD, 0 | HARD };
    const uint32_t m49[3] = { 49 | HARD, 20 | HARD, 0 | HARD };
    const uint32_t m86[3] = { 86 | HARD, 20 | HARD, 0 | HARD };

    // ── pinned: LegacySweepSignedTxKatTest ─────────────────────────────────────────────────────────────────────────
    begin("pinned_signedtx_p2pkh");
    {
        static const char *kPinned =
            "01000000011111111111111111111111111111111111111111111111111111111111111111"
            "000000006a47304402206b176e0b47dd319c6439a280457480e8c30fd865e94bd260ad718a"
            "c60e32cfbb022030503bbaeee82039abb7b7437725989d81e51045b449da7f76e17c209263"
            "67f201210208dd15cbb7af1394d7b565d52ef4480687a1a8774875d5e906758acdea24b150"
            "ffffffff015015cd1d000000001976a914424242424242424242424242424242424242424288ac00000000";
        uint8_t spk[25] = { 0x76, 0xa9, 0x14, 0x78, 0xf4, 0xf1, 0xef, 0x3b, 0x18, 0xbc, 0x8b, 0x3c, 0xdf, 0x90, 0xd6,
                            0xc6, 0xe9, 0x8b, 0xf6, 0x33, 0x6f, 0x6f, 0x69, 0x88, 0xac };
        BRLegacySweepInput in = { fill256(0x11), 0, 500000000ull, 0, 0, spk, sizeof(spk) };
        Sweep s = { g_seed, 64, "DigiByte seed", m0, 1, &in, 1, kDest, 100000 };
        Both b = run_both(&s, 0);
        check(b.r == BR_LEGACY_SWEEP_SIGNED, "the core signs");
        check(b.coreHex && strcmp(b.coreHex, kPinned) == 0, "the core's hex is the pinned hex");
        check(b.bridgeHex && strcmp(b.bridgeHex, kPinned) == 0, "the bridge's hex is the pinned hex");
        check(g_gateCalls == 1, "the bridge asked its gate once");
        both_free(&b);
    }
    end();

    // ── pinned: LegacySweepSegwitKatTest ───────────────────────────────────────────────────────────────────────────
    begin("pinned_segwit_p2wpkh");
    {
        static const char *kPinned =
            "0100000000010122222222222222222222222222222222222222222222"
            "222222222222222222220000000000ffffffff015015cd1d0000000019"
            "76a914424242424242424242424242424242424242424288ac02483045"
            "022100e9f5b167afa20f6d11bbbe6d292adb21177b9ab186a90605f1b2"
            "6932da34b5a9022039d1f68d963aae6504766651a112bd7478dee3eeac"
            "c7a462726f40633693346c0121038c33be7fcf1b24783fe6bb6fbee5c9"
            "3b36d618ef17e96f51052db7d3faed46de00000000";
        Inputs x = { .n = 0 };
        add_input(&x, g_seed, 64, "Bitcoin seed", m84, 3, 0, 0, BR_DERIVATION_FORMAT_P2WPKH, fill256(0x22), 0, 500000000ull);
        Sweep s = { g_seed, 64, "Bitcoin seed", m84, 3, x.in, 1, kDest, 100000 };
        Both b = run_both(&s, 0);
        check(b.r == BR_LEGACY_SWEEP_SIGNED, "the core signs");
        check(b.coreHex && strcmp(b.coreHex, kPinned) == 0, "the core's hex is the pinned hex");
        check(b.bridgeHex && strcmp(b.bridgeHex, kPinned) == 0, "the bridge's hex is the pinned hex");
        both_free(&b);
    }
    end();

    // ── pinned: LegacySweepAmountGuardTest ─────────────────────────────────────────────────────────────────────────
    {
        // m/0'/0/0 and m/0'/0/1 under "DigiByte seed", real scripts; the destination is m/0'/0/2's address.
        BRKey k2;
        char dest2[64];
        derive(&k2, g_seed, 64, "DigiByte seed", m0, 1, 0, 2);
        BRKeyAddress(&k2, dest2, sizeof(dest2));
        BRKeyClean(&k2);
        const uint64_t cases[3][2] = { { 300000, 300000 }, { 30000000, 30000000 }, { 5000000000ull, 5000000000ull } };
        const char *names[3] = { "pinned_amount_guard_under_reported", "pinned_amount_guard_normal",
                                 "pinned_amount_guard_over_reported" };
        const BRLegacySweepResult want[3] = { BR_LEGACY_SWEEP_FEE_TOO_HIGH, BR_LEGACY_SWEEP_SIGNED, BR_LEGACY_SWEEP_SIGNED };
        for (int c = 0; c < 3; c++) {
            begin(names[c]);
            Inputs x = { .n = 0 };
            add_input(&x, g_seed, 64, "DigiByte seed", m0, 1, 0, 0, BR_DERIVATION_FORMAT_P2PKH, fill256(0x11), 0, cases[c][0]);
            add_input(&x, g_seed, 64, "DigiByte seed", m0, 1, 0, 1, BR_DERIVATION_FORMAT_P2PKH, fill256(0x22), 0, cases[c][1]);
            Sweep s = { g_seed, 64, "DigiByte seed", m0, 1, x.in, 2, dest2, 100000 };
            agree(&s, want[c], "the guard case");
            BRLegacySweepFee f;
            uint8_t *ser = NULL; size_t n = 0;
            kat_core_sweep(s.seed, 64, s.hmac, m0, 1, x.in, 2, dest2, 100000, &f, &ser, &n, NULL);
            free(ser);
            check(f.estSize == 364 && f.fee == 36400, "2 inputs: estSize 364, fee 36400 (the test's figures)");
            end();
        }
    }

    // ── pinned: foreign_taproot_sign_kat, through the sweep ────────────────────────────────────────────────────────
    begin("pinned_foreign_taproot");
    {
        Inputs x = { .n = 0 };
        add_input(&x, g_seed, 64, "Bitcoin seed", m86, 3, 0, 0, BR_DERIVATION_FORMAT_P2TR, fill256(0x11), 0, 100000);
        Sweep s = { g_seed, 64, "Bitcoin seed", m86, 3, x.in, 1, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGNED, "a P2TR input");
        Both b = run_both(&s, 1);
        check(b.tx && b.tx->inputs[0].witLen >= 64, "the input carries a witness (the Schnorr signature)");
        check(b.tx && b.tx->inputs[0].sigLen == 0, "a key-path spend leaves scriptSig empty");
        both_free(&b);
    }
    end();

    begin("pinned_bip49_p2sh_p2wpkh");
    {
        Inputs x = { .n = 0 };
        add_input(&x, g_seed, 64, "Bitcoin seed", m49, 3, 0, 0, BR_DERIVATION_FORMAT_P2SH_P2WPKH, fill256(0x33), 1, 250000000ull);
        Sweep s = { g_seed, 64, "Bitcoin seed", m49, 3, x.in, 1, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGNED, "a P2SH-P2WPKH input");
        Both b = run_both(&s, 1);
        check(b.tx && b.tx->inputs[0].sigLen == 23 && b.tx->inputs[0].witLen > 0, "redeemScript push and a witness");
        both_free(&b);
    }
    end();

    // ── boundaries ─────────────────────────────────────────────────────────────────────────────────────────────────
    Inputs one = { .n = 0 }, two = { .n = 0 };
    add_input(&one, g_seed, 64, "DigiByte seed", m0, 1, 0, 0, BR_DERIVATION_FORMAT_P2PKH, fill256(0x11), 0, 0);
    add_input(&two, g_seed, 64, "DigiByte seed", m0, 1, 0, 0, BR_DERIVATION_FORMAT_P2PKH, fill256(0x11), 0, 0);
    add_input(&two, g_seed, 64, "DigiByte seed", m0, 1, 1, 3, BR_DERIVATION_FORMAT_P2PKH, fill256(0x12), 2, 0);
    Sweep s1 = { g_seed, 64, "DigiByte seed", m0, 1, one.in, 1, kDest, 100000 };
    Sweep s2 = { g_seed, 64, "DigiByte seed", m0, 1, two.in, 2, kDest, 100000 };

    begin("boundary_size");   // 3 inputs at 100 sat/byte: 10 + 480 + 34 = 524 bytes, fee 52400, output totalIn - 52400
    {
        Inputs x = { .n = 0 };
        for (uint32_t i = 0; i < 3; i++)
            add_input(&x, g_seed, 64, "DigiByte seed", m0, 1, 0, i, BR_DERIVATION_FORMAT_P2PKH, fill256(0x40 + i), i, 10000000);
        Sweep s = { g_seed, 64, "DigiByte seed", m0, 1, x.in, 3, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGNED, "three inputs");
        Both b = run_both(&s, 1);
        check(b.fee.estSize == 524 && b.fee.fee == 52400, "estSize 524, fee 52400");
        check(b.tx && b.tx->outCount == 1 && b.tx->outputs[0].amount == 30000000 - 52400, "one output of totalIn - fee");
        both_free(&b);
    }
    end();

    begin("boundary_floor_signs");   // feePerKb 1: 0.204 sat -> 0 -> the 1000 floor; 1547 leaves 547
    one.in[0].amount = 1547; s1.feePerKb = 1;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "1547 at the floor");
    { Both b = run_both(&s1, 1); check(b.tx && b.tx->outputs[0].amount == 547, "output 547"); both_free(&b); }
    end();
    begin("boundary_floor_zero_rate");   // feePerKb 0 is the floor too
    one.in[0].amount = 5000; s1.feePerKb = 0;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "feePerKb 0");
    { Both b = run_both(&s1, 1); check(b.tx && b.tx->outputs[0].amount == 4000, "output 4000"); both_free(&b); }
    end();
    begin("boundary_floor_dust");   // 1546 = 1000 + 546: refused
    one.in[0].amount = 1546; s1.feePerKb = 1;
    agree(&s1, BR_LEGACY_SWEEP_DUST_OUTPUT, "1546 at the floor");
    end();
    begin("boundary_floor_edge");   // feePerKb 4901: 204 * 4901 / 1000 = 999 -> 1000; 4902 -> 1000 exactly
    one.in[0].amount = 100000; s1.feePerKb = 4901;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "999 raised to 1000");
    { Both b = run_both(&s1, 0); check(b.fee.fee == 1000, "fee 1000"); both_free(&b); }
    s1.feePerKb = 4902;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "exactly 1000");
    s1.feePerKb = 4906;   // 1000.8 -> 1000
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "1000.8 truncated");
    { Both b = run_both(&s1, 0); check(b.fee.fee == 1000, "fee 1000 (truncated, not rounded)"); both_free(&b); }
    end();

    s1.feePerKb = 100000;   // fee 20400
    begin("boundary_dust_at");   // 20946 = 20400 + 546: refused
    one.in[0].amount = 20946;
    agree(&s1, BR_LEGACY_SWEEP_DUST_OUTPUT, "fee + 546");
    end();
    begin("boundary_dust_above");
    one.in[0].amount = 20947;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "fee + 547");
    end();
    begin("boundary_dust_below");
    one.in[0].amount = 20945;
    agree(&s1, BR_LEGACY_SWEEP_DUST_OUTPUT, "fee + 545");
    end();
    begin("boundary_ratio_single_exempt");   // a lone input paying 50% in fee still signs
    one.in[0].amount = 40800;
    agree(&s1, BR_LEGACY_SWEEP_SIGNED, "a lone input, fee 50%");
    end();

    // 2 inputs at 100 sat/byte: fee 36400, fee * 20 = 728000.
    begin("boundary_ratio_at");
    two.in[0].amount = 364000; two.in[1].amount = 364000;
    agree(&s2, BR_LEGACY_SWEEP_FEE_TOO_HIGH, "totalIn == fee * 20");
    end();
    begin("boundary_ratio_below");
    two.in[0].amount = 300000; two.in[1].amount = 300000;
    agree(&s2, BR_LEGACY_SWEEP_FEE_TOO_HIGH, "totalIn < fee * 20");
    end();
    begin("boundary_ratio_above");
    two.in[0].amount = 364000; two.in[1].amount = 364001;
    agree(&s2, BR_LEGACY_SWEEP_SIGNED, "totalIn == fee * 20 + 1");
    end();
    begin("boundary_ratio_floor");   // at feePerKb 1 the floor sets fee 1000 for 2 inputs: the line is 20000
    s2.feePerKb = 1;
    two.in[0].amount = 10000; two.in[1].amount = 10000;
    agree(&s2, BR_LEGACY_SWEEP_FEE_TOO_HIGH, "20000 at the floor");
    two.in[1].amount = 10001;
    agree(&s2, BR_LEGACY_SWEEP_SIGNED, "20001 at the floor");
    s2.feePerKb = 100000;
    end();

    // ── refusals ───────────────────────────────────────────────────────────────────────────────────────────────────
    two.in[0].amount = 10000000; two.in[1].amount = 10000000;
    begin("refuse_no_inputs");
    { Sweep s = s2; s.n = 0; agree(&s, BR_LEGACY_SWEEP_NO_INPUTS, "no inputs"); }
    end();
    begin("refuse_seed_length");
    {
        uint8_t big[65]; memset(big, 7, sizeof(big));
        Sweep s = s2; s.seed = big; s.seedLen = 65;
        agree(&s, BR_LEGACY_SWEEP_BAD_SEED, "65 bytes");
        s.seedLen = 0;
        agree(&s, BR_LEGACY_SWEEP_BAD_SEED, "0 bytes");
        check(kat_core_sweep_null_out(g_seed, 64) == BR_LEGACY_SWEEP_BAD_ARGUMENT, "no txOut: BAD_ARGUMENT");
    }
    end();
    begin("refuse_short_seed_signs");   // a 16-byte seed is a seed the bridge takes; its own keys sign
    {
        Inputs x = { .n = 0 };
        add_input(&x, g_seed, 16, "Bitcoin seed", m84, 3, 1, 5, BR_DERIVATION_FORMAT_P2WPKH, fill256(0x55), 0, 9000000);
        Sweep s = { g_seed, 16, "Bitcoin seed", m84, 3, x.in, 1, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGNED, "16-byte seed");
    }
    end();
    begin("refuse_script_length");
    {
        uint8_t junk[129];
        for (int i = 0; i < 129; i++) junk[i] = (uint8_t)(0x51 + i % 7);
        Sweep s = s2;
        BRLegacySweepInput in[2] = { two.in[0], two.in[1] };
        s.in = in;
        in[1].script = junk; in[1].scriptLen = 129;
        agree(&s, BR_LEGACY_SWEEP_BAD_INPUT, "129 bytes");
        { Both b = run_both(&s, 0); check(b.fee.input == 1, "the refused input is named"); both_free(&b); }
        in[1].scriptLen = 128;   // accepted, but no key matches it
        agree(&s, BR_LEGACY_SWEEP_SIGN_FAILED, "128 bytes, no key");
        in[1].scriptLen = 0;
        agree(&s, BR_LEGACY_SWEEP_BAD_INPUT, "empty");
        in[0].scriptLen = 0;
        { Both b = run_both(&s, 0); check(b.fee.input == 0, "the first refused input is named"); both_free(&b); }
    }
    end();
    begin("refuse_destination");
    {
        const char *bad[] = { "", "notanaddress", "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxf", "dgb1qxxxxxxxx" };
        for (int i = 0; i < 4; i++) { Sweep s = s2; s.dest = bad[i]; agree(&s, BR_LEGACY_SWEEP_BAD_DESTINATION, bad[i]); }
        // Other encodings sign: bech32, taproot, P2SH destinations of the seed's own keys.
        BRKey k; char a[96];
        derive(&k, g_seed, 64, "Bitcoin seed", m84, 3, 0, 9);
        BRKeySegwitAddress(&k, a, sizeof(a), OP_0); { Sweep s = s2; s.dest = a; agree(&s, BR_LEGACY_SWEEP_SIGNED, a); }
        BRKeyTaprootAddress(&k, a, sizeof(a)); { Sweep s = s2; s.dest = a; agree(&s, BR_LEGACY_SWEEP_SIGNED, a); }
        { Sweep s = s2; s.dest = "SNGj7dBdH5Lt3JSZgBBnTYkQrWKCF7mfB3"; Both b = run_both(&s, 0);
          check((b.bridgeHex == NULL) == (b.coreHex == NULL), "a P2SH destination: both alike"); both_free(&b); }
        BRKeyClean(&k);
    }
    end();
    begin("refuse_unmatched_key");   // the script is m/0'/0/1's, the path says index 0
    {
        Inputs x = { .n = 0 };
        add_input(&x, g_seed, 64, "DigiByte seed", m0, 1, 0, 1, BR_DERIVATION_FORMAT_P2PKH, fill256(0x11), 0, 9000000);
        x.in[0].index = 0;
        Sweep s = { g_seed, 64, "DigiByte seed", m0, 1, x.in, 1, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGN_FAILED, "a script of another key");
        s.hmac = "Bitcoin seed"; x.in[0].index = 1;   // the right path under the wrong HMAC key
        agree(&s, BR_LEGACY_SWEEP_SIGN_FAILED, "the wrong HMAC key");
    }
    end();
    begin("refuse_wraparound");   // the bridge's uint64 arithmetic, wrapped alike
    {
        Sweep s = s2;
        BRLegacySweepInput in[2] = { two.in[0], two.in[1] };
        s.in = in;
        in[0].amount = 1ull << 63; in[1].amount = 1ull << 63;   // totalIn wraps to 0
        agree(&s, BR_LEGACY_SWEEP_FEE_TOO_HIGH, "totalIn wraps to 0");
        in[0].amount = 10000000; in[1].amount = 10000000;
        s.feePerKb = UINT64_MAX;                                // estSize * feePerKb wraps; fee * 20 wraps
        { Both b = run_both(&s, 0);
          check((b.bridgeHex == NULL) == (b.coreHex == NULL) && (! b.coreHex || strcmp(b.coreHex, b.bridgeHex) == 0),
                "feePerKb UINT64_MAX: both alike"); both_free(&b); }
        s.feePerKb = (uint64_t)INT64_MAX;
        { Both b = run_both(&s, 0);
          check((b.bridgeHex == NULL) == (b.coreHex == NULL) && (! b.coreHex || strcmp(b.coreHex, b.bridgeHex) == 0),
                "feePerKb INT64_MAX: both alike"); both_free(&b); }
        s.feePerKb = 0x0CCCCCCCCCCCCCCDull;   // fee * 20 wraps to a small number for 1 input's estSize... checked alike
        { Both b = run_both(&s, 0);
          check((b.bridgeHex == NULL) == (b.coreHex == NULL) && (! b.coreHex || strcmp(b.coreHex, b.bridgeHex) == 0),
                "feePerKb near 2^64/20: both alike"); both_free(&b); }
    }
    end();

    // ── the gate stays the bridge's ─────────────────────────────────────────────────────────────────────────────────
    begin("gate_refusal_is_bridge_side");
    {
        Sweep s = s2;
        g_gateAnswer = 0;
        Both b = run_both(&s, 0);
        check(b.bridgeHex == NULL, "the bridge returns nothing when its gate refuses");
        check(g_gateCalls == 1, "the bridge asked its gate once, after signing");
        check(b.r == BR_LEGACY_SWEEP_SIGNED && b.coreHex, "the core signs (it asks no gate)");
        char *gh = malloc(g_gatedLen * 2 + 1); to_hex(g_gated, g_gatedLen, gh);
        check(b.coreHex && strcmp(gh, b.coreHex) == 0, "the bytes the bridge gated are the core's");
        free(gh);
        both_free(&b);
        g_gateAnswer = 1;
        Sweep r = s; r.feePerKb = 100000; BRLegacySweepInput in[2] = { two.in[0], two.in[1] }; in[0].amount = 1; in[1].amount = 1; r.in = in;
        g_gateCalls = 0;
        char *h = run_bridge(&r); free(h);
        check(g_gateCalls == 0, "a refused sweep never reaches the gate");
    }
    end();

    // ── amounts and scripts from BRRawTxBinding proofs ──────────────────────────────────────────────────────────────
    begin("proof");
    {
        // A signed parent paying m/84'/20'/0'/0/{0,1} at outputs 0 and 1.
        BRKey k0, k1; uint8_t sp0[40], sp1[40];
        derive(&k0, g_seed, 64, "Bitcoin seed", m84, 3, 0, 0);
        derive(&k1, g_seed, 64, "Bitcoin seed", m84, 3, 0, 1);
        size_t l0 = script_for(&k0, BR_DERIVATION_FORMAT_P2WPKH, sp0), l1 = script_for(&k1, BR_DERIVATION_FORMAT_P2WPKH, sp1);
        BRKeyClean(&k0); BRKeyClean(&k1);
        BRTransaction *p = BRTransactionNew();
        const uint8_t sig[2] = { 0x01, 0x51 };
        BRTransactionAddInput(p, fill256(0x99), 0, 0, sp0, l0, sig, sizeof(sig), NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(p, 7000000, sp0, l0);
        BRTransactionAddOutput(p, 8000000, sp1, l1);
        size_t pl = BRTransactionSerialize(p, NULL, 0);
        uint8_t *pb = malloc(pl);
        BRTransactionSerialize(p, pb, pl);
        BRTransactionFree(p);
        UInt256 pid;
        check(BRRawTxId(pb, pl, &pid), "setup: the parent has an id");
        char h0[64], h1[64]; to_hex(sp0, l0, h0); to_hex(sp1, l1, h1);
        BRRawTxBindingUtxo u[2] = { { pid, 0, 7000000, h0, strlen(h0) }, { pid, 1, 8000000, h1, strlen(h1) } };
        BRRawTxBindingParent par[1] = { { pid, pb, pl } };
        BRRawTxProof pr[2]; BRRawTxBindingVerdict v;
        check(BRRawTxBindingCheck(u, 2, par, 1, pr, &v) == BR_RAW_TX_BINDING_CHECKED && v.provenCount == 2,
              "setup: both coins proven");
        BRLegacySweepInput in[2];
        memset(in, 0, sizeof(in));
        for (int i = 0; i < 2; i++) {
            in[i].txHash = pid; in[i].vout = (uint32_t)i; in[i].chain = 0; in[i].index = (uint32_t)i;
            check(kat_core_from_proof(&in[i], &pr[i], par, 1) == 1, "a PROVEN proof fills the input");
        }
        check(in[0].amount == 7000000 && in[1].amount == 8000000 && in[0].scriptLen == l0 &&
              memcmp(in[1].script, sp1, l1) == 0, "the parent's amount and script");
        Sweep s = { g_seed, 64, "Bitcoin seed", m84, 3, in, 2, kDest, 100000 };
        agree(&s, BR_LEGACY_SWEEP_SIGNED, "a sweep of proven coins");
        BRRawTxProof bad = pr[0]; BRLegacySweepInput t = in[0];
        bad.state = BR_RAW_TX_PROOF_UNPROVEN;
        check(kat_core_from_proof(&t, &bad, par, 1) == 0 && t.amount == in[0].amount, "an UNPROVEN proof fills nothing");
        bad = pr[0]; bad.parent = 1;
        check(kat_core_from_proof(&t, &bad, par, 1) == 0, "a parent outside parents[] is refused");
        bad = pr[0]; bad.scriptOffset = pl;
        check(kat_core_from_proof(&t, &bad, par, 1) == 0, "a script range past the parent is refused");
        free(pb);
    }
    end();

    // ── fuzz ──────────────────────────────────────────────────────────────────────────────────────────────────────
    begin("fuzz");
    {
        long iters = getenv("KAT_FUZZ") ? atol(getenv("KAT_FUZZ")) : 2000;
        size_t pc;
        const BRDerivationProfile *profiles = BRDerivationProfiles(&pc);
        long signedN = 0, refusedN = 0, mism = 0;
        static const uint64_t rates[] = { 0, 1, 999, 1000, 4901, 4902, 100000, 100000, 100000, 250000, 1000000 };
        const char *dests[] = { kDest, "dgb1qxxxxxxxx", "", "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn" };
        for (long it = 0; it < iters; it++) {
            uint8_t seed[65];
            size_t seedLen = 64;
            for (int i = 0; i < 65; i++) seed[i] = (uint8_t)rnd();
            uint64_t sl = rndn(40);
            if (sl == 0) seedLen = 16; else if (sl == 1) seedLen = 32; else if (sl == 2) seedLen = 65; else if (sl == 3) seedLen = 0;
            const BRDerivationProfile *pf = &profiles[rndn(pc)];
            size_t n = 1 + (size_t)(rndn(4) == 0 ? rndn(12) : rndn(3));
            Inputs x = { .n = 0 };
            const size_t keySeedLen = (seedLen >= 1 && seedLen <= 64) ? seedLen : 64;
            for (size_t i = 0; i < n; i++) {
                int f = pf->formats[rndn(pf->formatCount)];
                if (rndn(8) == 0) f = (int)rndn(BR_DERIVATION_FORMAT_COUNT);   // the same key in another encoding
                add_input(&x, seed, keySeedLen, pf->hmacKey, pf->path, pf->pathLen, (uint32_t)rndn(2),
                          (uint32_t)rndn(40), f, fill256((uint8_t)rnd()), (uint32_t)rndn(5), 0);
                x.in[i].txHash.u64[0] = rnd();
                uint64_t m = rndn(60);
                if (m == 0) { for (size_t k = 0; k < x.in[i].scriptLen; k++) x.scripts[i][k] ^= (uint8_t)rndn(2); }   // maybe mismatched
                else if (m == 1) x.in[i].scriptLen = 0;
                else if (m == 2) x.in[i].index ^= 1;                                                             // wrong key
            }
            uint64_t rate = rndn(10) == 0 ? rnd() : rates[rndn(sizeof(rates) / sizeof(*rates))];
            uint64_t fee = model_fee(n, rate);
            uint64_t mode = rndn(6), total;
            if (mode == 0) total = fee * 20 + rndn(3) - 1;                        // the 5% line
            else if (mode == 1) total = fee + 546 + rndn(3) - 1;                  // the dust line
            else if (mode == 2) total = rndn(fee * 40 + 1);
            else if (mode == 3) total = rnd();                                    // anything, wraps included
            else total = fee * 20 + rndn(2100000000000000ull);
            uint64_t left = total;
            for (size_t i = 0; i < n; i++) {
                uint64_t a = (i + 1 == n) ? left : rndn(left + 1);
                if (mode == 3) a = rnd() >> rndn(64);
                x.in[i].amount = a; left -= (mode == 3 ? 0 : a);
            }
            const char *dest = rndn(12) == 0 ? dests[1 + rndn(3)] : dests[0];
            Sweep s = { seed, seedLen, pf->hmacKey, pf->path, pf->pathLen, x.in, n, dest, rate };
            Both b = run_both(&s, 0);
            int same = ((b.bridgeHex == NULL) == (b.coreHex == NULL)) &&
                       (! b.coreHex || strcmp(b.coreHex, b.bridgeHex) == 0);
            // The typed refusal against the rules as written (only where the inputs pin the outcome).
            if (same && seedLen >= 1 && seedLen <= 64) {
                int badScript = 0;
                uint64_t tin = 0;
                for (size_t i = 0; i < n; i++) { if (x.in[i].scriptLen == 0) badScript = 1; tin += x.in[i].amount; }
                uint8_t ds[64];
                int badDest = BRAddressScriptPubKey(ds, 48, dest) == 0;
                BRLegacySweepResult want;
                if (badScript) want = BR_LEGACY_SWEEP_BAD_INPUT;
                else if (badDest) want = BR_LEGACY_SWEEP_BAD_DESTINATION;
                else if (n > 1 && tin <= fee * 20) want = BR_LEGACY_SWEEP_FEE_TOO_HIGH;
                else if (tin <= fee + 546) want = BR_LEGACY_SWEEP_DUST_OUTPUT;
                else want = b.r == BR_LEGACY_SWEEP_SIGN_FAILED ? BR_LEGACY_SWEEP_SIGN_FAILED : BR_LEGACY_SWEEP_SIGNED;
                if (b.r != want) same = 0;
                if (b.r == BR_LEGACY_SWEEP_SIGNED && b.fee.outAmount != tin - fee) same = 0;
            } else if (same && b.r != BR_LEGACY_SWEEP_BAD_SEED) same = 0;
            if (! same) {
                mism++;
                if (mism <= 3 || g_verbose)
                    printf("   fuzz %ld: profile %s n=%zu rate=%llu mode=%llu result=%d bridge=%s core=%s\n", it,
                           pf->label, n, (unsigned long long)rate, (unsigned long long)mode, (int)b.r,
                           b.bridgeHex ? "signed" : "refused", b.coreHex ? "signed" : "refused");
            }
            if (b.coreHex) signedN++; else refusedN++;
            both_free(&b);
        }
        printf("   fuzz: %ld sweeps, %ld signed, %ld refused, %ld disagreements\n", iters, signedN, refusedN, mism);
        check(mism == 0, "every fuzzed sweep: the bridge's answer, and the typed refusal the rules give");
        check(signedN > iters / 5 && refusedN > iters / 10, "the fuzz reaches both signed and refused sweeps");
    }
    end();

    // ── keys wiped ─────────────────────────────────────────────────────────────────────────────────────────────────
    begin("keys_wiped");
    {
        uint8_t seedCopy[64];
        memcpy(seedCopy, g_seed, 64);
        Sweep cases[5];
        BRLegacySweepInput bad[2] = { two.in[0], two.in[1] };
        bad[1].scriptLen = 0;
        BRLegacySweepInput low[2] = { two.in[0], two.in[1] };
        low[0].amount = 1; low[1].amount = 1;
        cases[0] = s2;                                     // signed
        cases[1] = s2; cases[1].in = bad;                  // BAD_INPUT after one key was derived
        cases[2] = s2; cases[2].dest = "nope";             // BAD_DESTINATION with every key derived
        cases[3] = s2; cases[3].in = low;                  // FEE_TOO_HIGH
        cases[4] = s2; cases[4].in = two.in; cases[4].n = 2; // signed again
        int totalHits = 0;
        for (int c = 0; c < 5; c++) {
            set_secrets(&cases[c]);
            g_freedHits = 0; g_freedBlocks = 0;
            uint8_t *ser = NULL; size_t n = 0;
            kat_core_sweep(cases[c].seed, cases[c].seedLen, cases[c].hmac, cases[c].prefix, cases[c].prefixLen,
                           cases[c].in, cases[c].n, cases[c].dest, cases[c].feePerKb, NULL, &ser, &n, NULL);
            free(ser);
            check(g_freedBlocks >= 1, "the recorder saw the core's blocks");
            totalHits += g_freedHits;
        }
        check(totalHits == 0, "no block the core freed held a derived key or the seed");
        check(memcmp(seedCopy, g_seed, 64) == 0, "the caller's seed is unchanged");
        g_secretCount = 0; g_seedSecretLen = 0;
    }
    end();

    // ── stack probe (informational) ─────────────────────────────────────────────────────────────────────────────────
    {
        set_secrets(&s2);
        char *h = bridge_only(&s2); free(h);
        int bh = probe_stack();
        core_only(&s2);
        int ch = probe_stack();
        printf("   stack_probe: after the bridge %d key / %d seed match(es); after the core %d key / %d seed match(es)\n",
               bh % 100, bh / 100, ch % 100, ch / 100);
        g_secretCount = 0; g_seedSecretLen = 0;
    }

    free(g_gated);
    printf("%s: legacy_sweep_kat (%d failure%s)\n", g_fail ? "FAIL" : "PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
