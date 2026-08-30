/*
 * jni_asset.c
 *
 * JNI bridge for DigiAsset detection operations.
 *
 * Functions:
 *   isAssetTransaction          - wraps BRTXContainsAsset() from BRDigiAsset.c
 *   getOpReturnData             - extracts raw OP_RETURN script bytes from raw tx
 *   getTransactionOutputsForHash - walks a wallet-known tx's outputs by txid hex
 *   getRawTransactionOutputs    - same, but from raw bytes (foreign/unregistered tx)
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

#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include "jni_bridge.h"
#include "BRDigiAsset.h"
#include "BRInt.h"
#include "BRCrypto.h"
#include "BRBase58.h"

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

/* ---------- getRawTransactionOutputs ---------- */

/*
 * public static native String[] getRawTransactionOutputs(byte[] rawTx);
 *
 * Same output listing as getTransactionOutputsForHash, but sourced from raw
 * bytes instead of BRWallet:
 *
 *     "<vout>|<satoshis>|<scriptHex>"
 *
 * getTransactionOutputsForHash can only answer for transactions the wallet
 * has registered. A seed the user is migrating AWAY from has none registered,
 * so recovery has to work from the raw parent transactions the scan already
 * fetched. That is the only difference; the format is identical so the same
 * Kotlin parsing serves both.
 *
 * These bytes come from a remote lookup and are attacker-influenced, so they
 * go through BRTransactionParse — the same hardened parser every other raw-tx
 * entry point uses — rather than a second parser written in Kotlin. Returns
 * NULL on a null/empty array or a parse failure; never partially-valid data.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getRawTransactionOutputs(JNIEnv *env, jobject thiz,
                                                                     jbyteArray rawTx) {
    (void)thiz;

    if (!rawTx) {
        LOGW("getRawTransactionOutputs: rawTx is null");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, rawTx);
    if (txLen <= 0) {
        LOGW("getRawTransactionOutputs: empty rawTx");
        return NULL;
    }

    jbyte *txBytes = (*env)->GetByteArrayElements(env, rawTx, NULL);
    if (!txBytes) {
        LOGE("getRawTransactionOutputs: failed to pin rawTx byte array");
        return NULL;
    }

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, rawTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGW("getRawTransactionOutputs: BRTransactionParse returned NULL");
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) { BRTransactionFree(tx); return NULL; }

    jobjectArray result = (*env)->NewObjectArray(env, (jsize)tx->outCount, stringClass, NULL);
    if (!result) { BRTransactionFree(tx); return NULL; }

    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *out = &tx->outputs[i];
        size_t bufSize = 64 + out->scriptLen * 2 + 1;
        char *buf = (char *)malloc(bufSize);
        if (!buf) continue;

        int header = snprintf(buf, bufSize, "%zu|%llu|", i, (unsigned long long)out->amount);
        if (header < 0 || (size_t)header >= bufSize) { free(buf); continue; }

        char *p = buf + header;
        for (size_t j = 0; j < out->scriptLen; j++) {
            snprintf(p, 3, "%02x", out->script[j]);
            p += 2;
        }
        *p = '\0';

        jstring s = (*env)->NewStringUTF(env, buf);
        free(buf);
        if (s) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }

    LOGD("getRawTransactionOutputs: returned %zu outputs", tx->outCount);
    BRTransactionFree(tx);
    return result;
}

/* ---------- getRawTransactionInputs ---------- */

