plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// AddressSanitizer for the C core. OPT-IN VIA A GRADLE PROPERTY ONLY:
//
//     ./gradlew :app:assembleMainnetDebug -PasanNative=true
//
// Deliberately NOT an edit to the default cFlags. On 2026-08-04 two experiment flags were left
// in that list with a "REVERT BEFORE COMMITTING" comment, got built into a device APK, and cost
// most of a day: one of them armed a back-pressure gate whose own header documents that it
// "pauses FOREVER" on a deep restore, and the resulting stall was mistaken for a wallet defect.
// A property defaults to off, cannot be forgotten in the working tree, and shows up in the
// command line that produced the build.
//
// ASan on Android also needs, on the app module: the runtime .so packaged next to ours, a
// wrap.sh that LD_PRELOADs it, and a debuggable build. See app/build.gradle.kts.
val asanNative = (project.findProperty("asanNative") as String?)?.toBoolean() ?: false

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
                if (asanNative) {
                    // -fno-omit-frame-pointer so the report carries a usable stack; -O1 keeps it
                    // readable without making the scan unusably slow on a Note 8.
                    //
                    // -fsanitize-recover=address is the one that matters for THIS run's purpose.
                    // Without it ASan aborts on the FIRST error, so a session reports exactly one
                    // defect and dies — which is the same one-at-a-time treadmill this build
                    // exists to escape. With it (plus halt_on_error=0 in wrap.sh) the app keeps
                    // running and every distinct site is reported in a single pass.
                    cFlags(
                        "-fsanitize=address",
                        "-fsanitize-recover=address",
                        "-fno-omit-frame-pointer",
                        "-O1",
                        "-g"
                    )
                }
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
