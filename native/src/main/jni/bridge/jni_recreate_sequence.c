/*
 * jni_recreate_sequence.c
 *
 * JNI accessors for BRRecreateSequence.h -- the mid-session peer-manager
 * recreate ORDER.
 *
 * Test-support only; production Kotlin does not route through here. See
 * jni_cf_recovery_policy.c for the rationale (the Kotlin mirror stays host-JVM
 * testable, and NativeBridge cannot load on a host JVM).
 *
 * Unlike the other two push-downs, the C side here is a SPECIFICATION, not an
 * executor: the five steps are `suspend` lambdas in Kotlin and cannot be driven
 * from a C callback without blocking a coroutine thread inside JNI -- the
 * KeepaliveHealth.GIVE_UP_WEDGED hazard. So C owns the order and the names, each
 * platform keeps its own executor, and RecreateSequenceParityTest asserts that
 * the Kotlin executor actually visits the steps in the order this header
 * declares.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRRecreateSequence.h"

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recreateStepCount(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)BR_RECREATE_STEP_COUNT;
}

/* The step at an ordinal position, or -1 out of range. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recreateStepAt(JNIEnv *env, jobject thiz,
                                                          jint index) {
    (void)thiz;
    (void)env;
    return (jint)BRRecreateStepAt((int)index);
}

/* Stable label for a step, or null for an unknown one. Matches the prefixes
 * RecreateSequence.kt writes into its failure list. */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recreateStepName(JNIEnv *env, jobject thiz,
                                                            jint step) {
    (void)thiz;
    const char *name = BRRecreateStepName((BRRecreateStep)step);
    if (!name) return NULL;
    return (*env)->NewStringUTF(env, name);
}

/* Whether the executor must continue after this step fails. */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recreateContinuesAfterFailure(JNIEnv *env, jobject thiz,
                                                                         jint step) {
    (void)env; (void)thiz;
    return BRRecreateContinuesAfterFailure((BRRecreateStep)step) ? JNI_TRUE : JNI_FALSE;
}
