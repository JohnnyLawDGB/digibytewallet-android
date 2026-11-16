package app.aroundtheblock.wallet.ui.onboarding

import androidx.lifecycle.ViewModelStore
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.AddResult
import app.aroundtheblock.wallet.core.wallets.EntryState
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletProvisioner
import app.aroundtheblock.wallet.core.wallets.WalletScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [OnboardingViewModel.commitAdd], run for real over a mocked [WalletProvisioner]: a reservation that
 * gets no answer from the add (a throw, or the ViewModel cleared while the seed-key prompt is up) is
 * given back; the cap is reported; the passphrase is zeroed whatever the outcome; and a second
 * commit while one runs does nothing. `onCleared()` is reached through [ViewModelStore.clear].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingCommitAddTest {

    private val walletManager = mockk<WalletManager>(relaxed = true)
    private val pinManager = mockk<PinManager>(relaxed = true)
    private val scanService = mockk<RecoveryScanService>(relaxed = true)
    private val provisioner = mockk<WalletProvisioner>(relaxed = true)

    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val entry = WalletEntry("0123456789abcdef", null, 2, false, 1L, WalletOrigin.RESTORED, 1, EntryState.PENDING)

    @Before fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped += t }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(ActiveWallet)
        every { ActiveWallet.scope(any()) } returns WalletScope.legacy(false)
        every { provisioner.abandon(any()) } returns true
    }

    @After fun tearDown() {
        unmockkObject(ActiveWallet)
        Dispatchers.resetMain()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun addingViewModel(): OnboardingViewModel =
        onboardingViewModel(walletManager, pinManager, scanService, mode = OnboardingMode.ADD, provisioner = provisioner).apply {
            setRecoveryMnemonic(phrase)
            setPassphrase("correct horse battery staple")
        }

    private fun held(vm: OnboardingViewModel): ByteArray? =
        OnboardingViewModel::class.java.getDeclaredField("_passphrase").apply { isAccessible = true }.get(vm) as ByteArray?

    private fun ByteArray.allZero() = all { it == 0.toByte() }

    /** Starts the commit and waits until it answered or let an exception escape. */
    private fun settle(vm: OnboardingViewModel, prompt: (suspend () -> Unit)? = null): AddResult? {
        val result = AtomicReference<AddResult?>(null)
        val done = CountDownLatch(1)
        vm.commitAdd(prompt) { result.set(it); done.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (done.count > 0 && escaped.isEmpty() && System.nanoTime() < deadline) done.await(20, TimeUnit.MILLISECONDS)
        return result.get()
    }

    /** A prompt that says it is up, then never answers. */
    private fun hangingPrompt(up: CountDownLatch): suspend () -> Unit = {
        up.countDown()
        CompletableDeferred<Unit>().await()
    }

    @Test fun `an add that throws gives the reservation back`() {
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.READY)
        every { provisioner.add(any(), any(), any(), any()) } throws IllegalStateException("native")
        val vm = addingViewModel()
        val bytes = held(vm)!!

        assertEquals(AddResult.Failed, settle(vm))

        assertTrue("an exception escaped: $escaped", escaped.isEmpty())
        verify(exactly = 1) { provisioner.abandon(entry.id) }
        assertTrue(bytes.allZero())
        assertNull(held(vm))
    }

    @Test fun `clearing the ViewModel while the prompt is up gives the reservation back`() {
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL)
        val vm = addingViewModel()
        val bytes = held(vm)!!
        val up = CountDownLatch(1)
        vm.commitAdd(hangingPrompt(up)) { }
        assertTrue("the prompt never came up", up.await(5, TimeUnit.SECONDS))

        ViewModelStore().apply { put("onboarding", vm) }.clear()

        verify(timeout = 5_000, exactly = 1) { provisioner.abandon(entry.id) }
        verify(exactly = 0) { provisioner.add(any(), any(), any(), any()) }
        assertTrue(bytes.allZero())
        assertTrue("an exception escaped: $escaped", escaped.isEmpty())
    }

    @Test fun `no room on the network is the cap, with nothing to give back`() {
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns null
        val vm = addingViewModel()
        val bytes = held(vm)!!

        assertEquals(AddResult.CapReached, settle(vm))

        verify(exactly = 0) { provisioner.add(any(), any(), any(), any()) }
        verify(exactly = 0) { provisioner.abandon(any()) }
        assertTrue(bytes.allZero())
    }

    @Test fun `the passphrase is zeroed after every answer of the add`() {
        val answers = listOf(
            AddResult.Added(entry.copy(state = EntryState.READY)),
            AddResult.Duplicate(entry.copy(id = "legacy", state = EntryState.READY)),
            AddResult.Failed,
        )
        for (answer in answers) {
            every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.READY)
            val given = AtomicReference<ByteArray?>(null)
            every { provisioner.add(any(), any(), any(), any()) } answers {
                given.set(arg<ByteArray?>(2))
                answer
            }
            val vm = addingViewModel()
            val bytes = held(vm)!!

            assertEquals(answer, settle(vm))

            assertTrue("$answer: the held passphrase is not zero", bytes.allZero())
            assertTrue("$answer: the add's copy is not zero", given.get()!!.allZero())
            assertNull(held(vm))
        }
        // The add answered each time: its own erase covers what it did not keep.
        verify(exactly = 0) { provisioner.abandon(any()) }
    }

    @Test fun `a second commit while one runs does nothing`() {
        every { provisioner.prepareSeedKey(false, WalletOrigin.RESTORED) } returns (entry to SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL)
        val vm = addingViewModel()
        val up = CountDownLatch(1)
        vm.commitAdd(hangingPrompt(up)) { }
        assertTrue(up.await(5, TimeUnit.SECONDS))

        vm.commitAdd(null) { }

        verify(exactly = 1) { provisioner.prepareSeedKey(any(), any()) }
        ViewModelStore().apply { put("onboarding", vm) }.clear()
        verify(timeout = 5_000, exactly = 1) { provisioner.abandon(entry.id) }
    }
}
