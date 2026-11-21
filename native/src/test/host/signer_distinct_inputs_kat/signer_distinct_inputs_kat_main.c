// Host KAT: every native signer that takes its inputs from the caller checks, before it signs, that
// every input names a different outpoint (FOREIGN-ASSET-DISTINCT).
//
// THE INVARIANT. A transaction with a repeated input is refused: nothing is signed and no coin of it
// is left claimed. It holds for each signer whose inputs are the caller's list:
//   buildAndSignLegacySweep            (jni_derive.c)       the caller's outpoint arrays
//   buildAndSignForeignAssetTransfer   (jni_derive.c)       the caller's outpoint arrays
//   signTransaction                    (jni_transaction.c)  the caller's unsigned bytes
//   signPreparedTransaction            (jni_transaction.c)  the caller's prepared bytes
// (The wallet asset signer and the DigiDollar recovery signer make the same check and have their own
// suites: asset_send_kat, dd_transfer_guard_kat. run.sh's source gate holds all six to it.)
//
// THE SIGNERS ARE THE SHIPPING ONES. run.sh compiles the bridge's own jni_derive.c, jni_transaction.c
// and jni_wallet.c over identity_sign_bytes_kat's host JNI stand-ins, and every case calls the native
// itself. Two calls of the bridge are counted on their way to the core (run.sh renames them in the
// bridge's units only): BRTransactionSign, which the recovery signers sign with, and
// BRWalletSignTransaction, which seed_sign_transaction signs with. "Nothing is signed" is that count
// staying where it was.
//
// COMPARISON ARMS. One per signer: run.sh builds the bridge from a copy in which that signer's check
// never fires (the signer as it was before this row), and requires exactly that signer's
// *_repeated_input_refused case to fail there -- a transaction with a repeated input comes back
// signed and its coin is left claimed -- and every other case to pass.
// GUARD (every arm): valid_vectors_sign_unchanged. Transactions with distinct inputs, two outputs of
// one transaction among them, sign; their bytes hash to the digests pinned here, which were taken from
// the bridge before the check was added, and run.sh requires the VECTOR lines to be the same in
// every arm.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include <jni.h>
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRKey.h"
#include "BRCrypto.h"
#include "BRAddress.h"
#include "BRBIP39Mnemonic.h"
#include "BRBIP32Sequence.h"
#include "BRPeerManager.h"
#include "coin_reservation.h"

// ---- the bridge -------------------------------------------------------------------------------
extern BRWallet *g_wallet;
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);
jboolean JNICALL NativeBridge_recoverWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                     jlong creationTimestamp, jbyteArray passphrase);
jstring JNICALL NativeBridge_buildAndSignLegacySweep(JNIEnv *env, jobject thiz, jbyteArray seedBytes, jstring hmacKey,
                                                     jintArray prefixPath, jobjectArray txidsHex, jintArray vouts,
                                                     jlongArray amounts, jintArray chainIndices,
                                                     jintArray addressIndices, jobjectArray scriptPubKeysHex,
                                                     jstring destAddress, jlong feePerKb);
jstring JNICALL NativeBridge_buildAndSignForeignAssetTransfer(JNIEnv *env, jobject thiz, jbyteArray seedBytes,
                                                              jstring hmacKey, jintArray prefixPath,
                                                              jobjectArray txidsHex, jintArray vouts, jlongArray amounts,
                                                              jintArray chainIndices, jintArray addressIndices,
                                                              jobjectArray scriptPubKeysHex, jobjectArray outputAddresses,
                                                              jlongArray outputAmounts, jobjectArray outputScriptsHex,
                                                              jlong feePerKb);
jbyteArray JNICALL NativeBridge_signTransaction(JNIEnv *env, jobject thiz, jbyteArray unsignedTx);
jbyteArray JNICALL NativeBridge_signPreparedTransaction(JNIEnv *env, jobject thiz, jbyteArray unsignedTx,
                                                        jstring toAddress, jlong amountSats, jlong approvedFeeSats);

