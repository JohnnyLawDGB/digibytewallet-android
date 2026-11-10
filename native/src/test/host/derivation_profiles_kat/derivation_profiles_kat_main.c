// Host KAT: the core's Recover funds derivation (BRDerivationProfiles.h, BRRecoveryDerive.h) gives the same
// bytes as Android's bridge (jni_derive.c mnemonicToSeed / deriveAddresses) for every built-in profile, and
// its table is Android's (DerivationProfile.kt BUILT_INS, read by kotlin_profiles.py into kotlin_profiles.h).
//
// The bridge is the real jni_derive.c, compiled over host_jni/ (stand-ins for <jni.h>, <android/log.h>)
// with the env below. Every comparison is slot for slot: a null bridge slot must be an empty core slot.
//
// Cases (RESULT <case> pass|fail):
//   profile_<i>        RED under DERIVATION_PROFILES_RED for i = 5 only (with independent_profiles): the core's profile i equals Kotlin's
//                      field for field, and for each of four seeds and each format Kotlin probes it as, the
//                      core's addresses (from the core's table) equal the bridge's (from Kotlin's table) over
//                      the whole starting window, 200 receive + 100 change.
//                      Every slot of both must hold an address: a shared regression that derives nothing
//                      cannot pass by agreeing on empty slots.
//   seed_nul_refused   RED under RECOVERY_SEED_NUL_RED: a passphrase with a NUL byte is refused by both.
//   RED under RECOVERY_ENCODER_RED (P2SH-P2WPKH written with the legacy script version byte): profile_5,
//                      all_formats and independent_profiles, the cases that encode format 2.
//   independent_profiles  every profile and format, four seeds, first 10 + 10, against
//                      vectors/recovery-derivation/independent.txt (an implementation independent of the core).
//   address_probes     BRRecoveryAddressForKey refuses, without asserting, a NULL key, a buffer below
//                      BR_RECOVERY_ADDRESS_SLOT and P2WPKH of an uncompressed key.
//   GUARDS (every arm): profile_count, hard_bit, seed_match, seed_bounds, phrase_with_nul, derive_refusals,
//                      slot_positions, independent_vectors, caller_seed_untouched, address_probes.
//
// argv: vectors/recovery-derivation/independent.txt, then the vectors/derivation/*.txt files (all from the core).

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jni.h"
#include "BRRecoveryDerive.h"
#include "kotlin_profiles.h"
#include "wallet_spend_gate.h"   /* jni_derive.c's one wallet question, stubbed below */

jbyteArray JNICALL NativeBridge_mnemonicToSeed(JNIEnv *env, jobject thiz, jbyteArray phraseBytes, jbyteArray passphrase);
jobjectArray JNICALL NativeBridge_deriveAddresses(JNIEnv *env, jobject thiz, jbyteArray seedBytes, jstring hmacKey,
                                                  jintArray prefixPath, jint gapExternal, jint gapInternal,
                                                  jint addressFormat);

// jni_derive.c's signers ask jni_wallet.c's gate; nothing here signs.
int wallet_signed_spend_gate(BRTransaction *tx, const char *who) { (void)tx; (void)who; return 0; }
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }

/* ---- the host env: every object lives in an arena freed after each comparison ---------------------------- */

enum { H_BYTES, H_INTS, H_OBJS, H_STR, H_CLASS };
typedef struct { int kind; jsize len; void *data; } HObj;

static HObj **g_arena = NULL;
static size_t g_arenaLen = 0, g_arenaCap = 0;

static HObj *hnew(int kind, jsize len, size_t elem)
{
    HObj *o = calloc(1, sizeof(*o));

    o->kind = kind;
    o->len = len;
    o->data = calloc((size_t)(len > 0 ? len : 1), elem);
    if (g_arenaLen == g_arenaCap) {
        g_arenaCap = g_arenaCap ? 2 * g_arenaCap : 1024;
        g_arena = realloc(g_arena, g_arenaCap * sizeof(*g_arena));
    }
    g_arena[g_arenaLen++] = o;
    return o;
}

static void arena_free(void)
{
    for (size_t i = 0; i < g_arenaLen; i++) { free(g_arena[i]->data); free(g_arena[i]); }
    g_arenaLen = 0;
}

static HObj *hbytes(const void *b, size_t n) { HObj *o = hnew(H_BYTES, (jsize)n, 1); if (n) memcpy(o->data, b, n); return o; }
static HObj *hints(const uint32_t *v, size_t n)
{
    HObj *o = hnew(H_INTS, (jsize)n, sizeof(jint));
    for (size_t i = 0; i < n; i++) ((jint *)o->data)[i] = (jint)v[i];
    return o;
}
static HObj *hstr(const char *s) { HObj *o = hnew(H_STR, (jsize)strlen(s), 1); free(o->data); o->data = strdup(s); return o; }

