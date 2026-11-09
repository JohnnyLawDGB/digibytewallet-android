package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.dao.TransactionDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.ipfs.AssetMetadataService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DA-014-REF, the provenance walk: a hop reads a transaction's carrier by the one rule
 * ([DigiAssetCarrierRule]) that detection and native code use, DigiAsset Core's. It never takes
 * the first OP_RETURN, and a transaction the rule cannot decide names no asset.
 *
 * The production hop is [AssetManager.classifyProvenanceHopFrom]; only its native reads (the
 * output scripts of a raw transaction, from NativeBridge.getRawTransactionOutputs, and the
 * issuance id derivation) are passed in, because the native library cannot load on the host JVM.
 */
class AssetProvenanceCarrierRuleTest {

    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A minimal serialized transaction spending [prevTxid]:[prevVout]; [tag] is the lock time. */
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

    /** Locked issuance, 10 units, divisibility 0. */
    private val issuanceTen = bytes("6a06444103050a10")
    /** Locked issuance, 20 units, divisibility 1. */
    private val issuanceTwentyDiv1 = bytes("6a06444103051430")
    /** Transfer, 10 units to output 0. */
    private val transfer = bytes("6a0644410115000a")
    private val plain = bytes("6a04deadbeef")
    private val victim = bytes("0014" + "11".repeat(20))

    private val startId = "5".repeat(64)
    private val parentAId = "a".repeat(64)

    /** The received transaction; its first input spends A's issuance at [parentAId]:0. */
    private val start = tx(prevTxid = parentAId, prevVout = 0, tag = 1)
    /** A's issuance, whose own first input is c…c:1. */
    private val parentA = tx(prevTxid = "c".repeat(64), prevVout = 1, tag = 2)

    private val derive: (String, Int, Int, Int) -> String? =
        { prevTxid, prevVout, _, div -> "La-" + prevTxid.take(8) + ":" + prevVout + "/d" + div }

    /** What an issuance carried by [start] itself is named: from start's first input. */
    private val startIssuanceId = "La-aaaaaaaa:0/d"
    /** A's id: from its issuance's first input. */
    private val assetAId = "La-cccccccc:1/d0"

    private lateinit var manager: AssetManager

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        manager = AssetManager(
            mockk<UtxoDao>(relaxed = true), mockk<TransactionDao>(relaxed = true),
            mockk<AssetMetadataDao>(relaxed = true), mockk<AssetMetadataService>(relaxed = true),
        )
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `the fixtures decode as described`() {
        val d = DigiAssetDecoder()
        assertEquals(10L, d.decode(issuanceTen)?.totalQuantity)
        assertEquals(0, d.decode(issuanceTen)?.divisibility)
        assertEquals(20L, d.decode(issuanceTwentyDiv1)?.totalQuantity)
        assertEquals(1, d.decode(issuanceTwentyDiv1)?.divisibility)
        assertTrue(d.decode(issuanceTwentyDiv1)!!.locked)
        assertEquals(app.aroundtheblock.wallet.core.model.AssetOperation.TRANSFER, d.decode(transfer)?.operation)
        assertNull(d.decode(plain))
    }

    private suspend fun hop(startScripts: List<ByteArray>): AssetProvenanceWalker.Hop =
        manager.classifyProvenanceHopFrom(
            startId,
            fetch = { if (it == startId) start else if (it == parentAId) parentA else null },
            outputScriptsOf = { raw ->
                when (hex(raw)) {
                    hex(start) -> startScripts
                    hex(parentA) -> listOf(issuanceTen, victim)
                    else -> null
                }
            },
            deriveIssuanceAssetId = derive,
        )

