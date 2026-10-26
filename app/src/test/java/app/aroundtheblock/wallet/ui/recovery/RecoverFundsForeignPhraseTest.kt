package app.aroundtheblock.wallet.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import app.aroundtheblock.wallet.core.recovery.DerivationProfile
import app.aroundtheblock.wallet.core.recovery.DestResolution
import app.aroundtheblock.wallet.core.recovery.DigiDollarScan
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A recovery phrase that is not this wallet's own is held only for the step that uses it.
 *
 * Recover funds scans an entered phrase, shows what it found, and sweeps it into this wallet. The
 * sweep derives the scanned seed again, so the ViewModel keeps the phrase and its passphrase's
 * bytes from a scan that found something to move until that sweep, and for nothing else. Every way
 * out of the recovery clears them: the sweep completing or failing, a scan that leaves nothing to
 * sweep, and the screen going away — Back, leaving, and the lock clearing the back stack each clear
 * the screen's ViewModel. Nothing is written to saved state, so process death leaves nothing either
 * ([RecoverFundsForeignPhraseWiringTest]).
 *
 * "Held" is read from the ViewModel itself: every field it declares is checked for the phrase or
 * the passphrase's bytes, whatever the field is called. The arrays it handed to the seed derivation
 * are checked as well: each must be zeroed once its step is over.
 *
 * Runs the real [RecoverFundsViewModel] with a single-thread stand-in for Main, as
 * [RecoverFundsSeedAuthTest] does. The ViewModel's entry points are called on that thread, as the
 * screen calls them. The native calls are stood in for through the ViewModel's own seams.
 */
class RecoverFundsForeignPhraseTest {