static jclass e_FindClass(JNIEnv *e, const char *n) { (void)e; (void)n; return hnew(H_CLASS, 0, 1); }
static void e_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e; (void)o; }
static jstring e_NewStringUTF(JNIEnv *e, const char *s) { (void)e; return s ? hstr(s) : NULL; }
static const char *e_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)s)->data; }
static void e_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }
static jsize e_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HObj *)a)->len; }
static jobjectArray e_NewObjectArray(JNIEnv *e, jsize n, jclass c, jobject init)
{
    (void)e; (void)c;
    HObj *o = hnew(H_OBJS, n, sizeof(jobject));
    for (jsize i = 0; i < n; i++) ((jobject *)o->data)[i] = init;
    return o;
}
static jobject e_GetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i) { (void)e; return ((jobject *)((HObj *)a)->data)[i]; }
static void e_SetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i, jobject v) { (void)e; ((jobject *)((HObj *)a)->data)[i] = v; }
static jbyteArray e_NewByteArray(JNIEnv *e, jsize n) { (void)e; return hnew(H_BYTES, n, 1); }
// The direct pointer, never a copy: the bridge must not write through it, and caller_seed_untouched checks it.
static jbyte *e_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *p, jint m) { (void)e; (void)a; (void)p; (void)m; }
static void e_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, jbyte *b) { (void)e; memcpy(b, (jbyte *)((HObj *)a)->data + s, (size_t)n); }
static void e_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, const jbyte *b) { (void)e; memcpy((jbyte *)((HObj *)a)->data + s, b, (size_t)n); }
static jint *e_GetIntArrayElements(JNIEnv *e, jintArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseIntArrayElements(JNIEnv *e, jintArray a, jint *p, jint m) { (void)e; (void)a; (void)p; (void)m; }
static void e_GetIntArrayRegion(JNIEnv *e, jintArray a, jsize s, jsize n, jint *b) { (void)e; memcpy(b, (jint *)((HObj *)a)->data + s, (size_t)n * sizeof(jint)); }
static jlong *e_GetLongArrayElements(JNIEnv *e, jlongArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseLongArrayElements(JNIEnv *e, jlongArray a, jlong *p, jint m) { (void)e; (void)a; (void)p; (void)m; }

static const struct JNINativeInterface g_table = {
    e_FindClass, e_DeleteLocalRef, e_NewStringUTF, e_GetStringUTFChars, e_ReleaseStringUTFChars, e_GetArrayLength,
    e_NewObjectArray, e_GetObjectArrayElement, e_SetObjectArrayElement, e_NewByteArray, e_GetByteArrayElements,
    e_ReleaseByteArrayElements, e_GetByteArrayRegion, e_SetByteArrayRegion, e_GetIntArrayElements,
    e_ReleaseIntArrayElements, e_GetIntArrayRegion, e_GetLongArrayElements, e_ReleaseLongArrayElements,
};
static JNIEnv g_envp = &g_table;
static JNIEnv *const ENV = &g_envp;

/* ---- reporting ------------------------------------------------------------------------------------------ */

static int g_caseFail = 0, g_anyFail = 0;
static void failf(const char *c, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void failf(const char *c, const char *fmt, ...)
{
    va_list ap;

    if (g_caseFail++ < 5) {
        printf("FAIL: %s: ", c);
        va_start(ap, fmt);
        vprintf(fmt, ap);
        va_end(ap);
        printf("\n");
    }
}
static void begin(void) { g_caseFail = 0; }
static void end(const char *c)
{
    printf("RESULT %s %s\n", c, g_caseFail ? "fail" : "pass");
    if (g_caseFail) g_anyFail = 1;
}

/* ---- the two implementations -------------------------------------------------------------------------- */

static const struct { const char *mnemonic, *passphrase, *independentSeedHex; } SEEDS[] = {
    { "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "",
      "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4" },
    { "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "TREZOR",
      "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04" },
    // BIP39 (Trezor) published vectors: with "TREZOR" this is the second vector's seed.
    { "legal winner thank year wave sausage worth useful legal winner thank yellow", "", NULL },
    { "legal winner thank year wave sausage worth useful legal winner thank yellow", "TREZOR",
      "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607" },
};
#define SEED_COUNT (sizeof(SEEDS) / sizeof(SEEDS[0]))
static uint8_t g_seeds[SEED_COUNT][64];

// Bridge mnemonicToSeed: returns 1 and the seed, or 0 when it returned null. pass NULL = a null array.
static int bridge_seed(const void *phrase, size_t phraseLen, const void *pass, size_t passLen, uint8_t out[64])
{
    jbyteArray r = NativeBridge_mnemonicToSeed(ENV, NULL, hbytes(phrase, phraseLen), pass ? hbytes(pass, passLen) : NULL);
    int ok = r && ((HObj *)r)->len == 64;

    if (ok) memcpy(out, ((HObj *)r)->data, 64);
    arena_free();
    return ok;
}

// Bridge and core over the same inputs. Returns 1 when they agree slot for slot (both refusing counts).
static int g_requireAddresses = 0;   // set by the cases where every slot must hold an address
static int same_addresses(const char *c, const uint8_t *seed, size_t seedLen,
                          const char *bHmac, const uint32_t *bPath, size_t bPathLen, int bFormat,
                          const char *cHmac, const uint32_t *cPath, size_t cPathLen, int cFormat,
                          int32_t gapE, int32_t gapI)
{
    const size_t total = (gapE > 0 ? (size_t)gapE : 0) + (gapI > 0 ? (size_t)gapI : 0);
    char *out = malloc((total ? total : 1) * BR_RECOVERY_ADDRESS_SLOT);
    jobjectArray b;
    int coreOk, agree = 1;

    b = NativeBridge_deriveAddresses(ENV, NULL, hbytes(seed, seedLen), hstr(bHmac), hints(bPath, bPathLen), gapE, gapI,
                                     bFormat);
    coreOk = BRRecoveryDeriveAddresses(out, total, seed, seedLen, cHmac, cPath, cPathLen, gapE, gapI, cFormat);
    if ((b != NULL) != (coreOk != 0)) {
        failf(c, "bridge %s, core %s", b ? "derived" : "refused", coreOk ? "derived" : "refused");
        agree = 0;
    }
    else if (! b && g_requireAddresses) { failf(c, "both refused a derivation that must succeed"); agree = 0; }
    else if (b) {
        if ((size_t)((HObj *)b)->len != total) { failf(c, "bridge wrote %d slots, expected %zu", ((HObj *)b)->len, total); agree = 0; }
        for (size_t k = 0; agree && k < total; k++) {
            HObj *s = ((jobject *)((HObj *)b)->data)[k];
            const char *bs = s ? s->data : "";
            const char *cs = out + k * BR_RECOVERY_ADDRESS_SLOT;

            if (g_requireAddresses && (! s || ! s->data || ! ((char *)s->data)[0] || ! cs[0])) {
                failf(c, "slot %zu: an empty slot (bridge \"%s\", core \"%s\")", k, bs, cs);
                agree = 0;
            }
            else if (! s && cs[0]) { failf(c, "slot %zu: bridge null, core %s", k, cs); agree = 0; }
            else if (strcmp(bs, cs) != 0) { failf(c, "slot %zu: bridge %s, core %s", k, bs, cs); agree = 0; }
        }
    }
    free(out);
    arena_free();
    return agree;
}

static void tohex(const uint8_t *b, size_t n, char *out) { for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]); }

/* ---- cases ---------------------------------------------------------------------------------------------- */

static void case_profile(size_t i)
{
    char c[32];
    const KotlinProfile *k = &KOTLIN_PROFILES[i];
    const BRDerivationProfile *p = BRDerivationProfileAt(i);

    snprintf(c, sizeof(c), "profile_%zu", i);
    begin();
    if (! p) { failf(c, "the core has no profile %zu", i); end(c); return; }
    if (strcmp(k->label, p->label)) failf(c, "label: Kotlin \"%s\", core \"%s\"", k->label, p->label);
    if (strcmp(k->description, p->description)) failf(c, "description: Kotlin \"%s\", core \"%s\"", k->description, p->description);
    if (strcmp(k->hmacKey, p->hmacKey)) failf(c, "hmacKey: Kotlin \"%s\", core \"%s\"", k->hmacKey, p->hmacKey);
    if (k->pathLen != p->pathLen || memcmp(k->path, p->path, k->pathLen * sizeof(uint32_t))) failf(c, "path differs");
    for (size_t j = k->pathLen; j < BR_DERIVATION_PATH_MAX; j++) if (p->path[j] != 0) failf(c, "path entry %zu past pathLen is not 0", j);
    if (k->formatCount != p->formatCount) failf(c, "format count: Kotlin %zu, core %zu", k->formatCount, p->formatCount);
    for (size_t f = 0; f < k->formatCount && f < p->formatCount; f++)
        if (k->formats[f] != p->formats[f]) failf(c, "format %zu: Kotlin %d, core %d", f, k->formats[f], p->formats[f]);
    for (size_t f = p->formatCount; f < BR_DERIVATION_FORMATS_MAX; f++) if (p->formats[f] != -1) failf(c, "format slot %zu past formatCount is not -1", f);
    if (k->isNative != p->isNative) failf(c, "isNative: Kotlin %d, core %d", k->isNative, p->isNative);
    if (k->gapExternal != p->gapExternal || k->gapInternal != p->gapInternal)
        failf(c, "gaps: Kotlin %d/%d, core %d/%d", k->gapExternal, k->gapInternal, p->gapExternal, p->gapInternal);
    if (p->gapExternal != BR_DERIVATION_GAP_EXTERNAL_START || p->gapInternal != BR_DERIVATION_GAP_INTERNAL_START)
        failf(c, "gaps are not the START constants");

    g_requireAddresses = 1;
    // What Android derives (Kotlin's table through the bridge) against what a core caller derives (the core's
    // table through the core), whole starting window, every seed, every format Kotlin probes.
    for (size_t s = 0; s < SEED_COUNT; s++) {
        for (size_t f = 0; f < k->formatCount; f++) {
            int cf = f < p->formatCount ? p->formats[f] : -1;

            same_addresses(c, g_seeds[s], 64, k->hmacKey, k->path, k->pathLen, k->formats[f],
                           p->hmacKey, p->path, p->pathLen, cf, k->gapExternal, k->gapInternal);
        }
    }
    g_requireAddresses = 0;
    end(c);
}

static void case_seeds(void)
{
    const char *m = SEEDS[0].mnemonic;
    uint8_t b[64], co[64];
    static char longPhrase[600], longPass[200];
    int bOk, cOk;

    begin();
    for (size_t s = 0; s < SEED_COUNT; s++) {
        char hex[129];
        const char *pass = SEEDS[s].passphrase;

        bOk = bridge_seed(SEEDS[s].mnemonic, strlen(SEEDS[s].mnemonic), pass[0] ? pass : NULL, strlen(pass), b);
        cOk = BRRecoveryMnemonicToSeed(co, SEEDS[s].mnemonic, strlen(SEEDS[s].mnemonic), pass, strlen(pass));
        if (! bOk || ! cOk || memcmp(b, co, 64)) failf("seed_match", "seed %zu: bridge and core differ", s);
        tohex(co, 64, hex);
        if (SEEDS[s].independentSeedHex && strcmp(hex, SEEDS[s].independentSeedHex)) failf("seed_match", "seed %zu is not the published BIP39 seed: %s", s, hex);
        memcpy(g_seeds[s], co, 64);
    }
    end("seed_match");

    begin();
    memset(longPhrase, 'a', sizeof(longPhrase));
    memset(longPass, 'a', sizeof(longPass));
    {
        struct { const char *what; const char *ph; size_t phLen; const char *pa; size_t paLen; int expect; } t[] = {
            { "phrase 511 bytes", longPhrase, 511, NULL, 0, 1 },
            { "phrase 512 bytes", longPhrase, 512, NULL, 0, 0 },
            { "passphrase 128 bytes", m, strlen(m), longPass, 128, 1 },
            { "passphrase 129 bytes", m, strlen(m), longPass, 129, 0 },
            { "empty phrase", "", 0, NULL, 0, 1 },
            { "empty passphrase array", m, strlen(m), "", 0, 1 },
        };
        for (size_t i = 0; i < sizeof(t) / sizeof(t[0]); i++) {
            bOk = bridge_seed(t[i].ph, t[i].phLen, t[i].pa, t[i].paLen, b);
            cOk = BRRecoveryMnemonicToSeed(co, t[i].ph, t[i].phLen, t[i].pa, t[i].paLen);
            if (bOk != t[i].expect || cOk != t[i].expect) failf("seed_bounds", "%s: bridge %d, core %d, expected %d", t[i].what, bOk, cOk, t[i].expect);
            else if (bOk && memcmp(b, co, 64)) failf("seed_bounds", "%s: seeds differ", t[i].what);
        }
        // An empty passphrase array, no passphrase and the empty string are one seed.
        bridge_seed(m, strlen(m), "", 0, b);
        if (memcmp(b, g_seeds[0], 64)) failf("seed_bounds", "empty passphrase array is not the no-passphrase seed");
        cOk = BRRecoveryMnemonicToSeed(co, m, strlen(m), NULL, 0);
        if (! cOk || memcmp(co, g_seeds[0], 64)) failf("seed_bounds", "core NULL passphrase is not the empty-passphrase seed");
        cOk = BRRecoveryMnemonicToSeed(co, NULL, 0, NULL, 0);
        if (cOk) failf("seed_bounds", "core accepted a NULL phrase");
        for (size_t j = 0; j < 64; j++) if (co[j]) { failf("seed_bounds", "a refusal left seed bytes"); break; }
    }
    end("seed_bounds");

    begin();
    bOk = bridge_seed(m, strlen(m), "abc\0evil", 8, b);
    cOk = BRRecoveryMnemonicToSeed(co, m, strlen(m), "abc\0evil", 8);
    if (bOk) failf("seed_nul_refused", "the bridge accepted a passphrase with a NUL");
    if (cOk) failf("seed_nul_refused", "the core accepted a passphrase with a NUL");
    end("seed_nul_refused");

    // The phrase is not checked for NUL in either: both read it up to the NUL (bridge behaviour, kept).
    begin();
    {
        char ph[200];
        size_t n = strlen(m);

        memcpy(ph, m, n);
        memcpy(ph + n, "\0tail", 5);
        bOk = bridge_seed(ph, n + 5, NULL, 0, b);
        cOk = BRRecoveryMnemonicToSeed(co, ph, n + 5, NULL, 0);
        if (! bOk || ! cOk || memcmp(b, co, 64)) failf("phrase_with_nul", "bridge %d, core %d, or seeds differ", bOk, cOk);
        else if (memcmp(co, g_seeds[0], 64)) failf("phrase_with_nul", "the phrase was not read up to its NUL");
    }
    end("phrase_with_nul");
}

static void case_all_formats(void)
{
    begin();
    g_requireAddresses = 1;
    for (size_t i = 0; i < BR_DERIVATION_PROFILE_COUNT; i++) {
        const KotlinProfile *k = &KOTLIN_PROFILES[i];

        for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
            for (size_t s = 0; s < SEED_COUNT; s += 3)
                same_addresses("all_formats", g_seeds[s], 64, k->hmacKey, k->path, k->pathLen, f, k->hmacKey, k->path,
                               k->pathLen, f, 40, 20);
        }
    }
    g_requireAddresses = 0;
    end("all_formats");
}

