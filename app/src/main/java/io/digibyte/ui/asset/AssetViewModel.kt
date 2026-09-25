package io.digibyte.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.SendRefusal
import io.digibyte.core.TxResult
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.asset.rules.RuleCheckState
import io.digibyte.core.asset.rules.TransferRuleState
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.asset.send.AssetFeeEstimator
import io.digibyte.core.model.ApprovedSend
import io.digibyte.core.model.AssetQuantity
import io.digibyte.core.model.OwnedAsset
import io.digibyte.core.model.DgbAmount
import io.digibyte.core.model.SendConfirmation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fee warning surfaced under the custom-fee field — mirrors the regular
 *  send's [io.digibyte.ui.wallet.FeeWarning] semantics. */
sealed class AssetFeeWarning {
    data object None : AssetFeeWarning()
    data object BelowRelay : AssetFeeWarning()
    data object ZeroFee : AssetFeeWarning()
}

/**
 * The quantity a transfer request named, for as long as the form's quantity field still shows it.
 *
 * A request names WHOLE UNITS of the asset. The field shows text at the asset's divisibility and
 * Review reads text at the asset's divisibility — 150 units of an asset with two decimals read
 * "1.5" — and that divisibility is a chain fact which can arrive after the form is on screen. So
 * the units are kept here as the integer they arrived as, and text is only ever written FROM
 * them, at the divisibility of the moment the text is needed:
 *
 *  - [fieldText] is what the field shows, and is asked for again whenever the divisibility changes;
 *  - [textToApprove] is what the approval is read from, written at the divisibility it is read
 *    at — so the approval holds exactly the requested units, whatever the field showed a moment
 *    earlier;
 *  - [edited] ends the request: from the user's first change the field is the user's text, is
 *    never written over, and is read as any typed quantity is.
 *
 * Plain state, used from the main thread only. It lives in the view model so that it outlives a
 * composition: the route hands its argument over again on every new one, and only the first
 * delivery counts — a request the user has edited away does not come back.
 */
class RequestedAssetQuantity {
    private var delivered = false
    private var units: Long? = null

    /** The route's request: [units] whole units, or null (or nothing positive) when it names none. */
    fun deliver(units: Long?) {
        if (delivered) return
        delivered = true
        this.units = units?.takeIf { it > 0L }
    }

    /** The field's text for the request at [divisibility], or null when the field is the user's own. */
    fun fieldText(divisibility: Int): String? = units?.let { AssetQuantity.format(it, divisibility) }

    /** The user changed the quantity field: what it holds is no longer the request, now or later. */
    fun edited() {
        delivered = true
        units = null
    }

