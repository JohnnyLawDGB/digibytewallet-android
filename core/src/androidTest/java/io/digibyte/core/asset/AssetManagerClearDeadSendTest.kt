package io.digibyte.core.asset

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.db.WalletDatabase
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.ipfs.IpfsClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room-backed test for [AssetManager.clearDeadAssetSend] — the helper that
 * deletes the OWNED asset-output phantom rows a dead/failed asset send left
 * behind (the asset-change marker at our own address), while never touching
 * the non-owned recipient marker, rows for other txids, or unrelated real
 * holdings.
 */
@RunWith(AndroidJUnit4::class)
class AssetManagerClearDeadSendTest {
    private lateinit var db: WalletDatabase
    private lateinit var utxoDao: UtxoDao
    private lateinit var assetManager: AssetManager

    // Distinct scripts so "owned vs not-owned" is decidable by scriptPubKey,
    // matching how AssetManager's ownership gate decides it elsewhere.
    private val ownedScriptHex = "76a914aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa88ac"
    private val recipientScriptHex = "76a914bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb88ac"
    private val opReturnScriptHex = "6a0c48656c6c6f20776f726c64"

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java
        ).allowMainThreadQueries().build()
        utxoDao = db.utxoDao()
        assetManager = AssetManager(
            utxoDao = utxoDao,
            transactionDao = db.transactionDao(),
            metadataDao = db.assetMetadataDao(),
            metadataService = AssetMetadataService(
                IpfsClient(OkHttpClient()),
                db.assetMetadataDao()
            ),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun clearDeadAssetSend_deletesOnlyOwnedRowsForTheDeadTxid() = runTest {
        val assetId = "Ua1dead"
        val otherAssetId = "Ua2other"
        val deadTxid = "deadTxid"
        val otherTxid = "otherTxid"
        val origTxid = "origTxid"

        val ownedScript = hexToBytes(ownedScriptHex)
        val recipientScript = hexToBytes(recipientScriptHex)

        // Owned asset-change row the dead send fabricated at our own address.
        val deadChange = UtxoEntity(
            deadTxid, 2, ownedScript, 700, 1000,
            isAsset = true, assetId = assetId, assetQuantity = 20
        )
        // NON-owned recipient marker of the same dead tx — must survive.
        val deadRecipientMarker = UtxoEntity(
            deadTxid, 0, recipientScript, 700, 1000,
            isAsset = true, assetId = assetId, assetQuantity = 20
        )
        // Owned row for a DIFFERENT txid, same asset — must survive (txid-scoped).
        val otherHolding = UtxoEntity(
            otherTxid, 1, ownedScript, 6000, 1000,
            isAsset = true, assetId = assetId, assetQuantity = 10
        )
        // A real, unrelated holding of a different asset — must survive.
        val realHolding = UtxoEntity(
            origTxid, 0, ownedScript, 6000, 1000,
            isAsset = true, assetId = otherAssetId, assetQuantity = 10
        )
        utxoDao.insertAll(listOf(deadChange, deadRecipientMarker, otherHolding, realHolding))

        val beforeBalance = utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity
        assertEquals(50L, beforeBalance) // 20 (phantom change) + 20 (phantom recipient) + 10 (other holding)

        val outputs = listOf(
            "0|700|$recipientScriptHex",
            "1|0|$opReturnScriptHex",
            "2|700|$ownedScriptHex",
        )
        val ownedScriptHexes = setOf(ownedScriptHex)

        val deletedCount = assetManager.clearDeadAssetSend(deadTxid, ownedScriptHexes, outputs)
        assertEquals(1, deletedCount)

        val remaining = utxoDao.getAllAssetUtxosNow()
        assertFalse("(deadTxid,2) should be deleted", remaining.any { it.txid == deadTxid && it.vout == 2 })
        assertTrue("(deadTxid,0) non-owned marker must survive", remaining.any { it.txid == deadTxid && it.vout == 0 })
        assertTrue("(otherTxid,1) must survive", remaining.any { it.txid == otherTxid && it.vout == 1 })
        assertTrue("(origTxid,0) must survive", remaining.any { it.txid == origTxid && it.vout == 0 })

        val afterBalance = utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity
        assertEquals(30L, afterBalance)
        assertEquals(20L, beforeBalance - afterBalance)

        val otherBalance = utxoDao.getAssetBalances().first().first { it.assetId == otherAssetId }.totalQuantity
        assertEquals(10L, otherBalance)
    }
}
