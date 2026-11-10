// Host KAT: the server-transaction import and confirmation, moved from the JNI bridge into the core
// (BRServerTxImport.h), do exactly what the bridge does.
//
// Every scenario builds two identical fixtures (same seed, same chain, same filter peer): fixture A is driven
// through the bridge's own functions (jni_transaction.c: NativeBridge_registerRawTransaction,
// NativeBridge_confirmTransaction, NativeBridge_takeUnprovableServerHeight, compiled here over the host JNI stand-ins
// of identity_sign_bytes_kat), fixture B through the core's (BRServerTxImport, BRServerTxConfirm,
// BRServerTxUnprovableNoteTake). A scenario passes only when both give:
//   - the same boolean answer (the bridge's return, BRServerTxImportAdded / the confirm's return), and the core's
//     typed result is the one the scenario names;
//   - the same unprovable-height note (taken from both after the step), equal to the scenario's;
//   - the same calls into BRPeerManagerApplyServerHeight (count, height, timestamp), recorded by kat_apply;
//   - the same wallet afterwards: the serialized transactions byte for byte, the balance, total received and sent,
//     the selectable outputs, and whether the wallet has a record of the scenario's hash;
//   - the same peer traffic (getcfilters / getdata the manager sent).
// The manager is the real BRPeerManager.c (included below), with the peer calls intercepted by -D renames in run.sh,
// like server_height_recheck_kat. A scripted answer replaces the manager's only where the shipped manager cannot give
// it (BR_SERVER_HEIGHT_TAKEN, which it no longer returns), so the promotion mapping is covered too.
//
// Comparison arms (server_tx_import_kat_core.c): KAT_RED_KEEP_UNPROVABLE and KAT_RED_NO_DUP_CHECK each change one call
// in the core's procedure; run.sh requires each to fail exactly its scenarios and pass every other one.
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "jni_bridge.h"           // the bridge's globals and types, over host_jni/<jni.h>
#include "BRServerTxImport.h"     // types only; the procedure is called through server_tx_import_kat_core.c
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

// ---- the bridge's globals (jni_wallet.c defines them in the app) ------------------------------------------------
BRWallet *g_wallet = NULL;
BRPeerManager *g_peerManager = NULL;
pthread_mutex_t g_peerManagerMutex;
pthread_rwlock_t g_walletRwLock = PTHREAD_RWLOCK_INITIALIZER;
_Atomic double       g_peerGuardSinceMs = 0.0;
const char * _Atomic g_peerGuardFn      = NULL;
_Atomic int          g_peerGuardLine    = 0;
_Atomic int          g_peerGuardDepth   = 0;
int g_walletSwapped = 0;

double bridge_now_ms(void) { return 1.0; }
// Not on the paths this suite drives: reaching one is a failure of the suite.
static void notHere(const char *fn) { printf("FAIL: setup: %s was called\n", fn); exit(3); }
jobjectArray bridge_transaction_outputs(JNIEnv *e, const BRTransaction *tx, const char *who)
{ (void)e; (void)tx; (void)who; notHere("bridge_transaction_outputs"); return NULL; }
void coin_reservation_release(UInt256 owner) { (void)owner; notHere("coin_reservation_release"); }
int seed_is_valid(void) { notHere("seed_is_valid"); return 0; }
int seed_sign_transaction(BRWallet *w, BRTransaction *tx, int forkId)
{ (void)w; (void)tx; (void)forkId; notHere("seed_sign_transaction"); return 0; }

static int g_verbose = 0;
int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    (void)prio; (void)tag;
    if (! g_verbose) return 0;
    va_list ap;
    va_start(ap, fmt);
    printf("   [bridge] ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
    return 0;
}

