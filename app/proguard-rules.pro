# R8 shrinking only — no obfuscation for auditability
-dontobfuscate

# Keep JNI bridge methods
-keep class io.digibyte.core.bridge.NativeBridge { *; }
-keep class io.digibyte.core.bridge.NativeCallback { *; }

# Keep Room entities
-keep class io.digibyte.core.db.entity.** { *; }
