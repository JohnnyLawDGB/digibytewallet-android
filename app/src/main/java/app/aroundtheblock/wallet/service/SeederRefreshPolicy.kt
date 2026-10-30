package app.aroundtheblock.wallet.service

/**
 * When the cached seeder lists (`api.digiscope.me/api/peers`) are refreshed. The refresh always runs
 * in the background, never on a path that ends in `startSync()`: the start paths inject what is
 * cached, start, and leave the fetch to `SyncService.requestSeederRefresh`, as the iOS app does
 * (`WalletCore.addSeederPeers`). Pure, so the JVM tests can pin it. It mirrors the core's
 * `BRSeederShouldRefresh`, which iOS calls; moving Android onto that rule needs a JNI binding (a follow-up).
 */
internal object SeederRefreshPolicy {

    /** A cached list wants a refresh when it is empty or older than [intervalMs]. */
    fun isStale(lastFetchMs: Long, nowMs: Long, poolEmpty: Boolean, intervalMs: Long): Boolean =
        poolEmpty || nowMs - lastFetchMs > intervalMs
}
