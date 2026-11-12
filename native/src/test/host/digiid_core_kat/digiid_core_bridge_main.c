/*
 * digiid_core_bridge_main.c -- BRDigiId.h against the Android bridge it moved from, end to end.
 *
 * Compiles the bridge's OWN jni_wallet_sign.c, jni_wallet.c and slip13.c (with the core under them),
 * over identity_sign_bytes_kat's stand-ins for <jni.h> and <android/log.h>, boots it through the real
 * JNI_OnLoad, creates a wallet from a phrase through the real entry point, and calls the real
 * NativeBridge_signMessage, NativeBridge_signIdentityMessage and NativeBridge_identityAddress. Each answer
 * (or NULL) is held byte for byte to what the header answers (or refuses) for the same seed -- the
 * phrase's BRBIP39DeriveKey, as the bridge derives it -- the same message bytes and the same URI bytes.
 *
 * The host env's strings are UTF-16, as a JVM's are; its GetStringUTFChars writes what ART's does (art_utf
 * below, transcribed from ART's runtime/jni/jni_internal.cc and libdexfile/dex/utf-inl.h: a surrogate pair
 * as its four-byte character, against the JNI specification, U+0000 as C0 80 and a lone surrogate as its
 * own three bytes). The header is handed BRDigiIdUriBytesFromUtf16's bytes for the URI, and
 * BRDigiIdUtf8FromUtf16's for the message, so the sweep holds both encoders to what the bridge hashes.
 *
 *   digiid_core_bridge sweep    four phrases, a fixed pseudo-random stream of messages, URIs, indexes
 *                               and address formats, and long messages around 0xffff bytes.
 *   digiid_core_bridge print    the bridge's answers for the hand cases' fixed inputs (how the
 *                               literals in digiid_core_kat_main.c were read off the bridge).
 *
 * Exit 0 when every answer agrees.
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jni_bridge.h"   /* the bridge's own header, over host_jni/<jni.h> and host_jni/<android/log.h> */
#include "BRBIP39Mnemonic.h"
#include "BRDigiId.h"

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);
jboolean JNICALL NativeBridge_createWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                    jbyteArray passphraseBytes);
jstring JNICALL NativeBridge_signMessage(JNIEnv *env, jobject thiz, jstring message, jint addressFormat);
jstring JNICALL NativeBridge_signIdentityMessage(JNIEnv *env, jobject thiz, jstring message, jstring siteUri,
                                                 jint index);
jstring JNICALL NativeBridge_identityAddress(JNIEnv *env, jobject thiz, jstring siteUri, jint index);

static int g_fail = 0;
static void check(int cond, const char *fmt, ...)
{
    va_list ap;
    if (! cond) g_fail++;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

/* ---- the host VM and env (as identity_sign_bytes_kat's) ------------------------------------------ */

typedef struct { jsize len; jbyte *data; } HostArray;
typedef struct { jsize len; jchar *units; char *text; } HostString;

/* Every string handed out is freed at the end of the step that made it. */
static void *g_owned[4096];
static size_t g_ownedCount = 0;
static void *own(void *p)
{
    if (g_ownedCount < sizeof(g_owned) / sizeof(*g_owned)) g_owned[g_ownedCount++] = p;
    else { fprintf(stderr, "host env: too many objects\n"); exit(2); }
    return p;
}
static void release_all(void)
{
    while (g_ownedCount > 0) free(g_owned[--g_ownedCount]);
}

static HostArray *hostArray(const void *bytes, size_t len)
{
    HostArray *a = own(calloc(1, sizeof(*a)));
    a->len = (jsize)len;
    a->data = own(calloc(len ? len : 1, 1));
    if (len) memcpy(a->data, bytes, len);
    return a;
}

static HostString *hostString(const jchar *units, size_t len)
{
    HostString *s = own(calloc(1, sizeof(*s)));
    s->len = (jsize)len;
    s->units = own(calloc(len ? len : 1, sizeof(jchar)));
    if (len) memcpy(s->units, units, len * sizeof(jchar));
    return s;
}

static jsize h_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HostArray *)a)->len; }
static jbyte *h_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *isCopy)
{
    (void)e;
    if (isCopy) *isCopy = JNI_FALSE;
    return ((HostArray *)a)->data;
}
static void h_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *elems, jint mode) { (void)e; (void)a; (void)elems; (void)mode; }
static void h_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize start, jsize len, jbyte *buf)
{
    (void)e;
    memcpy(buf, ((HostArray *)a)->data + start, (size_t)len);
}
static jsize h_GetStringLength(JNIEnv *e, jstring s) { (void)e; return ((HostString *)s)->len; }
static const jchar *h_GetStringChars(JNIEnv *e, jstring s, jboolean *isCopy)
{
    HostString *h = s;
    jchar *copy = malloc((h->len ? (size_t)h->len : 1) * sizeof(jchar));
    (void)e;
    memcpy(copy, h->units, (size_t)h->len * sizeof(jchar));
    if (isCopy) *isCopy = JNI_TRUE;
    return copy;
}
static void h_ReleaseStringChars(JNIEnv *e, jstring s, const jchar *chars) { (void)e; (void)s; free((void *)chars); }

