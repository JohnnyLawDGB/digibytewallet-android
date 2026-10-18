/*
 * jni_transaction.c
 *
 * JNI bridge for transaction operations: create, sign, publish, fee estimation.
 *
 * All JNI function names match app.aroundtheblock.wallet.core.bridge.NativeBridge.
 *
 * Note: The C core uses RFC 6979 deterministic nonces for ECDSA signing
 * (via secp256k1_nonce_function_rfc6979 in BRKey.c / BRKeySign).
 */

#include <errno.h>
#include "jni_bridge.h"
#include "BRDigiDollar.h"
#include "BRNetwork.h"   /* BRNetworkIsTestnet() — runtime network for DD decode */
#include "BRPreparedSend.h"   /* the quote and the checks of a prepared DGB send (core; fee_quote_kat, prepared_send_kat) */
#include "digidollar_send.h"   /* the preview and the checks of a DigiDollar send (dd_send_preview_kat) */
#include "digidollar_event.h"  /* what a DigiDollar transaction did, for its activity row (digidollar_realtx_kat) */

/* ---------- publish result callback ----------
 *
 * BRPeerManagerPublishTx answers this callback EXACTLY ONCE per publish and reports every
 * verdict through it and nothing else: 0 when a peer relays or requests the transaction or a
 * block confirms it; EINVAL for an unsigned tx, for one the wallet judges invalid when a peer
 * asks for it, or for a peer's invalid/non-standard/dust rejection while no other peer had it;
 * ENOTCONN when the peer manager is not connected, from the disconnect path that cancels
 * pending publishes, and when the manager is torn down while the publish is pending;
 * ETIMEDOUT when no peer echoed it back; ECANCELED when the wallet itself removed the
 * transaction (removeTransaction) while it was pending; and EALREADY when this publish
 * duplicated one still pending -- the earlier publish's callback carries the verdict, so
 * EALREADY is consumed here as "no verdict" and never recorded.
 *
 * Passing NULL here — as this bridge did until 2026-08-20 — is not merely "don't tell me".
 * The cancellation loop in _peerDisconnected skips entries whose callback is NULL:
 *
 *     if (manager->publishedTx[i - 1].callback == NULL) continue;
 *
 * so a NULL-callback publish is never cancelled, never freed, and never removed from
 * publishedTx. It sits in the publish list for the life of the peer manager while the app
 * reports the send as successful. Combined with the wallet registration that happens just
 * before publishing, a send the network never accepted still marks its inputs spent — which
 * is how a wallet ends up spending outputs that exist nowhere else, and how the child of such
 * a send becomes an unconfirmable orphan (see OrphanSendPredicate).
 *
 * The context is heap-allocated and freed here: the callback fires asynchronously on a peer
 * thread long after the JNI frame returns, so a stack-local would be a use-after-free.
 */
typedef struct { char txid[65]; } PublishCtx;

/* ---------- publish result registry ----------
 *
 * The callback fires on a PEER thread, asynchronously, long after the Kotlin caller has
 * returned. Kotlin therefore cannot be handed the result directly; it has to be able to ASK
 * later. This is that record — the smallest thing that lets the UI stop saying "Pending" for
 * a transaction the network refused.
 *
 * A fixed ring, not a growing map: this is written from peer threads on every publish for the
 * life of the process, and an unbounded structure here would be a slow leak in the one place
 * that must never fail. Oldest entries are overwritten; a result nobody read within
 * PUBLISH_RESULT_MAX publishes was not being acted on anyway.
 *
 * `pending` (-1) distinguishes "asked before the answer arrived" from "delivered, error 0".
 * Without that the UI would read an in-flight publish as accepted — the exact false-success
 * this whole change exists to remove.
 */
#define PUBLISH_RESULT_MAX 32

typedef struct { char txid[65]; int error; } PublishResult;

static PublishResult g_publishResults[PUBLISH_RESULT_MAX];
static size_t        g_publishResultNext = 0;
static pthread_mutex_t g_publishResultLock = PTHREAD_MUTEX_INITIALIZER;

#define PUBLISH_RESULT_PENDING (-1)

static void _publishResultRecord(const char *txid, int error)
{
    pthread_mutex_lock(&g_publishResultLock);
    /* Overwrite an existing entry for the same txid rather than append: a republish must not
     * leave its older, staler verdict behind for a reader to find first. */
    for (size_t i = 0; i < PUBLISH_RESULT_MAX; i++) {
        if (strncmp(g_publishResults[i].txid, txid, 64) == 0) {
            g_publishResults[i].error = error;
            pthread_mutex_unlock(&g_publishResultLock);
            return;
        }
    }
    PublishResult *slot = &g_publishResults[g_publishResultNext];
    g_publishResultNext = (g_publishResultNext + 1) % PUBLISH_RESULT_MAX;
    memcpy(slot->txid, txid, 64);
    slot->txid[64] = '\0';
    slot->error = error;
    pthread_mutex_unlock(&g_publishResultLock);
}

