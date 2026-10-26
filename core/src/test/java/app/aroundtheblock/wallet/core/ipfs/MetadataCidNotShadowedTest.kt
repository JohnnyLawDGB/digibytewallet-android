package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import app.aroundtheblock.wallet.core.asset.network.AssetDataResponse
import app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 4.1.2 row 29: when the issuance names a metadata CID, only the document at that CID is the asset's
 * metadata. With IPFS unreachable, a provider's inline document used to be stored in its place --
 * never checked against the issuance, cached with a name, and from then on returned forever over the CID
 * -- and the provider's issuer was recorded as chain-proven.
 */
class MetadataCidNotShadowedTest {

    private val assetId = "La8JvCqENcANWZsFdjW5nfZki3EsnEzuycLJod"
    private val dao = mockk<AssetMetadataDao>(relaxed = true)
    private val ipfs = mockk<IpfsClient>()
    private val provider = mockk<AssetNetworkClient>()

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        coEvery { dao.getMetadata(any()) } returns null
        coEvery { ipfs.fetchVerified(any()) } returns null     // IPFS unreachable
        coEvery { provider.getAssetData(any()) } returns AssetDataResponse(
            assetId = assetId, cid = "bafyprovider", issuer = "DProviderSaysSo", count = 10, decimals = 0,
            ipfs = mapOf("data" to mapOf("assetName" to "Not The Issuer's Name")), rules = null,
        )
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun service() = AssetMetadataService(ipfsClient = ipfs, assetMetadataDao = dao, assetNetworkClient = provider)

    @Test fun `with a CID on the issuance, a provider's document is never stored in its place`() = runBlocking {
        assertNull(service().getMetadata(assetId, metadataCid = "bafyissuance"))
        coVerify(exactly = 0) { dao.updateIpfsFacts(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { provider.getAssetData(any()) }
    }

    /** The chain-facts row the parent walk writes for an issuance with no metadata hash. */
    private fun chainFacts(cid: String?) = AssetMetadataEntity(assetId = assetId, totalSupply = 10, decimals = 0, metadataCid = cid)

    @Test fun `with the chain showing no CID the provider is used, but its issuer is never recorded as proven`() = runBlocking {
        coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = null)
        service().getMetadata(assetId, metadataCid = null)
        coVerify { dao.updateIpfsFacts(assetId, any(), any(), any(), issuerAddress = null, any(), any(), any()) }
    }

    /** Review R6: the retry asked before the parent walk had written the issuance's facts, and the provider's
     *  document was stored with a name -- shadowing the issuance's CID once the walk came back with it. */
    @Test fun `before the chain says whether the issuance carries a CID, the provider is not asked`() = runBlocking {
        assertNull(service().getMetadata(assetId, metadataCid = null))
        coVerify(exactly = 0) { provider.getAssetData(any()) }
        coVerify(exactly = 0) { dao.updateIpfsFacts(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a caller without the CID gets the issuance's CID from the chain facts, never the provider`() = runBlocking {
        coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = "bafyissuance")
        service().getMetadata(assetId, metadataCid = null)
        coVerify { ipfs.fetchVerified("bafyissuance") }
        coVerify(exactly = 0) { provider.getAssetData(any()) }
    }
}
