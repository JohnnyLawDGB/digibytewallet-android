package app.aroundtheblock.wallet.ui.onboarding

import androidx.fragment.app.FragmentActivity
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.BiometricAuth
import app.aroundtheblock.wallet.core.security.BiometricResult
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * KS-BIND. Invariant: on a device with a secure lock screen the seed is wrapped under the auth-bound
 * key from creation. This drives the real [OnboardingViewModel] — what PIN setup and the restore's
 * start call — with a mocked WalletManager, BiometricAuth and activity, and the shipped prompt
 * builder [seedKeyPromptFor]:
 *  - a lapsed auth window prompts exactly once, and the write does not start before the prompt ends;
 *  - every other readiness, and a missing activity, never prompts;
 *  - the probe, the prompt and the write are one coroutine in the ViewModel's scope, so the screen
 *    that started them leaving composition (its own scope cancelled) does not orphan the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedKeyBindingStepTest {

    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val mainThread = Executors.newSingleThreadExecutor()
    private val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before fun setUp() {
        Dispatchers.setMain(mainThread.asCoroutineDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        mainThread.shutdownNow()
    }

    private fun walletManager(readiness: () -> SeedKeyReadiness): WalletManager = mockk<WalletManager>().also { wm ->
        every { wm.prepareSeedKeyForNewRecord() } answers { order += "prepare"; readiness() }
        every { wm.createWallet(any(), any()) } answers { order += "create"; true }
        every { wm.recoverWallet(any(), any(), any()) } answers { order += "recover"; true }
    }

    private fun bio(onPrompt: suspend () -> Unit = {}): BiometricAuth = mockk<BiometricAuth>().also { b ->
        coEvery { b.authenticateDeviceCredential(any(), any(), any()) } coAnswers {
            order += "prompt-start"
            kotlinx.coroutines.delay(50)   // a prompt that is not awaited would end after the write
            onPrompt()
            order += "prompt-end"
            BiometricResult.Success
        }
    }

    private fun vm(wm: WalletManager) =
        OnboardingViewModel(wm, mockk<PinManager>(relaxed = true), mockk<RecoveryScanService>(relaxed = true))
            .also { it.setRecoveryMnemonic(phrase) }

    private fun create(vm: OnboardingViewModel, prompt: (suspend () -> Unit)?): Boolean? {
        val done = CountDownLatch(1)
        var result: Boolean? = null
        vm.createWallet(prompt) { result = it; done.countDown() }
        assertTrue("the create never finished", done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun `KS-BIND a lapsed window prompts exactly once and the create waits for it`() {
        val wm = walletManager { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }
        assertEquals(true, create(vm(wm), seedKeyPromptFor(bio(), activity, "T", "S")))
        assertEquals(listOf("prepare", "prompt-start", "prompt-end", "create"), order.toList())
    }

    @Test fun `KS-BIND a lapsed window prompts exactly once and the restore waits for it`() {
        val wm = walletManager { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }
        val done = CountDownLatch(1)
        vm(wm).recoverWallet(seedKeyPromptFor(bio(), activity, "T", "S")) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("prepare", "prompt-start", "prompt-end", "recover"), order.toList())
    }

    @Test fun `KS-BIND the prompt carries the caller's text and activity`() {
        val wm = walletManager { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }
        val b = bio()
        create(vm(wm), seedKeyPromptFor(b, activity, "T", "S"))
        coVerify(exactly = 1) { b.authenticateDeviceCredential(activity, "T", "S") }
    }

    @Test fun `KS-BIND READY, NO_LOCK_SCREEN and UNAVAILABLE never prompt`() {
        for (readiness in listOf(SeedKeyReadiness.READY, SeedKeyReadiness.NO_LOCK_SCREEN, SeedKeyReadiness.UNAVAILABLE)) {
            order.clear()
            val wm = walletManager { readiness }
            assertEquals(true, create(vm(wm), seedKeyPromptFor(bio(), activity, "T", "S")))
            assertEquals("readiness=$readiness", listOf("prepare", "create"), order.toList())
        }
    }

    @Test fun `KS-BIND no activity means no prompt, and the create still happens`() {
        assertNull(seedKeyPromptFor(bio(), null, "T", "S"))
        val wm = walletManager { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }
        assertEquals(true, create(vm(wm), seedKeyPromptFor(bio(), null, "T", "S")))
        assertEquals(listOf("create"), order.toList())
    }

    @Test fun `KS-BIND a throwing probe never prompts and the create still happens`() {
        val wm = walletManager { throw IllegalStateException("keystore") }
        assertEquals(true, create(vm(wm), seedKeyPromptFor(bio(), activity, "T", "S")))
        assertEquals(listOf("prepare", "create"), order.toList())
    }

    @Test fun `KS-BIND the screen leaving composition while the prompt is up does not orphan the create`() {
        // The screen's own scope (rememberCoroutineScope) is cancelled when it leaves composition —
        // activity recreated or navigated away — while the device-credential prompt is up. When the
        // create ran in that scope after the prompt, it never ran: a saved PIN and no wallet record.
        val wm = walletManager { SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }
        val screenScope = CoroutineScope(Job() + Dispatchers.Main)
        val promptUp = CountDownLatch(1)
        val done = CountDownLatch(1)
        val prompt = seedKeyPromptFor(bio(onPrompt = { promptUp.countDown(); kotlinx.coroutines.delay(200) }), activity, "T", "S")
        val model = vm(wm)
        screenScope.launch { model.createWallet(prompt) { done.countDown() } }
        assertTrue("the prompt never opened", promptUp.await(5, TimeUnit.SECONDS))
        screenScope.cancel()
        assertTrue("the create was orphaned when the screen's scope was cancelled", done.await(5, TimeUnit.SECONDS))
        verify(exactly = 1) { wm.createWallet(phrase, any()) }
    }
}
