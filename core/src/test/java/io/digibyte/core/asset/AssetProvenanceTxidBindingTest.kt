package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.asset.network.AssetDataResponse
import io.digibyte.core.asset.network.AssetNetworkClient
import io.digibyte.core.asset.network.MultiEndpointAssetClient
import io.digibyte.core.asset.network.SyncStateResponse
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A parent transaction fetched for the provenance walk is accepted only when its bytes hash to
 * the id it was requested by.
 *
 * The walk names an asset from its issuance, and the issuance is reached by fetching parents by
 * id from the multi-endpoint asset client. That client is also where the recovery classifier
 * takes its parents from, so the check sits there: an answer whose id is not the requested one
 * counts as no answer from that endpoint, and the client moves to the next one before anything
 * reads or keeps the bytes.
 *
 * What runs here is production code end to end except the JNI hops: the real
 * [MultiEndpointAssetClient], the real [AssetProvenanceWalker], and the real hop
 * ([AssetManager.classifyProvenanceHopFrom] behind [AssetManager.fetchRawTransactionBytesFrom]),
 * with the real [DigiAssetDecoder]. The native layer cannot load on the host JVM (NativeBridge's
 * initializer loads the library), so its three reads are passed in: the id of a byte string
 * (in production NativeBridge.rawTransactionId, pinned against the core parser by
 * parent_txid_binding_kat, including a real transaction with a witness), the OP_RETURN of a
 * transaction, and the issuance id derivation. Each fixture below is a distinct byte string with
 * a declared id; the id function answers from that table and nothing else.
 */
class AssetProvenanceTxidBindingTest {

    // ---- fixtures ---------------------------------------------------------------------------

    /** A minimal serialized transaction: version, one input spending [prevTxid]:[prevVout],
     *  one empty output, [tag] as the lock time so every fixture is a different byte string. */
    private fun tx(prevTxid: String, prevVout: Int, tag: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun le32(v: Int) { for (i in 0 until 4) out += ((v ushr (8 * i)) and 0xFF).toByte() }
        le32(1)
        out += 0x01
        prevTxid.chunked(2).reversed().forEach { out += it.toInt(16).toByte() }
        le32(prevVout)
        out += 0x00
        le32(-1)
        out += 0x01
        repeat(8) { out += 0x00 }
        out += 0x00
        le32(tag)
        return out.toByteArray()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Locked issuance, 10 units, divisibility 0 (see AssetProvenanceTaggingTest for the bits). */
    private val issuanceOpReturn = bytes("6a06444103050a10")
    /** Transfer, one instruction: 10 units to output 0 (see AssetImplicitChangeTest). */
    private val transferOpReturn = bytes("6a0644410115000a")

    private val startId = "5".repeat(64)
    private val parentId = "a".repeat(64)
    private val otherId = "b".repeat(64)

    /** The transfer the wallet received; its first input spends the requested parent. */
    private val start = tx(prevTxid = parentId, prevVout = 0, tag = 1)
    /** The requested parent: an issuance whose first input is c…c:1. */
    private val parent = tx(prevTxid = "c".repeat(64), prevVout = 1, tag = 2)
    /** A valid issuance that is NOT the requested parent: its first input is d…d:0. */
    private val other = tx(prevTxid = "d".repeat(64), prevVout = 0, tag = 3)
    /** Bytes no id can be computed for. */
    private val unreadable = bytes("00")

    private val idOf = mapOf(hex(start) to startId, hex(parent) to parentId, hex(other) to otherId)
    private val opReturnOfFixture = mapOf(
        hex(start) to transferOpReturn, hex(parent) to issuanceOpReturn, hex(other) to issuanceOpReturn,
    )

    private val parentAssetId = "La-" + "c".repeat(8) + ":1"
    private val otherAssetId = "La-" + "d".repeat(8) + ":0"

    // ---- the pieces under test --------------------------------------------------------------

    /** Every byte string the hop read an OP_RETURN from, in order. */
    private val parsed = mutableListOf<String>()

    private class Endpoint(val label: String, val answer: (String) -> ByteArray?) : AssetNetworkClient {
        val asked = mutableListOf<String>()
        override val endpointLabel = label
        override suspend fun getAssetData(assetId: String): AssetDataResponse? = null
        override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? = null
        override suspend fun getSyncState(): SyncStateResponse? = null
        override suspend fun getRawTransaction(txHashHex: String): ByteArray? {
            asked += txHashHex
            return answer(txHashHex)
        }
    }

    /** Records identity writes separately from the frontier the walker keeps between attempts. */
    private class RecordingStore : ProvenanceStore {
        val identityWrites = mutableListOf<Pair<List<String>, ResolvedAssetFacts>>()
        val frontiers = mutableMapOf<String, WalkFrontier>()
        override suspend fun assetFor(txid: String): ResolvedAssetFacts? = null
        override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
            identityWrites += txids to facts
        }
        override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? = null
        override suspend fun frontierFor(startTxid: String): WalkFrontier? = frontiers[startTxid]
        override suspend fun putFrontier(frontier: WalkFrontier) { frontiers[frontier.startTxid] = frontier }
        override suspend fun clearFrontier(startTxid: String) { frontiers.remove(startTxid) }
    }