// ---- host JNI env: byte arrays and strings ----------------------------------------------------------------------
typedef struct { jsize len; jbyte *data; } HostArray;
typedef struct { char *text; } HostString;
static jsize h_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HostArray *)a)->len; }
static jbyte *h_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *isCopy)
{ (void)e; if (isCopy) *isCopy = JNI_FALSE; return ((HostArray *)a)->data; }
static void h_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *el, jint mode) { (void)e; (void)a; (void)el; (void)mode; }
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *isCopy)
{ (void)e; if (isCopy) *isCopy = JNI_TRUE; return strdup(((HostString *)s)->text); }
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; free((void *)c); }
static const struct JNINativeInterface g_table = {
    .GetArrayLength = h_GetArrayLength,
    .GetByteArrayElements = h_GetByteArrayElements,
    .ReleaseByteArrayElements = h_ReleaseByteArrayElements,
    .GetStringUTFChars = h_GetStringUTFChars,
    .ReleaseStringUTFChars = h_ReleaseStringUTFChars,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;

jboolean JNICALL NativeBridge_registerRawTransaction(JNIEnv *env, jobject thiz, jbyteArray rawTx, jlong blockHeight,
                                                     jlong blockTimestamp);
jboolean JNICALL NativeBridge_confirmTransaction(JNIEnv *env, jobject thiz, jstring txHashHex, jlong blockHeight,
                                                 jlong blockTimestamp);
jlong JNICALL NativeBridge_takeUnprovableServerHeight(JNIEnv *env, jobject thiz);

// ---- the core side (server_tx_import_kat_core.c) ----------------------------------------------------------------
BRServerTxImportResult kat_core_import(BRWallet *wallet, BRPeerManager *manager, const uint8_t *buf, size_t len,
                                       int64_t blockHeight, int64_t blockTimestamp, BRServerTxUnprovableNote *note,
                                       BRServerTxHeightLog *log);
int kat_core_confirm(BRWallet *wallet, BRPeerManager *manager, UInt256 txHash, int64_t blockHeight,
                     int64_t blockTimestamp, BRServerTxUnprovableNote *note, BRServerTxHeightLog *log);
uint32_t kat_core_take(BRServerTxUnprovableNote *note);

// ---- checks -------------------------------------------------------------------------------------------------------
static int g_fail = 0, g_scenarioFail = 0;
static const char *g_scenario = "";
static void check(int c, const char *d)
{
    if (! c) { printf("   FAIL: %s: %s\n", g_scenario, d); g_fail++; g_scenarioFail++; }
}

// ---- fixture (the chain of server_height_recheck_kat) ---------------------------------------------------------------
#define H0 5300000u
#define H  (H0 + 1)
#define DEEP (H0 - 1000)
#define MAX_CALLS 8

typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeer *peer;
    BRMerkleBlock *b0, *b;
    int live;
    int getcf, getdata;
    uint32_t getcfStart;
    int applyCalls;
    uint32_t applyH[MAX_CALLS], applyT[MAX_CALLS];
} Fx;

static Fx g_fx[2];   // [0] = the bridge's, [1] = the core's
static int g_scripted = -1;   // -1: the manager answers; otherwise kat_apply answers this

static Fx *fxForManager(BRPeerManager *m) { return m == g_fx[0].m ? &g_fx[0] : m == g_fx[1].m ? &g_fx[1] : NULL; }
static Fx *fxForPeer(BRPeer *p) { return p == g_fx[0].peer ? &g_fx[0] : p == g_fx[1].peer ? &g_fx[1] : NULL; }

BRServerHeightResult kat_apply(BRPeerManager *manager, UInt256 txHash, uint32_t height, uint32_t timestamp)
{
    Fx *f = fxForManager(manager);
    if (! f) { printf("FAIL: setup: apply on an unknown manager\n"); exit(3); }
    if (f->applyCalls < MAX_CALLS) { f->applyH[f->applyCalls] = height; f->applyT[f->applyCalls] = timestamp; }
    f->applyCalls++;
    if (g_scripted >= 0) return (BRServerHeightResult)g_scripted;
    return BRPeerManagerApplyServerHeight(manager, txHash, height, timestamp);
}

BRPeerStatus __wrap_BRPeerConnectStatus(BRPeer *peer)
{
    Fx *f = fxForPeer(peer);
    return (f && f->live) ? BRPeerStatusConnected : BRPeerStatusDisconnected;
}
int __wrap_BRPeerIsSocketOpen(BRPeer *peer) { Fx *f = fxForPeer(peer); return f && f->live; }
void __wrap_BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)filterType; (void)stopHash;
    Fx *f = fxForPeer(peer);
    if (f) { f->getcf++; f->getcfStart = startHeight; }
}
void __wrap_BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount)
{
    (void)blockHashes;
    Fx *f = fxForPeer(peer);
    if (f) f->getdata += (int)blockCount;
}

// BRTransactionIsSigned as both sides call it (-DBRTransactionIsSigned=kat_is_signed in run.sh). Bytes
// BRTransactionParseSigned accepts always carry a signature and a witness for every input, so the unsigned branch
// both sides have cannot be reached from bytes; a scenario forces the answer to cover it.
static int g_forceUnsigned = 0;
int kat_is_signed(const BRTransaction *tx) { return g_forceUnsigned ? 0 : BRTransactionIsSigned(tx); }

