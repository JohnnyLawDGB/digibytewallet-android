package io.digibyte.core.ipfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AssetMetadataService takes IPFS-fetched JSON whose contents are fully
 * attacker-controlled — anyone can issue an asset, anyone can pin
 * arbitrary bytes at the CID it points to. These tests verify the
 * pre-DB sanitization pass refuses or neutralizes the obvious attack
 * vectors before strings reach the cache row + UI.
 *
 * Targets the [AssetMetadataService.Companion.sanitize] /
 * [isDisplaySafe] predicate directly so we don't need to stand up Room
 * + IPFS infrastructure.
 */
class AssetMetadataSanitizationTest {

    private val SHORT = AssetMetadataService.MAX_SHORT_TEXT_LEN
    private val LONG = AssetMetadataService.MAX_LONG_TEXT_LEN

    private fun short(s: String?): String? =
        AssetMetadataService.sanitize(s, SHORT, allowNewlines = false)

    private fun long_(s: String?): String? =
        AssetMetadataService.sanitize(s, LONG, allowNewlines = true)

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `regular ASCII passes through unchanged`() {
        assertEquals("DigiScope Test v4", short("DigiScope Test v4"))
        assertEquals("Some longer description.", long_("Some longer description."))
    }

    @Test
    fun `unicode letters and emoji pass through`() {
        // Asset names in non-Latin scripts are legitimate.
        assertEquals("デジバイト", short("デジバイト"))
        assertEquals("MyAsset 🪙", short("MyAsset 🪙"))
        assertEquals("Müllerstraße", short("Müllerstraße"))
    }

    @Test
    fun `null and blank inputs return null`() {
        assertNull(short(null))
        assertNull(short(""))
        assertNull(short("   "))
        assertNull(short("\t  "))
    }

    // -------------------------------------------------------------------------
    // BiDi / RTL homoglyph attack
    // -------------------------------------------------------------------------

    @Test
    fun `RLO override is stripped`() {
        // The classic: "Foo-\u202Egnp.exe" displays as "Foo-exe.png".
        // Asset version: scammer issues "USDT-\u202Egnp.evil" hoping the
        // wallet renders it as the legit token. Strip U+202E entirely.
        val attack = "USDT-\u202Egnp.evil"
        val cleaned = short(attack) ?: error("non-null expected")
        assertFalse("RLO must be stripped", cleaned.contains('\u202E'))
        assertEquals("USDT-gnp.evil", cleaned)
    }

    @Test
    fun `all BiDi overrides U+202A through U+202E are stripped`() {
        for (cp in 0x202A..0x202E) {
            val ch = cp.toChar()
            val cleaned = short("a${ch}b") ?: error("non-null expected for cp=$cp")
            assertEquals("ab", cleaned)
            assertFalse(cleaned.contains(ch))
        }
    }

    @Test
    fun `BiDi isolates U+2066 through U+2069 are stripped`() {
        for (cp in 0x2066..0x2069) {
            val ch = cp.toChar()
            val cleaned = short("x${ch}y") ?: error("non-null expected for cp=$cp")
            assertEquals("xy", cleaned)
        }
    }

    // -------------------------------------------------------------------------
    // Control characters
    // -------------------------------------------------------------------------

    @Test
    fun `C0 control chars are stripped`() {
        // Every code point 0x00-0x1F (except newline in long form) must go.
        val attack = "Asset\u0000Name\u0007\u001B"
        val cleaned = short(attack) ?: error("non-null expected")
        assertEquals("AssetName", cleaned)
    }

    @Test
    fun `DEL and C1 control chars are stripped`() {
        // U+007F DELETE, U+0080..U+009F C1 controls
        val attack = "X\u007FY\u0080Z\u009FQ"
        val cleaned = short(attack) ?: error("non-null expected")
        assertEquals("XYZQ", cleaned)
    }

    @Test
    fun `newline is stripped from short text but kept in long`() {
        // Asset names shouldn't contain literal newlines (they collapse
        // visually in the list and look weird), but multi-paragraph
        // descriptions are legitimate.
        assertEquals("AB", short("A\nB"))
        assertEquals("Para1\nPara2", long_("Para1\nPara2"))
    }

    // -------------------------------------------------------------------------
    // Length caps — DOS prevention
    // -------------------------------------------------------------------------

    @Test
    fun `oversized short text is truncated, not rejected`() {
        // Truncate so a legitimate-but-long name still renders the bulk
        // of itself; reject would lose the whole field.
        val payload = "A".repeat(10_000)
        val cleaned = short(payload) ?: error("non-null expected")
        assertEquals(SHORT, cleaned.length)
    }

    @Test
    fun `oversized long text is truncated`() {
        val payload = "A".repeat(50_000)
        val cleaned = long_(payload) ?: error("non-null expected")
        assertEquals(LONG, cleaned.length)
    }

    @Test
    fun `text that is only control chars returns null`() {
        // After stripping, nothing visible remains — caller should treat
        // as missing, not as a present-but-empty name.
        assertNull(short("\u0000\u0001\u202E"))
        assertNull(long_("\u0007\u008F"))
    }

    // -------------------------------------------------------------------------
    // isDisplaySafe predicate spot-checks
    // -------------------------------------------------------------------------

    @Test
    fun `isDisplaySafe accepts ordinary printable chars`() {
        // Note: emoji like 🪙 are surrogate pairs and can't be a single
        // Char — they go through sanitize() naturally because the high
        // and low surrogates are both above U+009F. The list below
        // covers the BMP code points the predicate sees one at a time.
        for (ch in listOf('A', 'z', '0', ' ', '!', '日', 'Ä')) {
            assertTrue("$ch (cp=${ch.code}) should be display-safe",
                AssetMetadataService.isDisplaySafe(ch))
        }
        // Round-trip an emoji string through sanitize to make sure
        // surrogate pairs aren't mangled.
        val emoji = "MyAsset 🪙"
        assertEquals(emoji, short(emoji))
    }

    @Test
    fun `isDisplaySafe rejects all the right ranges`() {
        val dangerous = listOf(
            0x00, 0x01, 0x09, 0x0A, 0x0D, 0x1F,    // C0
            0x7F,                                    // DEL
            0x80, 0x9F,                              // C1
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,  // BiDi overrides
            0x2066, 0x2067, 0x2068, 0x2069,          // BiDi isolates
        )
        for (cp in dangerous) {
            assertFalse("U+%04X should be unsafe".format(cp),
                AssetMetadataService.isDisplaySafe(cp.toChar()))
        }
    }

    // -------------------------------------------------------------------------
    // Round-trip identity for safe inputs
    // -------------------------------------------------------------------------

    @Test
    fun `safe inputs round-trip unchanged within length cap`() {
        val cases = listOf(
            "DigiByte",
            "1234567890",
            "Hello, world!",
            "ÄÖÜß",
            "a b c d e",
        )
        for (s in cases) {
            assertEquals(s, short(s))
            assertEquals(s, long_(s))
        }
    }

    @Test
    fun `attack input does not round-trip`() {
        // Sanity: any input containing dangerous chars must be modified.
        val attacks = listOf(
            "Foo\u0000Bar",
            "USDT-\u202Egnp.evil",
            "X" + "\u202E".repeat(100),
        )
        for (a in attacks) {
            val cleaned = short(a)
            assertNotEquals("attack must be sanitized: ${a.codePoints().toArray().joinToString()}",
                a, cleaned)
        }
    }
}
