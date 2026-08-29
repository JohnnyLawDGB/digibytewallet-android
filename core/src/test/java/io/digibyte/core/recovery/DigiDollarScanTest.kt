package io.digibyte.core.recovery

import io.digibyte.core.reconcile.DigiDollarHoldingResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assembling what a foreign wallet holds in DigiDollar, from three sources that can each fail
 * independently.
 *
 * Finding the dollars takes a derived address, a balance from the DigiDollar endpoint, and the
 * transaction's outputs to locate the spendable outpoint. Knowing the balance is enough to TELL
 * someone; moving it needs the outpoint. Those are separate outcomes and the difference matters:
 * "you hold $50 and we cannot move it" is a true, useful sentence, and reporting it as either
 * "$50 recovered" or "nothing found" would be a lie in opposite directions.
 *
 * DigiDollar is assembled per WALLET rather than per derivation profile, because it always lives
 * at m/86'/20'/0' no matter which profile the wallet's plain DGB sits on.
 */
class DigiDollarScanTest {

    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val dd = "DD1JAMfBqU9mVnwqZE5SJfJCYT7TKCBnpGeMQQgdDThvbcKbDaTN"
    private val txid = "40a78f1306123354dfcbe3b067a2cc81b916567b418a22fe2c2a9108dae54653"

    private fun addr(chain: Int = 0, index: Int = 0, key: String = xq) =
        DigiDollarAddress(dd, key, chain, index)

    /** The real transaction's outputs: our token output, the sender's change, the marker. */
    private fun outputs(key: String = xq) = listOf(
        DigiDollarHolding.Output(0, 0L, "5120$key"),
        DigiDollarHolding.Output(1, 39_468_332_700L, "001406bc386e98cbfb283e967217f37452a76b93ed26"),
        DigiDollarHolding.Output(2, 0L, "6a02444401020164"),
    )

    private fun scan(
        addresses: List<DigiDollarAddress>,
        holding: (DigiDollarAddress) -> DigiDollarHoldingResult?,
        outs: (String) -> List<DigiDollarHolding.Output>? = { outputs() },
    ) = runBlocking { DigiDollarScan.assemble(addresses, holding, outs) }

    // ---- the ordinary case --------------------------------------------------------------------

    @Test fun `an address with dollars yields a spendable holding`() {
        val r = scan(listOf(addr()), { DigiDollarHoldingResult(100, 1, listOf(txid)) })
        assertEquals(100L, r.cents)
        val h = r.holdings.single()
        assertEquals(txid, h.txid)
        assertEquals("the zero-value P2TR output", 0, h.vout)
        assertEquals("5120$xq", h.scriptPubKeyHex)
        assertEquals("the derivation position needed to sign", 0, h.address.index)
        assertTrue(r.reachable)
    }

    @Test fun `several addresses are summed`() {
        val a0 = addr(index = 0)
        val a1 = addr(index = 1)
        val r = scan(listOf(a0, a1), { DigiDollarHoldingResult(100, 1, listOf(txid)) })
        assertEquals(200L, r.cents)
        assertEquals(2, r.holdings.size)
    }

    @Test fun `an address holding nothing contributes nothing`() {
        val r = scan(listOf(addr()), { DigiDollarHoldingResult(0, 0, emptyList()) })
        assertEquals(0L, r.cents)
        assertTrue(r.holdings.isEmpty())
        assertTrue("a confident zero is still a reachable answer", r.reachable)
    }

    // ---- the honest failures -------------------------------------------------------------------

    /**
     * A lookup that could not be made is not a zero balance. Reading it as one would tell someone
     * their wallet is empty of dollars it may well hold — the same mistake the reconcile path
     * guards against with reachableBackend.
     */
    @Test fun `an unreachable lookup is not a zero balance`() {
        val r = scan(listOf(addr()), { null })
        assertFalse("must not claim a confident answer", r.reachable)
        assertEquals(0L, r.cents)
        assertTrue(r.holdings.isEmpty())
    }

    /**
     * The balance is known but the outpoint is not — the transaction could not be fetched. The
     * dollars exist and cannot be moved, and BOTH halves have to survive into the result.
     */
    @Test fun `a known balance with no locatable outpoint is reported but not spendable`() {
        val r = scan(listOf(addr()), { DigiDollarHoldingResult(500, 1, listOf(txid)) }, outs = { null })
        assertEquals("the balance is still known", 500L, r.cents)
        assertTrue("but nothing can be spent", r.holdings.isEmpty())
        assertTrue("the lookup itself succeeded", r.reachable)
        assertTrue("and the gap is stated", r.unlocatableCents == 500L)
    }

    /** A transaction that pays a different key is not ours, however the endpoint listed it. */
    @Test fun `a transaction paying another key yields no holding`() {
        val other = "1".repeat(64)
        val r = scan(listOf(addr()), { DigiDollarHoldingResult(300, 1, listOf(txid)) },
            outs = { outputs(key = other) })
        assertEquals(300L, r.cents)
        assertTrue(r.holdings.isEmpty())
        assertEquals(300L, r.unlocatableCents)
    }

    /** One address failing must not discard another's dollars. */
    @Test fun `one unreachable address does not lose the others`() {
        val a0 = addr(index = 0)
        val a1 = addr(index = 1)
        val r = scan(listOf(a0, a1), { if (it.index == 0) null else DigiDollarHoldingResult(100, 1, listOf(txid)) })
        assertEquals("the address we could read still counts", 100L, r.cents)
        assertEquals(1, r.holdings.size)
        assertFalse("but the scan is incomplete and says so", r.reachable)
    }

    @Test fun `no addresses is an empty, reachable result`() {
        val r = scan(emptyList(), { null })
        assertEquals(0L, r.cents)
        assertTrue(r.reachable)
    }
}
