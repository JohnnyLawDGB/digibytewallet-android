/* Host KAT for JNI-CB-HANDLER.
 *
 * INVARIANT: a native peer callback never calls into a handler reference that setCallbackHandler has
 * deleted, and never calls with no handler.
 *
 * This file #includes the bridge's OWN jni_peer.c (so its file-static callbacks are reachable) over the
 * stand-ins in host_jni/, and links the bridge's own jni_wallet.c and the core. Nothing of the handler
 * code is mirrored: the callbacks fired here are the functions BRPeerManager is given, and the handler is
 * registered through NativeBridge_setCallbackHandler.
 *
 * The env below hands out references as records, so it can tell a live reference from a deleted one, and
 * counts every upcall by the handler it reached. Two of its entries can run a step of the test at a
 * chosen point INSIDE a bridge call, which is how each interleaving is forced rather than raced for:
 *   - DeleteGlobalRef, after the reference is dead: callbacks arrive while a handler is being replaced;
 *   - NewByteArray: the handler is replaced while a callback is between taking it and calling it;
 *   - CallVoidMethod: the handler is replaced while a callback is inside its upcall.
 * The step runs on another thread and is waited for with a limit, so a lock held across either point is
 * a reported failure, not a hang.
 *
 * One scenario per run (argv[1]); run.sh says which arms must be red on which. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>

#include "jni_peer.c"

#include "BRBIP39Mnemonic.h"
#include "BRBIP32Sequence.h"

/* ---- references ----------------------------------------------------------------------------------- */

enum { REF_PARAM, REF_LOCAL, REF_GLOBAL };
enum { OBJ_OTHER = 0, OBJ_HANDLER_A = 1, OBJ_HANDLER_B = 2, OBJ_COUNT };

typedef struct Ref {
    struct Ref *next;
    int kind;
    int object;          /* which object it refers to */
    _Atomic int live;
} Ref;

static pthread_mutex_t g_refsLock = PTHREAD_MUTEX_INITIALIZER;
static Ref *g_refs;      /* every record ever handed out; freed at exit, so a dead one can still be asked */

static _Atomic int g_liveGlobals, g_liveLocals;
static _Atomic int g_deletedUses;     /* a deleted handler reference reached the env */
static _Atomic int g_nullUpcalls;     /* an upcall with no handler */
static _Atomic int g_nullMethods;     /* an upcall with no method */
static _Atomic int g_doubleDeletes;
static _Atomic int g_upcalls[OBJ_COUNT];

static const char *const METHODS[][2] = {
    { "onSyncProgress", "(FJ)V" },       { "onSyncComplete", "()V" },
    { "onSyncFailed", "(ILjava/lang/String;)V" }, { "onBalanceChanged", "(J)V" },
    { "onSaveBlocks", "([BI)V" },        { "onSavePeers", "([BI)V" },
    { "onSaveFilterHeaders", "([B)V" },  { "onSaveCfLedger", "([B)V" },
};
#define METHOD_COUNT ((int)(sizeof(METHODS) / sizeof(METHODS[0])))
static _Atomic int g_methodCalls[METHOD_COUNT];
static _Atomic int g_unknownMethods;  /* GetMethodID for a name or signature NativeCallback does not have */

static Ref *new_ref(int kind, int object)
{
    Ref *r = calloc(1, sizeof(*r));
    if (!r) { printf("FATAL: out of memory\n"); _exit(2); }
    r->kind = kind;
    r->object = object;
    atomic_store(&r->live, 1);
    pthread_mutex_lock(&g_refsLock);
    r->next = g_refs;
    g_refs = r;
    pthread_mutex_unlock(&g_refsLock);
    if (kind == REF_GLOBAL) atomic_fetch_add(&g_liveGlobals, 1);
    if (kind == REF_LOCAL) atomic_fetch_add(&g_liveLocals, 1);
    return r;
}

static void free_refs(void)
{
    while (g_refs) { Ref *n = g_refs->next; free(g_refs); g_refs = n; }
}

