/*
 * Host KAT: a message is signed over the bytes the app sends for it.
 *
 * The app sends a Hub post, and a Digi-ID login, as the standard UTF-8 of its text (OkHttp and okio write
 * it so), and the signature must be over exactly those bytes -- that is what anyone checking it hashes.
 * JNI's GetStringUTFChars hands native code "modified UTF-8" instead: a character outside the Basic
 * Multilingual Plane (an emoji) comes as two 3-byte halves, and U+0000 as the two bytes C0 80. So the bridge
 * reads the message's UTF-16 code units and writes their standard UTF-8 itself; a surrogate without its
 * partner is written '?', as Java's and okio's encoders write it.
 *
 * It boots the bridge through the real JNI_OnLoad with a host VM and env, creates a wallet through the real
 * entry point, and calls the real NativeBridge_signMessage / NativeBridge_signIdentityMessage. The host env's
 * strings are UTF-16, as a JVM's are, and its GetStringUTFChars writes modified UTF-8 as the JNI
 * specification defines it -- so the comparison arm signs what a phone's bridge signed. A signature is
 * checked by recovering its public key over the message format of the expected bytes and comparing the
 * address that key gives with the address the bridge returned.
 *
 * SCENARIOS (argv[1]; run.sh says what each arm must do):
 *   emoji     a chat post holding an emoji is signed over its UTF-8.
 *   nul       a message holding U+0000 is signed over its UTF-8 (one 00 byte).
 *   lone      a surrogate without its partner is signed as '?'.
 *   identity  the per-site identity signs an emoji message over its UTF-8 (signIdentityMessage).
 *   ascii     GUARD: an ASCII Digi-ID login is signed over its bytes, and the signature is printed so run.sh
 *             can hold it equal across the arms: no login's signature changes.
 *   bmp       GUARD: text inside the Basic Multilingual Plane (no U+0000) is signed as it always was.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#include "jni_bridge.h"   /* the bridge's own header, over host_jni/<jni.h> and host_jni/<android/log.h> */
#include "BRKey.h"
#include "BRCrypto.h"

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);
jboolean JNICALL NativeBridge_createWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                    jbyteArray passphraseBytes);
jstring JNICALL NativeBridge_signMessage(JNIEnv *env, jobject thiz, jstring message, jint addressFormat);
jstring JNICALL NativeBridge_signIdentityMessage(JNIEnv *env, jobject thiz, jstring message, jstring siteUri,
                                                 jint index);

static int g_fail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

/* ---- the host VM and env ------------------------------------------------------------------------ */

/* Everything the host env hands out stays reachable from here, so the leak checker reports only what the
 * bridge itself allocates and does not free (its message bytes, a string it did not release). */
static void *g_kept[256];
static size_t g_keptCount = 0;
static void *keep(void *p)
{
    if (g_keptCount < sizeof(g_kept) / sizeof(*g_kept)) g_kept[g_keptCount++] = p;
    return p;
}

/* A Java byte[] as the host env hands it out. */
typedef struct { jsize len; jbyte *data; } HostArray;

static HostArray *hostArray(const void *bytes, size_t len)
{
    HostArray *a = keep(calloc(1, sizeof(*a)));
    a->len = (jsize)len;
    a->data = keep(calloc(len ? len : 1, 1));
    if (bytes && len) memcpy(a->data, bytes, len);
    return a;
}

/* A Java String as the host env holds it: UTF-16 code units. `text` is set for a string the bridge made
 * (NewStringUTF), so the KAT can read the bridge's answer. */
typedef struct { jsize len; jchar *units; char *text; } HostString;

static HostString *hostString(const jchar *units, size_t len)
{
    HostString *s = keep(calloc(1, sizeof(*s)));
    s->len = (jsize)len;
    s->units = keep(calloc(len ? len : 1, sizeof(jchar)));
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
static void h_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *elems, jint mode)
{
    (void)e; (void)a; (void)elems; (void)mode;
}
static void h_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize start, jsize len, jbyte *buf)
{
    (void)e;
    memcpy(buf, ((HostArray *)a)->data + start, (size_t)len);
}

static jsize h_GetStringLength(JNIEnv *e, jstring s) { (void)e; return ((HostString *)s)->len; }
static const jchar *h_GetStringChars(JNIEnv *e, jstring s, jboolean *isCopy)
{
    (void)e;
    HostString *h = s;
    jchar *copy = malloc((h->len ? (size_t)h->len : 1) * sizeof(jchar));
    memcpy(copy, h->units, (size_t)h->len * sizeof(jchar));
    if (isCopy) *isCopy = JNI_TRUE;
    return copy;
}
static void h_ReleaseStringChars(JNIEnv *e, jstring s, const jchar *chars) { (void)e; (void)s; free((void *)chars); }

