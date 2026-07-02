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

    size_t txCount = BRWalletTransactions(g_wallet, NULL, 0);
    if (txCount == 0) return NULL;

    BRTransaction **txs = malloc(txCount * sizeof(BRTransaction *));
    if (!txs) return NULL;
    txCount = BRWalletTransactions(g_wallet, txs, txCount);

    /* Calculate total size */
    size_t totalSize = 4; /* tx count */
    size_t *txSizes = malloc(txCount * sizeof(size_t));
    if (!txSizes) { free(txs); return NULL; }

    for (size_t i = 0; i < txCount; i++) {
        txSizes[i] = BRTransactionSerialize(txs[i], NULL, 0);
        totalSize += 4 + 4 + 4 + txSizes[i]; /* length + height + timestamp + data */
    }

    uint8_t *buf = malloc(totalSize);
    if (!buf) { free(txSizes); free(txs); return NULL; }

    size_t pos = 0;
    UInt32SetLE(&buf[pos], (uint32_t)txCount); pos += 4;

    for (size_t i = 0; i < txCount; i++) {
        UInt32SetLE(&buf[pos], (uint32_t)txSizes[i]); pos += 4;
        UInt32SetLE(&buf[pos], txs[i]->blockHeight); pos += 4;
        UInt32SetLE(&buf[pos], txs[i]->timestamp); pos += 4;
        BRTransactionSerialize(txs[i], &buf[pos], txSizes[i]);
        pos += txSizes[i];
    }

    free(txSizes);
    free(txs);

    jbyteArray result = (*env)->NewByteArray(env, (jsize)totalSize);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)totalSize, (jbyte *)buf);
    }
    free(buf);

    LOGI("getSerializedTransactions: serialized %zu txs (%zu bytes)", txCount, totalSize);
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
