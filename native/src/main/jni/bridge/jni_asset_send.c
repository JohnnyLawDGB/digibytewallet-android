/*
 * jni_asset_send.c
 *
 * JNI bridge for DigiAsset transfer transactions. Kotlin builds the high-
 * level pieces (coin selection, DA OP_RETURN encoding, output ordering)
 * and this bridge assembles a raw BRTransaction, signs with the wallet's
 * own keys (via seed_sign_transaction), and returns the signed hex.
 *
 * Pattern mirrors buildAndSignLegacySweep in jni_derive.c but doesn't need
 * seed-derivation per input — the inputs we're spending are our own BIP84
 * wallet's UTXOs, so BRWalletSignTransaction finds the right keys itself.
 */

#include "jni_bridge.h"
#include "BRAddress.h"
#include "BRTransaction.h"
#include "BRInt.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Local hex helpers (kept static, mirror the pattern in jni_derive.c). */

static size_t hex_to_bytes_local(const char *hex, uint8_t *out, size_t outMax) {
    if (!hex) return 0;
    size_t hexLen = strlen(hex);
    if (hexLen % 2 != 0) return 0;
    size_t outLen = hexLen / 2;
    if (outLen > outMax) return 0;
    for (size_t i = 0; i < outLen; i++) {
        char hi = hex[2 * i];
        char lo = hex[2 * i + 1];
        int h = (hi >= '0' && hi <= '9') ? hi - '0'
              : (hi >= 'a' && hi <= 'f') ? hi - 'a' + 10
              : (hi >= 'A' && hi <= 'F') ? hi - 'A' + 10 : -1;
        int l = (lo >= '0' && lo <= '9') ? lo - '0'
              : (lo >= 'a' && lo <= 'f') ? lo - 'a' + 10
              : (lo >= 'A' && lo <= 'F') ? lo - 'A' + 10 : -1;
        if (h < 0 || l < 0) return 0;
        out[i] = (uint8_t)((h << 4) | l);
    }
    return outLen;
}

static void bytes_to_hex_local(const uint8_t *bytes, size_t n, char *out) {
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) {
        out[2 * i]     = hex[(bytes[i] >> 4) & 0x0F];
        out[2 * i + 1] = hex[bytes[i] & 0x0F];
    }
    out[2 * n] = '\0';
}

