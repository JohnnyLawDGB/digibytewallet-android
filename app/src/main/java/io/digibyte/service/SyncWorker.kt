package io.digibyte.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.digibyte.core.bridge.NativeBridge
import kotlinx.coroutines.delay

/**
 * WorkManager periodic job for background header-only sync.
 *
 * Runs every 15 minutes when the network is connected. The worker keeps
 * BRPeerManager active for 30 seconds — enough to pull new block headers
 * and update the local chain tip — then disconnects cleanly.
 *
 * This supplements the foreground SyncService: when the user backgrounds
 * the app and the OS eventually stops the service, WorkManager ensures
 * headers stay reasonably current.
 *
 * Requires: WorkManager 2.9+ with Hilt integration (hilt-work artifact).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            NativeBridge.startSync()
            // Allow 30 seconds for header catch-up.
            delay(SYNC_DURATION_MS)
            NativeBridge.stopSync()
            Result.success()
        } catch (e: Exception) {
            // Retry — WorkManager will back off automatically.
            Result.retry()
        }
    }

    companion object {
        /** Duration the background sync job holds the peer connection open. */
        private const val SYNC_DURATION_MS = 30_000L

        /** Unique work name used with ExistingPeriodicWorkPolicy.KEEP. */
        const val WORK_NAME = "dgb_background_sync"
    }
}
