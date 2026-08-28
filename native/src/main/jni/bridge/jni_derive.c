/**
 * jni_derive.c — JNI entry points for Universal Restore key derivation.
 *
 * These functions are stateless — they do NOT touch g_wallet or any other
 * native global. They exist so the Kotlin RecoveryScanService can probe
 * arbitrary derivation paths with arbitrary HMAC seed keys ("Bitcoin seed"
 * vs "DigiByte seed") to surface funds on non-native paths (BIP44 DGB,
 * BIP44-with-wrong-coin, BIP49 DGB, legacy m/0H with either HMAC) during
 * seed restore.
 *
 * Two entry points:
 *
 *   deriveAddresses   — returns N + M addresses derived under a path prefix.
 *                       Gap limits are external / internal (receive / change).
 *   derivePrivateKeyWIF — returns a WIF-encoded private key for a specific
 *                       full path. Used by LegacySweepService when signing
 *                       inputs spent from non-native addresses.
 *
 * Seeds are zeroed from native memory before being released.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>

#include "../digibytewallet-core/BRBIP32Sequence.h"
#include "../digibytewallet-core/BRBIP39Mnemonic.h"
#include "../digibytewallet-core/BRKey.h"
#include "../digibytewallet-core/BRAddress.h"
#include "../digibytewallet-core/BRBase58.h"
#include "../digibytewallet-core/BRCrypto.h"
#include "../digibytewallet-core/BRTransaction.h"
#include "../digibytewallet-core/BRInt.h"
#include "jni_seed_buffer.h"
#include "foreign_tx_fee_guard.h"

#define LOG_TAG "DGB-Derive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** How far above the size-derived estimate an implied fee may sit before the transaction is
 *  refused. Generous enough for fee-rate disagreement and rounding; tight enough that a missing
 *  change output — the realistic way value gets burned here — cannot slip through. */

static void secure_zero(volatile void *p, size_t n) {
    volatile uint8_t *v = (volatile uint8_t *)p;
    while (n--) *v++ = 0;
}

/** hex "deadbeef" -> bytes. Returns bytes written, or 0 on bad input. */
static size_t hex_to_bytes(const char *hex, uint8_t *out, size_t outMax) {
    if (!hex || !out) return 0;
    size_t n = strlen(hex);
    if (n & 1) return 0;
    size_t byteLen = n / 2;
    if (byteLen > outMax) return 0;
    for (size_t i = 0; i < byteLen; i++) {
        int hi = hex[i * 2], lo = hex[i * 2 + 1];
        hi = (hi >= '0' && hi <= '9') ? hi - '0' :
             (hi >= 'a' && hi <= 'f') ? hi - 'a' + 10 :
             (hi >= 'A' && hi <= 'F') ? hi - 'A' + 10 : -1;
        lo = (lo >= '0' && lo <= '9') ? lo - '0' :
             (lo >= 'a' && lo <= 'f') ? lo - 'a' + 10 :
             (lo >= 'A' && lo <= 'F') ? lo - 'A' + 10 : -1;
        if (hi < 0 || lo < 0) return 0;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return byteLen;
}

static void bytes_to_hex(const uint8_t *bytes, size_t n, char *out) {
    static const char HEX[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) {
        out[i * 2] = HEX[(bytes[i] >> 4) & 0xf];
        out[i * 2 + 1] = HEX[bytes[i] & 0xf];
    }
    out[n * 2] = '\0';
}

/** addressFormat: 0=P2PKH, 1=P2WPKH bech32, 2=P2SH-P2WPKH.
 *  Returns 1 on success (addr populated + NUL terminated), 0 on failure. */
static int pubkey_to_address(BRKey *key, int addressFormat, char *out, size_t outLen) {
    if (outLen > 0) out[0] = '\0';
    switch (addressFormat) {
        case 0: {
            size_t n = BRKeyAddress(key, out, outLen);
            return (n > 0 && n <= outLen) ? 1 : 0;
        }
        case 1: {
            size_t n = BRKeySegwitAddress(key, out, outLen, OP_0);
            return (n > 0 && n <= outLen) ? 1 : 0;
        }
        case 2: {
            /* P2SH-wrapped P2WPKH: redeemScript = OP_0 <push20> <hash160(pubkey)>,
             * then wrap in P2SH. */
            UInt160 pkh = BRKeyHash160(key);
            uint8_t witness[22];
            witness[0] = 0x00; /* OP_0 */
            witness[1] = 0x14; /* push 20 bytes */
            memcpy(&witness[2], &pkh, 20);

            UInt160 scriptHash;
            BRHash160(&scriptHash, witness, sizeof(witness));

            uint8_t data[21];
            data[0] = DIGIBYTE_SCRIPT_ADDRESS;
            memcpy(&data[1], &scriptHash, 20);

            size_t n = BRBase58CheckEncode(out, outLen, data, sizeof(data));
            return (n > 0 && n <= outLen) ? 1 : 0;
        }
        default:
            return 0;
    }
}

/**
 * Convert a BIP39 mnemonic phrase to its 64-byte seed, with optional passphrase.
 *
 * Kotlin signature:
 *   external fun mnemonicToSeed(
 *       phraseBytes: ByteArray,      // UTF-8 bytes of the mnemonic
 *       passphrase: ByteArray?       // NFKD UTF-8 bytes, null = no passphrase
 *   ): ByteArray
 *
 * Caller should zero the returned ByteArray via fill(0) once done scanning.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_mnemonicToSeed(
    JNIEnv *env, jobject thiz,
    jbyteArray phraseBytes,
    jbyteArray passphrase)
{
    (void)thiz;
    if (!phraseBytes) return NULL;

    const jsize phraseLen = (*env)->GetArrayLength(env, phraseBytes);
    jbyte *phraseRaw = (*env)->GetByteArrayElements(env, phraseBytes, NULL);
    if (!phraseRaw) return NULL;

    /* BRBIP39DeriveKey needs a NUL-terminated phrase. Copy to an on-stack
     * buffer so we can NUL-terminate without mutating the caller's array. */
    char phraseCopy[512];
    if ((size_t)phraseLen >= sizeof(phraseCopy)) {
        (*env)->ReleaseByteArrayElements(env, phraseBytes, phraseRaw, JNI_ABORT);
        return NULL;
    }
    memcpy(phraseCopy, phraseRaw, (size_t)phraseLen);
    phraseCopy[phraseLen] = '\0';
    (*env)->ReleaseByteArrayElements(env, phraseBytes, phraseRaw, JNI_ABORT);

    /* Bytes, not a jstring: a String cannot be zeroed on the Kotlin side, and a JNI string
     * pointer must not be written through (see jni_seed_buffer.h). Copied into a buffer we own
     * so it can be wiped like the phrase beside it. */
    char passCopy[129];
    passCopy[0] = '\0';
    if (passphrase) {
        const jsize passLen = (*env)->GetArrayLength(env, passphrase);
        if (passLen < 0 || (size_t)passLen >= sizeof(passCopy)) {
            secure_zero(phraseCopy, sizeof(phraseCopy));
            return NULL;
        }
        if (passLen > 0) (*env)->GetByteArrayRegion(env, passphrase, 0, passLen, (jbyte *)passCopy);
        passCopy[passLen] = '\0';
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseCopy, passCopy[0] ? passCopy : NULL);
    secure_zero(phraseCopy, sizeof(phraseCopy));
    secure_zero(passCopy, sizeof(passCopy));

    jbyteArray result = (*env)->NewByteArray(env, sizeof(seed));
    if (!result) {
        secure_zero(seed, sizeof(seed));
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, sizeof(seed), (const jbyte *)seed);
    secure_zero(seed, sizeof(seed));
    return result;
}