    private lateinit var manager: AssetManager

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        manager = AssetManager(
            mockk<UtxoDao>(relaxed = true), mockk<TransactionDao>(relaxed = true),
            mockk<AssetMetadataDao>(relaxed = true), mockk<AssetMetadataService>(relaxed = true),
        )
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun client(vararg endpoints: AssetNetworkClient, failureThreshold: Int = 3) =
        MultiEndpointAssetClient(
            endpoints = endpoints.toList(),
            failureThreshold = failureThreshold,
            transactionIdOf = { idOf[hex(it)] },
        )

    /** The walk production runs, from the received transfer [start], which the wallet holds. */
    private fun walker(client: AssetNetworkClient, store: ProvenanceStore) = AssetProvenanceWalker(
        hop = { txid ->
            manager.classifyProvenanceHopFrom(
                txid,
                fetch = { id ->
                    manager.fetchRawTransactionBytesFrom(
                        id,
                        walletCopy = { if (it == startId) start else null },
                        client = client,
                    )
                },
                opReturnOf = { raw -> parsed += hex(raw); opReturnOfFixture[hex(raw)] },
                deriveIssuanceAssetId = { prevTxid, prevVout, _, _ -> "La-" + prevTxid.take(8) + ":" + prevVout },
            )
        },
        store = store,
    )

    // ---- the invariant ----------------------------------------------------------------------

    /** The only answer anywhere is a valid issuance that is not the requested parent. It is not
     *  read, the hop is Unavailable, and no asset identity is written for any transaction on the
     *  path — only the frontier, so the next attempt resumes at the parent. */
    @Test fun a_different_transaction_is_unavailable_and_names_no_asset() = runTest {
        val first = Endpoint("first") { other }
        val second = Endpoint("second") { null }
        val store = RecordingStore()

        val facts = walker(client(first, second), store).resolve(startId)

        assertNull("a different transaction named the asset", facts)
        assertTrue("an asset identity was written: ${store.identityWrites}", store.identityWrites.isEmpty())
        assertFalse("the different transaction's bytes were read", hex(other) in parsed)
        assertEquals("the walk should resume at the parent", parentId, store.frontiers[startId]?.resumeTxid)
    }

    /** The first endpoint answers with a different transaction, the second with the requested
     *  one. The client moves on before anything reads the first answer, and the asset is named
     *  from the requested parent's own issuance. */
    @Test fun the_client_moves_to_the_next_endpoint_before_anything_reads_the_answer() = runTest {
        val first = Endpoint("first") { other }
        val second = Endpoint("second") { if (it == parentId) parent else null }
        val store = RecordingStore()

        val facts = walker(client(first, second), store).resolve(startId)

        assertEquals(parentAssetId, facts?.assetId)
        assertEquals(listOf(parentId), second.asked)
        assertFalse("the different transaction's bytes were read", hex(other) in parsed)
        assertEquals(listOf(hex(start), hex(parent)), parsed)
        assertEquals(1, store.identityWrites.size)
        assertEquals(listOf(startId, parentId), store.identityWrites.single().first)
        assertEquals(parentAssetId, store.identityWrites.single().second.assetId)
        assertFalse(store.identityWrites.any { it.second.assetId == otherAssetId })
    }

    /** Bytes no id can be computed for are no answer either. */
    @Test fun bytes_with_no_id_are_no_answer() = runTest {
        val first = Endpoint("first") { unreadable }
        val second = Endpoint("second") { if (it == parentId) parent else null }
        val store = RecordingStore()

        val facts = walker(client(first, second), store).resolve(startId)

        assertEquals(parentAssetId, facts?.assetId)
        assertFalse("bytes with no id were read", hex(unreadable) in parsed)
    }

    /** An endpoint that keeps answering with the wrong transaction is counted as failing, and
     *  after the threshold the client stops asking it for the cool-down period. */
    @Test fun a_wrong_answer_counts_as_a_failure_of_that_endpoint() = runTest {
        val first = Endpoint("first") { other }
        val second = Endpoint("second") { if (it == parentId) parent else null }
        val multi = client(first, second, failureThreshold = 2)

        repeat(4) {
            assertTrue("the answer was not the requested transaction", multi.getRawTransaction(parentId).contentEquals(parent))
        }

        assertEquals("the endpoint was asked after it crossed the threshold", 2, first.asked.size)
        assertEquals(4, second.asked.size)
    }

    /** GUARD (passes before and after the check): the honest path is unchanged — the requested
     *  parent from the first endpoint names the asset, and only the second is never asked. */
    @Test fun guard_the_requested_parent_is_accepted() = runTest {
        val first = Endpoint("first") { if (it == parentId) parent else null }
        val second = Endpoint("second") { other }
        val store = RecordingStore()

        val facts = walker(client(first, second), store).resolve(startId)

        assertEquals(parentAssetId, facts?.assetId)
        assertTrue(second.asked.isEmpty())
    }

    /** GUARD: the comparison is on the id, not on how the request spelled it. */
    @Test fun guard_an_upper_case_request_is_compared_by_value() = runTest {
        val only = Endpoint("only") { if (it.equals(parentId, ignoreCase = true)) parent else null }

        val bytes = client(only).getRawTransaction(parentId.uppercase())

        assertTrue(bytes.contentEquals(parent))
    }
}
