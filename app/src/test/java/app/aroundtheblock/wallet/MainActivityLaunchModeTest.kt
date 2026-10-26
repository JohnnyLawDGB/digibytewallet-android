package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * There is one wallet screen: MainActivity is singleTask (4.1.2 row 38).
 *
 * In the standard launch mode every digiid:// link stacked a new MainActivity on the task (three
 * on the Moto after three links, 2026-10-25). The new one's arrival stopped the one beneath, whose
 * onStop locks the wallet, so a login link opened while using the app always met the PIN screen,
 * and MainActivity.onNewIntent -- the path meant to take the link -- never ran.
 */
class MainActivityLaunchModeTest {

    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun `MainActivity is singleTask in every manifest that declares it`() {
        val manifests = File(File("..").canonicalFile, "app/src").listFiles().orEmpty()
            .map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
        val declared = manifests.flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("activity")
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttributeNS(android, "name").endsWith(".MainActivity") }
                .map { file.parentFile.name to (it.getAttributeNS(android, "launchMode") to
                    (if (it.hasAttributeNS(android, "taskAffinity")) it.getAttributeNS(android, "taskAffinity") else null)) }
        }
        assertEquals("MainActivity is declared once, in main", listOf("main"), declared.map { it.first })
        assertEquals("singleTask", declared.single().second.first)
    }

    /** Review R12: with the default affinity, a task another app made with this package's affinity takes the screen in. */
    @Test fun `MainActivity has an empty task affinity`() {
        val file = File(File("..").canonicalFile, "app/src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("activity")
        val main = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttributeNS(android, "name").endsWith(".MainActivity") }
        assertTrue("MainActivity declares no taskAffinity", main.hasAttributeNS(android, "taskAffinity"))
        assertEquals("", main.getAttributeNS(android, "taskAffinity"))
    }
}
