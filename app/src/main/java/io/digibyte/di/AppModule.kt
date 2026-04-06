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
            wipeStaleData(context)
            provideDatabaseInner(context, ksm)
        }
    }

    /**
     * Wipe all app data that can become stale across installs/upgrades:
     * DB files, DB key prefs, PIN, sync data. The wallet seed prefs
     * (dgb_wallet_seed) are preserved — user funds are never lost.
     */
    private fun wipeStaleData(context: Context) {
        android.util.Log.w("AppModule", "Wiping stale app data (wallet seed preserved)")
        // Delete database files
        context.getDatabasePath("wallet.db").delete()
        context.getDatabasePath("wallet.db-journal").delete()
        context.getDatabasePath("wallet.db-shm").delete()
        context.getDatabasePath("wallet.db-wal").delete()
        // Clear DB key prefs
        context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE).edit().clear().apply()
        // Clear PIN store file (EncryptedSharedPreferences)
        val pinFile = java.io.File(context.filesDir.parent, "shared_prefs/dgb_pin_store.xml")
        if (pinFile.exists()) {
            pinFile.delete()
            android.util.Log.w("AppModule", "Deleted stale PIN store")
        }
        // Clear sync data (blocks/peers will be re-downloaded)
        context.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE).edit().clear().apply()
        // Clear bloom peer cache
        context.getSharedPreferences("dgb_bloom_peers", Context.MODE_PRIVATE).edit().clear().apply()
        // Delete stale Keystore keys
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            for (alias in listOf("dgb_db_passphrase", "dgb_wallet_master")) {
                if (ks.containsAlias(alias)) {
                    ks.deleteEntry(alias)
                    android.util.Log.w("AppModule", "Deleted stale Keystore key: $alias")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AppModule", "Could not clean Keystore: ${e.message}")
        }
    }

    private fun provideDatabaseInner(context: Context, ksm: KeyStoreManager): WalletDatabase {
        val dbFile = context.getDatabasePath("wallet.db")
        val prefs = context.getSharedPreferences("dgb_db_key", Context.MODE_PRIVATE)

        android.util.Log.i("AppModule", "DB init: exists=${dbFile.exists()} hasKey=${prefs.contains("encrypted_key")} legacy=${prefs.getBoolean("legacy_passphrase", false)}")

        val passphrase: ByteArray = when {
            prefs.contains("encrypted_key") -> {
                val stored = prefs.getString("encrypted_key", "")!!
                val parts = stored.split(":")
                if (parts.size != 2) throw IllegalStateException("Corrupt DB key format")
                val alias = prefs.getString("db_key_alias", null)
                if (alias != null) {
                    android.util.Log.i("AppModule", "Decrypting DB passphrase with dedicated key: $alias")
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    val key = keyStore.getKey(alias, null)
                        ?: throw IllegalStateException("DB key '$alias' not found in Keystore")
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = javax.crypto.spec.GCMParameterSpec(128, hexToBytes(parts[1]))
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, spec)
                    cipher.doFinal(hexToBytes(parts[0]))
                } else {
                    android.util.Log.i("AppModule", "Decrypting DB passphrase with legacy wallet key")
                    val encrypted = EncryptedData(
                        ciphertext = hexToBytes(parts[0]),
                        iv = hexToBytes(parts[1])
                    )
                    ksm.decrypt(encrypted)
                }
            }
            dbFile.exists() -> {
                android.util.Log.i("AppModule", "Using legacy hardcoded passphrase for existing DB")
                prefs.edit().putBoolean("legacy_passphrase", true).apply()
                "digibyte-wallet-db".toByteArray()
            }
            else -> {
                android.util.Log.i("AppModule", "New install: generating fresh DB passphrase")
                val dbKeyAlias = "dgb_db_passphrase"
                val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                if (!keyStore.containsAlias(dbKeyAlias)) {
                    val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                        dbKeyAlias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    javax.crypto.KeyGenerator.getInstance(
                        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                    ).apply { init(spec) }.generateKey()
                }
                val key = keyStore.getKey(dbKeyAlias, null)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                val ciphertext = cipher.doFinal(newPassphrase)
                val iv = cipher.iv
                prefs.edit()
                    .putString("encrypted_key", "${bytesToHex(ciphertext)}:${bytesToHex(iv)}")
                    .putString("db_key_alias", dbKeyAlias)
                    .apply()
                android.util.Log.i("AppModule", "DB passphrase encrypted and stored")
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
    fun provideWalletManager(@ApplicationContext context: Context, ksm: KeyStoreManager, um: UtxoManager): WalletManager =
        WalletManager(context, ksm, um)

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

    @Provides @Singleton
    fun provideAssetManager(
        utxoDao: UtxoDao,
        transactionDao: TransactionDao,
        metadataDao: AssetMetadataDao,
        metadataService: AssetMetadataService
    ): AssetManager = AssetManager(utxoDao, transactionDao, metadataDao, metadataService)
}
