package io.digibyte.core.asset

import io.digibyte.core.SendRefusal
import io.digibyte.core.TxResult
import io.digibyte.core.asset.rules.TransferRuleState
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
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

    /**
     * The verdict must be about the asset that was ASKED about.
     *
     * A UTXO the walk has not yet named carries an `unresolved:<txid>` placeholder asset_id, and
     * getOwnedAssets groups by it — so the screen asks about "unresolved:u1" while the forced walk
     * resolves the DERIVED id. Returning that walk's verdict under the placeholder let the screen
     * read NONE and enable Send, while sendAsset's own transferRuleState("unresolved:...") read
     * UNKNOWN and refused: the user paid a PIN prompt to be told "Blocked".
     *
     * What this test can and cannot reach: the walk's single hop is AssetManager's
     * classifyProvenanceHop, whose first act is NativeBridge.getSerializedTransactionForHash, and
     * NativeBridge's `init { System.loadLibrary("core-lib") }` cannot run on a JVM. So the walk
     * here always fails rather than resolving, and this pins the BOUNDARY: whatever the walk does,
     * a placeholder id must never come back as anything but UNKNOWN. The mismatch branch itself
     * (walk resolves "LaRuled", caller asked "unresolved:u1") is not drivable from a JVM test.
     */
    @Test fun `a placeholder asset id never takes a verdict from the id the walk resolves`() = runBlocking {
        store.putAssets(listOf("u1"), facts(1, true))          // the walk's answer would be "LaRuled"
        coEvery { metaDao.rulesJsonFor(any()) } returns null
        coEvery { utxoDao.getAssetUtxosByIdNow("unresolved:u1") } returns listOf(
            UtxoEntity(
                txid = "u1", vout = 0, scriptPubKey = ByteArray(0), satoshis = 600L,
                blockHeight = 24_000_000L, isAsset = true, assetId = "unresolved:u1",
            )
        )

        assertEquals(TransferRuleState.UNKNOWN, manager().verifyTransferRules("unresolved:u1"))
    }
}
