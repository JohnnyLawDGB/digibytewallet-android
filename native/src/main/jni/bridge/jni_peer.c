/*
 * jni_peer.c
 *
 * JNI bridge for peer/sync operations: start/stop sync, progress,
 * peer count, block heights, and callback handler registration.
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 */

#include "jni_bridge.h"
#include "BRCompactFilterChain.h"

/* Forward decl — defined in the BIP 158 bridge section; called from startSync. */
static void _applyPendingBip158State(void);

/* ---------- setSocksProxy / clearSocksProxy ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setSocksProxy(
    JNIEnv *env, jobject thiz, jstring host, jint port)
{
    (void)thiz;
    const char *hostStr = (*env)->GetStringUTFChars(env, host, NULL);
    if (hostStr == NULL) return;

    BRPeerSetSocksProxy(hostStr, (int)port);

    (*env)->ReleaseStringUTFChars(env, host, hostStr);
    LOGI("setSocksProxy: proxy configured (port=%d)", (int)port);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_clearSocksProxy(
    JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    BRPeerClearSocksProxy();
    LOGI("clearSocksProxy: proxy cleared");
}

/* ---------- BRPeerManager callback bridges ---------- */

static int g_initialSyncDone = 0;
static int g_isRescanning = 0;

/* Priority peer (digiscope.me) — resolved once in _injectPriorityPeer, reused for rescan */
static UInt128 g_priorityPeerAddr = { .u64 = {0, 0} };
static uint16_t g_priorityPeerPort = 0;

static void bridge_syncStarted(void *info) {
    (void)info;
    LOGD("bridge_syncStarted (rescanning=%d)", g_isRescanning);

    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler || !g_mid_onSyncProgress) return;

    jlong height = g_peerManager ? (jlong)BRPeerManagerLastBlockHeight(g_peerManager) : 0;

    if (g_isRescanning) {
        /* Signal Kotlin that a rescan is in progress — use progress=-1 as marker */
        (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncProgress, (jfloat)-1.0f, height);
    } else {
        (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncProgress, (jfloat)0.0f, height);
    }
}