/*
 * public static native String[] getRawTransactionInputs(byte[] rawTx);
 *
 * Lists what a transaction SPENDS, one entry per input:
 *
 *     "<prevTxidHexDisplayOrder>|<prevVout>"
 *
 * The DigiDollar endpoint lists every transaction that touched an address —
 * the spends as well as the receives — and reports only the live balance
 * beside them. A receive whose token output is already gone is still listed,
 * so locating an output in it claims a spendable dollar that no longer
 * exists. Reading the inputs of those same transactions is what tells the
 * two apart, and the spend is always among them: it is the reason the
 * transaction appears on the address at all.
 *
 * txHash is stored in internal byte order; it is reversed here so the hex
 * matches the display-order txid the lookup keys everything else by.
 *
 * These bytes come from a remote lookup and are attacker-influenced, so they
 * go through BRTransactionParse — the same hardened parser every other raw-tx
 * entry point uses. Returns NULL on a null/empty array or a parse failure.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getRawTransactionInputs(JNIEnv *env, jobject thiz,
                                                                    jbyteArray rawTx) {
    (void)thiz;

    if (!rawTx) {
        LOGW("getRawTransactionInputs: rawTx is null");
        return NULL;
    }

    jsize txLen = (*env)->GetArrayLength(env, rawTx);
    if (txLen <= 0) {
        LOGW("getRawTransactionInputs: empty rawTx");
        return NULL;
    }

    jbyte *txBytes = (*env)->GetByteArrayElements(env, rawTx, NULL);
    if (!txBytes) {
        LOGE("getRawTransactionInputs: failed to pin rawTx byte array");
        return NULL;
    }

    BRTransaction *tx = BRTransactionParse((const uint8_t *)txBytes, (size_t)txLen);
    (*env)->ReleaseByteArrayElements(env, rawTx, txBytes, JNI_ABORT);

    if (!tx) {
        LOGW("getRawTransactionInputs: BRTransactionParse returned NULL");
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) { BRTransactionFree(tx); return NULL; }

    jobjectArray result = (*env)->NewObjectArray(env, (jsize)tx->inCount, stringClass, NULL);
    if (!result) { BRTransactionFree(tx); return NULL; }

    for (size_t i = 0; i < tx->inCount; i++) {
        const BRTxInput *in = &tx->inputs[i];
        char buf[64 + 16];
        char *p = buf;
        /* Display order is the reverse of internal storage. */
        for (int j = (int)sizeof(in->txHash.u8) - 1; j >= 0; j--) {
            snprintf(p, 3, "%02x", in->txHash.u8[j]);
            p += 2;
        }
        snprintf(p, sizeof(buf) - (size_t)(p - buf), "|%u", (unsigned)in->index);

        jstring s = (*env)->NewStringUTF(env, buf);
        if (s) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }

    LOGD("getRawTransactionInputs: returned %zu inputs", tx->inCount);
    BRTransactionFree(tx);
    return result;
}

/* ---------- getTransactionOutputsForHash ---------- */

/*
 * public static native String[] getTransactionOutputsForHash(String txHashHex);
 *
 * Looks up a wallet-known transaction by its display-order hex txid and
 * returns its outputs as a String[], one entry per output formatted:
 *
 *     "<vout>|<satoshis>|<scriptHex>"
 *
 * Used by AssetManager on SPV receive to locate OP_RETURN + non-OP_RETURN
 * outputs natively without parsing raw tx bytes in Kotlin. Returns NULL
 * if the wallet has never seen this tx or the hex is malformed.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getTransactionOutputsForHash(JNIEnv *env, jobject thiz,
                                                                         jstring txHashHex) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("getTransactionOutputsForHash: wallet not initialized");
        return NULL;
    }
    if (!txHashHex) {
        LOGW("getTransactionOutputsForHash: txHashHex is null");
        return NULL;
    }

    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (!hashStr) return NULL;

    /* txid display hex is 64 chars big-endian; BRWallet stores little-endian.
     * uint256() parses hex as-is; UInt256Reverse flips byte order to internal. */
    size_t hexLen = strlen(hashStr);
    if (hexLen != 64) {
        LOGW("getTransactionOutputsForHash: bad hex len %zu", hexLen);
        (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
        return NULL;
    }
    UInt256 hash = UInt256Reverse(uint256(hashStr));
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);

    BRTransaction *tx = BRWalletTransactionForHash(g_wallet, hash);
    if (!tx) {
        LOGD("getTransactionOutputsForHash: tx not in wallet");
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    jobjectArray result = (*env)->NewObjectArray(env, (jsize)tx->outCount, stringClass, NULL);
    if (!result) return NULL;

    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *out = &tx->outputs[i];
        size_t bufSize = 64 + out->scriptLen * 2 + 1;
        char *buf = (char *)malloc(bufSize);
        if (!buf) continue;

        int header = snprintf(buf, bufSize, "%zu|%llu|", i, (unsigned long long)out->amount);
        if (header < 0 || (size_t)header >= bufSize) { free(buf); continue; }

        char *p = buf + header;
        for (size_t j = 0; j < out->scriptLen; j++) {
            snprintf(p, 3, "%02x", out->script[j]);
            p += 2;
        }
        *p = '\0';

        jstring s = (*env)->NewStringUTF(env, buf);
        free(buf);
        if (s) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }

    LOGD("getTransactionOutputsForHash: returned %zu outputs", tx->outCount);
    return result;
}

