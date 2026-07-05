package io.digibyte.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.PriceProvider
import io.digibyte.core.TransactionBuilder
import io.digibyte.core.TxResult
import io.digibyte.core.UtxoManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.DigiByteUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

sealed class SendState {
    data object Idle : SendState()
    data object Confirming : SendState()
    data object Sending : SendState()
    data class Success(val txid: String) : SendState()
    data class Error(val message: String) : SendState()
}

/** Estimated vsize for a typical 1-input P2WPKH → 2-output transaction. */
private const val TYPICAL_TX_VSIZE = 141L

/** Default fee rate: 100,000 sat/KB (100 sat/byte) — DigiByte min relay fee. */
private const val DEFAULT_FEE_PER_KB = 100_000L

sealed class FeeWarning {
    data object None : FeeWarning()
    data object BelowRelay : FeeWarning()
    data object ZeroFee : FeeWarning()
}

@HiltViewModel
class SendViewModel @Inject constructor(
    private val transactionBuilder: TransactionBuilder,
    private val utxoManager: UtxoManager,
    private val priceProvider: PriceProvider
) : ViewModel() {

    /** Destination address — validated on change. */
    val address = MutableStateFlow("")

    /** Is the current address valid? */
    private val _addressValid = MutableStateFlow<Boolean?>(null) // null = not yet validated
    val addressValid: StateFlow<Boolean?> = _addressValid.asStateFlow()

    /** Amount in DGB (user text input). */
    val amountDgb = MutableStateFlow("")

    /** Amount in fiat (user text input or converted). Kept in sync with amountDgb. */
    val amountFiat = MutableStateFlow("")

    /** Whether user has toggled custom fee mode. */
    val isCustomFee = MutableStateFlow(false)

    /** Custom fee input in DGB (text field value). */
    val customFeeInput = MutableStateFlow("")

    /** Default fee estimate in satoshis for a typical transaction. */
    val defaultFeeSat: Long = TYPICAL_TX_VSIZE * DEFAULT_FEE_PER_KB / 1000

    /** Fee rate in sat/KB to pass to the C core. */
    val feeRatePerKb: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            DEFAULT_FEE_PER_KB
        } else {
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            val feeSat = (feeDgb * 100_000_000).toLong()
            if (feeSat <= 0 || TYPICAL_TX_VSIZE <= 0) return@combine DEFAULT_FEE_PER_KB
            (feeSat * 1000) / TYPICAL_TX_VSIZE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FEE_PER_KB)

    /** Estimated total fee in satoshis (for display). */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            defaultFeeSat
        } else {
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            (feeDgb * 100_000_000).toLong()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultFeeSat)

    /** Warning state for the custom fee. */
    val feeWarning: StateFlow<FeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) return@combine FeeWarning.None
        val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
        val feeSat = (feeDgb * 100_000_000).toLong()
        if (feeSat <= 0) return@combine FeeWarning.ZeroFee
        val satPerVbyte = feeSat.toDouble() / TYPICAL_TX_VSIZE
        if (satPerVbyte < 100.0) FeeWarning.BelowRelay else FeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeeWarning.None)

    /** Current send flow state. */
    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** Error message for validation failures shown inline. */
    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    // ── Address validation ────────────────────────────────────────────────

    fun onAddressChanged(value: String) {
        address.value = value.trim()
        _addressValid.value = if (value.isBlank()) null
                              else NativeBridge.isValidAddress(value.trim())
        _validationError.value = null
    }

    // ── Amount input ──────────────────────────────────────────────────────

    fun onAmountDgbChanged(value: String) {
        amountDgb.value = value
        _validationError.value = null
        // Try to convert to fiat
        viewModelScope.launch {
            val dgb = value.toDoubleOrNull() ?: return@launch
            runCatching {
                val price = priceProvider.fetchPrice()
                val fiat = dgb * price.priceUsd
                amountFiat.value = NumberFormat.getNumberInstance(Locale.US).apply {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }.format(fiat)
            }
        }
    }

    fun onAmountFiatChanged(value: String) {
        amountFiat.value = value
        _validationError.value = null
        // Try to convert to DGB
        viewModelScope.launch {
            val fiat = value.toDoubleOrNull() ?: return@launch
            runCatching {
                val price = priceProvider.fetchPrice()
                if (price.priceUsd <= 0.0) return@launch
                val dgb = fiat / price.priceUsd
                amountDgb.value = NumberFormat.getNumberInstance(Locale.US).apply {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 8
                }.format(dgb)
            }
        }
    }

    // ── QR / URI parsing ─────────────────────────────────────────────────

    /**
     * Parse a scanned QR string (raw address or digibyte: URI) and populate fields.
     */
    fun applyScannedUri(raw: String) {
        val uri = DigiByteUri.parse(raw) ?: return
        onAddressChanged(uri.address)
        uri.amount?.let { sats ->
            val dgb = sats / 100_000_000.0
            onAmountDgbChanged(
                NumberFormat.getNumberInstance(Locale.US).apply {
                    minimumFractionDigits = 0
                    maximumFractionDigits = 8
                }.format(dgb)
            )
        }
    }

    // ── Amount conversion helpers ─────────────────────────────────────────

    /** Convert current DGB input to satoshis. Returns null if invalid. */
    fun amountSatoshis(): Long? {
        val dgb = amountDgb.value.replace(",", "").toDoubleOrNull() ?: return null
        if (dgb <= 0.0) return null
        return (dgb * 100_000_000).toLong()
    }

    // ── Send flow ─────────────────────────────────────────────────────────

    /** Move to the Confirming state (shows confirmation dialog). */
    fun requestConfirm() {
        val addr = address.value
        val sats = amountSatoshis()

        if (addr.isBlank() || _addressValid.value != true) {
            _validationError.value = "Enter a valid DigiByte address"
            return
        }
        if (sats == null || sats <= 0) {
            _validationError.value = "Enter a valid amount"
            return
        }

        _sendState.value = SendState.Confirming
    }

    fun cancelConfirm() {
        if (_sendState.value is SendState.Confirming) {
            _sendState.value = SendState.Idle
        }
    }

    /**
     * Execute the send after biometric/pin auth succeeds.
     * Collects spendable UTXOs then calls TransactionBuilder.
     */
    fun send() {
        val addr = address.value
        val sats = amountSatoshis() ?: run {
            _sendState.value = SendState.Error("Invalid amount")
            return
        }
        val feePerKb = feeRatePerKb.value

        _sendState.value = SendState.Sending

        viewModelScope.launch {
            // Collect spendable UTXOs once
            val utxos = utxoManager.getSpendableUtxos().first()
            val result = transactionBuilder.sendTransaction(addr, sats, feePerKb, utxos)
            _sendState.value = when (result) {
                is TxResult.Success -> SendState.Success(result.txid)
                is TxResult.Error   -> SendState.Error(result.message)
            }
        }
    }

    fun resetState() {
        _sendState.value = SendState.Idle
        _validationError.value = null
    }

    /** Toggle between default and custom fee mode. */
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            customFeeInput.value = String.format("%.8f", defaultFeeSat / 100_000_000.0)
        }
    }

    // ── DigiDollar send mode ─────────────────────────────────────────────

    enum class SendMode { DGB, DD }
    private val _sendMode = MutableStateFlow(SendMode.DGB)
    val sendMode: StateFlow<SendMode> = _sendMode.asStateFlow()
    fun toggleSendMode() { _sendMode.value = if (_sendMode.value == SendMode.DGB) SendMode.DD else SendMode.DGB }

    /** Live DigiDollar balance in cents — polled on the same 5s cadence as
     *  WalletViewModel's balance poll so the toggle + amount validation
     *  have a fresh ceiling while this screen is open. */
    private val _ddBalance = MutableStateFlow(0L)
    val ddBalance: StateFlow<Long> = _ddBalance.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val cents = runCatching { NativeBridge.getDigiDollarBalance() }.getOrDefault(0L)
                if (cents != _ddBalance.value) _ddBalance.value = cents
                delay(5_000L)
            }
        }
    }

    /**
     * Execute a DigiDollar send after the caller has validated [usd] via
     * [parseUsdToCents]/[ddAmountValid]. Mirrors [send]'s dispatcher pattern.
     */
    fun sendDigiDollar(tdAddress: String, usd: String, onResult: (txid: String?) -> Unit) {
        val cents = parseUsdToCents(usd)
        if (cents == null || !NativeBridge.isValidDigiDollarAddress(tdAddress)) { onResult(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            val txid = runCatching { NativeBridge.sendDigiDollar(tdAddress, cents) }
                .onFailure { android.util.Log.w("SendViewModel", "sendDigiDollar failed", it) }
                .getOrNull()
            onResult(txid)
        }
    }

    companion object {
        /** "40.50" USD -> 4050 cents; null if blank/non-numeric/negative. */
        fun parseUsdToCents(s: String): Long? {
            val d = s.trim().toDoubleOrNull() ?: return null
            if (d < 0) return null
            return Math.round(d * 100.0)
        }

        /** DD send amount valid: within consensus [100, 10000000] cents and <= held balance. */
        fun ddAmountValid(cents: Long, ddBalance: Long): Boolean =
            cents in 100..10_000_000 && cents <= ddBalance
    }
}
