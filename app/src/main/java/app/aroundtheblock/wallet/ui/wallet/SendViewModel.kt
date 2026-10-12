package app.aroundtheblock.wallet.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.Delivery
import app.aroundtheblock.wallet.core.DigiDollarPrepareResult
import app.aroundtheblock.wallet.core.PrepareResult
import app.aroundtheblock.wallet.core.PreparedDigiDollar
import app.aroundtheblock.wallet.core.PreparedSend
import app.aroundtheblock.wallet.core.PriceProvider
import app.aroundtheblock.wallet.core.SpendPreflight
import app.aroundtheblock.wallet.core.TransactionBuilder
import app.aroundtheblock.wallet.core.TxResult
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.model.ApprovedSend
import app.aroundtheblock.wallet.core.model.DigiByteUri
import app.aroundtheblock.wallet.core.model.DgbAmount
import app.aroundtheblock.wallet.core.model.SendConfirmation
import app.aroundtheblock.wallet.core.model.UsdCents
import app.aroundtheblock.wallet.core.send.DigiDollarSendState
import app.aroundtheblock.wallet.core.send.FeeQuote
import app.aroundtheblock.wallet.core.send.FeeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

sealed class SendState {
    data object Idle : SendState()
    /**
     * The confirmation is on screen, showing [approved] — the only thing [SendViewModel.send] accepts.
     * [approved] was made from [prepared], the send review built and quoted, and its bytes are what
     * the send signs: the fee shown is the fee signed.
     */
    data class Confirming(val approved: ApprovedSend.Dgb, val prepared: PreparedSend) : SendState()
    /** Signing and broadcasting, then waiting for a peer to take it: "Broadcasting…". */
    data object Sending : SendState()
    /** A peer has taken the transaction: "Sent". */
    data class Success(val txid: String) : SendState()
    /** Broadcast, and no peer has taken it yet: the wallet holds it as pending. It is not "Sent". */
    data class NotYetRelayed(val txid: String) : SendState()
    data class Error(val message: String) : SendState()
}

/** What the custom-fee field says of itself. There is no "below the relay minimum" state: the builder
 *  raises a low custom fee to the floor, and the confirmation shows the fee raised. */
sealed class FeeWarning {
    data object None : FeeWarning()
    /** A custom fee that is not a positive amount: review quotes nothing until it is one. */
    data object ZeroFee : FeeWarning()
}