static void bridge_syncStopped(void *info, int error) {
    (void)info;
    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler) return;

    if (error) {
        LOGW("bridge_syncStopped: error=%d (%s)", error, strerror(error));
        /* Report failure to UI — do NOT auto-reconnect here.
         * Immediate reconnect on "Network is unreachable" creates a tight
         * infinite loop that burns CPU and keeps resetting the UI to 0%.
         * The Kotlin SyncService handles reconnection via its peer-count
         * polling loop (every 30s) which will call startSync if needed. */
        if (g_mid_onSyncFailed) {
            jstring msg = (*env)->NewStringUTF(env, strerror(error));
            (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncFailed, (jint)error, msg);
            (*env)->DeleteLocalRef(env, msg);
        }
    } else {
        LOGD("bridge_syncStopped: sync complete");

        /* After FIRST successful sync, trigger a rescan to catch any
         * transactions that were in blocks downloaded during the initial
         * header-only fast sync. The bloom filter wasn't active during
         * that phase, so matching transactions were missed. */
        if (!g_initialSyncDone && g_peerManager) {
            g_initialSyncDone = 1;
            g_isRescanning = 1;
            LOGI("bridge_syncStopped: first sync done, triggering rescan for missed transactions");

            /* Rescan re-downloads blocks with the bloom filter active to find
             * transactions matching wallet addresses. The priority peer
             * (digiscope.me) is already at the top of the peer list from
             * _injectPriorityPeer, so it will be tried first on reconnect.
             * Don't use SetFixedPeer — it disconnects everything and clears
             * the peer array, causing reconnection failures. */
            BRPeerManagerRescan(g_peerManager);
            /* Don't fire onSyncComplete yet — wait for rescan to finish */
        } else {
            g_isRescanning = 0;
            if (g_mid_onSyncComplete) {
                (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncComplete);
            }
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

    /* Report sync progress — suppress during rescan and at 1.0.
     * onSyncComplete (via bridge_syncStopped) is the proper "done" signal. */
    if (g_mid_onSyncProgress && g_peerManager && !g_isRescanning) {
        double progress = BRPeerManagerSyncProgress(g_peerManager, 0);
        if (progress < 1.0) {
            jlong height = (jlong)BRPeerManagerLastBlockHeight(g_peerManager);
            (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSyncProgress,
                                   (jfloat)progress, height);
        }
    }
}

/* ── Block & Peer Persistence ────────────────────────────────────────────
 * Serialize blocks/peers to byte arrays and pass to Kotlin via JNI callback.
 * Kotlin side persists to SharedPreferences as hex-encoded blobs.
 * On restart, Kotlin passes the blobs back to startSync which deserializes
 * and feeds them to BRPeerManagerNew.
 */

/* Cached method IDs for save callbacks */
static jmethodID g_mid_onSaveBlocks = NULL;
static jmethodID g_mid_onSavePeers = NULL;
static jmethodID g_mid_onSaveFilterHeaders = NULL;

static void bridge_saveBlocks(void *info, int replace, BRMerkleBlock *blocks[],
                               size_t blocksCount, uint64_t *memIntegrityCheck) {
    (void)info;
    (void)memIntegrityCheck;

    if (blocksCount == 0 || !blocks) return;

    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler) {
        LOGD("bridge_saveBlocks: %zu blocks (no JNI env, skipping persist)", blocksCount);
        return;
    }

    /* Cache method ID on first call */
    if (!g_mid_onSaveBlocks) {
        jclass cls = (*env)->GetObjectClass(env, g_callbackHandler);
        g_mid_onSaveBlocks = (*env)->GetMethodID(env, cls, "onSaveBlocks", "([BI)V");
        (*env)->DeleteLocalRef(env, cls);
        if (!g_mid_onSaveBlocks) {
            LOGW("bridge_saveBlocks: onSaveBlocks method not found");
            return;
        }
    }

    /* Serialize all blocks into a single buffer:
     * [4 bytes: block count]
     * For each block:
     *   [4 bytes: serialized length]
     *   [4 bytes: height]
     *   [N bytes: serialized block data]
     */
    size_t totalSize = 4; /* block count */
    size_t *blockSizes = malloc(blocksCount * sizeof(size_t));
    if (!blockSizes) return;

    for (size_t i = 0; i < blocksCount; i++) {
        blockSizes[i] = BRMerkleBlockSerialize(blocks[i], NULL, 0);
        totalSize += 4 + 4 + blockSizes[i]; /* length + height + data */
    }

    uint8_t *buf = malloc(totalSize);
    if (!buf) { free(blockSizes); return; }

    size_t pos = 0;
    UInt32SetLE(&buf[pos], (uint32_t)blocksCount); pos += 4;

    for (size_t i = 0; i < blocksCount; i++) {
        UInt32SetLE(&buf[pos], (uint32_t)blockSizes[i]); pos += 4;
        UInt32SetLE(&buf[pos], blocks[i]->height); pos += 4;
        BRMerkleBlockSerialize(blocks[i], &buf[pos], blockSizes[i]);
        pos += blockSizes[i];
    }
    free(blockSizes);

    /* Pass to Kotlin */
    jbyteArray jbuf = (*env)->NewByteArray(env, (jsize)pos);
    (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)pos, (jbyte *)buf);
    (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSaveBlocks, jbuf, (jint)replace);
    (*env)->DeleteLocalRef(env, jbuf);
    free(buf);

    LOGD("bridge_saveBlocks: serialized %zu blocks (%zu bytes, replace=%d)", blocksCount, pos, replace);
}

