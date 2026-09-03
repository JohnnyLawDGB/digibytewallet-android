/*
 * jni_peer_penalty_persist.c
 *
 * JNI accessors for BRPeerPenaltyPersist.h -- the peer-penalty persistence
 * decision (Keep / Clear / Store).
 *
 * Test-support only; production Kotlin does not route through here. See
 * jni_cf_recovery_policy.c for the rationale.
 *
 * The accessor that matters is peerPenaltyHeaderBytes(): PeerPenaltyPersist.kt
 * carries `const val HEADER_BYTES = 4`, which duplicates BRPeerPenalty.h:73's
 * BR_PEER_PENALTY_HEADER_BYTES. That constant is not a policy choice -- it is a
 * fact about what BRPeerPenaltySerialize emits for an empty set. A platform copy
 * that drifts from the serializer turns every empty set into "unknown", so a
 * stale penalty set is never cleared, or worse turns "couldn't read" into
 * "empty" and discards penalties the wallet had banked against a fleet that is
 * refusing it.
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRPeerPenaltyPersist.h"

/* 0 = Keep, 1 = Clear, 2 = Store. Mirrors BRPeerPenaltyAction. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerPenaltyDecide(JNIEnv *env, jobject thiz,
                                                             jbyteArray blob) {
    (void)thiz;

    if (! blob) return (jint)BRPeerPenaltyDecide(0, 0);

    jsize len = (*env)->GetArrayLength(env, blob);
    jbyte *bytes = (*env)->GetByteArrayElements(env, blob, 0);
    if (! bytes) return (jint)BRPeerPenaltyDecide(0, 0);

    BRPeerPenaltyAction a = BRPeerPenaltyDecide((const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, blob, bytes, JNI_ABORT);
    return (jint)a;
}

/* The same decision from a length alone, for a caller that has no buffer. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerPenaltyDecideLength(JNIEnv *env, jobject thiz,
                                                                   jint length) {
    (void)env; (void)thiz;
    if (length < 0) return (jint)BRPeerPenaltyActionKeep;
    return (jint)BRPeerPenaltyDecideLength((size_t)length);
}

/* BRPeerPenalty.h's wire-format constants, so the Kotlin mirror can assert
 * against the serializer instead of against a comment. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerPenaltyHeaderBytes(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)BR_PEER_PENALTY_HEADER_BYTES;
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_peerPenaltyEntryBytes(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)BR_PEER_PENALTY_ENTRY_BYTES;
}