@HiltViewModel
class SendViewModel @Inject constructor(
    private val transactionBuilder: TransactionBuilder,
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

    /** Custom fee input: a TOTAL fee in DGB (text field value). */
    val customFeeInput = MutableStateFlow("")

    /**
     * The custom-fee field's own state. No fee figure is worked out here: the fee of a send is the
     * fee of the transaction review builds for it, which the confirmation shows and the send signs.
     */
    val feeWarning: StateFlow<FeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (FeeRequest.of(custom, input) == null) FeeWarning.ZeroFee else FeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeeWarning.None)

    /** Review is building and quoting the send. One review at a time. */
    private val _quoting = MutableStateFlow(false)
    val quoting: StateFlow<Boolean> = _quoting.asStateFlow()
    private var quoteJob: Job? = null

    /**
     * The dust line, in satoshis, the amount fell below at the last review — or null. A payment below
     * the smallest output the network relays to its address type is refused before anything is built.
     */
    private val _belowDustSats = MutableStateFlow<Long?>(null)
    val belowDustSats: StateFlow<Long?> = _belowDustSats.asStateFlow()

    /** The fee of the last send review quoted on this screen: where a custom fee starts from. */
    private var lastQuotedFeeSats: Long? = null

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
        _belowDustSats.value = null
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
        _belowDustSats.value = null
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
        _belowDustSats.value = null
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
     * Review. This is the one place the amount text is read. It becomes an [ApprovedSend] here, and
     * review builds and quotes the send for it before anything is shown: the confirmation is drawn
     * from the approval, which carries the fee of the transaction built ([ApprovedSend.Dgb.withQuote]),
     * and [send] receives that approval and signs those bytes. Nothing that changes a field afterwards
     * can change what is signed, and the fee shown is the fee signed.
     *
     * A custom fee that is not a positive amount quotes nothing (the field already says "Fee
     * required"). A payment below the dust line of its address type is refused here
     * ([belowDustSats]), and a send the balance cannot cover fails here, before any prompt.
     */
    fun requestConfirm() {
        val addr = address.value

        if (addr.isBlank() || _addressValid.value != true) {
            _validationError.value = "Enter a valid DigiByte address"
            return
        }
        val fee = FeeRequest.of(isCustomFee.value, customFeeInput.value) ?: return
        val typed = ApprovedSend.dgb(
            addr, amountDgb.value, FeeQuote.DEFAULT_FEE_PER_KB, 0L,
            dollarsShown = amountFiat.value,
        )
        if (typed == null) {
            _validationError.value = "Enter a valid amount"
            return
        }

        _belowDustSats.value = null
        quoteJob?.cancel()
        _quoting.value = true
        quoteJob = viewModelScope.launch {
            val result = try {
                transactionBuilder.prepareTransaction(typed.address, typed.sats, fee)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "review threw", t)
                PrepareResult.Error(t.message ?: SpendPreflight.NOT_SENT)
            } finally {
                _quoting.value = false
            }
            when (result) {
                is PrepareResult.Ready -> {
                    val prepared = result.send
                    lastQuotedFeeSats = prepared.quote.feeSats
                    android.util.Log.i(TAG, "review: ${prepared.amountSats} sats, fee ${prepared.quote.feeSats} sats " +
                        "over ${prepared.quote.vsize} vB at ${prepared.feePerKb}/kB" + if (prepared.quote.raised) " (raised)" else "")
                    _sendState.value = SendState.Confirming(typed.withQuote(prepared.feePerKb, prepared.quote.feeSats), prepared)
                }
                is PrepareResult.BelowDust -> _belowDustSats.value = result.thresholdSats
                is PrepareResult.Error -> _sendState.value = SendState.Error(result.message)
            }
        }
    }

    /** The confirmation was dismissed, or the credential prompt was: the prepared send goes with it,
     *  and the next review builds afresh. Nothing native outlived the prompt. */
    fun cancelConfirm() {
        quoteJob?.cancel()
        if (_sendState.value is SendState.Confirming) {
            _sendState.value = SendState.Idle
        }
    }

    /**
     * Execute the send after biometric/pin auth succeeds: sign the prepared bytes and broadcast them.
     */
    private val TAG = "DGB-Send"

    fun send(approved: ApprovedSend.Dgb) {
        // Only the approval that is on screen may be sent, and only once: a second tap, or an
        // approval cancelled while the credential prompt was up, finds nothing to send.
        val confirming = _sendState.value as? SendState.Confirming
        if (confirming?.approved !== approved) {
            android.util.Log.w(TAG, "send ignored: not the approval being confirmed")
            return
        }
        // The approval was made from this prepared send. Were they ever to differ, the confirmation
        // would show one send while another is signed: nothing is sent.
        val prepared = confirming.prepared
        if (prepared.toAddress != approved.address || prepared.amountSats != approved.sats ||
            prepared.feePerKb != approved.feePerKb || prepared.quote.feeSats != approved.feeEstimateSats
        ) {
            android.util.Log.e(TAG, "send refused: the prepared send is not the approval on screen")
            _sendState.value = SendState.Error(SpendPreflight.NOT_SENT)
            return
        }

        _sendState.value = SendState.Sending

        viewModelScope.launch {
            // Every attempt is logged, not just the ones that succeed. A send that fails leaves
            // nothing behind otherwise — no transaction, no store entry, nothing in the activity
            // list — so when someone reports "it just didn't register anywhere" there is nothing
            // to look at. This is the only durable trace a failed attempt produces.
            android.util.Log.i(TAG, "send attempt: ${approved.sats} sats to ${approved.address.take(12)}… " +
                "at ${approved.feePerKb}/kB, fee ${approved.feeEstimateSats} sats")
            try {
                val result = transactionBuilder.sendPrepared(prepared)
                _sendState.value = when (result) {
                    is TxResult.Success -> if (result.delivery == Delivery.RELAYED) {
                        android.util.Log.i(TAG, "send relayed: ${result.txid}")
                        SendState.Success(result.txid)
                    } else {
                        // Not "Sent": no peer has taken it yet. The wallet holds it and keeps publishing it.
                        android.util.Log.w(TAG, "send broadcast, no peer has taken it yet: ${result.txid}")
                        SendState.NotYetRelayed(result.txid)
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
        _belowDustSats.value = null
    }

    /** Toggle between default and custom fee mode. A custom fee starts from the fee of the last send
     *  review quoted here, when there is one. */
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            // Locale-free (DgbAmount, not String.format, which writes a comma under some locales).
            lastQuotedFeeSats?.let { customFeeInput.value = DgbAmount.format(it) }
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

    /** The DigiDollar confirmation on screen: the one approval [sendDigiDollar] accepts, and only once. */
    private val ddConfirmation = SendConfirmation<ApprovedSend.DigiDollar>()

    /** The approval on the confirmation and the transfer review built for it, made together. */
    @Volatile private var ddReviewed: Pair<ApprovedSend.DigiDollar, PreparedDigiDollar>? = null

    /** A DigiDollar review is building the transfer: one review at a time. */
    private val _ddReviewing = MutableStateFlow(false)
    val ddReviewing: StateFlow<Boolean> = _ddReviewing.asStateFlow()

    /** A DigiDollar send is out: from its claim until it ends, whatever its outcome. */
    private val _ddSending = MutableStateFlow(false)
    val ddSending: StateFlow<Boolean> = _ddSending.asStateFlow()

    /**
     * The send after the credential prompt: the builder's, which builds the approved transfer again, has
     * native code sign it only at the approved fee, records and persists it before anything is reported,
     * and reports it sent only when a peer took it ([DigiDollarSendState]). A test replaces it.
     */
    internal var sendDd: suspend (PreparedDigiDollar) -> DigiDollarSendState = { transactionBuilder.sendDigiDollar(it) }

    /**
     * Review the DigiDollar send on the form: build the transfer and read its fee, before the confirmation
     * (the builder runs detection first; nothing is signed). The approval returned carries that fee — the
     * fee of the transfer that will be signed — and is put on the confirmation; the confirmation and
     * [sendDigiDollar] use only it. Null when the form is not sendable, while another DigiDollar review or
     * send is out, or when the transfer cannot be built — then [onNotBuilt] is told.
     */
    suspend fun approveDigiDollar(onNotBuilt: () -> Unit = {}): ApprovedSend.DigiDollar? {
        if (_ddAddressValid.value != true) return null
        val typed = ApprovedSend.digiDollar(address.value, amountFiat.value)
            ?.takeIf { ddAmountValid(it.cents, _ddBalance.value) }
            ?: return null
        if (_ddSending.value || !_ddReviewing.compareAndSet(false, true)) return null
        try {
            val prepared = when (val result = transactionBuilder.prepareDigiDollar(typed.address, typed.cents)) {
                is DigiDollarPrepareResult.Ready -> result.send
                is DigiDollarPrepareResult.Error -> {
                    android.util.Log.w(TAG, "DigiDollar review built nothing: ${result.message}")
                    onNotBuilt()
                    return null
                }
            }
            val approved = typed   // STUB (red run): the approval carries no fee
            if (!ddConfirmation.open(approved)) return null
            ddReviewed = approved to prepared
            android.util.Log.i(TAG, "DigiDollar review: ${prepared.cents} cents, fee ${prepared.feeSats} sats, " +
                "${prepared.inputCount} inputs, change ${prepared.ddChangeCents} cents + ${prepared.dgbChangeSats} sats")
            return approved
        } finally {
            _ddReviewing.value = false
        }
    }

    /** The DigiDollar confirmation was dismissed, or its credential prompt refused: nothing is on screen to
     *  send, and the transfer review built goes with it. Nothing native outlived the prompt. */
    fun cancelDigiDollar() {
        ddReviewed = null
        ddConfirmation.close()
    }

    /**
     * Execute the DigiDollar send the user approved — with the guard [send] has: only the approval
     * on screen, and only once. A second submit while this one is out, or one whose confirmation was
     * closed, finds nothing to send; each send would otherwise be a transfer and a fee of its own.
     *
     * The send is the transfer review built for this approval, at the fee the confirmation showed. It is
     * recorded for retry and persisted before anything is said about it, and it is "Sent" only when a peer
     * has taken it, as for a DGB send; [onNotSent] when nothing went out.
     */
    fun sendDigiDollar(approved: ApprovedSend.DigiDollar, onNotSent: () -> Unit) {
        if (ddConfirmation.claim(approved)) {
            _ddSending.value = true
            viewModelScope.launch {
                val outcome = try {
                    // The transfer review built for this very approval. Were they ever to differ, the
                    // confirmation would show one send while another is signed: nothing is sent.
                    val prepared = ddReviewed?.takeIf { it.first === approved }?.second
                        ?.takeIf { it.address == approved.address && it.cents == approved.cents }
                    if (prepared == null) {
                        android.util.Log.e(TAG, "DigiDollar send refused: no transfer was reviewed for the approval on screen")
                        DigiDollarSendState.NotSent(SpendPreflight.NOT_SENT)
                    } else {
                        android.util.Log.i(TAG, "DigiDollar send attempt: ${prepared.cents} cents to " +
                            "${prepared.address.take(12)}…, fee ${prepared.feeSats} sats")
                        sendDd(prepared)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "sendDigiDollar failed", t)
                    DigiDollarSendState.NotSent(t.message ?: SpendPreflight.NOT_SENT)
                } finally {
                    ddReviewed = null
                    ddConfirmation.close()
                    _ddSending.value = false
                }
                when (outcome) {
                    is DigiDollarSendState.Sent -> _sendState.value = SendState.Success(outcome.txid)
                    is DigiDollarSendState.NotYetRelayed -> _sendState.value = SendState.NotYetRelayed(outcome.txid)
                    else -> onNotSent()
                }
            }
            return
        }
        android.util.Log.w(TAG, "DigiDollar send ignored: not the approval on screen, or already sent")
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