static void _publishResult(void *info, int error)
{
    PublishCtx *ctx = (PublishCtx *)info;
    if (!ctx) return;
    if (error == EALREADY) {
        /* This publish duplicated one still pending. The ring stays keyed by txid with the first
         * publish's slot, whose callback carries the verdict; recording here would overwrite a
         * pending or delivered verdict with a non-verdict. Consume the context and nothing else. */
        LOGD("publishTransaction: already pending txid=%s (earlier publish carries the verdict)", ctx->txid);
        free(ctx);
        return;
    }
    _publishResultRecord(ctx->txid, error);
    if (error) {
        LOGE("publishTransaction: REJECTED txid=%s error=%d (%s) — the network did not "
             "accept this transaction; the wallet still holds it locally",
             ctx->txid, error, strerror(error));
    } else {
        LOGI("publishTransaction: accepted txid=%s (relayed back by a peer)", ctx->txid);
    }
    free(ctx);
}

/* Returns the recorded publish result for a txid, or PUBLISH_RESULT_PENDING (-1) when no
 * verdict has arrived (or the entry aged out of the ring). Kotlin maps this through
 * core/sync/PublishOutcome. */
jint JNICALL
NativeBridge_getPublishResult(JNIEnv *env, jobject thiz,
                                                            jstring txidHex)
{
    (void)thiz;
    if (!txidHex) return PUBLISH_RESULT_PENDING;
    const char *txid = (*env)->GetStringUTFChars(env, txidHex, NULL);
    if (!txid) return PUBLISH_RESULT_PENDING;

    int result = PUBLISH_RESULT_PENDING;
    pthread_mutex_lock(&g_publishResultLock);
    for (size_t i = 0; i < PUBLISH_RESULT_MAX; i++) {
        if (strncmp(g_publishResults[i].txid, txid, 64) == 0) { result = g_publishResults[i].error; break; }
    }
    pthread_mutex_unlock(&g_publishResultLock);

    (*env)->ReleaseStringUTFChars(env, txidHex, txid);
    return result;
}

/* ---------- the wallet's record of a send ----------
 *
 * One owner per object. The wallet keeps its own record of a send and the peer manager is
 * handed the ORIGINAL to broadcast (BRPeerManagerPublishTx / StemPublishTx take ownership and
 * may release it at any time), so the wallet's record must never be the same object -- it is
 * an independent COPY. And the copy is made only when the wallet holds no record of the hash
 * yet: on a re-publish of a send it already holds (the 90-second stranded-send sweep, a manual
 * retry) the wallet keeps the record it has and no copy is made. If the wallet did not take
 * the copy after all -- a peer thread registered the hash between the lookup and the
 * registration (peer threads do not hold PEER_GUARD), or the record is a confirmed
 * non-wallet transaction the wallet keeps no record of -- the copy has no owner and is
 * released here. The timestamp is set first because _BRWalletInsertTx orders by it.
 *
 * Every publish entry point calls this, and only this, before it publishes; the host KAT
 * tx_publish_ownership_kat reads this file and requires exactly that shape.
 * Caller holds PEER_GUARD. */
static void _registerWalletCopy(BRTransaction *tx)
{
    if (!tx->timestamp) tx->timestamp = (uint32_t)time(NULL);
    if (BRWalletTransactionForHash(g_wallet, tx->txHash)) return;   /* already held: nothing to copy */
    BRTransaction *walletCopy = BRTransactionCopy(tx);
    if (!walletCopy) return;                                        /* the peer relay-back re-registers */
    BRWalletRegisterTransactionTrusted(g_wallet, walletCopy);   /* this wallet signed it: no input check */
    if (BRWalletTransactionForHash(g_wallet, tx->txHash) != walletCopy) BRTransactionFree(walletCopy);
}

/* ---------- createTransaction ---------- */

