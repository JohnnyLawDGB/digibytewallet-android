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
    private val foregroundVisits = app.aroundtheblock.wallet.ForegroundVisits()

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
        onboardingViewModel(walletManager, pinManager, mockk<RecoveryScanService>(relaxed = true), mode = mode, registry = registry, foregroundVisits = foregroundVisits)
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
     * The PIN entered for a setup is held for the stay in the foreground it was entered in. With no
     * wallet on the selected network the app has no lock to re-gate it once the app is left.
     */
    @Test fun `the entered PIN is owed again once the app has left the foreground`() = runBlocking {
        anotherWalletWithItsPin()
        val vm = vm()
        assertTrue(vm.keepsStoredPin())
        vm.onCurrentPinVerified()
        assertFalse(vm.currentPinOwed())

        foregroundVisits.left()

        assertTrue("the PIN entered before the app was left still opens the flow", vm.currentPinOwed())
        assertFalse("a create went through on a PIN entered before the app was left", await { vm.createWallet(it) })
        assertFalse("a restore went through on a PIN entered before the app was left", await { vm.recoverWallet(it) })
        verify(exactly = 0) { walletManager.createWallet(any(), any()) }
        verify(exactly = 0) { walletManager.recoverWallet(any(), any(), any()) }

        // Entered again, at the write: it goes through.
        vm.onCurrentPinVerified()
        assertFalse(vm.currentPinOwed())
        assertTrue(await { vm.createWallet(it) })
    }

    /** The seed-key prompt of a write may itself take the app out of the foreground: that write still lands. */
    @Test fun `a write asked for while the PIN is held is not refused by its own prompt`() {
        anotherWalletWithItsPin()
        val vm = vm().also { it.onCurrentPinVerified() }
        every { walletManager.prepareSeedKeyForNewRecord() } returns SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL
        assertTrue(await { vm.createWallet(seedKeyPrompt = { foregroundVisits.left() }, onResult = it) })
        verify(exactly = 1) { walletManager.createWallet(phrase, any()) }
    }

    /** Before any screen has read whether the PIN is kept, the answer is not "no PIN": the PIN is owed. */
    @Test fun `a first run that has not read the PIN store yet owes the PIN`() {
        anotherWalletWithItsPin()
        assertTrue(vm().currentPinOwed())
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

    /**
     * A restore writes its record before PIN setup, and PIN setup opens on a new ViewModel (the
     * restore pops the onboarding entry). With that record and no PIN stored, it shows the keypad
     * and stores the first PIN; a flow that has stored its own PIN is not then asked for it.
     */
    @Test fun `a clean device's restore sets its PIN after its own record`() = runBlocking {
        every { registry.anyRecordExists() } returns false
        every { pinManager.hasPin() } returns false
        val restore = vm()
        assertFalse(restore.keepsStoredPin())
        assertTrue(await { restore.recoverWallet(it) })
        // The restore's record is on the device now, and still no PIN.
        every { registry.anyRecordExists() } returns true

        val pinSetup = vm()
        assertFalse("PIN setup after a restore shows no keypad", pinSetup.keepsStoredPin())
        assertTrue("PIN setup after a restore stored no PIN", await { pinSetup.setPin("999999", it) })
        // The PIN is stored now. Read again, the flow keeps it, and holds it: nothing is asked.
        every { pinManager.hasPin() } returns true
        assertTrue(pinSetup.keepsStoredPin())
        assertFalse("the flow is asked for the PIN it has just set", pinSetup.currentPinOwed())
    }

    /**
     * A wallet record with no PIN stored (the app still starts on the first run): PIN setup stores
     * the first PIN, and the create that follows it lands. It is not refused for want of the PIN
     * this flow has just set.
     */
    @Test fun `a first run beside a record with no PIN sets its PIN and then creates`() = runBlocking {
        every { registry.anyRecordExists() } returns true
        every { pinManager.hasPin() } returns false
        val vm = vm()
        assertFalse(vm.keepsStoredPin())
        assertTrue(await { vm.setPin("999999", it) })
        every { pinManager.hasPin() } returns true
        assertTrue("the create was refused after this flow set its own PIN", await { vm.createWallet(it) })
        verify(exactly = 1) { walletManager.createWallet(phrase, any()) }
    }

    @Test fun `adding a wallet asks nothing here and sets no PIN`() = runBlocking {
        anotherWalletWithItsPin()
        val vm = vm(OnboardingMode.ADD)
        assertFalse(vm.keepsStoredPin())
        assertFalse(vm.currentPinOwed())
        assertFalse("add mode set a PIN", await { vm.setPin("999999", it) })
    }
}
