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

android {
    namespace = "io.digibyte"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.digibyte"
        minSdk = 26
        targetSdk = 35
        versionCode = 40039 // x-release-please-version-code
        versionName = "4.0.39" // x-release-please-version
    }

    // Match native module flavors
    flavorDimensions += "network"
    productFlavors {
        create("mainnet") { dimension = "network" }
        create("digiTestnet") {
            dimension = "network"
            applicationIdSuffix = ".testnet"
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
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../dgb-wallet-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "dgb-wallet-release"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            // R8 minification disabled — auto-generated ProGuard rules conflict
            // with Compose + Hilt. TODO: add proper keep rules and re-enable.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
