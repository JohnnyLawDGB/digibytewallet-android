package io.digibyte.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app should do with the result of a transaction publish.
 *
 * Until v4.0.42 the bridge passed a NULL callback, so nothing could reach this decision at
 * all: a send the network refused was indistinguishable from one it accepted, and the wallet
 * still marked its inputs spent. The callback is now wired; this is the policy that turns an
 * errno into an action.
 *
 * The codes come from BRPeerManagerPublishTx and its cancellation paths:
 *   0          a peer relayed the transaction back to us — genuine acceptance
 *   EINVAL     the transaction is not signed / malformed
 *   ENOTCONN   the peer manager is not connected, or a disconnect cancelled the publish
 *   ETIMEDOUT  it went out and NO peer echoed it back before the publish timeout
 *
 * ETIMEDOUT is the one that matters most here. It is exactly the shape of the live failure
 * that motivated this work: an asset transfer that was published, reported six relays, and
 * existed in no mempool and no block. Peers do not announce a rejection, so silence is the
 * only evidence a transaction was refused.
 */
class PublishOutcomeTest {

    @Test fun a_relayed_transaction_is_accepted() {
        val o = PublishOutcome.of(0)
        assertEquals(PublishOutcome.Kind.ACCEPTED, o.kind)
        assertFalse("an accepted send is not retryable — it is done", o.shouldRetry)
        assertFalse(o.isTerminal)
    }

    /** Malformed can never become valid, so retrying wastes radio forever and the wallet
     *  should stop claiming the inputs are spent. */
    @Test fun a_malformed_transaction_is_terminal_and_not_retryable() {
        val o = PublishOutcome.of(PublishOutcome.EINVAL)
        assertEquals(PublishOutcome.Kind.REJECTED, o.kind)
        assertTrue(o.isTerminal)
        assertFalse(o.shouldRetry)
    }

    /** No network says nothing about the transaction itself — keep it and try again. */
    @Test fun being_offline_is_transient_and_retryable() {
        val o = PublishOutcome.of(PublishOutcome.ENOTCONN)
        assertEquals(PublishOutcome.Kind.NOT_DELIVERED, o.kind)
        assertTrue(o.shouldRetry)
        assertFalse("offline is not proof the transaction is bad", o.isTerminal)
    }

    /**
     * Sent, and nobody echoed it. Peers do not announce rejections, so this is the ONLY
     * evidence of refusal — but it is also what a slow relay looks like, so it must not be
     * treated as proof. Retryable, NOT terminal: the wallet must never destroy a send that
     * might still be propagating.
     */
    @Test fun no_peer_echoed_it_back_is_suspicious_but_never_proof() {
        val o = PublishOutcome.of(PublishOutcome.ETIMEDOUT)
        assertEquals(PublishOutcome.Kind.UNCONFIRMED_DELIVERY, o.kind)
        assertTrue(o.shouldRetry)
        assertFalse("silence is not proof — a slow relay looks identical", o.isTerminal)
    }

    /** An unknown code must fail SAFE: retry, never destroy. A future core could add a code
     *  this build has never seen, and guessing "terminal" would throw away a live send. */
    @Test fun an_unrecognised_error_is_retryable_and_never_terminal() {
        val o = PublishOutcome.of(9999)
        assertEquals(PublishOutcome.Kind.NOT_DELIVERED, o.kind)
        assertTrue(o.shouldRetry)
        assertFalse(o.isTerminal)
    }

    /** The user-facing distinction the old code could not make: "sent" vs "not sent". */
    @Test fun only_acceptance_may_be_described_to_the_user_as_sent() {
        assertTrue(PublishOutcome.of(0).userVisiblySent)
        for (e in listOf(PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT, 9999)) {
            assertFalse("errno $e must not read as sent", PublishOutcome.of(e).userVisiblySent)
        }
    }
}