static const uint8_t kPlaceholder[1] = {0};
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A tx paying `amount` to spk from a prevout no wallet owns. signed_ = 0: the input carries no signature, so it
// serializes in the internal unsigned form; read in the signed format that is still a (different) signed tx.
static BRTransaction *payTx(const uint8_t *spk, size_t spkLen, uint64_t amount, uint8_t prevoutSeed, int signed_)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;
    memset(prevHash.u8, prevoutSeed, sizeof(prevHash.u8));
    if (signed_)
        BRTransactionAddInput(tx, prevHash, 0, 0, spk, spkLen, kPlaceholder, sizeof(kPlaceholder),
                              kPlaceholder, sizeof(kPlaceholder), 0xffffffff);
    else
        BRTransactionAddInput(tx, prevHash, 0, 0, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}

static BRMerkleBlock *blockAt(uint32_t height, uint8_t hashSeed, uint32_t timestamp)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = timestamp;
    return b;
}

static void walletScript(BRWallet *w, uint8_t *spk, size_t *spkLen)
{
    BRAddress addr = BRWalletReceiveAddress(w, 1);
    *spkLen = BRAddressScriptPubKey(spk, 64, addr.s);
}

static void foreignScript(uint8_t *spk, size_t *spkLen)
{
    // P2WPKH of a key no fixture wallet derives
    spk[0] = 0x00; spk[1] = 0x14;
    memset(spk + 2, 0x5A, 20);
    *spkLen = 22;
}

static void fxInit(Fx *f, int live)
{
    memset(f, 0, sizeof(*f));
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, mpk);
    assert(f->w != NULL);
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(f->m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    f->b0 = blockAt(H0, 0xD0, 1700300000);
    f->b  = blockAt(H,  0xD1, 1700300015);
    f->b->prevBlock = f->b0->blockHash;
    BRSetAdd(f->m->blocks, f->b0);
    BRSetAdd(f->m->blocks, f->b);
    f->m->lastBlock = f->b;
    // filter-header chain H0..H, so a height at H is re-checked over P2P (server_height_recheck_kat case A)
    f->m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H0, UINT256_ZERO);
    UInt256 dummy;
    memset(dummy.u8, 0x77, sizeof(dummy.u8));
    BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain), &dummy, 1);
    memset(dummy.u8, 0x78, sizeof(dummy.u8));
    BRCompactFilterChainAppend(f->m->compactFilterChain, BRCompactFilterChainTipHeader(f->m->compactFilterChain), &dummy, 1);
    BRCFScanLedgerInit(&f->m->cfLedger, H0);
    BRCFScanLedgerRecordRequested(&f->m->cfLedger, H0, H, UINT128_ZERO, 0, 1);
    BRCFScanLedgerMarkEvaluated(&f->m->cfLedger, H0);
    BRCFScanLedgerMarkEvaluated(&f->m->cfLedger, H);
    f->peer = BRPeerNew(BRMainNetParams.magicNumber);
    f->peer->address.u8[15] = (uint8_t)(f == &g_fx[0] ? 0x01 : 0x02);
    f->peer->port = 10001;
    f->peer->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(f->m->connectedPeers, f->peer);
    f->live = live;
}

static void fxFree(Fx *f)
{
    array_clear(f->m->connectedPeers);
    BRPeerManagerFree(f->m);
    BRPeerFree(f->peer);
    BRWalletFree(f->w);
    memset(f, 0, sizeof(*f));
}

// ---- the state both sides are compared on -------------------------------------------------------------------------
typedef struct {
    uint8_t *ser; size_t serLen;
    uint64_t balance, received, sent;
    size_t selectable;
    int hasRecord;
    uint32_t recordHeight, recordTimestamp;
} Snap;

static Snap snap(Fx *f, UInt256 hash)
{
    Snap s;
    memset(&s, 0, sizeof(s));
    s.serLen = BRWalletSerializeTransactions(f->w, NULL, 0);
    s.ser = calloc(s.serLen ? s.serLen : 1, 1);
    if (s.serLen) s.serLen = BRWalletSerializeTransactions(f->w, s.ser, s.serLen);
    s.balance = BRWalletBalance(f->w);
    s.received = BRWalletTotalReceived(f->w);
    s.sent = BRWalletTotalSent(f->w);
    BRUTXO u[32];
    s.selectable = BRWalletSelectableUTXOs(f->w, u, sizeof(u) / sizeof(*u));
    BRTransaction *t = BRWalletCopyTransactionForHash(f->w, hash);
    if (t) { s.hasRecord = 1; s.recordHeight = t->blockHeight; s.recordTimestamp = t->timestamp; BRTransactionFree(t); }
    return s;
}