/**
 * Derive external + internal addresses under a hardened path prefix.
 *
 * Kotlin signature:
 *   external fun deriveAddresses(
 *       seedBytes: ByteArray,        // BIP39 seed bytes (NOT mnemonic string)
 *       hmacKey: String,             // "Bitcoin seed" or "DigiByte seed"
 *       prefixPath: IntArray,        // [44|HARD, 20|HARD, 0|HARD] for BIP44 DGB
 *       gapExternal: Int,            // count of 0/i addresses to return
 *       gapInternal: Int,            // count of 1/i addresses to return
 *       addressFormat: Int           // 0=P2PKH, 1=P2WPKH, 2=P2SH-P2WPKH
 *   ): Array<String>
 *
 * Returns external[0..gapExternal-1] followed by internal[0..gapInternal-1].
 * Empty strings mark positions where derivation failed (should be rare —
 * the pubkey curve can reject an index, advance and skip in that case).
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_deriveAddresses(
    JNIEnv *env, jobject thiz,
    jbyteArray seedBytes,
    jstring hmacKey,
    jintArray prefixPath,
    jint gapExternal,
    jint gapInternal,
    jint addressFormat)
{
    (void)thiz;
    if (!seedBytes || !hmacKey || !prefixPath) return NULL;
    if (gapExternal < 0 || gapInternal < 0) return NULL;
    if (addressFormat < 0 || addressFormat > 2) return NULL;

    const jsize seedLen = (*env)->GetArrayLength(env, seedBytes);
    jbyte *seedRaw = (*env)->GetByteArrayElements(env, seedBytes, NULL);
    if (!seedRaw) return NULL;

    /* Take a private copy and hand the caller's array straight back, untouched.
     * Never wipe seedRaw: JNI_ABORT can only discard writes made to a COPY, and
     * whether GetByteArrayElements gave us one is unspecified. LegacySweepService
     * loads the seed once and reuses it across derivation profiles, so wiping in
     * place would empty it for every profile after the first. See
     * jni_seed_buffer.h. */
    SeedBuffer seed;
    const int seedOk = seed_buffer_take(&seed, seedRaw, (size_t)seedLen);
    (*env)->ReleaseByteArrayElements(env, seedBytes, seedRaw, JNI_ABORT);
    if (!seedOk) {
        LOGW("rejecting a seed of %d bytes (expected 1..%u)", (int)seedLen, SEED_BUFFER_MAX);
        return NULL;
    }

    const char *hmac = (*env)->GetStringUTFChars(env, hmacKey, NULL);
    if (!hmac) {
        seed_buffer_release(&seed);
        return NULL;
    }

    const jsize pathLen = (*env)->GetArrayLength(env, prefixPath);
    jint *pathJ = (*env)->GetIntArrayElements(env, prefixPath, NULL);
    uint32_t *path = NULL;
    if (pathLen > 0) {
        path = (uint32_t *)malloc((size_t)pathLen * sizeof(uint32_t));
        if (!path) {
            (*env)->ReleaseIntArrayElements(env, prefixPath, pathJ, JNI_ABORT);
            (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
            seed_buffer_release(&seed);
            return NULL;
        }
        for (jsize i = 0; i < pathLen; i++) path[i] = (uint32_t)pathJ[i];
    }

    BRMasterPubKey mpk = BRBIP32MasterPubKeyPath(
        seed.bytes, seed.len,
        hmac, path, (size_t)pathLen);

    /* Clean up intermediates now — seed memory zeroed and released. */
    if (path) { secure_zero(path, (size_t)pathLen * sizeof(uint32_t)); free(path); }
    (*env)->ReleaseIntArrayElements(env, prefixPath, pathJ, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
    seed_buffer_release(&seed);

    const int total = gapExternal + gapInternal;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, total, stringClass, NULL);
    if (!result) return NULL;

    uint8_t pub[33];
    char addr[91]; /* bech32 max plus margin */
    BRKey childKey;

    int outIdx = 0;
    for (int chain = 0; chain < 2; chain++) {
        const int gap = (chain == 0) ? gapExternal : gapInternal;
        for (int index = 0; index < gap; index++) {
            size_t pubLen = BRBIP32PubKey(pub, sizeof(pub), mpk,
                                          (uint32_t)chain, (uint32_t)index);
            if (pubLen == 0) { outIdx++; continue; }

            BRKeySetPubKey(&childKey, pub, pubLen);
            if (pubkey_to_address(&childKey, addressFormat, addr, sizeof(addr))) {
                jstring js = (*env)->NewStringUTF(env, addr);
                (*env)->SetObjectArrayElement(env, result, outIdx, js);
                (*env)->DeleteLocalRef(env, js);
            }
            BRKeyClean(&childKey);
            outIdx++;
        }
    }

    return result;
}

