package io.digibyte.core.asset

import io.digibyte.core.SendRefusal
import io.digibyte.core.TxResult
import io.digibyte.core.asset.rules.TransferRuleState
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The send path refuses BEFORE it reads a UTXO or touches the native bridge. Only refusals are
 * testable on the JVM (an allowed send goes straight into NativeBridge, which has no library
 * here) — and refusals are the whole point: on the unfixed code these tests die in
 * NativeBridge's static initialiser, which is the red this fix turns green.
 */
class AssetSendRuleGateTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaService = mockk<AssetMetadataService>(relaxed = true)
    private val store = InMemoryProvenanceStore()

    private fun manager() = AssetManager(
        utxoDao = utxoDao, transactionDao = txDao, metadataDao = metaDao,
        metadataService = metaService, provenanceStore = store,
    )

    private fun facts(opcode: Int?, locked: Boolean?) = ResolvedAssetFacts(
        assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = null,
        issuanceOpcode = opcode, issuanceLocked = locked,
    )

    @Test fun `a rule-bound asset is refused before any UTXO is read`() = runBlocking {
        store.putAssets(listOf("iss"), facts(4, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns null

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULE_BOUND_ASSET), r)
        coVerify(exactly = 0) { utxoDao.getAssetUtxosByIdNow(any()) }
    }

    @Test fun `an asset whose issuance was never walked is refused as unknown`() = runBlocking {
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns null

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULES_UNKNOWN), r)
    }

    @Test fun `the proxy's rules object refuses even a rule-free locked issuance`() = runBlocking {
        store.putAssets(listOf("iss"), facts(1, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns """{"royalty":{"addresses":{"D1":1}}}"""

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULE_BOUND_ASSET), r)
    }

    @Test fun `transferRuleState reads the two stores and nothing else`() = runBlocking {
        store.putAssets(listOf("iss"), facts(1, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns "{}"
        assertEquals(TransferRuleState.NONE, manager().transferRuleState("LaRuled"))

        coEvery { metaDao.rulesJsonFor("LaRuled") } returns """{"changeable":false}"""
        assertEquals(TransferRuleState.RULE_BOUND, manager().transferRuleState("LaRuled"))
        coVerify(exactly = 0) { metaService.refreshRules(any()) }
    }

    @Test fun `verifyTransferRules refuses as unknown when the asset has no held UTXO to walk from`() = runBlocking {
        coEvery { utxoDao.getAssetUtxosByIdNow("LaRuled") } returns emptyList()
        assertEquals(TransferRuleState.UNKNOWN, manager().verifyTransferRules("LaRuled"))
    }
}
