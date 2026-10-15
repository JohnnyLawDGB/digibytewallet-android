package app.aroundtheblock.wallet.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.aroundtheblock.wallet.core.db.dao.*
import app.aroundtheblock.wallet.core.db.entity.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** The schema version this build opens the database at. A fresh install is created here and runs
 *  none of [WALLET_DB_MIGRATIONS]; an existing install runs each step from its version up to it. */
internal const val WALLET_DB_VERSION = 12

/** Every step between schema versions, in order. [WalletDatabase.create] registers exactly these. */
internal val WALLET_DB_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12,
)

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
        CachedMessageEntity::class,
        AssetProvenanceEntity::class,
        AssetWalkFrontierEntity::class
    ],
    version = WALLET_DB_VERSION,
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
    abstract fun assetProvenanceDao(): AssetProvenanceDao

    companion object {
        /**
         * @param dbFileName Room DB file name. Defaults to the historical
         *   mainnet name `"wallet.db"` so any caller that doesn't pass one
         *   (tests, older call sites) is behavior-identical to before the
         *   per-network toggle existed. The app's DI module passes a
         *   `_testnet`-suffixed name when the testnet network is selected,
         *   so a testnet session never opens the mainnet DB file.
         */
        fun create(
            context: Context,
            passphrase: ByteArray,
            dbFileName: String = "wallet.db",
            onFirstOpenFailure: ((android.database.sqlite.SQLiteException) -> Boolean)? = null,
        ): WalletDatabase {
            System.loadLibrary("sqlcipher")
            val cipher = SupportOpenHelperFactory(passphrase)
            val factory = if (onFirstOpenFailure == null) cipher else FirstOpenRecovery(cipher, onFirstOpenFailure)
            return Room.databaseBuilder(
                context.applicationContext,
                WalletDatabase::class.java,
                dbFileName
            )
                .openHelperFactory(factory)
                .addMigrations(*WALLET_DB_MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
        }

        /** A database for this run only, in memory: nothing on disk is opened, changed or created. */
        fun createTemporary(context: Context): WalletDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, WalletDatabase::class.java).build()

        /** Opening and re-keying [file] directly with SQLCipher, outside Room. */
        fun cipherFiles(file: java.io.File): CipherFiles = SqlCipherFiles(file)
    }
}

/**
 * The first open of the database through [delegate], given one chance to set right a file that does
 * not open: [recover] answers true when it changed something (re-keyed or set the file aside) and the
 * open is tried once more. Only the first open — later failures reach their caller as they always have.
 */
internal class FirstOpenRecovery(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val recover: (android.database.sqlite.SQLiteException) -> Boolean,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            private var settled = false

            override val writableDatabase: SupportSQLiteDatabase get() = open { helper.writableDatabase }

            override val readableDatabase: SupportSQLiteDatabase get() = open { helper.readableDatabase }

            private fun open(get: () -> SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(this) {
                if (settled) return get()
                try {
                    get().also { settled = true }
                } catch (e: android.database.sqlite.SQLiteException) {
                    settled = true
                    if (!recover(e)) throw e
                    get()
                }
            }
        }
    }
}

/**
 * [CipherFiles] over SQLCipher. A probe opens the file with one password and reads its schema, then
 * closes it; nothing is written. Room is not involved, so no migration runs under a probe or a re-key.
 */
internal class SqlCipherFiles(private val file: java.io.File) : CipherFiles {

    override fun probe(password: ByteArray): OpenAnswer = openFailureAnswer(useOpen(password) { db ->
        db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
    })

    /**
     * SQLCipher's password change, then a reopen: [RekeyAnswer.REKEYED] only when every page reads
     * back under [to] (SQLite's integrity check, which decrypts and authenticates each page) and
     * [from] no longer opens the file; [RekeyAnswer.UNCHANGED] when it is still under [from].
     */
    override fun rekey(from: ByteArray, to: ByteArray): RekeyAnswer {
        val failure = useOpen(from) { db ->
            // Room keeps the database in write-ahead-log mode. Fold the log into the file and leave
            // that mode first, so the change rewrites every page of the one file; Room's next open
            // sets the mode again.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.changePassword(to)
        }
        if (failure != null) android.util.Log.w(TAG, "password change did not complete (${failure.javaClass.simpleName})")
        val underNew = checksUnder(to)
        val underOld = probe(from) == OpenAnswer.OPENS
        return when {
            underNew && !underOld -> RekeyAnswer.REKEYED
            underOld && !underNew -> RekeyAnswer.UNCHANGED
            else -> RekeyAnswer.UNKNOWN
        }
    }

    /** Opens with [password] and checks every page: true only when the integrity check answers "ok". */
    private fun checksUnder(password: ByteArray): Boolean {
        var ok = false
        val failure = useOpen(password) { db ->
            db.rawQuery("PRAGMA quick_check", null).use { c -> ok = c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext() }
        }
        return failure == null && ok
    }

    /** Opens the file with [password], runs [use], closes it. Null when all of it succeeded, else the failure. */
    private fun useOpen(password: ByteArray, use: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> Unit): Exception? {
        System.loadLibrary("sqlcipher")
        var db: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
        return try {
            db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                file.path, password, null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null,
            )
            use(db)
            null
        } catch (e: Exception) {
            // What the database said is read by openFailureAnswer; anything else says nothing about the file.
            e
        } finally {
            runCatching { db?.close() }
        }
    }

    private companion object {
        const val TAG = "WalletDatabase"
    }
}
