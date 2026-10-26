/*
 * jni_stranded_refusal.c
 *
 * JNI accessors for the refusal hold in BRStrandedSend.h -- when the stranded-send sweep removes a
 * send the network refused, which unlocks its coins: the action (PublishOutcome.actionFor), the
 * plain-DGB gate (PublishOutcome.isPlainDgb) and the ten-minute hold on one peer manager
 * (RefusalHold).
 *
 * Test-support only; production Kotlin does not route through here (SyncService calls the Kotlin
 * mirror). See jni_cf_recovery_policy.c for why the mirror is kept: the C header is the source of
 * truth (iOS imports it), and StrandedRefusalParityTest binds the two.
 *
 * strandedRefusalHoldRun replays one send's observations through one BRStrandedRefusalHold and
 * returns each answer, so the parity test can drive Kotlin's RefusalHold with the same script.
 * Per step, flags: bit 0 the core's answer (refused), bit 1 the manager read before it is known,
 * bit 2 the one read after it is known, bit 3 forget the send before this observation. Arrays of
 * unequal length, or that cannot be read, answer null.
 *
 * Touches no wallet state, takes no lock, safe before a wallet exists.
 */

#include "jni_bridge.h"
#include "BRStrandedSend.h"

#define REFUSAL_FLAG_REFUSED       1
#define REFUSAL_FLAG_BEFORE_KNOWN  2
#define REFUSAL_FLAG_AFTER_KNOWN   4
#define REFUSAL_FLAG_FORGET        8

/* BRStrandedRefusalActionFor: 0 = keep, 1 = remove and unlock. */
jint JNICALL
NativeBridge_strandedRefusalActionFor(JNIEnv *env, jobject thiz, jint publishError,
                                      jboolean plainDgb, jboolean refusedByPeers) {
    (void)env; (void)thiz;
    return (jint)BRStrandedRefusalActionFor((int)publishError, plainDgb ? 1 : 0, refusedByPeers ? 1 : 0);
}

/* BRStrandedSendIsPlainDgb over the sweep's three readings of the wallet's copy of a send. */
jboolean JNICALL
NativeBridge_strandedSendIsPlainDgb(JNIEnv *env, jobject thiz, jboolean held, jboolean containsAsset,
                                    jint digiDollarType) {
    (void)env; (void)thiz;
    return BRStrandedSendIsPlainDgb(held ? 1 : 0, containsAsset ? 1 : 0, (int)digiDollarType) ? JNI_TRUE : JNI_FALSE;
}

/* BR_STRANDED_REFUSAL_HOLD_MS, so RefusalHold.HOLD_MS is asserted against the header. */
jlong JNICALL
NativeBridge_strandedRefusalHoldMs(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)BR_STRANDED_REFUSAL_HOLD_MS;
}

/* One send's observations through BRStrandedRefusalHoldObserve; each answer 0 or 1. */
jintArray JNICALL
NativeBridge_strandedRefusalHoldRun(JNIEnv *env, jobject thiz, jlong holdMs, jlongArray nowMs,
                                    jlongArray managerBefore, jlongArray managerAfter, jintArray flags) {
    jsize n, i;
    jlong *now = NULL, *before = NULL, *after = NULL;
    jint *fl = NULL, *answers = NULL;
    jintArray out = NULL;
    BRStrandedRefusalHold hold = { 0, 0, 0 };

    (void)thiz;
    if (! nowMs || ! managerBefore || ! managerAfter || ! flags) return NULL;
    n = (*env)->GetArrayLength(env, nowMs);
    if ((*env)->GetArrayLength(env, managerBefore) != n || (*env)->GetArrayLength(env, managerAfter) != n ||
        (*env)->GetArrayLength(env, flags) != n) return NULL;

    // Stop at the first failed fetch: it leaves an OutOfMemoryError pending, and no further
    // Get*ArrayElements call may be made with an exception pending.
    if (! (now = (*env)->GetLongArrayElements(env, nowMs, NULL))) goto done;
    if (! (before = (*env)->GetLongArrayElements(env, managerBefore, NULL))) goto done;
    if (! (after = (*env)->GetLongArrayElements(env, managerAfter, NULL))) goto done;
    if (! (fl = (*env)->GetIntArrayElements(env, flags, NULL))) goto done;
    answers = (n > 0) ? (jint *)calloc((size_t)n, sizeof(jint)) : NULL;
    if (n == 0 || answers) {
        for (i = 0; i < n; i++) {
            if (fl[i] & REFUSAL_FLAG_FORGET) BRStrandedRefusalHoldForget(&hold);
            answers[i] = (jint)BRStrandedRefusalHoldObserve(&hold, (fl[i] & REFUSAL_FLAG_REFUSED) != 0,
                                                            (fl[i] & REFUSAL_FLAG_BEFORE_KNOWN) != 0, (int64_t)before[i],
                                                            (fl[i] & REFUSAL_FLAG_AFTER_KNOWN) != 0, (int64_t)after[i],
                                                            (int64_t)now[i], (int64_t)holdMs);
        }
        out = (*env)->NewIntArray(env, n);
        if (out && n > 0) (*env)->SetIntArrayRegion(env, out, 0, n, answers);
    }
done:
    if (now) (*env)->ReleaseLongArrayElements(env, nowMs, now, JNI_ABORT);
    if (before) (*env)->ReleaseLongArrayElements(env, managerBefore, before, JNI_ABORT);
    if (after) (*env)->ReleaseLongArrayElements(env, managerAfter, after, JNI_ABORT);
    if (fl) (*env)->ReleaseIntArrayElements(env, flags, fl, JNI_ABORT);
    free(answers);
    return out;
}
