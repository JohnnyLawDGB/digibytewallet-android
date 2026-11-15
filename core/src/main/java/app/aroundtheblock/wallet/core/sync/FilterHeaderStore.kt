package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.WALLET_NETWORK_SUFFIXES
import app.aroundtheblock.wallet.core.networkSuffix
import java.io.File

/**
 * File-backed persistence for the BIP158 compact-filter-header chain.
 *
 * The chain is stored as RAW bytes in a plain file — NOT hex-encoded in
 * SharedPreferences.
 *
 * Rationale (2026-07-14 heap-leak fix): on a long-history wallet the filter-header
 * chain grows to tens/hundreds of MB. The old code hex-encoded it (2x the bytes)
 * and `putString()`-ed it into `dgb_sync_data` on EVERY cfheaders batch. Android's
 * `SharedPreferencesImpl` keeps every value in a process-lifetime in-memory map, so
 * that hex String was pinned and re-grown on each batch — a JVM heap leak that drove
 * old wallets to the 512MB largeHeap ceiling (GC frees nothing) → OOM-restart loop,
 * so the wallet never finished syncing. A file has no in-memory pin and no hex
 * doubling; the caller coalesces/throttles writes to at most one per interval.
 *
 * Persistence is only a restore optimization — the native chain is authoritative in
 * memory — so best-effort I/O and a dropped oversized legacy blob (re-synced) are
 * acceptable.
 *
 * STORE-DURABILITY: every write goes through [DurableFile] (tmp, fsync, rename, directory
 * fsync), reports whether it landed, and never removes the previous file when it fails.
 * Since the recreate path (`flushLiveStateBeforeRecreate`, [RecreateSequence]) restores the
 * last PERSISTED snapshot, a lost or torn file costs a resume far behind the tip.
 */
object FilterHeaderStore {
    private const val LEGACY_PREFS = "dgb_sync_data"
    private const val LEGACY_KEY = "saved_filter_headers"
    private const val FILE_BASE = "saved_filter_headers"

    /**
     * Cap for the one-time legacy-hex → file migration. A larger legacy blob is
     * dropped (the native chain re-syncs), which also avoids OOM-ing while decoding
     * a huge hex String at startup. ~16 MB hex ≈ 8 MB chain ≈ 250k headers.
     */
    internal const val MAX_LEGACY_MIGRATE_HEX = 16 * 1024 * 1024

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin")

    private fun tmpFile(ctx: Context): File = File(ctx.filesDir, FILE_BASE + networkSuffix(ctx) + ".bin.tmp")

    private fun legacyPrefs(ctx: Context) =
        ctx.getSharedPreferences(LEGACY_PREFS + networkSuffix(ctx), Context.MODE_PRIVATE)

    private val ioLock = Any()
    @Volatile private var epoch = 0L

    /** Snapshot the current generation; pass it to [write] so a concurrent [delete]
     *  (watchdog re-anchor / rescan reset) invalidates a stale in-flight write. */
    fun currentEpoch(): Long = epoch

    /**
     * Persist the chain as raw bytes, durably ([DurableFile]: tmp, fsync, rename, directory fsync) — UNLESS a
     * [delete] has run since `snapshotEpoch` was taken, in which case the write is
     * dropped so a stale pre-reset chain can't be resurrected after the file was
     * already deleted. Serialized with [delete] on [ioLock]. Returns [StoreWrite.LANDED]
     * when the file now holds [bytes], [StoreWrite.OBSOLETE] for a dropped stale snapshot (not a
     * failure), and [StoreWrite.FAILED] with the previous file kept as it was (STORE-DURABILITY).
     */
    fun write(ctx: Context, bytes: ByteArray, snapshotEpoch: Long): StoreWrite = synchronized(ioLock) {
        if (epoch != snapshotEpoch) return StoreWrite.OBSOLETE // a delete() invalidated this snapshot
        if (writeLocked(ctx, bytes)) StoreWrite.LANDED else StoreWrite.FAILED
    }

    private fun legacy(ctx: Context) = DurableFile.LegacyKey({ legacyPrefs(ctx) }, LEGACY_KEY, MAX_LEGACY_MIGRATE_HEX)

