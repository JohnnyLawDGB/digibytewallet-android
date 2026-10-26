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
        val outside = constructions.filterNot { (path, _) -> path.endsWith("di/AppModule.kt") }
        assertTrue("the reconcile client is constructed outside the provider: $outside", outside.isEmpty())
        assertEquals("the provider must construct the reconcile client exactly once", 1, constructions.size)
        assertEquals("the provider does not pass the injected client", listOf("context", "client"), constructions.single().second)
    }
}
