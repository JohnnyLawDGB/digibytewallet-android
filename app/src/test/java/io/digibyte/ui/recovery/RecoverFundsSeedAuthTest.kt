package io.digibyte.ui.recovery

import io.digibyte.core.asset.network.AssetNetworkClient
import io.digibyte.core.reconcile.UtxoEntry
import io.digibyte.core.recovery.DerivationProfile
import io.digibyte.core.recovery.DestResolution
import io.digibyte.core.recovery.RecoveryScanService
import io.digibyte.core.recovery.SeedProvider
import io.digibyte.core.recovery.SweepDestination
import io.digibyte.core.security.KeystoreKeyInvalidatedException
import io.digibyte.core.security.KeystoreUserAuthRequiredException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/**
 * Recover funds reads THIS wallet's seed through [SeedProvider], whose key is bound to device
 * authentication. Outside that window the provider throws a typed exception by design; the
 * screen must answer it, never let it leave the ViewModel's scope.
 *
 * Runs the real [RecoverFundsViewModel] with a single-thread stand-in for the Main dispatcher,
 * so `viewModelScope` work is asynchronous as on a device and the tests wait for the state to
 * settle. Anything that leaves the scope uncaught reaches the default uncaught-exception handler,
 * which is recorded: on a device that is the event that ends the process.
 */
