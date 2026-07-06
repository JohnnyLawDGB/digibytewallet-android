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
        PriceCacheEntity::class,
        AssetMetadataEntity::class,
        DigiIdHistoryEntity::class,
        CachedMessageEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun utxoDao(): UtxoDao
    abstract fun headerDao(): HeaderDao
    abstract fun peerDao(): PeerDao
    abstract fun priceCacheDao(): PriceCacheDao
    abstract fun walletConfigDao(): WalletConfigDao
    abstract fun assetMetadataDao(): AssetMetadataDao
    abstract fun digiIdHistoryDao(): DigiIdHistoryDao
    abstract fun cachedMessageDao(): CachedMessageDao

    companion object {
        /**
         * @param dbFileName Room DB file name. Defaults to the historical
         *   mainnet name `"wallet.db"` so any caller that doesn't pass one
         *   (tests, older call sites) is behavior-identical to before the
         *   per-network toggle existed. The app's DI module passes a
         *   `_testnet`-suffixed name when the testnet network is selected,
         *   so a testnet session never opens the mainnet DB file.
         */
        fun create(context: Context, passphrase: ByteArray, dbFileName: String = "wallet.db"): WalletDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                WalletDatabase::class.java,
                dbFileName
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
