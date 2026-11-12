// Host KAT: the reconcile outpoint classifier moved into the core (BROutpointClassify.h) answers exactly
// what the bridge answers.
//
// THE MOVE. NativeBridge_classifyOutpoints and NativeBridge_spendableNotListed (jni_wallet.c) put each
// "txidHex|vout" line of a list through the wallet's own partition, and count the wallet's spendable
// outputs the list does not name. BRWalletClassifyOutpoints and BRWalletSpendableNotListed are the same
// rules in the core. This file compiles the bridge's own jni_wallet.c beside the core, over the host JNI
// stand-ins (identity_sign_bytes_kat/host_jni), and drives BOTH through the same wallets and the same lists:
//   - every code path: SPENDABLE (plain, taproot, mature coinbase, a send's change), HELD (DigiAsset change,
//     an outpoint registered by the asset layer), DIGIDOLLAR, IMMATURE, NOT_CREDITED (a foreign output, an
//     OP_RETURN, a watched-only pin, a coinbase's foreign output), PENDING, SPENT, CONFLICTED (an abandoned
//     send), NOT_HELD (unknown txids, an index past the outputs), UNREADABLE (every malformed shape), and
//     the shapes strtoul accepts (a leading space or '+', leading zeros, uppercase hex);
//   - duplicates, a mixed list, a large wallet and a large list, an empty wallet, no wallet, empty lists;
//   - NOT LISTED: no lines, every spendable line, a subset, duplicates, unreadable lines, large lists.
// Each comparison requires the same answer from both -- the same codes, or both no answer; the same
// [count, sat] -- and the same wallet state before and after each call (its serialized transactions,
// balances, spendable and DigiDollar sets, address count). Each fixture line also carries the code
// written here by hand, so a fixture that stopped reaching its path would be seen.
// Nine comparison arms, one per bit of -DOUTPOINT_CLASSIFY_UNFIXED (BROutpointClassify.h), change the
// core's copy only; each must differ from the bridge in exactly the cases run.sh lists for its bit.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <time.h>

#include "jni_bridge.h"   /* the bridge's own header, over host_jni/<jni.h> and host_jni/<android/log.h> */
#include "BROutpointClassify.h"
#include "BRWalletOpen.h"
#include "BRBIP39Mnemonic.h"
#include "BRDigiDollar.h"
#include "BRNetwork.h"

jintArray JNICALL NativeBridge_classifyOutpoints(JNIEnv *env, jobject thiz, jobjectArray outpoints);
jlongArray JNICALL NativeBridge_spendableNotListed(JNIEnv *env, jobject thiz, jobjectArray outpoints);

int outpoint_classify_kat_tu2(void);

static int g_failed = 0;
static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (! cond) g_failed = 1;
}
static int g_quietPass = 0;   // large comparisons report failures only
static void ckq(int cond, const char *fmt, ...)
{
    va_list ap;
    if (cond && g_quietPass) return;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (! cond) g_failed = 1;
}

#define N(a) (sizeof(a) / sizeof((a)[0]))

/* ---- the host JNI ------------------------------------------------------------------------------ */

typedef struct { jsize len; } HostArrayHead;
typedef struct { jsize len; void **elems; } HostObjArray;
typedef struct { jsize len; jint *v; } HostIntArray;
typedef struct { jsize len; jlong *v; } HostLongArray;
typedef struct { jsize len; jchar *units; } HostString;

static long g_utfOut = 0, g_localRefs = 0;

static jsize h_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HostArrayHead *)a)->len; }
static jobject h_GetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i)
{
    (void)e;
    HostObjArray *o = a;
    if (i < 0 || i >= o->len) { printf("FAIL: GetObjectArrayElement out of range\n"); g_failed = 1; return NULL; }
    if (o->elems[i]) g_localRefs++;
    return o->elems[i];
}
static void h_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e; if (o) g_localRefs--; }

// Modified UTF-8, as the JNI specification defines GetStringUTFChars.
static char *utfOf(const HostString *h)
{
    unsigned char *out = malloc((size_t)h->len * 3 + 1);
    size_t n = 0;
    for (jsize i = 0; i < h->len; i++) {
        uint32_t c = h->units[i];
        if (c != 0 && c < 0x80) out[n++] = (unsigned char)c;
        else if (c < 0x800) { out[n++] = (unsigned char)(0xC0 | (c >> 6)); out[n++] = (unsigned char)(0x80 | (c & 0x3F)); }
        else { out[n++] = (unsigned char)(0xE0 | (c >> 12)); out[n++] = (unsigned char)(0x80 | ((c >> 6) & 0x3F));
               out[n++] = (unsigned char)(0x80 | (c & 0x3F)); }
    }
    out[n] = 0;
    return (char *)out;
}
static const char *h_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *isCopy)
{
    (void)e;
    if (isCopy) *isCopy = JNI_TRUE;
    g_utfOut++;
    return utfOf((HostString *)s);
}
static void h_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; g_utfOut--; free((void *)c); }
static jintArray h_NewIntArray(JNIEnv *e, jsize n)
{
    (void)e;
    HostIntArray *a = calloc(1, sizeof(*a));
    a->len = n; a->v = calloc(n > 0 ? (size_t)n : 1, sizeof(jint));
    return a;
}
static jlongArray h_NewLongArray(JNIEnv *e, jsize n)
{
    (void)e;
    HostLongArray *a = calloc(1, sizeof(*a));
    a->len = n; a->v = calloc(n > 0 ? (size_t)n : 1, sizeof(jlong));
    return a;
}
static void h_SetIntArrayRegion(JNIEnv *e, jintArray a, jsize at, jsize n, const jint *v)
{
    (void)e;
    HostIntArray *x = a;
    if (at < 0 || n < 0 || at + n > x->len) { printf("FAIL: SetIntArrayRegion out of range\n"); g_failed = 1; return; }
    if (n) memcpy(x->v + at, v, (size_t)n * sizeof(jint));
}
static void h_SetLongArrayRegion(JNIEnv *e, jlongArray a, jsize at, jsize n, const jlong *v)
{
    (void)e;
    HostLongArray *x = a;
    if (at < 0 || n < 0 || at + n > x->len) { printf("FAIL: SetLongArrayRegion out of range\n"); g_failed = 1; return; }
    if (n) memcpy(x->v + at, v, (size_t)n * sizeof(jlong));
}

