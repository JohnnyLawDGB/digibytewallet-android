package app.aroundtheblock.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FLAG_SECURE must survive a secure->secure navigation.
 *
 * NavHost composes the destination (which acquires the flag) while the source is still on screen
 * and disposes the source only at the end of the transition. With one holder per screen and an
 * unconditional clear on dispose, the sequence set/set/clear leaves the flag OFF for the whole
 * destination screen — seed_verify -> seed_passphrase is the only route into passphrase entry and
 * it lost the flag exactly there. The holders must be counted: the window is cleared only when
 * the last one leaves.
 */
class SecureWindowFlagTest {

    private lateinit var flag: SecureWindowFlag
    private val applied = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        applied.clear()
        flag = SecureWindowFlag { secure -> applied += secure }
    }

    @Test
    fun `first holder applies the flag, second holder does not re-apply`() {
        flag.acquire()
        flag.acquire()
        assertEquals(listOf(true), applied)
        assertEquals(2, flag.holders)
    }

    @Test
    fun `secure to secure navigation keeps the flag set after the source disposes`() {
        flag.acquire()   // source screen composes
        flag.acquire()   // destination composes during the transition
        flag.release()   // source disposes at the end of the transition
        assertEquals(listOf(true), applied)
        assertTrue(flag.isSecure)
        assertEquals(1, flag.holders)
    }

    @Test
    fun `flag is cleared only when the last holder leaves`() {
        flag.acquire()
        flag.acquire()
        flag.release()
        flag.release()
        assertEquals(listOf(true, false), applied)
        assertFalse(flag.isSecure)
        assertEquals(0, flag.holders)
    }

    @Test
    fun `release below zero is clamped and does not clear twice`() {
        flag.acquire()
        flag.release()
        flag.release()
        assertEquals(listOf(true, false), applied)
        assertEquals(0, flag.holders)
    }

    @Test
    fun `re-acquire after full release applies the flag again`() {
        flag.acquire()
        flag.release()
        flag.acquire()
        assertEquals(listOf(true, false, true), applied)
        assertTrue(flag.isSecure)
    }

    // ── One counted holder per window ────────────────────────────────────────────────────
    // A dialog is its own window: FLAG_SECURE on the Activity window does not cover it, and the
    // flag on the dialog window must not be cleared by the Activity's holders leaving, or the
    // other way round. The same counted holder, kept per window.

    private val perWindow = mutableListOf<Pair<String, Boolean>>()
    private val windows = SecureWindowFlags<String> { window, secure -> perWindow += window to secure }

    @Test
    fun `a dialog window gets the flag on itself, not on the activity window`() {
        windows.acquire("dialog")
        assertEquals(listOf("dialog" to true), perWindow)
        assertTrue(windows.isSecure("dialog"))
        assertFalse(windows.isSecure("activity"))
    }

    @Test
    fun `each window counts its own holders`() {
        windows.acquire("activity")
        windows.acquire("dialog")
        windows.acquire("dialog")
        windows.release("dialog")
        assertEquals(listOf("activity" to true, "dialog" to true), perWindow)
        assertEquals(1, windows.holders("dialog"))
        assertEquals(1, windows.holders("activity"))
    }

    @Test
    fun `the last holder of one window leaving does not clear another`() {
        windows.acquire("activity")
        windows.acquire("dialog")
        windows.release("dialog")
        assertEquals(listOf("activity" to true, "dialog" to true, "dialog" to false), perWindow)
        assertTrue(windows.isSecure("activity"))
        assertFalse(windows.isSecure("dialog"))
    }

    @Test
    fun `releasing a window nobody holds changes nothing`() {
        windows.release("dialog")
        assertEquals(emptyList<Pair<String, Boolean>>(), perWindow)
        assertEquals(0, windows.holders("dialog"))
    }
}