// The bridge's externals this suite does not exercise.
int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }
static void notHere(const char *fn) { printf("FAIL: setup: %s was called\n", fn); exit(3); }
jobjectArray bridge_transaction_outputs(JNIEnv *e, const BRTransaction *tx, const char *who)
{ (void)e; (void)tx; (void)who; notHere("bridge_transaction_outputs"); return NULL; }
// jni_transaction.c's publish entry points reach the peer manager; no signer here does, and no manager exists.
BRServerHeightResult BRPeerManagerApplyServerHeight(BRPeerManager *m, UInt256 h, uint32_t height, uint32_t ts)
{ (void)m; (void)h; (void)height; (void)ts; notHere("BRPeerManagerApplyServerHeight"); return (BRServerHeightResult)0; }
void BRPeerManagerPublishTx(BRPeerManager *m, BRTransaction *tx, void *info, void (*cb)(void *info, int error))
{ (void)m; (void)tx; (void)info; (void)cb; notHere("BRPeerManagerPublishTx"); }
int BRPeerManagerStemPublishTx(BRPeerManager *m, BRTransaction *tx, void *info, void (*cb)(void *info, int error))
{ (void)m; (void)tx; (void)info; (void)cb; notHere("BRPeerManagerStemPublishTx"); return 0; }
void BRPeerManagerRemoveTransaction(BRPeerManager *m, UInt256 h) { (void)m; (void)h; notHere("BRPeerManagerRemoveTransaction"); }
size_t BRPeerManagerRelayCount(BRPeerManager *m, UInt256 h) { (void)m; (void)h; notHere("BRPeerManagerRelayCount"); return 0; }
int BRPeerManagerTxRefusedByNetwork(BRPeerManager *m, UInt256 h) { (void)m; (void)h; notHere("BRPeerManagerTxRefusedByNetwork"); return 0; }
int BRPeerManagerHasDandelionPeer(BRPeerManager *m) { (void)m; notHere("BRPeerManagerHasDandelionPeer"); return 0; }
void BRPeerManagerFluffTx(BRPeerManager *m, UInt256 h) { (void)m; (void)h; notHere("BRPeerManagerFluffTx"); }

// The two signing calls of the bridge, counted (run.sh: -DBRTransactionSign=kat_tx_sign on jni_derive.c,
// -DBRWalletSignTransaction=kat_wallet_sign on jni_wallet.c; this file is compiled without either).
static int g_txSignCalls = 0, g_walletSignCalls = 0;
int kat_tx_sign(BRTransaction *tx, int forkId, BRKey keys[], size_t keysCount)
{
    g_txSignCalls++;
    return BRTransactionSign(tx, forkId, keys, keysCount);
}
int kat_wallet_sign(BRWallet *wallet, BRTransaction *tx, int forkId, const void *seed, size_t seedLen)
{
    g_walletSignCalls++;
    return BRWalletSignTransaction(wallet, tx, forkId, seed, seedLen);
}

// ---- host JNI env -----------------------------------------------------------------------------
typedef struct { jsize len; void *data; } HostArray;
typedef struct { char *text; } HostString;

#define ARENA_MAX 8192
static void *g_arena[ARENA_MAX];
static size_t g_arenaCount = 0;
static void *keep(void *p) { if (g_arenaCount < ARENA_MAX) g_arena[g_arenaCount++] = p; else abort(); return p; }
static void arena_free(void) { while (g_arenaCount) free(g_arena[--g_arenaCount]); }
static HostArray *hostArray(const void *data, size_t elem, jsize len)
{
    HostArray *a = keep(calloc(1, sizeof(*a)));
    a->len = len;
    a->data = keep(calloc((size_t)(len ? len : 1), elem));
    if (data && len) memcpy(a->data, data, elem * (size_t)len);
    return a;
}
static HostString *hostString(const char *s)
{
    HostString *h = keep(calloc(1, sizeof(*h)));
    h->text = keep(strdup(s));
    return h;
}
static jsize h_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HostArray *)a)->len; }
static jbyte *h_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static void h_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize l, jbyte *buf) { (void)e; memcpy(buf, (jbyte *)((HostArray *)a)->data + s, (size_t)l); }
static jbyteArray h_NewByteArray(JNIEnv *e, jsize len) { (void)e; return hostArray(NULL, 1, len); }
static void h_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize l, const jbyte *buf) { (void)e; memcpy((jbyte *)((HostArray *)a)->data + s, buf, (size_t)l); }
static jint *h_GetIntArrayElements(JNIEnv *e, jintArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseIntArrayElements(JNIEnv *e, jintArray a, jint *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static jlong *h_GetLongArrayElements(JNIEnv *e, jlongArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseLongArrayElements(JNIEnv *e, jlongArray a, jlong *el, jint m) { (void)e; (void)a; (void)el; (void)m; }
static jobject h_GetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i) { (void)e; return ((jobject *)((HostArray *)a)->data)[i]; }
static void h_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e; (void)o; }
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { (void)e; if (c) *c = JNI_TRUE; return strdup(((HostString *)s)->text); }
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; free((void *)c); }
static jstring h_NewStringUTF(JNIEnv *e, const char *b) { (void)e; return hostString(b); }

