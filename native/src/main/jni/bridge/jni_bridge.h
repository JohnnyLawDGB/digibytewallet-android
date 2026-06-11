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
#include <pthread.h>

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

/* ---------- Peer manager concurrency guard ----------
 *
 * g_peerManager is created, freed, and read from many threads: MainActivity
 * onResume reconnect, SyncService's 10s keepalive, WalletViewModel pull-to-
 * refresh wake-up, SyncWorker, the BIP158 watchdog, and the tx broadcast /
 * Dandelion-stem path. startSync() tears the manager down (BRPeerManagerFree)
 * and rebuilds it; without serialization a concurrent caller dereferences a
 * pointer another thread is mid-free on → use-after-free / double-free →
 * native SIGSEGV. Every JNI entry point that touches g_peerManager holds this
 * recursive lock for its whole body via PEER_GUARD().
 *
 * IMPORTANT: the BRPeerManager callbacks (bridge_syncStarted / syncStopped /
 * txStatusUpdate) run on the manager's OWN peer threads and MUST NOT take this
 * lock. BRPeerManagerFree() joins those threads while a JVM thread holds the
 * lock, so a lock attempt inside a callback would deadlock. At 0 peers no peer
 * threads exist, which is exactly the reported crash window — pure JVM-thread
 * contention, fully closed here.
 *
 * Initialized recursive in JNI_OnLoad (jni_wallet.c). */
extern pthread_mutex_t g_peerManagerMutex;

static inline void _peerManagerUnlock(int *guard) {
    (void)guard;
    pthread_mutex_unlock(&g_peerManagerMutex);
}

/* Acquire g_peerManagerMutex for the rest of the enclosing scope. The cleanup
 * attribute (Clang/NDK) releases it on EVERY return path, so the many early
 * `return`s in the guarded functions can't leak the lock. */
#define PEER_GUARD() \
    int _peerGuard __attribute__((cleanup(_peerManagerUnlock), unused)) = \
        (pthread_mutex_lock(&g_peerManagerMutex), 0)

/* Session seed — managed by jni_wallet.c, accessed via functions below.
 * g_seed is static to jni_wallet.c — no direct extern access. */
int seed_is_valid(void);
int seed_sign_transaction(BRWallet *wallet, BRTransaction *tx, int forkId);
int seed_derive_key(BRKey *outKey, uint32_t chain, uint32_t index);
void seed_zero(void);

/* Master public key — derived once from seed */
extern BRMasterPubKey g_mpk;
extern int            g_mpkValid;

/* Wallet creation timestamp — used to pick the right sync checkpoint.
 * Set by createWallet (current time) or recoverWallet (user-provided). */
extern uint32_t g_walletCreationTime;

/* Flag: set to 1 when createWallet/recoverWallet is called, so startSync
 * knows to recreate the peer manager with the wallet's bloom filter. */
extern int g_peerManagerNeedsRecreate;

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

/* Secure memory zeroing — guaranteed not optimized away.
 * explicit_bzero is provided by Bionic (Android libc) since API 17
 * and is explicitly defined to be non-optimizable by the compiler/LTO. */
static inline void secure_zero(void *ptr, size_t len) {
    /* Volatile write + compiler barrier — safe against LTO.
     * memset_s (C11) and explicit_bzero are not reliably available
     * across all NDK versions, so we use the proven volatile + barrier pattern. */
    volatile uint8_t *p = (volatile uint8_t *)ptr;
    while (len--) *p++ = 0;
    __asm__ __volatile__("" ::: "memory");  /* compiler barrier — prevents LTO elision */
}

#endif /* JNI_BRIDGE_H */
