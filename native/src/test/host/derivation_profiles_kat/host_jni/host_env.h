/* The host JNIEnv for the bridge's jni_derive.c: the function table host_jni/jni.h declares, filled in.
 * Moved out of derivation_profiles_kat_main.c unchanged so that bip39_24w_vectors_kat drives the same bridge
 * through the same env (one copy). Every object lives in an arena; call arena_free() after each comparison.
 * Include once, from the suite's one translation unit. */
#ifndef DERIVATION_PROFILES_KAT_HOST_ENV_H
#define DERIVATION_PROFILES_KAT_HOST_ENV_H

#include <stdlib.h>
#include <string.h>
#include "jni.h"

/* ---- the host env: every object lives in an arena freed after each comparison ---------------------------- */

enum { H_BYTES, H_INTS, H_OBJS, H_STR, H_CLASS };
typedef struct { int kind; jsize len; void *data; } HObj;

static HObj **g_arena = NULL;
static size_t g_arenaLen = 0, g_arenaCap = 0;

static HObj *hnew(int kind, jsize len, size_t elem)
{
    HObj *o = calloc(1, sizeof(*o));

    o->kind = kind;
    o->len = len;
    o->data = calloc((size_t)(len > 0 ? len : 1), elem);
    if (g_arenaLen == g_arenaCap) {
        g_arenaCap = g_arenaCap ? 2 * g_arenaCap : 1024;
        g_arena = realloc(g_arena, g_arenaCap * sizeof(*g_arena));
    }
    g_arena[g_arenaLen++] = o;
    return o;
}

static void arena_free(void)
{
    for (size_t i = 0; i < g_arenaLen; i++) { free(g_arena[i]->data); free(g_arena[i]); }
    g_arenaLen = 0;
}

static HObj *hbytes(const void *b, size_t n) { HObj *o = hnew(H_BYTES, (jsize)n, 1); if (n) memcpy(o->data, b, n); return o; }
static HObj *hints(const uint32_t *v, size_t n)
{
    HObj *o = hnew(H_INTS, (jsize)n, sizeof(jint));
    for (size_t i = 0; i < n; i++) ((jint *)o->data)[i] = (jint)v[i];
    return o;
}
static HObj *hstr(const char *s) { HObj *o = hnew(H_STR, (jsize)strlen(s), 1); free(o->data); o->data = strdup(s); return o; }

static jclass e_FindClass(JNIEnv *e, const char *n) { (void)e; (void)n; return hnew(H_CLASS, 0, 1); }
static void e_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e; (void)o; }
static jstring e_NewStringUTF(JNIEnv *e, const char *s) { (void)e; return s ? hstr(s) : NULL; }
static const char *e_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)s)->data; }
static void e_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }
static jsize e_GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((HObj *)a)->len; }
static jobjectArray e_NewObjectArray(JNIEnv *e, jsize n, jclass c, jobject init)
{
    (void)e; (void)c;
    HObj *o = hnew(H_OBJS, n, sizeof(jobject));
    for (jsize i = 0; i < n; i++) ((jobject *)o->data)[i] = init;
    return o;
}
static jobject e_GetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i) { (void)e; return ((jobject *)((HObj *)a)->data)[i]; }
static void e_SetObjectArrayElement(JNIEnv *e, jobjectArray a, jsize i, jobject v) { (void)e; ((jobject *)((HObj *)a)->data)[i] = v; }
static jbyteArray e_NewByteArray(JNIEnv *e, jsize n) { (void)e; return hnew(H_BYTES, n, 1); }
// The direct pointer, never a copy: the bridge must not write through it, and caller_seed_untouched checks it.
static jbyte *e_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *p, jint m) { (void)e; (void)a; (void)p; (void)m; }
static void e_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, jbyte *b) { (void)e; memcpy(b, (jbyte *)((HObj *)a)->data + s, (size_t)n); }
static void e_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, const jbyte *b) { (void)e; memcpy((jbyte *)((HObj *)a)->data + s, b, (size_t)n); }
static jint *e_GetIntArrayElements(JNIEnv *e, jintArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseIntArrayElements(JNIEnv *e, jintArray a, jint *p, jint m) { (void)e; (void)a; (void)p; (void)m; }
static void e_GetIntArrayRegion(JNIEnv *e, jintArray a, jsize s, jsize n, jint *b) { (void)e; memcpy(b, (jint *)((HObj *)a)->data + s, (size_t)n * sizeof(jint)); }
static jlong *e_GetLongArrayElements(JNIEnv *e, jlongArray a, jboolean *c) { (void)e; if (c) *c = JNI_FALSE; return ((HObj *)a)->data; }
static void e_ReleaseLongArrayElements(JNIEnv *e, jlongArray a, jlong *p, jint m) { (void)e; (void)a; (void)p; (void)m; }

static const struct JNINativeInterface g_table = {
    e_FindClass, e_DeleteLocalRef, e_NewStringUTF, e_GetStringUTFChars, e_ReleaseStringUTFChars, e_GetArrayLength,
    e_NewObjectArray, e_GetObjectArrayElement, e_SetObjectArrayElement, e_NewByteArray, e_GetByteArrayElements,
    e_ReleaseByteArrayElements, e_GetByteArrayRegion, e_SetByteArrayRegion, e_GetIntArrayElements,
    e_ReleaseIntArrayElements, e_GetIntArrayRegion, e_GetLongArrayElements, e_ReleaseLongArrayElements,
};
static JNIEnv g_envp = &g_table;
static JNIEnv *const ENV = &g_envp;

#endif
