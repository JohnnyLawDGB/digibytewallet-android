package io.digibyte.core.settings

import android.content.Context
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.networkSuffix

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
 * The effective sync mode. Bloom (BIP37) is removed as a data path: the wallet
 * ALWAYS runs BIP157/158 compact filters only, so the address set never leaves the
 * device via a bloom filterload. The parameters are retained for call-site
 * compatibility but no longer select bloom — the stored `sync_mode` pref is ignored.
 */
@Suppress("UNUSED_PARAMETER")
fun syncModeFor(pref: Int, customNodeEnabled: Boolean, isTestnet: Boolean): Int =
    NativeBridge.SyncMode.COMPACT_FILTERS_ONLY

object CustomNodePrefs {
    private const val PREFS = "dgb_settings"
    private const val KEY_ENABLED = "custom_node_enabled"
    private const val KEY_HOSTPORT = "custom_node_hostport"
    private const val KEY_LABEL = "custom_node_label"
    private const val KEY_EXCLUSIVE = "custom_node_exclusive"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(base: String, ctx: Context) = base + networkSuffix(ctx)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(key(KEY_ENABLED, ctx), false)
    fun hostPort(ctx: Context): String? = prefs(ctx).getString(key(KEY_HOSTPORT, ctx), null)
    fun setEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(key(KEY_ENABLED, ctx), enabled).apply()
    fun setHostPort(ctx: Context, hostPort: String) =
        prefs(ctx).edit().putString(key(KEY_HOSTPORT, ctx), hostPort.trim()).apply()

    /** Optional user-facing label for the configured node (e.g. "My Node"). */
    fun label(ctx: Context): String? = prefs(ctx).getString(key(KEY_LABEL, ctx), null)
    fun setLabel(ctx: Context, label: String?) =
        prefs(ctx).edit().apply {
            if (label.isNullOrBlank()) remove(key(KEY_LABEL, ctx)) else putString(key(KEY_LABEL, ctx), label.trim())
        }.apply()

    /** When true, the own node is the ONLY peer dialed (see NativeBridge.setPinnedPeer). */
    fun isExclusive(ctx: Context): Boolean = prefs(ctx).getBoolean(key(KEY_EXCLUSIVE, ctx), false)
    fun setExclusive(ctx: Context, exclusive: Boolean) =
        prefs(ctx).edit().putBoolean(key(KEY_EXCLUSIVE, ctx), exclusive).apply()
}