static const struct JNINativeInterface g_table = {
    .DeleteLocalRef = h_DeleteLocalRef,
    .GetStringUTFChars = h_GetStringUTFChars,
    .ReleaseStringUTFChars = h_ReleaseStringUTFChars,
    .GetArrayLength = h_GetArrayLength,
    .GetObjectArrayElement = h_GetObjectArrayElement,
    .NewIntArray = h_NewIntArray,
    .NewLongArray = h_NewLongArray,
    .SetIntArrayRegion = h_SetIntArrayRegion,
    .SetLongArrayRegion = h_SetLongArrayRegion,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;

/* jni_registry.c is not compiled here. */
int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }

/* ---- lists ------------------------------------------------------------------------------------- */

// One list, as Kotlin hands it to the bridge (String[]) and as a platform hands it to the core (the
// strings' modified UTF-8: what the bridge itself reads). A NULL text is a null array element.
typedef struct {
    size_t n, cap;
    char **text;           // the Kotlin strings (bytes, ASCII here); NULL = a null element
    int32_t *want;         // the code written by hand; INT32_MIN = none
} List;

static void listAdd(List *l, const char *text, int32_t want)
{
    if (l->n == l->cap) {
        l->cap = l->cap ? l->cap * 2 : 16;
        l->text = realloc(l->text, l->cap * sizeof(*l->text));
        l->want = realloc(l->want, l->cap * sizeof(*l->want));
    }
    l->text[l->n] = text ? strdup(text) : NULL;
    l->want[l->n] = want;
    l->n++;
}
static void listFree(List *l)
{
    for (size_t i = 0; i < l->n; i++) free(l->text[i]);
    free(l->text); free(l->want);
    memset(l, 0, sizeof(*l));
}
#define NO_WANT INT32_MIN

static void lineOf(char out[80], UInt256 hash, uint32_t n)
{
    UInt256 be = UInt256Reverse(hash);
    snprintf(out, 80, "%s|%u", u256hex(be), n);
}
static void addOutpoint(List *l, UInt256 hash, uint32_t n, int32_t want)
{
    char line[80];
    lineOf(line, hash, n);
    listAdd(l, line, want);
}

typedef struct { HostObjArray arr; HostString *strs; } JavaList;

static JavaList javaOf(const List *l)
{
    JavaList j;
    j.arr.len = (jsize)l->n;
    j.arr.elems = calloc(l->n ? l->n : 1, sizeof(void *));
    j.strs = calloc(l->n ? l->n : 1, sizeof(HostString));
    for (size_t i = 0; i < l->n; i++) {
        if (! l->text[i]) continue;
        size_t len = strlen(l->text[i]);
        j.strs[i].len = (jsize)len;
        j.strs[i].units = calloc(len ? len : 1, sizeof(jchar));
        for (size_t k = 0; k < len; k++) j.strs[i].units[k] = (unsigned char)l->text[i][k];
        j.arr.elems[i] = &j.strs[i];
    }
    return j;
}
static void javaFree(JavaList *j)
{
    for (jsize i = 0; i < j->arr.len; i++) free(j->strs[i].units);
    free(j->strs); free(j->arr.elems);
}

/* ---- wallet state ------------------------------------------------------------------------------ */

typedef struct {
    uint8_t *ser; size_t serLen;
    uint64_t bal, dd, imm, sent, recv;
    size_t nU, nD, nAddr, nTx;
    BRUTXO *u, *d;
} State;

static State stateOf(BRWallet *w)
{
    State s;
    memset(&s, 0, sizeof(s));
    s.serLen = BRWalletSerializeTransactions(w, NULL, 0);
    s.ser = malloc(s.serLen ? s.serLen : 1);
    s.serLen = BRWalletSerializeTransactions(w, s.ser, s.serLen);
    s.bal = BRWalletBalance(w); s.dd = BRWalletDigiDollarBalance(w); s.imm = BRWalletImmatureBalance(w);
    s.sent = BRWalletTotalSent(w); s.recv = BRWalletTotalReceived(w);
    s.nU = BRWalletUTXOs(w, NULL, 0); s.u = malloc((s.nU + 1) * sizeof(BRUTXO)); s.nU = BRWalletUTXOs(w, s.u, s.nU);
    s.nD = BRWalletDigiDollarUTXOs(w, NULL, 0); s.d = malloc((s.nD + 1) * sizeof(BRUTXO)); s.nD = BRWalletDigiDollarUTXOs(w, s.d, s.nD);
    s.nAddr = BRWalletAllAddrsCount(w);
    s.nTx = BRWalletTransactions(w, NULL, 0);
    return s;
}
static int stateEq(const State *a, const State *b)
{
    return a->serLen == b->serLen && memcmp(a->ser, b->ser, a->serLen) == 0 && a->bal == b->bal && a->dd == b->dd &&
        a->imm == b->imm && a->sent == b->sent && a->recv == b->recv && a->nU == b->nU && a->nD == b->nD &&
        a->nAddr == b->nAddr && a->nTx == b->nTx && memcmp(a->u, b->u, a->nU * sizeof(BRUTXO)) == 0 &&
        memcmp(a->d, b->d, a->nD * sizeof(BRUTXO)) == 0;
}
static void stateFree(State *s) { free(s->ser); free(s->u); free(s->d); }

/* ---- the comparison ---------------------------------------------------------------------------- */

