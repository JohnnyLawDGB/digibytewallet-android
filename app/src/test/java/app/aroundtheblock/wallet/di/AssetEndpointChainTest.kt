package app.aroundtheblock.wallet.di

import app.aroundtheblock.wallet.core.asset.network.MultiEndpointAssetClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The asset metadata chain asks only providers that we, or a known operator, run.
 *
 * The chain takes the first answer any endpoint gives ([MultiEndpointAssetClient]), so an endpoint
 * on a host nobody we know controls would be trusted for asset names, metadata and images whenever
 * the ones before it cannot be reached. The chain is: digistamp (a known operator, its host pinned in
 * DigistampPins), then DigiScope (ours, pinned in DigiScopePins).
 */
class AssetEndpointChainTest {

    private val operated = listOf("assets.digistamp.co", "api.digiscope.me")

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Test fun `the asset metadata chain asks only the operated providers, in order`() {
        val chain = AppModule.provideAssetNetworkClient(OkHttpClient()) as MultiEndpointAssetClient
        val endpoints = field(chain, "endpoints") as List<*>

        val hosts = endpoints.map { (field(it!!, "baseUrl") as String).toHttpUrl().host }

        assertEquals(operated, hosts)
    }
}