/**
 * Build, sign, and serialize a DigiAsset transfer transaction.
 *
 * Inputs:
 *   inputTxidsHex[i], inputVouts[i], inputAmounts[i], inputScriptPubKeysHex[i]
 *   — one entry per input UTXO. Asset inputs MUST come first (DA spec
 *     places the instruction stream against asset inputs by order);
 *     DGB fee inputs follow.
 *
 * Outputs:
 *   outputAddresses[i], outputAmounts[i], outputScriptsHex[i]
 *   — one entry per output. If outputAddresses[i] is an empty string (not
 *     null), the bridge uses outputScriptsHex[i] as the raw scriptPubKey
 *     (this is how the OP_RETURN output is passed in). Otherwise the
 *     address is validated and BRAddressScriptPubKey computes the script.
 *     outputScriptsHex[i] is ignored in the address case.
 *
 * Returns: signed tx as hex string, or NULL on any failure.
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_buildAndSignAssetTransferTx(
    JNIEnv *env, jobject thiz,
    jobjectArray inputTxidsHex,
    jintArray inputVouts,
    jlongArray inputAmounts,
    jobjectArray inputScriptPubKeysHex,
    jobjectArray outputAddresses,
    jlongArray outputAmounts,
    jobjectArray outputScriptsHex)
{
    (void)thiz;

    if (!g_wallet) {
        LOGW("buildAndSignAssetTransferTx: wallet not initialized");
        return NULL;
    }
    if (!seed_is_valid()) {
        LOGW("buildAndSignAssetTransferTx: session locked (no seed)");
        return NULL;
    }
    if (!inputTxidsHex || !inputVouts || !inputAmounts || !inputScriptPubKeysHex ||
        !outputAddresses || !outputAmounts || !outputScriptsHex) {
        LOGW("buildAndSignAssetTransferTx: null required array");
        return NULL;
    }

    const jsize nIn  = (*env)->GetArrayLength(env, inputTxidsHex);
    const jsize nOut = (*env)->GetArrayLength(env, outputAddresses);
    if (nIn <= 0 || nOut <= 0) return NULL;
    if ((*env)->GetArrayLength(env, inputVouts) != nIn) return NULL;
    if ((*env)->GetArrayLength(env, inputAmounts) != nIn) return NULL;
    if ((*env)->GetArrayLength(env, inputScriptPubKeysHex) != nIn) return NULL;
    if ((*env)->GetArrayLength(env, outputAmounts) != nOut) return NULL;
    if ((*env)->GetArrayLength(env, outputScriptsHex) != nOut) return NULL;

    BRTransaction *tx = BRTransactionNew();
    if (!tx) return NULL;

    jint  *voutJ = (*env)->GetIntArrayElements(env, inputVouts, NULL);
    jlong *amtJ  = (*env)->GetLongArrayElements(env, inputAmounts, NULL);
    jlong *outAmtJ = (*env)->GetLongArrayElements(env, outputAmounts, NULL);

    int ok = 1;

    /* ── Inputs ─────────────────────────────────────────────────────── */
    for (jsize i = 0; ok && i < nIn; i++) {
        jstring txidJ = (jstring)(*env)->GetObjectArrayElement(env, inputTxidsHex, i);
        jstring scriptJ = (jstring)(*env)->GetObjectArrayElement(env, inputScriptPubKeysHex, i);
        if (!txidJ || !scriptJ) { ok = 0; break; }

        const char *txidStr = (*env)->GetStringUTFChars(env, txidJ, NULL);
        const char *scriptStr = (*env)->GetStringUTFChars(env, scriptJ, NULL);

        uint8_t txidBytes[32];
        uint8_t scriptBytes[256];
        size_t txidLen = hex_to_bytes_local(txidStr, txidBytes, sizeof(txidBytes));
        size_t scriptLen = hex_to_bytes_local(scriptStr, scriptBytes, sizeof(scriptBytes));

        (*env)->ReleaseStringUTFChars(env, txidJ, txidStr);
        (*env)->ReleaseStringUTFChars(env, scriptJ, scriptStr);
        (*env)->DeleteLocalRef(env, txidJ);
        (*env)->DeleteLocalRef(env, scriptJ);

        if (txidLen != 32 || scriptLen == 0) { ok = 0; break; }

        /* txid hex is big-endian display; internal is little-endian. */
        UInt256 txHash;
        for (int k = 0; k < 32; k++) txHash.u8[k] = txidBytes[31 - k];

        BRTransactionAddInput(tx, txHash, (uint32_t)voutJ[i],
                              (uint64_t)amtJ[i],
                              scriptBytes, scriptLen,
                              NULL, 0,   /* no pre-existing scriptSig */
                              NULL, 0,   /* no pre-existing witness  */
                              TXIN_SEQUENCE);
    }

    (*env)->ReleaseIntArrayElements(env, inputVouts, voutJ, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, inputAmounts, amtJ, JNI_ABORT);

    if (!ok) {
        (*env)->ReleaseLongArrayElements(env, outputAmounts, outAmtJ, JNI_ABORT);
        BRTransactionFree(tx);
        return NULL;
    }

    /* ── Outputs ────────────────────────────────────────────────────── */
    for (jsize i = 0; ok && i < nOut; i++) {
        jstring addrJ = (jstring)(*env)->GetObjectArrayElement(env, outputAddresses, i);
        jstring scriptJ = (jstring)(*env)->GetObjectArrayElement(env, outputScriptsHex, i);
        if (!addrJ) { ok = 0; break; }

        const char *addrStr = (*env)->GetStringUTFChars(env, addrJ, NULL);
        uint8_t scriptBytes[256];
        size_t scriptLen = 0;

        if (addrStr && addrStr[0] != '\0') {
            /* Normal address output — compute scriptPubKey from address. */
            scriptLen = BRAddressScriptPubKey(scriptBytes, sizeof(scriptBytes), addrStr);
            (*env)->ReleaseStringUTFChars(env, addrJ, addrStr);
            if (scriptLen == 0) {
                LOGW("buildAndSignAssetTransferTx: invalid output address at index %d", (int)i);
                ok = 0;
            }
        } else {
            /* OP_RETURN / raw-script output — use outputScriptsHex[i] directly. */
            (*env)->ReleaseStringUTFChars(env, addrJ, addrStr);
            if (!scriptJ) { ok = 0; }
            else {
                const char *scriptStr = (*env)->GetStringUTFChars(env, scriptJ, NULL);
                scriptLen = hex_to_bytes_local(scriptStr, scriptBytes, sizeof(scriptBytes));
                (*env)->ReleaseStringUTFChars(env, scriptJ, scriptStr);
                if (scriptLen == 0) {
                    LOGW("buildAndSignAssetTransferTx: empty raw script at index %d", (int)i);
                    ok = 0;
                }
            }
        }

        (*env)->DeleteLocalRef(env, addrJ);
        if (scriptJ) (*env)->DeleteLocalRef(env, scriptJ);

        if (ok) BRTransactionAddOutput(tx, (uint64_t)outAmtJ[i], scriptBytes, scriptLen);
    }

    (*env)->ReleaseLongArrayElements(env, outputAmounts, outAmtJ, JNI_ABORT);

    if (!ok) { BRTransactionFree(tx); return NULL; }

    /* ── Sign with the wallet's keys ────────────────────────────────── */
    int signed_ok = seed_sign_transaction(g_wallet, tx, 0);
    if (!signed_ok || !BRTransactionIsSigned(tx)) {
        LOGW("buildAndSignAssetTransferTx: signing failed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* ── Serialize + hex-encode ─────────────────────────────────────── */
    size_t serLen = BRTransactionSerialize(tx, NULL, 0);
    if (serLen == 0) { BRTransactionFree(tx); return NULL; }

    uint8_t *serBuf = (uint8_t *)malloc(serLen);
    if (!serBuf) { BRTransactionFree(tx); return NULL; }
    serLen = BRTransactionSerialize(tx, serBuf, serLen);

    char *hex = (char *)malloc(serLen * 2 + 1);
    if (!hex) { free(serBuf); BRTransactionFree(tx); return NULL; }
    bytes_to_hex_local(serBuf, serLen, hex);

    jstring result = (*env)->NewStringUTF(env, hex);

    free(hex);
    free(serBuf);
    BRTransactionFree(tx);

    LOGD("buildAndSignAssetTransferTx: signed tx, %zu bytes", serLen);
    return result;
}
