/*
 * The only place the native library names a Kotlin class.
 *
 * JNI_OnLoad binds NativeBridge's `external fun`s to their C bodies with RegisterNatives
 * (jni_registry.c), so the C function names carry no package. What remains package-bound is
 * the class the table is registered against and the one object type in its signatures — both
 * are here. Moving the Kotlin package means editing these defaults, or overriding them at
 * build time without touching C:
 *
 *   -DDGB_JNI_NATIVEBRIDGE_CLASS='"com/example/bridge/NativeBridge"'
 *
 * A mismatch is loud, not latent: JNI_OnLoad fails, System.loadLibrary throws, and the app
 * stops at its first native call rather than at some later, rarer one.
 */
#ifndef DGB_JNI_CLASS_PATHS_H
#define DGB_JNI_CLASS_PATHS_H

#ifndef DGB_JNI_NATIVEBRIDGE_CLASS
#define DGB_JNI_NATIVEBRIDGE_CLASS "app/aroundtheblock/wallet/core/bridge/NativeBridge"
#endif

#ifndef DGB_JNI_NATIVECALLBACK_CLASS
#define DGB_JNI_NATIVECALLBACK_CLASS "app/aroundtheblock/wallet/core/bridge/NativeCallback"
#endif

/* JNI type descriptor of NativeCallback, for setCallbackHandler's signature. */
#define DGB_JNI_NATIVECALLBACK_SIG "L" DGB_JNI_NATIVECALLBACK_CLASS ";"

/* Debug builds only (jni_test.c): the instrumented PeerTest in :native's androidTest. */
#ifndef DGB_JNI_PEERTEST_CLASS
#define DGB_JNI_PEERTEST_CLASS "app/aroundtheblock/wallet/native_core/PeerTest"
#endif

#endif /* DGB_JNI_CLASS_PATHS_H */