static void case_refusals(void)
{
    static const uint32_t path[3] = { 84 | BIP32_HARD, 20 | BIP32_HARD, 0 | BIP32_HARD };
    uint8_t big[65];
    char out[4 * BR_RECOVERY_ADDRESS_SLOT];

    begin();
    memcpy(big, g_seeds[0], 64);
    big[64] = 7;
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 3, -1, "Bitcoin seed", path, 3, -1, 2, 2);
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 3, 4, "Bitcoin seed", path, 3, 4, 2, 2);
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, -1, 2);
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, 2, -1);
    same_addresses("derive_refusals", big, 65, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, 2, 2);
    same_addresses("derive_refusals", big, 0, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, 2, 2);
    same_addresses("derive_refusals", big, 1, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, 2, 2);
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 0, 0, "Bitcoin seed", NULL, 0, 0, 2, 2);
    same_addresses("derive_refusals", g_seeds[0], 64, "Bitcoin seed", path, 3, 1, "Bitcoin seed", path, 3, 1, 0, 0);
    // Core-only bounds: a buffer too small, a NULL path with a length, a NULL hmac.
    if (BRRecoveryDeriveAddresses(out, 3, g_seeds[0], 64, "Bitcoin seed", path, 3, 2, 2, 1)) failf("derive_refusals", "core wrote 4 slots into 3");
    if (BRRecoveryDeriveAddresses(out, 4, g_seeds[0], 64, "Bitcoin seed", NULL, 3, 2, 2, 1)) failf("derive_refusals", "core took a NULL path of length 3");
    if (BRRecoveryDeriveAddresses(out, 4, g_seeds[0], 64, NULL, path, 3, 2, 2, 1)) failf("derive_refusals", "core took a NULL hmac");
    if (! BRRecoveryDeriveAddresses(out, 0, g_seeds[0], 64, "Bitcoin seed", path, 3, 0, 0, 1)) failf("derive_refusals", "two gaps of 0 were refused");
    end("derive_refusals");
}

