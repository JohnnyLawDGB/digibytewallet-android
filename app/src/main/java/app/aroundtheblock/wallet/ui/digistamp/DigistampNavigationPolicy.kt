package app.aroundtheblock.wallet.ui.digistamp

import app.aroundtheblock.wallet.core.digistamp.DigistampUris
import app.aroundtheblock.wallet.ui.util.isSafeExternalUrl

/** What the Market's web view does with one navigation. */
internal enum class DigistampNavigation {
    /** Load it in the web view: a main-frame page of the DigiStamp origin. */
    LOAD_HERE,

    /** Open the wallet screen a `digibyte:` or `digiid:` link names, for the user to read before acting. */
    WALLET_SCREEN,

    /** Hand it to the browser, through openExternalUrl: an HTTPS link that helper accepts. */
    OPEN_IN_BROWSER,

    /** Do nothing. */
    BLOCK,
}

/**
 * Decides every navigation of the Market's web view (DigistampScreen). Pure — no Android types —
 * so each rule is a unit test (DigistampNavigationPolicyTest).
 *
 * The view stays inside the DigiStamp origin, [DigistampUris.isInAppOrigin]. The platform asks the
 * view's client about the GET navigations a page starts, and about redirects; [decide] answers
 * those. It never asks about a POST, a load the app starts, or Back and Forward, so every
 * main-frame page that commits is checked again with [mustReturnHome].
 */
internal object DigistampNavigationPolicy {

    /** The schemes the wallet opens a screen for. Any other scheme a page names is not the wallet's to open. */
    private val walletSchemes = setOf("digibyte", "digiid")

    /**
     * What the view does with a navigation to [target].
     *
     * @param isMainFrame whether the page itself navigates. A frame inside the page decides nothing:
     *   it never loads, opens a wallet screen, or reaches the browser through this client.
     * @param shownUrl the page the view has committed, which is the page asking. Only a DigiStamp
     *   page may open a wallet screen or the browser; null while nothing has committed.
     */
    fun decide(target: String, isMainFrame: Boolean, shownUrl: String?): DigistampNavigation {
        if (!isMainFrame) return DigistampNavigation.BLOCK
        if (DigistampUris.isInAppOrigin(target)) return DigistampNavigation.LOAD_HERE
        if (shownUrl == null || !DigistampUris.isInAppOrigin(shownUrl)) return DigistampNavigation.BLOCK
        val scheme = target.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme in walletSchemes) return DigistampNavigation.WALLET_SCREEN
        if (scheme == "https" && isSafeExternalUrl(target)) return DigistampNavigation.OPEN_IN_BROWSER
        return DigistampNavigation.BLOCK
    }

    /** True when a main-frame page that committed at [committedUrl] is not a DigiStamp page. */
    fun mustReturnHome(committedUrl: String?): Boolean =
        committedUrl == null || !DigistampUris.isInAppOrigin(committedUrl)
}
