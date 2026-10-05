package app.aroundtheblock.wallet.core.tor

import java.io.IOException

/**
 * Where an app-made connection may go right now, given the user's Tor setting and Tor's state.
 *
 * The rule: while the user's Tor setting is on, nothing goes direct until the wallet has told the
 * user it is going direct. Until Tor is connected a connection waits, then fails ([Blocked]); it is
 * never quietly sent outside Tor. The one way to direct with the setting on is the clearnet
 * fallback, which the sync service takes only after raising the "Tor unavailable" banner on the
 * wallet screen (see [TorManager.announceClearnetFallback]).
 */
sealed class TorRoute {
    /** The user's Tor setting is off, or the clearnet fallback has been announced. */
    data object Direct : TorRoute()

    /** Tor is connected: route through its SOCKS listener on loopback. */
    data class Socks(val port: Int) : TorRoute()

    /** Tor is on but not connected, and no fallback has been announced: nothing may leave. */
    data object Blocked : TorRoute()

    companion object {
        /**
         * @param torEnabled the user's Tor setting.
         * @param socksPort Tor's SOCKS port, non-null only while Tor is connected (bootstrap 100%).
         * @param clearnetFallbackAnnounced true once the wallet has told the user it is going direct.
         */
        fun of(torEnabled: Boolean, socksPort: Int?, clearnetFallbackAnnounced: Boolean): TorRoute = when {
            socksPort != null -> Socks(socksPort)
            !torEnabled -> Direct
            clearnetFallbackAnnounced -> Direct
            else -> Blocked
        }
    }
}

/**
 * A request was refused because the user's Tor setting is on and Tor is not connected yet.
 * An [IOException], so every caller's existing network-failure path handles it; callers that
 * should wait rather than fail can tell it apart.
 */
class TorNotReadyException(message: String) : IOException(message)
