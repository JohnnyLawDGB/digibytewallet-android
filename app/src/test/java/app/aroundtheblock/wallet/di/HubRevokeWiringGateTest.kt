package app.aroundtheblock.wallet.di

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (WIPE-WRITES-NOTHING): the Hub revoke that a wipe and a removal run is built without
 * the Hub client.
 *
 * `DigiScopeClient` loads its token when it is built, and that load moves a token from before the
 * encrypted store into it, which creates that store's file. The revoke runs before the wipe erases
 * anything, and a wipe can be the first thing in a process to ask for the Hub (the launch
 * backstop): a revoke that built the client there would make a file the wipe then takes for the
 * device's own and leaves. What the revoke does is covered in `core` (`StoredHubSessionRevokeTest`,
 * `WipeWritesNothingTest`, whose model is this wiring); this pins that the app wires that revoke,
 * and that the two providers that take it are not handed the client at all.
 *
 * The identity step still logs the client out, and builds it if nothing has: that runs after the
 * token is erased, when the client's load finds nothing to move.
 */
class HubRevokeWiringGateTest {

    private val code = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/di/AppModule.kt").readText()).code

    /** From `fun name(` to the end of that declaration's parameter list or, with [body], its expression. */
    private fun declaration(name: String, body: Boolean): String {
        val from = code.indexOf("fun $name(")
        assertTrue("AppModule has no $name", from >= 0)
        var depth = 0
        var at = code.indexOf('(', from)
        do {
            when (code[at]) { '(' -> depth++; ')' -> depth-- }
            at++
        } while (depth > 0)
        return if (!body) code.substring(from, at) else code.substring(from, code.indexOf("\n\n", at).takeIf { it >= 0 } ?: code.length)
    }

    @Test fun `the revoke is built from the stored tokens and never from the Hub client`() {
        val revoke = declaration("hubSessionRevoke", body = true)
        assertTrue("the revoke is not StoredHubSessionRevoke:\n$revoke", "StoredHubSessionRevoke(" in revoke)
        assertFalse("the revoke names the Hub client:\n$revoke", "DigiScopeClient" in revoke)
        assertFalse("the revoke asks a client for the stored session:\n$revoke", "revokeStoredSession" in revoke)
    }

    @Test fun `the wipe and the removal are handed that revoke and not the Hub client`() {
        val handed = Regex("""\b(hubRevoke|revokeHub)\s*=\s*([^\n]+)""").findAll(code).map { it.groupValues[2].trim().trimEnd(',') }.toList()
        assertEquals("every revoke the app hands over", listOf("hubSessionRevoke(context, httpClient)", "hubSessionRevoke(context, httpClient)"), handed)
        for (provider in listOf("provideWalletManager", "provideWalletRemoval")) {
            val parameters = declaration(provider, body = false)
            assertFalse("$provider takes the Hub client:\n$parameters", "DigiScopeClient" in parameters)
        }
    }
}