/* What ART's GetStringUTFChars writes for units[0, n) into out (room for 3n + 1): its length, without the
 * NUL it also writes. ART's ConvertUtf16ToUtf8<kUtfUseShortZero = false, kUtfUse4ByteSequence = true,
 * kUtfReplaceBadSurrogates = false> (libdexfile/dex/utf-inl.h; the flags in runtime/jni/jni_internal.cc),
 * line for line. digiid_core_kat_main.c holds the header to the older form of the same rule. */
static size_t art_utf(const jchar *utf16, size_t char_count, unsigned char *out)
{
    size_t k = 0;
    for (size_t i = 0; i < char_count; ++i) {
        int has_trail = i + 1 != char_count && (utf16[i + 1] & 0xfc00u) == 0xdc00u;
        uint16_t ch = utf16[i];
        if (ch < 0x80u && ch != 0u) {
            out[k++] = (unsigned char)ch;                                   /* One byte. */
        } else if (ch < 0x800u) {
            out[k++] = (unsigned char)((ch >> 6) | 0xc0);                   /* Two bytes. */
            out[k++] = (unsigned char)((ch & 0x3f) | 0x80);
        } else if ((ch & 0xfc00u) == 0xd800u && has_trail) {
            const uint32_t offset = (0xd800u << 10) + 0xdc00u - 0x10000u;   /* A valid surrogate pair: four bytes. */
            uint32_t code_point = ((uint32_t)ch << 10) + (uint32_t)utf16[i + 1] - offset;
            ++i;
            out[k++] = (unsigned char)((code_point >> 18) | 0xf0);
            out[k++] = (unsigned char)(((code_point >> 12) & 0x3f) | 0x80);
            out[k++] = (unsigned char)(((code_point >> 6) & 0x3f) | 0x80);
            out[k++] = (unsigned char)((code_point & 0x3f) | 0x80);
        } else {
            out[k++] = (unsigned char)((ch >> 12) | 0xe0);                  /* Three bytes. */
            out[k++] = (unsigned char)(((ch >> 6) & 0x3f) | 0x80);
            out[k++] = (unsigned char)((ch & 0x3f) | 0x80);
        }
    }
    out[k] = 0;
    return k;
}
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *isCopy)
{
    HostString *h = s;
    unsigned char *out = malloc((size_t)h->len * 3 + 1);
    (void)e;
    art_utf(h->units, (size_t)h->len, out);
    if (isCopy) *isCopy = JNI_TRUE;
    return (const char *)out;
}
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *chars) { (void)e; (void)s; free((void *)chars); }
static jstring h_NewStringUTF(JNIEnv *e, const char *bytes)
{
    HostString *s = hostString(NULL, 0);
    (void)e;
    s->text = own(strdup(bytes));
    return s;
}

static const struct JNINativeInterface g_table = {
    .GetArrayLength = h_GetArrayLength,
    .GetByteArrayElements = h_GetByteArrayElements,
    .ReleaseByteArrayElements = h_ReleaseByteArrayElements,
    .GetByteArrayRegion = h_GetByteArrayRegion,
    .GetStringLength = h_GetStringLength,
    .GetStringChars = h_GetStringChars,
    .ReleaseStringChars = h_ReleaseStringChars,
    .GetStringUTFChars = h_GetStringUTFChars,
    .ReleaseStringUTFChars = h_ReleaseStringUTFChars,
    .NewStringUTF = h_NewStringUTF,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;
static jint h_GetEnv(JavaVM *vm, void **out, jint version)
{
    (void)vm; (void)version;
    *out = (void *)env;
    return JNI_OK;
}
static const struct JNIInvokeInterface g_invoke = { .GetEnv = h_GetEnv };
static JavaVM g_vmValue = &g_invoke;

int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }   /* jni_registry.c is not compiled here */
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }

static const char *answerText(jstring answer) { return answer ? ((HostString *)answer)->text : NULL; }

/* ---- the header's side ---------------------------------------------------------------------------- */

static uint8_t g_seed[64];
static uint8_t *g_work;
static size_t g_workCap;

static void need_work(size_t n)
{
    if (n <= g_workCap) return;
    free(g_work);
    g_work = malloc(n);
    g_workCap = n;
}

static int boot_phrase(const char *phrase)
{
    HostArray *p = hostArray(phrase, strlen(phrase));
    int ok = NativeBridge_createWalletFromBytes(env, NULL, p, NULL) == JNI_TRUE;
    BRBIP39DeriveKey(g_seed, phrase, NULL);
    release_all();
    return ok;
}

/* The header's answer for signMessage(units, format): into out, or NULL for a refusal. */
static const char *header_shared(const jchar *units, size_t n, int32_t format, char *out)
{
    size_t len = 0;
    uint8_t *bytes = malloc(3 * n + 1);
    const char *r = NULL;
    if (BRDigiIdUtf8FromUtf16(bytes, 3 * n + 1, &len, units, n) == BR_DIGIID_OK) {
        need_work(BRDigiIdDigestWorkSize(len));
        if (BRDigiIdSignShared(out, BR_DIGIID_ANSWER_CAPACITY, g_seed, sizeof(g_seed), bytes, len, format, g_work, g_workCap) ==
            BR_DIGIID_OK) r = out;
    }
    free(bytes);
    return r;
}

/* The header's bytes for a URI: BRDigiIdUriBytesFromUtf16's, into ub (room for 3 * un + 1). */
static size_t header_uri(const jchar *uri, size_t un, unsigned char *ub)
{
    size_t ulen = 0;
    static const jchar none[1] = { 0 };
    if (BRDigiIdUriBytesFromUtf16(ub, 3 * un + 1, &ulen, uri ? uri : none, uri ? un : 0) != BR_DIGIID_OK) {
        check(0, "BRDigiIdUriBytesFromUtf16 refused a %zu-unit URI", un);
        ulen = 0;
    }
    return ulen;
}

static const char *header_site(const jchar *units, size_t n, const jchar *uri, size_t un, int32_t index, char *out)
{
    size_t len = 0, ulen;
    uint8_t *bytes = malloc(3 * n + 1);
    unsigned char *ub = malloc(3 * un + 1);
    const char *r = NULL;
    ulen = header_uri(uri, un, ub);
    if (BRDigiIdUtf8FromUtf16(bytes, 3 * n + 1, &len, units, n) == BR_DIGIID_OK) {
        need_work(BRDigiIdSignSiteWorkSize(ulen, len));
        if (BRDigiIdSignSite(out, BR_DIGIID_ANSWER_CAPACITY, g_seed, sizeof(g_seed), bytes, len, (const char *)ub, ulen, index,
                             g_work, g_workCap) == BR_DIGIID_OK) r = out;
    }
    free(bytes);
    free(ub);
    return r;
}

static const char *header_address(const jchar *uri, size_t un, int hasUri, int32_t index, char *out)
{
    unsigned char *ub = malloc(3 * un + 1);
    size_t ulen = header_uri(uri, hasUri ? un : 0, ub);
    const char *r = NULL;
    need_work(BRDigiIdIndexesWorkSize(ulen));
    if (BRDigiIdAddress(out, BR_DIGIID_ADDRESS_CAPACITY, g_seed, sizeof(g_seed), hasUri, (const char *)ub, ulen, index, g_work,
                        g_workCap) == BR_DIGIID_OK) r = out;
    free(ub);
    return r;
}

static int same(const char *a, const char *b) { return (! a && ! b) || (a && b && strcmp(a, b) == 0); }

/* ---- a fixed pseudo-random stream --------------------------------------------------------------- */

static uint64_t RNG = 0x2545f4914f6cdd1dULL;
static uint64_t rnd(void) { RNG ^= RNG << 13; RNG ^= RNG >> 7; RNG ^= RNG << 17; return RNG; }
static uint64_t below(uint64_t n) { return rnd() % n; }

static jchar rnd_unit(void)
{
    switch (below(10)) {
        case 0: return 0x0000;
        case 1: return (jchar)(0xD800 + below(0x400));   /* a high surrogate */
        case 2: return (jchar)(0xDC00 + below(0x400));   /* a low surrogate */
        case 3: return (jchar)(0x80 + below(0x780));     /* two bytes */
        case 4: return (jchar)(0x800 + below(0xD000));   /* three bytes */
        case 5: return (jchar)(0xE000 + below(0x2000));
        default: return (jchar)(0x20 + below(0x5f));     /* printable ASCII */
    }
}