static void compareSides(UInt256 hash)
{
    Snap a = snap(&g_fx[0], hash), b = snap(&g_fx[1], hash);
    check(a.serLen == b.serLen && memcmp(a.ser, b.ser, a.serLen) == 0, "the wallets' serialized transactions are identical");
    check(a.balance == b.balance && a.received == b.received && a.sent == b.sent, "the balances and totals are identical");
    check(a.selectable == b.selectable, "the selectable outputs are identical");
    check(a.hasRecord == b.hasRecord && a.recordHeight == b.recordHeight && a.recordTimestamp == b.recordTimestamp,
          "both wallets have the same record (or none) of the scenario's hash");
    check(g_fx[0].getcf == g_fx[1].getcf && g_fx[0].getcfStart == g_fx[1].getcfStart && g_fx[0].getdata == g_fx[1].getdata,
          "the same peer traffic");
    int sameCalls = g_fx[0].applyCalls == g_fx[1].applyCalls;
    for (int i = 0; sameCalls && i < g_fx[0].applyCalls && i < MAX_CALLS; i++)
        sameCalls = g_fx[0].applyH[i] == g_fx[1].applyH[i] && g_fx[0].applyT[i] == g_fx[1].applyT[i];
    check(sameCalls, "the same calls into BRPeerManagerApplyServerHeight");
    free(a.ser); free(b.ser);
}

// The scenario's hash, as both wallets would name it: the same bytes give the same hash.
static Snap snapCore(UInt256 hash) { return snap(&g_fx[1], hash); }

// ---- scenarios --------------------------------------------------------------------------------------------------
enum { PRE_NONE, PRE_PENDING, PRE_CONFIRMED, PRE_FOREIGN_PENDING };
enum { BYTES_WALLET, BYTES_FOREIGN, BYTES_UNSIGNED, BYTES_GARBAGE, BYTES_EMPTY, BYTES_NULL };

typedef struct {
    const char *name;
    int pre;              // what the wallet has before the step
    int bytes;            // what the step is handed
    int live, noManager, noWallet;
    int64_t height, timestamp;
    int scripted;         // -1: the manager answers
    BRServerTxImportResult expect;
    uint32_t expectNote;
    int expectRecord;     // -1 do not care; 0 / 1: the core wallet has a record of the hash after
    uint32_t expectRecordHeight;
    int expectApplyCalls;
    int forceUnsigned;    // kat_is_signed answers 0 for the step (see kat_is_signed)
} ImportCase;

typedef struct { uint8_t *buf; size_t len; UInt256 hash; } Bytes;

static Bytes bytesFor(int which, Fx *f, uint8_t seedByte)
{
    Bytes r = { NULL, 0, UINT256_ZERO };
    uint8_t spk[64]; size_t spkLen = 0;
    BRTransaction *tx = NULL;
    switch (which) {
        case BYTES_WALLET:   walletScript(f->w, spk, &spkLen); tx = payTx(spk, spkLen, 400000, seedByte, 1); break;
        case BYTES_FOREIGN:  foreignScript(spk, &spkLen);      tx = payTx(spk, spkLen, 400000, seedByte, 1); break;
        case BYTES_UNSIGNED: walletScript(f->w, spk, &spkLen); tx = payTx(spk, spkLen, 400000, seedByte, 0); break;
        case BYTES_GARBAGE:  r.len = 7; r.buf = malloc(r.len); memset(r.buf, 0xEE, r.len); return r;
        case BYTES_EMPTY:    r.len = 0; r.buf = malloc(1); return r;
        default:             return r;
    }
    r.len = BRTransactionSerialize(tx, NULL, 0);
    r.buf = malloc(r.len);
    BRTransactionSerialize(tx, r.buf, r.len);
    BRTransactionFree(tx);
    // the hash both sides will name: the one the signed-format parse computes over these bytes
    BRTransaction *p = BRTransactionParseSigned(r.buf, r.len);
    if (p) { r.hash = p->txHash; BRTransactionFree(p); }
    return r;
}

// Gives the wallet its record before the step, the same way on both sides.
static void preload(Fx *f, int pre, const Bytes *b)
{
    if (pre == PRE_NONE) return;
    BRTransaction *tx = BRTransactionParseSigned(b->buf, b->len);
    if (! tx) { printf("FAIL: setup: the preload bytes do not parse\n"); exit(3); }
    int ok = BRWalletRegisterTransaction(f->w, tx);
    if (pre == PRE_FOREIGN_PENDING) {
        if (ok || BRWalletTransactionForHash(f->w, tx->txHash) != tx) { printf("FAIL: setup: the foreign tx is not kept\n"); exit(3); }
        return;
    }
    if (! ok) { printf("FAIL: setup: the preload is not registered\n"); exit(3); }
    if (pre == PRE_CONFIRMED) {
        UInt256 h = b->hash;
        BRWalletUpdateTransactions(f->w, &h, 1, H0, 1700300000);
    }
}

