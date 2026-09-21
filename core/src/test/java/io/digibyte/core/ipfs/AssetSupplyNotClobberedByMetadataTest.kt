package io.digibyte.core.ipfs

import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.AssetMetadataEntity
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Total Supply must survive the arrival of an IPFS metadata document.
 *
 * ## What went wrong
 *
 * Reported 2026-09-11 from a live device, twice on the same screen eight minutes apart with no
 * send in between: `Total Supply 5` with no name, then — once the metadata fetch landed and
 * supplied the name — `Total Supply Unknown`. The fetch that finally makes the asset human-
 * readable is what destroys its supply.
 *
 * `AssetDetailScreen` renders "Unknown" when `totalSupply <= 0`, so the stored value was 0, not
 * absent. It got there because [AssetMetadataService.storeFromJson] built a whole
 * [AssetMetadataEntity] from the document and wrote it through `insert`, which is
 * `OnConflictStrategy.REPLACE`. Canonical DigiAsset metadata — the shape DigiAsset Core actually
 * writes — carries no `totalSupply` key at all, so `optLong("totalSupply", 0L)` returned the
 * default and REPLACE stamped 0 over the chain-derived number.
 *
 * ## Why it is a one-directional bug
 *
 * The mirror guard already exists. `AssetMetadataDao.updateChainFacts` is a targeted UPDATE whose
 * comment says it merges chain facts "without disturbing IPFS-sourced fields (name, description,
 * imageUrl)", and `AssetManager` explains the principle: totalSupply and decimals "are
 * cryptographically tied to the issuance tx, whereas name/imageUrl depend on IPFS reachability".
 * Chain data was protected from IPFS-timing; IPFS data was never protected from clobbering chain
 * data back. Last writer won, which is why the wrong reading is intermittent and self-heals on the
 * next chain-facts pass — and why it is worst in the first minutes after an asset arrives, exactly
 * when someone opening a freshly dropped collectible looks for the edition size.
 *
 * ## The rule this pins
 *
 * Supply is chain data. An IPFS metadata document is written by whoever issued the asset and is
 * authenticated by nothing, so it must not be able to set supply — least of all by being silently
 * absent. A metadata write may only touch the columns it is the source of.
 */
class AssetSupplyNotClobberedByMetadataTest {

    private val assetId = "La8JvCqENcANWZsFdjW5nfZki3EsnEzuycLJod"

    private fun service(dao: AssetMetadataDao) = AssetMetadataService(
        ipfsClient = mockk(relaxed = true),
        assetMetadataDao = dao,
    )

    /**
     * The canonical document, taken from what the proxy returns for a real mainnet asset:
     * everything nested under `data`, the name keyed `assetName`, and no `totalSupply` anywhere.
     */
    private fun canonicalDocument() = JSONObject(
        """
        {"data":{"assetName":"Brasa Rule Gate Test 008",
                 "description":"Throwaway, issued to a test device.",
                 "urls":[],
                 "userData":{"issuer":"Brasa Studios"}}}
        """.trimIndent()
    )

    /**
     * The regression, asserted positively: the whole-row REPLACE must not be used at all, and the
     * targeted IPFS-only write must be. Asserting "no insert carried supply 0" would pass
     * vacuously once insert stops being called, which is a gate that observes nothing.
     */
    @Test
    fun `a metadata document is written through the targeted path, never a whole-row REPLACE`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        service(dao).storeFromJson(assetId, cid = "bafk", jsonRaw = canonicalDocument(), provenIssuer = null)

        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 1) {
            dao.updateIpfsFacts(assetId, any(), any(), any(), any(), any(), any(), any())
        }
    }

    /** Any row this path seeds must carry supply 0 — it has no chain data and must not invent one. */
    @Test
    fun `a seeded row never asserts a supply`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        val seeded = mutableListOf<AssetMetadataEntity>()
        service(dao).storeFromJson(assetId, cid = "bafk", jsonRaw = canonicalDocument(), provenIssuer = null)
        coVerify(exactly = 1) { dao.insertChainFacts(capture(seeded)) }
        assertEquals(1, seeded.size)
        assertEquals("a seed row must not claim a supply", 0L, seeded[0].totalSupply)
    }

    /** And it must still store what it *is* the source of, or the fix would just be a deletion. */
    @Test
    fun `the name from the document is still persisted`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        service(dao).storeFromJson(assetId, cid = "bafk", jsonRaw = canonicalDocument(), provenIssuer = null)
        coVerify(exactly = 1) {
            dao.updateIpfsFacts(
                assetId, "Brasa Rule Gate Test 008", any(),
                "Throwaway, issued to a test device.", any(), "bafk", any(), any(),
            )
        }
    }

    /**
     * An older flat document that really does declare a supply is still not trusted with it.
     * The document is unauthenticated; a wrong edition size shown confidently is worse than one
     * that arrives a moment later from the chain.
     */
    @Test
    fun `even a document that declares totalSupply does not set it`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        val seeded = mutableListOf<AssetMetadataEntity>()
        val doc = JSONObject("""{"name":"Flat","totalSupply":1,"decimals":3}""")
        service(dao).storeFromJson(assetId, cid = null, jsonRaw = doc, provenIssuer = null)

        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 1) { dao.insertChainFacts(capture(seeded)) }
        assertEquals("a declared supply is still not honoured", 0L, seeded[0].totalSupply)
        assertEquals("a declared divisibility is still not honoured", 0, seeded[0].decimals)
    }
}