// Both answers for one list: the bridge's (through JNI, over g_wallet) and the core's (on w, over the
// strings the bridge reads). Requires the same answer, the same state before and after, and balanced JNI
// references. Returns the core's codes (malloc'd; NULL when there was no answer).
static int32_t *classifyBoth(BRWallet *w, const List *l, const char *what)
{
    JavaList j = javaOf(l);
    const char **lines = calloc(l->n ? l->n : 1, sizeof(char *));
    for (size_t i = 0; i < l->n; i++) lines[i] = j.arr.elems[i] ? utfOf(&j.strs[i]) : NULL;
    int32_t *codes = calloc(l->n ? l->n : 1, sizeof(int32_t));

    State s0 = w ? stateOf(w) : (State){ 0 };
    g_wallet = w;
    HostIntArray *bridge = NativeBridge_classifyOutpoints(env, NULL, &j.arr);
    g_wallet = NULL;
    State s1 = w ? stateOf(w) : (State){ 0 };
    int ok = BRWalletClassifyOutpoints(w, lines, l->n, codes);
    State s2 = w ? stateOf(w) : (State){ 0 };

    ck(g_utfOut == 0 && g_localRefs == 0, "%s: the bridge released every string and reference", what);
    ck((bridge != NULL) == (ok != 0), "%s: both answer, or neither (bridge %s, core %s)", what,
       bridge ? "answered" : "null", ok ? "answered" : "0");
    if (bridge && ok) {
        size_t diff = 0, first = 0;
        ck((size_t)bridge->len == l->n, "%s: the bridge answers one code per line", what);
        for (size_t i = 0; i < l->n && i < (size_t)bridge->len; i++)
            if (bridge->v[i] != codes[i]) { if (! diff) first = i; diff++; }
        ck(diff == 0, "%s: the same %zu codes%s", what, l->n, diff ? "" : "");
        if (diff) printf("      first difference: line %zu \"%s\": bridge %d, core %d (%zu lines differ)\n", first,
                         l->text[first] ? l->text[first] : "(null)", bridge->v[first], codes[first], diff);
        for (size_t i = 0; i < l->n; i++) {
            if (l->want[i] == NO_WANT) continue;
            ckq(bridge->v[i] == l->want[i] && codes[i] == l->want[i], "%s: \"%.80s\" is %d (bridge %d, core %d)", what,
                l->text[i] ? l->text[i] : "(null)", l->want[i], bridge->v[i], codes[i]);
        }
    }
    if (w) {
        ck(stateEq(&s0, &s1) && stateEq(&s1, &s2), "%s: the wallet's state is unchanged by either", what);
        stateFree(&s0); stateFree(&s1); stateFree(&s2);
    }
    if (bridge) { free(bridge->v); free(bridge); }
    for (size_t i = 0; i < l->n; i++) free((void *)lines[i]);
    free(lines);
    javaFree(&j);
    if (! ok) { free(codes); return NULL; }
    return codes;
}

// Both NOT LISTED answers for one list. *count / *sat: the core's.
static int notListedBoth(BRWallet *w, const List *l, const char *what, int64_t *count, int64_t *sat)
{
    JavaList j = javaOf(l);
    const char **lines = calloc(l->n ? l->n : 1, sizeof(char *));
    for (size_t i = 0; i < l->n; i++) lines[i] = j.arr.elems[i] ? utfOf(&j.strs[i]) : NULL;
    int64_t c = -7, s = -7;

    State s0 = w ? stateOf(w) : (State){ 0 };
    g_wallet = w;
    HostLongArray *bridge = NativeBridge_spendableNotListed(env, NULL, &j.arr);
    g_wallet = NULL;
    State s1 = w ? stateOf(w) : (State){ 0 };
    int ok = BRWalletSpendableNotListed(w, lines, l->n, &c, &s);
    State s2 = w ? stateOf(w) : (State){ 0 };

    ck(g_utfOut == 0 && g_localRefs == 0, "%s: the bridge released every string and reference", what);
    ck((bridge != NULL) == (ok != 0), "%s: both answer, or neither", what);
    if (bridge && ok)
        ck(bridge->len == 2 && bridge->v[0] == c && bridge->v[1] == s, "%s: the same [count, sat]: bridge [%lld, %lld], core [%lld, %lld]",
           what, (long long)bridge->v[0], (long long)bridge->v[1], (long long)c, (long long)s);
    if (w) {
        ck(stateEq(&s0, &s1) && stateEq(&s1, &s2), "%s: the wallet's state is unchanged by either", what);
        stateFree(&s0); stateFree(&s1); stateFree(&s2);
    }
    if (bridge) { free(bridge->v); free(bridge); }
    for (size_t i = 0; i < l->n; i++) free((void *)lines[i]);
    free(lines);
    javaFree(&j);
    if (count) *count = c;
    if (sat) *sat = s;
    return ok;
}

/* ---- the fixture wallet ------------------------------------------------------------------------ */

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const uint8_t kEmpty[1] = { 0 };

static BRWallet *openWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWalletMasterKeys keys = BRWalletMasterKeysFromSeed(seed, sizeof(seed));
    BRWallet *w = BRWalletOpenWithKeys(NULL, 0, keys);
    memset(seed, 0, sizeof(seed));
    return w;
}

typedef struct { uint8_t tr[64]; size_t trLen; uint8_t wpkh[64]; size_t wpkhLen; } OwnScripts;

// A transaction as a peer or a block delivers it: built, serialized, and parsed back (its hash set).
static BRTransaction *finalize(BRTransaction *tx)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len);
    BRTransactionSerialize(tx, buf, len);
    BRTransaction *t = BRTransactionParse(buf, len);
    free(buf);
    BRTransactionFree(tx);
    return t;
}

static size_t witnessOf(uint8_t w[106], uint8_t fill)
{
    w[0] = 71; memset(w + 1, fill, 71); w[1] = 0x30; w[71] = 0x01;
    w[72] = 33; w[73] = 0x02; memset(w + 74, (uint8_t)(fill ^ 0x5a), 32);
    return 106;
}
static void foreignInput(BRTransaction *tx, uint8_t tag, uint32_t n, uint32_t sequence)
{
    uint8_t wit[106];
    UInt256 prev;
    memset(prev.u8, tag, 32);
    BRTransactionAddInput(tx, prev, n, 0, NULL, 0, kEmpty, 0, wit, witnessOf(wit, tag), sequence);
}

static uint8_t kForeignWpkh[22], kForeignWpkh2[22], kForeignTr[34], kWatchWpkh[22];
static char kWatchAddr[80];

static size_t ddOpReturn(uint8_t *s, int64_t a0, int64_t a1)
{
    uint8_t num[9];
    size_t n = 0, l;
    s[n++] = OP_RETURN;
    s[n++] = 0x02; s[n++] = 0x44; s[n++] = 0x44;
    s[n++] = 0x01; s[n++] = DD_TYPE_TRANSFER;
    l = BRDigiDollarWriteScriptNum(a0, num); s[n++] = (uint8_t)l; memcpy(s + n, num, l); n += l;
    l = BRDigiDollarWriteScriptNum(a1, num); s[n++] = (uint8_t)l; memcpy(s + n, num, l); n += l;
    return n;
}