/**
 * Derive WIF-encoded private key at an arbitrary full path.
 *
 * Kotlin signature:
 *   external fun derivePrivateKeyWIF(
 *       seedBytes: ByteArray,
 *       hmacKey: String,             // "Bitcoin seed" or "DigiByte seed"
 *       fullPath: IntArray           // e.g. [44|HARD, 20|HARD, 0|HARD, 0, 5]
 *   ): String?
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_derivePrivateKeyWIF(
    JNIEnv *env, jobject thiz,
    jbyteArray seedBytes,
    jstring hmacKey,
    jintArray fullPath)
{
    (void)thiz;
    if (!seedBytes || !hmacKey || !fullPath) return NULL;

    const jsize seedLen = (*env)->GetArrayLength(env, seedBytes);
    jbyte *seedRaw = (*env)->GetByteArrayElements(env, seedBytes, NULL);
    if (!seedRaw) return NULL;

    /* Take a private copy and hand the caller's array straight back, untouched.
     * Never wipe seedRaw: JNI_ABORT can only discard writes made to a COPY, and
     * whether GetByteArrayElements gave us one is unspecified. LegacySweepService
     * loads the seed once and reuses it across derivation profiles, so wiping in
     * place would empty it for every profile after the first. See
     * jni_seed_buffer.h. */
    SeedBuffer seed;
    const int seedOk = seed_buffer_take(&seed, seedRaw, (size_t)seedLen);
    (*env)->ReleaseByteArrayElements(env, seedBytes, seedRaw, JNI_ABORT);
    if (!seedOk) {
        LOGW("rejecting a seed of %d bytes (expected 1..%u)", (int)seedLen, SEED_BUFFER_MAX);
        return NULL;
    }

    const char *hmac = (*env)->GetStringUTFChars(env, hmacKey, NULL);
    if (!hmac) {
        seed_buffer_release(&seed);
        return NULL;
    }

    const jsize pathLen = (*env)->GetArrayLength(env, fullPath);
    jint *pathJ = (*env)->GetIntArrayElements(env, fullPath, NULL);
    uint32_t *path = NULL;
    if (pathLen > 0) {
        path = (uint32_t *)malloc((size_t)pathLen * sizeof(uint32_t));
        if (!path) {
            (*env)->ReleaseIntArrayElements(env, fullPath, pathJ, JNI_ABORT);
            (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
            seed_buffer_release(&seed);
            return NULL;
        }
        for (jsize i = 0; i < pathLen; i++) path[i] = (uint32_t)pathJ[i];
    }

    BRKey key;
    BRBIP32PrivKeyArrayPath(&key, seed.bytes, seed.len,
                            hmac, path, (size_t)pathLen);

    if (path) { secure_zero(path, (size_t)pathLen * sizeof(uint32_t)); free(path); }
    (*env)->ReleaseIntArrayElements(env, fullPath, pathJ, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
    seed_buffer_release(&seed);

    char wif[80];
    size_t n = BRKeyPrivKey(&key, wif, sizeof(wif));
    BRKeyClean(&key);

    if (n == 0) return NULL;
    jstring result = (*env)->NewStringUTF(env, wif);
    secure_zero(wif, sizeof(wif));
    return result;
}

/**
 * Build + sign a sweep transaction spending legacy-path UTXOs into one
 * destination address.
 *
 * Each input references a UTXO on a non-native derivation path. We derive
 * the per-input private key from seed + hmacKey + (prefixPath + chain +
 * index), wire the UTXO's scriptPubKey into the BRTransaction input, then
 * call BRTransactionSign which picks the right scriptSig / witness format
 * based on the scriptPubKey shape (P2PKH or P2WPKH).
 *
 * P2SH-P2WPKH (BIP49) is NOT yet supported end-to-end — BRTransactionSign
 * doesn't construct the P2SH redeem-script scriptSig for BIP49 inputs.
 * Callers should filter BIP49 UTXOs out and surface them to the user as
 * "manual recovery needed" until we extend BRTransactionSign.
 *
 * Returns the hex of the fully-signed tx, or null on any parse / sign /
 * validation failure.
 *
 * Kotlin signature:
 *   external fun buildAndSignLegacySweep(
 *       seedBytes: ByteArray,
 *       hmacKey: String,
 *       prefixPath: IntArray,
 *       txidsHex: Array<String>,
 *       vouts: IntArray,
 *       amounts: LongArray,
 *       chainIndices: IntArray,
 *       addressIndices: IntArray,
 *       scriptPubKeysHex: Array<String>,
 *       destAddress: String,
 *       feePerKb: Long
 *   ): String?
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_buildAndSignLegacySweep(
    JNIEnv *env, jobject thiz,
    jbyteArray seedBytes,
    jstring hmacKey,
    jintArray prefixPath,
    jobjectArray txidsHex,
    jintArray vouts,
    jlongArray amounts,
    jintArray chainIndices,
    jintArray addressIndices,
    jobjectArray scriptPubKeysHex,
    jstring destAddress,
    jlong feePerKb)
{
    (void)thiz;
    if (!seedBytes || !hmacKey || !prefixPath || !txidsHex || !vouts ||
        !amounts || !chainIndices || !addressIndices || !scriptPubKeysHex ||
        !destAddress) {
        return NULL;
    }

    const jsize inputCount = (*env)->GetArrayLength(env, txidsHex);
    if (inputCount <= 0) return NULL;
    if ((*env)->GetArrayLength(env, vouts) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, amounts) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, chainIndices) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, addressIndices) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, scriptPubKeysHex) != inputCount) return NULL;

    /* ── Pull seed + hmacKey + prefixPath. Seed zeroed before return. ── */
    const jsize seedLen = (*env)->GetArrayLength(env, seedBytes);
    jbyte *seedRaw = (*env)->GetByteArrayElements(env, seedBytes, NULL);
    if (!seedRaw) return NULL;

    /* Take a private copy and hand the caller's array straight back, untouched.
     * Never wipe seedRaw: JNI_ABORT can only discard writes made to a COPY, and
     * whether GetByteArrayElements gave us one is unspecified. LegacySweepService
     * loads the seed once and reuses it across derivation profiles, so wiping in
     * place would empty it for every profile after the first. See
     * jni_seed_buffer.h. */
    SeedBuffer seed;
    const int seedOk = seed_buffer_take(&seed, seedRaw, (size_t)seedLen);
    (*env)->ReleaseByteArrayElements(env, seedBytes, seedRaw, JNI_ABORT);
    if (!seedOk) {
        LOGW("rejecting a seed of %d bytes (expected 1..%u)", (int)seedLen, SEED_BUFFER_MAX);
        return NULL;
    }

    const char *hmac = (*env)->GetStringUTFChars(env, hmacKey, NULL);
    if (!hmac) {
        seed_buffer_release(&seed);
        return NULL;
    }

    const jsize prefixLen = (*env)->GetArrayLength(env, prefixPath);
    jint *prefixJ = (*env)->GetIntArrayElements(env, prefixPath, NULL);

    /* ── Derive per-input keys and parse UTXO data. ── */
    BRKey *keys = (BRKey *)calloc((size_t)inputCount, sizeof(BRKey));
    if (!keys) {
        (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    BRTransaction *tx = BRTransactionNew();
    if (!tx) {
        free(keys);
        (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    jint *voutJ = (*env)->GetIntArrayElements(env, vouts, NULL);
    jlong *amtJ = (*env)->GetLongArrayElements(env, amounts, NULL);
    jint *chainJ = (*env)->GetIntArrayElements(env, chainIndices, NULL);
    jint *addrIdxJ = (*env)->GetIntArrayElements(env, addressIndices, NULL);

    int ok = 1;
    uint64_t totalIn = 0;

    /* Build the full path buffer once — reused per input. */
    uint32_t *fullPath = (uint32_t *)malloc(
        ((size_t)prefixLen + 2) * sizeof(uint32_t));
    if (!fullPath) { ok = 0; }
    else for (jsize i = 0; i < prefixLen; i++) fullPath[i] = (uint32_t)prefixJ[i];

    for (jsize i = 0; ok && i < inputCount; i++) {
        jstring txidJ = (jstring)(*env)->GetObjectArrayElement(env, txidsHex, i);
        jstring scriptJ = (jstring)(*env)->GetObjectArrayElement(env, scriptPubKeysHex, i);
        if (!txidJ || !scriptJ) { ok = 0; break; }

        const char *txidStr = (*env)->GetStringUTFChars(env, txidJ, NULL);
        const char *scriptStr = (*env)->GetStringUTFChars(env, scriptJ, NULL);

        uint8_t txidBytes[32];
        uint8_t scriptBytes[128];
        size_t txidLen = hex_to_bytes(txidStr, txidBytes, sizeof(txidBytes));
        size_t scriptLen = hex_to_bytes(scriptStr, scriptBytes, sizeof(scriptBytes));

        (*env)->ReleaseStringUTFChars(env, txidJ, txidStr);
        (*env)->ReleaseStringUTFChars(env, scriptJ, scriptStr);
        (*env)->DeleteLocalRef(env, txidJ);
        (*env)->DeleteLocalRef(env, scriptJ);

        if (txidLen != 32 || scriptLen == 0) { ok = 0; break; }

        /* txid hex is big-endian display; internal is little-endian. */
        UInt256 txHash;
        for (int k = 0; k < 32; k++) txHash.u8[k] = txidBytes[31 - k];

        /* Derive key at prefix + chain + index. */
        fullPath[prefixLen] = (uint32_t)chainJ[i];
        fullPath[prefixLen + 1] = (uint32_t)addrIdxJ[i];
        BRBIP32PrivKeyArrayPath(&keys[i], seed.bytes, seed.len,
                                hmac, fullPath, (size_t)prefixLen + 2);
        /* BRBIP32PrivKeyArrayPath → BRKeySetSecret(compressed=1) internally. */

        BRTransactionAddInput(tx, txHash, (uint32_t)voutJ[i],
                              (uint64_t)amtJ[i],
                              scriptBytes, scriptLen,
                              NULL, 0,   /* no pre-existing signature */
                              NULL, 0,   /* no pre-existing witness */
                              TXIN_SEQUENCE);

        totalIn += (uint64_t)amtJ[i];
    }

    (*env)->ReleaseIntArrayElements(env, vouts, voutJ, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, amounts, amtJ, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, chainIndices, chainJ, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, addressIndices, addrIdxJ, JNI_ABORT);

    if (fullPath) { secure_zero(fullPath, ((size_t)prefixLen + 2) * sizeof(uint32_t)); free(fullPath); }
    (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);

    if (!ok) {
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    /* ── Add destination output. ── */
    const char *destStr = (*env)->GetStringUTFChars(env, destAddress, NULL);
    uint8_t destScript[48];
    size_t destScriptLen = BRAddressScriptPubKey(destScript, sizeof(destScript), destStr);
    (*env)->ReleaseStringUTFChars(env, destAddress, destStr);
    if (destScriptLen == 0) {
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    /* Estimate tx size before fee calc: rough upper bound. P2PKH input is
     * ~148 bytes, P2WPKH is ~105. Use 160/input as a safe upper bound.
     * Output is ~34 bytes. Plus fixed 10 bytes of overhead. */
    size_t estSize = 10 + (size_t)inputCount * 160 + 34;
    uint64_t fee = ((uint64_t)estSize * (uint64_t)feePerKb) / 1000;
    if (fee < 1000) fee = 1000; /* DGB min relay */

    /* ── Amount-provenance fee-sanity guard (bug #2, fund-loss). ──
     * The legacy P2PKH sighash does NOT commit to input amounts, so a stale or
     * under-reported amounts[] still produces a VALID signature. The tx then
     * spends the REAL on-chain prevout value and the unreported remainder
     * (realValue - outAmount) is silently burned to fee by the network. We
     * cannot verify a FOREIGN prevout on-device without fetching it, but we
     * CAN refuse the pathological under-report: on a multi-input consolidation
     * a legitimate total dwarfs the fee, so a computed fee that is >= 5% of the
     * reported total (fee*20 >= totalIn) means the amounts are almost certainly
     * stale/under-reported. Refuse rather than sign a lopsided sweep.
     * Single-input sweeps are exempt — a lone small UTXO can legitimately have
     * the fee be a meaningful fraction of its value (the dust check below still
     * protects that case). fee*20 cannot overflow: fee is bounded by
     * estSize*feePerKb. */
    if (inputCount > 1 && totalIn <= fee * 20) {
        LOGW("buildAndSignLegacySweep: fee-sanity guard tripped "
             "(inputs=%d fee=%llu totalIn=%llu) — refusing under-reported sweep",
             (int)inputCount, (unsigned long long)fee, (unsigned long long)totalIn);
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    if (totalIn <= fee + 546 /* dust */) {
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    uint64_t outAmount = totalIn - fee;
    BRTransactionAddOutput(tx, outAmount, destScript, destScriptLen);

    /* ── Sign. forkId=0 for DGB (no BCH/BSV replay). ── */
    int signOk = BRTransactionSign(tx, 0, keys, (size_t)inputCount);

    for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
    free(keys);
    (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
    seed_buffer_release(&seed);

    if (!signOk || !BRTransactionIsSigned(tx)) {
        LOGW("buildAndSignLegacySweep: signing failed (signOk=%d)", signOk);
        BRTransactionFree(tx);
        return NULL;
    }

    /* ── Serialize and return hex. ── */
    size_t serLen = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *ser = (uint8_t *)malloc(serLen);
    if (!ser) { BRTransactionFree(tx); return NULL; }
    BRTransactionSerialize(tx, ser, serLen);

    char *hex = (char *)malloc(serLen * 2 + 1);
    if (!hex) { free(ser); BRTransactionFree(tx); return NULL; }
    bytes_to_hex(ser, serLen, hex);

    BRTransactionFree(tx);
    free(ser);

    jstring result = (*env)->NewStringUTF(env, hex);
    free(hex);
    return result;
}

/**
 * Test-support: parse a serialized transaction (hex) and report whether
 * BRTransactionIsSigned() holds. Used by the Layer-B signed-tx known-answer
 * vector (LegacySweepSignedTxKatTest) to assert the sweep signer emits a
 * fully-signed, consensus-shaped tx without a live wallet. Stateless; touches
 * no native global.
 *
 * Kotlin signature:
 *   external fun isRawTransactionSigned(rawTxHex: String): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isRawTransactionSigned(
    JNIEnv *env, jobject thiz, jstring rawTxHex)
{
    (void)thiz;
    if (!rawTxHex) return JNI_FALSE;

    const char *hex = (*env)->GetStringUTFChars(env, rawTxHex, NULL);
    if (!hex) return JNI_FALSE;

    size_t hexLen = strlen(hex);
    if (hexLen == 0 || (hexLen & 1)) {
        (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
        return JNI_FALSE;
    }

    size_t bufLen = hexLen / 2;
    uint8_t *buf = (uint8_t *)malloc(bufLen);
    if (!buf) {
        (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
        return JNI_FALSE;
    }

    size_t n = hex_to_bytes(hex, buf, bufLen);
    (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
    if (n != bufLen) { free(buf); return JNI_FALSE; }

    BRTransaction *tx = BRTransactionParse(buf, bufLen);
    free(buf);
    if (!tx) {
        LOGW("isRawTransactionSigned: BRTransactionParse failed");
        return JNI_FALSE;
    }

    jboolean isSigned = BRTransactionIsSigned(tx) ? JNI_TRUE : JNI_FALSE;
    BRTransactionFree(tx);
    return isSigned;
}

/**
 * Build and sign a transaction from a FOREIGN seed with CALLER-SPECIFIED outputs.
 *
 * buildAndSignLegacySweep derives keys from any seed but can only produce one plain output.
 * buildAndSignAssetTransferTx builds arbitrary outputs — DigiAsset markers, OP_RETURN and all —
 * but signs with g_wallet's session seed, so it cannot touch a wallet the user is migrating FROM.
 * Moving a DigiAsset out of an old wallet needs both halves, which is this.
 *
 * The DigiAsset structure is decided in Kotlin and arrives as outputAddresses / outputAmounts /
 * outputScriptsHex, exactly as the asset-send path already does it. An empty address means the
 * output is a raw script (the OP_RETURN marker) taken from outputScriptsHex instead.
 *
 * ## The fee is IMPLIED, and that is the dangerous part
 *
 * The sweep computes `out = totalIn - fee`, so it cannot leak value. Here the caller states the
 * outputs, and whatever is left over becomes the fee — including anything the caller forgot to
 * account for. A transfer that spends a 6,000-sat asset UTXO plus a 100,000-sat fee UTXO and
 * writes back only the asset output burns roughly 100,000 sats on a transaction that should cost
 * about 40,000. Nothing rejects that: it is a perfectly valid transaction, and the miner keeps
 * the difference.
 *
 * So the implied fee is checked in BOTH directions before signing:
 *   too LOW  — below the relay minimum, the transaction simply never confirms.
 *   too HIGH — far above what the size justifies, which means the caller lost track of value.
 *              Refused rather than signed, because the loss is silent and irreversible.
 *
 * @return signed transaction hex, or NULL on any refusal (each logged with its reason).
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_buildAndSignForeignAssetTransfer(
    JNIEnv *env, jobject thiz,
    jbyteArray seedBytes,
    jstring hmacKey,
    jintArray prefixPath,
    jobjectArray txidsHex,
    jintArray vouts,
    jlongArray amounts,
    jintArray chainIndices,
    jintArray addressIndices,
    jobjectArray scriptPubKeysHex,
    jobjectArray outputAddresses,
    jlongArray outputAmounts,
    jobjectArray outputScriptsHex,
    jlong feePerKb)
{
    (void)thiz;
    if (!seedBytes || !hmacKey || !prefixPath || !txidsHex || !vouts || !amounts ||
        !chainIndices || !addressIndices || !scriptPubKeysHex ||
        !outputAddresses || !outputAmounts || !outputScriptsHex) return NULL;

    const jsize inputCount = (*env)->GetArrayLength(env, txidsHex);
    const jsize outputCount = (*env)->GetArrayLength(env, outputAddresses);
    if (inputCount <= 0 || outputCount <= 0) return NULL;
    if ((*env)->GetArrayLength(env, vouts) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, amounts) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, chainIndices) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, addressIndices) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, scriptPubKeysHex) != inputCount) return NULL;
    if ((*env)->GetArrayLength(env, outputAmounts) != outputCount) return NULL;
    if ((*env)->GetArrayLength(env, outputScriptsHex) != outputCount) return NULL;

    const jsize seedLen = (*env)->GetArrayLength(env, seedBytes);
    jbyte *seedRaw = (*env)->GetByteArrayElements(env, seedBytes, NULL);
    if (!seedRaw) return NULL;

    /* Private copy; the caller's array goes straight back untouched. See jni_seed_buffer.h. */
    SeedBuffer seed;
    const int seedOk = seed_buffer_take(&seed, seedRaw, (size_t)seedLen);
    (*env)->ReleaseByteArrayElements(env, seedBytes, seedRaw, JNI_ABORT);
    if (!seedOk) {
        LOGW("buildAndSignForeignAssetTransfer: rejecting a seed of %d bytes", (int)seedLen);
        return NULL;
    }

    const char *hmac = (*env)->GetStringUTFChars(env, hmacKey, NULL);
    if (!hmac) { seed_buffer_release(&seed); return NULL; }

    const jsize prefixLen = (*env)->GetArrayLength(env, prefixPath);
    jint *prefixJ = (*env)->GetIntArrayElements(env, prefixPath, NULL);

    BRKey *keys = (BRKey *)calloc((size_t)inputCount, sizeof(BRKey));
    if (!keys) {
        (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    BRTransaction *tx = BRTransactionNew();
    if (!tx) {
        free(keys);
        (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    jint  *voutJ    = (*env)->GetIntArrayElements(env, vouts, NULL);
    jlong *amtJ     = (*env)->GetLongArrayElements(env, amounts, NULL);
    jint  *chainJ   = (*env)->GetIntArrayElements(env, chainIndices, NULL);
    jint  *addrIdxJ = (*env)->GetIntArrayElements(env, addressIndices, NULL);
    jlong *outAmtJ  = (*env)->GetLongArrayElements(env, outputAmounts, NULL);

    int ok = 1;
    uint64_t totalIn = 0;
    uint32_t *fullPath = (uint32_t *)calloc((size_t)prefixLen + 2, sizeof(uint32_t));
    if (!fullPath) ok = 0;

    /* ── Inputs: derive the signing key for each, at its own (chain, index). ── */
    for (jsize i = 0; ok && i < inputCount; i++) {
        jstring txidJ   = (jstring)(*env)->GetObjectArrayElement(env, txidsHex, i);
        jstring scriptJ = (jstring)(*env)->GetObjectArrayElement(env, scriptPubKeysHex, i);
        if (!txidJ || !scriptJ) { ok = 0; break; }

        const char *txidStr   = (*env)->GetStringUTFChars(env, txidJ, NULL);
        const char *scriptStr = (*env)->GetStringUTFChars(env, scriptJ, NULL);

        uint8_t txidBytes[32];
        uint8_t scriptBytes[256];
        size_t txidLen   = txidStr   ? hex_to_bytes(txidStr, txidBytes, sizeof(txidBytes)) : 0;
        size_t scriptLen = scriptStr ? hex_to_bytes(scriptStr, scriptBytes, sizeof(scriptBytes)) : 0;

        if (txidStr)   (*env)->ReleaseStringUTFChars(env, txidJ, txidStr);
        if (scriptStr) (*env)->ReleaseStringUTFChars(env, scriptJ, scriptStr);
        (*env)->DeleteLocalRef(env, txidJ);
        (*env)->DeleteLocalRef(env, scriptJ);

        if (txidLen != 32 || scriptLen == 0 || amtJ[i] <= 0) { ok = 0; break; }

        UInt256 txHash;
        /* Display-order hex is reversed relative to internal byte order. */
        for (int b = 0; b < 32; b++) txHash.u8[b] = txidBytes[31 - b];

        for (jsize p = 0; p < prefixLen; p++) fullPath[p] = (uint32_t)prefixJ[p];
        fullPath[prefixLen]     = (uint32_t)chainJ[i];
        fullPath[prefixLen + 1] = (uint32_t)addrIdxJ[i];
        BRBIP32PrivKeyArrayPath(&keys[i], seed.bytes, seed.len,
                                hmac, fullPath, (size_t)prefixLen + 2);

        BRTransactionAddInput(tx, txHash, (uint32_t)voutJ[i], (uint64_t)amtJ[i],
                              scriptBytes, scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        totalIn += (uint64_t)amtJ[i];
    }

    /* ── Outputs: address outputs, or raw scripts for the DigiAsset OP_RETURN. ── */
    uint64_t totalOut = 0;
    for (jsize i = 0; ok && i < outputCount; i++) {
        jstring addrJ   = (jstring)(*env)->GetObjectArrayElement(env, outputAddresses, i);
        jstring scriptJ = (jstring)(*env)->GetObjectArrayElement(env, outputScriptsHex, i);
        if (!addrJ) { ok = 0; break; }

        const char *addrStr = (*env)->GetStringUTFChars(env, addrJ, NULL);
        uint8_t scriptBytes[256];
        size_t scriptLen = 0;

        if (addrStr && addrStr[0] != '\0') {
            scriptLen = BRAddressScriptPubKey(scriptBytes, sizeof(scriptBytes), addrStr);
            (*env)->ReleaseStringUTFChars(env, addrJ, addrStr);
            if (scriptLen == 0) {
                LOGW("buildAndSignForeignAssetTransfer: invalid output address at %d", (int)i);
                ok = 0;
            }
        } else {
            if (addrStr) (*env)->ReleaseStringUTFChars(env, addrJ, addrStr);
            if (!scriptJ) { ok = 0; }
            else {
                const char *scriptStr = (*env)->GetStringUTFChars(env, scriptJ, NULL);
                scriptLen = scriptStr ? hex_to_bytes(scriptStr, scriptBytes, sizeof(scriptBytes)) : 0;
                if (scriptStr) (*env)->ReleaseStringUTFChars(env, scriptJ, scriptStr);
                if (scriptLen == 0) {
                    LOGW("buildAndSignForeignAssetTransfer: empty raw script at %d", (int)i);
                    ok = 0;
                }
            }
        }

        (*env)->DeleteLocalRef(env, addrJ);
        if (scriptJ) (*env)->DeleteLocalRef(env, scriptJ);

        if (ok) {
            if (outAmtJ[i] < 0) { ok = 0; break; }
            BRTransactionAddOutput(tx, (uint64_t)outAmtJ[i], scriptBytes, scriptLen);
            totalOut += (uint64_t)outAmtJ[i];
        }
    }

    (*env)->ReleaseIntArrayElements(env, vouts, voutJ, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, amounts, amtJ, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, chainIndices, chainJ, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, addressIndices, addrIdxJ, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, outputAmounts, outAmtJ, JNI_ABORT);
    if (fullPath) { secure_zero(fullPath, ((size_t)prefixLen + 2) * sizeof(uint32_t)); free(fullPath); }
    (*env)->ReleaseIntArrayElements(env, prefixPath, prefixJ, JNI_ABORT);

    if (!ok || totalOut > totalIn) {
        if (totalOut > totalIn) {
            LOGW("buildAndSignForeignAssetTransfer: outputs %llu exceed inputs %llu",
                 (unsigned long long)totalOut, (unsigned long long)totalIn);
        }
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        seed_buffer_release(&seed);
        return NULL;
    }

    /* ── The implied fee, checked BOTH ways. See foreign_tx_fee_guard.h. ──
     * The band lives in a JNI-free header so foreign_tx_fee_guard_kat can pin its exact
     * boundaries on the host; reaching them on a device needs a real asset and a real fee
     * UTXO, which is not a test you run per commit. */
    uint64_t impliedFee = 0, expectedFee = 0;
    const ForeignTxFeeVerdict feeVerdict = foreign_tx_fee_check(
        totalIn, totalOut, (size_t)inputCount, (size_t)outputCount,
        (uint64_t)feePerKb, &impliedFee, &expectedFee);

    switch (feeVerdict) {
        case FOREIGN_TX_FEE_OK:
            break;
        case FOREIGN_TX_FEE_TOO_LOW:
            LOGW("buildAndSignForeignAssetTransfer: implied fee %llu below the %llu this size "
                 "needs — would not relay",
                 (unsigned long long)impliedFee, (unsigned long long)expectedFee);
            ok = 0;
            break;
        case FOREIGN_TX_FEE_TOO_HIGH:
            /* The caller lost track of value — almost always a missing change output. Burning
             * it is silent and irreversible, so refuse rather than sign. */
            LOGW("buildAndSignForeignAssetTransfer: implied fee %llu is over %dx the expected "
                 "%llu — refusing to burn the difference (missing change output?)",
                 (unsigned long long)impliedFee, FOREIGN_TX_MAX_FEE_FACTOR,
                 (unsigned long long)expectedFee);
            ok = 0;
            break;
        case FOREIGN_TX_FEE_OVERSPEND:
            /* Already caught above; kept so a future reordering cannot fall through silently. */
            LOGW("buildAndSignForeignAssetTransfer: outputs exceed inputs");
            ok = 0;
            break;
    }

    int signOk = 0;
    if (ok) signOk = BRTransactionSign(tx, 0, keys, (size_t)inputCount);

    for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
    free(keys);
    (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
    seed_buffer_release(&seed);

    if (!ok || !signOk || !BRTransactionIsSigned(tx)) {
        LOGW("buildAndSignForeignAssetTransfer: signing failed or refused (ok=%d signOk=%d)", ok, signOk);
        BRTransactionFree(tx);
        return NULL;
    }

    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *ser = (uint8_t *)malloc(len);
    if (!ser) { BRTransactionFree(tx); return NULL; }
    len = BRTransactionSerialize(tx, ser, len);
    BRTransactionFree(tx);

    char *hex = (char *)malloc(len * 2 + 1);
    if (!hex) { free(ser); return NULL; }
    for (size_t i = 0; i < len; i++) sprintf(hex + i * 2, "%02x", ser[i]);
    hex[len * 2] = '\0';
    free(ser);

    jstring result = (*env)->NewStringUTF(env, hex);
    free(hex);
    return result;
}
