/*
 * jni_transaction.c
 *
 * JNI bridge for transaction operations: create, sign, publish, fee estimation.
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 *
 * Note: The C core uses RFC 6979 deterministic nonces for ECDSA signing
 * (via secp256k1_nonce_function_rfc6979 in BRKey.c / BRKeySign).
 */

#include "jni_bridge.h"

/* ---------- createTransaction ---------- */

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_createTransaction(JNIEnv *env, jobject thiz,
                                                             jstring toAddress,
                                                             jlong amountSatoshis,
                                                             jlong feePerKb) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("createTransaction: wallet not initialized");
        return NULL;
    }
    if (!toAddress) {
        LOGW("createTransaction: toAddress is null");
        return NULL;
    }
    if (amountSatoshis <= 0) {
        LOGW("createTransaction: invalid amount=%lld", (long long)amountSatoshis);
        return NULL;
    }

    const char *addrChars = (*env)->GetStringUTFChars(env, toAddress, NULL);
    if (!addrChars) return NULL;

    if (!BRAddressIsValid(addrChars)) {
        LOGW("createTransaction: invalid address");
        (*env)->ReleaseStringUTFChars(env, toAddress, addrChars);
        return NULL;
    }

    /* Set fee rate if specified */
    if (feePerKb > 0) {
        BRWalletSetFeePerKb(g_wallet, (uint64_t)feePerKb);
    }

    BRTransaction *tx = BRWalletCreateTransaction(g_wallet, (uint64_t)amountSatoshis, addrChars);
    (*env)->ReleaseStringUTFChars(env, toAddress, addrChars);

    if (!tx) {
        LOGW("createTransaction: BRWalletCreateTransaction returned NULL (insufficient funds?)");
        return NULL;
    }

    /* Serialize the unsigned transaction */
    size_t txLen = BRTransactionSerialize(tx, NULL, 0);
    if (txLen == 0) {
        LOGE("createTransaction: serialization returned 0 length");
        BRTransactionFree(tx);
        return NULL;
    }

    uint8_t buf[txLen];
    txLen = BRTransactionSerialize(tx, buf, sizeof(buf));

    jbyteArray result = (*env)->NewByteArray(env, (jsize)txLen);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)txLen, (jbyte *)buf);
    }

    BRTransactionFree(tx);
    LOGD("createTransaction: created tx, %zu bytes", txLen);
    return result;
}

/* ---------- signTransaction ---------- */

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_signTransaction(JNIEnv *env, jobject thiz,
                                                           jbyteArray unsignedTx) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("signTransaction: wallet not initialized");
        return NULL;
    }
    if (!seed_is_valid()) {
        LOGW("signTransaction: session not unlocked (no seed)");
        return NULL;
    }
    if (!unsignedTx) {
        LOGW("signTransaction: unsignedTx is null");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, unsignedTx);
    if (txLen <= 0) {
        LOGW("signTransaction: empty tx data");
        return NULL;
    }

    jbyte *txBytes = (*env)->GetByteArrayElements(env, unsignedTx, NULL);
    if (!txBytes) return NULL;

    /* Parse the serialized transaction */
    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, unsignedTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGE("signTransaction: failed to parse transaction");
        return NULL;
    }

    /* Sign with wallet (uses RFC 6979 deterministic nonces internally) */
    /* forkId=0 for DigiByte (same as Bitcoin) */
    int signed_ok = seed_sign_transaction(g_wallet, tx, 0);
    if (!signed_ok) {
        LOGW("signTransaction: BRWalletSignTransaction failed");
        BRTransactionFree(tx);
        return NULL;
    }

    if (!BRTransactionIsSigned(tx)) {
        LOGW("signTransaction: transaction not fully signed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* Serialize signed transaction */
    size_t signedLen = BRTransactionSerialize(tx, NULL, 0);
    if (signedLen == 0) {
        LOGE("signTransaction: signed tx serialization returned 0");
        BRTransactionFree(tx);
        return NULL;
    }

    uint8_t signedBuf[signedLen];
    signedLen = BRTransactionSerialize(tx, signedBuf, sizeof(signedBuf));

    jbyteArray result = (*env)->NewByteArray(env, (jsize)signedLen);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)signedLen, (jbyte *)signedBuf);
    }

    BRTransactionFree(tx);
    LOGD("signTransaction: signed tx, %zu bytes", signedLen);
    return result;
}

/* ---------- publishTransaction ---------- */

/* Publish callback context */
typedef struct {
    int  error;
    int  done;
    char txid_hex[65]; /* 32 bytes * 2 hex chars + nul */
} PublishContext;