static size_t rnd_message(jchar *out, size_t cap)
{
    size_t n = (size_t)below(cap), i;
    for (i = 0; i < n; i++) {
        if (below(6) == 0 && i + 1 < n) {   /* a whole surrogate pair */
            out[i++] = (jchar)(0xD800 + below(0x400));
            out[i] = (jchar)(0xDC00 + below(0x400));
        }
        else out[i] = rnd_unit();
    }
    return n;
}

static size_t rnd_uri(jchar *out, size_t cap)
{
    static const char *bases[] = { "https://example.com/callback", "https://api.digiscope.me/api/digiid/callback",
                                   "http://localhost:8080/auth", "https://Example.COM/cb", "" };
    const char *b = bases[below(5)];
    size_t n = strlen(b), i, extra;
    for (i = 0; i < n; i++) out[i] = (jchar)(unsigned char)b[i];
    extra = (size_t)below(cap - n);
    for (i = 0; i < extra; i++) {
        if (below(16) == 0 && i + 1 < extra) {   /* a whole surrogate pair: an emoji in the path */
            out[n + i++] = (jchar)(0xD800 + below(0x400));
            out[n + i] = (jchar)(0xDC00 + below(0x400));
        }
        else out[n + i] = below(8) == 0 ? rnd_unit() : (jchar)(0x21 + below(0x5e));
    }
    return n + extra;
}

static int32_t rnd_index(void)
{
    switch (below(8)) {
        case 0: return -1;
        case 1: return INT32_MIN;
        case 2: return INT32_MAX;
        case 3: return (int32_t)(rnd() & 0x7fffffff);
        case 4: return (int32_t)below(300);
        default: return 0;
    }
}

static int32_t rnd_format(void)
{
    static const int32_t f[] = { 0, 1, 2, 2, 3, -1, INT32_MIN, INT32_MAX };
    return f[below(8)];
}

static const char *PHRASES[] = {
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    "legal winner thank year wave sausage worth useful legal winner thank yellow",
    "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
    "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
};

static jstring jascii(const char *text)
{
    size_t n = strlen(text);
    jchar *u = calloc(n ? n : 1, sizeof(jchar));
    for (size_t i = 0; i < n; i++) u[i] = (jchar)(unsigned char)text[i];
    jstring s = hostString(u, n);
    free(u);
    return s;
}

