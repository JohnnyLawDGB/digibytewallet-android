package io.digibyte.core

import android.content.Context
import io.digibyte.core.sync.SavedBlockStore
import io.digibyte.core.sync.fakeContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I2 review (MINOR): [WalletManager.clearSyncData] clears the `dgb_sync_data`
 * prefs blob but — before this fix — never deleted the file-backed
 * [SavedBlockStore] window (moved out of that prefs blob by the I2 fix). Left
 * behind, a fresh wallet (`createWallet`/`recoverWallet`) or a
 * seed-fingerprint-mismatch restore (`restoreFromDisk`) — all three call
 * `clearSyncData()` — would silently inherit a DIFFERENT wallet's saved-blocks
 * window from disk.
 *
 * `clearSyncData()` is `internal` (not `private`) specifically so this test can
 * call it directly, mirroring [WalletManagerRescanClearsCfLedgerTest]'s pattern
 * for [WalletManager.rebuildFromChainRescan].
 */
class WalletManagerClearSyncDataTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun walletManager(ctx: Context) = WalletManager(
        context = ctx,
        keyStoreManager = mockk(relaxed = true),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        // Keep NativeBridge's System.loadLibrary out of this JVM test.
        quiesceNative = { },
    )

    @Test
    fun clearSyncData_deletesTheFileBackedSavedBlocksWindow() {
        val ctx = fakeContext()
        every { ctx.filesDir } returns tmp.newFolder("files")

        SavedBlockStore.write(ctx, byteArrayOf(1, 2, 3), SavedBlockStore.currentEpoch())
        assertTrue("precondition: a saved-blocks window is persisted", SavedBlockStore.file(ctx).exists())

        walletManager(ctx).clearSyncData()

        assertFalse(
            "clearSyncData left the file-backed saved-blocks window on disk — the " +
                "next wallet (fresh create/recover, or a seed-mismatch restore) would " +
                "inherit a DIFFERENT wallet's block window",
            SavedBlockStore.file(ctx).exists(),
        )
    }
}
