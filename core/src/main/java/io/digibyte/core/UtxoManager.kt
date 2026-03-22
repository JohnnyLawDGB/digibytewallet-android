package io.digibyte.core

import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.flow.Flow

class UtxoManager(private val utxoDao: UtxoDao) {

    /** Flow of spendable (non-asset, unspent) DGB UTXOs */
    fun getSpendableUtxos(): Flow<List<UtxoEntity>> = utxoDao.getSpendableDigiByteUtxos()

    /** Flow of asset UTXOs (protected from coin selection) */
    fun getAssetUtxos(): Flow<List<UtxoEntity>> = utxoDao.getAssetUtxos()

    /** Flow of total DGB balance in satoshis */
    fun getBalance(): Flow<Long> = utxoDao.getDigiByteBalance()

    /** Add new UTXOs from sync */
    suspend fun addUtxos(utxos: List<UtxoEntity>) {
        utxoDao.insertAll(utxos)
    }

    /** Mark a UTXO as spent (after tx broadcast) */
    suspend fun markSpent(txid: String, vout: Int) {
        utxoDao.markSpent(txid, vout)
    }

    /** Clear all UTXOs (for wallet wipe or resync) */
    suspend fun clearAll() {
        utxoDao.deleteAll()
    }
}