// Android's RecoveryDerivationTest.mapDerived_droppedMiddleSlot_keepsTrueIndices, case for case: dropping an
// empty middle slot must not shift a surviving address's (chain, index).
static void case_slot_positions(void)
{
    static const char *raw[] = { "addrE0", "", "addrE2", "addrI0" };
    static const struct { const char *a; uint32_t chain, index; } want[] = { { "addrE0", 0, 0 }, { "addrE2", 0, 2 }, { "addrI0", 1, 0 } };
    char out[4 * BR_RECOVERY_ADDRESS_SLOT];
    size_t got = 0;

    begin();
    memset(out, 0, sizeof(out));
    for (size_t k = 0; k < 4; k++) strcpy(out + k * BR_RECOVERY_ADDRESS_SLOT, raw[k]);
    for (size_t k = 0; k < 4; k++) {
        uint32_t chain = 9, index = 9;
        const char *a = BRRecoveryDerivedSlot(out, 4, 3, k, &chain, &index);

        if (! a) continue;
        if (got >= 3) { failf("slot_positions", "more than 3 survivors"); break; }
        if (strcmp(a, want[got].a) || chain != want[got].chain || index != want[got].index)
            failf("slot_positions", "survivor %zu: %s (%u,%u), expected %s (%u,%u)", got, a, chain, index, want[got].a,
                  want[got].chain, want[got].index);
        got++;
    }
    if (got != 3) failf("slot_positions", "%zu survivors, expected 3 (the empty slot dropped)", got);
    if (BRRecoveryDerivedSlot(out, 4, 3, 4, NULL, NULL)) failf("slot_positions", "a slot past the result was read");
    end("slot_positions");
}

