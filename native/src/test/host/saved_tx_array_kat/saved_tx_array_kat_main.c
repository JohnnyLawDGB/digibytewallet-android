/* saved_tx_array_kat -- the saved records reach only the wallet they were loaded for, and the bridge keeps
 * no pointer to them once that wallet is built.
 *
 * THE INVARIANTS THIS PINS
 * ------------------------
 * loadSerializedTransactions parses the saved records into the bridge's array (g_savedTransactions) for the
 * disk restore, restoreWalletFromBytes, which must follow: WalletManager.restoreFromDisk loads the cache,
 * then calls it. It is the only entry that builds a wallet from loaded records.
 *   1. Once the wallet is built, the bridge names none of the records: BRWalletNewDual takes the ones it
 *      keeps (the wallet owns them from then on, and BRWalletFree releases them) and skips the rest (an
 *      unsigned record, a repeated hash, one outside the money range); the skipped ones are released, then
 *      the array, and the array is cleared. So a second load in the same process frees nothing a wallet
 *      owns, and a later build never starts from a record a wallet released.
 *   2. A wallet is never built from records loaded for another one: creating a wallet
 *      (createWalletFromBytes) and recovering one from a phrase (recoverWalletFromBytes) first drop any
 *      pending load, so neither -- nor any restore after them -- builds from it.
 *
 * THE PATH (the bridge's own code, compiled here)
 * -----------------------------------------------
 * This KAT compiles jni_transaction_persist.c and jni_wallet.c themselves, over the stand-ins in host_jni/
 * for <jni.h> and <android/log.h> (jni_registry.c is not compiled: dgb_register_natives is stubbed below).
 * It boots the bridge through the real JNI_OnLoad with a host VM and env, then calls the real entry points
 * the app calls: getSerializedTransactions (what WalletManager / SyncService persist), the restore from
 * disk (loadSerializedTransactions, then restoreWalletFromBytes -- WalletManager.restoreFromDisk), a
 * recovery from a phrase (recoverWalletFromBytes -- WalletManager.recoverWallet) and a creation
 * (createWalletFromBytes -- WalletManager.createWallet). run.sh holds the Kotlin side to the same rule: the
 * load and restoreWalletFromBytes are called from restoreFromDisk alone.
 *
 * ARMS (presence of a macro selects a comparison arm; the shipped arm is built with no -D):
 *   default                          both rules above.
 *   -DSAVED_TX_ARRAY_UNFIXED         the array keeps naming the records after the build (rule 1 off).
 *   -DSAVED_TX_PENDING_LOAD_UNFIXED  creating and recovering keep a pending load (rule 2 off).
 *
 * SCENARIOS (argv[1]; run.sh says what each arm must do):
 *   reload_after_build       RED-THEN-GREEN (AddressSanitizer) against rule 1. Restore from disk twice in
 *                            one process: the second load must not release the live wallet's records, so
 *                            the second restore frees each record once and builds from the new load.
 *   recover_after_build      RED-THEN-GREEN (AddressSanitizer) against rule 1. Recover from the phrase after
 *                            a restore: the new wallet is built from no saved records, never from records
 *                            the previous wallet released.
 *   unadopted_released       RED-THEN-GREEN (assertion) against rule 1. A cache that repeats a record: the
 *                            wallet takes the first copy and skips the second; once built, the skipped copy
 *                            is released and the array is cleared.
 *   foreign_load_recover     RED-THEN-GREEN (assertion) against rule 2. Wallet A's cache is loaded and no
 *                            wallet is built from it; wallet B, recovered from its phrase, holds none of it.
 *   foreign_load_create      RED-THEN-GREEN (assertion) against rule 2. Wallet A's cache is loaded; wallet B
 *                            is created, which drops the load, so B's later restore holds none of it.
 *   load_twice_before_build  GUARD, every arm. Two loads before any build: the second releases the first
 *                            load's records (no wallet ever took them) and the restore takes the second's.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <time.h>

#include "jni_bridge.h"   /* the bridge's own header, over host_jni/<jni.h> and host_jni/<android/log.h> */

