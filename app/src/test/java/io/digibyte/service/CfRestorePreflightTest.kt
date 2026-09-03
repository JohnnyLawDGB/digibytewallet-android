package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CfRestorePreflightTest {
    @Test
    fun `healthy persisted frontier keeps the saved header window`() {
        assertEquals(
            null,
            cfRestoreResetReason(
                ledger(scannedThrough = 1_000, requestedThrough = 1_200),
                savedBlocks(1_200, 1_000, 900),
                wasSynced = true,
                hasTransactionCheckpoint = true,
            ),
        )
    }

    // Fix-G (convergence): an abandoned band that the saved header window still
    // covers (lowestNeeded >= blockFloor) must RESUME, not force a rebuild-to-floor.
    // Before the fix, `state.abandonedBelow > 0L` short-circuited to
    // ABANDONED_HISTORY unconditionally, ahead of this exact coverage check — so a
    // device that abandoned even one band would rebuild from the birth floor on
    // EVERY restart, scan, abandon again, and never converge. The abandoned band
    // itself stays surfaced via the persisted ledger/CfAbandonmentStore — this
    // reason only controls whether headers/filters get rebuilt from scratch.
    @Test
    fun `abandoned history still covered by saved headers resumes without a rebuild`() {
        assertEquals(
            null,
            cfRestoreResetReason(
                ledger(scannedThrough = 1_000, requestedThrough = 1_200, abandonedBelow = 1_100),
                savedBlocks(1_200, 1_100),
                wasSynced = false,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    // Fix-G (precision preserved): abandonment does NOT blanket-exempt the coverage
    // guard — if the saved header window genuinely doesn't reach down to what's
    // still needed (lowestNeeded < blockFloor), it's still HEADER_WINDOW_MISSING.
    @Test
    fun `abandoned history not yet covered by saved headers still forces a rebuild`() {
        assertEquals(
            CfRestoreResetReason.HEADER_WINDOW_MISSING,
            cfRestoreResetReason(
                ledger(scannedThrough = 1_000, requestedThrough = 1_200, abandonedBelow = 1_100),
                savedBlocks(1_200, 1_150),
                wasSynced = false,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    @Test
    fun `frontier below the saved header floor forces a rebuild`() {
        assertEquals(
            CfRestoreResetReason.HEADER_WINDOW_MISSING,
            cfRestoreResetReason(
                ledger(scannedThrough = 1_000, requestedThrough = 1_200),
                savedBlocks(1_200, 1_100),
                wasSynced = false,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    @Test
    fun `ledger without saved block headers forces a rebuild`() {
        assertEquals(
            CfRestoreResetReason.HEADER_WINDOW_MISSING,
            cfRestoreResetReason(
                ledger(1_000, 1_200), null, wasSynced = false,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    @Test
    fun `synced wallet without a ledger is rescanned`() {
        assertEquals(
            CfRestoreResetReason.MISSING_LEDGER,
            cfRestoreResetReason(
                null, savedBlocks(1_200), wasSynced = true,
                hasTransactionCheckpoint = true,
            ),
        )
    }

    @Test
    fun `fresh wallet without a ledger starts normally`() {
        assertEquals(
            null,
            cfRestoreResetReason(
                null, savedBlocks(1_200), wasSynced = false,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    @Test
    fun `invalid persisted inputs fail safe`() {
        assertEquals(
            CfRestoreResetReason.INVALID_LEDGER,
            cfRestoreResetReason(
                ByteArray(28), savedBlocks(1_200), wasSynced = true,
                hasTransactionCheckpoint = true,
            ),
        )
        assertEquals(
            CfRestoreResetReason.INVALID_SAVED_BLOCKS,
            cfRestoreResetReason(
                ledger(1_000, 1_200), byteArrayOf(2, 0, 0, 0), wasSynced = true,
                hasTransactionCheckpoint = true,
            ),
        )
    }

    @Test
    fun `synced frontier without a transaction checkpoint is rebuilt`() {
        assertEquals(
            CfRestoreResetReason.MISSING_TRANSACTION_CHECKPOINT,
            cfRestoreResetReason(
                ledger(
                    scannedThrough = 23_998_330,
                    requestedThrough = 23_998_330,
                    start = 23_996_054,
                ),
                savedBlocks(23_998_351, 23_998_052),
                wasSynced = true,
                hasTransactionCheckpoint = false,
            ),
        )
    }

    @Test
    fun `synced resume ignores a stale deep restore birth override`() {
        assertEquals(
            23_997_221L,
            compactFilterBirthHeight(
                wasSynced = true,
                savedTip = 23_997_321L,
                walletBirth = 22_650_000L,
                persistedBirth = 22_650_000L,
            ),
        )
    }

    @Test
    fun `unfinished restore retains its persisted birth floor`() {
        assertEquals(
            22_650_000L,
            compactFilterBirthHeight(
                wasSynced = false,
                savedTip = 23_997_321L,
                walletBirth = 22_650_000L,
                persistedBirth = 22_650_000L,
            ),
        )
    }

    // DGB-1005: `has_synced` is committed the instant sync completes, but the CF scan ledger is
    // only flushed by a debounced writer / onDestroy — and an OS kill of a backgrounded app never
    // reaches onDestroy. If has_synced outlives the ledger, the next cold start's preflight sees
    // MISSING_LEDGER and floors the chain to the birth checkpoint. shouldMarkSynced gates the
    // has_synced commit on the ledger being durable, but ONLY when there is ledger state to lose.

    @Test
    fun `defer marking synced when a pending ledger has not reached disk`() {
        // The exact divergence: we hold ledger state that is not yet durable. Marking synced now
        // would set has_synced without a ledger on disk → birth reset on next launch.
        assertEquals(false, shouldMarkSynced(hasPendingLedger = true, ledgerDurable = false))
    }

    @Test
    fun `mark synced once the pending ledger is durable`() {
        assertEquals(true, shouldMarkSynced(hasPendingLedger = true, ledgerDurable = true))
    }

    @Test
    fun `mark synced when there is no pending ledger to lose`() {
        // A fresh / at-tip wallet with nothing scanned must NOT hang in "syncing" forever waiting
        // for a ledger that will never exist. No pending ledger means no divergence to guard.
        assertEquals(true, shouldMarkSynced(hasPendingLedger = false, ledgerDurable = false))
        assertEquals(true, shouldMarkSynced(hasPendingLedger = false, ledgerDurable = true))
    }

    private fun ledger(
        scannedThrough: Int,
        requestedThrough: Int,
        abandonedBelow: Int = 0,
        start: Int = 900,
    ): ByteArray = ByteArray(32).also {
        putU32(it, 0, 0x43464c31)
        putU32(it, 4, 3)
        putU32(it, 8, start)
        putU32(it, 12, scannedThrough)
        putU32(it, 16, requestedThrough)
        putU32(it, 20, abandonedBelow)
        putU32(it, 24, 0)
        putU32(it, 28, 0)
    }

    private fun savedBlocks(vararg heights: Int): ByteArray = ByteArray(4 + heights.size * 8).also {
        putU32(it, 0, heights.size)
        heights.forEachIndexed { index, height ->
            putU32(it, 4 + index * 8, 0)
            putU32(it, 8 + index * 8, height)
        }
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
