// Host KAT: the DigiAsset transfer builder and signer, moved from the JNI bridge into the core (BRAssetSend.c,
// BRWalletBuildAssetTransfer), signs exactly what the bridge signs, and refuses where it says it does.
//
// Each send is made twice against one wallet: through the bridge's own NativeBridge_buildAndSignAssetTransferTx
// (jni_asset_send.c, signing through jni_wallet.c's seed_sign_transaction, all compiled here over
// identity_sign_bytes_kat's host JNI stand-ins, the wallet recovered through the bridge's own
// recoverWalletFromBytes), fed the arrays AssetManager.sendAsset writes out of the plan; and through
// BRWalletBuildAssetTransfer, given the same plan (the outcome BRAssetSendChoose answered), the same rows and
// the seed the bridge derived. Where both sign they must sign the same bytes; where the core refuses, the
// refusal must be the status the scenario names.
//
// SCENARIOS
//   sign_*           every source script type (P2WPKH, P2PKH, P2TR), with and without asset change, DGB change,
//                    fee inputs; many inputs; a txid written in upper case. Same bytes both sides.
//   fuzz             KAT_FUZZ sends (default 300; KAT_FUZZ_SEED varies them) over random coins, scripts,
//                    quantities and fee rates: every plan the choice makes signs the same bytes on both sides;
//                    then one tamper per send (carrier, fee, approval, quantity, a held fee coin, a marker,
//                    the recipient), which the core must refuse with the tamper's status.
//   binding_*        AssetSendBindingTest's rules as the builder sees them: the units approved are the units
//                    sent, the asset is the approval's, the recipient is the approval's address without
//                    whitespace (with it, both sides refuse), the fee is at most the approval.
//   rule_state       a PLANNED outcome signed under a rule state other than NONE (BOUND, UNKNOWN, a value
//                    BRSweepRuleState does not define): refused before anything else is read.
//   conflicted_*     asset_conflicted_send_kat's wallet (a stuck send replaced by a re-send): its assertions on
//                    BRWalletOutpointAssetState, then a send from each outpoint it names.
//   shape_*, bounds_*, duplicate_input, carrier_*, fee_mismatch, held_*, fee_coin_*, record_*, spent_*,
//   gate_*, sign_failed, no_memory, not_planned, arguments: every check of BRAssetSend.h, each status.
//   keys_wiped       checked after EVERY core call in every scenario too: no block the core's unit frees holds
//                    the seed, and the caller's seed is unchanged. A regression guard, not a witness of the
//                    wipe: the core's unit puts nothing of the seed on the heap, and the derived keys live on
//                    BRWalletSignTransaction's stack (BRKeyClean there), which this recorder cannot see.
//
// The bridge's reservation table (coin_reservation.c) and the core's (BRCoinReservation.c) are separate: each
// side claims in its own, and both are cleared before each send unless the scenario sets claims itself.
//
// Comparison arms (BR_ASSET_TRANSFER_UNFIXED bits, on the core's unit only): run.sh requires each to fail
// exactly its scenarios and pass every other one.
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>

#include <jni.h>
#include "BRAssetSend.h"
#include "BRAssetEncode.h"
#include "BRBIP39Mnemonic.h"
#include "BRBIP32Sequence.h"
#include "BRAddress.h"
#include "BRCoinReservation.h"
#include "coin_reservation.h"   // the bridge's table

// ---- the bridge -----------------------------------------------------------------------------------------------
extern BRWallet *g_wallet;
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);
jboolean JNICALL NativeBridge_recoverWalletFromBytes(JNIEnv *env, jobject thiz, jbyteArray phraseBytes,
                                                     jlong creationTimestamp, jbyteArray passphrase);
jstring JNICALL NativeBridge_buildAndSignAssetTransferTx(JNIEnv *env, jobject thiz, jobjectArray inputTxidsHex,
                                                         jintArray inputVouts, jlongArray inputAmounts,
                                                         jobjectArray inputScriptPubKeysHex, jobjectArray outputAddresses,
                                                         jlongArray outputAmounts, jobjectArray outputScriptsHex);
int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }
int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    (void)prio; (void)tag; (void)fmt;
    return 0;
}

// ---- the allocation recorder (the core's unit only: -Dcalloc=kat_calloc -Dmalloc=kat_malloc -Dfree=kat_free) ----
static uint8_t g_seedBytes[64];
static int g_freedHits = 0, g_freedBlocks = 0;
static long g_allocSeq = 0, g_failAt = -1;

static int holds(const uint8_t *p, size_t n, const uint8_t *needle, size_t nlen)
{
    if (nlen == 0 || n < nlen) return 0;
    for (size_t i = 0; i + nlen <= n; i++) if (p[i] == needle[0] && memcmp(p + i, needle, nlen) == 0) return 1;
    return 0;
}
void *kat_malloc(size_t n)
{
    if (g_failAt >= 0 && g_allocSeq++ == g_failAt) return NULL;
    uint8_t *b = malloc(n + 16);
    if (! b) return NULL;
    memcpy(b, &n, sizeof(n));
    return b + 16;
}
void *kat_calloc(size_t c, size_t s)
{
    if (s && c > (SIZE_MAX - 16) / s) return NULL;
    uint8_t *p = kat_malloc(c * s);
    if (p) memset(p, 0, c * s);
    return p;
}
void kat_free(void *p)
{
    if (! p) return;
    uint8_t *b = (uint8_t *)p - 16;
    size_t n;
    memcpy(&n, b, sizeof(n));
    g_freedBlocks++;
    if (holds(p, n, g_seedBytes, 32) || holds(p, n, g_seedBytes + 32, 32)) g_freedHits++;
    free(b);
}

// ---- host JNI env ----------------------------------------------------------------------------------------------
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

// ---- checks ----------------------------------------------------------------------------------------------------
static int g_fail = 0;             // failures in the current scenario
static const char *g_scenario = "";
static void check(int ok, const char *fmt, ...)
{
    if (ok) return;
    va_list ap;
    va_start(ap, fmt);
    printf("   FAIL [%s]: ", g_scenario);
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
    g_fail++;
}

static const char *statusName(BRAssetTransferStatus s)
{
    static const char *n[] = { "SIGNED", "BAD_ARGUMENT", "NOT_PLANNED", "BAD_SEED", "NO_MEMORY", "PLAN_SHAPE",
        "WRONG_ASSET", "BAD_INPUT", "DUPLICATE_INPUT", "BAD_RECIPIENT", "CARRIER_MISMATCH", "UNITS_MISPLACED",
        "FEE_MISMATCH", "FEE_NOT_APPROVED", "INPUT_SPENT", "INPUT_CONFLICTED", "INPUT_UNKNOWN", "INPUT_MISMATCH",
        "INPUT_HELD", "FEE_COIN_NOT_PLAIN", "SIGN_FAILED", "GATE_REFUSED", "RULE_STATE" };
    return ((unsigned)s < sizeof(n) / sizeof(*n)) ? n[s] : "?";
}

// ---- the PRNG (the KAT's own; the core draws none) ------------------------------------------------------------
static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;
static uint64_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static uint64_t rndBelow(uint64_t n) { return n ? rnd() % n : 0; }

// ---- the wallet and its fixtures ------------------------------------------------------------------------------
static const char *kMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *kOtherMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow";
static const char *kAsset = "La4WAqZfExampleExampleExampleExampleEx";
static const char *kOtherAsset = "La9OtherAssetOtherAssetOtherAssetOther";
#define OWN_PER_TYPE 12
static BRAddress g_own[3][OWN_PER_TYPE];          // script type 0 P2PKH, 1 P2WPKH, 2 P2TR
static uint8_t g_ownSpk[3][OWN_PER_TYPE][64];
static size_t g_ownLen[3][OWN_PER_TYPE];
static BRAddress g_foreign[2][4];                  // another wallet's: P2PKH, P2WPKH
static uint8_t g_foreignSpk[64];
static size_t g_foreignLen;

static UInt256 g_funded[512];
static size_t g_fundedCount = 0;
static uint32_t g_tag = 1;

