package app.aroundtheblock.wallet.core.send

import app.aroundtheblock.wallet.core.Delivery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DigiDollar send machine: after the credential prompt the transfer is published, its txid is
 * recorded for retry and the wallet's transactions are persisted — before anything is reported about it
 * — and only then is the publish verdict read. It is "Sent" only when a peer took it.
 */
class DigiDollarSendStateTest {

    private val log = mutableListOf<String>()

    private fun machine(
        txid: String? = "dd1",
        delivery: Delivery = Delivery.RELAYED,
    ) = DigiDollarSend<String>(
        publish = { p -> log += "publish $p"; txid },
        record = { id, _ -> log += "record $id" },
        persist = { log += "persist" },
        awaitVerdict = { id -> log += "verdict $id"; delivery },
    )

    private suspend fun DigiDollarSend<String>.runLogged(): DigiDollarSendState =
        run("transfer") { state -> log += "report ${state::class.simpleName}" }

    @Test fun `the send is recorded and persisted before anything is reported about it`() = runTest {
        val state = machine().runLogged()

        assertEquals(DigiDollarSendState.Sent("dd1"), state)
        assertEquals(
            listOf(
                "report Sending",       // "Broadcasting…" while it goes out
                "publish transfer",
                "record dd1",           // saved for retry ...
                "persist",              // ... and on disk ...
                "verdict dd1",          // ... before the verdict is read
                "report Sent",
            ),
            log,
        )
    }

    @Test fun `a verdict that is not a peer taking it is not sent`() = runTest {
        for (delivery in listOf(Delivery.NOT_YET_RELAYED, Delivery.NOT_AWAITED)) {
            log.clear()
            val state = machine(delivery = delivery).runLogged()
            assertEquals("$delivery was reported as sent", DigiDollarSendState.NotYetRelayed("dd1"), state)
            assertTrue("$delivery: not recorded before it was reported: $log",
                log.indexOf("record dd1") in 0 until log.indexOf("report NotYetRelayed"))
        }
    }

    @Test fun `nothing published is nothing recorded`() = runTest {
        val state = machine(txid = null).runLogged()

        assertEquals(DigiDollarSendState.NotSent(DigiDollarSend.NOT_SENT), state)
        assertEquals(listOf("report Sending", "publish transfer", "report NotSent"), log)
    }
}