static const char *resultName(BRServerTxImportResult r)
{
    static const char *n[] = { "NO_INPUT", "PARSE_FAILED", "UNSIGNED", "ALREADY_CONFIRMED", "ALREADY_PENDING",
                               "PROMOTED", "NOT_WALLET", "NOT_KEPT_UNPROVABLE", "IMPORTED" };
    return (unsigned)r < sizeof(n) / sizeof(*n) ? n[r] : "?";
}

static void beginScenario(const char *name) { g_scenario = name; g_scenarioFail = 0; }
static void endScenario(void) { printf("%s: %s\n", g_scenarioFail ? "SCENARIO-FAIL" : "SCENARIO-PASS", g_scenario); }

static void runImport(const ImportCase *c)
{
    beginScenario(c->name);
    fxInit(&g_fx[0], c->live);
    fxInit(&g_fx[1], c->live);
    Bytes a = bytesFor(c->bytes, &g_fx[0], 0xD2), b = bytesFor(c->bytes, &g_fx[1], 0xD2);
    check(a.len == b.len && (a.len == 0 || memcmp(a.buf, b.buf, a.len) == 0), "setup: both sides are handed the same bytes");
    preload(&g_fx[0], c->pre, &a);
    preload(&g_fx[1], c->pre, &b);
    g_fx[0].getcf = g_fx[1].getcf = g_fx[0].getdata = g_fx[1].getdata = 0;

    // A: the bridge
    g_wallet = c->noWallet ? NULL : g_fx[0].w;
    g_peerManager = c->noManager ? NULL : g_fx[0].m;
    HostArray arr = { (jsize)a.len, (jbyte *)a.buf };
    g_scripted = c->scripted;
    g_forceUnsigned = c->forceUnsigned;
    jboolean bridge = NativeBridge_registerRawTransaction(env, NULL, c->bytes == BYTES_NULL ? NULL : (jbyteArray)&arr,
                                                          c->height, c->timestamp);
    jlong bridgeNote = NativeBridge_takeUnprovableServerHeight(env, NULL);
    g_wallet = NULL; g_peerManager = NULL;

    // B: the core
    BRServerTxUnprovableNote note = { 0 };
    BRServerTxHeightLog log;
    BRServerTxImportResult r = kat_core_import(c->noWallet ? NULL : g_fx[1].w, c->noManager ? NULL : g_fx[1].m,
                                               c->bytes == BYTES_NULL ? NULL : b.buf, b.len, c->height, c->timestamp,
                                               &note, &log);
    uint32_t coreNote = kat_core_take(&note);
    g_scripted = -1;
    g_forceUnsigned = 0;

    printf("NOTE: %s: bridge %d, core %s (added %d), notes %lld / %u, apply calls %d / %d\n", c->name, bridge,
           resultName(r), BRServerTxImportAdded(r), (long long)bridgeNote, coreNote, g_fx[0].applyCalls, g_fx[1].applyCalls);
    check((bridge ? 1 : 0) == BRServerTxImportAdded(r), "the bridge and the core give the same answer");
    check(r == c->expect, "the core's result is the scenario's");
    check((uint64_t)bridgeNote == coreNote, "the same unprovable height is noted");
    check(coreNote == c->expectNote, "the noted height is the scenario's");
    check(kat_core_take(&note) == 0 && NativeBridge_takeUnprovableServerHeight(env, NULL) == 0, "a take resets the note");
    check(g_fx[1].applyCalls == c->expectApplyCalls, "the core applied the height the scenario's number of times");
    check(log.applied == (c->expectApplyCalls > 0), "the log says whether the height was applied");
    compareSides(a.hash);
    if (c->expectRecord >= 0) {
        Snap s = snapCore(b.hash);
        check(s.hasRecord == c->expectRecord, "the wallet has a record of the hash, or none, as the scenario says");
        if (c->expectRecord) check(s.recordHeight == c->expectRecordHeight, "the record's height is the scenario's");
        free(s.ser);
    }
    free(a.buf); free(b.buf);
    fxFree(&g_fx[0]); fxFree(&g_fx[1]);
    endScenario();
}

typedef struct {
    const char *name;
    int pre;              // PRE_NONE: the hash is unknown to the wallet
    int live, noManager, noWallet;
    int64_t height, timestamp;
    int scripted;
    int expect;
    uint32_t expectNote;
    uint32_t expectRecordHeight;   // when pre != PRE_NONE
    int expectApplyCalls;
} ConfirmCase;

