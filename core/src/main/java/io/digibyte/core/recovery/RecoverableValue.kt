package io.digibyte.core.recovery

/**
 * Whether a completed scan found anything the user can act on, and whether it is entitled to say
 * so with confidence.
 *
 * The findings screen used to answer both questions from the sweepable UTXO list alone. That is
 * wrong for DigiDollar: the token output carries ZERO satoshis and the reconcile endpoint filters
 * zero-value outputs out of the UTXO set, so a wallet holding only dollars produces an EMPTY UTXO
 * list. The screen then showed "no funds found" over real money and offered no button to move it.
 *
 * Measured on mainnet — wallet A held $1.00 (tx 3ffcb1f3…, height 24,119,554) with zero DGB: the
 * scan located the dollar, the screen reported the wallet empty.
 */
object RecoverableValue {

    /** True when the scan found value worth offering a move for — UTXOs or DigiDollar cents. */
    fun exists(sweepableFindings: Int, digiDollar: DigiDollarScan.Result?): Boolean =
        sweepableFindings > 0 || (digiDollar?.hasDollars == true)

    /**
     * True when a "nothing found" verdict is an ANSWER rather than a gap. A DigiDollar lookup
     * that could not be made is not a zero balance — same rule the reconcile path follows for an
     * unreachable backend — so the screen must not close the question on it.
     *
     * Only meaningful when [exists] is false; with value found there is nothing to close.
     */
    fun answered(sweepableFindings: Int, digiDollar: DigiDollarScan.Result?): Boolean =
        exists(sweepableFindings, digiDollar) || digiDollar == null || digiDollar.reachable
}
