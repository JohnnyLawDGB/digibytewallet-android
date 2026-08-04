package io.digibyte.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.isTestnet
import io.digibyte.core.networkSuffix
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
            // NEVER run against a live foreground sync. This worker ends by calling
            // NativeBridge.stopSync(), which is BRPeerManagerDisconnect() on the ONE global
            // peer manager — the same one a foreground restore is using. WorkManager periodic
            // work is not gated on process state (the only constraint here is
            // NetworkType.CONNECTED), so without this check a background catch-up can drop
            // every peer out from under a multi-hour compact-filter scan. SyncService is
            // already doing strictly more than this worker would.
            if (SyncService.foregroundSyncLive.get()) {
                return Result.success()
            }

            // A wallet must exist before any of this means anything. Native startSync()
            // returns silently when g_wallet is NULL ("startSync: wallet not initialized"),
            // which is exactly the state after Android restarts this process for the SERVICE
            // alone following a native crash — no MainActivity, no unlock, no wallet. The old
            // code ignored that, slept 30s, persisted a null transaction blob and reported
            // success, so a dead wallet left no trace it was dead. Retry instead: the next
            // attempt succeeds once the wallet is loaded.
            if (!NativeBridge.isWalletLoaded()) {
                android.util.Log.w("SyncWorker", "background catch-up skipped: no wallet loaded")
                return Result.retry()
            }

            // Refresh filter-capable peers from the seeder API (cached hourly)
            fetchBloomPeers()
            NativeBridge.startSync()
            // Allow 30 seconds for header catch-up.
            delay(SYNC_DURATION_MS)
            // Persist transactions so balance is current on next app open
            val txData = NativeBridge.getSerializedTransactions()
            if (txData != null) {
                val hex = txData.joinToString("") { "%02x".format(it) }
                applicationContext.getSharedPreferences("dgb_sync_data" + networkSuffix(applicationContext), 0)
                    .edit().putString("saved_transactions", hex).apply()
            }
            // Re-check before tearing down: SyncService can have started during our 30s
            // window — the user opening the app is exactly the event this worker is filling
            // in for — and disconnecting then would kill a sync that had just come up.
            if (!SyncService.foregroundSyncLive.get()) {
                NativeBridge.stopSync()
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun fetchBloomPeers() {
        if (isTestnet(applicationContext)) {
            // Testnet26 has no mainnet-shaped seeder infra — api.digiscope.me
            // only knows mainnet peers. The native startSync() path (and
            // SyncService.injectTestnetPeers()) already handle testnet26 peer
            // discovery via the hardcoded peers + refreshed testnet DNS
            // seeds; this background catch-up worker just calls startSync()
            // without hitting the mainnet seeder.
            return
        }
        val prefs = applicationContext.getSharedPreferences("dgb_bloom_peers" + networkSuffix(applicationContext), 0)
        val cachedJson = prefs.getString("peers_json", null)
        val lastFetch = prefs.getLong("last_fetch", 0L)
        val now = System.currentTimeMillis()

        val json: String? = if (now - lastFetch < 60 * 60 * 1000L && cachedJson != null) {
            cachedJson
        } else {
            try {
                // capability=filter: the wallet is CF-only end to end, so the
                // background catch-up worker must never source a bloom-only peer.
                val url = java.net.URL("$SEEDER_URL?capability=filter")
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
                    // services_hex (e.g. "0x44d") carries the compact-filter bit
                    // (0x40) so filter peers are tagged; absent → 0 → native
                    // CF-tagged default (INJECT_DEFAULT_SERVICES, jni_peer.c).
                    val services = parseSeederServicesHex(p.optString("services_hex", ""))
                    NativeBridge.injectPeerByIp(p.getString("ip"), p.optInt("port", 12024), services)
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        /** Duration the background sync job holds the peer connection open. */
        private const val SYNC_DURATION_MS = 30_000L

        /** Capability-aware seeder API. Returns filter-capable peers when available,
         *  falling through to bloom peers when not. */
        private const val SEEDER_URL = "https://api.digiscope.me/api/peers"

        /** Unique work name used with ExistingPeriodicWorkPolicy.KEEP. */
        const val WORK_NAME = "dgb_background_sync"
    }
}
