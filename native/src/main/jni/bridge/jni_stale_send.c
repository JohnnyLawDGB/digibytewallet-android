/*
 * jni_stale_send.c
 *
 * JNI accessors for BRStaleSend.h, the stale-send clear's decision (4.1.3, review R8), which
 * WalletManager.clearStuckSends and DgbNodeClient.sendNeverRelayed call in production (iOS calls
 * the same functions):
 *
 *   staleSendOldEnough  BRStaleSendOldEnough: a send with this timestamp (seconds; 0: none) is
 *                       old enough at nowS to ask the node about -- above 0 and one day
 *                       (BR_STALE_SEND_MIN_AGE_S) old or more.
 *   staleSendIsStale    BRStaleSendIsStale: old enough, and the network never took it.
 *   staleSendNextStep   BRStaleSendNextStep: from the node's answers so far, the next question
 *                       (the txid, input number inputsAsked) or the verdict. The codes are
 *                       BRStaleSendTxStatus, BRStaleSendInputAnswer and BRStaleSendStep, which
 *                       DgbNodeClient names (StaleSendCoreRuleGateTest reads them against the
 *                       header).
 *
 * Together they replaced the Kotlin StaleSendPredicate (WalletManager.kt) and the decision in
 * DgbNodeClient.sendNeverRelayed; the network calls and the parse of their answers stay Kotlin.
 * Kotlin's Long is a jlong is an int64_t, so the times cross unchanged and their difference wraps
 * as it did in Kotlin. A negative count is not a size and reads as 0 (an inputCount of 0 is never
 * asked about; inputsAsked 0 is the start). The rule's values are checked by the host KAT
 * (stale_send_kat). Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRStaleSend.h"

jboolean JNICALL
NativeBridge_staleSendOldEnough(JNIEnv *env, jobject thiz, jlong timestampS, jlong nowS) {
    (void)env; (void)thiz;
    return BRStaleSendOldEnough((int64_t)timestampS, (int64_t)nowS) ? JNI_TRUE : JNI_FALSE;
}

jboolean JNICALL
NativeBridge_staleSendIsStale(JNIEnv *env, jobject thiz, jlong timestampS, jlong nowS, jboolean neverRelayed) {
    (void)env; (void)thiz;
    return BRStaleSendIsStale((int64_t)timestampS, (int64_t)nowS, neverRelayed ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}

jint JNICALL
NativeBridge_staleSendNextStep(JNIEnv *env, jobject thiz, jboolean pinnedNode, jint inputCount, jint txStatus,
                               jint inputsAsked, jint lastInputAnswer) {
    (void)env; (void)thiz;
    return (jint)BRStaleSendNextStep(pinnedNode ? 1 : 0, inputCount > 0 ? (size_t)inputCount : 0, (int)txStatus,
                                     inputsAsked > 0 ? (size_t)inputsAsked : 0, (int)lastInputAnswer);
}
