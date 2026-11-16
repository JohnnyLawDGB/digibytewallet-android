package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import app.aroundtheblock.wallet.core.recovery.DerivationProfile
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.AddResult
import app.aroundtheblock.wallet.core.wallets.EntryState
import app.aroundtheblock.wallet.core.wallets.QuickStart
import app.aroundtheblock.wallet.core.wallets.QuickStartStore
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletProvisioner
import app.aroundtheblock.wallet.core.wallets.WalletScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [OnboardingViewModel.runQuickStart] and [OnboardingViewModel.commitAdd] together: what the quick
 * start finds is held in memory and stored only in the added wallet's own scope, after it is
 * added and before the switch; an own-node lookup with no own node set sends nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingQuickStartTest {

    private val scans = mockk<QuickStartScans>()
    private val provisioner = mockk<WalletProvisioner>(relaxed = true)
    private val service = mockk<RecoveryScanService>()
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val entry = WalletEntry("0123456789abcdef", null, 2, false, 1L, WalletOrigin.RESTORED, 1, EntryState.PENDING)
    private val native = DerivationProfile.BUILT_INS.filter { it.isNative }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(ActiveWallet, QuickStartStore)
        every { ActiveWallet.scope(any()) } returns WalletScope.legacy(false)
        every { provisioner.abandon(any()) } returns true
    }

    @After fun tearDown() {
        unmockkObject(ActiveWallet, QuickStartStore)
        Dispatchers.resetMain()
    }

    private fun vm(): OnboardingViewModel =
        onboardingViewModel(
            mockk<WalletManager>(relaxed = true), mockk<PinManager>(relaxed = true), mockk(relaxed = true),
            mode = OnboardingMode.ADD, provisioner = provisioner, quickStartScans = scans,
        ).apply { setRecoveryMnemonic(phrase) }

    private fun done(vararg sats: Long) = RecoveryScanService.State.Done(
        results = native.mapIndexed { i, p ->
            val amount = sats.getOrElse(i) { 0L }
            RecoveryScanService.ProfileResult(
                profile = p, addresses = listOf("addr$i"), derivedAddresses = emptyList(),
                utxos = if (amount > 0) listOf(UtxoEntry("t$i", 0, amount, "addr$i", 1L)) else emptyList(),
                rawTxs = emptyMap(),
            )
        },
    )

    private fun awaitState(vm: OnboardingViewModel, until: (QuickStartUi) -> Boolean): QuickStartUi {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!until(vm.quickStart.value) && System.nanoTime() < deadline) Thread.sleep(10)
        return vm.quickStart.value
    }

    @Test fun `an own-node lookup with no own node set sends nothing`() {
        every { scans.serviceFor(QuickStartSource.OWN_NODE, null) } returns null
        val v = vm()
        v.runQuickStart(QuickStartSource.OWN_NODE)
        assertEquals(QuickStartUi.Failed, v.quickStart.value)
        coVerify(exactly = 0) { service.scan(any(), any()) }
    }

    @Test fun `the quick start counts both native paths and is stored only for the added wallet, before the switch`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        coEvery { service.scan(phrase, null) } returns done(100_000_000L, 50_000_000L)
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.READY)
        every { provisioner.add(any(), any(), any(), any()) } returns AddResult.Added(entry)
        val v = vm()

        v.runQuickStart(QuickStartSource.DIGISCOPE)
        val found = awaitState(v) { it is QuickStartUi.Found } as QuickStartUi.Found
        assertEquals("taproot coins were dropped", 150_000_000L, found.quickStart.unverifiedSat)
        assertEquals("digiscope.me", found.quickStart.sourceLabel)

        val written = slot<QuickStart>()
        var addAnsweredAtWrite: AddResult? = AddResult.Failed
        every { QuickStartStore.write(any(), WalletScope(entry.id, false), capture(written)) } answers {
            addAnsweredAtWrite = v.addResult.value
            true
        }
        val answered = CountDownLatch(1)
        v.commitAdd(null) { answered.countDown() }
        assertTrue(answered.await(5, TimeUnit.SECONDS))

        assertEquals(found.quickStart, written.captured)
        assertNull("stored after the switch could start", addAnsweredAtWrite)
        verify(exactly = 1) { QuickStartStore.write(any(), any(), any()) }
    }

    @Test fun `a lookup with a path unanswered shows no figure and stores nothing`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        val partial = done(100_000_000L).let { d ->
            d.copy(results = d.results.mapIndexed { i, r -> if (i == 1) r.copy(reachableBackend = false) else r })
        }
        coEvery { service.scan(phrase, null) } returns partial
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.READY)
        every { provisioner.add(any(), any(), any(), any()) } returns AddResult.Added(entry)
        val v = vm()

        v.runQuickStart(QuickStartSource.DIGISCOPE)
        assertEquals(QuickStartUi.Incomplete, awaitState(v) { it != QuickStartUi.Running && it != QuickStartUi.Idle })
        val answered = CountDownLatch(1)
        v.commitAdd(null) { answered.countDown() }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        verify(exactly = 0) { QuickStartStore.write(any(), any(), any()) }
    }

    @Test fun `a capped scan shows no figure and stores nothing`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        val capped = done(100_000_000L).let { d -> d.copy(results = d.results.mapIndexed { i, r -> if (i == 0) r.copy(capped = true) else r }) }
        coEvery { service.scan(phrase, null) } returns capped
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.READY)
        every { provisioner.add(any(), any(), any(), any()) } returns AddResult.Added(entry)
        val v = vm()

        v.runQuickStart(QuickStartSource.DIGISCOPE)
        assertEquals(QuickStartUi.Incomplete, awaitState(v) { it != QuickStartUi.Running && it != QuickStartUi.Idle })
        val answered = CountDownLatch(1)
        v.commitAdd(null) { answered.countDown() }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        verify(exactly = 0) { QuickStartStore.write(any(), any(), any()) }
    }

    @Test fun `an own-node lookup goes to the node shown in the offer`() {
        every { scans.customEndpoint() } returns "https://node.example"
        every { scans.serviceFor(QuickStartSource.OWN_NODE, "https://node.example") } returns (service to "node.example")
        coEvery { service.scan(phrase, null) } returns done(1L)
        val v = vm()
        val offer = kotlinx.coroutines.runBlocking { v.quickStartOffer() }
        assertEquals(listOf(QuickStartSource.OWN_NODE, QuickStartSource.DIGISCOPE), offer.choices)
        v.runQuickStart(QuickStartSource.OWN_NODE)
        assertTrue(awaitState(v) { it is QuickStartUi.Found } is QuickStartUi.Found)
        verify(exactly = 1) { scans.serviceFor(QuickStartSource.OWN_NODE, "https://node.example") }
    }

    @Test fun `a quick start cancelled by another scan is not left running`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        coEvery { service.scan(phrase, null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val v = vm()
        v.runQuickStart(QuickStartSource.DIGISCOPE)
        assertEquals(QuickStartUi.Running, v.quickStart.value)
        v.runRecoveryScan()   // shares scanJob: cancels the quick start's
        assertEquals(QuickStartUi.Idle, awaitState(v) { it != QuickStartUi.Running })
    }

    @Test fun `leaving a running quick start stops it and keeps nothing`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        coEvery { service.scan(phrase, null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val v = vm()
        v.runQuickStart(QuickStartSource.DIGISCOPE)
        v.cancelQuickStart()
        assertEquals(QuickStartUi.Idle, v.quickStart.value)
    }

    @Test fun `a new phrase drops the quick start`() {
        every { scans.serviceFor(QuickStartSource.DIGISCOPE, any()) } returns (service to "digiscope.me")
        coEvery { service.scan(phrase, null) } returns done(100_000_000L)
        val v = vm()
        v.runQuickStart(QuickStartSource.DIGISCOPE)
        awaitState(v) { it is QuickStartUi.Found }
        v.setRecoveryMnemonic(phrase)
        assertEquals(QuickStartUi.Idle, v.quickStart.value)
    }
}
