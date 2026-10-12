package app.aroundtheblock.wallet.core.send

import app.aroundtheblock.wallet.core.Delivery

/**
 * Where a DigiDollar send stands after its credential prompt, and what the screen may say about it.
 *
 * The same rule as a DGB send ([app.aroundtheblock.wallet.core.TransactionBuilder.sendPrepared]):
 * "Sent" means a peer took the transaction; until then it is not sent.
 */
sealed class DigiDollarSendState {
    /** Signed and published, its verdict not yet read: "Broadcasting…". */
    data object Sending : DigiDollarSendState()

    /** A peer took the transaction (it requested or relayed it, or a block confirmed it): "Sent". */
    data class Sent(val txid: String) : DigiDollarSendState()

    /** Published, and no peer has taken it yet. The wallet holds it, has recorded it for the stranded-send
     *  sweep to publish again, and it is never reported as sent. */
    data class NotYetRelayed(val txid: String) : DigiDollarSendState()

    /** Nothing was signed or published: the wallet changed while the prompt was up (the transfer would
     *  no longer be the approved send at the approved fee), the session is locked, or it cannot be built. */
    data class NotSent(val message: String) : DigiDollarSendState()
}

/**
 * The steps of a DigiDollar send after its credential prompt, in the only order they run.
 *
 *  1. [publish] signs and publishes the approved transfer and returns its txid — or null, and then
 *     nothing was sent and nothing is recorded;
 *  2. [record] records the txid, so the stranded-send sweep publishes it again until it confirms and
 *     the activity list knows it was sent — before anything is reported about it;
 *  3. [persist] writes the wallet's transactions to disk, so a force-stop now loses nothing;
 *  4. only then is the publish verdict read ([awaitVerdict]), and the send is [DigiDollarSendState.Sent]
 *     only when a peer took it; any other verdict, or none, is [DigiDollarSendState.NotYetRelayed].
 *
 * Pure: every step is handed in, so the order is testable without a wallet.
 */
class DigiDollarSend<P>(
    private val publish: suspend (P) -> String?,
    private val record: suspend (txid: String, prepared: P) -> Unit,
    private val persist: suspend () -> Unit,
    private val awaitVerdict: suspend (txid: String) -> Delivery,
) {
    /** Runs the send of [prepared], telling [report] each state it reaches; returns the last. */
    suspend fun run(prepared: P, report: (DigiDollarSendState) -> Unit = {}): DigiDollarSendState {
        report(DigiDollarSendState.Sending)
        val txid = publish(prepared)
            ?: return DigiDollarSendState.NotSent(NOT_SENT).also(report)
        record(txid, prepared)
        persist()
        val outcome = if (awaitVerdict(txid) == Delivery.RELAYED) DigiDollarSendState.Sent(txid)
                      else DigiDollarSendState.NotYetRelayed(txid)
        report(outcome)
        return outcome
    }

    companion object {
        /** What a send that went nowhere reports: the send screen's general failure wording. */
        const val NOT_SENT = "The transaction could not be sent."
    }
}
