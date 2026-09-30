package io.digibyte.core.sync

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.decodeSavedTransactionsOrNull
import io.digibyte.core.networkSuffix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Rebuild the transaction history from the chain once, on the first launch of this build.
 *
 * A transaction cache (`saved_transactions`) written by an earlier build is discarded before the
 * wallet loads it, and the history is re-derived by the normal compact-filter scan, the same path
 * the manual "Full rebuild from chain" uses. Differences from the manual rebuild:
 *  - it runs at process start, before any service or unlock, so nothing stale survives in memory;
 *  - the scan floor is carried down to the oldest confirmed record the old cache held
 *    ([KEY_FLOOR_HINT] / [KEY_FLOOR_TIME]), never only the wallet's birth: a bogus low floor costs
 *    a longer scan, a floor that is too high would hide history;
 *  - recorded-send metadata ([OutgoingTxStore]) is kept;
 *  - it is deferred while one of the wallet's own recorded sends is not settled (the scan cannot
 *    re-learn an unconfirmed transaction), for at most [DEFER_CAP_MS] from the first deferral.
 *
 * It runs to completion once per network. [KEY_IN_PROGRESS] is committed before anything is
 * cleared, so a process death part-way re-runs the clear rather than skipping it. It never runs for
 * a cache written by this build or later: an empty cache stamps [KEY_DONE], and [markNotNeeded] is
 * called wherever the cache is rebuilt from scratch (create, recover, seed change, manual rebuild).
 *
 * No native call anywhere in here: the native library is not in use yet when this runs.
 */
object HistoryRebuildOnUpgrade {
    const val TAG = "HistoryRebuild"
    const val PREFS_BASE = "dgb_history_rebuild"
    const val KEY_DONE = "done"
    const val KEY_IN_PROGRESS = "in_progress"
    const val KEY_DEFERRED_SINCE_MS = "deferred_since_ms"
    /** Lowest confirmed block height the discarded cache held (raw height), 0 = none. Consumed by
     *  SyncService when it picks the compact-filter floor. */
    const val KEY_FLOOR_HINT = "floor_hint"
    /** Lowest timestamp (unix seconds) of a confirmed record the discarded cache held, 0 = none.
     *  The native checkpoint lookup is time-based, so this is what lowers the header anchor. Kept
     *  (never raised) until the wallet itself changes. */
    const val KEY_FLOOR_TIME = "floor_time"
    /** Set in the same commit as [KEY_IN_PROGRESS] when a run starts: the Room `transactions`
     *  table of this network (read by DigiAsset history) still holds rows for the discarded
     *  records. Cleared by [clearRoomTransactionsIfPending] at the first sync start. */
    const val KEY_ROOM_CLEAR_PENDING = "room_clear_pending"

    /** Matches the default mempool expiry: a send still unconfirmed after this is in no default
     *  mempool, so holding the cache for it any longer buys nothing. */
    const val DEFER_CAP_MS = 14L * 24 * 60 * 60 * 1000

    /** Same record cap as the native loader (jni_transaction_persist.c). */
    const val MAX_RECORDS = 10_000L

    /** TX_UNCONFIRMED (BRTransaction.h): the height of a record that is not in a block. */
    const val UNCONFIRMED_HEIGHT = 0x7fffffffL

    private const val SYNC_PREFS_BASE = "dgb_sync_data"

    enum class Decision { NOT_NEEDED, DEFER, RUN }

    data class State(
        val done: Boolean = false,
        val inProgress: Boolean = false,
        val deferredSinceMs: Long = 0L,
        val floorHint: Long = 0L,
        val floorTime: Long = 0L,
    )

    /**
     * What the old cache holds, read from its framing only (the transactions themselves are not
     * parsed). [declaredCount] is the leading count; [records] is how many whole records follow.
     */
    data class CacheSummary(
        val declaredCount: Long,
        val records: Int,
        val minConfirmedHeight: Long,
        val minRecordTime: Long,
    ) {
        val isEmpty: Boolean get() = declaredCount == 0L

        companion object {
            val EMPTY = CacheSummary(0L, 0, 0L, 0L)
        }
    }

    enum class OutcomeKind { NOT_NEEDED, DEFERRED, RAN }

