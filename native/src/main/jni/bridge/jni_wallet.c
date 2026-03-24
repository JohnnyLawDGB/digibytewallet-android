/*
 * jni_wallet.c
 *
 * JNI bridge for wallet operations: mnemonic generation, wallet creation,
 * session management, address derivation, balance, and address validation.
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 */

#include "jni_bridge.h"

/* ---------- Global state definitions ---------- */

JavaVM       *g_jvm          = NULL;
BRWallet     *g_wallet       = NULL;
BRPeerManager *g_peerManager = NULL;
uint8_t       g_seed[64];
int           g_seedValid    = 0;
BRMasterPubKey g_mpk;
int           g_mpkValid     = 0;
uint32_t      g_walletCreationTime = 0;
int           g_peerManagerNeedsRecreate = 0;

/* Callback globals — defined here, used by jni_peer.c via extern */
jobject   g_callbackHandler  = NULL;
jmethodID g_mid_onSyncProgress         = NULL;
jmethodID g_mid_onTransactionReceived  = NULL;
jmethodID g_mid_onPeerConnected        = NULL;
jmethodID g_mid_onPeerDisconnected     = NULL;
jmethodID g_mid_onSyncComplete         = NULL;
jmethodID g_mid_onSyncFailed           = NULL;
jmethodID g_mid_onBalanceChanged       = NULL;

/* ---------- JNI_OnLoad ---------- */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad: core-lib loaded, JVM cached");
    return JNI_VERSION_1_6;
}

/* ---------- generateMnemonic ---------- */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_generateMnemonic(JNIEnv *env, jobject thiz, jint entropyBits) {
    (void)thiz;

    /* Validate entropy: BIP39 supports 128, 160, 192, 224, 256 bits */
    if (entropyBits < 128 || entropyBits > 256 || (entropyBits % 32) != 0) {
        LOGW("generateMnemonic: invalid entropyBits=%d", entropyBits);
        return NULL;
    }

    size_t entropyLen = (size_t)(entropyBits / 8);
    uint8_t entropy[32]; /* max 256 bits = 32 bytes */

    /* Generate cryptographic random entropy */
    /* BRRand is NOT cryptographic — use /dev/urandom directly */
    FILE *f = fopen("/dev/urandom", "rb");
    if (!f) {
        LOGE("generateMnemonic: failed to open /dev/urandom");
        return NULL;
    }
    size_t read = fread(entropy, 1, entropyLen, f);
    fclose(f);
    if (read != entropyLen) {
        LOGE("generateMnemonic: short read from /dev/urandom");
        secure_zero(entropy, sizeof(entropy));
        return NULL;
    }

    /* Encode entropy to BIP39 mnemonic */
    size_t phraseLen = BRBIP39Encode(NULL, 0, BRBIP39WordsEn, entropy, entropyLen);
    if (phraseLen == 0) {
        LOGE("generateMnemonic: BRBIP39Encode returned 0");
        secure_zero(entropy, sizeof(entropy));
        return NULL;
    }

    char phrase[phraseLen];
    BRBIP39Encode(phrase, sizeof(phrase), BRBIP39WordsEn, entropy, entropyLen);

    /* Zero entropy immediately */
    secure_zero(entropy, sizeof(entropy));

    jstring result = (*env)->NewStringUTF(env, phrase);

    /* Zero the phrase buffer */
    secure_zero(phrase, sizeof(phrase));

    LOGD("generateMnemonic: generated %d-bit mnemonic", entropyBits);
    return result;
}

