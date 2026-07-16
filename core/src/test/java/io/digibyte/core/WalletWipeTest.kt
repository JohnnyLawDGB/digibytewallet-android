package io.digibyte.core

import io.digibyte.core.security.KeyStoreManager
import io.mockk.coVerify
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
        override fun eraseFilterHeaders() { calls += "filter" }
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
                listOf("seed", "sync", "bloom", "watched", "outgoing", "filter", "db")
            )
        )
        // UTXO cache + seed Keystore key destroyed.
        coVerify(exactly = 1) { um.clearAll() }
        verify(exactly = 1) { ksm.deleteKey() }
        // State reset to no-wallet.
        assertTrue(wm.walletState.value is WalletState.NoWallet)
    }
}
