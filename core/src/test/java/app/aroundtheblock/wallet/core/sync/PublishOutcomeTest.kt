package app.aroundtheblock.wallet.core.sync

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        for (e in listOf(PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT, PublishOutcome.ECANCELED, 9999)) {
            assertFalse("errno $e must not read as sent", PublishOutcome.of(e).userVisiblySent)
        }
    }

    // ── What the stranded-send sweep does with it ─────────────────────────

    /**
     * A plain DGB send the network refused can never be accepted. The sweep used to log the
     * verdict and carry on: the send stayed in the wallet with its coins spent, and stayed
     * "pending" in the activity list, for good. It is removed, which unlocks its coins, and its
     * record goes with it.
     */
    @Test fun a_terminal_rejection_of_a_plain_DGB_send_removes_it_and_unlocks_its_coins() {
        assertEquals(
            "a send the network refused for good is only logged; its coins stay locked",
            PublishOutcome.Action.REMOVE_AND_UNLOCK,
            PublishOutcome.of(PublishOutcome.EINVAL).actionFor(plainDgb = true),
        )
    }

    /** An asset or DigiDollar send keeps its own handling: removing it would unseat the asset
     *  layer's CONFLICTED accounting, which reads the abandoned attempt from the wallet. */
    @Test fun a_terminal_rejection_of_an_asset_or_DigiDollar_send_is_left_to_its_own_handling() {
        assertEquals(PublishOutcome.Action.KEEP, PublishOutcome.of(PublishOutcome.EINVAL).actionFor(plainDgb = false))
    }

    /** Nothing short of a terminal verdict removes a send: a slow relay must never lose one. */
    @Test fun nothing_but_a_terminal_rejection_removes_a_send() {
        for (e in listOf(0, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT, PublishOutcome.ECANCELED, 9999)) {
            for (plain in listOf(true, false)) {
                assertEquals("errno $e (plain DGB: $plain) must not remove a send", PublishOutcome.Action.KEEP, PublishOutcome.of(e).actionFor(plain))
            }
        }
    }

    /** The wallet removing its own send cancels the publish: no verdict about the transaction. */
    @Test fun a_cancelled_publish_is_not_terminal() {
        val o = PublishOutcome.of(PublishOutcome.ECANCELED)
        assertEquals(PublishOutcome.Kind.NOT_DELIVERED, o.kind)
        assertFalse(o.isTerminal)
    }

    // ── Waiting for a verdict after a send ────────────────────────────────

    /** "Sent" means a peer took it. Until a verdict arrives the answer is none — never "sent". */
    @Test fun no_verdict_within_the_wait_is_no_verdict() = runTest {
        var reads = 0
        val o = PublishOutcome.await(read = { reads++; PublishOutcome.PENDING })
        assertNull("a send no peer answered for was given a verdict", o)
        assertTrue("the wait gave up before a peer could answer (${currentTime} ms)", currentTime >= 20_000L)
        assertTrue("the wait did not poll", reads > 1)
    }

    @Test fun a_peer_taking_the_send_ends_the_wait_as_accepted() = runTest {
        var reads = 0
        val o = PublishOutcome.await(read = { if (++reads < 3) PublishOutcome.PENDING else 0 })
        assertEquals(PublishOutcome.Kind.ACCEPTED, o?.kind)
        assertEquals(3, reads)
    }

    /** The core answers each publish once, so the first verdict is the verdict. */
    @Test fun the_first_verdict_ends_the_wait() = runTest {
        val o = PublishOutcome.await(read = { PublishOutcome.ETIMEDOUT })
        assertEquals(PublishOutcome.Kind.UNCONFIRMED_DELIVERY, o?.kind)
        assertEquals("the wait went on after the verdict", 0L, currentTime)
    }
}