/* ---------- createWallet ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_createWallet(JNIEnv *env, jobject thiz, jstring phrase) {
    (void)thiz;

    if (!phrase) {
        LOGW("createWallet: phrase is null");
        return JNI_FALSE;
    }

    const char *phraseChars = (*env)->GetStringUTFChars(env, phrase, NULL);
    if (!phraseChars) return JNI_FALSE;

    /* Validate the phrase against BIP39 word list */
    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("createWallet: invalid BIP39 phrase");
        (*env)->ReleaseStringUTFChars(env, phrase, phraseChars);
        return JNI_FALSE;
    }

    /* Derive 512-bit seed from mnemonic */
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    (*env)->ReleaseStringUTFChars(env, phrase, phraseChars);

    /* Derive master public key */
    BRMasterPubKey mpk = BRBIP32MasterPubKey(seed, sizeof(seed));

    /* Create wallet with no initial transactions */
    if (g_wallet) {
        LOGW("createWallet: wallet already exists, freeing old one");
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) {
        LOGE("createWallet: BRWalletNew failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    /* Store seed and MPK for session use */
    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpk;
    g_mpkValid = 1;
    g_walletCreationTime = (uint32_t)time(NULL);  /* New wallet = now */
    g_peerManagerNeedsRecreate = 1;  /* Force peer manager rebuild on next startSync */

    secure_zero(seed, sizeof(seed));

    /* Diagnostic: log how many addresses the wallet generated */
    {
        size_t addrCount = BRWalletAllAddrs(g_wallet, NULL, 0);
        LOGI("createWallet: wallet has %zu addresses in bloom filter pool", addrCount);
        if (addrCount > 0 && addrCount < 100) {
            BRAddress *addrs = malloc(addrCount * sizeof(BRAddress));
            if (addrs) {
                BRWalletAllAddrs(g_wallet, addrs, addrCount);
                for (size_t i = 0; i < addrCount && i < 5; i++) {
                    LOGI("createWallet: addr[%zu] = %s", i, addrs[i].s);
                }
                if (addrCount > 5) LOGI("createWallet: ... and %zu more", addrCount - 5);
                free(addrs);
            }
        }
    }
    LOGI("createWallet: wallet created successfully (creationTime=%u)", g_walletCreationTime);
    return JNI_TRUE;
}

/* ---------- recoverWallet ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recoverWallet(JNIEnv *env, jobject thiz,
                                                         jstring phrase, jlong creationTimestamp) {
    (void)thiz;

    if (!phrase) {
        LOGW("recoverWallet: phrase is null");
        return JNI_FALSE;
    }

    const char *phraseChars = (*env)->GetStringUTFChars(env, phrase, NULL);
    if (!phraseChars) return JNI_FALSE;

    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("recoverWallet: invalid BIP39 phrase");
        (*env)->ReleaseStringUTFChars(env, phrase, phraseChars);
        return JNI_FALSE;
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    (*env)->ReleaseStringUTFChars(env, phrase, phraseChars);

    BRMasterPubKey mpk = BRBIP32MasterPubKey(seed, sizeof(seed));

    if (g_wallet) {
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) {
        LOGE("recoverWallet: BRWalletNew failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpk;
    g_mpkValid = 1;

    secure_zero(seed, sizeof(seed));

    /* Use the user-provided creation timestamp for sync checkpoint selection */
    g_walletCreationTime = creationTimestamp > 0 ? (uint32_t)creationTimestamp : (uint32_t)time(NULL);
    g_peerManagerNeedsRecreate = 1;  /* Force peer manager rebuild on next startSync */
    LOGI("recoverWallet: wallet recovered, creationTime=%u", g_walletCreationTime);
    return JNI_TRUE;
}

/* ---------- unlockSession ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_unlockSession(JNIEnv *env, jobject thiz,
                                                         jbyteArray authToken) {
    (void)thiz;

    if (!authToken) {
        LOGW("unlockSession: authToken is null");
        return JNI_FALSE;
    }

    jsize tokenLen = (*env)->GetArrayLength(env, authToken);
    if (tokenLen <= 0) {
        LOGW("unlockSession: authToken is empty");
        return JNI_FALSE;
    }

    /* In a full implementation, the authToken would be a Keystore-decrypted
       seed blob. For now, we accept a raw seed (64 bytes) or mnemonic phrase. */
    if (tokenLen == 64) {
        jbyte *tokenBytes = (*env)->GetByteArrayElements(env, authToken, NULL);
        if (!tokenBytes) return JNI_FALSE;

        memcpy(g_seed, tokenBytes, 64);
        g_seedValid = 1;

        (*env)->ReleaseByteArrayElements(env, authToken, tokenBytes, JNI_ABORT);
        LOGI("unlockSession: session unlocked with 64-byte seed");
        return JNI_TRUE;
    }

    LOGW("unlockSession: unexpected authToken length=%d", tokenLen);
    return JNI_FALSE;
}

/* ---------- lockSession ---------- */

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_lockSession(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    secure_zero(g_seed, sizeof(g_seed));
    g_seedValid = 0;
    LOGI("lockSession: seed zeroed");
}