static void finalizeTxHash(BRTransaction *tx)
{
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(n);
    BRTransactionSerialize(tx, data, n);
    BRTransaction *t = BRTransactionParse(data, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
}

// A receive: one foreign input, outputs out[0, count) to the scripts given, and, when carrierQty is above 0,
// a transfer carrier last that names output 0 with carrierQty units. Registered in the wallet (confirmed when
// asked); its hash is kept for cleanup().
static UInt256 fund(const uint8_t *const spk[], const size_t spkLen[], const uint64_t amt[], size_t count,
                    int64_t carrierQty, int confirmed)
{
    static const uint8_t kEmpty[1] = { 0 };
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(prev.u8, 0, 32);
    uint32_t t = g_tag++;
    memcpy(prev.u8, &t, sizeof(t));
    prev.u8[31] = 0xa5;
    BRTransactionAddInput(tx, prev, 0, 0, g_foreignSpk, g_foreignLen, kEmpty, 0, kEmpty, 0, TXIN_SEQUENCE);
    for (size_t i = 0; i < count; i++) BRTransactionAddOutput(tx, amt[i], spk[i], spkLen[i]);
    if (carrierQty > 0) {
        uint8_t s[BR_ASSET_ENCODE_MAX_SCRIPT];
        size_t n = 0;
        BRAssetEncodeSimpleTransfer(3, 0, carrierQty, s, sizeof(s), &n, NULL);
        BRTransactionAddOutput(tx, 0, s, n);
    }
    finalizeTxHash(tx);
    UInt256 h = tx->txHash;
    if (! BRWalletRegisterTransaction(g_wallet, tx)) {
        check(0, "a funding transaction did not register");
        BRTransactionFree(tx);
        return h;
    }
    if (confirmed) BRWalletUpdateTransactions(g_wallet, &h, 1, 1000000 + t, 1780000000u + t);
    if (g_fundedCount < sizeof(g_funded) / sizeof(*g_funded)) g_funded[g_fundedCount++] = h;
    return h;
}

static void cleanup(void)
{
    while (g_fundedCount) BRWalletRemoveTransaction(g_wallet, g_funded[--g_fundedCount]);
    coin_reservation_clear();
    BRCoinReservationClear();
}

// One row of the wallet's: its outpoint, script, sats and (asset rows) asset and units. The row points into
// this struct, which must stay where it is.
typedef struct {
    char txid[80];
    uint8_t script[300];
    size_t scriptLen;
    UInt256 hash;
    uint32_t n;
    BRAssetRow row;
} Coin;

static void toDisplayHex(UInt256 h, char *out)
{
    for (int k = 0; k < 32; k++) sprintf(out + 2 * k, "%02x", h.u8[31 - k]);
    out[64] = '\0';
}

static void coinSet(Coin *c, UInt256 h, uint32_t n, const uint8_t *spk, size_t len, int64_t sats, const char *assetId,
                    int64_t qty)
{
    memset(c, 0, sizeof(*c));
    c->hash = h;
    c->n = n;
    toDisplayHex(h, c->txid);
    memcpy(c->script, spk, len);
    c->scriptLen = len;
    c->row.txid = c->txid;
    c->row.vout = (int32_t)n;
    c->row.script = c->script;
    c->row.scriptLen = len;
    c->row.satoshis = sats;
    c->row.blockHeight = 1;
    c->row.isAsset = assetId != NULL;
    c->row.assetId = assetId;
    c->row.assetQuantity = qty;
    c->row.source = BR_ASSET_SOURCE_NATIVE;
    c->row.credit = BR_ASSET_CREDIT_VERIFIED;
}

// An asset coin: its own receive of 6000 sats (or sats) at own[type][addr], qty units of kAsset.
static void assetCoin(Coin *c, int type, int addr, int64_t qty, uint64_t sats)
{
    const uint8_t *s[1] = { g_ownSpk[type][addr] };
    size_t l[1] = { g_ownLen[type][addr] };
    uint64_t a[1] = { sats };
    UInt256 h = fund(s, l, a, 1, qty, 1);
    coinSet(c, h, 0, s[0], l[0], (int64_t)sats, kAsset, qty);
}

// A plain coin: its own confirmed receive at own[type][addr].
static void plainCoin(Coin *c, int type, int addr, uint64_t sats)
{
    const uint8_t *s[1] = { g_ownSpk[type][addr] };
    size_t l[1] = { g_ownLen[type][addr] };
    uint64_t a[1] = { sats };
    UInt256 h = fund(s, l, a, 1, 0, 1);
    coinSet(c, h, 0, s[0], l[0], (int64_t)sats, NULL, 0);
}

// ---- the plan -------------------------------------------------------------------------------------------------
#define PLAN_ROWS 24
typedef struct {
    BRAssetRow a[PLAN_ROWS], d[PLAN_ROWS];
    size_t na, nd;
    BRAssetSendLookup ans[PLAN_ROWS];
    BRAssetUnits ent[PLAN_ROWS];
    BRAssetFact parent[PLAN_ROWS];
    size_t work[4 * PLAN_ROWS];
    uint8_t marks[PLAN_ROWS];
    BRAssetSendOutcome o;
} Plan;

// The choice AssetManager.planAssetTransfer makes, with the indexer confirming every asset coin. 1 when PLANNED.
static int choose(Plan *p, Coin *const assets[], size_t na, Coin *const fees[], size_t nf, int64_t qty, int64_t rate)
{
    memset(p, 0, sizeof(*p));
    p->na = na;
    p->nd = nf;
    for (size_t i = 0; i < na; i++) {
        p->a[i] = assets[i]->row;
        p->ent[i].assetId = kAsset;
        p->ent[i].count = assets[i]->row.assetQuantity;
        p->ans[i].txid = assets[i]->row.txid;
        p->ans[i].vout = assets[i]->row.vout;
        p->ans[i].lookup = BR_ASSET_STACK_FOUND;
        p->ans[i].entries = &p->ent[i];
        p->ans[i].entryCount = 1;
    }
    for (size_t i = 0; i < nf; i++) {
        p->d[i] = fees[i]->row;
        p->parent[i] = BR_ASSET_FACT_NO;
    }
    p->o = BRAssetSendChoose(BR_SWEEP_RULE_NONE, 1, kAsset, p->a, na, p->d, p->parent, nf, qty, rate, NULL, 0, NULL, 0,
                             p->ans, na, p->marks, PLAN_ROWS, p->work, 4 * PLAN_ROWS);
    return p->o.kind == BR_ASSET_SEND_PLANNED;
}

static void hexOf(const uint8_t *b, size_t n, char *out)
{
    for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]);
    out[2 * n] = '\0';
}

static size_t unhex(const char *h, uint8_t *out, size_t cap)
{
    size_t n = strlen(h) / 2;
    if (n > cap) return 0;
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2 * i, "%2x", &v); out[i] = (uint8_t)v; }
    return n;
}

// The bridge, fed what AssetManager.sendAsset writes out of the plan. 1 and *ser when it signs.
static int bridgeSign(const Plan *p, const BRAssetSendOutcome *o, const char *recipient, uint8_t *ser, size_t *serLen)
{
    const BRAssetTransferPlan *pl = &o->plan;
    size_t nIn = pl->assetInputCount + pl->dgbInputCount, nOut = pl->outputCount, i;
    jobject txids[64], scripts[64], addrs[8], oscripts[8];
    jint vouts[64];
    jlong amts[64], oamts[8];
    char hex[2 * 600 + 1];

    *serLen = 0;
    if (nIn > 64 || nOut > 8) return 0;
    for (i = 0; i < nIn; i++) {
        const BRAssetRow *r = i < pl->assetInputCount ? &p->a[pl->assetInputs[i]] : &p->d[pl->dgbInputs[i - pl->assetInputCount]];
        txids[i] = hostString(r->txid ? r->txid : "");
        hexOf(r->script ? r->script : (const uint8_t *)"", r->script ? r->scriptLen : 0, hex);
        scripts[i] = hostString(hex);
        vouts[i] = r->vout;
        amts[i] = r->satoshis;
    }
    for (i = 0; i < nOut; i++) {
        const BRAssetPlannedOutput *out = &pl->outputs[i];
        if (out->role == BR_ASSET_ROLE_RECIPIENT_MARKER) { addrs[i] = hostString(recipient); oscripts[i] = hostString(""); }
        else if (out->role == BR_ASSET_ROLE_ASSET_DATA) {
            addrs[i] = hostString("");
            hexOf(pl->opReturnScript, pl->opReturnLen, hex);
            oscripts[i] = hostString(hex);
        } else {
            addrs[i] = hostString("");
            hexOf(pl->sourceScript, pl->sourceScriptLen, hex);
            oscripts[i] = hostString(hex);
        }
        oamts[i] = out->sats;
    }
    HostString *r = (HostString *)NativeBridge_buildAndSignAssetTransferTx(env, NULL,
        hostArray(txids, sizeof(jobject), (jsize)nIn), hostArray(vouts, sizeof(jint), (jsize)nIn),
        hostArray(amts, sizeof(jlong), (jsize)nIn), hostArray(scripts, sizeof(jobject), (jsize)nIn),
        hostArray(addrs, sizeof(jobject), (jsize)nOut), hostArray(oamts, sizeof(jlong), (jsize)nOut),
        hostArray(oscripts, sizeof(jobject), (jsize)nOut));
    if (! r) return 0;
    *serLen = unhex(r->text, ser, 4096);
    return *serLen > 0;
}

// The outpoints a plan's rows name (those whose txid reads), for the claim probe.
static size_t planInputs(const Plan *p, const BRAssetSendOutcome *o, BRTxInput *ins, size_t cap)
{
    const BRAssetTransferPlan *pl = &o->plan;
    size_t n = 0;
    if (o->kind != BR_ASSET_SEND_PLANNED || ! pl->assetInputs) return 0;
    for (size_t i = 0; i < pl->assetInputCount + pl->dgbInputCount && n < cap; i++) {
        size_t k = i < pl->assetInputCount ? pl->assetInputs[i] : (pl->dgbInputs ? pl->dgbInputs[i - pl->assetInputCount] : SIZE_MAX);
        const BRAssetRow *r = i < pl->assetInputCount ? (k < p->na ? &p->a[k] : NULL) : (k < p->nd ? &p->d[k] : NULL);
        if (! r || ! r->txid || strlen(r->txid) != 64 || r->vout < 0) continue;
        memset(&ins[n], 0, sizeof(ins[n]));
        for (int b = 0; b < 32; b++) { unsigned v; sscanf(r->txid + 2 * b, "%2x", &v); ins[n].txHash.u8[31 - b] = (uint8_t)v; }
        ins[n].index = (uint32_t)r->vout;
        n++;
    }
    return n;
}

static BRTransaction g_sentinel;
static int g_keepClaims = 0;   // a scenario sets claims itself: no clearing, no refusal probe
static BRSweepRuleState g_ruleState = BR_SWEEP_RULE_NONE;   // the rule state every core call passes
static BRAssetTransferReport g_lastReport;                  // the report of the last core call
static int g_lastSignedValid = 0;
static UInt256 g_lastSignedHash;

// The core, given the plan. *ser on SIGNED. Every call: the out-parameter and report contracts, the seed, the
// freed blocks, and nothing claimed after a refusal.
static BRAssetTransferStatus coreSign(const Plan *p, const BRAssetSendOutcome *o, const char *assetId, int64_t qty,
                                      const char *recipient, int64_t approved, const BRUTXO *held, size_t nh,
                                      uint8_t *ser, size_t *serLen, BRAssetTransferReport *rep)
{
    BRTransaction *tx = &g_sentinel;
    BRAssetTransferReport r;
    uint8_t before[64];
    memcpy(before, g_seedBytes, 64);
    memset(&r, 0xab, sizeof(r));
    BRAssetTransferStatus s = BRWalletBuildAssetTransfer(&tx, g_wallet, g_ruleState, o, p->a, p->na, p->d, p->nd, assetId, qty,
                                                         recipient, approved, held, nh, g_seedBytes, 64, 1000.0, &r);
    *serLen = 0;
    g_lastSignedValid = 0;
    check(memcmp(before, g_seedBytes, 64) == 0, "the caller's seed changed");
    check(g_freedHits == 0, "a block the core freed holds the seed");
    if (s == BR_ASSET_TRANSFER_SIGNED) {
        check(tx != NULL && tx != &g_sentinel, "SIGNED with no transaction");
        if (tx && tx != &g_sentinel) {
            *serLen = BRTransactionSerialize(tx, ser, 4096);
            g_lastSignedHash = tx->txHash;
            g_lastSignedValid = 1;
            BRTransactionFree(tx);
        }
        check(r.input == SIZE_MAX, "SIGNED reports input %zu", r.input);
        check(r.paidFeeSats == o->plan.paidFeeSats, "SIGNED reports fee %lld, the plan %lld",
              (long long)r.paidFeeSats, (long long)o->plan.paidFeeSats);
    } else {
        check(tx == NULL, "refused (%s) and *txOut is not NULL", statusName(s));
        // The wallet's checks (15) name the input they refuse, as BAD_INPUT does; every other refusal names none.
        if (s >= BR_ASSET_TRANSFER_INPUT_SPENT && s <= BR_ASSET_TRANSFER_FEE_COIN_NOT_PLAIN)
            check(r.input < o->plan.assetInputCount + o->plan.dgbInputCount, "%s reports input %zu", statusName(s), r.input);
        else if (s != BR_ASSET_TRANSFER_BAD_INPUT) check(r.input == SIZE_MAX, "%s reports input %zu", statusName(s), r.input);
        // Nothing claimed on a refusal: another owner may claim every coin of the plan.
        BRTxInput ins[64];
        size_t n = planInputs(p, o, ins, 64);
        UInt256 probe;
        memset(probe.u8, 0x77, 32);
        if (n && ! g_keepClaims) {
            check(BRCoinReservationClaim(probe, ins, n, 1000.0) == 1, "refused (%s) and a coin is still claimed", statusName(s));
            BRCoinReservationRelease(probe);
        }
    }
    g_lastReport = r;
    if (rep) *rep = r;
    return s;
}

