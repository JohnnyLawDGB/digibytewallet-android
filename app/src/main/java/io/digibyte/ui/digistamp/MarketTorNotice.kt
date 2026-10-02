package io.digibyte.ui.digistamp

/**
 * Whether the Market screen must first say that it is not routed through Tor.
 *
 * The Market is a WebView, and a WebView's network stack does not use the app's proxy: with the
 * user's Tor setting on, the site still sees the device's own address. So while the setting is on
 * the screen asks before anything loads. A "Continue" holds for the rest of the session (the
 * process); "Cancel" leaves the screen and asks again next time.
 *
 * The text is English only for now; it is listed for localisation.
 */
object MarketTorNotice {
    const val TITLE = "Market is not routed through Tor"
    const val BODY = "Market is not routed through Tor; the site will see your IP address."
    const val CONTINUE = "Continue"

    @Volatile private var acceptedThisSession = false

    /** True when the notice must be shown before the Market loads. */
    fun mustAsk(torEnabled: Boolean): Boolean = torEnabled && !acceptedThisSession

    /** The user chose Continue: do not ask again this session. */
    fun accept() {
        acceptedThisSession = true
    }

    /** Test seam: forget the session's answer. */
    internal fun resetForTest() {
        acceptedThisSession = false
    }
}
