package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import app.aroundtheblock.wallet.core.recovery.DerivationProfile
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.PinManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A scan result is published only for the phrase it was produced for. Entering a new phrase
 * cancels the running scan, and an answer that still arrives for the old phrase is dropped: it
 * must not appear on the scan screen, set the passphrase verdict, or route the restore of the
 * new phrase to the sweep screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryScanPhraseBindingTest {

    private val walletManager = mockk<WalletManager>()
    private val pinManager = mockk<PinManager>(relaxed = true)
    private val scanService = mockk<RecoveryScanService>()

    private val phraseA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val phraseB = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    /** A result for phrase A with funds on a non-native path. */
    private val fundedElsewhere = RecoveryScanService.State.Done(
        results = listOf(
            RecoveryScanService.ProfileResult(
                profile = DerivationProfile.BUILT_INS.first { !it.isNative },
                addresses = listOf("addrA"),
                derivedAddresses = emptyList(),
                utxos = listOf(UtxoEntry("aa".repeat(32), 0, 50_000L, "addrA", 10L, null)),
                rawTxs = emptyMap(),
            ),
        ),
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { walletManager.recoverWallet(any(), any(), any()) } returns true
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun restore(vm: OnboardingViewModel) {
        val done = CountDownLatch(1)
        vm.recoverWallet { done.countDown() }
        assertTrue("recoverWallet did not finish", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun `a scan that ignores cancellation still cannot publish for a replaced phrase`() {
        val release = CompletableDeferred<Unit>()
        // The scan does its network work without a suspension point that honours cancellation.
        coEvery { scanService.scan(phraseA, any()) } coAnswers {
            withContext(NonCancellable) { release.await() }
            fundedElsewhere
        }
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic(phraseA)
        vm.setPassphrase("p")
        vm.runRecoveryScan()
        assertTrue(vm.scanResults.value is RecoveryScanService.State.Scanning)

        vm.setRecoveryMnemonic(phraseB)
        release.complete(Unit)

        assertEquals(RecoveryScanService.State.Idle, vm.scanResults.value)
        assertNull(vm.passphraseVerdict.value)
        restore(vm)
        assertFalse("restore of B routed to the sweep screen by A's scan", vm.pendingLegacyRecovery.value)
    }

    @Test fun `a cancellable scan is cancelled when the phrase changes`() {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { scanService.scan(phraseA, any()) } coAnswers {
            started.complete(Unit)
            try { CompletableDeferred<Unit>().await() } finally { cancelled = true }
            fundedElsewhere
        }
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic(phraseA)
        vm.runRecoveryScan()
        assertTrue(started.isCompleted)

        vm.setRecoveryMnemonic(phraseB)

        assertTrue("the scan for the old phrase is still running", cancelled)
        assertEquals(RecoveryScanService.State.Idle, vm.scanResults.value)
    }

    @Test fun `the scan for the current phrase still publishes`() {
        coEvery { scanService.scan(phraseB, any()) } returns fundedElsewhere
        val vm = OnboardingViewModel(walletManager, pinManager, scanService)
        vm.setRecoveryMnemonic(phraseA)
        vm.setRecoveryMnemonic(phraseB)
        vm.runRecoveryScan()

        assertEquals(fundedElsewhere, vm.scanResults.value)
        restore(vm)
        assertTrue(vm.pendingLegacyRecovery.value)
    }
}
