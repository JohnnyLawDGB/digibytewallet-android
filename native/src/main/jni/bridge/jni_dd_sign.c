/* DigiDollar taproot signer (ADR-0001: Kotlin builds the bytes, C signs
 * the digests).
 *
 * BIP340 Schnorr signing and BIP86 Owner-key derivation over the seed
 * accessor API — private keys never cross the JNI boundary. The Kotlin
 * protocol module computes BIP341 sighash digests; this file signs them
 * with either the tap-tweaked key (key-path spends) or the untweaked key
 * (script-path / tapleaf spends).
 *
 * schnorrsig + extrakeys symbols come from the secp256k1 copy vendored in
 * digibytewallet-core (v0.4.1), compiled into this library by the BRKey.c
 * amalgamation.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include "jni_bridge.h"
#include "BRKey.h"
#include "BRCrypto.h"
#include "BRBIP32Sequence.h"
#include "BRInt.h"

#include "secp256k1.h"
#include "secp256k1_extrakeys.h"
#include "secp256k1_schnorrsig.h"

#define BIP86_PURPOSE 86
#define DD_ACCOUNT    0

static secp256k1_context *g_ddCtx = NULL;
static pthread_once_t g_ddCtxOnce = PTHREAD_ONCE_INIT;

static void _dd_ctx_init(void) {
    g_ddCtx = secp256k1_context_create(SECP256K1_CONTEXT_NONE);
    if (g_ddCtx) {
        uint8_t rnd[32];
        arc4random_buf(rnd, sizeof(rnd));
        secp256k1_context_randomize(g_ddCtx, rnd);
        secure_zero(rnd, sizeof(rnd));
    }
}

static secp256k1_context *_dd_ctx(void) {
    pthread_once(&g_ddCtxOnce, _dd_ctx_init);
    return g_ddCtx;
}

/* BIP340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg) */
static void _tagged_hash(const char *tag, const uint8_t *msg, size_t msgLen,
                         uint8_t out32[32]) {
    uint8_t tagHash[32];
    BRSHA256(tagHash, tag, strlen(tag));

    uint8_t buf[64 + 64]; /* tagHash twice + up to 64 bytes of message */
    memcpy(buf, tagHash, 32);
    memcpy(buf + 32, tagHash, 32);
    memcpy(buf + 64, msg, msgLen);
    BRSHA256(out32, buf, 64 + msgLen);
    secure_zero(buf, sizeof(buf));
}

/* BIP341 tap tweak of an x-only key with no script tree: t = H_TapTweak(x). */
static void _tap_tweak(const uint8_t xonly32[32], uint8_t out32[32]) {
    _tagged_hash("TapTweak", xonly32, 32, out32);
}

/* Derive the BIP86 Owner keypair m/86'/coin'/0'/chain/index from the seed.
 * Returns 1 on success. Caller must zero `kp`. */
static int _derive_owner_keypair(secp256k1_keypair *kp, uint32_t coinType,
                                 uint32_t chain, uint32_t index) {
    if (!seed_is_valid()) return 0;

    const uint32_t path[] = {
        BIP86_PURPOSE | BIP32_HARD,
        coinType | BIP32_HARD,
        DD_ACCOUNT | BIP32_HARD,
        chain,
        index,
    };
    BRKey key;
    memset(&key, 0, sizeof(key));
    if (!seed_derive_path_key(&key, path, sizeof(path) / sizeof(path[0]))) return 0;

    int ok = secp256k1_keypair_create(_dd_ctx(), kp, key.secret.u8);
    BRKeyClean(&key);
    return ok;
}

/* ---------- ddDeriveOwnerKey: x-only Owner pubkey for (coin, chain, index) ---------- */

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddDeriveOwnerKey(JNIEnv *env, jobject thiz,
                                                           jint coinType, jint chain,
                                                           jint index) {
    (void)thiz;

    secp256k1_keypair kp;
    if (!_derive_owner_keypair(&kp, (uint32_t)coinType, (uint32_t)chain, (uint32_t)index)) {
        return NULL;
    }

    secp256k1_xonly_pubkey xonly;
    uint8_t out[32];
    int ok = secp256k1_keypair_xonly_pub(_dd_ctx(), &xonly, NULL, &kp) &&
             secp256k1_xonly_pubkey_serialize(_dd_ctx(), out, &xonly);
    secure_zero(&kp, sizeof(kp));
    if (!ok) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, 32);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte *)out);
    return result;
}

