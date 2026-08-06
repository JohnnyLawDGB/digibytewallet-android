/*
 * jni_wallet.c
 *
 * JNI bridge for wallet operations: mnemonic generation, wallet creation,
 * session management, address derivation, balance, and address validation.
 *
 * All JNI function names match io.digibyte.core.bridge.NativeBridge.
 */

#include "jni_bridge.h"
#include "BRNetwork.h"
#include "BRDigiDollar.h"
#include "BRWalletFilterElements.h"

/* ---------- Global state definitions ---------- */

JavaVM       *g_jvm          = NULL;
BRWallet     *g_wallet       = NULL;
BRPeerManager *g_peerManager = NULL;
pthread_mutex_t g_peerManagerMutex;  /* recursive; initialized in JNI_OnLoad. Guards all g_peerManager access — see PEER_GUARD in jni_bridge.h */

/* PEER_GUARD holder tracking — see jni_bridge.h. Answers "who holds g_peerManagerMutex and for
 * how long" WITHOUT taking any lock, so a thread already blocked on the guard can report it. */
_Atomic double       g_peerGuardSinceMs = 0.0;
const char * _Atomic g_peerGuardFn      = NULL;
_Atomic int          g_peerGuardLine    = 0;
_Atomic int          g_peerGuardDepth   = 0;

double bridge_now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (double)tv.tv_sec * 1000.0 + (double)tv.tv_usec / 1000.0;
}
static uint8_t  g_seed[64];
static int      g_seedValid = 0;
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

    /* Recursive mutex serializing every g_peerManager access across the bridge
     * (jni_peer.c / jni_transaction.c / jni_wallet.c). Recursive so a future
     * nested guarded call on the same thread can't self-deadlock. */
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&g_peerManagerMutex, &attr);
    pthread_mutexattr_destroy(&attr);

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

/* ---------- isValidMnemonic ----------
 * Validates a BIP39 recovery phrase including the checksum (the last word
 * encodes a checksum over the entropy). Returns false for phrases that are the
 * right length and use real wordlist words but fail the checksum — the exact
 * case where a typo'd/made-up phrase would otherwise be accepted, build no
 * wallet, and leave sync stuck at "Connecting" forever. Lets the UI reject it
 * at input time. Does NOT create or touch any wallet state. */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isValidMnemonic(JNIEnv *env, jobject thiz, jstring phrase) {
    (void)thiz;
    if (!phrase) return JNI_FALSE;
    const char *phraseChars = (*env)->GetStringUTFChars(env, phrase, NULL);
    if (!phraseChars) return JNI_FALSE;
    int valid = BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars);
    (*env)->ReleaseStringUTFChars(env, phrase, phraseChars);
    return valid ? JNI_TRUE : JNI_FALSE;
}

/* ---------- setNetwork ----------
 * Runtime mainnet/testnet selection. MUST be called before any wallet or
 * peer-manager creation — BRSetNetwork() flips the process-global consulted
 * by BRAddress/BRKey (address version bytes) and by the peer-manager's
 * BRChainParams selection (jni_peer.c). Defaults to mainnet (isTestnet=0)
 * inside the C core itself (BRNetwork.c g_isTestnet = 0), so an app that
 * never calls this — or calls it with false — behaves exactly as before this
 * function existed. */
JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setNetwork(JNIEnv *env, jobject thiz, jboolean isTestnet) {
    (void)env;
    (void)thiz;
    BRSetNetwork(isTestnet ? 1 : 0);
    LOGI("setNetwork: isTestnet=%d", isTestnet ? 1 : 0);
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

    /* Derive master public key (BIP84: m/84'/20'/0') */
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    /* Taproot (BIP86: m/86'/20'/0') twin — derived from the SAME seed as BIP84 so the
     * P2TR receive chain shares the wallet's seed. Installed after BRWalletNew below. */
    BRMasterPubKey mpkBIP86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

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

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

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
                for (size_t i = 0; i < addrCount; i++) {
                    LOGI("createWallet: addr[%zu] = %s", i, addrs[i].s);
                }
                free(addrs);
            }
        }
    }
    LOGI("createWallet: wallet created successfully (creationTime=%u)", g_walletCreationTime);
    return JNI_TRUE;
}

