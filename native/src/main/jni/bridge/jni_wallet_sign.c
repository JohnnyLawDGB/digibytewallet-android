/*
 * jni_wallet_sign.c
 *
 * JNI bridge for Bitcoin-style message signing (Digi-ID).
 * Derives the first external BIP32 key and signs messages using
 * compact recoverable signatures (BRKeyCompactSign).
 *
 * Returns "address|base64signature" so the caller gets the exact
 * address that corresponds to the signing key.
 */

#include "jni_bridge.h"
#include <stdio.h>

/* ---- Base64 encoding ---- */

static const char b64_table[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static size_t base64_encode(char *out, size_t outLen,
                            const uint8_t *in, size_t inLen) {
    size_t needed = ((inLen + 2) / 3) * 4 + 1;
    if (!out || outLen < needed) return needed;

    size_t pos = 0;
    for (size_t i = 0; i < inLen; i += 3) {
        uint32_t n = ((uint32_t)in[i]) << 16;
        if (i + 1 < inLen) n |= ((uint32_t)in[i + 1]) << 8;
        if (i + 2 < inLen) n |= (uint32_t)in[i + 2];

        out[pos++] = b64_table[(n >> 18) & 0x3F];
        out[pos++] = b64_table[(n >> 12) & 0x3F];
        out[pos++] = (i + 1 < inLen) ? b64_table[(n >> 6) & 0x3F] : '=';
        out[pos++] = (i + 2 < inLen) ? b64_table[n & 0x3F] : '=';
    }
    out[pos] = '\0';
    return pos;
}

/* ---- Varint encoding (Bitcoin compact size) ---- */

static size_t write_varint(uint8_t *buf, uint64_t val) {
    if (val < 0xfd) {
        buf[0] = (uint8_t)val;
        return 1;
    } else if (val <= 0xffff) {
        buf[0] = 0xfd;
        buf[1] = (uint8_t)(val & 0xff);
        buf[2] = (uint8_t)((val >> 8) & 0xff);
        return 3;
    }
    /* Messages over 64KB won't happen for Digi-ID */
    buf[0] = (uint8_t)val;
    return 1;
}

/* ---- signMessage JNI ---- */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_signMessage(JNIEnv *env, jobject thiz,
                                                       jstring message, jint addressFormat) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("signMessage: wallet not initialized");
        return NULL;
    }
    if (!seed_is_valid()) {
        LOGW("signMessage: session locked — no seed available");
        return NULL;
    }
    if (!message) {
        LOGW("signMessage: message is null");
        return NULL;
    }

    const char *msgChars = (*env)->GetStringUTFChars(env, message, NULL);
    if (!msgChars) return NULL;
    size_t msgLen = strlen(msgChars);

    /* Derive BIP32 key: external chain (0), index 0 — first receive address.
     * This gives a deterministic address for Digi-ID across sessions. */
    BRKey key;
    memset(&key, 0, sizeof(key));
    if (!seed_derive_key(&key, 0, 0)) {
        LOGW("signMessage: failed to derive key — session locked");
        (*env)->ReleaseStringUTFChars(env, message, msgChars);
        return NULL;
    }
    key.compressed = 1;

    /* Get the address for this key */
    char addr[75];
    memset(addr, 0, sizeof(addr));
    if (addressFormat == 2) {
        BRKeySegwitAddress(&key, addr, sizeof(addr), 0);
    } else {
        BRKeyAddress(&key, addr, sizeof(addr));
    }

    if (addr[0] == '\0') {
        LOGW("signMessage: failed to derive address");
        BRKeyClean(&key);
        (*env)->ReleaseStringUTFChars(env, message, msgChars);
        return NULL;
    }

    /*
     * Construct Bitcoin-style signed message:
     *   header = "\x19DigiByte Signed Message:\n"   (0x19 = 25 = strlen)
     *   payload = header + varint(msgLen) + message
     *   hash = SHA256(SHA256(payload))
     */
    static const char header[] = "\x19" "DigiByte Signed Message:\n";
    size_t headerLen = sizeof(header) - 1; /* exclude null terminator */

    uint8_t varint[9];
    size_t varintLen = write_varint(varint, msgLen);

    size_t totalLen = headerLen + varintLen + msgLen;
    uint8_t *payload = malloc(totalLen);
    if (!payload) {
        BRKeyClean(&key);
        (*env)->ReleaseStringUTFChars(env, message, msgChars);
        return NULL;
    }

    memcpy(payload, header, headerLen);
    memcpy(payload + headerLen, varint, varintLen);
    memcpy(payload + headerLen + varintLen, msgChars, msgLen);

    (*env)->ReleaseStringUTFChars(env, message, msgChars);

    /* Double SHA256 */
    UInt256 md;
    BRSHA256_2(&md, payload, totalLen);
    free(payload);

    /* Compact recoverable signature (65 bytes: 1 recovery + 32 r + 32 s) */
    uint8_t compactSig[65];
    size_t sigLen = BRKeyCompactSign(&key, compactSig, sizeof(compactSig), md);
    BRKeyClean(&key);

    if (sigLen == 0) {
        LOGE("signMessage: BRKeyCompactSign failed");
        return NULL;
    }

    /* Base64 encode the 65-byte signature */
    char sigB64[92]; /* ceil(65/3)*4 + 1 = 89 */
    base64_encode(sigB64, sizeof(sigB64), compactSig, sigLen);

    /* Return "address|base64signature" */
    char result[256];
    snprintf(result, sizeof(result), "%s|%s", addr, sigB64);

    LOGD("signMessage: signed successfully (sigLen=%zu)", sigLen);
    return (*env)->NewStringUTF(env, result);
}
