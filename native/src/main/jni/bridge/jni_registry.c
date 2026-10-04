/*
 * jni_registry.c — binds NativeBridge's natives with RegisterNatives at load time.
 *
 * The table (jni_registry_gen.h) is generated from the Kotlin declarations and the C bodies by
 * scripts/gen-jni-registry.py, which also proves their types agree. This file applies it, and
 * is strict in both directions, so that a mismatch fails System.loadLibrary instead of throwing
 * UnsatisfiedLinkError at the first call of whichever method drifted:
 *
 *   - a table entry whose method the loaded class DECLARES as native, but which RegisterNatives
 *     refuses (wrong descriptor, e.g. a renamed NativeCallback), fails the load;
 *   - a native method the class declares that the table does not cover fails the load;
 *   - a table entry the class does not declare at all is skipped. That is legitimate: the
 *     :native androidTest ships its own 48-method NativeBridge under the same name, and R8 may
 *     drop natives nothing calls.
 *
 * Methods are registered one at a time because RegisterNatives is all-or-nothing per call: one
 * absent name would otherwise void the whole table.
 */
#include <jni.h>
#include <string.h>

#include "jni_bridge.h"
#include "jni_class_paths.h"
#include "jni_registry_gen.h"

#define JAVA_MODIFIER_NATIVE 0x100

static const JNINativeMethod kNativeBridgeMethods[] = { DGB_JNI_NATIVEBRIDGE_METHODS };

/* Names of the methods `cls` itself declares with the `native` modifier, via reflection.
 * Returns the count, or -1 if reflection failed. Caller frees each name and the array. */
static int declared_native_names(JNIEnv *env, jclass cls, char ***out) {
    *out = NULL;
    jclass classCls = (*env)->FindClass(env, "java/lang/Class");
    jclass methodCls = (*env)->FindClass(env, "java/lang/reflect/Method");
    if (!classCls || !methodCls) return -1;
    jmethodID getDeclaredMethods = (*env)->GetMethodID(env, classCls, "getDeclaredMethods",
                                                       "()[Ljava/lang/reflect/Method;");
    jmethodID getName = (*env)->GetMethodID(env, methodCls, "getName", "()Ljava/lang/String;");
    jmethodID getModifiers = (*env)->GetMethodID(env, methodCls, "getModifiers", "()I");
    if (!getDeclaredMethods || !getName || !getModifiers) return -1;

    jobjectArray methods = (jobjectArray) (*env)->CallObjectMethod(env, cls, getDeclaredMethods);
    if ((*env)->ExceptionCheck(env) || !methods) return -1;

    jsize n = (*env)->GetArrayLength(env, methods);
    char **names = calloc((size_t) (n > 0 ? n : 1), sizeof(char *));
    if (!names) return -1;
    int count = 0;
    for (jsize i = 0; i < n; i++) {
        jobject m = (*env)->GetObjectArrayElement(env, methods, i);
        jint mods = (*env)->CallIntMethod(env, m, getModifiers);
        if (!(*env)->ExceptionCheck(env) && (mods & JAVA_MODIFIER_NATIVE)) {
            jstring jname = (jstring) (*env)->CallObjectMethod(env, m, getName);
            const char *utf = jname ? (*env)->GetStringUTFChars(env, jname, NULL) : NULL;
            if (utf) {
                names[count++] = strdup(utf);
                (*env)->ReleaseStringUTFChars(env, jname, utf);
            }
            if (jname) (*env)->DeleteLocalRef(env, jname);
        }
        (*env)->DeleteLocalRef(env, m);
        if ((*env)->ExceptionCheck(env)) {
            for (int k = 0; k < count; k++) free(names[k]);
            free(names);
            return -1;
        }
    }
    (*env)->DeleteLocalRef(env, methods);
    *out = names;
    return count;
}

static int contains(char **names, int count, const char *name) {
    for (int i = 0; i < count; i++) {
        if (names[i] && strcmp(names[i], name) == 0) return 1;
    }
    return 0;
}

#ifdef DGB_JNI_WITH_PEER_TEST
int dgb_register_peer_test(JNIEnv *env);  /* jni_test.c, Debug builds only */
#endif

int dgb_register_natives(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, DGB_JNI_NATIVEBRIDGE_CLASS);
    if (!cls) {
        (*env)->ExceptionClear(env);
        LOGE("JNI registry: class %s not found — jni_class_paths.h does not match the Kotlin package",
             DGB_JNI_NATIVEBRIDGE_CLASS);
        return -1;
    }

    char **declared = NULL;
    int ndeclared = declared_native_names(env, cls, &declared);
    if (ndeclared < 0) {
        (*env)->ExceptionClear(env);
        LOGE("JNI registry: could not list %s's native methods", DGB_JNI_NATIVEBRIDGE_CLASS);
        return -1;
    }

    const int ntable = (int) (sizeof(kNativeBridgeMethods) / sizeof(kNativeBridgeMethods[0]));
    int registered = 0, skipped = 0, failed = 0;
    for (int i = 0; i < ntable; i++) {
        const JNINativeMethod *m = &kNativeBridgeMethods[i];
        if (!contains(declared, ndeclared, m->name)) {
            skipped++;
            continue;
        }
        if ((*env)->RegisterNatives(env, cls, m, 1) != JNI_OK) {
            (*env)->ExceptionClear(env);
            LOGE("JNI registry: %s.%s%s refused — the Kotlin declaration and the C table disagree",
                 DGB_JNI_NATIVEBRIDGE_CLASS, m->name, m->signature);
            failed++;
        } else {
            registered++;
        }
    }

    /* The other direction: a native the class declares that nothing in the table binds. */
    for (int i = 0; i < ndeclared; i++) {
        int bound = 0;
        for (int j = 0; j < ntable && !bound; j++) {
            bound = strcmp(declared[i], kNativeBridgeMethods[j].name) == 0;
        }
        if (!bound) {
            LOGE("JNI registry: %s declares native %s, which has no C body in the table",
                 DGB_JNI_NATIVEBRIDGE_CLASS, declared[i]);
            failed++;
        }
        free(declared[i]);
    }
    free(declared);
    (*env)->DeleteLocalRef(env, cls);

    LOGI("JNI registry: %d natives bound on %s (%d table entries not declared by this build)",
         registered, DGB_JNI_NATIVEBRIDGE_CLASS, skipped);
    if (failed) return -1;

#ifdef DGB_JNI_WITH_PEER_TEST
    dgb_register_peer_test(env);  /* optional: its class exists only in the instrumented test */
#endif
    return 0;
}