    private companion object {
        const val PHRASE = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        const val PASSPHRASE = "a passphrase"
        val PHRASE_BYTES: ByteArray = PHRASE.toByteArray()
        val PASSPHRASE_BYTES: ByteArray =
            Normalizer.normalize(PASSPHRASE, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
    }

    private lateinit var main: ExecutorCoroutineDispatcher
    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

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

    /** Stands in for the native seed derivation. Keeps every array it was handed, a copy of what
     *  each held at the call, and every seed it answered with. */
    private class Derivations {
        val phrases: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val passphrases: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val phrasesAtCall: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val passphrasesAtCall: MutableList<ByteArray?> = Collections.synchronizedList(mutableListOf())
        val seeds: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var answers: Boolean = true

        fun derive(phrase: ByteArray, passphrase: ByteArray?): ByteArray? {
            phrases += phrase
            phrasesAtCall += phrase.copyOf()
            passphrase?.let { passphrases += it }
            passphrasesAtCall += passphrase?.copyOf()
            if (!answers) return null
            return ByteArray(64) { (it + 1).toByte() }.also { seeds += it }
        }
    }

    private val derivations = Derivations()
    private val scan: RecoveryScanService = mockk(relaxed = true)
    private val assets: AssetNetworkClient = mockk(relaxed = true)

    /** A profile holding one output, so the findings offer a sweep. */
    private val funded = RecoveryScanService.ProfileResult(
        profile = DerivationProfile.BUILT_INS.first { !it.isNative },
        addresses = listOf("DTestAddress"),
        derivedAddresses = emptyList(),
        utxos = listOf(UtxoEntry("ab".repeat(32), 0, 50_000L, "DTestAddress", 1L)),
        rawTxs = emptyMap(),
    )

    /** Dollars and no DGB: the findings offer a sweep, and the recovery runs to its end without
     *  reaching a native call (there is nothing to sign, and the dollar move is not reached). */
    private val dollarsOnly = DigiDollarScan.Result(
        cents = 100L, holdings = emptyList(), reachable = true, unlocatableCents = 0L,
    )

    private fun viewModel(): RecoverFundsViewModel =
        RecoverFundsViewModel(
            scanService = scan,
            seedProvider = mockk(relaxed = true),
            outgoingTxStore = mockk(relaxed = true),
            walletTxPersister = mockk(relaxed = true),
            assetNetworkClient = assets,
            assetManager = mockk(relaxed = true),
        ).also { vm ->
            vm.destinationResolver = { DestResolution.Ok("dgb1qtestdestination") }
            vm.phraseIsValid = { true }
            vm.seedFromPhrase = derivations::derive
            // Not a finished scan, so the dollar move answers "nothing to say" without native calls.
            every { scan.state } returns MutableStateFlow<RecoveryScanService.State>(RecoveryScanService.State.Idle)
        }

    // ── Driving it as the screen does ────────────────────────────────────────

    private fun <T> onMain(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        main.executor.execute {
            result = runCatching(block)
            done.countDown()
        }
        assertTrue("the main thread did not answer", done.await(5, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    /** The screen's back-stack entry. Back, leaving, and the lock (which clears the back stack)
     *  each clear its store, and with it the ViewModel. */
    private fun entryHolding(vm: RecoverFundsViewModel): ViewModelStore {
        val store = ViewModelStore()
        val served = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[RecoverFundsViewModel::class.java]
        assertTrue(served === vm)
        return store
    }

    private fun scanFinds(done: RecoveryScanService.State) {
        coEvery { scan.scanFromSeed(any()) } returns done
    }

    private fun scanPhrase(vm: RecoverFundsViewModel) = onMain { vm.classifyForeign(PHRASE, PASSPHRASE) }

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

    private fun awaitFindings(vm: RecoverFundsViewModel) =
        awaitState(vm, "findings") { it is RecoverFundsViewModel.UiState.Findings }
            as RecoverFundsViewModel.UiState.Findings

    // ── What the ViewModel holds ─────────────────────────────────────────────

    /** Every field of the ViewModel holding the phrase or the passphrase's bytes: "name (what)". */
    private fun holders(vm: RecoverFundsViewModel): List<String> = onMain {
        var type: Class<*>? = RecoverFundsViewModel::class.java
        val found = mutableListOf<String>()
        while (type != null && type != ViewModel::class.java) {
            for (f in type.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val what = when (val v = f.get(vm)) {
                    is String -> if (v.contains(PHRASE)) "the phrase" else null
                    is CharArray -> if (String(v).contains(PHRASE)) "the phrase" else null
                    is ByteArray -> when {
                        v.contentEquals(PASSPHRASE_BYTES) -> "the passphrase"
                        v.contentEquals(PHRASE_BYTES) -> "the phrase"
                        else -> null
                    }
                    is StateFlow<*> -> if (v.value.toString().contains(PHRASE)) "the phrase" else null
                    else -> null
                }
                if (what != null) found += "${f.name} ($what)"
            }
            type = type.superclass
        }
        found
    }

    private fun zeroed(b: ByteArray) = b.all { it == 0.toByte() }

    /** Nothing left of the phrase: no field holds it or the passphrase, and every array handed to
     *  or returned by the seed derivation is zeroed. Waits for a step still winding down. */
    private fun assertNothingLeft(vm: RecoverFundsViewModel, after: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var left: List<String>
        while (true) {
            left = holders(vm) +
                derivations.passphrases.filterNot(::zeroed).map { "a passphrase array handed to the seed derivation" } +
                derivations.phrases.filterNot(::zeroed).map { "a phrase array handed to the seed derivation" } +
                derivations.seeds.filterNot(::zeroed).map { "a derived seed" }
            if (left.isEmpty() || System.currentTimeMillis() > deadline) break
            Thread.sleep(10)
        }
        assertEquals("after $after, still holding the phrase: $left", emptyList<String>(), left)
        assertTrue("the test never saw the passphrase handed over", derivations.passphrases.isNotEmpty())
        assertTrue("an exception left the ViewModel scope: $escaped", escaped.isEmpty())
    }

    // ── Held for the sweep, and only for it ──────────────────────────────────

    @Test
    fun `a scan that found something holds the phrase for the sweep, which derives the scanned seed`() {
        // The control for every test below: the detector sees the phrase where it is held, and the
        // sweep, the one step it is held for, still has it.
        val reached = CompletableDeferred<Unit>()
        coEvery { assets.getRawTransaction(any()) } coAnswers {
            reached.complete(Unit)
            CompletableDeferred<ByteArray?>().await()
        }
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()
        val store = entryHolding(vm)

        scanPhrase(vm)
        awaitFindings(vm)

        val held = holders(vm)
        assertTrue("the phrase is not held for the sweep: $held", held.any { it.endsWith("(the phrase)") })
        assertTrue("the passphrase is not held for the sweep: $held", held.any { it.endsWith("(the passphrase)") })

        onMain { vm.sweepForeign() }
        awaitState(vm, "the sweep under way") { reached.isCompleted }

        assertEquals(2, derivations.phrasesAtCall.size)
        assertArrayEquals("the sweep did not derive from the scanned phrase", PHRASE_BYTES, derivations.phrasesAtCall[1])
        assertArrayEquals("the sweep did not derive with the scanned passphrase", PASSPHRASE_BYTES, derivations.passphrasesAtCall[1])
        onMain { store.clear() }
    }

    @Test
    fun `a sweep that completes leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(emptyList(), digiDollar = dollarsOnly))
        val vm = viewModel()

        scanPhrase(vm)
        awaitFindings(vm)
        onMain { vm.sweepForeign() }
        awaitState(vm, "the recovery done") { it is RecoverFundsViewModel.UiState.Done }

        assertNothingLeft(vm, "a completed sweep")
    }

    @Test
    fun `a sweep that fails leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(emptyList(), digiDollar = dollarsOnly))
        val vm = viewModel()
        // The recovery fails partway: the dollar move cannot read the scan it moves from.
        every { scan.state } throws IllegalStateException("scan state unreadable")

        scanPhrase(vm)
        awaitFindings(vm)
        onMain { vm.sweepForeign() }
        val ended = awaitState(vm, "the sweep to fail") { it is RecoverFundsViewModel.UiState.Error }

        assertEquals(RecoverFundsViewModel.UiState.Error("scan state unreadable"), ended)
        assertNothingLeft(vm, "a failed sweep")
    }

    @Test
    fun `a sweep that cannot derive the seed again leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()

        scanPhrase(vm)
        awaitFindings(vm)
        derivations.answers = false
        onMain { vm.sweepForeign() }
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertNothingLeft(vm, "a sweep with no seed")
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    @Test
    fun `leaving the screen after a scan leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()
        val store = entryHolding(vm)

        scanPhrase(vm)
        awaitFindings(vm)
        onMain { store.clear() }

        assertNothingLeft(vm, "leaving the findings")
    }

    @Test
    fun `leaving the screen during the sweep leaves nothing of the phrase`() {
        val reached = CompletableDeferred<Unit>()
        coEvery { assets.getRawTransaction(any()) } coAnswers {
            reached.complete(Unit)
            CompletableDeferred<ByteArray?>().await()
        }
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()
        val store = entryHolding(vm)

        scanPhrase(vm)
        awaitFindings(vm)
        onMain { vm.sweepForeign() }
        awaitState(vm, "the sweep under way") { reached.isCompleted }
        onMain { store.clear() }

        assertNothingLeft(vm, "leaving during the sweep")
    }

    @Test
    fun `a mode switch leaves nothing of the phrase`() {
        // reset() already cleared the held fields; the phrase's bytes handed to the seed
        // derivation were never zeroed on any path.
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()

        scanPhrase(vm)
        awaitFindings(vm)
        onMain { vm.reset() }

        assertNothingLeft(vm, "a mode switch")
    }

    // ── A scan that leaves no sweep to hold it for ───────────────────────────

    @Test
    fun `a scan that finds nothing to move leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(emptyList()))
        val vm = viewModel()

        scanPhrase(vm)
        val shown = awaitFindings(vm)
        assertTrue("the test's premise: no sweep is offered", shown.findings.isEmpty() && shown.digiDollar == null)

        assertNothingLeft(vm, "a scan with nothing to move")
    }

    @Test
    fun `a scan that fails leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Failed("scan failed"))
        val vm = viewModel()

