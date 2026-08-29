package io.digibyte.core.recovery

/**
 * One DigiDollar address of a foreign wallet, in both encodings, with its derivation position.
 *
 * The `DD…` form and the taproot output key X(Q) are the same key written two ways, and each half
 * serves a different step:
 *
 *  - [ddAddress] is what the backend's DigiDollar endpoint is keyed by. The ordinary UTXO lookup
 *    cannot see these holdings at all, because a token output carries zero satoshis and is
 *    filtered out of the UTXO set.
 *  - [taprootOutputKeyHex] locates the token output inside a transaction and fills the recipient
 *    field of a transfer.
 *  - [chain] and [index] are what the signer derives the spending key from.
 *
 * They are parsed as a unit rather than assembled from separate calls: a mismatch between the two
 * encodings would mean looking up one wallet's dollars while trying to spend another's.
 */
data class DigiDollarAddress(
    val ddAddress: String,
    val taprootOutputKeyHex: String,
    val chain: Int,
    val index: Int,
) {
    /** The script this key is spent from: `OP_1 <push32> X(Q)`. */
    val scriptPubKeyHex: String get() = "5120$taprootOutputKeyHex"

    companion object {
        private const val KEY_HEX_LEN = 64          // 32 bytes
        private val HEX = Regex("^[0-9a-fA-F]+$")

        /**
         * Parse one `"<ddAddress>|<taprootKeyHex>|<chain>|<index>"` line, or null.
         *
         * Null rather than a partially-filled address on anything malformed. A derivation that
         * failed comes back as an empty slot, and reading one as an address would query the
         * backend for an empty string — or worse, pair a blank key with a real derivation
         * position and build a transfer paying a malformed script.
         */
        fun parse(line: String?): DigiDollarAddress? {
            val parts = line?.trim()?.split("|") ?: return null
            if (parts.size != 4) return null
            val (dd, key, chainStr) = parts
            val index = parts[3].toIntOrNull() ?: return null
            val chain = chainStr.toIntOrNull() ?: return null
            if (dd.isEmpty()) return null
            if (key.length != KEY_HEX_LEN || !HEX.matches(key)) return null
            return DigiDollarAddress(dd, key, chain, index)
        }

        /** Parse a whole derivation batch, dropping empty slots and anything unparseable. */
        fun parseAll(lines: Array<String>?): List<DigiDollarAddress> =
            lines?.mapNotNull { parse(it) } ?: emptyList()
    }
}
