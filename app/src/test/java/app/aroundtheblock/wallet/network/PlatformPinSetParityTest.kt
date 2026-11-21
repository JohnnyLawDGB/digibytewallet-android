package app.aroundtheblock.wallet.network

import app.aroundtheblock.wallet.core.network.DigiScopePins
import app.aroundtheblock.wallet.core.network.DigistampIdPins
import app.aroundtheblock.wallet.core.network.DigistampPins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DIGISTAMP-XML: a host's pins live in one place. The platform pin-set in network_security_config.xml
 * also covers WebView traffic, so it must equal the host's Kotlin list exactly, the same pins in the
 * same order, and never list a pin that host has retired. scripts/check-pins-live.sh checks the Kotlin lists against
 * the live chains, so this keeps the XML under that check too.
 */
class PlatformPinSetParityTest {

    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/network_security_config.xml"))

    /** host → the pins its domain-config lists, as "sha256/<base64>". */
    private val xmlPins: Map<String, List<String>> = buildMap {
        val configs = doc.getElementsByTagName("domain-config")
        for (i in 0 until configs.length) {
            val config = configs.item(i) as Element
            val domains = config.getElementsByTagName("domain")
            val pins = config.getElementsByTagName("pin")
            val list = (0 until pins.length).map { j ->
                val pin = pins.item(j) as Element
                assertEquals("only SHA-256 pins", "SHA-256", pin.getAttribute("digest"))
                "sha256/" + pin.textContent.trim()
            }
            for (d in 0 until domains.length) put(domains.item(d).textContent.trim(), list)
        }
    }

    /** Every host that has a single Kotlin pin list. */
    private val kotlinPins = mapOf(
        DigistampPins.HOST to DigistampPins.PINS,
        DigistampIdPins.HOST to DigistampIdPins.PINS,
        DigiScopePins.HOST to DigiScopePins.PINS,
    )

    /**
     * What each host has retired. Per host: DigiStamp's two hosts have separate certificates, and the
     * intermediate one of them moved away from (YE2) is the one the other is served by today.
     */
    private val retiredPins = mapOf(
        DigistampPins.HOST to DigistampPins.RETIRED_PINS,
        DigistampIdPins.HOST to DigistampIdPins.RETIRED_PINS,
        DigiScopePins.HOST to DigiScopePins.RETIRED_PINS,
    )

    @Test
    fun `every platform pin-set equals its host's Kotlin list`() {
        assertTrue("the Digistamp pin-set must be declared", DigistampPins.HOST in xmlPins)
        assertTrue("the DigiStamp sign-in host's pin-set must be declared", DigistampIdPins.HOST in xmlPins)
        assertTrue("the sign-in host's pin-set is empty", xmlPins.getValue(DigistampIdPins.HOST).isNotEmpty())
        for ((host, pins) in xmlPins) {
            if (pins.isEmpty()) continue
            val expected = kotlinPins[host]
            assertTrue("$host is pinned in XML but has no Kotlin list", expected != null)
            assertEquals("$host: XML pin-set differs from its Kotlin list", expected, pins)
        }
    }

    @Test
    fun `no retired pin is listed anywhere`() {
        for ((host, listed) in xmlPins) {
            val retired = retiredPins[host].orEmpty().filter { it in listed }
            assertTrue("$host: retired pins still in the XML: $retired", retired.isEmpty())
        }
        for ((host, pins) in kotlinPins) {
            assertTrue("$host: a retired pin is back in the Kotlin list", pins.none { it in retiredPins.getValue(host) })
        }
    }

    @Test
    fun `no pin-set carries an expiration`() {
        // An expired pin-set stops pinning, so an expiration date would make the pins fail open.
        val sets = doc.getElementsByTagName("pin-set")
        for (i in 0 until sets.length) {
            assertTrue("pin-set has an expiration", (sets.item(i) as Element).getAttribute("expiration").isEmpty())
        }
    }

    @Test
    fun `main declares the only network security config`() {
        // A flavor or build-type copy would replace this one unseen by the checks above.
        val copies = File("src").walkTopDown().filter { it.name == "network_security_config.xml" }
            .map { it.invariantSeparatorsPath }.toList()
        assertEquals(listOf("src/main/res/xml/network_security_config.xml"), copies)
    }
}