// The core's table-driven addresses against vectors generated without the core (vectors/derivation/).
static void case_independent(int argc, char **argv)
{
    begin();
    if (argc < 3) failf("independent_vectors", "no vector files given");
    for (int a = 2; a < argc; a++) {
        FILE *fp = fopen(argv[a], "r");
        char line[512], deriv[32], fmt[32], addr[128];
        unsigned chain, index;
        size_t seedIdx = (size_t)-1, checked = 0;
        char out[40 * BR_RECOVERY_ADDRESS_SLOT];

        if (! fp) { failf("independent_vectors", "cannot open %s", argv[a]); continue; }
        while (fgets(line, sizeof(line), fp)) {
            const BRDerivationProfile *p;
            int format;

            if (! strncmp(line, "# passphrase: ", 14)) seedIdx = strncmp(line + 14, "TREZOR", 6) == 0 ? 1 : 0;
            if (line[0] == '#' || sscanf(line, "%31s %31s %u %u %127s", deriv, fmt, &chain, &index, addr) != 5) continue;
            if (seedIdx == (size_t)-1) { failf("independent_vectors", "%s: no passphrase line", argv[a]); break; }
            if (! strcmp(deriv, "bip84") && ! strcmp(fmt, "p2wpkh")) { p = BRDerivationProfileAt(0); format = BR_DERIVATION_FORMAT_P2WPKH; }
            else if (! strcmp(deriv, "bip86") && ! strcmp(fmt, "p2tr")) { p = BRDerivationProfileAt(6); format = BR_DERIVATION_FORMAT_P2TR; }
            else if (! strcmp(deriv, "legacy") && ! strcmp(fmt, "p2pkh")) { p = BRDerivationProfileAt(1); format = BR_DERIVATION_FORMAT_P2PKH; }
            else if (! strcmp(deriv, "legacy") && ! strcmp(fmt, "p2wpkh")) { p = BRDerivationProfileAt(1); format = BR_DERIVATION_FORMAT_P2WPKH; }
            else { failf("independent_vectors", "unknown derivation %s %s", deriv, fmt); continue; }
            if (chain > 1 || index >= 20 ||
                ! BRRecoveryDeriveAddresses(out, 40, g_seeds[seedIdx], 64, p->hmacKey, p->path, p->pathLen, 20, 20, format)) {
                failf("independent_vectors", "cannot derive %s %s %u %u", deriv, fmt, chain, index);
                continue;
            }
            if (strcmp(out + (chain * 20 + index) * BR_RECOVERY_ADDRESS_SLOT, addr))
                failf("independent_vectors", "%s %s %u %u: core %s, vector %s", deriv, fmt, chain, index,
                      out + (chain * 20 + index) * BR_RECOVERY_ADDRESS_SLOT, addr);
            checked++;
        }
        fclose(fp);
        printf("   %s: %zu independent addresses checked\n", argv[a], checked);
        if (checked < 160) failf("independent_vectors", "%s: only %zu addresses", argv[a], checked);
    }
    end("independent_vectors");
}


