/*
 * jni_seeder_refresh.c
 *
 * JNI accessor for BRSeederPeers.h's refresh rule, which SyncService and SyncWorker call in
 * production (iOS calls the same function from WalletCore):
 *
 *   seederShouldRefresh  BRSeederShouldRefresh: whether a cached seeder list, fetched at
 *                        lastFetchMs (0: never) and holding cachedCount peers, is fetched again
 *                        at nowMs -- it is empty, or more than BR_SEEDER_REFRESH_MS (one hour)
 *                        old. It replaced the Kotlin SeederRefreshPolicy.isStale (Android #187).
 *
 * Kotlin's Long is a jlong is an int64_t, so the times cross unchanged and their difference wraps
 * as it did in Kotlin. A count below zero is not a size and reads as empty. The rule's values are
 * checked by the host KAT (seeder_peers_kat). Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRSeederPeers.h"

jboolean JNICALL
NativeBridge_seederShouldRefresh(JNIEnv *env, jobject thiz, jlong lastFetchMs, jlong nowMs, jint cachedCount) {
    (void)env; (void)thiz;
    return BRSeederShouldRefresh((int64_t)lastFetchMs, (int64_t)nowMs,
                                 cachedCount > 0 ? (size_t)cachedCount : 0) ? JNI_TRUE : JNI_FALSE;
}
