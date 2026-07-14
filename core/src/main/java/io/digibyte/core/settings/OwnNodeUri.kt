package io.digibyte.core.settings

import java.net.URLDecoder

/**
 * A scanned/typed own-node reference. `dgbnode://host[:port][?net=&label=]`, or a raw
 * host[:port] (manual-field fallback). Host:port validation is delegated to CustomNode.parse
 * (IPv4/hostname; no IPv6/onion/URL-scheme-in-host). Returns null on any malformed input.
 */
data class OwnNodeUri(val node: CustomNode, val label: String?, val net: String?) {
    companion object {
        private const val SCHEME = "dgbnode://"
        private const val LABEL_MAX = 32

        fun parse(raw: String, defaultPort: Int): OwnNodeUri? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (! trimmed.startsWith(SCHEME)) {
                // raw host:port fallback — no metadata
                if (! looksSaneHostPort(trimmed)) return null
                val node = CustomNode.parse(trimmed, defaultPort) ?: return null
                return OwnNodeUri(node, label = null, net = null)
            }
            val body = trimmed.substring(SCHEME.length)
            val qIdx = body.indexOf('?')
            val hostPort = (if (qIdx < 0) body else body.substring(0, qIdx)).trim()
            val query = if (qIdx < 0) "" else body.substring(qIdx + 1)
            if (! looksSaneHostPort(hostPort)) return null
            // onion deferred (Seq 2.5) — check the host only, stripping any ":port" suffix.
            if (hostPort.substringBeforeLast(':').endsWith(".onion", ignoreCase = true)) return null
            val node = CustomNode.parse(hostPort, defaultPort) ?: return null
            var net: String? = null; var label: String? = null
            for (pair in query.split('&')) {
                val eq = pair.indexOf('='); if (eq < 0) continue
                val k = pair.substring(0, eq); val v = decode(pair.substring(eq + 1))
                when (k) {
                    "net" -> if (v == "mainnet" || v == "testnet") net = v
                    "label" -> label = v.filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-_." }
                        .trim().take(LABEL_MAX).ifEmpty { null }
                }
            }
            return OwnNodeUri(node, label, net)
        }

        private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

        // CustomNode.parse only rejects blank/"//"/embedded ':' — it doesn't validate charset,
        // so raw garbage (whitespace, control chars, stray unicode) would otherwise pass through
        // as a "host". Restrict to the DNS-hostname/IPv4 charset before delegating.
        private fun looksSaneHostPort(s: String): Boolean =
            s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
    }
}