// Every profile and format the core's table probes, against independent.txt (pure Python, its own hand-written
// profile table). The only independent check of profiles 2-5.
static void case_independent_profiles(const char *path)
{
    FILE *fp = path ? fopen(path, "r") : NULL;
    char line[512], addr[128], hex[129];
    unsigned s, prof, chain, index;
    int fmt;
    size_t checked = 0, seeds = 0, perCombo[BR_DERIVATION_PROFILE_COUNT][BR_DERIVATION_FORMAT_COUNT];
    char out[20 * BR_RECOVERY_ADDRESS_SLOT];

    begin();
    memset(perCombo, 0, sizeof(perCombo));
    if (! fp) { failf("independent_profiles", "cannot open %s", path ? path : "(none)"); end("independent_profiles"); return; }
    while (fgets(line, sizeof(line), fp)) {
        const BRDerivationProfile *p;

        if (sscanf(line, "seed %u %128s", &s, hex) == 2) {
            char mine[129];

            if (s >= SEED_COUNT) { failf("independent_profiles", "seed %u out of range", s); continue; }
            tohex(g_seeds[s], 64, mine);
            if (strcmp(mine, hex)) failf("independent_profiles", "seed %u differs", s);
            seeds++;
            continue;
        }
        if (sscanf(line, "indep %u %u %d %u %u %127s", &s, &prof, &fmt, &chain, &index, addr) != 6) continue;
        p = BRDerivationProfileAt(prof);
        if (! p || s >= SEED_COUNT || fmt < 0 || fmt >= BR_DERIVATION_FORMAT_COUNT || chain > 1 || index >= 10) {
            failf("independent_profiles", "bad line: %s", line);
            continue;
        }
        if (! BRRecoveryDeriveAddresses(out, 20, g_seeds[s], 64, p->hmacKey, p->path, p->pathLen, 10, 10, fmt)) {
            failf("independent_profiles", "core refused seed %u profile %u format %d", s, prof, fmt);
            continue;
        }
        if (strcmp(out + (chain * 10 + index) * BR_RECOVERY_ADDRESS_SLOT, addr))
            failf("independent_profiles", "seed %u profile %u format %d %u/%u: core %s, independent %s", s, prof, fmt,
                  chain, index, out + (chain * 10 + index) * BR_RECOVERY_ADDRESS_SLOT, addr);
        perCombo[prof][fmt]++;
        checked++;
    }
    fclose(fp);
    if (seeds != SEED_COUNT) failf("independent_profiles", "%zu seed lines, expected %zu", seeds, (size_t)SEED_COUNT);
    // Coverage: every format every profile is probed as, 4 seeds x 20 addresses; nothing else.
    for (size_t i = 0; i < BR_DERIVATION_PROFILE_COUNT; i++) {
        const BRDerivationProfile *p = BRDerivationProfileAt(i);

        for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
            int probed = 0;

            for (size_t j = 0; j < p->formatCount; j++) if (p->formats[j] == f) probed = 1;
            if (perCombo[i][f] != (probed ? SEED_COUNT * 20 : 0))
                failf("independent_profiles", "profile %zu format %d: %zu independent addresses, expected %zu", i, f,
                      perCombo[i][f], probed ? (size_t)SEED_COUNT * 20 : (size_t)0);
        }
    }
    printf("   %s: %zu independent addresses checked\n", path, checked);
    end("independent_profiles");
}

