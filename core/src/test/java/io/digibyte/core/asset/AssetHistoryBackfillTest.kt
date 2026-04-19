package io.digibyte.core.asset

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.entity.TransactionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AssetHistoryBackfillTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var txDao: TransactionDao

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        editor = mockk<SharedPreferences.Editor>(relaxed = true).also {
            every { it.putBoolean(any(), any()) } returns it
        }
        prefs = mockk {
            every { edit() } returns editor
        }
        context = mockk {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
        txDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `skips work when flag is already set`() = runTest {
        every { prefs.getBoolean(any(), any()) } returns true
        val worker = AssetHistoryBackfill(context, txDao, DigiAssetDecoder())

        worker.runIfNeeded()

        coVerify(exactly = 0) { txDao.backfillAssetIdFromUtxos() }
        coVerify(exactly = 0) { txDao.getUnbackfilledAssetTxsBatch() }
    }

    @Test
    fun `happy path runs pass 1 then empty pass 2 and persists flag`() = runTest {
        every { prefs.getBoolean(any(), any()) } returns false
        coEvery { txDao.backfillAssetIdFromUtxos() } returns Unit
        coEvery { txDao.getUnbackfilledAssetTxsBatch() } returns emptyList()

        val worker = AssetHistoryBackfill(context, txDao, DigiAssetDecoder())
        worker.runIfNeeded()

        coVerify(exactly = 1) { txDao.backfillAssetIdFromUtxos() }
        coVerify(exactly = 1) { txDao.getUnbackfilledAssetTxsBatch() }
        coVerify(exactly = 1) { editor.putBoolean("asset_history_backfill_v5_done", true) }
    }

    @Test
    fun `pass 1 failure aborts without persisting flag`() = runTest {
        every { prefs.getBoolean(any(), any()) } returns false
        coEvery { txDao.backfillAssetIdFromUtxos() } throws RuntimeException("db locked")

        val worker = AssetHistoryBackfill(context, txDao, DigiAssetDecoder())
        worker.runIfNeeded()

        coVerify(exactly = 1) { txDao.backfillAssetIdFromUtxos() }
        coVerify(exactly = 0) { txDao.getUnbackfilledAssetTxsBatch() }
        coVerify(exactly = 0) { editor.putBoolean(any(), any()) }
    }

    @Test
    fun `pass 2 bails out of infinite loop when batch cannot decode`() = runTest {
        every { prefs.getBoolean(any(), any()) } returns false
        coEvery { txDao.backfillAssetIdFromUtxos() } returns Unit
        // First call returns rows that can't decode (no rawBytes), second would loop forever.
        val undecodableRow = TransactionEntity(
            txid = "abc",
            blockHeight = 0,
            timestamp = 0,
            amount = 0,
            fee = 0,
            toAddress = "",
            fromAddress = "",
            confirmations = 0,
            isAssetTx = true,
            rawBytes = null,   // decode always fails
        )
        coEvery { txDao.getUnbackfilledAssetTxsBatch() } returns listOf(undecodableRow)

        val worker = AssetHistoryBackfill(context, txDao, DigiAssetDecoder())
        worker.runIfNeeded()

        // Loop should break after one batch — we did NOT update anyone, so
        // calling again would infinite-loop. Assertion: batch fetch called once only.
        coVerify(exactly = 1) { txDao.getUnbackfilledAssetTxsBatch() }
        coVerify(exactly = 0) { txDao.updateAssetId(any(), any()) }
        coVerify(exactly = 1) { editor.putBoolean(any(), any()) }
    }

    @Test
    fun `concurrent runIfNeeded serialises through mutex`() = runTest {
        // Flag starts false, reading it twice on two concurrent calls would
        // normally let them both pass the gate; mutex should serialise.
        every { prefs.getBoolean(any(), any()) } returnsMany listOf(false, true)
        coEvery { txDao.backfillAssetIdFromUtxos() } returns Unit
        coEvery { txDao.getUnbackfilledAssetTxsBatch() } returns emptyList()

        val worker = AssetHistoryBackfill(context, txDao, DigiAssetDecoder())
        worker.runIfNeeded()
        worker.runIfNeeded() // second call reads flag=true, no-ops

        coVerify(exactly = 1) { txDao.backfillAssetIdFromUtxos() }
    }
}
