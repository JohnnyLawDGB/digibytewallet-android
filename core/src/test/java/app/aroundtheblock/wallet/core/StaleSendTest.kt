package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.reconcile.TxNetworkStatus
import app.aroundtheblock.wallet.core.reconcile.parseSpendingAnswer
import app.aroundtheblock.wallet.core.reconcile.parseTxNetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A valid own send that never relayed is cleared only once it is a day old and the pinned node shows the
 * network never took it: its own "not found" for the txid AND every input unspent (4.1.3, review R8).
 * clearStuckSends itself calls NativeBridge (JNI), so the pure halves are pinned here: the predicate and
 * the two answer parses.
 */
class StaleSendTest {
    private val now = 1_800_000_000L
    private val day = StaleSendPredicate.MIN_AGE_S

    @Test fun aDayOldSendTheNetworkNeverTookIsStale() {
        assertTrue(StaleSendPredicate.isStale(now - day, now, neverRelayed = true))
        assertFalse(StaleSendPredicate.isStale(now - 10 * day, now, neverRelayed = false))
    }

    @Test fun aYoungSendOrOneWithNoTimestampIsNeverStale() {
        assertFalse(StaleSendPredicate.isStale(now - day + 1, now, neverRelayed = true))
        assertFalse(StaleSendPredicate.isStale(0L, now, neverRelayed = true))
        assertFalse(StaleSendPredicate.oldEnough(0L, now))
    }

    @Test fun onlyAnExplicitUnspentAnswerCountsAsUnspent() {
        assertEquals(true, parseSpendingAnswer(200, """{"spent":false}"""))
        assertEquals(false, parseSpendingAnswer(200, """{"spent":true,"spending_txid":null}"""))
        assertEquals(null, parseSpendingAnswer(200, """{"spent":"false"}"""))
        assertEquals(null, parseSpendingAnswer(200, """{}"""))
        assertEquals(null, parseSpendingAnswer(404, """{"error":"Output not found"}"""))
        assertEquals(null, parseSpendingAnswer(500, null))
    }

    @Test fun onlyTheExplorersOwn404IsUnknown() {
        assertEquals(TxNetworkStatus.UNKNOWN, parseTxNetworkStatus(404, """{"error":"Transaction not found"}"""))
        // a 404 from anything else -- a misrouted endpoint, a proxy, a custom node without the route -- proves nothing
        assertEquals(TxNetworkStatus.UNAVAILABLE, parseTxNetworkStatus(404, "<html>Not Found</html>"))
        assertEquals(TxNetworkStatus.UNAVAILABLE, parseTxNetworkStatus(404, """{"error":"Output not found"}"""))
        assertEquals(TxNetworkStatus.UNAVAILABLE, parseTxNetworkStatus(404, null))
        assertEquals(TxNetworkStatus.UNAVAILABLE, parseTxNetworkStatus(500, """{"error":"Failed to fetch transaction"}"""))
        assertEquals(TxNetworkStatus.UNAVAILABLE, parseTxNetworkStatus(200, "not json"))
    }

    @Test fun aKnownTransactionIsConfirmedOrInTheMempool() {
        assertEquals(TxNetworkStatus.CONFIRMED,
            parseTxNetworkStatus(200, """{"txid":"aa","blockhash":"bb","confirmations":3}"""))
        assertEquals(TxNetworkStatus.IN_MEMPOOL, parseTxNetworkStatus(200, """{"txid":"aa"}"""))
        assertEquals(TxNetworkStatus.IN_MEMPOOL, parseTxNetworkStatus(200, """{"txid":"aa","blockhash":"","confirmations":0}"""))
    }
}
