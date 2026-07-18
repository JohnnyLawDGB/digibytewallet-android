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
        // Owned row for a DIFFERENT txid at the SAME vout (2) as the row that
        // gets deleted below — proves the delete is scoped by txid, not just
        // vout: a coincidental vout match on another transaction must survive.
        val otherHolding = UtxoEntity(
            otherTxid, 2, ownedScript, 6000, 1000,
            isAsset = true, assetId = assetId, assetQuantity = 10
        )
        // A real, unrelated holding of a different asset — must survive.
        val realHolding = UtxoEntity(
            origTxid, 0, ownedScript, 6000, 1000,
            isAsset = true, assetId = otherAssetId, assetQuantity = 10
        )
        // Owned DGB-change output of the SAME dead tx — is_asset = 0, exactly
        // the shape of a normal asset send's change back to our own wallet.
        // deleteAssetUtxo is is_asset = 1 scoped, so attempting to delete this
        // row is a no-op: it must survive AND must not be counted in the
        // returned deleted-rows total (the bug this test guards against —
        // the old code did `deleted++` unconditionally per attempt, over-
        // reporting 2 when only the one real asset row was removed).
        val dgbChangeRow = UtxoEntity(
            deadTxid, 3, ownedScript, 99300, 1000,
            isAsset = false
        )
        utxoDao.insertAll(listOf(deadChange, deadRecipientMarker, otherHolding, realHolding, dgbChangeRow))

        val beforeBalance = utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity
        assertEquals(50L, beforeBalance) // 20 (phantom change) + 20 (phantom recipient) + 10 (other holding)

        val outputs = listOf(
            "0|700|$recipientScriptHex",
            "1|0|$opReturnScriptHex",
            "2|700|$ownedScriptHex",
            "3|99300|$ownedScriptHex",
        )
        val ownedScriptHexes = setOf(ownedScriptHex)

        val deletedCount = assetManager.clearDeadAssetSend(deadTxid, ownedScriptHexes, outputs)
        // Exactly ONE asset row was actually removed — (deadTxid,3) is a
        // same-owner, same-txid attempt that no-ops (is_asset = 0), and must
        // NOT inflate the count to 2.
        assertEquals(1, deletedCount)

        val remaining = utxoDao.getAllAssetUtxosNow()
        assertFalse("(deadTxid,2) should be deleted", remaining.any { it.txid == deadTxid && it.vout == 2 })
        assertTrue("(deadTxid,0) non-owned marker must survive", remaining.any { it.txid == deadTxid && it.vout == 0 })
        assertTrue("(otherTxid,2) same-vout-different-txid must survive", remaining.any { it.txid == otherTxid && it.vout == 2 })
        assertTrue("(origTxid,0) must survive", remaining.any { it.txid == origTxid && it.vout == 0 })

        // The DGB-change row is is_asset = 0 so it never appears in the
        // asset-rows query at all — confirm it's untouched via the DGB
        // balance path instead.
        val dgbBalanceAfter = utxoDao.getSpendableDigiByteUtxosNow()
        assertTrue(
            "(deadTxid,3) DGB-change row must survive untouched",
            dgbBalanceAfter.any { it.txid == deadTxid && it.vout == 3 }
        )

        val afterBalance = utxoDao.getAssetBalances().first().first { it.assetId == assetId }.totalQuantity
        assertEquals(30L, afterBalance)
        assertEquals(20L, beforeBalance - afterBalance)

        val otherBalance = utxoDao.getAssetBalances().first().first { it.assetId == otherAssetId }.totalQuantity
        assertEquals(10L, otherBalance)
    }
}
