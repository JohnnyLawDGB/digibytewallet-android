package io.digibyte.core.dandelion

import io.digibyte.core.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Single broadcast entry point for the wallet. Stems the tx to one Dandelion node
 * when enabled + available, else floods (today's behavior). On a stem it arms a
 * random embargo: if the network hasn't relayed the tx back by the deadline it
 * self-fluffs, so a transaction is never stranded for the sake of privacy.
 *
 * A process-lifetime [SupervisorJob] scope owns the embargo timers (they fire
 * 10–30s after the send, outliving any UI coroutine). The scope holds no Android
 * context, so there's nothing to leak. The decision logic lives in the pure,
 * unit-tested [shouldStem]/[shouldFluffAfterEmbargo]/[embargoDelayMs] functions.
 *
 * Source of truth for the on/off setting is the C core ([NativeBridge.hasDandelionPeer]
 * already returns `dandelionEnabled && a capable peer is connected`); [dandelionEnabled]
 * here is a belt-and-suspenders Kotlin mirror the settings toggle keeps in sync.
 */
object Broadcaster {

    private val embargoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()

    /** Mirrors the user's Dandelion setting; updated by the settings toggle, which
     *  also calls [NativeBridge.setDandelionEnabled] (the authoritative gate). */
    @Volatile
    var dandelionEnabled: Boolean = true

    /** Broadcast a signed tx. Returns the txid on success, or null on failure. */
    fun broadcast(signedTx: ByteArray): String? {
        if (shouldStem(dandelionEnabled, NativeBridge.hasDandelionPeer())) {
            val txid = NativeBridge.publishTransactionStem(signedTx)
            if (txid != null) {
                armEmbargo(txid)
                return txid
            }
            // stem returned null (no capable peer at the last instant) → flood below
        }
        return NativeBridge.publishTransaction(signedTx)
    }

    private fun armEmbargo(txid: String) {
        val delayMs = embargoDelayMs(rng.nextDouble())
        embargoScope.launch {
            delay(delayMs)
            // If the relay count can't be read, assume it propagated (don't double-send).
            val relays = try { NativeBridge.getRelayCount(txid) } catch (_: Throwable) { 1 }
            if (shouldFluffAfterEmbargo(relays)) {
                try { NativeBridge.fluffTransaction(txid) } catch (_: Throwable) { /* best effort */ }
            }
        }
    }
}
