package io.digibyte.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.digibyte.core.*
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.tor.TorManager
import io.digibyte.core.db.WalletDatabase
import io.digibyte.core.db.dao.*
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.HubWebSocket
import io.digibyte.core.digiid.DigiIdManager
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.ipfs.IpfsClient
import io.digibyte.core.security.*
import okhttp3.OkHttpClient
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideTorManager(@ApplicationContext context: Context): TorManager = TorManager(context)

    @Provides @Singleton
    fun provideKeyStoreManager(@ApplicationContext context: Context): KeyStoreManager = KeyStoreManager(context)

    @Provides @Singleton
    fun providePinManager(@ApplicationContext context: Context): PinManager = PinManager(context)

    @Provides @Singleton
    fun provideBiometricAuth(): BiometricAuth = BiometricAuth()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context, ksm: KeyStoreManager): WalletDatabase {
        return try {
            provideDatabaseInner(context, ksm)
        } catch (e: Exception) {
            // If ANYTHING fails during DB init, wipe stale data and try fresh.
            // The wallet seed is in its own SharedPreferences — not lost.
            android.util.Log.e("AppModule", "DB init failed, wiping stale data and retrying: ${e.message}", e)
            io.digibyte.StaleDataWiper.wipeAll(context)
            provideDatabaseInner(context, ksm)
        }
    }

    private fun provideDatabaseInner(context: Context, ksm: KeyStoreManager): WalletDatabase {
        // Per-network DB file (e.g. "wallet.db" mainnet / "wallet_testnet.db"
        // testnet) so a testnet session never opens the mainnet DB, and vice
        // versa. dgb_db_key stays unsuffixed/shared — the same passphrase
        // encrypts either network's DB file, no cross-network trust issue.
        val dbFileName = "wallet${networkSuffix(context)}.db"
        val dbFile = context.getDatabasePath(dbFileName)
        val prefs = context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE)

        val hasKey = prefs.contains("encrypted_key")
        val dbExists = dbFile.exists()
        val legacyConfirmed = prefs.getBoolean(LEGACY_KEY_CONFIRMED, false)
        val key = databaseKeyPlan(hasKey, dbExists, legacyConfirmed) { probeLegacyPassphrase(context, dbFileName) }
        android.util.Log.i("AppModule", "DB init: dbExists=$dbExists hasKey=$hasKey key=$key")
        if (key == DatabaseKey.NEW_AFTER_DISCARD) discardUnopenableDatabase(context, dbFileName)
        if (key == DatabaseKey.LEGACY && !legacyConfirmed) {
            // The legacy passphrase has just opened this file: later starts do not ask it again.
            prefs.edit().putBoolean(LEGACY_KEY_CONFIRMED, true).apply()
        }

        val passphrase: ByteArray = when (key) {
            DatabaseKey.STORED -> {
                // Existing install: decrypt the stored passphrase using the wallet key.
                // The wallet key no longer requires user authentication, so this works
                // on all API levels without UserNotAuthenticatedException.
                android.util.Log.i("AppModule", "Decrypting stored DB passphrase")
                val stored = prefs.getString("encrypted_key", "")!!
                val parts = stored.split(":")
                if (parts.size != 2) throw IllegalStateException("Corrupt DB key format")

                // Handle both old dedicated DB key and wallet key
                val alias = prefs.getString("db_key_alias", null)
                if (alias != null) {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    val key = keyStore.getKey(alias, null)
                        ?: throw IllegalStateException("Keystore key '$alias' missing")
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                        javax.crypto.spec.GCMParameterSpec(128, hexToBytes(parts[1])))
                    cipher.doFinal(hexToBytes(parts[0]))
                } else {
                    ksm.decrypt(EncryptedData(hexToBytes(parts[0]), hexToBytes(parts[1])))
                }
            }
            DatabaseKey.LEGACY, DatabaseKey.LEGACY_UNCONFIRMED -> {
                // Legacy DB from earlier version — use hardcoded passphrase
                android.util.Log.i("AppModule", "Legacy DB — using hardcoded passphrase")
                LEGACY_DB_PASSPHRASE.toByteArray()
            }
            DatabaseKey.NEW, DatabaseKey.NEW_AFTER_DISCARD -> newDatabaseKey(ksm, prefs)
        }

        return WalletDatabase.create(context, passphrase, dbFileName)
    }

    private const val LEGACY_DB_PASSPHRASE = "digibyte-wallet-db"

    /** Which key the wallet database opens with at start. See [databaseKeyPlan]. */
    internal enum class DatabaseKey { STORED, LEGACY, LEGACY_UNCONFIRMED, NEW, NEW_AFTER_DISCARD }

    /**
     * STORED when a key is stored. With no stored key, a database file is one of three:
     *  - a file the legacy passphrase opens — an install from before keys were stored: LEGACY. Once
     *    that is known ([legacyConfirmed], recorded the first time it opens) it is not asked again,
     *    so such an install opens its database once per start, not twice;
     *  - a file that answers it is not a database under the legacy passphrase: no key on this device
     *    opens it any more (a completed wipe removed its key, and a session still running at the
     *    time wrote the file back). It is discarded and a NEW database made, so the start opens; it
     *    held only what the chain and the wallet rebuild: NEW_AFTER_DISCARD;
     *  - a file whose probe failed for any other reason — locked, a disk or space failure, a file
     *    that could not be opened at all — which says nothing about its key: kept and opened with
     *    the legacy passphrase as before, and nothing recorded: LEGACY_UNCONFIRMED.
     * No file and no key: a NEW database. [probeLegacy] is asked only about a file with no stored
     * key and no record that the legacy passphrase opens it.
     */
    internal fun databaseKeyPlan(
        hasStoredKey: Boolean,
        databaseExists: Boolean,
        legacyConfirmed: Boolean,
        probeLegacy: () -> LegacyProbe,
    ): DatabaseKey = when {
        hasStoredKey -> DatabaseKey.STORED
        !databaseExists -> DatabaseKey.NEW
        legacyConfirmed -> DatabaseKey.LEGACY
        else -> when (probeLegacy()) {
            LegacyProbe.OPENS -> DatabaseKey.LEGACY
            LegacyProbe.NOT_A_DATABASE -> DatabaseKey.NEW_AFTER_DISCARD
            LegacyProbe.UNDECIDED -> DatabaseKey.LEGACY_UNCONFIRMED
        }
    }

    /** What trying the legacy passphrase on a database file answered. See [legacyProbeAnswer]. */
    internal enum class LegacyProbe { OPENS, NOT_A_DATABASE, UNDECIDED }

    /**
     * Reads a probe's failure (null: it opened). Only the file's own answer that it is not a
     * database under the key it was opened with — SQLite's code 26, as the cipher library words it,
     * anywhere in the chain of causes — is NOT_A_DATABASE. Anything else says nothing about the key:
     * UNDECIDED.
     */
    internal fun legacyProbeAnswer(failure: Throwable?): LegacyProbe {
        if (failure == null) return LegacyProbe.OPENS
        val fileAnswered = generateSequence(failure) { it.cause }
            .take(MAX_CAUSES_READ)
            .any { it is android.database.sqlite.SQLiteException && it.message?.let(NOT_A_DATABASE_MESSAGE::containsMatchIn) == true }
        return if (fileAnswered) LegacyProbe.NOT_A_DATABASE else LegacyProbe.UNDECIDED
    }

    private const val MAX_CAUSES_READ = 8
    private val NOT_A_DATABASE_MESSAGE = Regex("""\bcode 26\b|file is not a database""")

    /** In dgb_db_key: the legacy passphrase has opened this install's database file. A new key clears it. */
    private const val LEGACY_KEY_CONFIRMED = "legacy_key_confirmed"

    /**
     * Tries the legacy passphrase on the database file the way the app opens it (Room over the
     * cipher helper), then closes it again. An SQLite failure is read by [legacyProbeAnswer]; any
     * other failure is left to [provideDatabase]'s own recovery.
     */
    private fun probeLegacyPassphrase(context: Context, dbFileName: String): LegacyProbe {
        val probe = WalletDatabase.create(context, LEGACY_DB_PASSPHRASE.toByteArray(), dbFileName)
        val failure: android.database.sqlite.SQLiteException? = try {
            probe.openHelper.writableDatabase
            null
        } catch (e: android.database.sqlite.SQLiteException) {
            e
        } finally {
            runCatching { probe.close() }
        }
        val answer = legacyProbeAnswer(failure)
        if (failure != null) {
            android.util.Log.w("AppModule", "DB init: legacy passphrase probe answered $answer (${failure.javaClass.simpleName})")
        }
        return answer
    }

    /** Delete a database file that no key on this device opens, with its journal files. */
    private fun discardUnopenableDatabase(context: Context, dbFileName: String) {
        for (suffix in listOf("", "-journal", "-shm", "-wal")) {
            val file = context.getDatabasePath(dbFileName + suffix)
            if (file.exists() && !file.delete()) {
                android.util.Log.w("AppModule", "DB init: could not delete ${file.name}")
            }
        }
        android.util.Log.w("AppModule", "DB init: discarded a database file no key on this device opens")
    }

    /** New database: a random passphrase, stored encrypted under the wallet key. */
    private fun newDatabaseKey(ksm: KeyStoreManager, prefs: android.content.SharedPreferences): ByteArray {
        android.util.Log.i("AppModule", "New install — generating DB passphrase")
        ksm.createKey()
        val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = ksm.encrypt(newPassphrase)
        prefs.edit()
            .putString("encrypted_key",
                "${bytesToHex(encrypted.ciphertext)}:${bytesToHex(encrypted.iv)}")
            // The file this key opens is not a legacy one: a record that one was goes with it.
            .remove(LEGACY_KEY_CONFIRMED)
            .apply()
        return newPassphrase
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Provides fun provideTransactionDao(db: WalletDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideUtxoDao(db: WalletDatabase): UtxoDao = db.utxoDao()
    @Provides fun provideHeaderDao(db: WalletDatabase): HeaderDao = db.headerDao()
    @Provides fun providePeerDao(db: WalletDatabase): PeerDao = db.peerDao()
    @Provides fun providePriceCacheDao(db: WalletDatabase): PriceCacheDao = db.priceCacheDao()
    @Provides fun provideWalletConfigDao(db: WalletDatabase): io.digibyte.core.db.dao.WalletConfigDao = db.walletConfigDao()

    @Provides @Singleton
    fun provideUtxoManager(utxoDao: UtxoDao): UtxoManager = UtxoManager(utxoDao)

    @Provides @Singleton
    fun provideCoinSelector(): CoinSelector = CoinSelector()

    @Provides @Singleton
    fun provideOutgoingTxStore(@ApplicationContext context: Context): io.digibyte.core.OutgoingTxStore =
        io.digibyte.core.OutgoingTxStore(context)

    @Provides @Singleton
    fun provideWalletTxPersister(@ApplicationContext context: Context): io.digibyte.core.WalletTxPersister =
        io.digibyte.core.WalletTxPersister(context)

    @Provides @Singleton
    fun provideTransactionBuilder(
        cs: CoinSelector,
        um: UtxoManager,
        outgoing: io.digibyte.core.OutgoingTxStore,
        persister: io.digibyte.core.WalletTxPersister,
    ): TransactionBuilder =
        TransactionBuilder(cs, um, outgoing, persister)

    @Provides @Singleton
    fun provideDgbNodeClient(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ): io.digibyte.core.reconcile.DgbNodeClient =
        io.digibyte.core.reconcile.DgbNodeClient(context, client)

    @Provides @Singleton
    fun provideUtxoSource(
        nodeClient: io.digibyte.core.reconcile.DgbNodeClient,
    ): io.digibyte.core.recovery.UtxoSource =
        io.digibyte.core.recovery.ReconcileBackendUtxoSource(nodeClient)

    @Provides @Singleton
    fun provideRecoveryScanService(
        utxoSource: io.digibyte.core.recovery.UtxoSource,
    ): io.digibyte.core.recovery.RecoveryScanService =
        io.digibyte.core.recovery.RecoveryScanService(utxoSource)

    // AssetManager is injected here for clearStuckSends()'s dead-send phantom
    // asset-row cleanup. No Hilt cycle: AssetManager (and its own deps — DAOs,
    // AssetMetadataService, the network client) never depend on WalletManager,
    // so this is a one-directional edge in the graph.
    @Provides @Singleton
    fun provideWalletManager(
        @ApplicationContext context: Context,
        ksm: KeyStoreManager,
        um: UtxoManager,
        am: AssetManager,
        identitySessions: io.digibyte.IdentitySessionEraser,
    ): WalletManager =
        WalletManager(context, ksm, um, assetManager = am, identitySessions = identitySessions)

    /**
     * The app-side half of a wallet wipe (Hub login held in memory, DigiStamp web session),
     * handed to [WalletManager] so ONE wipe covers them whichever screen starts it. Both
     * clients are taken lazily: nothing here needs them until a wipe runs, and WalletManager
     * must not start constructing the Hub client as a side effect of being created.
     */
    @Provides @Singleton
    fun provideIdentitySessionEraser(
        @ApplicationContext context: Context,
        digiScopeClient: dagger.Lazy<DigiScopeClient>,
        hubWebSocket: dagger.Lazy<HubWebSocket>,
    ): io.digibyte.IdentitySessionEraser =
        io.digibyte.IdentitySessionEraser(context, digiScopeClient, hubWebSocket)

    /**
     * Seed seam for the recovery flow. Delegates to the existing seed store via
     * [WalletManager.loadBip39Seed], which decrypts the stored mnemonic and
     * converts it once to the 64-byte BIP39 seed (zeroing the mnemonic). The
     * RecoverFundsViewModel owns and zeros the returned seed.
     */
    @Provides @Singleton
    fun provideSeedProvider(walletManager: WalletManager): io.digibyte.core.recovery.SeedProvider =
        io.digibyte.core.recovery.SeedProvider { walletManager.loadBip39Seed() }

    @Provides @Singleton
    fun providePriceProvider(dao: PriceCacheDao, client: OkHttpClient): PriceProvider =
        PriceProvider(dao, okHttpFetcher(client))

    @Provides fun provideAssetMetadataDao(db: WalletDatabase): AssetMetadataDao = db.assetMetadataDao()
    @Provides fun provideDigiIdHistoryDao(db: WalletDatabase): DigiIdHistoryDao = db.digiIdHistoryDao()

    @Provides @Singleton
    fun provideIpfsClient(client: OkHttpClient): IpfsClient = IpfsClient(client)

    @Provides @Singleton
    fun provideAssetMetadataService(
        ipfsClient: IpfsClient,
        dao: AssetMetadataDao,
        assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
    ): AssetMetadataService =
        AssetMetadataService(ipfsClient, dao, assetNetworkClient)

    /**
     * Multi-endpoint asset network client with a per-endpoint circuit breaker.
     *
     * digistamp leads deliberately. An asset whose issuance carries no metadata hash can only
     * get a name from getAssetData, and that fallback had NOWHERE to go: digiscope answers its
     * asset route with `500 getassetdata error: Invalid params`, and digistamp — which answers
     * correctly — was not in the rotation at all. On device that showed as an asset rendering
     * as a bare `La4WAqZf…`, supply and divisibility present (those come from the on-chain
     * header) but no name, description or issuer.
     *
     * digiscope stays as fallback rather than being replaced. Depending on one host is the
     * state that produced the outage; two providers speaking the same shapes is the point.
     */
    @Provides @Singleton
    fun provideAssetNetworkClient(
        okHttpClient: OkHttpClient,
    ): io.digibyte.core.asset.network.AssetNetworkClient =
        io.digibyte.core.asset.network.MultiEndpointAssetClient(
            endpoints = listOf(
                io.digibyte.core.asset.network.DigistampAssetClient(baseClient = okHttpClient),
                io.digibyte.core.asset.network.DigiScopeAssetClient(baseClient = okHttpClient),
                io.digibyte.core.asset.network.DigiAssetsNetClient(baseClient = okHttpClient),
            )
        )

    @Provides @Singleton
    fun provideDigiIdManager(client: OkHttpClient, historyDao: DigiIdHistoryDao, digiScopeClient: DigiScopeClient): DigiIdManager =
        DigiIdManager(client, historyDao, digiScopeClient)

    @Provides @Singleton
    fun provideDigiScopeClient(
        client: OkHttpClient,
        @ApplicationContext context: Context
    ): DigiScopeClient = DigiScopeClient(client, context)

    @Provides @Singleton
    fun provideHubWebSocket(client: OkHttpClient, digiScopeClient: DigiScopeClient): HubWebSocket =
        HubWebSocket(client, digiScopeClient)

    @Provides fun provideCachedMessageDao(db: WalletDatabase): CachedMessageDao = db.cachedMessageDao()

    @Provides fun provideAssetProvenanceDao(db: WalletDatabase):
        io.digibyte.core.db.dao.AssetProvenanceDao = db.assetProvenanceDao()

    /** Persistent memory for the DigiAsset parent-walk. A process-lifetime store would leave a
     *  deep transfer chain re-walking from zero after every restart, which is how a transferred
     *  asset ended up with no name and no artwork. */
    @Provides @Singleton
    fun provideProvenanceStore(
        dao: io.digibyte.core.db.dao.AssetProvenanceDao,
    ): io.digibyte.core.asset.ProvenanceStore =
        io.digibyte.core.asset.RoomProvenanceStore(dao)

    @Provides @Singleton
    fun provideAssetManager(
        utxoDao: UtxoDao,
        transactionDao: TransactionDao,
        metadataDao: AssetMetadataDao,
        metadataService: AssetMetadataService,
        assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
        outgoing: io.digibyte.core.OutgoingTxStore,
        persister: io.digibyte.core.WalletTxPersister,
        provenanceStore: io.digibyte.core.asset.ProvenanceStore,
    ): AssetManager = AssetManager(
        utxoDao = utxoDao,
        transactionDao = transactionDao,
        metadataDao = metadataDao,
        metadataService = metadataService,
        assetNetworkClient = assetNetworkClient,
        outgoingTxStore = outgoing,
        walletTxPersister = persister,
        provenanceStore = provenanceStore,
    )

    @Provides @Singleton
    fun provideAssetHistoryBackfill(
        @ApplicationContext context: Context,
        transactionDao: TransactionDao,
    ): io.digibyte.core.asset.AssetHistoryBackfill =
        io.digibyte.core.asset.AssetHistoryBackfill(
            context,
            transactionDao,
            io.digibyte.core.asset.DigiAssetDecoder()
        )
}
