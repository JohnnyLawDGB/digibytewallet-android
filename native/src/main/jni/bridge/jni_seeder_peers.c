/*
 * jni_seeder_peers.c
 *
 * JNI accessors for BRSeederPeers.h's rules, which SyncService and SyncWorker call in production
 * (iOS calls the same functions from SeederPeers.swift and WalletCore):
 *
 *   seederServicesParse  BRSeederServicesParse: a seeder peer's services_hex ("0x44d") as service
 *                        bits, 0 ("unknown") when it is absent or cannot be read. It replaced the
 *                        Kotlin parseSeederServicesHex (PeerServicesPolicy.kt). injectPeerByIp
 *                        (jni_peer.c) tags a 0 as the canon (BRSeederPeerServices).
 *   seederShouldRefresh  BRSeederShouldRefresh: whether a cached seeder list, fetched at
 *                        lastFetchMs (0: never) and holding cachedCount peers, is fetched again
 *                        at nowMs -- it is empty, or more than BR_SEEDER_REFRESH_MS (one hour)
 *                        old. It replaced the Kotlin SeederRefreshPolicy.isStale (Android #187).
 *
 * The string reaches the core as JNI's modified UTF-8, which has no zero byte and agrees with
 * UTF-8 on every ASCII character, so the core reads the same bytes iOS hands it for any value the
 * seeder can write; anything non-ASCII is not hex and reads as 0 on both. The result is at most
 * INT64_MAX, so it crosses as a non-negative jlong. Kotlin's Long is a jlong is an int64_t, so the
 * times cross unchanged and their difference wraps as it did in Kotlin. A count below zero is not
 * a size and reads as empty. The rules' values are checked by the host KAT (seeder_peers_kat).
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRSeederPeers.h"
#include <string.h>

jlong JNICALL
NativeBridge_seederServicesParse(JNIEnv *env, jobject thiz, jstring servicesHex) {
    (void)thiz;
    if (! servicesHex) return 0;
    const char *s = (*env)->GetStringUTFChars(env, servicesHex, NULL);
    if (! s) return 0;  // OutOfMemoryError is pending; the caller sees it
    uint64_t services = BRSeederServicesParse(s, strlen(s));
    (*env)->ReleaseStringUTFChars(env, servicesHex, s);
    return (jlong)services;
}

jboolean JNICALL
NativeBridge_seederShouldRefresh(JNIEnv *env, jobject thiz, jlong lastFetchMs, jlong nowMs, jint cachedCount) {
    (void)env; (void)thiz;
    return BRSeederShouldRefresh((int64_t)lastFetchMs, (int64_t)nowMs,
                                 cachedCount > 0 ? (size_t)cachedCount : 0) ? JNI_TRUE : JNI_FALSE;
}
