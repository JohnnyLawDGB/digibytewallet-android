package app.aroundtheblock.wallet.ui.hub

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate for the single-room Hub (website migration 141): the server closed every channel but
 * the chat room and answers in-chat tips with 501. Nothing may offer them again: no channel list
 * or chooser in the Hub screens, and no tip sheet or tip call anywhere in the app or the core.
 */
class HubOneRoomSourceTest {

    private val hubUi = File("src/main/java/app/aroundtheblock/wallet/ui/hub")
    private val mainSources = listOf(File("src/main/java"), File("../core/src/main/java"))

    private fun kotlinFiles(root: File) = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `the Hub screens build no channel list and offer no channel choice`() {
        val files = kotlinFiles(hubUi)
        assertTrue("the Hub UI sources were not found from ${File(".").absolutePath}", files.isNotEmpty())
        val offenders = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> Regex("""(?<!\w)Channel\(|selectChannel|leaveChannel|ChannelChipRow|getChannels""").containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("channel options in the Hub UI:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun `no in-chat tip sheet or tip call is left`() {
        val files = mainSources.flatMap { kotlinFiles(it) }
        assertTrue("the main sources were not found", files.size > 10)
        val offenders = files.filter { file ->
            file.name == "TipBottomSheet.kt" || Regex("""TipBottomSheet|\bsendTip\s*\(|/hub/tip\b""").containsMatchIn(file.readText())
        }.map { it.path }
        assertTrue("in-chat tipping is back: $offenders", offenders.isEmpty())
    }
}
