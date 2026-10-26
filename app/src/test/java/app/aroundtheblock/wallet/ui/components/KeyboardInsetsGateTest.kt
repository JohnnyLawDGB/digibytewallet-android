package app.aroundtheblock.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen makes room for the keyboard through [keyboardPadding] / [keyboardBottomPx]
 * (4.1.2 Wave 10, Note 8). Below Android 11 Compose's own IME inset can stay at the height of a
 * keyboard that has already gone, and a screen that reads it directly loses its lower half —
 * Receive's format chips, Copy, Share and the amount field were unreachable with no keyboard up.
 */
class KeyboardInsetsGateTest {

    private val root = File(File("..").canonicalFile, "app/src/main/java")

    @Test fun `no screen reads Compose's IME inset except the keyboard helper`() {
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "KeyboardInsets.kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    if (Regex("""\.imePadding\(\)|\bimePadding\(\)|WindowInsets\.ime\b""").containsMatchIn(code))
                        "${f.relativeTo(root)}:${i + 1}" else null
                }
            }.toList()
        assertTrue("use keyboardPadding()/keyboardBottomPx(): $offenders", offenders.isEmpty())
    }

    @Test fun `closed keyboard below 30 is no padding - the Note 8 numbers`() {
        assertEquals(0, legacyKeyboardPx(systemWindowInsetBottom = 126, stableInsetBottom = 126))
    }

    @Test fun `open keyboard below 30 is the system inset above the navigation bar`() {
        assertEquals(874, legacyKeyboardPx(systemWindowInsetBottom = 1000, stableInsetBottom = 126))
    }

    @Test fun `a system inset below the stable one is never negative padding`() {
        assertEquals(0, legacyKeyboardPx(systemWindowInsetBottom = 0, stableInsetBottom = 126))
    }
}