    /** [discarded] is the number of records in the cache that was discarded (RAN only). */
    data class Outcome(val kind: OutcomeKind, val discarded: Int = 0)

    private val _lastOutcome = MutableStateFlow<Outcome?>(null)

    /** The outcome of the most recent evaluation in this process, null before the first. */
    val lastOutcome: StateFlow<Outcome?> = _lastOutcome.asStateFlow()

    /** Per network (suffix), the outcome already reached in this process. See [beforeWalletLoad]. */
    private val evaluated = ConcurrentHashMap<String, Outcome>()

    // ── Pure parts ───────────────────────────────────────────────────────────

    /**
     * The decision for one network.
     *  - done → NOT_NEEDED;
     *  - in progress → RUN (resume; pending sends no longer matter, the cache is already partly gone);
     *  - empty cache → NOT_NEEDED (the caller stamps done);
     *  - a recorded send not settled, and no deferral yet or the first one is under
     *    [DEFER_CAP_MS] old → DEFER. A clock that reads earlier than the first deferral is treated
     *    as still deferred, never as expired;
     *  - otherwise RUN.
     */
    fun decide(state: State, cache: CacheSummary, pendingSendCount: Int, nowMs: Long): Decision {
        if (state.done) return Decision.NOT_NEEDED
        if (state.inProgress) return Decision.RUN
        if (cache.isEmpty) return Decision.NOT_NEEDED
        if (pendingSendCount > 0 && stillDeferred(state.deferredSinceMs, nowMs)) return Decision.DEFER
        return Decision.RUN
    }

    /** [decide] over the raw cache bytes (null = absent or undecodable). */
    fun decide(state: State, cacheBytes: ByteArray?, pendingSendCount: Int, nowMs: Long): Decision =
        decide(state, summarize(cacheBytes), pendingSendCount, nowMs)

    internal fun stillDeferred(deferredSinceMs: Long, nowMs: Long): Boolean {
        if (deferredSinceMs <= 0L) return true
        val elapsed = nowMs - deferredSinceMs
        if (elapsed < 0L) return true
        return elapsed < DEFER_CAP_MS
    }

    /**
     * Read the framing of a `saved_transactions` blob (layout of jni_transaction_persist.c: u32
     * count, then per record u32 size, u32 height, u32 timestamp, size bytes; all little-endian).
     * Never throws. Mirrors the native loader's bounds: a count of 0 or above [MAX_RECORDS] loads
     * nothing, and a record whose size runs past the end stops the walk. The floor height comes only
     * from records at a real height (0 < height < [UNCONFIRMED_HEIGHT]); the floor time also from
     * unconfirmed records (height == [UNCONFIRMED_HEIGHT]), whose time is when they were seen.
     */
    fun summarize(bytes: ByteArray?): CacheSummary {
        if (bytes == null || bytes.size <= 4) return CacheSummary.EMPTY
        val count = u32le(bytes, 0)
        if (count == 0L) return CacheSummary.EMPTY
        if (count > MAX_RECORDS) return CacheSummary(count, 0, 0L, 0L)
        var pos = 4L
        var records = 0
        var minHeight = 0L
        var minTime = 0L
        val len = bytes.size.toLong()
        var i = 0L
        while (i < count && pos + 12L <= len) {
            val size = u32le(bytes, pos.toInt())
            val height = u32le(bytes, pos.toInt() + 4)
            val time = u32le(bytes, pos.toInt() + 8)
            pos += 12L
            if (pos + size > len) break
            pos += size
            records++
            if (height in 1L until UNCONFIRMED_HEIGHT) {
                if (minHeight == 0L || height < minHeight) minHeight = height
            }
            if (height in 1L..UNCONFIRMED_HEIGHT && time > 0L && (minTime == 0L || time < minTime)) minTime = time
            i++
        }
        return CacheSummary(count, records, minHeight, minTime)
    }