// A coin-generation transaction paying `amount` to `own` and 1 DGB to a foreign script.
static BRTransaction *coinbase(const uint8_t *own, size_t ownLen, uint64_t amount, uint8_t tag)
{
    UInt256 placeholder;
    memset(placeholder.u8, tag, 32);
    uint8_t sig[6] = { 0x03, 1, 2, tag, 0x42, 0 };
    BRTransaction *cb = BRTransactionNew();
    BRTransactionAddInput(cb, placeholder, 0xffffffff, 0, NULL, 0, sig, 5, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(cb, amount, own, ownLen);
    BRTransactionAddOutput(cb, 100000000, kForeignWpkh, sizeof(kForeignWpkh));
    size_t len = BRTransactionSerialize(cb, NULL, 0);
    uint8_t *buf = malloc(len);
    BRTransactionSerialize(cb, buf, len);
    size_t at = (buf[4] == 0x00) ? 7 : 5;   /* past version (+ segwit marker/flag) and input count */
    memset(&buf[at], 0, 32);                 /* the null prevout */
    BRTransaction *parsed = BRTransactionParseSigned(buf, len);
    free(buf);
    BRTransactionFree(cb);
    return parsed;
}

// The asset send of asset_conflicted_send_kat: marker to a foreign script, the DA OP_RETURN, asset change
// and DGB change to us; `fee` makes two attempts distinct.
static BRTransaction *assetSend(const OwnScripts *own, UInt256 prev, uint32_t prevN, uint64_t fee)
{
    static const uint8_t kOpReturn[10] = { 0x6a, 0x08, 0x44, 0x41, 0x03, 0x15, 0x00, 0x01, 0x02, 0x09 };
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prev, prevN, 0, own->wpkh, own->wpkhLen, kEmpty, 0, kEmpty, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 6000, kForeignWpkh, sizeof(kForeignWpkh));   // 0: the recipient's marker
    BRTransactionAddOutput(tx, 0, kOpReturn, sizeof(kOpReturn));            // 1: 1 unit -> 0, 9 -> 2
    BRTransactionAddOutput(tx, 6000, own->wpkh, own->wpkhLen);              // 2: asset change
    BRTransactionAddOutput(tx, 5000000 - fee, own->wpkh, own->wpkhLen);     // 3: DGB change
    return finalize(tx);
}

typedef struct {
    BRWallet *w;
    OwnScripts own;
    uint32_t tip;
    UInt256 f1, stuck, resend, r1, r2, pend, cbImm, cbMat, dd, ddPend, watch;
    size_t nExtra;
    UInt256 *extra;   // the large wallet's receives (two spendable outputs each)
} Fixture;

static void registerConfirmed(Fixture *f, BRTransaction *tx, uint32_t height, int trusted, const char *what)
{
    int ok = trusted ? BRWalletRegisterTransactionTrusted(f->w, tx) : BRWalletRegisterTransaction(f->w, tx);
    if (! ok) { ck(0, "setup: %s registers", what); return; }
    if (height != TX_UNCONFIRMED) BRWalletUpdateTransactions(f->w, &tx->txHash, 1, height, 1784980000u + height);
}