// One send both ways. want: the core's status. bridgeWant: 0 the bridge refuses too, -1 the bridge's answer is not
// judged, -2 the bridge is not asked, 2 (with want SIGNED) the same bytes.
// Returns the core's status.
static BRAssetTransferStatus sendBoth(const char *what, const Plan *p, const BRAssetSendOutcome *o, const char *assetId,
                                      int64_t qty, const char *recipient, int64_t approved, const BRUTXO *held, size_t nh,
                                      BRAssetTransferStatus want, int bridgeWant)
{
    uint8_t bser[4096], cser[4096];
    size_t blen = 0, clen = 0;
    if (! g_keepClaims) { coin_reservation_clear(); BRCoinReservationClear(); }
    int bridgeSigned = (bridgeWant == -2) ? 0 : bridgeSign(p, o, recipient, bser, &blen);
    if (! g_keepClaims) coin_reservation_clear();
    BRAssetTransferStatus s = coreSign(p, o, assetId, qty, recipient, approved, held, nh, cser, &clen, NULL);
    check(s == want, "%s: the core answered %s, want %s", what, statusName(s), statusName(want));
    if (want == BR_ASSET_TRANSFER_SIGNED && s == want) {
        check(bridgeSigned, "%s: the core signed and the bridge did not", what);
        if (bridgeSigned) check(blen == clen && memcmp(bser, cser, blen) == 0, "%s: the bridge and the core signed different bytes", what);
    } else if (bridgeWant == 0) check(! bridgeSigned, "%s: the bridge signed", what);
    arena_free();
    return s;
}

#define NOLIMIT INT64_MAX
static const char *recip(void) { return g_foreign[1][0].s; }

// ---- scenarios -------------------------------------------------------------------------------------------------

