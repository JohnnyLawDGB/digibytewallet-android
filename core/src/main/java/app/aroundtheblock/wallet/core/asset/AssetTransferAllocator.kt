package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation

/** [count] units of the asset [assetId]: one entry of an output's (or an input's) asset stack. */
data class AssetUnits(val assetId: String, val count: Long)

/**
 * Where DigiAsset Core puts the units of a TRANSFER or BURN, given what every input carried, and
 * the units an ISSUANCE creates ([allocateIssuance]).
 *
 * A port of `DigiByteTransaction::decodeAssetTransfer` (DigiAsset_Core
 * `src/DigiByteTransaction.cpp`, the instruction loop at :298-410 and the leftover at :411-436),
 * together with the two checks in `decodeAssetTX` that decide whether a transfer is an asset
 * transaction at all (:256-284). The rules a wallet has to get exactly right:
 *
 *  - Instructions draw from the inputs **in order**, from the top of each input's stack. Inputs
 *    that carry nothing are not part of that order.
 *  - An instruction that asks for more than the inputs hold, that runs into a different asset,
 *    or that names an output the transaction does not have, voids **every** instruction: what was
 *    already assigned is taken back and nothing more is read.
 *  - Whatever the inputs still hold afterwards goes to the transaction's **last** output, so a
 *    voided transfer delivers everything to the last output and nothing to the others.
 *  - A transfer none of whose inputs carries an asset is not an asset transaction: nothing moves.
 *
 * So no output can receive more of an asset than the inputs carried, and an output only receives
 * an asset some input carried. Summing instruction amounts ([AssetTxQuantity.forOutput]) has
 * neither property; that is the difference this exists for.
 *
 * Fails closed: anything this port cannot decide exactly is [Result.Indeterminate], never a guess.
 * That covers an input whose contents are unknown, a percent instruction (the reference reads it
 * against an input it has not bounds-checked), an instruction that would draw across two stack
 * entries of an asset that is not known to be aggregable (hybrid assets make that invalid, and
 * dispersed ones are compared entry by entry), an asset that may carry transfer rules (a rule
 * failure clears every output, `DigiByteTransaction.cpp:268-276`), and arithmetic overflow.
 *
 * Pure: no native, no network. The caller supplies the input stacks it can vouch for.
 */
object AssetTransferAllocator {

    sealed interface Result {
        /** DigiAsset Core does not treat the transaction as an asset transaction, and no output
         *  receives anything: a transfer none of whose inputs carries an asset, or an issuance
         *  Core rejects (`DigiAsset::processIssuance`). */
        data object NotAnAssetTransfer : Result

        /**
         * What each output holds afterwards; an output absent from [outputs] holds nothing.
         * [instructionsVoided] is true when an instruction was invalid and every unit went to the
         * last output instead.
         */
        data class Allocated(
            val outputs: Map<Int, List<AssetUnits>>,
            val instructionsVoided: Boolean,
        ) : Result {
            /** The distinct assets the inputs carried, in the order they were met. */
            val assetsMoved: List<String>
                get() = outputs.values.flatten().map { it.assetId }.distinct()
        }

        /** The allocation cannot be decided exactly from what was supplied. */
        data class Indeterminate(val reason: String) : Result
    }

