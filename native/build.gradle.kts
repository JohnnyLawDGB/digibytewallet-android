plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.digibyte.native_core"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cFlags(
                    "-DANDROID_TOOLCHAIN=clang",
                    "-fstack-protector-strong",
                    "-fvisibility=hidden"
                )
                targets("core-lib")
                arguments("-DANDROID_TOOLCHAIN=clang")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }

    flavorDimensions += "network"
    productFlavors {
        create("mainnet") {
            dimension = "network"
            externalNativeBuild {
                cmake { cFlags("-DBITCOIN_TESTNET=0") }
            }
        }
        create("digiTestnet") {
            dimension = "network"
            externalNativeBuild {
                cmake { cFlags("-DBITCOIN_TESTNET=1") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