static const struct JNINativeInterface g_table = {
    .GetArrayLength = h_GetArrayLength,
    .GetByteArrayElements = h_GetByteArrayElements,
    .ReleaseByteArrayElements = h_ReleaseByteArrayElements,
    .GetByteArrayRegion = h_GetByteArrayRegion,
    .NewByteArray = h_NewByteArray,
    .SetByteArrayRegion = h_SetByteArrayRegion,
    .GetIntArrayElements = h_GetIntArrayElements,
    .ReleaseIntArrayElements = h_ReleaseIntArrayElements,
    .GetLongArrayElements = h_GetLongArrayElements,
    .ReleaseLongArrayElements = h_ReleaseLongArrayElements,
    .GetObjectArrayElement = h_GetObjectArrayElement,
    .DeleteLocalRef = h_DeleteLocalRef,
    .GetStringUTFChars = h_GetStringUTFChars,
    .ReleaseStringUTFChars = h_ReleaseStringUTFChars,
    .NewStringUTF = h_NewStringUTF,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;
static jint h_GetEnv(JavaVM *vm, void **out, jint version) { (void)vm; (void)version; *out = (void *)env; return JNI_OK; }
static const struct JNIInvokeInterface g_invoke = { .GetEnv = h_GetEnv };
static JavaVM g_vmValue = &g_invoke;

// ---- checks -----------------------------------------------------------------------------------
static int g_failed = 0;
static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_failed = 1;
}

static void hexOf(const uint8_t *b, size_t n, char *out)
{
    for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]);
    out[2 * n] = '\0';
}
static size_t bytesOf(const char *hex, uint8_t *out, size_t cap)
{
    size_t n = strlen(hex) / 2;
    if (n > cap) return 0;
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(hex + 2 * i, "%2x", &v); out[i] = (uint8_t)v; }
    return n;
}

// ---- the recovered wallet's coins, and a foreign seed's ------------------------------------------
#define H 0x80000000u
#define RATE 100000LL   // 100 sat/byte: the rate every caller passes

static const char *kWalletMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *kForeignMnemonic =
    "legal winner thank year wave sausage worth useful legal winner thank yellow";

static uint8_t g_foreignSeed[64];
static char g_destAddr[75];      // an address neither seed owns
static uint8_t g_destSpk[22];
static BRAddress g_ownAddr;      // the wallet's native-segwit receive address
static uint8_t g_ownSpk[64];
static size_t g_ownSpkLen;

// A coin of the foreign seed: P2PKH at m/44'/20'/0'/0/<index>, the previous transaction id every byte `fill`.
typedef struct { uint8_t fill; uint32_t vout; uint32_t index; uint64_t sats; int upper; } Coin;

static void foreignScript(uint32_t index, uint8_t out[25])
{
    const uint32_t path[5] = { 44 | H, 20 | H, 0 | H, 0, index };
    BRKey k;
    UInt160 h;
    BRBIP32PrivKeyArrayPath(&k, g_foreignSeed, sizeof(g_foreignSeed), "Bitcoin seed", path, 5);
    h = BRKeyHash160(&k);
    BRKeyClean(&k);
    out[0] = 0x76; out[1] = 0xa9; out[2] = 0x14; memcpy(out + 3, h.u8, 20); out[23] = 0x88; out[24] = 0xac;
}

