package io.digibyte.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.PriceProvider
import io.digibyte.core.TransactionBuilder
import io.digibyte.core.TxResult
import io.digibyte.core.UtxoManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.ApprovedSend
import io.digibyte.core.model.DigiByteUri
import io.digibyte.core.model.DgbAmount
import io.digibyte.core.model.UsdCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

sealed class SendState {
    data object Idle : SendState()
    /** The confirmation is on screen, showing [approved] — the only thing [SendViewModel.send] accepts. */
    data class Confirming(val approved: ApprovedSend.Dgb) : SendState()
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

    /** Is the current address valid as a DigiDollar (TD…) address? Computed
     *  alongside [addressValid] since SendScreen reuses one address field
     *  for both DGB and DD send modes. */
    private val _ddAddressValid = MutableStateFlow<Boolean?>(null) // null = not yet validated
    val ddAddressValid: StateFlow<Boolean?> = _ddAddressValid.asStateFlow()

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
            val feeSat = DgbAmount.toSats(input) ?: 0L
            if (feeSat <= 0 || TYPICAL_TX_VSIZE <= 0) return@combine DEFAULT_FEE_PER_KB
            (feeSat * 1000) / TYPICAL_TX_VSIZE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FEE_PER_KB)

    /** Estimated total fee in satoshis (for display). */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            defaultFeeSat
        } else {
            DgbAmount.toSats(input) ?: 0L
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultFeeSat)

    /** Warning state for the custom fee. */
    val feeWarning: StateFlow<FeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) return@combine FeeWarning.None
        val feeSat = DgbAmount.toSats(input) ?: 0L
        if (feeSat <= 0) return@combine FeeWarning.ZeroFee
        // Whole satoshis on both sides: under 100 sat/vB across the typical size.
        if (feeSat < 100L * TYPICAL_TX_VSIZE) FeeWarning.BelowRelay else FeeWarning.None
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
        _ddAddressValid.value = if (value.isBlank()) null
                              else runCatching { NativeBridge.isValidDigiDollarAddress(value.trim()) }.getOrDefault(false)
        _validationError.value = null
    }

    // ── Amount input ──────────────────────────────────────────────────────

    /**
     * Numbers every edit of either amount field. Each field can be worked out from the other
     * through a price lookup, and the lookup takes time: what it returns belongs to the edit that
     * started it, and may be written only while that edit is still the latest one. Main thread only.
     */
    private var amountEdit = 0L

    fun onAmountDgbChanged(value: String) {
        val edit = ++amountEdit
        amountDgb.value = value
        // The dollars line on screen was worked out from the text BEFORE this edit. It goes now,
        // not when the lookup returns: until the line for this text lands there is no line, so
        // neither the form nor a confirmation can set this amount beside an earlier one's dollars.
        amountFiat.value = ""
        _validationError.value = null
        // Read by the parser the send uses. Text that is not an amount gets no line at all.
        if (DgbAmount.toSats(value) == null) return
        viewModelScope.launch {
            val preview = runCatching {
                UsdCents.previewForDgb(value, priceProvider.fetchPrice().priceUsd)
            }.getOrDefault("")
            // Only for the edit it was worked out for — not after a later one, in either field.
            if (edit == amountEdit) amountFiat.value = preview
        }
    }

    fun onAmountFiatChanged(value: String) {
        val edit = ++amountEdit
        amountFiat.value = value
        // The same rule in the other direction, where it decides what can be sent: the DGB amount
        // is what Review reads, and the one on screen was worked out from the dollars text before
        // this edit. It goes now. Until the amount for THIS text lands the DGB field is empty, and
        // Review answers "enter a valid amount" instead of approving an amount for other text.
        amountDgb.value = ""
        _validationError.value = null
        if (UsdCents.parse(value) == null) return
        viewModelScope.launch {
            val dgbText = runCatching {
                UsdCents.dgbTextFor(value, priceProvider.fetchPrice().priceUsd)
            }.getOrDefault("")
            if (edit == amountEdit) amountDgb.value = dgbText
        }
    }

    // ── QR / URI parsing ─────────────────────────────────────────────────

    /**
     * Parse a scanned QR string (raw address or digibyte: URI) and populate fields.
     */
    fun applyScannedUri(raw: String) {
        val uri = DigiByteUri.parse(raw) ?: return
        onAddressChanged(uri.address)
        uri.amount?.let { applyPrefillAmountSats(it) }
    }

    /** Put a payment request's amount into the DGB field, as the user would have typed it. */
    fun applyPrefillAmountSats(sats: Long) {
        // A DigiDollar destination sends dollars; a DGB amount means nothing there and would
        // otherwise be mirrored into the dollar field at the current price.
        if (_ddAddressValid.value == true) return
        // Activity recreation re-runs the screen's prefill effect; an amount the user already
        // typed or edited wins over what the QR said.
        if (amountDgb.value.isNotBlank()) return
        onAmountDgbChanged(DgbAmount.format(sats))
    }

    // ── Send flow ─────────────────────────────────────────────────────────

    /**
     * Move to the Confirming state (shows confirmation dialog).
     *
     * This is the one place the amount text is read. It becomes an [ApprovedSend] here; the
     * confirmation is drawn from that object and [send] receives it, so nothing that changes a
     * field afterwards can change what is signed.
     */
    fun requestConfirm() {
        val addr = address.value

        if (addr.isBlank() || _addressValid.value != true) {
            _validationError.value = "Enter a valid DigiByte address"
            return
        }
        val approved = ApprovedSend.dgb(
            addr, amountDgb.value, feeRatePerKb.value, estimatedFeeSat.value,
            dollarsShown = amountFiat.value,
        )
        if (approved == null) {
            _validationError.value = "Enter a valid amount"
            return
        }

        _sendState.value = SendState.Confirming(approved)
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
    private val TAG = "DGB-Send"

    fun send(approved: ApprovedSend.Dgb) {
        // Only the approval that is on screen may be sent, and only once: a second tap, or an
        // approval cancelled while the credential prompt was up, finds nothing to send.
        if ((_sendState.value as? SendState.Confirming)?.approved !== approved) {
            android.util.Log.w(TAG, "send ignored: not the approval being confirmed")
            return
        }
        val addr = approved.address
        val sats = approved.sats
        val feePerKb = approved.feePerKb

        _sendState.value = SendState.Sending

        viewModelScope.launch {
            // Every attempt is logged, not just the ones that succeed. A send that fails leaves
            // nothing behind otherwise — no transaction, no store entry, nothing in the activity
            // list — so when someone reports "it just didn't register anywhere" there is nothing
            // to look at. This is the only durable trace a failed attempt produces.
            android.util.Log.i(TAG, "send attempt: $sats sats to ${addr.take(12)}… at $feePerKb/kB")
            try {
                // Collect spendable UTXOs once
                val utxos = utxoManager.getSpendableUtxos().first()
                val result = transactionBuilder.sendTransaction(addr, sats, feePerKb, utxos)
                _sendState.value = when (result) {
                    is TxResult.Success -> {
                        android.util.Log.i(TAG, "send accepted: ${result.txid}")
                        SendState.Success(result.txid)
                    }
                    is TxResult.Error -> {
                        android.util.Log.w(TAG, "send refused: ${result.message}")
                        SendState.Error(result.message)
                    }
                    is TxResult.Refused -> {
                        // Unreachable for a plain DGB send today; typed refusals are asset-only.
                        android.util.Log.w(TAG, "send refused by policy: ${result.reason}")
                        SendState.Error(result.reason.name)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The screen went away mid-send. Nothing to show — but say so, because the
                // alternative is a send that vanishes from the record entirely.
                android.util.Log.w(TAG, "send cancelled before completing")
                throw e
            } catch (t: Throwable) {
                // Without this the coroutine dies and _sendState stays at Sending forever: the
                // button never re-enables, no outcome is ever shown, and nothing is recorded.
                // A throw must become a visible failure, not a permanent spinner.
                android.util.Log.e(TAG, "send threw", t)
                _sendState.value = SendState.Error(t.message ?: "The transaction could not be sent.")
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
            // Locale-free: String.format under a decimal-comma locale wrote "0,00014100".
            customFeeInput.value = DgbAmount.format(defaultFeeSat)
        }
    }

    // ── DigiDollar send mode ─────────────────────────────────────────────
    // Auto-detected from the destination address: a valid DD… address means a
    // DigiDollar send (SendScreen: effectiveDdMode = ddAddressValid == true).
    // There is no manual DGB/DD toggle — the address type disambiguates.

    /** Live DigiDollar balance in cents — polled on the same 5s cadence as
     *  WalletViewModel's balance poll so the amount validation + available
     *  ceiling stay fresh while this screen is open. */
    private val _ddBalance = MutableStateFlow(0L)
    val ddBalance: StateFlow<Long> = _ddBalance.asStateFlow()

    /** Fill the amount field with MAX sendable DigiDollar — the held balance
     *  clamped to the per-transfer consensus cap so MAX always passes validation. */
    fun setDdAmountToMax() {
        // Through the handler, as if typed: the other field is emptied and the edit is numbered,
        // so nothing worked out for earlier text stays beside it or lands on top of it.
        onAmountFiatChanged(ddCentsToPlainUsd(minOf(_ddBalance.value, DD_MAX_CENTS)))
    }

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
     * The DigiDollar send as it will be confirmed and sent, or null when the form is not sendable.
     * Read once, when the user asks to send; the confirmation and [sendDigiDollar] use only this.
     */
    fun approveDigiDollar(): ApprovedSend.DigiDollar? {
        if (_ddAddressValid.value != true) return null
        return ApprovedSend.digiDollar(address.value, amountFiat.value)
            ?.takeIf { ddAmountValid(it.cents, _ddBalance.value) }
    }

    /**
     * Execute the DigiDollar send the user approved. Mirrors [send]'s dispatcher pattern.
     */
    fun sendDigiDollar(approved: ApprovedSend.DigiDollar, onResult: (txid: String?) -> Unit) {
        if (!NativeBridge.isValidDigiDollarAddress(approved.address)) { onResult(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            // Through the builder, never the bridge: it runs asset detection to completion before
            // the native transfer takes its network fee from the plain-coin set.
            val txid = try {
                transactionBuilder.sendDigiDollar(approved.address, approved.cents)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("SendViewModel", "sendDigiDollar failed", t)
                null
            }
            onResult(txid)
        }
    }

    companion object {
        /** "40.50" USD -> 4050 cents; null unless the text is a whole number of cents. */
        fun parseUsdToCents(s: String): Long? = UsdCents.parse(s)

        /** Consensus max DigiDollar per transfer, in cents ($100,000.00). */
        const val DD_MAX_CENTS = 10_000_000L

        /** Consensus min DigiDollar per transfer, in cents ($1.00). */
        const val DD_MIN_CENTS = 100L

        /** DD send amount valid: within consensus [DD_MIN_CENTS, DD_MAX_CENTS] and <= held balance. */
        fun ddAmountValid(cents: Long, ddBalance: Long): Boolean =
            cents in DD_MIN_CENTS..DD_MAX_CENTS && cents <= ddBalance

        /** cents -> plain "X.XX" for the amount field (5000 -> "50.00"). */
        fun ddCentsToPlainUsd(cents: Long): String = UsdCents.format(cents)

        /** cents -> "$X,XXX.XX" for display (5000 -> "$50.00"). */
        fun formatDdUsd(cents: Long): String =
            NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(cents, 2))
    }
}
