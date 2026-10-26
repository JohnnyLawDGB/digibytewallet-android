package app.aroundtheblock.wallet.ui.onboarding

import androidx.lifecycle.ViewModelStore
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The onboarding passphrase (AND-006): every onboarding passphrase byte is zero after `onCleared()`
 * and after a failed create — whether the create reports failure or throws — and the same for a
 * restore. Runs the real [OnboardingViewModel]; `onCleared()` is reached the way the framework
 * reaches it, through [ViewModelStore.clear].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPassphraseWipeTest {

    private val walletManager = mockk<WalletManager>()
    private val pinManager = mockk<PinManager>(relaxed = true)
    private val scanService = mockk<RecoveryScanService>(relaxed = true)

    /** Exceptions that left the ViewModel's scope; on a device each one ends the process. */
    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped += t }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** The bytes the ViewModel holds — the array itself, so its zeroing can be seen. */
    private fun held(vm: OnboardingViewModel): ByteArray? =
        OnboardingViewModel::class.java.getDeclaredField("_passphrase").apply { isAccessible = true }.get(vm) as ByteArray?

    private fun ByteArray.allZero() = all { it == 0.toByte() }

    /** Starts [start] and waits until it reported a result or let an exception escape. */
    private fun settle(start: ((Boolean) -> Unit) -> Unit): Boolean? {
        val result = AtomicReference<Boolean?>(null)
        val done = CountDownLatch(1)
        start { result.set(it); done.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (done.count > 0 && escaped.isEmpty() && System.nanoTime() < deadline) done.await(20, TimeUnit.MILLISECONDS)
        return result.get()
    }

    @Test fun `every onboarding passphrase byte is zero after onCleared()`() {
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setPassphrase("correct horse battery staple")
        val bytes = held(vm)
        assertNotNull("precondition: the ViewModel holds the passphrase", bytes)
        assertFalse("precondition: the passphrase is not already zero", bytes!!.allZero())

        ViewModelStore().apply { put("onboarding", vm) }.clear()

        assertTrue("onboarding passphrase bytes survive onCleared()", bytes.allZero())
        assertNull("the ViewModel still holds a passphrase after onCleared()", held(vm))
    }

    @Test fun `every onboarding passphrase byte is zero after a failed create`() {
        var given: ByteArray? = null
        every { walletManager.createWallet(any(), any()) } answers {
            given = secondArg()
            throw IllegalStateException("the record write did not land")
        }
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setPassphrase("correct horse battery staple")
        val bytes = held(vm)!!

        val result = settle { vm.createWallet(it) }

        assertTrue("onboarding passphrase bytes survive a create that failed", bytes.allZero())
        assertNotNull("precondition: the create was handed the passphrase", given)
        assertTrue("the passphrase bytes the failed create was handed survive it", given!!.allZero())
        assertNull("the ViewModel still holds a passphrase after a failed create", held(vm))
        assertTrue("a failed create let its exception escape the ViewModel: $escaped", escaped.isEmpty())
        assertEquals("a failed create did not report failure", false, result)
    }

    @Test fun `every onboarding passphrase byte is zero after a failed restore`() {
        var given: ByteArray? = null
        every { walletManager.recoverWallet(any(), any(), any()) } answers {
            given = thirdArg()
            throw IllegalStateException("the record write did not land")
        }
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic(phrase)
        vm.setPassphrase("correct horse battery staple")
        val bytes = held(vm)!!

        val result = settle { vm.recoverWallet(it) }

        assertTrue("onboarding passphrase bytes survive a restore that failed", bytes.allZero())
        assertNotNull("precondition: the restore was handed the passphrase", given)
        assertTrue("the passphrase bytes the failed restore was handed survive it", given!!.allZero())
        assertTrue("a failed restore let its exception escape the ViewModel: $escaped", escaped.isEmpty())
        assertEquals("a failed restore did not report failure", false, result)
    }

    // GUARD: a create that reports failure already zeroed the passphrase before this change.
    @Test fun `every onboarding passphrase byte is zero after a create that reports failure`() {
        var given: ByteArray? = null
        every { walletManager.createWallet(any(), any()) } answers { given = secondArg(); false }
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setPassphrase("correct horse battery staple")
        val bytes = held(vm)!!

        assertEquals(false, settle { vm.createWallet(it) })
        assertTrue(bytes.allZero())
        assertTrue(given!!.allZero())
    }
}
