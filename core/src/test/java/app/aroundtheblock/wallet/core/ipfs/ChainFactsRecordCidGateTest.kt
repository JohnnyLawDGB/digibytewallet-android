package app.aroundtheblock.wallet.core.ipfs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chain-facts write records the issuance's metadata CID over whatever row is there (4.1.2 row 29, R6).
 *
 * The provider may answer only for an asset whose chain-facts row shows no CID. A rules lookup can seed the
 * row first (insertChainFacts is INSERT-OR-IGNORE), which made the parent walk's insert a no-op; its update
 * then wrote supply and decimals only, so an issuance WITH a CID read as having none and a provider's
 * document could take its place. The update now carries the CID, and the walk passes the issuance's.
 */
class ChainFactsRecordCidGateTest {

    private val root = File("..").canonicalFile
    private fun src(p: String) = File(root, p).readText()

    @Test fun `updateChainFacts writes the metadata CID`() {
        val dao = src("core/src/main/java/app/aroundtheblock/wallet/core/db/dao/AssetMetadataDao.kt")
        val query = Regex("""@Query\("UPDATE asset_metadata SET totalSupply[^"]*"\)\s*suspend fun updateChainFacts""").find(dao)?.value
        assertTrue("updateChainFacts was not found", query != null)
        assertTrue("updateChainFacts does not write metadataCid: $query", query!!.contains("metadataCid = :metadataCid"))
    }

    @Test fun `the parent walk passes the issuance's CID to updateChainFacts`() {
        val am = src("core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt")
        val call = Regex("""metadataDao\.updateChainFacts\(([^)]*)\)""").find(am)?.groupValues?.get(1)
        assertTrue("the walk does not call updateChainFacts", call != null)
        assertTrue("the walk does not pass the issuance's CID: $call", call!!.contains("metadataCid = resolved.metadataCid"))
    }
}
