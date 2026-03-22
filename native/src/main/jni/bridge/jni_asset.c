/*
 * jni_asset.c
 *
 * JNI bridge for DigiAsset detection operations.
 *
 * Functions:
 *   isAssetTransaction  - wraps BRTXContainsAsset() from BRDigiAsset.c
 *   getOpReturnData     - extracts raw OP_RETURN script bytes from a transaction
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 * Raw transaction bytes are deserialized via BRTransactionParse() and freed
 * after use; no ownership is retained.
 *
 * OP_RETURN opcode: 0x6A
 *   A standard OP_RETURN output script has the form:
 *     [0x6A] [push-data-len] [data...]
 *   We locate the first output whose script begins with 0x6A and return the
 *   full script bytes (including the opcode and length prefix) so the Kotlin
 *   DigiAssetDecoder can parse the DigiAsset payload directly.
 */

#include "jni_bridge.h"
#include "BRDigiAsset.h"

#define OP_RETURN_OPCODE 0x6A

/* ---------- isAssetTransaction ---------- */

/*
 * public static native boolean isAssetTransaction(byte[] rawTx);
 *
 * Deserializes rawTx and calls BRTXContainsAsset().
 * Returns JNI_FALSE on any error (null input, parse failure, empty tx).
 */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isAssetTransaction(JNIEnv *env, jobject thiz,
                                                              jbyteArray rawTx) {
    (void)thiz;

    if (!rawTx) {
        LOGW("isAssetTransaction: rawTx is null");
        return JNI_FALSE;
    }

    jsize txLen = (*env)->GetArrayLength(env, rawTx);
    if (txLen <= 0) {
        LOGW("isAssetTransaction: empty rawTx");
        return JNI_FALSE;
    }

    jbyte *txBytes = (*env)->GetByteArrayElements(env, rawTx, NULL);
    if (!txBytes) {
        LOGE("isAssetTransaction: failed to pin rawTx byte array");
        return JNI_FALSE;
    }

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, rawTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGW("isAssetTransaction: BRTransactionParse returned NULL");
        return JNI_FALSE;
    }

    uint8_t result = BRTXContainsAsset(tx);
    BRTransactionFree(tx);

    LOGD("isAssetTransaction: result=%d", (int)result);
    return (result != 0) ? JNI_TRUE : JNI_FALSE;
}

/* ---------- getOpReturnData ---------- */

/*
 * public static native byte[] getOpReturnData(byte[] rawTx);
 *
 * Parses rawTx, walks its outputs, and returns the full script bytes of the
 * first OP_RETURN output found (script[0] == 0x6A).  Returns NULL if there
 * is no OP_RETURN output, or on any parse/allocation error.
 *
 * The returned byte array includes the leading 0x6A opcode and push-data
 * length byte(s) so that the Kotlin DigiAssetDecoder sees the same format
 * it expects from a raw scriptPubKey.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getOpReturnData(JNIEnv *env, jobject thiz,
                                                           jbyteArray rawTx) {
    (void)thiz;

    if (!rawTx) {
        LOGW("getOpReturnData: rawTx is null");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, rawTx);
    if (txLen <= 0) {
        LOGW("getOpReturnData: empty rawTx");
        return NULL;
    }

    jbyte *txBytes = (*env)->GetByteArrayElements(env, rawTx, NULL);
    if (!txBytes) {
        LOGE("getOpReturnData: failed to pin rawTx byte array");
        return NULL;
    }

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, rawTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGW("getOpReturnData: BRTransactionParse returned NULL");
        return NULL;
    }

    /* Walk outputs looking for OP_RETURN (first byte == 0x6A) */
    jbyteArray result = NULL;
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *out = &tx->outputs[i];
        if (out->scriptLen > 0 && out->script != NULL &&
            out->script[0] == OP_RETURN_OPCODE) {

            /* Return the full script so Kotlin sees [OP_RETURN][len][data...] */
            result = (*env)->NewByteArray(env, (jsize)out->scriptLen);
            if (result) {
                (*env)->SetByteArrayRegion(env, result, 0,
                                           (jsize)out->scriptLen,
                                           (const jbyte *)out->script);
                LOGD("getOpReturnData: found OP_RETURN at output %zu, scriptLen=%zu",
                     i, out->scriptLen);
            } else {
                LOGE("getOpReturnData: NewByteArray allocation failed");
            }
            break;
        }
    }

    BRTransactionFree(tx);

    if (!result) {
        LOGD("getOpReturnData: no OP_RETURN output found");
    }
    return result;
}
