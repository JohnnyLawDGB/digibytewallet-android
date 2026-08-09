package io.digibyte.core.sync

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I2 fix: `saved_blocks` moves out of `dgb_sync_data` SharedPreferences (hex String,
 * pinned in the process-lifetime SharedPreferencesImpl in-memory map) into a plain
 * file, mirroring [FilterHeaderStore] — the same fix already shipped for the
 * filter-header chain (v3.10.29) for the identical OOM pattern.
 *
 * [FakeSharedPreferences] and the `dgb_sync_data`-only [Context] wiring reuse the
 * helper defined in CfAbandonmentStoreTest.kt (same package); this class adds a
 * real temp-directory [Context.getFilesDir] so file I/O actually round-trips.
 */
class SavedBlockStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fakeCtx(): Context {
        val stores = HashMap<String, FakeSharedPreferences>()
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } answers {
            stores.getOrPut(firstArg()) { FakeSharedPreferences() }
        }
        every { ctx.filesDir } returns tmp.root
        return ctx
    }

    // ── round-trip ─────────────────────────────────────────────────────────

    @Test fun roundTripsWrittenBytes() {
        val ctx = fakeCtx()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 0xff.toByte())
        SavedBlockStore.write(ctx, bytes, SavedBlockStore.currentEpoch())
        assertArrayEquals(bytes, SavedBlockStore.load(ctx))
    }

    @Test fun writeOverwritesPreviousContent() {
        val ctx = fakeCtx()
        SavedBlockStore.write(ctx, byteArrayOf(1, 1, 1), SavedBlockStore.currentEpoch())
        SavedBlockStore.write(ctx, byteArrayOf(9, 9), SavedBlockStore.currentEpoch())
        assertArrayEquals(byteArrayOf(9, 9), SavedBlockStore.load(ctx))
    }

    // ── absent / empty handling ───────────────────────────────────────────

    @Test fun absentFileAndNoLegacyReturnsNull() {
        val ctx = fakeCtx()
        assertNull(SavedBlockStore.load(ctx))
    }

    @Test fun deleteRemovesTheFileAndSubsequentLoadIsNull() {
        val ctx = fakeCtx()
        SavedBlockStore.write(ctx, byteArrayOf(9, 9), SavedBlockStore.currentEpoch())
        assertTrue(SavedBlockStore.file(ctx).exists())
        SavedBlockStore.delete(ctx)
        assertFalse(SavedBlockStore.file(ctx).exists())
        assertNull(SavedBlockStore.load(ctx))
    }

    /** A write snapshotted BEFORE a concurrent delete() must not resurrect the
     *  file after the delete — the same epoch guard FilterHeaderStore uses to
     *  stop a stale watchdog-reset write from undoing a rescan/wipe. */
    @Test fun staleEpochWriteAfterDeleteIsDropped() {
        val ctx = fakeCtx()
        val epoch = SavedBlockStore.currentEpoch()
        SavedBlockStore.delete(ctx) // bumps the epoch
        SavedBlockStore.write(ctx, byteArrayOf(1, 2, 3), epoch) // stale snapshot
        assertNull(SavedBlockStore.load(ctx))
    }

    // ── I2 review: SyncService.onDestroy() resurrection defect ───────────────
    //
    // SyncService caches the latest onSaveBlocks() window so onDestroy() can flush
    // it synchronously before teardown (a graceful stop must not drop the last
    // window to serviceScope.cancel()). WalletManager.wipeWallet() calls
    // SavedBlockStore.delete() but does NOT stop SyncService — so if onDestroy()
    // runs afterward with no fresh onSaveBlocks callback in between, the flush is
    // exactly this store-level sequence: cache at epoch E -> delete() bumps the
    // epoch -> flush. These two tests pin BOTH call patterns SyncService could use
    // for that flush, using the store's real write()/delete()/currentEpoch() API
    // (SyncService itself has no JVM test harness in this codebase — it's an
    // Android Service with no Robolectric setup — so this is the exercisable proxy
    // for the call-site contract the fix depends on).

    /** BUG PATTERN (pre-fix `lastSavedBlocksData: ByteArray?`): the flush re-reads
     *  [SavedBlockStore.currentEpoch] FRESH at flush time instead of reusing the
     *  epoch captured when the window was cached. A fresh read is always equal to
     *  itself, so the epoch guard can never reject the write — the just-deleted
     *  file is resurrected with the wiped wallet's stale block window. This
     *  documents why that call pattern is unsafe; it is expected to stay true
     *  regardless of the SyncService-side fix (the store's guard is working
     *  exactly as designed — it's the caller that must supply the right epoch). */
    @Test fun freshEpochReadAtFlushTimeResurrectsAFileDeletedSinceCaching() {
        val ctx = fakeCtx()
        val cached = byteArrayOf(1, 2, 3) // "cache at epoch E" (onSaveBlocks receipt)
        SavedBlockStore.write(ctx, cached, SavedBlockStore.currentEpoch())
        assertTrue("precondition: window persisted", SavedBlockStore.file(ctx).exists())

        SavedBlockStore.delete(ctx) // WalletManager.wipeWallet() — SyncService NOT stopped
        assertFalse("precondition: wipe deleted the file", SavedBlockStore.file(ctx).exists())

        // onDestroy, BUG PATTERN: flush(cached, SavedBlockStore.currentEpoch()) —
        // reads the epoch fresh, AFTER the delete, so it trivially matches itself.
        SavedBlockStore.write(ctx, cached, SavedBlockStore.currentEpoch())

        assertTrue(
            "bug reproduction: a fresh epoch read at flush time resurrects the " +
                "wiped file with the stale pre-wipe block window",
            SavedBlockStore.file(ctx).exists(),
        )
    }

    /** FIX PATTERN (post-fix `lastSavedBlocksData: Pair<ByteArray, Long>?`,
     *  mirroring FilterHeaderStore's `pendingFilterHeaders`): the flush reuses the
     *  SAME epoch captured alongside the data at onSaveBlocks receipt. A delete()
     *  after caching but before the flush bumps the epoch, so the snapshotted
     *  value no longer matches and SavedBlockStore.write() drops the stale write —
     *  the wiped file stays deleted. */
    @Test fun snapshotEpochCapturedAtCacheTimePreventsResurrectionAfterDelete() {
        val ctx = fakeCtx()
        val cached = byteArrayOf(1, 2, 3)
        val snapshotEpoch = SavedBlockStore.currentEpoch() // captured WITH the data at receipt
        SavedBlockStore.write(ctx, cached, snapshotEpoch)
        assertTrue("precondition: window persisted", SavedBlockStore.file(ctx).exists())

        SavedBlockStore.delete(ctx) // WalletManager.wipeWallet() — SyncService NOT stopped
        assertFalse("precondition: wipe deleted the file", SavedBlockStore.file(ctx).exists())

        // onDestroy, FIX PATTERN: flush(cached, snapshotEpoch) — reuses the epoch
        // captured at cache time, now stale relative to the post-delete epoch.
        SavedBlockStore.write(ctx, cached, snapshotEpoch)

        assertFalse(
            "fix: reusing the epoch captured at cache time must NOT resurrect " +
                "a file deleted since then",
            SavedBlockStore.file(ctx).exists(),
        )
    }

    // ── legacy prefs -> file migration ────────────────────────────────────

    @Test fun migratesLegacyHexAndDropsOnlyTheSavedBlocksKey() {
        val ctx = fakeCtx()
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val prefs = ctx.getSharedPreferences("dgb_sync_data", 0)
        prefs.edit()
            .putString("saved_blocks", hex)
            .putString("saved_peers", "aabbcc")
            .putBoolean("has_synced", true)
            .putLong("last_balance", 12345L)
            .putString("saved_transactions", "beef")
            .apply()

        val loaded = SavedBlockStore.load(ctx)

        assertArrayEquals(bytes, loaded)
        assertArrayEquals(bytes, SavedBlockStore.file(ctx).readBytes())
        // Only saved_blocks is removed — every sibling field survives untouched.
        assertNull(prefs.getString("saved_blocks", null))
        assertEquals("aabbcc", prefs.getString("saved_peers", null))
        assertTrue(prefs.getBoolean("has_synced", false))
        assertEquals(12345L, prefs.getLong("last_balance", 0L))
        assertEquals("beef", prefs.getString("saved_transactions", null))
    }

    @Test fun migrationIsIdempotentOnASecondLoad() {
        val ctx = fakeCtx()
        val bytes = byteArrayOf(1, 2, 3)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        ctx.getSharedPreferences("dgb_sync_data", 0).edit().putString("saved_blocks", hex).apply()

        val first = SavedBlockStore.load(ctx)
        val second = SavedBlockStore.load(ctx) // file now exists — no legacy prefs path taken
        assertArrayEquals(bytes, first)
        assertArrayEquals(bytes, second)
    }

    @Test fun noMigrationWhenFileAlreadyExists_legacyHexIsIgnored() {
        val ctx = fakeCtx()
        SavedBlockStore.write(ctx, byteArrayOf(7, 7, 7), SavedBlockStore.currentEpoch())
        // A stale/mismatched legacy blob must never override an already-migrated file.
        ctx.getSharedPreferences("dgb_sync_data", 0).edit().putString("saved_blocks", "aabb").apply()
        assertArrayEquals(byteArrayOf(7, 7, 7), SavedBlockStore.load(ctx))
    }

    @Test fun malformedLegacyHexIsDroppedNotMigrated() {
        val ctx = fakeCtx()
        val prefs = ctx.getSharedPreferences("dgb_sync_data", 0)
        prefs.edit().putString("saved_blocks", "not-hex-zz").putString("saved_peers", "keep-me").apply()

        assertNull(SavedBlockStore.load(ctx))
        assertFalse(SavedBlockStore.file(ctx).exists())
        // The corrupt key is still dropped so it can't wedge every future load.
        assertNull(prefs.getString("saved_blocks", null))
        assertEquals("keep-me", prefs.getString("saved_peers", null))
    }

    @Test fun oversizedLegacyHexIsDroppedNotMigrated() {
        val ctx = fakeCtx()
        val huge = "ab".repeat(SavedBlockStore.MAX_LEGACY_MIGRATE_HEX / 2 + 1)
        ctx.getSharedPreferences("dgb_sync_data", 0).edit().putString("saved_blocks", huge).apply()

        assertNull(SavedBlockStore.load(ctx))
        assertFalse(SavedBlockStore.file(ctx).exists())
    }
}
