package app.aroundtheblock.wallet.core.dandelion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [Broadcaster.broadcast] arms the Dandelion++ embargo only when the send really stemmed
 * (core `BRDandelionEmbargo.h`: "the embargo is armed when the stem returns 1"). The bridge's
 * stem call floods the transaction itself when the capable peer drops between its check and the
 * stem; that send is already announced to every peer, so arming an embargo on it would only make
 * the deadline fluff announce it a second time, and publishing it again would do the same now.
 */
class BroadcasterStemArmTest {

    private class FakeBridge(
        var hasPeer: Boolean = true,
        var stemResult: StemResult? = null,
    ) : BroadcastBridge {
        val stemCalls = mutableListOf<ByteArray>()
        val publishCalls = mutableListOf<ByteArray>()
        val fluffCalls = mutableListOf<String>()

        override fun hasDandelionPeer(): Boolean = hasPeer
        override fun publishTransactionStem(signedTx: ByteArray): StemResult? {
            stemCalls += signedTx
            return stemResult
        }
        override fun publishTransaction(signedTx: ByteArray): String? {
            publishCalls += signedTx
            return FLOOD_TXID
        }
        override fun getRelayCount(txid: String): Int = 1
        override fun fluffTransaction(txid: String) { fluffCalls += txid }
    }

    private val tx = byteArrayOf(1, 2, 3)
    private lateinit var fake: FakeBridge
    private lateinit var original: BroadcastBridge

    @Before fun install() {
        original = Broadcaster.bridge
        fake = FakeBridge()
        Broadcaster.bridge = fake
        Broadcaster.dandelionEnabled = true
    }

    @After fun restore() {
        Broadcaster.bridge = original
        Broadcaster.dandelionEnabled = false
    }

    @Test fun `a real stem arms the embargo and does not flood`() {
        fake.stemResult = StemResult(STEM_TXID, stemmed = true)
        assertEquals(STEM_TXID, Broadcaster.broadcast(tx))
        assertTrue(Broadcaster.isEmbargoPending(STEM_TXID))
        assertEquals(1, fake.stemCalls.size)
        assertEquals(0, fake.publishCalls.size)
    }

    @Test fun `a stem the bridge flooded instead returns the txid without an embargo or a second publish`() {
        fake.stemResult = StemResult(RACE_TXID, stemmed = false)
        assertEquals(RACE_TXID, Broadcaster.broadcast(tx))
        assertFalse("a flooded send must not arm an embargo", Broadcaster.isEmbargoPending(RACE_TXID))
        assertEquals(1, fake.stemCalls.size)
        assertEquals("the bridge already flooded it", 0, fake.publishCalls.size)
    }

    @Test fun `no capable peer publishes plainly`() {
        fake.hasPeer = false
        assertEquals(FLOOD_TXID, Broadcaster.broadcast(tx))
        assertEquals(0, fake.stemCalls.size)
        assertEquals(1, fake.publishCalls.size)
        assertFalse(Broadcaster.isEmbargoPending(FLOOD_TXID))
    }

    @Test fun `a stem call that handled nothing falls back to a plain publish`() {
        fake.stemResult = null
        assertEquals(FLOOD_TXID, Broadcaster.broadcast(tx))
        assertEquals(1, fake.stemCalls.size)
        assertEquals(1, fake.publishCalls.size)
        assertFalse(Broadcaster.isEmbargoPending(FLOOD_TXID))
    }

    @Test fun `with the setting off nothing is stemmed`() {
        Broadcaster.dandelionEnabled = false
        assertEquals(FLOOD_TXID, Broadcaster.broadcast(tx))
        assertEquals(0, fake.stemCalls.size)
        assertEquals(1, fake.publishCalls.size)
    }

    private companion object {
        val STEM_TXID = "aa".repeat(32)
        val RACE_TXID = "bb".repeat(32)
        val FLOOD_TXID = "cc".repeat(32)
    }
}