// BRRecoveryAddressForKey refuses what the core's encoders would assert on (asserts are live in this build:
// an assert here aborts the run and the case never reports).
static void case_address_probes(void)
{
    static const UInt256 secret = { .u8 = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 } };
    char buf[BR_RECOVERY_ADDRESS_SLOT];
    BRKey comp, uncomp;

    begin();
    BRKeySetSecret(&comp, &secret, 1);
    BRKeySetSecret(&uncomp, &secret, 0);
    for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
        memset(buf, 'x', sizeof(buf));
        if (BRRecoveryAddressForKey(NULL, f, buf, sizeof(buf)) || buf[0]) failf("address_probes", "format %d: a NULL key was encoded", f);
        memset(buf, 'x', sizeof(buf));
        if (BRRecoveryAddressForKey(&comp, f, buf, BR_RECOVERY_ADDRESS_SLOT - 1) || buf[0])
            failf("address_probes", "format %d: a %d-byte buffer was accepted", f, BR_RECOVERY_ADDRESS_SLOT - 1);
        if (BRRecoveryAddressForKey(&comp, f, buf, 0)) failf("address_probes", "format %d: a 0-byte buffer was accepted", f);
        if (BRRecoveryAddressForKey(&comp, f, NULL, sizeof(buf))) failf("address_probes", "format %d: a NULL buffer was accepted", f);
        if (! BRRecoveryAddressForKey(&comp, f, buf, sizeof(buf)) || ! buf[0] || strlen(buf) >= sizeof(buf))
            failf("address_probes", "format %d: a compressed key in a full slot was not encoded", f);
    }
    if (BRRecoveryAddressForKey(&comp, -1, buf, sizeof(buf)) || BRRecoveryAddressForKey(&comp, 4, buf, sizeof(buf)) || buf[0])
        failf("address_probes", "an unknown format was encoded");
    memset(buf, 'x', sizeof(buf));
    if (BRRecoveryAddressForKey(&uncomp, BR_DERIVATION_FORMAT_P2WPKH, buf, sizeof(buf)) || buf[0])
        failf("address_probes", "P2WPKH of an uncompressed key was encoded");
    if (! BRRecoveryAddressForKey(&uncomp, BR_DERIVATION_FORMAT_P2PKH, buf, sizeof(buf)) || buf[0] != 'D')
        failf("address_probes", "P2PKH of an uncompressed key was not encoded");
    BRKeyClean(&comp);
    BRKeyClean(&uncomp);
    // The overflow bound: more slots than SIZE_MAX bytes hold is refused before anything is written. Only a
    // 32-bit size_t can reach it with int32_t gaps; on 64-bit the largest request is checked to pass the bound.
    if (SIZE_MAX / BR_RECOVERY_ADDRESS_SLOT < (size_t)INT32_MAX * 2) {
        if (BRRecoveryDeriveAddresses(buf, SIZE_MAX, g_seeds[0], 64, "Bitcoin seed", NULL, 0, INT32_MAX, INT32_MAX, 0))
            failf("address_probes", "a slot count past SIZE_MAX bytes was accepted");
    }
    else if (BRRecoveryDeriveAddresses(buf, 0, g_seeds[0], 64, "Bitcoin seed", NULL, 0, INT32_MAX, INT32_MAX, 0))
        failf("address_probes", "a request larger than its buffer was accepted");
    end("address_probes");
}

