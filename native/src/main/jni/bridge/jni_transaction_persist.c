/*
 * jni_transaction_persist.c
 *
 * JNI bridge for saving/loading wallet transactions to survive app restarts.
 * Transactions are serialized to a byte array and stored by Kotlin in SharedPreferences.
 * On restore, they're passed to BRWalletNew so the wallet starts with full tx history
 * and the balance is immediately spendable.
 */

#include "jni_bridge.h"

/* ---------- getSerializedTransactions ----------
 * Returns all wallet transactions as a single byte array:
 * [4 bytes: tx count]
 * For each transaction:
 *   [4 bytes: serialized length]
 *   [4 bytes: block height]
 *   [4 bytes: timestamp]
 *   [N bytes: serialized tx data]
 */
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getSerializedTransactions(JNIEnv *env, jobject thiz) {
    (void)thiz;

    if (!g_wallet) return NULL;

    /* I1 fix: serialize UNDER the wallet lock. The old shape here copied raw
     * BRTransaction* pointers out via BRWalletTransactions (which unlocks after the
     * copy) and then ran the size+write passes with NO lock held — so a peer-thread
     * or reconcile-driven BRWalletRemoveTransaction could free a snapshotted tx
     * mid-serialize (use-after-free; torn bytes into the durable checkpoint). The new
     * BRWalletSerializeTransactions holds wallet->lock across the whole size+write
     * pass, so no tx can be freed between sizing and writing. Byte layout is unchanged,
     * so persisted blobs stay compatible with loadSerializedTransactions. */
    size_t need = BRWalletSerializeTransactions(g_wallet, NULL, 0);
    if (need <= 4) return NULL; /* only the tx-count header -> no transactions to persist */

    uint8_t *buf = malloc(need);
    if (!buf) return NULL;

    size_t totalSize = BRWalletSerializeTransactions(g_wallet, buf, need);
    if (totalSize > need) {
        /* The tx set grew between the two locked calls; retry once at the larger size. */
        free(buf);
        need = totalSize;
        buf = malloc(need);
        if (!buf) return NULL;
        totalSize = BRWalletSerializeTransactions(g_wallet, buf, need);
        if (totalSize > need) { free(buf); return NULL; } /* still racing — bail cleanly */
    }
    if (totalSize <= 4) { free(buf); return NULL; } /* all txs removed in the window */

    jbyteArray result = (*env)->NewByteArray(env, (jsize)totalSize);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)totalSize, (jbyte *)buf);
    }
    free(buf);

    LOGI("getSerializedTransactions: serialized %zu bytes (locked)", totalSize);
    return result;
}

/* ---------- loadSerializedTransactions ----------
 * Parse saved transactions and store them for use by BRWalletNew.
 * Must be called BEFORE recoverWallet/createWallet.
 */
BRTransaction **g_savedTransactions = NULL;
size_t g_savedTransactionCount = 0;

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_loadSerializedTransactions(JNIEnv *env, jobject thiz,
                                                                       jbyteArray data) {
    (void)thiz;

    /* Free any previously loaded transactions */
    if (g_savedTransactions) {
        for (size_t i = 0; i < g_savedTransactionCount; i++) {
            if (g_savedTransactions[i]) BRTransactionFree(g_savedTransactions[i]);
        }
        free(g_savedTransactions);
        g_savedTransactions = NULL;
        g_savedTransactionCount = 0;
    }

    if (!data) return 0;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 4) return 0;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return 0;

    const uint8_t *buf = (const uint8_t *)bytes;
    size_t pos = 0;

    uint32_t txCount = UInt32GetLE(&buf[pos]); pos += 4;
    if (txCount == 0 || txCount > 10000) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return 0;
    }

    g_savedTransactions = calloc(txCount, sizeof(BRTransaction *));
    if (!g_savedTransactions) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return 0;
    }

    size_t loaded = 0;
    for (uint32_t i = 0; i < txCount && pos + 12 <= (size_t)len; i++) {
        uint32_t txSize = UInt32GetLE(&buf[pos]); pos += 4;
        uint32_t height = UInt32GetLE(&buf[pos]); pos += 4;
        uint32_t timestamp = UInt32GetLE(&buf[pos]); pos += 4;

        if (pos + txSize > (size_t)len) break;

        BRTransaction *tx = BRTransactionParse(&buf[pos], txSize);
        pos += txSize;

        if (tx) {
            tx->blockHeight = height;
            tx->timestamp = timestamp;
            g_savedTransactions[loaded++] = tx;
        }
    }

    g_savedTransactionCount = loaded;
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    LOGI("loadSerializedTransactions: loaded %zu transactions from persistent storage", loaded);
    return (jint)loaded;
}
