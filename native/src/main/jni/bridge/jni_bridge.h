/*
 * jni_bridge.h
 *
 * Shared header for the clean JNI bridge (Task 3).
 * Common includes, logging macros, and global state declarations.
 */

#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <time.h>

/* C core headers */
#include "BRWallet.h"
#include "BRPeerManager.h"
#include "BRTransaction.h"
#include "BRKey.h"
#include "BRAddress.h"
#include "BRBIP39Mnemonic.h"
#include "BRBIP39WordsEn.h"
#include "BRBIP32Sequence.h"
#include "BRInt.h"
#include "BRCrypto.h"
#include "BRChainParams.h"
#include "BRMerkleBlock.h"

/* ---------- Logging macros ---------- */

#define LOG_TAG "DGB-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---------- Global state ---------- */

/* Cached JVM pointer — set in JNI_OnLoad */
extern JavaVM *g_jvm;

/* Wallet and peer manager — owned by the C core, managed by jni_wallet.c / jni_peer.c */
extern BRWallet     *g_wallet;
extern BRPeerManager *g_peerManager;

/* Session seed — zeroed on lockSession, set on unlockSession */
extern uint8_t  g_seed[64];
extern int      g_seedValid;

/* Master public key — derived once from seed */
extern BRMasterPubKey g_mpk;
extern int            g_mpkValid;

/* Wallet creation timestamp — used to pick the right sync checkpoint.
 * Set by createWallet (current time) or recoverWallet (user-provided). */
extern uint32_t g_walletCreationTime;

/* Callback handler (NativeCallback interface) */
extern jobject  g_callbackHandler;

/* Cached method IDs for NativeCallback — set in JNI_OnLoad or setCallbackHandler */
extern jmethodID g_mid_onSyncProgress;
extern jmethodID g_mid_onTransactionReceived;
extern jmethodID g_mid_onPeerConnected;
extern jmethodID g_mid_onPeerDisconnected;
extern jmethodID g_mid_onSyncComplete;
extern jmethodID g_mid_onSyncFailed;
extern jmethodID g_mid_onBalanceChanged;

/* ---------- Helper functions ---------- */

/* Get JNIEnv for the current thread, attaching if necessary. */
static inline JNIEnv *jni_get_env(void) {
    if (!g_jvm) return NULL;
    JNIEnv *env;
    int status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (status != JNI_OK) return NULL;
    }
    return env;
}

/* Secure memory zeroing — compiler cannot optimize this away. */
static inline void secure_zero(void *ptr, size_t len) {
    volatile uint8_t *p = (volatile uint8_t *)ptr;
    while (len--) *p++ = 0;
}

#endif /* JNI_BRIDGE_H */
