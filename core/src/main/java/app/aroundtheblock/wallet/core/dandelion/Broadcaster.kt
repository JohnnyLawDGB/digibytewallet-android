package app.aroundtheblock.wallet.core.dandelion

import app.aroundtheblock.wallet.core.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Single broadcast entry point for the wallet. Stems the tx to one Dandelion node
 * when enabled + available, else floods (today's behavior). On a stem that really went to a
 * capable peer (not the bridge's flood fallback, see [StemResult]) it arms a random embargo: if the network hasn't relayed the tx back by the deadline it
 * self-fluffs, so a transaction is never stranded for the sake of privacy.
 *
 * A process-lifetime [SupervisorJob] scope owns the embargo timers (they fire
 * 10–30s after the send, outliving any UI coroutine). The scope holds no Android
 * context, so there's nothing to leak. The decision logic lives in the pure,
 * unit-tested [shouldStem]/[shouldFluffAfterEmbargo]/[embargoDelayMs] functions.
 *
 * Source of truth for the on/off setting is the C core ([NativeBridge.hasDandelionPeer]
 * already returns `dandelionEnabled && a capable peer is connected`); [dandelionEnabled]
 * here is a belt-and-suspenders Kotlin mirror kept in sync by [applySetting].
 */
object Broadcaster {

    private val embargoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()

    /** The native calls broadcast() makes; a JVM test substitutes a fake (the library is absent there). */
    @Volatile
    internal var bridge: BroadcastBridge = NativeBroadcastBridge

    /** Txids stemmed by this process whose embargo has not finished. */
    private val embargoPending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True while this process's embargo for [txid] is still running (see [shouldSweepRepublish]). */
    fun isEmbargoPending(txid: String): Boolean = txid in embargoPending

    /** Mirrors the user's Dandelion setting. False in every new process until
     *  [applySetting] runs — at sync start from the saved pref, and on the settings toggle. */
    @Volatile
    var dandelionEnabled: Boolean = false

    /** Apply the user's Dandelion setting: the Kotlin mirror and the native gate
     *  ([NativeBridge.setDandelionEnabled], authoritative) together. The mirror is set
     *  first, so a native failure cannot leave it stale. */
    fun applySetting(enabled: Boolean) {
        dandelionEnabled = enabled
        try { NativeBridge.setDandelionEnabled(enabled) } catch (_: Throwable) { /* re-applied on next sync start */ }
    }

    /** Broadcast a signed tx. Returns the txid on success, or null on failure. */
    fun broadcast(signedTx: ByteArray): String? {
        if (shouldStem(dandelionEnabled, bridge.hasDandelionPeer())) {
            val result = bridge.publishTransactionStem(signedTx)
            if (result != null) {
                // Arm only on a real stem (core BRDandelionEmbargo.h: "armed when the stem
                // returns 1"). stemmed=false: the capable peer dropped between the bridge's
                // check and the stem, and the bridge has already flooded the send. An embargo
                // there would only fluff it to every peer a second time at the deadline, and a
                // publish below would do the same now, so return the txid as it is.
                if (result.stemmed) armEmbargo(result.txid)
                return result.txid
            }
            // null: the bridge handled nothing (no capable peer connected when it checked, or
            // the tx did not parse), so publish it plainly.
        }
        return bridge.publishTransaction(signedTx)
    }

    private fun armEmbargo(txid: String) {
        val delayMs = embargoDelayMs(rng.nextDouble())
        embargoPending.add(txid)
        embargoScope.launch {
            try {
                delay(delayMs)
                // If the relay count can't be read, assume it propagated (don't double-send).
                val relays = try { bridge.getRelayCount(txid) } catch (_: Throwable) { 1 }
                if (shouldFluffAfterEmbargo(relays)) {
                    try { bridge.fluffTransaction(txid) } catch (_: Throwable) { /* best effort */ }
                }
            } finally {
                embargoPending.remove(txid)
            }
        }
    }
}

/** What [NativeBridge.publishTransactionStem] did with a send: [txid] is the send's txid, and
 *  [stemmed] is true only when it went to a Dandelion-capable peer (the core's stem returned 1).
 *  False means the bridge found no capable peer at the stem itself and flooded the send. */
data class StemResult(val txid: String, val stemmed: Boolean)

/** The native calls [Broadcaster] makes. */
internal interface BroadcastBridge {
    fun hasDandelionPeer(): Boolean
    /** Null when the bridge handled nothing (no capable peer before it parsed the tx, or a
     *  failure): the caller publishes. Otherwise the tx is registered and broadcast. */
    fun publishTransactionStem(signedTx: ByteArray): StemResult?
    fun publishTransaction(signedTx: ByteArray): String?
    fun getRelayCount(txid: String): Int
    fun fluffTransaction(txid: String)
}

internal object NativeBroadcastBridge : BroadcastBridge {
    override fun hasDandelionPeer(): Boolean = NativeBridge.hasDandelionPeer()
    override fun publishTransactionStem(signedTx: ByteArray): StemResult? {
        val stemmed = IntArray(1)
        val txid = NativeBridge.publishTransactionStem(signedTx, stemmed) ?: return null
        return StemResult(txid, stemmed[0] == 1)
    }
    override fun publishTransaction(signedTx: ByteArray): String? = NativeBridge.publishTransaction(signedTx)
    override fun getRelayCount(txid: String): Int = NativeBridge.getRelayCount(txid)
    override fun fluffTransaction(txid: String) = NativeBridge.fluffTransaction(txid)
}