// The input arrays every recovery signer takes, as its Kotlin caller writes them.
typedef struct { HostArray *seed, *prefix, *txids, *vouts, *amounts, *chains, *indices, *scripts; HostString *hmac; } InArgs;
static InArgs inArgs(const Coin *c, size_t n)
{
    const jint prefix[3] = { (jint)(44 | H), (jint)(20 | H), (jint)(0 | H) };
    jobject *txids = calloc(n, sizeof(jobject)), *scripts = calloc(n, sizeof(jobject));
    jint *vouts = calloc(n, sizeof(jint)), *chains = calloc(n, sizeof(jint)), *idx = calloc(n, sizeof(jint));
    jlong *amts = calloc(n, sizeof(jlong));
    InArgs a;
    for (size_t i = 0; i < n; i++) {
        char id[65], sh[51];
        uint8_t spk[25];
        for (int b = 0; b < 32; b++) sprintf(id + 2 * b, c[i].upper ? "%02X" : "%02x", c[i].fill);
        foreignScript(c[i].index, spk);
        hexOf(spk, sizeof(spk), sh);
        txids[i] = hostString(id); scripts[i] = hostString(sh);
        vouts[i] = (jint)c[i].vout; amts[i] = (jlong)c[i].sats; chains[i] = 0; idx[i] = (jint)c[i].index;
    }
    a.seed = hostArray(g_foreignSeed, 1, sizeof(g_foreignSeed));
    a.hmac = hostString("Bitcoin seed");
    a.prefix = hostArray(prefix, sizeof(jint), 3);
    a.txids = hostArray(txids, sizeof(jobject), (jsize)n);
    a.vouts = hostArray(vouts, sizeof(jint), (jsize)n);
    a.amounts = hostArray(amts, sizeof(jlong), (jsize)n);
    a.chains = hostArray(chains, sizeof(jint), (jsize)n);
    a.indices = hostArray(idx, sizeof(jint), (jsize)n);
    a.scripts = hostArray(scripts, sizeof(jobject), (jsize)n);
    free(txids); free(scripts); free(vouts); free(chains); free(idx); free(amts);
    return a;
}

// The sweep of these coins to g_destAddr: the signed hex, or NULL.
static const char *sweep(const Coin *c, size_t n)
{
    InArgs a = inArgs(c, n);
    HostString *r = NativeBridge_buildAndSignLegacySweep(env, NULL, a.seed, a.hmac, a.prefix, a.txids, a.vouts, a.amounts,
                                                         a.chains, a.indices, a.scripts, hostString(g_destAddr), RATE);
    return r ? r->text : NULL;
}

// A transfer in the asset recovery layout: the destination, an OP_RETURN (a raw script), and what is
// left of the coins after `fee` back to the destination.
static const char *foreignTransfer(const Coin *c, size_t n, uint64_t fee)
{
    InArgs a = inArgs(c, n);
    uint64_t total = 0;
    for (size_t i = 0; i < n; i++) total += c[i].sats;
    jobject addrs[3] = { hostString(g_destAddr), hostString(""), hostString(g_destAddr) };
    jobject raws[3] = { hostString(""), hostString("6a0844410315000a01c0"), hostString("") };
    jlong amts[3] = { 6000, 0, (jlong)(total - fee - 6000) };
    HostString *r = NativeBridge_buildAndSignForeignAssetTransfer(env, NULL, a.seed, a.hmac, a.prefix, a.txids, a.vouts,
        a.amounts, a.chains, a.indices, a.scripts, hostArray(addrs, sizeof(jobject), 3), hostArray(amts, sizeof(jlong), 3),
        hostArray(raws, sizeof(jobject), 3), RATE);
    return r ? r->text : NULL;
}
// What foreign_tx_fee_check expects of `n` inputs and three outputs at RATE.
static uint64_t transferFee(size_t n) { return (10 + 160 * (uint64_t)n + 34 * 3) * (uint64_t)RATE / 1000; }

