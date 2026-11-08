/*
 * jni_recovery_gap.c
 *
 * JNI accessors for BRRecoveryGapWindow.h, which RecoveryScanService (through RecoveryGapRule.Core)
 * calls in production while it widens each recovery path's window (4.1.3 G1). iOS calls the same
 * functions. They replaced the Kotlin RecoveryGapWindow and the per-path decision that was in
 * RecoveryScanService.classifyWidening:
 *
 *   recoveryGapNext        BRRecoveryGapNext: the window a chain grows to.
 *   recoveryGapOutcome     BRRecoveryGapOutcome: ask again (0), finished (1) or capped (2).
 *   recoveryGapRoundBound  BRRecoveryGapScanRoundBound: the scan's round bound.
 *
 * Kotlin's Int is a jint is an int32_t, so every value crosses unchanged; Kotlin passes a negative
 * highest-used index for "nothing used" (its null). The rules' values are checked by the host KAT
 * (recovery_gap_window_kat). Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRRecoveryGapWindow.h"

jint JNICALL
NativeBridge_recoveryGapNext(JNIEnv *env, jobject thiz, jint chain, jint count, jint highestUsed) {
    (void)env; (void)thiz;
    return (jint)BRRecoveryGapNext((int)chain, (int32_t)count, (int32_t)highestUsed);
}

jint JNICALL
NativeBridge_recoveryGapOutcome(JNIEnv *env, jobject thiz, jint externalCount, jint internalCount,
                                jint nextExternal, jint nextInternal, jint usedExternal,
                                jint usedInternal, jint round, jint bound) {
    (void)env; (void)thiz;
    return (jint)BRRecoveryGapOutcome((int32_t)externalCount, (int32_t)internalCount,
                                      (int32_t)nextExternal, (int32_t)nextInternal,
                                      (int32_t)usedExternal, (int32_t)usedInternal,
                                      (int32_t)round, (int32_t)bound);
}

jint JNICALL
NativeBridge_recoveryGapRoundBound(JNIEnv *env, jobject thiz, jint startExternal, jint startInternal) {
    (void)env; (void)thiz;
    return (jint)BRRecoveryGapScanRoundBound((int32_t)startExternal, (int32_t)startInternal);
}
