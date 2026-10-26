/* Host stand-in for <jni.h>, so the bridge's own .c files compile in a host KAT. Only the types and the
 * function-table entries the compiled bridge files use are declared; the field names and signatures are
 * the JNI specification's, so a call written for Android compiles here unchanged. The table is filled in
 * by the KAT (saved_tx_array_kat_main.c). A bridge file that starts using another entry stops compiling
 * here -- add the entry, with the specification's signature. */
#ifndef SAVED_TX_ARRAY_KAT_HOST_JNI_H
#define SAVED_TX_ARRAY_KAT_HOST_JNI_H

#include <stdint.h>
#include <stdarg.h>

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

struct _jmethodID;
typedef struct _jmethodID *jmethodID;
struct _jfieldID;
typedef struct _jfieldID *jfieldID;

#define JNI_FALSE        0
#define JNI_TRUE         1
#define JNI_OK           0
#define JNI_ERR          (-1)
#define JNI_EDETACHED    (-2)
#define JNI_COMMIT       1
#define JNI_ABORT        2
#define JNI_VERSION_1_6  0x00010006

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

struct JNINativeInterface;
typedef const struct JNINativeInterface *JNIEnv;
struct JNIInvokeInterface;
typedef const struct JNIInvokeInterface *JavaVM;

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
    jintArray    (*NewIntArray)(JNIEnv *, jsize);
    jlongArray   (*NewLongArray)(JNIEnv *, jsize);
    jbyte *      (*GetByteArrayElements)(JNIEnv *, jbyteArray, jboolean *);
    void         (*ReleaseByteArrayElements)(JNIEnv *, jbyteArray, jbyte *, jint);
    void         (*GetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, jbyte *);
    void         (*SetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, const jbyte *);
    void         (*SetIntArrayRegion)(JNIEnv *, jintArray, jsize, jsize, const jint *);
    void         (*SetLongArrayRegion)(JNIEnv *, jlongArray, jsize, jsize, const jlong *);
};

struct JNIInvokeInterface {
    jint (*GetEnv)(JavaVM *, void **, jint);
    jint (*AttachCurrentThread)(JavaVM *, JNIEnv **, void *);
};

#endif
