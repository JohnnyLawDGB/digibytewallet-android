# ============================================================================
# R8 rules — DigiByte wallet
#
# Obfuscation is ON deliberately: the APK is distributed publicly, so it is the
# artifact an attacker actually works from. Keeping the source private while
# shipping readable class names protects the wrong door.
#
# Every keep below exists because something resolves a name at RUNTIME and R8
# cannot see it. Each is annotated with what breaks without it — a keep whose
# reason isn't written down becomes a keep nobody dares remove.
# ============================================================================

# ---- Crash reports stay decodable ------------------------------------------
# Without these, every future stack trace from a user is unreadable. CI must
# also archive mapping.txt per release — the mapping is the only way to decode
# a trace AFTER the fact, and it cannot be regenerated from a shipped APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations drive Room, Hilt and serialization; signatures drive generics.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---- JNI: the C core resolves these by name --------------------------------
# Native functions are exported as Java_io_digibyte_core_bridge_NativeBridge_<m>.
# Rename the class or a method and the link fails at runtime with
# UnsatisfiedLinkError — on the very first wallet operation.
-keep class io.digibyte.core.bridge.NativeBridge { *; }

# jni_peer.c takes GetObjectClass(handler) then GetMethodID by NAME and
# SIGNATURE (onSyncProgress, onSaveBlocks, onSaveCfLedger, …). The class itself
# comes from the object so it may be renamed; the METHOD names may not.
-keep class io.digibyte.core.bridge.NativeCallback { *; }
-keep class * implements io.digibyte.core.bridge.NativeCallback { *; }

# Belt and braces for anything else annotated as native.
-keepclasseswithmembernames class * {
    native <methods>;
}

# NOTE: bridge/core.c and PeerManager.c also FindClass on
# io/digibyte/presenter/entities/* and io/digibyte/wallet/BR*Manager. Every one
# of those classes is ABSENT from this app — they are dead bread-wallet paths
# whose lookups already fail at runtime. Deliberately NOT kept: keeping absent
# classes would be a rule that silently protects nothing.

# ---- Enums -----------------------------------------------------------------
# values()/valueOf()/$VALUES are the reflective surface every enum needs; keeping
# them costs nothing and breaks nothing.
#
# This block used to end with `public *;`, which kept the public members — and so
# the CONSTANT NAMES — of every enum in the app, ours and our dependencies'. Its
# comment justified that with two examples, and one of them was not even an enum:
# `asset_source` is a TEXT column (UtxoEntity.kt) fed by `object AssetSource`
# (AssetSource.kt), a holder of String constants. A rule kept alive by a reason
# that had stopped being true.
#
# Enum constant names are descriptive by design — SCANNING, PEER_UNRESPONSIVE —
# so keeping all of them hands a reader of the APK a map of the state machines.
# Narrowed in the v4.0.58 security cycle (finding 2) to the two enums that
# genuinely need their names, each verified by a source sweep rather than assumed.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
}

# Round-trips through SharedPreferences: written as `.name` (WalletViewModel:417),
# read back as `valueOf(prefs.getString(...))` (:407). A renamed constant does not
# crash — it silently stops matching stored data, which is the worst failure mode
# available to a wallet.
-keep class io.digibyte.ui.wallet.WalletViewModel$DisplayCurrency { *; }

# `stage.name` is embedded in the bug-report URL (SettingsScreen.kt:169). Renaming
# it would not break anything; it would just turn every user-submitted report into
# "stage=a", which is worse than the leak this narrowing is closing.
-keep class io.digibyte.core.model.SyncStage { *; }

# ---- SQLCipher: JNI-backed, loaded reflectively -----------------------------
# If these are stripped the encrypted database fails to open, which in this app
# triggers wipeStaleData() — so the symptom is not "crash", it is "the wallet
# appears to have lost its data".
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**
-dontwarn net.sqlcipher.**

# ---- Room ------------------------------------------------------------------
# Entity field names map to column names; generated DAO impls are resolved by
# name at runtime.
-keep class io.digibyte.core.db.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin / coroutines ---------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }

# ---- Tor (opt-in, default OFF) ---------------------------------------------
# Loaded only when the user enables it, so R8 sees no reference and would strip
# it — turning a toggle into a crash for exactly the privacy-conscious users
# most likely to switch it on.
-keep class io.matthewnelson.kmp.tor.** { *; }
-dontwarn io.matthewnelson.kmp.tor.**

# ---- OkHttp / okio ---------------------------------------------------------
# These ship consumer rules; the dontwarns cover optional compile-only deps.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- kmp-process (a kmp-tor dependency) -------------------------------------
# It references java.lang.management.ManagementFactory / RuntimeMXBean to read a
# process id. Those are JVM-only and absent on Android, and the code path is
# never taken here — but R8 treats a missing class as fatal, so the build stops.
# Silencing the reference is correct; keeping the classes is impossible.
-dontwarn java.lang.management.**
-dontwarn io.matthewnelson.kmp.process.**
-keep class io.matthewnelson.kmp.process.** { *; }
