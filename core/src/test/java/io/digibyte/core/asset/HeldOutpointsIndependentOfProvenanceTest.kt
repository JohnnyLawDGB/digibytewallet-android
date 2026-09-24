package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.asset.rules.TransferRuleState
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Discarding what the provenance walk resolved never makes an asset carrier spendable, and never
 * changes what the wallet holds: the outputs held out of the plain-coin set are decided from the
 * asset rows and the wallet's own transactions alone, with no provenance at all. After the
 * discard an asset reads as not yet identified for a send, and the walk identifies it again.
 *
 * The held set has two writers besides detection: the startup replay over the stored rows and
 * the pass in front of every spend. Both run here against a provenance store that records any
 * call made to it, and must reach their answer without one.
 */
class HeldOutpointsIndependentOfProvenanceTest {

    /** Records every call; answers nothing. The held set must not need it. */
    private class UnreachableProvenance : ProvenanceStore {
        val calls = mutableListOf<String>()
        override suspend fun assetFor(txid: String): ResolvedAssetFacts? { calls += "assetFor"; return null }
        override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) { calls += "putAssets" }
        override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? { calls += "issuanceFactsFor"; return null }
        override suspend fun frontierFor(startTxid: String): WalkFrontier? { calls += "frontierFor"; return null }
        override suspend fun putFrontier(frontier: WalkFrontier) { calls += "putFrontier" }
        override suspend fun clearFrontier(startTxid: String) { calls += "clearFrontier" }
    }

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val metadataDao = mockk<AssetMetadataDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()

    private val assetId = "La" + "c".repeat(30)
    private fun id(tag: String) = tag.padEnd(64, '0')

    private fun row(txid: String, vout: Int, quantity: Long) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = byteArrayOf(1, 2, 3), satoshis = 600L, blockHeight = 100L,
        isAsset = true, assetId = assetId, assetQuantity = quantity, spent = false, assetSource = AssetSource.NATIVE,
    )

    private val carrier = row(id("c1"), 0, 10L)
    private val targetedZero = row(id("c2"), 3, 0L)
    private val plainChange = row(id("c3"), 1, 0L)

    private fun manager(store: ProvenanceStore) = AssetManager(
        utxoDao = utxoDao,
        transactionDao = mockk(relaxed = true),
        metadataDao = metadataDao,
        metadataService = mockk(relaxed = true),
        provenanceStore = store,
        registerAssetOutpoint = { txid, vout -> held += txid to vout; true },
        resolveRowTargets = { txid, _ -> txid == targetedZero.txid },
    )

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(carrier, targetedZero, plainChange)
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun the_startup_replay_holds_every_carrier_with_no_provenance() = runTest {
        val store = UnreachableProvenance()

        manager(store).replayAssetOutpointExclusions()

        assertEquals(listOf(carrier.txid to 0, targetedZero.txid to 3), held)
        assertTrue("the replay consulted provenance: ${store.calls}", store.calls.isEmpty())
    }

    /** The same replay over a store holding an identity for every row holds the same outputs:
     *  what the walk resolved has no say in what is held. */
    @Test fun what_the_walk_resolved_does_not_change_the_held_set() = runTest {
        val resolved = InMemoryProvenanceStore().apply {
            putAssets(
                listOf(carrier.txid, targetedZero.txid, plainChange.txid),
                ResolvedAssetFacts(assetId, 10L, 0, null, issuanceOpcode = 1, issuanceLocked = true),
            )
        }
        manager(resolved).replayAssetOutpointExclusions()
        val withIdentities = held.toList()
        held.clear()

        manager(UnreachableProvenance()).replayAssetOutpointExclusions()

        assertEquals(withIdentities, held)
    }

    @Test fun the_pass_before_a_spend_holds_every_carrier_with_no_provenance() = runTest {
        val ours = "0014" + "11".repeat(20)
        val theirs = "0014" + "22".repeat(20)
        val transfer = id("b1")
        val outputs = mapOf(
            // Marker to someone else, the payload (10 units to output 0), OUR output last: with
            // what the inputs carried unknown, a remainder may ride to it, so it is held.
            transfer to arrayOf("0|700|$theirs", "1|0|6a0644410115000a", "2|50000|$ours"),
            id("a1") to arrayOf("0|150000000|$ours"),
        )
        val store = UnreachableProvenance()

        manager(store).holdAssetOutputsBeforeSpendImpl(
            txHashes = { outputs.keys.toTypedArray() },
            outputsOf = { outputs[it] },
            inputsOf = { if (it == transfer) arrayOf(id("f1") + "|1") else emptyArray() },
            ownedScriptHexes = { setOf(ours) },
        )

        assertEquals(listOf(transfer to 2), held)
        assertTrue("the pass consulted provenance: ${store.calls}", store.calls.isEmpty())
    }

    /** After the discard a send reads the asset as not yet identified — the screen shows its
     *  check in progress and walks again — rather than trusting an identity nobody re-derived. */
    @Test fun a_discarded_identity_reads_as_not_yet_identified() = runTest {
        coEvery { metadataDao.rulesJsonFor(assetId) } returns null
        val before = InMemoryProvenanceStore().apply {
            putAssets(listOf(carrier.txid), ResolvedAssetFacts(assetId, 10L, 0, null, 1, true))
        }
        assertEquals(
            "GUARD: a locked rule-free issuance on record is NONE",
            TransferRuleState.NONE, manager(before).transferRuleState(assetId),
        )

        assertEquals(TransferRuleState.UNKNOWN, manager(InMemoryProvenanceStore()).transferRuleState(assetId))
    }

    /** The walk derives a discarded identity again from the chain, and keeps what it derived. */
    @Test fun the_walk_derives_a_discarded_identity_again() = runTest {
        val start = id("d1")
        val parent = id("d2")
        val bound = ResolvedAssetFacts("La" + "e".repeat(30), 10L, 0, null, 1, true)
        val hops = mutableListOf<String>()
        val hop: suspend (String) -> AssetProvenanceWalker.Hop = { txid ->
            hops += txid
            if (txid == start) AssetProvenanceWalker.Hop.Transfer(parent) else AssetProvenanceWalker.Hop.Issuance(bound)
        }
        val store = InMemoryProvenanceStore()

        val facts = AssetProvenanceWalker(hop = hop, store = store).resolve(start)

        assertEquals(bound, facts)
        assertEquals(listOf(start, parent), hops)
        assertEquals(bound, store.assetFor(start))
        assertEquals(bound, store.issuanceFactsFor(bound.assetId))
    }
}
