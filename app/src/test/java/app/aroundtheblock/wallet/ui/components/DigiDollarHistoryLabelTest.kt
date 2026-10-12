package app.aroundtheblock.wallet.ui.components

import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.model.DigiDollarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A DigiDollar row in the activity list says what the transaction is — minted, sent, received, moved
 * to the wallet itself, redeemed — from the event native code reads from the transaction
 * (digidollar_event.h, run on the host by digidollar_realtx_kat), never from the sign of its DGB
 * movement: a mint sends DGB away as collateral while dollars arrive, a transfer to yourself moves only
 * its fee, a redemption brings collateral back while dollars are burned.
 */
class DigiDollarHistoryLabelTest {

    private fun event(kind: DigiDollarEvent.Kind, cents: Long = 10_000L, change: Long = 0L) = DigiDollarEvent(kind, cents, change)

    // ── what each kind reads as ────────────────────────────────────────────────────────────────

    @Test fun `a mint reads as minted, dollars arriving, though its DGB went out`() {
        val look = rowLook(dgbAmount = -10_010_000_000L, kind = TxKind.DIGIDOLLAR, ddEvent = event(DigiDollarEvent.Kind.MINTED))
        assertEquals(RowLook(R.string.tx_dd_minted, RowDirection.IN), look)
    }

    @Test fun `a transfer to yourself reads as moved to yourself, though its fee went out`() {
        val look = rowLook(dgbAmount = -10_000_000L, kind = TxKind.DIGIDOLLAR, ddEvent = event(DigiDollarEvent.Kind.SELF_TRANSFER))
        assertEquals(RowLook(R.string.tx_dd_self_transfer, RowDirection.SELF), look)
    }

    @Test fun `a redemption reads as redeemed, dollars leaving, though its DGB came back`() {
        val full = rowLook(dgbAmount = 9_990_000_000L, kind = TxKind.DIGIDOLLAR, ddEvent = event(DigiDollarEvent.Kind.REDEEMED))
        val withChange = rowLook(dgbAmount = 5_990_000_000L, kind = TxKind.DIGIDOLLAR,
            ddEvent = event(DigiDollarEvent.Kind.REDEEMED, cents = 6_000L, change = 4_000L))
        assertEquals(RowLook(R.string.tx_dd_redeemed, RowDirection.OUT), full)
        assertEquals(RowLook(R.string.tx_dd_redeemed, RowDirection.OUT), withChange)
    }

    @Test fun `a transfer sent or received reads as sent or received`() {
        assertEquals(RowLook(R.string.txd_sent, RowDirection.OUT),
            rowLook(-10_000_000L, TxKind.DIGIDOLLAR, event(DigiDollarEvent.Kind.SENT, cents = 2_500L, change = 7_500L)))
        assertEquals(RowLook(R.string.txd_received, RowDirection.IN),
            rowLook(0L, TxKind.DIGIDOLLAR, event(DigiDollarEvent.Kind.RECEIVED, cents = 2_500L)))
    }

    @Test fun `with no event a row reads from its DGB amount, as every other row does`() {
        assertEquals(RowLook(R.string.txd_sent, RowDirection.OUT), rowLook(-5L, TxKind.DIGIDOLLAR, null))
        assertEquals(RowLook(R.string.txd_received, RowDirection.IN), rowLook(5L, TxKind.DIGIDOLLAR, null))
        assertEquals(RowLook(R.string.txd_sent, RowDirection.OUT), rowLook(-5L, TxKind.DGB, null))
        // An event never relabels a row that is not a DigiDollar row.
        assertEquals(RowLook(R.string.txd_received, RowDirection.IN), rowLook(5L, TxKind.DIGIASSET, event(DigiDollarEvent.Kind.REDEEMED)))
    }

    // ── where the label comes from ─────────────────────────────────────────────────────────────

    private fun source(rel: String): String = File("src/main/java/app/aroundtheblock/wallet/$rel").let {
        assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
        it.readText()
    }

    @Test fun `the row's label and icon come from its look, not from the sign of its DGB amount`() {
        val item = source("ui/components/TransactionItem.kt")
        val start = item.indexOf("fun TransactionItem(")
        assertTrue("cannot find TransactionItem — the gate is blind, not clean", start >= 0)
        val body = item.substring(start)
        assertTrue("the row's look is not taken from rowLook", body.contains("val look = rowLook(tx.amount, kind, ddEvent)"))
        assertEquals("the label and the icon's description must both be the look's", 2,
            Regex(Regex.escape("stringResource(look.label)")).findAll(body).count())
        assertFalse("the row still labels itself from the DGB sign",
            body.contains("if (isSend) R.string.txd_sent else R.string.txd_received"))
    }

    @Test fun `the activity list reads each DigiDollar row's event and passes it to the row`() {
        val vm = source("ui/wallet/WalletViewModel.kt")
        assertTrue("the view model does not read the DigiDollar event", vm.contains("NativeBridge.digiDollarEvent(tx.txid)"))
        assertFalse("the view model still takes a DigiDollar row's direction from the DGB sign", vm.contains("digiDollarTxAmount"))
        val screen = source("ui/wallet/WalletScreen.kt")
        assertTrue("the list does not hand each row its DigiDollar event", screen.contains("ddEvent = txDdEvents[tx.txid]"))
    }
}
