package app.aroundtheblock.wallet.core.wallets

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.wipeTestContext
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ActiveWalletTest {
    private val roots = mutableListOf<java.io.File>()

    private fun context(testnet: Boolean, prefs: InMemoryPrefsFactory = InMemoryPrefsFactory()): android.content.Context {
        if (testnet) prefs.prefsFor("dgb_settings").seed("dgb_network_testnet", true)
        val root = Files.createTempDirectory("active-wallet").toFile().also { roots += it }
        return wipeTestContext(prefs, root)
    }

    @Before @After
    fun reset() = ActiveWallet.resetForTest()

    @After
    fun cleanup() = roots.forEach { it.deleteRecursively() }

    @Test
    fun `with no registry the scope is legacy on the selected network`() {
        assertEquals(WalletScope.legacy(true), ActiveWallet.scope(context(testnet = true)))
    }

    @Test
    fun `the scope is resolved once per application context`() {
        val prefs = InMemoryPrefsFactory()
        // Records must exist for a wallet to resolve as active.
        prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "x")
        val ctx = context(false, prefs)
        val registry = ActiveWallet.registry(ctx)
        val b = registry.reserve(false, WalletOrigin.CREATED, 1L)!!
        prefs.prefsFor("dgb_wallet_seed_${b.id}").seed("encrypted_seed_v2", "x")
        assertEquals(true, registry.complete(b.id, makeActive = false))
        assertEquals(WalletScope.legacy(false), ActiveWallet.scope(ctx))
        assertEquals(true, registry.setActive(false, b.id))
        assertEquals(WalletScope.legacy(false), ActiveWallet.scope(ctx))
        ActiveWallet.resetForTest()
        assertEquals(WalletScope(b.id, false), ActiveWallet.scope(ctx))
    }

    @Test
    fun `a different application context resolves afresh`() {
        val first = context(testnet = false)
        assertEquals(WalletScope.legacy(false), ActiveWallet.scope(first))
        assertEquals(WalletScope.legacy(true), ActiveWallet.scope(context(testnet = true)))
        assertEquals(WalletScope.legacy(true), ActiveWallet.scope(context(testnet = true)))
    }
}
