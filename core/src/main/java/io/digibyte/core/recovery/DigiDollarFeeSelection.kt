package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry

/**
 * Picks the plain DGB that pays a DigiDollar transfer's consensus fee.
 *
 * Total by construction: a wallet with no spendable DGB yields an EMPTY selection rather than no
 * selection. That distinction is the whole reason this exists. The recovery flow used to choose
 * inline and bail out — `findings.firstOrNull { it.utxos.isNotEmpty() } ?: return null` — so a
 * wallet already swept of DGB that still held dollars was never mentioned at all. Handing the
 * empty selection to [DigiDollarTransferService] instead gets the honest refusal it already
 * knows how to produce, with the balance reported.
 *
 * ## Which coins may pay
 *
 * Only coins the sweep itself would be allowed to spend. [SweepPartition] decides, from the same
 * verdicts the sweep is handed, so an output classified as carrying a DigiAsset is never a fee
 * coin and its units stay with their output — and neither is one that could not be classified,
 * because "could not tell" is never read as "plain". An outpoint already claimed earlier in the
 * same pass is not offered a second time.
 *
 * One transfer signs its fee inputs with one derivation, so the choice is made per profile: the
 * first whose usable coins cover the fee, taken largest first and only until it is covered —
 * covered for the transfer at hand, as [DigiDollarTransferPlan] sizes it. What is not needed
 * stays where the sweep will find it.
 *
 * The dollars themselves are NOT here: they live at m/86'/20'/0' and are found by
 * [DigiDollarScan]. This is only the fee.
 */
object DigiDollarFeeSelection {

    data class Choice(
        val inputs: List<ForeignAssetTransferPlan.Spend>,
        /** The derivation the inputs came from — they are NOT at m/86'. Signing needs it. */
        val profile: DerivationProfile,
    )

    /**
     * @param findings         every profile the scan found coins on, in scan order.
     * @param verdicts         this round's classification, the same map the sweep receives. An
     *                         output absent from it was never classified and is held back.
     * @param excludeOutpoints `txid:vout` of every outpoint already claimed in this pass.
     * @param covers           whether the coins picked so far pay for the transfer. Asked after
     *                         each coin; selection stops at the first yes. The fee depends on the
     *                         transfer's own size, so the caller hands over that sizing's answer
     *                         ([DigiDollarTransferService.feeInputsSuffice]) rather than a figure.
     */
    fun from(
        findings: List<RecoveryScanService.ProfileResult>,
        verdicts: Map<UtxoEntry, ForeignUtxoAssetClassifier.Verdict>,
        excludeOutpoints: Set<String>,
        covers: (List<ForeignAssetTransferPlan.Spend>) -> Boolean,
    ): Choice {
        // Kept for when no profile covers the fee: the best-funded one, in full, so the refusal
        // downstream states the smallest true shortfall.
        var bestShort: Choice? = null
        var bestShortSat = 0L

        for (found in findings) {
            val byAddress = found.derivedAddresses.associateBy { it.address }
            val plain = SweepPartition.split(
                utxos = found.utxos,
                carriesAsset = { verdicts[it]?.carriesAsset ?: false },
                classified = { verdicts[it]?.classified ?: false },
            ).sweepable

            val usable = plain.mapNotNull { u ->
                if (u.amountSatoshi <= 0L) return@mapNotNull null
                if ("${u.txid}:${u.vout}" in excludeOutpoints) return@mapNotNull null
                val d = byAddress[u.address] ?: return@mapNotNull null
                // No script means nothing to sign against — dropped rather than guessed at.
                val script = u.scriptPubKeyHex ?: return@mapNotNull null
                ForeignAssetTransferPlan.Spend(
                    txid = u.txid, vout = u.vout, amountSat = u.amountSatoshi,
                    scriptPubKeyHex = script, chain = d.chain, index = d.index,
                )
            }
                // One outpoint is one input, however many times a lookup listed it.
                .distinctBy { it.txid to it.vout }
                .sortedByDescending { it.amountSat }
            if (usable.isEmpty()) continue

            val picked = ArrayList<ForeignAssetTransferPlan.Spend>()
            // Only compared between profiles, so it is held at the top of the range rather than
            // carried past it. Every amount here is above zero.
            var have = 0L
            for (coin in usable) {
                picked += coin
                have = if (coin.amountSat > Long.MAX_VALUE - have) Long.MAX_VALUE
                       else have + coin.amountSat
                if (covers(picked)) return Choice(picked.toList(), found.profile)
            }

            if (bestShort == null || have > bestShortSat) {
                bestShort = Choice(picked, found.profile)
                bestShortSat = have
            }
        }

        return bestShort ?: Choice(emptyList(), DerivationProfile.BUILT_INS.first())
    }
}
