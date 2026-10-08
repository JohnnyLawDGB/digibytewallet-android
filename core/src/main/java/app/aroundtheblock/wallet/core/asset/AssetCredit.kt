package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.asset.send.StackLookup

/**
 * Whether an asset row's units are backed, stored in `utxos.asset_credit`.
 *
 * **The rule: a row counts toward the displayed balance, is offered for sending, and names its
 * asset, only when it is [BACKED] or [VERIFIED].** A transfer's OP_RETURN is written by whoever
 * built the transaction, and on its own it proves nothing about what the inputs carried. DigiAsset
 * Core delivers an instruction's units only when the inputs hold them, and voids every instruction
 * when they do not ([AssetTransferAllocator]). So a quantity read from the instructions alone is a
 * claim, not a holding.
 *
 *  - [BACKED]: the wallet applied DigiAsset Core's transfer rules to inputs whose contents it knows
 *    (its own backed rows, and coins whose creating transaction has no data output), or the row is
 *    an issuance output. Exact, and decided on the device.
 *  - [VERIFIED]: the DigiAsset indexer reported what the output holds; asset and count are its.
 *    Used where the inputs are someone else's and their contents cannot be known locally.
 *  - [MIXED]: the output holds more than one asset. One row cannot represent that, so it counts
 *    for nothing (and stays out of plain-DGB spending like any other targeted output).
 *  - [UNCHECKED]: neither yet. Every row written before this column existed starts here, and so
 *    does a received output until the indexer has answered. Its stored quantity is the claim,
 *    kept only because the hold-out rules read it; nothing displays it.
 *
 * [BACKED], [VERIFIED] and [MIXED] are final: an unspent output's contents never change.
 */
object AssetCredit {
    const val UNCHECKED = "UNCHECKED"
    const val BACKED = "BACKED"
    const val VERIFIED = "VERIFIED"
    const val MIXED = "MIXED"

    /** Does a row in this state count (and name its asset)? */
    fun counts(credit: String?): Boolean = credit == BACKED || credit == VERIFIED

    /** Has this row's content been decided for good? */
    fun isSettled(credit: String?): Boolean = credit == BACKED || credit == VERIFIED || credit == MIXED
}

/** What the wallet can say about one output of an asset transaction. */
sealed interface OutputCredit {
    /**
     * The output holds exactly [units] (null: nothing). [label] is the asset to name an output
     * that holds nothing by, when the transaction moved exactly one asset; it labels activity and
     * never adds to a balance.
     */
    data class Holds(val units: AssetUnits?, val label: String? = null) : OutputCredit

    /** The output holds more than one asset. */
    data object Mixed : OutputCredit

    /** Not decidable from what the wallet knows; the indexer has to be asked. */
    data object Unknown : OutputCredit
}

/** Turning an allocation, or an indexer answer, into an [OutputCredit]. Pure. */
object AssetCreditRules {

    /**
     * What [vout] holds under [allocation]. When the allocation could not be decided, an output
     * still provably holds nothing if no instruction names it and it is not the last output: Core
     * only ever delivers to named outputs and, for leftovers or a voided transfer, the last one.
     */
    fun forOutput(
        allocation: AssetTransferAllocator.Result,
        header: DecodedAssetHeader,
        vout: Int,
        outputCount: Int,
    ): OutputCredit = when (allocation) {
        AssetTransferAllocator.Result.NotAnAssetTransfer -> OutputCredit.Holds(null)
        is AssetTransferAllocator.Result.Allocated -> {
            val held = allocation.outputs[vout].orEmpty()
            val label = allocation.assetsMoved.singleOrNull()
            when {
                held.isEmpty() -> OutputCredit.Holds(null, label)
                held.size == 1 -> OutputCredit.Holds(held[0], held[0].assetId)
                else -> OutputCredit.Mixed
            }
        }
        is AssetTransferAllocator.Result.Indeterminate ->
            if (vout != outputCount - 1 && !namedByAnyInstruction(header, vout)) OutputCredit.Holds(null)
            else OutputCredit.Unknown
    }

    /** Does any instruction (fixed, range or percent; a burn marker included, conservatively)
     *  name [vout]? */
    private fun namedByAnyInstruction(header: DecodedAssetHeader, vout: Int): Boolean =
        header.transferInstructions.any { inst ->
            if (inst.range) vout <= inst.outputIndex else inst.outputIndex == vout
        }

    /**
     * The most units of any asset the transaction's own instructions can deliver to [vout]: the
     * sum of the fixed amounts aimed at it, and of every range instruction that covers it. The
     * indexer's count for a received output is accepted only up to this bound, so a wrong or
     * substituted answer cannot credit more than the transaction itself could deliver there.
     *
     * Null is "no bound": the LAST output, which also receives every leftover (and everything
     * when the instructions are voided); an output a percent instruction names, whose amount
     * depends on the inputs; and a sum that overflows.
     */
    fun maxDeliverable(header: DecodedAssetHeader, vout: Int, outputCount: Int): Long? {
        if (vout == outputCount - 1) return null
        val burnMarker = header.operation == app.aroundtheblock.wallet.core.model.AssetOperation.BURN
        var max = 0L
        for (inst in header.transferInstructions) {
            val named = if (inst.range) vout <= inst.outputIndex else inst.outputIndex == vout
            if (!named || (burnMarker && !inst.range && inst.outputIndex == AssetTransferAllocator.BURN_OUTPUT)) continue
            if (inst.percent) return null
            max = try { Math.addExact(max, inst.amount) } catch (e: ArithmeticException) { return null }
        }
        return max
    }

    /**
     * What the indexer's answer for an unspent output says it holds, or null for no answer yet
     * (the output is not reported unspent — not yet confirmed, or the indexer is behind — or no
     * answer could be had). Several entries of one asset are that asset's total; entries of
     * different assets are [OutputCredit.Mixed].
     */
    fun fromIndexer(lookup: StackLookup): OutputCredit? = when (lookup) {
        is StackLookup.Found -> {
            val entries = lookup.entries.filter { it.count > 0L }
            val ids = entries.map { it.assetId }.distinct()
            when {
                ids.isEmpty() -> OutputCredit.Holds(null)
                ids.size > 1 -> OutputCredit.Mixed
                else -> try {
                    OutputCredit.Holds(
                        AssetUnits(ids[0], entries.fold(0L) { acc, e -> Math.addExact(acc, e.count) }),
                        ids[0],
                    )
                } catch (e: ArithmeticException) {
                    null
                }
            }
        }
        StackLookup.NotUnspent, StackLookup.Unavailable -> null
    }
}
