package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.db.entity.TransactionEntity
import app.aroundtheblock.wallet.core.sync.ChainTipPolicy

/**
 * The activity list's rows as the shared core computes them (BRTxRows.h, through
 * [app.aroundtheblock.wallet.core.bridge.NativeBridge.getTransactionRows]). Every figure is the
 * core's: the signed amount (a send is its payee amount only, the fee its own field), the fee or
 * unknown, and the date shown (a pending row with no timestamp shows the caller's `now`). The rows
 * come in the core's order, newest first, and nothing here re-sorts or re-computes them; iOS takes
 * the same rows (Policy/TransactionRows.swift).
 *
 * DISPLAY ONLY (BRTxRows.h): nothing read here may drive crediting, asset ingestion, notification
 * gating or the stranded-send sweep. Those read getTransactionDetails.
 */
object TransactionRows {

    /** How many rows the activity list reads: the newest this many. */
    const val LIMIT = 100

    /** The core's TX_UNCONFIRMED: a row's height while unconfirmed. */
    private const val TX_UNCONFIRMED = Int.MAX_VALUE.toLong()

    /** BR_TX_ROW_FEE_UNKNOWN (UINT64_MAX): a fee the wallet cannot know, never shown as a figure. */
    val FEE_UNKNOWN: ULong = ULong.MAX_VALUE

    /** BRTxRowAmountRule. */
    enum class Rule { NET, PAYEE, DIGIDOLLAR }

    /** One line of getTransactionRows, as the core wrote it (tx_rows_list.h). */
    data class Row(
        val txid: String,
        val amount: Long,
        /** null for [FEE_UNKNOWN]. */
        val fee: ULong?,
        /** TX_UNCONFIRMED while unconfirmed, as the core has it. */
        val blockHeight: Long,
        val shownTimestamp: Long,
        val sent: ULong,
        val received: ULong,
        val rule: Rule,
    )

    /** The lines getTransactionRows returned, in their order. A line that does not parse is left
     *  out rather than shown with made-up figures. */
    fun parse(text: String): List<Row> =
        text.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): Row? {
        val p = line.trim().split('|')
        if (p.size != 8 || p[0].length != 64) return null
        val fee = p[2].toULongOrNull() ?: return null
        return Row(
            txid = p[0],
            amount = p[1].toLongOrNull() ?: return null,
            fee = if (fee == FEE_UNKNOWN) null else fee,
            blockHeight = p[3].toUIntOrNull()?.toLong() ?: return null,
            shownTimestamp = p[4].toUIntOrNull()?.toLong() ?: return null,
            sent = p[5].toULongOrNull() ?: return null,
            received = p[6].toULongOrNull() ?: return null,
            rule = when (p[7]) {
                "0" -> Rule.NET
                "1" -> Rule.PAYEE
                "2" -> Rule.DIGIDOLLAR
                else -> return null
            },
        )
    }

    /** The fee as the screens take it: satoshis, or [TransactionEntity.FEE_UNKNOWN]. A figure no
     *  Long holds is past DigiByte's whole supply and is not shown either. */
    fun feeForEntity(fee: ULong?): Long =
        if (fee == null || fee > Long.MAX_VALUE.toULong()) TransactionEntity.FEE_UNKNOWN else fee.toLong()

    /**
     * One row as the activity list shows it: only the mapping, no rule of its own. [confTip] is the
     * tip confirmations count from; [toAddress] the payee the app recorded when it broadcast this
     * row's send (shown for a payee row only: it says who the amount went to).
     */
    fun entity(row: Row, confTip: Long, toAddress: String?): TransactionEntity {
        // Pending normalised to 0, so confirmations read 0 and the detail screen says "Pending".
        val height = if (row.blockHeight in 1 until TX_UNCONFIRMED) row.blockHeight else 0L
        return TransactionEntity(
            txid = row.txid,
            amount = row.amount,
            fee = feeForEntity(row.fee),
            blockHeight = height,
            timestamp = row.shownTimestamp,
            toAddress = if (row.rule == Rule.PAYEE) toAddress.orEmpty() else "",
            fromAddress = "",
            confirmations = ChainTipPolicy.confirmationsFor(height, confTip),
            sent = row.sent.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            received = row.received.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            isAssetTx = false,
        )
    }
}
