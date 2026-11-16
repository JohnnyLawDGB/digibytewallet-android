package app.aroundtheblock.wallet.core.security

import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.InMemoryPrefs
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wipeTestContext
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each wallet has its own Hub session: its own plain store and its own encrypted store. */
class HubTokenStoreScopeTest {
    private val roots = mutableListOf<java.io.File>()
    private val prefs = InMemoryPrefsFactory()
    private val secureMap = LinkedHashMap<String, InMemoryPrefs>()
    private val secureNames = mutableListOf<String>()
    private val factory: (String) -> SharedPreferences = { name ->
        secureNames += name
        secureMap.getOrPut(name) { InMemoryPrefs() }
    }
    private val b = WalletScope("0123456789abcdef", false)
    private val a = WalletScope.legacy(false)

    private fun context() = wipeTestContext(
        prefs, Files.createTempDirectory("hub-scope").toFile().also { roots += it },
    )

    @After fun cleanup() = roots.forEach { it.deleteRecursively() }

    @Test fun `each wallet opens its own Hub stores`() {
        val ctx = context()
        HubTokenStore(ctx, a, factory).also { it.save("x"); it.load() }
        assertEquals(listOf("dgb_hub_session"), secureNames.distinct())
        assertEquals(setOf("dgb_digiscope"), prefs.names())

        secureNames.clear()
        HubTokenStore(ctx, b, factory).also { it.save("y"); it.load() }
        assertEquals(listOf("dgb_hub_session_0123456789abcdef"), secureNames.distinct())
        assertEquals(setOf("dgb_digiscope", "dgb_digiscope_0123456789abcdef"), prefs.names())
    }

    @Test fun `wallet B never reads wallet A's Hub token`() {
        val ctx = context()
        val storeA = HubTokenStore(ctx, a, factory)
        val storeB = HubTokenStore(ctx, b, factory)
        storeA.save("tok-a")
        assertNull(storeB.load())
        assertEquals("tok-a", storeA.load())
    }
}
