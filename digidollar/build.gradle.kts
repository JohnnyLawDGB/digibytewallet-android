plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom("$rootDir/config/detekt.yml")
    buildUponDefaultConfig = true
}

// Pure-protocol DigiDollar module (ADR-0001): deterministic functions only.
// No I/O, no Android, no coroutines — bytes and integers in, bytes and
// integers out. Consensus-critical: changes here must keep the fixture
// byte-parity tests green.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Fixture JSON parsing in tests only.
    testImplementation("org.json:json:20240303")
    // JVM secp256k1 point math for the EcOps test double — production uses
    // the native signer via NativeBridge (ADR-0001); main source stays EC-free.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
