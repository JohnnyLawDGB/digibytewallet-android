/* Host stand-in for <jni.h>, so the bridge's own jni_derive.c compiles in this host KAT. Only the types and
 * the function-table entries jni_derive.c uses are declared; the field names and signatures are the JNI
 * specification's, so a call written for Android compiles here unchanged. The table is filled in by
 * derivation_profiles_kat_main.c. A bridge change that uses another entry stops compiling here -- add the
 * entry, with the specification's signature. (identity_sign_bytes_kat/host_jni lacks the int and long
 * array entries jni_derive.c needs, hence this copy.) */
#ifndef DERIVATION_PROFILES_KAT_HOST_JNI_H
#define DERIVATION_PROFILES_KAT_HOST_JNI_H

#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>   /* jni_derive.c's snprintf/sprintf: on Android the NDK headers bring it in */

typedef uint8_t  jboolean;
typedef int8_t   jbyte;
typedef uint16_t jchar;
typedef int16_t  jshort;
typedef int32_t  jint;
typedef int64_t  jlong;
typedef float    jfloat;
typedef double   jdouble;
typedef jint     jsize;

typedef void *jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray  jobjectArray;
typedef jarray  jbyteArray;
typedef jarray  jintArray;
typedef jarray  jlongArray;
typedef jobject jthrowable;

#define JNI_FALSE        0
#define JNI_TRUE         1
#define JNI_OK           0
#define JNI_ERR          (-1)
#define JNI_COMMIT       1
#define JNI_ABORT        2
#define JNI_VERSION_1_6  0x00010006

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

struct JNINativeInterface;
typedef const struct JNINativeInterface *JNIEnv;

struct JNINativeInterface {
    jclass       (*FindClass)(JNIEnv *, const char *);
    void         (*DeleteLocalRef)(JNIEnv *, jobject);
    jstring      (*NewStringUTF)(JNIEnv *, const char *);
    const char * (*GetStringUTFChars)(JNIEnv *, jstring, jboolean *);
    void         (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);
    jsize        (*GetArrayLength)(JNIEnv *, jarray);
    jobjectArray (*NewObjectArray)(JNIEnv *, jsize, jclass, jobject);
    jobject      (*GetObjectArrayElement)(JNIEnv *, jobjectArray, jsize);
    void         (*SetObjectArrayElement)(JNIEnv *, jobjectArray, jsize, jobject);
    jbyteArray   (*NewByteArray)(JNIEnv *, jsize);
    jbyte *      (*GetByteArrayElements)(JNIEnv *, jbyteArray, jboolean *);
    void         (*ReleaseByteArrayElements)(JNIEnv *, jbyteArray, jbyte *, jint);
    void         (*GetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, jbyte *);
    void         (*SetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, const jbyte *);
    jint *       (*GetIntArrayElements)(JNIEnv *, jintArray, jboolean *);
    void         (*ReleaseIntArrayElements)(JNIEnv *, jintArray, jint *, jint);
    void         (*GetIntArrayRegion)(JNIEnv *, jintArray, jsize, jsize, jint *);
    jlong *      (*GetLongArrayElements)(JNIEnv *, jlongArray, jboolean *);
    void         (*ReleaseLongArrayElements)(JNIEnv *, jlongArray, jlong *, jint);
};

#endif
