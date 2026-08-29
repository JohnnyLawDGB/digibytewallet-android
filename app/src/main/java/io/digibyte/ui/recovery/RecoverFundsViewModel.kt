package io.digibyte.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.recovery.DestResolution
import io.digibyte.core.recovery.LegacySweepService
import io.digibyte.core.recovery.RecoveryScanService
import io.digibyte.core.recovery.SeedProvider
import io.digibyte.core.recovery.SweepDestination
import io.digibyte.core.recovery.resolve
import io.digibyte.core.recovery.sweepSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the shared Recover-Funds UI (the Compose screen is Task 6). Owns the
 * seed lifecycle and orchestrates classify -> sweep.
 *
 * CRITICAL-3 seed handling:
 *  - The seed is loaded fresh for each operation (classify and sweep) and
 *    `fill(0)`-zeroed in a `finally` on BOTH paths.
 *  - [SeedProvider.loadSeed] returns the 64-byte BIP39 seed (the mnemonic ->
 *    seed conversion happens once inside the provider; see
 *    `WalletManager.loadBip39Seed`).
 *  - [RecoveryScanService.scanFromSeed] / [LegacySweepService.sweepFromSeed] do
 *    NOT zero the seed and are NOT internally guaranteed to be on an IO
 *    dispatcher by contract, so each invocation is wrapped in
 *    `withContext(Dispatchers.IO)` (they do blocking JNI + network work).
 *
 * Depends on the concrete [RecoveryScanService] (Hilt-injected) and constructs
 * a stateless [LegacySweepService] per sweep.
 */
