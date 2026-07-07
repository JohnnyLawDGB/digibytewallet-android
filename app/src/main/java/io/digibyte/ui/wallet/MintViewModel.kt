package io.digibyte.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.digidollar.DigiDollarStatus
import io.digibyte.core.digidollar.MintService
import io.digibyte.digidollar.Collateral
import io.digibyte.digidollar.DigiDollarLimits
import io.digibyte.digidollar.LockTier
import io.digibyte.digidollar.LockTiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Mint calculator + confirm flow (issue #11): the user enters a USD
 * DigiDollar amount and picks a Lock tier; the screen previews the DGB
 * collateral this Mint will lock (live Oracle price × the tier's ratio) and,
 * on confirm, drives [MintService.mint] — signing, broadcasting, and landing a
 * new collateral Position. Testnet-only until the 4.0.0 mainnet unlock: the
 * nav entry is testnet-gated and MintService refuses mainnet regardless.
 *
 * The preview reads [MintService.currentStatus] — the SAME price source and
 * network mint() computes against — and reuses [Collateral.requiredSats], so
 * the on-screen number cannot drift from what actually gets locked.
 */
@HiltViewModel
class MintViewModel @Inject constructor(
    private val mintService: MintService,
) : ViewModel() {

    /** Live Oracle status for the collateral preview; null until fetched / on error. */
    private val _status = MutableStateFlow<DigiDollarStatus?>(null)
    val status: StateFlow<DigiDollarStatus?> = _status.asStateFlow()

    /** null = not yet attempted; true = reachable; false = unreachable (retry). */
    private val _statusLoaded = MutableStateFlow<Boolean?>(null)
    val statusLoaded: StateFlow<Boolean?> = _statusLoaded.asStateFlow()

    val amountUsd = MutableStateFlow("")

    private val _tierIndex = MutableStateFlow(0)
    val tierIndex: StateFlow<Int> = _tierIndex.asStateFlow()

    sealed class MintState {
        data object Idle : MintState()
        data object Minting : MintState()
        data class Success(
            val txid: String,
            val unlockHeight: Int,
            val collateralSats: Long,
        ) : MintState()

        /** Policy blocked (gate/price/sync) OR the attempt failed — one banner. */
        data class Failed(val message: String) : MintState()
    }

    private val _mintState = MutableStateFlow<MintState>(MintState.Idle)
    val mintState: StateFlow<MintState> = _mintState.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = mintService.currentStatus()
            _status.value = s
            _statusLoaded.value = s != null
        }
    }

    fun onAmountChanged(value: String) {
        amountUsd.value = value
    }

    fun selectTier(index: Int) {
        _tierIndex.value = index
    }

    val selectedTier: LockTier get() = LockTiers.byIndex(_tierIndex.value)

    fun mint() {
        if (_mintState.value is MintState.Minting) return
        val cents = parseUsdToCents(amountUsd.value)
        if (cents == null || !mintAmountValid(cents)) {
            _mintState.value = MintState.Failed(
                "Enter a DigiDollar amount between \$100 and \$100,000",
            )
            return
        }
        val tier = selectedTier
        _mintState.value = MintState.Minting
        viewModelScope.launch(Dispatchers.IO) {
            _mintState.value = when (val r = mintService.mint(cents, tier)) {
                is MintService.MintResult.Success ->
                    MintState.Success(r.txid, r.unlockHeight, r.collateralSats)
                is MintService.MintResult.Blocked -> MintState.Failed(r.reason)
                is MintService.MintResult.Error -> MintState.Failed(r.message)
            }
        }
    }

    fun dismissResult() {
        _mintState.value = MintState.Idle
    }

    companion object {
        /** "100.00" USD → 10000 cents; null if blank/non-numeric/negative. */
        fun parseUsdToCents(s: String): Long? {
            val d = s.trim().toDoubleOrNull() ?: return null
            if (d < 0) return null
            return Math.round(d * 100.0)
        }

        /** Within the consensus Mint range [$100, $100,000]. */
        fun mintAmountValid(cents: Long): Boolean =
            cents in DigiDollarLimits.MIN_MINT_CENTS..DigiDollarLimits.MAX_MINT_CENTS

        /**
         * DGB collateral (satoshis) this Mint locks, previewing the exact
         * [Collateral.requiredSats] math mint() runs. Null when the amount is
         * non-positive or the price is missing — the screen shows a dash rather
         * than a bogus figure. Excludes the flat network fee (added separately).
         */
        fun previewCollateralSats(
            cents: Long,
            tier: LockTier,
            priceMicroUsd: Long,
            dcaMultiplierBps: Long,
        ): Long? = runCatching {
            Collateral.requiredSats(cents, tier, priceMicroUsd, dcaMultiplierBps)
        }.getOrNull()
    }
}
