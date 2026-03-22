plugins {
    id("com.android.library")
}

android {
    namespace = "io.digibyte.native_core"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26

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
}
