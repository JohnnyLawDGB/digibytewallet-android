package io.digibyte.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.digidollar.PositionsService
import io.digibyte.core.digidollar.RedemptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DigiDollar collateral positions (issue #10): lists the wallet's open
 * positions from [PositionsService] and drives [RedemptionService.redeem].
 * All wallet/native work runs on IO — redeem() signs and persists on the
 * caller's dispatcher (the TransactionBuilder convention).
 */
@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val positionsService: PositionsService,
    private val redemptionService: RedemptionService,
) : ViewModel() {

    /** One position with its unlock state against the network tip. */
    data class PositionRow(
        val position: RedemptionService.CollateralPosition,
        val unlocked: Boolean,
        val blocksRemaining: Long,
    )

    sealed class RedeemState {
        data object Idle : RedeemState()
        data object Redeeming : RedeemState()
        data class Success(val txid: String, val collateralReturnedSats: Long) : RedeemState()
        /** Policy said no (still locked, softfork gate) or the attempt failed. */
        data class Failed(val message: String) : RedeemState()
    }

    private val _positions = MutableStateFlow<List<PositionRow>?>(null) // null = loading
    val positions: StateFlow<List<PositionRow>?> = _positions.asStateFlow()

    private val _redeemState = MutableStateFlow<RedeemState>(RedeemState.Idle)
    val redeemState: StateFlow<RedeemState> = _redeemState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val networkHeight = NativeBridge.getEstimatedBlockHeight()
            _positions.value = positionsService.listOpenPositions().map { p ->
                PositionRow(
                    position = p,
                    unlocked = networkHeight >= p.lockHeight,
                    blocksRemaining = (p.lockHeight - networkHeight).coerceAtLeast(0),
                )
            }
        }
    }

    fun redeem(position: RedemptionService.CollateralPosition) {
        if (_redeemState.value == RedeemState.Redeeming) return
        _redeemState.value = RedeemState.Redeeming
        viewModelScope.launch(Dispatchers.IO) {
            _redeemState.value = when (val result = redemptionService.redeem(position)) {
                is RedemptionService.RedemptionResult.Success ->
                    RedeemState.Success(result.txid, result.collateralReturnedSats)
                is RedemptionService.RedemptionResult.Blocked -> RedeemState.Failed(result.reason)
                is RedemptionService.RedemptionResult.Error -> RedeemState.Failed(result.message)
            }
            refresh() // the redeemed position's collateral is now spent
        }
    }

    fun dismissRedeemResult() {
        _redeemState.value = RedeemState.Idle
    }
}
