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

    // ---- Sovereign asset-UTXO reconcile primitives (fix 30-shown-for-10) ----

    // Distinct scripts so "owned vs phantom" is decidable by scriptPubKey, the
    // way AssetManager's sovereign prune decides it.
    private val ownedScript = byteArrayOf(0x76, 0x01, 0x02)
    private val recipientScript = byteArrayOf(0x00, 0x14, 0x09)

    /** Reproduces the exact inflation the user hit and heals it the way the
     *  sovereign prune does: one true holding of 10 at an OWNED script, plus two
     *  phantom recipient-marker rows of 10 at a NOT-owned script, sum to 30.
     *  Deleting the phantom outpoints (what refreshAssetUtxosFromNetwork does
     *  after filtering getAllAssetUtxosNow by ownership) heals the balance to 10
     *  and never touches the real holding. */
    @Test
    fun sovereignPrune_deletesPhantomsAndHealsInflatedBalance() = runTest {
        val assetId = "Ua1inflated"
        val real = UtxoEntity("real", 0, ownedScript, 6000, 1000, isAsset = true, assetId = assetId, assetQuantity = 10)
        val phantom1 = UtxoEntity("stuckSend", 0, recipientScript, 6000, 1001, isAsset = true, assetId = assetId, assetQuantity = 10)
        val phantom2 = UtxoEntity("recoverResend", 0, recipientScript, 6000, 1002, isAsset = true, assetId = assetId, assetQuantity = 10)
        utxoDao.insertAll(listOf(real, phantom1, phantom2))

        // Pre-condition: the bug — balance reads 30 for a true holding of 10.
        assertEquals(30L, utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity)

        // Sovereign prune: rows whose script isn't in the owned set are phantoms.
        val ownedHex = setOf(ownedScript.joinToString("") { "%02x".format(it) })
        val phantoms = utxoDao.getAllAssetUtxosNow().filter {
            it.scriptPubKey.isNotEmpty() &&
                it.scriptPubKey.joinToString("") { b -> "%02x".format(b) } !in ownedHex
        }
        assertEquals(2, phantoms.size)
        for (p in phantoms) utxoDao.deleteAssetUtxo(p.txid, p.vout)

        val healed = utxoDao.getAssetBalances().first()
        assertEquals(1, healed.size)
        assertEquals(10L, healed.first { it.assetId == assetId }.totalQuantity)
    }

    /** getAllAssetUtxosNow returns every asset row (spent + unspent) and no
     *  plain-DGB rows — the input the sovereign prune filters over. */
    @Test
    fun getAllAssetUtxosNow_returnsAllAssetRowsExcludingDgb() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("dgb", 0, byteArrayOf(), 500000, 1000, isAsset = false),
            UtxoEntity("assetUnspent", 0, ownedScript, 6000, 1000, isAsset = true, assetId = "Uu", assetQuantity = 3),
            UtxoEntity("assetSpent", 0, ownedScript, 6000, 1000, isAsset = true, assetId = "Us", assetQuantity = 4, spent = true)
        ))
        val all = utxoDao.getAllAssetUtxosNow()
        assertEquals(2, all.size)
        assertTrue(all.none { it.txid == "dgb" })
    }

    /** deleteAssetUtxo is scoped to is_asset = 1: a plain-DGB row is untouched. */
    @Test
    fun deleteAssetUtxo_neverTouchesDgb() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("shared", 0, byteArrayOf(), 500000, 1000, isAsset = false),
            UtxoEntity("shared", 1, ownedScript, 6000, 1000, isAsset = true, assetId = "Ua", assetQuantity = 9)
        ))
        utxoDao.deleteAssetUtxo("shared", 0)   // (shared,0) is DGB — must survive
        assertEquals(500000L, utxoDao.getDigiByteBalance().first())
        utxoDao.deleteAssetUtxo("shared", 1)   // (shared,1) is the asset — removed
        assertEquals(0, utxoDao.getAssetUtxos().first().size)
    }

    /** markUnspent is the inverse of markSpent: it restores a spent asset UTXO
     *  to spendable (spent = 0), the seam a held branch will wire in to undo a
     *  dead/failed asset-send that consumed an input on the client side. */
    @Test
    fun markUnspent_restoresAssetUtxoToSpendableAndBalance() = runTest {
        val assetUtxo = UtxoEntity("tx1", 0, ownedScript, 6000, 1000, isAsset = true, assetId = "asset123", assetQuantity = 5)
        val otherAssetUtxo = UtxoEntity("tx1", 1, ownedScript, 6000, 1000, isAsset = true, assetId = "asset123", assetQuantity = 7)
        utxoDao.insertAll(listOf(assetUtxo, otherAssetUtxo))

        utxoDao.markSpent("tx1", 0)
        assertEquals(1, utxoDao.getAssetUtxos().first().size)
        assertEquals("tx1", utxoDao.getAssetUtxos().first()[0].txid)
        assertEquals(1, utxoDao.getAssetUtxos().first()[0].vout)
        assertEquals(7L, utxoDao.getAssetBalances().first().first { it.assetId == "asset123" }.totalQuantity)

        utxoDao.markUnspent("tx1", 0)

        val assets = utxoDao.getAssetUtxos().first()
        assertEquals(2, assets.size)
        assertTrue(assets.any { it.txid == "tx1" && it.vout == 0 })
        assertEquals(12L, utxoDao.getAssetBalances().first().first { it.assetId == "asset123" }.totalQuantity)
    }

    /** markUnspent is scoped to the exact (txid, vout) outpoint — a sibling
     *  vout that is still spent must stay spent. */
    @Test
    fun markUnspent_onlyAffectsExactOutpoint() = runTest {
        utxoDao.insertAll(listOf(
            UtxoEntity("tx1", 0, ownedScript, 6000, 1000, isAsset = true, assetId = "asset123", assetQuantity = 5),
            UtxoEntity("tx1", 1, ownedScript, 6000, 1000, isAsset = true, assetId = "asset123", assetQuantity = 7)
        ))
        utxoDao.markSpent("tx1", 0)
        utxoDao.markSpent("tx1", 1)
        assertEquals(0, utxoDao.getAssetUtxos().first().size)

        utxoDao.markUnspent("tx1", 0)

        val assets = utxoDao.getAssetUtxos().first()
        assertEquals(1, assets.size)
        assertEquals(0, assets[0].vout)
    }

}
