package io.digibyte.core.sync

/**
 * Persistence policy for the peer re-dial penalty set.
 *
 * The whole point of this object is one distinction: **"nothing to save" and "can't tell
 * right now" are different answers.** An empty penalty set still serializes to a 4-byte
 * count header, so a null blob means the native side could not answer — the peer manager
 * was momentarily absent, or the probe threw. Treating that as "empty" would delete
 * penalties already banked, which is the opposite of what a transient hiccup should cost.
 *
 * The same "unknown is not empty" rule guards the asset spent-state reconcile and the
 * owned-script cache; this is the peer-side instance of it.
 */
object PeerPenaltyPersist {

    /** Bytes of the count header that even an empty set carries (see BRPeerPenalty.h). */
    const val HEADER_BYTES = 4

    sealed interface Action {
        /** Can't tell — leave any stored blob exactly as it is. */
        data object Keep : Action

        /** Definitely empty — drop the stored blob so it isn't restored next launch. */
        data object Clear : Action

        /** Live entries to persist, already hex-encoded for prefs. */
        data class Store(val hex: String) : Action
    }

    fun decide(blob: ByteArray?): Action = when {
        blob == null || blob.size < HEADER_BYTES -> Action.Keep
        blob.size == HEADER_BYTES -> Action.Clear
        else -> Action.Store(blob.joinToString("") { "%02x".format(it) })
    }

    /** Decode a stored blob. Null on anything malformed — this comes off disk, and a
     *  half-parsed blob would restore penalties against the wrong peers. */
    fun decodeHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
