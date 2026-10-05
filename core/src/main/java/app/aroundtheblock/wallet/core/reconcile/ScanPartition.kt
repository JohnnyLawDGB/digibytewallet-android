package app.aroundtheblock.wallet.core.reconcile

import app.aroundtheblock.wallet.core.asset.AssetTxQuantity
import app.aroundtheblock.wallet.core.asset.HeldOutputFacts

/**
 * How "Scan for missing funds" reports what the node returned: each output is put through the
 * wallet's OWN partition — the one its balance is built from — and listed in exactly one class.
 * The headline [ScanClass.SPENDABLE] figure is therefore the wallet's spendable balance by
 * construction, wherever the node agrees with the wallet; [ScanPartition.walletOnly] and
 * [ScanClass.NOT_IN_WALLET] are where they disagree. Only [ScanClass.NOT_IN_WALLET] is missing
 * funds.
 */
enum class ScanClass {
    /** In the wallet's spendable set: what its DGB balance sums. */
    SPENDABLE,
    /** Held out of the spendable set because it carries DigiAsset units. */
    HELD_ASSET,
    /** Held out because what the asset transaction's inputs carried is not known, so the last
     *  output's remainder is not known (held fail-closed). */
    HELD_UNKNOWN,
    /** Held out until now, proven to carry no units; it leaves the hold at the next start. */
    PROVEN_PLAIN,
    /** A DigiDollar token output. */
    DIGIDOLLAR,
    /** A coin-generation output that has not matured. */
    IMMATURE,
    /** In a transaction the wallet holds as pending, or spent by one. */
    PENDING,
    /** The wallet does not credit it: the transaction is not held, not valid, or the output does
     *  not pay one of the wallet's derived addresses. The only missing-funds class. */
    NOT_IN_WALLET,
}

/** The codes [app.aroundtheblock.wallet.core.bridge.NativeBridge.classifyOutpoints] answers with. */
object WalletOutpointCode {
    const val SPENT = 0
    const val SPENDABLE = 1
    const val HELD = 2
    const val DIGIDOLLAR = 3
    const val IMMATURE = 4
    const val NOT_CREDITED = 5
    const val PENDING = 6
    const val NOT_HELD = -1
    const val CONFLICTED = -2
    const val UNREADABLE = -3
}

data class ClassTotal(val count: Int = 0, val sat: Long = 0L) {
    operator fun plus(u: UtxoEntry) = ClassTotal(count + 1, sat + u.amountSatoshi)
}

/** Why an output native holds out of the spendable set is held, for the report. */
enum class HeldKind { ASSET, UNKNOWN_REMAINDER, PROVEN_ZERO }

data class ScanPartition(
    /** Every class, zero when empty. */
    val totals: Map<ScanClass, ClassTotal>,
    /** The outputs in each class, for follow-up work (the remainder proof) and logs. */
    val members: Map<ScanClass, List<UtxoEntry>>,
    /** Outputs in the wallet's spendable set that the node did not report. */
    val walletOnly: ClassTotal,
    /** The wallet's own spendable balance at the time of the scan. */
    val walletSpendableSat: Long,
) {
    fun total(c: ScanClass): ClassTotal = totals[c] ?: ClassTotal()

    /** The headline: the node's outputs that the wallet counts as spendable. */
    val spendableSat: Long get() = total(ScanClass.SPENDABLE).sat

    /** True only when the two views agree output for output: nothing the node reports is missing
     *  from the wallet, nothing the wallet spends is missing from the node, and the sums agree. */
    val matchesWallet: Boolean
        get() = total(ScanClass.NOT_IN_WALLET).count == 0 && walletOnly.count == 0 &&
            spendableSat == walletSpendableSat

    /** The same partition with [moved] outputs taken from [from] and put in [to]. */
    fun move(moved: Collection<UtxoEntry>, from: ScanClass, to: ScanClass): ScanPartition {
        if (moved.isEmpty()) return this
        val keys = moved.map { outpointKey(it) }.toSet()
        val fromList = members[from].orEmpty()
        val leaving = fromList.filter { outpointKey(it) in keys }
        if (leaving.isEmpty()) return this
        val newMembers = members.toMutableMap()
        newMembers[from] = fromList.filter { outpointKey(it) !in keys }
        newMembers[to] = members[to].orEmpty() + leaving
        return copy(members = newMembers, totals = totalsOf(newMembers))
    }
}

