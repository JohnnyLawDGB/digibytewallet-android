package io.digibyte.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.TxResult
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.TransactionEntity
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
        feeSats: Long,
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
                feeSats = feeSats,
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
}
