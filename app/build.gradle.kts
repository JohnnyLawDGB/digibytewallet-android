import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
}

// AddressSanitizer packaging. OPT-IN ONLY, via the same property the :native module reads:
//
//     ./gradlew :app:assembleMainnetDebug -PasanNative=true
//
// When off, nothing under app/asan/ is packaged at all — no runtime .so, no wrap.sh — so a normal
// build is byte-for-byte unaffected and there is no way to ship this by forgetting to revert an
// edit. (That failure mode cost a day on 2026-08-04.)
val asanNative = (project.findProperty("asanNative") as String?)?.toBoolean() ?: false

// ── Release signing (app.aroundtheblock.wallet) ─────────────────────────────────────────────────
// The release key is the app's identity on Play AND on the digiscope.me download, so it lives only
// with its owner: never in this repo, never generated here (scripts/create-signing-key.sh, run once,
// offline). A build finds it in, first to last:
//
//   ATB_SIGNING_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD   (CI, or a one-off shell)
//   keystore.properties at the repo root: storeFile, storePassword, keyAlias, keyPassword (gitignored)
//
// keyAlias defaults to aroundtheblock-wallet and keyPassword to storePassword (PKCS12 has one).
// With neither present, every non-release build works as before, and a release build STOPS with
// the message below. It never falls back to the debug key and never writes an unsigned APK.
// docs/SIGNING.md has the whole procedure.
data class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val releaseSigning: ReleaseSigning? = run {
    val props = Properties()
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) propsFile.inputStream().use { props.load(it) }
    fun pick(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotEmpty() } ?: props.getProperty(prop)?.takeIf { it.isNotEmpty() }
    val store = pick("ATB_SIGNING_STORE_FILE", "storeFile") ?: return@run null
    val storePassword = pick("ATB_SIGNING_STORE_PASSWORD", "storePassword") ?: return@run null
    ReleaseSigning(
        storeFile = rootProject.file(store),
        storePassword = storePassword,
        keyAlias = pick("ATB_SIGNING_KEY_ALIAS", "keyAlias") ?: "aroundtheblock-wallet",
        keyPassword = pick("ATB_SIGNING_KEY_PASSWORD", "keyPassword") ?: storePassword,
    )
}

// Checked when the task graph is known, so only a build that would SIGN a release pays for it —
// tests, lint, debug and minifiedDebug never see it.
gradle.taskGraph.whenReady {
    val signsRelease = allTasks.any { t ->
        t.project == project && t.name.contains("Release") && !t.name.contains("Debug") &&
            (t.name.startsWith("package") || t.name.startsWith("bundle") || t.name.startsWith("sign") ||
                t.name.startsWith("validateSigning"))
    }
    if (!signsRelease) return@whenReady
    val problem = when {
        releaseSigning == null ->
            "no release key configured"
        !releaseSigning.storeFile.isFile ->
            "the keystore ${releaseSigning.storeFile} does not exist"
        else -> null
    }
    if (problem != null) throw GradleException(
        "Release signing: $problem.\n" +
            "Set ATB_SIGNING_STORE_FILE and ATB_SIGNING_STORE_PASSWORD, or create keystore.properties at the\n" +
            "repo root (storeFile=, storePassword=). A release is never signed with the debug key.\n" +
            "See docs/SIGNING.md."
    )
}

