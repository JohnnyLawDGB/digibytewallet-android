package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.sync.PeerPenaltyPersist
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [PeerPenaltyPersist] to `BRPeerPenaltyPersist.h`.
 *
 * ## The distinction under test
 *
 * "Nothing to save" and "can't tell right now" are different answers. An empty penalty set
 * still serializes to a 4-byte count header, so a null blob means the native probe could not
 * answer — the peer manager was momentarily absent, or the probe threw. Treating that as
 * "empty" deletes penalties already banked, and a wallet that comes back up having forgotten
 * which peers refused it can skip the entire canon fleet it must reach first. That is the
 * on-ramp to 0 peers → watchdog → recreate → floor-to-birth.
 *
 * ## Why `headerBytesMatchesTheWireFormat` is the important one
 *
 * `PeerPenaltyPersist.HEADER_BYTES = 4` is not a policy choice. It is a fact about what
 * `BRPeerPenaltySerialize` emits, already stated in C as `BR_PEER_PENALTY_HEADER_BYTES`
 * (`BRPeerPenalty.h:73`). The Kotlin copy is the third statement of the same number — the
 * compiler catches a duplicate in C, and nothing catches this one. So it gets a test.
 */
@RunWith(AndroidJUnit4::class)
class PeerPenaltyPersistParityTest {

    private companion object {
        const val KEEP = 0
        const val CLEAR = 1
        const val STORE = 2
    }

    private fun kotlinCode(blob: ByteArray?): Int = when (PeerPenaltyPersist.decide(blob)) {
        is PeerPenaltyPersist.Action.Keep -> KEEP
        is PeerPenaltyPersist.Action.Clear -> CLEAR
        is PeerPenaltyPersist.Action.Store -> STORE
    }

    /** The assumption, pinned to the serializer rather than to a comment. */
    @Test
    fun headerBytesMatchesTheWireFormat() {
        assertEquals(
            "PeerPenaltyPersist.HEADER_BYTES drifted from BR_PEER_PENALTY_HEADER_BYTES. It is " +
                "not a free parameter — it is what BRPeerPenaltySerialize emits for an empty set.",
            NativeBridge.peerPenaltyHeaderBytes(),
            PeerPenaltyPersist.HEADER_BYTES,
        )
    }

    /** Sanity on the other half of the wire format, so a stride change is visible here too. */
    @Test
    fun entryStrideIsWhatTheFormatDeclares() {
        assertEquals(
            "BR_PEER_PENALTY_ENTRY_BYTES should be 16 addr + 2 port + 8 time",
            26,
            NativeBridge.peerPenaltyEntryBytes(),
        )
    }

    /** Both sides must treat an unanswered probe as Keep. */
    @Test
    fun nullBlobIsKeepOnBothSides() {
        assertEquals("Kotlin must Keep a null blob", KEEP, kotlinCode(null))
        assertEquals("C must Keep a null blob", KEEP, NativeBridge.peerPenaltyDecide(null))
    }

    /** And a blob too short to hold its own count header is also unknown, not empty. */
    @Test
    fun shortBlobIsKeepOnBothSides() {
        (0 until PeerPenaltyPersist.HEADER_BYTES).forEach { n ->
            val blob = ByteArray(n)
            assertEquals("Kotlin should Keep a $n-byte blob", KEEP, kotlinCode(blob))
            assertEquals("C should Keep a $n-byte blob", KEEP, NativeBridge.peerPenaltyDecide(blob))
        }
    }

    /** Exactly the header is a genuinely empty set. */
    @Test
    fun headerSizedBlobIsClearOnBothSides() {
        val blob = ByteArray(PeerPenaltyPersist.HEADER_BYTES)
        assertEquals(CLEAR, kotlinCode(blob))
        assertEquals(CLEAR, NativeBridge.peerPenaltyDecide(blob))
    }

    /** Anything longer carries entries. */
    @Test
    fun longerBlobIsStoreOnBothSides() {
        listOf(5, 30, 82, 512).forEach { n ->
            val blob = ByteArray(n)
            assertEquals("Kotlin should Store a $n-byte blob", STORE, kotlinCode(blob))
            assertEquals("C should Store a $n-byte blob", STORE, NativeBridge.peerPenaltyDecide(blob))
        }
    }

    /** The length-only accessor must agree with the buffer one across the boundary. */
    @Test
    fun lengthOnlyAccessorAgrees() {
        listOf(0, 1, 3, 4, 5, 26, 30, 82, 512).forEach { n ->
            assertEquals(
                "C's length-only decision disagrees with its own buffer decision at $n",
                NativeBridge.peerPenaltyDecide(ByteArray(n)),
                NativeBridge.peerPenaltyDecideLength(n),
            )
        }
        assertEquals(
            "a negative length must be Keep, not a wild read",
            KEEP,
            NativeBridge.peerPenaltyDecideLength(-1),
        )
    }
}
