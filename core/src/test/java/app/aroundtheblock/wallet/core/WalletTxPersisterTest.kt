package app.aroundtheblock.wallet.core

import android.content.Context
import app.aroundtheblock.wallet.core.sync.SavedTransactionsCheckpoint
import app.aroundtheblock.wallet.core.sync.SavedTransactionsStore
import app.aroundtheblock.wallet.core.sync.fakeContext
import io.mockk.every
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TX-CKPT-CAP: the one checkpoint writer. Above the shared limit the native writer returns null; the
 * result is REFUSED_OVER_LIMIT, which does not open the checkpoint gate (so SyncService writes no
 * scan ledger and no `has_synced`), the previous checkpoint stays, and a dgb_diag breadcrumb is
 * written once when the refusal starts.
 */
class WalletTxPersisterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ctx(): Context = fakeContext().also { c -> every { c.filesDir } returns tmp.newFolder() }

    private class Fake(var count: Long, var blob: ByteArray?) : WalletTxPersister.Source {
        override fun transactionCount() = count
        override fun serialized() = blob
    }

    private val blob = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 5, 0, 0, 0, 6, 0, 0, 0, 0x42)

    @Test fun `a normal set is written and opens the gate`() {
        val c = ctx()
        val r = WalletTxPersister(c, Fake(1, blob)).persistCheckpoint()
        assertEquals(WalletTxPersister.Result.WRITTEN, r)
        assertTrue(r.landed)
        assertArrayEquals(blob, SavedTransactionsStore.load(c))
    }

    @Test fun `no transactions is nothing to save and opens the gate`() {
        val r = WalletTxPersister(ctx(), Fake(0, null)).persistCheckpoint()
        assertEquals(WalletTxPersister.Result.EMPTY, r)
        assertTrue(r.landed)
    }

    // (b) the above-limit caller
    @Test fun `above the limit the writer refuses, the gate stays shut and the old checkpoint stays`() {
        val c = ctx()
        val fake = Fake(1, blob)
        val p = WalletTxPersister(c, fake, nowMs = { 1_234L })
        assertEquals(WalletTxPersister.Result.WRITTEN, p.persistCheckpoint())

        fake.count = SavedTransactionsCheckpoint.MAX_RECORDS + 1
        fake.blob = null   // what getSerializedTransactions returns when BRWalletSerializeTransactions refuses
        val r = p.persistCheckpoint()
        assertEquals(WalletTxPersister.Result.REFUSED_OVER_LIMIT, r)
        assertFalse("the gate must stay shut: no ledger write, no has_synced", r.landed)
        assertFalse(p.persist())
        assertArrayEquals("the previous checkpoint is kept", blob, SavedTransactionsStore.load(c))

        val diag = c.getSharedPreferences("dgb_diag", Context.MODE_PRIVATE)
        assertTrue(diag.getBoolean(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED, false))
        assertEquals(SavedTransactionsCheckpoint.MAX_RECORDS + 1, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_COUNT, 0))
        assertEquals(1_234L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_AT_MS, 0))
    }

    @Test fun `the refusal breadcrumb is written once, not per attempt`() {
        val c = ctx()
        var now = 10L
        val fake = Fake(SavedTransactionsCheckpoint.MAX_RECORDS + 1, null)
        val p = WalletTxPersister(c, fake, nowMs = { now })
        p.persistCheckpoint()
        now = 20L; fake.count += 7
        p.persistCheckpoint()
        val diag = c.getSharedPreferences("dgb_diag", Context.MODE_PRIVATE)
        assertEquals(10L, diag.getLong(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED_AT_MS, 0))
        // back under the limit: a write lands and the state ends
        fake.count = 1; fake.blob = blob
        assertEquals(WalletTxPersister.Result.WRITTEN, p.persistCheckpoint())
        assertFalse(diag.getBoolean(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED, true))
    }

    @Test fun `a null set under the limit is a failure, not a refusal`() {
        val c = ctx()
        val r = WalletTxPersister(c, Fake(5, null)).persistCheckpoint()
        assertEquals(WalletTxPersister.Result.FAILED, r)
        assertFalse(c.getSharedPreferences("dgb_diag", Context.MODE_PRIVATE).contains(SavedTransactionsCheckpoint.KEY_DIAG_REFUSED))
    }

    @Test fun `an abort after the read writes nothing`() {
        val c = ctx()
        val r = WalletTxPersister(c, Fake(1, blob)).persistCheckpoint { true }
        assertEquals(WalletTxPersister.Result.ABORTED, r)
        assertFalse(SavedTransactionsStore.file(c).exists())
    }
}
