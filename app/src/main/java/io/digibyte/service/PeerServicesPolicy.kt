package io.digibyte.service

/**
 * Pure parsing of the seeder's per-peer `services_hex` field into the service
 * bitmask the native peer manager expects.
 *
 * The wire value is a hex string such as `"0x44d"` (or bare `"44d"`), where
 * `0x44d = NODE_NETWORK | NODE_BLOOM | NODE_WITNESS | NODE_COMPACT_FILTERS |
 * NODE_NETWORK_LIMITED`. Bit `0x40` ([SERVICES_NODE_COMPACT_FILTERS]) is the
 * one that makes BRPeerManager's filter-first selection recognize and hold a
 * BIP157/158 filter peer — the whole point of threading this field through
 * [io.digibyte.core.bridge.NativeBridge.injectPeerByIp].
 *
 * Returns 0 for an empty or malformed value, which the native side treats as
 * "unknown" and falls back to the legacy `NODE_NETWORK | NODE_BLOOM` default —
 * so a missing/garbage tag never regresses behavior.
 */
internal const val SERVICES_NODE_COMPACT_FILTERS = 0x40L

internal fun parseSeederServicesHex(raw: String?): Long {
    if (raw == null) return 0L
    val s = raw.trim().removePrefix("0x").removePrefix("0X")
    if (s.isEmpty()) return 0L
    return s.toLongOrNull(16) ?: 0L
}