static void bridge_savePeers(void *info, int replace, const BRPeer peers[], size_t peersCount) {
    (void)info;

    if (peersCount == 0 || !peers) return;

    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler) {
        LOGD("bridge_savePeers: %zu peers (no JNI env, skipping persist)", peersCount);
        return;
    }

    /* Cache method ID on first call */
    if (!g_mid_onSavePeers) {
        jclass cls = (*env)->GetObjectClass(env, g_callbackHandler);
        g_mid_onSavePeers = (*env)->GetMethodID(env, cls, "onSavePeers", "([BI)V");
        (*env)->DeleteLocalRef(env, cls);
        if (!g_mid_onSavePeers) {
            LOGW("bridge_savePeers: onSavePeers method not found");
            return;
        }
    }

    /* Serialize peers: [4 bytes count] + [per peer: 16 bytes addr + 2 bytes port + 8 bytes timestamp + 8 bytes services] */
    size_t peerSize = 16 + 2 + 8 + 8; /* 34 bytes per peer */
    size_t totalSize = 4 + peersCount * peerSize;
    uint8_t *buf = malloc(totalSize);
    if (!buf) return;

    size_t pos = 0;
    UInt32SetLE(&buf[pos], (uint32_t)peersCount); pos += 4;

    for (size_t i = 0; i < peersCount; i++) {
        memcpy(&buf[pos], &peers[i].address, 16); pos += 16;
        UInt16SetLE(&buf[pos], peers[i].port); pos += 2;
        UInt64SetLE(&buf[pos], peers[i].timestamp); pos += 8;
        UInt64SetLE(&buf[pos], peers[i].services); pos += 8;
    }

    jbyteArray jbuf = (*env)->NewByteArray(env, (jsize)pos);
    (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)pos, (jbyte *)buf);
    (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSavePeers, jbuf, (jint)replace);
    (*env)->DeleteLocalRef(env, jbuf);
    free(buf);

    LOGD("bridge_savePeers: serialized %zu peers (%zu bytes, replace=%d)", peersCount, pos, replace);
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

/* ── Saved blocks/peers globals (populated by loadSavedBlocks/loadSavedPeers) ── */

static BRMerkleBlock **g_savedBlocks = NULL;
static size_t g_savedBlocksCount = 0;
static BRPeer *g_savedPeers = NULL;
static size_t g_savedPeersCount = 0;

/* ---------- Priority peer injection ---------- */

#include <netdb.h>
#include <arpa/inet.h>

/**
 * Resolve a hostname and prepend it to g_savedPeers so the peer manager
 * tries it first. Uses a recent timestamp so it sorts to the top.
 * If resolution fails, logs a warning and returns silently.
 */
static void _injectPriorityPeer(const char *hostname, uint16_t port) {
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;  /* IPv4 — most reliable for mobile */
    hints.ai_socktype = SOCK_STREAM;

    int err = getaddrinfo(hostname, NULL, &hints, &res);
    if (err != 0 || !res) {
        LOGW("_injectPriorityPeer: failed to resolve %s: %s", hostname,
             err ? gai_strerror(err) : "no results");
        return;
    }

    struct sockaddr_in *ipv4 = (struct sockaddr_in *)res->ai_addr;
    uint32_t ip4 = ipv4->sin_addr.s_addr; /* network byte order */

    /* Build IPv4-mapped IPv6 address (::ffff:x.x.x.x) as UInt128 */
    UInt128 addr = UINT128_ZERO;
    addr.u16[5] = 0xffff;
    addr.u32[3] = ip4;

    freeaddrinfo(res);

    /* Check if already present in saved peers */
    for (size_t i = 0; i < g_savedPeersCount; i++) {
        if (UInt128Eq(g_savedPeers[i].address, addr)) {
            /* Already there — just bump its timestamp to the top */
            g_savedPeers[i].timestamp = (uint64_t)time(NULL);
            g_savedPeers[i].services = SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM;
            g_priorityPeerAddr = addr;
            g_priorityPeerPort = port;
            LOGI("_injectPriorityPeer: %s already in saved peers, bumped timestamp", hostname);
            return;
        }
    }

    /* Prepend: allocate new array with +1 slot */
    size_t newCount = g_savedPeersCount + 1;
    BRPeer *newPeers = malloc(newCount * sizeof(BRPeer));
    if (!newPeers) return;

    /* Priority peer at index 0 */
    newPeers[0] = (BRPeer){ addr, port,
        SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM,
        (uint64_t)time(NULL), 0 };

    /* Copy existing peers after it */
    if (g_savedPeers && g_savedPeersCount > 0) {
        memcpy(newPeers + 1, g_savedPeers, g_savedPeersCount * sizeof(BRPeer));
    }

    free(g_savedPeers);
    g_savedPeers = newPeers;
    g_savedPeersCount = newCount;

    char ipStr[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &ip4, ipStr, sizeof(ipStr));
    /* Cache for rescan locking */
    g_priorityPeerAddr = addr;
    g_priorityPeerPort = port;

    LOGI("_injectPriorityPeer: injected %s (%s:%u) as priority peer", hostname, ipStr, port);
}