/* ---------- ddSignDigest: BIP340-sign a 32-byte digest with an Owner key ---------- */

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddSignDigest(JNIEnv *env, jobject thiz,
                                                       jbyteArray digest, jint coinType,
                                                       jint chain, jint index,
                                                       jboolean tapTweak) {
    (void)thiz;

    if (!digest || (*env)->GetArrayLength(env, digest) != 32) return NULL;
    uint8_t msg[32];
    (*env)->GetByteArrayRegion(env, digest, 0, 32, (jbyte *)msg);

    secp256k1_keypair kp;
    if (!_derive_owner_keypair(&kp, (uint32_t)coinType, (uint32_t)chain, (uint32_t)index)) {
        return NULL;
    }

    int ok = 1;
    if (tapTweak) {
        /* Key-path spend: sign with the output key = internal key tweaked by
         * H_TapTweak(internal). Script-path leaf CHECKSIGs verify the
         * UNtweaked Owner key, so they skip this.
         *
         * The tweak deliberately commits to NO script tree (no merkle-root
         * param): DigiDollar DD-token outputs are key-path-only, and the
         * Collateral output's internal key is a NUMS point, so its key path
         * can never be signed — no output this wallet signs key-path for
         * carries a script tree. */
        secp256k1_xonly_pubkey xonly;
        uint8_t xser[32], tweak[32];
        ok = secp256k1_keypair_xonly_pub(_dd_ctx(), &xonly, NULL, &kp) &&
             secp256k1_xonly_pubkey_serialize(_dd_ctx(), xser, &xonly);
        if (ok) {
            _tap_tweak(xser, tweak);
            ok = secp256k1_keypair_xonly_tweak_add(_dd_ctx(), &kp, tweak);
        }
    }

    uint8_t sig[64];
    if (ok) {
        uint8_t aux[32];
        arc4random_buf(aux, sizeof(aux));
        ok = secp256k1_schnorrsig_sign32(_dd_ctx(), sig, msg, &kp, aux);
        secure_zero(aux, sizeof(aux));
    }
    secure_zero(&kp, sizeof(kp));
    if (!ok) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, 64);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 64, (const jbyte *)sig);
    return result;
}

/* ---------- KAT surface: raw-key operations for the BIP340/341 vector tests ---------- */
/* These take caller-supplied secret keys, so they are for test vectors only —
 * production signing goes through the seed-derived paths above. Compiled out
 * of release builds (NDEBUG) so no raw-secret-key JNI surface ships. */

#ifndef NDEBUG
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddSchnorrSign(JNIEnv *env, jobject thiz,
                                                        jbyteArray seckey, jbyteArray msg,
                                                        jbyteArray auxRand) {
    (void)thiz;

    if (!seckey || (*env)->GetArrayLength(env, seckey) != 32) return NULL;
    if (!msg || (*env)->GetArrayLength(env, msg) != 32) return NULL;
    if (!auxRand || (*env)->GetArrayLength(env, auxRand) != 32) return NULL;

    uint8_t sk[32], m[32], aux[32], sig[64];
    (*env)->GetByteArrayRegion(env, seckey, 0, 32, (jbyte *)sk);
    (*env)->GetByteArrayRegion(env, msg, 0, 32, (jbyte *)m);
    (*env)->GetByteArrayRegion(env, auxRand, 0, 32, (jbyte *)aux);

    secp256k1_keypair kp;
    int ok = secp256k1_keypair_create(_dd_ctx(), &kp, sk) &&
             secp256k1_schnorrsig_sign32(_dd_ctx(), sig, m, &kp, aux);
    secure_zero(sk, sizeof(sk));
    secure_zero(&kp, sizeof(kp));
    if (!ok) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, 64);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 64, (const jbyte *)sig);
    return result;
}
#endif /* !NDEBUG */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddSchnorrVerify(JNIEnv *env, jobject thiz,
                                                          jbyteArray pubkey, jbyteArray msg,
                                                          jbyteArray sig) {
    (void)thiz;

    if (!pubkey || (*env)->GetArrayLength(env, pubkey) != 32) return JNI_FALSE;
    if (!msg || (*env)->GetArrayLength(env, msg) != 32) return JNI_FALSE;
    if (!sig || (*env)->GetArrayLength(env, sig) != 64) return JNI_FALSE;

    uint8_t pk[32], m[32], s[64];
    (*env)->GetByteArrayRegion(env, pubkey, 0, 32, (jbyte *)pk);
    (*env)->GetByteArrayRegion(env, msg, 0, 32, (jbyte *)m);
    (*env)->GetByteArrayRegion(env, sig, 0, 64, (jbyte *)s);

    secp256k1_xonly_pubkey xonly;
    if (!secp256k1_xonly_pubkey_parse(_dd_ctx(), &xonly, pk)) return JNI_FALSE;
    return secp256k1_schnorrsig_verify(_dd_ctx(), s, m, 32, &xonly) ? JNI_TRUE : JNI_FALSE;
}

