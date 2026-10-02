package io.digibyte.ui.components

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: an activity row keeps its left column readable whatever the amount says.
 *
 * The amount of an asset row is "N <asset name>", and a name can be long. The amount column was
 * measured first at full width and the left column (label, kind chip, address, date) got what was
 * left, so "DigiAsset" broke one letter per line and "Received" broke inside the word. The row now
 * caps the amount column ([amountColumnMaxWidth], tested on the JVM in TransactionRowLayoutTest),
 * cuts the amount to one line with an ellipsis, and keeps the label and the chip on one line each.
 *
 * Compose layout has no JVM harness in this repository, so the wiring is pinned here.
 */
class TransactionRowLayoutGateTest {

    private val gate = KotlinSourceGate.of(
        File("src/main/java/io/digibyte/ui/components/TransactionItem.kt").readText()
    )

    private fun textCallWhose(
        argument: String,
        startsWith: String,
        within: IntRange = gate.code.indices,
    ): KotlinSourceGate.Call {
        val found = gate.calls("Text", within).filter { call -> call.named[argument]?.startsWith(startsWith) == true }
        assertEquals("expected one Text( with $argument = $startsWith… — the gate is blind, not clean", 1, found.size)
        return found.single()
    }

    @Test fun `the amount is one line, cut with an ellipsis`() {
        val amount = textCallWhose("text", "amountText")
        assertEquals("1", amount.named["maxLines"])
        assertEquals("TextOverflow.Ellipsis", amount.named["overflow"])
    }

    @Test fun `the amount column is capped, so the left column is never squeezed to nothing`() {
        val amount = textCallWhose("text", "amountText")
        val column = gate.calls("Column").filter { call ->
            val block = gate.trailingBlock(call)
            block != null && amount.range.first in block
        }.minByOrNull { it.range.last - it.range.first }
        assertTrue("the amount Text is not inside a Column", column != null)
        val modifier = column!!.named["modifier"].orEmpty()
        assertTrue("the amount column has no width cap: `$modifier`", modifier.contains("amountColumnMaxWidth("))
    }

    @Test fun `the direction label stays on one line`() {
        val label = textCallWhose("text", "stringResource(if (isSend) R.string.txd_sent")
        assertEquals("1", label.named["maxLines"])
        assertEquals("false", label.named["softWrap"])
    }

    @Test fun `the kind chip stays on one line`() {
        val typeChip = gate.range("fun TypeChip(", "fun ConfirmationsBadge(")
        assertTrue("cannot find TypeChip — the gate is blind, not clean", typeChip != null)
        val chip = textCallWhose("text", "label", typeChip!!)
        assertEquals("1", chip.named["maxLines"])
        assertEquals("false", chip.named["softWrap"])
    }
}
