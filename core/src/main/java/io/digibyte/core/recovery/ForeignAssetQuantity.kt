package io.digibyte.core.recovery

import io.digibyte.core.asset.AssetTxQuantity
import io.digibyte.core.asset.DigiAssetDecoder

/**
 * How many DigiAsset units sit on a given output of a FOREIGN transaction.
 *
 * The wallet's own detection ([io.digibyte.core.asset.AssetManager.processIncomingAssetTx])
 * answers this for transactions `BRWallet` has registered. A seed the user is migrating away
 * from has none registered, so the outputs arrive here as raw parsed bytes instead. The rule
 * itself is not re-implemented — [AssetTxQuantity] owns it, and both callers go through it, so
 * the two cannot drift apart.
 *
 * ## The under-count is deliberate, and it is only safe in context
 *
 * `inputUnits` is passed as null: resolving the implicit-change remainder needs the parent
 * balances of a foreign wallet, which would mean a provenance walk we cannot do offline.
 * [AssetTxQuantity] likewise skips `percent` instructions. Both resolve to zero.
 *
 * That is acceptable ONLY because [ForeignAssetTransferPlan] sends every output of the transfer
 * to the destination wallet, so units this function misses are still credited to the user by the
 * protocol's last-output rule. Any future caller that sends change anywhere else must not use
 * this number as if it were complete.
 */
object ForeignAssetQuantity {

    private const val OP_RETURN: Byte = 0x6a.toByte()

    /** One parsed output of a raw transaction. */
    data class Output(val vout: Int, val sats: Long, val script: ByteArray) {
        // ByteArray in a data class: identity equals would silently break map/set use.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Output && vout == other.vout && sats == other.sats &&
                script.contentEquals(other.script))

        override fun hashCode(): Int =
            (vout * 31 + sats.hashCode()) * 31 + script.contentHashCode()
    }

    /**
     * Units credited to [vout], or 0 when the transaction carries no readable DigiAsset marker.
     *
     * Never throws: a foreign transaction is attacker-influenced input, and a malformed marker
     * must read as "no units here" rather than abort a recovery.
     */
    fun unitsOn(
        outputs: List<Output>,
        vout: Int,
        decoder: DigiAssetDecoder = DigiAssetDecoder(),
    ): Long {
        if (outputs.isEmpty()) return 0L

        val marker = outputs.firstOrNull { it.script.isNotEmpty() && it.script[0] == OP_RETURN }
            ?: return 0L
        val header = try {
            decoder.decode(marker.script)
        } catch (_: Throwable) {
            null
        } ?: return 0L

        val firstNonOpReturn = outputs.firstOrNull {
            it.script.isEmpty() || it.script[0] != OP_RETURN
        }?.vout

        return try {
            AssetTxQuantity.forOutputTotal(
                header = header,
                vout = vout,
                firstNonOpReturnVout = firstNonOpReturn,
                // Unknowable for a foreign seed without a provenance walk. Null credits the
                // implicit remainder to nobody — an under-count, never an invention.
                inputUnits = null,
                outputCount = outputs.size,
            )
        } catch (_: Throwable) {
            0L
        }
    }
}
