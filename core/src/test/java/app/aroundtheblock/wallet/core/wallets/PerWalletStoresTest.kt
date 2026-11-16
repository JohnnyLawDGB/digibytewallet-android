package app.aroundtheblock.wallet.core.wallets

import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.OutgoingTxStore
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.keyStoreManagerOver
import app.aroundtheblock.wallet.core.keyTestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerWalletStoresTest {
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val id = "0123456789abcdef"
    private val b = WalletScope(id, false)
    private val legacy = WalletScope.legacy(false)
    private val roots = mutableListOf<File>()
    private val prefs = InMemoryPrefsFactory()

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun cleanup() {
        unmockkStatic(Log::class)
        roots.forEach { it.deleteRecursively() }
    }

    private fun manager(scope: WalletScope) = WalletManager(
        context = keyTestContext(prefs, Files.createTempDirectory("per-wallet").toFile().also { roots += it }),
        keyStoreManager = keyStoreManagerOver(FakeVault()),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        quiesceNative = { },
        recoverNative = { _, _, _ -> true },
        seedNative = { _, _ -> ByteArray(64) },
        rescanNative = { },
        birthHeightNative = { 21_500_000L },
        releaseDatabaseKey = { true },
        scope = scope,
    )

    @Test fun `a non-legacy wallet's watch set, sends and floor are its own`() {
        prefs.prefsFor("dgb_watched_addrs_$id").seed("addrs", setOf("dgb1qb"))
        assertEquals(setOf("dgb1qb"), manager(b).watchedReceiveAddresses())
        assertTrue(manager(legacy).watchedReceiveAddresses().isEmpty())

        val ctx = keyTestContext(prefs, Files.createTempDirectory("per-wallet").toFile().also { roots += it })
        OutgoingTxStore(ctx, b).record("tx1", 1L, 1L, "dgb1qx")
        assertTrue(prefs.prefsFor("dgb_outgoing_tx").all.isEmpty())

        assertTrue(manager(b).recoverWallet(phrase, 1_600_000_000L))
        val settings = prefs.prefsFor("dgb_settings")
        assertTrue(settings.contains("cf_birth_height_$id"))
        assertFalse(settings.contains("cf_birth_height"))
    }

    @Test fun `legacy still writes cf_birth_height`() {
        assertTrue(manager(legacy).recoverWallet(phrase, 1_600_000_000L))
        val settings = prefs.prefsFor("dgb_settings")
        assertTrue(settings.contains("cf_birth_height"))
        assertEquals(setOf("cf_birth_height"), settings.all.keys)
    }
}
