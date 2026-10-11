package app.aroundtheblock.wallet.core.sync

/**
 * Whether the core's "refused by the network" answer for a send has held long enough to act on.
 *
 * The stranded-send sweep removes a plain DGB send the core calls refused (peers in two network groups
 * rejected it, and no peer has had it), and removing it gives the send's coins back. The core can give
 * that answer moments after the wallet starts, before every connected peer has answered: rejections
 * can arrive before an honest peer has announced the send or asked for it. Acted on then, a send the
 * network accepts is removed, its coins are spent again, and both payments can confirm.
 *
 * So the sweep acts only on a refusal that has held: [observe] answers true only when the send is
 * refused at this observation and was refused at an earlier one at least [holdMs] before it, and at
 * every observation in between. Each send has its own first-seen-refused time; an observation that
 * finds it not refused drops that time, so the refusal has to hold again from the start. The times
 * belong to this session (this instance) and are never persisted: a new session starts every send
 * afresh.
 *
 * [clock] reads milliseconds and should never step back; the service passes awake time
 * (`SystemClock.uptimeMillis`), which a change of the device's clock cannot move and which does not
 * advance while the device sleeps and no peer can be heard. A reading earlier than a send's first
 * sighting never counts as time held.
 */
class RefusalHold(
    private val clock: () -> Long,
    private val holdMs: Long = HOLD_MS,
) {
    init {
        require(holdMs > 0) { "a hold of $holdMs ms acts on a refusal at its first sighting" }
    }

    private val firstSeenRefused = HashMap<String, Long>()

    /** Records one observation of the core's answer for [txid]; true only once the refusal has held for [holdMs]. */
    @Synchronized
    fun observe(txid: String, refused: Boolean): Boolean {
        if (!refused) {
            firstSeenRefused.remove(txid)
            return false
        }
        val now = clock()
        val first = firstSeenRefused.getOrPut(txid) { now }
        return now - first >= holdMs
    }

    /** Drops [txid]'s first-seen-refused time: the send has left the sweep. */
    @Synchronized
    fun forget(txid: String) {
        firstSeenRefused.remove(txid)
    }

    companion object {
        /** Ten minutes: about seven sweeps at the sweep's 90-second cadence. */
        const val HOLD_MS = 10 * 60 * 1000L
    }
}
