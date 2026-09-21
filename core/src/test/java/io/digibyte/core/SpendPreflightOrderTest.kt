package io.digibyte.core

import android.util.Log
import io.digibyte.core.send.SendFailure
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A spend is built from the wallet's plain-coin set only after asset detection has run to
 * completion, and nothing is built when it did not.
 *
 * Asset detection is what holds an asset-carrying output out of the plain-coin set, so the set is
 * right to build from once detection has looked at every transaction the wallet holds. Both
 * spends that leave through [TransactionBuilder] take their coins, or their network fee, from that
 * set: the DGB send and the DigiDollar send.
 *
 * The order is asserted positively — the recorded sequence is compared as a whole — because "the
 * builder was reached" is true of a send that never ran detection at all.
 */
class SpendPreflightOrderTest {

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Records each native step in the order it is reached. */
    private val native = object : SpendNative {
        override fun isValidAddress(address: String): Boolean { events += "address"; return true }
        override fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray? {
            events += "create"; return byteArrayOf(1)
        }
        override fun signTransaction(unsignedTx: ByteArray): ByteArray? { events += "sign"; return byteArrayOf(2) }
        override fun broadcast(signedTx: ByteArray): String? { events += "broadcast"; return "txid" }
        override fun sendDigiDollar(tdAddress: String, cents: Long): String? { events += "sendDigiDollar"; return "ddtxid" }
    }

    private fun builder(beforeSpend: (suspend () -> Unit)? = null): TransactionBuilder {
        val utxoManager = mockk<UtxoManager>(relaxed = true)
        val outgoing = mockk<OutgoingTxStore>(relaxed = true)
        val persister = mockk<WalletTxPersister>(relaxed = true)
        return if (beforeSpend == null) {
            TransactionBuilder(CoinSelector(), utxoManager, outgoing, persister, native = native)
        } else {
            TransactionBuilder(CoinSelector(), utxoManager, outgoing, persister, beforeSpend = beforeSpend, native = native)
        }
    }

    private suspend fun TransactionBuilder.sendDgb(): TxResult =
        sendTransaction("dgb1qrecipient", 150_000_000L, 100_000L, emptyList())

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        SpendPreflight.clear()
    }

    @After fun tearDown() {
        SpendPreflight.clear()
        unmockkStatic(Log::class)
    }

    // ── DGB send ──────────────────────────────────────────────────────────

    @Test fun `a DGB send runs detection to completion before anything is built`() = runTest {
        val b = builder {
            events += "detect:start"
            yield()
            events += "detect:end"
        }

        val result = b.sendDgb()

        assertEquals(
            listOf("address", "detect:start", "detect:end", "create", "sign", "broadcast"),
            events.toList(),
        )
        assertEquals(TxResult.Success("txid"), result)
    }

    @Test fun `a DGB send builds nothing when detection could not finish`() = runTest {
        val b = builder {
            events += "detect"
            throw IOException("the pass did not finish")
        }

        val result = b.sendDgb()

        assertEquals(listOf("address", "detect"), events.toList())
        assertEquals(TxResult.Error(SpendPreflight.NOT_SENT), result)
    }

    @Test fun `a cancelled detection cancels the DGB send and builds nothing`() = runTest {
        val b = builder {
            events += "detect"
            throw CancellationException("the screen went away")
        }

        val thrown = runCatching { b.sendDgb() }.exceptionOrNull()

        assertEquals(listOf("address", "detect"), events.toList())
        assertTrue("a cancellation must reach the caller, got $thrown", thrown is CancellationException)
    }

    /** On a host with no native library the first bridge call ends in a linkage error. That is a
     *  pass that did not finish, like any other. */
    @Test fun `a pass that ends in a linkage error builds nothing`() = runTest {
        val b = builder {
            events += "detect"
            throw UnsatisfiedLinkError("no native library on this host")
        }

        val result = b.sendDgb()

        assertEquals(listOf("address", "detect"), events.toList())
        assertEquals(TxResult.Error(SpendPreflight.NOT_SENT), result)
    }

    /** An error of the virtual machine says nothing about the pass, so it is not turned into a
     *  verdict on it: it reaches the caller as it is, and nothing is built. */
    @Test fun `a virtual machine error reaches the caller and builds nothing`() = runTest {
        val b = builder {
            events += "detect"
            throw InternalError("raised by the test")
        }

        val thrown = runCatching { b.sendDgb() }.exceptionOrNull()

        assertEquals(listOf("address", "detect"), events.toList())
        assertTrue("expected the error itself, got $thrown", thrown is InternalError)
    }

    // ── DigiDollar send (its network fee comes from the plain-coin set) ───

    @Test fun `a DigiDollar send runs detection to completion before anything is built`() = runTest {
        val b = builder {
            events += "detect:start"
            yield()
            events += "detect:end"
        }

        val txid = b.sendDigiDollar("TDrecipient", 4_050L)

        assertEquals(listOf("detect:start", "detect:end", "sendDigiDollar"), events.toList())
        assertEquals("ddtxid", txid)
    }

    @Test fun `a DigiDollar send builds nothing when detection could not finish`() = runTest {
        val b = builder {
            events += "detect"
            throw IOException("the pass did not finish")
        }

        val txid = b.sendDigiDollar("TDrecipient", 4_050L)

        assertEquals(listOf("detect"), events.toList())
        assertNull(txid)
    }

    @Test fun `a cancelled detection cancels the DigiDollar send and builds nothing`() = runTest {
        val b = builder {
            events += "detect"
            throw CancellationException("the screen went away")
        }

        val thrown = runCatching { b.sendDigiDollar("TDrecipient", 4_050L) }.exceptionOrNull()

        assertEquals(listOf("detect"), events.toList())
        assertTrue("a cancellation must reach the caller, got $thrown", thrown is CancellationException)
    }

    // ── The production default ────────────────────────────────────────────

    @Test fun `a builder given no seam runs the installed pass first`() = runTest {
        SpendPreflight.install { events += "installed-pass" }

        val result = builder().sendDgb()

        assertEquals(listOf("address", "installed-pass", "create", "sign", "broadcast"), events.toList())
        assertEquals(TxResult.Success("txid"), result)
    }

    @Test fun `with no pass installed no spend is built`() = runTest {
        val b = builder()

        assertEquals(TxResult.Error(SpendPreflight.NOT_SENT), b.sendDgb())
        assertNull(b.sendDigiDollar("TDrecipient", 4_050L))
        assertEquals(listOf("address"), events.toList())
    }

    // ── What the user is told ─────────────────────────────────────────────

    /** The refusal adds no text of its own: it is the send screen's existing general failure. */
    @Test fun `a refused spend reads as the send screen's general failure`() {
        val shown = SendFailure.of(SpendPreflight.NOT_SENT)
        val general = SendFailure.of(null)

        assertEquals(SendFailure.Kind.UNKNOWN, shown.kind)
        assertEquals("send_fail_unknown", shown.guidanceKey)
        assertTrue("trying again can help once the pass finishes", shown.retryable)
        assertEquals(general.rawReason, shown.rawReason)
    }
}