android {
    namespace = "app.aroundtheblock.wallet"
    compileSdk = 36

    defaultConfig {
        // The new app's permanent identity on Play and every device (rebrand 2026-10). It is a
        // different app from io.digibyte: it installs beside it, never over it.
        applicationId = "app.aroundtheblock.wallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 40087 // x-release-please-version-code
        versionName = "4.0.87" // x-release-please-version
    }

    // Match native module flavors
    flavorDimensions += listOf("network", "distribution")
    productFlavors {
        create("mainnet") { dimension = "network" }
        create("digiTestnet") {
            dimension = "network"
            applicationIdSuffix = ".testnet"
        }

        // How the APK reaches people. Play forbids an app it distributes from updating itself
        // outside Play, so the GitHub self-updater exists only in src/sideload; src/play has an
        // empty twin. Same applicationId and key for both: a sideloaded install and a Play
        // install are the same app, and either can update the other.
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "RELEASES_REPO", "\"\"")
        }
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            // This app's sideload builds, published apart from the source repository: io.digibyte
            // installs read that repository's releases with no tag filter.
            buildConfigField("String", "RELEASES_REPO", "\"JohnnyLawDGB/aroundtheblock-wallet-releases\"")
        }
    }

    buildFeatures {
        compose = true
        // Needed for BuildConfig.DEBUG / BuildConfig.FLAVOR — used to dev-gate
        // the Settings > Advanced network toggle (mainnet release must not
        // render it). AGP 8+ defaults this off.
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // Left empty without a key; the taskGraph check above stops a release build first.
            releaseSigning?.let {
                storeFile = it.storeFile
                storePassword = it.storePassword
                keyAlias = it.keyAlias
                keyPassword = it.keyPassword
            }
        }
    }

    buildTypes {
        // Debug-signed builds get their own ID so they install beside a release build instead of
        // failing on its signature. minifiedDebug inherits this through initWith(debug).
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            // R8 ON, obfuscation included. The APK is distributed publicly, so it is the
            // artifact an attacker works from — shipping readable class names while the source
            // is private protects the wrong door.
            //
            // Proven on `minifiedDebug` (identical R8 config, debug-signed) before flipping:
            // Note 8, real wallet, JNI + Keystore + SQLCipher + Room + sync + asset resolution
            // + the Market WebView all intact, mapping confirming AssetManager -> e5.M while
            // NativeBridge stayed put for JNI linkage.
            //
            // CI MUST keep archiving mapping.txt with each release. It is the only way to
            // decode a user's stack trace afterwards and cannot be regenerated from the APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }

        // Release's R8 configuration, debug-signed so it can actually be installed and used.
        // The point is to make minified output TESTABLE: same shrinking, same obfuscation, same
        // keep rules as release will have, on a build that goes on an emulator or a test device.
        create("minifiedDebug") {
            initWith(getByName("debug"))
            // MUST be false. AGP silently disables all optimization AND obfuscation for
            // debuggable builds, so a debuggable "minified" build tests nothing and would
            // have reported R8 as safe without ever running it.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }

    packaging {
        // NORMAL BUILDS (asanNative off): store native libs UNCOMPRESSED and page-aligned so the
        // dynamic linker can mmap them directly from the APK. Required for 16 KB page-size
        // devices (Android 15+/newer flagships): with legacy (compressed)
        // packaging the libs aren't 16 KB-aligned in the APK and the app trips
        // the "isn't 16 KB compatible" check — on a real 16 KB device the native
        // SPV engine fails to load and the wallet hangs at "Connecting". Paired
        // with the -Wl,-z,max-page-size=16384 ELF alignment in native/CMakeLists.
        // ASan builds MUST use legacy (extracted) packaging. wrap.sh is exec'd by the dynamic
        // linker from lib/<abi>/, which requires it to exist as a real file — with
        // extractNativeLibs=false nothing is unpacked and the install itself fails with
        // INSTALL_FAILED_INVALID_APK "Failed to extract native libraries, res=-2".
        // The 16 KB-page alignment this trades away only matters on 16 KB devices (S25 Ultra),
        // and this build is a debug-only diagnostic that never ships.
        jniLibs.useLegacyPackaging = asanNative
    }

    if (asanNative) {
        // The ASan runtime is COPIED FROM THE NDK at build time rather than committed. It is
        // ~2.6 MB of prebuilt binary that must match the NDK doing the instrumenting, so vendoring
        // it would add a blob to git that silently skews the moment ndkVersion changes.
        sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("asanRuntime"))
        // wrap.sh must land at lib/<abi>/wrap.sh in the APK; the resources source set is the
        // documented way to put a non-.so file there. The dynamic linker runs it INSTEAD of
        // starting the app directly, and it LD_PRELOADs the ASan runtime.
        sourceSets["main"].resources.srcDir("asan/resources")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":native"))
    implementation(project(":game"))
    // BiometricPrompt error codes: the spend gate decides which biometric outcomes fall
    // through to the in-app PIN dialog, and the constants live in androidx.biometric.
    implementation(libs.androidx.biometric)

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material.icons.extended)

    // Activity + Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ZXing for QR code generation + scanning
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Core KTX
    implementation(libs.androidx.core.ktx)

    // Chrome Custom Tabs — for opening external URLs (marketplace, block
    // explorers, release downloads) in a Chrome-overlay tab with a clear
    // close affordance that returns to the wallet, instead of dropping the
    // user into a separate browser task with no way back.
    implementation(libs.androidx.browser)

    // Material 3 for XML theme (Theme.Material3.DayNight.NoActionBar)
    implementation(libs.android.material)

    // OkHttp (needed so Hilt/KSP can resolve OkHttpClient in NetworkModule + AppModule)
    implementation(libs.okhttp)

    // Coil — Compose-native async image loading with a custom IPFS fetcher
    // that routes ipfs:// URIs through our hash-verifying IpfsClient.
    implementation(libs.coil.compose)

    // Room runtime (needed so app module can reference WalletDatabase from :core)
    implementation(libs.androidx.room.runtime)

    // WorkManager + Hilt integration (for SyncWorker background sync job)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Unit test deps (:app)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ── AddressSanitizer runtime staging (opt-in: -PasanNative=true) ─────────────────────────────
//
// Copies libclang_rt.asan-<abi>-android.so out of the NDK into a build dir that the asan source
// set picks up as jniLibs. Located by GLOB, not a hardcoded path: the runtime sits under
// .../lib/clang/<major>/lib/linux/, and that <major> moves with every NDK bump — a pinned path
// would break silently and produce an APK that installs, runs, and reports nothing.
if (asanNative) {
    val stageAsanRuntime = tasks.register<Copy>("stageAsanRuntime") {
        val ndkDir = android.ndkDirectory
        val found = fileTree(ndkDir) {
            include("toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/libclang_rt.asan-aarch64-android.so")
        }.files
        doFirst {
            require(found.isNotEmpty()) {
                "ASan runtime not found under $ndkDir. Expected " +
                    "toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/" +
                    "libclang_rt.asan-aarch64-android.so — check ndkVersion."
            }
        }
        from(found)
        into(layout.buildDirectory.dir("asanRuntime/arm64-v8a"))
    }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
        .configureEach { dependsOn(stageAsanRuntime) }
    tasks.matching { it.name.startsWith("package") }
        .configureEach { dependsOn(stageAsanRuntime) }
}
