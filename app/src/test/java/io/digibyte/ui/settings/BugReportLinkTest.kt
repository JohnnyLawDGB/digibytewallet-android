package io.digibyte.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure coverage for the bug-report deep-link assembly (device gathering is
 *  Android-side and not tested here). */
class BugReportLinkTest {

    @Test
    fun `assembles and url-encodes params in order`() {
        val url = assembleReportUrl(
            "https://digiscope.me/report",
            listOf("type" to "bug", "model" to "Pixel 10 Pro", "android" to "17"),
        )
        assertEquals("https://digiscope.me/report?type=bug&model=Pixel+10+Pro&android=17", url)
    }

    @Test
    fun `omits empty-valued params (e-g- unknown page size)`() {
        val url = assembleReportUrl(
            "https://digiscope.me/report",
            listOf("type" to "bug", "pagesize" to "", "sync" to "Synced"),
        )
        assertEquals("https://digiscope.me/report?type=bug&sync=Synced", url)
    }

    @Test
    fun `no params yields base only`() {
        assertEquals("https://digiscope.me/report", assembleReportUrl("https://digiscope.me/report", emptyList()))
    }

    @Test
    fun `all-empty params yield base only (no trailing question mark)`() {
        assertEquals(
            "https://digiscope.me/report",
            assembleReportUrl("https://digiscope.me/report", listOf("a" to "", "b" to "")),
        )
    }
}