/* ASan runtime query: nonzero when the byte at addr is in a poisoned (released) region. It reads shadow
 * memory and never dereferences addr, so it is safe to ask about a released record. */
int __asan_address_is_poisoned(void const volatile *addr);

/* The bridge's array and entry points under test (jni_transaction_persist.c, jni_wallet.c). */
extern BRTransaction **g_savedTransactions;
extern size_t g_savedTransactionCount;
jint JNICALL NativeBridge_loadSerializedTransactions(JNIEnv *env, jobject thiz, jbyteArray data);
jbyteArray JNICALL NativeBridge_getSerializedTransactions(JNIEnv *env, jobject thiz);
jboolean JNICALL NativeBridge_recoverWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                     jlong creationTimestamp, jbyteArray passphrase);
jboolean JNICALL NativeBridge_createWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                    jbyteArray passphrase);
jboolean JNICALL NativeBridge_restoreWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                     jlong creationTimestamp, jbyteArray passphrase);
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);

static int g_fail = 0;
static void check(int cond, const char *what)
{
    printf("   %s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

/* ---- the host VM and env ------------------------------------------------------------------------ */

/* A Java byte[] as the host env hands it out: the bytes, owned by the KAT and released per scenario. */
typedef struct { jsize len; jbyte *data; } HostArray;
static HostArray *g_arrays[32];
static size_t g_arrayCount = 0;

static HostArray *hostArray(const void *bytes, size_t len)
{
    HostArray *a = calloc(1, sizeof(*a));
    a->len = (jsize)len;
    a->data = calloc(len ? len : 1, 1);
    if (bytes && len) memcpy(a->data, bytes, len);
    if (g_arrayCount < sizeof(g_arrays)/sizeof(*g_arrays)) g_arrays[g_arrayCount++] = a;
    return a;
}

static void releaseHostArrays(void)
{
    for (size_t i = 0; i < g_arrayCount; i++) { free(g_arrays[i]->data); free(g_arrays[i]); }
    g_arrayCount = 0;
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
static jbyteArray h_NewByteArray(JNIEnv *e, jsize len) { (void)e; return hostArray(NULL, (size_t)len); }
static void h_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize start, jsize len, const jbyte *buf)
{
    (void)e;
    memcpy(((HostArray *)a)->data + start, buf, (size_t)len);
}

static const struct JNINativeInterface g_table = {
    .GetArrayLength = h_GetArrayLength,
    .GetByteArrayElements = h_GetByteArrayElements,
    .ReleaseByteArrayElements = h_ReleaseByteArrayElements,
    .GetByteArrayRegion = h_GetByteArrayRegion,
    .NewByteArray = h_NewByteArray,
    .SetByteArrayRegion = h_SetByteArrayRegion,
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

/* ---- fixtures ------------------------------------------------------------------------------------ */

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const char *kOtherMnemonic =   /* another wallet: a BIP39 reference phrase */
    "legal winner thank year wave sausage worth useful legal winner thank yellow";
static const uint8_t kEmpty[1] = { 0 };
#define CREATION_TIME 1700000000

static HostArray *phraseArray(const char *phrase) { return hostArray(phrase, strlen(phrase)); }

/* The native call WalletManager.restoreFromDisk makes after it loads the cache. */
static jboolean restoreEntry(const char *phrase)
{
    return NativeBridge_restoreWalletFromBytes(env, NULL, phraseArray(phrase), CREATION_TIME, NULL);
}

/* The native call WalletManager.recoverWallet makes: a phrase the user entered. */
static jboolean recoverEntry(const char *phrase)
{
    return NativeBridge_recoverWalletFromBytes(env, NULL, phraseArray(phrase), CREATION_TIME, NULL);
}

/* The native call WalletManager.createWallet makes. */
static jboolean createEntry(const char *phrase)
{
    return NativeBridge_createWalletFromBytes(env, NULL, phraseArray(phrase), NULL);
}

static jint loadSaved(HostArray *blob) { return NativeBridge_loadSerializedTransactions(env, NULL, blob); }

/* A receive as a peer or a block delivers it: someone else's coin in (segwit witness), `amount` to `spk`,
 * change to the sender. Built, serialized and parsed back, so the record is the wire object. */
static BRTransaction *receive(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t tag)
{
    BRTransaction *tx = BRTransactionNew();
    uint8_t wit[106], fwpkh[22];
    UInt256 prev;

    memset(prev.u8, tag, 32);
    wit[0] = 71; memset(wit + 1, tag, 71); wit[1] = 0x30; wit[71] = 0x01;
    wit[72] = 33; wit[73] = 0x02; memset(wit + 74, (uint8_t)(tag ^ 0x5a), 32);
    fwpkh[0] = 0x00; fwpkh[1] = 0x14; memset(fwpkh + 2, 0x66, 20);
    tx->version = 2;
    BRTransactionAddInput(tx, prev, 0, 0, NULL, 0, kEmpty, 0, wit, sizeof(wit), 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    BRTransactionAddOutput(tx, 50000000, fwpkh, sizeof(fwpkh));

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len);
    BRTransactionSerialize(tx, buf, len);
    BRTransaction *parsed = BRTransactionParse(buf, len);
    free(buf);
    BRTransactionFree(tx);
    parsed->blockHeight = 24300000 + tag;
    parsed->timestamp = 1790000000u + tag;
    return parsed;
}

/* The first launch: the bridge boots, the wallet is recovered from its phrase with no saved records, it
 * receives twice, and the app saves its records (getSerializedTransactions). Then that process ends: its
 * wallet is released and nothing is left in the bridge. Returns the saved cache; hashes[] get the records. */
static HostArray *firstLaunchCache(UInt256 hashes[2])
{
    check(JNI_OnLoad(&g_vmValue, NULL) == JNI_VERSION_1_6, "the bridge boots (JNI_OnLoad)");
    check(recoverEntry(kMnemonic) == JNI_TRUE && g_wallet != NULL, "first launch: the wallet is recovered from its phrase");

    BRAddress a = BRWalletReceiveAddress(g_wallet, 1);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    BRTransaction *r1 = receive(spk, spkLen, 100000000, 0x21);
    BRTransaction *r2 = receive(spk, spkLen, 25000000, 0x22);
    hashes[0] = r1->txHash;
    hashes[1] = r2->txHash;
    check(BRWalletRegisterTransaction(g_wallet, r1) && BRWalletRegisterTransaction(g_wallet, r2),
          "first launch: the wallet holds two receives");

    HostArray *cache = (HostArray *)NativeBridge_getSerializedTransactions(env, NULL);
    check(cache != NULL && cache->len > 4, "first launch: the app saves the wallet's records");

    BRWalletFree(g_wallet);   /* the first process ends */
    g_wallet = NULL;
    return cache;
}

/* The restore from disk WalletManager.restoreFromDisk performs: load the cache, then restore. */
static void restoreFromDisk(HostArray *cache, jint expectLoaded, const char *what)
{
    jint loaded = loadSaved(cache);
    jboolean ok = restoreEntry(kMnemonic);
    check(loaded == expectLoaded && ok == JNI_TRUE && g_wallet != NULL, what);
}

static int holds(UInt256 h) { return g_wallet && BRWalletTransactionForHash(g_wallet, h) != NULL; }

static void endScenario(void)
{
    if (g_wallet) BRWalletFree(g_wallet);   /* the process ends */
    g_wallet = NULL;
    releaseHostArrays();
}

/* ---- reload_after_build --------------------------------------------------------------------------- */
static void scenario_reload_after_build(void)
{
    printf("\n-- reload_after_build: restoring from disk twice in one process frees each record once --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    restoreFromDisk(cache, 2, "restore from disk: two records loaded, the wallet recovered");
    check(holds(h[0]) && holds(h[1]), "the wallet holds both saved records");
    check(g_savedTransactions == NULL && g_savedTransactionCount == 0,
          "INVARIANT: once the wallet is built, the bridge names none of the records");

    /* The same process restores from disk again: the cache is read a second time, then the wallet is
     * restored, which releases the first wallet (and with it the records it took). */
    restoreFromDisk(cache, 2, "restore from disk again: two records loaded, the wallet recovered");
    check(holds(h[0]) && holds(h[1]), "the new wallet holds both saved records");

    HostArray *again = (HostArray *)NativeBridge_getSerializedTransactions(env, NULL);
    check(again != NULL && again->len == cache->len && memcmp(again->data, cache->data, (size_t)cache->len) == 0,
          "the app's next save writes the same records");
    endScenario();
}

/* ---- recover_after_build -------------------------------------------------------------------------- */
static void scenario_recover_after_build(void)
{
    printf("\n-- recover_after_build: recovering from the phrase after a restore builds from no saved records --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    restoreFromDisk(cache, 2, "restore from disk: two records loaded, the wallet recovered");
    check(holds(h[0]) && holds(h[1]), "the wallet holds both saved records");

    /* A recovery from the phrase in the same process: no new load. */
    check(recoverEntry(kMnemonic) == JNI_TRUE && g_wallet != NULL, "recover from the phrase again");
    check(! holds(h[0]) && ! holds(h[1]), "the new wallet holds no record the previous wallet took");
    check(g_savedTransactions == NULL && g_savedTransactionCount == 0,
          "INVARIANT: the bridge names none of the records");
    endScenario();
}

/* ---- unadopted_released --------------------------------------------------------------------------- */
static void scenario_unadopted_released(void)
{
    printf("\n-- unadopted_released: a record the wallet does not take is released when the wallet is built --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    /* The cache with its first record written twice: [count 3][record 1][record 2][record 1]. */
    size_t len1 = 12 + UInt32GetLE(&((uint8_t *)cache->data)[4]);
    size_t dupLen = (size_t)cache->len + len1;
    uint8_t *dup = malloc(dupLen);
    memcpy(dup, cache->data, (size_t)cache->len);
    memcpy(dup + cache->len, (uint8_t *)cache->data + 4, len1);
    UInt32SetLE(dup, 3);
    HostArray *repeated = hostArray(dup, dupLen);
    free(dup);

    check(loadSaved(repeated) == 3, "the cache's three records are loaded");
    BRTransaction *p[3] = { g_savedTransactions[0], g_savedTransactions[1], g_savedTransactions[2] };
    check(UInt256Eq(p[0]->txHash, p[2]->txHash) && p[0] != p[2], "the first and third records are two copies of one");

    check(restoreEntry(kMnemonic) == JNI_TRUE && g_wallet != NULL, "the wallet is restored from them");
    check(BRWalletTransactionForHash(g_wallet, p[0]->txHash) == p[0] &&
          BRWalletTransactionForHash(g_wallet, p[1]->txHash) == p[1],
          "the wallet took the first copy and the second record");
    check(__asan_address_is_poisoned(p[2]) != 0,
          "INVARIANT: the copy the wallet did not take is released when the wallet is built");
    check(g_savedTransactions == NULL && g_savedTransactionCount == 0,
          "INVARIANT: once the wallet is built, the bridge names none of the records");
    check(__asan_address_is_poisoned(p[0]) == 0 && __asan_address_is_poisoned(p[1]) == 0,
          "the records the wallet took are untouched");
    endScenario();
}

/* ---- load_twice_before_build ---------------------------------------------------------------------- */
static void scenario_load_twice_before_build(void)
{
    printf("\n-- load_twice_before_build: a second load before any build releases the first load's records --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    check(loadSaved(cache) == 2, "the cache is loaded");
    check(loadSaved(cache) == 2, "the cache is loaded again before any wallet was built from it");
    check(restoreEntry(kMnemonic) == JNI_TRUE && holds(h[0]) && holds(h[1]),
          "the wallet is restored from the second load and holds both records");
    HostArray *again = (HostArray *)NativeBridge_getSerializedTransactions(env, NULL);
    check(again != NULL && again->len == cache->len, "the app's next save writes both records");
    endScenario();
}

/* ---- foreign_load_recover ------------------------------------------------------------------------- */
static void scenario_foreign_load_recover(void)
{
    printf("\n-- foreign_load_recover: a wallet recovered from a phrase is never built from records loaded for another --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    /* A restore from disk read wallet A's cache and stopped before building. */
    check(loadSaved(cache) == 2, "wallet A's cache is loaded, and no wallet is built from it");
    check(recoverEntry(kOtherMnemonic) == JNI_TRUE && g_wallet != NULL, "wallet B is recovered from its phrase");
    check(! holds(h[0]) && ! holds(h[1]), "INVARIANT: wallet B holds none of the records loaded for wallet A");
    check(g_savedTransactions == NULL && g_savedTransactionCount == 0, "INVARIANT: the bridge names none of them");
    endScenario();
}

/* ---- foreign_load_create -------------------------------------------------------------------------- */
static void scenario_foreign_load_create(void)
{
    printf("\n-- foreign_load_create: creating a wallet drops a pending load, so no later restore builds from it --\n");
    UInt256 h[2];
    HostArray *cache = firstLaunchCache(h);

    check(loadSaved(cache) == 2, "wallet A's cache is loaded, and no wallet is built from it");
    check(createEntry(kOtherMnemonic) == JNI_TRUE && g_wallet != NULL, "wallet B is created");
    check(g_savedTransactions == NULL && g_savedTransactionCount == 0,
          "INVARIANT: creating a wallet drops the pending load");
    /* Wallet B is restored from disk later in this process. Its own cache was cleared when it was
     * created, so this restore loads nothing. */
    check(restoreEntry(kOtherMnemonic) == JNI_TRUE && g_wallet != NULL, "wallet B is restored, with no new load");
    check(! holds(h[0]) && ! holds(h[1]), "INVARIANT: wallet B holds none of the records loaded for wallet A");
    endScenario();
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
#if defined(SAVED_TX_ARRAY_UNFIXED)
    printf("ARM: comparison (-DSAVED_TX_ARRAY_UNFIXED) -- the array keeps naming the records after the build\n");
#elif defined(SAVED_TX_PENDING_LOAD_UNFIXED)
    printf("ARM: comparison (-DSAVED_TX_PENDING_LOAD_UNFIXED) -- creating and recovering keep a pending load\n");
#else
    printf("ARM: shipped -- the records reach only their wallet, and the bridge keeps none once it is built\n");
#endif
    static const struct { const char *name; void (*fn)(void); } scenarios[] = {
        { "reload_after_build",      scenario_reload_after_build },
        { "recover_after_build",     scenario_recover_after_build },
        { "unadopted_released",      scenario_unadopted_released },
        { "load_twice_before_build", scenario_load_twice_before_build },
        { "foreign_load_recover",    scenario_foreign_load_recover },
        { "foreign_load_create",     scenario_foreign_load_create },
    };
    const char *want = (argc > 1) ? argv[1] : NULL;
    int ran = 0;

    if (! want) { printf("usage: %s <scenario>   (one scenario per process: the bridge boots once)\n", argv[0]); return 2; }
    for (size_t i = 0; i < sizeof(scenarios)/sizeof(*scenarios); i++) {
        if (strcmp(want, scenarios[i].name) != 0) continue;
        scenarios[i].fn();
        ran++;
    }
    if (! ran) { printf("unknown scenario: %s\n", want); return 2; }

    printf(g_fail ? "\nsaved_tx_array_kat[%s]: %d CHECK(S) FAILED\n" : "\nsaved_tx_array_kat[%s]: ALL PASS\n", want, g_fail);
    return g_fail ? 1 : 0;
}
