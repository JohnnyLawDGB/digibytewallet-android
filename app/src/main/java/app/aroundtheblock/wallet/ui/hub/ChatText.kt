package app.aroundtheblock.wallet.ui.hub

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Chat text as plain text with its web links marked, for display. Pure: no Compose, no Android.
 *
 * Message text is never read as HTML or markup of any kind; the only thing found in it is an
 * `http://` or `https://` link (the website's chatText.js rule, without its explorer links), with
 * the punctuation that usually ends a sentence after it left out of the link.
 */
object ChatText {

    sealed class Piece {
        abstract val text: String
        data class Plain(override val text: String) : Piece()
        /** [text] as written, which is also the URL opened. */
        data class Link(override val text: String) : Piece()
    }

    private val URL = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("""[.,!?)]+$""")

    fun pieces(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        fun plain(value: String) {
            if (value.isEmpty()) return
            val last = out.lastOrNull()
            if (last is Piece.Plain) out[out.lastIndex] = Piece.Plain(last.text + value) else out += Piece.Plain(value)
        }
        var at = 0
        for (match in URL.findAll(text)) {
            plain(text.substring(at, match.range.first))
            val link = match.value.replace(TRAILING, "")
            if (isWebLink(link)) out += Piece.Link(link) else plain(link)
            plain(match.value.substring(link.length))
            at = match.range.last + 1
        }
        plain(text.substring(at))
        return out
    }

    /** True for an `http:` or `https:` URL with a host: the only links the chat opens. */
    fun isWebLink(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return (url.scheme == "http" || url.scheme == "https") && url.host.isNotEmpty()
    }

    /** True for an `https:` image URL: the only preview images the chat loads. */
    fun isHttpsImage(value: String?): Boolean {
        val url = value?.toHttpUrlOrNull() ?: return false
        return url.isHttps && value.startsWith("https://", ignoreCase = true)
    }

    /** The host a preview links to, taken from its URL, never from the page's own `site_name`. */
    fun previewHost(url: String): String? =
        url.takeIf { isWebLink(it) }?.toHttpUrlOrNull()?.host?.removePrefix("www.")
}