// 1 when none of these coins is claimed: another send may claim every one of them.
static int foreignCoinsFree(const Coin *c, size_t n)
{
    BRTxInput *in = calloc(n, sizeof(*in));
    UInt256 other;
    int free_;
    for (size_t i = 0; i < n; i++) { memset(in[i].txHash.u8, c[i].fill, 32); in[i].index = c[i].vout; }
    memset(other.u8, 0xee, sizeof(other));
    free_ = coin_reservation_claim(other, in, n, coin_reservation_now());
    coin_reservation_release(other);
    free(in);
    return free_;
}

// ---- the wallet's own coins ---------------------------------------------------------------------
static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A confirmed transaction with `outs` outputs of `sats` each to the wallet's receive address; its id.
static UInt256 fund(uint8_t tag, uint64_t sats, size_t outs)
{
    static const uint8_t placeholder[1] = { 0 };
    BRTransaction *c = BRTransactionNew();
    UInt256 h, id;
    memset(h.u8, tag, sizeof(h.u8));
    c->version = 1;
    BRTransactionAddInput(c, h, 0, 0, g_ownSpk, g_ownSpkLen, placeholder, 0, placeholder, 0, 0xffffffff);
    for (size_t i = 0; i < outs; i++) BRTransactionAddOutput(c, sats, g_ownSpk, g_ownSpkLen);
    c->blockHeight = 1900000;
    c->timestamp = 1700000000;
    finalizeTxHash(c);
    id = c->txHash;
    if (!BRWalletRegisterTransaction(g_wallet, c)) { printf("FATAL: a funding transaction did not register\n"); exit(2); }
    return id;
}

typedef struct { UInt256 hash; uint32_t n; uint64_t sats; } Own;

