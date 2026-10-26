package app.aroundtheblock.wallet.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.SendRefusal
import app.aroundtheblock.wallet.core.TxResult
import app.aroundtheblock.wallet.core.asset.AssetManager
import app.aroundtheblock.wallet.core.asset.rules.RuleCheckState
import app.aroundtheblock.wallet.core.asset.rules.TransferRuleState
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.TransactionEntity
import app.aroundtheblock.wallet.core.asset.send.AssetFeeEstimator
import app.aroundtheblock.wallet.core.asset.send.AssetTransferPlan
import app.aroundtheblock.wallet.core.asset.send.PlannedOutput
import app.aroundtheblock.wallet.core.model.ApprovedSend
import app.aroundtheblock.wallet.core.model.AssetQuantity
import app.aroundtheblock.wallet.core.model.OwnedAsset
import app.aroundtheblock.wallet.core.model.DgbAmount
import app.aroundtheblock.wallet.core.model.SendConfirmation
import app.aroundtheblock.wallet.core.send.FeeQuote
import app.aroundtheblock.wallet.core.send.FeeRequest
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

/** What the custom-fee field says of itself — the regular send's
 *  [app.aroundtheblock.wallet.ui.wallet.FeeWarning]. No "below the relay minimum" state: the planner
 *  raises a low custom fee to the floor, and the confirmation shows the fee raised. */
sealed class AssetFeeWarning {
    data object None : AssetFeeWarning()
    /** A custom fee that is not a positive amount: Review plans nothing until it is one. */
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

/**
 * The DGB an asset send moves, read from the transfer as planned: the markers it emits and the fee
 * it pays. The confirmation's marker lines and total come from here, from the same plan as its fee,
 * rather than from the displayed balance. (The planner emits the asset-change marker on every send,
 * as the last output to the source address; [changeMarkerSats] still reads it from the plan.)
 */
data class AssetSendCost(
    /** The recipient's marker. */
    val recipientMarkerSats: Long,
    /** The marker that carries asset change back to the wallet; 0 when the plan returns none. */
    val changeMarkerSats: Long,
    /** What the planned transaction pays the network. */
    val feeSats: Long,
) {
    val hasChangeMarker: Boolean get() = changeMarkerSats > 0L
    val totalSats: Long get() = recipientMarkerSats + changeMarkerSats + feeSats

    companion object {
        /** Before anything is planned (the form): the two markers every send emits — the
         *  recipient's and the last output's — and the estimated fee. */
        fun beforePlan(estimatedFeeSats: Long): AssetSendCost = AssetSendCost(
            recipientMarkerSats = app.aroundtheblock.wallet.core.asset.send.DA_MARKER_SATS,
            changeMarkerSats = app.aroundtheblock.wallet.core.asset.send.DA_MARKER_SATS,
            feeSats = estimatedFeeSats,
        )

        fun of(plan: AssetTransferPlan): AssetSendCost = AssetSendCost(
            recipientMarkerSats = plan.outputs.filter { it.role == PlannedOutput.Role.RECIPIENT_MARKER }.sumOf { it.sats },
            changeMarkerSats = plan.outputs.filter { it.role == PlannedOutput.Role.ASSET_CHANGE_MARKER }.sumOf { it.sats },
            feeSats = plan.paidFeeSats,
        )
    }
}

/**
 * The planned cost of one approval: shown only while that approval is the one on screen.
 * [raisedFromSats] is the custom total typed when the planned transfer pays more than it, else null.
 */
class PlannedAssetCost(val approval: ApprovedSend.Asset, val cost: AssetSendCost, val raisedFromSats: Long? = null)

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

    /** The cost of the plan the confirmation was opened with, keyed to that approval. */
    private val _plannedCost = MutableStateFlow<PlannedAssetCost?>(null)
    val plannedCost: StateFlow<PlannedAssetCost?> = _plannedCost.asStateFlow()

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

    // ── Fee state ──────────────────────────────────────────────────────
    //
    // An asset send is a regular DGB tx carrying an OP_RETURN + a pinned asset UTXO, so the fee is a
    // regular DGB fee: 100 sat/vB by default (DigiByte's relay floor), or a custom TOTAL in DGB. The
    // fee the confirmation shows is the fee of the transfer as planned at Review (B231), which the
    // send signs no more than; a custom total becomes a rate over THAT transfer's own size.

    /** Whether the user has toggled the custom (total-DGB) fee override. */
    val isCustomFee = MutableStateFlow(false)

    /** Custom fee input — a TOTAL fee in DGB (same UX as the regular send). */
    val customFeeInput = MutableStateFlow("")

