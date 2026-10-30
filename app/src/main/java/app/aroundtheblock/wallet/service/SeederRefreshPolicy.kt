package app.aroundtheblock.wallet.service

/**
 * When the cached seeder lists (`api.digiscope.me/api/peers`) are refreshed. The refresh always runs
 * in the background, never on a path that ends in `startSync()`: the start paths inject what is
 * cached, start, and leave the fetch to `SyncService.requestSeederRefresh`, as the iOS app does
 * (`WalletCore.addSeederPeers`). Pure, so the JVM tests can pin it.
 */
internal object SeederRefreshPolicy {

    /** How long a failed refresh waits before the next attempt. A success is good for the refresh interval. */
    const val RETRY_BACKOFF_MS: Long = 5 * 60 * 1000L

    /** A cached list wants a refresh when it is empty or older than [intervalMs]. */
    fun isStale(lastFetchMs: Long, nowMs: Long, poolEmpty: Boolean, intervalMs: Long): Boolean =
        poolEmpty || nowMs - lastFetchMs > intervalMs

    /**
     * Whether a background refresh may start now, given when the last one started. Never-attempted
     * and a clock that went backwards both allow one; otherwise one per [RETRY_BACKOFF_MS], so a
     * seeder that does not answer costs one background request per window.
     */
    fun mayAttempt(lastAttemptMs: Long, nowMs: Long): Boolean =
        lastAttemptMs <= 0L || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= RETRY_BACKOFF_MS
}