    private suspend fun walk(startScripts: List<ByteArray>): ResolvedAssetFacts? {
        val store = object : ProvenanceStore {
            val frontiers = mutableMapOf<String, WalkFrontier>()
            override suspend fun assetFor(txid: String): ResolvedAssetFacts? = null
            override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {}
            override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? = null
            override suspend fun frontierFor(startTxid: String): WalkFrontier? = frontiers[startTxid]
            override suspend fun putFrontier(frontier: WalkFrontier) { frontiers[frontier.startTxid] = frontier }
            override suspend fun clearFrontier(startTxid: String) { frontiers.remove(startTxid) }
        }
        return AssetProvenanceWalker(
            hop = { txid ->
                manager.classifyProvenanceHopFrom(
                    txid,
                    fetch = { if (it == startId) start else if (it == parentAId) parentA else null },
                    outputScriptsOf = { raw ->
                        when (hex(raw)) {
                            hex(start) -> startScripts
                            hex(parentA) -> listOf(issuanceTen, victim)
                            else -> null
                        }
                    },
                    deriveIssuanceAssetId = derive,
                )
            },
            store = store,
        ).resolve(startId)
    }

    /**
     * RED on the first-OP_RETURN hop. [transfer of A (decoy), issuance of B, victim]: DigiAsset
     * Core reads the last nulldata output, B's issuance. The first-OP_RETURN hop read the decoy,
     * climbed to A's issuance and named the received units A.
     */
    @Test fun `a decoy transfer before an issuance does not name the issuance after the decoy's asset`() = runTest {
        val facts = walk(listOf(transfer, issuanceTwentyDiv1, victim))

        assertNotNull("the issuance was not resolved", facts)
        assertEquals(startIssuanceId + "1", facts!!.assetId)
        assertEquals(20L, facts.totalSupply)
        assertEquals(1, facts.divisibility)
        assertTrue("named after the decoy's asset", facts.assetId != assetAId)
    }

    /** RED on the first-OP_RETURN hop: of two issuance carriers the last one names the asset and
     *  supplies its chain facts. */
    @Test fun `of two issuance carriers the last one names the asset and its facts`() = runTest {
        val h = hop(listOf(issuanceTen, issuanceTwentyDiv1, victim))

        val facts = (h as? AssetProvenanceWalker.Hop.Issuance)?.facts
        assertNotNull("not an issuance: $h", facts)
        assertEquals(startIssuanceId + "1", facts!!.assetId)
        assertEquals(20L, facts.totalSupply)
        assertEquals(1, facts.divisibility)
    }

    /** RED on the first-OP_RETURN hop: [plain, issuance]: the issuance is the reference carrier. */
    @Test fun `a plain OP_RETURN before the carrier does not hide it from the walk`() = runTest {
        val h = hop(listOf(plain, issuanceTen, victim))

        assertEquals(startIssuanceId + "0", (h as? AssetProvenanceWalker.Hop.Issuance)?.facts?.assetId)
    }

    /** RED on the first-OP_RETURN hop: [issuance, plain]: DigiAsset Core reads the plain output
     *  and sees no DigiAsset transaction. The walk cannot decide it and names nothing. */
    @Test fun `a plain OP_RETURN after the carrier is undecided and names nothing`() = runTest {
        assertEquals(AssetProvenanceWalker.Hop.DeadEnd, hop(listOf(issuanceTen, plain, victim)))
        assertNull(walk(listOf(issuanceTen, plain, victim)))
    }

    /** GUARD: outputs that could not be read name nothing either. */
    @Test fun `unreadable outputs name nothing`() = runTest {
        assertEquals(AssetProvenanceWalker.Hop.DeadEnd, hop(emptyList()))
    }

    /** GUARD: the honest single-carrier issuance still resolves. */
    @Test fun `a single issuance carrier resolves`() = runTest {
        assertEquals(startIssuanceId + "0", (hop(listOf(issuanceTen, victim)) as? AssetProvenanceWalker.Hop.Issuance)?.facts?.assetId)
    }

    /** GUARD: a single transfer carrier climbs to its parent's issuance. */
    @Test fun `a single transfer carrier walks to the parent issuance`() = runTest {
        assertEquals(assetAId, walk(listOf(transfer, victim))?.assetId)
    }
}
