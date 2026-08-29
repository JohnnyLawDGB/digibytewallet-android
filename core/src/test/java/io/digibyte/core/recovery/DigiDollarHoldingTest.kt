package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a foreign wallet's DigiDollar, which the ordinary UTXO lookup cannot see.
 *
 * ## Why this needs its own path
 *
 * A DigiDollar token output carries ZERO satoshis — the cents live in the transaction's OP_RETURN,
 * not in the output amount — and the reconcile endpoint filters it out of the UTXO set entirely.
 * Measured on mainnet: the taproot address holding $1.00 returns `balance 0, utxo_count 0` from
 * the ordinary lookup, while the backend's DigiDollar endpoint reports `dd_balance_cents: 100`
 * against the same key.
 *
 * So a scan that derived the right addresses would still conclude the wallet held nothing. The
 * derivation was never the blocker; the lookup is.
 *
 * ## What this has to produce
 *
 * A cent balance is enough to REPORT, but not to MOVE. Spending needs an outpoint — txid and vout
 * — plus the scriptPubKey. The endpoint gives the txid; the vout is whichever output pays our
 * taproot key, which is found by matching the P2TR script against the derived output key.
 */
class DigiDollarHoldingTest {

    /** X(Q) for the wallet under test, and the P2TR script that pays it. */
    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val ourScript = "5120$xq"
    private val otherScript = "001406bc386e98cbfb283e967217f37452a76b93ed26"
    private val opReturn = "6a02444401020164"

    /** The outputs of the real mainnet DigiDollar transaction 40a78f13…, verbatim. */
    private val realOutputs = listOf(
        DigiDollarHolding.Output(0, 0L, ourScript),
        DigiDollarHolding.Output(1, 39_468_332_700L, otherScript),
        DigiDollarHolding.Output(2, 0L, opReturn),
    )

    @Test fun `the outpoint paying our taproot key is found`() {
        val found = DigiDollarHolding.locate(realOutputs, taprootOutputKeyHex = xq)
        assertEquals("the zero-value P2TR output that pays us", 0, found?.vout)
        assertEquals(ourScript, found?.scriptPubKeyHex)
    }

    /** The OP_RETURN is also zero-value; matching on "value 0" alone would pick the wrong one. */
    @Test fun `the OP_RETURN is not mistaken for the token output`() {
        val found = DigiDollarHolding.locate(realOutputs, taprootOutputKeyHex = xq)
        assertTrue("must not select the marker", found?.scriptPubKeyHex != opReturn)
    }

    /** Someone else's change in the same transaction is not ours. */
    @Test fun `another party's output in the same transaction is ignored`() {
        val found = DigiDollarHolding.locate(realOutputs, taprootOutputKeyHex = xq)
        assertTrue(found!!.scriptPubKeyHex != otherScript)
    }

    @Test fun `a transaction that pays a different key yields nothing`() {
        val other = "1111111111111111111111111111111111111111111111111111111111111111"
        assertTrue(DigiDollarHolding.locate(realOutputs, taprootOutputKeyHex = other) == null)
    }

    /**
     * A DD token output must be zero-value. A P2TR output paying the same key but carrying
     * satoshis is ordinary DGB, not dollars, and spending it as a DD input would misstate the
     * transaction's value.
     */
    @Test fun `a funded output at the same key is not a DigiDollar output`() {
        val funded = listOf(DigiDollarHolding.Output(0, 500_000L, ourScript))
        assertTrue(DigiDollarHolding.locate(funded, taprootOutputKeyHex = xq) == null)
    }

    @Test fun `no outputs yields nothing rather than throwing`() {
        assertTrue(DigiDollarHolding.locate(emptyList(), taprootOutputKeyHex = xq) == null)
    }

    /** Case must not decide whether someone's dollars are found. */
    @Test fun `the key match is case-insensitive`() {
        val found = DigiDollarHolding.locate(realOutputs, taprootOutputKeyHex = xq.uppercase())
        assertEquals(0, found?.vout)
    }

    // ---- reporting the balance ------------------------------------------------------------

    @Test fun `cents are rendered as dollars for display`() {
        assertEquals("$1.00", DigiDollarHolding.formatCents(100))
        assertEquals("$0.05", DigiDollarHolding.formatCents(5))
        assertEquals("$1,234.56", DigiDollarHolding.formatCents(123_456))
    }

    /** Zero is not "some dollars we cannot see" — it is no dollars, and must not be reported. */
    @Test fun `zero cents is not a holding`() {
        assertTrue(!DigiDollarHolding.isHolding(0))
        assertTrue(DigiDollarHolding.isHolding(1))
    }
}