/* Modified UTF-8, as the JNI specification defines GetStringUTFChars: each UTF-16 unit on its own (so a
 * surrogate pair is two 3-byte sequences), and U+0000 as C0 80, so the result holds no zero byte. */
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *isCopy)
{
    (void)e;
    HostString *h = s;
    unsigned char *out = malloc((size_t)h->len * 3 + 1);
    size_t n = 0;
    for (jsize i = 0; i < h->len; i++) {
        uint32_t c = h->units[i];
        if (c != 0 && c < 0x80) {
            out[n++] = (unsigned char)c;
        } else if (c < 0x800) {
            out[n++] = (unsigned char)(0xC0 | (c >> 6));
            out[n++] = (unsigned char)(0x80 | (c & 0x3F));
        } else {
            out[n++] = (unsigned char)(0xE0 | (c >> 12));
            out[n++] = (unsigned char)(0x80 | ((c >> 6) & 0x3F));
            out[n++] = (unsigned char)(0x80 | (c & 0x3F));
        }
    }
    out[n] = 0;
    if (isCopy) *isCopy = JNI_TRUE;
    return (const char *)out;
}
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *chars) { (void)e; (void)s; free((void *)chars); }

static jstring h_NewStringUTF(JNIEnv *e, const char *bytes)
{
    (void)e;
    HostString *s = hostString(NULL, 0);
    s->text = keep(strdup(bytes));
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

/* jni_registry.c is not compiled here; JNI_OnLoad's registration step succeeds. */
int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }

int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    va_list ap;
    (void)prio; (void)tag;
    va_start(ap, fmt);
    printf("   [bridge] ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
    return 0;
}

/* ---- checking a signature ------------------------------------------------------------------------ */