/* ---------- injectPeerByIp ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_injectPeerByIp(JNIEnv *env, jobject thiz,
                                                           jstring ipStr, jint port) {
    (void)thiz;
    if (!ipStr) return;

    const char *ip = (*env)->GetStringUTFChars(env, ipStr, NULL);
    if (!ip) return;

    /* Update g_savedPeers for the cold-start case when peer manager doesn't
     * exist yet — _injectPriorityPeer dedupes and prepends. */
    _injectPriorityPeer(ip, (uint16_t)port);

    /* If the peer manager is already alive, ALSO add to its live candidate
     * pool. Without this, post-startup injections accumulate in g_savedPeers
     * which is only consumed once at manager creation — the keepalive ends
     * up calling injectPeerByIp on every tick with zero effect, leaving the
     * wallet stuck at 0 peers when the initial peer set fails to dial. */
    if (g_peerManager) {
        UInt128 addr = UINT128_ZERO;
        struct in_addr ip4;
        if (inet_pton(AF_INET, ip, &ip4) == 1) {
            /* Build IPv4-mapped IPv6 address (::ffff:x.x.x.x) */
            addr.u16[5] = 0xffff;
            addr.u32[3] = ip4.s_addr;
            BRPeerManagerAddPeer(g_peerManager, addr, (uint16_t)port,
                                  SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM);
        }
    }

    (*env)->ReleaseStringUTFChars(env, ipStr, ip);
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

    if (g_peerManager && g_peerManagerNeedsRecreate) {
        /* Don't destroy the peer manager mid-rescan — the rescan needs it alive
         * to find transactions. Defer the recreate until rescan finishes. */
        if (g_isRescanning) {
            LOGI("startSync: deferring peer manager recreate — rescan in progress");
            return;
        }
        /* Wallet was created/recovered after peer manager was initialized.
         * Destroy and recreate so the bloom filter includes the wallet's addresses.
         * Only do this ONCE — clear the flag immediately. */
        g_peerManagerNeedsRecreate = 0;
        LOGI("startSync: recreating peer manager (wallet changed since last init)");
        BRPeerManagerDisconnect(g_peerManager);
        BRPeerManagerFree(g_peerManager);
        g_peerManager = NULL;
    }

    if (g_peerManager) {
        /* Peer manager already exists — only reconnect if not already connected */
        if (BRPeerManagerPeerCount(g_peerManager) > 0) {
            LOGD("startSync: already connected with %zu peers, skipping",
                 BRPeerManagerPeerCount(g_peerManager));
            return;
        }
        LOGI("startSync: peer manager exists, reconnecting");
        BRPeerManagerConnect(g_peerManager);
        return;
    }

    {
        /* Inject digiscope.me as a priority peer so it's always tried.
         * Resolve the hostname and prepend to g_savedPeers before creating
         * the peer manager. This ensures a bloom-filter-enabled node is
         * always in the pool, even if DNS seeds aren't re-queried. */
        _injectPriorityPeer("digiscope.me", 12024);

        /* Create peer manager for mainnet.
         * Use g_walletCreationTime (set by createWallet/recoverWallet) so the
         * peer manager starts syncing from the checkpoint nearest to when the
         * wallet was created — not from BIP39_CREATION_TIME (Dec 2017). */
        uint32_t syncFromTime = g_walletCreationTime ? g_walletCreationTime : (uint32_t)time(NULL);
        LOGI("startSync: creating peer manager (syncFromTime=%u, savedBlocks=%zu, savedPeers=%zu)",
             syncFromTime, g_savedBlocksCount, g_savedPeersCount);
        g_peerManager = BPPeerManagerMainNetNew(g_wallet, syncFromTime,
                                                 g_savedBlocks, g_savedBlocksCount,
                                                 g_savedPeers, g_savedPeersCount);
        if (!g_peerManager) {
            LOGE("startSync: BPPeerManagerMainNetNew failed");
            return;
        }

        /* Clear the flag — peer manager is now built with current wallet.
         * Without this, the poll loop's next startSync call would see the
         * stale flag and destroy what we just created. */
        g_peerManagerNeedsRecreate = 0;

        /* Set callbacks */
        BRPeerManagerSetCallbacks(g_peerManager, NULL,
                                  bridge_syncStarted,
                                  bridge_syncStopped,
                                  bridge_txStatusUpdate,
                                  bridge_saveBlocks,
                                  bridge_savePeers,
                                  bridge_networkIsReachable,
                                  bridge_threadCleanup);

        /* Apply any BIP 158 state SyncService.kt configured before this point
         * (setSyncMode, setCompactFilterChain, enableAutoCompactFilterFetch). */
        _applyPendingBip158State();
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

/* ---------- rescan ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_rescan(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (g_peerManager) {
        LOGI("rescan: triggering BRPeerManagerRescan");
        BRPeerManagerRescan(g_peerManager);
    } else {
        LOGW("rescan: peer manager not initialized");
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

/* ---------- getSavedBlocksTip ----------
 *
 * Highest height in the in-memory saved-blocks set, or 0 if none loaded.
 * Unlike getLastBlockHeight(), does not depend on the peer manager — safe
 * to call between loadSavedBlocks() and startSync(). SyncService uses this
 * to pick the BIP 158 birth height before the peer manager exists.
 */
JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getSavedBlocksTip(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_savedBlocks || g_savedBlocksCount == 0) return 0;
    uint32_t maxHeight = 0;
    for (size_t i = 0; i < g_savedBlocksCount; i++) {
        if (g_savedBlocks[i] && g_savedBlocks[i]->height > maxHeight) {
            maxHeight = g_savedBlocks[i]->height;
        }
    }
    return (jlong)maxHeight;
}