int main(int argc, char **argv)
{
    begin();
    if (KOTLIN_PROFILE_COUNT != BR_DERIVATION_PROFILE_COUNT) failf("profile_count", "Kotlin %d, core %d", KOTLIN_PROFILE_COUNT, BR_DERIVATION_PROFILE_COUNT);
    {
        size_t n = 0;
        BRDerivationProfiles(&n);
        if (n != BR_DERIVATION_PROFILE_COUNT || BRDerivationProfileAt(BR_DERIVATION_PROFILE_COUNT) != NULL) failf("profile_count", "table count");
    }
    if (KOTLIN_PATH_MAX > BR_DERIVATION_PATH_MAX || KOTLIN_FORMATS_MAX > BR_DERIVATION_FORMATS_MAX) failf("profile_count", "Kotlin exceeds the core's bounds");
    end("profile_count");

    begin();
    if (BR_DERIVATION_HARD != BIP32_HARD) failf("hard_bit", "BR_DERIVATION_HARD != BIP32_HARD");
    if (strcmp(BR_DERIVATION_HMAC_STANDARD, "Bitcoin seed") || strcmp(BR_DERIVATION_HMAC_DIGIBYTE, "DigiByte seed")) failf("hard_bit", "hmac names");
    end("hard_bit");

    case_seeds();
    for (size_t i = 0; i < KOTLIN_PROFILE_COUNT; i++) case_profile(i);
    case_all_formats();
    case_refusals();
    case_slot_positions();
    case_independent(argc, argv);
    case_independent_profiles(argc > 1 ? argv[1] : NULL);
    case_address_probes();

    begin();
    {
        uint8_t copy[64];
        static const uint32_t path[1] = { BIP32_HARD };
        char out[2 * BR_RECOVERY_ADDRESS_SLOT];
        HObj *arr = hbytes(g_seeds[2], 64);

        memcpy(copy, g_seeds[2], 64);
        NativeBridge_deriveAddresses(ENV, NULL, arr, hstr("DigiByte seed"), hints(path, 1), 1, 1, 0);
        if (memcmp(arr->data, copy, 64)) failf("caller_seed_untouched", "the bridge wrote the caller's seed");
        arena_free();
        BRRecoveryDeriveAddresses(out, 2, g_seeds[2], 64, "DigiByte seed", path, 1, 1, 1, 0);
        if (memcmp(g_seeds[2], copy, 64)) failf("caller_seed_untouched", "the core wrote the caller's seed");
    }
    end("caller_seed_untouched");

    free(g_arena);
    if (! g_anyFail) printf("ALL PASS\n");
    return g_anyFail;
}
