package app.aroundtheblock.wallet.core.digiscope

import android.util.Log
import app.aroundtheblock.wallet.core.filePrefsContext
import app.aroundtheblock.wallet.core.wallets.WalletScope
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The revoke a wipe and a removal run, as the app builds it: from the shared HTTP client and the
 * tokens on the device, without the Hub client. It reads a wallet's token where it is and sends it
 * once to the server's logout endpoint over the pinned client's settings; it writes nothing, and
 * it builds no HTTP client when there is no token to send.
 */
class StoredHubSessionRevokeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    @Test fun `a stored token is sent once to the logout endpoint and nothing is written`() {
        val root = tmp.newFolder()
        val (ctx, prefs) = filePrefsContext(root)
        prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).seed(HubTokenStore.KEY_JWT, "stored-before-the-wipe")
        val before = prefs.files()
        val answered = CountDownLatch(1)
        val server = ScriptedServer { ScriptedServer.Answer(200, "{}").also { answered.countDown() } }
        var clientsBuilt = 0

        StoredHubSessionRevoke(ctx) { clientsBuilt++; server.client() }.revoke(WalletScope.legacy(false))

        assertTrue("the revoke never told the server", answered.await(5, TimeUnit.SECONDS))
        val seen = server.seen.single()
        assertEquals("POST", seen.method)
        assertEquals("https://api.digiscope.me/api/auth/logout", seen.url.toString())
        assertEquals("Bearer stored-before-the-wipe", seen.authorization)
        assertFalse("a revoke must not follow a redirect with the token", seen.followsRedirects)
        assertFalse(seen.followsSslRedirects)
        assertEquals(1, clientsBuilt)
        assertEquals("files the revoke created or removed", before, prefs.files())
        assertEquals(
            "the token is read where it is, not moved",
            "stored-before-the-wipe",
            prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).getString(HubTokenStore.KEY_JWT, null),
        )
    }

    @Test fun `a wallet with no stored session sends nothing and builds no client`() {
        val (ctx, prefs) = filePrefsContext(tmp.newFolder())
        val server = ScriptedServer { ScriptedServer.Answer(200, "{}") }
        var clientsBuilt = 0

        StoredHubSessionRevoke(ctx) { clientsBuilt++; server.client() }.revoke(WalletScope.legacy(false))

        Thread.sleep(200)
        assertTrue(server.seen.isEmpty())
        assertEquals(0, clientsBuilt)
        assertEquals(emptyList<String>(), prefs.files())
    }
}
