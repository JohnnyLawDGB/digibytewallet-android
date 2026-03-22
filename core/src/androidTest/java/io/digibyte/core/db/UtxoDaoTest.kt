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
}