    /**
     * @param inputs one entry per transaction input, in input order: the asset stack that input
     *   carried (empty for none), or null when it is not known.
     * @param outputCount the number of outputs of the transaction, OP_RETURN included.
     * @param ruleFree whether an asset is known to carry no transfer rules.
     */
    fun allocate(
        header: DecodedAssetHeader,
        inputs: List<List<AssetUnits>?>,
        outputCount: Int,
        ruleFree: (String) -> Boolean,
    ): Result {
        val burnType = when (header.operation) {
            AssetOperation.TRANSFER -> false
            AssetOperation.BURN -> true
            AssetOperation.ISSUANCE -> return Result.Indeterminate("issuance")
            AssetOperation.UNCLASSIFIABLE -> return Result.Indeterminate("unclassifiable carrier")
        }
        if (outputCount < 1) return Result.Indeterminate("no outputs")
        if (inputs.isEmpty()) return Result.Indeterminate("no inputs")
        if (inputs.any { it == null }) return Result.Indeterminate("an input's contents are unknown")
        val known = inputs.map { stack -> stack!!.filter { it.count > 0L } }
        if (known.any { stack -> stack.any { it.count < 0L } }) return Result.Indeterminate("negative count")

        // `_assetFound` (DigiByteTransaction.cpp:94, :264): no asset on any input, no asset transfer.
        if (known.all { it.isEmpty() }) return Result.NotAnAssetTransfer

        if (header.transferInstructions.any { it.percent }) {
            return Result.Indeterminate("percent instruction")
        }
        val onInputs = known.flatten().map { it.assetId }.toSet()
        if (!onInputs.all(ruleFree)) return Result.Indeterminate("an input asset may carry transfer rules")

        // Versions before 3 ignore every instruction when the first input carries nothing
        // (DigiByteTransaction.cpp:316-318: "a 0 amount causes the input to get wasted").
        val legacyIgnoresInstructions = header.version < 3 && known[0].isEmpty()
        return distribute(header, known, outputCount, burnType, legacyIgnoresInstructions, ::aggregationOf)
    }

    /**
     * Where an ISSUANCE puts the units it creates. Core runs the same transfer code with the new
     * asset as the only input (`decodeAssetTX`, DigiByteTransaction.cpp:236-243): the instructions
     * place units, the remainder goes to the LAST output, and an invalid instruction voids them
     * all, so the remainder is everything. Units the inputs already carried are not moved by an
     * issuance (Core burns them); they are not part of this answer.
     *
     * [payloadExact] is [AssetPayloadCheck.readsExactly] for the transaction's OP_RETURN: without
     * an exact reading the instructions are not known and the answer is [Result.Indeterminate].
     * An issuance Core rejects (`DigiAsset::processIssuance`, DigiAsset.cpp:351-362: a count of
     * zero or above 2^54-1) issues nothing: [Result.NotAnAssetTransfer].
     *
     * @param assetId the id the wallet names the new asset by (derived, or its placeholder).
     */
    fun allocateIssuance(
        header: DecodedAssetHeader,
        assetId: String,
        outputCount: Int,
        payloadExact: Boolean,
    ): Result {
        if (header.operation != AssetOperation.ISSUANCE) return Result.Indeterminate("not an issuance")
        if (!payloadExact) return Result.Indeterminate("payload not read exactly")
        if (outputCount < 1) return Result.Indeterminate("no outputs")
        val total = header.totalQuantity ?: return Result.Indeterminate("no issued amount")
        if (total <= 0L || total > MAX_ISSUANCE) return Result.NotAnAssetTransfer
        if (header.transferInstructions.any { it.percent }) return Result.Indeterminate("percent instruction")
        return distribute(
            header, listOf(listOf(AssetUnits(assetId, total))), outputCount,
            burnType = false, legacyIgnoresInstructions = false,
            aggregation = { header.aggregation },
        )
    }

    /** The largest count an issuance may create (`DigiAsset::processIssuance`). */
    const val MAX_ISSUANCE = (1L shl 54) - 1

