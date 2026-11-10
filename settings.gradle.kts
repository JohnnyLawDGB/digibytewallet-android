pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "digibytewallet-android"
include(":app", ":core", ":native", ":game")

// ---------------------------------------------------------------------------
// Build-tool dependency floors (Dependabot alerts on settings.gradle.kts).
//
// Every library below reaches the build only through Gradle plugins and the
// tool configurations they create, never the app: the root buildscript
// classpath (AGP 8.7.3, KGP, KSP, Hilt), AGP's Unified Test Platform
// configurations (`_internal-unified-test-platform-*`), the KSP / Hilt
// annotation-processor classpaths, and KGP's `kotlinBouncyCastleConfiguration`
// and `swiftExportClasspathResolvable`. The exposure is the machine that runs
// the build, which for releases is the owner's (RELEASING.md).
//
// A floor only RAISES a version that is below it, and only on the same major
// line. A floor never adds a module directly, but a raised version can bring
// new transitive modules of its own (commons-compress 1.26 brings commons-lang3,
// which therefore has a floor too). A configuration that contains none of these
// modules resolves exactly as before: the app's release runtime classpaths
// included (verified by an all-configuration resolution diff and a release R8
// dex/mapping comparison when this was added). Version ranges and dynamic
// versions are left alone.
//
// Not covered here: org.jetbrains.kotlin:kotlin-gradle-plugin (GHSA-r937-
// wjx7-w2jp, fixed in Kotlin 2.4.20) needs a Kotlin + KSP bump, not a pin.
// org.json:json is a direct test dependency, bumped in libs.versions.toml.
// ---------------------------------------------------------------------------
private data class BuildToolFloor(val line: String, val version: String, val why: String)

private val buildToolFloors: List<Pair<(ModuleVersionSelector) -> Boolean, BuildToolFloor>> = listOf(
    // Netty modules must stay aligned, so the whole 4.1 group moves together.
    { m: ModuleVersionSelector -> m.group == "io.netty" && m.name != "netty-tcnative" && !m.name.startsWith("netty-tcnative-") } to
        BuildToolFloor("4.1.", "4.1.137.Final", "GHSA-c4c3-7fpv-j4q5, GHSA-fccg-mwvh-qqg4, GHSA-8c42-7qj2-3j46 and the earlier netty 4.1 advisories"),
    // BouncyCastle modules are released together; keep bcprov/bcpkix/bcutil/bcpg aligned.
    { m: ModuleVersionSelector -> m.group == "org.bouncycastle" && m.name.endsWith("-jdk18on") } to
        BuildToolFloor("1.", "1.85", "GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-574f-3g2m-x479, GHSA-cj8j-37rh-8475, GHSA-wg6q-6289-32hp"),
    // Guava keeps its flavour: an -android request is raised to the -android build, everything else to -jre.
    { m: ModuleVersionSelector -> m.group == "com.google.guava" && m.name == "guava" && m.version?.endsWith("-android") == true } to
        BuildToolFloor("", "33.7.2-android", "GHSA-xxph-c9ww-hj94"),
    { m: ModuleVersionSelector -> m.group == "com.google.guava" && m.name == "guava" && m.version?.endsWith("-android") != true } to
        BuildToolFloor("", "33.7.2-jre", "GHSA-xxph-c9ww-hj94"),
    { m: ModuleVersionSelector -> m.group == "com.google.protobuf" && (m.name == "protobuf-java" || m.name == "protobuf-java-util") } to
        BuildToolFloor("3.", "3.25.5", "GHSA-735f-pc8j-v9w8"),
    { m: ModuleVersionSelector -> m.group == "commons-io" && m.name == "commons-io" } to
        BuildToolFloor("2.", "2.14.0", "GHSA-78wr-2p64-hpwj"),
    { m: ModuleVersionSelector -> m.group == "org.apache.commons" && m.name == "commons-compress" } to
        BuildToolFloor("1.", "1.26.0", "GHSA-4265-ccf5-phj5, GHSA-4g9r-vxhx-9pgx"),
    // Brought in by commons-compress 1.26.
    { m: ModuleVersionSelector -> m.group == "org.apache.commons" && m.name == "commons-lang3" } to
        BuildToolFloor("3.", "3.18.0", "GHSA-j288-q9x7-2f5v"),
    { m: ModuleVersionSelector -> m.group == "org.bitbucket.b_c" && m.name == "jose4j" } to
        BuildToolFloor("0.9.", "0.9.6", "GHSA-3677-xxcr-wjqv"),
    { m: ModuleVersionSelector -> m.group == "org.jdom" && m.name == "jdom2" } to
        BuildToolFloor("2.", "2.0.6.1", "GHSA-2363-cqg2-863c"),
    // opentelemetry-api and -context are versioned together.
    { m: ModuleVersionSelector -> m.group == "io.opentelemetry" && (m.name == "opentelemetry-api" || m.name == "opentelemetry-context") } to
        BuildToolFloor("1.", "1.67.0", "GHSA-ccrj-gw8r-854m, GHSA-rcgg-9c38-7xpx"),
)

/** Numeric, segment-wise version compare; qualifiers such as "Final" or "jre" are ignored. */
private fun versionBelow(candidate: String, floor: String): Boolean {
    fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
    val a = parts(candidate)
    val b = parts(floor)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

private fun applyBuildToolFloors(configurations: ConfigurationContainer) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedVersion = requested.version ?: return@eachDependency
            // Ranges ([1,2), 1.+) and dynamic versions are not floors' business.
            if (requestedVersion.any { it in "[](),+" } || requestedVersion.startsWith("latest.")) return@eachDependency
            for ((matches, floor) in buildToolFloors) {
                if (matches(requested) &&
                    requestedVersion.startsWith(floor.line) &&
                    versionBelow(requestedVersion, floor.version)
                ) {
                    useVersion(floor.version)
                    because("build-tool floor: ${floor.why}")
                    break
                }
            }
        }
    }
}

gradle.beforeProject {
    applyBuildToolFloors(buildscript.configurations)
    applyBuildToolFloors(configurations)
}
