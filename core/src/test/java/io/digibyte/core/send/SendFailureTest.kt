package io.digibyte.core.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a user is told when a send does not go out.
 *
 * ## Observed on a real device, 2026-08-28
 *
 * A send that failed reported itself as one line of small red text at the bottom of the form,
 * below the button, with nothing to dismiss and nothing written anywhere. The success case is a
 * full-screen overlay that blocks the app until acknowledged.
 *
 * So the two outcomes had opposite volumes: the good one was impossible to miss and the bad one
 * was easy to. Someone sending several payments quickly reported three that "didn't get recorded
 * anywhere — no stuck, no failure", and that is exactly what this looks like from the outside.
 * Reproduced on the Note 8: `BRWalletCreateTransaction returned NULL`, the confirm dialog closed,
 * the form returned to its previous state, and the entire report was the words "Insufficient
 * balance" below the fold.
 *
 * ## Why the message needs a model and not just a string
 *
 * "Insufficient balance" is true and useless when the balance is visibly sufficient — the amount
 * left nothing for the fee. A failure the user cannot act on reads as a malfunction, and a wallet
 * that looks like it malfunctioned while holding someone's money is the thing to avoid.
 *
 * So each failure carries what happened, whether trying again could help, and what to change.
 */
class SendFailureTest {

    @Test fun `insufficient balance explains the fee, which is the part that surprises people`() {
        val f = SendFailure.of("Insufficient balance")
        assertEquals(SendFailure.Kind.INSUFFICIENT, f.kind)
        assertTrue(
            "the fee is why a 'sufficient' balance is not enough — say so: ${f.guidanceKey}",
            f.guidanceKey.contains("fee"),
        )
        assertTrue("retrying the same amount cannot help", !f.retryable)
    }

    @Test fun `a broadcast failure is worth retrying — the transaction was built and signed`() {
        val f = SendFailure.of("Failed to broadcast transaction")
        assertEquals(SendFailure.Kind.BROADCAST, f.kind)
        assertTrue("peers come and go; the same send may work in a moment", f.retryable)
    }

    @Test fun `a signing failure is not something the user can fix by retrying`() {
        val f = SendFailure.of("Failed to sign transaction")
        assertEquals(SendFailure.Kind.SIGNING, f.kind)
        assertTrue(!f.retryable)
    }

    @Test fun `an address problem points at the address`() {
        val f = SendFailure.of("Invalid DigiByte address")
        assertEquals(SendFailure.Kind.ADDRESS, f.kind)
    }

    @Test fun `an amount problem points at the amount`() {
        val f = SendFailure.of("Amount must be positive")
        assertEquals(SendFailure.Kind.AMOUNT, f.kind)
    }

    /**
     * An unrecognised reason must still produce something a person can read, and must NOT be
     * discarded — the raw text is the only clue anyone will have about a failure nobody
     * anticipated.
     */
    @Test fun `an unknown reason is still reported, with its raw text preserved`() {
        val f = SendFailure.of("some new native error nobody has seen")
        assertEquals(SendFailure.Kind.UNKNOWN, f.kind)
        assertEquals("some new native error nobody has seen", f.rawReason)
        assertTrue("an unknown failure may be transient", f.retryable)
    }

    @Test fun `a null or blank reason does not produce an empty screen`() {
        listOf(null, "", "   ").forEach { r ->
            val f = SendFailure.of(r)
            assertEquals(SendFailure.Kind.UNKNOWN, f.kind)
            assertTrue("there must be something to show for '$r'", f.rawReason.isNotBlank())
        }
    }

    /** Every kind maps to its own guidance, or the model is not earning its keep. */
    @Test fun `each kind gives distinct guidance`() {
        val keys = SendFailure.Kind.entries.map { kind ->
            SendFailure.of(
                when (kind) {
                    SendFailure.Kind.INSUFFICIENT -> "Insufficient balance"
                    SendFailure.Kind.BROADCAST -> "Failed to broadcast transaction"
                    SendFailure.Kind.SIGNING -> "Failed to sign transaction"
                    SendFailure.Kind.ADDRESS -> "Invalid DigiByte address"
                    SendFailure.Kind.AMOUNT -> "Amount must be positive"
                    SendFailure.Kind.UNKNOWN -> "???"
                }
            ).guidanceKey
        }
        assertEquals("guidance must differ per kind", keys.size, keys.toSet().size)
    }

    /** Matching is on content, not exact equality, so a reworded native string still maps. */
    @Test fun `matching survives rewording`() {
        assertEquals(SendFailure.Kind.INSUFFICIENT, SendFailure.of("insufficient balance for fee").kind)
        assertEquals(SendFailure.Kind.BROADCAST, SendFailure.of("FAILED TO BROADCAST").kind)
        assertNotEquals(SendFailure.Kind.UNKNOWN, SendFailure.of("Insufficient Balance").kind)
    }
}
