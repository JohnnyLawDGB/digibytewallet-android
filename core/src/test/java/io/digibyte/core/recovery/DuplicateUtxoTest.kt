package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One outpoint must produce one input, however many times the backend mentions it.
 *
 * ## Observed, not hypothetical
 *
 * The reconcile endpoint returned the same outpoint twice for a real address — once with a
 * confirmed height and once with `h=0`:
 *
 *     6bd35c442896c27f:0   6000 sats   h=24106618
 *     6bd35c442896c27f:0   6000 sats   h=0
 *
 * Nothing in the recovery path deduplicated it, and that has two consequences, the second worse
 * than the first:
 *
 *  1. **The balance double-counts.** "Recoverable balance" reports coins that do not exist.
 *  2. **The transaction is invalid.** `assembleSweepInputs` adds one input per entry, so the
 *     sweep would spend the SAME outpoint twice in one transaction. Bitcoin-derived consensus
 *     rejects that outright — the whole sweep fails, and the reason ("bad-txns-inputs-duplicate"
 *     from a relay peer, if it surfaces at all) points nowhere near a duplicated JSON row.
 *
 * A wallet cannot control what a backend sends it. It can refuse to build nonsense out of it.
 */
class DuplicateUtxoTest {

    private fun utxo(txid: String, vout: Int, sats: Long, height: Long) = UtxoEntry(
        txid = txid, vout = vout, amountSatoshi = sats,
        address = "D7Vxdup", blockHeight = height, scriptPubKeyHex = "76a91488ac",
    )

    // The real pair, verbatim.
    private val confirmed = utxo("6bd35c442896c27f", 0, 6_000L, 24_106_618L)
    private val unconfirmedCopy = utxo("6bd35c442896c27f", 0, 6_000L, 0L)
    private val other = utxo("ac3cbb77c252e78d", 0, 500_000_000L, 0L)

    @Test fun `the same outpoint twice collapses to one`() {
        val deduped = UtxoDedup.byOutpoint(listOf(confirmed, unconfirmedCopy, other))

        assertEquals(2, deduped.size)
        assertEquals(1, deduped.count { it.txid == "6bd35c442896c27f" && it.vout == 0 })
    }

    /** Double-counting is what makes the wallet quote a balance that does not exist. */
    @Test fun `the total stops double-counting`() {
        val raw = listOf(confirmed, unconfirmedCopy, other)
        assertEquals("raw sum is inflated", 500_012_000L, raw.sumOf { it.amountSatoshi })

        val deduped = UtxoDedup.byOutpoint(raw)
        assertEquals("real total", 500_006_000L, deduped.sumOf { it.amountSatoshi })
    }

    /**
     * When the two copies disagree, keep the CONFIRMED one. A height of 0 means "not yet in a
     * block"; preferring it would describe a settled coin as pending and can gate spending.
     */
    @Test fun `the confirmed copy wins over the unconfirmed one`() {
        val fromUnconfirmedFirst = UtxoDedup.byOutpoint(listOf(unconfirmedCopy, confirmed))
        assertEquals(24_106_618L, fromUnconfirmedFirst.single { it.txid == "6bd35c442896c27f" }.blockHeight)

        val fromConfirmedFirst = UtxoDedup.byOutpoint(listOf(confirmed, unconfirmedCopy))
        assertEquals(24_106_618L, fromConfirmedFirst.single { it.txid == "6bd35c442896c27f" }.blockHeight)
    }

    /** Same txid, different vout, is two genuinely different coins. */
    @Test fun `different vouts of one transaction are not duplicates`() {
        val a = utxo("aaaa", 0, 1_000L, 100L)
        val b = utxo("aaaa", 1, 2_000L, 100L)
        assertEquals(2, UtxoDedup.byOutpoint(listOf(a, b)).size)
    }

    @Test fun `order is preserved so nothing else shifts under it`() {
        val out = UtxoDedup.byOutpoint(listOf(other, confirmed, unconfirmedCopy))
        assertEquals("ac3cbb77c252e78d", out.first().txid)
    }

    @Test fun `an empty list stays empty`() {
        assertTrue(UtxoDedup.byOutpoint(emptyList()).isEmpty())
    }

    @Test fun `a clean list is returned unchanged`() {
        val clean = listOf(confirmed, other)
        assertEquals(clean, UtxoDedup.byOutpoint(clean))
    }
}
