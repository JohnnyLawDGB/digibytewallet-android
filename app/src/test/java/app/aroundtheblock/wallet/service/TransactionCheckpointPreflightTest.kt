package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.core.WalletTxPersister
import app.aroundtheblock.wallet.core.sync.SavedTransactionsCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TX-CKPT-CAP, the sync-start side: a restore recorded as short (fewer checkpointed transactions
 * loaded than the checkpoint declared; SavedTransactionsCheckpoint.recordRestore) rebuilds the
 * headers, filters and ledger from the birth floor, even when the stored frontier is healthy; a
 * complete restore on that frontier resumes near the tip. The SyncService wiring is held by
 * reading its source, as the sibling preflight gates do.
 */
class TransactionCheckpointPreflightTest {

    /** What SyncService's CF preflight decides (its expression, over the same inputs). */
    private fun preflight(shortRestoreRecorded: Boolean, ledger: ByteArray?, blocks: ByteArray?, wasSynced: Boolean) =
        transactionCheckpointResetReason(shortRestoreRecorded)
            ?: cfRestoreResetReason(ledger, blocks, wasSynced, hasTransactionCheckpoint = true)

    private val healthyLedger = ledger(scannedThrough = 24_000_000, requestedThrough = 24_000_100, start = 23_999_000)
    private val healthyWindow = savedBlocks(24_000_150, 23_999_900)

    @Test fun `a complete restore on a healthy frontier resumes near the tip`() {
        assertNull(preflight(false, healthyLedger, healthyWindow, wasSynced = true))
    }

    @Test fun `a short restore on the same frontier rebuilds from the birth floor`() {
        assertEquals(
            CfRestoreResetReason.TRANSACTION_CHECKPOINT_SHORT,
            preflight(true, healthyLedger, healthyWindow, wasSynced = true),
        )
        // ...and whatever else the stored state says: the shortfall is decided first.
        assertEquals(CfRestoreResetReason.TRANSACTION_CHECKPOINT_SHORT, preflight(true, null, null, wasSynced = false))
    }

    @Test fun `a checkpoint past the old reader limit that loaded nothing is short`() {
        // 10,001 declared, 0 loaded: the shape a wallet past 10,000 transactions restored as before.
        val blob = byteArrayOf(0x11, 0x27, 0, 0) + ByteArray(16)
        val declared = SavedTransactionsCheckpoint.declaredCount(blob)
        assertEquals(10_001L, declared)
        assertTrue(SavedTransactionsCheckpoint.isShort(declared, 0))
        assertEquals(
            CfRestoreResetReason.TRANSACTION_CHECKPOINT_SHORT,
            preflight(SavedTransactionsCheckpoint.isShort(declared, 0), healthyLedger, healthyWindow, wasSynced = true),
        )
        assertNull(preflight(SavedTransactionsCheckpoint.isShort(declared, 10_001), healthyLedger, healthyWindow, wasSynced = true))
    }

    @Test fun `SyncService checks the short restore first and answers it with the birth-floor rebuild`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText()
        val decision = Regex(
            """val cfResetReason = transactionCheckpointResetReason\(shortRestoreDeclared != null\)\s*\?: cfRestoreResetReason\("""
        ).find(src)
        assertTrue("the CF preflight no longer checks the short-restore record first", decision != null)
        val read = src.indexOf("prefs.contains(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE)")
        assertTrue("the preflight no longer reads the short-restore record", read in 1 until decision!!.range.first)

        val reset = src.indexOf("if (cfResetReason != null) {", decision.range.last)
        assertTrue("no reset block follows the decision", reset > 0)
        val block = src.substring(reset, src.indexOf("blockBytes = null", reset))
        for (step in listOf(
            ".remove(\"saved_blocks\")", ".remove(\"saved_filter_headers\")", ".remove(\"has_synced\")",
            ".remove(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE)",
            "CfScanLedgerStore.delete(this@SyncService)",
            "val birth = NativeBridge.getWalletBirthCheckpointHeight()",
            "putLong(\"cf_birth_height\", birth)",
        )) assertTrue("the rebuild no longer does: $step", block.contains(step))
        // The record goes in the same commit that drops the frontier, never before it.
        val commit = block.substring(block.indexOf("prefs.edit()"), block.indexOf(".commit()") + 1)
        assertTrue("the record must be cleared in the frontier's commit",
            commit.contains(".remove(SavedTransactionsCheckpoint.KEY_SHORT_RESTORE)") && commit.contains(".remove(\"has_synced\")"))
    }

    // (b) the above-limit caller: when the writer refuses (getSerializedTransactions returns null for a
    // wallet above the limit), the gate is shut, and SyncService writes neither the scan ledger nor
    // has_synced behind it.
    @Test fun `a refused checkpoint shuts the gate in front of the ledger and has_synced`() {
        assertEquals(false, checkpointGateOpen(WalletTxPersister.Result.REFUSED_OVER_LIMIT))
        assertEquals(false, checkpointGateOpen(WalletTxPersister.Result.FAILED))
        assertEquals(false, checkpointGateOpen(WalletTxPersister.Result.ABORTED))
        assertEquals(true, checkpointGateOpen(WalletTxPersister.Result.WRITTEN))
        assertEquals(true, checkpointGateOpen(WalletTxPersister.Result.EMPTY))

        val src = File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText()
        val gate = src.substring(src.indexOf("private fun persistWalletTransactionsCheckpoint()"))
            .let { it.substring(0, it.indexOf("}.getOrDefault(false)")) }
        assertTrue("the gate no longer goes through the one writer",
            gate.contains("val persister = WalletTxPersister(this)") &&
                gate.contains("persister.persistCheckpoint { stoodDownForWipe }"))
        assertTrue("the gate no longer stops on a refused checkpoint",
            gate.contains("if (!checkpointGateOpen(result) || stoodDownForWipe) return@runCatching false"))
        assertTrue("the marker must follow the gate", gate.indexOf("checkpointGateOpen") < gate.indexOf("transactions_checkpointed"))
        // Every ledger write sits behind the gate, and has_synced's writer refuses without it.
        val ledgerWrites = Regex("""CfScanLedgerStore\.write\(""").findAll(src).map { it.range.first }.toList()
        assertTrue("no ledger writes found: re-anchor the gate", ledgerWrites.isNotEmpty())
        for (at in ledgerWrites) {
            val before = src.substring(maxOf(0, at - 300), at)
            assertTrue("a scan-ledger write is not behind the checkpoint gate", before.contains("if (persistWalletTransactionsCheckpoint())"))
        }
        val completion = src.substring(src.indexOf("private fun persistSyncCompletionState(): Boolean {"))
        assertTrue("has_synced no longer waits for the checkpoint",
            completion.trimStart().lines()[1].trim() == "if (!persistWalletTransactionsCheckpoint()) return false")
    }

    private fun ledger(scannedThrough: Int, requestedThrough: Int, start: Int): ByteArray = ByteArray(32).also {
        putU32(it, 0, 0x43464c31)
        putU32(it, 4, 3)
        putU32(it, 8, start)
        putU32(it, 12, scannedThrough)
        putU32(it, 16, requestedThrough)
        putU32(it, 20, 0)
        putU32(it, 24, 0)
        putU32(it, 28, 0)
    }

    private fun savedBlocks(vararg heights: Int): ByteArray = ByteArray(4 + heights.size * 8).also {
        putU32(it, 0, heights.size)
        heights.forEachIndexed { index, height ->
            putU32(it, 4 + index * 8, 0)
            putU32(it, 8 + index * 8, height)
        }
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
