/*
 * jni_peer_canon.c
 *
 * JNI accessors for BRPeerCanon.h -- the hardcoded compact-filter peer canon.
 *
 * Unlike the jni_*_policy accessors these are NOT test-support only: they are
 * how Kotlin reads the canon. SyncService.kt used to carry its own copy of the
 * testnet26 set (three IPs, the port, the 0x41 service bits) so it could
 * re-inject those peers on every reconnect. That copy is deleted -- the table
 * lives in the core once, and the platforms ask for it. A second copy of the
 * wallet's only reliable filter source is exactly the divergence the header
 * exists to prevent.
 *
 * The port is not part of the canon header (it is BRChainParams.h's
 * standardPort); it is exposed here from the chain params for the same reason.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRNetwork.h"
#include "BRPeerCanon.h"

/* Entry count for a network (testnet != 0 selects testnet26). */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerCanonCount(JNIEnv *env, jobject thiz,
                                                          jboolean testnet) {
    (void)env; (void)thiz;
    return (jint)BRPeerCanonCount(testnet ? 1 : 0);
}

/* Entry `index` as a dotted-quad string, or NULL past the end. */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerCanonIp(JNIEnv *env, jobject thiz,
                                                       jboolean testnet, jint index) {
    (void)thiz;
    if (index < 0) return NULL;
    const char *ip = BRPeerCanonIPAt(testnet ? 1 : 0, (size_t)index);
    return ip ? (*env)->NewStringUTF(env, ip) : NULL;
}

/* The P2P port the canon is dialled on: the chain params' standardPort. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerCanonPort(JNIEnv *env, jobject thiz,
                                                         jboolean testnet) {
    (void)env; (void)thiz;
    return (jint)(testnet ? BRTestNetParams.standardPort : BRMainNetParams.standardPort);
}

/* BR_PEER_CANON_SERVICES: how every canon peer is tagged on injection. */
JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerCanonServices(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)BR_PEER_CANON_SERVICES;
}

/* Is this dotted-quad one of the network's canon peers? 1/0, or 0 for an
 * unparsable string -- a hostname is never a canon peer. */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerCanonContains(JNIEnv *env, jobject thiz,
                                                             jboolean testnet, jstring ipStr) {
    (void)thiz;
    if (! ipStr) return JNI_FALSE;
    const char *ip = (*env)->GetStringUTFChars(env, ipStr, NULL);
    if (! ip) return JNI_FALSE;
    UInt128 addr;
    int hit = BRPeerCanonParseIPv4(ip, &addr) && BRPeerCanonContains(testnet ? 1 : 0, addr);
    (*env)->ReleaseStringUTFChars(env, ipStr, ip);
    return hit ? JNI_TRUE : JNI_FALSE;
}