/** The address list sent to the node: each address once, first-seen order kept. */
internal fun distinctAddresses(addrs: List<String>): List<String> =
    addrs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun outpointKey(u: UtxoEntry): Pair<String, Int> = u.txid.lowercase() to u.vout

/** The node's rows with each outpoint once (first row kept), before anything is counted. */
internal fun distinctOutpoints(utxos: List<UtxoEntry>): List<UtxoEntry> = utxos.distinctBy(::outpointKey)

/**
 * Why native holds an output out, from what the hold rule reads ([HeldOutputFacts]). An output
 * whose only claim to units is the last-output remainder, with no stored quantity, is
 * [HeldKind.UNKNOWN_REMAINDER] while the replay would still hold it and [HeldKind.PROVEN_ZERO]
 * once it would not (the remainder is known, or proven, to be zero; native lets go at the next
 * start). Anything an instruction names, a stored quantity, or a transaction the asset layer
 * cannot read is [HeldKind.ASSET].
 */
internal fun heldKindOf(facts: HeldOutputFacts?, vout: Int): HeldKind {
    if (facts == null) return HeldKind.ASSET
    if ((facts.rowQuantity ?: 0L) > 0L) return HeldKind.ASSET
    val namedAnyway = AssetTxQuantity.targetsOutput(facts.header, vout, facts.firstNonOpReturnVout, 0L, facts.outputCount)
    val namedIfUnknown = AssetTxQuantity.targetsOutput(facts.header, vout, facts.firstNonOpReturnVout, null, facts.outputCount)
    if (namedAnyway || !namedIfUnknown) return HeldKind.ASSET
    return if (facts.heldByRuleNow == false) HeldKind.PROVEN_ZERO else HeldKind.UNKNOWN_REMAINDER
}

/**
 * Partition the node's outputs by the wallet's own classes.
 *
 * @param utxos the node's rows, already [distinctOutpoints].
 * @param codes one [WalletOutpointCode] per row, in order (from the bridge); null when the wallet
 *              could not answer, in which case there is no partition.
 * @param heldKind why a [WalletOutpointCode.HELD] row is held.
 * @param walletOnly the wallet's spendable outputs that are not among [utxos] (count, sat); null
 *                   when the wallet could not answer, in which case there is no partition.
 * @param walletSpendableSat the wallet's spendable balance.
 */
internal suspend fun partitionScan(
    utxos: List<UtxoEntry>,
    codes: IntArray?,
    heldKind: suspend (UtxoEntry) -> HeldKind,
    walletOnly: ClassTotal?,
    walletSpendableSat: Long,
): ScanPartition? {
    if (codes == null || codes.size != utxos.size || walletOnly == null) return null
    val members = ScanClass.values().associateWith { mutableListOf<UtxoEntry>() }
    for ((i, u) in utxos.withIndex()) {
        val cls = when (codes[i]) {
            WalletOutpointCode.SPENDABLE -> ScanClass.SPENDABLE
            WalletOutpointCode.DIGIDOLLAR -> ScanClass.DIGIDOLLAR
            WalletOutpointCode.IMMATURE -> ScanClass.IMMATURE
            WalletOutpointCode.SPENT, WalletOutpointCode.PENDING -> ScanClass.PENDING
            WalletOutpointCode.HELD -> when (heldKind(u)) {
                HeldKind.ASSET -> ScanClass.HELD_ASSET
                HeldKind.UNKNOWN_REMAINDER -> ScanClass.HELD_UNKNOWN
                HeldKind.PROVEN_ZERO -> ScanClass.PROVEN_PLAIN
            }
            else -> ScanClass.NOT_IN_WALLET
        }
        members.getValue(cls).add(u)
    }
    return ScanPartition(
        totals = totalsOf(members),
        members = members,
        walletOnly = walletOnly,
        walletSpendableSat = walletSpendableSat,
    )
}

private fun totalsOf(members: Map<ScanClass, List<UtxoEntry>>): Map<ScanClass, ClassTotal> =
    ScanClass.values().associateWith { c ->
        members[c].orEmpty().fold(ClassTotal()) { acc, u -> acc + u }
    }

/** [app.aroundtheblock.wallet.core.bridge.NativeBridge.spendableNotListed]'s [count, sat] answer; null when
 *  there is none. */
internal fun walletOnlyOf(answer: LongArray?): ClassTotal? =
    if (answer == null || answer.size < 2 || answer[0] < 0 || answer[1] < 0) null
    else ClassTotal(answer[0].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), answer[1])
