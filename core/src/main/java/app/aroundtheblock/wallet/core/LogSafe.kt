package app.aroundtheblock.wallet.core

/**
 * A string from outside the device, made safe to put in a log line (ASSET-LOG-CRLF).
 *
 * Invariant: no log line in core/ or app/ interpolates an untrusted remote string -- issuer
 * metadata, a server body or field, a WebSocket close reason, an exception message that may quote
 * either -- without neutralising its control characters and bounding its length. Logcat starts a new
 * line at every line break inside a message, so a raw value could print text that reads as another
 * line; this returns a value with no control, line-separator or format character left in it, and
 * no more than [max] characters of the input. A caught throwable from a remote parse is logged as its
 * class name and `text(message)`, never passed to Log: its message and stack trace quote the input.
 *
 * `LogRedactionSourceGateTest` checks this by source pattern, not by data flow: it follows the reads it
 * names (JSON fields, HTTP bodies, WebSocket strings, metadata fields, exception messages) through
 * assignments in one function, and passes what it cannot trace -- a value handed in from another
 * function, a field not on its list. It catches the common forms; it does not prove the invariant.
 *
 * Escapes: `\n` `\r` `\t` by name, a backslash as `\\` (so a backslash sequence in the input cannot pass
 * for one of these), every other control, line/paragraph separator, format character (bidi override,
 * zero-width, BOM) and unpaired surrogate as `\uXXXX`. A cut value ends `…(+N)`, N the characters left
 * out; the cut never splits a surrogate pair.
 */
object LogSafe {
    const val DEFAULT_MAX = 120

    fun text(value: Any?, max: Int = DEFAULT_MAX): String {
        val s = value?.toString() ?: return "null"
        var end = minOf(s.length, max.coerceAtLeast(0))
        if (end in 1 until s.length && Character.isHighSurrogate(s[end - 1]) && Character.isLowSurrogate(s[end])) end--
        val out = StringBuilder(end + 16)
        var i = 0
        while (i < end) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(s[i + 1])) {
                out.append(c).append(s[i + 1]); i += 2; continue
            }
            when {
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\\' -> out.append("\\\\")
                needsEscape(c) -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                else -> out.append(c)
            }
            i++
        }
        if (end < s.length) out.append("…(+").append(s.length - end).append(')')
        return out.toString()
    }

    private fun needsEscape(c: Char): Boolean {
        if (Character.isISOControl(c) || Character.isSurrogate(c)) return true
        return when (Character.getType(c).toByte()) {
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT -> true
            else -> false
        }
    }
}