#ifndef NDEBUG
JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddXOnlyPubKey(JNIEnv *env, jobject thiz,
                                                        jbyteArray seckey) {
    (void)thiz;

    if (!seckey || (*env)->GetArrayLength(env, seckey) != 32) return NULL;
    uint8_t sk[32], out[32];
    (*env)->GetByteArrayRegion(env, seckey, 0, 32, (jbyte *)sk);

    secp256k1_keypair kp;
    secp256k1_xonly_pubkey xonly;
    int ok = secp256k1_keypair_create(_dd_ctx(), &kp, sk) &&
             secp256k1_keypair_xonly_pub(_dd_ctx(), &xonly, NULL, &kp) &&
             secp256k1_xonly_pubkey_serialize(_dd_ctx(), out, &xonly);
    secure_zero(sk, sizeof(sk));
    secure_zero(&kp, sizeof(kp));
    if (!ok) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, 32);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte *)out);
    return result;
}
#endif /* !NDEBUG */

/* ---------- ddXOnlyTweakAdd: EC point tweak for Kotlin output construction ---------- */
/* Public-key-only (no secrets): returns parity byte (0/1) followed by the
 * 32-byte x coordinate of (xonlyKey + tweak*G). The Kotlin protocol module
 * computes the tagged-hash tweaks itself and uses this for taproot output
 * keys (DD-token and Collateral outputs) and control-block parity. */

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_ddXOnlyTweakAdd(JNIEnv *env, jobject thiz,
                                                          jbyteArray xonlyKey,
                                                          jbyteArray tweak) {
    (void)thiz;

    if (!xonlyKey || (*env)->GetArrayLength(env, xonlyKey) != 32) return NULL;
    if (!tweak || (*env)->GetArrayLength(env, tweak) != 32) return NULL;

    uint8_t xk[32], tw[32];
    (*env)->GetByteArrayRegion(env, xonlyKey, 0, 32, (jbyte *)xk);
    (*env)->GetByteArrayRegion(env, tweak, 0, 32, (jbyte *)tw);

    secp256k1_xonly_pubkey base;
    secp256k1_pubkey tweaked;
    secp256k1_xonly_pubkey tweakedX;
    int parity = 0;
    uint8_t outX[32];

    if (!secp256k1_xonly_pubkey_parse(_dd_ctx(), &base, xk) ||
        !secp256k1_xonly_pubkey_tweak_add(_dd_ctx(), &tweaked, &base, tw) ||
        !secp256k1_xonly_pubkey_from_pubkey(_dd_ctx(), &tweakedX, &parity, &tweaked) ||
        !secp256k1_xonly_pubkey_serialize(_dd_ctx(), outX, &tweakedX)) {
        return NULL;
    }

    uint8_t out[33];
    out[0] = (uint8_t)parity;
    memcpy(out + 1, outX, 32);

    jbyteArray result = (*env)->NewByteArray(env, 33);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 33, (const jbyte *)out);
    return result;
}