/* ---- forced steps --------------------------------------------------------------------------------- */

typedef void (*Step)(void);
static _Atomic(Step) g_atDeleteGlobal;   /* one-shot: taken when it runs */
static _Atomic(Step) g_atNewByteArray;
static _Atomic(Step) g_atUpcall;

#define STEP_LIMIT_SECONDS 30

typedef struct { Step step; _Atomic int done; } StepRun;

static void *step_thread(void *arg)
{
    StepRun *run = arg;
    run->step();
    atomic_store(&run->done, 1);
    return NULL;
}

/* Run the step on another thread and wait for it, with a limit. */
static void run_step(_Atomic(Step) *slot, const char *where)
{
    Step step = atomic_exchange(slot, NULL);
    if (!step) return;
    StepRun run = { .step = step };
    pthread_t t;
    if (pthread_create(&t, NULL, step_thread, &run) != 0) { printf("FATAL: no thread\n"); _exit(2); }
    for (int i = 0; i < STEP_LIMIT_SECONDS * 1000 && !atomic_load(&run.done); i++) {
        struct timespec ms = { 0, 1000000 };
        nanosleep(&ms, NULL);
    }
    if (!atomic_load(&run.done)) {
        printf("   FAIL: a step run %s finished (it was still blocked after %d s)\n", where, STEP_LIMIT_SECONDS);
        _exit(1);
    }
    pthread_join(t, NULL);
}

/* ---- the env -------------------------------------------------------------------------------------- */

/* 1 when obj is a live reference; a dead one is counted. */
static int use_ref(jobject obj)
{
    Ref *r = obj;
    if (atomic_load(&r->live)) return 1;
    atomic_fetch_add(&g_deletedUses, 1);
    return 0;
}

static jobject h_NewGlobalRef(JNIEnv *e, jobject obj)
{
    (void)e;
    if (!obj) return NULL;
    use_ref(obj);
    return new_ref(REF_GLOBAL, ((Ref *)obj)->object);
}

static void h_DeleteGlobalRef(JNIEnv *e, jobject obj)
{
    (void)e;
    Ref *r = obj;
    if (!r) return;
    if (r->kind != REF_GLOBAL || !atomic_exchange(&r->live, 0)) { atomic_fetch_add(&g_doubleDeletes, 1); return; }
    atomic_fetch_sub(&g_liveGlobals, 1);
    run_step(&g_atDeleteGlobal, "while a global reference was being deleted");
}

static jobject h_NewLocalRef(JNIEnv *e, jobject obj)
{
    (void)e;
    if (!obj) return NULL;
    if (!use_ref(obj)) return NULL;
    return new_ref(REF_LOCAL, ((Ref *)obj)->object);
}

static void h_DeleteLocalRef(JNIEnv *e, jobject obj)
{
    (void)e;
    Ref *r = obj;
    if (!r) return;
    if (r->kind != REF_LOCAL || !atomic_exchange(&r->live, 0)) { atomic_fetch_add(&g_doubleDeletes, 1); return; }
    atomic_fetch_sub(&g_liveLocals, 1);
}

static jclass h_GetObjectClass(JNIEnv *e, jobject obj)
{
    (void)e;
    use_ref(obj);
    return new_ref(REF_LOCAL, OBJ_OTHER);
}

static jmethodID h_GetMethodID(JNIEnv *e, jclass cls, const char *name, const char *sig)
{
    (void)e;
    use_ref(cls);
    for (int i = 0; i < METHOD_COUNT; i++) {
        if (strcmp(METHODS[i][0], name) == 0 && strcmp(METHODS[i][1], sig) == 0) return (jmethodID)&METHODS[i];
    }
    atomic_fetch_add(&g_unknownMethods, 1);
    return NULL;
}

