package app.aroundtheblock.wallet.core.ipfs

/**
 * Which gateway answered, and when, that it does not have a CID (IPFS-MISS-BACKOFF).
 *
 * A gateway that serves pinned files only answers 404 for a CID it has not pinned, and it gives the
 * same answer a second later. Every refresh path in the wallet goes through [IpfsClient], so the
 * answer is remembered here, once, and the gateway is left alone for that CID until [ttlMs] has
 * passed.
 *
 * A remembered miss is never a permanent "no art":
 *  - it expires after [ttlMs], and the next fetch asks that gateway again;
 *  - it lives in memory only, so a new process starts with none;
 *  - a clock that went backwards (the user changed the time) drops the entry instead of extending it;
 *  - it is per gateway: the other gateways are still asked for the same CID.
 *
 * At most [maxEntries] answers are held; the oldest goes first. A dropped entry costs one request.
 */
internal class GatewayMissMemory(
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long,
) {
    /** (gateway, cid) -> the clock reading when the gateway said so. Insertion-ordered: oldest first. */
    private val saidAt = LinkedHashMap<String, Long>()

    /** True while [gateway]'s "not here" for [cid] is younger than [ttlMs]. */
    @Synchronized
    fun isRemembered(gateway: String, cid: String): Boolean {
        val key = key(gateway, cid)
        val at = saidAt[key] ?: return false
        val age = clock() - at
        if (age in 0 until ttlMs) return true
        saidAt.remove(key)
        return false
    }

    @Synchronized
    fun remember(gateway: String, cid: String) {
        val key = key(gateway, cid)
        saidAt.remove(key) // re-inserted at the young end
        saidAt[key] = clock()
        while (saidAt.size > maxEntries) {
            val oldest = saidAt.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun size(): Int = saidAt.size

    // A CID is alphanumeric and a gateway is a URL template: neither holds a newline.
    private fun key(gateway: String, cid: String) = "$gateway\n$cid"
}
