package io.digibyte.core.recovery

import io.digibyte.core.asset.DigiAssetEncoder
import io.digibyte.core.asset.send.AssetFeeEstimator
import io.digibyte.core.asset.send.DA_MARKER_SATS

/**
 * Builds the transaction that moves ONE DigiAsset out of a wallet the user is leaving.
 *
 * ## Why this exists
 *
 * [SweepPartition] refuses to spend an asset-bearing UTXO as plain DGB, because that destroys the
 * asset instead of moving it, and [AssetFeeReserve] holds DGB back so the asset can pay its own
 * way out. The recovery screen tells the user exactly that — the assets "were left in the old
 * wallet along with enough DGB to move them later". Nothing built the later. This does.
 *
 * ## Why the whole layout goes to the destination
 *
 * A DigiAsset transfer's OP_RETURN assigns units to named outputs, and the protocol credits
 * everything it does NOT name to the transaction's last output. Our unit count comes from
 * [io.digibyte.core.asset.AssetTxQuantity], which deliberately under-counts rather than invent a
 * number it cannot derive — it skips `percent` instructions outright. So the layout is:
 *
 *     vout 0   marker      DA_MARKER_SATS   → destination
 *     vout 1   OP_RETURN   0                → "all counted units to vout 0"
 *     vout 2   change      remainder        → destination      (LAST)
 *
 * Both value outputs belong to the user's new wallet. Under-count the units and the remainder
 * rides to vout 2 and still arrives; there is no arrangement of our arithmetic that burns it.
 * That property is the reason this is safe to ship against an imperfect decoder — not the
 * arithmetic, which is merely correct.
 *
 * ## Why a missing change output is a refusal
 *
 * Drop vout 2 and the last output becomes the OP_RETURN, which is unspendable: residual units
 * would be destroyed. So when the reserved DGB cannot cover the fee AND leave a non-dust change
 * output, this refuses. An asset that stays put can be moved tomorrow; a burned one cannot.
 *
 * Pure function — no JNI, no network, no wallet state. The seed only enters at signing time.
 */
object ForeignAssetTransferPlan {

    /** Below this a change output is unrelayable dust; matches the asset-send path. */
    const val CHANGE_DUST_THRESHOLD = 5_460L

    /** DigiAsset transfer encoding version. v3 is what the wallet's own sends emit. */
    private const val TRANSFER_VERSION = 3

    /**
     * One outpoint to spend, carrying the derivation position its signing key lives at. The
     * (chain, index) pair is read straight from [DerivedAddress] — never reconstructed from a
     * list position, which is how a filtered-out empty slot once signed with the wrong key.
     */
    data class Spend(
        val txid: String,
        val vout: Int,
        val amountSat: Long,
        val scriptPubKeyHex: String,
        val chain: Int,
        val index: Int,
    )

    /** One output. An empty [address] means "use [scriptHex] raw" — that is the OP_RETURN. */
    data class Out(
        val address: String,
        val amountSat: Long,
        val scriptHex: String,
    )

    data class Plan(
        val inputs: List<Spend>,
        val outputs: List<Out>,
        /** Implied: total in minus total out. Stated so the caller can show it and assert on it. */
        val feeSat: Long,
        val assetUnits: Long,
    )

    enum class Reason {
        /** The reserved DGB cannot pay the fee and still leave a spendable change output. */
        INSUFFICIENT_FEE_FUNDS,
        /** No usable quantity for this outpoint. Never guessed — see the class note. */
        UNKNOWN_QUANTITY,
        /** No destination to send to. */
        NO_DESTINATION,
        /** The OP_RETURN payload could not be encoded. */
        ENCODE_FAILED,
    }

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Refused(val reason: Reason, val detail: String) : Result()
    }

    /**
     * @param assetInput  the asset-bearing outpoint. Spent FIRST so the transfer instruction can
     *                    reference input 0.
     * @param assetUnits  units known to sit on [assetInput]. Must be positive.
     * @param feeInputs   plain-DGB outpoints held back by [AssetFeeReserve] to pay for this move.
     * @param destAddress the destination wallet's receive address. Every value output goes here.
     */
    fun build(
        assetInput: Spend,
        assetUnits: Long,
        feeInputs: List<Spend>,
        destAddress: String,
        feePerKb: Long,
    ): Result {
        val dest = destAddress.trim()
        if (dest.isEmpty()) {
            return Result.Refused(Reason.NO_DESTINATION, "no destination address")
        }
        if (assetUnits <= 0L) {
            // Zero is not "an asset worth nothing" — it is "we could not work out what is here".
            // The encoder cannot express it either: a transfer payload needs one instruction.
            return Result.Refused(
                Reason.UNKNOWN_QUANTITY,
                "quantity on ${assetInput.txid}:${assetInput.vout} is $assetUnits",
            )
        }
        if (feeInputs.isEmpty()) {
            return Result.Refused(
                Reason.INSUFFICIENT_FEE_FUNDS,
                "no DGB was held back to pay for this transfer",
            )
        }

        val opReturnScript = try {
            DigiAssetEncoder.encodeTransferScript(
                version = TRANSFER_VERSION,
                instructions = listOf(
                    DigiAssetEncoder.TransferInstruction(
                        skip = false,
                        range = false,
                        percent = false,
                        outputIndex = 0,   // the marker at vout 0
                        amount = assetUnits,
                    ),
                ),
            )
        } catch (e: Exception) {
            return Result.Refused(Reason.ENCODE_FAILED, e.message ?: "encode failed")
        }

        val inputs = listOf(assetInput) + feeInputs
        val totalIn = inputs.sumOf { it.amountSat }

        // Two VALUE outputs — the marker and the change. The OP_RETURN is sized separately.
        val feeSat = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1,
            dgbInputCount = feeInputs.size,
            outputCount = 2,
            opReturnBytes = opReturnScript.size,
            feePerKb = feePerKb,
        )

        val change = totalIn - DA_MARKER_SATS - feeSat
        if (change <= CHANGE_DUST_THRESHOLD) {
            // Squeezing the change out would put the OP_RETURN last and burn any residual units.
            return Result.Refused(
                Reason.INSUFFICIENT_FEE_FUNDS,
                "need ${DA_MARKER_SATS + feeSat + CHANGE_DUST_THRESHOLD + 1} sats, have $totalIn",
            )
        }

        val outputs = listOf(
            Out(address = dest, amountSat = DA_MARKER_SATS, scriptHex = ""),
            Out(address = "", amountSat = 0L, scriptHex = opReturnScript.toHex()),
            // LAST on purpose: unassigned units are credited here, and here is the user's wallet.
            Out(address = dest, amountSat = change, scriptHex = ""),
        )

        return Result.Ok(
            Plan(
                inputs = inputs,
                outputs = outputs,
                feeSat = totalIn - outputs.sumOf { it.amountSat },
                assetUnits = assetUnits,
            )
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
