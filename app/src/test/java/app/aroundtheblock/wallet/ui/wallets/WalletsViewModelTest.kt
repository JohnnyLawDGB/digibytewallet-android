package app.aroundtheblock.wallet.ui.wallets

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.WalletSwitcher
import app.aroundtheblock.wallet.core.WipeReport
import app.aroundtheblock.wallet.core.wallets.EntryState
import app.aroundtheblock.wallet.core.wallets.RemoveResult
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletIds
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletRemoval
import app.aroundtheblock.wallet.core.wallets.WalletScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletsViewModelTest {
    private val b = "bbbbbbbbbbbbbbbb"
    private val dispatcher = UnconfinedTestDispatcher()
    private val registry = mockk<WalletRegistry>(relaxed = true)
    private val switcher = mockk<WalletSwitcher>()
    private val removal = mockk<WalletRemoval>()
    private val calls = mutableListOf<String>()

    private val legacy = WalletEntry(WalletIds.LEGACY, null, 1, null, 0L, WalletOrigin.CREATED, 0, EntryState.READY)
    private val walletB = WalletEntry(b, null, 2, false, 0L, WalletOrigin.CREATED, 1, EntryState.READY)

    /** The registry's mainnet list, reordered by [WalletRegistry.reorder] as the real one is. */
    private var listed = listOf(legacy, walletB)

    private var known: Map<String, LastKnown> = mapOf(
        WalletIds.LEGACY to LastKnown(150_000_000, null),
        b to LastKnown(7, null),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { registry.list(false) } answers { listed }
        every { registry.canReserve(false) } returns true
        every { registry.activeId(false) } returns WalletIds.LEGACY
        every { registry.reorder(false, any()) } answers {
            val ids = secondArg<List<String>>()
            listed = ids.map { id -> listed.first { it.id == id } }
            true
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = WalletsViewModel(
        registry = registry,
        switcher = switcher,
        removal = removal,
        testnet = false,
        lastKnown = { scope -> known.getValue(scope.walletId) },
        defaultName = { n -> "Wallet $n" },
        runningId = WalletIds.LEGACY,
        restart = { calls += "restart" },
        afterWipe = { calls += "afterWipe"; false },
        io = dispatcher,
    )

    @Test fun `rows show each wallet's own last balance`() {
        assertEquals(listOf(150_000_000L, 7L), vm().rows.value.map { it.lastBalanceSat })
    }

    @Test fun `a wallet that has never synced shows no balance`() {
        known = known + (b to LastKnown(null, null))
        val row = vm().rows.value.single { it.id == b }
        assertNull(row.lastBalanceSat)
        assertNull(row.lastDdCents)
    }

    @Test fun `default names follow the wallet number, not the order`() {
        val vm = vm()
        assertTrue(vm.move(b, -1))
        assertEquals(listOf("Wallet 2", "Wallet 1"), vm.rows.value.map { it.shownName })
    }

    @Test fun `a move past either end changes nothing`() {
        val vm = vm()
        assertFalse(vm.move(WalletIds.LEGACY, -1))
        assertFalse(vm.move(b, +1))
        verify(exactly = 0) { registry.reorder(any(), any()) }
    }

    @Test fun `an own name is shown instead of the default`() {
        listed = listOf(legacy.copy(name = "Savings"), walletB)
        assertEquals(listOf("Savings", "Wallet 2"), vm().rows.value.map { it.shownName })
    }

    @Test fun `the eighth wallet hides Add`() {
        // canReserve counts READY and PENDING entries (F31); the view model asks it, not the list size.
        every { registry.canReserve(false) } returns false
        assertFalse(vm().canAdd.value)
    }

    @Test fun `Add shows while the network has room`() {
        assertTrue(vm().canAdd.value)
    }

    @Test fun `legacy is marked as also the testnet wallet`() {
        val rows = vm().rows.value
        assertTrue(rows.single { it.id == WalletIds.LEGACY }.alsoTestnet)
        assertFalse(rows.single { it.id == b }.alsoTestnet)
    }

    @Test fun `only the one wallet on the device is flagged as the last`() {
        assertNull(vm().onlyWalletId.value)
        listed = listOf(walletB)
        assertEquals(b, vm().onlyWalletId.value)
    }

    @Test fun `the wallet this process runs is the active row`() {
        assertEquals(listOf(true, false), vm().rows.value.map { it.active })
    }

    @Test fun `removing passes the shown name and drops the row`() = runTest(dispatcher) {
        coEvery { removal.remove(b, "Wallet 2", "Wallet 2") } answers {
            listed = listOf(legacy)
            RemoveResult.Removed
        }
        val vm = vm()
        assertEquals(RemoveResult.Removed, vm.remove(b, "Wallet 2"))
        assertEquals(listOf(WalletIds.LEGACY), vm.rows.value.map { it.id })
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `removing the running wallet restarts`() = runTest(dispatcher) {
        coEvery { removal.remove(WalletIds.LEGACY, any(), "Wallet 1") } returns RemoveResult.RemovedRestartNeeded
        assertEquals(RemoveResult.RemovedRestartNeeded, vm().remove(WalletIds.LEGACY, "Wallet 1"))
        assertEquals(listOf("restart"), calls)
    }

    @Test fun `removing the last wallet hands the wipe over`() = runTest(dispatcher) {
        val report = WipeReport(seedGone = true, everythingCleared = true)
        coEvery { removal.remove(b, any(), any()) } returns RemoveResult.WipedEverything(report)
        vm().remove(b, "Wallet 2")
        assertEquals(listOf("afterWipe"), calls)
    }

    @Test fun `a removal of an unknown wallet is refused before WalletRemoval runs`() = runTest(dispatcher) {
        assertEquals(RemoveResult.NotRemoved, vm().remove("cccccccccccccccc", "Wallet 3"))
        coVerify(exactly = 0) { removal.remove(any(), any(), any()) }
    }

    @Test fun `switching goes through the switcher`() {
        every { switcher.switchTo(b) } returns WalletSwitcher.Result.RESTARTING
        assertEquals(WalletSwitcher.Result.RESTARTING, vm().switchTo(b))
    }

    // -- the production reader -------------------------------------------------------------------

    private fun prefs(values: Map<String, Any>): SharedPreferences = mockk {
        every { contains(any()) } answers { firstArg<String>() in values }
        every { getLong(any(), any()) } answers { (values[firstArg()] as? Long) ?: secondArg() }
        every { getBoolean(any(), any()) } answers { (values[firstArg()] as? Boolean) ?: secondArg() }
    }

    private fun context(files: Map<String, SharedPreferences>, opened: MutableList<String>): Context = mockk {
        every { getSharedPreferences(any(), any()) } answers {
            val name = firstArg<String>()
            opened += name
            files[name] ?: prefs(emptyMap())
        }
    }

    @Test fun `the production reader is per wallet and honours has_synced`() {
        val opened = mutableListOf<String>()
        val legacyPrefs = prefs(mapOf("last_balance" to 150_000_000L, "has_synced" to true))
        val scopeB = WalletScope(b, false)

        val unsynced = context(
            mapOf("dgb_sync_data" to legacyPrefs, "dgb_sync_data_$b" to prefs(mapOf("last_balance" to 7L))),
            opened,
        )
        assertEquals(LastKnown(null, null), readLastKnown(unsynced, scopeB))

        val synced = context(
            mapOf(
                "dgb_sync_data" to legacyPrefs,
                "dgb_sync_data_$b" to prefs(mapOf("last_balance" to 7L, "has_synced" to true)),
            ),
            opened,
        )
        assertEquals(7L, readLastKnown(synced, scopeB).balanceSat)

        assertTrue(opened.isNotEmpty())
        assertTrue("legacy's store was opened for B: $opened", opened.all { it == "dgb_sync_data_$b" })
    }

    @Test fun `the production reader gives DigiDollar cents once synced`() {
        val ctx = context(
            mapOf("dgb_sync_data" to prefs(mapOf("last_balance" to 5L, "last_dd_balance" to 250L, "has_synced" to true))),
            mutableListOf(),
        )
        assertEquals(LastKnown(5L, 250L), readLastKnown(ctx, WalletScope.legacy(false)))
    }
}
