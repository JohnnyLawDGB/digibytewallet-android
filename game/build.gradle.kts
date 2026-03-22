plugins {
    id("com.android.library")
}
android {
    namespace = "io.digibyte.game"
    compileSdk = 35
    defaultConfig { minSdk = 26 }

    // Must match app module's flavor dimension
    flavorDimensions += "network"
    productFlavors {
        create("mainnet") { dimension = "network" }
        create("digiTestnet") { dimension = "network" }
    }
}
