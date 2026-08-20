package io.digibyte.core.model

/**
 * A parsed `digibyte:` URI — the wallet's entry point for QR codes and deep links.
 *
 * Everything this parses is **untrusted input**: anyone can put a QR code on a screen or hand
 * the OS a deep link. The parse is therefore deliberately Android-free. It used to call
 * `android.net.Uri`, which is a throwing stub on a plain JVM, so no unit test could reach it
 * and this file had no coverage at all. Hand-parsing the query string is a few lines and makes
 * every decision below testable — the same reason `PeerPenaltyPersist` and `CfRecoveryPolicy`
 * are plain objects.
 *
 * ## Asset transfer requests
 *
 * [assetId] + [assetAmount] extend BIP21 rather than introduce a new scheme. BIP21 permits
 * extension params and requires unknown ones to be ignored, so a wallet that does not
 * understand these still sees a valid plain-DGB request — it degrades to something safe
 * rather than something wrong.
 *
 * An asset request asks the user to **give an asset away**, which is categorically different
 * from a payment request asking them to receive one. Two consequences are enforced here; the
 * rest belongs to the confirmation UI:
 *
 * - **Fail closed.** A URI naming an asset without a usable quantity — or a quantity with no
 *   asset — is rejected outright. It is never degraded into a plain DGB send, because that
 *   would turn a malformed asset request into a payment prompt for an entirely different
 *   thing, to an address the requester chose.
 * - **Parsing proves nothing about ownership.** A well-formed request is only a request. That
 *   the wallet actually holds the asset is settled against `AssetManager` — which counts only
 *   outputs the native wallet still holds — and never inferred from the URI.
 */
data class DigiByteUri(
    val address: String,
    val amount: Long? = null, // satoshis
    val label: String? = null,
    val message: String? = null,
    /** DigiAsset ID this request transfers, or null for a plain DGB request. */
    val assetId: String? = null,
    /**
     * Quantity in the asset's own RAW units — never scaled the way [amount] is. Divisibility
     * is a property of the asset itself, so the requester does not get to assume it and the
     * wallet must not apply a decimal shift it cannot verify.
     */
    val assetAmount: Long? = null,
) {
    companion object {
        fun parse(input: String): DigiByteUri? {
            val cleaned = input.trim()
            if (cleaned.isBlank()) return null

            // Reject non-DigiByte URIs (digiid://, http://, etc.)
            if (cleaned.contains("://") && !cleaned.startsWith("digibyte:")) {
                return null
            }

            // Handle raw address (no scheme)
            if (!cleaned.startsWith("digibyte:")) {
                return DigiByteUri(address = cleaned)
            }

            // digibyte:DAddress?amount=1.5&label=Payment
            val body = cleaned.removePrefix("digibyte:")
            val address = body.substringBefore('?').trim()
            if (address.isBlank()) return null

            val params = parseQuery(body.substringAfter('?', ""))

            val assetId = params["assetId"]?.takeIf { it.isNotBlank() }
            // Reject rather than silently drop: a quantity that fails to parse must not become
            // "no quantity", which would read as an ordinary payment request.
            val assetAmount = params["assetAmount"]?.let { raw ->
                raw.toLongOrNull() ?: return null
            }

            // The two asset fields are meaningless alone — one names what to send with no
            // amount, the other an amount with nothing to send.
            if ((assetId == null) != (assetAmount == null)) return null
            if (assetAmount != null && assetAmount <= 0L) return null

            val amountSats = params["amount"]?.toDoubleOrNull()?.let {
                (it * 100_000_000).toLong()
            }

            return DigiByteUri(
                address = address,
                amount = amountSats,
                label = params["label"],
                message = params["message"],
                assetId = assetId,
                assetAmount = assetAmount,
            )
        }

        fun encode(address: String, amountSats: Long? = null, label: String? = null): String {
            val sb = StringBuilder("digibyte:$address")
            val params = mutableListOf<String>()
            amountSats?.let { params.add("amount=${it.toDouble() / 100_000_000}") }
            label?.let { params.add("label=${encodeComponent(it)}") }
            if (params.isNotEmpty()) sb.append("?${params.joinToString("&")}")
            return sb.toString()
        }

        /**
         * Build a request to transfer [assetAmount] raw units of [assetId] to [address].
         * Deliberately separate from [encode] so a caller cannot produce an asset request by
         * accident, and so the quantity never passes through the DGB decimal conversion.
         */
        fun encodeAssetRequest(
            address: String,
            assetId: String,
            assetAmount: Long,
            label: String? = null,
        ): String {
            require(assetAmount > 0) { "asset quantity must be positive" }
            val params = mutableListOf(
                "assetId=${encodeComponent(assetId)}",
                "assetAmount=$assetAmount",
            )
            label?.let { params.add("label=${encodeComponent(it)}") }
            return "digibyte:$address?${params.joinToString("&")}"
        }

        /**
         * Split `a=1&b=2` and percent-decode. The FIRST occurrence of a key wins: a duplicated
         * param is a standard way to smuggle a second value past a display that shows only the
         * first, so later ones are dropped rather than allowed to override.
         */
        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = decodeComponent(pair.substringBefore('='))
                if (key.isBlank() || out.containsKey(key)) continue
                out[key] = decodeComponent(pair.substringAfter('=', ""))
            }
            return out
        }

        private fun decodeComponent(s: String): String {
            if (!s.contains('%') && !s.contains('+')) return s
            val bytes = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '+' -> { bytes.write(' '.code); i++ }
                    c == '%' && i + 2 < s.length -> {
                        val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                        if (hex == null) { bytes.write(c.code); i++ } else { bytes.write(hex); i += 3 }
                    }
                    else -> {
                        // Write the character's UTF-8 bytes so non-ASCII survives intact.
                        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                        i++
                    }
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        private val SAFE = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet() + setOf('-', '_', '.', '~')

        private fun encodeComponent(s: String): String {
            val sb = StringBuilder(s.length)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in SAFE) sb.append(c) else sb.append('%').append("%02X".format(b))
            }
            return sb.toString()
        }
    }
}
