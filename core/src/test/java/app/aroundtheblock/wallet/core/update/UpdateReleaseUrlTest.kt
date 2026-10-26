package app.aroundtheblock.wallet.core.update

import app.aroundtheblock.wallet.core.UpdateChecker.Companion.RELEASE_DOWNLOAD
import app.aroundtheblock.wallet.core.UpdateChecker.Companion.RELEASE_PAGE
import app.aroundtheblock.wallet.core.UpdateChecker.Companion.isReleaseUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update dialog opens these links, so only this repository's GitHub releases may pass (AND-010). */
class UpdateReleaseUrlTest {

    private val repo = "JohnnyLawDGB/aroundtheblock-wallet-releases"
    private val apk = "https://github.com/$repo/releases/download/v4.0.89/aroundtheblock-wallet-v4.0.89.apk"
    private val page = "https://github.com/$repo/releases/tag/v4.0.89"

    @Test
    fun `this repository's release links pass`() {
        assertTrue(isReleaseUrl(apk, repo, RELEASE_DOWNLOAD))
        assertTrue(isReleaseUrl(page, repo, RELEASE_PAGE))
        assertTrue(isReleaseUrl(apk, repo, RELEASE_PAGE))
    }

    @Test
    fun `anything else is refused`() {
        val refused = listOf(
            apk.replace("https://", "http://"),
            "file:///sdcard/Download/aroundtheblock-wallet-v4.0.89.apk",
            "content://downloads/1",
            "intent://x#Intent;end",
            "javascript:alert(1)",
            apk.replace("github.com", "github.com.example.net"),
            apk.replace("github.com", "evilgithub.com"),
            apk.replace("https://github.com", "https://github.com:8443"),
            apk.replace("https://", "https://user@"),
            apk.replace("https://", "https://github.com@"),
            "https://github.com/someone-else/aroundtheblock-wallet-releases/releases/download/v1/a.apk",
            "https://github.com/$repo-fork/releases/download/v1/a.apk",
            "https://github.com/JohnnyLawDGB/digibytewallet-android/releases/download/v4.0.87/a.apk",
            "https://github.com/$repo/archive/main.zip",
            "not a url",
        )
        for (url in refused) assertFalse(url, isReleaseUrl(url, repo, RELEASE_DOWNLOAD))
        assertFalse(page, isReleaseUrl(page, repo, RELEASE_DOWNLOAD))
    }

    @Test
    fun `no repository means no update link`() {
        assertFalse(isReleaseUrl(apk, "", RELEASE_DOWNLOAD))
    }
}