/* ---------- getSerializedTransactionForHash ---------- */

/*
 * public static native byte[] getSerializedTransactionForHash(String txHashHex);
 *
 * Returns the full serialized bytes of a wallet-known transaction, suitable
 * for re-parsing via BRTransactionParse or Kotlin-side OP_RETURN extraction.
 * Used by the M3 parent-walk fast path — skips a network round-trip when
 * the parent tx happens to be one we've already sent/received.
 *
 * Returns null if the txid isn't in BRWallet.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getSerializedTransactionForHash(JNIEnv *env, jobject thiz,
                                                                            jstring txHashHex) {
    (void)thiz;
    if (!g_wallet || !txHashHex) return NULL;

    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (!hashStr) return NULL;
    if (strlen(hashStr) != 64) {
        (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
        return NULL;
    }
    UInt256 hash = UInt256Reverse(uint256(hashStr));
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);

    BRTransaction *tx = BRWalletTransactionForHash(g_wallet, hash);
    if (!tx) return NULL;

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    if (len == 0) return NULL;
    uint8_t *buf = (uint8_t *)malloc(len);
    if (!buf) return NULL;
    size_t written = BRTransactionSerialize(tx, buf, len);
    if (written != len) { free(buf); return NULL; }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)len);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (const jbyte *)buf);
    free(buf);
    return result;
}

/* ---------- getTransactionInputsForHash ---------- */

/*
 * public static native String[] getTransactionInputsForHash(String txHashHex);
 *
 * Returns each input of a wallet-known transaction as
 *     "<prevTxidDisplayHex>|<prevVout>"
 * This is what asset-id derivation needs for ISSUANCE txs — and what the
 * M3 parent-walk will need to recurse backward from a transfer to its
 * issuance. Returns null if the wallet hasn't seen this txid.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getTransactionInputsForHash(JNIEnv *env, jobject thiz,
                                                                        jstring txHashHex) {
    (void)thiz;
    if (!g_wallet || !txHashHex) return NULL;

    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (!hashStr) return NULL;
    if (strlen(hashStr) != 64) {
        (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
        return NULL;
    }
    UInt256 hash = UInt256Reverse(uint256(hashStr));
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);

    BRTransaction *tx = BRWalletTransactionForHash(g_wallet, hash);
    if (!tx) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)tx->inCount, stringClass, NULL);
    if (!result) return NULL;

    for (size_t i = 0; i < tx->inCount; i++) {
        const BRTxInput *in = &tx->inputs[i];
        /* BRTxInput.txHash is stored internal-LE; render as display-BE hex. */
        char hashHex[65];
        for (int j = 0; j < 32; j++) {
            sprintf(&hashHex[j * 2], "%02x", in->txHash.u8[31 - j]);
        }
        hashHex[64] = '\0';

        char buf[96];
        int written = snprintf(buf, sizeof(buf), "%s|%u", hashHex, (unsigned int)in->index);
        if (written < 0 || (size_t)written >= sizeof(buf)) continue;

        jstring s = (*env)->NewStringUTF(env, buf);
        if (s) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    return result;
}

/* ---------- deriveIssuanceAssetId ---------- */

