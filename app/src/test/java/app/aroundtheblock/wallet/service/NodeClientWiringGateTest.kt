package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the reconcile client ([app.aroundtheblock.wallet.core.reconcile.DgbNodeClient]) is built in one
 * place only — the Hilt provider — and from the injected shared client, so every reconcile call
 * (the "Scan for missing funds" screen, the post-upgrade reconcile, the pending-confirmation
 * reconcile) carries the Tor routing and the pins that client carries.
 *
 * A gate because the call sites are a Compose screen, an Android service and an object driven from
 * an Activity, none of which this JVM can run; what the provider builds is covered by
 * `app.aroundtheblock.wallet.di.SharedClientPinsTest`.
 */
class NodeClientWiringGateTest {

    private val roots = listOf(File("src/main/java"), File("../core/src/main/java"))

    private fun sources(): List<File> = roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun `the source roots are where this test thinks they are`() {
        assertTrue(File("src/main/java/app/aroundtheblock/wallet/di/AppModule.kt").isFile)
        assertTrue(File("../core/src/main/java/app/aroundtheblock/wallet/core/reconcile/DgbNodeClient.kt").isFile)
    }

    @Test fun `the reconcile client is constructed only by the provider, from the injected client`() {
        val constructions = sources().flatMap { file ->
            val gate = KotlinSourceGate.of(file.readText())
            // Matches the plain and the package-qualified spelling alike; the class's own
            // declaration is not a construction.
            gate.calls("DgbNodeClient")
                .filterNot { gate.code.substring(0, it.range.first).trimEnd().endsWith("class") }
                .map { file.path to it.arguments }
        }
        // The only other constructions: the two pinned factories inside the class, digiScopeOnly and
        // ownNodeOnly, each from the client its caller passes (Task 15).
        val (factory, rest) = constructions.partition { (path, _) -> path.endsWith("core/reconcile/DgbNodeClient.kt") }
        assertEquals(
            "the class builds itself other than through its two pinned factories: $factory",
            listOf(listOf("context", "client", "DEFAULT_BASE_URL"), listOf("context", "client", "pinned")),
            factory.map { it.second },
        )
        val outside = rest.filterNot { (path, _) -> path.endsWith("di/AppModule.kt") }
        assertTrue("the reconcile client is constructed outside the provider: $outside", outside.isEmpty())
        assertEquals("the provider must construct the reconcile client exactly once", 1, rest.size)
        assertEquals("the provider does not pass the injected client", listOf("context", "client"), rest.single().second)
    }

    /**
     * The pinned clients are built from the injected shared client, as the provider's is, and only
     * by the quick start. Matched by name whatever the receiver (`DgbNodeClient.`, `Companion.`, none).
     */
    @Test fun `the pinned clients are built from the injected client`() {
        val callers = sources().flatMap { file ->
            val gate = KotlinSourceGate.of(file.readText())
            listOf("digiScopeOnly", "ownNodeOnly").flatMap { name -> gate.calls(name).map { Triple(file.name, name, it.arguments) } }
        }
        assertEquals(
            "scanner is blind or another caller was added: $callers",
            setOf(
                Triple("QuickStartScans.kt", "digiScopeOnly", listOf("context", "client")),
                Triple("QuickStartScans.kt", "ownNodeOnly", listOf("context", "client", "endpoint")),
            ),
            callers.toSet(),
        )
        assertEquals("a pinned client is built twice: $callers", 2, callers.size)
        val scans = File("src/main/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartScans.kt").readText()
        assertTrue("QuickStartScans does not take the injected client", Regex("""@Inject\s+constructor\([^)]*private val client: OkHttpClient""").containsMatchIn(scans))
    }
}
