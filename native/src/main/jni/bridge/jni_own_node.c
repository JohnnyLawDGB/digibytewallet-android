/*
 * jni_own_node.c
 *
 * JNI accessors for BROwnNode.h's own-node health and watchdog pin rules, which SyncService calls
 * in production (iOS calls the same functions from WalletCore):
 *
 *   ownNodeHealthObserve  BROwnNodeHealthObserve: one health poll. The run of polls without a
 *                         connection is kept by the caller in a LongArray of 3 (the
 *                         BROwnNodeHealthTrack fields, in order: runStart, lastPoll, inRun), all
 *                         zero for a newly paired node. Returns the BROwnNodeHealth value
 *                         (1 connecting, 2 serving, 3 dark), or -1 when the array cannot be read.
 *   ownNodeWatchdogMayPin BROwnNodeWatchdogMayPin.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BROwnNode.h"

jint JNICALL
NativeBridge_ownNodeHealthObserve(JNIEnv *env, jobject thiz, jlongArray track, jint cfPeerStatus,
                                  jlong nowSecs) {
    jlong v[3];
    BROwnNodeHealthTrack t;
    BROwnNodeHealth h;

    (void)thiz;
    if (! track || (*env)->GetArrayLength(env, track) != 3) return -1;
    (*env)->GetLongArrayRegion(env, track, 0, 3, v);
    if ((*env)->ExceptionCheck(env)) return -1;
    t.runStart = (int64_t)v[0]; t.lastPoll = (int64_t)v[1]; t.inRun = (int64_t)v[2];
    h = BROwnNodeHealthObserve(&t, (int)cfPeerStatus, (int64_t)nowSecs);
    v[0] = (jlong)t.runStart; v[1] = (jlong)t.lastPoll; v[2] = (jlong)t.inRun;
    (*env)->SetLongArrayRegion(env, track, 0, 3, v);
    return (jint)h;
}

jboolean JNICALL
NativeBridge_ownNodeWatchdogMayPin(JNIEnv *env, jobject thiz, jboolean ownNodePinned) {
    (void)env; (void)thiz;
    return BROwnNodeWatchdogMayPin(ownNodePinned ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}
