package io.digibyte.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {
    private lateinit var db: WalletDatabase
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java
        ).allowMainThreadQueries().build()
        transactionDao = db.transactionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeEntity(txid: String, timestamp: Long = 1000L, amount: Long = 100_000L) =
        TransactionEntity(
            txid = txid,
            blockHeight = 800_000L,
            timestamp = timestamp,
            amount = amount,
            fee = 1000L,
            toAddress = "dgb1qrecipient",
            fromAddress = "dgb1qsender",
            confirmations = 6
        )

    @Test
    fun insert_andQueryById_returnsCorrectTx() = runTest {
        val tx = makeEntity("aabbcc")
        transactionDao.insert(tx)

        val result = transactionDao.getTransaction("aabbcc")
        assertNotNull(result)
        assertEquals("aabbcc", result!!.txid)
        assertEquals(100_000L, result.amount)
    }

    @Test
    fun getAllTransactions_orderedByTimestampDesc() = runTest {
        transactionDao.insertAll(listOf(
            makeEntity("tx_old", timestamp = 1000L),
            makeEntity("tx_new", timestamp = 9000L),
            makeEntity("tx_mid", timestamp = 5000L)
        ))

        val all = transactionDao.getAllTransactions().first()
        assertEquals(3, all.size)
        assertEquals("tx_new", all[0].txid)
        assertEquals("tx_mid", all[1].txid)
        assertEquals("tx_old", all[2].txid)
    }

    @Test
    fun updateConfirmations_updatesOnlyTargetTx() = runTest {
        transactionDao.insertAll(listOf(
            makeEntity("tx1"),
            makeEntity("tx2")
        ))
        transactionDao.updateConfirmations("tx1", 100)

        val tx1 = transactionDao.getTransaction("tx1")
        val tx2 = transactionDao.getTransaction("tx2")
        assertEquals(100, tx1!!.confirmations)
        assertEquals(6, tx2!!.confirmations)
    }

    @Test
    fun insert_replaceConflict_updatesExisting() = runTest {
        transactionDao.insert(makeEntity("txdupe", amount = 50_000L))
        transactionDao.insert(makeEntity("txdupe", amount = 75_000L))

        val result = transactionDao.getTransaction("txdupe")
        assertEquals(75_000L, result!!.amount)
    }

    @Test
    fun deleteAll_removesEverything() = runTest {
        transactionDao.insertAll(listOf(makeEntity("tx1"), makeEntity("tx2")))
        transactionDao.deleteAll()

        val all = transactionDao.getAllTransactions().first()
        assertTrue(all.isEmpty())
    }
}
