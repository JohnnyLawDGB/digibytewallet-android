package app.aroundtheblock.wallet.core.sync

/**
 * Whether the core's "refused by the network" answer for a send has held long enough to act on, on one
 * peer manager.
 *
 * The stranded-send sweep removes a plain DGB send the core calls refused (peers in two network groups
 * rejected it, and no peer has had it), and removing it gives the send's coins back. The core can give
 * that answer moments after a peer manager starts, before every connected peer has answered: rejections
 * can arrive before an honest peer has announced the send or asked for it. Acted on then, a send the
 * network accepts is removed, its coins are spent again, and both payments can confirm.
 *
 * So the sweep acts only on a refusal that has held: [observe] answers true only when the send is
 * refused at this observation and was refused at an earlier one at least [holdMs] before it, on the
 * same peer manager, and at every observation in between. Each send has its own first sighting; an
 * observation that finds it not refused drops it, so the refusal has to hold again from the start.
 *
 * The core keeps its relay evidence per peer manager, and a rebuilt manager starts with none, so a
 * sighting belongs to the manager it was made on. [manager] names that manager (the bridge's
 * generation, which every build bumps; null when it cannot be read) and is read before and after each
 * answer. An answer during which the manager changed, or whose manager is unknown, is no sighting and
 * drops the send's first sighting; a first sighting made on another manager starts again on this one.
 * A hold started on one manager never completes on another.
 *
 * The sightings belong to this session (this instance) and are never persisted. [clock] reads
 * milliseconds and should never step back; the service passes awake time (`SystemClock.uptimeMillis`),
 * which a change of the device's clock cannot move and which does not advance while the device sleeps
 * and no peer can be heard. A reading earlier than a send's first sighting never counts as time held.
 *
 * A mirror of the C core's `BRStrandedRefusalHoldObserve` (`BRStrandedSend.h`), which is the source of
 * truth (iOS imports it; there the caller keeps one `BRStrandedRefusalHold` per key instead of this
 * map); `StrandedRefusalParityTest` binds the two on a device. Change both, or that test fails.
 */
class RefusalHold(
    private val clock: () -> Long,
    private val manager: () -> Long?,
    private val holdMs: Long = HOLD_MS,
) {
    init {
        require(holdMs > 0) { "a hold of $holdMs ms acts on a refusal at its first sighting" }
    }

    private class Sighting(val at: Long, val manager: Long)

    private val firstSeenRefused = HashMap<String, Sighting>()

    /**
     * Reads [refused], the core's answer for [txid], between two readings of the peer manager, records
     * the observation, and returns true only once the refusal has held for [holdMs] on one manager.
     */
    fun observe(txid: String, refused: () -> Boolean): Boolean {
        val before = manager()
        val answer = refused()
        val after = manager()
        return record(txid, refusedOn = if (answer && before != null && before == after) before else null)
    }

    /** [refusedOn]: the manager a refusal was seen on, or null for no sighting. */
    @Synchronized
    private fun record(txid: String, refusedOn: Long?): Boolean {
        if (refusedOn == null) {
            firstSeenRefused.remove(txid)
            return false
        }
        val now = clock()
        val first = firstSeenRefused[txid]
        if (first == null || first.manager != refusedOn) {
            firstSeenRefused[txid] = Sighting(now, refusedOn)
            return false
        }
        return now - first.at >= holdMs
    }

    /** Drops [txid]'s first sighting: the send has left the sweep. */
    @Synchronized
    fun forget(txid: String) {
        firstSeenRefused.remove(txid)
    }

    companion object {
        /** Ten minutes: about seven sweeps at the sweep's 90-second cadence. */
        const val HOLD_MS = 10 * 60 * 1000L
    }
}
