package app.aroundtheblock.wallet.ui.digistamp

import android.webkit.WebSettings
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Each setting of the Market's web view is the least the page needs, and the view has no others.
 *
 * What the page needs was read from the site itself (every page linked from its home page, and
 * every script those pages load): it is a Next.js app, so it needs script; the one thing it keeps
 * in DOM storage is a theme choice, read inside a try/catch that falls back to the system theme,
 * so it does not need DOM storage. It needs no file, no content provider, no location, and no
 * window opened by script.
 */
class DigistampWebSettingsTest {

    private val settings = mockk<WebSettings>(relaxed = true).also { DigistampWebViewHost.applyLeastSettings(it) }

    /** Kept: the site renders nothing without script. There is no JavaScript bridge to reach. */
    @Test fun `script is on, the one capability kept`() {
        verify(exactly = 1) { settings.javaScriptEnabled = true }
    }

    @Test fun `DOM storage is off`() {
        verify(exactly = 1) { settings.domStorageEnabled = false }
    }

    @Test fun `file access is off`() {
        verify(exactly = 1) { settings.allowFileAccess = false }
    }

    @Test fun `content access is off`() {
        verify(exactly = 1) { settings.allowContentAccess = false }
    }

    @Test fun `script cannot open windows on its own`() {
        verify(exactly = 1) { settings.javaScriptCanOpenWindowsAutomatically = false }
    }

    @Test fun `location is off`() {
        verify(exactly = 1) { settings.setGeolocationEnabled(false) }
    }

    /** A setting added later must be added here, with what the page needs it for. */
    @Test fun `nothing else is set`() {
        verify {
            settings.javaScriptEnabled = any()
            settings.domStorageEnabled = any()
            settings.allowFileAccess = any()
            settings.allowContentAccess = any()
            settings.javaScriptCanOpenWindowsAutomatically = any()
            settings.setGeolocationEnabled(any())
        }
        confirmVerified(settings)
    }
}
