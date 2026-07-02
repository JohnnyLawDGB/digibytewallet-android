package io.digibyte.ui.wallet

/**
 * Pure decision logic for the active-screen connection-readiness nudge, split
 * out of [WalletViewModel]'s 5s balance/peer poll so it can be unit-tested
 * without the JNI bridge or coroutine timing.
 *
 * Context: while the app is in the foreground on the wallet screen we want a
 * send/receive to never wait on a cold connection. The SPV peer manager mostly
 * takes care of itself — the C core self-heals individual peer drops via its
 * disconnect callbacks, and the SyncService keepalive (10s) plus onResume own
 * the heavier recovery (forceReconnect / service resurrection). This is a thin,
 * gentle, second path: while the user is actively looking at the screen, wake a
 * *dormant* (0-peer) manager on the next poll tick (~5s) rather than waiting up
 * to a 10s keepalive cycle.
 *
 * Deliberately gentle: the only action this gates is a plain [startSync]; the
 * destructive forceReconnect/recreate is left to the keepalive and onResume so
 * this layer can't hurt broadcast latency or re-open the v3.7.1 recreate race.
 *
 * Each guard:
 *  - [peerCount] == 0 only: startSync early-returns when peers > 0, so it cannot
 *    top up a degraded-but-nonzero count; the C core already repairs that case
 *    on its own. A nudge above 0 is a no-op, so don't issue one.
 *  - [isScreenActive]: honors "active on screen, not background." The poll's
 *    ViewModel can outlive the resumed Activity, so VM liveness != on-screen.
 *  - !{@code torComingUp}: dialing direct while the SOCKS proxy is still wiring
 *    up would leak the wallet's IP — defer until Tor settles or is off.
 *
 * @param isScreenActive the wallet Activity is in the RESUMED lifecycle state.
 * @param torComingUp Tor is enabled and still Starting/Connecting.
 * @param peerCount current connected SPV peer count from the C core.
 */
internal fun shouldWakePeers(
    isScreenActive: Boolean,
    torComingUp: Boolean,
    peerCount: Int,
): Boolean = isScreenActive && !torComingUp && peerCount == 0