static void sweep(void)
{
    char h1[BR_DIGIID_ANSWER_CAPACITY], h2[BR_DIGIID_ANSWER_CAPACITY], h3[BR_DIGIID_ADDRESS_CAPACITY];
    static jchar msg[200], uri[200];
    long agree = 0, differ = 0, answered = 0, refused = 0;

    check(JNI_OnLoad(&g_vmValue, NULL) == JNI_VERSION_1_6, "the bridge boots (JNI_OnLoad)");
    for (size_t p = 0; p < sizeof(PHRASES) / sizeof(*PHRASES); p++) {
        check(boot_phrase(PHRASES[p]), "a wallet is created from phrase %zu", p);
        for (int it = 0; it < 150; it++) {
            size_t mn = rnd_message(msg, sizeof(msg) / sizeof(*msg)), un = rnd_uri(uri, sizeof(uri) / sizeof(*uri));
            int32_t fmt = rnd_format(), idx = rnd_index();
            int hasUri = below(3) != 0;
            const char *b1 = answerText(NativeBridge_signMessage(env, NULL, hostString(msg, mn), fmt));
            const char *b2 = answerText(NativeBridge_signIdentityMessage(env, NULL, hostString(msg, mn), hostString(uri, un), idx));
            const char *b3 = answerText(NativeBridge_identityAddress(env, NULL, hasUri ? hostString(uri, un) : NULL, idx));
            const char *a1 = header_shared(msg, mn, fmt, h1);
            const char *a2 = header_site(msg, mn, uri, un, idx, h2);
            const char *a3 = header_address(uri, un, hasUri, idx, h3);
            const char *bs[3] = { b1, b2, b3 }, *as[3] = { a1, a2, a3 };
            for (int k = 0; k < 3; k++) {
                if (same(bs[k], as[k])) agree++;
                else if (differ++ < 6)
                    check(0, "phrase %zu step %d call %d (format %d, index %d, hasUri %d): the bridge says %s, the header %s", p, it, k,
                          (int)fmt, (int)idx, hasUri, bs[k] ? bs[k] : "NULL", as[k] ? as[k] : "NULL");
                if (bs[k]) answered++;
                else refused++;
            }
            release_all();
        }
    }

    /* Long messages: the bridge's length prefix around 0xffff, and above it. */
    check(boot_phrase(PHRASES[0]), "phrase 0 again");
    {
        static const size_t lens[] = { 0xfc, 0xfd, 0xfffe, 0xffff, 0x10000, 0x10001, 0x10100, 70000 };
        jchar *big = malloc(70000 * sizeof(jchar));
        for (size_t i = 0; i < 70000; i++) big[i] = (jchar)('a' + i % 26);
        for (size_t i = 0; i < sizeof(lens) / sizeof(*lens); i++) {
            const char *b1 = answerText(NativeBridge_signMessage(env, NULL, hostString(big, lens[i]), 0));
            const char *b2 = answerText(NativeBridge_signIdentityMessage(env, NULL, hostString(big, lens[i]),
                                                                         jascii("https://example.com/callback"), 0));
            const char *a1 = header_shared(big, lens[i], 0, h1);
            static const jchar cb[] = { 'h','t','t','p','s',':','/','/','e','x','a','m','p','l','e','.','c','o','m','/','c','a','l','l','b','a','c','k' };
            const char *a2 = header_site(big, lens[i], cb, sizeof(cb) / sizeof(*cb), 0, h2);
            check(b1 && same(b1, a1), "a %zu-byte message: the shared identity's answer is the bridge's", lens[i]);
            check(b2 && same(b2, a2), "a %zu-byte message: the per-site identity's answer is the bridge's", lens[i]);
            release_all();
        }
        free(big);
    }

    check(differ == 0, "%ld answers compared over four phrases: %ld agree, %ld differ", agree + differ, agree, differ);
    check(answered > 800 && refused > 150, "the stream reaches both answers and refusals: %ld answered, %ld refused", answered, refused);

    /* The URI bytes alone: what the bridge's GetStringUTFChars hands seed_derive_identity_key, against
     * BRDigiIdUriBytesFromUtf16, over the URIs above and strings of arbitrary units. */
    {
        long same_bytes = 0, diff_bytes = 0, pairs = 0, nuls = 0, lone = 0;
        unsigned char *ub = malloc(3 * 200 + 1);
        for (int it = 0; it < 20000; it++) {
            size_t un = (it & 1) ? rnd_uri(uri, sizeof(uri) / sizeof(*uri)) : rnd_message(uri, sizeof(uri) / sizeof(*uri)), i;
            HostString *hs = hostString(uri, un);
            const char *art = h_GetStringUTFChars(env, hs, NULL);
            size_t alen = strlen(art), hlen = header_uri(uri, un, ub);
            for (i = 0; i < un; i++) {
                if (uri[i] == 0) nuls++;
                else if (uri[i] >= 0xD800 && uri[i] <= 0xDBFF && i + 1 < un && uri[i + 1] >= 0xDC00 && uri[i + 1] <= 0xDFFF) { pairs++; i++; }
                else if (uri[i] >= 0xD800 && uri[i] <= 0xDFFF) lone++;
            }
            if (alen == hlen && memcmp(art, ub, hlen) == 0) same_bytes++;
            else if (diff_bytes++ < 4) check(0, "a %zu-unit URI: GetStringUTFChars gives %zu bytes, BRDigiIdUriBytesFromUtf16 %zu", un, alen, hlen);
            h_ReleaseStringUTFChars(env, hs, art);
            release_all();
        }
        free(ub);
        check(diff_bytes == 0, "20000 URIs of UTF-16 units: BRDigiIdUriBytesFromUtf16's bytes are GetStringUTFChars's (%ld differ)", diff_bytes);
        check(pairs > 10000 && nuls > 5000 && lone > 5000, "the URIs hold surrogate pairs (%ld), U+0000 (%ld) and lone surrogates (%ld)",
              pairs, nuls, lone);
    }
}

