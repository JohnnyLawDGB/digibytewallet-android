package io.digibyte.core.recovery

/**
 * Moves DigiAssets out of a wallet first, then sweeps the plain DGB that is left.
 *
 * ## Why this order
 *
 * Sweeping first forces something to hold DGB back so the assets can pay their own transfer fee,
 * and whatever does that has to guess the fee before the transfer exists. `AssetFeeReserve` did
 * exactly that, with a per-asset constant that shipped at 40,000 sats against a real cost of
 * 54,900–70,100 while its own comment called it "deliberately an over-estimate".
 *
 * Moving first removes the guess. The plans are built before anything is swept, so the outpoints
 * they spend are a fact, and the sweep is DEFINED as the complement of them. There is nothing
 * left to estimate, so there is nothing left to estimate wrongly.
 *
 * It also fixes the failure mode. A move that fails after a sweep has broadcast leaves only
 * whatever was reserved — and if that was short, the asset cannot move at all without sending
 * funds back into a wallet the user is leaving. A move that fails BEFORE the sweep leaves the
 * wallet untouched, and the retry has everything.
 *
 * ## The parent/descendant guarantee
 *
 * Because the sweep's inputs are the complement of the moves', neither transaction spends an
 * output of the other and neither spends the same outpoint. So both have zero unconfirmed
 * ancestors and there is no chain to hit mempool limits on. That was true in the reserve design
 * too, but only because the reserve happened to be disjoint; here it is structural.
 */
object RecoverySequence {

    /**
     * What one asset's move did, in terms of outpoints.
     *
     * @param outpoint     the asset-bearing outpoint this move was for.
     * @param spentInputs  every outpoint the plan spends — the asset and its fee inputs. Empty
     *                     when no plan was built (an unreadable quantity, no destination).
     * @param broadcast    true once the signed transaction reached relay.
     */
    data class MoveRecord(
        val outpoint: String,
        val spentInputs: List<String>,
        val broadcast: Boolean,
    )

    /**
     * Outpoints the sweep must not touch.
     *
     * Two reasons an outpoint lands here, and they are deliberately treated the same:
     *
     *  - **The move spent it.** Sweeping it would double-spend an outpoint a broadcast — and
     *    still unconfirmed — transaction already claimed. Both transactions would be individually
     *    valid and the network would decide which one survives.
     *
     *  - **The move failed and its plan named it.** Nothing was spent, so the DGB is still there,
     *    and it has to stay there or the retry has nothing to pay with. This is what the reserve
     *    used to approximate; here it is the exact input list a real plan asked for.
     */
    /**
     * Which asset-bearing outpoints the result screen may call "left behind".
     *
     * Under the old order every asset the sweep excluded was left behind, so the two were the
     * same list. Since assets now move FIRST, they are not: an outpoint excluded from the sweep
     * has usually just been moved. Reading one as the other printed "2 left behind — DigiAssets"
     * two lines above "2 of 2 assets moved" on a mainnet run where both had arrived.
     *
     * An asset with no move record was never attempted and is still in the old wallet, so it
     * counts as left behind. That is the fail-closed direction: over-reporting sends someone to
     * check a wallet that does have something in it; under-reporting tells them it is empty when
     * it is not.
     */
    fun assetsLeftBehind(
        assetBearing: List<String>,
        moves: List<ForeignAssetTransferService.Move>,
    ): List<String> {
        val arrived = moves.filter { it.moved }.mapTo(mutableSetOf()) { it.outpoint }
        return assetBearing.filterNot { it in arrived }
    }

    fun sweepExclusions(moved: List<MoveRecord>): Set<String> =
        moved.flatMapTo(mutableSetOf()) { record ->
            // The asset outpoint itself is always excluded: spent if the move went through,
            // and never sweepable as plain DGB if it did not — spending an asset UTXO as
            // ordinary DGB destroys the asset.
            record.spentInputs + record.outpoint
        }
}
