// Plugin versions come from gradle/libs.versions.toml so they stay locked to
// the matching runtime artifacts — the Hilt plugin must never drift from
// hilt-android/hilt-android-compiler, and the Compose compiler plugin must
// never drift from the Kotlin version (KSP is versioned on its own since 2.3,
// see gradle/libs.versions.toml). Subprojects apply these by plain
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

// Annotation processors read the @Metadata of compiled Kotlin classes with
// org.jetbrains.kotlin:kotlin-metadata-jvm, which only reads metadata up to one
// minor version past its own. Dagger/Hilt 2.58 asks for 2.2.20, which rejects
// Kotlin 2.4 classes ("Provided Metadata instance has version 2.4.0, while
// maximum supported version is 2.2.0") in :app:hiltJavaCompile*. Dagger stopped
// shading it in 2.57 precisely so that a build can raise it, so every
// configuration that asks for it gets the Kotlin compiler's own version. It is
// a processor-classpath library only: no app runtime classpath contains it.
val kotlinVersion = libs.versions.kotlin.get()
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion(kotlinVersion)
                because("read Kotlin $kotlinVersion class metadata in annotation processors (Dagger/Hilt)")
            }
        }
    }
}