class RecoverFundsSeedAuthTest {

    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var main: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped += t }
        main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** Answers each [SeedProvider.loadSeed] call with the next scripted step, and keeps every
     *  array it handed out so the test can check each one was zeroed. */
    private class ScriptedSeeds(vararg steps: () -> ByteArray?) : SeedProvider {
        private val queue = ArrayDeque(steps.toList())
        val handedOut: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var calls = 0

        override fun loadSeed(): ByteArray? {
            calls++
            val step = synchronized(queue) { queue.removeFirst() }
            return step()?.also { handedOut += it }
        }
    }

    private fun seed(): ByteArray = ByteArray(64) { (it + 1).toByte() }
    private fun needsAuth(): ByteArray? = throw KeystoreUserAuthRequiredException(RuntimeException("window lapsed"))
    private fun invalidated(): ByteArray? = throw KeystoreKeyInvalidatedException(RuntimeException("lock removed"))
    private fun noSeed(): ByteArray? = null
    private fun fails(): ByteArray? = throw IllegalStateException("store unreadable")

    private val scan: RecoveryScanService = mockk(relaxed = true)
    private val assets: AssetNetworkClient = mockk(relaxed = true)

    /** One non-native profile holding one output, so the own-wallet sweep has findings. */
    private val funded = RecoveryScanService.ProfileResult(
        profile = DerivationProfile.BUILT_INS.first { !it.isNative },
        addresses = listOf("DTestAddress"),
        derivedAddresses = emptyList(),
        utxos = listOf(UtxoEntry("ab".repeat(32), 0, 50_000L, "DTestAddress", 1L)),
        rawTxs = emptyMap(),
    )

    private fun viewModel(seeds: SeedProvider): RecoverFundsViewModel =
        RecoverFundsViewModel(
            scanService = scan,
            seedProvider = seeds,
            outgoingTxStore = mockk(relaxed = true),
            walletTxPersister = mockk(relaxed = true),
            assetNetworkClient = assets,
            assetManager = mockk(relaxed = true),
        ).also { vm ->
            vm.destinationResolver = { DestResolution.Ok("dgb1qtestdestination") }
        }

    private fun awaitState(
        vm: RecoverFundsViewModel,
        what: String,
        ok: (RecoverFundsViewModel.UiState) -> Boolean,
    ): RecoverFundsViewModel.UiState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (ok(s)) return s
            Thread.sleep(10)
        }
        throw AssertionError("expected $what, state stayed ${vm.state.value}; uncaught: $escaped")
    }

    private fun settled(s: RecoverFundsViewModel.UiState) =
        s !is RecoverFundsViewModel.UiState.Classifying && s !is RecoverFundsViewModel.UiState.Sweeping

    private fun assertNothingUncaught() =
        assertTrue("an exception left the ViewModel scope: $escaped", escaped.isEmpty())

    private fun zeroed(b: ByteArray) = b.all { it == 0.toByte() }

    private fun error(vm: RecoverFundsViewModel): String =
        (awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }
            as RecoverFundsViewModel.UiState.Error).reason

    private fun awaitCredentialRequest(vm: RecoverFundsViewModel) {
        awaitState(vm, "a device-credential request") {
            it is RecoverFundsViewModel.UiState.NeedsDeviceCredential
        }
    }

    /** The screen answers on Main, as the Compose effect does. */
    private fun answer(vm: RecoverFundsViewModel, confirmed: Boolean) {
        val done = CountDownLatch(1)
        main.executor.execute { vm.onDeviceCredentialResult(confirmed); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun classified(vm: RecoverFundsViewModel) {
        coEvery { scan.scanFromSeed(any()) } returns RecoveryScanService.State.Done(listOf(funded))
        vm.classify()
        awaitState(vm, "findings") { it is RecoverFundsViewModel.UiState.Findings }
    }

    // ── classify(): the screen's entry effect ────────────────────────────────

    @Test
    fun `own-wallet scan settles when the seed key needs a fresh device authentication`() {
        val seeds = ScriptedSeeds(::needsAuth)
        val vm = viewModel(seeds)

        vm.classify()

        awaitState(vm, "a settled state") { settled(it) }
        assertNothingUncaught()
        assertEquals(1, seeds.calls)
    }

    @Test
    fun `own-wallet scan settles on an error when the seed key was invalidated`() {
        val vm = viewModel(ScriptedSeeds(::invalidated))

        vm.classify()

        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan settles on the seed message when there is no seed`() {
        val seeds = ScriptedSeeds(::noSeed)
        val vm = viewModel(seeds)

        vm.classify()

        assertEquals(RecoverFundsViewModel.SeedReason.UNAVAILABLE, error(vm))
        assertEquals(1, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan settles on the seed message when the provider fails any other way`() {
        val seeds = ScriptedSeeds(::fails)
        val vm = viewModel(seeds)

        vm.classify()

        assertEquals(RecoverFundsViewModel.SeedReason.UNAVAILABLE, error(vm))
        assertEquals(1, seeds.calls)
        assertNothingUncaught()
    }

    // ── sweep(): the own-wallet sweep ────────────────────────────────────────

    @Test
    fun `own-wallet sweep settles on the seed message when there is no seed`() {
        val seeds = ScriptedSeeds(::seed, ::noSeed)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))

        assertEquals(RecoverFundsViewModel.SeedReason.UNAVAILABLE, error(vm))
        assertEquals(2, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet sweep settles on the seed message when the provider fails any other way`() {
        val seeds = ScriptedSeeds(::seed, ::fails)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))

        assertEquals(RecoverFundsViewModel.SeedReason.UNAVAILABLE, error(vm))
        assertEquals(2, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet sweep settles when the seed key needs a fresh device authentication`() {
        val seeds = ScriptedSeeds(::seed, ::needsAuth)
        val vm = viewModel(seeds)
        classified(vm)
        assertTrue("the scan's seed was not zeroed", seeds.handedOut.single().all { it == 0.toByte() })

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))

        awaitState(vm, "a settled state") { settled(it) }
        assertNothingUncaught()
        assertEquals(2, seeds.calls)
    }

    @Test
    fun `own-wallet sweep settles on an error when the seed key was invalidated`() {
        val vm = viewModel(ScriptedSeeds(::seed, ::invalidated))
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))

        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }
        assertNothingUncaught()
    }

    // ── The device-credential answer ─────────────────────────────────────────

    @Test
    fun `own-wallet scan asks for the device credential and a decline ends in the seed message`() {
        val seeds = ScriptedSeeds(::needsAuth)
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        answer(vm, confirmed = false)

        assertEquals(RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED, error(vm))
        assertEquals("a declined prompt must not read the seed again", 1, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan runs once more after the credential is confirmed and zeroes that seed`() {
        val seeds = ScriptedSeeds(::needsAuth, ::seed)
        coEvery { scan.scanFromSeed(any()) } returns RecoveryScanService.State.Done(listOf(funded))
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)

        awaitState(vm, "findings") { it is RecoverFundsViewModel.UiState.Findings }
        assertEquals(2, seeds.calls)
        assertTrue("the retried scan's seed was not zeroed", zeroed(seeds.handedOut.single()))
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan retries once only - a key still refused after the prompt is a message`() {
        val seeds = ScriptedSeeds(::needsAuth, ::needsAuth)
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)

        assertEquals(RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED, error(vm))
        assertEquals(2, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan names the removed device lock when the key was invalidated`() {
        val vm = viewModel(ScriptedSeeds(::invalidated))

        vm.classify()

        assertEquals(RecoverFundsViewModel.SeedReason.KEY_INVALIDATED, error(vm))
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet scan names the removed device lock when the retry finds the key invalidated`() {
        val vm = viewModel(ScriptedSeeds(::needsAuth, ::invalidated))

        vm.classify()
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)

        assertEquals(RecoverFundsViewModel.SeedReason.KEY_INVALIDATED, error(vm))
        assertNothingUncaught()
    }

    /** The screen's Retry on a waiting request, on Main as the button's click is. */
    private fun askAgain(vm: RecoverFundsViewModel) {
        val done = CountDownLatch(1)
        main.executor.execute { vm.askDeviceCredentialAgain(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a waiting device-credential request can be asked again and still runs its step once`() {
        val seeds = ScriptedSeeds(::needsAuth, ::seed)
        coEvery { scan.scanFromSeed(any()) } returns RecoveryScanService.State.Done(listOf(funded))
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        val first = vm.state.value
        askAgain(vm)

        val again = vm.state.value
        assertTrue("asking again left the request", again is RecoverFundsViewModel.UiState.NeedsDeviceCredential)
        assertTrue("asking again must be a new request, so the screen shows the prompt again", again != first)
        assertEquals("asking again must not read the seed", 1, seeds.calls)

        answer(vm, confirmed = true)

        awaitState(vm, "findings") { it is RecoverFundsViewModel.UiState.Findings }
        assertEquals(2, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `a waiting device-credential request asked again can still be declined`() {
        val seeds = ScriptedSeeds(::needsAuth)
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        askAgain(vm)
        askAgain(vm)
        answer(vm, confirmed = false)

        assertEquals(RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED, error(vm))
        assertEquals(1, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `asking again when no request is waiting changes nothing`() {
        val seeds = ScriptedSeeds(::noSeed)
        val vm = viewModel(seeds)

        vm.classify()
        val before = error(vm)
        askAgain(vm)

        assertEquals(RecoverFundsViewModel.UiState.Error(before), vm.state.value)
        assertEquals(1, seeds.calls)
    }

    @Test
    fun `only a key the device invalidated for good offers no Retry`() {
        assertFalse(RecoverFundsViewModel.SeedReason.offersRetry(RecoverFundsViewModel.SeedReason.KEY_INVALIDATED))
        listOf(
            RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED,
            RecoverFundsViewModel.SeedReason.UNAVAILABLE,
            "Nothing to recover",
            "java.net.UnknownHostException: lookup",
            "",
        ).forEach { reason ->
            assertTrue("'$reason' must offer Retry", RecoverFundsViewModel.SeedReason.offersRetry(reason))
        }
    }

    @Test
    fun `an answer after leaving the request is ignored`() {
        val seeds = ScriptedSeeds(::needsAuth)
        val vm = viewModel(seeds)

        vm.classify()
        awaitCredentialRequest(vm)
        val left = CountDownLatch(1)
        main.executor.execute { vm.reset(); left.countDown() }
        assertTrue(left.await(5, TimeUnit.SECONDS))
        answer(vm, confirmed = true)

        assertEquals(RecoverFundsViewModel.UiState.Idle, vm.state.value)
        assertEquals("a stale answer must not read the seed", 1, seeds.calls)
    }

    @Test
    fun `own-wallet sweep asks for the device credential and a decline ends in the seed message`() {
        val seeds = ScriptedSeeds(::seed, ::needsAuth)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))
        awaitCredentialRequest(vm)
        answer(vm, confirmed = false)

        assertEquals(RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED, error(vm))
        assertEquals(2, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet sweep proceeds into the recovery after the credential is confirmed`() {
        // The recovery's first network step is held open, so the test sees it reached with the
        // seed read on the retry, and nothing past it runs (the native library is absent here).
        val reached = CompletableDeferred<Unit>()
        coEvery { assets.getRawTransaction(any()) } coAnswers {
            reached.complete(Unit)
            CompletableDeferred<ByteArray?>().await()
        }
        val seeds = ScriptedSeeds(::seed, ::needsAuth, ::seed)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)

        awaitState(vm, "the sweep under way") { reached.isCompleted }
        assertEquals(RecoverFundsViewModel.UiState.Sweeping, vm.state.value)
        assertEquals(3, seeds.calls)
        assertFalse("the retried sweep must use a live seed", zeroed(seeds.handedOut.last()))
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet sweep retries once only - a key still refused after the prompt is a message`() {
        val seeds = ScriptedSeeds(::seed, ::needsAuth, ::needsAuth)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)

        assertEquals(RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED, error(vm))
        assertEquals(3, seeds.calls)
        assertNothingUncaught()
    }

    @Test
    fun `own-wallet sweep zeroes the seed it read after the credential was confirmed`() {
        // The recovery's first network step is held, then fails: the sweep ends, and the seed
        // read on the retry must be zeroed however it ended.
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ByteArray?>()
        coEvery { assets.getRawTransaction(any()) } coAnswers {
            reached.complete(Unit)
            release.await()
        }
        val seeds = ScriptedSeeds(::seed, ::needsAuth, ::seed)
        val vm = viewModel(seeds)
        classified(vm)

        vm.sweep(SweepDestination.External("dgb1qtestdestination"))
        awaitCredentialRequest(vm)
        answer(vm, confirmed = true)
        awaitState(vm, "the sweep under way") { reached.isCompleted }
        assertFalse("the retried sweep must use a live seed", zeroed(seeds.handedOut.last()))

        release.completeExceptionally(java.io.IOException("lookup failed"))

        awaitState(vm, "the sweep to end") {
            it !is RecoverFundsViewModel.UiState.Sweeping &&
                it !is RecoverFundsViewModel.UiState.SplittingForAssets
        }
        assertEquals(3, seeds.calls)
        assertTrue("the retried sweep's seed was not zeroed", zeroed(seeds.handedOut.last()))
        assertNothingUncaught()
    }
}
