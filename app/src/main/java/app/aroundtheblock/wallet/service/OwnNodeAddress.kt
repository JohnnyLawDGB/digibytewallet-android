package app.aroundtheblock.wallet.service

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The IPv4 address the user's own node is dialled at — found without a name lookup outside Tor.
 *
 * The native pin takes an IPv4 address (the C core dials peers by address, through the SOCKS proxy
 * when Tor is on). A host name has to be resolved first, and the device's resolver would send that
 * query beside Tor, naming the user's own node to whoever watches their network. So while the
 * user's Tor setting is on, only an IPv4 address is used; a host name — a `.onion` name included,
 * which the native pin cannot dial — is refused, and the node is not pinned. With Tor off a host
 * name is resolved as before.
 */
object OwnNodeAddress {

    sealed class Result {
        /** Dial the node at this IPv4 address. */
        data class Address(val ipv4: String) : Result()

        /** Tor is on and the node is given by name: not resolved, not pinned. */
        data object RefusedUnderTor : Result()

        /** Tor is off and the name did not resolve to an IPv4 address. */
        data class Unresolved(val reason: String?) : Result()
    }

    /**
     * @param lookup the resolver, used only when [torEnabled] is false and [host] is not an
     *   IPv4 address. Replaceable so a test can prove it is not called.
     */
    fun resolve(
        host: String,
        torEnabled: Boolean,
        lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Result {
        ipv4Literal(host)?.let { return Result.Address(it) }
        if (torEnabled) return Result.RefusedUnderTor
        return try {
            lookup(host).firstOrNull { it is Inet4Address }?.hostAddress?.let { Result.Address(it) }
                ?: Result.Unresolved("no IPv4 address")
        } catch (e: Exception) {
            Result.Unresolved(e.message ?: e.javaClass.simpleName)
        }
    }

    /** [host] as a canonical dotted-quad IPv4 address, or null when it is anything else. No lookup. */
    fun ipv4Literal(host: String): String? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        return octets.joinToString(".")
    }
}
