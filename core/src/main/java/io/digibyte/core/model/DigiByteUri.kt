package io.digibyte.core.model

data class DigiByteUri(
    val address: String,
    val amount: Long? = null, // satoshis
    val label: String? = null,
    val message: String? = null
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

            // Parse digibyte:DAddress?amount=1.5&label=Payment
            val uri = android.net.Uri.parse(cleaned)
            val ssp = uri.schemeSpecificPart ?: return null
            val address = ssp.split("?").firstOrNull()?.trim() ?: return null
            if (address.isBlank()) return null

            return try {
                val amountDgb = uri.getQueryParameter("amount")?.toDoubleOrNull()
                val amountSats = amountDgb?.let { (it * 100_000_000).toLong() }

                DigiByteUri(
                    address = address,
                    amount = amountSats,
                    label = uri.getQueryParameter("label"),
                    message = uri.getQueryParameter("message")
                )
            } catch (e: UnsupportedOperationException) {
                // Non-hierarchical URI — just return the address
                DigiByteUri(address = address)
            }
        }

        fun encode(address: String, amountSats: Long? = null, label: String? = null): String {
            val sb = StringBuilder("digibyte:$address")
            val params = mutableListOf<String>()
            amountSats?.let { params.add("amount=${it.toDouble() / 100_000_000}") }
            label?.let { params.add("label=${android.net.Uri.encode(it)}") }
            if (params.isNotEmpty()) sb.append("?${params.joinToString("&")}")
            return sb.toString()
        }
    }
}
