package app.aroundtheblock.wallet.core.asset.send

/**
 * A model of how DigiAsset Core assigns assets in a version-3 transfer, for tests only. Ported
 * from DigiAsset_Core `DigiByteTransaction::decodeAssetTransfer` (src/DigiByteTransaction.cpp,
 * the instruction loop, the catch that sends everything to the leftover, and the leftover to the
 * last output) and `DigiAsset::checkRulesPass` (src/DigiAsset.cpp, the per-address change and the
 * early return when no address gains the asset). Range and percent instructions and hybrid
 * assets are not modelled; the wallet emits none of them.
 *
 * Rules are reduced to one fact: an asset in [ruleBearing] fails its rule check whenever the check
 * runs; [Result.ruleCheckFailed] records that, and it voids the transfer's asset assignments
 * (DigiByteTransaction::decodeAssetTX).
 */
internal object DigiAssetTransferModel {

    data class Input(val address: String, val stack: List<Pair<String, Long>>)
    data class Instruction(val skip: Boolean, val output: Int, val amount: Long)

    data class Result(
        /** Per output, the assets it ends up with, in order. */
        val outputs: List<List<Pair<String, Long>>>,
        /** True when a rule check ran and failed (the transfer's asset assignments are void). */
        val ruleCheckFailed: Boolean,
    )

    fun apply(
        inputs: List<Input>,
        outputAddresses: List<String>,
        instructions: List<Instruction>,
        ruleBearing: Set<String>,
    ): Result {
        fun freshStacks() = inputs.filter { it.stack.isNotEmpty() }
            .map { inp -> inp.stack.map { it.first to it.second }.toMutableList() }.toMutableList()

        var stacks = freshStacks()
        val outs = List(outputAddresses.size) { mutableListOf<Pair<String, Long>>() }.toMutableList()
        fun addTo(o: Int, id: String, n: Long) {
            val i = outs[o].indexOfFirst { it.first == id }
            if (i >= 0) outs[o][i] = id to (outs[o][i].second + n) else outs[o] += id to n
        }

        var index = 0
        var allowSkip = true
        for (ins in instructions) {
            try {
                if (index >= stacks.size || stacks[index].isEmpty()) error("input has no assets")
                var left = ins.amount
                val removed = stacks[index][0].first
                while (left > 0) {
                    if (index >= stacks.size || stacks[index].isEmpty()) error("input has no assets")
                    val (id, count) = stacks[index][0]
                    if (id != removed) error("different asset than expected")
                    allowSkip = true
                    if (count <= left) {
                        left -= count
                        stacks[index].removeAt(0)
                        if (stacks[index].isEmpty()) {
                            index++
                            allowSkip = false
                        }
                    } else {
                        stacks[index][0] = id to (count - left)
                        left = 0
                    }
                }
                if (ins.amount > 0) {
                    if (ins.output >= outs.size) error("no such output")
                    addTo(ins.output, removed, ins.amount)
                }
                if (ins.skip) {
                    if (allowSkip) index++
                    allowSkip = true
                }
            } catch (e: IllegalStateException) {
                // Core: clear what was applied, restore the inputs, stop reading instructions.
                outs.forEach { it.clear() }
                stacks = freshStacks()
                break
            }
        }

        // Every unit left on any input goes to the last output.
        val last = outs.lastIndex
        for (stack in stacks) for ((id, n) in stack) if (n > 0) addTo(last, id, n)

        // Rule check, per rule-bearing asset on the inputs: skipped when no address gains it.
        val ruleBearingOnInputs = inputs.flatMap { it.stack }.map { it.first }.filter { it in ruleBearing }.toSet()
        for (asset in ruleBearingOnInputs) {
            val change = HashMap<String, Long>()
            for (inp in inputs) for ((id, n) in inp.stack) if (id == asset) change.merge(inp.address, -n, Long::plus)
            for ((o, held) in outs.withIndex()) for ((id, n) in held) if (id == asset) change.merge(outputAddresses[o], n, Long::plus)
            if (change.values.any { it > 0 }) {
                return Result(outputs = List(outs.size) { emptyList() }, ruleCheckFailed = true)
            }
        }
        // Assets on a data output are burned.
        val final = outs.mapIndexed { o, held -> if (outputAddresses[o].isEmpty()) emptyList() else held.toList() }
        return Result(outputs = final, ruleCheckFailed = false)
    }
}
