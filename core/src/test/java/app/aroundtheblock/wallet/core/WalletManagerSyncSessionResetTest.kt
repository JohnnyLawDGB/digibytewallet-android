package app.aroundtheblock.wallet.core

import android.content.Context
import app.aroundtheblock.wallet.core.model.SyncStage
import app.aroundtheblock.wallet.core.model.SyncState
import app.aroundtheblock.wallet.core.model.SYNC_TARGET_MAX_LEAD
import app.aroundtheblock.wallet.core.sync.fakeContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The process-wide "reached Synced this session" latch ([WalletManager.syncFrontierSession]) is
 * cleared whenever the session can no longer be known to have stayed synced: the app going to the
 * background, a lock, and a new wallet. After any of them, a peer target past SYNC_TARGET_MAX_LEAD
 * must read Syncing again (it holds Send), as on iOS (`WalletCore.enterBackground`).
 */
class WalletManagerSyncSessionResetTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tip = 24_000_000L
    private val outlier = tip + SYNC_TARGET_MAX_LEAD + 1

    private fun walletManager(ctx: Context = fakeContext()) = WalletManager(
        context = ctx,
        keyStoreManager = mockk(relaxed = true),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        quiesceNative = { },
    )

    private fun WalletManager.deriveAt(target: Long) = syncFrontierSession.derive(
        state = SyncState.Complete, peerCount = 8,
        currentHeight = tip, targetHeight = target, externalTip = tip, cfTip = tip, scanFrontier = tip,
    )

    /** Synced at the tip, so the latch is set and an outlier target reads Synced. */
    private fun WalletManager.latched(): WalletManager {
        assertEquals(SyncStage.Synced, deriveAt(tip).stage)
        assertEquals("precondition: the latch treats the outlier as unproven", SyncStage.Synced, deriveAt(outlier).stage)
        return this
    }

    private fun WalletManager.assertCold(after: String) {
        assertFalse("$after left the latch set", syncFrontierSession.reachedSynced)
        assertEquals("after $after an outlier peer target must hold the wallet in Syncing", SyncStage.Syncing, deriveAt(outlier).stage)
    }

    @Test fun `going to the background clears the latch`() {
        val wm = walletManager().latched()
        wm.enterBackground()
        wm.assertCold("going to the background")
    }

    @Test fun `a UI lock clears the latch`() {
        val wm = walletManager().latched()
        wm.lockUi()
        wm.assertCold("a UI lock")
    }

    @Test fun `a new wallet clears the latch`() {
        // clearSyncData backs createWallet, recoverWallet and a seed-change restore.
        val ctx = fakeContext()
        every { ctx.filesDir } returns tmp.newFolder("files")
        val wm = walletManager(ctx).latched()
        wm.clearSyncData()
        wm.assertCold("a new wallet")
    }

    @Test fun `the latch is earned again after a resume`() {
        val wm = walletManager().latched()
        wm.enterBackground()
        assertEquals(SyncStage.Synced, wm.deriveAt(tip).stage)
        assertTrue(wm.syncFrontierSession.reachedSynced)
        assertEquals(SyncStage.Synced, wm.deriveAt(outlier).stage)
    }
}
