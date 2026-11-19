package app.aroundtheblock.wallet.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.prepareSeedKeyThenPrompt
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.security.OnboardingPin
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.AddResult
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletProvisioner
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.QuickStart
import app.aroundtheblock.wallet.core.wallets.QuickStartStore
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.WalletSwitcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Shared ViewModel for the onboarding flow.
 * Holds mnemonic state in memory only — never persisted or logged.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val walletManager: WalletManager,
    /** The only PIN writes these screens can reach: refused while the stored PIN guards a wallet. */
    private val onboardingPin: OnboardingPin,
    private val recoveryScanService: app.aroundtheblock.wallet.core.recovery.RecoveryScanService,
    savedStateHandle: SavedStateHandle,
    private val provisioner: WalletProvisioner,
    private val switcher: WalletSwitcher,
    private val registry: WalletRegistry,
    @ApplicationContext private val appContext: Context,
    private val quickStartScans: QuickStartScans,
    /** The stay in the foreground a PIN was entered in: what this flow holds does not outlive it. */
    private val foregroundVisits: app.aroundtheblock.wallet.ForegroundVisits,
) : ViewModel() {

    /** The flow these screens run: the route argument of the onboarding entry this ViewModel belongs to. */
    val mode: OnboardingMode = OnboardingMode.of(savedStateHandle.get<String>(ONBOARDING_MODE_ARG))

    /** The network this process runs; an added wallet joins it. */
    private val testnet: Boolean get() = ActiveWallet.scope(appContext).testnet

    /** How the wallet being added came about: set by [generateMnemonic] and [setRecoveryMnemonic]. */
    private var _origin: WalletOrigin = WalletOrigin.CREATED

    // In-memory mnemonic — cleared after wallet creation
    private var _mnemonic: List<String> = emptyList()

    // Word count for create flow
    private var _wordCount: Int = 12

    // Recovery timestamp (Unix seconds) — 0 means full rescan
    private var _recoveryTimestamp: Long = 0L

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _pendingLegacyRecovery = MutableStateFlow(false)
    val pendingLegacyRecovery: StateFlow<Boolean> = _pendingLegacyRecovery.asStateFlow()

    fun getWordCount(): Int = _wordCount

    /** Set the word count (12 or 24) and generate a new mnemonic. */
    fun setWordCount(count: Int) {
        _wordCount = count
    }

    /**
     * OPTIONAL BIP39 passphrase, in memory only until the wallet is created.
     *
     * Held here rather than passed through navigation arguments so it never enters a back-stack
     * entry, a saved-state bundle, or a deep link.
     */
    private var _passphrase: ByteArray? = null

    /**
     * Converted to bytes on the way in, so the String the user typed is not held across the four
     * setup screens. It used to be kept as a String from the seed screen until wallet creation —
     * a secret that could not be wiped, alive for the whole flow.
     */
    fun setPassphrase(value: String?) {
        _passphrase?.fill(0)
        _passphrase = app.aroundtheblock.wallet.core.Bip39Passphrase.prepare(value)
    }

    fun hasPassphrase(): Boolean = _passphrase != null

    /** Returns the current in-memory mnemonic words. */
    fun getMnemonicWords(): List<String> = _mnemonic

    /** Generate a fresh mnemonic with the chosen word count. */
    fun generateMnemonic() {
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            val result = withContext(Dispatchers.Default) {
                NativeBridge.generateMnemonic(if (_wordCount == 24) 256 else 128)
            }
            if (result != null) {
                _mnemonic = result.trim().split(" ")
                _origin = WalletOrigin.CREATED
                _uiState.value = OnboardingUiState.MnemonicReady
            } else {
                _uiState.value = OnboardingUiState.Error("Failed to generate mnemonic")
            }
        }
    }

    /** Set mnemonic from recovery input (splits on whitespace). */
    fun setRecoveryMnemonic(phrase: String) {
        _mnemonic = phrase.trim().split("\\s+".toRegex())
        _origin = WalletOrigin.RESTORED
        // A scan answers for the phrase it was run on. Going back and entering another phrase must
        // not leave the old answer on screen, nor let it steer the post-restore sweep prompt — and
        // a scan still running for the old phrase must not deliver its answer afterwards.
        cancelRecoveryScan()
        _scanResults.value = app.aroundtheblock.wallet.core.recovery.RecoveryScanService.State.Idle
        _passphraseVerdict.value = null
        quickStartKept = null
        _quickStart.value = QuickStartUi.Idle
    }

    /** Set the recovery timestamp mapped from the date picker. */
    fun setRecoveryTimestamp(timestamp: Long) {
        _recoveryTimestamp = timestamp
    }

    // ── Universal Restore scan state ─────────────────────────────────────────
    //
    // Holds the most recent multi-path scan result so RecoveryScanScreen can
    // show it without re-running the scan on config change, and so
    // RecoveryDateScreen / WalletScreen can consult it for "we found funds
    // on legacy paths" sweep context.

    private val _scanResults = kotlinx.coroutines.flow.MutableStateFlow<
            app.aroundtheblock.wallet.core.recovery.RecoveryScanService.State
            >(app.aroundtheblock.wallet.core.recovery.RecoveryScanService.State.Idle)
    val scanResults: kotlinx.coroutines.flow.StateFlow<
            app.aroundtheblock.wallet.core.recovery.RecoveryScanService.State
            > = _scanResults.asStateFlow()

    /** The running scan, and the number of the phrase it was started for: a result is published
     *  only while [scanGeneration] still names that phrase. The number is the guard that holds even
     *  when the scan does not stop at a cancellation point. Main thread only. */
    private var scanJob: kotlinx.coroutines.Job? = null
    private var scanGeneration = 0L

    private fun cancelRecoveryScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
    }

    /** Run the multi-path derivation scan against the entered mnemonic and passphrase (see
     *  [setPassphrase]). Results land in [scanResults].
     *
     *  OPT-IN ONLY. The scan sends the addresses the phrase derives to the reconcile backend
     *  (api.digiscope.me by default); the restore itself never needs it, because sync finds this
     *  app's own address formats privately on the phone. The user starts it from
     *  RecoveryScanScreen after being told what it sends. */
    fun runRecoveryScan() {
        val hadPassphrase = _passphrase != null
        _scanResults.value = RecoveryScanService.State.Scanning("Deriving addresses…")
        runScan(recoveryScanService) { result ->
            // NO second, passphrase-less scan here. Re-scanning without the passphrase would answer
            // "is it a typo?", but it sends a SECOND wallet's addresses to the backend from the same
            // IP — and the people who use a passphrase include those who keep a decoy wallet on the
            // bare phrase. That would link the two at DigiScope, which is exactly what a hidden
            // wallet exists to prevent, and the user only agreed to send THIS phrase's addresses.
            // The screen says plainly what an empty passphrase result can mean instead.
            _passphraseVerdict.value =
                if (hadPassphrase && result is RecoveryScanService.State.Done) {
                    app.aroundtheblock.wallet.core.recovery.PassphraseScanVerdict.of(
                        withPassphraseSat = result.totalBalanceSat,
                        withoutPassphraseSat = null,
                        incomplete = result.anyBackendUnreachable || result.cappedProfileLabels.isNotEmpty(),
                    )
                } else null
            _scanResults.value = result
        }
    }

    /**
     * The one scan routine: [service] over the entered phrase and a copy of the passphrase, zeroed
     * straight after (the held passphrase lives until the wallet is written). [onDone] is called
     * on the main thread with the answer, only while [scanGeneration] still names the phrase it
     * was started for. A blank phrase answers [RecoveryScanService.State.Failed] at once.
     */
    private fun runScan(service: RecoveryScanService, onDone: (RecoveryScanService.State) -> Unit): kotlinx.coroutines.Job? {
        val phrase = _mnemonic.joinToString(" ")
        if (phrase.isBlank()) {
            onDone(RecoveryScanService.State.Failed("No mnemonic entered"))
            return null
        }
        cancelRecoveryScan()
        val generation = scanGeneration
        scanJob = viewModelScope.launch {
            val passBytes = _passphrase?.copyOf()
            val result = try {
                service.scan(phrase, passBytes)
            } finally {
                passBytes?.fill(0)
            }
            if (generation != scanGeneration) return@launch   // the phrase changed: not this scan's answer to give
            onDone(result)
        }
        return scanJob
    }

    // ── Quick start (ADD mode, restore) ──────────────────────────────────────

    private val _quickStart = MutableStateFlow<QuickStartUi>(QuickStartUi.Idle)

    /** The quick start as QuickStartScreen shows it. */
    val quickStart: StateFlow<QuickStartUi> = _quickStart.asStateFlow()

    /** What the quick start found, held for [commitAdd] to store with the new wallet. Display only. */
    private var quickStartKept: QuickStart? = null

    /** The own node shown by the last [quickStartOffer]: the only place an own-node lookup goes. Main thread. */
    private var offeredOwnNode: String? = null

    /** The running quick start's job. Its end with the state still Running (cancelled, or its answer no
     *  longer this phrase's) puts the quick start back to Idle. */
    @Volatile private var quickStartJob: kotlinx.coroutines.Job? = null

    /** What the quick start screen offers. Reads the own-node setting, so off the main thread. */
    suspend fun quickStartOffer(): QuickStartOffer {
        val endpoint = withContext(Dispatchers.IO) { runCatching { quickStartScans.customEndpoint() }.getOrNull() }
        offeredOwnNode = endpoint
        return QuickStartOffer(quickStartChoices(endpoint), endpoint?.let(::quickStartHost), endpoint)
    }

    /** Leaving the quick start while it runs: stops it, and nothing it finds afterwards is kept. */
    fun cancelQuickStart() {
        if (_quickStart.value == QuickStartUi.Running) cancelRecoveryScan()
        quickStartJob = null
        quickStartKept = null
        _quickStart.value = QuickStartUi.Idle
    }

    /**
     * The opt-in quick start of a wallet being added by restore: sends this wallet's own address
     * formats to [source] and keeps what it reports, DISPLAY ONLY, for [commitAdd] to store with the
     * new wallet. Nothing it finds is ever spendable or part of a balance; the compact-filter sync
     * proves the wallet's outputs itself. Started only from QuickStartScreen, by the user's choice.
     */
    fun runQuickStart(source: QuickStartSource) {
        check(mode == OnboardingMode.ADD) { "the quick start runs only when adding a wallet" }
        if (_quickStart.value == QuickStartUi.Running) return
        quickStartKept = null
        val built = runCatching { quickStartScans.serviceFor(source, offeredOwnNode) }.getOrNull()
        if (built == null) {
            _quickStart.value = QuickStartUi.Failed
            return
        }
        val (service, label) = built
        _quickStart.value = QuickStartUi.Running
        val job = runScan(service) { result ->
            if (result !is RecoveryScanService.State.Done || result.allBackendUnreachable) {
                _quickStart.value = QuickStartUi.Failed
                return@runScan
            }
            // An answer with any path unanswered, or cut short by the cap, could show less than the
            // wallet holds as if it were all: no figure rather than a confident wrong one.
            if (result.anyBackendUnreachable || result.cappedProfileLabels.isNotEmpty()) {
                _quickStart.value = QuickStartUi.Incomplete
                return@runScan
            }
            // Every profile scanned is native (BIP84 and BIP86), so the total is the native total;
            // nativeResult alone would be BIP84 only and drop the taproot coins.
            val found = QuickStart(
                unverifiedSat = result.totalBalanceSat,
                // Null today: scan() does not look up DigiDollar (only scanFromSeed does). Kept for when it does.
                unverifiedDdCents = result.digiDollar?.cents,
                sourceLabel = label,
                atSecs = System.currentTimeMillis() / 1000,
            )
            quickStartKept = found
            _quickStart.value = QuickStartUi.Found(found)
        } ?: return
        quickStartJob = job
        // Cancelled (runRecoveryScan and a new phrase share scanJob) or ended with no answer to give:
        // not a dead end on "Running".
        job.invokeOnCompletion {
            if (quickStartJob === job && _quickStart.value == QuickStartUi.Running) _quickStart.value = QuickStartUi.Idle
        }
    }

    /** Why a passphrase scan came back empty, when one was supplied. Null when not applicable. */
    private val _passphraseVerdict =
        MutableStateFlow<app.aroundtheblock.wallet.core.recovery.PassphraseScanVerdict.Outcome?>(null)
    val passphraseVerdict: StateFlow<app.aroundtheblock.wallet.core.recovery.PassphraseScanVerdict.Outcome?> =
        _passphraseVerdict

    // ── The stored app PIN while a wallet exists (INT-2026-11-19-B) ──────────
    //
    // Invariant: no onboarding path replaces or clears the app PIN while any wallet record exists.
    // A first run that starts while a wallet and its PIN are stored (the selected network has no
    // wallet, another one has) asks for that PIN before anything else and sets none.

    /** What [keepsStoredPin] last answered; null until it has been asked. */
    @Volatile private var keepsPinAnswer: Boolean? = null

    /**
     * The stay in the foreground ([foregroundVisits]) in which this flow came to hold the app PIN:
     * the current one was entered ([onCurrentPinVerified]), or the flow stored the first one itself
     * ([setPin]). Null when neither. Held for that stay only: once the app has left the foreground
     * the PIN is owed again, at the first screen and at the write.
     */
    @Volatile private var pinHeldInVisit: Long? = null

    private fun holdsPin(): Boolean = pinHeldInVisit == foregroundVisits.current()

    /**
     * Whether this flow keeps the stored app PIN: it then asks for that PIN before the phrase is
     * shown or typed, shows no PIN setup and sets none. Asked afresh each time, off the main
     * thread; a screen reads it once when it opens. Never in ADD mode, which touches no PIN and
     * runs behind the unlock.
     */
    suspend fun keepsStoredPin(): Boolean {
        if (mode != OnboardingMode.FIRST_RUN) return false
        return withContext(Dispatchers.IO) { storedPinGuardsAWallet() }.also { keepsPinAnswer = it }
    }

    /** The screen calls this once the app's PIN check has accepted the current PIN. */
    fun onCurrentPinVerified() {
        pinHeldInVisit = foregroundVisits.current()
    }

    /**
     * True while a first run must ask for the current PIN before it goes on: the stored PIN guards
     * a wallet (or that has not been read yet, which is not read as "no PIN") and this flow does
     * not hold it in this stay in the foreground.
     */
    fun currentPinOwed(): Boolean = mode == OnboardingMode.FIRST_RUN && keepsPinAnswer != false && !holdsPin()

    /** A PIN store that cannot be read beside a wallet record counts as guarding it. */
    private fun storedPinGuardsAWallet(): Boolean = try {
        onboardingPin.mustKeepPin()
    } catch (e: Exception) {
        true
    }

    /** Who waits for the first-run create in flight; null when none runs. Main thread only. */
    private var createWaiters: MutableList<(Boolean) -> Unit>? = null

    /** Create wallet from generated mnemonic. Clears mnemonic from memory when done. */
    fun createWallet(onResult: (Boolean) -> Unit) = createWallet(seedKeyPrompt = null, onResult = onResult)

    /**
     * As [createWallet], with the KS-BIND seed-key step first: [prepareSeedKeyThenPrompt] probes the
     * auth-bound key and, when the auth window has lapsed, runs [seedKeyPrompt] once and awaits it.
     * Both run here, in [viewModelScope], in ONE coroutine with the write: a screen that leaves
     * composition while the prompt is up (activity recreated, navigated away) cancels nothing, so the
     * PIN it already saved is never left without the wallet record it was saved for.
     */
    fun createWallet(seedKeyPrompt: (suspend () -> Unit)?, onResult: (Boolean) -> Unit) {
        // This writes the RUNNING wallet's record. ADD mode writes only the new wallet's, through commitAdd.
        check(mode == OnboardingMode.FIRST_RUN) { "createWallet runs only on the first run" }
        // One create at a time. A second call while one runs (PIN setup recomposed from nothing while
        // it waits, which the kept-PIN step would turn into a second create by itself) gets the
        // running create's answer instead of starting another over the same phrase.
        createWaiters?.let { waiting ->
            waiting += onResult
            return
        }
        val waiters = mutableListOf(onResult)
        createWaiters = waiters
        val answer: (Boolean) -> Unit = { created ->
            createWaiters = null
            waiters.toList().forEach { it(created) }
        }
        val phrase = _mnemonic.joinToString(" ")
        // This create's own copy: clearing the screen's copy (onCleared) never changes the bytes a
        // create in progress is using, and this one is zeroed however the create ends.
        val pass = _passphrase?.copyOf()
        // Read as the write is asked for: the seed-key prompt below may take the app out of the foreground.
        val holdsPin = holdsPin()
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            if (refusedWithoutCurrentPin(holdsPin, "Wallet creation failed", answer)) {
                pass?.fill(0)
                return@launch
            }
            if (seedKeyPrompt != null) {
                prepareSeedKeyThenPrompt(prepare = { walletManager.prepareSeedKeyForNewRecord() }, prompt = seedKeyPrompt)
            }
            val success = try {
                withContext(Dispatchers.Default) {
                    // Do NOT clearPin here — PinSetupScreen calls setPin() right
                    // before this, and clearing afterward wipes the freshly-set
                    // PIN. (recoverWallet keeps its clearPin because it runs
                    // before pin_setup, replacing any stale-from-prior-install PIN.)
                    walletManager.createWallet(phrase, pass)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A create that throws has failed: reported as such, not let out of the scope.
                false
            } finally {
                // Created or not, the passphrase has no further use here — and now it can be wiped.
                pass?.fill(0)
                clearPassphrase()
            }
            wipeMnemonicFromMemory()
            _uiState.value = if (success) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Wallet creation failed")
            answer(success)
        }
    }

    /** Recover wallet from entered mnemonic and chosen timestamp. */
    fun recoverWallet(onResult: (Boolean) -> Unit) = recoverWallet(seedKeyPrompt = null, onResult = onResult)

    /** As [recoverWallet], with the KS-BIND seed-key step first, in the same coroutine (see [createWallet]). */
    fun recoverWallet(seedKeyPrompt: (suspend () -> Unit)?, onResult: (Boolean) -> Unit) {
        // ADD mode never reaches this: it writes only the new wallet's record, through commitAdd.
        check(mode == OnboardingMode.FIRST_RUN) { "recoverWallet runs only on the first run" }
        val phrase = _mnemonic.joinToString(" ")
        val ts = _recoveryTimestamp
        // This restore's own copy, as in createWallet.
        val pass = _passphrase?.copyOf()
        // As in createWallet: read as the write is asked for.
        val holdsPin = holdsPin()
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            if (refusedWithoutCurrentPin(holdsPin, "Recovery failed", onResult)) {
                pass?.fill(0)
                return@launch
            }
            if (seedKeyPrompt != null) {
                prepareSeedKeyThenPrompt(prepare = { walletManager.prepareSeedKeyForNewRecord() }, prompt = seedKeyPrompt)
            }

            // NOTE: no depth gate here. Restores are accepted at ANY depth — the
            // paced convoy bounds the memory of an arbitrarily deep CF scan, so
            // there is nothing to refuse. (A backup you can't restore from isn't a
            // backup.)
            val success = try {
                withContext(Dispatchers.Default) {
                    // Clear any stale PIN from a previous install so the user
                    // is routed to PIN setup, not the unlock screen. Done inside
                    // the worker dispatcher so the Keystore-backed prefs write
                    // never lands on the main thread.
                    // Only with no wallet record on the device: a PIN that protects another
                    // wallet (testnet wallets left after removing the only mainnet one) is not
                    // stale, and clearing it would leave those wallets unprotected (F31). The
                    // rule is OnboardingPin's; this ViewModel holds no other way to the PIN.
                    onboardingPin.clearStalePin()
                    walletManager.recoverWallet(phrase, ts, pass)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A restore that throws has failed: reported as such, not let out of the scope.
                false
            } finally {
                // As in createWallet: restored or not, the passphrase has no further use here.
                pass?.fill(0)
                clearPassphrase()
            }

            // If the pre-recovery scan found funds on non-native paths, signal
            // the UI to navigate to RecoverFundsScreen after the wallet lands.
            // No silent sweep happens here anymore — the user is shown the screen
            // and initiates the sweep themselves.
            if (success) {
                val scan = _scanResults.value
                _pendingLegacyRecovery.value =
                    scan is app.aroundtheblock.wallet.core.recovery.RecoveryScanService.State.Done &&
                    scan.nonNativeWithFunds.isNotEmpty()
            }

            wipeMnemonicFromMemory()
            _uiState.value = if (success) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Recovery failed")
            onResult(success)
        }
    }

    // ── ADD mode ─────────────────────────────────────────────────────────────

    /** True while the network has room for one more wallet (PENDING adds count, F31). */
    fun canAddWallet(): Boolean = runCatching { registry.canReserve(testnet) }.getOrDefault(false)

    /**
     * The wallet already in the app that the entered phrase + passphrase opens, or null (also when
     * it cannot be told: [commitAdd] checks again). Derives a seed, so call it off the main thread.
     */
    fun checkDuplicate(): WalletEntry? {
        val phrase = _mnemonic.joinToString(" ")
        if (phrase.isBlank()) return null
        val pass = _passphrase?.copyOf()
        return try {
            provisioner.duplicateOf(testnet, phrase, pass)
        } catch (e: Exception) {
            null
        } finally {
            pass?.fill(0)
        }
    }

    /** Restart into [id] (an added wallet, or the one a duplicate phrase already opens). */
    fun switchTo(id: String): WalletSwitcher.Result = switcher.switchTo(id)

    private val _addResult = MutableStateFlow<AddResult?>(null)

    /** The answer of the one [commitAdd] this flow runs; null until it has answered. */
    val addResult: StateFlow<AddResult?> = _addResult.asStateFlow()

    /** One add at a time: the commit step starts it once, and a second call while it runs does nothing. Main thread only. */
    private var addInFlight = false

    /**
     * ADD mode's write: reserves the new wallet's entry and seed key, prompts for the device
     * credential when the key needs it (in this one coroutine, as [recoverWallet] does), then writes
     * only the new wallet's record ([WalletProvisioner.add]). The running wallet, its record and the
     * app PIN are never touched. The phrase and passphrase are wiped however it ends.
     *
     * Whatever happens between the reservation and the add's answer (a throw, or this ViewModel
     * being cleared because the user left) abandons the reservation, so it holds no slot or alias.
     */
    fun commitAdd(seedKeyPrompt: (suspend () -> Unit)?, onResult: (AddResult) -> Unit) {
        check(mode == OnboardingMode.ADD) { "commitAdd runs only in ADD mode" }
        if (addInFlight || _addResult.value != null) return
        addInFlight = true
        val phrase = _mnemonic.joinToString(" ")
        val pass = _passphrase?.copyOf()
        val origin = _origin
        val creationTime = if (origin == WalletOrigin.RESTORED) _recoveryTimestamp else System.currentTimeMillis() / 1000
        val net = testnet
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            var reserved: WalletEntry? = null
            var answered = false
            val result = try {
                if (phrase.isBlank()) {
                    AddResult.Failed
                } else {
                    // Noted inside the block: a cancellation that lands while it runs still knows what to give back.
                    val prepared = withContext(Dispatchers.IO) {
                        provisioner.prepareSeedKey(net, origin).also { reserved = it?.first }
                    }
                    if (prepared == null) {
                        AddResult.CapReached
                    } else {
                        prepareSeedKeyThenPrompt(prepare = { prepared.second }, prompt = seedKeyPrompt)
                        withContext(Dispatchers.Default) { provisioner.add(prepared.first, phrase, pass, creationTime) }
                            .also { answered = true }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AddResult.Failed
            } finally {
                // The add erases its own scope on every answer but Added; before an answer the
                // reservation is this ViewModel's to give back, also when it is being cleared.
                val left = reserved
                if (left != null && !answered) {
                    withContext(NonCancellable + Dispatchers.IO) { provisioner.abandon(left.id) }
                }
                pass?.fill(0)
                clearPassphrase()
                wipeMnemonicFromMemory()
                addInFlight = false
            }
            // The quick start's figure goes to the new wallet's own store once it exists, before the
            // switch (which the screen starts from addResult). Display only: a failed write loses
            // a "not yet verified" line, nothing else.
            val kept = quickStartKept
            quickStartKept = null
            if (result is AddResult.Added && kept != null) {
                val stored = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { QuickStartStore.write(appContext, WalletScope(result.entry.id, net), kept) }.getOrDefault(false)
                }
                if (!stored) android.util.Log.w("OnboardingVM", "quick start not stored for the added wallet")
            }
            _uiState.value = if (result is AddResult.Added) OnboardingUiState.WalletCreated else OnboardingUiState.Error("Wallet add failed")
            _addResult.value = result
            onResult(result)
        }
    }

    /**
     * The write of a first-run wallet is refused while a stored PIN guards a wallet and this flow
     * does not hold that PIN ([holdsPin], as it stood when the write was asked for), however its
     * screens were reached. The store is asked afresh, off the main thread. Reported as a failed
     * [message], with nothing written and the phrase left for the flow to end on its own.
     */
    private suspend fun refusedWithoutCurrentPin(holdsPin: Boolean, message: String, onResult: (Boolean) -> Unit): Boolean {
        if (holdsPin) return false
        if (!withContext(Dispatchers.Default) { storedPinGuardsAWallet() }) return false
        runCatching { android.util.Log.w("OnboardingVM", "a wallet and its PIN are stored and the PIN was not entered; nothing written") }
        _uiState.value = OnboardingUiState.Error(message)
        onResult(false)
        return true
    }

    /**
     * Stores the PIN chosen at PIN setup. False, with nothing written, when it did not land or
     * while the stored PIN guards a wallet ([OnboardingPin.setFirstPin]). A flow whose own first
     * PIN landed holds the PIN from then on: the wallet write that follows is not refused for want
     * of a PIN this flow has just set.
     */
    fun setPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.Default) {
                runCatching { onboardingPin.setFirstPin(pin); true }.getOrDefault(false)
            }
            if (success) pinHeldInVisit = foregroundVisits.current()
            onResult(success)
        }
    }

    fun resetError() {
        _uiState.value = OnboardingUiState.Idle
    }

    /** Overwrite mnemonic list with dummy data and clear — defence in depth. */
    private fun wipeMnemonicFromMemory() {
        _mnemonic = List(_mnemonic.size) { "wipe" }
        _mnemonic = emptyList()
    }

    /** Every byte of the held passphrase zeroed, then let go (AND-006). */
    private fun clearPassphrase() {
        _passphrase?.fill(0)
        _passphrase = null
    }

    override fun onCleared() {
        super.onCleared()
        wipeMnemonicFromMemory()
        // Setup abandoned or finished: the passphrase is zeroed here too, not only after a create.
        clearPassphrase()
    }
}

sealed class OnboardingUiState {
    data object Idle : OnboardingUiState()
    data object Loading : OnboardingUiState()
    data object MnemonicReady : OnboardingUiState()
    data object WalletCreated : OnboardingUiState()
    data class Error(val message: String) : OnboardingUiState()

}
