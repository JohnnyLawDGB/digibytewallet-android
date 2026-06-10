plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom("$rootDir/config/detekt.yml")
    buildUponDefaultConfig = true
}

android {
    namespace = "io.digibyte.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Must match native module's flavor dimension
    flavorDimensions += "network"
    productFlavors {
        create("mainnet") { dimension = "network" }
        create("digiTestnet") { dimension = "network" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        // Expose Room schema JSON files to MigrationTestHelper via test assets
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":native"))

    // Room + SQLCipher
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp (for price API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Argon2 for PIN hashing
    implementation("org.signal:argon2:13.1")

    // Tor via kmp-tor (NO-EXEC mode — Tor loaded in-process via dlopen, not
    // exec'd as a child process). Required because we package native libs
    // uncompressed (jniLibs.useLegacyPackaging = false) for 16 KB page-size
    // compatibility: exec mode needs libtor.so EXTRACTED to nativeLibraryDir,
    // which uncompressed packaging doesn't do, so exec mode fails to find it.
    // No-exec also avoids the SELinux exec-from-data-dir denials newer Android
    // (15/16) enforces — more robust on modern devices.
    val kmpTorRuntime = "2.4.0"
    val kmpTorResource = "408.16.4"
    implementation("io.matthewnelson.kmp-tor:runtime:$kmpTorRuntime")
    implementation("io.matthewnelson.kmp-tor:resource-noexec-tor:$kmpTorResource")

    // EncryptedSharedPreferences for PIN storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // BiometricPrompt
    implementation("androidx.biometric:biometric:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    // org.json real implementation for unit tests (Android stubs are empty)
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
