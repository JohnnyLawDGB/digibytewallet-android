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
            // Refresh bloom peers from seeder API (cached hourly)
            fetchBloomPeers()
            NativeBridge.startSync()
            // Allow 30 seconds for header catch-up.
            delay(SYNC_DURATION_MS)
            // Persist transactions so balance is current on next app open
            val txData = NativeBridge.getSerializedTransactions()
            if (txData != null) {
                val hex = txData.joinToString("") { "%02x".format(it) }
                applicationContext.getSharedPreferences("dgb_sync_data", 0)
                    .edit().putString("saved_transactions", hex).apply()
            }
            NativeBridge.stopSync()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun fetchBloomPeers() {
        val prefs = applicationContext.getSharedPreferences("dgb_bloom_peers", 0)
        val cachedJson = prefs.getString("peers_json", null)
        val lastFetch = prefs.getLong("last_fetch", 0L)
        val now = System.currentTimeMillis()

        val json: String? = if (now - lastFetch < 60 * 60 * 1000L && cachedJson != null) {
            cachedJson
        } else {
            try {
                val url = java.net.URL(BLOOM_SEEDER_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (conn.responseCode == 200) {
                    prefs.edit().putString("peers_json", body).putLong("last_fetch", now).apply()
                    body
                } else cachedJson
            } catch (e: Exception) { cachedJson }
        }

        if (json != null) {
            try {
                val peers = org.json.JSONObject(json).getJSONArray("peers")
                for (i in 0 until peers.length()) {
                    val p = peers.getJSONObject(i)
                    NativeBridge.injectPeerByIp(p.getString("ip"), p.optInt("port", 12024))
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        /** Duration the background sync job holds the peer connection open. */
        private const val SYNC_DURATION_MS = 30_000L

        /** Bloom seeder API URL. */
        private const val BLOOM_SEEDER_URL = "https://api.digiscope.me/api/peers/bloom"

        /** Unique work name used with ExistingPeriodicWorkPolicy.KEEP. */
        const val WORK_NAME = "dgb_background_sync"
    }
}
