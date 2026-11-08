package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.reconcile.STALE_INPUT_NO_ANSWER
import app.aroundtheblock.wallet.core.reconcile.STALE_INPUT_SPENT
import app.aroundtheblock.wallet.core.reconcile.STALE_INPUT_UNSPENT
import app.aroundtheblock.wallet.core.reconcile.STALE_TX_CONFIRMED
import app.aroundtheblock.wallet.core.reconcile.STALE_TX_IN_MEMPOOL
import app.aroundtheblock.wallet.core.reconcile.STALE_TX_UNAVAILABLE
import app.aroundtheblock.wallet.core.reconcile.STALE_TX_UNKNOWN
import app.aroundtheblock.wallet.core.reconcile.TxNetworkStatus
import app.aroundtheblock.wallet.core.reconcile.parseSpendingAnswer
import app.aroundtheblock.wallet.core.reconcile.parseTxNetworkStatus
import app.aroundtheblock.wallet.core.reconcile.staleInputCode
import app.aroundtheblock.wallet.core.reconcile.staleTxCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A valid own send that never relayed is cleared only once it is a day old and the pinned node shows the
 * network never took it: its own "not found" for the txid AND every input unspent (4.1.3, review R8).
 * That decision is the core's (BRStaleSend.h; its cases, the old StaleSendPredicate ones among them, are
 * in the host KAT stale_send_kat, and StaleSendCoreRuleGateTest pins the wiring). What stays Kotlin is
 * the parse of the node's two answers, pinned here, and the naming of their values as the core's codes.
 */
class StaleSendTest {
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

    @Test fun eachAnswerIsNamedAsTheCoresCode() {
        assertEquals(STALE_TX_CONFIRMED, staleTxCode(TxNetworkStatus.CONFIRMED))
        assertEquals(STALE_TX_IN_MEMPOOL, staleTxCode(TxNetworkStatus.IN_MEMPOOL))
        assertEquals(STALE_TX_UNKNOWN, staleTxCode(TxNetworkStatus.UNKNOWN))
        assertEquals(STALE_TX_UNAVAILABLE, staleTxCode(TxNetworkStatus.UNAVAILABLE))
        assertEquals(STALE_INPUT_UNSPENT, staleInputCode(true))
        assertEquals(STALE_INPUT_SPENT, staleInputCode(false))
        assertEquals(STALE_INPUT_NO_ANSWER, staleInputCode(null))
    }

    @Test fun aKnownTransactionIsConfirmedOrInTheMempool() {
        assertEquals(TxNetworkStatus.CONFIRMED,
            parseTxNetworkStatus(200, """{"txid":"aa","blockhash":"bb","confirmations":3}"""))
        assertEquals(TxNetworkStatus.IN_MEMPOOL, parseTxNetworkStatus(200, """{"txid":"aa"}"""))
        assertEquals(TxNetworkStatus.IN_MEMPOOL, parseTxNetworkStatus(200, """{"txid":"aa","blockhash":"","confirmations":0}"""))
    }
}
