package app.aroundtheblock.wallet.core

import android.util.Log
import app.aroundtheblock.wallet.core.send.FeeRequest
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
 * A DGB send is built and quoted at review, before the credential prompt; after the prompt those
 * same bytes are signed — by the signer that checks them against the approved fee — and broadcast.
 * Nothing is built between the prompt and the signature, the record keeps the fee that was signed,
 * and the send is reported sent only when a peer has taken it.
 */
class PreparedSendTest {

    private val native = RecordingSpendNative()
    private val outgoing = mockk<OutgoingTxStore>(relaxed = true)

    private fun builder(): TransactionBuilder = TransactionBuilder(
        CoinSelector(), mockk(relaxed = true), outgoing, mockk(relaxed = true),
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
        native.quotes["tx@100000"] = longArrayOf(23_200, 208)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun prepared(): PreparedSend {
        val r = builder().prepareTransaction(ADDR, 150_000_000L, FeeRequest.Default)
        assertTrue("review did not prepare a send: $r", r is PrepareResult.Ready)
        return (r as PrepareResult.Ready).send
    }

    @Test fun `the bytes built at review are the bytes signed, at the approved fee`() = runTest {
        val send = prepared()
        native.events.clear()   // everything below happens after the prompt

        val result = builder().sendPrepared(send)

        assertFalse(
            "the send was built again after the prompt, so what was signed is not what was approved: ${native.events}",
            native.events.any { it.startsWith("create@") },
        )
        assertTrue(
            "the prepared bytes did not reach the checked signer with the approved payment and fee: ${native.events}",
            native.events.contains("sign tx@100000 to=$ADDR amount=150000000 fee=23200"),
        )
        assertFalse("a signer that does not check the fee was used: ${native.events}", native.events.any { it.startsWith("sign-unchecked") })
        assertTrue("not sent: $result", result is TxResult.Success)
    }

    @Test fun `the pre-spend pass runs before the review build and again before signing`() = runTest {
        val b = builder()
        val r = b.prepareTransaction(ADDR, 150_000_000L, FeeRequest.Default) as PrepareResult.Ready
        val review = native.events.toList()
        assertTrue("review built before asset detection ran: $review",
            review.indexOf("detect") in 0 until review.indexOf("create@100000"))
        native.events.clear()

        b.sendPrepared(r.send)

        val send = native.events.toList()
        val sign = send.indexOfFirst { it.startsWith("sign ") }
        assertTrue("the prepared send was signed before asset detection ran again: $send",
            send.indexOf("detect") in 0 until sign)
    }

    @Test fun `bytes the checked signer refuses are not broadcast`() = runTest {
        val send = prepared()
        native.signerSigns = false

        val result = builder().sendPrepared(send)

        assertFalse("bytes the signer refused were broadcast: ${native.events}", native.events.any { it.startsWith("broadcast") })
        assertTrue("a refused signature is not reported as a failure: $result", result is TxResult.Error)
    }

    @Test fun `the activity record keeps the fee that was signed`() = runTest {
        builder().sendPrepared(prepared())

        verify { outgoing.record(txid = "txid", sentSats = 150_000_000L, feeSats = 23_200L, toAddress = ADDR) }
    }

    @Test fun `a send no peer has taken is not reported sent`() = runTest {
        native.verdicts = listOf(-1)   // no verdict, through the whole wait

        val result = builder().sendPrepared(prepared())

        assertEquals(
            "a send was reported sent although no peer took it",
            TxResult.Success("txid", Delivery.NOT_YET_RELAYED), result,
        )
    }

    @Test fun `a send a peer refused or never answered is not reported sent`() = runTest {
        for (verdict in listOf(22, 107, 110, 125)) {   // EINVAL, ENOTCONN, ETIMEDOUT, ECANCELED
            native.verdicts = listOf(-1, verdict)
            val result = builder().sendPrepared(prepared())
            assertEquals("verdict $verdict was reported as sent", TxResult.Success("txid", Delivery.NOT_YET_RELAYED), result)
        }
    }

    @Test fun `a send a peer has taken is reported sent`() = runTest {
        native.verdicts = listOf(-1, -1, 0)

        val result = builder().sendPrepared(prepared())

        assertEquals(TxResult.Success("txid", Delivery.RELAYED), result)
    }

    @Test fun `a payment below the dust line of its address type is refused before anything is built`() = runTest {
        native.scriptFor = { P2PKH }   // a legacy D... address: Core's line is 5,460 sat

        val r = builder().prepareTransaction(ADDR, 5_459L, FeeRequest.Default)

        assertEquals("5,459 sat to a legacy address was not refused below the 5,460 sat line", PrepareResult.BelowDust(5_460L), r)
        assertFalse("a dust payment was built: ${native.events}", native.events.any { it.startsWith("create@") })
    }

    @Test fun `a payment at the dust line of its address type is built`() = runTest {
        native.scriptFor = { P2PKH }
        native.quotes["tx@100000"] = longArrayOf(22_600, 226)
        native.outputsOf = { arrayOf("0|5460|$P2PKH_HEX", "1|149000000|${RecordingSpendNative.P2WPKH_HEX}") }

        val r = builder().prepareTransaction(ADDR, 5_460L, FeeRequest.Default)

        assertTrue("a payment at the line was refused: $r", r is PrepareResult.Ready)
    }

    @Test fun `review refuses a built transaction with an output below the line`() = runTest {
        // The destination passes; an output of the built bytes does not (the builder folds dust
        // change into the fee, so this is a guard on what is signed, not a path seen today).
        native.outputsOf = { arrayOf("0|150000000|${RecordingSpendNative.P2WPKH_HEX}", "1|2939|${RecordingSpendNative.P2WPKH_HEX}") }

        val r = builder().prepareTransaction(ADDR, 150_000_000L, FeeRequest.Default)

        assertEquals(PrepareResult.BelowDust(2_940L), r)
    }

    companion object {
        const val ADDR = "dgb1qrecipient"
        /** A legacy (P2PKH) output script: OP_DUP OP_HASH160, a 20-byte push, OP_EQUALVERIFY OP_CHECKSIG. */
        val P2PKH_HEX: String = "76a914" + "22".repeat(20) + "88ac"
        val P2PKH: ByteArray = RecordingSpendNative.hex(P2PKH_HEX)
    }
}