jbyteArray JNICALL
NativeBridge_createTransaction(JNIEnv *env, jobject thiz,
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

    /* Build at the caller's rate, if specified, for this one build only. The rate is handed to the
     * builder rather than set on the wallet: the wallet's rate is shared state that later builds
     * read (asset sends, sweeps, a later send at the default rate) and that peers' feefilter
     * messages and concurrent builds update, so a custom rate never touches it. */
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(g_wallet, (uint64_t)amountSatoshis, addrChars,
                                                            feePerKb > 0 ? (uint64_t)feePerKb : 0);
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

jbyteArray JNICALL
NativeBridge_signTransaction(JNIEnv *env, jobject thiz,
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

/* ---------- a prepared DGB send: quoteTransaction, signPreparedTransaction ----------
 *
 * The fee a DGB send's confirmation shows is the fee of the transaction that is signed. Review builds
 * the send (createTransaction) and quotes the bytes here; after the credential prompt the SAME bytes
 * come back to be signed. Kotlin holds them as bytes plus the quote, never a native object, so
 * nothing native outlives the prompt -- and the wallet may have changed while it was up, which is
 * why signing checks them first. Both decisions live in the core's BRPreparedSend.h, shared with iOS,
 * which fee_quote_kat and prepared_send_kat run on the host; this file only parses, calls and
 * serializes. */

/* [fee, vsize] of unsigned bytes the wallet built: the fee is the transaction's inputs minus its
 * outputs, the vsize the size it was built at. NULL when the bytes do not parse or the fee is
 * unknown (an input is not a transaction the wallet holds). Reads the wallet; changes nothing. */
jlongArray JNICALL
NativeBridge_quoteTransaction(JNIEnv *env, jobject thiz, jbyteArray unsignedTx)
{
    (void)thiz;
    if (!g_wallet || !unsignedTx) return NULL;

    jsize txLen = (*env)->GetArrayLength(env, unsignedTx);
    if (txLen <= 0) return NULL;
    jbyte *txBytes = (*env)->GetByteArrayElements(env, unsignedTx, NULL);
    if (!txBytes) return NULL;
    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, unsignedTx, txBytes, JNI_ABORT);
    if (!tx) {
        LOGE("quoteTransaction: failed to parse transaction");
        return NULL;
    }

    uint64_t fee = 0, vsize = 0;
    int quoted = BRPreparedSendQuote(g_wallet, tx, &fee, &vsize);
    BRTransactionFree(tx);
    if (!quoted) {
        LOGW("quoteTransaction: the fee is unknown -- an input is not a transaction the wallet holds");
        return NULL;
    }

    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result) {
        jlong quote[2] = { (jlong)fee, (jlong)vsize };
        (*env)->SetLongArrayRegion(env, result, 0, 2, quote);
    }
    return result;
}

/* Sign prepared unsigned bytes, or NULL: only the send that was approved -- amountSats to toAddress
 * at approvedFeeSats. Before signing, the bytes' outputs must be exactly that payment plus at most one
 * change output to an address of this wallet, they must pay exactly approvedFeeSats, and they must
 * spend only coins that are still the wallet's spendable plain coins; after signing, the signed
 * transaction must pass the same check, or nothing is handed back to be broadcast. It signs those
 * bytes and nothing else: no build, no publish (the publish entry points stay publishTransaction /
 * publishTransactionStem, which tx_publish_ownership_kat watches). */
jbyteArray JNICALL
NativeBridge_signPreparedTransaction(JNIEnv *env, jobject thiz, jbyteArray unsignedTx, jstring toAddress,
                                     jlong amountSats, jlong approvedFeeSats)
{
    (void)thiz;
    if (!g_wallet) {
        LOGW("signPreparedTransaction: wallet not initialized");
        return NULL;
    }
    if (!seed_is_valid()) {
        LOGW("signPreparedTransaction: session not unlocked (no seed)");
        return NULL;
    }
    if (!unsignedTx || !toAddress || amountSats <= 0 || approvedFeeSats <= 0) {
        LOGW("signPreparedTransaction: no prepared bytes, or no approved payment or fee");
        return NULL;
    }

    /* The approved payment's script, from the approved destination. */
    uint8_t payScript[128];
    size_t payScriptLen = 0;
    const char *addr = (*env)->GetStringUTFChars(env, toAddress, NULL);
    if (!addr) return NULL;
    if (BRAddressIsValid(addr)) {
        size_t need = BRAddressScriptPubKey(NULL, 0, addr);
        if (need > 0 && need <= sizeof(payScript)) payScriptLen = BRAddressScriptPubKey(payScript, sizeof(payScript), addr);
    }
    (*env)->ReleaseStringUTFChars(env, toAddress, addr);
    if (payScriptLen == 0) {
        LOGW("signPreparedTransaction: the approved destination is not an address");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, unsignedTx);
    if (txLen <= 0) return NULL;
    jbyte *txBytes = (*env)->GetByteArrayElements(env, unsignedTx, NULL);
    if (!txBytes) return NULL;
    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, unsignedTx, txBytes, JNI_ABORT);
    if (!tx) {
        LOGE("signPreparedTransaction: failed to parse the prepared transaction");
        return NULL;
    }

    /* Before signing: exactly the approved payment and fee, and only coins still the wallet's plain ones. */
    BRPreparedSendVerdict verdict;
    if ((verdict = BRPreparedSendCheck(g_wallet, tx, payScript, payScriptLen, (uint64_t)amountSats,
                                       (uint64_t)approvedFeeSats)) != BRPreparedSendOK) {
        LOGW("signPreparedTransaction: not signed -- the prepared send no longer matches its approval (verdict %d)",
             (int)verdict);
        BRTransactionFree(tx);
        return NULL;
    }

    /* Sign with wallet (RFC 6979 deterministic nonces); forkId=0 for DigiByte. */
    if (!seed_sign_transaction(g_wallet, tx, 0) || !BRTransactionIsSigned(tx)) {
        LOGW("signPreparedTransaction: signing failed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* After signing: the transaction that would be broadcast is still exactly the approved send. */
    if ((verdict = BRPreparedSendCheck(g_wallet, tx, payScript, payScriptLen, (uint64_t)amountSats,
                                       (uint64_t)approvedFeeSats)) != BRPreparedSendOK) {
        LOGE("signPreparedTransaction: the signed transaction is not the approved send (verdict %d); not returned",
             (int)verdict);
        BRTransactionFree(tx);
        return NULL;
    }

    size_t signedLen = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *signedBuf = signedLen ? malloc(signedLen) : NULL;
    if (!signedBuf) {
        LOGE("signPreparedTransaction: could not serialize the signed transaction");
        BRTransactionFree(tx);
        return NULL;
    }
    signedLen = BRTransactionSerialize(tx, signedBuf, signedLen);
    BRTransactionFree(tx);

    jbyteArray result = signedLen ? (*env)->NewByteArray(env, (jsize)signedLen) : NULL;
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)signedLen, (jbyte *)signedBuf);
    free(signedBuf);
    LOGD("signPreparedTransaction: signed tx, %zu bytes, fee %lld", signedLen, (long long)approvedFeeSats);
    return result;
}

