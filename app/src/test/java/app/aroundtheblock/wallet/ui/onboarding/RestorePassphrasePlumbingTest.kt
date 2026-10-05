package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.core.Bip39Passphrase
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The passphrase typed at restore must be the one the wallet is rebuilt with.
 *
 * Until 2026-10-05 the restore flow never passed one: WalletManager.recoverWallet took a
 * passphrase, the restore UI had nowhere to type it, and a passphrase wallet "restored" into a
 * different, valid, empty wallet. No error, just no coins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestorePassphrasePlumbingTest {

    private val walletManager = mockk<WalletManager>()
    private val pinManager = mockk<PinManager>(relaxed = true)
    private val scanService = mockk<RecoveryScanService>()

    /** What recoverWallet received, copied at call time — the ViewModel zeroes its buffer after. */
    private var receivedPassphrase: ByteArray? = null
    private var receivedTimestamp: Long = -1
    private var called = false

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { walletManager.recoverWallet(any(), any(), any()) } answers {
            called = true
            receivedTimestamp = secondArg()
            receivedPassphrase = thirdArg<ByteArray?>()?.copyOf()
            true
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun restore(vm: OnboardingViewModel) {
        val done = CountDownLatch(1)
        vm.recoverWallet { done.countDown() }
        assertTrue("recoverWallet did not finish", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun `the passphrase entered at restore reaches recoverWallet`() {
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        vm.setPassphrase("correct horse")
        vm.setRecoveryTimestamp(1767225600L)

        restore(vm)

        assertTrue(called)
        assertEquals(1767225600L, receivedTimestamp)
        assertArrayEquals(Bip39Passphrase.prepare("correct horse"), receivedPassphrase)
        verify(exactly = 1) { walletManager.recoverWallet(any(), 1767225600L, any()) }
    }

    @Test fun `no passphrase restores with null, exactly like a wallet created without one`() {
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        vm.setPassphrase(null)

        restore(vm)

        assertTrue(called)
        assertNull(receivedPassphrase)
    }

    @Test fun `the restore never runs the server scan on its own`() {
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")

        restore(vm)

        io.mockk.coVerify(exactly = 0) { scanService.scan(any(), any()) }
    }
}
