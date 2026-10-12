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

/* ---- The message's bytes, as the app sends them ----
 *
 * A message is signed over the bytes the app sends for it: the standard UTF-8 of the Java string,
 * which is what OkHttp and okio write. GetStringUTFChars gives JNI's modified UTF-8 instead, which
 * writes a character outside the Basic Multilingual Plane (an emoji) as two 3-byte halves and U+0000
 * as two bytes, so a signature over it would not be over the bytes sent. The bytes are read here from
 * the string's UTF-16 code units; a surrogate without its partner is written '?', as Java's and
 * okio's encoders write it. Returns a buffer the caller frees, holding *outLen bytes, or NULL.
 */
static uint8_t *message_utf8(JNIEnv *env, jstring message, size_t *outLen)
{
    jsize n = (*env)->GetStringLength(env, message);
    if (n < 0) return NULL;
    const jchar *units = (*env)->GetStringChars(env, message, NULL);
    if (!units) return NULL;
    /* A unit takes at most 3 bytes; a surrogate pair (two units) takes 4. */
    uint8_t *out = malloc((size_t)n * 3 + 1);
    if (!out) {
        (*env)->ReleaseStringChars(env, message, units);
        return NULL;
    }
    size_t len = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t c = units[i];
        if (c < 0x80) {
            out[len++] = (uint8_t)c;
        } else if (c < 0x800) {
            out[len++] = (uint8_t)(0xC0 | (c >> 6));
            out[len++] = (uint8_t)(0x80 | (c & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
            uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + ((uint32_t)units[i + 1] - 0xDC00);
            i++;
            out[len++] = (uint8_t)(0xF0 | (cp >> 18));
            out[len++] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
            out[len++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
            out[len++] = (uint8_t)(0x80 | (cp & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            out[len++] = '?';
        } else {
            out[len++] = (uint8_t)(0xE0 | (c >> 12));
            out[len++] = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
            out[len++] = (uint8_t)(0x80 | (c & 0x3F));
        }
    }
    (*env)->ReleaseStringChars(env, message, units);
    *outLen = len;
    return out;
}

#ifdef SIGN_MESSAGE_BYTES_UNFIXED
/* Comparison arm of identity_sign_bytes_kat only: the message read as JNI's modified UTF-8, as before. */
static const char *read_message(JNIEnv *env, jstring message, size_t *outLen)
{
    const char *chars = (*env)->GetStringUTFChars(env, message, NULL);
    if (chars) *outLen = strlen(chars);
    return chars;
}
static void release_message(JNIEnv *env, jstring message, const char *chars)
{
    (*env)->ReleaseStringUTFChars(env, message, chars);
}
#else
/* The message's bytes as sent (message_utf8); release with release_message. */
static const char *read_message(JNIEnv *env, jstring message, size_t *outLen)
{
    return (const char *)message_utf8(env, message, outLen);
}
static void release_message(JNIEnv *env, jstring message, const char *chars)
{
    (void)env; (void)message;
    free((void *)chars);
}
#endif

/* ---- Shared signing core ----
 *
 * Signs msgChars with an ALREADY-DERIVED key using the Bitcoin-style
 * "\x19DigiByte Signed Message:\n" scheme and returns "address|base64sig".
 * Cleans the key before returning in every path. Both signMessage (legacy
 * m/0\'/0/0 identity) and signIdentityMessage (per-site SLIP-0013 identity,
 * docs/specs/digiid-key-isolation.md) funnel through here so the two cannot
 * drift in message formatting.
 */
static jstring sign_with_key(JNIEnv *env, BRKey *key, const char *msgChars,
                             size_t msgLen, jint addressFormat, const char *tag) {
    key->compressed = 1;

    /* Get the address for this key */
    char addr[75];
    memset(addr, 0, sizeof(addr));
    if (addressFormat == 2) {
        BRKeySegwitAddress(key, addr, sizeof(addr), 0);
    } else {
        BRKeyAddress(key, addr, sizeof(addr));
    }

    if (addr[0] == '\0') {
        LOGW("%s: failed to derive address", tag);
        BRKeyClean(key);
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
        BRKeyClean(key);
        return NULL;
    }

    memcpy(payload, header, headerLen);
    memcpy(payload + headerLen, varint, varintLen);
    memcpy(payload + headerLen + varintLen, msgChars, msgLen);

    /* Double SHA256 */
    UInt256 md;
    BRSHA256_2(&md, payload, totalLen);
    free(payload);

    /* Compact recoverable signature (65 bytes: 1 recovery + 32 r + 32 s) */
    uint8_t compactSig[65];
    size_t sigLen = BRKeyCompactSign(key, compactSig, sizeof(compactSig), md);
    BRKeyClean(key);

    if (sigLen == 0) {
        LOGE("%s: BRKeyCompactSign failed", tag);
        return NULL;
    }

    /* Base64 encode the 65-byte signature */
    char sigB64[92]; /* ceil(65/3)*4 + 1 = 89 */
    base64_encode(sigB64, sizeof(sigB64), compactSig, sigLen);

    /* Return "address|base64signature" */
    char result[256];
    snprintf(result, sizeof(result), "%s|%s", addr, sigB64);

    LOGD("%s: signed successfully (sigLen=%zu)", tag, sigLen);
    return (*env)->NewStringUTF(env, result);
}

/* ---- signMessage JNI (legacy shared identity, m/0'/0/0) ---- */

jstring JNICALL
NativeBridge_signMessage(JNIEnv *env, jobject thiz,
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

    size_t msgLen = 0;
    const char *msgChars = read_message(env, message, &msgLen);
    if (!msgChars) return NULL;

    /* Derive BIP32 key: external chain (0), index 0 — first receive address.
     * This gives a deterministic address for Digi-ID across sessions. */
    BRKey key;
    memset(&key, 0, sizeof(key));
    if (!seed_derive_key(&key, 0, 0)) {
        LOGW("signMessage: failed to derive key — session locked");
        release_message(env, message, msgChars);
        return NULL;
    }

    jstring out = sign_with_key(env, &key, msgChars, msgLen, addressFormat, "signMessage");
    release_message(env, message, msgChars);
    return out;
}

/* ---- signIdentityMessage JNI (per-site SLIP-0013 identity, m/13'/A'/B'/C'/D') ----
 *
 * siteUri must be the site's canonical callback URL (scheme://host/path, no
 * query) so the per-login nonce never perturbs the identity. Address format is
 * fixed to legacy P2PKH — the format the Digi-ID ecosystem verifies against.
 */
jstring JNICALL
NativeBridge_signIdentityMessage(JNIEnv *env, jobject thiz,
                                                              jstring message, jstring siteUri,
                                                              jint index) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("signIdentityMessage: wallet not initialized");
        return NULL;
    }
    if (!seed_is_valid()) {
        LOGW("signIdentityMessage: session locked — no seed available");
        return NULL;
    }
    if (!message || !siteUri || index < 0) {
        LOGW("signIdentityMessage: bad arguments");
        return NULL;
    }

    const char *uriChars = (*env)->GetStringUTFChars(env, siteUri, NULL);
    if (!uriChars) return NULL;

    BRKey key;
    memset(&key, 0, sizeof(key));
    int derived = seed_derive_identity_key(&key, uriChars, (uint32_t)index);
    (*env)->ReleaseStringUTFChars(env, siteUri, uriChars);
    if (!derived) {
        LOGW("signIdentityMessage: identity derivation failed");
        return NULL;
    }

    size_t msgLen = 0;
    const char *msgChars = read_message(env, message, &msgLen);
    if (!msgChars) {
        BRKeyClean(&key);
        return NULL;
    }

    jstring out = sign_with_key(env, &key, msgChars, msgLen, /*addressFormat=*/0,
                                "signIdentityMessage");
    release_message(env, message, msgChars);
    return out;
}
