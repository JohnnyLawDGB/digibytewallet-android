package app.aroundtheblock.wallet.core.send

import app.aroundtheblock.wallet.core.model.DgbAmount
import java.math.BigInteger

/**
 * The fee of one built transaction: what the confirmation shows, what the approval carries, and what
 * native code checks again before the transaction is signed.
 *
 * It is read from the transaction the wallet built for the send — its inputs minus its outputs — never
 * computed from a typical size. A transaction that gathers many coins is several times the size of a
 * typical one and pays several times the fee, and the confirmation shows that fee.
 */
data class FeeQuote(
    /** Inputs minus outputs of the built transaction, in satoshis. */
    val feeSats: Long,
    /** Its virtual size as built, signatures estimated, in vbytes: the size its fee was priced on. */
    val vsize: Long,
    /** The custom total the user entered, or null when the fee is the default rate's. */
    val requestedSats: Long? = null,
) {
    /**
     * The transaction pays more than the custom total entered. The builder never pays less than
     * 100 sat/vB, DigiByte's relay floor, rounds a rate's fee up to whole hundreds, and folds change
     * too small to be an output into the fee, so a total can be raised; the confirmation says so
     * beside the fee it shows, instead of showing the lower figure the transaction will not pay.
     */
    val raised: Boolean get() = requestedSats != null && feeSats > requestedSats

    companion object {
        /** 100 sat/vB: DigiByte's minimum relay fee, and the rate a send is built at by default. */
        const val DEFAULT_FEE_PER_KB: Long = 100_000L

        /**
         * The bridge's answer for built bytes, `[fee, vsize]`, as a quote; null when it has none — the
         * bytes did not parse, or an input is not a transaction the wallet holds, so the fee is unknown.
         */
        fun of(native: LongArray?, requestedSats: Long? = null): FeeQuote? {
            if (native == null || native.size != 2) return null
            val (fee, vsize) = native[0] to native[1]
            if (fee < 0 || vsize <= 0) return null
            return FeeQuote(fee, vsize, requestedSats)
        }

        /**
         * The rate, in sat/kB, at which a transaction of [vsize] vbytes pays [totalSats]:
         * `floor(total × 1000 / vsize)`. 0 when there is no size or no total; saturates at
         * [Long.MAX_VALUE] rather than wrapping.
         */
        fun rateForTotal(totalSats: Long, vsize: Long): Long {
            if (vsize <= 0 || totalSats <= 0) return 0L
            val rate = BigInteger.valueOf(totalSats).multiply(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(vsize))
            return if (rate.bitLength() >= 64) Long.MAX_VALUE else rate.toLong()
        }
    }
}

/** How the fee of a send was asked for. */
sealed class FeeRequest {
    /** The default rate, [FeeQuote.DEFAULT_FEE_PER_KB]. */
    data object Default : FeeRequest()

    /** A total the user typed, in satoshis; always positive. */
    data class CustomTotal(val totalSats: Long) : FeeRequest()

    companion object {
        /**
         * The request for the fee field as it stands, or null when nothing may be quoted from it: a
         * custom fee that is not a positive amount is no fee, and review stops there (the field already
         * says "Fee required") instead of approving a fee of zero and building at another rate.
         */
        fun of(custom: Boolean, typedTotal: String): FeeRequest? {
            if (!custom) return Default
            val sats = DgbAmount.toSats(typedTotal) ?: return null
            return if (sats > 0) CustomTotal(sats) else null
        }
    }
}