/* The bridge's answers for the hand cases' inputs, printed as the literals the hand cases hold. */
static void print(void)
{
    static const char *golden[] = {
        "https://example.com/callback", "https://example.com:8443/callback", "https://example.com:443/callback",
        "https://shop.example.com/digiid/callback/v1", "http://example.com/auth", "http://localhost:8080/auth",
        "https://api.digiscope.me/api/digiid/callback", "https://example.com/cb", "https://example.com/",
        "https://example.com", "https://sub.domain-with-dash.co.uk/digiid/login.php", "http://192.168.1.10:3000/digiid",
        "https://Example.COM/cb", "https://site.example/login",
    };
    static const char *login = "digiid://api.digiscope.me/api/auth/digiid/callback?x=00112233445566778899aabbccddeeff";
    static const char *request = "digiid://example.com/callback?x=abc123";
    static const jchar emoji[] = { 'g', 'm', ' ', 0xD83D, 0xDE00, ' ', '!' };

    JNI_OnLoad(&g_vmValue, NULL);
    boot_phrase(PHRASES[0]);
    for (size_t i = 0; i < sizeof(golden) / sizeof(*golden); i++) {
        printf("ADDRESS %s %s\n", golden[i], answerText(NativeBridge_identityAddress(env, NULL, jascii(golden[i]), 0)));
        release_all();
    }
    printf("ADDRESS shared %s\n", answerText(NativeBridge_identityAddress(env, NULL, NULL, 0)));
    printf("ADDRESS example.com/callback index 1 %s\n", answerText(NativeBridge_identityAddress(env, NULL, jascii(golden[0]), 1)));
    printf("ADDRESS example.com/callback index 2147483647 %s\n",
           answerText(NativeBridge_identityAddress(env, NULL, jascii(golden[0]), INT32_MAX)));
    printf("ADDRESS empty uri %s\n", answerText(NativeBridge_identityAddress(env, NULL, jascii(""), 0)));
    {
        static const jchar emojiUri[] = { 'h','t','t','p','s',':','/','/','e','x','a','m','p','l','e','.','c','o','m','/', 0xD83D, 0xDE00 };
        static const jchar nulUri[] = { 'h','t','t','p','s',':','/','/','e','x','a','m','p','l','e','.','c','o','m','/', 'a', 0x0000, 'b' };
        static const jchar loneUri[] = { 'h','t','t','p','s',':','/','/','e','x','a','m','p','l','e','.','c','o','m','/', 'a', 0xD800, 'b' };
        printf("ADDRESS https://example.com/<U+1F600> %s\n", answerText(NativeBridge_identityAddress(env, NULL, hostString(emojiUri, 22), 0)));
        printf("ADDRESS https://example.com/a<U+0000>b %s\n", answerText(NativeBridge_identityAddress(env, NULL, hostString(nulUri, 23), 0)));
        printf("ADDRESS https://example.com/a<U+D800>b %s\n", answerText(NativeBridge_identityAddress(env, NULL, hostString(loneUri, 23), 0)));
        printf("SIGN site emoji uri %s\n", answerText(NativeBridge_signIdentityMessage(env, NULL, jascii(request), hostString(emojiUri, 22), 0)));
        release_all();
    }
    printf("SIGN shared login format 0 %s\n", answerText(NativeBridge_signMessage(env, NULL, jascii(login), 0)));
    printf("SIGN shared login format 2 %s\n", answerText(NativeBridge_signMessage(env, NULL, jascii(login), 2)));
    printf("SIGN shared empty format 0 %s\n", answerText(NativeBridge_signMessage(env, NULL, jascii(""), 0)));
    printf("SIGN site request %s\n", answerText(NativeBridge_signIdentityMessage(env, NULL, jascii(request), jascii(golden[0]), 0)));
    printf("SIGN shared request %s\n", answerText(NativeBridge_signMessage(env, NULL, jascii(request), 0)));
    printf("SIGN site emoji %s\n", answerText(NativeBridge_signIdentityMessage(env, NULL, hostString(emoji, 7),
                                                                                jascii("https://site.example/login"), 0)));
    release_all();
    boot_phrase("inhale praise target steak garlic cricket paper better evil almost sadness crawl city banner amused fringe fox "
                "insect roast aunt prefer hollow basic ladder");
    printf("ADDRESS bitid inhale %s\n", answerText(NativeBridge_identityAddress(env, NULL, jascii("http://bitid.bitcoin.blue/callback"), 0)));
    release_all();
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s sweep|print\n", argv[0]); return 2; }
    if (strcmp(argv[1], "print") == 0) print();
    else if (strcmp(argv[1], "sweep") == 0) sweep();
    else { fprintf(stderr, "unknown mode %s\n", argv[1]); return 2; }
    free(g_work);
    printf("RESULT bridge %s\n", g_fail ? "fail" : "pass");
    return g_fail ? 1 : 0;
}
