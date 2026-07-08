package io.digibyte.core.settings

import io.digibyte.core.bridge.NativeBridge

/** A user-configured DigiByte node to use as a priority compact-filter peer. */
data class CustomNode(val host: String, val port: Int) {
    fun asHostPort(): String = "$host:$port"

    companion object {
        const val MAINNET_DEFAULT_PORT = 12024
        const val TESTNET_DEFAULT_PORT = 12033

        /**
         * Parse "host" or "host:port" (IPv4 literal or A-record hostname; IPv6 and
         * URL schemes are rejected — the SPV core drops IPv6 peers). Returns null if
         * the host is blank/malformed or the port is not in 1..65535.
         */
        fun parse(raw: String, defaultPort: Int): CustomNode? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val idx = trimmed.lastIndexOf(':')
            val host: String
            val port: Int
            if (idx < 0) {
                host = trimmed; port = defaultPort
            } else {
                host = trimmed.substring(0, idx).trim()
                port = trimmed.substring(idx + 1).trim().toIntOrNull() ?: return null
                if (port !in 1..65535) return null
            }
            if (host.isEmpty()) return null
            // Reject URL schemes ("//") and IPv6 literals (a residual ':' after the split).
            if (host.contains("//") || host.contains(':')) return null
            return CustomNode(host, port)
        }
    }
}

/**
 * The effective sync mode. A configured own-node (or testnet) forces
 * COMPACT_FILTERS_ONLY so no bloom filterload — and thus no address-set leak —
 * ever goes on the wire; otherwise the user's stored sync_mode pref wins.
 */
fun syncModeFor(pref: Int, customNodeEnabled: Boolean, isTestnet: Boolean): Int =
    if (isTestnet || customNodeEnabled) NativeBridge.SyncMode.COMPACT_FILTERS_ONLY else pref
