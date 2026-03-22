package io.digibyte.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.digibyte.core.db.dao.*
import io.digibyte.core.db.entity.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        TransactionEntity::class,
        UtxoEntity::class,
        HeaderEntity::class,
        PeerEntity::class,
        WalletConfigEntity::class,
        PriceCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun utxoDao(): UtxoDao
    abstract fun headerDao(): HeaderDao
    abstract fun peerDao(): PeerDao
    abstract fun priceCacheDao(): PriceCacheDao

    companion object {
        fun create(context: Context, passphrase: ByteArray): WalletDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                WalletDatabase::class.java,
                "wallet.db"
            )
                .openHelperFactory(factory)
                .build()
        }
    }
}