static void runConfirm(const ConfirmCase *c)
{
    beginScenario(c->name);
    fxInit(&g_fx[0], c->live);
    fxInit(&g_fx[1], c->live);
    int bytes = c->pre == PRE_FOREIGN_PENDING ? BYTES_FOREIGN : BYTES_WALLET;
    Bytes a = bytesFor(bytes, &g_fx[0], 0xD3), b = bytesFor(bytes, &g_fx[1], 0xD3);
    preload(&g_fx[0], c->pre, &a);
    preload(&g_fx[1], c->pre, &b);
    g_fx[0].getcf = g_fx[1].getcf = g_fx[0].getdata = g_fx[1].getdata = 0;

    // the txid the bridge is handed: display order (reversed), lowercase hex
    char hex[65];
    for (int k = 0; k < 32; k++) sprintf(hex + 2 * k, "%02x", a.hash.u8[31 - k]);
    HostString s = { hex };

    g_wallet = c->noWallet ? NULL : g_fx[0].w;
    g_peerManager = c->noManager ? NULL : g_fx[0].m;
    g_scripted = c->scripted;
    jboolean bridge = NativeBridge_confirmTransaction(env, NULL, (jstring)&s, c->height, c->timestamp);
    jlong bridgeNote = NativeBridge_takeUnprovableServerHeight(env, NULL);
    g_wallet = NULL; g_peerManager = NULL;

    BRServerTxUnprovableNote note = { 0 };
    BRServerTxHeightLog log;
    int r = kat_core_confirm(c->noWallet ? NULL : g_fx[1].w, c->noManager ? NULL : g_fx[1].m, b.hash, c->height,
                             c->timestamp, &note, &log);
    uint32_t coreNote = kat_core_take(&note);
    g_scripted = -1;

    printf("NOTE: %s: bridge %d, core %d, notes %lld / %u, apply calls %d / %d\n", c->name, bridge, r,
           (long long)bridgeNote, coreNote, g_fx[0].applyCalls, g_fx[1].applyCalls);
    check((bridge ? 1 : 0) == (r ? 1 : 0), "the bridge and the core give the same answer");
    check((r ? 1 : 0) == c->expect, "the core's answer is the scenario's");
    check((uint64_t)bridgeNote == coreNote, "the same unprovable height is noted");
    check(coreNote == c->expectNote, "the noted height is the scenario's");
    check(g_fx[1].applyCalls == c->expectApplyCalls, "the core applied the height the scenario's number of times");
    check(log.applied == (c->expectApplyCalls > 0), "the log says whether the height was applied");
    compareSides(a.hash);
    if (c->pre != PRE_NONE) {
        Snap sn = snapCore(b.hash);
        check(sn.hasRecord && sn.recordHeight == c->expectRecordHeight, "the record stays, at the scenario's height");
        free(sn.ser);
    }
    free(a.buf); free(b.buf);
    fxFree(&g_fx[0]); fxFree(&g_fx[1]);
    endScenario();
}

// Several imports in a row on the same two fixtures, the note taken only at the end: it keeps the lowest height.
static void runNoteLowest(void)
{
    beginScenario("note_lowest");
    fxInit(&g_fx[0], 1);
    fxInit(&g_fx[1], 1);
    const uint32_t heights[3] = { DEEP, DEEP - 500, DEEP - 100 };
    const uint8_t seeds[3] = { 0xA1, 0xA2, 0xA3 };
    BRServerTxUnprovableNote note = { 0 };
    UInt256 last = UINT256_ZERO;
    for (int i = 0; i < 3; i++) {
        Bytes a = bytesFor(BYTES_WALLET, &g_fx[0], seeds[i]), b = bytesFor(BYTES_WALLET, &g_fx[1], seeds[i]);
        g_wallet = g_fx[0].w; g_peerManager = g_fx[0].m;
        HostArray arr = { (jsize)a.len, (jbyte *)a.buf };
        jboolean bridge = NativeBridge_registerRawTransaction(env, NULL, (jbyteArray)&arr, heights[i], 1700000000);
        g_wallet = NULL; g_peerManager = NULL;
        BRServerTxImportResult r = kat_core_import(g_fx[1].w, g_fx[1].m, b.buf, b.len, heights[i], 1700000000, &note, NULL);
        check(bridge == JNI_FALSE && r == BR_SERVER_TX_NOT_KEPT_UNPROVABLE, "each import is not kept (unprovable)");
        compareSides(a.hash);
        last = a.hash;
        free(a.buf); free(b.buf);
    }
    jlong bridgeNote = NativeBridge_takeUnprovableServerHeight(env, NULL);
    uint32_t coreNote = kat_core_take(&note);
    printf("NOTE: note_lowest: notes %lld / %u\n", (long long)bridgeNote, coreNote);
    check((uint64_t)bridgeNote == coreNote && coreNote == DEEP - 500, "the note keeps the lowest height, on both sides");
    check(NativeBridge_takeUnprovableServerHeight(env, NULL) == 0 && kat_core_take(&note) == 0, "a take resets it");
    Snap s = snapCore(last);
    check(! s.hasRecord && s.serLen == BRWalletSerializeTransactions(g_fx[1].w, NULL, 0) && s.balance == 0,
          "nothing was kept");
    free(s.ser);
    fxFree(&g_fx[0]); fxFree(&g_fx[1]);
    endScenario();
}

