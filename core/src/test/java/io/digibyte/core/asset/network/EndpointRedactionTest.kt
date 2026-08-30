package io.digibyte.core.asset.network

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointRedactionTest {
    @Test
    fun `history route drops the wallet address but keeps the route and query`() {
        assertEquals(
            "https://api.digiscope.me/api/digiassets/history/<addr>?limit=50",
            endpointOf("https://api.digiscope.me/api/digiassets/history/dgb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx?limit=50")
        )
    }

    @Test
    fun `routes without an address are unchanged`() {
        val u = "https://assets.digistamp.co/assets/La4WAqZf/data"
        assertEquals(u, endpointOf(u))
    }
}