/*
 * public static native String deriveIssuanceAssetId(
 *     String firstInputTxidHex, int firstInputVout,
 *     boolean locked, int aggregation, int divisibility);
 *
 * Derives a DigiAsset v3 asset ID deterministically from the issuance
 * transaction's first input outpoint (locked path only — the common case,
 * matches "La..." / "Lh..." / "Ld..." prefixes). The unlocked path
 * requires the scriptPubKey of the spent output and is not yet supported
 * here (transfers to our wallet don't give us the issuance's first input
 * script; M3 parent-walk is the prerequisite).
 *
 * Algorithm (per DigiByte-Core/AssetId/assetIdEncoder.js):
 *   data       = ASCII bytes of "<txid_hex>:<vout>"
 *   hash256    = SHA256(data)
 *   hash160    = RIPEMD160(hash256)
 *   padding    = 2-byte version prefix selected by (locked, aggregation)
 *   divBytes   = [0x00, divisibility & 0xFF]
 *   payload    = padding || hash160 || divBytes       (24 bytes)
 *   assetId    = base58check(payload)                 (~38 chars)
 *
 * Version prefix table (locked):
 *   aggregatable → {0x20, 0xce}  "La"
 *   hybrid       → {0x21, 0x02}  "Lh"
 *   dispersed    → {0x20, 0xe4}  "Ld"
 *
 * @param aggregation  0 = aggregatable, 1 = hybrid, 2 = dispersed
 * @param divisibility 0..7 (decimal places)
 * @return base58check asset ID, or NULL on any failure.
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_deriveIssuanceAssetId(JNIEnv *env, jobject thiz,
                                                                  jstring firstInputTxidHex,
                                                                  jint firstInputVout,
                                                                  jboolean locked,
                                                                  jint aggregation,
                                                                  jint divisibility) {
    (void)thiz;

    if (!firstInputTxidHex) return NULL;
    if (firstInputVout < 0) return NULL;
    if (aggregation < 0 || aggregation > 2) return NULL;
    if (divisibility < 0 || divisibility > 7) return NULL;

    /* Unlocked path not yet implemented — needs prev output scriptPubKey. */
    if (!locked) {
        LOGW("deriveIssuanceAssetId: unlocked path not supported yet");
        return NULL;
    }

    const char *txidHex = (*env)->GetStringUTFChars(env, firstInputTxidHex, NULL);
    if (!txidHex) return NULL;
    size_t txidLen = strlen(txidHex);
    if (txidLen != 64) {
        LOGW("deriveIssuanceAssetId: bad txid hex length %zu", txidLen);
        (*env)->ReleaseStringUTFChars(env, firstInputTxidHex, txidHex);
        return NULL;
    }

    /* Build "<txid>:<vout>" ASCII buffer — same as digiasset-js. */
    char input[64 + 1 + 10 + 1];
    int inputLen = snprintf(input, sizeof(input), "%s:%d", txidHex, (int)firstInputVout);
    (*env)->ReleaseStringUTFChars(env, firstInputTxidHex, txidHex);
    if (inputLen <= 0 || (size_t)inputLen >= sizeof(input)) {
        LOGE("deriveIssuanceAssetId: input buffer overflow");
        return NULL;
    }

    /* hash160(data) = RIPEMD160(SHA256(data)) */
    uint8_t sha[32];
    BRSHA256(sha, input, (size_t)inputLen);
    uint8_t hash160[20];
    BRRMD160(hash160, sha, sizeof(sha));

    /* Version prefix by (locked=true, aggregation). */
    static const uint8_t LOCKED_AGG[2]  = { 0x20, 0xce };
    static const uint8_t LOCKED_HYB[2]  = { 0x21, 0x02 };
    static const uint8_t LOCKED_DIS[2]  = { 0x20, 0xe4 };
    const uint8_t *prefix;
    switch (aggregation) {
        case 0: prefix = LOCKED_AGG; break;
        case 1: prefix = LOCKED_HYB; break;
        case 2: prefix = LOCKED_DIS; break;
        default: return NULL;  /* unreachable */
    }

    /* payload = prefix[2] || hash160[20] || {0x00, divisibility}[2] = 24 bytes */
    uint8_t payload[24];
    payload[0] = prefix[0];
    payload[1] = prefix[1];
    memcpy(payload + 2, hash160, 20);
    payload[22] = 0x00;
    payload[23] = (uint8_t)(divisibility & 0xFF);

    /* base58check appends its own 4-byte SHA256D checksum. */
    size_t outLen = BRBase58CheckEncode(NULL, 0, payload, sizeof(payload));
    if (outLen == 0 || outLen > 64) {
        LOGE("deriveIssuanceAssetId: base58check sized %zu", outLen);
        return NULL;
    }
    char *out = (char *)malloc(outLen);
    if (!out) return NULL;
    BRBase58CheckEncode(out, outLen, payload, sizeof(payload));

    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    LOGD("deriveIssuanceAssetId: derived id for %.8s...:%d", input, (int)firstInputVout);
    return result;
}