// A null note is allowed on the core side; the procedure is otherwise the same.
static void runNullNote(void)
{
    beginScenario("null_note");
    fxInit(&g_fx[1], 1);
    Bytes b = bytesFor(BYTES_WALLET, &g_fx[1], 0xB1);
    BRServerTxImportResult r = kat_core_import(g_fx[1].w, g_fx[1].m, b.buf, b.len, DEEP, 1700000000, NULL, NULL);
    check(r == BR_SERVER_TX_NOT_KEPT_UNPROVABLE, "an import with no note is still not kept");
    check(BRServerTxUnprovableNoteTake(NULL) == 0, "taking a null note answers 0");
    free(b.buf);
    fxFree(&g_fx[1]);
    endScenario();
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    g_verbose = argc > 1 && strcmp(argv[1], "-v") == 0;
    BRSetNetwork(0);
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&g_peerManagerMutex, &attr);

    const int64_t T = 1700300015, BIG = (int64_t)1 << 40;
    const ImportCase imports[] = {
        // name                       pre            bytes          live noM noW height      ts       scripted  expect                          note   rec  recH            calls
        { "import_no_wallet",         PRE_NONE,      BYTES_WALLET,  1, 0, 1, H,           T,        -1, BR_SERVER_TX_NO_INPUT,            0,      0, 0,              0 },
        { "import_null_bytes",        PRE_NONE,      BYTES_NULL,    1, 0, 0, H,           T,        -1, BR_SERVER_TX_NO_INPUT,            0,     -1, 0,              0 },
        { "import_empty_bytes",       PRE_NONE,      BYTES_EMPTY,   1, 0, 0, H,           T,        -1, BR_SERVER_TX_NO_INPUT,            0,     -1, 0,              0 },
        { "import_garbage",           PRE_NONE,      BYTES_GARBAGE, 1, 0, 0, H,           T,        -1, BR_SERVER_TX_PARSE_FAILED,        0,     -1, 0,              0 },
        { "import_unsigned_form",     PRE_NONE,      BYTES_UNSIGNED,1, 0, 0, H,           T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 1 },
        { "import_unsigned",          PRE_NONE,      BYTES_WALLET,  1, 0, 0, H,           T,        -1, BR_SERVER_TX_UNSIGNED,            0,      0, 0,              0, 1 },
        { "import_not_wallet",        PRE_NONE,      BYTES_FOREIGN, 1, 0, 0, H,           T,        -1, BR_SERVER_TX_NOT_WALLET,          0,      1, TX_UNCONFIRMED, 0 },
        { "import_not_wallet_again",  PRE_FOREIGN_PENDING, BYTES_FOREIGN, 1, 0, 0, H,     T,        -1, BR_SERVER_TX_ALREADY_PENDING,     0,      1, TX_UNCONFIRMED, 1 },
        { "import_rechecking",        PRE_NONE,      BYTES_WALLET,  1, 0, 0, H,           T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 1 },
        { "import_no_filter_peer",    PRE_NONE,      BYTES_WALLET,  0, 0, 0, H,           T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 1 },
        { "import_above_tip",         PRE_NONE,      BYTES_WALLET,  1, 0, 0, H + 50,      T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 1 },
        { "import_height_zero",       PRE_NONE,      BYTES_WALLET,  1, 0, 0, 0,           T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 0 },
        { "import_height_negative",   PRE_NONE,      BYTES_WALLET,  1, 0, 0, -7,          T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 0 },
        { "import_height_unconfirmed",PRE_NONE,      BYTES_WALLET,  1, 0, 0, TX_UNCONFIRMED, T,     -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 0 },
        { "import_height_huge",       PRE_NONE,      BYTES_WALLET,  1, 0, 0, BIG,         T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 0 },
        { "import_no_manager",        PRE_NONE,      BYTES_WALLET,  1, 1, 0, DEEP,        T,        -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 0 },
        { "import_timestamp_wide",    PRE_NONE,      BYTES_WALLET,  1, 0, 0, H,           BIG + 5,  -1, BR_SERVER_TX_IMPORTED,            0,      1, TX_UNCONFIRMED, 1 },
        { "import_unprovable",        PRE_NONE,      BYTES_WALLET,  1, 0, 0, DEEP,        T,        -1, BR_SERVER_TX_NOT_KEPT_UNPROVABLE, DEEP,   0, 0,              1 },
        { "import_scripted_taken",    PRE_NONE,      BYTES_WALLET,  1, 0, 0, H,           T,  BR_SERVER_HEIGHT_TAKEN, BR_SERVER_TX_IMPORTED, 0,   1, TX_UNCONFIRMED, 1 },
        { "dup_confirmed",            PRE_CONFIRMED, BYTES_WALLET,  1, 0, 0, H,           T,        -1, BR_SERVER_TX_ALREADY_CONFIRMED,   0,      1, H0,             0 },
        { "dup_confirmed_deep",       PRE_CONFIRMED, BYTES_WALLET,  1, 0, 0, DEEP,        T,        -1, BR_SERVER_TX_ALREADY_CONFIRMED,   0,      1, H0,             0 },
        { "dup_pending_rechecking",   PRE_PENDING,   BYTES_WALLET,  1, 0, 0, H,           T,        -1, BR_SERVER_TX_ALREADY_PENDING,     0,      1, TX_UNCONFIRMED, 1 },
        { "dup_pending_unprovable",   PRE_PENDING,   BYTES_WALLET,  1, 0, 0, DEEP,        T,        -1, BR_SERVER_TX_ALREADY_PENDING,     DEEP,   1, TX_UNCONFIRMED, 1 },
        { "dup_pending_no_manager",   PRE_PENDING,   BYTES_WALLET,  1, 1, 0, H,           T,        -1, BR_SERVER_TX_ALREADY_PENDING,     0,      1, TX_UNCONFIRMED, 0 },
        { "dup_pending_scripted_taken", PRE_PENDING, BYTES_WALLET,  1, 0, 0, H,           T,  BR_SERVER_HEIGHT_TAKEN, BR_SERVER_TX_PROMOTED, 0,   1, TX_UNCONFIRMED, 1 },
    };
    for (size_t i = 0; i < sizeof(imports) / sizeof(*imports); i++) runImport(&imports[i]);

    const ConfirmCase confirms[] = {
        // name                         pre            live noM noW height        ts      scripted                expect note  recH            calls
        { "confirm_no_wallet",          PRE_PENDING,   1, 0, 1, H,              T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_height_zero",        PRE_PENDING,   1, 0, 0, 0,              T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_height_negative",    PRE_PENDING,   1, 0, 0, -1,             T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_height_unconfirmed", PRE_PENDING,   1, 0, 0, TX_UNCONFIRMED, T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_height_huge",        PRE_PENDING,   1, 0, 0, BIG,            T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_unknown_hash",       PRE_NONE,      1, 0, 0, H,              T,      -1,                     0, 0,    0,              0 },
        { "confirm_confirmed_noop",     PRE_CONFIRMED, 1, 0, 0, H,              T,      -1,                     0, 0,    H0,             0 },
        { "confirm_rechecking",         PRE_PENDING,   1, 0, 0, H,              T,      -1,                     0, 0,    TX_UNCONFIRMED, 1 },
        { "confirm_above_tip",          PRE_PENDING,   1, 0, 0, H + 50,         T,      -1,                     0, 0,    TX_UNCONFIRMED, 1 },
        { "confirm_unprovable",         PRE_PENDING,   1, 0, 0, DEEP,           T,      -1,                     0, DEEP, TX_UNCONFIRMED, 1 },
        { "confirm_no_manager",         PRE_PENDING,   1, 1, 0, H,              T,      -1,                     0, 0,    TX_UNCONFIRMED, 0 },
        { "confirm_foreign_pending",    PRE_FOREIGN_PENDING, 1, 0, 0, H,        T,      -1,                     0, 0,    TX_UNCONFIRMED, 1 },
        { "confirm_timestamp_wide",     PRE_PENDING,   1, 0, 0, H,              BIG + 5, -1,                    0, 0,    TX_UNCONFIRMED, 1 },
        { "confirm_scripted_taken",     PRE_PENDING,   1, 0, 0, H,              T,      BR_SERVER_HEIGHT_TAKEN, 1, 0,    TX_UNCONFIRMED, 1 },
    };
    for (size_t i = 0; i < sizeof(confirms) / sizeof(*confirms); i++) runConfirm(&confirms[i]);

    runNoteLowest();
    runNullNote();

    printf("\n%s: server_tx_import_kat (%d failed check%s)\n", g_fail ? "FAIL" : "PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
