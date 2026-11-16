package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.FakeVault
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.WipeReport
import app.aroundtheblock.wallet.core.asKeystoreAliases
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wipeTestContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Removing one wallet never deletes another wallet's data. The active wallet is only marked: its
 * live database and session are not erased under the running process, and the next launch's
 * housekeeping erases it. Removing the last wallet on the device is the full wipe.
 */
class WalletRemovalTest {

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

    private val legacyMain = WalletScope.legacy(false)
    private val legacyTest = WalletScope.legacy(true)

    private fun prefsOf(scope: WalletScope): Set<String> =
        (WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS).map { WalletStores.prefsName(it, scope) }.toSet() +
            WalletStores.seedRecordPrefs(scope)

    private fun filesOf(scope: WalletScope): Set<String> =
        WalletStores.WALLET_AND_NETWORK_FILES.map { "files/" + WalletStores.fileName(it, scope) }.toSet() +
            "databases/${WalletStores.databaseName(scope)}"

    private fun aliasesOf(scope: WalletScope) = setOf(WalletStores.keyAlias(scope), WalletStores.keyAliasV2(scope))

    private inner class Device(
        val releaseLegacyDbWrap: () -> Boolean = { true },
    ) {
        val vault = FakeVault()
        val prefs = InMemoryPrefsFactory()
        val root: File = tmp.newFolder()
        val ctx: Context = wipeTestContext(prefs, root).also {
            every { it.deleteSharedPreferences(any()) } returns true
        }
        val registry = WalletRegistry(
            prefs = prefs.prefsFor(WalletRegistry.PREFS),
            recordExists = { scope ->
                val p = prefs.prefsFor(WalletStores.seedRecordPrefs(scope))
                p.contains("encrypted_seed") || p.contains("encrypted_seed_v2")
            },
            recordsOnDisk = { emptyList() },
        )
        var wipes = 0

        fun eraser(release: () -> Boolean = releaseLegacyDbWrap) =
            WalletStoreEraser(ctx, vault.asKeystoreAliases(), release, { name -> prefs.prefsFor(name).all.isNotEmpty() })

        fun removal(active: WalletScope, eraser: WalletStoreEraser = eraser(), registry: WalletRegistry = this.registry) = WalletRemoval(
            registry, eraser, active,
            wipeAll = { wipes++; WipeReport(seedGone = true, everythingCleared = true) },
        )

        fun housekeeping(release: () -> Boolean = releaseLegacyDbWrap) = WalletHousekeeping(registry, eraser(release))

        /** Every store, file and alias of [scope] (both networks for legacy), each holding something. */
        fun populate(scope: WalletScope) {
            val scopes = if (scope.isLegacy) listOf(legacyMain, legacyTest) else listOf(scope)
            for (s in scopes) {
                prefsOf(s).forEach { prefs.prefsFor(it).seed("held", "$it-value") }
                filesOf(s).forEach { File(root, it).apply { parentFile!!.mkdirs() }.writeBytes(it.toByteArray()) }
            }
            prefs.prefsFor(WalletStores.seedRecordPrefs(scope)).seed("encrypted_seed_v2", "sealed-${scope.walletId}")
            aliasesOf(scope).forEach(vault::createUnboundKey)
        }

        fun installLegacy() = populate(legacyMain)

        fun addWallet(testnet: Boolean, makeActive: Boolean = false): String {
            val entry = registry.reserve(testnet, WalletOrigin.RESTORED, 1L)!!
            populate(WalletScope(entry.id, testnet))
            assertTrue(registry.complete(entry.id, makeActive))
            return entry.id
        }

        /** What [scope] holds, read from every one of its stores. */
        fun snapshot(scope: WalletScope): Map<String, Any?> {
            val scopes = if (scope.isLegacy) listOf(legacyMain, legacyTest) else listOf(scope)
            val out = linkedMapOf<String, Any?>()
            for (s in scopes) {
                prefsOf(s).forEach { out["prefs:$it"] = prefs.prefsFor(it).all.toMap() }
                filesOf(s).forEach { out["file:$it"] = File(root, it).takeIf(File::exists)?.readBytes()?.toList() }
            }
            aliasesOf(scope).forEach { out["alias:$it"] = it in vault.keys.keys }
            return out
        }

        fun everything(): Any = listOf(
            prefs.names().associateWith { prefs.prefsFor(it).all.toMap() },
            root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() },
            vault.keys.keys.toSet(),
        )
    }

    @Test fun `removing one wallet leaves every byte of the others`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        val c = d.addWallet(false)
        val scopeC = WalletScope(c, false)
        val legacyBefore = d.snapshot(legacyMain)
        val cBefore = d.snapshot(scopeC)

        assertEquals(RemoveResult.Removed, d.removal(active = legacyMain).remove(b, "Wallet 2", "Wallet 2"))

        assertEquals(legacyBefore, d.snapshot(legacyMain))
        assertEquals(cBefore, d.snapshot(scopeC))
        assertFalse(d.eraser().anythingLeft(WalletScope(b, false)))
        assertNull(d.registry.find(b))
        assertEquals(listOf("legacy", c), d.registry.list(false).map { it.id })
    }

    @Test fun `removing the active wallet moves the active id, erases nothing, and the next launch erases it`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false, makeActive = true)
        val scopeB = WalletScope(b, false)
        assertEquals(b, d.registry.activeId(false))
        val bBefore = d.snapshot(scopeB)

        assertEquals(RemoveResult.RemovedRestartNeeded, d.removal(active = scopeB).remove(b, "Wallet 2", "Wallet 2"))

        assertEquals("legacy", d.registry.activeId(false))
        assertEquals(EntryState.REMOVING, d.registry.find(b)!!.state)
        assertEquals("B was erased under the running process", bBefore, d.snapshot(scopeB))

        assertEquals(1, d.housekeeping().run())
        assertFalse(d.eraser().anythingLeft(scopeB))
        assertNull(d.registry.find(b))
    }

    @Test fun `a removal killed mid-erase finishes at the next launch`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        val scopeB = WalletScope(b, false)
        val dying = spyk(d.eraser())
        every { dying.eraseAliases(any()) } throws IllegalStateException("process killed")

        assertEquals(RemoveResult.Incomplete, d.removal(active = legacyMain, eraser = dying).remove(b, "Wallet 2", "Wallet 2"))
        assertEquals(EntryState.REMOVING, d.registry.find(b)!!.state)
        assertTrue("the record was not erased first", d.prefs.prefsFor(WalletStores.seedRecordPrefs(scopeB)).all.isEmpty())

        assertEquals(1, d.housekeeping().run())
        assertFalse(d.eraser().anythingLeft(scopeB))
    }

    @Test fun `removing the last wallet is the full wipe`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val before = d.everything()

        val result = d.removal(active = legacyMain).remove("legacy", "Wallet 1", "Wallet 1")

        assertEquals(RemoveResult.WipedEverything(WipeReport(seedGone = true, everythingCleared = true)), result)
        assertEquals(1, d.wipes)
        assertEquals("the stub wipe touches nothing; removal itself wrote nothing", before, d.everything())
    }

    @Test fun `removing legacy keeps its key while the database key record depends on it`() = runBlocking {
        val d = Device(releaseLegacyDbWrap = { false })
        d.installLegacy()
        val c = d.addWallet(false, makeActive = true)

        assertEquals(
            RemoveResult.DatabaseKeyStillOnLegacy,
            d.removal(active = WalletScope(c, false)).remove("legacy", "Wallet 1", "Wallet 1"),
        )
        assertTrue("dgb_wallet_master" in d.vault.keys.keys)
        assertEquals(EntryState.REMOVING, d.registry.find("legacy")!!.state)
        assertEquals(listOf(c), d.registry.list(false).map { it.id })

        assertEquals(0, d.housekeeping().run())
        assertEquals(EntryState.REMOVING, d.registry.find("legacy")!!.state)
        assertTrue("dgb_wallet_master" in d.vault.keys.keys)
    }

    @Test fun `removing legacy moves both networks' active id`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val c = d.addWallet(false)
        val t = d.addWallet(true)
        assertEquals("legacy", d.registry.activeId(false))
        assertEquals("legacy", d.registry.activeId(true))

        assertEquals(RemoveResult.Removed, d.removal(active = WalletScope(c, false)).remove("legacy", "Wallet 1", "Wallet 1"))

        assertEquals(c, d.registry.activeId(false))
        assertEquals(t, d.registry.activeId(true))
        assertFalse(d.eraser().anythingLeft(legacyMain))
        assertFalse(d.eraser().anythingLeft(legacyTest))
    }

    @Test fun `removing legacy keeps the custom node endpoint`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val c = d.addWallet(false, makeActive = true)
        d.prefs.prefsFor("dgb_reconcile").apply {
            seed("custom_rpc_endpoint", "https://node.example")
            seed("reconcile_done", true)
        }

        assertEquals(RemoveResult.Removed, d.removal(active = WalletScope(c, false)).remove("legacy", "Wallet 1", "Wallet 1"))

        assertEquals("https://node.example", d.prefs.prefsFor("dgb_reconcile").getString("custom_rpc_endpoint", null))
    }

    @Test fun `a wrong typed name removes nothing`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        val before = d.everything()

        assertEquals(RemoveResult.NameMismatch, d.removal(active = legacyMain).remove(b, "Wallet", "Wallet 2"))

        assertEquals(before, d.everything())
        assertEquals(0, d.wipes)
        assertEquals(EntryState.READY, d.registry.find(b)!!.state)
    }

    @Test fun `removing a mainnet wallet while only testnet wallets remain is not the full wipe`() = runBlocking {
        val d = Device()
        val b = d.addWallet(false, makeActive = true)
        val t = d.addWallet(true)

        assertEquals(RemoveResult.Removed, d.removal(active = WalletScope(t, true)).remove(b, "Wallet 1", "Wallet 1"))

        assertEquals(0, d.wipes)
        assertNull(d.registry.find(b))
        assertEquals(listOf(t), d.registry.list(true).map { it.id })
        assertFalse(d.eraser().anythingLeft(WalletScope(b, false)))
    }

    @Test fun `removing the only READY wallet while another is REMOVING is the full wipe`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        assertTrue(d.registry.markRemoving(b))

        val result = d.removal(active = legacyMain).remove("legacy", "Wallet 1", "Wallet 1")

        assertTrue("$result", result is RemoveResult.WipedEverything)
        assertEquals(1, d.wipes)
    }

    @Test fun `a refused mark removes nothing and says so`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        val refusing = spyk(d.registry)
        every { refusing.markRemoving(any()) } returns false
        val before = d.everything()

        assertEquals(RemoveResult.NotRemoved, d.removal(active = legacyMain, registry = refusing).remove(b, "Wallet 2", "Wallet 2"))

        assertEquals(before, d.everything())
        assertEquals(EntryState.READY, d.registry.find(b)!!.state)
    }

    @Test fun `an unknown wallet removes nothing`() = runBlocking {
        val d = Device()
        d.installLegacy()
        d.addWallet(false)
        val before = d.everything()

        assertEquals(RemoveResult.NotRemoved, d.removal(active = legacyMain).remove("00000000deadbeef", "Wallet 9", "Wallet 9"))

        assertEquals(before, d.everything())
    }

    @Test fun `a second submit of a removal already marked is left to housekeeping`() = runBlocking {
        val d = Device()
        d.installLegacy()
        val b = d.addWallet(false)
        assertTrue(d.registry.markRemoving(b))
        val refusing = spyk(d.registry)
        every { refusing.markRemoving(any()) } returns false

        assertEquals(RemoveResult.Incomplete, d.removal(active = legacyMain, registry = refusing).remove(b, "Wallet 2", "Wallet 2"))
        assertEquals(EntryState.REMOVING, d.registry.find(b)!!.state)
    }
}
