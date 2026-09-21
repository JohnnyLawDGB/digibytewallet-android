package io.digibyte.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One approved send: what the confirmation shows, and what the send then receives.
 *
 * The object is made ONCE, from the text in the amount field, at the moment the user asks to
 * review. After that the text plays no part. The confirmation shows [amountText], which is written
 * out from the integer held here, and the send is handed this same object — so the number the user
 * reads, approves and authenticates for is, by construction, the number that is signed. Anything
 * that changes a field afterwards (a price lookup finishing late, a pre-fill running again) changes
 * a field, not the send.
 *
 * Instances come only from [dgb], [digiDollar] and [asset]: there is no way to hold one whose
 * amount did not come through the exact parsers.
 */
sealed class ApprovedSend {
    abstract val address: String

    /**
     * The amount as the confirmation shows it: plain decimal, written from the integer. Never the
     * typed text — "1,234", "1.234" and "1.2340" are one amount and are approved in one spelling.
     */
    abstract val amountText: String

    class Dgb internal constructor(
        override val address: String,
        /** Satoshis to send. */
        val sats: Long,
        /** Fee rate handed to the transaction builder, in satoshis per kilobyte. */
        val feePerKb: Long,
        /** The fee estimate the confirmation shows, in satoshis. */
        val feeEstimateSats: Long,
        /**
         * Whole cents for the confirmation's approximate-dollars row, or null when there is no
         * row. Shown beside the amount and never sent; it is held here so that the row, like the
         * amount, is what stood on the form at review and cannot change while the dialog is up.
         */
        val approxUsdCents: Long? = null,
    ) : ApprovedSend() {
        override val amountText: String get() = DgbAmount.format(sats)

        /** The approximate-dollars row, written from whole cents as [amountText] is; "" for no row. */
        val approxUsdText: String get() = approxUsdCents?.let { UsdCents.format(it) }.orEmpty()
    }

    class DigiDollar internal constructor(
        override val address: String,
        /** Cents to send. */
        val cents: Long,
    ) : ApprovedSend() {
        override val amountText: String get() = UsdCents.format(cents)
    }

    class Asset internal constructor(
        override val address: String,
        /** The asset to move. */
        val assetId: String,
        /** Whole units to send — the integer the transfer is built from. */
        val units: Long,
        /**
         * Decimals of this asset, as its issuance fixes them: the scale [units] were read at and
         * the scale they are shown at. Held here so that the two are one scale by construction.
         */
        val divisibility: Int,
        /** Fee rate handed to the asset send, in satoshis per kilobyte. */
        val feePerKb: Long,
        /** The fee estimate the confirmation shows, in satoshis. */
        val feeEstimateSats: Long,
    ) : ApprovedSend() {
        override val amountText: String get() = AssetQuantity.format(units, divisibility)
    }

    companion object {
        /**
         * An approval for [typedAmount] DGB, or null when the text is not a positive amount.
         *
         * [dollarsShown] is the text of the dollars field at review. It only ever supplies the
         * approximate-dollars row: text that is not whole cents gives an approval with no row.
         */
        fun dgb(
            address: String,
            typedAmount: String,
            feePerKb: Long,
            feeEstimateSats: Long,
            dollarsShown: String = "",
        ): Dgb? {
            val sats = DgbAmount.toSats(typedAmount)?.takeIf { it > 0L } ?: return null
            return Dgb(address, sats, feePerKb, feeEstimateSats, UsdCents.parse(dollarsShown))
        }

        /** An approval for [typedUsd] dollars of DigiDollar, or null when it is not positive whole cents. */
        fun digiDollar(address: String, typedUsd: String): DigiDollar? {
            val cents = UsdCents.parse(typedUsd)?.takeIf { it > 0L } ?: return null
            return DigiDollar(address, cents)
        }

        /**
         * An approval for [typedQuantity] of the asset [assetId], which has [divisibility]
         * decimals, or null when the text is not a positive quantity of such an asset.
         */
        fun asset(
            address: String,
            assetId: String,
            typedQuantity: String,
            divisibility: Int,
            feePerKb: Long,
            feeEstimateSats: Long,
        ): Asset? {
            val units = AssetQuantity.parse(typedQuantity, divisibility) ?: return null
            return Asset(address, assetId, units, divisibility, feePerKb, feeEstimateSats)
        }
    }
}

/**
 * The confirmation on screen, and with it the one approval a send may be made for.
 *
 * A send names its approval. It goes ahead only when that is the very object on screen here — not
 * one that reads the same, not one that was replaced, not one whose confirmation was closed — and
 * only once. Identity, not equality: two approvals that read the same are still two approvals, and
 * only one of them is what the user is looking at.
 *
 * A claimed approval stays on screen while its send is out, and nothing can be opened over it;
 * [close] ends it, whatever the outcome.
 */
class SendConfirmation<T : ApprovedSend> {
    private val onScreen = MutableStateFlow<T?>(null)
    private var claimed = false

    /** The approval the confirmation shows, or null when no confirmation is up. */
    val approval: StateFlow<T?> = onScreen.asStateFlow()

    /** Put [approval] on screen in place of an unclaimed one. False while a claimed one is still up. */
    @Synchronized
    fun open(approval: T): Boolean {
        if (claimed) return false
        onScreen.value = approval
        return true
    }

    /** True exactly once, and only for the object that is on screen. */
    @Synchronized
    fun claim(approval: T): Boolean {
        if (claimed || onScreen.value !== approval) return false
        claimed = true
        return true
    }

    /** The confirmation is gone — dismissed, or its send has ended. Nothing is on screen. */
    @Synchronized
    fun close() {
        onScreen.value = null
        claimed = false
    }
}