// The unsigned bytes of a send of these wallet coins: `pay` to g_destAddr, the rest less `fee` back to
// the wallet -- what createTransaction hands Kotlin and Kotlin hands back to be signed.
static HostArray *unsignedBytes(const Own *c, size_t n, uint64_t pay, uint64_t fee)
{
    BRTransaction *tx = BRTransactionNew();
    uint64_t total = 0;
    size_t len;
    uint8_t *buf;
    HostArray *a;
    for (size_t i = 0; i < n; i++) {
        BRTransactionAddInput(tx, c[i].hash, c[i].n, c[i].sats, g_ownSpk, g_ownSpkLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        total += c[i].sats;
    }
    BRTransactionAddOutput(tx, pay, g_destSpk, sizeof(g_destSpk));
    BRTransactionAddOutput(tx, total - pay - fee, g_ownSpk, g_ownSpkLen);
    len = BRTransactionSerialize(tx, NULL, 0);
    buf = malloc(len);
    len = BRTransactionSerialize(tx, buf, len);
    a = hostArray(buf, 1, (jsize)len);
    free(buf);
    BRTransactionFree(tx);
    return a;
}

static int ownCoinsFree(const Own *c, size_t n)
{
    BRTxInput *in = calloc(n, sizeof(*in));
    UInt256 other;
    int free_;
    for (size_t i = 0; i < n; i++) { in[i].txHash = c[i].hash; in[i].index = c[i].n; }
    memset(other.u8, 0xee, sizeof(other));
    free_ = coin_reservation_claim(other, in, n, coin_reservation_now());
    coin_reservation_release(other);
    free(in);
    return free_;
}

#define PAY 300000ULL
#define FEE 14100ULL

static const char *signTx(const Own *c, size_t n)
{
    HostArray *r = NativeBridge_signTransaction(env, NULL, unsignedBytes(c, n, PAY, FEE));
    char *hex;
    if (!r) return NULL;
    hex = keep(malloc((size_t)r->len * 2 + 1));
    hexOf(r->data, (size_t)r->len, hex);
    return hex;
}
static const char *signPrepared(const Own *c, size_t n)
{
    HostArray *r = NativeBridge_signPreparedTransaction(env, NULL, unsignedBytes(c, n, PAY, FEE), hostString(g_destAddr),
                                                        (jlong)PAY, (jlong)FEE);
    char *hex;
    if (!r) return NULL;
    hex = keep(malloc((size_t)r->len * 2 + 1));
    hexOf(r->data, (size_t)r->len, hex);
    return hex;
}

// ---- the two outcomes ---------------------------------------------------------------------------

// A valid transaction: it comes back signed, its bytes parse as a signed transaction of `inputs`
// inputs and hash to the pinned digest. Prints VECTOR <name> <hex>.
static void signsUnchanged(const char *name, const char *hex, size_t inputs, const char *digestHex)
{
    uint8_t raw[4096];
    char digest[65];
    UInt256 d;
    size_t len;
    BRTransaction *tx;

    ck(hex != NULL, "%s: signed and handed back", name);
    if (!hex) return;
    len = bytesOf(hex, raw, sizeof(raw));
    tx = len ? BRTransactionParse(raw, len) : NULL;
    ck(tx && BRTransactionIsSigned(tx) && tx->inCount == inputs, "%s: a signed transaction of %zu inputs", name, inputs);
    if (tx) BRTransactionFree(tx);
    BRSHA256_2(&d, raw, len);
    hexOf(d.u8, 32, digest);
    ck(strcmp(digest, digestHex) == 0, "%s: the signed bytes hash to the pinned digest (got %s)", name, digest);
    printf("VECTOR %s %s\n", name, hex);
    coin_reservation_clear();
}

// A transaction with a repeated input: nothing comes back, the signing call was never reached, and
// none of its coins is left claimed.
static void refusedNow(const char *what, const char *hex, int signCallsBefore, int signCallsNow, int coinsFree)
{
    ck(hex == NULL, "%s: no signed transaction is handed back", what);
    ck(signCallsNow == signCallsBefore, "%s: nothing is signed (the signing call is not reached)", what);
    ck(coinsFree, "%s: none of its coins is left claimed (another send may claim them all)", what);
    coin_reservation_clear();
}

// ---- cases --------------------------------------------------------------------------------------

static void valid_vectors_sign_unchanged(void)
{
    // The legacy sweep: one coin; two coins; two outputs of one transaction (two outpoints).
    const Coin one[] = { { 0x41, 0, 0, 500000, 0 } };
    signsUnchanged("sweep_one_coin", sweep(one, 1), 1, 
                   "d9b4d2f018c6095d7c80696947d80bea8bb34d0437faeaea4718294f2088b577");
    const Coin two[] = { { 0x41, 0, 0, 500000, 0 }, { 0x42, 1, 1, 700000, 0 } };
    signsUnchanged("sweep_two_coins", sweep(two, 2), 2, 
                   "261b6a9765afb11b7a05ee53c76c146deddce10876c4c73b5039932bcbdeb196");
    const Coin sib[] = { { 0x41, 0, 0, 500000, 0 }, { 0x41, 1, 1, 700000, 0 } };
    signsUnchanged("sweep_sibling_outputs", sweep(sib, 2), 2, 
                   "89958903087d64aae9b3eb8cc97d4923891bb6086652bd6c5447f57cde313848");

    // The asset recovery transfer: an asset coin and a fee coin; a second fee coin; sibling outputs.
    const Coin at[] = { { 0x51, 0, 0, 6000, 0 }, { 0x52, 0, 1, 100000, 0 } };
    signsUnchanged("foreign_asset_two_coins", foreignTransfer(at, 2, transferFee(2)), 2, 
                   "9b5f49e7480ae8cb1651c8b77bc55e879244e637eefa33a9398c20b2fb3a92af");
    const Coin at3[] = { { 0x51, 0, 0, 6000, 0 }, { 0x52, 0, 1, 100000, 0 }, { 0x53, 2, 2, 100000, 0 } };
    signsUnchanged("foreign_asset_three_coins", foreignTransfer(at3, 3, transferFee(3)), 3, 
                   "6317f3387b3cf2ef47f418b4eedd79dbdc8b37407d47d052e40f6d15731251ea");
    const Coin atSib[] = { { 0x51, 0, 0, 6000, 0 }, { 0x51, 1, 1, 100000, 0 }, { 0x51, 2, 2, 100000, 0 } };
    signsUnchanged("foreign_asset_sibling_outputs", foreignTransfer(atSib, 3, transferFee(3)), 3, 
                   "3e1e59ce25468d3d466ee1796af63e8fc3328005f323a499f902fcd75b7271ff");

    // The wallet's own coins, as bytes: one coin; two coins; two outputs of one transaction.
    UInt256 a = fund(0xA1, 1000000, 1), b = fund(0xA2, 800000, 1), s = fund(0xA3, 600000, 2);
    const Own w1[] = { { a, 0, 1000000 } };
    const Own w2[] = { { a, 0, 1000000 }, { b, 0, 800000 } };
    const Own wSib[] = { { s, 0, 600000 }, { s, 1, 600000 } };
    signsUnchanged("sign_transaction_one_coin", signTx(w1, 1), 1, 
                   "baf62033009a2eaa7c2bfe74ef5443455685083b84b528731def137009ff1495");
    signsUnchanged("sign_transaction_two_coins", signTx(w2, 2), 2, 
                   "e3715a92e77579750ffcbbefc9e931e2b8fe051de4c0536f9253463cd8c1651b");
    signsUnchanged("sign_transaction_sibling_outputs", signTx(wSib, 2), 2, 
                   "9a624281419db14ed0fa35161c3802a72981c5b05392ecc2e08698ed953e32a3");
    signsUnchanged("sign_prepared_one_coin", signPrepared(w1, 1), 1, 
                   "baf62033009a2eaa7c2bfe74ef5443455685083b84b528731def137009ff1495");
    signsUnchanged("sign_prepared_two_coins", signPrepared(w2, 2), 2, 
                   "e3715a92e77579750ffcbbefc9e931e2b8fe051de4c0536f9253463cd8c1651b");
    signsUnchanged("sign_prepared_sibling_outputs", signPrepared(wSib, 2), 2, 
                   "9a624281419db14ed0fa35161c3802a72981c5b05392ecc2e08698ed953e32a3");
}

static void sweep_repeated_input_refused(void)
{
    int before = g_txSignCalls;
    const Coin twice[] = { { 0x41, 0, 0, 500000, 0 }, { 0x41, 0, 0, 500000, 0 } };
    const char *r = sweep(twice, 2);
    refusedNow("a sweep listing one coin twice", r, before, g_txSignCalls, foreignCoinsFree(twice, 2));

    before = g_txSignCalls;
    const Coin apart[] = { { 0x41, 0, 0, 500000, 0 }, { 0x42, 1, 1, 700000, 0 }, { 0x41, 0, 0, 500000, 0 } };
    r = sweep(apart, 3);
    refusedNow("a sweep listing one coin twice with another between", r, before, g_txSignCalls, foreignCoinsFree(apart, 3));

    // One outpoint is one outpoint however its id is spelled.
    before = g_txSignCalls;
    const Coin spelled[] = { { 0xab, 0, 0, 500000, 0 }, { 0xab, 0, 0, 500000, 1 } };
    r = sweep(spelled, 2);
    refusedNow("a sweep listing one coin twice, its id in lower and in upper case", r, before, g_txSignCalls,
               foreignCoinsFree(spelled, 2));
}

static void foreign_asset_repeated_input_refused(void)
{
    int before = g_txSignCalls;
    const Coin fee[] = { { 0x51, 0, 0, 6000, 0 }, { 0x52, 0, 1, 100000, 0 }, { 0x52, 0, 1, 100000, 0 } };
    const char *r = foreignTransfer(fee, 3, transferFee(3));
    refusedNow("a transfer listing a fee coin twice", r, before, g_txSignCalls, foreignCoinsFree(fee, 3));

    before = g_txSignCalls;
    const Coin asset[] = { { 0x51, 0, 0, 60000, 0 }, { 0x51, 0, 0, 60000, 0 }, { 0x52, 0, 1, 100000, 0 } };
    r = foreignTransfer(asset, 3, transferFee(3));
    refusedNow("a transfer listing the asset coin twice", r, before, g_txSignCalls, foreignCoinsFree(asset, 3));

    before = g_txSignCalls;
    const Coin apart[] = { { 0x52, 0, 1, 100000, 0 }, { 0x51, 0, 0, 6000, 0 }, { 0x53, 2, 2, 100000, 0 }, { 0x52, 0, 1, 100000, 0 } };
    r = foreignTransfer(apart, 4, transferFee(4));
    refusedNow("a transfer listing a fee coin twice with other coins between", r, before, g_txSignCalls,
               foreignCoinsFree(apart, 4));

    before = g_txSignCalls;
    const Coin spelled[] = { { 0x51, 0, 0, 6000, 0 }, { 0xcd, 0, 1, 100000, 0 }, { 0xcd, 0, 1, 100000, 1 } };
    r = foreignTransfer(spelled, 3, transferFee(3));
    refusedNow("a transfer listing a fee coin twice, its id in lower and in upper case", r, before, g_txSignCalls,
               foreignCoinsFree(spelled, 3));
}

static void sign_transaction_repeated_input_refused(void)
{
    UInt256 a = fund(0xA1, 1000000, 1), b = fund(0xA2, 800000, 1);
    int before = g_walletSignCalls;
    const Own twice[] = { { a, 0, 1000000 }, { a, 0, 1000000 } };
    const char *r = signTx(twice, 2);
    refusedNow("bytes listing one wallet coin twice", r, before, g_walletSignCalls, ownCoinsFree(twice, 2));

    before = g_walletSignCalls;
    const Own apart[] = { { a, 0, 1000000 }, { b, 0, 800000 }, { a, 0, 1000000 } };
    r = signTx(apart, 3);
    refusedNow("bytes listing one wallet coin twice with another between", r, before, g_walletSignCalls, ownCoinsFree(apart, 3));
}

static void sign_prepared_repeated_input_refused(void)
{
    UInt256 a = fund(0xA1, 1000000, 1), b = fund(0xA2, 800000, 1);
    int before = g_walletSignCalls;
    const Own twice[] = { { a, 0, 1000000 }, { a, 0, 1000000 } };
    const char *r = signPrepared(twice, 2);
    refusedNow("prepared bytes listing one wallet coin twice", r, before, g_walletSignCalls, ownCoinsFree(twice, 2));

    before = g_walletSignCalls;
    const Own apart[] = { { a, 0, 1000000 }, { b, 0, 800000 }, { a, 0, 1000000 } };
    r = signPrepared(apart, 3);
    refusedNow("prepared bytes listing one wallet coin twice with another between", r, before, g_walletSignCalls,
               ownCoinsFree(apart, 3));
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "valid_vectors_sign_unchanged", valid_vectors_sign_unchanged },
    { "sweep_repeated_input_refused", sweep_repeated_input_refused },
    { "foreign_asset_repeated_input_refused", foreign_asset_repeated_input_refused },
    { "sign_transaction_repeated_input_refused", sign_transaction_repeated_input_refused },
    { "sign_prepared_repeated_input_refused", sign_prepared_repeated_input_refused },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (JNI_OnLoad(&g_vmValue, NULL) != JNI_VERSION_1_6) { printf("FATAL: the bridge did not boot\n"); return 2; }
    if (NativeBridge_recoverWalletFromBytes(env, NULL, hostArray(kWalletMnemonic, 1, (jsize)strlen(kWalletMnemonic)),
                                            1700000000, NULL) != JNI_TRUE || !g_wallet) {
        printf("FATAL: the bridge did not recover the wallet\n");
        return 2;
    }
    BRWalletSetBlockHeight(g_wallet, 2000000);
    BRBIP39DeriveKey(g_foreignSeed, kForeignMnemonic, NULL);
    g_destSpk[0] = 0x00; g_destSpk[1] = 0x14; memset(g_destSpk + 2, 0x5a, 20);
    BRAddressFromScriptPubKey(g_destAddr, sizeof(g_destAddr), g_destSpk, sizeof(g_destSpk));
    g_ownAddr = BRWalletReceiveAddress(g_wallet, 1);
    g_ownSpkLen = BRAddressScriptPubKey(g_ownSpk, sizeof(g_ownSpk), g_ownAddr.s);
    if (g_destAddr[0] == '\0' || g_ownSpkLen == 0) { printf("FATAL: an address did not encode\n"); return 2; }
    coin_reservation_clear();

    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        kCases[i].run();
        arena_free();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
