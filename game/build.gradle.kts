plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.aroundtheblock.wallet.game"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    flavorDimensions += "network"
    productFlavors {
        create("mainnet") { dimension = "network" }
        create("digiTestnet") { dimension = "network" }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.3 made the `android { kotlinOptions { } }` properties a compile error; this is the
// compilerOptions form of the same setting (must match compileOptions.targetCompatibility).
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.material3)
}