    /** The lower of two optional (0 = none) floors: a floor is never raised. */
    internal fun lowerFloor(existing: Long, candidate: Long): Long = when {
        existing <= 0L -> candidate.coerceAtLeast(0L)
        candidate <= 0L -> existing
        else -> minOf(existing, candidate)
    }

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xffL) or
            ((b[off + 1].toLong() and 0xffL) shl 8) or
            ((b[off + 2].toLong() and 0xffL) shl 16) or
            ((b[off + 3].toLong() and 0xffL) shl 24)

    // ── Android runner ───────────────────────────────────────────────────────

    private fun statePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_BASE + networkSuffix(context), Context.MODE_PRIVATE)

    private fun syncPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SYNC_PREFS_BASE + networkSuffix(context), Context.MODE_PRIVATE)

    fun readState(context: Context): State {
        val p = statePrefs(context)
        return State(
            done = p.getBoolean(KEY_DONE, false),
            inProgress = p.getBoolean(KEY_IN_PROGRESS, false),
            deferredSinceMs = p.getLong(KEY_DEFERRED_SINCE_MS, 0L),
            floorHint = p.getLong(KEY_FLOOR_HINT, 0L),
            floorTime = p.getLong(KEY_FLOOR_TIME, 0L),
        )
    }

    /**
     * Evaluate and, when due, run the rebuild for the selected network. Called from
     * `Application.onCreate` right after the network is selected, before any service or unlock.
     * A throw leaves [KEY_DONE] unset, so the next launch tries again.
     */
    fun runAtProcessStart(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        checkpointTimeAtOrBelow: (Long) -> Long = ::nativeCheckpointTimeAtOrBelow,
    ): Outcome {
        val outcome = evaluate(context, nowMs, checkpointTimeAtOrBelow)
        evaluated[networkSuffix(context)] = outcome
        return outcome
    }

    /**
     * The same, from the wallet load (`WalletManager.restoreFromDisk`, before it reads the cache).
     * If this process already evaluated the selected network at start, that outcome stands for the
     * rest of the process: in particular a start-time DEFER is never turned into a RUN while a sync
     * session may already hold the old state in memory. Only a network this process has not
     * evaluated yet (or a start-time evaluation that threw) is evaluated here.
     */
    fun beforeWalletLoad(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        checkpointTimeAtOrBelow: (Long) -> Long = ::nativeCheckpointTimeAtOrBelow,
    ): Outcome {
        evaluated[networkSuffix(context)]?.let { return it }
        return runAtProcessStart(context, nowMs, checkpointTimeAtOrBelow)
    }

    /** The native table lookup; 0 when the library cannot answer. The native library is loaded
     *  by the network selection before the start-time call. */
    private fun nativeCheckpointTimeAtOrBelow(height: Long): Long =
        runCatching { NativeBridge.getCheckpointTimeAtOrBelow(height) }.getOrDefault(0L)

    private fun evaluate(context: Context, nowMs: Long, checkpointTimeAtOrBelow: (Long) -> Long): Outcome {
        val state = readState(context)
        if (state.done) return publish(Outcome(OutcomeKind.NOT_NEEDED))

        val sync = syncPrefs(context)
        val cacheBytes = decodeSavedTransactionsOrNull(sync.getString(KEY_SAVED_TRANSACTIONS, null))
        val summary = summarize(cacheBytes)
        val pending = runCatching { OutgoingTxStore(context).pendingTxids().size }.getOrDefault(0)

        return when (decide(state, summary, pending, nowMs)) {
            Decision.NOT_NEEDED -> {
                // Empty cache: nothing written by an earlier build to discard. An empty commit on
                // the sync file first makes any pending asynchronous clear of it durable, so a
                // cleared cache cannot reappear after `done` is on disk. If that write does not
                // land, `done` is not stamped either.
                check(sync.edit().commit()) { "sync state not durable; not marking the rebuild done" }
                check(
                    statePrefs(context).edit()
                        .putBoolean(KEY_DONE, true)
                        .putBoolean(KEY_IN_PROGRESS, false)
                        .putLong(KEY_DEFERRED_SINCE_MS, 0L)
                        .commit(),
                ) { "rebuild state not durable" }
                log("no earlier transaction cache on this network; nothing to rebuild")
                publish(Outcome(OutcomeKind.NOT_NEEDED))
            }
            Decision.DEFER -> {
                // Only the first deferral is stamped. A clock that reads earlier later on does not
                // move it (that would shorten the cap once the clock is right again); a clock that
                // was ahead at the stamp lengthens the deferral instead, which only delays.
                if (state.deferredSinceMs <= 0L) {
                    statePrefs(context).edit().putLong(KEY_DEFERRED_SINCE_MS, nowMs).commit()
                }
                log("rebuild deferred: $pending recorded send(s) not settled")
                publish(Outcome(OutcomeKind.DEFERRED))
            }
            Decision.RUN -> {
                run(context, state, summary, checkpointTimeAtOrBelow)
                publish(Outcome(OutcomeKind.RAN, summary.records))
            }
        }
    }

    /**
     * Steps 1-4. Each step is committed; re-running from any point completes the same way. A clear
     * that does not land throws before `done` is stamped: `in_progress` stays set, the wallet load
     * then skips the cache, and the next start runs it again.
     *
     * The floor time is the lower of the records' own times and the time of the compiled
     * checkpoint at or below the floor height: the header anchor chosen from it (the latest
     * checkpoint more than a week older) is then at or below the floor height whatever the records'
     * times say (zero, or later than their block).
     */
    private fun run(context: Context, state: State, summary: CacheSummary, checkpointTimeAtOrBelow: (Long) -> Long) {
        val hint = lowerFloor(state.floorHint, summary.minConfirmedHeight)
        val checkpointTime = if (hint > 0L) runCatching { checkpointTimeAtOrBelow(hint) }.getOrDefault(0L) else 0L
        val time = lowerFloor(lowerFloor(state.floorTime, summary.minRecordTime), checkpointTime)
        check(
            statePrefs(context).edit()
                .putBoolean(KEY_IN_PROGRESS, true)
                .putLong(KEY_FLOOR_HINT, hint)
                .putLong(KEY_FLOOR_TIME, time)
                .putBoolean(KEY_ROOM_CLEAR_PENDING, true)
                .commit(),
        ) { "rebuild state not durable; nothing cleared" }
        log(
            "rebuilding history from the chain: discarding ${summary.records} cached record(s), " +
                "floor height $hint, floor time $time (checkpoint time $checkpointTime)",
        )
        check(clearTransactionCacheForRebuild(context)) { "transaction cache clear did not land; retried next start" }
        check(
            statePrefs(context).edit()
                .putBoolean(KEY_DONE, true)
                .putBoolean(KEY_IN_PROGRESS, false)
                .putLong(KEY_DEFERRED_SINCE_MS, 0L)
                .commit(),
        ) { "rebuild state not durable; runs again next start" }
    }

    /**
     * Stamp the selected network done: the cache from here on is written by this build, so there
     * is nothing to rebuild. [forgetFloor] drops the floor hints too, for a different wallet
     * (create, recover, seed change); a manual rebuild of the same wallet keeps them.
     */
    fun markNotNeeded(context: Context, forgetFloor: Boolean = false): Boolean {
        val e = statePrefs(context).edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(KEY_IN_PROGRESS, false)
            .putLong(KEY_DEFERRED_SINCE_MS, 0L)
        if (forgetFloor) e.remove(KEY_FLOOR_HINT).remove(KEY_FLOOR_TIME).remove(KEY_ROOM_CLEAR_PENDING)
        return e.commit()
    }

    /** True while a rebuild has started clearing and not finished (the cache may be partial). */
    fun isInProgress(context: Context): Boolean = statePrefs(context).getBoolean(KEY_IN_PROGRESS, false)

    /** The pending floor height for the compact-filter scan, 0 = none. */
    fun floorHint(context: Context): Long = statePrefs(context).getLong(KEY_FLOOR_HINT, 0L)

    /** The floor time for the native checkpoint anchor (unix seconds), 0 = none. */
    fun floorTime(context: Context): Long = statePrefs(context).getLong(KEY_FLOOR_TIME, 0L)

    /** The floor height has been carried into `cf_birth_height`; forget it. */
    fun clearFloorHint(context: Context) {
        statePrefs(context).edit().remove(KEY_FLOOR_HINT).commit()
    }

    private fun publish(outcome: Outcome): Outcome {
        _lastOutcome.value = outcome
        return outcome
    }

    private fun log(msg: String) {
        runCatching { android.util.Log.i(TAG, msg) }
    }

    /**
     * Arm a run for the next process start, keeping the floors: `done` unset, `in_progress` set.
     * Used by the manual rebuild BEFORE it clears, so whatever a writer puts back between its clear
     * and the process exit is cleared again by the runner at the next start. Returns whether the
     * commit landed.
     */
    fun armRerun(context: Context): Boolean =
        statePrefs(context).edit()
            .putBoolean(KEY_DONE, false)
            .putBoolean(KEY_IN_PROGRESS, true)
            .commit()

    /**
     * After a run, clear this network's Room `transactions` table once (it backs DigiAsset history
     * and would keep rows for the discarded records). [clear] runs first; the flag is dropped only
     * after it returned, so a clear that throws is retried at the next start. Returns whether a
     * clear ran. Rows refill as the scan re-finds the transactions.
     */
    suspend fun clearRoomTransactionsIfPending(context: Context, clear: suspend () -> Unit): Boolean {
        val p = statePrefs(context)
        if (!p.getBoolean(KEY_ROOM_CLEAR_PENDING, false)) return false
        val ok = runCatching { clear() }
            .onFailure { runCatching { android.util.Log.w(TAG, "transaction table clear failed; retried next start", it) } }
            .isSuccess
        if (!ok) return false
        p.edit().remove(KEY_ROOM_CLEAR_PENDING).commit()
        log("cleared the transaction table after the history rebuild")
        return true
    }

    /** Test seam: forget what this process has evaluated. */
    internal fun resetProcessStateForTest() {
        evaluated.clear()
        _lastOutcome.value = null
    }

    private const val KEY_SAVED_TRANSACTIONS = "saved_transactions"
}