static void h_CallVoidMethod(JNIEnv *e, jobject obj, jmethodID mid, ...)
{
    (void)e;
    if (!obj) { atomic_fetch_add(&g_nullUpcalls, 1); return; }
    if (!use_ref(obj)) return;
    if (!mid) { atomic_fetch_add(&g_nullMethods, 1); return; }
    atomic_fetch_add(&g_upcalls[((Ref *)obj)->object], 1);
    atomic_fetch_add(&g_methodCalls[(const char *const (*)[2])mid - METHODS], 1);
    run_step(&g_atUpcall, "while a callback was inside its upcall");
}

static jbyteArray h_NewByteArray(JNIEnv *e, jsize len)
{
    (void)e; (void)len;
    run_step(&g_atNewByteArray, "while a callback was between taking the handler and calling it");
    return new_ref(REF_LOCAL, OBJ_OTHER);
}

static void h_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize start, jsize len, const jbyte *buf)
{
    (void)e; (void)start; (void)len; (void)buf;
    use_ref(a);
}

static jstring h_NewStringUTF(JNIEnv *e, const char *s)
{
    (void)e; (void)s;
    return new_ref(REF_LOCAL, OBJ_OTHER);
}

static const struct JNINativeInterface g_table = {
    .NewGlobalRef = h_NewGlobalRef,
    .DeleteGlobalRef = h_DeleteGlobalRef,
    .NewLocalRef = h_NewLocalRef,
    .DeleteLocalRef = h_DeleteLocalRef,
    .GetObjectClass = h_GetObjectClass,
    .GetMethodID = h_GetMethodID,
    .CallVoidMethod = h_CallVoidMethod,
    .NewByteArray = h_NewByteArray,
    .SetByteArrayRegion = h_SetByteArrayRegion,
    .NewStringUTF = h_NewStringUTF,
};
static JNIEnv g_envValue = &g_table;
static JNIEnv *env = &g_envValue;

static jint h_GetEnv(JavaVM *vm, void **out, jint version)
{
    (void)vm; (void)version;
    *out = (void *)env;
    return JNI_OK;
}
static const struct JNIInvokeInterface g_invoke = { .GetEnv = h_GetEnv };
static JavaVM g_vmValue = &g_invoke;

jint JNI_OnLoad(JavaVM *vm, void *reserved);   /* jni_wallet.c */
/* jni_registry.c is not compiled here; JNI_OnLoad's registration step succeeds. */
int dgb_register_natives(JNIEnv *e) { (void)e; return 0; }

/* The bridge's log lines are not part of any verdict. */
int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    (void)prio; (void)tag; (void)fmt;
    return 0;
}

/* ---- the fixture ---------------------------------------------------------------------------------- */

