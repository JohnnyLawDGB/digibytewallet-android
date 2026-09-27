package io.digibyte.service

import io.digibyte.core.OutgoingTxStore.SweepAttempts

/**
 * Which recorded sends the stranded-send sweep re-publishes, decided from the wallet's own
 * transaction list. Pure, so the decision is testable without a wallet.
 *
 * The list ([io.digibyte.core.bridge.NativeBridge.getTransactionDetails]) is one
 * `txHash|amount|fee|blockHeight|timestamp|sent|received` row per transaction, the
 * [DETAILS_WINDOW] most recent only. The wallet orders its transactions by block height first,
 * and an unconfirmed transaction carries the greatest height of all ([TX_UNCONFIRMED]), so every
 * unconfirmed transaction stands at the newest end of the list: a full window lists every
 * unconfirmed transaction, and a transaction the window has aged out has confirmed. The sweep
 * therefore reads a recorded send as:
 *  - listed at a height below the sentinel → confirmed: settled, never re-published;
 *  - listed at the sentinel (or with a height it cannot read) → re-published, [MAX_ATTEMPTS] times
 *    in a row and then once per [HOLD_MS], for as long as it stays unconfirmed;
 *  - absent from a full window → aged out of it, so confirmed: settled;
 *  - absent from a window that is not full → the wallet does not hold it: tried [MAX_ATTEMPTS]
 *    times (a send recorded a moment before the wallet registered it), then settled as given up.
 *
 * A confirmed send that the sweep once read as "not listed, so unconfirmed" was re-published to
 * every peer every 90 s for as long as the wallet ran; the invariant here is that a send the
 * wallet holds at a confirmed height is never re-published and leaves the sweep's working set.
 */
object StrandedSendSelector {
    /** Rows the wallet's transaction list is capped at (its most recent). */
    const val DETAILS_WINDOW = 100

    /** The block height of a transaction not yet in a block. */
    const val TX_UNCONFIRMED = Int.MAX_VALUE.toLong()

    /** Re-publishes of one send before the sweep holds it (or, for a send the wallet does not
     *  hold, gives it up). */
    const val MAX_ATTEMPTS = 3

    /** How long a held send waits before the sweep tries it once more. */
    const val HOLD_MS = 60 * 60 * 1000L

    enum class Action {
        /** Re-publish now; the caller counts the attempt. */
        REPUBLISH,
        /** Unconfirmed, but the attempt cap was reached within the hold: leave it alone this sweep. */
        HOLD,
        /** Listed at a confirmed height. */
        SETTLE_CONFIRMED,
        /** Not listed although the window is full: older than every listed row, so confirmed. */
        SETTLE_AGED_OUT,
        /** Not held by the wallet after every attempt: nothing left to re-publish. */
        SETTLE_GAVE_UP,
    }

    data class Decision(val txid: String, val action: Action, val height: Long? = null) {
        /** True when the send leaves the sweep's working set. */
        val settles: Boolean get() = action != Action.REPUBLISH && action != Action.HOLD
    }

    /** The wallet's list as read: each listed txid to its height (null when the row's height
     *  cannot be read), how many rows the list had, and how many of them are confirmed. */
    data class Details(val heights: Map<String, Long?>, val rows: Int, val confirmedRows: Int) {
        /** A window that has aged rows out: at capacity, and anchored by a confirmed row. A
         *  window of nothing but unconfirmed rows says nothing about what fell out of it — an
         *  unconfirmed parent of those rows would be older than all of them — so it is not read
         *  as full. */
        val full: Boolean get() = rows >= DETAILS_WINDOW && confirmedRows > 0
    }

    /** Reads every non-blank row; a row that cannot be read still counts as a row and, when it
     *  names a txid, lists it with no height. Never throws on a malformed row. */
    fun parseDetails(text: String): Details {
        val heights = HashMap<String, Long?>()
        var rows = 0
        var confirmedRows = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            rows++
            val parts = line.split('|')
            val txid = parts[0].trim()
            if (txid.isEmpty()) continue
            val height = parts.getOrNull(3)?.trim()?.toLongOrNull()
            heights[txid] = height
            if (isConfirmed(height)) confirmedRows++
        }
        return Details(heights, rows, confirmedRows)
    }

    private fun isConfirmed(height: Long?): Boolean = height != null && height > 0L && height < TX_UNCONFIRMED

    /**
     * One [Decision] per recorded send, in txid order — or none at all when [detailsText] is
     * blank: a list the wallet could not produce says nothing about any send, so nothing is
     * tried, settled or counted on its account.
     *
     * @param recorded the sends the sweep still looks after (the store's pending records)
     * @param detailsText the wallet's transaction list, as the bridge returns it
     * @param attempts the sweep's attempt count and last attempt time for a txid
     * @param nowMs the current wall-clock time
     */
    fun select(
        recorded: Collection<String>,
        detailsText: String,
        attempts: (String) -> SweepAttempts,
        nowMs: Long,
    ): List<Decision> {
        if (detailsText.isBlank()) return emptyList()
        val details = parseDetails(detailsText)
        return recorded.sorted().map { txid -> decide(txid, details, attempts(txid), nowMs) }
    }

    private fun decide(txid: String, details: Details, tried: SweepAttempts, nowMs: Long): Decision {
        if (txid in details.heights) {
            val height = details.heights[txid]
            if (isConfirmed(height)) return Decision(txid, Action.SETTLE_CONFIRMED, height)
            // Unconfirmed, or a height that cannot be read: bounded re-publishes, then hourly.
            val held = tried.count >= MAX_ATTEMPTS && nowMs - tried.lastMs < HOLD_MS
            return Decision(txid, if (held) Action.HOLD else Action.REPUBLISH, height)
        }
        if (details.full) return Decision(txid, Action.SETTLE_AGED_OUT)
        return Decision(txid, if (tried.count >= MAX_ATTEMPTS) Action.SETTLE_GAVE_UP else Action.REPUBLISH)
    }
}
