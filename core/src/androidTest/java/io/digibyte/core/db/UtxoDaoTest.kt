package io.digibyte.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UtxoDaoTest {
    private lateinit var db: WalletDatabase
    private lateinit var utxoDao: UtxoDao

    @Before
    fun setup() {
        // Use in-memory database WITHOUT SQLCipher for testing
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java
        ).allowMainThreadQueries().build()
        utxoDao = db.utxoDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun getSpendableDigiByteUtxos_excludesAssetUtxos() = runTest {
        val dgbUtxo = UtxoEntity("tx1", 0, byteArrayOf(), 100000, 1000, isAsset = false)
        val assetUtxo = UtxoEntity("tx2", 0, byteArrayOf(), 600, 1000, isAsset = true, assetId = "asset123")
        utxoDao.insertAll(listOf(dgbUtxo, assetUtxo))

        val spendable = utxoDao.getSpendableDigiByteUtxos().first()
        assertEquals(1, spendable.size)
        assertEquals("tx1", spendable[0].txid)
    }

    @Test
    fun getAssetUtxos_returnsOnlyAssets() = runTest {
        val dgbUtxo = UtxoEntity("tx1", 0, byteArrayOf(), 100000, 1000, isAsset = false)
        val assetUtxo = UtxoEntity("tx2", 0, byteArrayOf(), 600, 1000, isAsset = true, assetId = "asset123")
        utxoDao.insertAll(listOf(dgbUtxo, assetUtxo))

        val assets = utxoDao.getAssetUtxos().first()
        assertEquals(1, assets.size)
        assertEquals("tx2", assets[0].txid)
    }

    @Test
    fun getDigiByteBalance_sumsOnlyNonAssetUnspent() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("tx1", 0, byteArrayOf(), 100000, 1000, isAsset = false),
            UtxoEntity("tx2", 0, byteArrayOf(), 200000, 1000, isAsset = false),
            UtxoEntity("tx3", 0, byteArrayOf(), 600, 1000, isAsset = true)
        ))
        val balance = utxoDao.getDigiByteBalance().first()
        assertEquals(300000L, balance)
    }

    @Test
    fun markSpent_excludesFromBalance() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("tx1", 0, byteArrayOf(), 100000, 1000),
            UtxoEntity("tx2", 0, byteArrayOf(), 200000, 1000)
        ))
        utxoDao.markSpent("tx1", 0)

        val balance = utxoDao.getDigiByteBalance().first()
        assertEquals(200000L, balance)
    }

    // ---- Authoritative asset-UTXO reconcile (fixes 30-shown-for-10 bug) ----

    /** Reproduces the exact inflation the user hit: one true holding of 10
     *  plus two never-pruned phantom rows (recipient markers of a stuck send +
     *  a recover-resend) sum to 30. Replacing with the node's authoritative set
     *  {the true holding} must prune the phantoms and heal the balance to 10. */
    @Test
    fun replaceAssetUtxos_prunesPhantomsAndHealsInflatedBalance() = runTest {
        val assetId = "Ua1inflated"
        val real = UtxoEntity("real", 0, byteArrayOf(), 6000, 1000, isAsset = true, assetId = assetId, assetQuantity = 10)
        val phantom1 = UtxoEntity("stuckSend", 0, byteArrayOf(), 6000, 1001, isAsset = true, assetId = assetId, assetQuantity = 10)
        val phantom2 = UtxoEntity("recoverResend", 0, byteArrayOf(), 6000, 1002, isAsset = true, assetId = assetId, assetQuantity = 10)
        utxoDao.insertAll(listOf(real, phantom1, phantom2))

        // Pre-condition: the bug — balance reads 30 for a true holding of 10.
        assertEquals(30L, utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity)

        // The node reports only the genuinely-unspent holding at our address.
        utxoDao.replaceAssetUtxos(listOf(real))

        val healed = utxoDao.getAssetBalances().first()
        assertEquals(1, healed.size)
        assertEquals(10L, healed.first { it.assetId == assetId }.totalQuantity)
        assertEquals(1, utxoDao.getAssetUtxos().first().size)
    }

    /** The prune MUST be scoped to asset rows — plain-DGB UTXOs (the user's
     *  spendable balance) are reconciled by the native wallet, not this path,
     *  and must never be deleted by an asset refresh. */
    @Test
    fun replaceAssetUtxos_neverTouchesPlainDgbUtxos() = runTest {
        val dgb = UtxoEntity("dgb", 0, byteArrayOf(), 500000, 1000, isAsset = false)
        val staleAsset = UtxoEntity("staleAsset", 0, byteArrayOf(), 6000, 1000, isAsset = true, assetId = "Uold", assetQuantity = 5)
        val freshAsset = UtxoEntity("freshAsset", 0, byteArrayOf(), 6000, 1001, isAsset = true, assetId = "Unew", assetQuantity = 7)
        utxoDao.insertAll(listOf(dgb, staleAsset))

        utxoDao.replaceAssetUtxos(listOf(freshAsset))

        // DGB balance untouched; stale asset pruned; fresh asset present.
        assertEquals(500000L, utxoDao.getDigiByteBalance().first())
        val assets = utxoDao.getAssetUtxos().first()
        assertEquals(1, assets.size)
        assertEquals("freshAsset", assets[0].txid)
    }

    /** deleteAssetUtxosNotIn keeps only the listed keys, among asset rows. */
    @Test
    fun deleteAssetUtxosNotIn_scopedToAssetsOnly() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("dgb", 0, byteArrayOf(), 500000, 1000, isAsset = false),
            UtxoEntity("keep", 0, byteArrayOf(), 6000, 1000, isAsset = true, assetId = "Uk", assetQuantity = 3),
            UtxoEntity("drop", 1, byteArrayOf(), 6000, 1000, isAsset = true, assetId = "Ud", assetQuantity = 9)
        ))
        utxoDao.deleteAssetUtxosNotIn(listOf("keep:0"))

        assertEquals(500000L, utxoDao.getDigiByteBalance().first()) // DGB survives
        val assets = utxoDao.getAssetUtxos().first()
        assertEquals(1, assets.size)
        assertEquals("keep", assets[0].txid)
    }
}