    /** The instruction loop and the remainder, shared by transfers and issuances. */
    private fun distribute(
        header: DecodedAssetHeader,
        known: List<List<AssetUnits>>,
        outputCount: Int,
        burnType: Boolean,
        legacyIgnoresInstructions: Boolean,
        aggregation: (String) -> Aggregation?,
    ): Result {
        fun freshStacks(): MutableList<MutableList<AssetUnits>> =
            known.filter { it.isNotEmpty() }.map { it.toMutableList() }.toMutableList()

        var stacks = freshStacks()
        val outputs = LinkedHashMap<Int, MutableList<AssetUnits>>()
        fun add(vout: Int, units: AssetUnits) {
            val held = outputs.getOrPut(vout) { ArrayList() }
            val i = held.indexOfFirst { it.assetId == units.assetId }
            if (i >= 0) {
                held[i] = units.copy(count = Math.addExact(held[i].count, units.count))
            } else {
                held.add(units)
            }
        }

        var voided = false
        var index = 0
        var allowSkip = true
        try {
            if (!legacyIgnoresInstructions) for (inst in header.transferInstructions) {
                val output = inst.outputIndex
                val amount = inst.amount
                if (output < 0 || amount < 0L) return Result.Indeterminate("malformed instruction")
                val total = if (inst.range) Math.multiplyExact(output.toLong() + 1L, amount) else amount

                val valid = run apply@{
                    if (index >= stacks.size || stacks[index].isEmpty()) return@apply false
                    var left = total
                    val removed = stacks[index][0].assetId
                    while (left > 0L) {
                        if (index >= stacks.size || stacks[index].isEmpty()) return@apply false
                        val top = stacks[index][0]
                        if (top.assetId != removed) return@apply false
                        allowSkip = true
                        if (top.count < left) {
                            // Drawing past this entry. With nothing after it the inputs run out:
                            // invalid whatever the asset. Otherwise it is invalid for a hybrid
                            // asset, compared entry by entry for a dispersed one, and unknowable
                            // for an asset whose aggregation we cannot read.
                            val more = stacks[index].size > 1 || index + 1 < stacks.size
                            if (!more) return@apply false
                            when (aggregation(top.assetId)) {
                                Aggregation.AGGREGATABLE -> Unit
                                Aggregation.HYBRID -> return@apply false
                                else -> return Result.Indeterminate("draw across entries of a non-aggregable asset")
                            }
                        }
                        if (top.count <= left) {
                            left -= top.count
                            stacks[index].removeAt(0)
                            if (stacks[index].isEmpty()) {
                                index++
                                allowSkip = false
                            }
                        } else {
                            stacks[index][0] = top.copy(count = top.count - left)
                            left = 0L
                        }
                    }
                    if (total > 0L) {
                        val destroyed = burnType && !inst.range && output == BURN_OUTPUT
                        if (!destroyed) {
                            if (output >= outputCount) return@apply false
                            val start = if (inst.range) 0 else output
                            for (vout in start..output) add(vout, AssetUnits(removed, amount))
                        }
                    }
                    if (inst.skip) {
                        if (allowSkip) index++
                        allowSkip = true
                    }
                    true
                }
                if (!valid) {
                    // Core's catch: take back what was applied, restore the inputs, stop reading.
                    outputs.clear()
                    stacks = freshStacks()
                    voided = true
                    break
                }
            }

            val last = outputCount - 1
            for (stack in stacks) for (units in stack) if (units.count > 0L) add(last, units)
        } catch (e: ArithmeticException) {
            return Result.Indeterminate("overflow")
        }
        return Result.Allocated(outputs.mapValues { it.value.toList() }, voided)
    }

    /** The non-range output index that destroys units in a BURN transaction. */
    const val BURN_OUTPUT = 31

    /**
     * An asset's aggregation, read from its id. DigiAsset ids carry it in their two-character
     * prefix (`DigiAsset.cpp:124`, header options 0x2e37/0x2e6b/0x2e4e locked and
     * 0x20ce/0x2102/0x20e4 unlocked, which encode as "La"/"Lh"/"Ld" and "Ua"/"Uh"/"Ud").
     * Null for anything else, a wallet placeholder included.
     */
    fun aggregationOf(assetId: String): Aggregation? {
        if (assetId.length < 3 || (assetId[0] != 'L' && assetId[0] != 'U')) return null
        return when (assetId[1]) {
            'a' -> Aggregation.AGGREGATABLE
            'h' -> Aggregation.HYBRID
            'd' -> Aggregation.DISPERSED
            else -> null
        }
    }
}
