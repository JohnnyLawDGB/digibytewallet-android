package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GUARD: the Hub chat hands the image loader nothing but a Hub media file on the DigiScope API
 * host ([app.aroundtheblock.wallet.core.hub.HubMedia.imageUrl], the one check, tested in
 * HubChatMediaParsingTest) and the user's own picked image. A link preview's `image_url` names a
 * host the poster chose; fetching it would show that host the address of every wallet that
 * scrolled past, so it is never loaded.
 *
 * It reads code: every `AsyncImage(` in the Hub UI has a `model =` that is either `mediaUrl`, which
 * is only ever assigned from `HubMedia.imageUrl(`, or `pendingImage`, and nothing in the Hub UI
 * reads a preview's `imageUrl` or builds its own image request.
 */
class HubImageSourcesGateTest {

    private val hubUi = File("src/main/java/app/aroundtheblock/wallet/ui/hub")

    private val files: Map<String, String> by lazy {
        hubUi.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.name to KotlinSourceGate.of(it.readText()).code }
            .also { assertTrue("the Hub UI sources were not found", it.size > 5) }
    }

    @Test fun `every image the Hub UI loads is a Hub media URL or the picked image`() {
        val models = files.flatMap { (name, code) ->
            Regex("""AsyncImage\s*\(""").findAll(code).map { call ->
                val model = Regex("""model\s*=\s*([\w.]+)""").find(code, call.range.last)?.groupValues?.get(1)
                "$name: $model"
            }.toList()
        }
        assertTrue("no AsyncImage found: the gate is blind", models.size >= 3)
        val other = models.filterNot { it.endsWith(": mediaUrl") || it.endsWith(": pendingImage") }
        assertEquals("images loaded from something else", emptyList<String>(), other)
    }

    @Test fun `mediaUrl is only ever what HubMedia imageUrl answers`() {
        val assignments = files.flatMap { (name, code) ->
            Regex("""(?:val|var)\s+mediaUrl\s*=\s*([^\n]+)""").findAll(code).map { "$name: ${it.groupValues[1].trim()}" }.toList()
        }
        assertTrue("no mediaUrl found: the gate is blind", assignments.isNotEmpty())
        val other = assignments.filterNot { it.substringAfter(": ").startsWith("HubMedia.imageUrl(") }
        assertEquals(emptyList<String>(), other)
    }

    @Test fun `no preview image URL is read and no image request is built in the Hub UI`() {
        val found = files.flatMap { (name, code) ->
            listOf(
                Regex("""(?<!HubMedia)\.imageUrl\b"""), Regex("""\bImageRequest\b"""),
                Regex("""\brememberAsyncImagePainter\b"""), Regex("""\bSubcomposeAsyncImage\b"""),
            )
                .flatMap { re -> re.findAll(code).map { "$name: ${it.value}" }.toList() }
        }
        assertEquals(emptyList<String>(), found)
    }
}
