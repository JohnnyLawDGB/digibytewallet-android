package io.digibyte.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Schemes we'll launch externally. Anything else — javascript:, file:,
 * intent:, content:, custom app schemes — is refused at this boundary.
 *
 * Custom Tabs itself rejects most of these, but the ACTION_VIEW fallback
 * doesn't, and any caller who lets a network-sourced or otherwise tainted
 * URL flow into [openExternalUrl] (asset metadata `urls[]` arrays,
 * release-channel data, etc.) shouldn't be one careless caller away from
 * shipping a tap-to-execute primitive.
 */
private val ALLOWED_SCHEMES = setOf("http", "https")

/** Sanity-cap. Anything over this is either a typo, a paste accident, or
 *  a deliberate try at intent overflow. Real URLs in our flows are well
 *  under 2KB; longer strings get rejected before we hand them to Android. */
private const val MAX_URL_LEN = 2048

/**
 * Open an external URL in a Chrome Custom Tab so the user can return to
 * the wallet via the tab's close button. Falls back to a plain ACTION_VIEW
 * intent (with NEW_TASK) on devices without any browser that supports
 * Custom Tabs — the user lands in a stand-alone browser task and Android's
 * recents/back gesture still surfaces the wallet again.
 *
 * Schemes outside [ALLOWED_SCHEMES] are silently refused and logged. Use
 * this for anything outside our own app: marketplace, block explorer
 * links, release download URLs, etc. Don't use it for in-wallet navigation.
 *
 * @return true if the URL was launched (or attempted), false if it was
 *         refused by the scheme/length filter.
 */
fun openExternalUrl(context: Context, url: String): Boolean {
    if (!isSafeExternalUrl(url)) {
        Log.w("ExternalUrl", "refusing to launch ${url.take(64)}: failed scheme/length filter")
        return false
    }
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    return true
}

/**
 * Pure predicate exposed for unit testing. Returns true iff [url] is
 * non-blank, length-bounded, free of control chars, and uses an
 * http(s) scheme.
 *
 * Intentionally case-insensitive on the scheme (RFC 3986 §3.1) but
 * exact-match on the allowed set — defends against `Javascript:` /
 * `JaVaScRiPt:` evasion.
 *
 * Implemented with pure string ops rather than Uri.parse so the check
 * is unaffected by Android's URI normalization quirks (which have
 * historically silently stripped some control chars during parse).
 */
internal fun isSafeExternalUrl(url: String): Boolean {
    if (url.isBlank() || url.length > MAX_URL_LEN) return false
    // Reject embedded control chars / null bytes that some parsers will
    // happily strip — Android's URI parser used to be one of them.
    if (url.any { it.code < 0x20 || it.code == 0x7F }) return false
    // RFC 3986 §3.1: scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
    // followed by ":". We hand-parse rather than trusting Uri.parse so
    // exotic inputs like `data:text/html,<script>` can't slip through
    // a normalizer that strips the colon.
    val colonIdx = url.indexOf(':')
    if (colonIdx <= 0) return false
    val scheme = url.substring(0, colonIdx).lowercase()
    if (scheme.isEmpty() || !scheme[0].isLetter()) return false
    if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return false
    return scheme in ALLOWED_SCHEMES
}
