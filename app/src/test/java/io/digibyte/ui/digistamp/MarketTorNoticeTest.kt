package io.digibyte.ui.digistamp

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MarketTorNotice]: asked while the Tor setting is on, until the user continues once this session. */
class MarketTorNoticeTest {

    @After fun reset() = MarketTorNotice.resetForTest()

    @Test fun `with Tor off the Market loads without the notice`() {
        assertFalse(MarketTorNotice.mustAsk(torEnabled = false))
    }

    @Test fun `with Tor on the notice is shown until the user continues`() {
        assertTrue(MarketTorNotice.mustAsk(torEnabled = true))
        assertTrue("asking does not answer", MarketTorNotice.mustAsk(torEnabled = true))
        MarketTorNotice.accept()
        assertFalse("Continue holds for the session", MarketTorNotice.mustAsk(torEnabled = true))
    }

    @Test fun `the notice says what the site sees`() {
        assertTrue(MarketTorNotice.BODY.contains("not routed through Tor"))
        assertTrue(MarketTorNotice.BODY.contains("IP address"))
    }
}