static void publish_callback(void *info, int error) {
    PublishContext *ctx = (PublishContext *)info;
    ctx->error = error;
    ctx->done = 1;
    if (error) {
        LOGE("publishTransaction: callback error=%d (%s)", error, strerror(error));
    } else {
        LOGD("publishTransaction: broadcast succeeded");
    }
}

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_publishTransaction(JNIEnv *env, jobject thiz,
                                                              jbyteArray signedTx) {
    (void)thiz;

    if (!g_wallet || !g_peerManager) {
        LOGW("publishTransaction: wallet or peer manager not initialized");
        return NULL;
    }
    if (!signedTx) {
        LOGW("publishTransaction: signedTx is null");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, signedTx);
    if (txLen <= 0) return NULL;

    jbyte *txBytes = (*env)->GetByteArrayElements(env, signedTx, NULL);
    if (!txBytes) return NULL;

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, signedTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGE("publishTransaction: failed to parse signed transaction");
        return NULL;
    }

    /* Get txid before publishing (BRPeerManagerPublishTx takes ownership) */
    UInt256 txHash = tx->txHash;
    char txidHex[65];
    for (int i = 0; i < 32; i++) {
        sprintf(txidHex + i * 2, "%02x", txHash.u8[31 - i]); /* reverse byte order for display */
    }
    txidHex[64] = '\0';

    /* Register the tx into the wallet *before* publish so it's in
     * BRWalletTransactions() the moment publishTransaction returns —
     * otherwise the Kotlin-side save-after-broadcast hook can race the
     * peer relay-back path and persist a stale snapshot. The peer's
     * relay-back later calls BRWalletRegisterTransaction again, which
     * is idempotent (BRWallet.c:1111). Timestamp must be set first
     * because _BRWalletInsertTx orders by timestamp. */
    if (!tx->timestamp) tx->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(g_wallet, tx);

    /* Publish — note: BRPeerManagerPublishTx takes ownership of tx, do NOT free it */
    PublishContext ctx = { .error = 0, .done = 0 };
    BRPeerManagerPublishTx(g_peerManager, tx, &ctx, publish_callback);

    /* The callback may be asynchronous; return txid immediately.
       The caller can monitor status via NativeCallback events. */
    LOGD("publishTransaction: submitted txid=%s", txidHex);
    return (*env)->NewStringUTF(env, txidHex);
}

/* ---------- registerRawTransaction ----------
 *
 * Injects a node-verified transaction into the wallet. Used by
 * ChainReconciliationService when a UTXO appears on one of our addresses
 * on-chain but was missed by the SPV bloom scan (stale peers, merkleblock
 * drops, etc.).
 *
 * The caller is responsible for merkle-proof-verifying the tx against a
 * block header the wallet already trusts — this function only validates
 * that the tx is signed, parses correctly, and belongs to the wallet
 * (BRWalletRegisterTransaction does the ownership check).
 *
 * Returns JNI_TRUE if the tx was added, JNI_FALSE otherwise (duplicate,
 * unsigned, parse failure, or tx not associated with any wallet key). */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_registerRawTransaction(JNIEnv *env, jobject thiz,
                                                                  jbyteArray rawTx,
                                                                  jlong blockHeight,
                                                                  jlong blockTimestamp) {
    (void)thiz;
    if (!g_wallet || !rawTx) return JNI_FALSE;

    jsize txLen = (*env)->GetArrayLength(env, rawTx);
    jbyte *txBytes = (*env)->GetByteArrayElements(env, rawTx, NULL);
    if (!txBytes || txLen <= 0) return JNI_FALSE;

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, rawTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGE("registerRawTransaction: failed to parse raw tx");
        return JNI_FALSE;
    }
    if (!BRTransactionIsSigned(tx)) {
        LOGE("registerRawTransaction: tx is not signed");
        BRTransactionFree(tx);
        return JNI_FALSE;
    }

    /* Fast-path duplicate check — avoids the memory leak in
     * BRWalletRegisterTransaction's duplicate branch. */
    BRTransaction *existing = BRWalletTransactionForHash(g_wallet, tx->txHash);
    if (existing) {
        LOGD("registerRawTransaction: duplicate, skipping");
        BRTransactionFree(tx);
        return JNI_FALSE;
    }

    tx->blockHeight = (uint32_t)blockHeight;
    tx->timestamp = (uint32_t)blockTimestamp;

    int ok = BRWalletRegisterTransaction(g_wallet, tx);
    if (!ok) {
        /* Not owned by any wallet address — free to avoid leak.
         * BRWalletRegisterTransaction's non-wallet path leaves tx orphaned
         * unless blockHeight == TX_UNCONFIRMED (in which case the wallet
         * tracks it). For confirmed txs that don't match, we free. */
        if (tx->blockHeight != TX_UNCONFIRMED) BRTransactionFree(tx);
        LOGW("registerRawTransaction: tx not associated with wallet");
        return JNI_FALSE;
    }
    LOGI("registerRawTransaction: registered tx at height=%ld ts=%ld",
         (long)blockHeight, (long)blockTimestamp);
    return JNI_TRUE;
}

/* ---------- getEstimatedFee ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getEstimatedFee(JNIEnv *env, jobject thiz,
                                                           jint priority) {
    (void)env;
    (void)thiz;
    (void)priority;

    /* DigiByte has 15-second blocks and no fee market.
     * All transactions at the minimum relay fee confirm in the next block.
     * Return DEFAULT_FEE_PER_KB (100 sat/byte) for all priority levels. */
    return (jlong)DEFAULT_FEE_PER_KB;
}
