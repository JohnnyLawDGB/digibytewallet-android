package io.digibyte.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What to do with the blob `NativeBridge.serializePeerPenalties()` hands back.
 *
 * The distinction that matters is the one this codebase keeps having to relearn:
 * **"nothing to save" and "can't tell right now" are different answers.** An empty
 * penalty set still serializes to a 4-byte header, so null means the native side could
 * not answer at all — the peer manager was momentarily absent, or the probe threw.
 * Treating that as "empty" would DELETE penalties we had already banked, which is the
 * opposite of what a transient hiccup should cost us.
 *
 * Observed live: a snapshot logged `0 bytes` on a wallet holding 8 healthy peers, i.e.
 * null rather than the expected empty-set header — exactly the ambiguous case.
 */
class PeerPenaltyPersistTest {

    @Test fun an_unavailable_answer_keeps_whatever_was_already_saved() {
        assertEquals(PeerPenaltyPersist.Action.Keep, PeerPenaltyPersist.decide(null))
    }

    /** A genuinely empty set is a definite answer: nothing is penalized, so the stale blob
     *  must go rather than be restored on the next launch. */
    @Test fun an_empty_set_clears_the_stored_blob() {
        val header = byteArrayOf(0, 0, 0, 0) // count = 0
        assertEquals(PeerPenaltyPersist.Action.Clear, PeerPenaltyPersist.decide(header))
    }

    /** A blob too short to even carry the count is malformed, not empty — keep what we have
     *  rather than act on it. */
    @Test fun a_runt_blob_is_treated_as_unavailable() {
        assertEquals(PeerPenaltyPersist.Action.Keep, PeerPenaltyPersist.decide(byteArrayOf(1, 2)))
        assertEquals(PeerPenaltyPersist.Action.Keep, PeerPenaltyPersist.decide(ByteArray(0)))
    }

    @Test fun a_populated_set_is_stored_as_hex() {
        val blob = byteArrayOf(1, 0, 0, 0, 0xAB.toByte(), 0x0F)
        val action = PeerPenaltyPersist.decide(blob)
        assertTrue(action is PeerPenaltyPersist.Action.Store)
        assertEquals("01000000ab0f", (action as PeerPenaltyPersist.Action.Store).hex)
    }

    /** Round-trips through the same hex the prefs hold, so a stored blob comes back byte
     *  for byte — a mangled restore would re-penalize the wrong peers. */
    @Test fun stored_hex_decodes_back_to_the_same_bytes() {
        val blob = byteArrayOf(2, 0, 0, 0, 0x00, 0xFF.toByte(), 0x7F, 0x80.toByte())
        val hex = (PeerPenaltyPersist.decide(blob) as PeerPenaltyPersist.Action.Store).hex
        assertTrue(PeerPenaltyPersist.decodeHex(hex)!!.contentEquals(blob))
    }

    @Test fun malformed_hex_decodes_to_nothing_rather_than_garbage() {
        assertEquals(null, PeerPenaltyPersist.decodeHex("abc"))    // odd length
        assertEquals(null, PeerPenaltyPersist.decodeHex("zz00"))   // not hex
    }
}
