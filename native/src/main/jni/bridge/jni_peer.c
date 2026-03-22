/*
 * jni_peer.c
 *
 * JNI bridge for peer/sync operations: start/stop sync, progress,
 * peer count, block heights, and callback handler registration.
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 */

#include "jni_bridge.h"

/* ---------- BRPeerManager callback bridges ---------- */

static void bridge_syncStarted(void *info) {
    (void)info;
    LOGD("bridge_syncStarted");
    /* Sync started — onSyncProgress(0.0, lastBlockHeight) */
    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler || !g_mid_onSyncProgress) return;

    jlong height = g_peerManager ? (jlong)BRPeerManagerLastBlockHeight(g_peerManager) : 0;
    (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncProgress, (jfloat)0.0f, height);
}

static void bridge_syncStopped(void *info, int error) {
    (void)info;
    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler) return;

    if (error) {
        LOGW("bridge_syncStopped: error=%d (%s)", error, strerror(error));
        if (g_mid_onSyncFailed) {
            jstring msg = (*env)->NewStringUTF(env, strerror(error));
            (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncFailed, (jint)error, msg);
            (*env)->DeleteLocalRef(env, msg);
        }
    } else {
        LOGD("bridge_syncStopped: success");
        if (g_mid_onSyncComplete) {
            (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncComplete);
        }
    }
}

static void bridge_txStatusUpdate(void *info) {
    (void)info;
    LOGD("bridge_txStatusUpdate");
    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler || !g_wallet) return;

    /* Report balance change */
    if (g_mid_onBalanceChanged) {
        jlong balance = (jlong)BRWalletBalance(g_wallet);
        (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onBalanceChanged, balance);
    }

    /* Report sync progress */
    if (g_mid_onSyncProgress && g_peerManager) {
        double progress = BRPeerManagerSyncProgress(g_peerManager, 0);
        jlong height = (jlong)BRPeerManagerLastBlockHeight(g_peerManager);
        (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncProgress,
                               (jfloat)progress, height);
    }
}

static void bridge_saveBlocks(void *info, int replace, BRMerkleBlock *blocks[],
                               size_t blocksCount, uint64_t *memIntegrityCheck) {
    (void)info;
    (void)replace;
    (void)blocks;
    (void)blocksCount;
    (void)memIntegrityCheck;
    /* Block persistence will be implemented in Task 4 (Room database).
       For now, blocks are kept in memory only. */
    LOGD("bridge_saveBlocks: %zu blocks (replace=%d)", blocksCount, replace);
}

static void bridge_savePeers(void *info, int replace, const BRPeer peers[], size_t peersCount) {
    (void)info;
    (void)replace;
    (void)peers;
    (void)peersCount;
    /* Peer persistence will be implemented in Task 4 (Room database). */
    LOGD("bridge_savePeers: %zu peers (replace=%d)", peersCount, replace);
}

static int bridge_networkIsReachable(void *info) {
    (void)info;
    /* For now, always return reachable. Task 11 (SPV service) will
       integrate with Android ConnectivityManager. */
    return 1;
}

static void bridge_threadCleanup(void *info) {
    (void)info;
    if (g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/* ---------- startSync ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_startSync(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_wallet) {
        LOGW("startSync: wallet not initialized");
        return;
    }

    if (!g_peerManager) {
        /* Create peer manager for mainnet */
        LOGI("startSync: creating peer manager");
        g_peerManager = BPPeerManagerMainNetNew(g_wallet, BIP39_CREATION_TIME, NULL, 0, NULL, 0);
        if (!g_peerManager) {
            LOGE("startSync: BPPeerManagerMainNetNew failed");
            return;
        }

        /* Set callbacks */
        BRPeerManagerSetCallbacks(g_peerManager, NULL,
                                  bridge_syncStarted,
                                  bridge_syncStopped,
                                  bridge_txStatusUpdate,
                                  bridge_saveBlocks,
                                  bridge_savePeers,
                                  bridge_networkIsReachable,
                                  bridge_threadCleanup);
    }

    BRPeerManagerConnect(g_peerManager);
    LOGI("startSync: connecting to peers");
}

/* ---------- stopSync ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_stopSync(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (g_peerManager) {
        BRPeerManagerDisconnect(g_peerManager);
        LOGI("stopSync: disconnected");
    }
}

/* ---------- getSyncProgress ---------- */

JNIEXPORT jfloat JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getSyncProgress(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_peerManager) return 0.0f;
    return (jfloat)BRPeerManagerSyncProgress(g_peerManager, 0);
}

/* ---------- getPeerCount ---------- */

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getPeerCount(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_peerManager) return 0;
    return (jint)BRPeerManagerPeerCount(g_peerManager);
}

/* ---------- getEstimatedBlockHeight ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getEstimatedBlockHeight(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_peerManager) return 0;
    return (jlong)BRPeerManagerEstimatedBlockHeight(g_peerManager);
}

/* ---------- getLastBlockHeight ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getLastBlockHeight(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_peerManager) return 0;
    return (jlong)BRPeerManagerLastBlockHeight(g_peerManager);
}

/* ---------- setCallbackHandler ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setCallbackHandler(JNIEnv *env, jobject thiz,
                                                              jobject handler) {
    (void)thiz;

    /* Release old global ref if exists */
    if (g_callbackHandler) {
        (*env)->DeleteGlobalRef(env, g_callbackHandler);
        g_callbackHandler = NULL;
    }

    if (!handler) {
        LOGI("setCallbackHandler: handler cleared");
        g_mid_onSyncProgress        = NULL;
        g_mid_onTransactionReceived = NULL;
        g_mid_onPeerConnected       = NULL;
        g_mid_onPeerDisconnected    = NULL;
        g_mid_onSyncComplete        = NULL;
        g_mid_onSyncFailed          = NULL;
        g_mid_onBalanceChanged      = NULL;
        return;
    }

    /* Create global ref so the handler survives across JNI calls */
    g_callbackHandler = (*env)->NewGlobalRef(env, handler);

    /* Cache method IDs for the NativeCallback interface */
    jclass callbackClass = (*env)->GetObjectClass(env, handler);
    if (!callbackClass) {
        LOGE("setCallbackHandler: failed to get handler class");
        return;
    }

    g_mid_onSyncProgress = (*env)->GetMethodID(env, callbackClass,
        "onSyncProgress", "(FJ)V");
    g_mid_onTransactionReceived = (*env)->GetMethodID(env, callbackClass,
        "onTransactionReceived", "(Ljava/lang/String;JZ)V");
    g_mid_onPeerConnected = (*env)->GetMethodID(env, callbackClass,
        "onPeerConnected", "(I)V");
    g_mid_onPeerDisconnected = (*env)->GetMethodID(env, callbackClass,
        "onPeerDisconnected", "(I)V");
    g_mid_onSyncComplete = (*env)->GetMethodID(env, callbackClass,
        "onSyncComplete", "()V");
    g_mid_onSyncFailed = (*env)->GetMethodID(env, callbackClass,
        "onSyncFailed", "(ILjava/lang/String;)V");
    g_mid_onBalanceChanged = (*env)->GetMethodID(env, callbackClass,
        "onBalanceChanged", "(J)V");

    (*env)->DeleteLocalRef(env, callbackClass);

    LOGI("setCallbackHandler: handler registered, method IDs cached");
}
