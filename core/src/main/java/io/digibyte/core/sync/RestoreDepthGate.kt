package io.digibyte.core.sync

/**
 * Pure decision for the deep-restore depth gate (spec Part 3c).
 *
 * A CF restore whose scan span (`tip − birthHeight`) is deeper than the native
 * CF-retention ceiling ([io.digibyte.core.bridge.NativeBridge.restoreScanDepthLimit],
 * = `CF_RETENTION_MAX_SPAN` in BRPeerManager.h) cannot be scanned on-device without
 * either syncing to a WRONG balance (deep history never scanned) or OOMing. The app
 * refuses such a restore up front with a plain message rather than start the doomed
 * scan. This is the app-layer half of the fix that pairs with the native memory-ceiling
 * + determinism guard (Tasks 1–4).
 *
 * Kept pure (no JNI, no Android) so the decision is unit-testable off-device — the
 * native depth/limit inputs are read once by the caller and passed in.
 */
object RestoreDepthGate {

    /**
     * True if a restore of [depthBlocks] blocks exceeds [limitBlocks] and must be
     * refused. A depth exactly at the limit is allowed; a zero/negative depth (a
     * fresh or near-tip birth) is always allowed. A non-positive [limitBlocks]
     * disables the gate (treated as "no limit") so a bad/zero native value can never
     * block every restore.
     */
    fun isRestoreTooDeep(depthBlocks: Long, limitBlocks: Long): Boolean =
        limitBlocks > 0L && depthBlocks > limitBlocks
}
