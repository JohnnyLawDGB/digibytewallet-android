// Host KAT: no JNI entry point reads g_wallet while a create or restore frees it (4.1.2 row 6, N7).
//
// Thread B swaps the wallet (NativeBridge_createWalletFromBytes: free g_wallet, install a new one) while
// thread A calls readers that do not take PEER_GUARD -- getBalance, getReceiveAddress,
// getTransactionCount, getTransactionDetails -- through the bridge's own code, over the host JNI stand-ins
// of identity_sign_bytes_kat (host_jni/ is shared). Built under ThreadSanitizer.
//
// Two phases:
//   interleave  (KAT-RACE: the red arm shows the race on every run.) One swap, then one reader call, per
//               reader, in that order. The order is enforced with RELAXED atomics only: ThreadSanitizer
//               gives a relaxed load/store no happens-before edge, so the step flag orders the threads
//               in real time while leaving the bridge's own synchronization as the only edge between the
//               swap's write of g_wallet and the reader's read of it. Unguarded, every reader's first
//               read of g_wallet is a race TSan sees on every run; guarded, the write side's unlock
//               happens-before the reader's shared lock and the step is clean. No timing is involved:
//               the old stress loop alone showed the race on about half the runs.
//               What it proves: the read of g_wallet has no happens-before edge to the swap's write, which
//               is what lets a concurrent free be read. The free-while-reading window itself is NOT run:
//               the reader reads the new, live wallet, no fault occurs, and a red run's rc 66 is the TSan
//               report alone -- it is not a reproduced use-after-free.
//               TSan reports a race on a word once and then marks the word as read-only for the rest of
//               the process, so a red run that proves EVERY reader runs one process per reader
//               (`interleave <reader>`); the green arm runs all four steps in one process.
//   stress      the swap loop against the reader loop (green arm only): the guard holds under contention.
//   RED    -DWALLET_READ_GUARD_UNFIXED, `interleave <reader>` per reader: a TSan data race in its step.
//   GREEN  WALLET_READ_GUARD() on every reader, the write side around the swap: both phases TSan clean.
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <signal.h>
#include <sched.h>
#include <stdatomic.h>
#include <unistd.h>

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

jlong JNICALL NativeBridge_getBalance(JNIEnv *env, jobject thiz);
jstring JNICALL NativeBridge_getReceiveAddress(JNIEnv *env, jobject thiz, jint index, jint format);
jint JNICALL NativeBridge_getTransactionCount(JNIEnv *env, jobject thiz);
jstring JNICALL NativeBridge_getTransactionDetails(JNIEnv *env, jobject thiz);

#define SWAPS 40
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static HostArray *g_phrase;

/* ---- interleave: one swap, then one read, per reader, ordered without a happens-before edge ---------- */

static void call_balance(void) { (void)NativeBridge_getBalance(env, NULL); }
static void call_receive(void) { (void)NativeBridge_getReceiveAddress(env, NULL, 0, 0); }
static void call_count(void) { (void)NativeBridge_getTransactionCount(env, NULL); }
static void call_details(void) { (void)NativeBridge_getTransactionDetails(env, NULL); }
static const struct { const char *name; void (*call)(void); } kReaders[] = {
    { "getBalance", call_balance },
    { "getReceiveAddress", call_receive },
    { "getTransactionCount", call_count },
    { "getTransactionDetails", call_details },
};
#define NREADERS ((int)(sizeof(kReaders) / sizeof(*kReaders)))

/* Step 2i: B swaps. Step 2i+1: A calls reader i. Relaxed on purpose -- see the header. */
static atomic_int g_step;
static void await_step(int step)
{
    while (atomic_load_explicit(&g_step, memory_order_relaxed) != step) sched_yield();
}
static void set_step(int step) { atomic_store_explicit(&g_step, step, memory_order_relaxed); }

static int g_first = 0, g_last = NREADERS;   /* the readers this run steps through: [g_first, g_last) */

static void *interleave_swapper(void *arg)
{
    (void)arg;
    for (int i = g_first; i < g_last; i++) {
        await_step(2 * i);
        (void)NativeBridge_createWalletFromBytes(env, NULL, g_phrase, NULL);
        set_step(2 * i + 1);
    }
    return NULL;
}

