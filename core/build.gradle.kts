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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Artifact-only `@aar` notation (also implies non-transitive) can't be
    // expressed as a catalog module coordinate — only the version comes from
    // the catalog. See the note in gradle/libs.versions.toml.
    implementation("net.zetetic:sqlcipher-android:${libs.versions.sqlcipher.get()}@aar")
    implementation(libs.androidx.sqlite.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // OkHttp (for price API)
    implementation(libs.okhttp)

    // Argon2 for PIN hashing
    implementation(libs.argon2)

    // Tor via kmp-tor (NO-EXEC mode — Tor loaded in-process via dlopen, not
    // exec'd as a child process). Required because we package native libs
    // uncompressed (jniLibs.useLegacyPackaging = false) for 16 KB page-size
    // compatibility: exec mode needs libtor.so EXTRACTED to nativeLibraryDir,
    // which uncompressed packaging doesn't do, so exec mode fails to find it.
    // No-exec also avoids the SELinux exec-from-data-dir denials newer Android
    // (15/16) enforces — more robust on modern devices.
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.resource.noexec)

    // EncryptedSharedPreferences for PIN storage
    implementation(libs.androidx.security.crypto)

    // BiometricPrompt
    implementation(libs.androidx.biometric)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // org.json real implementation for unit tests (Android stubs are empty)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
