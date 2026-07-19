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
#include "BRDigiDollar.h"
#include "BRNetwork.h"   /* BRNetworkIsTestnet() — runtime network for DD decode */

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

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_publishTransaction(JNIEnv *env, jobject thiz,
                                                              jbyteArray signedTx) {
    (void)thiz;
    PEER_GUARD();

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

    /* Publish — BRPeerManagerPublishTx takes ownership of tx, do NOT free it.
       Pass NULL info/callback: the callback fires asynchronously on the peer
       thread AFTER this JNI frame returns, so a stack-local PublishContext
       would be written cross-thread once it is out of scope — a use-after-free.
       Kotlin already polls acceptance via getRelayCount (Broadcaster embargo +
       SyncService.rebroadcastStrandedSends), so no native callback is needed.
       Matches publishTransactionStem's proven NULL/NULL pattern below. */
    BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL);

    LOGD("publishTransaction: submitted txid=%s", txidHex);
    return (*env)->NewStringUTF(env, txidHex);
}

/* ---------- Dandelion stem / fluff / relay-count ---------- */

/* Parse a 64-char display txid (reversed byte order) into an internal UInt256. */
static UInt256 _u256FromTxidHex(const char *hex) {
    UInt256 h = UINT256_ZERO;
    for (int i = 0; i < 32; i++) {
        unsigned byte = 0;
        if (sscanf(hex + i * 2, "%2x", &byte) != 1) return UINT256_ZERO;
        h.u8[31 - i] = (uint8_t)byte; /* reverse: display -> internal */
    }
    return h;
}

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_publishTransactionStem(JNIEnv *env, jobject thiz,
                                                                 jbyteArray signedTx) {
    (void)thiz;
    PEER_GUARD();
    if (!g_wallet || !g_peerManager || !signedTx) return NULL;
    /* Fast no-op when no capable peer is connected — caller floods via
       publishTransaction. Avoids parsing/registering a tx we won't stem. */
    if (!BRPeerManagerHasDandelionPeer(g_peerManager)) return NULL;

    jsize txLen = (*env)->GetArrayLength(env, signedTx);
    if (txLen <= 0) return NULL;
    jbyte *txBytes = (*env)->GetByteArrayElements(env, signedTx, NULL);
    if (!txBytes) return NULL;
    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, signedTx, txBytes, JNI_ABORT);
    if (!tx) { LOGE("publishTransactionStem: parse failed"); return NULL; }

    UInt256 txHash = tx->txHash;
    char txidHex[65];
    for (int i = 0; i < 32; i++) sprintf(txidHex + i * 2, "%02x", txHash.u8[31 - i]);
    txidHex[64] = '\0';

    /* Register before broadcast (same coherence rule as publishTransaction). */
    if (!tx->timestamp) tx->timestamp = (uint32_t)time(NULL);
    BRWalletRegisterTransaction(g_wallet, tx);

    /* NULL info/callback: a stem callback would be async and the stack-ctx pattern
       in publishTransaction is UAF-prone; Kotlin monitors via getRelayCount. */
    int stemmed = BRPeerManagerStemPublishTx(g_peerManager, tx, NULL, NULL);
    if (!stemmed) {
        /* Rare race: the capable peer dropped after the check above. The tx is
           registered but unbroadcast — flood it so it isn't stranded. */
        BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL);
        LOGW("publishTransactionStem: no stem peer at submit — flooded instead (txid=%s)", txidHex);
    } else {
        LOGD("publishTransactionStem: stemmed txid=%s", txidHex);
    }
    return (*env)->NewStringUTF(env, txidHex);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_fluffTransaction(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    PEER_GUARD();
    if (!g_peerManager || !jtxid) return;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return;
    if (strlen(txid) == 64) BRPeerManagerFluffTx(g_peerManager, _u256FromTxidHex(txid));
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getRelayCount(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    PEER_GUARD();
    if (!g_peerManager || !jtxid) return 0;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return 0;
    jint count = (strlen(txid) == 64)
        ? (jint)BRPeerManagerRelayCount(g_peerManager, _u256FromTxidHex(txid)) : 0;
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
    return count;
}

/* ---------- removeTransaction ----------
 * Drop a transaction (and any dependents) from the wallet, restoring the
 * balance view. Used to clear a phantom unconfirmed send that can never confirm
 * — a double-spend whose inputs were already spent by a confirmed tx. Guarded
 * by a transaction-exists check so an unknown hash is a harmless no-op. */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_removeTransaction(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    if (!g_wallet || !jtxid) return JNI_FALSE;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return JNI_FALSE;
    jboolean removed = JNI_FALSE;
    if (strlen(txid) == 64) {
        UInt256 h = _u256FromTxidHex(txid);
        if (BRWalletTransactionForHash(g_wallet, h)) {
            BRWalletRemoveTransaction(g_wallet, h);
            LOGI("removeTransaction: dropped %s", txid);
            removed = JNI_TRUE;
        }
    }
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
    return removed;
}

/* ---------- isTransactionValid ----------
 * Returns false when the tx's inputs are already spent by another (confirmed)
 * tx — i.e. a double-spend that can never confirm. Returns true for unknown
 * hashes (don't act on a tx the wallet doesn't hold). */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isTransactionValid(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    if (!g_wallet || !jtxid) return JNI_TRUE;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return JNI_TRUE;
    jboolean valid = JNI_TRUE;
    if (strlen(txid) == 64) {
        BRTransaction *tx = BRWalletTransactionForHash(g_wallet, _u256FromTxidHex(txid));
        if (tx) valid = BRWalletTransactionIsValid(g_wallet, tx) ? JNI_TRUE : JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
    return valid;
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
        /* Already registered. If it's stuck UNCONFIRMED and the caller now has
         * its real confirming height, PROMOTE it rather than skip. This is the
         * confirmation-reconcile path: a tx first detected while pending (CF
         * match / mempool relay) can strand at TX_UNCONFIRMED because, in
         * CF-only mode, the confirming block's cfilter is never re-requested
         * once the scan window passes it. BRWalletUpdateTransactions sets the
         * height and re-runs _BRWalletUpdateBalance, which also RELEASES a
         * withheld DigiDollar/asset credit (a 0-value DD token output is
         * otherwise held back by the dust-pending gate). No-op when it's already
         * confirmed or the incoming height is itself unconfirmed. */
        int promoted = JNI_FALSE;
        if (existing->blockHeight == TX_UNCONFIRMED &&
            (uint32_t)blockHeight != TX_UNCONFIRMED && blockHeight > 0) {
            UInt256 h = existing->txHash;
            BRWalletUpdateTransactions(g_wallet, &h, 1,
                                       (uint32_t)blockHeight, (uint32_t)blockTimestamp);
            LOGI("registerRawTransaction: promoted stuck-pending tx to height=%ld ts=%ld",
                 (long)blockHeight, (long)blockTimestamp);
            promoted = JNI_TRUE;
        } else {
            LOGD("registerRawTransaction: duplicate (already confirmed / incoming unconfirmed), skipping");
        }
        BRTransactionFree(tx);
        return promoted;
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

/* ---------- isValidDigiDollarAddress ---------- */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isValidDigiDollarAddress(JNIEnv *env, jobject thiz, jstring addr) {
    (void)thiz;
    if (! addr) return JNI_FALSE;
    const char *s = (*env)->GetStringUTFChars(env, addr, NULL);
    uint8_t key[32];
    /* Runtime network — MUST match the encode side (getDigiDollarReceiveAddress
     * uses BRNetworkIsTestnet()). NOT compile-time #ifdef BITCOIN_TESTNET: the
     * mainnet flavor builds with -DBITCOIN_TESTNET=0, which still DEFINES the
     * macro, so #ifdef was ALWAYS true and forced testnet decode on mainnet —
     * rejecting every valid DD… address (accepted TD… only). */
    int isTestnet = BRNetworkIsTestnet();
    int ok = s && BRDigiDollarAddressDecode(key, s, isTestnet);
    if (s) (*env)->ReleaseStringUTFChars(env, addr, s);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ---------- digiDollarTxType: classify a registered tx for the activity list ---------- */
/* Returns the DigiDollar tx type for the wallet-known tx with this display (BE)
 * txid: 1=MINT, 2=TRANSFER, 3=REDEEM, or 0 if the tx isn't a DigiDollar tx (or
 * isn't known to the wallet). Read-only, display-only — lets the UI label a row
 * DigiDollar vs DGB. Uses the same BE->LE reversal + BRWalletTransactionForHash
 * lookup as outpointSpentState; BRDigiDollarTxType is the sovereign native
 * classifier (DD version-marker + OP_RETURN type push). */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_digiDollarTxType(JNIEnv *env, jobject thiz, jstring txHashHex) {
    (void)thiz;
    if (! g_wallet || ! txHashHex) return 0;
    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (! hashStr) return 0;

    jint type = 0;
    if (strlen(hashStr) == 64) {
        UInt256 hash = UInt256Reverse(uint256(hashStr)); /* display BE -> internal LE */
        BRTransaction *tx = BRWalletTransactionForHash(g_wallet, hash);
        if (tx) type = BRDigiDollarTxType(tx);
    }
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
    return type;
}

/* ---------- sendDigiDollar: decode TD -> build -> sign -> publish (in-memory) ---------- */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_sendDigiDollar(JNIEnv *env, jobject thiz, jstring tdAddress, jlong cents) {
    (void)thiz;
    PEER_GUARD(); /* serialize g_peerManager access (v3.7.1 UAF fix); auto-releases on any return */
    if (! g_wallet || ! g_peerManager) { LOGW("sendDigiDollar: wallet/peerManager not ready"); return NULL; }
    if (! seed_is_valid()) { LOGW("sendDigiDollar: session locked (no seed)"); return NULL; }
    if (! tdAddress || cents <= 0) return NULL;

    const char *td = (*env)->GetStringUTFChars(env, tdAddress, NULL);
    uint8_t key[32];
    /* Runtime network — see isValidDigiDollarAddress note (compile-time #ifdef
     * BITCOIN_TESTNET was always-true on the -DBITCOIN_TESTNET=0 mainnet build,
     * so DD sends could never decode a mainnet DD… recipient). */
    int isTestnet = BRNetworkIsTestnet();
    int decoded = td && BRDigiDollarAddressDecode(key, td, isTestnet);
    if (td) (*env)->ReleaseStringUTFChars(env, tdAddress, td);
    if (! decoded) { LOGW("sendDigiDollar: bad TD address"); return NULL; }

    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(g_wallet, key, (uint64_t)cents);
    if (! tx) { LOGW("sendDigiDollar: builder returned NULL (insufficient DD/DGB or bounds)"); return NULL; }

    int signed_ok = seed_sign_transaction(g_wallet, tx, 0);
    if (! signed_ok || ! BRTransactionIsSigned(tx)) {
        LOGW("sendDigiDollar: signing failed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* txid (reversed display order) before publish — BRPeerManagerPublishTx takes ownership */
    UInt256 txHash = tx->txHash;
    char txidHex[65];
    for (int i = 0; i < 32; i++) sprintf(txidHex + i * 2, "%02x", txHash.u8[31 - i]);
    txidHex[64] = '\0';

    BRWalletRegisterTransaction(g_wallet, tx);
    BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL);   /* takes ownership; do NOT free tx */

    return (*env)->NewStringUTF(env, txidHex);
}