static void build(Fixture *f, size_t nExtra)
{
    memset(f, 0, sizeof(*f));
    kForeignWpkh[0] = 0x00; kForeignWpkh[1] = 0x14; memset(kForeignWpkh + 2, 0x66, 20);
    kForeignWpkh2[0] = 0x00; kForeignWpkh2[1] = 0x14; memset(kForeignWpkh2 + 2, 0x67, 20);
    kForeignTr[0] = 0x51; kForeignTr[1] = 0x20; memset(kForeignTr + 2, 0x77, 32);
    kWatchWpkh[0] = 0x00; kWatchWpkh[1] = 0x14; memset(kWatchWpkh + 2, 0x55, 20);
    BRAddressFromScriptPubKey(kWatchAddr, sizeof(kWatchAddr), kWatchWpkh, sizeof(kWatchWpkh));

    f->w = openWallet();
    BRAddress a = BRWalletReceiveAddress(f->w, 2), b = BRWalletReceiveAddress(f->w, 1);
    f->own.trLen = BRAddressScriptPubKey(f->own.tr, sizeof(f->own.tr), a.s);
    f->own.wpkhLen = BRAddressScriptPubKey(f->own.wpkh, sizeof(f->own.wpkh), b.s);
    const uint32_t H = BRNetworkDigiDollarActivationHeight() + 1000;
    f->tip = H + 1000;
    BRWalletSetBlockHeight(f->w, f->tip);
    BRTransaction *tx;

    // F1: a confirmed receive; its output 0 is spent by the send below, output 1 pays someone else.
    tx = BRTransactionNew(); foreignInput(tx, 0x11, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 5020000, f->own.wpkh, f->own.wpkhLen);
    BRTransactionAddOutput(tx, 1000000, kForeignWpkh, sizeof(kForeignWpkh));
    tx = finalize(tx); f->f1 = tx->txHash; registerConfirmed(f, tx, H, 0, "F1");

    // STUCK then RESEND: two sends of F1:0; the re-send confirms, the stuck attempt is left invalid.
    tx = assetSend(&f->own, f->f1, 0, 10000); f->stuck = tx->txHash; registerConfirmed(f, tx, TX_UNCONFIRMED, 1, "STUCK");
    tx = assetSend(&f->own, f->f1, 0, 20000); f->resend = tx->txHash; registerConfirmed(f, tx, H + 1, 1, "RESEND");

    // R1: a confirmed receive of two outputs; the asset layer registers output 1.
    tx = BRTransactionNew(); foreignInput(tx, 0x21, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 100000000, f->own.wpkh, f->own.wpkhLen);
    BRTransactionAddOutput(tx, 50000000, f->own.wpkh, f->own.wpkhLen);
    tx = finalize(tx); f->r1 = tx->txHash; registerConfirmed(f, tx, H + 2, 0, "R1");
    BRWalletRegisterAssetOutpoint(f->w, f->r1, 1);

    // R2: a confirmed receive to the taproot address.
    tx = BRTransactionNew(); foreignInput(tx, 0x22, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 70000000, f->own.tr, f->own.trLen);
    tx = finalize(tx); f->r2 = tx->txHash; registerConfirmed(f, tx, H + 3, 0, "R2");

    // PEND: an unconfirmed receive that signals replacement (sequence below UINT32_MAX - 1): pending.
    tx = BRTransactionNew(); foreignInput(tx, 0x23, 0, 0xfffffffdu);
    BRTransactionAddOutput(tx, 30000000, f->own.wpkh, f->own.wpkhLen);
    BRTransactionAddOutput(tx, 1000000, kForeignWpkh2, sizeof(kForeignWpkh2));
    tx = finalize(tx); f->pend = tx->txHash; registerConfirmed(f, tx, TX_UNCONFIRMED, 0, "PEND");

    // CB_IMM / CB_MAT: coin generation, immature and mature at the tip.
    tx = coinbase(f->own.wpkh, f->own.wpkhLen, 7200000000ULL, 0xc1);
    if (tx) { tx->blockHeight = f->tip - 10; tx->timestamp = 1784990000u; f->cbImm = tx->txHash;
              ck(BRWalletRegisterTransactionTrusted(f->w, tx), "setup: CB_IMM registers"); }
    else ck(0, "setup: CB_IMM parses");
    tx = coinbase(f->own.wpkh, f->own.wpkhLen, 7300000000ULL, 0xc2);
    if (tx) { tx->blockHeight = f->tip - 500; tx->timestamp = 1784980000u; f->cbMat = tx->txHash;
              ck(BRWalletRegisterTransactionTrusted(f->w, tx), "setup: CB_MAT registers"); }
    else ck(0, "setup: CB_MAT parses");

    // DD: a confirmed DigiDollar transfer of $50.00 to our taproot output; DD_PEND: the same, unconfirmed.
    for (int k = 0; k < 2; k++) {
        uint8_t opr[40];
        tx = BRTransactionNew();
        tx->version = ((uint32_t)DD_TYPE_TRANSFER << 24) | DD_VERSION_MARKER;
        foreignInput(tx, (uint8_t)(0x31 + k), 1, 0xffffffff);
        foreignInput(tx, (uint8_t)(0x41 + k), 0, 0xffffffff);
        BRTransactionAddOutput(tx, 0, f->own.tr, f->own.trLen);
        BRTransactionAddOutput(tx, 0, kForeignTr, sizeof(kForeignTr));
        BRTransactionAddOutput(tx, 0, opr, ddOpReturn(opr, 5000, 2500));
        BRTransactionAddOutput(tx, 190000000, kForeignWpkh, sizeof(kForeignWpkh));
        tx = finalize(tx);
        if (k == 0) { f->dd = tx->txHash; registerConfirmed(f, tx, H + 10, 0, "DD"); }
        else { f->ddPend = tx->txHash; registerConfirmed(f, tx, TX_UNCONFIRMED, 0, "DD_PEND"); }
    }

    // WATCH: a confirmed receive to a watched-only pin (and change to us, so the wallet keeps it).
    BRWalletAddWatchedAddress(f->w, kWatchAddr);
    tx = BRTransactionNew(); foreignInput(tx, 0x51, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 40000000, kWatchWpkh, sizeof(kWatchWpkh));
    BRTransactionAddOutput(tx, 20000000, f->own.wpkh, f->own.wpkhLen);
    tx = finalize(tx); f->watch = tx->txHash; registerConfirmed(f, tx, H + 4, 0, "WATCH");

    // The large wallet: nExtra more confirmed receives of two outputs each.
    f->nExtra = nExtra;
    f->extra = calloc(nExtra ? nExtra : 1, sizeof(UInt256));
    for (size_t i = 0; i < nExtra; i++) {
        tx = BRTransactionNew();
        UInt256 prev; memset(prev.u8, 0x99, 32); prev.u8[0] = (uint8_t)i; prev.u8[1] = (uint8_t)(i >> 8);
        uint8_t wit[106];
        BRTransactionAddInput(tx, prev, 0, 0, NULL, 0, kEmpty, 0, wit, witnessOf(wit, 0x99), 0xffffffff);
        BRTransactionAddOutput(tx, 1000000 + i, f->own.wpkh, f->own.wpkhLen);
        BRTransactionAddOutput(tx, 2000000 + i, f->own.wpkh, f->own.wpkhLen);
        tx = finalize(tx); f->extra[i] = tx->txHash;
        registerConfirmed(f, tx, H + 20 + (uint32_t)(i % 900), 0, "an extra receive");
    }
}

static void teardown(Fixture *f)
{
    g_wallet = NULL;
    BRWalletFree(f->w);
    free(f->extra);
}

/* ---- the lists of each path -------------------------------------------------------------------- */

