package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splits a foreign seed's UTXOs into "safe to sweep as plain DGB" and "carries a DigiAsset".
 *
 * ## The defect this exists to close
 *
 * `LegacySweepService` takes every UTXO with a scriptPubKey and spends them into a single plain
 * output. It has no asset awareness — and it *cannot* have any, because [UtxoEntry] has no field
 * that could carry it: txid, vout, amountSatoshi, address, blockHeight, scriptPubKeyHex.
 *
 * A DigiAsset lives on a specific UTXO. Spend that UTXO as ordinary DGB, without the DigiAsset
 * output structure, and the asset is destroyed — not moved, destroyed. So sweeping a seed that
 * holds assets burns them, on a path shipped today at Settings → "Recover funds from another
 * wallet".
 *
 * ## Why the split is a pure function
 *
 * Deciding *which* outputs carry assets needs raw transactions and the native DigiAsset parser.
 * Deciding *what to do* with that answer does not, and it is the part where a mistake costs
 * somebody their asset. Keeping it separate makes the rule testable without JNI, and makes the
 * one invariant that matters assertable directly:
 *
 * **an outpoint known to carry an asset must never appear in the sweep list.**
 *
 * The default is deliberately paranoid. An outpoint we could not classify is held back, not
 * swept: an unswept asset can be moved later, a burned one cannot, and "we could not check"
 * must never resolve to "go ahead".
 */
class SweepPartitionTest {

    private fun utxo(txid: String, vout: Int, sats: Long = 100_000L) = UtxoEntry(
        txid = txid,
        vout = vout,
        amountSatoshi = sats,
        address = "D7Vx${txid.take(6)}",
        blockHeight = 24_000_000L,
        scriptPubKeyHex = "76a914${txid.take(40)}88ac",
    )

    private val plainA = utxo("aaaa", 0)
    private val plainB = utxo("bbbb", 1)
    private val assetC = utxo("cccc", 0, sats = 6_000L)
    private val assetD = utxo("dddd", 2, sats = 600L)

    // ---- the invariant ----------------------------------------------------------------------

    @Test fun `an asset-bearing outpoint never reaches the sweep list`() {
        val result = SweepPartition.split(
            utxos = listOf(plainA, assetC, plainB, assetD),
            carriesAsset = { u -> u.txid == "cccc" || u.txid == "dddd" },
            classified = { true },
        )

        assertEquals(listOf(plainA, plainB), result.sweepable)
        assertTrue(result.sweepable.none { it.txid == "cccc" || it.txid == "dddd" })
        assertEquals(listOf(assetC, assetD), result.assetBearing)
    }

    @Test fun `a wallet with no assets sweeps entirely`() {
        val result = SweepPartition.split(
            utxos = listOf(plainA, plainB),
            carriesAsset = { false },
            classified = { true },
        )

        assertEquals(2, result.sweepable.size)
        assertTrue(result.assetBearing.isEmpty())
        assertTrue(result.unclassified.isEmpty())
    }

    @Test fun `a wallet of only assets sweeps nothing`() {
        val result = SweepPartition.split(
            utxos = listOf(assetC, assetD),
            carriesAsset = { true },
            classified = { true },
        )

        assertTrue("nothing may be swept", result.sweepable.isEmpty())
        assertEquals(2, result.assetBearing.size)
    }

    // ---- the paranoid default ---------------------------------------------------------------

    /**
     * The case that decides whether this design is safe. When classification fails — the provider
     * is down, a raw tx will not fetch — the outpoint is held back. An unswept asset can be moved
     * tomorrow; a burned one is gone. "We could not check" must never mean "go ahead".
     */
    @Test fun `an unclassifiable outpoint is held back, not swept`() {
        val result = SweepPartition.split(
            utxos = listOf(plainA, assetC),
            carriesAsset = { false },          // would say "safe" — but it was never asked
            classified = { it.txid != "cccc" }, // cccc could not be classified
        )

        assertEquals(listOf(plainA), result.sweepable)
        assertEquals(listOf(assetC), result.unclassified)
        assertTrue("unclassified is not an asset claim", result.assetBearing.isEmpty())
    }

    @Test fun `nothing is swept when classification fails entirely`() {
        val result = SweepPartition.split(
            utxos = listOf(plainA, plainB, assetC),
            carriesAsset = { false },
            classified = { false },
        )

        assertTrue(result.sweepable.isEmpty())
        assertEquals(3, result.unclassified.size)
    }

    // ---- accounting ------------------------------------------------------------------------

    /** Every input must land in exactly one bucket, or something has silently vanished. */
    @Test fun `every utxo lands in exactly one bucket`() {
        val all = listOf(plainA, plainB, assetC, assetD)
        val result = SweepPartition.split(
            utxos = all,
            carriesAsset = { it.txid == "cccc" },
            classified = { it.txid != "dddd" },
        )

        val total = result.sweepable + result.assetBearing + result.unclassified
        assertEquals(all.size, total.size)
        assertEquals(all.toSet(), total.toSet())
    }

    @Test fun `sweepable total counts only what is actually swept`() {
        val result = SweepPartition.split(
            utxos = listOf(plainA, plainB, assetC),
            carriesAsset = { it.txid == "cccc" },
            classified = { true },
        )

        assertEquals(200_000L, result.sweepableSat)
    }

    @Test fun `an empty wallet is not an error`() {
        val result = SweepPartition.split(emptyList(), { false }, { true })
        assertTrue(result.sweepable.isEmpty())
        assertEquals(0L, result.sweepableSat)
    }
}
