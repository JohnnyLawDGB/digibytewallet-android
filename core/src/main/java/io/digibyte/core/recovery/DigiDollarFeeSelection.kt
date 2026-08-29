package io.digibyte.core.recovery

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
 * The dollars themselves are NOT here: they live at m/86'/20'/0' and are found by
 * [DigiDollarScan]. This is only the fee.
 */
object DigiDollarFeeSelection {

    data class Choice(
        val inputs: List<ForeignAssetTransferPlan.Spend>,
        /** The derivation the inputs came from — they are NOT at m/86'. Signing needs it. */
        val profile: DerivationProfile,
    )

    fun from(findings: List<RecoveryScanService.ProfileResult>): Choice {
        val funded = findings.firstOrNull { it.utxos.isNotEmpty() }
            ?: return Choice(emptyList(), DerivationProfile.BUILT_INS.first())

        val byAddress = funded.derivedAddresses.associateBy { it.address }
        val inputs = funded.utxos.mapNotNull { u ->
            val d = byAddress[u.address] ?: return@mapNotNull null
            // No script means nothing to sign against — dropped rather than guessed at.
            val script = u.scriptPubKeyHex ?: return@mapNotNull null
            ForeignAssetTransferPlan.Spend(
                txid = u.txid, vout = u.vout, amountSat = u.amountSatoshi,
                scriptPubKeyHex = script, chain = d.chain, index = d.index,
            )
        }
        return Choice(inputs, funded.profile)
    }
}