/**
 * Clear the transaction cache and everything the chain rebuild re-derives, for the selected
 * network: the tx cache, the filter-header chain, the saved block headers, `has_synced`,
 * `last_balance`, the CF scan ledger and its surfaced band. Shared by the manual
 * [io.digibyte.core.WalletManager.rebuildFromChainRescan] and [HistoryRebuildOnUpgrade] so the two
 * cannot drift. It does NOT touch recorded sends ([OutgoingTxStore]) or `cf_birth_height`; the
 * manual path handles those itself. Synchronous (`commit`): callers may end the process next.
 * Returns false when the prefs commit did not land or a store file is still on disk.
 */
fun clearTransactionCacheForRebuild(context: Context): Boolean {
    val suffix = networkSuffix(context)
    val committed = context.getSharedPreferences("dgb_sync_data$suffix", Context.MODE_PRIVATE).edit()
        .remove("saved_transactions")   // the tx graph — re-derived from chain
        .remove("saved_filter_headers") // CF chain re-anchors at the floor
        .remove("saved_blocks")         // legacy key belt-and-suspenders — file store is authoritative now
        .remove("saved_blocks_tip")
        .remove("has_synced")
        .remove("last_balance")
        .commit()
    FilterHeaderStore.delete(context) // the file-backed CF-header chain (synchronous)
    SavedBlockStore.delete(context)   // and the file-backed saved-blocks window (I2 fix)
    // …and the file-backed CF SCAN LEDGER. This is load-bearing, not tidiness
    // (paced-convoy fetch, spec Part E / GATE 3(iii)): on the next start startSync() Inits
    // the native ledger fresh at `abandonedBelow = 0`, and SyncService then feeds whatever
    // survives here straight into restoreCfScanLedger(), which Parses the OLD
    // `abandonedBelow` right back over that Init. `abandonedBelow` is a monotonic hard floor
    // clamping every CF request (BRCFScanLedger.c:433/600/666), so leaving the blob in place
    // means the CF path can NEVER re-cover an abandoned band — the "a full rescan re-covers
    // it" half of the recovery guarantee would be a lie, and the B2 valve's residual (it can
    // only prove refusal by the peers it is connected to, so a servable height CAN be
    // abandoned) would become permanent silent loss instead of a recoverable inconvenience.
    CfScanLedgerStore.delete(context)
    // The surfaced band goes with it: after the re-Init `abandonedBelow` really is 0, so there
    // is nothing left to recover and nothing to nag about.
    CfAbandonmentStore.clear(context)
    // Read back: a store file that is still there means the clear did not land.
    val filesGone = listOf(FilterHeaderStore.file(context), SavedBlockStore.file(context), CfScanLedgerStore.file(context))
        .none { runCatching { it.exists() }.getOrDefault(true) }
    return committed && filesGone
}
