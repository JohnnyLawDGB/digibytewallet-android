package io.digibyte.core

import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the complete destructive routine [WalletManager.wipeWallet] runs — the
 * single path shared by the manual Settings wipe and the PIN wipe-after-N backstop.
 *
 * Pure JVM: the native quiesce is injected as a no-op (so NativeBridge's
 * `System.loadLibrary` never runs) and the data-erasure side effects are captured
 * by a fake [WalletDataEraser] whose invocation order is asserted (seed FIRST).
 */
class WalletWipeTest {

    private class RecordingEraser : WalletDataEraser {
        val calls = mutableListOf<String>()
        override fun eraseSeedCiphertext() { calls += "seed" }
        override fun eraseSyncData() { calls += "sync" }
        override fun eraseBloomPeerCache() { calls += "bloom" }
        override fun eraseWatchedAddresses() { calls += "watched" }
        override fun eraseOutgoingTx() { calls += "outgoing" }
        override fun eraseCfSyncState() { calls += "cfsync" }
        override fun eraseDatabase() { calls += "db" }
    }

    @Test fun wipeClearsSeedDbWatchedOutgoingFilterHeadersSeedFirst() = runTest {
        val eraser = RecordingEraser()
        val ksm = mockk<KeyStoreManager>(relaxed = true)
        val um = mockk<UtxoManager>(relaxed = true)
        // Relaxed context: WalletManager's init/field setup touches getSharedPreferences
        // only; no native. AndroidWalletDataEraser is NOT constructed (we inject the fake).
        val ctx = mockk<android.content.Context>(relaxed = true)

        val wm = WalletManager(
            context = ctx,
            keyStoreManager = ksm,
            utxoManager = um,
            dataEraser = eraser,
            quiesceNative = { /* no-op: keep the native lib out of this JVM test */ },
        )

        wm.wipeWallet()

        // Seed ciphertext cleared FIRST (crash-safety invariant).
        assertEquals("seed", eraser.calls.first())
        // Every privacy-sensitive + regenerable store is destroyed.
        assertTrue(
            "missing erase steps: ${eraser.calls}",
            eraser.calls.containsAll(
                listOf("seed", "sync", "bloom", "watched", "outgoing", "cfsync", "db")
            )
        )
        // UTXO cache + seed Keystore key destroyed.
        coVerify(exactly = 1) { um.clearAll() }
        verify(exactly = 1) { ksm.deleteKey() }
        // State reset to no-wallet.
        assertTrue(wm.walletState.value is WalletState.NoWallet)
    }

    /**
     * The order test above proves only that the interface method was CALLED — it would
     * stay green if that method deleted nothing. This drives the real
     * [AndroidWalletDataEraser] against a temp filesDir and asserts the files are gone.
     *
     * The scan ledger matters specifically: it records which heights already had a
     * cfilter evaluated. Surviving a wipe, it tells the NEXT wallet that this wallet's
     * scanned heights are done, so the new wallet's transactions in those blocks are
     * never looked for — a silent, permanent fund-visibility hole. Both files are
     * asserted so neither can regress alone.
     */
    @Test fun eraseCfSyncStateDeletesBothTheHeaderChainAndTheScanLedger() {
        val filesDir = java.nio.file.Files.createTempDirectory("wipe-cfsync").toFile()
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.filesDir } returns filesDir

        val headerChain = FilterHeaderStore.file(ctx)
        val scanLedger = CfScanLedgerStore.file(ctx)
        headerChain.writeBytes(byteArrayOf(1, 2, 3))
        scanLedger.writeBytes(byteArrayOf(4, 5, 6))
        assertTrue("precondition: header chain exists", headerChain.exists())
        assertTrue("precondition: scan ledger exists", scanLedger.exists())

        AndroidWalletDataEraser(ctx).eraseCfSyncState()

        assertTrue("filter-header chain survived the wipe", !headerChain.exists())
        assertTrue("CF scan ledger survived the wipe", !scanLedger.exists())
    }
}