    /**
     * The text an approval is read from at [divisibility]: the requested units written at that
     * divisibility while the field is still the request's, and otherwise [fieldText], the user's own.
     */
    fun textToApprove(fieldText: String, divisibility: Int): String = fieldText(divisibility) ?: fieldText
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetViewModel @Inject constructor(
    private val assetManager: AssetManager,
    private val assetMetadataDao: AssetMetadataDao
) : ViewModel() {

    val ownedAssets: StateFlow<List<OwnedAsset>> = assetManager.getOwnedAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedAssetId = MutableStateFlow<String?>(null)

    val selectedAsset: StateFlow<OwnedAsset?> = _selectedAssetId
        .combine(ownedAssets) { id, assets -> assets.find { it.assetId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Per-asset transaction history. Re-collects whenever the selected
     *  asset changes — previously this snapshot-read `_selectedAssetId.value`
     *  once at construction (always null) and never updated. */
    val assetHistory: StateFlow<List<TransactionEntity>> =
        _selectedAssetId
            .filterNotNull()
            .flatMapLatest { id -> assetManager.getAssetHistory(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Send flow state ────────────────────────────────────────────────

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** The confirmation on screen. It holds the one approval [sendAssetTransfer] accepts. */
    private val confirmation = SendConfirmation<ApprovedSend.Asset>()

    /** The approval the confirmation is drawn from, or null when no confirmation is up. */
    val approval: StateFlow<ApprovedSend.Asset?> = confirmation.approval

    /** What a transfer request asked for, while the quantity field still shows it. */
    val requestedQuantity = RequestedAssetQuantity()

    // ── Transfer-rule check ─────────────────────────────────────────────
    //
    // Resolved once per selected asset. Reads the stores first; only if that is UNKNOWN does it
    // walk the asset to its issuance and ask the proxy (verifyTransferRules). CHECKING is the only
    // transient value, and nothing but NONE enables Send — see RuleCheckState.allowsSend.
    private val _ruleCheck = MutableStateFlow(RuleCheckState.CHECKING)
    val ruleCheck: StateFlow<RuleCheckState> = _ruleCheck.asStateFlow()

    /** The in-flight check. Cancelled when a new asset is selected: a slow check for the asset
     *  the user just navigated away from must not land on the one they are looking at. */
    private var ruleJob: Job? = null

    private fun checkRules(assetId: String) {
        _ruleCheck.value = RuleCheckState.CHECKING
        ruleJob?.cancel()
        ruleJob = viewModelScope.launch {
            val read = try {
                assetManager.transferRuleState(assetId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("AssetViewModel", "rule check failed for $assetId", t)
                TransferRuleState.UNKNOWN
            }
            val state = if (read != TransferRuleState.UNKNOWN) read else try {
                assetManager.verifyTransferRules(assetId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("AssetViewModel", "rule verify failed for $assetId", t)
                TransferRuleState.UNKNOWN
            }
            // Cancellation is not instantaneous — a job already past its last suspension point
            // still runs to here. Writing only for the asset still selected is what makes the
            // guarantee: asset A's verdict can never enable Send for asset B.
            if (_selectedAssetId.value == assetId) _ruleCheck.value = RuleCheckState.of(state)
        }
    }

    fun retryRuleCheck() {
        _selectedAssetId.value?.let { checkRules(it) }
    }

    // ── Fee state (mirrors SendViewModel) ──────────────────────────────
    //
    // An asset send is a regular DGB tx carrying an OP_RETURN + a pinned
    // asset UTXO, so the fee is a regular DGB fee: a sat/kB rate (default
    // 100 sat/byte = min relay) with a custom TOTAL-DGB override. The old
    // 1/5/20 sat/byte tier chips were all BELOW min relay and got stuck.

    /** Whether the user has toggled the custom (total-DGB) fee override. */
    val isCustomFee = MutableStateFlow(false)

    /** Custom fee input — a TOTAL fee in DGB (same UX as the regular send). */
    val customFeeInput = MutableStateFlow("")

    /** Default estimated total fee in sats for a typical asset transfer. */
    val defaultFeeSat: Long = ASSET_TYPICAL_VSIZE * DEFAULT_FEE_PER_KB / 1000

    /** Fee rate in sat/kB handed to [AssetManager.sendAsset]. When custom,
     *  the user's total-DGB fee is converted to a rate over an asset-typical
     *  vsize; the size-aware estimator + min-relay floor in sendAsset then
     *  applies it to the concrete tx shape. */
    val feeRatePerKb: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            DEFAULT_FEE_PER_KB
        } else {
            val feeSat = DgbAmount.toSats(input) ?: 0L
            if (feeSat <= 0 || ASSET_TYPICAL_VSIZE <= 0) return@combine DEFAULT_FEE_PER_KB
            (feeSat * 1000) / ASSET_TYPICAL_VSIZE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FEE_PER_KB)

    /** Estimated total fee in sats (for the fee-row display). */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            defaultFeeSat
        } else {
            DgbAmount.toSats(input) ?: 0L
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultFeeSat)

    /** Warning state for the custom fee (amber below relay, red on zero). */
    val feeWarning: StateFlow<AssetFeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) return@combine AssetFeeWarning.None
        val feeSat = DgbAmount.toSats(input) ?: 0L
        if (feeSat <= 0) return@combine AssetFeeWarning.ZeroFee
        // Whole satoshis on both sides: under 100 sat/vB across the asset-typical size.
        if (feeSat < 100L * ASSET_TYPICAL_VSIZE) AssetFeeWarning.BelowRelay else AssetFeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AssetFeeWarning.None)

    /** Toggle between default and custom fee mode. Seeds the custom field
     *  with the current default so the user edits from a sane baseline. */
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            // Locale-free: String.format under a decimal-comma locale wrote "0,00014100".
            customFeeInput.value = DgbAmount.format(defaultFeeSat)
        }
    }

    fun selectAsset(assetId: String) {
        val needsCheck = _selectedAssetId.value != assetId ||
            _ruleCheck.value == RuleCheckState.UNVERIFIED
        // An approval is for one asset: it does not outlive the selection it was made under.
        if (_selectedAssetId.value != assetId) confirmation.close()
        // Set BEFORE launching: checkRules writes its result only while this asset is still the
        // selected one, and that guard is meaningless if the selection lands after the launch.
        _selectedAssetId.value = assetId
        if (needsCheck) checkRules(assetId)
    }

    /** Reset the send flow state — call on confirm dialog dismiss, screen
     *  re-entry, or after the user has seen a terminal result. */
    fun resetSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * Review. This is the one place the quantity text is read for a send: it becomes an
     * [ApprovedSend.Asset] here, at the asset's divisibility. The confirmation is drawn from that
     * object and [sendAssetTransfer] receives it, so nothing typed, pre-filled or loaded afterwards
     * can change what is sent.
     *
     * While the field still shows a transfer request, the text read is written here from the
     * requested units, at the same divisibility it is then read at: the approval holds exactly
     * those units even if the asset's divisibility became known after the field was filled.
     *
     * Returns false when the text is not a positive quantity of the selected asset. A quantity
     * with no recipient is accepted and opens nothing: a confirmation names where it goes.
     */
    fun requestConfirm(toAddress: String, quantityInput: String): Boolean {
        val asset = selectedAsset.value ?: return false
        val divisibility = divisibilityOf(asset)
        val approved = ApprovedSend.asset(
            address = toAddress,
            assetId = asset.assetId,
            typedQuantity = requestedQuantity.textToApprove(quantityInput, divisibility),
            divisibility = divisibility,
            feePerKb = feeRatePerKb.value,
            feeEstimateSats = estimatedFeeSat.value,
        ) ?: return false
        if (toAddress.isBlank()) return true
        // The confirmation shows the fee of the transfer as it would be built now (B231), not a
        // typical-size guess: a send that consolidates many coins pays for every input, and the
        // user approves that. sendAssetTransfer then refuses to sign anything costing more.
        planJob?.cancel()
        _planning.value = true
        planJob = viewModelScope.launch {
            val planned = try {
                assetManager.previewAssetTransferFee(
                    approved.assetId, approved.units, approved.address, approved.feePerKb,
                )
            } finally {
                _planning.value = false
            }
            // An answer for an asset no longer selected is not the user's current send.
            if (_selectedAssetId.value != approved.assetId) return@launch
            when (planned) {
                is AssetManager.AssetTransferPlanning.Planned ->
                    confirmation.open(approved.withPlannedFee(planned.plan.paidFeeSats))
                is AssetManager.AssetTransferPlanning.NotPlanned -> finish(planned.result.toSendState())
            }
        }
        return true
    }

    /** Planning the transfer for the confirmation (see [requestConfirm]) is under way. */
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning.asStateFlow()
    private var planJob: Job? = null

    private fun TxResult.toSendState(): SendState = when (this) {
        is TxResult.Success -> SendState.Success(txid)
        is TxResult.Error -> SendState.Failure(message)
        is TxResult.Refused -> SendState.Refused(reason)
    }

    /** Decimals of [asset] as the wallet holds them: the scale its quantities are typed and shown at. */
    private fun divisibilityOf(asset: OwnedAsset): Int = asset.metadata?.decimals ?: 0

    /** The confirmation was dismissed, or the credential prompt was: nothing is on screen to send. */
    fun cancelConfirm() {
        planJob?.cancel()
        confirmation.close()
        _sendState.value = SendState.Idle
    }

    /** A result is in. It takes the confirmation's place on screen. */
    private fun finish(result: SendState) {
        confirmation.close()
        _sendState.value = result
    }

    /**
     * Broadcast the DigiAsset transfer the user approved. The asset, the destination, the whole
     * units and the fee rate all come from [approved]; no field is read again.
     *
     * Result is surfaced via [sendState] so the UI can show progress,
     * success (txid), or typed errors.
     */
    fun sendAssetTransfer(approved: ApprovedSend.Asset) {
        // Only the approval that is on screen may be sent, and only once: a second tap, or an
        // approval replaced or cancelled while the credential prompt was up, finds nothing to send.
        if (!confirmation.claim(approved)) {
            android.util.Log.w("AssetViewModel", "send ignored: not the approval being confirmed")
            return
        }

        val asset = selectedAsset.value
        if (asset == null || asset.assetId != approved.assetId) {
            finish(SendState.Failure("No asset selected"))
            return
        }

        // The approval was read, and is shown, at one divisibility. If that is no longer the
        // asset's, the text on screen and the units held here are not one quantity: nothing is sent.
        if (divisibilityOf(asset) != approved.divisibility) {
            finish(SendState.Failure("Invalid quantity"))
            return
        }

        if (approved.units > asset.quantity) {
            finish(SendState.Failure(
                "Insufficient balance: have ${asset.quantity}, need ${approved.units}"
            ))
            return
        }

        if (!_ruleCheck.value.allowsSend) {
            finish(SendState.Refused(
                if (_ruleCheck.value == RuleCheckState.RULE_BOUND) SendRefusal.RULE_BOUND_ASSET
                else SendRefusal.RULES_UNKNOWN
            ))
            return
        }

        _sendState.value = SendState.Sending
        viewModelScope.launch {
            val result = assetManager.sendAsset(
                assetId = approved.assetId,
                quantity = approved.units,
                toAddress = approved.address,
                feePerKb = approved.feePerKb,
                maxFeeSats = approved.feeEstimateSats,
            )
            finish(result.toSendState())
        }
    }

    sealed class SendState {
        object Idle : SendState()
        object Sending : SendState()
        data class Success(val txid: String) : SendState()
        data class Failure(val message: String) : SendState()
        data class Refused(val reason: SendRefusal) : SendState()
    }

    companion object {
        /** DGB min relay / default fee rate — reuse the exact estimator
         *  constant (100,000 sat/kB = 100 sat/byte). */
        private const val DEFAULT_FEE_PER_KB = AssetFeeEstimator.MIN_RELAY_FEE_PER_KB

        /** Asset-typical vsize used ONLY for the custom total-DGB ⇄ rate
         *  conversion and the default-fee display. The regular send uses
         *  ~141 (close to its real 1-in/2-out size, so its estimate is
         *  accurate); an asset transfer (mixed inputs + OP_RETURN + markers)
         *  runs much larger. This MUST match the vsize the estimator actually
         *  charges over — AssetFeeEstimator's typical shape (1 asset + 1 DGB
         *  input incl. its +1 margin, 3 outputs, ~40-byte OP_RETURN) is
         *  12 + 3·150 + 3·34 + 9 + 40 = 613 vB. Using the old 400 biased the
         *  displayed/confirm-dialog fee ~45% LOW and made a custom TOTAL-DGB
         *  entry T get charged as ~1.47·T (T over 400 vB re-applied to ~590 vB).
         *  613 keeps the on-screen estimate at/above what AssetManager.sendAsset
         *  actually deducts and round-trips the custom total. */
        private const val ASSET_TYPICAL_VSIZE = 613L
    }
}
