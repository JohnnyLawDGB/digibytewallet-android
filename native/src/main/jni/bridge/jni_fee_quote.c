/*
 * jni_fee_quote.c
 *
 * JNI accessors for BRFeePolicy.h's 4.1.0 rule -- what the fee field asks for, the rate a total
 * is priced at over a send's own size, and when the confirmation says the fee was raised.
 *
 * Test-support only; production Kotlin does not route through here (TransactionBuilder and the
 * send screen call FeeQuote.kt). See jni_cf_recovery_policy.c for why the Kotlin mirror is kept:
 * the C header is the source of truth (iOS imports it), and FeeQuoteParityTest binds the two.
 *
 * The fee field crosses as UTF-8 bytes, not a jstring: GetStringUTFChars hands C modified UTF-8
 * (a NUL as two bytes, a character outside the BMP as six), which is not what iOS passes the
 * parser, so the test encodes the text the way iOS does. A null array is no text.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRFeePolicy.h"

/* BRFeeRequestFor: [request, totalSats], request 0 = none, 1 = the default rate, 2 = a custom
 * total. [-1, 0] if the text could not be read. */
jlongArray JNICALL
NativeBridge_feeRequestFor(JNIEnv *env, jobject thiz, jboolean custom, jbyteArray utf8) {
    jsize len = 0;
    jbyte *bytes = NULL;
    int64_t total = 0;
    jlong out[2] = { -1, 0 };
    jlongArray result;

    (void)thiz;
    if (utf8) len = (*env)->GetArrayLength(env, utf8);
    if (len > 0) {
        bytes = (*env)->GetByteArrayElements(env, utf8, NULL);
        if (! bytes) len = -1;
    }
    if (len >= 0) {
        /* A present but empty field is text of length 0, not "no text". */
        out[0] = (jlong)BRFeeRequestFor(custom ? 1 : 0, bytes ? (const char *)bytes : (utf8 ? "" : NULL),
                                        (size_t)len, &total);
        out[1] = (jlong)total;
    }
    if (bytes) (*env)->ReleaseByteArrayElements(env, utf8, bytes, JNI_ABORT);
    result = (*env)->NewLongArray(env, 2);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 2, out);
    return result;
}

/* BRFeeRateForTotal, in sat/kB (never above INT64_MAX, so it fits a jlong). */
jlong JNICALL
NativeBridge_feeRateForTotal(JNIEnv *env, jobject thiz, jlong totalSats, jlong vsize) {
    (void)env; (void)thiz;
    return (jlong)BRFeeRateForTotal((int64_t)totalSats, (int64_t)vsize);
}

/* BRFeeRateForCustomTotal: the rate a custom total is built at over the send's own vsize. */
jlong JNICALL
NativeBridge_feeRateForCustomTotal(JNIEnv *env, jobject thiz, jlong totalSats, jlong builtVsize) {
    (void)env; (void)thiz;
    return (jlong)BRFeeRateForCustomTotal((int64_t)totalSats, (int64_t)builtVsize);
}

/* BRFeeQuoteRaised: the transaction pays more than the custom total typed. */
jboolean JNICALL
NativeBridge_feeQuoteRaised(JNIEnv *env, jobject thiz, jboolean custom, jlong typedTotalSats, jlong feeSats) {
    (void)env; (void)thiz;
    return BRFeeQuoteRaised(custom ? 1 : 0, (int64_t)typedTotalSats, (int64_t)feeSats) ? JNI_TRUE : JNI_FALSE;
}

/* DEFAULT_FEE_PER_KB, so FeeQuote.DEFAULT_FEE_PER_KB is asserted against the core. */
jlong JNICALL
NativeBridge_feeDefaultPerKb(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)DEFAULT_FEE_PER_KB;
}
