package io.digibyte.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import androidx.room.Room
import io.digibyte.core.db.WalletDatabase
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Paced-convoy fetch, spec Part E / GATE 3(iii) — **abandonment must not be
 * terminal: a full rescan really does clear `abandonedBelow`.**
 *
 * The B2 abandonment valve can only prove a height unservable by the peers it is
 * CURRENTLY connected to, never fleet-wide. Under documented fleet saturation (a
 * canon oracle at `maxconnections`) the one node that would serve a height may
 * never be consulted, so a servable height CAN be abandoned. The operator accepted
 * that residual only on the condition that every abandoned band stays surfaced and
 * RECOVERABLE. This test guards half of "recoverable".
 *
 * **Why this drives the whole production sequence rather than calling `Init`.**
 * The obvious version of this test — call `BRCFScanLedgerInit` and assert
 * `abandonedBelow == 0` — passes on the broken build, because `Init` was never the
 * bug. The bug is what happens AFTER it: `rebuildFromChainRescan()` did not delete
 * `CfScanLedgerStore`, so on the forced restart `SyncService` fed the SURVIVING
 * blob to `restoreCfScanLedger()`, which `Parse`d the old `abandonedBelow` straight
 * back over the fresh `Init`. That watermark is a monotonic hard floor clamping
 * every CF request (`BRCFScanLedger.c:433/600/666`), so the band could never be
 * re-covered and the "a full rescan re-covers it" recovery claim was unsound. The
 * sequence below is therefore: persisted ledger with `abandonedBelow > 0` →
 * `rebuildFromChainRescan()` → simulated restart (`startSync()` re-`Init`s) →
 * the same restore call production makes → assert the watermark is 0.
 *
 * RED without the `CfScanLedgerStore.delete(context)` fix in
 * `WalletManager.rebuildFromChainRescan()`.
 *
 * Requires a device/emulator: `startSync()` must create the native peer manager
 * before `restoreCfScanLedger()` will do anything (it is `g_peerManager`-guarded).
 */
@RunWith(AndroidJUnit4::class)
class WalletManagerRescanTest {

    private lateinit var ctx: Context
    private lateinit var db: WalletDatabase

    /** A known-good BIP39 mnemonic; the wallet content is irrelevant here — the
     *  test only needs a loaded wallet so `startSync()` creates the peer manager. */
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"

