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

        android.util.Log.i("AppModule", "DB init: dbExists=${dbFile.exists()} hasKey=${prefs.contains("encrypted_key")}")

        val passphrase: ByteArray = when {
            prefs.contains("encrypted_key") -> {
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
            dbFile.exists() -> {
                // Legacy DB from earlier version — use hardcoded passphrase
                android.util.Log.i("AppModule", "Legacy DB — using hardcoded passphrase")
                "digibyte-wallet-db".toByteArray()
            }
            else -> {
                // New install: generate random passphrase, encrypt with wallet key
                android.util.Log.i("AppModule", "New install — generating DB passphrase")
                ksm.createKey()
                val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val encrypted = ksm.encrypt(newPassphrase)
                prefs.edit()
                    .putString("encrypted_key",
                        "${bytesToHex(encrypted.ciphertext)}:${bytesToHex(encrypted.iv)}")
                    .apply()
                newPassphrase
            }
        }

        return WalletDatabase.create(context, passphrase, dbFileName)
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
    ): WalletManager =
        WalletManager(context, ksm, um, assetManager = am)

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
