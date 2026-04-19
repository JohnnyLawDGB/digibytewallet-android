package io.digibyte

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import io.digibyte.core.ipfs.IpfsClient
import io.digibyte.service.SyncWorker
import io.digibyte.ui.asset.IpfsCacheKeyer
import io.digibyte.ui.asset.IpfsFetcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class DigiByteApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Injected for Coil's image loader — reuses the app-wide Tor-aware
    // OkHttp client for https:// image URLs and routes ipfs:// URIs through
    // the hash-verifying IpfsClient.
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var ipfsClient: IpfsClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleBackgroundSync()
    }

    /**
     * Coil 2.x singleton image loader. Called lazily on first image request,
     * which happens after Application.onCreate completes Hilt injection, so
     * [okHttpClient] and [ipfsClient] are guaranteed populated by this point.
     *
     * IPFS paths are memory-only (no disk cache) because Coil doesn't re-run
     * the Fetcher on disk hits — verified bytes wouldn't be re-authenticated
     * on read. IPFS images are rare (one per asset), so fetch-once-per-session
     * is acceptable. HTTPS images use Coil's default disk cache.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                add(IpfsFetcher.Factory(ipfsClient))
                add(IpfsCacheKeyer())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache(null)
            .build()

    /**
     * Register a periodic WorkManager job that performs a header-only
     * background sync every 15 minutes when connected to the network.
     * KEEP policy ensures we never queue duplicates across app restarts.
     */
    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
