/*
 * jni_transaction_persist.c
 *
 * JNI bridge for saving/loading wallet transactions to survive app restarts.
 * Transactions are serialized to a byte array and stored by Kotlin in SharedPreferences.
 * On restore, they're passed to BRWalletNew so the wallet starts with full tx history
 * and the balance is immediately spendable.
 */

#include "jni_bridge.h"
#include "saved_transactions_deserialize.h"

/* ---------- getSerializedTransactions ----------
 * Returns all wallet transactions as a single byte array:
 * [4 bytes: tx count]
 * For each transaction:
 *   [4 bytes: serialized length]
 *   [4 bytes: block height]
 *   [4 bytes: timestamp]
 *   [N bytes: serialized tx data]
 */
jbyteArray JNICALL
NativeBridge_getSerializedTransactions(JNIEnv *env, jobject thiz) {
    WALLET_READ_GUARD();   /* reads g_wallet: never while a create/restore frees it */
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
 * Parse saved transactions and hold them for restoreWalletFromBytes, the disk restore, which must follow:
 * it is the only entry that builds a wallet from loaded records. createWalletFromBytes and
 * recoverWalletFromBytes drop a pending load instead (saved_transactions_discard), so a wallet is never
 * built from records loaded for another one.
 */
BRTransaction **g_savedTransactions = NULL;
size_t g_savedTransactionCount = 0;

/* ---------- saved_transactions_discard ----------
 * Drops a pending load: the records the array holds -- none of them a wallet's, since building a wallet
 * from the array releases it (saved_transactions_release_after_build) -- then the array, which is cleared.
 */
void saved_transactions_discard(void) {
    if (g_savedTransactions) {
        for (size_t i = 0; i < g_savedTransactionCount; i++) {
            if (g_savedTransactions[i]) BRTransactionFree(g_savedTransactions[i]);
        }
        free(g_savedTransactions);
    }
    g_savedTransactions = NULL;
    g_savedTransactionCount = 0;
}

jint JNICALL
NativeBridge_loadSerializedTransactions(JNIEnv *env, jobject thiz,
                                                                       jbyteArray data) {
    (void)thiz;

    /* A new load replaces a pending one. */
    saved_transactions_discard();

    if (!data) return 0;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 4) return 0;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return 0;

    /* Guarded, JNI-free parse (saved_transactions_deserialize.h): a corrupt count, a failed
     * allocation, or a record length that runs past the blob is never parsed. */
    size_t loaded = deserialize_saved_transactions_guarded((const uint8_t *)bytes, (size_t)len,
                                                           &g_savedTransactions);

    g_savedTransactionCount = loaded;
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    LOGI("loadSerializedTransactions: loaded %zu transactions from persistent storage", loaded);
    return (jint)loaded;
}

/* ---------- saved_transactions_release_after_build ----------
 * Called by restoreWalletFromBytes right after BRWalletOpenWithKeys (BRWalletOpen.h) has built `wallet` from
 * the loaded records, before anything else uses the wallet. It takes the records it keeps -- the wallet owns them
 * from then on, and BRWalletFree releases them -- and skips the rest (an unsigned record, a repeated hash,
 * one outside the money range). From here the bridge names none of them: a record the wallet did not take
 * is released, then the array, and the array is cleared. So a later load or drop frees nothing the wallet
 * owns, and a later build never starts from a record a wallet has released.
 */
void saved_transactions_release_after_build(BRWallet *wallet) {
    if (!wallet) return;   /* nothing was built: the records are still the array's */

    if (g_savedTransactions) {
        for (size_t i = 0; i < g_savedTransactionCount; i++) {
            BRTransaction *tx = g_savedTransactions[i];
            if (tx && BRWalletTransactionForHash(wallet, tx->txHash) != tx) BRTransactionFree(tx);
        }
        free(g_savedTransactions);
    }
    g_savedTransactions = NULL;
    g_savedTransactionCount = 0;
}
