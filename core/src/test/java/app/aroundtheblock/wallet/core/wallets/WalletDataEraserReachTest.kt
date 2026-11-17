package app.aroundtheblock.wallet.core.wallets

import android.util.Log
import app.aroundtheblock.wallet.core.AndroidWalletDataEraser
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.asKeystoreAliases
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wipeTestContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F12. The full wipe erases legacy's stores through [AndroidWalletDataEraser], which names them
 * itself; [WalletStores] is the one list of per-wallet stores. This holds the two together: every
 * per-wallet base in that list, under legacy's names on both networks, is reached by the full wipe.
 * A per-wallet base added to [WalletStores] later fails here until the full wipe reaches it.
 */
class WalletDataEraserReachTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ChainTipStore.invalidateCache()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() {
        unmockkStatic(Log::class)
        ChainTipStore.invalidateCache()
    }

    @Test fun `every per-wallet store is reached by the full wipe`() {
        val prefs = InMemoryPrefsFactory()
        val root = tmp.newFolder()
        val ctx = wipeTestContext(prefs, root)
        val eraser = AndroidWalletDataEraser(ctx, FakeVault().asKeystoreAliases()) {
            HubTokenStore(prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME), { prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME) })
        }
        val legacy = listOf(false, true).map(WalletScope::legacy)
        val hubStores = setOf(HubTokenStore.LEGACY_PREFS_NAME, HubTokenStore.SECURE_PREFS_NAME)

        val prefsNames = legacy.flatMap { s ->
            (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS).map { WalletStores.prefsName(it, s) }
        }.distinct()
        prefsNames.forEach { name ->
            // The Hub session stores hold one thing, the token, and the wipe removes the token.
            prefs.prefsFor(name).seed(if (name in hubStores) HubTokenStore.KEY_JWT else "held", "$name-value")
        }
        val files = legacy.flatMap { s ->
            WalletStores.WALLET_AND_NETWORK_FILES.flatMap { base ->
                val name = WalletStores.fileName(base, s)
                listOf(File(ctx.filesDir, name), File(ctx.filesDir, "$name.tmp"))
            }
        }
        files.forEach { it.writeBytes(byteArrayOf(1)) }

        eraser.eraseSeedCiphertext()
        eraser.eraseSyncData()
        eraser.eraseBloomPeerCache()
        eraser.eraseWatchedAddresses()
        eraser.eraseOutgoingTx()
        eraser.eraseCfSyncState()
        eraser.eraseDatabase()
        eraser.eraseLeftoverState()
        eraser.eraseHubSession()

        assertEquals(
            "per-wallet prefs the full wipe does not reach",
            emptyList<String>(),
            prefsNames.filter { prefs.prefsFor(it).all.isNotEmpty() },
        )
        assertEquals("per-wallet files the full wipe does not reach", emptyList<String>(), files.filter { it.exists() }.map { it.name })
    }
}