/* ---------- getWalletBirthCheckpointHeight ----------
 *
 * Height of the latest hardcoded checkpoint whose timestamp is at least a
 * week before g_walletCreationTime, mirroring the SPV chain-download
 * anchor in BRPeerManagerNewEx. Returns 0 if no wallet is loaded. Used as
 * the BIP 158 birth-height anchor for fresh wallets that have not yet
 * persisted any blocks.
 */
JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getWalletBirthCheckpointHeight(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_wallet || g_walletCreationTime == 0) return 0;

    uint32_t result = 0;
    const BRChainParams *params = &BRMainNetParams;
    for (size_t i = 0; i < params->checkpointsCount; i++) {
        if (i == 0 ||
            params->checkpoints[i].timestamp + 7*24*60*60 < g_walletCreationTime) {
            result = params->checkpoints[i].height;
        }
    }
    return (jlong)result;
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

/* ── Load saved blocks/peers ─────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_loadSavedBlocks(JNIEnv *env, jobject thiz,
                                                           jbyteArray data) {
    (void)thiz;
    if (!data) return 0;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 4) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return 0;

    uint8_t *b = (uint8_t *)buf;
    size_t pos = 0;
    uint32_t count = UInt32GetLE(&b[pos]); pos += 4;

    /* Free any previously loaded blocks */
    if (g_savedBlocks) {
        for (size_t i = 0; i < g_savedBlocksCount; i++) {
            if (g_savedBlocks[i]) BRMerkleBlockFree(g_savedBlocks[i]);
        }
        free(g_savedBlocks);
    }

    g_savedBlocks = malloc(count * sizeof(BRMerkleBlock *));
    g_savedBlocksCount = 0;

    for (uint32_t i = 0; i < count && pos + 8 <= (size_t)len; i++) {
        uint32_t blockLen = UInt32GetLE(&b[pos]); pos += 4;
        uint32_t height = UInt32GetLE(&b[pos]); pos += 4;

        if (pos + blockLen > (size_t)len) break;

        BRMerkleBlock *block = BRMerkleBlockParse(&b[pos], blockLen);
        pos += blockLen;

        if (block) {
            block->height = height;
            g_savedBlocks[g_savedBlocksCount++] = block;
        }
    }

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    LOGI("loadSavedBlocks: loaded %zu blocks from persistent storage", g_savedBlocksCount);
    return (jint)g_savedBlocksCount;
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_loadSavedPeers(JNIEnv *env, jobject thiz,
                                                          jbyteArray data) {
    (void)thiz;
    if (!data) return 0;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 4) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return 0;

    uint8_t *b = (uint8_t *)buf;
    size_t pos = 0;
    uint32_t count = UInt32GetLE(&b[pos]); pos += 4;

    if (g_savedPeers) free(g_savedPeers);
    g_savedPeers = calloc(count, sizeof(BRPeer));
    g_savedPeersCount = 0;

    size_t peerSize = 16 + 2 + 8 + 8; /* addr + port + timestamp + services */
    for (uint32_t i = 0; i < count && pos + peerSize <= (size_t)len; i++) {
        memcpy(&g_savedPeers[i].address, &b[pos], 16); pos += 16;
        g_savedPeers[i].port = UInt16GetLE(&b[pos]); pos += 2;
        g_savedPeers[i].timestamp = (uint32_t)UInt64GetLE(&b[pos]); pos += 8;
        g_savedPeers[i].services = UInt64GetLE(&b[pos]); pos += 8;
        g_savedPeersCount++;
    }

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    LOGI("loadSavedPeers: loaded %zu peers from persistent storage", g_savedPeersCount);
    return (jint)g_savedPeersCount;
}

