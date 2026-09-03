/*
 * jni_publish_outcome.c
 *
 * JNI accessor for BRPublishOutcome.h -- the publish errno -> action mapping.
 *
 * This exists ONLY so an instrumented parity test can compare the C mapping
 * against Kotlin's PublishOutcome, and -- more importantly -- assert that
 * Kotlin's hardcoded errno constants match THIS PLATFORM's real values.
 * Production Kotlin deliberately does NOT route through here; see
 * jni_cf_recovery_policy.c for the same rationale (the Kotlin mirror stays
 * host-JVM testable, and NativeBridge cannot load on a host JVM).
 *
 * Why the errno accessor matters more than the table comparison:
 * PublishOutcome.kt hardcodes ENOTCONN = 107 and ETIMEDOUT = 110. Those are
 * Linux values, so they are CORRECT on Android -- there is no live Android bug.
 * They are 57 and 60 on Darwin, so an iOS port that copied them would make
 * UNCONFIRMED_DELIVERY unreachable and silently lose the "went out, nobody
 * echoed it back" signal. publishErrnoValue() lets the parity test pin the
 * assumption to the platform instead of to a comment, so nobody "tidies" those
 * constants later without the test noticing.
 *
 * Returns a packed int rather than a struct so the boundary stays a jint:
 *   bits 0-1 (0x03) = kind (BRPublishKind)
 *   bit  2   (0x04) = shouldRetry
 *   bit  3   (0x08) = isTerminal
 * Touches no wallet state, takes no lock, safe before a wallet exists.
 */

#include "jni_bridge.h"
#include "BRPublishOutcome.h"

#define PUBLISH_KIND_MASK      0x03
#define PUBLISH_BIT_SHOULD_RETRY 0x04
#define PUBLISH_BIT_IS_TERMINAL  0x08

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_publishOutcomeOf(JNIEnv *env, jobject thiz,
                                                            jint error) {
    (void)env;
    (void)thiz;

    BRPublishOutcome o = BRPublishOutcomeOf((int)error);

    return ((jint)o.kind & PUBLISH_KIND_MASK)
         | (o.shouldRetry ? PUBLISH_BIT_SHOULD_RETRY : 0)
         | (o.isTerminal  ? PUBLISH_BIT_IS_TERMINAL  : 0);
}

/*
 * This platform's errno value for the given index (0 = EINVAL, 1 = ENOTCONN,
 * 2 = ETIMEDOUT). 0 for an out-of-range index.
 *
 * The point of exposing this: on Android it returns the Linux values, so the
 * parity test passes today and DOCUMENTS the assumption. The same header
 * compiled for iOS returns Darwin's, which is what makes the Swift side correct
 * without anyone maintaining a second table.
 */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_publishErrnoValue(JNIEnv *env, jobject thiz,
                                                             jint index) {
    (void)env;
    (void)thiz;
    return (jint)BRPublishErrnoValue((int)index);
}