    /**
     * The fee for the form's cost preview, which labels it an estimate: the custom total typed, or
     * the planner's own size-aware estimate for the smallest transfer (one asset coin and one fee
     * coin) at the default rate. Never the confirmation's fee: that is the planned transfer's.
     */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (custom) DgbAmount.toSats(input) ?: 0L else smallestTransferFeeSats()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, smallestTransferFeeSats())

    /** The custom-fee field's own state: no figure is worked out here. */
    val feeWarning: StateFlow<AssetFeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (FeeRequest.of(custom, input) == null) AssetFeeWarning.ZeroFee else AssetFeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AssetFeeWarning.None)

    /** The fee of the last transfer planned on this screen: where a custom fee starts from. */
    private var lastPlannedFeeSats: Long? = null

    /** Toggle between default and custom fee mode. A custom fee starts from the fee of the last
     *  transfer planned here, when there is one. */
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            // Locale-free (DgbAmount, not String.format, which writes a comma under some locales).
            lastPlannedFeeSats?.let { customFeeInput.value = DgbAmount.format(it) }
        }
    }

    fun selectAsset(assetId: String) {
        val needsCheck = _selectedAssetId.value != assetId ||
            _ruleCheck.value == RuleCheckState.UNVERIFIED
        // An approval is for one asset: it does not outlive the selection it was made under.
        if (_selectedAssetId.value != assetId) {
            confirmation.close()
            _plannedCost.value = null
        }
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
     * with no recipient is accepted and opens nothing: a confirmation names where it goes; nor does
     * a custom fee that is not a positive amount (the field says "Fee required").
     */
    fun requestConfirm(toAddress: String, quantityInput: String): Boolean {
        val asset = selectedAsset.value ?: return false
        val divisibility = divisibilityOf(asset)
        val typed = ApprovedSend.asset(
            address = toAddress,
            assetId = asset.assetId,
            typedQuantity = requestedQuantity.textToApprove(quantityInput, divisibility),
            divisibility = divisibility,
            feePerKb = DEFAULT_FEE_PER_KB,
            feeEstimateSats = 0L,
        ) ?: return false
        if (toAddress.isBlank()) return true
        val fee = FeeRequest.of(isCustomFee.value, customFeeInput.value) ?: return true
        // The confirmation shows the fee of the transfer as it would be built now (B231), not a
        // typical-size guess: a send that consolidates many coins pays for every input, and the
        // user approves that. sendAssetTransfer then refuses to sign anything costing more.
        planJob?.cancel()
        _planning.value = true
        planJob = viewModelScope.launch {
            val (approved, planned) = try {
                planFor(typed, fee)
            } finally {
                _planning.value = false
            }
            // An answer for an asset no longer selected is not the user's current send.
            if (_selectedAssetId.value != approved.assetId) return@launch
            when (planned) {
                is AssetManager.AssetTransferPlanning.Planned -> {
                    // Fee, markers and total from one plan: the one the send then signs.
                    val cost = AssetSendCost.of(planned.plan)
                    lastPlannedFeeSats = planned.plan.paidFeeSats
                    val raisedFrom = (fee as? FeeRequest.CustomTotal)?.totalSats?.takeIf { planned.plan.paidFeeSats > it }
                    if (confirmation.open(approved.withPlannedFee(planned.plan.paidFeeSats))) {
                        _plannedCost.value = confirmation.approval.value?.let { PlannedAssetCost(it, cost, raisedFrom) }
                    }
                }
                is AssetManager.AssetTransferPlanning.NotPlanned -> finish(planned.result.toSendState())
            }
        }
        return true
    }

    /**
     * The transfer as planned for [fee], and the approval it is planned under. At the default rate;
     * or, for a custom total, at the rate that total is over this transfer's own size — measured by
     * planning it at the default rate first, where the planner's fee is its size at 100 sat/vB. The
     * planner raises a total below the floor; the confirmation then says so.
     */
    private suspend fun planFor(
        typed: ApprovedSend.Asset,
        fee: FeeRequest,
    ): Pair<ApprovedSend.Asset, AssetManager.AssetTransferPlanning> {
        val atDefault = assetManager.previewAssetTransferFee(typed.assetId, typed.units, typed.address, typed.feePerKb)
        if (fee !is FeeRequest.CustomTotal || atDefault !is AssetManager.AssetTransferPlanning.Planned) return typed to atDefault
        val vsize = atDefault.plan.estimatedFeeSats * 1000 / DEFAULT_FEE_PER_KB
        val approved = typed.atRate(FeeQuote.rateForTotal(fee.totalSats, vsize).coerceAtLeast(1L))
        return approved to assetManager.previewAssetTransferFee(approved.assetId, approved.units, approved.address, approved.feePerKb)
    }

    /** Planning the transfer for the confirmation (see [requestConfirm]) is under way. */
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning.asStateFlow()
    private var planJob: Job? = null

    private fun TxResult.toSendState(): SendState = when (this) {
        is TxResult.Success -> SendState.Success(txid)
        is TxResult.Error -> SendState.Failure(message)
        is TxResult.Refused -> SendState.Refused(reason, limit)
    }

    /** Decimals of [asset] as the wallet holds them: the scale its quantities are typed and shown at. */
    private fun divisibilityOf(asset: OwnedAsset): Int = asset.metadata?.decimals ?: 0

    /** The confirmation was dismissed, or the credential prompt was: nothing is on screen to send. */
    fun cancelConfirm() {
        planJob?.cancel()
        confirmation.close()
        _plannedCost.value = null
        _sendState.value = SendState.Idle
    }

    /** A result is in. It takes the confirmation's place on screen. */
    private fun finish(result: SendState) {
        confirmation.close()
        _plannedCost.value = null
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
        data class Refused(val reason: SendRefusal, val limit: Long? = null) : SendState()
    }

    companion object {
        /** DGB min relay / default fee rate — reuse the exact estimator
         *  constant (100,000 sat/kB = 100 sat/byte). */
        private const val DEFAULT_FEE_PER_KB = AssetFeeEstimator.MIN_RELAY_FEE_PER_KB

        /** The planner's size-aware estimate for the smallest transfer — one asset coin, one fee coin,
         *  the recipient's and the change markers and DGB change, a short instruction — at the default
         *  rate. Only the form's cost preview, labelled an estimate, shows it. */
        private fun smallestTransferFeeSats(): Long = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 3, opReturnBytes = 40, feePerKb = DEFAULT_FEE_PER_KB,
        )
    }
}
