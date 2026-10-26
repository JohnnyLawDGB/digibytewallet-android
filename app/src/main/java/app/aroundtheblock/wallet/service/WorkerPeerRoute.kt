package app.aroundtheblock.wallet.service

/**
 * Where the background worker's peer connections may go, decided before it starts the peer
 * manager.
 *
 * The worker can run in a process where SyncService never ran, so nothing there has set the
 * native layer's SOCKS proxy. With the user's Tor setting on, the worker therefore starts peers
 * only through a SOCKS port it sets itself, and otherwise starts nothing and tries again later.
 */
sealed interface WorkerPeerRoute {
    /** The Tor setting is off: peers are dialled directly. */
    object Direct : WorkerPeerRoute

    /** The Tor setting is on and Tor is connected in this process: set this port first. */
    data class ViaSocks(val port: Int) : WorkerPeerRoute

    /** The Tor setting is on but there is no SOCKS port in this process: start nothing. */
    object Wait : WorkerPeerRoute

    companion object {
        fun of(torEnabled: Boolean, socksPort: Int?): WorkerPeerRoute = when {
            !torEnabled -> Direct
            socksPort != null && socksPort in 1..65_535 -> ViaSocks(socksPort)
            else -> Wait
        }
    }
}
