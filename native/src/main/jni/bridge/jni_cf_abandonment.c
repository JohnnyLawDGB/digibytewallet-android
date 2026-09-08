/*
 * jni_cf_abandonment.c
 *
 * JNI accessors for BRCFAbandonment.h -- the abandoned compact-filter band's
 * three decisions: fold a polled watermark into the band, is the band retired,
 * is its coverage proven.
 *
 * Test-support only; production Kotlin does not route through here. See
 * jni_cf_recovery_policy.c for the rationale: CfAbandonmentStore.kt keeps its
 * mirror because its suite runs on the host JVM, and CfAbandonmentParityTest
 * binds the mirror to this C. The direction that matters is coverageIsProven's
 * ledger-start qualifier -- a mirror that drifts there clears a band the
 * ledger never looked at, which is a silent balance under-report.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRCFAbandonment.h"

/* Fold `abandonedBelow` into the band described by (existingLow, existingHigh,
 * existingLowKnown), or into no band when hasExisting is false.
 * Returns a long[4]: { changed, low, high, lowKnown }. When changed == 0 the
 * other three echo the input band (or 0s). */
JNIEXPORT jlongArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_cfAbandonedBandNext(JNIEnv *env, jobject thiz,
        jboolean hasExisting, jlong existingLow, jlong existingHigh, jboolean existingLowKnown,
        jlong abandonedBelow, jlong lowHint) {
    (void)thiz;
    BRCFAbandonedBand existing, next;
    jlong out[4] = { 0, 0, 0, 0 };
    int changed;

    existing.low = (uint32_t)existingLow;
    existing.high = (uint32_t)existingHigh;
    existing.lowKnown = existingLowKnown ? 1 : 0;
    /* Negative watermarks cannot happen natively (uint32_t); a Kotlin test
     * passing one is asking about "nothing abandoned". */
    changed = BRCFAbandonedBandNext(hasExisting ? &existing : NULL,
                                    abandonedBelow > 0 ? (uint32_t)abandonedBelow : 0,
                                    lowHint > 0 ? (uint32_t)lowHint : 0,
                                    &next);
    out[0] = changed;
    if (changed) {
        out[1] = next.low; out[2] = next.high; out[3] = next.lowKnown;
    } else if (hasExisting) {
        out[1] = existing.low; out[2] = existing.high; out[3] = existing.lowKnown;
    }

    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (! arr) return NULL;
    (*env)->SetLongArrayRegion(env, arr, 0, 4, out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_cfAbandonedBandIsRetired(JNIEnv *env, jobject thiz,
        jlong bandLow, jlong abandonedBelow) {
    (void)env; (void)thiz;
    return BRCFAbandonedBandIsRetired((uint32_t)bandLow, (uint32_t)abandonedBelow)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_cfAbandonedBandCoverageIsProven(JNIEnv *env, jobject thiz,
        jlong bandLow, jlong bandHigh, jboolean lowKnown,
        jlong ledgerStart, jlong scannedThrough, jlong abandonedBelow, jlong gaveUp) {
    (void)env; (void)thiz;
    /* A negative reading is as much a failed read as a zero one. */
    if (ledgerStart < 0 || scannedThrough < 0 || abandonedBelow < 0 || gaveUp < 0) return JNI_FALSE;
    return BRCFAbandonedBandCoverageIsProven((uint32_t)bandLow, (uint32_t)bandHigh,
                                             lowKnown ? 1 : 0,
                                             (uint32_t)ledgerStart, (uint32_t)scannedThrough,
                                             (uint32_t)abandonedBelow, (size_t)gaveUp)
        ? JNI_TRUE : JNI_FALSE;
}
