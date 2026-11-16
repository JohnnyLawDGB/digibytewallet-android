package app.aroundtheblock.wallet.core.reconcile

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wipeTestContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A lookup the user addressed to DigiScope goes to DigiScope, whatever own node is configured, and
 * a lookup addressed to the own node never falls back to DigiScope unnoticed: [DgbNodeClient.customEndpoint]
 * says whether one is set at all.
 */
class DgbNodeClientDigiScopeOnlyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prefs = InMemoryPrefsFactory()
    private val ctx by lazy { wipeTestContext(prefs, tmp.root) }
    private val client = OkHttpClient()

    private fun setEndpoint(value: String?) {
        val p = prefs.prefsFor(WalletStores.prefsName("dgb_reconcile", WalletScope.legacy(false)))
        p.edit().apply { if (value == null) remove("custom_rpc_endpoint") else putString("custom_rpc_endpoint", value) }.commit()
    }

    @Test fun `digiscope only ignores the custom endpoint`() {
        setEndpoint("https://node.example")
        assertEquals(DgbNodeClient.DEFAULT_BASE_URL, DgbNodeClient.digiScopeOnly(ctx, client).endpoint())
        assertEquals("https://node.example", DgbNodeClient(ctx, client).customEndpoint())
        assertEquals("https://node.example", DgbNodeClient(ctx, client).endpoint())
    }

    @Test fun `no custom endpoint reads as none`() {
        setEndpoint(null)
        assertNull(DgbNodeClient(ctx, client).customEndpoint())
        setEndpoint("   ")
        assertNull(DgbNodeClient(ctx, client).customEndpoint())
        setEndpoint(" https://node.example/ ")
        assertEquals("https://node.example", DgbNodeClient(ctx, client).customEndpoint())
        assertEquals(DgbNodeClient.DEFAULT_BASE_URL, DgbNodeClient.digiScopeOnly(ctx, client).endpoint())
    }
}
