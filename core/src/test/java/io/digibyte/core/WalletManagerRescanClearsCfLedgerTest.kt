package io.digibyte.core

import android.content.Context
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.fakeContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Paced-convoy fetch, spec Part E / GATE 3(iii) — **the full rescan must actually
 * be able to re-cover an abandoned band.**
 *
 * The claim "a full CF rescan re-covers the band" rests on
 * `BRCFScanLedgerInit` resetting `abandonedBelow` to 0 on the next launch. That
 * claim was UNSOUND: `rebuildFromChainRescan()` deleted the saved transactions,
 * the saved blocks and `FilterHeaderStore`, but NOT `CfScanLedgerStore`. So on the
 * forced restart `startSync()` Init'd the ledger fresh and then
 * `restoreCfScanLedger()` parsed the SURVIVING blob straight back on top of it,
 * restoring `abandonedBelow > 0` — a monotonic hard floor that clamps every CF
 * request (`BRCFScanLedger.c:433/600/666`), so the CF path could never re-cover the
 * band. Abandonment would then be TERMINAL, and the safety argument for allowing
 * the B2 valve to abandon a possibly-servable height collapses.
 *
 * This test drives the KOTLIN half of the production sequence — the half that owns
 * the defect — with no device: the surviving blob is what `restoreCfScanLedger()`
 * is fed on the next launch, so "the blob is gone" is exactly "there is nothing to
 * restore `abandonedBelow` from". The native half of the same sequence
 * (Init → restore → `getAbandonedBelow() == 0`) is asserted end-to-end in
 * `core/src/androidTest/.../WalletManagerRescanTest`.
 *
 * RED before the fix: `CfScanLedgerStore.load()` still returns the blob.
 */
class WalletManagerRescanClearsCfLedgerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A v3 CF-scan-ledger blob with no outstanding/gaveUp entries — the shape a
     *  wallet persists after the B2 valve abandoned a band. Layout per
     *  `BRCFScanLedger.c` "Blob layout": magic | version | start | scannedThrough |
     *  requestedThrough | abandonedBelow | outCount | gaveUpCount, all LE u32. */
    private fun cfLedgerBlob(
        start: Long, scannedThrough: Long, requestedThrough: Long, abandonedBelow: Long,
    ): ByteArray {
        val out = java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x43464C31)          // CF_LEDGER_MAGIC "CFL1"
        out.putInt(3)                   // CF_LEDGER_VERSION (v3)
        out.putInt(start.toInt())
        out.putInt(scannedThrough.toInt())
        out.putInt(requestedThrough.toInt())
        out.putInt(abandonedBelow.toInt())
        out.putInt(0)                   // outstandingCount
        out.putInt(0)                   // gaveUpCount
        return out.array()
    }

    private fun walletManager(ctx: Context) = WalletManager(
        context = ctx,
        keyStoreManager = mockk(relaxed = true),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        // Keep NativeBridge's System.loadLibrary out of this JVM test. The native
        // calls inside rebuildFromChainRescan() are themselves runCatching-wrapped,
        // so they degrade to no-ops here without changing the file/pref side effects
        // under test.
        quiesceNative = { },
    )

    @Test
    fun rebuildFromChainRescan_deletesThePersistedCfScanLedger() {
        val ctx = fakeContext()
        every { ctx.filesDir } returns tmp.newFolder("files")

        // A wallet that abandoned blocks 23,900,120–23,900,124.
        CfScanLedgerStore.write(
            ctx,
            cfLedgerBlob(
                start = 23_000_000L,
                scannedThrough = 23_900_119L,
                requestedThrough = 23_910_000L,
                abandonedBelow = 23_900_125L,
            ),
            CfScanLedgerStore.currentEpoch(),
        )
        assertNotNull("precondition: a ledger blob is persisted", CfScanLedgerStore.load(ctx))

        walletManager(ctx).rebuildFromChainRescan()

        // On the forced restart, SyncService does:
        //     CfScanLedgerStore.load(ctx)?.let { NativeBridge.restoreCfScanLedger(it) }
        // If anything survives here, abandonedBelow is parsed straight back over the
        // fresh Init and the rescan cannot re-cover the band.
        assertNull(
            "rescan left the CF scan ledger on disk — restoreCfScanLedger() will " +
                "re-apply abandonedBelow over the fresh Init and the abandoned band " +
                "can never be re-covered",
            CfScanLedgerStore.load(ctx),
        )
    }

    /** The rescan also forgets the surfaced band: after it, `abandonedBelow` really
     *  is 0, so a banner pointing at a band that no longer exists would be a lie
     *  the user cannot dismiss. */
    @Test
    fun rebuildFromChainRescan_forgetsTheSurfacedAbandonedBand() {
        val ctx = fakeContext()
        every { ctx.filesDir } returns tmp.newFolder("files")

        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 23_900_125L, lowHint = 23_900_120L)
        assertNotNull("precondition: a band is surfaced", CfAbandonmentStore.unrecoveredBand(ctx))

        walletManager(ctx).rebuildFromChainRescan()

        assertNull(
            "rescan re-Inits the native ledger at abandonedBelow=0, so the recorded " +
                "band no longer exists and must not keep nagging",
            CfAbandonmentStore.band(ctx),
        )
    }
}
