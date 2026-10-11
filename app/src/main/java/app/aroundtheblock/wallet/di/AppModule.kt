package app.aroundtheblock.wallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import app.aroundtheblock.wallet.core.*
import app.aroundtheblock.wallet.core.asset.AssetManager
import app.aroundtheblock.wallet.core.tor.TorManager
import app.aroundtheblock.wallet.core.db.WalletDatabase
import app.aroundtheblock.wallet.core.db.dao.*
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import app.aroundtheblock.wallet.core.digiid.DigiIdManager
import app.aroundtheblock.wallet.core.ipfs.AssetMetadataService
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
import app.aroundtheblock.wallet.core.security.*
import okhttp3.OkHttpClient
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

    /**
     * The wallet database. Every decision about its password — which key unwraps it, a database
     * made before passwords were stored (re-keyed), a record still under the seed's legacy key
     * (moved onto its own key), a file that cannot be opened (set aside, never deleted), a
     * Keystore that cannot be asked right now (a temporary database for this run) — is made in
     * core by DatabaseKeyProvisioner, where it is tested. Nothing here deletes a database or a key,
     * and no failure is turned into a wipe.
     */
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WalletDatabase = provideDatabaseInner(context)

    private fun provideDatabaseInner(context: Context): WalletDatabase {
        // Per-network DB file (e.g. "wallet.db" mainnet / "wallet_testnet.db"
        // testnet) so a testnet session never opens the mainnet DB, and vice
        // versa. The key record stays unsuffixed/shared — the same password
        // encrypts either network's DB file, no cross-network trust issue.
        val dbFileName = "wallet${networkSuffix(context)}.db"
        return app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner.openWalletDatabase(context, dbFileName)
    }

    @Provides fun provideTransactionDao(db: WalletDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideUtxoDao(db: WalletDatabase): UtxoDao = db.utxoDao()
    @Provides fun provideHeaderDao(db: WalletDatabase): HeaderDao = db.headerDao()
    @Provides fun providePeerDao(db: WalletDatabase): PeerDao = db.peerDao()
    @Provides fun providePriceCacheDao(db: WalletDatabase): PriceCacheDao = db.priceCacheDao()
    @Provides fun provideWalletConfigDao(db: WalletDatabase): app.aroundtheblock.wallet.core.db.dao.WalletConfigDao = db.walletConfigDao()

    @Provides @Singleton
    fun provideUtxoManager(utxoDao: UtxoDao): UtxoManager = UtxoManager(utxoDao)

    @Provides @Singleton
    fun provideCoinSelector(): CoinSelector = CoinSelector()

    @Provides @Singleton
    fun provideOutgoingTxStore(@ApplicationContext context: Context): app.aroundtheblock.wallet.core.OutgoingTxStore =
        app.aroundtheblock.wallet.core.OutgoingTxStore(context)

    @Provides @Singleton
    fun provideWalletTxPersister(@ApplicationContext context: Context): app.aroundtheblock.wallet.core.WalletTxPersister =
        app.aroundtheblock.wallet.core.WalletTxPersister(context)

    @Provides @Singleton
    fun provideTransactionBuilder(
        cs: CoinSelector,
        um: UtxoManager,
        outgoing: app.aroundtheblock.wallet.core.OutgoingTxStore,
        persister: app.aroundtheblock.wallet.core.WalletTxPersister,
    ): TransactionBuilder =
        TransactionBuilder(cs, um, outgoing, persister)

    @Provides @Singleton
    fun provideDgbNodeClient(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ): app.aroundtheblock.wallet.core.reconcile.DgbNodeClient =
        app.aroundtheblock.wallet.core.reconcile.DgbNodeClient(context, client)

    @Provides @Singleton
    fun provideUtxoSource(
        nodeClient: app.aroundtheblock.wallet.core.reconcile.DgbNodeClient,
    ): app.aroundtheblock.wallet.core.recovery.UtxoSource =
        app.aroundtheblock.wallet.core.recovery.ReconcileBackendUtxoSource(nodeClient)

    @Provides @Singleton
    fun provideRecoveryScanService(
        utxoSource: app.aroundtheblock.wallet.core.recovery.UtxoSource,
    ): app.aroundtheblock.wallet.core.recovery.RecoveryScanService =
        app.aroundtheblock.wallet.core.recovery.RecoveryScanService(utxoSource)

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
        identitySessions: app.aroundtheblock.wallet.IdentitySessionEraser,
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
    ): app.aroundtheblock.wallet.IdentitySessionEraser =
        app.aroundtheblock.wallet.IdentitySessionEraser(context, digiScopeClient, hubWebSocket)

    /**
     * Seed seam for the recovery flow. Delegates to the existing seed store via
     * [WalletManager.loadBip39Seed], which decrypts the stored mnemonic and
     * converts it once to the 64-byte BIP39 seed (zeroing the mnemonic). The
     * RecoverFundsViewModel owns and zeros the returned seed.
     */
    @Provides @Singleton
    fun provideSeedProvider(walletManager: WalletManager): app.aroundtheblock.wallet.core.recovery.SeedProvider =
        app.aroundtheblock.wallet.core.recovery.SeedProvider { walletManager.loadBip39Seed() }

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
        assetNetworkClient: app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient,
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
    ): app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient =
        app.aroundtheblock.wallet.core.asset.network.MultiEndpointAssetClient(
            endpoints = listOf(
                app.aroundtheblock.wallet.core.asset.network.DigistampAssetClient(baseClient = okHttpClient),
                app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetClient(baseClient = okHttpClient),
                app.aroundtheblock.wallet.core.asset.network.DigiAssetsNetClient(baseClient = okHttpClient),
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
        app.aroundtheblock.wallet.core.db.dao.AssetProvenanceDao = db.assetProvenanceDao()

    /** Persistent memory for the DigiAsset parent-walk. A process-lifetime store would leave a
     *  deep transfer chain re-walking from zero after every restart, which is how a transferred
     *  asset ended up with no name and no artwork. */
    @Provides @Singleton
    fun provideProvenanceStore(
        dao: app.aroundtheblock.wallet.core.db.dao.AssetProvenanceDao,
    ): app.aroundtheblock.wallet.core.asset.ProvenanceStore =
        app.aroundtheblock.wallet.core.asset.RoomProvenanceStore(dao)

    @Provides @Singleton
    fun provideAssetManager(
        utxoDao: UtxoDao,
        transactionDao: TransactionDao,
        metadataDao: AssetMetadataDao,
        metadataService: AssetMetadataService,
        assetNetworkClient: app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient,
        outgoing: app.aroundtheblock.wallet.core.OutgoingTxStore,
        persister: app.aroundtheblock.wallet.core.WalletTxPersister,
        provenanceStore: app.aroundtheblock.wallet.core.asset.ProvenanceStore,
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): AssetManager = AssetManager(
        utxoDao = utxoDao,
        transactionDao = transactionDao,
        metadataDao = metadataDao,
        metadataService = metadataService,
        assetNetworkClient = assetNetworkClient,
        outgoingTxStore = outgoing,
        walletTxPersister = persister,
        provenanceStore = provenanceStore,
        // Proofs that an asset transaction's last output carries no units, kept across restarts
        // so the startup replay does not hold that output out again.
        remainderProofs = app.aroundtheblock.wallet.core.asset.PrefsRemainderProofStore(context),
        // What each asset input holds is read from our own indexer, over the injected client
        // (Tor when enabled) with the DigiScope pins added by the client itself.
        assetStackSource = app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetClient(baseClient = okHttpClient),
    )

    @Provides @Singleton
    fun provideAssetHistoryBackfill(
        @ApplicationContext context: Context,
        transactionDao: TransactionDao,
    ): app.aroundtheblock.wallet.core.asset.AssetHistoryBackfill =
        app.aroundtheblock.wallet.core.asset.AssetHistoryBackfill(
            context,
            transactionDao,
            app.aroundtheblock.wallet.core.asset.DigiAssetDecoder()
        )
}