static int g_failures;
static void check(int ok, const char *what)
{
    printf("   %s: %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) g_failures++;
}

static Ref *g_handlerA, *g_handlerB;       /* what Kotlin passes to setCallbackHandler */
static BRCompactFilterChain *g_chain;

static void set_handler(Ref *handler) { NativeBridge_setCallbackHandler(env, NULL, handler); }
static void set_a(void)    { set_handler(g_handlerA); }
static void set_b(void)    { set_handler(g_handlerB); }
static void set_none(void) { set_handler(NULL); }

#define UPCALL_SITES 10

/* Every callback the bridge gives BRPeerManager, through each of its upcall sites: ten upcalls when a
 * handler is registered throughout. */
static void fire_all(void)
{
    static const uint8_t bytes[8] = { 1, 0, 0, 0, 0, 0, 0, 0 };
    BRPeer peer;
    memset(&peer, 0, sizeof(peer));

    g_isRescanning = 1;
    bridge_syncStarted(NULL);                       /* onSyncProgress, the rescan marker */
    g_isRescanning = 0;
    bridge_syncStarted(NULL);                       /* onSyncProgress */
    bridge_syncStopped(NULL, ENETUNREACH);          /* onSyncFailed */
    g_initialSyncDone = 1;
    bridge_syncStopped(NULL, 0);                    /* onSyncComplete */
    bridge_txStatusUpdate(NULL);                    /* onBalanceChanged, onSyncProgress */
    bridge_saveBlocks(NULL, 1, bytes, sizeof(bytes), NULL);
    bridge_savePeers(NULL, 1, &peer, 1);
    bridge_saveFilterHeaders(NULL, g_chain);
    bridge_saveCFLedger(NULL, bytes, sizeof(bytes));
}

static void save_blocks_once(void)
{
    static const uint8_t bytes[8] = { 1, 0, 0, 0, 0, 0, 0, 0 };
    bridge_saveBlocks(NULL, 1, bytes, sizeof(bytes), NULL);
}

static int upcalls(int object) { return atomic_load(&g_upcalls[object]); }

/* What every scenario ends on, in every arm. */
static void check_invariant(void)
{
    printf("   upcalls: handler A %d, handler B %d; deleted-reference uses %d; upcalls with no handler %d\n",
           upcalls(OBJ_HANDLER_A), upcalls(OBJ_HANDLER_B), atomic_load(&g_deletedUses), atomic_load(&g_nullUpcalls));
    check(atomic_load(&g_deletedUses) == 0, "no callback used a handler reference that had been deleted");
    check(atomic_load(&g_nullUpcalls) == 0, "no callback made an upcall with no handler");
}

static void check_accounting(void)
{
    set_none();
    check(atomic_load(&g_liveGlobals) == 0, "control: with the handler cleared the bridge holds no global reference");
    check(atomic_load(&g_liveLocals) == 0, "control: no local reference outlives its callback");
    check(atomic_load(&g_doubleDeletes) == 0, "control: no reference was deleted twice");
    check(atomic_load(&g_nullMethods) == 0 && atomic_load(&g_unknownMethods) == 0,
          "control: every upcall named a method NativeCallback has");
    check(upcalls(OBJ_OTHER) == 0, "control: every upcall was made on a handler");
}

/* ---- scenarios ------------------------------------------------------------------------------------ */

static void scenario_deliver(void)
{
    static const int expected[METHOD_COUNT] = { 3, 1, 1, 1, 1, 1, 1, 1 };
    set_a();
    fire_all();
    check(upcalls(OBJ_HANDLER_A) == UPCALL_SITES, "control: all ten upcall sites reached the registered handler");
    int same = 1;
    for (int i = 0; i < METHOD_COUNT; i++) same = same && atomic_load(&g_methodCalls[i]) == expected[i];
    check(same, "control: each method was called as often as its sites");
    check(atomic_load(&g_liveGlobals) == 1, "control: one handler, one global reference");
}

static void scenario_none(void)
{
    fire_all();
    set_a();
    set_none();
    fire_all();
    check(upcalls(OBJ_HANDLER_A) == 0 && upcalls(OBJ_HANDLER_B) == 0,
          "control: with no handler, never registered or cleared, every callback is dropped");
}

static void scenario_swap_between(void)
{
    set_a();
    fire_all();
    set_b();
    check(atomic_load(&g_liveGlobals) == 1, "control: a replaced handler's global reference is released");
    fire_all();
    check(upcalls(OBJ_HANDLER_A) == UPCALL_SITES && upcalls(OBJ_HANDLER_B) == UPCALL_SITES,
          "control: callbacks after a replacement reach the new handler");
}

/* The handler is replaced while a callback is between taking it and calling it. */
static void scenario_swap_mid_callback(void)
{
    set_a();
    atomic_store(&g_atNewByteArray, set_b);
    save_blocks_once();
    check(upcalls(OBJ_HANDLER_A) + upcalls(OBJ_HANDLER_B) == 1, "control: the callback in flight was delivered once");
}

/* The handler is replaced while a callback is inside its upcall, and the next callback follows. */
static void scenario_swap_during_upcall(void)
{
    set_a();
    atomic_store(&g_atUpcall, set_b);
    save_blocks_once();
    save_blocks_once();
    check(upcalls(OBJ_HANDLER_A) == 1 && upcalls(OBJ_HANDLER_B) == 1,
          "control: the upcall in flight finished on its handler and the next callback reached the new one");
}

/* The handler is cleared while a callback is between taking it and calling it. */
static void scenario_clear_mid_callback(void)
{
    set_a();
    atomic_store(&g_atNewByteArray, set_none);
    save_blocks_once();
}

/* Every callback arrives while the old handler's reference is being deleted for a replacement. */
static void scenario_during_delete_swap(void)
{
    set_a();
    atomic_store(&g_atDeleteGlobal, fire_all);
    set_b();
}

/* The same, while it is being deleted for a clear. */
static void scenario_during_delete_clear(void)
{
    set_a();
    atomic_store(&g_atDeleteGlobal, fire_all);
    set_none();
}

/* Free-running: one thread fires every callback in a loop while another registers A, B and nothing in
 * turn. No interleaving is forced; the verdict is the same invariant, plus what the sanitizer the arm
 * was built with reports. */
#define STRESS_ROUNDS 3000
static _Atomic int g_stressDone;

static void *stress_callbacks(void *arg)
{
    (void)arg;
    while (!atomic_load(&g_stressDone)) fire_all();
    return NULL;
}

static void scenario_stress(void)
{
    pthread_t t;
    set_a();
    if (pthread_create(&t, NULL, stress_callbacks, NULL) != 0) { printf("FATAL: no thread\n"); _exit(2); }
    for (int i = 0; i < STRESS_ROUNDS; i++) {
        set_b();
        sched_yield();
        set_none();
        set_a();
        sched_yield();
    }
    atomic_store(&g_stressDone, 1);
    pthread_join(t, NULL);
    check(upcalls(OBJ_HANDLER_A) + upcalls(OBJ_HANDLER_B) > 0, "control: callbacks were delivered while the handler changed");
}

static const struct { const char *name; void (*run)(void); } SCENARIOS[] = {
    { "deliver", scenario_deliver },
    { "none", scenario_none },
    { "swap-between", scenario_swap_between },
    { "swap-mid-callback", scenario_swap_mid_callback },
    { "swap-during-upcall", scenario_swap_during_upcall },
    { "clear-mid-callback", scenario_clear_mid_callback },
    { "during-delete-swap", scenario_during_delete_swap },
    { "during-delete-clear", scenario_during_delete_clear },
    { "stress", scenario_stress },
};

int main(int argc, char **argv)
{
    static const char mnemonic[] =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { printf("usage: %s <scenario>\n", argv[0]); return 2; }

    void (*run)(void) = NULL;
    for (size_t i = 0; i < sizeof(SCENARIOS) / sizeof(SCENARIOS[0]); i++) {
        if (strcmp(argv[1], SCENARIOS[i].name) == 0) run = SCENARIOS[i].run;
    }
    if (!run) { printf("unknown scenario %s\n", argv[1]); return 2; }

    if (JNI_OnLoad(&g_vmValue, NULL) != JNI_VERSION_1_6) { printf("FATAL: JNI_OnLoad\n"); return 2; }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, mnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    g_wallet = BRWalletNew(NULL, 0, mpk);
    g_peerManager = g_wallet ? BPPeerManagerMainNetNew(g_wallet, 0, NULL, 0, NULL, 0) : NULL;
    g_chain = BRCompactFilterChainNew(0, 0, UINT256_ZERO);
    if (!g_wallet || !g_peerManager || !g_chain) { printf("FATAL: fixture\n"); return 2; }

    g_handlerA = new_ref(REF_PARAM, OBJ_HANDLER_A);
    g_handlerB = new_ref(REF_PARAM, OBJ_HANDLER_B);

    printf("SCENARIO %s\n", argv[1]);
    run();
    check_invariant();
    check_accounting();

    BRCompactFilterChainFree(g_chain);
    BRPeerManagerFree(g_peerManager);
    BRWalletFree(g_wallet);
    free_refs();

    printf(g_failures ? "\n%d FAILED\n" : "\nALL PASS\n", g_failures);
    return g_failures ? 1 : 0;
}