static void addSpendable(const Fixture *f, List *l)
{
    addOutpoint(l, f->r1, 0, BR_OUTPOINT_SPENDABLE);
    addOutpoint(l, f->resend, 3, BR_OUTPOINT_SPENDABLE);   // a send's DGB change
    addOutpoint(l, f->r2, 0, BR_OUTPOINT_SPENDABLE);       // taproot
    addOutpoint(l, f->cbMat, 0, BR_OUTPOINT_SPENDABLE);    // a mature coinbase
    addOutpoint(l, f->watch, 1, BR_OUTPOINT_SPENDABLE);
}
static void addHeld(const Fixture *f, List *l)
{
    addOutpoint(l, f->resend, 2, BR_OUTPOINT_HELD);   // DigiAsset change
    addOutpoint(l, f->r1, 1, BR_OUTPOINT_HELD);       // registered by the asset layer
}
static void addDigiDollar(const Fixture *f, List *l) { addOutpoint(l, f->dd, 0, BR_OUTPOINT_DIGIDOLLAR); }
static void addImmature(const Fixture *f, List *l) { addOutpoint(l, f->cbImm, 0, BR_OUTPOINT_IMMATURE); }
static void addNotCredited(const Fixture *f, List *l)
{
    addOutpoint(l, f->f1, 1, BR_OUTPOINT_NOT_CREDITED);       // a foreign output
    addOutpoint(l, f->resend, 0, BR_OUTPOINT_NOT_CREDITED);   // the recipient's marker
    addOutpoint(l, f->resend, 1, BR_OUTPOINT_NOT_CREDITED);   // an OP_RETURN: no address
    addOutpoint(l, f->dd, 1, BR_OUTPOINT_NOT_CREDITED);
    addOutpoint(l, f->dd, 2, BR_OUTPOINT_NOT_CREDITED);
    addOutpoint(l, f->dd, 3, BR_OUTPOINT_NOT_CREDITED);
    addOutpoint(l, f->watch, 0, BR_OUTPOINT_NOT_CREDITED);    // a watched-only pin
    addOutpoint(l, f->cbImm, 1, BR_OUTPOINT_NOT_CREDITED);    // a coinbase's foreign output
    addOutpoint(l, f->cbMat, 1, BR_OUTPOINT_NOT_CREDITED);
}
static void addPending(const Fixture *f, List *l)
{
    addOutpoint(l, f->pend, 0, BR_OUTPOINT_PENDING);
    addOutpoint(l, f->pend, 1, BR_OUTPOINT_PENDING);   // pending is asked before ownership
    addOutpoint(l, f->ddPend, 0, BR_OUTPOINT_PENDING);
    addOutpoint(l, f->ddPend, 3, BR_OUTPOINT_PENDING);
}
static void addSpent(const Fixture *f, List *l) { addOutpoint(l, f->f1, 0, BR_OUTPOINT_SPENT); }
static void addConflicted(const Fixture *f, List *l)
{
    for (uint32_t n = 0; n < 4; n++) addOutpoint(l, f->stuck, n, BR_OUTPOINT_CONFLICTED);
    addOutpoint(l, f->stuck, 4, BR_OUTPOINT_NOT_HELD);   // past its outputs: asked first
}
static void addNotHeld(const Fixture *f, List *l)
{
    UInt256 unknown;
    memset(unknown.u8, 0xee, 32);
    addOutpoint(l, unknown, 0, BR_OUTPOINT_NOT_HELD);
    unknown.u8[0] = 0x01;
    addOutpoint(l, unknown, 7, BR_OUTPOINT_NOT_HELD);
    addOutpoint(l, f->r1, 2, BR_OUTPOINT_NOT_HELD);
    addOutpoint(l, f->r1, UINT32_MAX, BR_OUTPOINT_NOT_HELD);
    addOutpoint(l, UINT256_ZERO, 0, BR_OUTPOINT_NOT_HELD);
}
// Malformed lines; `trailing` adds the ones with characters after the index.
static void addUnreadable(const Fixture *f, List *l, int trailing)
{
    char base[80], buf[160];
    lineOf(base, f->r1, 0);
    base[64] = '\0';   // the txid alone
    listAdd(l, NULL, BR_OUTPOINT_UNREADABLE);
    listAdd(l, "", BR_OUTPOINT_UNREADABLE);
    listAdd(l, "|0", BR_OUTPOINT_UNREADABLE);
    listAdd(l, base, BR_OUTPOINT_UNREADABLE);                                             // no index
    snprintf(buf, sizeof(buf), "%s|", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);    // 65 bytes
    snprintf(buf, sizeof(buf), "%s0", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);    // no '|'
    snprintf(buf, sizeof(buf), "%s:0", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    snprintf(buf, sizeof(buf), "%.63s|0", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE); // 63 hex
    snprintf(buf, sizeof(buf), "%s0|0", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);  // 65 hex
    snprintf(buf, sizeof(buf), "%s|x", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    snprintf(buf, sizeof(buf), "%s|4294967296", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    snprintf(buf, sizeof(buf), "%s|99999999999999999999999", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    if (sizeof(unsigned long) > 4) {   // strtoul negates: -1 is ULONG_MAX, above UINT32_MAX where long is 64-bit
        snprintf(buf, sizeof(buf), "%s|-1", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    }
    snprintf(buf, sizeof(buf), "%s| ", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    if (trailing) {
        snprintf(buf, sizeof(buf), "%s|0x", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
        snprintf(buf, sizeof(buf), "%s|0 ", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
        snprintf(buf, sizeof(buf), "%s|1|2", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
        snprintf(buf, sizeof(buf), "%s|0\n", base); listAdd(l, buf, BR_OUTPOINT_UNREADABLE);
    }
    // what strtoul accepts: read as the bridge reads them
    snprintf(buf, sizeof(buf), "%s| 0", base); listAdd(l, buf, BR_OUTPOINT_SPENDABLE);
    snprintf(buf, sizeof(buf), "%s|+0", base); listAdd(l, buf, BR_OUTPOINT_SPENDABLE);
    snprintf(buf, sizeof(buf), "%s|0000", base); listAdd(l, buf, BR_OUTPOINT_SPENDABLE);
    snprintf(buf, sizeof(buf), "%s|4294967295", base); listAdd(l, buf, BR_OUTPOINT_NOT_HELD);
    for (size_t i = 0; i < 64; i++) buf[i] = (base[i] >= 'a' && base[i] <= 'f') ? (char)(base[i] - 'a' + 'A') : base[i];
    snprintf(buf + 64, sizeof(buf) - 64, "|0"); listAdd(l, buf, BR_OUTPOINT_SPENDABLE);   // uppercase hex
}

static void addEverything(const Fixture *f, List *l, int trailing)
{
    addSpendable(f, l); addHeld(f, l); addDigiDollar(f, l); addImmature(f, l); addNotCredited(f, l);
    addPending(f, l); addSpent(f, l); addConflicted(f, l); addNotHeld(f, l); addUnreadable(f, l, trailing);
}

/* ---- the cases --------------------------------------------------------------------------------- */

static void onePath(void (*add)(const Fixture *, List *), const char *what)
{
    Fixture f;
    List l = { 0 };
    build(&f, 0);
    add(&f, &l);
    int32_t *codes = classifyBoth(f.w, &l, what);
    ck(codes != NULL, "%s: an answer", what);
    free(codes);
    listFree(&l);
    teardown(&f);
}

static void c_spendable(void) { onePath(addSpendable, "spendable"); }
static void c_held(void) { onePath(addHeld, "held"); }
static void c_digidollar(void) { onePath(addDigiDollar, "DigiDollar"); }
static void c_immature(void) { onePath(addImmature, "immature"); }
static void c_not_credited(void) { onePath(addNotCredited, "not credited"); }
static void c_pending(void) { onePath(addPending, "pending"); }
static void c_spent(void) { onePath(addSpent, "spent"); }
static void c_conflicted(void) { onePath(addConflicted, "conflicted"); }
static void c_not_held(void) { onePath(addNotHeld, "not held"); }
static void addUnreadableTrailing(const Fixture *f, List *l) { addUnreadable(f, l, 1); }
static void c_unreadable(void) { onePath(addUnreadableTrailing, "unreadable"); }

static void c_fixture(void)
{
    Fixture f;
    build(&f, 0);
    ck(BRWalletImmatureBalance(f.w) == 7200000000ULL, "the immature coinbase is the immature balance");
    ck(BRWalletDigiDollarBalance(f.w) == 5000, "the confirmed DigiDollar transfer is the DigiDollar balance");
    BRTransaction *t = BRWalletCopyTransactionForHash(f.w, f.stuck);
    ck(t && ! BRWalletTransactionIsValid(f.w, t), "the stuck send is held and invalid");
    if (t) BRTransactionFree(t);
    t = BRWalletCopyTransactionForHash(f.w, f.pend);
    ck(t && BRWalletTransactionIsPending(f.w, t), "PEND is pending");
    if (t) BRTransactionFree(t);
    ck(BRWalletContainsAddress(f.w, kWatchAddr), "the pin is watched");
    ck(outpoint_classify_kat_tu2() == 1, "a second translation unit links");
    teardown(&f);
}

// Every path at once, twice over, the second copy in reverse order: every duplicate the same code.
static void c_mixed_duplicates(void)
{
    Fixture f;
    List l = { 0 }, once = { 0 };
    build(&f, 0);
    addEverything(&f, &once, 1);
    for (size_t i = 0; i < once.n; i++) listAdd(&l, once.text[i], once.want[i]);
    for (size_t i = once.n; i > 0; i--) listAdd(&l, once.text[i - 1], once.want[i - 1]);
    int32_t *codes = classifyBoth(f.w, &l, "mixed, twice");
    if (codes) {
        size_t bad = 0;
        for (size_t i = 0; i < once.n; i++) if (codes[i] != codes[l.n - 1 - i]) bad++;
        ck(bad == 0, "each duplicate has its first copy's code");
    } else ck(0, "an answer");
    free(codes);
    listFree(&l); listFree(&once);
    teardown(&f);
}

static void largeList(const Fixture *f, List *l, size_t copies)
{
    for (size_t c = 0; c < copies; c++) {
        for (size_t i = 0; i < f->nExtra; i++) {
            addOutpoint(l, f->extra[i], (uint32_t)((i + c) % 3), ((i + c) % 3) == 2 ? BR_OUTPOINT_NOT_HELD : BR_OUTPOINT_SPENDABLE);
        }
        addEverything(f, l, 0);
    }
}

static void c_large(void)
{
    Fixture f;
    List l = { 0 };
    build(&f, 250);
    largeList(&f, &l, 6);
    g_quietPass = 1;
    int32_t *codes = classifyBoth(f.w, &l, "large");
    g_quietPass = 0;
    ck(codes != NULL && l.n > 1800, "%zu lines over %zu spendable outputs answered", l.n, BRWalletUTXOs(f.w, NULL, 0));
    free(codes);
    listFree(&l);
    teardown(&f);
}

static void c_no_wallet_and_empty(void)
{
    Fixture f;
    List l = { 0 }, empty = { 0 };
    build(&f, 0);
    addNotHeld(&f, &l);
    int32_t *codes = classifyBoth(NULL, &l, "no wallet");
    ck(codes == NULL, "no wallet: no answer from either");
    int64_t c, s;
    ck(! notListedBoth(NULL, &l, "no wallet", &c, &s), "no wallet: no NOT LISTED answer from either");
    codes = classifyBoth(f.w, &empty, "an empty list");
    ck(codes != NULL, "an empty list: an answer from both");
    free(codes);
    // the core's own arguments (the bridge's null array is the platform's to refuse)
    int32_t one[1];
    g_wallet = NULL;
    ck(! BRWalletClassifyOutpoints(f.w, NULL, 1, one), "core: NULL lines with a count: 0");
    const char *ln[1] = { "x" };
    ck(! BRWalletClassifyOutpoints(f.w, ln, 1, NULL), "core: NULL codes with a count: 0");
    ck(BRWalletClassifyOutpoints(f.w, NULL, 0, NULL), "core: nothing to classify: 1");
    ck(! BRWalletSpendableNotListed(f.w, ln, 1, NULL, &s), "core: nowhere to write: 0");
    ck(! BRWalletSpendableNotListed(f.w, NULL, 1, &c, &s), "core: NULL lines with a count: 0");
    UInt256 h = UINT256_ZERO; uint32_t n = 0;
    ck(! BROutpointLineParse(NULL, &h, &n), "parse: NULL is unreadable");
    char line[80];
    lineOf(line, f.r1, 5);
    ck(BROutpointLineParse(line, &h, &n) && UInt256Eq(h, f.r1) && n == 5, "parse: a line reads back its outpoint");
    listFree(&l);
    // an empty wallet
    teardown(&f);
    BRWallet *w = openWallet();
    build(&f, 0); // for txids only
    List all = { 0 };
    addEverything(&f, &all, 0);
    for (size_t i = 0; i < all.n; i++) if (all.want[i] != BR_OUTPOINT_UNREADABLE) all.want[i] = BR_OUTPOINT_NOT_HELD;
    codes = classifyBoth(w, &all, "an empty wallet");
    ck(codes != NULL, "an empty wallet answers");
    free(codes);
    ck(notListedBoth(w, &all, "an empty wallet", &c, &s) && c == 0 && s == 0, "an empty wallet lists nothing");
    listFree(&all);
    BRWalletFree(w);
    teardown(&f);
}

static int64_t spendableSat(BRWallet *w) { return (int64_t)BRWalletBalance(w); }

static void n_not_listed(void)
{
    Fixture f;
    int64_t c, s;
    build(&f, 0);
    size_t nU = BRWalletUTXOs(f.w, NULL, 0);
    BRUTXO *u = calloc(nU + 1, sizeof(BRUTXO));
    nU = BRWalletUTXOs(f.w, u, nU);
    List none = { 0 }, every = { 0 }, half = { 0 }, dup = { 0 }, other = { 0 };
    for (size_t i = 0; i < nU; i++) {
        addOutpoint(&every, u[i].hash, u[i].n, NO_WANT);
        if (i % 2 == 0) addOutpoint(&half, u[i].hash, u[i].n, NO_WANT);
        addOutpoint(&dup, u[i].hash, u[i].n, NO_WANT); addOutpoint(&dup, u[i].hash, u[i].n, NO_WANT);
    }
    addHeld(&f, &other); addNotCredited(&f, &other); addPending(&f, &other); addSpent(&f, &other); addNotHeld(&f, &other);
    ck(notListedBoth(f.w, &none, "no lines", &c, &s), "no lines: an answer");
    ck(c == (int64_t)nU && s == spendableSat(f.w), "no lines: every spendable output, the whole balance (%lld, %lld)", (long long)c, (long long)s);
    ck(notListedBoth(f.w, &every, "every spendable line", &c, &s) && c == 0 && s == 0, "every spendable line: nothing");
    ck(notListedBoth(f.w, &half, "half", &c, &s) && c == (int64_t)(nU / 2), "half: the other half (%lld)", (long long)c);
    ck(notListedBoth(f.w, &dup, "every line twice", &c, &s) && c == 0, "every line twice: nothing");
    ck(notListedBoth(f.w, &other, "only lines that are not spendable", &c, &s) && c == (int64_t)nU, "lines naming no spendable output name nothing");
    listFree(&none); listFree(&every); listFree(&half); listFree(&dup); listFree(&other);
    free(u);
    teardown(&f);
}

static void n_unreadable(void)
{
    Fixture f;
    int64_t c, s;
    build(&f, 0);
    List l = { 0 };
    addUnreadable(&f, &l, 1);   // the accepted shapes name R1:0
    char line[80], buf[100];
    lineOf(line, f.r2, 0);
    snprintf(buf, sizeof(buf), "%sx", line); listAdd(&l, buf, NO_WANT);   // R2:0 with a trailing character names nothing
    ck(notListedBoth(f.w, &l, "unreadable lines", &c, &s), "an answer");
    ck(c == (int64_t)BRWalletUTXOs(f.w, NULL, 0) - 1 && s == spendableSat(f.w) - 100000000,
       "only the readable lines name R1:0 (%lld, %lld)", (long long)c, (long long)s);
    listFree(&l);
    teardown(&f);
}

static void n_large(void)
{
    Fixture f;
    int64_t c, s;
    build(&f, 250);
    List l = { 0 };
    largeList(&f, &l, 1);   // one output of each extra receive named (index i % 3: 0, 1, or none)
    ck(notListedBoth(f.w, &l, "large", &c, &s), "an answer");
    ck(c > 0 && c < (int64_t)BRWalletUTXOs(f.w, NULL, 0), "some, not all, unlisted (%lld)", (long long)c);
    listFree(&l);
    teardown(&f);
}

static double timeBridge(BRWallet *w, const List *l, int core)
{
    double best = 1e30;
    JavaList j = javaOf(l);
    const char **lines = calloc(l->n ? l->n : 1, sizeof(char *));
    for (size_t i = 0; i < l->n; i++) lines[i] = j.arr.elems[i] ? utfOf(&j.strs[i]) : NULL;
    int32_t *codes = calloc(l->n ? l->n : 1, sizeof(int32_t));
    for (int rep = 0; rep < 3; rep++) {
        clock_t t0 = clock();
        if (core) {
            int64_t c, s;
            BRWalletClassifyOutpoints(w, lines, l->n, codes);
            BRWalletSpendableNotListed(w, lines, l->n, &c, &s);
        } else {
            g_wallet = w;
            HostIntArray *a = NativeBridge_classifyOutpoints(env, NULL, &j.arr);
            HostLongArray *b = NativeBridge_spendableNotListed(env, NULL, &j.arr);
            g_wallet = NULL;
            if (a) { free(a->v); free(a); }
            if (b) { free(b->v); free(b); }
        }
        double dt = (double)(clock() - t0) / CLOCKS_PER_SEC;
        if (dt < best) best = dt;
    }
    for (size_t i = 0; i < l->n; i++) free((void *)lines[i]);
    free(lines); free(codes);
    javaFree(&j);
    return best > 1e-6 ? best : 1e-6;
}

// The move keeps the bridge's cost (each line against each spendable output): the core is no slower.
static void cost_matches_bridge(void)
{
    Fixture f;
    build(&f, 400);
    List l = { 0 };
    largeList(&f, &l, 3);
    double b = timeBridge(f.w, &l, 0), c = timeBridge(f.w, &l, 1);
    ck(c / b < 3.0, "%zu lines over %zu outputs: the core took %.2fx the bridge's time", l.n, BRWalletUTXOs(f.w, NULL, 0), c / b);
    listFree(&l);
    teardown(&f);
}

/* ---- driver ------------------------------------------------------------------------------------ */

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "c_fixture", c_fixture },
    { "c_spendable", c_spendable },
    { "c_held", c_held },
    { "c_digidollar", c_digidollar },
    { "c_immature", c_immature },
    { "c_not_credited", c_not_credited },
    { "c_pending", c_pending },
    { "c_spent", c_spent },
    { "c_conflicted", c_conflicted },
    { "c_not_held", c_not_held },
    { "c_unreadable", c_unreadable },
    { "c_mixed_duplicates", c_mixed_duplicates },
    { "c_large", c_large },
    { "c_no_wallet_and_empty", c_no_wallet_and_empty },
    { "n_not_listed", n_not_listed },
    { "n_unreadable", n_unreadable },
    { "n_large", n_large },
    { "cost_matches_bridge", cost_matches_bridge },
};

int main(int argc, char **argv)
{
    size_t i;
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) == 0) {
            CASES[i].fn();
            printf("RESULT %s %s\n", argv[1], g_failed ? "fail" : "pass");
            return g_failed ? 1 : 0;
        }
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
