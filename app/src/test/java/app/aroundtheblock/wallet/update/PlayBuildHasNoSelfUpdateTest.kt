package app.aroundtheblock.wallet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google Play forbids an app it distributes from updating itself any other way. The GitHub
 * self-updater therefore lives only in src/sideload; src/main and src/play must not reach it.
 * If they did, the code that downloads an APK from GitHub would ship in the Play bundle — and a
 * reviewer finding it is a policy rejection, or a removal after the fact.
 *
 * Checked on built output too (2026-10-05): the mainnetPlay minified APK carries no
 * "api.github.com/repos" string and no UpdateDialog; the sideload one carries both.
 */
class PlayBuildHasNoSelfUpdateTest {

    private val forbidden = listOf("UpdateChecker", "UpdateDialog", "RELEASES_REPO", "browser_download_url")

    private fun kotlinUnder(dir: String): List<File> =
        File(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `neither main nor play references the self-updater`() {
        val offenders = (kotlinUnder("src/main/java") + kotlinUnder("src/play/java")).flatMap { f ->
            // Code only: a comment that names the updater ships nothing.
            val text = f.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
            forbidden.filter { text.contains(it) }.map { "${f.path}: $it" }
        }
        assertEquals("self-update code reachable outside src/sideload", emptyList<String>(), offenders)
    }

    @Test fun `both flavors provide SelfUpdatePrompt with the same signature`() {
        val sig = "fun SelfUpdatePrompt(okHttpClient: OkHttpClient, torManager: TorManager)"
        for (flavor in listOf("play", "sideload")) {
            val f = File("src/$flavor/java/app/aroundtheblock/wallet/update/SelfUpdatePrompt.kt")
            assertTrue("missing ${f.path}", f.isFile)
            assertTrue("${f.path} changed the shared signature", f.readText().contains(sig))
        }
    }
}