    /** STORE-DURABILITY ([DurableFile]): true only when the file now holds [bytes]; a failed write
     *  leaves the previous file as it was. A write that lands also removes a legacy key still there. */
    private fun writeLocked(ctx: Context, bytes: ByteArray): Boolean =
        DurableFile.write(file(ctx), tmpFile(ctx), bytes).also { landed -> if (landed) legacy(ctx).afterLanded() }

    /**
     * Load the chain for restore, by the one legacy-key rule ([DurableFile.LegacyKey]): a
     * readable legacy hex key (capped) is the newer copy, rewritten to the file and removed
     * once that write landed (a failed write keeps it, as the last good copy); an unusable
     * one is dropped. Then the file; then a complete tmp file (a write that synced but was
     * killed before its rename, recovered into place). Returns null if none / dropped.
     */
    fun load(ctx: Context): ByteArray? = synchronized(ioLock) {
        val f = file(ctx)
        // The legacy key first ([DurableFile.LegacyKey]): a readable one is the newer copy.
        when (val key = legacy(ctx).read()) {
            is DurableFile.LegacyKey.Read.Usable -> {
                writeLocked(ctx, key.bytes) // removes the key only if the file landed
                return key.bytes
            }
            DurableFile.LegacyKey.Read.Unusable -> legacy(ctx).remove()
            DurableFile.LegacyKey.Read.Absent -> Unit
        }
        if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        // STORE-DURABILITY: a write that synced its tmp but was killed before the rename.
        DurableFile.recoverTmp(f, tmpFile(ctx), ::isComplete)
    }

    /** Delete the persisted chain (file, tmp, and legacy prefs key). Used on re-anchor /
     *  reset. Bumps the epoch so any in-flight [write] captured before now is dropped,
     *  and is serialized with [write] on [ioLock] so it can't be resurrected. */
    fun delete(ctx: Context) {
        synchronized(ioLock) {
            epoch++
            runCatching { DurableFile.delete(file(ctx), tmpFile(ctx)) }
            runCatching { legacyPrefs(ctx).edit().remove(LEGACY_KEY).apply() }
        }
    }

    /**
     * [delete] for EVERY network's chain, not only the selected one — a full wallet reset leaves
     * no network's copy for the next wallet to load. Returns true only when, read back, no file
     * is left and the legacy key's removal was written.
     */
    fun deleteAllNetworks(ctx: Context): Boolean = synchronized(ioLock) {
        epoch++
        WALLET_NETWORK_SUFFIXES.map { net ->
            val bin = File(ctx.filesDir, "$FILE_BASE$net.bin")
            val tmp = File(ctx.filesDir, "$FILE_BASE$net.bin.tmp")
            DurableFile.delete(bin, tmp)
            val legacyGone = runCatching {
                ctx.getSharedPreferences(LEGACY_PREFS + net, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).commit()
            }.getOrDefault(false)
            !bin.exists() && !tmp.exists() && legacyGone
        }.all { it }
    }

    /**
     * Decode a hex string to bytes without the `chunked(2).map { … }` amplifier
     * (which allocates millions of 2-char Strings + boxed Bytes — a multi-GB
     * transient spike / startup OOM on a large chain). Returns null on odd length
     * or a non-hex character.
     */
    fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    /**
     * Whether [b] is a whole chain as BRCompactFilterChainSerialize writes it: magic "BCFC", version 1,
     * filter type, start height, anchor (46 bytes), then exactly `count` 32-byte headers. Only decides
     * whether a tmp file may be recovered; the native deserializer still validates what it is handed.
     */
    internal fun isComplete(b: ByteArray): Boolean {
        if (b.size < CHAIN_HEADER_LEN) return false
        if (b.u32(0) != CHAIN_MAGIC || b[4].toInt() != CHAIN_VERSION) return false
        return b.size.toLong() == CHAIN_HEADER_LEN + 32L * b.u32(42)
    }

    internal const val CHAIN_MAGIC = 0x46434243L // "BCFC", BRCompactFilterChain.c
    internal const val CHAIN_VERSION = 1
    internal const val CHAIN_HEADER_LEN = 46L

    private fun ByteArray.u32(at: Long): Long = (this[at.toInt()].toLong() and 0xffL) or
        ((this[at.toInt() + 1].toLong() and 0xffL) shl 8) or
        ((this[at.toInt() + 2].toLong() and 0xffL) shl 16) or
        ((this[at.toInt() + 3].toLong() and 0xffL) shl 24)
}
