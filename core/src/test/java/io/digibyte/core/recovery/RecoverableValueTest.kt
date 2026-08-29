package io.digibyte.core.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The findings screen decides between "here is what we found" and "no funds found" from the
 * sweepable UTXO list alone. A DigiDollar token output carries ZERO satoshis, so a wallet whose
 * only value is DigiDollar produces an EMPTY utxo list — and the screen told the user their
 * wallet was empty while it held dollars, with no button to move them.
 *
 * Measured on mainnet: wallet A held $1.00 (tx 3ffcb1f3…, height 24,119,554) and zero DGB. The
 * scan located the dollar; the screen said "no funds found".
 */
class RecoverableValueTest {

    private fun dd(cents: Long, reachable: Boolean = true, unlocatable: Long = 0L) =
        DigiDollarScan.Result(
            cents = cents,
            holdings = emptyList(),
            reachable = reachable,
            unlocatableCents = unlocatable,
        )

    @Test
    fun `dollars alone are recoverable value`() {
        assertTrue(RecoverableValue.exists(sweepableFindings = 0, digiDollar = dd(100)))
    }

    @Test
    fun `no utxos and no dollars is genuinely nothing`() {
        assertFalse(RecoverableValue.exists(sweepableFindings = 0, digiDollar = dd(0)))
    }

    @Test
    fun `no utxos and no dollar scan at all is nothing found`() {
        assertFalse(RecoverableValue.exists(sweepableFindings = 0, digiDollar = null))
    }

    @Test
    fun `utxos alone are recoverable value`() {
        assertTrue(RecoverableValue.exists(sweepableFindings = 2, digiDollar = null))
    }

    /**
     * An unreachable DigiDollar lookup is not a zero balance. The screen must not close the
     * question with "no funds found" when it never got an answer.
     */
    @Test
    fun `unreachable dollar lookup is not nothing found`() {
        assertFalse(RecoverableValue.answered(sweepableFindings = 0, digiDollar = dd(0, reachable = false)))
    }

    @Test
    fun `reachable zero is a confident nothing`() {
        assertTrue(RecoverableValue.answered(sweepableFindings = 0, digiDollar = dd(0)))
    }
}
