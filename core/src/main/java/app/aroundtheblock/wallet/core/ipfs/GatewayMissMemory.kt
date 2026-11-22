package app.aroundtheblock.wallet.core.ipfs

/**
 * Which gateway answered, and when, that it does not have a CID (IPFS-MISS-BACKOFF).
 *
 * A gateway that serves pinned files only answers 404 for a CID it has not pinned, and it gives the
 * same answer a second later. Every refresh path in the wallet goes through [IpfsClient], so the
 * answer is remembered here, once, and the gateway is left alone for that CID for a while.
 *
 * How long grows with each further miss: [firstMs] after the first, twice that after the next, up
 * to [maxMs]. The first 404 for a just-issued asset is the normal case (its document is pinned
 * after the wallet has seen the issuance), so the first wait is short; a CID that stays missing is
 * asked less and less often.
 *
 * A remembered miss is never a permanent "no art":
 *  - it expires, after [maxMs] at the longest, and the next fetch asks that gateway again;
 *  - it lives in memory only, so a new process starts with none;
 *  - a clock that went backwards (the user changed the time) drops the entry instead of extending it;
 *  - it is per gateway: the other gateways are still asked for the same CID;
 *  - a gateway that serves the CID is [forgotten][forget], so a later miss starts at [firstMs] again.
 *
 * The same memory holds two more answers of the same kind (ASSET-MEDIA-FIXES), so that there is
 * one mechanism and one bound: a gateway whose file was refused for size, filed exactly like a
 * miss, and "this CID's bytes are not a picture", filed under a name no gateway has and with a
 * wait of its own (see [remember]).
 *
 * At most [maxEntries] answers are held; the oldest goes first. A dropped entry costs one request
 * (and its next wait is [firstMs] again).
 */
internal class GatewayMissMemory(
    private val firstMs: Long,
    private val maxMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long,
) {
    /** When the gateway said so, and how long it is left alone from then. */
    private class Miss(val at: Long, val waitMs: Long)

    /** (gateway, cid) -> the last miss. Insertion-ordered: oldest first. */
    private val misses = LinkedHashMap<String, Miss>()

    /** True while [gateway]'s "not here" for [cid] is younger than its wait. */
    @Synchronized
    fun isRemembered(gateway: String, cid: String): Boolean {
        val key = key(gateway, cid)
        val miss = misses[key] ?: return false
        val age = clock() - miss.at
        if (age in 0 until miss.waitMs) return true
        // Over. The entry stays only as "how long to wait after the next miss" -- unless the clock
        // went backwards, or nobody has asked for so long that the count starts again.
        if (age < 0 || age - miss.waitMs >= maxMs) misses.remove(key)
        return false
    }

    /**
     * [gateway] just said it does not have [cid].
     *
     * [fixedWaitMs], when given, is the wait itself, with no growth and no [maxMs] ceiling: for an
     * answer about the CID's bytes rather than about where they are (ASSET-MEDIA-FIXES, "this file
     * is not a picture"), which does not become less true with time the way "not pinned here" does.
     */
    @Synchronized
    fun remember(gateway: String, cid: String, fixedWaitMs: Long? = null) {
        val key = key(gateway, cid)
        val now = clock()
        val last = misses[key]
        // Two fetches that raced report one answer twice: it is one miss, and the wait is not doubled.
        if (last != null && now - last.at in 0 until last.waitMs) return
        val waitMs = when {
            fixedWaitMs != null -> fixedWaitMs
            last == null || now < last.at -> firstMs
            else -> (last.waitMs * 2).coerceIn(firstMs, maxMs)
        }
        misses.remove(key) // re-inserted at the young end
        misses[key] = Miss(now, if (fixedWaitMs != null) waitMs else waitMs.coerceAtMost(maxMs))
        while (misses.size > maxEntries) {
            val oldest = misses.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** [gateway] served [cid]: whatever it said before is over. */
    @Synchronized
    fun forget(gateway: String, cid: String) {
        misses.remove(key(gateway, cid))
    }

    @Synchronized
    fun size(): Int = misses.size

    // A CID is alphanumeric and a gateway is a URL template: neither holds a newline.
    private fun key(gateway: String, cid: String) = "$gateway\n$cid"
}