/* ---------- BIP 158 bridge ---------- */

static void bridge_saveFilterHeaders(void *info, const BRCompactFilterChain *chain);

/* Pending state for BIP 158 setters that get called before the peer manager
 * exists. SyncService.kt configures sync mode / chain / auto-fetch BEFORE
 * calling startSync (which is where the peer manager is created), so without
 * this defer-and-apply layer the setters all no-op. Applied in
 * _applyPendingBip158State after BRPeerManagerSetCallbacks. */
static int      g_pendingSyncMode          = BR_SYNC_MODE_BLOOM_ONLY;
static int      g_pendingSyncModeSet       = 0;
static uint8_t *g_pendingFilterChain       = NULL;
static size_t   g_pendingFilterChainLen    = 0;
static int      g_pendingAutoFetchEnabled  = 0;
static uint32_t g_pendingAutoFetchStart    = 0;

static void _applyPendingBip158State(void) {
    if (!g_peerManager) return;

    if (g_pendingSyncModeSet) {
        BRPeerManagerSetSyncMode(g_peerManager, (BRSyncMode)g_pendingSyncMode);
        if ((BRSyncMode)g_pendingSyncMode != BR_SYNC_MODE_BLOOM_ONLY) {
            BRPeerManagerSetSaveFilterHeaders(g_peerManager, NULL, bridge_saveFilterHeaders);
        }
        LOGI("BIP158: applied pending syncMode=%d", g_pendingSyncMode);
        g_pendingSyncModeSet = 0;
    }

    if (g_pendingFilterChain && g_pendingFilterChainLen > 0) {
        BRCompactFilterChain *chain = BRCompactFilterChainDeserialize(g_pendingFilterChain,
                                                                       g_pendingFilterChainLen);
        if (chain) {
            BRPeerManagerSetCompactFilterChain(g_peerManager, chain);
            LOGI("BIP158: applied pending filter chain (%zu bytes)", g_pendingFilterChainLen);
        } else {
            LOGW("BIP158: pending filter chain failed to deserialize");
        }
        free(g_pendingFilterChain);
        g_pendingFilterChain = NULL;
        g_pendingFilterChainLen = 0;
    }

    if (g_pendingAutoFetchEnabled) {
        BRPeerManagerEnableAutoCompactFilterFetch(g_peerManager, g_pendingAutoFetchStart);
        uint32_t actual = BRPeerManagerGetAutoFetchCFiltersStart(g_peerManager);
        if (actual != g_pendingAutoFetchStart) {
            LOGI("BIP158: applied pending auto-fetch — requested %u, clamped to %u (in-memory window)",
                 g_pendingAutoFetchStart, actual);
        } else {
            LOGI("BIP158: applied pending auto-fetch from height %u", actual);
        }
        g_pendingAutoFetchEnabled = 0;
        g_pendingAutoFetchStart = 0;
    }
}

