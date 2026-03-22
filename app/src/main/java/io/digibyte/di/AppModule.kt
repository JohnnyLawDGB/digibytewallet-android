package io.digibyte.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.digibyte.core.*
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.db.WalletDatabase
import io.digibyte.core.db.dao.*
import io.digibyte.core.digiscope.DigiScopeClient
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
    fun provideKeyStoreManager(): KeyStoreManager = KeyStoreManager()

    @Provides @Singleton
    fun providePinManager(@ApplicationContext context: Context): PinManager = PinManager(context)

    @Provides @Singleton
    fun provideBiometricAuth(): BiometricAuth = BiometricAuth()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context, ksm: KeyStoreManager): WalletDatabase {
        val dbFile = context.getDatabasePath("wallet.db")
        val prefs = context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE)

        val passphrase: ByteArray = when {
            prefs.contains("encrypted_key") -> {
                // Existing install: decrypt the stored random passphrase
                val stored = prefs.getString("encrypted_key", "")!!
                val parts = stored.split(":")
                val encrypted = EncryptedData(
                    ciphertext = hexToBytes(parts[0]),
                    iv = hexToBytes(parts[1])
                )
                ksm.decrypt(encrypted)
            }
            dbFile.exists() -> {
                // Phase 1 upgrade: DB already exists with the old hardcoded passphrase.
                // Re-keying an SQLCipher DB through Room's abstraction layer requires
                // low-level PRAGMA rekey calls that bypass Room's lifecycle — this is
                // fragile and risks DB corruption. Compromise: continue using the legacy
                // passphrase for existing installs and store a flag to document the state.
                // Security improvement (random Keystore-derived passphrase) applies to
                // all new installs going forward.
                prefs.edit().putBoolean("legacy_passphrase", true).apply()
                "digibyte-wallet-db".toByteArray()
            }
            else -> {
                // New install: generate a random 32-byte passphrase, encrypt it with
                // an Android Keystore AES-256-GCM key, and persist the encrypted blob.
                ksm.createKey()
                val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val encrypted = ksm.encrypt(newPassphrase)
                prefs.edit()
                    .putString(
                        "encrypted_key",
                        "${bytesToHex(encrypted.ciphertext)}:${bytesToHex(encrypted.iv)}"
                    )
                    .apply()
                newPassphrase
            }
        }

        return WalletDatabase.create(context, passphrase)
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
    fun provideTransactionBuilder(cs: CoinSelector, um: UtxoManager): TransactionBuilder =
        TransactionBuilder(cs, um)

    @Provides @Singleton
    fun provideWalletManager(ksm: KeyStoreManager, um: UtxoManager): WalletManager =
        WalletManager(ksm, um)

    @Provides @Singleton
    fun providePriceProvider(dao: PriceCacheDao, client: OkHttpClient): PriceProvider =
        PriceProvider(dao, okHttpFetcher(client))

    @Provides fun provideAssetMetadataDao(db: WalletDatabase): AssetMetadataDao = db.assetMetadataDao()
    @Provides fun provideDigiIdHistoryDao(db: WalletDatabase): DigiIdHistoryDao = db.digiIdHistoryDao()

    @Provides @Singleton
    fun provideIpfsClient(client: OkHttpClient): IpfsClient = IpfsClient(client)

    @Provides @Singleton
    fun provideAssetMetadataService(ipfsClient: IpfsClient, dao: AssetMetadataDao): AssetMetadataService =
        AssetMetadataService(ipfsClient, dao)

    @Provides @Singleton
    fun provideDigiIdManager(client: OkHttpClient, historyDao: DigiIdHistoryDao): DigiIdManager =
        DigiIdManager(client, historyDao)

    @Provides @Singleton
    fun provideDigiScopeClient(client: OkHttpClient): DigiScopeClient = DigiScopeClient(client)

    @Provides @Singleton
    fun provideAssetManager(
        utxoDao: UtxoDao,
        transactionDao: TransactionDao,
        metadataDao: AssetMetadataDao,
        metadataService: AssetMetadataService
    ): AssetManager = AssetManager(utxoDao, transactionDao, metadataDao, metadataService)
}
