plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "io.digibyte"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.digibyte"
        minSdk = 26
        targetSdk = 35
        versionCode = 40002 // x-release-please-version-code
        versionName = "4.0.2" // x-release-please-version
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
        // Store native libs UNCOMPRESSED and page-aligned so the dynamic linker
        // can mmap them directly from the APK. Required for 16 KB page-size
        // devices (Android 15+/newer flagships): with legacy (compressed)
        // packaging the libs aren't 16 KB-aligned in the APK and the app trips
        // the "isn't 16 KB compatible" check — on a real 16 KB device the native
        // SPV engine fails to load and the wallet hangs at "Connecting". Paired
        // with the -Wl,-z,max-page-size=16384 ELF alignment in native/CMakeLists.
        jniLibs.useLegacyPackaging = false
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ZXing for QR code generation + scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Core KTX
    implementation("androidx.core:core-ktx:1.15.0")

    // Chrome Custom Tabs — for opening external URLs (marketplace, block
    // explorers, release downloads) in a Chrome-overlay tab with a clear
    // close affordance that returns to the wallet, instead of dropping the
    // user into a separate browser task with no way back.
    implementation("androidx.browser:browser:1.8.0")

    // Material 3 for XML theme (Theme.Material3.DayNight.NoActionBar)
    implementation("com.google.android.material:material:1.12.0")

    // OkHttp (needed so Hilt/KSP can resolve OkHttpClient in NetworkModule + AppModule)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil — Compose-native async image loading with a custom IPFS fetcher
    // that routes ipfs:// URIs through our hash-verifying IpfsClient.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Room runtime (needed so app module can reference WalletDatabase from :core)
    implementation("androidx.room:room-runtime:2.7.1")

    // WorkManager + Hilt integration (for SyncWorker background sync job)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Unit test deps (:app)
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
}
