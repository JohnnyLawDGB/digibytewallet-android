package io.digibyte.core.asset

import io.digibyte.core.db.dao.AssetProvenanceDao
import io.digibyte.core.db.entity.AssetProvenanceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Room store must carry the issuance opcode both ways, and a legacy row must read as null. */
class RoomProvenanceStoreTest {

    private val dao = mockk<AssetProvenanceDao>(relaxed = true)
    private val store = RoomProvenanceStore(dao, nowMillis = { 1L })

    private val ruled = ResolvedAssetFacts(
        assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = "bafk",
        issuanceOpcode = 4, issuanceLocked = true,
    )

    @Test fun `putAssets writes the opcode and locked flag on every row`() = runBlocking {
        val rows = slot<List<AssetProvenanceEntity>>()
        coEvery { dao.putProvenance(capture(rows)) } returns Unit

        store.putAssets(listOf("tx0", "tx1"), ruled)

        assertEquals(2, rows.captured.size)
        rows.captured.forEach {
            assertEquals(4, it.issuanceOpcode)
            assertEquals(true, it.issuanceLocked)
            assertEquals("LaRuled", it.assetId)
        }
    }

    @Test fun `assetFor maps the columns back`() = runBlocking {
        coEvery { dao.provenanceFor("tx0") } returns AssetProvenanceEntity(
            txid = "tx0", assetId = "LaRuled", totalSupply = 5, divisibility = 0,
            metadataCid = "bafk", issuanceOpcode = 4, issuanceLocked = true,
        )
        assertEquals(ruled, store.assetFor("tx0"))
    }

    @Test fun `a legacy row reads as opcode null`() = runBlocking {
        coEvery { dao.provenanceFor("old") } returns AssetProvenanceEntity(
            txid = "old", assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = null,
        )
        val facts = store.assetFor("old")!!
        assertNull(facts.issuanceOpcode)
        assertNull(facts.issuanceLocked)
    }

    @Test fun `issuanceFactsFor delegates to the opcode-aware query`() = runBlocking {
        coEvery { dao.issuanceFactsFor("LaRuled") } returns AssetProvenanceEntity(
            txid = "tx9", assetId = "LaRuled", totalSupply = 5, divisibility = 0,
            metadataCid = "bafk", issuanceOpcode = 4, issuanceLocked = true,
        )
        assertEquals(ruled, store.issuanceFactsFor("LaRuled"))
        coVerify { dao.issuanceFactsFor("LaRuled") }
    }
}
