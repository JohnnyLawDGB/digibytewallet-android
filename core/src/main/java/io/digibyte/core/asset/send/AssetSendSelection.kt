package io.digibyte.core.asset.send

import io.digibyte.core.db.entity.UtxoEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Chooses what an asset send spends, and confirms it before anything is built.
 *
 * Two rules, both from how the protocol moves assets:
 *  - every asset input comes from one address, and the last output returns to it
 *    ([AssetTransferPlanner.plan]), so any unit the instructions do not assign stays there;
 *  - every asset input is confirmed by the indexer to hold exactly the asset being sent at the
 *    recorded quantity ([AssetInputCheck]). An input that holds anything else is set aside and the
 *    send is planned again without it; an input nobody can vouch for stops the send.
 *
 * No native calls: the creating-transaction check is passed in, so this is testable on the JVM.
 */
class AssetSendSelection(
    private val stackSource: AssetStackSource?,
    /** True when the transaction that created [txid] has a data (OP_RETURN) output, false when it
     *  has none, null when it cannot be read. */
    private val parentHasDataOutput: (txid: String) -> Boolean?,
) {

    sealed interface Outcome {
        data class Planned(val plan: AssetTransferPlan) : Outcome
        /** The asset's coins could not be confirmed right now: nothing is sent on a guess. */
        data object Unverified : Outcome
        /** No coin left that is confirmed to hold only this asset covers the amount. */
        data object Unconfirmed : Outcome
        /** The amount needs coins at more than one address; [largestSingleSource] is the most
         *  one send can move. */
        data class SpansAddresses(val largestSingleSource: Long) : Outcome
        /** The plan could not be made for an ordinary reason (not enough asset or DGB, …). */
        data class NotPlanned(val message: String) : Outcome
    }

    /** Outpoints confirmed (key includes asset and quantity) and outpoints that failed, kept for
     *  the life of the process: an unspent output's stack does not change. */
    private val verified = ConcurrentHashMap.newKeySet<String>()
    private val setAside = ConcurrentHashMap.newKeySet<String>()

    suspend fun choose(
        assetId: String,
        assetUtxos: List<UtxoEntity>,
        dgbUtxos: List<UtxoEntity>,
        quantity: Long,
        feePerKb: Long,
    ): Outcome {
        val source = stackSource ?: return Outcome.Unverified
        val total = assetUtxos.sumOf { it.assetQuantity }
        if (total < quantity) return Outcome.NotPlanned("Not enough asset: need $quantity, have $total")

        // Each round either plans and confirms, stops, or sets at least one more coin aside; the
        // coin count bounds the rounds.
        for (round in 0..assetUtxos.size) {
            val candidates = assetUtxos.filter { outpoint(it) !in setAside }
            val groups = AssetTransferPlanner.sourceGroups(candidates, quantity)
            if (groups.isEmpty()) {
                return if (candidates.sumOf { it.assetQuantity } >= quantity) {
                    Outcome.SpansAddresses(AssetTransferPlanner.largestSingleSource(candidates))
                } else {
                    Outcome.Unconfirmed
                }
            }
            val group = groups.first()
            val sourceScript = group.first().scriptPubKey
            val feeCoins = dgbUtxos.filter {
                AssetTransferPlanner.feeCoinEligible(it, sourceScript, parentHasDataOutput(it.txid))
            }
            val plan = when (val r = AssetTransferPlanner.plan(group, feeCoins, quantity, feePerKb)) {
                is AssetTransferPlanner.Result.Refused -> return Outcome.NotPlanned(
                    if (r.message.startsWith("Not enough DGB") && feeCoins.size < dgbUtxos.size) {
                        r.message + " (an asset send's fee is paid only from coins received in ordinary payments or held at the asset's own address)"
                    } else r.message,
                )
                is AssetTransferPlanner.Result.Ready -> r.plan
            }
            var allConfirmed = true
            for (input in plan.assetInputs) {
                val key = confirmedKey(input, assetId)
                if (key in verified) continue
                when (AssetInputCheck.judge(source.stackOf(input.txid, input.vout), assetId, input.assetQuantity)) {
                    AssetInputCheck.Verdict.VERIFIED -> verified += key
                    AssetInputCheck.Verdict.MISMATCH -> {
                        setAside += outpoint(input)
                        allConfirmed = false
                    }
                    AssetInputCheck.Verdict.UNAVAILABLE -> return Outcome.Unverified
                }
            }
            if (allConfirmed) return Outcome.Planned(plan)
        }
        return Outcome.Unconfirmed
    }

    /** Outpoints this process has set aside, for logging. */
    fun setAsideOutpoints(): Set<String> = setAside.toSet()

    private fun outpoint(u: UtxoEntity) = "${u.txid.lowercase()}:${u.vout}"
    private fun confirmedKey(u: UtxoEntity, assetId: String) = "${outpoint(u)}:$assetId:${u.assetQuantity}"
}
