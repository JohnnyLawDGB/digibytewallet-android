package io.digibyte.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.TxResult
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.asset.send.AssetFeeEstimator
import io.digibyte.core.model.OwnedAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/** Fee warning surfaced under the custom-fee field — mirrors the regular
 *  send's [io.digibyte.ui.wallet.FeeWarning] semantics. */
sealed class AssetFeeWarning {
    data object None : AssetFeeWarning()
    data object BelowRelay : AssetFeeWarning()
    data object ZeroFee : AssetFeeWarning()
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
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            val feeSat = (feeDgb * 100_000_000).toLong()
            if (feeSat <= 0 || ASSET_TYPICAL_VSIZE <= 0) return@combine DEFAULT_FEE_PER_KB
            (feeSat * 1000) / ASSET_TYPICAL_VSIZE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FEE_PER_KB)

    /** Estimated total fee in sats (for the fee-row display). */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            defaultFeeSat
        } else {
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            (feeDgb * 100_000_000).toLong()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultFeeSat)

    /** Warning state for the custom fee (amber below relay, red on zero). */
    val feeWarning: StateFlow<AssetFeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) return@combine AssetFeeWarning.None
        val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
        val feeSat = (feeDgb * 100_000_000).toLong()
        if (feeSat <= 0) return@combine AssetFeeWarning.ZeroFee
        val satPerVbyte = feeSat.toDouble() / ASSET_TYPICAL_VSIZE
        if (satPerVbyte < 100.0) AssetFeeWarning.BelowRelay else AssetFeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AssetFeeWarning.None)

    /** Toggle between default and custom fee mode. Seeds the custom field
     *  with the current default so the user edits from a sane baseline. */
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            customFeeInput.value = String.format("%.8f", defaultFeeSat / 100_000_000.0)
        }
    }

    fun selectAsset(assetId: String) {
        _selectedAssetId.value = assetId
    }

    /** Reset the send flow state — call on confirm dialog dismiss, screen
     *  re-entry, or after the user has seen a terminal result. */
    fun resetSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * Broadcast a DigiAsset transfer. Caller passes the raw user input
     * (decimal string, e.g. "0.50"); this method scales by the asset's
     * divisibility to the internal integer amount before handing off to
     * [AssetManager.sendAsset].
     *
     * Result is surfaced via [sendState] so the UI can show progress,
     * success (txid), or typed errors.
     */
    fun sendAssetTransfer(
        toAddress: String,
        quantityInput: String,
        feePerKb: Long,
    ) {
        val asset = selectedAsset.value
        if (asset == null) {
            _sendState.value = SendState.Failure("No asset selected")
            return
        }

        val divisibility = asset.metadata?.decimals ?: 0
        val internalQty = scaleToInternalUnits(quantityInput, divisibility)
        if (internalQty == null || internalQty <= 0L) {
            _sendState.value = SendState.Failure("Invalid quantity")
            return
        }
        if (internalQty > asset.quantity) {
            _sendState.value = SendState.Failure(
                "Insufficient balance: have ${asset.quantity}, need $internalQty"
            )
            return
        }

        _sendState.value = SendState.Sending
        viewModelScope.launch {
            val result = assetManager.sendAsset(
                assetId = asset.assetId,
                quantity = internalQty,
                toAddress = toAddress,
                feePerKb = feePerKb,
            )
            _sendState.value = when (result) {
                is TxResult.Success -> SendState.Success(result.txid)
                is TxResult.Error -> SendState.Failure(result.message)
            }
        }
    }

    sealed class SendState {
        object Idle : SendState()
        object Sending : SendState()
        data class Success(val txid: String) : SendState()
        data class Failure(val message: String) : SendState()
    }

    /**
     * Convert a user-entered decimal string (e.g. "1.25") to the asset's
     * internal integer representation given its divisibility. Rejects
     * values with more decimal places than the asset supports rather than
     * silently truncating — an asset with divisibility=2 can't represent
     * 1.234, so we refuse instead of sending the user-surprising 1.23.
     */
    private fun scaleToInternalUnits(input: String, divisibility: Int): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val decimal = trimmed.toBigDecimalOrNull() ?: return null
        if (decimal.signum() < 0) return null
        // Disallow more fractional digits than the asset allows.
        if (decimal.scale() > divisibility) return null
        val scaled = decimal.movePointRight(divisibility).setScale(0, RoundingMode.UNNECESSARY)
        return scaled.toLongOrNull()
    }

    private fun BigDecimal.toLongOrNull(): Long? =
        try { longValueExact() } catch (_: ArithmeticException) { null }

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