static void *interleave_reader(void *arg)
{
    (void)arg;
    for (int i = g_first; i < g_last; i++) {
        await_step(2 * i + 1);
        /* The marker precedes the call, so a report printed during the call lands after it: run.sh
         * attributes every report to the step it fell in. stdout is unbuffered, as is TSan's stderr. */
        printf("   [step] %s after a swap\n", kReaders[i].name);
        kReaders[i].call();
        set_step(2 * i + 2);
    }
    return NULL;
}

/* ---- stress: the swap loop against the reader loop --------------------------------------------------- */

/* The reader keeps calling until the swapper is done, so every swap meets readers (the reader used to
 * run a fixed count and could finish before the swapper's slow BIP39 derivations had begun). */
static atomic_int g_swapsDone;
static int g_readerRounds;

static void *swapper(void *arg)
{
    (void)arg;
    for (int i = 0; i < SWAPS; i++) NativeBridge_createWalletFromBytes(env, NULL, g_phrase, NULL);
    atomic_store_explicit(&g_swapsDone, 1, memory_order_relaxed);
    return NULL;
}

static void *reader(void *arg)
{
    (void)arg;
    while (! atomic_load_explicit(&g_swapsDone, memory_order_relaxed)) {
        (void)NativeBridge_getBalance(env, NULL);
        (void)NativeBridge_getReceiveAddress(env, NULL, 0, 0);
        (void)NativeBridge_getTransactionCount(env, NULL);
        (void)NativeBridge_getTransactionDetails(env, NULL);
        g_readerRounds++;
    }
    return NULL;
}

static void run_pair(void *(*fa)(void *), void *(*fb)(void *))
{
    pthread_t a, b;
    pthread_create(&b, NULL, fb, NULL);
    pthread_create(&a, NULL, fa, NULL);
    pthread_join(a, NULL);
    pthread_join(b, NULL);
}

static void on_alarm(int sig)
{
    static const char mark[] = "\n   [watchdog] stopped by the in-process alarm\n";
    (void)sig;
    (void)!write(STDOUT_FILENO, mark, sizeof(mark) - 1);
    _exit(142);
}

/* usage: wallet_read_guard_kat all                  both phases, every reader (the default)
 *        wallet_read_guard_kat interleave [reader]  the interleave phase only, for one reader or all */
int main(int argc, char **argv)
{
    int stress = !(argc > 1 && strcmp(argv[1], "interleave") == 0);
    if (! stress && argc > 2) {
        g_first = -1;
        for (int i = 0; i < NREADERS; i++)
            if (strcmp(argv[2], kReaders[i].name) == 0) { g_first = i; g_last = i + 1; }
        if (g_first < 0) { printf("unknown reader: %s\n", argv[2]); return 2; }
    }
    atomic_store_explicit(&g_step, 2 * g_first, memory_order_relaxed);
    setvbuf(stdout, NULL, _IONBF, 0);
    /* In-process watchdog just inside run.sh's (WALLET_READ_GUARD_ALARM_S, which run.sh derives from its
     * LIMIT_S): a step that never comes (a thread that died) ends the run with a marker run.sh reports as
     * a wedge, not as a missing race. */
    const char *alarmEnv = getenv("WALLET_READ_GUARD_ALARM_S");
    int alarmS = alarmEnv ? atoi(alarmEnv) : 0;
    signal(SIGALRM, on_alarm);
    alarm(alarmS > 0 ? (unsigned)alarmS : 240);
    check(JNI_OnLoad(&g_vmValue, NULL) == JNI_VERSION_1_6, "the bridge boots (JNI_OnLoad)");
    g_phrase = hostArray(kMnemonic, strlen(kMnemonic));
    check(NativeBridge_createWalletFromBytes(env, NULL, g_phrase, NULL) == JNI_TRUE, "a wallet is created");

    run_pair(interleave_reader, interleave_swapper);
    printf("   [interleave] %d swap-then-read steps finished\n", g_last - g_first);

    if (stress) run_pair(reader, swapper);
    check(NativeBridge_getBalance(env, NULL) >= 0, "the wallet still answers after the swaps");
    if (g_fail) { printf("wallet_read_guard_kat: %d check(s) failed\n", g_fail); return 1; }
    if (stress) printf("wallet_read_guard_kat: %d swaps against %d reader rounds finished\n", SWAPS, g_readerRounds);
    return 0;
}