/* ---------- getReceiveAddress ---------- */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getReceiveAddress(JNIEnv *env, jobject thiz,
                                                             jint index, jint format) {
    (void)thiz;

    if (!g_wallet) {
        LOGW("getReceiveAddress: wallet not initialized");
        return NULL;
    }

    /* format: 0=legacy, 1=p2sh-segwit (not directly supported), 2=bech32 */
    int useSegwit = (format == 2) ? 1 : 0;

    BRAddress addr = BRWalletReceiveAddress(g_wallet, useSegwit);
    if (addr.s[0] == '\0') {
        LOGW("getReceiveAddress: empty address returned");
        return NULL;
    }

    return (*env)->NewStringUTF(env, addr.s);
}

/* ---------- getChangeAddress ---------- */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getChangeAddress(JNIEnv *env, jobject thiz,
                                                            jint index, jint format) {
    (void)thiz;
    (void)index;
    (void)format;

    if (!g_wallet) {
        LOGW("getChangeAddress: wallet not initialized");
        return NULL;
    }

    BRAddress addr = BRWalletInternalChangeAddress(g_wallet);
    if (addr.s[0] == '\0') {
        LOGW("getChangeAddress: empty address returned");
        return NULL;
    }

    return (*env)->NewStringUTF(env, addr.s);
}

/* ---------- getBalance ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getBalance(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_wallet) return 0;
    return (jlong)BRWalletBalance(g_wallet);
}

/* ---------- isValidAddress ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isValidAddress(JNIEnv *env, jobject thiz,
                                                          jstring address) {
    (void)thiz;

    if (!address) return JNI_FALSE;

    const char *addrChars = (*env)->GetStringUTFChars(env, address, NULL);
    if (!addrChars) return JNI_FALSE;

    /* Empty string check */
    if (addrChars[0] == '\0') {
        (*env)->ReleaseStringUTFChars(env, address, addrChars);
        return JNI_FALSE;
    }

    int valid = BRAddressIsValid(addrChars);
    (*env)->ReleaseStringUTFChars(env, address, addrChars);

    return valid ? JNI_TRUE : JNI_FALSE;
}

/* ---------- getTransactionCount ---------- */

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getTransactionCount(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_wallet) return 0;
    return (jint)BRWalletTransactions(g_wallet, NULL, 0);
}

/* ---------- getTransactionDetails ----------
 * Returns a pipe-separated string for each transaction:
 * "txHash|amount|fee|blockHeight|timestamp\n..."
 * Amount is signed: positive = received, negative = sent.
 */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getTransactionDetails(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_wallet) return (*env)->NewStringUTF(env, "");

    size_t txCount = BRWalletTransactions(g_wallet, NULL, 0);
    if (txCount == 0) return (*env)->NewStringUTF(env, "");

    BRTransaction **txs = malloc(txCount * sizeof(BRTransaction *));
    if (!txs) return (*env)->NewStringUTF(env, "");
    txCount = BRWalletTransactions(g_wallet, txs, txCount);

    /* Build result string — estimate 120 chars per tx */
    size_t bufSize = txCount * 120 + 1;
    char *buf = malloc(bufSize);
    if (!buf) { free(txs); return (*env)->NewStringUTF(env, ""); }
    buf[0] = '\0';
    size_t pos = 0;

    for (size_t i = 0; i < txCount && i < 50; i++) { /* limit to 50 most recent */
        BRTransaction *tx = txs[i];
        if (!tx) continue;

        /* Calculate amount: received - sent */
        uint64_t received = BRWalletAmountReceivedFromTx(g_wallet, tx);
        uint64_t sent = BRWalletAmountSentByTx(g_wallet, tx);
        int64_t amount = (int64_t)received - (int64_t)sent;
        uint64_t fee = BRWalletFeeForTx(g_wallet, tx);

        /* txHash as hex string */
        char hashHex[65];
        for (int j = 0; j < 32; j++) {
            sprintf(&hashHex[j*2], "%02x", tx->txHash.u8[31 - j]);
        }
        hashHex[64] = '\0';

        /* Use tx timestamp if available, otherwise fall back to current time.
         * tx->timestamp is 0 until the block header is processed by the peer manager. */
        uint32_t ts = tx->timestamp ? tx->timestamp : (uint32_t)time(NULL);

        int written = snprintf(buf + pos, bufSize - pos,
            "%s|%lld|%llu|%u|%u\n",
            hashHex,
            (long long)amount,
            (unsigned long long)fee,
            tx->blockHeight,
            ts);
        if (written > 0) pos += written;
    }

    free(txs);
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}
