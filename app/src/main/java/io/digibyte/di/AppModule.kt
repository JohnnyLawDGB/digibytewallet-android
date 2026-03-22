package io.digibyte.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.digibyte.core.*
import io.digibyte.core.db.WalletDatabase
import io.digibyte.core.db.dao.*
import io.digibyte.core.security.*
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
        // Use a derived key from Keystore for DB encryption
        // For now, use a static passphrase — will be replaced with Keystore-derived key
        val passphrase = "digibyte-wallet-db".toByteArray()
        return WalletDatabase.create(context, passphrase)
    }

    @Provides fun provideTransactionDao(db: WalletDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideUtxoDao(db: WalletDatabase): UtxoDao = db.utxoDao()
    @Provides fun provideHeaderDao(db: WalletDatabase): HeaderDao = db.headerDao()
    @Provides fun providePeerDao(db: WalletDatabase): PeerDao = db.peerDao()
    @Provides fun providePriceCacheDao(db: WalletDatabase): PriceCacheDao = db.priceCacheDao()

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
    fun providePriceProvider(dao: PriceCacheDao): PriceProvider = PriceProvider(dao)
}