@HiltViewModel
class RecoverFundsViewModel @Inject constructor(
    private val scanService: RecoveryScanService,
    private val seedProvider: SeedProvider,
    private val outgoingTxStore: io.digibyte.core.OutgoingTxStore,
    private val walletTxPersister: io.digibyte.core.WalletTxPersister,
    private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Classifying : UiState()
        data class Findings(
            val findings: List<RecoveryScanService.ProfileResult>,
            val totalSat: Long,
            val backendUnreachable: Boolean,
            /** Some derivation paths could not be checked. The findings below are therefore
             *  incomplete, and "no funds" must not be presented as a settled answer. */
            val partialFailurePaths: List<String> = emptyList(),
            val isForeign: Boolean = false,
            /**
             * How many outpoints look asset-bearing, counted from the raw parent transactions the
             * scan ALREADY fetched — no extra network. Used to warn before the recovery runs,
             * because moving an asset is irreversible and the user should be told first.
             *
             * Coarse in the same direction as ForeignUtxoAssetClassifier: it counts outpoints
             * whose parent transaction carries a DigiAsset marker. Over-counting warns about an
             * asset that turns out to be plain change; under-counting would fail to warn at all.
             */
            val assetOutpointCount: Int = 0,
            /**
             * What the scan found in DigiDollar, BEFORE any move is attempted.
             *
             * Carried here because a DigiDollar token output holds ZERO satoshis: it never lands
             * in [findings], so a wallet whose only value is dollars produced an empty findings
             * list and the screen answered "no funds found" over real money — with no button to
             * move it. Measured on mainnet against a wallet holding $1.00 and no DGB.
             */
            val digiDollar: io.digibyte.core.recovery.DigiDollarScan.Result? = null,
        ) : UiState()
        data object Sweeping : UiState()

        /** Splitting the wallet's DGB so every asset has an output to pay its own transfer with.
         *  Its own state because it is the one step that waits on a confirmation. */
        data class SplittingForAssets(val feeOutputCount: Int) : UiState()
        data class Done(
            val outcomes: List<LegacySweepService.SweepOutcome>,
            /** What happened to each DigiAsset, in the phase that ran BEFORE the sweep. */
            val assetMoves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move> = emptyList(),
            /** What happened to the wallet's DigiDollar, or null when it holds none. */
            val digiDollar: io.digibyte.core.recovery.DigiDollarTransferService.Result? = null,
        ) : UiState()
        data class Error(val reason: String) : UiState()
    }



    /**
     * Decides which of the foreign seed's UTXOs may be spent as plain DGB.
     *
     * A DigiAsset lives on a specific UTXO; sweeping it as ordinary DGB destroys it. The raw
     * transaction comes from the asset provider and the marker check from the native parser —
     * the same one the wallet's own asset detection uses.
     */
    private fun assetClassifier() = io.digibyte.core.recovery.ForeignUtxoAssetClassifier(
        fetchRawTx = { txid -> assetNetworkClient.getRawTransaction(txid) },
        isAssetTx = { raw -> io.digibyte.core.bridge.NativeBridge.isAssetTransaction(raw) },
    )

    /** Everything one recovery pass did: assets moved, DGB swept, dollars moved. */
    data class RecoveryOutcome(
        val assetMoves: List<io.digibyte.core.recovery.ForeignAssetTransferService.Move>,
        val swept: LegacySweepService.Result,
        val digiDollar: io.digibyte.core.recovery.DigiDollarTransferService.Result?,
    )

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Classification result from the most recent [classify], used by [sweep].
     * This is the source of truth for "funds found" — never infer that from a
     * sweep outcome's `inputCount`.
     */
    private var lastFindings: List<RecoveryScanService.ProfileResult> = emptyList()


    /** The findings the last sweep ran against, kept so the asset move can reuse them without
     *  re-scanning. Cleared by [reset] with everything else. */
    private var sweptFindings: List<RecoveryScanService.ProfileResult> = emptyList()

    /** True when the last sweep used an entered phrase rather than this wallet's own seed —
     *  which decides where [moveHeldAssets] gets its signing seed from. */
    private var sweptForeign: Boolean = false

    // Transient: the entered foreign phrase, held between classifyForeign and
    // sweepForeign so the sweep re-derives the same seed. Cleared after sweep /
    // on reset. (A JVM String can't be zeroed — same accepted limit as restore.)
    private var pendingForeignMnemonic: String? = null
    /**
     * NFKD UTF-8 bytes of the passphrase for the phrase being restored, or null. Never persisted,
     * and zeroed on every reset path — this used to be a String that lived for the whole screen
     * and could not be wiped.
     */
    private var pendingForeignPassphrase: ByteArray? = null

    /**
     * Why a passphrase restore came back empty. Null when no passphrase was supplied.
     *
     * Wired here rather than only in OnboardingViewModel because THIS is the flow a user
     * actually reaches — the onboarding copy was unreachable in production.
     */
    private val _passphraseVerdict =
        MutableStateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?>(null)
    val passphraseVerdict: StateFlow<io.digibyte.core.recovery.PassphraseScanVerdict.Outcome?> =
        _passphraseVerdict

    // The coroutine launched by whichever of classify/sweep/classifyForeign/
    // sweepForeign is currently in flight. reset() cancels it so a stale scan
    // or sweep can't complete afterwards and overwrite the Idle state it just
    // set. Cancellation still runs each method's `finally` block, so the seed
    // is zeroed either way.
    private var activeJob: kotlinx.coroutines.Job? = null

    /** Return to Idle, cancel any in-flight scan/sweep, and drop any held foreign phrase (mode switch / leaving). */
    fun reset() {
        activeJob?.cancel()
        pendingForeignMnemonic = null
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = null
        _passphraseVerdict.value = null
        sweptFindings = emptyList()
        sweptForeign = false
        _state.value = UiState.Idle
    }

    fun classify() {
        _state.value = UiState.Classifying
        activeJob = viewModelScope.launch {
            val seed = seedProvider.loadSeed() ?: run {
                _state.value = UiState.Error("Wallet seed unavailable")
                return@launch
            }
            try {
                // seed = 64-byte BIP39 seed (scanFromSeed takes ONLY the seed —
                // no passphrase argument).
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        lastFindings = s.nonNativeWithFunds
                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else {
                            UiState.Findings(
                                findings = s.nonNativeWithFunds,
                                totalSat = s.totalBalanceSat,
                                backendUnreachable = false,
                                partialFailurePaths = s.unreachableProfileLabels,
                                assetOutpointCount = countAssetOutpoints(s.nonNativeWithFunds),
                                // Deliberately NOT carried on the own-wallet tab. DigiDollar
                                // lives at m/86' of THIS seed, which this wallet already watches
                                // — reporting it as a recovery finding claims money is missing
                                // when it is in hand, and offers to move it to itself.
                            )
                        }
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Scan failed")
            } finally {
                seed.fill(0)
            }
        }
    }

    fun sweep(destination: SweepDestination) {
        val findings = lastFindings
        if (findings.isEmpty()) {
            _state.value = UiState.Error("Nothing to recover")
            return
        }
        when (val res = destination.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )) {
            is DestResolution.Invalid -> _state.value = UiState.Error(res.reason)
            is DestResolution.Ok -> {
                _state.value = UiState.Sweeping
                // A Native destination is this wallet's own fresh receive address,
                // so the sweep is a self-transfer that increases the balance — tag
                // it so the activity list keeps the C core's receive categorization
                // instead of rendering a negative "Sent" (see OutgoingTxStore
                // .shouldApplyOutgoingOverride). External destinations are real sends.
                val destIsSelf = destination is SweepDestination.Native
                activeJob = viewModelScope.launch {
                    val seed = seedProvider.loadSeed() ?: run {
                        _state.value = UiState.Error("Wallet seed unavailable")
                        return@launch
                    }
                    try {
                        val outcome = withContext(Dispatchers.IO) {
                            runRecovery(seed, findings, res.address, destIsSelf, isForeign = false)
                        }
                        sweptFindings = findings
                        sweptForeign = false
                        _state.value = UiState.Done(
                            outcome.swept.outcomes, outcome.assetMoves, outcome.digiDollar,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        _state.value = UiState.Error(t.message ?: "Sweep failed")
                    } finally {
                        seed.fill(0)
                    }
                }
            }
        }
    }

    /**
     * Count outpoints whose parent transaction carries a DigiAsset marker.
     *
     * Reads the raw transactions the scan already fetched, so this costs no network — the marker
     * check is a native call over bytes we hold. That is what makes it usable on the findings
     * screen, before the user commits to anything.
     */
    private fun countAssetOutpoints(results: List<RecoveryScanService.ProfileResult>): Int =
        results.sumOf { r ->
            r.utxos.count { u ->
                val hex = r.rawTxs[u.txid]?.hex ?: return@count false
                runCatching {
                    val b = ByteArray(hex.length / 2) {
                        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                    }
                    NativeBridge.isAssetTransaction(b)
                }.getOrDefault(false)
            }
        }

    /**
     * The recovery itself: move the DigiAssets, then sweep what they did not spend.
     *
     * Order matters and is the whole point. Sweeping first forced something to hold DGB back so
     * the assets could pay their own transfer fee, and that hold-back had to guess the fee before
     * the transfer existed. Moving first means the plans are built while every coin is still
     * there, so the sweep's input set is the exact complement of what they claimed — nothing is
     * estimated. See RecoverySequence.
     */
    private suspend fun runRecovery(
        seed: ByteArray,
        findings: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        destIsSelf: Boolean,
        isForeign: Boolean,
    ): RecoveryOutcome {
        val classifier = assetClassifier()
        var current = findings

        // At most one split. A wallet with more assets than spendable outputs needs its DGB
        // divided before anything can move; after that round there is an output per asset, so a
        // second split can never be required. Bounding it means a bug cannot loop on the user's
        // money.
        var fanOutTxid: String? = null
        repeat(2) {
            // Classified ONCE per round for both phases. Each pass fetches every parent
            // transaction, so classifying twice for one round doubles the work for one answer.
            val verdicts = classifier.classify(current.flatMap { it.utxos })

            val moveResult = io.digibyte.core.recovery.ForeignAssetTransferService(
                assetClassifier = classifier,
                outgoingTxStore = outgoingTxStore,
                walletTxPersister = walletTxPersister,
            ).moveAssets(
                seedBytes = seed,
                results = current,
                destAddress = destAddress,
                precomputedVerdicts = verdicts,
            )

            val fan = moveResult.fanOut
            if (fan is io.digibyte.core.recovery.ForeignAssetTransferService.FanOut.Broadcast) {
                // Its outputs are unconfirmed, and DigiByte inherits Bitcoin's
                // limitdescendantcount — dozens of unconfirmed children of one parent would be
                // rejected outright. So wait for it, then re-scan rather than trying to predict
                // the new UTXO set: the chain is the authority on what the split produced.
                fanOutTxid = fan.txid
                _state.value = UiState.SplittingForAssets(fan.feeOutputCount)
                val rescanned = awaitFanOutThenRescan(seed, fan.txid)
                if (rescanned == null) {
                    // The split is on the wire and will confirm; we simply could not see it in
                    // time. Nothing is lost — re-running recovery finds the new outputs, because
                    // the split paid the source wallet's own addresses.
                    return RecoveryOutcome(emptyList(), LegacySweepService.Result(emptyList()), null)
                }
                current = if (isForeign) rescanned.allWithFunds else rescanned.nonNativeWithFunds
                _state.value = UiState.Sweeping
                return@repeat
            }

            // DigiDollar moves alongside the assets and BEFORE the sweep, for the same reason:
            // the DGB paying its consensus fee is exactly what the sweep would otherwise take.
            // Its inputs join the same exclusion set.
            // Foreign only. On the own-wallet path the source and destination m/86' keys are the
            // same key, so a "move" would spend the 0.1 DGB consensus fee to send the dollars to
            // where they already are.
            val dd = if (isForeign) moveDigiDollar(seed, current, destIsSelf) else null

            val exclusions = io.digibyte.core.recovery.RecoverySequence.sweepExclusions(
                moveResult.moves.map {
                    io.digibyte.core.recovery.RecoverySequence.MoveRecord(
                        outpoint = it.outpoint,
                        spentInputs = it.spentInputs,
                        broadcast = it.moved,
                    )
                }
            )

            val swept = LegacySweepService(outgoingTxStore, walletTxPersister, classifier).sweepFromSeed(
                seedBytes = seed,
                nonNativeResults = current,
                destAddress = destAddress,
                excludeOutpoints = exclusions + dd?.spentInputs.orEmpty().toSet(),
                precomputedVerdicts = verdicts,
                destIsSelf = destIsSelf,
            )
            return RecoveryOutcome(moveResult.moves, swept, dd)
        }

        // Both rounds asked to split, which should be impossible: after one split there is an
        // output per asset. Reported rather than looped on.
        return RecoveryOutcome(emptyList(), LegacySweepService.Result(emptyList()), null)
    }

    /**
     * Move whatever DigiDollar this seed holds.
     *
     * Null when there is nothing to say — no dollars and a reachable lookup. Anything else is
     * reported, including dollars we found and could not move: a recovery that empties a wallet
     * of DGB while staying silent about its dollars is the failure this whole path exists to fix.
     */
    private suspend fun moveDigiDollar(
        seed: ByteArray,
        findings: List<RecoveryScanService.ProfileResult>,
        destIsSelf: Boolean,
    ): io.digibyte.core.recovery.DigiDollarTransferService.Result? {
        if (!destIsSelf) return null   // DigiDollar always comes home; there is no external form
        val scan = (scanService.state.value as? RecoveryScanService.State.Done)?.digiDollar
            ?: return null
        if (!scan.hasDollars && scan.reachable) return null

        // The destination is THIS wallet's DigiDollar address — the BIP86 taproot key it already
        // watches — so the dollars land somewhere the wallet can see and spend.
        val recipient = NativeBridge.getDigiDollarTaprootKeyHex() ?: return null
        val change = NativeBridge.getReceiveAddress(0, format = 2) ?: return null

        // The fee comes from the plain DGB the scan found, wherever it lives. Asset-bearing
        // outpoints are excluded by the same partition the sweep uses.
        //
        // An EMPTY selection is passed through rather than returned on: a wallet with dollars and
        // no DGB used to bail out here, so its dollars went unmentioned — the silence this path
        // exists to end. The transfer service refuses it honestly and reports the balance.
        val fee = io.digibyte.core.recovery.DigiDollarFeeSelection.from(findings)

        return io.digibyte.core.recovery.DigiDollarTransferService(
            outgoingTxStore = outgoingTxStore,
            walletTxPersister = walletTxPersister,
        ).move(
            seedBytes = seed,
            scan = scan,
            feeInputs = fee.inputs,
            feeProfile = fee.profile,
            recipientKeyHex = recipient,
            changeAddress = change,
        )
    }

    /**
     * Wait for the fee-output split to confirm, then re-scan.
     *
     * Re-scanning rather than predicting: the split spent inputs and created outputs, so the UTXO
     * list held in memory is stale the moment it broadcasts. The chain is the authority on what it
     * produced, and a re-scan is self-correcting if anything about the split differed from plan.
     *
     * Returns null on timeout — not a failure. The split is signed and on the wire; it pays the
     * SOURCE wallet's own addresses, so re-running recovery later simply finds the new outputs.
     * Nothing is stranded and nothing double-spends.
     */
    private suspend fun awaitFanOutThenRescan(
        seed: ByteArray,
        fanOutTxid: String,
    ): RecoveryScanService.State.Done? {
        repeat(FANOUT_CONFIRM_POLLS) {
            delay(FANOUT_POLL_INTERVAL_MS)
            val scanned = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }
            if (scanned is RecoveryScanService.State.Done) {
                // The split has landed once its outputs are visible as spendable UTXOs.
                val seen = scanned.results.any { r -> r.utxos.any { it.txid == fanOutTxid } }
                if (seen) return scanned
            }
        }
        return null
    }


    /** Scan a DIFFERENT wallet's phrase (not this wallet's stored seed). */
    fun classifyForeign(mnemonic: String, passphrase: String? = null) {
        val phrase = mnemonic.trim().split(Regex("\\s+")).joinToString(" ") { it.lowercase() }
        if (!NativeBridge.isValidMnemonic(phrase)) {
            _state.value = UiState.Error("That doesn't look like a valid recovery phrase.")
            return
        }
        pendingForeignMnemonic = phrase
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = io.digibyte.core.Bip39Passphrase.prepare(passphrase)
        _state.value = UiState.Classifying
        activeJob = viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), pendingForeignPassphrase) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        val set = sweepSet(s, isForeign = true)      // includes native
                        val total = set.sumOf { it.totalSat }

                        // A passphrase was supplied and found nothing. Ask the OTHER question:
                        // does this phrase have funds without it? A BIP39 passphrase has no
                        // checksum, so a typo derives a valid EMPTY wallet and the honest answer
                        // — "no funds" — reads to the user as stolen coins. One more scan turns
                        // that into "check the passphrase".
                        val bareTotal: Long? =
                            if (pendingForeignPassphrase != null && total == 0L) {
                                val bare = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null)
                                bare?.let {
                                    try {
                                        (withContext(Dispatchers.IO) { scanService.scanFromSeed(it) }
                                            as? RecoveryScanService.State.Done)
                                            ?.let { d -> sweepSet(d, isForeign = true).sumOf { f -> f.totalSat } }
                                    } finally {
                                        it.fill(0)
                                    }
                                }
                            } else null

                        _passphraseVerdict.value =
                            if (pendingForeignPassphrase == null) null
                            else io.digibyte.core.recovery.PassphraseScanVerdict.of(
                                withPassphraseSat = total,
                                withoutPassphraseSat = bareTotal,
                                incomplete = s.unreachableProfileLabels.isNotEmpty(),
                            )

                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else UiState.Findings(
                            findings = set, totalSat = total,
                            backendUnreachable = false, isForeign = true,
                            partialFailurePaths = s.unreachableProfileLabels,
                            assetOutpointCount = countAssetOutpoints(set),
                            digiDollar = s.digiDollar,
                        )
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Scan failed")
            } finally {
                seed.fill(0)
            }
        }
    }

    /** Sweep the previously-scanned foreign phrase into THIS wallet (native). */
    fun sweepForeign() {
        val phrase = pendingForeignMnemonic ?: run {
            _state.value = UiState.Error("Enter a recovery phrase and scan first."); return
        }
        val shown = _state.value as? UiState.Findings
        val findings = shown?.findings ?: emptyList()
        // Dollars alone are enough to run. A DigiDollar token output carries zero satoshis and so
        // never lands in [findings]; gating on findings alone meant a wallet holding only dollars
        // could not be recovered at all — there was nothing to press.
        if (!io.digibyte.core.recovery.RecoverableValue.exists(findings.size, shown?.digiDollar)) {
            _state.value = UiState.Error("Nothing to recover"); return
        }
        val dest = SweepDestination.Native.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )
        if (dest !is DestResolution.Ok) {
            _state.value = UiState.Error("Could not get a destination address"); return
        }
        _state.value = UiState.Sweeping
        activeJob = viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), pendingForeignPassphrase) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase.")
                pendingForeignMnemonic = null
        pendingForeignPassphrase?.fill(0)
        pendingForeignPassphrase = null
        _passphraseVerdict.value = null
                return@launch
            }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // foreign: all-funded incl. native; lands in THIS wallet -> receive
                    runRecovery(seed, findings, dest.address, destIsSelf = true, isForeign = true)
                }
                sweptFindings = findings
                sweptForeign = true
                _state.value = UiState.Done(
                    outcome.swept.outcomes, outcome.assetMoves, outcome.digiDollar,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Sweep failed")
            } finally {
                seed.fill(0)
                // The phrase is deliberately NOT cleared here any more. Moving the DigiAssets the
                // sweep held back needs the same seed, and it is a separate user-confirmed step
                // that happens after this returns. It is cleared by reset() when the user leaves
                // the screen, and by moveHeldAssets() as soon as the move finishes — so the
                // window is "this screen, until the assets are dealt with" rather than the whole
                // session. A JVM String still cannot be zeroed; that limit is unchanged.
                _passphraseVerdict.value = null
            }
        }
    }


    private companion object {
        /** ~15s blocks on DigiByte, so this allows roughly three minutes for the split to land.
         *  Timing out is not a failure — the split is signed, broadcast, and pays the source
         *  wallet's own addresses, so a later run finds its outputs. */
        const val FANOUT_CONFIRM_POLLS = 18
        const val FANOUT_POLL_INTERVAL_MS = 10_000L
    }
}