        scanPhrase(vm)
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertNothingLeft(vm, "a failed scan")
    }

    @Test
    fun `a scan that throws leaves nothing of the phrase`() {
        coEvery { scan.scanFromSeed(any()) } throws IllegalStateException("scan threw")
        val vm = viewModel()

        scanPhrase(vm)
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertNothingLeft(vm, "a scan that threw")
    }

    @Test
    fun `a scan that cannot reach the lookup leaves nothing of the phrase`() {
        scanFinds(RecoveryScanService.State.Done(listOf(funded.copy(reachableBackend = false))))
        val vm = viewModel()

        scanPhrase(vm)
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertNothingLeft(vm, "a scan that could not reach the lookup")
    }

    @Test
    fun `a phrase whose seed cannot be derived is not held`() {
        derivations.answers = false
        scanFinds(RecoveryScanService.State.Done(listOf(funded)))
        val vm = viewModel()

        scanPhrase(vm)
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertNothingLeft(vm, "a phrase with no seed")
    }

    @Test
    fun `a phrase that is not valid is not held`() {
        // Already true before this change (it is refused before anything holds it); kept beside
        // the other ways a scan ends.
        val vm = viewModel()
        vm.phraseIsValid = { false }

        scanPhrase(vm)
        awaitState(vm, "an error") { it is RecoverFundsViewModel.UiState.Error }

        assertEquals(emptyList<String>(), holders(vm))
        assertTrue("an invalid phrase reached the seed derivation", derivations.phrases.isEmpty())
    }
}
