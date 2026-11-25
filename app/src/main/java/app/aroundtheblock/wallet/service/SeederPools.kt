package app.aroundtheblock.wallet.service

/**
 * How a fresh seeder answer joins a cached pool that merges answers over time (SEEDER-PEER-CAP).
 *
 * The numbers are the core's (BRSeederPeers.h: BRSeederPeersAccepted, BRSeederPoolDrop), handed in
 * as functions so this stays plain Kotlin that a unit test can run without the native library.
 * Until 2026-11 the pool only grew: every address not seen before was appended and written back,
 * and nothing was ever dropped, so whoever answers as the seeder could grow it without limit.
 */
internal object SeederPools {

    /**
     * [pool] with the entries of [fresh] it does not hold yet appended (same [key] = same entry;
     * an entry repeated inside [fresh] is added once), after dropping as many of the pool's OLDEST
     * entries (its front) as [poolDrop] says are needed to make room.
     *
     * [poolDrop] is `NativeBridge::seederPoolDrop`: (pooled, adding) -> how many to drop, at most
     * `pooled`. An answer beyond what it can make room for is cut to fit the same way: by never
     * adding more than [maxAdd], which is `NativeBridge.seederPeersAccepted(fresh.size)`.
     */
    fun <T> merged(
        pool: List<T>,
        fresh: List<T>,
        maxAdd: Int,
        key: (T) -> Any,
        poolDrop: (pooled: Int, adding: Int) -> Int,
    ): List<T> {
        val seen = pool.mapTo(HashSet()) { key(it) }
        val unseen = ArrayList<T>()
        for (p in fresh) {
            if (unseen.size >= maxAdd.coerceAtLeast(0)) break
            if (seen.add(key(p))) unseen.add(p)
        }
        val drop = poolDrop(pool.size, unseen.size).coerceIn(0, pool.size)
        return pool.drop(drop) + unseen
    }
}