/* ---------- publishTransaction ---------- */

jstring JNICALL
NativeBridge_publishTransaction(JNIEnv *env, jobject thiz,
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

    /* The wallet's record goes in *before* the publish so it is in BRWalletTransactions() the
     * moment publishTransaction returns — otherwise the Kotlin-side save-after-broadcast hook
     * can race the peer relay-back path and persist a stale snapshot. The wallet keeps an
     * independent copy, made only when it holds no record of the hash yet; the peer manager is
     * handed the ORIGINAL below and owns it from then on (see _registerWalletCopy). */
    _registerWalletCopy(tx);

    /* Publish — BRPeerManagerPublishTx takes ownership of tx, do NOT free it. The context is
       heap-allocated: the callback fires asynchronously on a peer thread AFTER this JNI frame
       returns. Non-NULL callback is load-bearing — see _publishResult. On allocation failure
       fall back to the old NULL behaviour rather than dropping the send entirely: a publish
       that cannot be tracked is still better than no publish at all. */
    PublishCtx *ctx = calloc(1, sizeof(*ctx));
    if (ctx) memcpy(ctx->txid, txidHex, sizeof(ctx->txid));
    BRPeerManagerPublishTx(g_peerManager, tx, ctx, ctx ? _publishResult : NULL);

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

jstring JNICALL
NativeBridge_publishTransactionStem(JNIEnv *env, jobject thiz,
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

    /* The wallet's record before the broadcast (same coherence rule as publishTransaction);
     * the peer manager gets the ORIGINAL — see _registerWalletCopy. */
    _registerWalletCopy(tx);

    /* Heap ctx, not stack — the callback is async and fires long after this frame returns.
       A NULL callback would also make the publish invisible to the cancellation path that
       skips NULL-callback entries, leaving it stuck in publishedTx forever; see
       _publishResult. */
    PublishCtx *stemCtx = calloc(1, sizeof(*stemCtx));
    if (stemCtx) memcpy(stemCtx->txid, txidHex, sizeof(stemCtx->txid));
    int stemmed = BRPeerManagerStemPublishTx(g_peerManager, tx, stemCtx,
                                             stemCtx ? _publishResult : NULL);
    if (!stemmed) {
        /* Rare race: the capable peer dropped after the check above. The tx is
           registered but unbroadcast — flood it so it isn't stranded. */
        if (stemCtx) { free(stemCtx); stemCtx = NULL; }  /* stem never took ownership */
        PublishCtx *floodCtx = calloc(1, sizeof(*floodCtx));
        if (floodCtx) memcpy(floodCtx->txid, txidHex, sizeof(floodCtx->txid));
        BRPeerManagerPublishTx(g_peerManager, tx, floodCtx, floodCtx ? _publishResult : NULL);
        LOGW("publishTransactionStem: no stem peer at submit — flooded instead (txid=%s)", txidHex);
    } else {
        LOGD("publishTransactionStem: stemmed txid=%s", txidHex);
    }
    return (*env)->NewStringUTF(env, txidHex);
}

void JNICALL
NativeBridge_fluffTransaction(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    PEER_GUARD();
    if (!g_peerManager || !jtxid) return;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return;
    if (strlen(txid) == 64) BRPeerManagerFluffTx(g_peerManager, _u256FromTxidHex(txid));
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
}

jint JNICALL
NativeBridge_getRelayCount(JNIEnv *env, jobject thiz, jstring jtxid) {
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

/* ---------- isRefusedByNetwork ----------
 * True when the network has refused the transaction on evidence no single peer can supply: peers in
 * at least two network groups rejected it as invalid, non-standard or dust, and no peer has relayed or
 * announced it (BRPeerManagerTxRefusedByNetwork). One peer's rejection is never enough: the
 * stranded-send sweep removes a send, and so gives its coins back, only on this answer. */
jboolean JNICALL
NativeBridge_isRefusedByNetwork(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    PEER_GUARD();
    if (!g_peerManager || !jtxid) return JNI_FALSE;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return JNI_FALSE;
    jboolean refused = (strlen(txid) == 64 &&
                        BRPeerManagerTxRefusedByNetwork(g_peerManager, _u256FromTxidHex(txid))) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
    return refused;
}

/* ---------- removeTransaction ----------
 * Drop a transaction (and any dependents) from the wallet, restoring the
 * balance view. Used to clear a phantom unconfirmed send that can never confirm
 * — a double-spend whose inputs were already spent by a confirmed tx. Guarded
 * by a transaction-exists check so an unknown hash is a harmless no-op.
 *
 * Routed through the one manager-locked removal so the publish list and the
 * wallet agree about every object: a record the wallet releases here leaves no
 * list entry naming it. PEER_GUARD serialises g_peerManager the same way the
 * other broadcast-path entry points do.
 *
 * That route removes from the wallet the manager was built with, so it is taken
 * only while that is this g_wallet: createWalletFromBytes / recoverWalletFromBytes
 * set g_walletSwapped BEFORE they begin swapping the wallet, and until the next
 * startSync rebuilds the manager the two names stand for different wallets — so
 * the marker is already set for the whole of any window in which they differ,
 * including while the new wallet is being built. A plain reconnect request
 * (g_peerManagerNeedsRecreate from forceReconnect, or a rescan deferring the
 * recreate) leaves the pair intact, so it does not switch this route off.
 * With the marker set — and when there is no manager at all, so no publish list
 * — the wallet removal stands alone on g_wallet, exactly as it did before.
 *
 * The return value says whether g_wallet has really let go of the record: the
 * caller deletes its own rows for this hash on a true return, and a row deleted
 * while the wallet still holds the record could never be cleared again. */
jboolean JNICALL
NativeBridge_removeTransaction(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    PEER_GUARD();
    if (!g_wallet || !jtxid) return JNI_FALSE;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return JNI_FALSE;
    jboolean removed = JNI_FALSE;
    if (strlen(txid) == 64) {
        UInt256 h = _u256FromTxidHex(txid);
        if (BRWalletTransactionForHash(g_wallet, h)) {
            if (g_peerManager && !g_walletSwapped) {
                BRPeerManagerRemoveTransaction(g_peerManager, h);
            } else {
                BRWalletRemoveTransaction(g_wallet, h);
            }
            /* Report on what g_wallet holds now, not on which path ran. */
            removed = BRWalletTransactionForHash(g_wallet, h) ? JNI_FALSE : JNI_TRUE;
            if (removed) LOGI("removeTransaction: dropped %s", txid);
            else LOGW("removeTransaction: %s is still held by the wallet — reporting not dropped", txid);
        }
    }
    (*env)->ReleaseStringUTFChars(env, jtxid, txid);
    return removed;
}

/* ---------- isTransactionValid ----------
 * Returns false when the tx's inputs are already spent by another (confirmed)
 * tx — i.e. a double-spend that can never confirm. Returns true for unknown
 * hashes (don't act on a tx the wallet doesn't hold). */
jboolean JNICALL
NativeBridge_isTransactionValid(JNIEnv *env, jobject thiz, jstring jtxid) {
    (void)thiz;
    if (!g_wallet || !jtxid) return JNI_TRUE;
    const char *txid = (*env)->GetStringUTFChars(env, jtxid, NULL);
    if (!txid) return JNI_TRUE;
    jboolean valid = JNI_TRUE;
    if (strlen(txid) == 64) {
        /* a copy taken under the wallet lock: no wallet pointer is kept past it (tx_read_snapshot_kat) */
        BRTransaction *tx = BRWalletCopyTransactionForHash(g_wallet, _u256FromTxidHex(txid));
        if (tx) valid = BRWalletTransactionIsValid(g_wallet, tx) ? JNI_TRUE : JNI_FALSE;
        if (tx) BRTransactionFree(tx);
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

jboolean JNICALL
NativeBridge_registerRawTransaction(JNIEnv *env, jobject thiz,
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
    /* The wallet's record is read from a copy taken under its lock (tx_read_snapshot_kat). */
    BRTransaction *existing = BRWalletCopyTransactionForHash(g_wallet, tx->txHash);
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
            /* A server's height, not one this wallet proved: the record stays signature-checked. */
            BRWalletUpdateTransactionsUnproven(g_wallet, &h, 1,
                                               (uint32_t)blockHeight, (uint32_t)blockTimestamp);
            LOGI("registerRawTransaction: promoted stuck-pending tx to height=%ld ts=%ld",
                 (long)blockHeight, (long)blockTimestamp);
            promoted = JNI_TRUE;
        } else {
            LOGD("registerRawTransaction: duplicate (already confirmed / incoming unconfirmed), skipping");
        }
        BRTransactionFree(existing);
        BRTransactionFree(tx);
        return promoted;
    }

    tx->blockHeight = (uint32_t)blockHeight;
    tx->timestamp = (uint32_t)blockTimestamp;

    UInt256 txHash = tx->txHash;
    /* The height is the server's, not proven by this wallet: the tx's signatures on wallet coins are
     * checked at any height (BRWalletRegisterTransactionUnproven), now and at every balance rebuild. */
    int ok = BRWalletRegisterTransactionUnproven(g_wallet, tx);
    if (!ok) {
        /* Not taken by the wallet. It keeps an unconfirmed non-wallet tx in its allTx set; anything
         * else it refused -- a confirmed non-wallet tx, an out-of-range tx, an unconfirmed tx that
         * does not validly sign a wallet output -- is not kept at any height, so ask the wallet
         * whether it holds this object rather than infer it from the height, and free it if not. */
        if (BRWalletTransactionForHash(g_wallet, txHash) != tx) BRTransactionFree(tx);
        LOGW("registerRawTransaction: tx not associated with wallet");
        return JNI_FALSE;
    }
    LOGI("registerRawTransaction: registered tx at height=%ld ts=%ld",
         (long)blockHeight, (long)blockTimestamp);
    return JNI_TRUE;
}

/* ---------- confirmTransaction ---------- */
/* Promote an ALREADY-REGISTERED wallet tx from TX_UNCONFIRMED to a real
 * confirming height, driven by txid alone (no raw hex needed). The txid-based
 * confirmation-reconcile uses this: it enumerates the wallet's own pending txs
 * and asks the node for each one's height, so it recovers a stuck-pending tx
 * even when the node's UTXO enumeration (scantxoutset) never surfaces it — e.g.
 * a 0-value DigiDollar token output that a dust filter omits, which the
 * address/UTXO-based reconcile can't reach.
 * BRWalletUpdateTransactions sets the height and re-runs _BRWalletUpdateBalance,
 * which releases the withheld DigiDollar/asset credit. Returns true only if a
 * known, still-unconfirmed tx was promoted with a confirmed height. */
jboolean JNICALL
NativeBridge_confirmTransaction(JNIEnv *env, jobject thiz,
                                                             jstring txHashHex,
                                                             jlong blockHeight,
                                                             jlong blockTimestamp) {
    (void)thiz;
    if (!g_wallet || !txHashHex) return JNI_FALSE;
    if ((uint32_t)blockHeight == TX_UNCONFIRMED || blockHeight <= 0) return JNI_FALSE;

    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (!hashStr) return JNI_FALSE;

    jboolean promoted = JNI_FALSE;
    if (strlen(hashStr) == 64) {
        UInt256 h = UInt256Reverse(uint256(hashStr)); /* display BE -> internal LE */
        /* the height is read from a copy taken under the wallet lock (tx_read_snapshot_kat) */
        BRTransaction *existing = BRWalletCopyTransactionForHash(g_wallet, h);
        int pending = existing && existing->blockHeight == TX_UNCONFIRMED;
        if (existing) BRTransactionFree(existing);
        if (pending) {
            /* A server's height, not one this wallet proved: the record stays signature-checked. */
            BRWalletUpdateTransactionsUnproven(g_wallet, &h, 1,
                                               (uint32_t)blockHeight, (uint32_t)blockTimestamp);
            LOGI("confirmTransaction: promoted %s to height=%ld ts=%ld",
                 hashStr, (long)blockHeight, (long)blockTimestamp);
            promoted = JNI_TRUE;
        }
    }
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
    return promoted;
}

/* ---------- getEstimatedFee ---------- */

jlong JNICALL
NativeBridge_getEstimatedFee(JNIEnv *env, jobject thiz,
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
jboolean JNICALL
NativeBridge_isValidDigiDollarAddress(JNIEnv *env, jobject thiz, jstring addr) {
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
jint JNICALL
NativeBridge_digiDollarTxType(JNIEnv *env, jobject thiz, jstring txHashHex) {
    (void)thiz;
    if (! g_wallet || ! txHashHex) return 0;
    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (! hashStr) return 0;

    jint type = 0;
    if (strlen(hashStr) == 64) {
        UInt256 hash = UInt256Reverse(uint256(hashStr)); /* display BE -> internal LE */
        /* a copy taken under the wallet lock (tx_read_snapshot_kat) */
        BRTransaction *tx = BRWalletCopyTransactionForHash(g_wallet, hash);
        if (tx) type = BRDigiDollarTxType(tx);
        if (tx) BRTransactionFree(tx);
    }
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
    return type;
}

/* ---------- digiDollarEvent: what a DigiDollar transaction did, for its activity row ----------
 *
 * The row says what the transaction IS -- minted, sent, received, moved to the wallet itself, redeemed
 * -- computed in digidollar_event.h (digidollar_realtx_kat runs it on the host) from the wallet's own
 * view of the transaction: whose outputs it pays, and whose outputs its inputs spend and how many
 * cents of DigiDollar those held. The DGB the transaction moves is not an input: a mint sends DGB
 * away as collateral while dollars arrive, and a redemption brings DGB back while dollars are burned.
 * Display-only; reads the wallet, changes nothing. */
static int _ddEventPaysWallet(void *ctx, const BRTxOutput *output)
{
    return output->address[0] != '\0' && BRWalletContainsAddress((BRWallet *)ctx, output->address);
}

static int _ddEventSpendsWallet(void *ctx, const BRTxInput *input, int64_t *ddCents)
{
    BRWallet *wallet = (BRWallet *)ctx;
    /* a copy taken under the wallet lock (tx_read_snapshot_kat); freed on every path */
    BRTransaction *spent = BRWalletCopyTransactionForHash(wallet, input->txHash);
    int pays = 0;
    *ddCents = -1;
    if (spent && input->index < spent->outCount) {
        const BRTxOutput *output = &spent->outputs[input->index];
        if (output->address[0] != '\0' && BRWalletContainsAddress(wallet, output->address)) {
            *ddCents = BRDigiDollarOutputAmount(spent, input->index);   /* -1: a DGB coin, not DigiDollar */
            pays = 1;
        }
    }
    if (spent) BRTransactionFree(spent);
    return pays;
}

/* `[kind, cents, changeCents]` for the wallet-known tx with this display (BE) txid -- kind 0 (none),
 * 1 minted, 2 sent, 3 received, 4 to the wallet itself, 5 redeemed -- or NULL when the wallet does
 * not hold the transaction. */
jlongArray JNICALL
NativeBridge_digiDollarEvent(JNIEnv *env, jobject thiz, jstring txHashHex) {
    (void)thiz;
    if (! g_wallet || ! txHashHex) return NULL;
    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (! hashStr) return NULL;

    int known = 0;
    DdEvent ev = { DD_EVENT_NONE, 0, 0 };
    if (strlen(hashStr) == 64) {
        UInt256 hash = UInt256Reverse(uint256(hashStr)); /* display BE -> internal LE */
        /* a copy taken under the wallet lock (tx_read_snapshot_kat) */
        BRTransaction *tx = BRWalletCopyTransactionForHash(g_wallet, hash);
        if (tx) {
            DdEventWallet wallet = { g_wallet, _ddEventPaysWallet, _ddEventSpendsWallet, NULL };
            ev = dd_event_of(tx, &wallet);
            known = 1;
            BRTransactionFree(tx);
        }
    }
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
    if (! known) return NULL;

    jlongArray result = (*env)->NewLongArray(env, 3);
    if (result) {
        jlong out[3] = { (jlong)ev.kind, (jlong)ev.cents, (jlong)ev.changeCents };
        (*env)->SetLongArrayRegion(env, result, 0, 3, out);
    }
    return result;
}

/* ---------- a DigiDollar send: previewDigiDollar, sendApprovedDigiDollar ----------
 *
 * The fee a DigiDollar send's confirmation shows is the fee of the transfer that is signed. A transfer
 * cannot be carried across the credential prompt as unsigned bytes (the serializer leaves out a zero
 * input amount and DigiDollar inputs are zero-value, so the bytes do not parse back), so review builds
 * it, reads what it pays and frees it (previewDigiDollar), and after the prompt it is built again and
 * signed only when it is the approved send at the approved fee (sendApprovedDigiDollar). No native
 * object outlives the prompt. Both decisions live in digidollar_send.h, which dd_send_preview_kat runs
 * against the live core on the host; this file only decodes, builds, calls and publishes. */

/* The taproot output key a DigiDollar address (TD... testnet / DD... mainnet) pays: 1, or 0 when it is
 * not one. Runtime network -- see isValidDigiDollarAddress (a compile-time #ifdef BITCOIN_TESTNET was
 * always true on the -DBITCOIN_TESTNET=0 mainnet build, so no mainnet DD... recipient ever decoded). */
static int _ddRecipientKey(JNIEnv *env, jstring tdAddress, uint8_t key[32])
{
    const char *td = (*env)->GetStringUTFChars(env, tdAddress, NULL);
    int decoded = td && BRDigiDollarAddressDecode(key, td, BRNetworkIsTestnet());
    if (td) (*env)->ReleaseStringUTFChars(env, tdAddress, td);
    return decoded;
}

/* `[fee, ddChangeCents, dgbChangeSats, inputCount]` of the transfer of `cents` to tdAddress the wallet
 * would build now: the fee is the transfer's inputs minus its outputs. The transfer is freed before
 * this returns; nothing is signed, kept or published. NULL when it cannot be built (not enough
 * DigiDollar, not enough DGB for the fee, an amount outside the per-output bounds, not an address). */
jlongArray JNICALL
NativeBridge_previewDigiDollar(JNIEnv *env, jobject thiz, jstring tdAddress, jlong cents)
{
    (void)thiz;
    if (! g_wallet) { LOGW("previewDigiDollar: wallet not initialized"); return NULL; }
    if (! tdAddress || cents <= 0) return NULL;

    uint8_t key[32];
    if (! _ddRecipientKey(env, tdAddress, key)) { LOGW("previewDigiDollar: not a DigiDollar address"); return NULL; }

    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(g_wallet, key, (uint64_t)cents);
    if (! tx) { LOGW("previewDigiDollar: the transfer cannot be built (DigiDollar, DGB for the fee, or bounds)"); return NULL; }
    DdSendPreview preview;
    int ok = dd_send_preview(g_wallet, tx, &preview);
    BRTransactionFree(tx);   /* nothing native outlives the prompt */
    if (! ok) { LOGW("previewDigiDollar: the transfer's fee cannot be told"); return NULL; }

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) {
        jlong out[4] = { (jlong)preview.feeSats, (jlong)preview.ddChangeCents,
                         (jlong)preview.dgbChangeSats, (jlong)preview.inputCount };
        (*env)->SetLongArrayRegion(env, result, 0, 4, out);
    }
    return result;
}

/* Build the transfer of `cents` to tdAddress again, and sign and publish it only when it is the approved
 * send: output 0 pays exactly those cents to that address, everything else is the OP_RETURN or change to
 * this wallet, and it pays exactly approvedFeeSats -- checked before signing and again after. The wallet
 * keeps its own copy (_registerWalletCopy) and the peer manager is handed the original. Returns the
 * txid, or NULL when nothing was signed (the wallet changed while the prompt was up, the session is
 * locked, or the transfer cannot be built). */
jstring JNICALL
NativeBridge_sendApprovedDigiDollar(JNIEnv *env, jobject thiz, jstring tdAddress, jlong cents, jlong approvedFeeSats)
{
    (void)thiz;
    PEER_GUARD(); /* serialize g_peerManager access (v3.7.1 UAF fix); auto-releases on any return */
    if (! g_wallet || ! g_peerManager) { LOGW("sendApprovedDigiDollar: wallet/peerManager not ready"); return NULL; }
    if (! seed_is_valid()) { LOGW("sendApprovedDigiDollar: session locked (no seed)"); return NULL; }
    if (! tdAddress || cents <= 0 || approvedFeeSats <= 0) {
        LOGW("sendApprovedDigiDollar: no approved amount or fee");
        return NULL;
    }

    uint8_t key[32];
    if (! _ddRecipientKey(env, tdAddress, key)) { LOGW("sendApprovedDigiDollar: not a DigiDollar address"); return NULL; }

    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(g_wallet, key, (uint64_t)cents);
    if (! tx) { LOGW("sendApprovedDigiDollar: the transfer cannot be built"); return NULL; }

    /* Before signing: the approved payment, at exactly the approved fee. */
    DdSendVerdict verdict;
    if ((verdict = dd_send_check(g_wallet, tx, key, (uint64_t)cents, (uint64_t)approvedFeeSats)) != DD_SEND_OK) {
        LOGW("sendApprovedDigiDollar: not signed -- the transfer is no longer the approved send (verdict %d)", (int)verdict);
        BRTransactionFree(tx);
        return NULL;
    }

    if (! seed_sign_transaction(g_wallet, tx, 0) || ! BRTransactionIsSigned(tx)) {
        LOGW("sendApprovedDigiDollar: signing failed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* After signing: what would be published is still exactly the approved send. */
    if ((verdict = dd_send_check(g_wallet, tx, key, (uint64_t)cents, (uint64_t)approvedFeeSats)) != DD_SEND_OK) {
        LOGE("sendApprovedDigiDollar: the signed transfer is not the approved send (verdict %d); not published", (int)verdict);
        BRTransactionFree(tx);
        return NULL;
    }

    /* txid (reversed display order) before publish -- BRPeerManagerPublishTx takes ownership */
    UInt256 txHash = tx->txHash;
    char txidHex[65];
    for (int i = 0; i < 32; i++) sprintf(txidHex + i * 2, "%02x", txHash.u8[31 - i]);
    txidHex[64] = '\0';

    /* One owner per object -- the same shape the plain-DGB paths (publishTransaction /
     * publishTransactionStem) use: the wallet keeps its own record, the publisher is handed the
     * ORIGINAL. See _registerWalletCopy. */
    _registerWalletCopy(tx);

    /* Non-NULL callback: see _publishResult -- NULL makes the publish invisible to the
       cancellation path and it never leaves publishedTx. */
    PublishCtx *ctx = calloc(1, sizeof(*ctx));
    if (ctx) memcpy(ctx->txid, txidHex, sizeof(ctx->txid));
    BRPeerManagerPublishTx(g_peerManager, tx, ctx, ctx ? _publishResult : NULL);   /* owns the original; do NOT free tx */

    LOGD("sendApprovedDigiDollar: submitted txid=%s, fee %lld", txidHex, (long long)approvedFeeSats);
    return (*env)->NewStringUTF(env, txidHex);
}
