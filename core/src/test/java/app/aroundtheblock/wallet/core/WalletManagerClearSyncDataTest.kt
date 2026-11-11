package app.aroundtheblock.wallet.core

import android.content.Context
import app.aroundtheblock.wallet.core.sync.CfScanLedgerStore
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
import app.aroundtheblock.wallet.core.sync.SavedBlockStore
import app.aroundtheblock.wallet.core.sync.fakeContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
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

    /** STORE-DURABILITY review: a new wallet (create, recover, seed change) must not inherit the previous
     *  wallet's scan ledger or filter-header chain, nor a tmp of either, nor a write snapshotted before. */
    @Test
    fun clearSyncData_deletesTheFilterHeaderChainAndScanLedger() {
        val ctx = fakeContext()
        val dir = tmp.newFolder("files")
        every { ctx.filesDir } returns dir
        val headerEpoch = FilterHeaderStore.currentEpoch()
        val ledgerEpoch = CfScanLedgerStore.currentEpoch()
        FilterHeaderStore.write(ctx, byteArrayOf(1, 2, 3), headerEpoch)
        CfScanLedgerStore.write(ctx, byteArrayOf(4, 5, 6), ledgerEpoch)
        File(dir, "saved_filter_headers.bin.tmp").writeBytes(byteArrayOf(7))
        File(dir, "saved_cf_ledger.bin.tmp").writeBytes(byteArrayOf(8))

        walletManager(ctx).clearSyncData()

        for (name in listOf("saved_filter_headers.bin", "saved_filter_headers.bin.tmp", "saved_cf_ledger.bin", "saved_cf_ledger.bin.tmp")) {
            assertFalse("clearSyncData left $name for the next wallet", File(dir, name).exists())
        }
        // Through delete(): an in-flight write snapshotted before the clear is dropped, not resurrected.
        FilterHeaderStore.write(ctx, byteArrayOf(9), headerEpoch)
        CfScanLedgerStore.write(ctx, byteArrayOf(9), ledgerEpoch)
        assertFalse(FilterHeaderStore.file(ctx).exists())
        assertFalse(CfScanLedgerStore.file(ctx).exists())
    }
}
