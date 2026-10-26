package app.aroundtheblock.wallet.core

import android.util.Log
import app.aroundtheblock.wallet.core.send.DigiDollarSend
import app.aroundtheblock.wallet.core.send.DigiDollarSendState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A DigiDollar send is reviewed before the credential prompt — the transfer is built, what it pays is
 * read, and it is freed — and sent after it: built again and signed by native code only at exactly the
 * fee the confirmation showed. Like a DGB send, it is recorded for retry and the wallet's transactions
 * are persisted before anything is said about it, and it is "Sent" only when a peer took it.
 */
class DigiDollarSendTest {

    private val native = RecordingSpendNative()
    private val outgoing = mockk<OutgoingTxStore>(relaxed = true)
    private val persister = mockk<WalletTxPersister>(relaxed = true)

    private fun builder(): TransactionBuilder = TransactionBuilder(
        CoinSelector(), mockk(relaxed = true), outgoing, persister,
        beforeSpend = { native.events += "detect" },
        native = native,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        // The order of the durable steps, among the native ones.
        every { outgoing.record(any(), any(), any(), any(), any(), any()) } answers { native.events.add("record ${firstArg<String>()}"); Unit }
        every { persister.persist() } answers { native.events.add("persist"); true }
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun reviewed(): PreparedDigiDollar {
        val r = builder().prepareDigiDollar(TD, 4_050L)
        assertTrue("review did not prepare a DigiDollar send: $r", r is DigiDollarPrepareResult.Ready)
        return (r as DigiDollarPrepareResult.Ready).send
    }

    // ── the review: the transfer's own fee, read before the prompt ───────────────────────────────

    @Test fun `review shows the fee of the transfer it built, not the floor`() = runTest {
        // A 0.1003 DGB fee coin: the 30,000 sat left over is below the dust line and goes to the fee.
        native.ddPreview = longArrayOf(10_030_000L, 0L, 0L, 2L)

        val send = reviewed()

        assertEquals("the confirmation's fee is not the transfer's own", 10_030_000L, send.feeSats)
        assertTrue(
            "review did not build the transfer to read its fee: ${native.events}",
            native.events.contains("previewDigiDollar to=$TD cents=4050"),
        )
    }

    @Test fun `review carries what comes back to the wallet`() = runTest {
        native.ddPreview = longArrayOf(10_000_000L, 6_000L, 40_000_000L, 2L)

        val send = reviewed()

        assertEquals(6_000L, send.ddChangeCents)
        assertEquals(40_000_000L, send.dgbChangeSats)
        assertEquals(2L, send.inputCount)
    }

    @Test fun `review that cannot build the transfer prepares nothing`() = runTest {
        native.ddPreview = null   // not enough DGB for the fee, say

        val r = builder().prepareDigiDollar(TD, 4_050L)

        assertTrue("a transfer that cannot be built was prepared: $r", r is DigiDollarPrepareResult.Error)
    }

    @Test fun `review runs detection before it builds`() = runTest {
        builder().prepareDigiDollar(TD, 4_050L)

        val review = native.events.toList()
        assertTrue("review built before detection ran: $review",
            review.indexOf("detect") in 0 until review.indexOfFirst { it.startsWith("previewDigiDollar") })
    }

    // ── the send: the approved fee, then durable, then the verdict ───────────────────────────────

    @Test fun `the send hands native code the fee the confirmation showed`() = runTest {
        val send = reviewed()
        native.events.clear()   // everything below happens after the prompt

        builder().sendDigiDollar(send)

        assertTrue(
            "the transfer was not signed at the approved fee: ${native.events}",
            native.events.contains("sendApprovedDigiDollar to=$TD cents=4050 fee=10030000"),
        )
        assertFalse("the transfer was previewed again after the prompt: ${native.events}",
            native.events.any { it.startsWith("previewDigiDollar") })
    }

    @Test fun `the send is recorded for retry and persisted before its verdict is read`() = runTest {
        val send = reviewed()
        native.events.clear()
        native.verdicts = listOf(-1, 0)

        val state = builder().sendDigiDollar(send)

        val e = native.events.toList()
        val sent = e.indexOfFirst { it.startsWith("sendApprovedDigiDollar") }
        val record = e.indexOf("record ddtxid")
        val persist = e.indexOf("persist")
        val verdict = e.indexOfFirst { it.startsWith("verdict") }
        assertTrue("the send was not recorded for the stranded-send sweep: $e", record >= 0)
        assertTrue("the wallet's transactions were not persisted: $e", persist >= 0)
        assertTrue("recorded before it was sent, or after its verdict was read: $e", record in (sent + 1) until verdict)
        assertTrue("persisted before it was recorded, or after its verdict was read: $e", persist in (record + 1) until verdict)
        assertEquals(DigiDollarSendState.Sent("ddtxid"), state)
    }

    @Test fun `the record keeps the fee signed and leaves the wallet's own reading of the row`() = runTest {
        builder().sendDigiDollar(reviewed())

        verify {
            outgoing.record(txid = "ddtxid", sentSats = 0L, feeSats = 10_030_000L, toAddress = TD,
                isSelfTransfer = false, isDigiDollar = true)
        }
    }

    @Test fun `a send no peer has taken is not reported sent`() = runTest {
        val send = reviewed()
        for (verdict in listOf(-1, 22, 107, 110, 125)) {   // none, EINVAL, ENOTCONN, ETIMEDOUT, ECANCELED
            native.verdicts = listOf(-1, verdict)
            val state = builder().sendDigiDollar(send)
            assertEquals("verdict $verdict was reported as sent", DigiDollarSendState.NotYetRelayed("ddtxid"), state)
        }
    }

    @Test fun `a transfer native code refused to sign is not recorded and not sent`() = runTest {
        val send = reviewed()
        native.ddTxid = null   // the wallet changed while the prompt was up: the rebuilt transfer pays another fee

        val state = builder().sendDigiDollar(send)

        assertEquals(DigiDollarSendState.NotSent(DigiDollarSend.NOT_SENT), state)
        verify(exactly = 0) { outgoing.record(any(), any(), any(), any(), any(), any()) }
        assertFalse("a refused send's verdict was waited for: ${native.events}", native.events.any { it.startsWith("verdict") })
    }

    @Test fun `the send runs detection before it signs`() = runTest {
        val send = reviewed()
        native.events.clear()

        builder().sendDigiDollar(send)

        val e = native.events.toList()
        assertTrue("signed before detection ran: $e",
            e.indexOf("detect") in 0 until e.indexOfFirst { it.startsWith("sendApprovedDigiDollar") })
    }

    companion object {
        const val TD = "TDrecipientDigiDollarAddress"
    }
}