static void bridge_saveFilterHeaders(void *info, const BRCompactFilterChain *chain) {
    (void)info;
    if (!chain) return;

    JNIEnv *env = jni_get_env();
    if (!env || !g_callbackHandler) {
        LOGD("bridge_saveFilterHeaders: no JNI env, skipping persist");
        return;
    }

    if (!g_mid_onSaveFilterHeaders) {
        jclass cls = (*env)->GetObjectClass(env, g_callbackHandler);
        g_mid_onSaveFilterHeaders = (*env)->GetMethodID(env, cls, "onSaveFilterHeaders", "([B)V");
        (*env)->DeleteLocalRef(env, cls);
        if (!g_mid_onSaveFilterHeaders) {
            LOGW("bridge_saveFilterHeaders: onSaveFilterHeaders method not found");
            return;
        }
    }

    size_t need = BRCompactFilterChainSerialize(chain, NULL, 0);
    if (need == 0) return;
    uint8_t *buf = malloc(need);
    if (!buf) return;
    size_t got = BRCompactFilterChainSerialize(chain, buf, need);
    if (got != need) { free(buf); return; }

    jbyteArray jbuf = (*env)->NewByteArray(env, (jsize)got);
    (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)got, (jbyte *)buf);
    (*env)->CallVoidMethod(env, g_callbackHandler, g_mid_onSaveFilterHeaders, jbuf);
    (*env)->DeleteLocalRef(env, jbuf);
    free(buf);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setSyncMode(JNIEnv *env, jobject thiz, jint mode) {
    (void)env; (void)thiz;
    BRSyncMode m = (BRSyncMode)mode;
    if (!g_peerManager) {
        /* Defer until startSync creates the peer manager. */
        g_pendingSyncMode = mode;
        g_pendingSyncModeSet = 1;
        LOGI("setSyncMode: peer manager not yet created — deferred mode=%d", (int)mode);
        return;
    }
    BRPeerManagerSetSyncMode(g_peerManager, m);
    if (m != BR_SYNC_MODE_BLOOM_ONLY) {
        BRPeerManagerSetSaveFilterHeaders(g_peerManager, NULL, bridge_saveFilterHeaders);
    }
    LOGI("setSyncMode: mode=%d", (int)mode);
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getSyncMode(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) return 0;
    return (jint)BRPeerManagerGetSyncMode(g_peerManager);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setDandelionEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    (void)env; (void)thiz;
    if (!g_peerManager) {
        LOGI("setDandelionEnabled: peer manager not created — ignoring (re-applied on sync start)");
        return;
    }
    BRPeerManagerSetDandelionEnabled(g_peerManager, enabled ? 1 : 0);
    LOGI("setDandelionEnabled: %d", enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_addDandelionPeer(JNIEnv *env, jobject thiz, jstring ipStr) {
    (void)thiz;
    if (!g_peerManager || !ipStr) return;
    const char *ip = (*env)->GetStringUTFChars(env, ipStr, NULL);
    if (!ip) return;
    struct in_addr ip4;
    if (inet_pton(AF_INET, ip, &ip4) == 1) {
        UInt128 addr = UINT128_ZERO;
        addr.u16[5] = 0xffff;          /* IPv4-mapped IPv6 (::ffff:x.x.x.x) */
        addr.u32[3] = ip4.s_addr;
        BRPeerManagerAddDandelionPeer(g_peerManager, addr);
        LOGI("addDandelionPeer: %s", ip);
    }
    (*env)->ReleaseStringUTFChars(env, ipStr, ip);
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_hasDandelionPeer(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) return JNI_FALSE;
    return BRPeerManagerHasDandelionPeer(g_peerManager) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_fallbackToBloom(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) {
        LOGI("fallbackToBloom: peer manager not created — ignoring");
        return;
    }
    BRPeerManagerFallbackToBloom(g_peerManager);
    LOGI("fallbackToBloom: switched to BLOOM_ONLY, reloaded bloom on connected peers");
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getCFChainTipHeight(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) return 0;
    return (jint)BRPeerManagerCFChainTipHeight(g_peerManager);
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_reanchorCompactFilterChainAtFloor(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) {
        LOGI("reanchorCompactFilterChainAtFloor: peer manager not created — ignoring");
        return JNI_FALSE;
    }
    int r = BRPeerManagerReanchorCompactFilterChainAtFloor(g_peerManager);
    if (r) LOGI("reanchorCompactFilterChainAtFloor: re-anchored filter chain at block floor");
    return r ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getCompactFilterChain(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_peerManager) return NULL;

    const BRCompactFilterChain *chain = BRPeerManagerGetCompactFilterChain(g_peerManager);
    if (!chain) return NULL;

    size_t need = BRCompactFilterChainSerialize(chain, NULL, 0);
    if (need == 0) return NULL;
    uint8_t *buf = malloc(need);
    if (!buf) return NULL;
    size_t got = BRCompactFilterChainSerialize(chain, buf, need);
    if (got != need) { free(buf); return NULL; }

    jbyteArray jbuf = (*env)->NewByteArray(env, (jsize)got);
    (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)got, (jbyte *)buf);
    free(buf);
    return jbuf;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setCompactFilterChain(JNIEnv *env, jobject thiz, jbyteArray data) {
    (void)thiz;
    if (!data) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return JNI_FALSE;
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return JNI_FALSE;

    if (!g_peerManager) {
        /* Defer — store a copy until the peer manager is created in startSync. */
        if (g_pendingFilterChain) { free(g_pendingFilterChain); }
        g_pendingFilterChain = (uint8_t *)malloc((size_t)len);
        if (!g_pendingFilterChain) {
            (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
            LOGW("setCompactFilterChain: out of memory storing pending chain");
            return JNI_FALSE;
        }
        memcpy(g_pendingFilterChain, buf, (size_t)len);
        g_pendingFilterChainLen = (size_t)len;
        (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
        LOGI("setCompactFilterChain: peer manager not yet created — deferred (%d bytes)", (int)len);
        return JNI_TRUE;
    }

    BRCompactFilterChain *chain = BRCompactFilterChainDeserialize((const uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    if (!chain) {
        LOGW("setCompactFilterChain: deserialize failed (len=%d)", (int)len);
        return JNI_FALSE;
    }

    BRPeerManagerSetCompactFilterChain(g_peerManager, chain);
    LOGI("setCompactFilterChain: restored chain (%d bytes)", (int)len);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_requestCompactFilters(JNIEnv *env, jobject thiz,
                                                                jlong startHeight, jlong stopHeight) {
    (void)env; (void)thiz;
    if (!g_peerManager) return 0;
    if (startHeight < 0 || stopHeight < 0) return 0;
    size_t n = BRPeerManagerRequestCompactFilters(g_peerManager,
                                                  (uint32_t)startHeight, (uint32_t)stopHeight);
    return (jlong)n;
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_enableAutoCompactFilterFetch(JNIEnv *env, jobject thiz,
                                                                       jlong startHeight) {
    (void)env; (void)thiz;
    if (startHeight < 0) startHeight = 0;
    if (!g_peerManager) {
        g_pendingAutoFetchEnabled = 1;
        g_pendingAutoFetchStart = (uint32_t)startHeight;
        LOGI("enableAutoCompactFilterFetch: peer manager not yet created — deferred startHeight=%ld",
             (long)startHeight);
        return;
    }
    BRPeerManagerEnableAutoCompactFilterFetch(g_peerManager, (uint32_t)startHeight);
    LOGI("enableAutoCompactFilterFetch: startHeight=%ld", (long)startHeight);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_disableAutoCompactFilterFetch(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) return;
    BRPeerManagerDisableAutoCompactFilterFetch(g_peerManager);
    LOGI("disableAutoCompactFilterFetch");
}