static void sign_shapes(void)
{
    for (int t = 0; t < 3; t++) {
        for (int variant = 0; variant < 3; variant++) {
            Coin a[2], f[1];
            Plan p;
            assetCoin(&a[0], t, 0, 700, 6000);
            assetCoin(&a[1], t, 0, 300, 6000);
            plainCoin(&f[0], (t + 1) % 3, 1, 50000000);
            Coin *as[2] = { &a[0], &a[1] }, *fs[1] = { &f[0] };
            int64_t q = variant == 0 ? 800 : variant == 1 ? 1000 : 1;
            check(choose(&p, as, 2, fs, 1, q, 100000), "type %d variant %d: planned", t, variant);
            char what[64];
            snprintf(what, sizeof(what), "source type %d, %lld of 1000", t, (long long)q);
            sendBoth(what, &p, &p.o, kAsset, q, variant == 2 ? g_foreign[0][1].s : recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
            cleanup();
        }
    }
}

static void sign_no_dgb_change(void)
{
    Coin a[2], f[1];
    Plan p;
    assetCoin(&a[0], 1, 0, 50, 6000);
    assetCoin(&a[1], 1, 0, 50, 6000);
    plainCoin(&f[0], 1, 1, 50000000);
    Coin *as[2] = { &a[0], &a[1] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 2, fs, 1, 60, 100000), "planned (large fee coin)");
    int64_t fee = p.o.plan.estimatedFeeSats;
    cleanup();
    assetCoin(&a[0], 1, 0, 50, 6000);
    assetCoin(&a[1], 1, 0, 50, 6000);
    plainCoin(&f[0], 1, 1, (uint64_t)fee + 100);
    check(choose(&p, as, 2, fs, 1, 60, 100000), "planned (a fee coin of the fee and 100)");
    check(p.o.plan.outputCount == 3, "no DGB change output (%zu outputs)", p.o.plan.outputCount);
    sendBoth("no DGB change", &p, &p.o, kAsset, 60, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void sign_no_fee_input(void)
{
    Coin a[1];
    Plan p;
    assetCoin(&a[0], 1, 2, 10, 10000000);   // an asset coin that pays the fee and the markers itself
    Coin *as[1] = { &a[0] };
    check(choose(&p, as, 1, NULL, 0, 4, 100000), "planned");
    check(p.o.plan.dgbInputCount == 0, "no fee input (%zu)", p.o.plan.dgbInputCount);
    sendBoth("no fee input", &p, &p.o, kAsset, 4, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void sign_many_inputs(void)
{
    Coin a[6], f[5];
    Coin *as[6], *fs[5];
    Plan p;
    for (int i = 0; i < 6; i++) { assetCoin(&a[i], 1, 3, 100 + i, 6000); as[i] = &a[i]; }
    for (int i = 0; i < 5; i++) { plainCoin(&f[i], i % 3, 4 + i, 60000 + 10000 * (uint64_t)i); fs[i] = &f[i]; }
    check(choose(&p, as, 6, fs, 5, 550, 100000), "planned (why %d)", (int)p.o.plan.why);
    check(p.o.plan.assetInputCount >= 5 && p.o.plan.dgbInputCount >= 2, "many inputs (%zu asset, %zu fee)",
          p.o.plan.assetInputCount, p.o.plan.dgbInputCount);
    sendBoth("six asset coins, five fee coins", &p, &p.o, kAsset, 550, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void sign_txid_case(void)
{
    Coin a[1], f[1];
    Plan p;
    assetCoin(&a[0], 1, 0, 10, 6000);
    plainCoin(&f[0], 1, 1, 50000000);
    for (char *c = a[0].txid; *c; c++) if (*c >= 'a' && *c <= 'f') *c = (char)(*c - 32);
    for (char *c = f[0].txid; *c; c++) if (*c >= 'a' && *c <= 'f') *c = (char)(*c - 32);
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 1, fs, 1, 3, 100000), "planned");
    sendBoth("txids in upper case", &p, &p.o, kAsset, 3, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

// A plan from one source of `na` asset coins and `nf` fee coins.
static void basicPlan(Plan *p, Coin *a, size_t na, Coin *f, size_t nf, int64_t q)
{
    Coin *as[8], *fs[8];
    for (size_t i = 0; i < na; i++) { assetCoin(&a[i], 1, 0, 600 + 100 * (int64_t)i, 6000); as[i] = &a[i]; }
    for (size_t i = 0; i < nf; i++) { plainCoin(&f[i], 1, 1 + (int)i, 40000000); fs[i] = &f[i]; }
    check(choose(p, as, na, fs, nf, q, 100000), "planned");
}

static void binding_quantity(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);   // 600 + 700
    sendBoth("the approved units", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    sendBoth("one unit fewer approved", &p, &p.o, kAsset, 799, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    sendBoth("one unit more approved", &p, &p.o, kAsset, 801, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    sendBoth("all the units approved", &p, &p.o, kAsset, 1300, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    cleanup();
}

static void binding_quantity_bounds(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    sendBoth("zero units approved", &p, &p.o, kAsset, 0, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    sendBoth("negative units approved", &p, &p.o, kAsset, -800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    sendBoth("more units than the inputs hold", &p, &p.o, kAsset, 1301, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    // An asset input that holds no units (or fewer than none), the sum kept at the plan's: the row is refused itself.
    BRAssetRow *r0 = &p.a[p.o.plan.assetInputs[0]], *r1 = &p.a[p.o.plan.assetInputs[1]];
    int64_t q0 = r0->assetQuantity, q1 = r1->assetQuantity;
    r0->assetQuantity = q0 + q1; r1->assetQuantity = 0;
    sendBoth("an asset input of 0 units", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    r0->assetQuantity = q0 + q1 + 5; r1->assetQuantity = -5;
    sendBoth("an asset input of -5 units", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    r0->assetQuantity = q0; r1->assetQuantity = q1;
    cleanup();
}

static void binding_asset(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    sendBoth("another asset approved", &p, &p.o, kOtherAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    p.a[p.o.plan.assetInputs[0]].assetId = kOtherAsset;
    sendBoth("an asset input of another asset", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    p.a[p.o.plan.assetInputs[0]].assetId = NULL;
    sendBoth("an asset input with no asset", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_WRONG_ASSET, -1);
    cleanup();
}

static void binding_recipient(void)
{
    Coin a[1], f[1];
    Plan p;
    char padded[4][120];
    basicPlan(&p, a, 1, f, 1, 100);
    snprintf(padded[0], 120, "%s\n", recip());
    snprintf(padded[1], 120, " %s", recip());
    snprintf(padded[2], 120, "\t%s \r\n", recip());
    snprintf(padded[3], 120, "%s\xc2\xa0", recip());
    sendBoth("the address as approved", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    for (int i = 0; i < 4; i++)
        sendBoth("the address with whitespace", &p, &p.o, kAsset, 100, padded[i], NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_RECIPIENT, 0);
    sendBoth("not an address", &p, &p.o, kAsset, 100, "x", NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_RECIPIENT, 0);
    sendBoth("an empty address", &p, &p.o, kAsset, 100, "", NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_RECIPIENT, -1);
    sendBoth("a P2PKH recipient", &p, &p.o, kAsset, 100, g_foreign[0][2].s, NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    sendBoth("a taproot recipient (our own)", &p, &p.o, kAsset, 100, g_own[2][9].s, NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void binding_fee_approval(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    int64_t paid = p.o.plan.paidFeeSats;
    sendBoth("the fee approved exactly", &p, &p.o, kAsset, 100, recip(), paid, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    sendBoth("no limit (Kotlin's default)", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    sendBoth("an approval one below the fee", &p, &p.o, kAsset, 100, recip(), paid - 1, NULL, 0, BR_ASSET_TRANSFER_FEE_NOT_APPROVED, -1);
    sendBoth("an approval of zero", &p, &p.o, kAsset, 100, recip(), 0, NULL, 0, BR_ASSET_TRANSFER_FEE_NOT_APPROVED, -1);
    sendBoth("a negative approval", &p, &p.o, kAsset, 100, recip(), -1, NULL, 0, BR_ASSET_TRANSFER_FEE_NOT_APPROVED, -1);
    cleanup();
}

static void rule_state(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);   // planned under NONE
    BRSweepRuleState states[3] = { BR_SWEEP_RULE_BOUND, BR_SWEEP_RULE_UNKNOWN, (BRSweepRuleState)99 };
    for (int i = 0; i < 3; i++) {
        g_ruleState = states[i];
        sendBoth("a PLANNED outcome under a rule state other than NONE", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0,
                 BR_ASSET_TRANSFER_RULE_STATE, -2);
        check(g_lastReport.input == SIZE_MAX && g_lastReport.paidFeeSats == 0, "refused before any input is read");
    }
    g_ruleState = BR_SWEEP_RULE_NONE;
    sendBoth("the same outcome under NONE", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

// asset_conflicted_send_kat's wallet: funding F, a stuck send of F:0, its re-send (confirmed).
typedef struct { UInt256 funding, stuck, resend; const uint8_t *spk; size_t spkLen; } Conflict;
static BRTransaction *conflictSend(const uint8_t *spk, size_t spkLen, UInt256 prev, uint64_t fee)
{
    static const uint8_t kPh[1] = { 0 };
    static const uint8_t kOpReturn[10] = { 0x6a, 0x08, 0x44, 0x41, 0x03, 0x15, 0x00, 0x01, 0x02, 0x09 };
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPh, 0, kPh, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 6000, g_foreignSpk, g_foreignLen);
    BRTransactionAddOutput(tx, 0, kOpReturn, sizeof(kOpReturn));
    BRTransactionAddOutput(tx, 6000, spk, spkLen);
    BRTransactionAddOutput(tx, 5000000 - fee, spk, spkLen);
    finalizeTxHash(tx);
    return tx;
}
static void conflictSetup(Conflict *c)
{
    c->spk = g_ownSpk[1][5];
    c->spkLen = g_ownLen[1][5];
    const uint8_t *s[1] = { c->spk };
    size_t l[1] = { c->spkLen };
    uint64_t amt[1] = { 5020000 };
    c->funding = fund(s, l, amt, 1, 0, 1);
    BRTransaction *stuck = conflictSend(c->spk, c->spkLen, c->funding, 10000);
    c->stuck = stuck->txHash;
    check(BRWalletRegisterTransactionTrusted(g_wallet, stuck) != 0, "the stuck attempt registered");
    BRTransaction *resend = conflictSend(c->spk, c->spkLen, c->funding, 20000);
    c->resend = resend->txHash;
    check(BRWalletRegisterTransactionTrusted(g_wallet, resend) != 0, "the re-send registered");
    BRWalletUpdateTransactions(g_wallet, &c->resend, 1, 1700001, 1784980100);
}

static void conflicted_states(void)
{
    Conflict c;
    conflictSetup(&c);
    BRTransaction *stuck = BRWalletCopyTransactionForHash(g_wallet, c.stuck), *resend = BRWalletCopyTransactionForHash(g_wallet, c.resend);
    check(stuck && resend, "both attempts are in the wallet");
    if (stuck && resend) {
        check(BRWalletOutpointSpent(g_wallet, c.stuck, 2) == 0, "nothing spends the stuck attempt's change");
        check(BRWalletTransactionIsValid(g_wallet, stuck) == 0, "the stuck attempt is invalid once the re-send spends its input");
        check(BRWalletTransactionIsValid(g_wallet, resend) == 1, "the re-send is valid");
        check(BRWalletOutpointSpent(g_wallet, c.resend, 2) == 0, "the re-send's change is unspent");
    }
    if (stuck) BRTransactionFree(stuck);
    if (resend) BRTransactionFree(resend);
    check(BRWalletOutpointAssetState(g_wallet, c.stuck, 2) == -2, "the stuck attempt's change reads CONFLICTED");
    check(BRWalletOutpointAssetState(g_wallet, c.resend, 2) == 1, "the re-send's change reads HELD");
    check(BRWalletOutpointAssetState(g_wallet, c.funding, 0) == 0, "the funding outpoint reads SPENT");
    UInt256 unknown;
    memset(unknown.u8, 0xEE, 32);
    check(BRWalletOutpointAssetState(g_wallet, unknown, 0) == -1, "an unknown outpoint reads UNDETECTED");
    cleanup();
}

// A send of 4 of 9 units from the asset row (hash, n), with a plain fee coin.
static void conflictSendFrom(const char *what, const Conflict *c, UInt256 hash, uint32_t n, int64_t sats,
                             BRAssetTransferStatus want, int bridgeWant)
{
    Coin a[1], f[1];
    Plan p;
    coinSet(&a[0], hash, n, c->spk, c->spkLen, sats, kAsset, 9);
    plainCoin(&f[0], 1, 6, 30000000);
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 1, fs, 1, 4, 100000), "%s: planned", what);
    sendBoth(what, &p, &p.o, kAsset, 4, recip(), NOLIMIT, NULL, 0, want, bridgeWant);
}

static void conflicted_resend_change(void)
{
    Conflict c;
    conflictSetup(&c);
    conflictSendFrom("the re-send's change", &c, c.resend, 2, 6000, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void conflicted_stuck_change(void)
{
    Conflict c;
    conflictSetup(&c);
    conflictSendFrom("the stuck attempt's change", &c, c.stuck, 2, 6000, BR_ASSET_TRANSFER_INPUT_CONFLICTED, -1);
    check(g_lastReport.input == 0, "INPUT_CONFLICTED names the asset input (%zu)", g_lastReport.input);
    // The stuck attempt's DGB change as a fee coin.
    Coin a[1], f[1];
    Plan p;
    assetCoin(&a[0], 1, 0, 10, 6000);
    coinSet(&f[0], c.stuck, 3, c.spk, c.spkLen, 5000000 - 10000, NULL, 0);
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 1, fs, 1, 4, 100000), "planned");
    sendBoth("the stuck attempt's DGB change as the fee", &p, &p.o, kAsset, 4, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_INPUT_CONFLICTED, -1);
    check(g_lastReport.input == 1, "INPUT_CONFLICTED names the fee input (%zu)", g_lastReport.input);
    cleanup();
}

static void conflicted_spent_funding(void)
{
    Conflict c;
    conflictSetup(&c);
    conflictSendFrom("the funding outpoint the re-send spent", &c, c.funding, 0, 5020000, BR_ASSET_TRANSFER_INPUT_SPENT, 0);
    cleanup();
}

static void conflicted_undetected(void)
{
    Conflict c;
    conflictSetup(&c);
    UInt256 unknown;
    memset(unknown.u8, 0xEE, 32);
    conflictSendFrom("an outpoint the wallet never saw", &c, unknown, 0, 6000, BR_ASSET_TRANSFER_INPUT_UNKNOWN, -1);
    conflictSendFrom("an output index past the funding transaction's", &c, c.resend, 9, 6000, BR_ASSET_TRANSFER_INPUT_UNKNOWN, -1);
    cleanup();
}

static void not_planned(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRAssetSendOutcome o = p.o;
    BRAssetSendKind kinds[] = { BR_ASSET_SEND_UNVERIFIED, BR_ASSET_SEND_LOOKUP, BR_ASSET_SEND_UNCONFIRMED,
                                BR_ASSET_SEND_SPANS_ADDRESSES, BR_ASSET_SEND_NOT_PLANNED, BR_ASSET_SEND_RULE_BOUND,
                                BR_ASSET_SEND_RULES_UNKNOWN, BR_ASSET_SEND_CAPACITY, (BRAssetSendKind)99 };
    for (size_t i = 0; i < sizeof(kinds) / sizeof(*kinds); i++) {
        o = p.o;
        o.kind = kinds[i];
        sendBoth("an outcome that is not PLANNED", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_NOT_PLANNED, -2);
    }
    o = p.o;
    o.plan.status = BR_ASSET_PLAN_REFUSED;
    sendBoth("a plan that is not READY", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_NOT_PLANNED, -2);
    o.plan.status = BR_ASSET_PLAN_CAPACITY;
    sendBoth("a plan that is not READY", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_NOT_PLANNED, -2);
    // A real refusal of the choice: the rules are not known.
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    Plan q;
    choose(&q, as, 1, fs, 1, 100, 100000);
    q.o = BRAssetSendChoose(BR_SWEEP_RULE_UNKNOWN, 1, kAsset, q.a, 1, q.d, q.parent, 1, 100, 100000, NULL, 0, NULL, 0, q.ans, 1,
                            q.marks, PLAN_ROWS, q.work, 4 * PLAN_ROWS);
    check(q.o.kind == BR_ASSET_SEND_RULES_UNKNOWN, "the choice refuses an asset whose rules are unknown");
    sendBoth("the choice's refusal", &q, &q.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_NOT_PLANNED, -2);
    cleanup();
}

static void arguments(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRTransaction *tx = &g_sentinel;
    BRUTXO held[1];
    memset(held, 0, sizeof(held));
    const uint8_t *seed = g_seedBytes;
#define CALL(txp, w, o, ar, an, dr, dn, id, rc, h, hn, sd, sl) \
    BRWalletBuildAssetTransfer(txp, w, BR_SWEEP_RULE_NONE, o, ar, an, dr, dn, id, 100, rc, NOLIMIT, h, hn, sd, sl, 1000.0, NULL)
    check(CALL(NULL, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT, "txOut NULL");
    tx = &g_sentinel;
    check(CALL(&tx, NULL, &p.o, p.a, 1, p.d, 1, kAsset, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "wallet NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, NULL, p.a, 1, p.d, 1, kAsset, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "outcome NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, NULL, 1, p.d, 1, kAsset, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "asset rows NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, NULL, 1, kAsset, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "fee rows NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, NULL, recip(), NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "asset id NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, NULL, NULL, 0, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "recipient NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), NULL, 1, seed, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "held NULL with a count");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), held, 0, NULL, 64) == BR_ASSET_TRANSFER_BAD_ARGUMENT && ! tx, "seed NULL");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), held, 0, seed, 0) == BR_ASSET_TRANSFER_BAD_SEED && ! tx, "seed of 0 bytes");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), held, 0, seed, 65) == BR_ASSET_TRANSFER_BAD_SEED && ! tx, "seed of 65 bytes");
    tx = &g_sentinel;
    check(CALL(&tx, g_wallet, &p.o, p.a, 1, p.d, 1, kAsset, recip(), held, 0, seed, 64) == BR_ASSET_TRANSFER_SIGNED && tx && tx != &g_sentinel,
          "held with a count of 0 signs");
    if (tx && tx != &g_sentinel) BRTransactionFree(tx);
    BRCoinReservationClear();
    // The report is written on every path, and may be NULL.
    BRAssetTransferReport rep;
    memset(&rep, 0xab, sizeof(rep));
    tx = &g_sentinel;
    check(BRWalletBuildAssetTransfer(&tx, g_wallet, BR_SWEEP_RULE_NONE, &p.o, p.a, 1, p.d, 1, kAsset, 100, recip(), NOLIMIT, NULL, 0, seed, 0,
                                     1000.0, &rep) == BR_ASSET_TRANSFER_BAD_SEED && rep.input == SIZE_MAX && rep.paidFeeSats == 0,
          "a refusal before any input is read reports none");
#undef CALL
    cleanup();
}

static void shape_indexes(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    BRAssetSendOutcome o;
    size_t bad[4];
    // An index past every row: no row may be read through it (UBSan flags the pointer arithmetic if one is).
    o = p.o; bad[0] = SIZE_MAX / 2; o.plan.assetInputs = bad; o.plan.assetInputCount = 1;
    sendBoth("an asset input index past the rows", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; bad[0] = SIZE_MAX / 2; o.plan.dgbInputs = bad; o.plan.dgbInputCount = 1;
    sendBoth("a fee input index past the rows", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.assetInputCount = 0;
    sendBoth("no asset input", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.assetInputs = NULL;
    sendBoth("no asset index list", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.dgbInputs = NULL;
    sendBoth("no fee index list", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.outputCount = 2;
    sendBoth("two outputs", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.outputCount = 5;
    sendBoth("five outputs", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.opReturnLen = 0;
    sendBoth("an empty carrier", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, 0);
    o = p.o; o.plan.sourceScript = NULL;
    sendBoth("no source script", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o = p.o; o.plan.sourceScriptLen = BR_ASSET_TRANSFER_SCRIPT_MAX + 1;
    sendBoth("a source script past 256 bytes", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    // The asset rows agreeing with such a source script: the source script's own bounds refuse it.
    uint8_t longScript[BR_ASSET_TRANSFER_SCRIPT_MAX + 1];
    memset(longScript, 0x51, sizeof(longScript));
    o = p.o; o.plan.sourceScript = longScript; o.plan.sourceScriptLen = sizeof(longScript);
    for (size_t i = 0; i < o.plan.assetInputCount; i++) { p.a[o.plan.assetInputs[i]].script = longScript; p.a[o.plan.assetInputs[i]].scriptLen = sizeof(longScript); }
    sendBoth("asset rows and source script of 257 bytes", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    o.plan.sourceScript = NULL; o.plan.sourceScriptLen = 0;
    for (size_t i = 0; i < o.plan.assetInputCount; i++) { p.a[o.plan.assetInputs[i]].script = NULL; p.a[o.plan.assetInputs[i]].scriptLen = 0; }
    sendBoth("asset rows and source script with no script", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -2);
    cleanup();
}

static void shape_outputs(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    check(p.o.plan.outputCount == 4, "four outputs");
    BRAssetSendOutcome o;
    o = p.o; o.plan.outputs[2] = p.o.plan.outputs[3]; o.plan.outputs[3] = p.o.plan.outputs[2];
    sendBoth("the DGB change after the last marker", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.outputs[0].sats -= 1; o.plan.outputs[2].sats += 1;
    sendBoth("a recipient marker of 5999", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.outputs[1].sats = 1; o.plan.outputs[2].sats -= 1;
    sendBoth("a carrier of 1 sat", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.outputs[3].sats += 1; o.plan.outputs[2].sats -= 1;
    sendBoth("a last marker of 6001", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.outputs[0].role = BR_ASSET_ROLE_ASSET_DATA; o.plan.outputs[1].role = BR_ASSET_ROLE_RECIPIENT_MARKER;
    sendBoth("the carrier first", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.paidFeeSats += o.plan.outputs[2].sats - BR_ASSET_CHANGE_DUST_SATS; o.plan.outputs[2].sats = BR_ASSET_CHANGE_DUST_SATS;
    sendBoth("a DGB change at the dust line", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    o = p.o; o.plan.sourceScript = g_ownSpk[1][7]; o.plan.sourceScriptLen = g_ownLen[1][7];
    sendBoth("change to another of our scripts than the asset inputs'", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    // Every asset input is held to the source script, not only the first.
    BRAssetRow *second = &p.a[p.o.plan.assetInputs[1]];
    const uint8_t *ss = second->script;
    size_t sl = second->scriptLen;
    second->script = g_ownSpk[1][7]; second->scriptLen = g_ownLen[1][7];
    sendBoth("a second asset input at another of our scripts", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_PLAN_SHAPE, -1);
    second->script = ss; second->scriptLen = sl;
    cleanup();
}

static void bounds_txid(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    size_t k = p.o.plan.dgbInputs[0];
    char shortId[80], badHex[80];
    snprintf(shortId, sizeof(shortId), "%.63s", p.d[k].txid);
    snprintf(badHex, sizeof(badHex), "%s", p.d[k].txid);
    badHex[10] = 'g';
    const char *orig = p.d[k].txid;
    p.d[k].txid = shortId;
    sendBoth("a txid of 63 digits", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, 0);
    p.d[k].txid = badHex;
    sendBoth("a txid with a letter that is not hex", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, 0);
    p.d[k].txid = NULL;
    sendBoth("no txid", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, 0);
    p.d[k].txid = orig;
    uint8_t longScript[BR_ASSET_TRANSFER_SCRIPT_MAX + 1];
    memset(longScript, 0x51, sizeof(longScript));
    const uint8_t *os = p.d[k].script;
    size_t ol = p.d[k].scriptLen;
    p.d[k].script = longScript; p.d[k].scriptLen = sizeof(longScript);
    sendBoth("a fee script of 257 bytes", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, 0);
    p.d[k].scriptLen = 0;
    sendBoth("an empty fee script", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, 0);
    p.d[k].script = os; p.d[k].scriptLen = ol;
    BRAssetTransferReport rep;
    uint8_t ser[4096];
    size_t n;
    p.d[k].txid = shortId;
    coreSign(&p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, ser, &n, &rep);
    check(rep.input == p.o.plan.assetInputCount, "BAD_INPUT names the fee input (%zu)", rep.input);
    p.d[k].txid = orig;
    cleanup();
}

static void bounds_amounts(void)
{
    Coin a[1], f[2];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    size_t k = p.o.plan.dgbInputs[0];
    int64_t os = p.d[k].satoshis;
    int32_t ov = p.d[k].vout;
    p.d[k].vout = -1;
    sendBoth("a vout of -1", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, -1);
    p.d[k].vout = ov;
    p.d[k].satoshis = -1;
    sendBoth("a fee coin of -1 sats", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, -1);
    p.d[k].satoshis = (int64_t)MAX_MONEY + 1;
    sendBoth("a fee coin above MAX_MONEY", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, -1);
    p.d[k].satoshis = os;
    p.a[p.o.plan.assetInputs[0]].satoshis = (int64_t)MAX_MONEY;
    p.d[k].satoshis = 2;
    sendBoth("inputs summing past MAX_MONEY", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_BAD_INPUT, -1);
    cleanup();
}

static void duplicate_input(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRAssetSendOutcome o = p.o;
    size_t twice[2] = { p.o.plan.dgbInputs[0], p.o.plan.dgbInputs[0] };
    o.plan.dgbInputs = twice;
    o.plan.dgbInputCount = 2;
    sendBoth("a fee coin listed twice", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_DUPLICATE_INPUT, 0);
    // The same outpoint in two spellings of its txid.
    char upper[80];
    snprintf(upper, sizeof(upper), "%s", p.d[twice[0]].txid);
    for (char *c = upper; *c; c++) if (*c >= 'a' && *c <= 'f') *c = (char)(*c - 32);
    p.d[p.nd] = p.d[twice[0]];
    p.d[p.nd].txid = upper;
    twice[1] = p.nd;
    p.nd++;
    sendBoth("one fee coin in two spellings", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_DUPLICATE_INPUT, 0);
    // An asset coin given again as a fee coin.
    p.d[p.nd] = p.a[p.o.plan.assetInputs[0]];
    p.d[p.nd].isAsset = 0;
    twice[1] = p.nd;
    p.nd++;
    sendBoth("an asset coin as a fee coin too", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_DUPLICATE_INPUT, 0);
    cleanup();
    // Two outputs of one transaction are two outpoints: a send spending both signs.
    Coin a2[1], f2[2];
    Plan q;
    assetCoin(&a2[0], 1, 0, 10, 6000);
    const uint8_t *s2[2] = { g_ownSpk[1][2], g_ownSpk[1][3] };
    size_t l2[2] = { g_ownLen[1][2], g_ownLen[1][3] };
    uint64_t amt2[2] = { 50000, 50000 };
    UInt256 h2 = fund(s2, l2, amt2, 2, 0, 1);
    coinSet(&f2[0], h2, 0, s2[0], l2[0], 50000, NULL, 0);
    coinSet(&f2[1], h2, 1, s2[1], l2[1], 50000, NULL, 0);
    Coin *as2[1] = { &a2[0] }, *fs2[2] = { &f2[0], &f2[1] };
    check(choose(&q, as2, 1, fs2, 2, 4, 100000), "planned");
    check(q.o.plan.dgbInputCount == 2, "both outputs of the one transaction pay the fee (%zu)", q.o.plan.dgbInputCount);
    sendBoth("two outputs of one transaction as fee coins", &q, &q.o, kAsset, 4, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

// A carrier from instructions, written with the encoder, or (altFirst) with the first amount in another
// fixed-precision form of the same value: mantissa m10 * 10, exponent e - 1.
static size_t writeCarrier(const BRAssetTransferInstruction *insts, size_t n, int version, int opcode, int altFirst,
                           uint8_t *out)
{
    uint8_t payload[80];
    BRAssetBitWriter w = BRAssetBitWriterMake(payload, sizeof(payload));
    BRAssetBitsWriteByte(&w, 0x44);
    BRAssetBitsWriteByte(&w, 0x41);
    BRAssetBitsWriteByte(&w, (uint8_t)version);
    BRAssetBitsWriteByte(&w, (uint8_t)opcode);
    for (size_t i = 0; i < n; i++) {
        BRAssetBitsWrite(&w, insts[i].skip ? 1 : 0, 1);
        BRAssetBitsWrite(&w, 0, 1);
        BRAssetBitsWrite(&w, 0, 1);
        BRAssetBitsWrite(&w, (uint64_t)insts[i].outputIndex, 5);
        if (i == 0 && altFirst) {   // amount 800 = 8e2 canonically; written as 80e1
            BRAssetBitsWrite(&w, 1, 3);
            BRAssetBitsWrite(&w, (uint64_t)(insts[i].amount / 10), 9);
            BRAssetBitsWrite(&w, 1, 4);
        } else BRAssetBitsWriteFixedPrecision(&w, insts[i].amount);
    }
    size_t len = BRAssetBitWriterLength(&w);
    out[0] = 0x6a;
    out[1] = (uint8_t)len;
    memcpy(out + 2, payload, len);
    return len + 2;
}

static void setCarrier(BRAssetSendOutcome *o, const uint8_t *s, size_t n)
{
    memset(o->plan.opReturnScript, 0, sizeof(o->plan.opReturnScript));
    memcpy(o->plan.opReturnScript, s, n);
    o->plan.opReturnLen = n;
}

static void carrier_units(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);   // 700 then 600 (largest first): [700->0 skip] [100->0] [500->3]
    BRAssetSendOutcome o;
    uint8_t s[100];
    BRAssetTransferInstruction all[2] = { { 1, 0, 0, 0, 0, 700 }, { 0, 0, 0, 0, 0, 600 } };
    o = p.o; setCarrier(&o, s, writeCarrier(all, 2, 3, 0x15, 0, s));
    sendBoth("a carrier sending every unit to the recipient", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    BRAssetTransferInstruction toChange[3] = { { 1, 0, 0, 0, 0, 700 }, { 0, 0, 0, 0, 0, 100 }, { 0, 0, 0, 0, 2, 500 } };
    o = p.o; setCarrier(&o, s, writeCarrier(toChange, 3, 3, 0x15, 0, s));
    sendBoth("a carrier sending the change to the DGB change output", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    BRAssetTransferInstruction toCarrier[3] = { { 1, 0, 0, 0, 0, 700 }, { 0, 0, 0, 0, 0, 100 }, { 0, 0, 0, 0, 1, 500 } };
    o = p.o; setCarrier(&o, s, writeCarrier(toCarrier, 3, 3, 0x15, 0, s));
    sendBoth("a carrier sending the change to the carrier output", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    BRAssetTransferInstruction past[3] = { { 1, 0, 0, 0, 0, 700 }, { 0, 0, 0, 0, 0, 100 }, { 0, 0, 0, 0, 9, 500 } };
    o = p.o; setCarrier(&o, s, writeCarrier(past, 3, 3, 0x15, 0, s));
    sendBoth("a carrier naming an output past the last (all voided to the last)", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    // A percent instruction: the carrier reads exactly, but where its units land is not decided.
    uint8_t pc[8] = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x15, 0x20, 0x32 };   // percent, output 0, 50%
    o = p.o; setCarrier(&o, pc, sizeof(pc));
    sendBoth("a percent instruction", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_UNITS_MISPLACED, -1);
    cleanup();
}

static void carrier_list(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    BRAssetSendOutcome o;
    uint8_t s[100];
    // The change left implicit: the units land as the plan's do, but the list is not the plan's.
    BRAssetTransferInstruction implicit[2] = { { 1, 0, 0, 0, 0, 700 }, { 0, 0, 0, 0, 0, 100 } };
    o = p.o; setCarrier(&o, s, writeCarrier(implicit, 2, 3, 0x15, 0, s));
    sendBoth("the change left to the implicit rule", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    // The first input's units in two instructions: the same landing, another list.
    BRAssetTransferInstruction split[4] = { { 0, 0, 0, 0, 0, 400 }, { 1, 0, 0, 0, 0, 300 }, { 0, 0, 0, 0, 0, 100 },
                                            { 0, 0, 0, 0, 3, 500 } };
    o = p.o; setCarrier(&o, s, writeCarrier(split, 4, 3, 0x15, 0, s));
    sendBoth("the units drawn in another split", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    cleanup();
}

static void carrier_noncanonical(void)
{
    Coin a[1], f[1];
    Plan p;
    Coin *as[1], *fs[1];
    assetCoin(&a[0], 1, 0, 1000, 6000); as[0] = &a[0];
    plainCoin(&f[0], 1, 1, 40000000); fs[0] = &f[0];
    check(choose(&p, as, 1, fs, 1, 800, 100000), "planned");
    BRAssetTransferInstruction list[2] = { { 0, 0, 0, 0, 0, 800 }, { 0, 0, 0, 0, 3, 200 } };
    uint8_t s[100];
    size_t n = writeCarrier(list, 2, 3, 0x15, 0, s);
    check(n == p.o.plan.opReturnLen && memcmp(s, p.o.plan.opReturnScript, n) == 0, "the KAT's writer writes the plan's carrier");
    BRAssetSendOutcome o = p.o;
    n = writeCarrier(list, 2, 3, 0x15, 1, s);
    check(n == p.o.plan.opReturnLen && memcmp(s, p.o.plan.opReturnScript, n) != 0, "800 written as 80e1 is another script of the same length");
    setCarrier(&o, s, n);
    BRAssetHeader h;
    BRAssetTransferInstruction got[4];
    check(BRAssetDecodeInstructions(s, n, &h, got, 4) == BR_ASSET_SCRIPT_ASSET && h.instructionCount == 2 && got[0].amount == 800 &&
          BRAssetPayloadReadsExactly(s, n, &h, list, 2), "it reads exactly as the plan's list");
    sendBoth("the plan's list in another encoding", &p, &o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    cleanup();
}

static void carrier_not_exact(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);   // 600: [100->0] [500->3]
    BRAssetSendOutcome o;
    uint8_t s[100];
    size_t n = p.o.plan.opReturnLen;
    // A byte after the push: the lenient decoder reads the push, the exact reading refuses the script.
    o = p.o; memcpy(s, p.o.plan.opReturnScript, n); s[n] = 0x00; setCarrier(&o, s, n + 1);
    sendBoth("a byte after the push", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    // The payload pushed with OP_PUSHDATA1: the exact reading accepts that framing, the plan's is a direct push.
    o = p.o; s[0] = 0x6a; s[1] = 0x4c; s[2] = (uint8_t)(n - 2); memcpy(s + 3, p.o.plan.opReturnScript + 2, n - 2); setCarrier(&o, s, n + 1);
    sendBoth("the payload framed with OP_PUSHDATA1", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    cleanup();
}

static void carrier_not_transfer(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRAssetSendOutcome o;
    uint8_t s[100];
    BRAssetTransferInstruction list[2] = { { 0, 0, 0, 0, 0, 100 }, { 0, 0, 0, 0, 3, 500 } };
    o = p.o; setCarrier(&o, s, writeCarrier(list, 2, 2, 0x15, 0, s));
    sendBoth("a version-2 transfer", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    o = p.o; setCarrier(&o, s, writeCarrier(list, 2, 3, 0x25, 0, s));
    sendBoth("a burn", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    static const uint8_t notDA[6] = { 0x6a, 0x04, 0x58, 0x58, 0x03, 0x15 };
    o = p.o; setCarrier(&o, notDA, sizeof(notDA));
    sendBoth("an OP_RETURN that is not a carrier", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    static const uint8_t cut[5] = { 0x6a, 0x03, 0x44, 0x41, 0x03 };
    o = p.o; setCarrier(&o, cut, sizeof(cut));
    sendBoth("a carrier cut short", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_CARRIER_MISMATCH, -1);
    cleanup();
}

static void fee_mismatch(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRAssetSendOutcome o;
    o = p.o; o.plan.paidFeeSats += 1;
    sendBoth("a plan naming one sat more fee", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.outputs[2].sats -= 1000;
    sendBoth("a DGB change 1000 sats short of the plan's fee", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.outputs[2].sats = (int64_t)MAX_MONEY;
    sendBoth("outputs worth more than the inputs", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    // The plan's fee rule (the estimate plus 0..dust), with the fee paid and the plan agreeing.
    int64_t paid = p.o.plan.paidFeeSats;
    o = p.o; o.plan.outputs[2].sats -= BR_ASSET_CHANGE_DUST_SATS + 1; o.plan.paidFeeSats += BR_ASSET_CHANGE_DUST_SATS + 1;
    sendBoth("change above the dust line moved into the fee", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.estimatedFeeSats = 0;
    sendBoth("an estimate of 0", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.estimatedFeeSats = paid + 1;
    sendBoth("an estimate above the fee paid", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.estimatedFeeSats = paid - BR_ASSET_CHANGE_DUST_SATS - 1;
    sendBoth("a fee one sat past the estimate and dust", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o = p.o; o.plan.estimatedFeeSats = paid - BR_ASSET_CHANGE_DUST_SATS;
    sendBoth("a fee of the estimate and dust exactly", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    // A fee within dust of nothing: only the estimate's own bound (above 0) refuses an estimate of 0 or less.
    o = p.o; o.plan.outputs[2].sats += paid - 1000; o.plan.paidFeeSats = 1000;
    o.plan.estimatedFeeSats = 0;
    sendBoth("an estimate of 0 under a fee of 1000", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o.plan.estimatedFeeSats = -1;
    sendBoth("an estimate of -1 under a fee of 1000", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    o.plan.estimatedFeeSats = 1;
    sendBoth("an estimate of 1 under a fee of 1000", &p, &o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
    // Every unit sent (no asset change), the DGB change dropped and paid as fee: the outputs still read as a plan's.
    Coin a2[1], f2[1];
    Plan q;
    basicPlan(&q, a2, 1, f2, 1, 600);
    check(q.o.plan.outputCount == 4, "four outputs");
    o = q.o; o.plan.paidFeeSats += o.plan.outputs[2].sats; o.plan.outputs[2] = o.plan.outputs[3]; o.plan.outputCount = 3;
    sendBoth("the DGB change folded into the fee", &q, &o, kAsset, 600, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_MISMATCH, -1);
    cleanup();
}

static void held_fee_coin(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRUTXO held[3];
    memset(held, 0, sizeof(held));
    held[0].hash = a[0].hash; held[0].n = a[0].n;
    UInt256 other; memset(other.u8, 0x31, 32);
    held[1].hash = other; held[1].n = 0;
    sendBoth("the asset input held, an unrelated outpoint held", &p, &p.o, kAsset, 100, recip(), NOLIMIT, held, 2, BR_ASSET_TRANSFER_SIGNED, 2);
    held[2].hash = f[0].hash; held[2].n = f[0].n;
    sendBoth("the fee coin held", &p, &p.o, kAsset, 100, recip(), NOLIMIT, held, 3, BR_ASSET_TRANSFER_INPUT_HELD, -1);
    check(g_lastReport.input == p.o.plan.assetInputCount, "INPUT_HELD names the fee input (%zu)", g_lastReport.input);
    held[2].n = f[0].n + 1;
    sendBoth("another output of the fee coin's transaction held", &p, &p.o, kAsset, 100, recip(), NOLIMIT, held, 3, BR_ASSET_TRANSFER_SIGNED, 2);
    cleanup();
}

static void fee_coin_not_plain(void)
{
    Coin a[1], f[2];
    Plan p;
    assetCoin(&a[0], 1, 0, 600, 6000);
    // An unconfirmed coin someone else sent.
    const uint8_t *s[1] = { g_ownSpk[1][8] };
    size_t l[1] = { g_ownLen[1][8] };
    uint64_t amt[1] = { 40000000 };
    UInt256 h = fund(s, l, amt, 1, 0, 0);
    coinSet(&f[0], h, 0, s[0], l[0], 40000000, NULL, 0);
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 1, fs, 1, 100, 100000), "planned");
    sendBoth("an unconfirmed receive as the fee", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_COIN_NOT_PLAIN, -1);
    // An output a carrier names (the wallet's asset set) as the fee.
    assetCoin(&f[1], 1, 9, 5, 40000000);
    Coin *fs2[1] = { &f[1] };
    f[1].row.isAsset = 0; f[1].row.assetId = NULL; f[1].row.assetQuantity = 0;
    check(choose(&p, as, 1, fs2, 1, 100, 100000), "planned");
    sendBoth("an asset output as the fee", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_FEE_COIN_NOT_PLAIN, -1);
    check(g_lastReport.input == 1, "FEE_COIN_NOT_PLAIN names the fee input (%zu)", g_lastReport.input);
    // An output of a transaction whose other output is a plain coin: an outpoint is its txid and its vout.
    // (Someone else's script, so a coin of the wallet's own at the other vout is the only plain one.)
    {
        const uint8_t *s3[2] = { g_ownSpk[1][4], g_foreignSpk };
        size_t l3[2] = { g_ownLen[1][4], g_foreignLen };
        uint64_t amt3[2] = { 30000000, 40000000 };
        UInt256 h3 = fund(s3, l3, amt3, 2, 0, 1);
        Coin f3[1];
        coinSet(&f3[0], h3, 1, g_foreignSpk, g_foreignLen, 40000000, NULL, 0);
        Coin *fs3[1] = { &f3[0] };
        check(choose(&p, as, 1, fs3, 1, 100, 100000), "planned");
        sendBoth("the other output of a plain coin's transaction as the fee", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0,
                 BR_ASSET_TRANSFER_FEE_COIN_NOT_PLAIN, -1);
    }
    // The planner's fee-coin rule, read from the funding transaction: a coin from a transaction with a data
    // output (here one that is no carrier) pays the fee only at the source script.
    for (int atSource = 0; atSource < 2; atSource++) {
        const uint8_t *spk = atSource ? a[0].script : g_ownSpk[1][10];
        size_t spkLen = atSource ? a[0].scriptLen : g_ownLen[1][10];
        static const uint8_t kData[6] = { 0x6a, 0x04, 0x58, 0x58, 0x58, 0x58 };
        static const uint8_t kPh[1] = { 0 };
        BRTransaction *tx = BRTransactionNew();
        UInt256 prev;
        memset(prev.u8, 0, 32);
        uint32_t t = g_tag++;
        memcpy(prev.u8, &t, sizeof(t));
        prev.u8[31] = 0xa5;
        BRTransactionAddInput(tx, prev, 0, 0, g_foreignSpk, g_foreignLen, kPh, 0, kPh, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(tx, 40000000, spk, spkLen);
        BRTransactionAddOutput(tx, 0, kData, sizeof(kData));
        finalizeTxHash(tx);
        UInt256 h4 = tx->txHash;
        check(BRWalletRegisterTransaction(g_wallet, tx) != 0, "a receive with a data output registered");
        BRWalletUpdateTransactions(g_wallet, &h4, 1, 1000000 + t, 1780000000u + t);
        if (g_fundedCount < sizeof(g_funded) / sizeof(*g_funded)) g_funded[g_fundedCount++] = h4;
        Coin f4[1];
        coinSet(&f4[0], h4, 0, spk, spkLen, 40000000, NULL, 0);
        Coin *fs4[1] = { &f4[0] };
        check(choose(&p, as, 1, fs4, 1, 100, 100000), "planned (the KAT tells the choice the parent has no data output)");
        if (atSource)
            sendBoth("a coin at the source script from a transaction with a data output", &p, &p.o, kAsset, 100, recip(), NOLIMIT,
                     NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
        else
            sendBoth("a coin at another script from a transaction with a data output", &p, &p.o, kAsset, 100, recip(), NOLIMIT,
                     NULL, 0, BR_ASSET_TRANSFER_FEE_COIN_NOT_PLAIN, -1);
    }
    cleanup();
}

static void record_mismatch(void)
{
    Coin a[1], f[1];
    Plan p;
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    assetCoin(&a[0], 1, 0, 600, 6000);
    plainCoin(&f[0], 1, 1, 40000000);
    f[0].row.satoshis += 1000;   // the row says more than the coin holds
    check(choose(&p, as, 1, fs, 1, 100, 100000), "planned");
    sendBoth("a fee coin worth less than its row", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_INPUT_MISMATCH, -1);
    f[0].row.satoshis -= 1000;
    a[0].row.script = g_ownSpk[1][10]; a[0].row.scriptLen = g_ownLen[1][10];   // the row names another of our scripts
    check(choose(&p, as, 1, fs, 1, 100, 100000), "planned");
    sendBoth("an asset row at another script than its coin's", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_INPUT_MISMATCH, -1);
    cleanup();
}

static void spent_before_sign(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    // A send the wallet registered meanwhile, spending the fee coin.
    static const uint8_t kPh[1] = { 0 };
    BRTransaction *spend = BRTransactionNew();
    BRTransactionAddInput(spend, f[0].hash, f[0].n, 40000000, f[0].script, f[0].scriptLen, kPh, 0, kPh, 0, TXIN_SEQUENCE);
    BRTransactionAddOutput(spend, 39000000, g_foreignSpk, g_foreignLen);
    finalizeTxHash(spend);
    check(BRWalletRegisterTransactionTrusted(g_wallet, spend) != 0, "a spend of the fee coin registered");
    sendBoth("a fee coin a registered send spends", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_INPUT_SPENT, 0);
    BRTransaction *spend2 = BRTransactionNew();
    BRTransactionAddInput(spend2, a[0].hash, a[0].n, 6000, a[0].script, a[0].scriptLen, kPh, 0, kPh, 0, TXIN_SEQUENCE);
    BRTransactionAddOutput(spend2, 5000, g_foreignSpk, g_foreignLen);
    finalizeTxHash(spend2);
    check(BRWalletRegisterTransactionTrusted(g_wallet, spend2) != 0, "a spend of the asset coin registered");
    Coin f2[1];
    plainCoin(&f2[0], 1, 3, 40000000);
    Plan q;
    Coin *as[1] = { &a[0] }, *fs[1] = { &f2[0] };
    check(choose(&q, as, 1, fs, 1, 100, 100000), "planned");
    sendBoth("an asset coin a registered send spends", &q, &q.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_INPUT_SPENT, 0);
    cleanup();
}

static void gate_claims(void)
{
    Coin a[1], f[1];
    Plan p;
    basicPlan(&p, a, 1, f, 1, 100);
    BRTxInput in;
    memset(&in, 0, sizeof(in));
    in.txHash = f[0].hash; in.index = f[0].n;
    UInt256 other; memset(other.u8, 0x42, 32);
    // Another signed send holds the fee coin, in each side's table.
    coin_reservation_clear(); BRCoinReservationClear();
    check(BRCoinReservationClaim(other, &in, 1, 999.0) == 1 && coin_reservation_claim(other, &in, 1, coin_reservation_now()) == 1,
          "another send claims the fee coin");
    g_keepClaims = 1;
    sendBoth("a fee coin another signed send holds", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_GATE_REFUSED, 0);
    check(BRCoinReservationClaim(other, &in, 1, 1000.0) == 1, "the other send's claim stands");
    BRCoinReservationRelease(other);
    coin_reservation_release(other);
    sendBoth("the same send once that claim is released", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    g_keepClaims = 0;
    // A signed send keeps its claim until its publish releases it.
    check(g_lastSignedValid, "the signed transaction's id");
    check(BRCoinReservationClaim(other, &in, 1, 1001.0) == 0, "after SIGNED the fee coin is claimed for the signed send");
    BRTxInput ain;
    memset(&ain, 0, sizeof(ain));
    ain.txHash = a[0].hash; ain.index = a[0].n;
    check(BRCoinReservationClaim(other, &ain, 1, 1001.0) == 0, "and so is the asset coin");
    if (g_lastSignedValid) BRCoinReservationRelease(g_lastSignedHash);
    check(BRCoinReservationClaim(other, &in, 1, 1002.0) == 1, "released by the publish, it may be claimed again");
    BRCoinReservationRelease(other);
    cleanup();
}

static void sign_failed(void)
{
    // A wallet transaction with an output to someone else's script; an asset row on that output.
    Coin a[1], f[1];
    Plan p;
    const uint8_t *s[2] = { g_ownSpk[1][11], g_foreignSpk };
    size_t l[2] = { g_ownLen[1][11], g_foreignLen };
    uint64_t amt[2] = { 6000, 6000 };
    UInt256 h = fund(s, l, amt, 2, 0, 1);
    coinSet(&a[0], h, 1, g_foreignSpk, g_foreignLen, 6000, kAsset, 50);
    plainCoin(&f[0], 1, 1, 40000000);
    Coin *as[1] = { &a[0] }, *fs[1] = { &f[0] };
    check(choose(&p, as, 1, fs, 1, 10, 100000), "planned");
    sendBoth("an input the wallet has no key for", &p, &p.o, kAsset, 10, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGN_FAILED, 0);
    cleanup();
}

static void no_memory(void)
{
    Coin a[2], f[1];
    Plan p;
    basicPlan(&p, a, 2, f, 1, 800);
    // Every allocation the core's unit makes, failed in turn, is NO_MEMORY; with none failed, it signs.
    g_failAt = LONG_MAX;
    g_allocSeq = 0;
    sendBoth("counting the core's allocations", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    long total = g_allocSeq;
    check(total >= 2, "the core allocated %ld blocks", total);
    for (long k = 0; k < total; k++) {
        g_allocSeq = 0;
        g_failAt = k;
        sendBoth("one of the core's allocations fails", &p, &p.o, kAsset, 800, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_NO_MEMORY, -2);
    }
    g_failAt = -1;
    cleanup();
}

static void keys_wiped(void)
{
    Coin a[1], f[1];
    Plan p;
    int before = g_freedBlocks;
    basicPlan(&p, a, 1, f, 1, 100);
    sendBoth("a send", &p, &p.o, kAsset, 100, recip(), NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2);
    sendBoth("a refusal after the carrier is read", &p, &p.o, kAsset, 100, recip(), 1, NULL, 0, BR_ASSET_TRANSFER_FEE_NOT_APPROVED, -1);
    check(g_freedBlocks > before, "the recorder saw the core's blocks (%d)", g_freedBlocks - before);
    check(g_freedHits == 0, "no block the core freed held the seed");
    cleanup();
}

// ---- the fuzz --------------------------------------------------------------------------------------------------
static void fuzz(void)
{
    long iters = getenv("KAT_FUZZ") ? atol(getenv("KAT_FUZZ")) : 300;
    long planned = 0, tampered = 0, signedBoth = 0;
    long refusedBy[32] = { 0 };
    if (getenv("KAT_FUZZ_SEED")) g_rng ^= (uint64_t)strtoull(getenv("KAT_FUZZ_SEED"), NULL, 10) * 0x2545F4914F6CDD1DULL;
    for (long it = 0; it < iters; it++) {
        Coin a[6], f[6];
        Coin *as[6], *fs[6];
        Plan p;
        size_t na = 1 + rndBelow(5), nf = rndBelow(6);
        int srcType = (int)rndBelow(3);
        int64_t total = 0;
        for (size_t i = 0; i < na; i++) {
            int64_t q = (int64_t)(1 + rndBelow(rndBelow(2) ? 1000 : 2000000000));
            int addr = rndBelow(4) == 0 ? 1 : 0;   // mostly one source, sometimes two
            uint64_t sats = rndBelow(4) == 0 ? 6000 + rndBelow(20000000) : 6000;
            assetCoin(&a[i], srcType, addr, q, sats);
            as[i] = &a[i];
            total += q;
        }
        for (size_t i = 0; i < nf; i++) {
            plainCoin(&f[i], (int)rndBelow(3), 2 + (int)rndBelow(8), 1000 + rndBelow(rndBelow(2) ? 100000 : 300000000));
            fs[i] = &f[i];
        }
        int64_t qty = 1 + (int64_t)rndBelow((uint64_t)total);
        int64_t rate = rndBelow(3) == 0 ? 0 : (int64_t)rndBelow(1500000);
        const char *to = g_foreign[rndBelow(2)][rndBelow(4)].s;
        if (! choose(&p, as, na, fs, nf, qty, rate)) { cleanup(); continue; }
        planned++;
        if (sendBoth("a planned send", &p, &p.o, kAsset, qty, to, NOLIMIT, NULL, 0, BR_ASSET_TRANSFER_SIGNED, 2) == BR_ASSET_TRANSFER_SIGNED)
            signedBoth++;

        BRAssetSendOutcome o = p.o;
        BRUTXO held[1];
        int64_t q2 = qty, approved = NOLIMIT;
        const char *to2 = to;
        char padded[120];
        size_t nh = 0;
        BRAssetTransferStatus want = BR_ASSET_TRANSFER_SIGNED, alt;
        int kind = (int)rndBelow(9);
        if (kind == 4 && o.plan.dgbInputCount == 0) kind = 1;
        if (kind == 7 && ! (o.plan.outputCount == 4 && o.plan.outputs[2].sats > 2 * BR_ASSET_CHANGE_DUST_SATS + 2)) kind = 1;
        switch (kind) {
        case 0: {   // an instruction's amount changed by one, re-encoded: the units land elsewhere or the list differs
            BRAssetHeader h;
            BRAssetTransferInstruction insts[BR_ASSET_MAX_INSTRUCTIONS];
            size_t n = 0;
            BRAssetDecodeInstructions(o.plan.opReturnScript, o.plan.opReturnLen, &h, insts, BR_ASSET_MAX_INSTRUCTIONS);
            size_t at = rndBelow(h.instructionCount);
            insts[at].amount += insts[at].amount > 1 && rndBelow(2) ? -1 : 1;
            BRAssetEncodeTransferScript(3, insts, h.instructionCount, o.plan.opReturnScript, sizeof(o.plan.opReturnScript), &n, NULL);
            o.plan.opReturnLen = n;
            want = BR_ASSET_TRANSFER_UNITS_MISPLACED;
            alt = BR_ASSET_TRANSFER_CARRIER_MISMATCH;
            break;
        }
        case 1: o.plan.paidFeeSats += 1 + (int64_t)rndBelow(1000); want = BR_ASSET_TRANSFER_FEE_MISMATCH; break;
        case 2: approved = o.plan.paidFeeSats - 1 - (int64_t)rndBelow(1000); want = BR_ASSET_TRANSFER_FEE_NOT_APPROVED; break;
        case 3: q2 = qty + 1; want = q2 <= total ? BR_ASSET_TRANSFER_UNITS_MISPLACED : BR_ASSET_TRANSFER_WRONG_ASSET; break;
        case 4: {
            size_t k = o.plan.dgbInputs[rndBelow(o.plan.dgbInputCount)];
            for (size_t i = 0; i < nf; i++) if (strcmp(f[i].txid, p.d[k].txid) == 0) { held[0].hash = f[i].hash; held[0].n = f[i].n; }
            nh = 1;
            want = BR_ASSET_TRANSFER_INPUT_HELD;
            break;
        }
        case 5: o.plan.outputs[0].sats += 1; want = BR_ASSET_TRANSFER_PLAN_SHAPE; break;
        case 7: {   // change above the dust line moved into the fee, the plan's fee agreeing
            int64_t x = BR_ASSET_CHANGE_DUST_SATS + 1 + (int64_t)rndBelow((uint64_t)(o.plan.outputs[2].sats - 2 * BR_ASSET_CHANGE_DUST_SATS - 2));
            o.plan.outputs[2].sats -= x;
            o.plan.paidFeeSats += x;
            want = BR_ASSET_TRANSFER_FEE_MISMATCH;
            break;
        }
        case 8: g_ruleState = rndBelow(2) ? BR_SWEEP_RULE_BOUND : BR_SWEEP_RULE_UNKNOWN; want = BR_ASSET_TRANSFER_RULE_STATE; break;
        default: snprintf(padded, sizeof(padded), "%s ", to); to2 = padded; want = BR_ASSET_TRANSFER_BAD_RECIPIENT; break;
        }
        if (kind != 0) alt = want;
        tampered++;
        uint8_t ser[4096];
        size_t n;
        coin_reservation_clear(); BRCoinReservationClear();   // the planned send above holds its claim
        BRAssetTransferStatus s = coreSign(&p, &o, kAsset, q2, to2, approved, held, nh, ser, &n, NULL);
        g_ruleState = BR_SWEEP_RULE_NONE;
        check(s == want || s == alt, "tamper %d: the core answered %s, want %s", kind, statusName(s), statusName(want));
        if ((unsigned)s < 32) refusedBy[s]++;
        coin_reservation_clear(); BRCoinReservationClear();
        cleanup();
    }
    printf("   fuzz: %ld sends, %ld planned, %ld signed byte for byte by both, %ld tampered (", iters, planned, signedBoth, tampered);
    for (int s = 0; s < 32; s++) if (refusedBy[s]) printf(" %s %ld", statusName((BRAssetTransferStatus)s), refusedBy[s]);
    printf(" )\n");
    check(planned >= iters / 3, "too few sends were planned (%ld of %ld)", planned, iters);
}

// ---- main ------------------------------------------------------------------------------------------------------
typedef struct { const char *name; void (*fn)(void); } Scenario;
static const Scenario kScenarios[] = {
    { "sign_shapes", sign_shapes }, { "sign_no_dgb_change", sign_no_dgb_change }, { "sign_no_fee_input", sign_no_fee_input },
    { "sign_many_inputs", sign_many_inputs }, { "sign_txid_case", sign_txid_case },
    { "binding_quantity", binding_quantity }, { "binding_quantity_bounds", binding_quantity_bounds },
    { "binding_asset", binding_asset }, { "binding_recipient", binding_recipient }, { "binding_fee_approval", binding_fee_approval },
    { "rule_state", rule_state },
    { "conflicted_states", conflicted_states }, { "conflicted_resend_change", conflicted_resend_change },
    { "conflicted_stuck_change", conflicted_stuck_change }, { "conflicted_spent_funding", conflicted_spent_funding },
    { "conflicted_undetected", conflicted_undetected },
    { "not_planned", not_planned }, { "arguments", arguments }, { "shape_indexes", shape_indexes }, { "shape_outputs", shape_outputs },
    { "bounds_txid", bounds_txid }, { "bounds_amounts", bounds_amounts }, { "duplicate_input", duplicate_input },
    { "carrier_units", carrier_units }, { "carrier_list", carrier_list }, { "carrier_noncanonical", carrier_noncanonical },
    { "carrier_not_exact", carrier_not_exact }, { "carrier_not_transfer", carrier_not_transfer },
    { "fee_mismatch", fee_mismatch }, { "held_fee_coin", held_fee_coin }, { "fee_coin_not_plain", fee_coin_not_plain },
    { "record_mismatch", record_mismatch }, { "spent_before_sign", spent_before_sign }, { "gate_claims", gate_claims },
    { "sign_failed", sign_failed }, { "no_memory", no_memory }, { "keys_wiped", keys_wiped }, { "fuzz", fuzz },
};

int main(int argc, char **argv)
{
    size_t ns = sizeof(kScenarios) / sizeof(*kScenarios);
    if (argc > 1 && strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < ns; i++) printf("%s\n", kScenarios[i].name);
        return 0;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (JNI_OnLoad(&g_vmValue, NULL) != JNI_VERSION_1_6) { printf("FATAL: the bridge did not boot\n"); return 2; }
    HostArray *phrase = hostArray(kMnemonic, 1, (jsize)strlen(kMnemonic));
    if (NativeBridge_recoverWalletFromBytes(env, NULL, phrase, 1700000000, NULL) != JNI_TRUE || ! g_wallet) {
        printf("FATAL: the bridge did not recover the wallet\n");
        return 2;
    }
    arena_free();
    BRBIP39DeriveKey(g_seedBytes, kMnemonic, NULL);   // the seed the bridge holds
    BRWalletSetBlockHeight(g_wallet, 2000000);
    for (int t = 0; t < 3; t++) {
        size_t n = BRWalletUnusedAddrs(g_wallet, g_own[t], OWN_PER_TYPE, 0, t);
        if (n != OWN_PER_TYPE) { printf("FATAL: %zu addresses of type %d\n", n, t); return 2; }
        for (int i = 0; i < OWN_PER_TYPE; i++) g_ownLen[t][i] = BRAddressScriptPubKey(g_ownSpk[t][i], 64, g_own[t][i].s);
    }
    uint8_t otherSeed[64];
    BRBIP39DeriveKey(otherSeed, kOtherMnemonic, NULL);
    BRWallet *other = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(otherSeed, sizeof(otherSeed)));
    for (int t = 0; t < 2; t++) BRWalletUnusedAddrs(other, g_foreign[t], 4, 0, t);
    g_foreignLen = BRAddressScriptPubKey(g_foreignSpk, sizeof(g_foreignSpk), g_foreign[1][3].s);
    BRWalletFree(other);

    int failed = 0;
    for (size_t i = 0; i < ns; i++) {
        if (argc > 1 && strcmp(argv[1], kScenarios[i].name) != 0) continue;
        g_scenario = kScenarios[i].name;
        g_fail = 0;
        kScenarios[i].fn();
        cleanup();
        printf("%s: %s\n", g_fail ? "SCENARIO-FAIL" : "SCENARIO-PASS", kScenarios[i].name);
        if (g_fail) failed++;
    }
    if (failed) { printf("FAIL: asset_send_kat (%d scenarios)\n", failed); return 1; }
    printf("PASS: asset_send_kat\n");
    return 0;
}
