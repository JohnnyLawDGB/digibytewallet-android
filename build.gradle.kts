// Plugin versions come from gradle/libs.versions.toml so they stay locked to
// the matching runtime artifacts — the Hilt plugin must never drift from
// hilt-android/hilt-android-compiler, and KSP + the Compose compiler plugin
// must never drift from the Kotlin version. Subprojects apply these by plain
// id(), inheriting the version resolved here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom("$rootDir/config/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
}

// Apply detekt config to all subprojects that use it
subprojects {
    afterEvaluate {
        extensions.findByType<io.gitlab.arturbosch.detekt.extensions.DetektExtension>()?.apply {
            config.setFrom("$rootDir/config/detekt.yml")
            buildUponDefaultConfig = true
        }
    }
}
