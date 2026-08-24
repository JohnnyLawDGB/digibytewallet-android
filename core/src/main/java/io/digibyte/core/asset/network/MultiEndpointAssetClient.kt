package io.digibyte.core.asset.network

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Asset network client that rotates across a priority-ordered list of
 * endpoints with a per-endpoint circuit breaker.
 *
 * Pattern is borrowed from RenzoDD's `digibyte-js` MultiEndpoint Blockbook
 * client, adapted for coroutine-based suspend fns and our particular
 * endpoint set (digiscope.me, api.digiassets.net, user-configured).
 *
 * Failure semantics per method: each call tries endpoints in order, skipping
 * any that are currently "open circuit" (cooled-down after consecutive
 * failures). The first non-null response wins. If every endpoint fails, the
 * method returns null — callers get the same semantics as a single-endpoint
 * client, just with built-in failover.
 *
 * The circuit breaker is intentionally simple: an endpoint that fails N
 * times in a row is skipped for [circuitOpenMs]; any success resets the
 * counter. No exponential backoff, no half-open probes — callers retry on
 * the next user action (navigate, refresh) and naturally probe through the
 * skipped endpoints then.
 */
class MultiEndpointAssetClient(
    private val endpoints: List<AssetNetworkClient>,
    private val failureThreshold: Int = 3,
    private val circuitOpenMs: Long = 60_000L,
) : AssetNetworkClient {

    override val endpointLabel: String = "multi(${endpoints.joinToString(",") { it.endpointLabel }})"

    /** Per-endpoint state; indexed into [endpoints]. */
    private data class EndpointState(var failures: Int = 0, var openedAt: Long = 0L)

    private val states = Array(endpoints.size) { EndpointState() }
    private val mutex = Mutex()

    override suspend fun getAssetData(assetId: String): AssetDataResponse? =
        tryEach("getAssetData") { it.getAssetData(assetId) }

    override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? =
        tryEach("getAddressHistory") { it.getAddressHistory(address, limit) }

    override suspend fun getSyncState(): SyncStateResponse? =
        tryEach("getSyncState") { it.getSyncState() }

    override suspend fun getRawTransaction(txHashHex: String): ByteArray? =
        tryEach("getRawTransaction") { it.getRawTransaction(txHashHex) }

    private suspend fun <T> tryEach(label: String, block: suspend (AssetNetworkClient) -> T?): T? {
        require(endpoints.isNotEmpty()) { "MultiEndpointAssetClient requires at least one endpoint" }
        val now = System.currentTimeMillis()
        for (i in endpoints.indices) {
            // Skip endpoints whose circuit breaker is open and not yet cooled
            val state = states[i]
            if (mutex.withLock { state.failures >= failureThreshold && now - state.openedAt < circuitOpenMs }) {
                Log.d(TAG, "$label: skipping ${endpoints[i].endpointLabel} (circuit open)")
                continue
            }
            val endpoint = endpoints[i]
            try {
                val result = block(endpoint)
                if (result != null) {
                    mutex.withLock {
                        state.failures = 0
                        state.openedAt = 0L
                    }
                    return result
                }
                mutex.withLock { recordFailure(state, now, endpoint.endpointLabel, label) }
            } catch (t: Throwable) {
                mutex.withLock { recordFailure(state, now, endpoint.endpointLabel, label) }
                Log.w(TAG, "$label: ${endpoint.endpointLabel} threw", t)
            }
        }
        return null
    }

    private fun recordFailure(state: EndpointState, now: Long, endpointLabel: String, call: String) {
        state.failures++
        if (state.failures >= failureThreshold) {
            state.openedAt = now
            Log.w(
                TAG,
                "$call: $endpointLabel crossed failure threshold ($failureThreshold) — " +
                    "circuit open for ${circuitOpenMs}ms"
            )
        }
    }

    companion object {
        private const val TAG = "MultiAssetClient"
    }
}