/* ---------- createWalletFromBytes ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_createWalletFromBytes(JNIEnv *env, jobject thiz,
                                                                  jbyteArray phraseBytes) {
    (void)thiz;

    if (!phraseBytes) {
        LOGW("createWalletFromBytes: phraseBytes is null");
        return JNI_FALSE;
    }

    jsize phraseLen = (*env)->GetArrayLength(env, phraseBytes);
    if (phraseLen <= 0 || phraseLen > 1024) {
        LOGW("createWalletFromBytes: invalid length=%d", phraseLen);
        return JNI_FALSE;
    }

    /* Copy phrase bytes to a null-terminated C string on the stack */
    char phraseChars[phraseLen + 1];
    (*env)->GetByteArrayRegion(env, phraseBytes, 0, phraseLen, (jbyte *)phraseChars);
    phraseChars[phraseLen] = '\0';

    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("createWalletFromBytes: invalid BIP39 phrase");
        secure_zero(phraseChars, sizeof(phraseChars));
        return JNI_FALSE;
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    secure_zero(phraseChars, sizeof(phraseChars));

    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    /* Taproot (BIP86: m/86'/20'/0') twin from the SAME seed — installed after BRWalletNew. */
    BRMasterPubKey mpkBIP86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

    if (g_wallet) {
        LOGW("createWalletFromBytes: wallet already exists, freeing old one");
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) {
        LOGE("createWalletFromBytes: BRWalletNew failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpk;
    g_mpkValid = 1;
    /* A freshly-created wallet has no history before now, so stamp the real
     * creation time. getWalletBirthCheckpointHeight / BRPeerManagerNewEx then
     * anchor the SPV + BIP158 sync to the newest checkpoint >=1 week old — a
     * short, bounded catch-up that self-updates as checkpoints ship and never
     * goes stale. Matches createWallet (see above).
     *
     * (Previously hardcoded to 2025-02-01 to give the DigiRunner sync game
     * airtime, but a FIXED past date makes every new wallet re-scan an
     * ever-growing span — ~3.17M blocks / ~1.5 years by mid-2026 — hunting
     * for transactions that cannot exist before the wallet was created.) */
    g_walletCreationTime = (uint32_t)time(NULL);  /* New wallet = now */
    g_peerManagerNeedsRecreate = 1;

    secure_zero(seed, sizeof(seed));

    LOGI("createWalletFromBytes: wallet created successfully (creationTime=%u)", g_walletCreationTime);
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

    BRMasterPubKey mpkBIP84  = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));
    /* Taproot (BIP86: m/86'/20'/0') twin from the SAME seed — installed after the wallet
     * is built so the P2TR receive chain shares the wallet's seed. */
    BRMasterPubKey mpkBIP86  = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

    if (g_wallet) {
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    /* Use saved transactions if available — wallet starts with full history
     * so balance is immediately spendable without waiting for rescan.
     * Dual scan covers both BIP84 (m/84'/20'/0') and legacy (m/0H) paths. */
    extern BRTransaction **g_savedTransactions;
    extern size_t g_savedTransactionCount;

    if (g_savedTransactions && g_savedTransactionCount > 0) {
        LOGI("recoverWallet: restoring with %zu saved transactions", g_savedTransactionCount);
        g_wallet = BRWalletNewDual(g_savedTransactions, g_savedTransactionCount, mpkBIP84, mpkLegacy);
    } else {
        g_wallet = BRWalletNewDual(NULL, 0, mpkBIP84, mpkLegacy);
    }
    if (!g_wallet) {
        LOGE("recoverWallet: BRWalletNewDual failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpkBIP84;
    g_mpkValid = 1;

    secure_zero(seed, sizeof(seed));

    /* Use the user-provided creation timestamp for sync checkpoint selection */
    g_walletCreationTime = creationTimestamp > 0 ? (uint32_t)creationTimestamp : (uint32_t)time(NULL);
    g_peerManagerNeedsRecreate = 1;  /* Force peer manager rebuild on next startSync */

    /* Log all addresses for debugging bloom filter coverage */
    {
        size_t addrCount = BRWalletAllAddrs(g_wallet, NULL, 0);
        LOGI("recoverWallet: wallet has %zu addresses in bloom filter pool", addrCount);
        if (addrCount > 0 && addrCount < 300) {
            BRAddress *addrs = malloc(addrCount * sizeof(BRAddress));
            if (addrs) {
                BRWalletAllAddrs(g_wallet, addrs, addrCount);
                for (size_t i = 0; i < addrCount; i++) {
                    LOGI("recoverWallet: addr[%zu] = %s", i, addrs[i].s);
                }
                free(addrs);
            }
        }
    }
    LOGI("recoverWallet: wallet recovered, creationTime=%u", g_walletCreationTime);
    return JNI_TRUE;
}

/* ---------- recoverWalletFromBytes ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recoverWalletFromBytes(JNIEnv *env, jobject thiz,
                                                                   jbyteArray phraseBytes,
                                                                   jlong creationTimestamp) {
    (void)thiz;

    if (!phraseBytes) {
        LOGW("recoverWalletFromBytes: phraseBytes is null");
        return JNI_FALSE;
    }

    jsize phraseLen = (*env)->GetArrayLength(env, phraseBytes);
    if (phraseLen <= 0 || phraseLen > 1024) {
        LOGW("recoverWalletFromBytes: invalid length=%d", phraseLen);
        return JNI_FALSE;
    }

    char phraseChars[phraseLen + 1];
    (*env)->GetByteArrayRegion(env, phraseBytes, 0, phraseLen, (jbyte *)phraseChars);
    phraseChars[phraseLen] = '\0';

    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("recoverWalletFromBytes: invalid BIP39 phrase");
        secure_zero(phraseChars, sizeof(phraseChars));
        return JNI_FALSE;
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    secure_zero(phraseChars, sizeof(phraseChars));

    BRMasterPubKey mpkBIP84  = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));
    /* Taproot (BIP86: m/86'/20'/0') twin from the SAME seed — installed after the wallet
     * is built so the P2TR receive chain shares the wallet's seed. */
    BRMasterPubKey mpkBIP86  = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

    if (g_wallet) {
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    extern BRTransaction **g_savedTransactions;
    extern size_t g_savedTransactionCount;

    if (g_savedTransactions && g_savedTransactionCount > 0) {
        LOGI("recoverWalletFromBytes: restoring with %zu saved transactions", g_savedTransactionCount);
        g_wallet = BRWalletNewDual(g_savedTransactions, g_savedTransactionCount, mpkBIP84, mpkLegacy);
    } else {
        g_wallet = BRWalletNewDual(NULL, 0, mpkBIP84, mpkLegacy);
    }
    if (!g_wallet) {
        LOGE("recoverWalletFromBytes: BRWalletNewDual failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpkBIP84;
    g_mpkValid = 1;

    secure_zero(seed, sizeof(seed));

    g_walletCreationTime = creationTimestamp > 0 ? (uint32_t)creationTimestamp : (uint32_t)time(NULL);
    g_peerManagerNeedsRecreate = 1;

    LOGI("recoverWalletFromBytes: wallet recovered, creationTime=%u", g_walletCreationTime);
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

    seed_zero();
    LOGI("lockSession: seed zeroed");
}

/* ---------- Seed accessor functions ---------- */
/* These provide controlled access to g_seed so other .c files
 * never touch the global directly. */

int seed_is_valid(void) {
    return g_seedValid;
}

int seed_sign_transaction(BRWallet *wallet, BRTransaction *tx, int forkId) {
    if (!g_seedValid) return 0;
    return BRWalletSignTransaction(wallet, tx, forkId, g_seed, sizeof(g_seed));
}

int seed_derive_key(BRKey *outKey, uint32_t chain, uint32_t index) {
    if (!g_seedValid) return 0;
    BRBIP32PrivKey(outKey, g_seed, sizeof(g_seed), chain, index);
    return 1;
}

void seed_zero(void) {
    secure_zero(g_seed, sizeof(g_seed));
    g_seedValid = 0;
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

    /* format: 0=legacy(P2PKH), 1=p2sh-segwit (not directly supported), 2=bech32(P2WPKH),
     * 3=taproot(P2TR, dgb1p). BRWalletReceiveAddress threads its arg straight into
     * BRWalletUnusedAddrs' scriptType (0=P2PKH, 1=P2WPKH, 2=P2TR). */
    int scriptType = (format == 3) ? 2 : (format == 2) ? 1 : 0;

    BRAddress addr = BRWalletReceiveAddress(g_wallet, scriptType);
    if (addr.s[0] == '\0') {
        LOGW("getReceiveAddress: empty address returned");
        return NULL;
    }

    return (*env)->NewStringUTF(env, addr.s);
}

/* ---------- addWatchedAddresses ----------
 * Pin every Receive-screen address into the wallet's permanent watch set so a
 * receive to it is always in the BIP158 match set / balance detection, even if
 * derivation never reaches it after a restart. Idempotent; invalid entries ignored. */
JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_addWatchedAddresses(JNIEnv *env, jobject thiz,
                                                              jobjectArray addrs) {
    (void)thiz;
    if (!g_wallet || !addrs) return;
    jsize n = (*env)->GetArrayLength(env, addrs);
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, addrs, i);
        if (!js) continue;
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        if (s) {
            BRWalletAddWatchedAddress(g_wallet, s);
            (*env)->ReleaseStringUTFChars(env, js, s);
        }
        (*env)->DeleteLocalRef(env, js);
    }
}

/* ---------- getDigiDollarReceiveAddress ----------
 *
 * The wallet's canonical DigiDollar receive address: the BIP86 taproot owner key at
 * m/86'/20'/0'/0/0 (index 0 of the same watched P2TR receive chain the wallet already
 * scans), tap-tweaked to its output key X(Q) and Base58Check-encoded as "TD…" (testnet) /
 * "DD…" (mainnet) for the active runtime network. This is the SAME output key as the
 * wallet's first dgbt1p… P2TR address, so DigiDollar sent here is detected and spendable
 * with the key we sign with (seed_sign_transaction derives the same path). Requires an
 * unlocked session (g_seedValid). Returns null if locked or on derivation failure. */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getDigiDollarReceiveAddress(JNIEnv *env, jobject thiz) {
    (void)thiz;

    if (!g_seedValid) {
        LOGW("getDigiDollarReceiveAddress: session locked (no seed)");
        return NULL;
    }

    BRKey key;
    memset(&key, 0, sizeof(key));
    BRBIP32PrivKeyBIP86(&key, g_seed, sizeof(g_seed), 0, 0); /* m/86'/20'/0'/0/0 owner key */

    uint8_t outputKey[32];
    int ok = BRKeyTaprootOutputKey(&key, outputKey); /* X(Q): BIP341 tap-tweaked output key */
    BRKeyClean(&key);                                /* zero the private key immediately */
    if (!ok) {
        LOGW("getDigiDollarReceiveAddress: taproot output-key derivation failed");
        return NULL;
    }

    char addr[128];
    memset(addr, 0, sizeof(addr));
    size_t n = BRDigiDollarAddressEncode(addr, sizeof(addr), outputKey, BRNetworkIsTestnet());
    if (n == 0) {
        LOGW("getDigiDollarReceiveAddress: address encode failed");
        return NULL;
    }

    return (*env)->NewStringUTF(env, addr);
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

/* ---------- getDigiDollarBalance (cents) ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getDigiDollarBalance(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_wallet) return 0;
    return (jlong)BRWalletDigiDollarBalance(g_wallet);
}

/* ---------- isWalletLoaded ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isWalletLoaded(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return g_wallet != NULL ? JNI_TRUE : JNI_FALSE;
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

/* ---------- getAllTransactionHashes ---------- */

/*
 * public static native String[] getAllTransactionHashes();
 *
 * Returns every wallet-known transaction's display-order hex txid.
 * Unlike getTransactionDetails() this does not truncate to 50 and
 * does not compute balances, making it cheap enough to use as input
 * to a full native-asset-detection sweep after sync completion.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getAllTransactionHashes(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_wallet) return NULL;

    size_t txCount = BRWalletTransactions(g_wallet, NULL, 0);
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)txCount, stringClass, NULL);
    if (!result) return NULL;
    if (txCount == 0) return result;

    BRTransaction **txs = malloc(txCount * sizeof(BRTransaction *));
    if (!txs) return result;
    txCount = BRWalletTransactions(g_wallet, txs, txCount);

    for (size_t i = 0; i < txCount; i++) {
        BRTransaction *tx = txs[i];
        if (!tx) continue;
        char hashHex[65];
        for (int j = 0; j < 32; j++) {
            sprintf(&hashHex[j * 2], "%02x", tx->txHash.u8[31 - j]);
        }
        hashHex[64] = '\0';
        jstring s = (*env)->NewStringUTF(env, hashHex);
        if (s) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    free(txs);
    return result;
}

/* ---------- getTransactionDetails ----------
 * Returns a pipe-separated string for each transaction:
 * "txHash|amount|fee|blockHeight|timestamp|sent|received\n..."
 * Amount is signed: positive = received, negative = sent.
 * sent/received are unsigned raw values for self-send detection.
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

    /* Build result string — estimate 160 chars per tx (added sent|received fields) */
    size_t bufSize = txCount * 160 + 1;
    char *buf = malloc(bufSize);
    if (!buf) { free(txs); return (*env)->NewStringUTF(env, ""); }
    buf[0] = '\0';
    size_t pos = 0;

    /* BRWalletTransactions returns txs sorted by date OLDEST-FIRST (BRWallet.c).
     * To cap at the 100 MOST RECENT we must start near the end of the array, not
     * at index 0 — otherwise a wallet with >100 txs keeps its oldest 100 and drops
     * every new send past the cap (balance still updates because it sums all txs,
     * but the new tx never appears in the list). Start offset = txCount-100. */
    size_t startIdx = (txCount > 100) ? (txCount - 100) : 0;
    for (size_t i = startIdx; i < txCount; i++) { /* the 100 most recent */
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
            "%s|%lld|%llu|%u|%u|%llu|%llu\n",
            hashHex,
            (long long)amount,
            (unsigned long long)fee,
            tx->blockHeight,
            ts,
            (unsigned long long)sent,
            (unsigned long long)received);
        if (written > 0) pos += written;
    }

    free(txs);
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

/* ---------- getDerivationPath ---------- */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getDerivationPath(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, "m/84'/20'/0'");
}

/* ---------- hasLegacyFunds ---------- */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_hasLegacyFunds(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    if (!g_wallet) return JNI_FALSE;
    return BRWalletHasLegacyFunds(g_wallet) ? JNI_TRUE : JNI_FALSE;
}

/* ---------- dumpAllAddresses ---------- */
/* Diagnostic: returns all wallet addresses (BIP84 external + internal + all
 * legacy chains) as a newline-separated string for on-chain cross-checking.
 * Used to answer "is a missing UTXO on an address we're not scanning?" */

JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_dumpAllAddresses(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    if (!g_wallet) return (*env)->NewStringUTF(env, "");

    /* Single-call snapshot. The old two-call BRWalletAllAddrs form sized `count` from one
     * call and refilled from another with the wallet lock released in between, then looped
     * to the RETURN value -- so a chain growing in that window produced a heap over-read
     * here as well as the over-write in the fill. */
    size_t count = 0;
    BRAddress *addrs = BRWalletCopyAllAddrs(g_wallet, &count, NULL);
    if (!addrs || count == 0) { free(addrs); return (*env)->NewStringUTF(env, ""); }

    /* Each address is up to ~62 chars for bech32; reserve 80/address for safety */
    size_t cap = count * 80 + 1;
    char *buf = (char *)malloc(cap);
    if (!buf) { free(addrs); return (*env)->NewStringUTF(env, ""); }
    size_t off = 0;
    for (size_t i = 0; i < count; i++) {
        size_t len = strnlen(addrs[i].s, sizeof(addrs[i].s));
        if (off + len + 2 >= cap) break;
        memcpy(buf + off, addrs[i].s, len);
        off += len;
        buf[off++] = '\n';
    }
    buf[off] = '\0';

    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    free(addrs);
    return result;
}

/* ---------- getFilterElementStats ----------
 * Counters from the most recent BIP158 filter-element build, as
 *   addrs|elements|derived|watched|dropped|allocFailures|firstDroppedPrefix
 * or "" if no build has happened yet.
 *
 * `derived` vs `watched` is the element count BY SOURCE: addresses from the derived
 * chains vs explicitly-watched Receive pins. There is no DigiDollar bucket -- a DD
 * token output is a plain P2TR script, so its element comes from the taproot chain and
 * is counted as derived (see BRWalletFilterElements.h).
 *
 * `dropped` counts addresses with no encodable scriptPubKey. It should be 0; a non-zero
 * value means BRAddressIsValid and BRAddressScriptPubKey have diverged and the match set
 * is quietly smaller than the address set. firstDroppedPrefix is the first 6 characters
 * ONLY -- never a full address, since these counters are surfaced to logs.
 *
 * Takes NO lock (the underlying accessor uses a private leaf mutex), so it must not be
 * given PEER_GUARD: it touches neither g_peerManager nor g_wallet internals, and taking
 * that guard would add contention plus a future deadlock foothold against
 * BRPeerManagerFree's peer-thread join. */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getFilterElementStats(JNIEnv *env, jobject thiz)
{
    (void)thiz;

    BRWalletFilterElementsStats st;
    if (! BRWalletFilterElementsGetStats(g_wallet, &st)) return (*env)->NewStringUTF(env, "");

    char buf[192];
    snprintf(buf, sizeof(buf), "%zu|%zu|%zu|%zu|%zu|%zu|%s",
             st.addrs, st.elements, st.derived, st.watched, st.dropped, st.allocFailures,
             st.firstDroppedPrefix);
    return (*env)->NewStringUTF(env, buf);
}

/* ---------- walletContainsAddress (test-only) ---------- */
/* Test hook for the taproot watch-set coherence tests: returns true if `addr`
 * was previously generated by the wallet (present in wallet->allAddrs). Thin
 * wrapper over BRWalletContainsAddress. Not called from production code. */

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_walletContainsAddress(JNIEnv *env, jobject thiz,
                                                                jstring address)
{
    (void)thiz;
    if (!g_wallet || !address) return JNI_FALSE;

    const char *addrChars = (*env)->GetStringUTFChars(env, address, NULL);
    if (!addrChars) return JNI_FALSE;

    int contained = BRWalletContainsAddress(g_wallet, addrChars);
    (*env)->ReleaseStringUTFChars(env, address, addrChars);
    return contained ? JNI_TRUE : JNI_FALSE;
}

/* ---------- outpointSpentState ---------- */
/* Sovereign, chain-derived spent-state for an asset outpoint, as a tri-state:
 *    0 = SPENT       (the outpoint is in the wallet's spentOutputs set)
 *    1 = HELD        (the wallet knows the funding tx and the outpoint is unspent)
 *   -1 = UNDETECTED  (the wallet doesn't know the funding tx yet — e.g. a
 *                     backend-sourced row the SPV sync hasn't reached)
 * The reconcile marks a Room asset row spent on 0, unspent on 1, and leaves it
 * unchanged on -1 (so a mid-sync wallet never hides a real holding). Uses
 * BRWalletOutpointSpent (the authoritative spentOutputs set), NOT the asset-UTXO
 * array, which is never pruned of spends. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_outpointSpentState(JNIEnv *env, jobject thiz,
                                                             jstring txHashHex, jint vout)
{
    (void)thiz;
    if (!g_wallet || !txHashHex || vout < 0) return -1;

    const char *hashStr = (*env)->GetStringUTFChars(env, txHashHex, NULL);
    if (!hashStr) return -1;

    jint state = -1;
    if (strlen(hashStr) == 64) {
        UInt256 hash = UInt256Reverse(uint256(hashStr)); /* display BE -> internal LE */
        if (BRWalletOutpointSpent(g_wallet, hash, (uint32_t)vout)) {
            state = 0;                                   /* spent */
        } else if (BRWalletTransactionForHash(g_wallet, hash)) {
            state = 1;                                   /* known + unspent = held */
        } else {
            state = -1;                                  /* undetected */
        }
    }
    (*env)->ReleaseStringUTFChars(env, txHashHex, hashStr);
    return state;
}
