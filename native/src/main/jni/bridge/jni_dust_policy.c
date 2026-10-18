/*
 * jni_dust_policy.c
 *
 * JNI accessors for BRDustPolicy.h -- the dust line an output is held to, by its script, and the
 * DigiDollar token exemption.
 *
 * Test-support only; production Kotlin does not route through here (TransactionBuilder calls
 * DustPolicy.kt). See jni_cf_recovery_policy.c for why the Kotlin mirror is kept: the C header is
 * the source of truth (iOS imports it), and DustPolicyParityTest binds the two.
 *
 * dustDigiDollarTxType is the core's own BRDigiDollarTxType, which the dust rule takes its
 * "is this a DigiDollar transaction" from; it is here so the parity test can bind
 * DustPolicy.isDigiDollarTransaction to it.
 *
 * A script array that cannot be read answers -1, which is no line and no decision, so the parity
 * test fails rather than matching by accident. Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRDustPolicy.h"
#include "BRDigiDollar.h"

#define DUST_BIT_IS_DUST 1
#define DUST_BIT_EXEMPT  2

/* BRDustThreshold of script, in satoshis. A null or empty array is the empty script. */
jlong JNICALL
NativeBridge_dustThreshold(JNIEnv *env, jobject thiz, jbyteArray script) {
    jsize len = 0;
    jbyte *bytes;
    int64_t line;

    (void)thiz;
    if (script) len = (*env)->GetArrayLength(env, script);
    if (len <= 0) return (jlong)BRDustThreshold(NULL, 0);
    bytes = (*env)->GetByteArrayElements(env, script, NULL);
    if (! bytes) return -1;
    line = BRDustThreshold((const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, script, bytes, JNI_ABORT);
    return (jlong)line;
}

/* The decision for one output, packed: bit 0 = BRDustIsDust, bit 1 = BRDustIsDigiDollarExempt. */
jint JNICALL
NativeBridge_dustDecide(JNIEnv *env, jobject thiz, jlong amount, jbyteArray script,
                        jboolean txIsDigiDollar) {
    jsize len = 0;
    jbyte *bytes = NULL;
    int dd = txIsDigiDollar ? 1 : 0, r;

    (void)thiz;
    if (script) len = (*env)->GetArrayLength(env, script);
    if (len > 0) {
        bytes = (*env)->GetByteArrayElements(env, script, NULL);
        if (! bytes) return -1;
    }
    else len = 0;
    r = (BRDustIsDust((int64_t)amount, (const uint8_t *)bytes, (size_t)len, dd) ? DUST_BIT_IS_DUST : 0) |
        (BRDustIsDigiDollarExempt((int64_t)amount, (const uint8_t *)bytes, (size_t)len, dd) ? DUST_BIT_EXEMPT : 0);
    if (bytes) (*env)->ReleaseByteArrayElements(env, script, bytes, JNI_ABORT);
    return (jint)r;
}

/* BRDigiDollarTxType of a transaction with this version: 1-3, 0 for none, -1 if no transaction
 * could be made. Kotlin's Int carries the same 32 bits as BRTransaction.version. */
jint JNICALL
NativeBridge_dustDigiDollarTxType(JNIEnv *env, jobject thiz, jint version) {
    BRTransaction *tx;
    int type;

    (void)env; (void)thiz;
    tx = BRTransactionNew();
    if (! tx) return -1;
    tx->version = (uint32_t)version;
    type = BRDigiDollarTxType(tx);
    BRTransactionFree(tx);
    return (jint)type;
}

/* BR_DUST_RELAY_FEE_PER_KB, so the Kotlin constant is asserted against the header. */
jlong JNICALL
NativeBridge_dustRelayFeePerKb(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)BR_DUST_RELAY_FEE_PER_KB;
}
