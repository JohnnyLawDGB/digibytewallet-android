package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.db.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L2: the activity list's rows (BRTxRows.h) through the Kotlin the app runs. The core computes every
 * row (the amount, fee, date and order rules); jni_wallet.c getTransactionRows prints them
 * (tx_rows_list.h) and [TransactionRows] parses and maps them. NativeBridge cannot load on a host
 * JVM, so the rules themselves are held by the host KATs (tx_rows_order_kat, tx_rows_amount_kat,
 * tx_rows_wallet_kat, tx_rows_list_kat). What this holds is the seam: for every answer in the shared
 * vectors (`vectors/tx-rows/vectors.txt`, the file iOS's TxRowsTests also reads), a line carrying the
 * core's figure reaches the screen as that figure -- not re-computed from sent and received, not an
 * unknown fee read as 0, not a row moved by a sort of the app's own.
 */
class TransactionRowsVectorsTest {

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("$rel not found above ${System.getProperty("user.dir")}: is the core submodule checked out?")
    }

    private val vectors: List<List<String>> by lazy {
        repoFile("native/src/main/jni/digibytewallet-core/vectors/tx-rows/vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ') }
    }

    private fun txid(i: Int) = "%064x".format(i)

    /** A line as tx_rows_list.h prints it. */
    private fun line(
        txid: String = txid(1),
        amount: String = "0",
        fee: String = "0",
        height: String = "24000000",
        shown: String = "1762560000",
        sent: String = "0",
        received: String = "0",
        rule: String = "0",
    ) = "$txid|$amount|$fee|$height|$shown|$sent|$received|$rule"

    private fun one(text: String): TransactionRows.Row =
        TransactionRows.parse(text).also { assertEquals("one row from: $text", 1, it.size) }.single()

    private val ruleCode = mapOf("NET" to "0", "PAYEE" to "1", "DIGIDOLLAR" to "2")

    @Test
    fun everySharedVectorReachesTheScreenAsTheCoreGaveIt() {
        val counts = HashMap<String, Int>()
        for (f in vectors) {
            counts[f[0]] = (counts[f[0]] ?: 0) + 1
            when (f[0]) {
                "shown" -> {
                    val row = one(line(shown = f[3], height = "2147483647"))
                    assertEquals("shown ${f[1]} ${f[2]}", f[3].toLong(), row.shownTimestamp)
                    assertEquals("the entity's date is the core's shown date", f[3].toLong(),
                        TransactionRows.entity(row, 24_000_100, null).timestamp)
                }
                "fee" -> {
                    val row = one(line(fee = f[2]))
                    val want = f[2].toULong()
                    if (want == TransactionRows.FEE_UNKNOWN) {
                        assertNull("fee ${f[1]}: unknown is no figure", row.fee)
                    } else {
                        assertEquals("fee ${f[1]}", want, row.fee)
                    }
                    val shown = TransactionRows.entity(row, 24_000_100, null).fee
                    if (want > Long.MAX_VALUE.toULong()) {
                        assertEquals("fee ${f[1]}: not a figure the screen can show", TransactionEntity.FEE_UNKNOWN, shown)
                    } else {
                        assertEquals("fee ${f[1]}", want.toLong(), shown)
                    }
                }
                "amount" -> {
                    // received sent toOthers rule amount: the line carries the core's figures.
                    val row = one(line(amount = f[7], received = f[3], sent = f[4], rule = ruleCode.getValue(f[6])))
                    assertEquals("rule: ${f.joinToString(" ")}", TransactionRows.Rule.valueOf(f[6]), row.rule)
                    val entity = TransactionRows.entity(row, 24_000_100, "dgb1qpayee")
                    assertEquals("amount, not re-computed: ${f.joinToString(" ")}", f[7].toLong(), entity.amount)
                    assertEquals("the payee is named on a payee row only: ${f.joinToString(" ")}",
                        if (f[6] == "PAYEE") "dgb1qpayee" else "", entity.toAddress)
                }
                "order" -> {
                    val arrow = f.indexOf("->")
                    val items = f.subList(2, arrow).map { it.split(':') }
                    val want = f.subList(arrow + 1, f.size).map { it.toInt() }
                    fun lineOf(walletIndex: Int): String {
                        val p = items.first { it[2].toInt() == walletIndex }
                        return line(txid = txid(walletIndex), shown = p[0], height = p[1])
                    }
                    // In the core's order the list is the core's order.
                    val inCoreOrder = want.joinToString("\n") { lineOf(it) }
                    assertEquals("order ${f[1]}", want.map { txid(it) },
                        TransactionRows.parse(inCoreOrder).map { TransactionRows.entity(it, 24_000_100, null).txid })
                    // In any other order it stays that order: the app never sorts.
                    val reversed = want.reversed().joinToString("\n") { lineOf(it) }
                    assertEquals("order ${f[1]} reversed", want.reversed().map { txid(it) },
                        TransactionRows.parse(reversed).map { it.txid })
                }
                else -> error("unknown vector kind ${f[0]}")
            }
        }
        // Never vacuous: the file as the core writes it at the pin.
        assertEquals(mapOf("shown" to 16, "fee" to 5, "amount" to 68, "order" to 9), counts)
    }

    @Test
    fun aPendingRowIsPendingAndAConfirmedRowCountsConfirmations() {
        val pending = TransactionRows.entity(one(line(height = "2147483647")), 24_000_100, null)
        assertEquals(0L, pending.blockHeight)
        assertEquals(0, pending.confirmations)
        val confirmed = TransactionRows.entity(one(line(height = "24000000")), 24_000_100, null)
        assertEquals(24_000_000L, confirmed.blockHeight)
        assertEquals(101, confirmed.confirmations)
    }

    @Test
    fun aLineThatDoesNotParseIsLeftOutNotGuessed() {
        val good = line()
        for (bad in listOf(
            "", "garbage",
            good.substringBeforeLast('|'),                       // a field short
            "$good|9",                                           // a field over
            line(txid = "ab"),                                   // not a txid
            line(amount = "x"), line(fee = "-1"), line(height = "-1"), line(shown = "4294967296"),
            line(rule = "3"),
        )) {
            assertTrue("parsed: '$bad'", TransactionRows.parse(bad).isEmpty())
        }
        assertEquals(2, TransactionRows.parse("$good\n\n${line(txid = txid(2))}\n").size)
    }

    /** The Kotlin reads the fields in the order tx_rows_list.h prints them. */
    @Test
    fun theParserReadsTheFormatTheBridgePrints() {
        val header = repoFile("native/src/main/jni/bridge/tx_rows_list.h").readText()
        assertTrue("tx_rows_list.h no longer prints the line this parser reads; change both",
            header.contains(""""%s|%lld|%llu|%u|%u|%llu|%llu|%u\n",""") &&
                header.contains("hex, (long long)r->amount, (unsigned long long)r->fee, r->blockHeight,") &&
                header.contains("r->shownTimestamp, (unsigned long long)r->sent, (unsigned long long)r->received,"))
        val jni = repoFile("native/src/main/jni/bridge/jni_wallet.c").readText()
        assertTrue("getTransactionRows no longer returns tx_rows_list's text",
            jni.contains("char *buf = tx_rows_list(g_wallet, now, (size_t)limit);"))
    }
}
