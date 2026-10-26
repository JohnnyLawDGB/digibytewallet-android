package app.aroundtheblock.wallet.core.send

import android.util.Log
import app.aroundtheblock.wallet.core.CoinSelector
import app.aroundtheblock.wallet.core.PrepareResult
import app.aroundtheblock.wallet.core.RecordingSpendNative
import app.aroundtheblock.wallet.core.TransactionBuilder
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
 * The fee the confirmation shows is the fee of the transaction that is signed: its inputs minus its
 * outputs, read from the transaction the wallet built for this send at review — not a typical
 * 141-byte transaction at the default rate, which is what every confirmation used to show. A
 * transaction that gathers many coins is several times that size and pays several times that fee.
 *
 * A custom fee is a total the user types. It becomes a rate over THIS transaction's size, and when
 * the transaction must pay more than the total — DigiByte's relay floor of 100 sat/vB, which the
 * builder applies whatever rate it is given — the quote says so: it is shown raised, never as the
 * lower figure with a "below relay minimum" warning about a transaction the builder cannot produce.
 */
class FeeQuoteTest {

    private val native = RecordingSpendNative()

    private fun builder() = TransactionBuilder(
        CoinSelector(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        beforeSpend = {}, native = native,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun quote(fee: FeeRequest): FeeQuote {
        val r = builder().prepareTransaction("dgb1qrecipient", 150_000_000L, fee)
        assertTrue("review did not prepare a send: $r", r is PrepareResult.Ready)
        return (r as PrepareResult.Ready).send.quote
    }

    @Test fun `a six-input transaction is quoted at the fee it pays, not at a typical 141 bytes`() = runTest {
        // Six P2WPKH coins gathered into one send: 750 vB, 75,000 sat at 100 sat/vB.
        native.quotes["tx@100000"] = longArrayOf(75_000, 750)

        val q = quote(FeeRequest.Default)

        assertEquals(
            "the confirmation shows a 141-byte guess instead of the fee of the transaction built for this send",
            75_000L, q.feeSats,
        )
        assertEquals(750L, q.vsize)
        assertFalse("a default-rate fee is never 'raised'", q.raised)
    }

    @Test fun `a custom total below the floor is shown raised to the fee the transaction pays`() = runTest {
        // The transaction is 208 vB. Any rate under 100 sat/vB builds at the floor: 23,200 sat.
        native.quotes["tx@100000"] = longArrayOf(23_200, 208)
        native.quotes["tx@${1_000L * 1000 / 208}"] = longArrayOf(23_200, 208)

        val q = quote(FeeRequest.CustomTotal(1_000))

        assertEquals("a custom fee below the floor is shown as typed instead of as the fee signed", 23_200L, q.feeSats)
        assertEquals(1_000L, q.requestedSats)
        assertTrue("a fee the builder raised is not reported raised", q.raised)
        assertTrue(
            "the custom total was not turned into a rate over this transaction's 208 vB: ${native.events}",
            native.events.contains("create@${1_000L * 1000 / 208}"),
        )
    }

    @Test fun `a custom total the transaction pays exactly is not shown raised`() = runTest {
        native.quotes["tx@100000"] = longArrayOf(23_200, 208)
        native.quotes["tx@${50_000L * 1000 / 208}"] = longArrayOf(50_000, 208)

        val q = quote(FeeRequest.CustomTotal(50_000))

        assertEquals(50_000L, q.feeSats)
        assertFalse(q.raised)
    }

    @Test fun `a custom total becomes a rate over the transaction's own size`() {
        assertEquals("the rate is taken over a typical 141 bytes, not this transaction's 208", 240_384L, FeeQuote.rateForTotal(50_000, 208))
        assertEquals(100_000L, FeeQuote.rateForTotal(20_800, 208))
        assertEquals("no rate is derived from a size of zero", 0L, FeeQuote.rateForTotal(50_000, 0))
        assertEquals("an absurd total saturates instead of wrapping", Long.MAX_VALUE, FeeQuote.rateForTotal(Long.MAX_VALUE, 1))
    }

    @Test fun `a custom fee that is not a positive amount quotes nothing`() {
        for (typed in listOf("0", "0.00000000", "", "abc", "-1")) {
            assertNull("a custom fee of \"$typed\" was quoted at the default rate as if it were a fee", FeeRequest.of(custom = true, typedTotal = typed))
        }
        assertEquals(FeeRequest.CustomTotal(50_000), FeeRequest.of(custom = true, typedTotal = "0.0005"))
        assertEquals(FeeRequest.Default, FeeRequest.of(custom = false, typedTotal = "abc"))
    }

    @Test fun `the quote is the bridge's fee and size, or nothing`() {
        assertEquals(FeeQuote(75_000, 750), FeeQuote.of(longArrayOf(75_000, 750)))
        assertEquals(FeeQuote(23_200, 208, requestedSats = 1_000), FeeQuote.of(longArrayOf(23_200, 208), requestedSats = 1_000))
        assertNull(FeeQuote.of(null))
        assertNull(FeeQuote.of(longArrayOf(75_000)))
        assertNull("an unknown fee is not a fee", FeeQuote.of(longArrayOf(-1, 750)))
        assertNull(FeeQuote.of(longArrayOf(75_000, 0)))
    }
}
