package app.aroundtheblock.wallet.core.hub

import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import org.json.JSONObject

/**
 * The parts of a Hub chat message that the REST history (`GET /hub/channels/1/messages`) and the
 * socket's `message` event share, read one way for both: the attached image and the link preview
 * (the server's `_shapeMessage`). Either may be absent or JSON null; both read as null then.
 *
 * A JSON null is never read as the text "null": Android's org.json returns "null" from `optString`
 * for a JSON null where the JVM's returns the fallback, so every field goes through [text].
 */
object HubJson {

    /** The socket error code the server sends when a post is refused below Trust Level 2. */
    const val TRUST_L2_REQUIRED = "TRUST_L2_REQUIRED"

    /** `attachment: { url, width, height } | null`. One with no url is no attachment. */
    fun attachment(message: JSONObject): ChatAttachment? {
        val obj = message.optJSONObject("attachment")
        val url = obj?.let { text(it, "url") }
        return if (obj == null || url == null) null else ChatAttachment(url, int(obj, "width"), int(obj, "height"))
    }

    /** `preview: { url, title, description, site_name, image_url } | null`. One with no url is no preview. */
    fun preview(message: JSONObject): LinkPreview? = previewObject(message.optJSONObject("preview"))

    /** A preview object as it stands, for the socket's `preview` event (`{ messageId, preview }`). */
    fun previewObject(obj: JSONObject?): LinkPreview? {
        val url = obj?.let { text(it, "url") }
        return if (obj == null || url == null) null else LinkPreview(
            url = url,
            title = text(obj, "title"),
            description = text(obj, "description"),
            siteName = text(obj, "site_name"),
            imageUrl = text(obj, "image_url"),
        )
    }

    /** The field as text, or null when it is missing, JSON null or empty. */
    fun text(obj: JSONObject, name: String): String? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return obj.optString(name, "").takeIf { it.isNotEmpty() }
    }

    /** The field as a whole number, or null when it is missing, JSON null or not a number. */
    fun intOrNull(obj: JSONObject, name: String): Int? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return (obj.opt(name) as? Number)?.toInt() ?: obj.optString(name, "").toIntOrNull()
    }

    private fun int(obj: JSONObject, name: String): Int = intOrNull(obj, name)?.coerceAtLeast(0) ?: 0
}

/**
 * Where the app loads a chat image from. The server sends a path (`/api/hub/media/<file>`) that is
 * resolved against the API host the Hub client already uses ([DigiScopeClient.BASE_URL]), and
 * nothing else: a path that is not a plain media file name, or a full URL to any host, loads
 * nothing. The image is fetched by the app's image loader on the shared client, which carries the
 * DigiScope pins and the Tor routing.
 */
object HubMedia {
    /** The scheme and host of [DigiScopeClient.BASE_URL]: the server's paths already start with `/api`. */
    val ORIGIN: String = DigiScopeClient.BASE_URL.removeSuffix("/api")

    /** The server's own file names (hub-media.js `FILE_RE`): 128 random bits in hex and the image type. */
    private val MEDIA_PATH = Regex("""^/api/hub/media/[0-9a-f]{32}\.(png|jpg|gif|webp)$""")

    /** The URL to load [attachment]'s image from, or null when its path is not a Hub media file. */
    fun imageUrl(attachment: ChatAttachment): String? =
        attachment.url.takeIf { MEDIA_PATH.matches(it) }?.let { ORIGIN + it }
}
