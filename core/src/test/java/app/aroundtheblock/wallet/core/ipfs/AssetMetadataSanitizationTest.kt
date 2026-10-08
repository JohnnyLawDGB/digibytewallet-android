package app.aroundtheblock.wallet.core.ipfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.ipfs.AssetMetadataService.Companion.displaySafe

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
        // Char. sanitize() walks code points, so a pair reaches the
        // predicate whole; a lone half is rejected (see below).
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

    // -------------------------------------------------------------------------
    // Category filter (BB-2026-10-08-ricki): everything that is not visible text
    // -------------------------------------------------------------------------

    @Test
    fun `line and paragraph separators never reach a one-line field`() {
        assertEquals("FAKEUSDT", short("FAKE\u2028USDT"))
        assertEquals("FAKEUSDT", short("FAKE\u2029USDT"))
        // A description may keep '\n', and only '\n'.
        assertEquals("line1\nline2", long_("line1\u2028\nline2\u2029"))
    }

    @Test
    fun `format characters are stripped`() {
        val format = listOf(
            0x200E, 0x200F, 0x061C,          // LRM, RLM, ALM
            0x200B, 0x200C, 0x200D, 0x2060,  // ZWSP, ZWNJ, ZWJ, WJ
            0xFEFF, 0x00AD, 0x180E,          // BOM, soft hyphen, Mongolian vowel separator
            0x2061, 0x2064, 0x206A, 0x206F,  // invisible operators, deprecated format chars
            0xFFF9, 0xFFFB,                  // interlinear annotation
        )
        for (cp in format) {
            assertFalse("U+%04X should be unsafe".format(cp), AssetMetadataService.isDisplaySafe(cp))
            assertEquals("U+%04X".format(cp), "AB", short("A" + String(Character.toChars(cp)) + "B"))
        }
        // Astral format characters: the Unicode tag block.
        assertEquals("AB", short("A" + String(Character.toChars(0xE0041)) + "B"))
    }

    @Test
    fun `private use and lone surrogates are stripped`() {
        assertEquals("AB", short("A\uE000B"))
        assertEquals("AB", short("A" + String(Character.toChars(0xF0000)) + "B"))
        assertEquals("AB", short("A\uD800B"))
        assertEquals("AB", short("A\uDC00B"))
    }

    @Test
    fun `a name made only of invisible characters is no name`() {
        assertNull(short("\u200B"))
        assertNull(short("\u200E\u200F\uFEFF"))
        assertNull(short("\u2028"))
    }

    @Test
    fun `the cap counts code points and never splits a pair`() {
        val coin = "🪙"  // one code point, two UTF-16 units
        val out = AssetMetadataService.sanitize("a" + coin.repeat(10), maxLen = 4, allowNewlines = false)!!
        assertEquals("a" + coin.repeat(3), out)
        assertEquals(4, out.codePointCount(0, out.length))
    }

    @Test
    fun `cached rows from older builds display clean`() {
        val stale = AssetMetadataEntity(
            assetId = "La123",
            name = "USDT\u200B",
            symbol = "US\u2028DT",
            description = "first\u2029second\nthird",
            decimals = 8,
            totalSupply = 42,
            rulesJson = "{}",
        )
        val shown = stale.displaySafe()
        assertEquals("USDT", shown.name)
        assertEquals("USDT", shown.symbol)
        assertEquals("firstsecond\nthird", shown.description)
        // Everything that is not display text is untouched.
        assertEquals(stale.copy(name = shown.name, symbol = shown.symbol, description = shown.description), shown)
        // Idempotent.
        assertEquals(shown, shown.displaySafe())
    }
}
