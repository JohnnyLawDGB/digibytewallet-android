package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.WalletState
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.ui.navigation.startDestinationFor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * INT-2026-11-19-B. Invariant: no onboarding path sets or replaces the app PIN while any wallet
 * record exists.
 *
 * The state: the selected network has no wallet, so the app starts on the first run, while another
 * wallet's record and the app PIN are stored. Driven through the real [OnboardingViewModel] and the
 * real `OnboardingPin` (see [onboardingViewModel]); the registry and the PIN store answer as that
 * device does. What the PIN store itself holds afterwards, and how the registry comes to that
 * state, is `OnboardingPinTest` in `core`, over the real PinManager and WalletRegistry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingKeepsPinTest {

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val walletManager = mockk<WalletManager>()
    private val pinManager = mockk<PinManager>(relaxed = true)
    private val registry = mockk<WalletRegistry>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { walletManager.createWallet(any(), any()) } returns true
        every { walletManager.recoverWallet(any(), any(), any()) } returns true
        every { walletManager.prepareSeedKeyForNewRecord() } returns SeedKeyReadiness.READY
    }

    @After fun tearDown() = Dispatchers.resetMain()

    /** Another network's wallet and the app PIN are on the device. */
    private fun anotherWalletWithItsPin() {
        every { registry.anyRecordExists() } returns true
        every { pinManager.hasPin() } returns true
    }

    private fun vm(mode: OnboardingMode = OnboardingMode.FIRST_RUN) =
        onboardingViewModel(walletManager, pinManager, mockk<RecoveryScanService>(relaxed = true), mode = mode, registry = registry)
            .also { it.setRecoveryMnemonic(phrase) }

    private fun await(start: ((Boolean) -> Unit) -> Unit): Boolean {
        val done = CountDownLatch(1)
        var answer = false
        start { answer = it; done.countDown() }
        assertTrue("no answer", done.await(5, TimeUnit.SECONDS))
        return answer
    }

    @Test fun `the app starts on the first run whatever the PIN store holds`() {
        // The active wallet of a network with no wallet holds no record: NoWallet, and the first run.
        assertEquals(
            onboardingRoute(OnboardingMode.FIRST_RUN),
            startDestinationFor(WalletState.NoWallet, storeAvailable = true) { true }.route,
        )
    }

    @Test fun `that first run keeps the stored PIN and owes it before anything else`() = runBlocking {
        anotherWalletWithItsPin()
        val vm = vm()
        assertTrue("the flow does not keep the PIN that guards the other wallet", vm.keepsStoredPin())
        assertTrue("the current PIN is not asked for", vm.currentPinOwed())
        vm.onCurrentPinVerified()
        assertFalse(vm.currentPinOwed())
    }

    @Test fun `that first run cannot set a PIN, before or after the current one is entered`() {
        anotherWalletWithItsPin()
        val vm = vm()
        assertFalse("PIN setup replaced the PIN that guards the other wallet", await { vm.setPin("999999", it) })
        vm.onCurrentPinVerified()
        assertFalse("PIN setup replaced the PIN once the current one was entered", await { vm.setPin("999999", it) })
    }

    @Test fun `a create there writes nothing until the current PIN is entered, then sets no PIN`() {
        anotherWalletWithItsPin()
        val vm = vm()
        assertFalse(await { vm.createWallet(it) })
        verify(exactly = 0) { walletManager.createWallet(any(), any()) }
        verify(exactly = 0) { walletManager.prepareSeedKeyForNewRecord() }

        val entered = vm().also { it.onCurrentPinVerified() }
        assertTrue(await { entered.createWallet(seedKeyPrompt = {}, onResult = it) })
        verify(exactly = 1) { walletManager.createWallet(phrase, any()) }
    }

    @Test fun `a restore there writes nothing until the current PIN is entered, then sets no PIN`() {
        anotherWalletWithItsPin()
        val vm = vm()
        assertFalse(await { vm.recoverWallet(it) })
        verify(exactly = 0) { walletManager.recoverWallet(any(), any(), any()) }
        verify(exactly = 0) { walletManager.prepareSeedKeyForNewRecord() }

        val entered = vm().also { it.onCurrentPinVerified() }
        assertTrue(await { entered.recoverWallet(seedKeyPrompt = {}, onResult = it) })
        verify(exactly = 1) { walletManager.recoverWallet(phrase, any(), any()) }
    }

    @Test fun `a PIN store that cannot be read beside a wallet is treated as guarding it`() = runBlocking {
        every { registry.anyRecordExists() } returns true
        every { pinManager.hasPin() } throws IllegalStateException("the PIN store could not be opened")
        val vm = vm()
        assertTrue(vm.keepsStoredPin())
        assertFalse(await { vm.createWallet(it) })
        verify(exactly = 0) { walletManager.createWallet(any(), any()) }
    }

    /**
     * The kept-PIN step starts its create by itself when it is composed. Composed again while that
     * create still runs, it must get the running create's answer, never start a second one.
     */
    @Test fun `a second create while one runs joins it`() {
        anotherWalletWithItsPin()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { walletManager.createWallet(any(), any()) } answers {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            true
        }
        val vm = vm().also { it.onCurrentPinVerified() }
        val answers = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val done = CountDownLatch(2)
        vm.createWallet { answers += it; done.countDown() }
        assertTrue("the first create did not start", started.await(5, TimeUnit.SECONDS))
        vm.createWallet { answers += it; done.countDown() }
        release.countDown()
        assertTrue("a caller got no answer", done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(true, true), answers.toList())
        verify(exactly = 1) { walletManager.createWallet(any(), any()) }
    }

    // GUARDS, not red-then-green: what the first run and the add did before and must keep doing.

    @Test fun `a clean device still sets a PIN and creates without being asked for one`() = runBlocking {
        every { registry.anyRecordExists() } returns false
        every { pinManager.hasPin() } returns false
        val vm = vm()
        assertFalse(vm.keepsStoredPin())
        assertFalse(vm.currentPinOwed())
        assertTrue(await { vm.setPin("999999", it) })
        // PIN setup has stored the PIN by the time it creates the wallet.
        every { pinManager.hasPin() } returns true
        assertTrue(await { vm.createWallet(it) })
        verify(exactly = 1) { walletManager.createWallet(phrase, any()) }
    }

    @Test fun `a clean device's restore sets its PIN after its own record`() = runBlocking {
        every { registry.anyRecordExists() } returns false
        every { pinManager.hasPin() } returns false
        val vm = vm()
        assertFalse(vm.keepsStoredPin())
        assertTrue(await { vm.recoverWallet(it) })
        // The restore's record is on the device now, and still no PIN.
        every { registry.anyRecordExists() } returns true
        assertTrue(await { vm.setPin("999999", it) })
        assertFalse("the flow changed its mind after writing its own record", vm.keepsStoredPin())
    }

    @Test fun `adding a wallet asks nothing here and sets no PIN`() = runBlocking {
        anotherWalletWithItsPin()
        val vm = vm(OnboardingMode.ADD)
        assertFalse(vm.keepsStoredPin())
        assertFalse(vm.currentPinOwed())
        assertFalse("add mode set a PIN", await { vm.setPin("999999", it) })
    }
}
