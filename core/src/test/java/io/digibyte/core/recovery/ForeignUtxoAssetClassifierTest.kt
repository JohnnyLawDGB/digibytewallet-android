package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of asset-aware sweeping that decides whether the asset question can be answered.
 *
 * Every "unknown" here becomes a held-back outpoint in [SweepPartition], so the cases that matter
 * most are the failures: a fetch that returns null, a parser that throws, an empty body. Each has
 * to produce "I don't know" rather than "looks fine" — because the caller acts on the difference,
 * and acting wrongly burns an asset rather than inconveniencing someone.
 */
class ForeignUtxoAssetClassifierTest {

    private fun utxo(txid: String, vout: Int = 0) = UtxoEntry(
        txid = txid, vout = vout, amountSatoshi = 50_000L,
        address = "D7Vx$txid", blockHeight = 24_000_000L, scriptPubKeyHex = "76a91488ac",
    )

    private val plainTx = byteArrayOf(1, 2, 3)
    private val assetTx = byteArrayOf(9, 9, 9)

    private fun classifier(
        fetch: suspend (String) -> ByteArray? = { plainTx },
        isAsset: (ByteArray) -> Boolean = { it.contentEquals(assetTx) },
    ) = ForeignUtxoAssetClassifier(fetch, isAsset)

    // ---- the straightforward answers --------------------------------------------------------

    @Test fun `a plain transaction classifies as no asset`() = runBlocking {
        val u = utxo("aaaa")
        val v = classifier().classify(listOf(u))[u]!!
        assertTrue(v.classified)
        assertFalse(v.carriesAsset)
    }

    @Test fun `an asset transaction classifies as carrying an asset`() = runBlocking {
        val u = utxo("bbbb")
        val v = classifier(fetch = { assetTx }).classify(listOf(u))[u]!!
        assertTrue(v.classified)
        assertTrue(v.carriesAsset)
    }

    /**
     * Coarse on purpose: EVERY output of an asset transaction is held back, including what may be
     * ordinary change. Over-holding costs a manual move; under-holding destroys an asset.
     */
    @Test fun `every output of an asset transaction is held back, change included`() = runBlocking {
        val outs = listOf(utxo("cccc", 0), utxo("cccc", 1), utxo("cccc", 2))
        val v = classifier(fetch = { assetTx }).classify(outs)
        assertTrue("all three outputs must be flagged", v.values.all { it.carriesAsset })
    }

    // ---- the failures, which are the point --------------------------------------------------

    @Test fun `an unfetchable transaction is unknown, not safe`() = runBlocking {
        val u = utxo("dddd")
        val v = classifier(fetch = { null }).classify(listOf(u))[u]!!
        assertFalse("must not claim to have classified it", v.classified)
        assertFalse("unknown is not an asset claim either", v.carriesAsset)
    }

    @Test fun `an empty body is unknown, not safe`() = runBlocking {
        val u = utxo("eeee")
        val v = classifier(fetch = { ByteArray(0) }).classify(listOf(u))[u]!!
        assertFalse(v.classified)
    }

    /** A transaction the native parser cannot read is precisely the one to be careful with. */
    @Test fun `a throwing parser is unknown, not safe`() = runBlocking {
        val u = utxo("ffff")
        val v = classifier(isAsset = { throw IllegalStateException("malformed") })
            .classify(listOf(u))[u]!!
        assertFalse(v.classified)
        assertFalse(v.carriesAsset)
    }

    @Test fun `one unreachable transaction does not strand the rest`() = runBlocking {
        val good = utxo("aaaa")
        val bad = utxo("dead")
        val v = classifier(fetch = { txid -> if (txid == "dead") null else plainTx })
            .classify(listOf(good, bad))

        assertTrue("the reachable one still classifies", v[good]!!.classified)
        assertFalse("the unreachable one is unknown", v[bad]!!.classified)
        assertEquals(2, v.size)
    }

    // ---- efficiency, which is also correctness here -----------------------------------------

    /** Several outputs of one transaction must cost one fetch, not one per output — a wallet
     *  with many outputs from a single tx would otherwise hammer the provider and time out,
     *  turning classifiable outpoints into unknown ones. */
    @Test fun `a parent transaction is fetched once however many outputs it has`() = runBlocking {
        var fetches = 0
        val outs = listOf(utxo("cccc", 0), utxo("cccc", 1), utxo("cccc", 2), utxo("aaaa", 0))
        classifier(fetch = { fetches++; plainTx }).classify(outs)
        assertEquals("expected one fetch per distinct txid", 2, fetches)
    }

    @Test fun `an empty wallet classifies to nothing`() = runBlocking {
        assertTrue(classifier().classify(emptyList()).isEmpty())
    }
}