    private val ledgerStart = 23_000_000L
    private val abandonedBelow = 23_900_125L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // In-memory, un-encrypted: rebuildFromChainRescan() never touches the DB —
        // UtxoManager is only a WalletManager constructor dependency here.
        db = Room.inMemoryDatabaseBuilder(ctx, WalletDatabase::class.java)
            .allowMainThreadQueries().build()
        NativeBridge.stopSync()
        CfScanLedgerStore.delete(ctx)
        CfAbandonmentStore.clear(ctx)
    }

    @After
    fun tearDown() {
        NativeBridge.stopSync()
        CfScanLedgerStore.delete(ctx)
        CfAbandonmentStore.clear(ctx)
        db.close()
    }

    /** v3 CF-scan-ledger blob, no outstanding/gaveUp entries — the shape a wallet
     *  persists after the valve abandoned a band. Layout per `BRCFScanLedger.c`
     *  "Blob layout": magic | version | start | scannedThrough | requestedThrough |
     *  abandonedBelow | outCount | gaveUpCount, all little-endian u32. */
    private fun cfLedgerBlob(
        start: Long, scannedThrough: Long, requestedThrough: Long, abandonedBelow: Long,
    ): ByteArray = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x43464C31)          // CF_LEDGER_MAGIC "CFL1"
        putInt(3)                   // CF_LEDGER_VERSION
        putInt(start.toInt())
        putInt(scannedThrough.toInt())
        putInt(requestedThrough.toInt())
        putInt(abandonedBelow.toInt())
        putInt(0)                   // outstandingCount
        putInt(0)                   // gaveUpCount
    }.array()

    private fun walletManager() = WalletManager(
        context = ctx,
        keyStoreManager = KeyStoreManager(),
        utxoManager = UtxoManager(db.utxoDao()),
    )

    /** Load a wallet and bring the native peer manager up, exactly as a launch
     *  does, so the CF scan ledger exists and is addressable. */
    private fun loadWalletAndStartSync() {
        val bytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            assertTrue(
                "native wallet failed to load — cannot exercise the ledger",
                NativeBridge.recoverWalletFromBytes(bytes, 1_700_000_000L, null),
            )
        } finally {
            bytes.fill(0)
        }
        NativeBridge.startSync()
    }

    /** Replay SyncService's restore step verbatim (SyncService.kt: load →
     *  restoreCfScanLedger). This is the step that resurrected `abandonedBelow`. */
    private fun replayProductionLedgerRestore(): Boolean {
        val saved = CfScanLedgerStore.load(ctx) ?: return false
        return NativeBridge.restoreCfScanLedger(saved)
    }

    @Test
    fun rescan_resets_abandonedBelow() {
        // ── 1. A wallet that abandoned blocks 23,900,120–23,900,124 ──────────
        loadWalletAndStartSync()
        CfScanLedgerStore.write(
            ctx,
            cfLedgerBlob(
                start = ledgerStart,
                scannedThrough = abandonedBelow - 6,
                requestedThrough = abandonedBelow + 10_000,
                abandonedBelow = abandonedBelow,
            ),
            CfScanLedgerStore.currentEpoch(),
        )
        assertTrue("precondition: the persisted ledger restores", replayProductionLedgerRestore())
        assertEquals(
            "precondition: the native watermark reflects the abandonment",
            abandonedBelow, NativeBridge.getAbandonedBelow(),
        )
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow, abandonedBelow - 5)
        assertNotNull("precondition: the band is surfaced", CfAbandonmentStore.unrecoveredBand(ctx))

        // ── 2. The user taps "Full rebuild from chain" ────────────────────────
        walletManager().rebuildFromChainRescan()

        // ── 3. Simulated restart: fresh Init, then the SAME restore production
        //       performs. This is where the surviving blob used to strike.
        loadWalletAndStartSync()
        replayProductionLedgerRestore()

        // ── 4. The band is genuinely re-coverable ─────────────────────────────
        assertEquals(
            "rescan did not clear abandonedBelow — the surviving CfScanLedgerStore " +
                "blob was parsed back over the fresh Init, so the monotonic hard " +
                "floor still clamps every CF request and the abandoned band can " +
                "NEVER be re-scanned. Abandonment would be terminal.",
            0L, NativeBridge.getAbandonedBelow(),
        )
        assertNull("the surfaced band must be forgotten too", CfAbandonmentStore.band(ctx))
    }

    /** The scan frontier must fall back to the rescan floor, not stay pinned at
     *  the abandoned watermark — otherwise the rescan clears the flag but still
     *  refuses to look below it. */
    @Test
    fun rescan_lowersTheScanFrontier_belowTheAbandonedBand() {
        loadWalletAndStartSync()
        CfScanLedgerStore.write(
            ctx,
            cfLedgerBlob(ledgerStart, abandonedBelow - 6, abandonedBelow + 10_000, abandonedBelow),
            CfScanLedgerStore.currentEpoch(),
        )
        replayProductionLedgerRestore()
        assertTrue(
            "precondition: the frontier is clamped at the abandoned watermark",
            NativeBridge.getLowestNeededHeight() >= abandonedBelow,
        )

        walletManager().rebuildFromChainRescan()
        loadWalletAndStartSync()
        replayProductionLedgerRestore()

        assertTrue(
            "the CF scan frontier is still pinned at/above the abandoned band, so " +
                "the rescan cannot reach the missing heights",
            NativeBridge.getLowestNeededHeight() < abandonedBelow,
        )
    }
}