static int b64value(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* Base64 to bytes; returns the length, or 0 for anything malformed. */
static size_t b64decode(const char *in, uint8_t *out, size_t cap)
{
    size_t n = 0, len = strlen(in);
    if (len % 4 != 0) return 0;
    for (size_t i = 0; i < len; i += 4) {
        int v[4];
        for (int k = 0; k < 4; k++) v[k] = (in[i + k] == '=') ? 0 : b64value(in[i + k]);
        if (v[0] < 0 || v[1] < 0 || v[2] < 0 || v[3] < 0) return 0;
        uint32_t w = ((uint32_t)v[0] << 18) | ((uint32_t)v[1] << 12) | ((uint32_t)v[2] << 6) | (uint32_t)v[3];
        uint8_t b[3] = { (uint8_t)(w >> 16), (uint8_t)(w >> 8), (uint8_t)w };
        int take = 3 - (in[i + 2] == '=') - (in[i + 3] == '=');
        for (int k = 0; k < take; k++) { if (n >= cap) return 0; out[n++] = b[k]; }
    }
    return n;
}

/* True when `answer` ("address|base64 signature") is a signature, by that address's key, over the message
 * format of exactly `bytes`: "\x19DigiByte Signed Message:\n" + compact size + the bytes, hashed twice. */
static int signs_bytes(const char *answer, const uint8_t *bytes, size_t len)
{
    const char *bar = answer ? strchr(answer, '|') : NULL;
    if (! bar) return 0;
    char address[75] = { 0 };
    size_t addressLen = (size_t)(bar - answer);
    if (addressLen == 0 || addressLen >= sizeof(address)) return 0;
    memcpy(address, answer, addressLen);

    uint8_t sig[65];
    if (b64decode(bar + 1, sig, sizeof(sig)) != sizeof(sig)) return 0;

    static const char header[] = "\x19" "DigiByte Signed Message:\n";
    size_t headerLen = sizeof(header) - 1;
    uint8_t *payload = malloc(headerLen + 3 + len);
    size_t at = 0;
    memcpy(payload, header, headerLen); at += headerLen;
    if (len < 0xfd) {
        payload[at++] = (uint8_t)len;
    } else {
        payload[at++] = 0xfd; payload[at++] = (uint8_t)(len & 0xff); payload[at++] = (uint8_t)(len >> 8);
    }
    memcpy(payload + at, bytes, len); at += len;
    UInt256 md;
    BRSHA256_2(&md, payload, at);
    free(payload);

    BRKey key;
    memset(&key, 0, sizeof(key));
    if (! BRKeyRecoverPubKey(&key, md, sig, sizeof(sig))) return 0;
    char recovered[75] = { 0 };
    BRKeyAddress(&key, recovered, sizeof(recovered));
    return strcmp(recovered, address) == 0;
}

/* ---- fixtures ------------------------------------------------------------------------------------ */

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static jstring utf16(const jchar *units, size_t len) { return hostString(units, len); }

/* An ASCII (or Latin-1) C string as a Java String. */
static jstring ascii(const char *text)
{
    size_t len = strlen(text);
    jchar *units = calloc(len ? len : 1, sizeof(jchar));
    for (size_t i = 0; i < len; i++) units[i] = (jchar)(unsigned char)text[i];
    jstring s = utf16(units, len);
    free(units);
    return s;
}

static const char *answerText(jstring answer) { return answer ? ((HostString *)answer)->text : NULL; }

static void boot(void)
{
    check(JNI_OnLoad(&g_vmValue, NULL) == JNI_VERSION_1_6, "the bridge boots (JNI_OnLoad)");
    HostArray *phrase = hostArray(kMnemonic, strlen(kMnemonic));
    check(NativeBridge_createWalletFromBytes(env, NULL, phrase, NULL) == JNI_TRUE, "a wallet is created from its phrase");
}

/* ---- scenarios ----------------------------------------------------------------------------------- */

/* "gm " U+1F600 " !": the emoji is the surrogate pair D83D DE00 in UTF-16, F0 9F 98 80 in UTF-8. */
static const jchar kEmoji[] = { 'g', 'm', ' ', 0xD83D, 0xDE00, ' ', '!' };
static const uint8_t kEmojiUtf8[] = { 'g', 'm', ' ', 0xF0, 0x9F, 0x98, 0x80, ' ', '!' };

static void scenario_emoji(void)
{
    const char *answer = answerText(NativeBridge_signMessage(env, NULL, utf16(kEmoji, 7), 1));
    check(answer != NULL, "the message is signed");
    check(signs_bytes(answer, kEmojiUtf8, sizeof(kEmojiUtf8)),
          "the signature is over the message's UTF-8, the bytes sent (F0 9F 98 80 for the emoji)");
}

static void scenario_nul(void)
{
    static const jchar units[] = { 'a', 0x0000, 'b' };
    static const uint8_t bytes[] = { 'a', 0x00, 'b' };
    const char *answer = answerText(NativeBridge_signMessage(env, NULL, utf16(units, 3), 1));
    check(answer != NULL, "the message is signed");
    check(signs_bytes(answer, bytes, sizeof(bytes)), "the signature is over the message's UTF-8 (U+0000 as one 00 byte)");
}

static void scenario_lone(void)
{
    static const jchar units[] = { 'a', 0xD800, 'b', 0xDC00 };
    static const uint8_t bytes[] = { 'a', '?', 'b', '?' };
    const char *answer = answerText(NativeBridge_signMessage(env, NULL, utf16(units, 4), 1));
    check(answer != NULL, "the message is signed");
    check(signs_bytes(answer, bytes, sizeof(bytes)), "the signature is over the message's UTF-8 (a surrogate without its partner as '?', as it is sent)");
}

static void scenario_identity(void)
{
    const char *answer = answerText(NativeBridge_signIdentityMessage(env, NULL, utf16(kEmoji, 7),
                                                                     ascii("https://site.example/login"), 0));
    check(answer != NULL, "the message is signed with the per-site identity");
    check(signs_bytes(answer, kEmojiUtf8, sizeof(kEmojiUtf8)), "the per-site signature is over the message's UTF-8");
}

static void scenario_ascii(void)
{
    static const char *login = "digiid://api.digiscope.me/api/auth/digiid/callback?x=00112233445566778899aabbccddeeff";
    const char *answer = answerText(NativeBridge_signMessage(env, NULL, ascii(login), 0));
    check(answer != NULL, "the login is signed");
    check(signs_bytes(answer, (const uint8_t *)login, strlen(login)), "the signature is over the login's bytes");
    printf("SIGNATURE %s\n", answer ? answer : "(none)");
}

static void scenario_bmp(void)
{
    /* "é ü 中": two-byte and three-byte characters, no surrogate, no U+0000. */
    static const jchar units[] = { 0x00E9, ' ', 0x00FC, ' ', 0x4E2D };
    static const uint8_t bytes[] = { 0xC3, 0xA9, ' ', 0xC3, 0xBC, ' ', 0xE4, 0xB8, 0xAD };
    const char *answer = answerText(NativeBridge_signMessage(env, NULL, utf16(units, 5), 1));
    check(answer != NULL, "the message is signed");
    check(signs_bytes(answer, bytes, sizeof(bytes)), "the signature is over the message's UTF-8");
    printf("SIGNATURE %s\n", answer ? answer : "(none)");
}

int main(int argc, char **argv)
{
    static const struct { const char *name; void (*run)(void); } scenarios[] = {
        { "emoji", scenario_emoji }, { "nul", scenario_nul }, { "lone", scenario_lone },
        { "identity", scenario_identity }, { "ascii", scenario_ascii }, { "bmp", scenario_bmp },
    };
    const char *want = (argc > 1) ? argv[1] : NULL;
    if (! want) { printf("usage: %s <scenario>\n", argv[0]); return 2; }
    for (size_t i = 0; i < sizeof(scenarios) / sizeof(*scenarios); i++) {
        if (strcmp(scenarios[i].name, want) != 0) continue;
        printf("== %s\n", want);
        boot();
        scenarios[i].run();
        printf("%s: %d failure(s)\n", want, g_fail);
        return g_fail == 0 ? 0 : 1;
    }
    printf("unknown scenario: %s\n", want);
    return 2;
}
