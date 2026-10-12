package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.reconcile.UtxoEntry

/**
 * Picks the plain DGB that pays a DigiDollar transfer's network fee (0.1 DGB at least: a wallet policy
 * copied from DigiByte Core's builder).
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
 * ## Which of them are taken
 *
 * The usable coins of every profile, largest first, and only until the fee is covered — covered for
 * the transfer at hand, as [DigiDollarTransferPlan] sizes it. Coins of different profiles may pay one
 * fee together: each input carries the derivation it was found under ([Choice.profiles]) and is signed
 * with it, so a fee that only two profiles' coins can cover is paid rather than refused. What is not
 * needed stays where the sweep will find it. When nothing covers the fee, every usable coin is handed
 * over, so the refusal downstream states the true shortfall.
 *
 * The dollars themselves are NOT here: they live at m/86'/20'/0' and are found by
 * [DigiDollarScan]. This is only the fee.
 */
object DigiDollarFeeSelection {

    data class Choice(
        val inputs: List<ForeignAssetTransferPlan.Spend>,
        /** The derivation each input came from, in the order of [inputs] — they are NOT at m/86', and
         *  they need not all be the same one. Signing needs each input's own. */
        val profiles: List<DerivationProfile>,
    ) {
        init {
            require(profiles.size == inputs.size) { "one derivation per fee input" }
        }
    }

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
        val usable = ArrayList<Pair<ForeignAssetTransferPlan.Spend, DerivationProfile>>()
        for (found in findings) {
            val byAddress = found.derivedAddresses.associateBy { it.address }
            val plain = SweepPartition.split(
                utxos = found.utxos,
                carriesAsset = { verdicts[it]?.carriesAsset ?: false },
                classified = { verdicts[it]?.classified ?: false },
            ).sweepable

            plain.mapNotNullTo(usable) { u ->
                if (u.amountSatoshi <= 0L) return@mapNotNullTo null
                if ("${u.txid}:${u.vout}" in excludeOutpoints) return@mapNotNullTo null
                val d = byAddress[u.address] ?: return@mapNotNullTo null
                // No script means nothing to sign against — dropped rather than guessed at.
                val script = u.scriptPubKeyHex ?: return@mapNotNullTo null
                ForeignAssetTransferPlan.Spend(
                    txid = u.txid, vout = u.vout, amountSat = u.amountSatoshi,
                    scriptPubKeyHex = script, chain = d.chain, index = d.index,
                ) to found.profile
            }
        }

        // One outpoint is one input, however many times a lookup listed it — under one profile or
        // two (the first, in scan order, is kept). Largest first; equal amounts keep scan order.
        val coins = usable
            .distinctBy { (spend, _) -> spend.txid to spend.vout }
            .sortedByDescending { (spend, _) -> spend.amountSat }

        val picked = ArrayList<Pair<ForeignAssetTransferPlan.Spend, DerivationProfile>>()
        for (coin in coins) {
            picked += coin
            if (covers(picked.map { it.first })) break
        }
        return Choice(picked.map { it.first }, picked.map { it.second })
    }
}
