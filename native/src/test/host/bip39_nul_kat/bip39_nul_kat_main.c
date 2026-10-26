// Host KAT: a BIP39 passphrase with an embedded NUL is refused by the bridge, never truncated (4.1.2 row 25).
//   [A] createWalletFromBytes(phrase, "abc\0evil") is refused -- RED under BIP39_NUL_UNFIXED, where it
//       derives the wallet of "abc";
//   [B] guard: an ordinary passphrase still creates a wallet.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
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
    return 0; printf("   [bridge] ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
    return 0;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    static const char *kPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    check(JNI_OnLoad(&g_vmValue, NULL) == JNI_VERSION_1_6, "the bridge boots (JNI_OnLoad)");
    HostArray *phrase = hostArray(kPhrase, strlen(kPhrase));
    const char nul[] = { 'a', 'b', 'c', 0, 'e', 'v', 'i', 'l' };
    if (NativeBridge_createWalletFromBytes(env, NULL, phrase, hostArray(nul, sizeof(nul))) != JNI_FALSE) {
        printf("FAIL: A: a passphrase with an embedded NUL created a wallet\n"); g_fail++;
    } else check(1, "a passphrase with an embedded NUL is refused");
    check(NativeBridge_createWalletFromBytes(env, NULL, phrase, hostArray("correct horse", 13)) == JNI_TRUE,
          "an ordinary passphrase still creates a wallet");
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: bip39_nul_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
