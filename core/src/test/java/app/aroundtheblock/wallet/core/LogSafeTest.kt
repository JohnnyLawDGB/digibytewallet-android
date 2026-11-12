package app.aroundtheblock.wallet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASSET-LOG-CRLF: a string that came from outside the device (issuer metadata, a server body, a close
 * reason, an exception message quoting either) reaches a log line only through [LogSafe.text], which
 * leaves no control or format character in it -- so it cannot start a line of its own -- and bounds
 * its length.
 */
class LogSafeTest {

    @Test
    fun `line breaks and other controls are escaped, never kept`() {
        val out = LogSafe.text("x\nW/WalletManager: forged\r\u0000\u001b[31m\u007f\u0085end")
        assertEquals("x\\nW/WalletManager: forged\\r\\u0000\\u001b[31m\\u007f\\u0085end", out)
        assertFalse(out.any { Character.isISOControl(it) })
    }

    @Test
    fun `unicode line separators and format characters are escaped`() {
        val out = LogSafe.text("a b c‮d​e﻿")
        assertEquals("a\\u2028b\\u2029c\\u202ed\\u200be\\ufeff", out)
    }

    @Test
    fun `a backslash is escaped so an escape in the input cannot pass for one of ours`() {
        assertEquals("a\\\\nb", LogSafe.text("a\\nb"))
    }

    @Test
    fun `tab is escaped`() {
        assertEquals("a\\tb", LogSafe.text("a\tb"))
    }

    @Test
    fun `plain text, accents and emoji pass unchanged`() {
        assertEquals("ipfs://bafy x é 日本 😀", LogSafe.text("ipfs://bafy x é 日本 😀"))
    }

    @Test
    fun `an unpaired surrogate is escaped`() {
        assertEquals("a\\ud800b", LogSafe.text("a\uD800b"))
    }

    @Test
    fun `length is bounded and the cut says how much was left out`() {
        val out = LogSafe.text("a".repeat(2048), max = 10)
        assertEquals("aaaaaaaaaa…(+2038)", out)
        assertTrue(LogSafe.text("b".repeat(5000)).length < 200)
    }

    @Test
    fun `the bound counts characters of the input, not of the escapes`() {
        // Ten newlines escape to twenty characters; the bound is on the ten.
        assertEquals("\\n".repeat(3) + "…(+7)", LogSafe.text("\n".repeat(10), max = 3))
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        assertEquals("a…(+2)", LogSafe.text("a😀", max = 2))
    }

    @Test
    fun `null reads as null`() {
        assertEquals("null", LogSafe.text(null))
    }
}
