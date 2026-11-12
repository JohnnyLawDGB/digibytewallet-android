package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * After a cancelled or failed device-lock prompt, the next correct PIN offers the prompt again,
 * every time; the unlock screen never wedges in a state only a process restart clears.
 *
 * Seen on a Note 8 (Android 9): the auth-bound seed key was outside its window, the PIN was right,
 * the device-lock prompt was cancelled, and from then on every PIN entry in that process just left
 * "Unlock failed" on the screen. The prompt itself is asked for afresh on every attempt
 * (`isAuthRetry` is a parameter, not remembered). The latch was the PIN entry: a correct PIN never
 * cleared it, so after an attempt that did not navigate it stayed full, and the keypad takes a digit
 * only while the entry is short. Every later digit was ignored, no unlock started, and the error was
 * never cleared, since only a taken digit clears it.
 */
class UnlockRetryAfterCancelTest {

    private val length = 6
    private val pin = "258147"

    /** Types [digits] into [start] the way the keypad does; returns the entry and the PINs it submitted. */
    private fun type(start: String, digits: String): Pair<String, List<String>> {
        var entry = start
        val submitted = mutableListOf<String>()
        for (d in digits) {
            when (val press = UnlockPinEntry.press(entry, d.toString(), length)) {
                UnlockPinEntry.Press.Ignored -> Unit
                is UnlockPinEntry.Press.Typed -> entry = press.entry
                is UnlockPinEntry.Press.Complete -> { entry = press.entry; submitted += press.entry }
            }
        }
        return entry to submitted
    }

    @Test fun `a correct PIN after a cancelled device-lock prompt starts an unlock again, every time`() {
        var entry = ""
        repeat(5) { attempt ->
            val (typed, submitted) = type(entry, pin)
            assertEquals("attempt ${attempt + 1}: the PIN was not submitted", listOf(pin), submitted)
            // The PIN is right; the key needs the device lock; the prompt is cancelled. The attempt
            // ends without opening the wallet.
            entry = UnlockPinEntry.afterUnlockEnded(typed)
        }
    }

    @Test fun `an attempt that ended leaves room for a whole PIN`() {
        assertEquals("", UnlockPinEntry.afterUnlockEnded(pin))
        assertEquals("", UnlockPinEntry.afterUnlockEnded(""))
    }

    @Test fun `the keypad takes digits up to the length and no further`() {
        assertEquals(UnlockPinEntry.Press.Typed("2"), UnlockPinEntry.press("", "2", length))
        assertEquals(UnlockPinEntry.Press.Complete(pin), UnlockPinEntry.press(pin.dropLast(1), pin.last().toString(), length))
        assertEquals(UnlockPinEntry.Press.Ignored, UnlockPinEntry.press(pin, "1", length))
    }

    // ---- source gate: the screen uses these rules, on every way out of an attempt ----

    private val screen = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/ui/onboarding/UnlockScreen.kt").readText())

    private fun function(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(screen.code) ?: error("scanner is blind: no $name")
        return screen.blockAt(screen.code.indexOf('{', declared.range.last)) ?: error("unbalanced $name")
    }

    @Test fun `every way out of an unlock attempt spends the entry`() {
        val body = function("performUnlockAndNavigate")
        val text = screen.code.substring(body)
        val fin = Regex("""\bfinally\s*\{""").findAll(text).toList()
        assertEquals("scanner is blind: the unlock attempt has no single finally", 1, fin.size)
        val block = screen.blockAt(body.first + fin.single().range.last) ?: error("unbalanced finally")
        assertTrue(
            "the attempt's finally does not spend the PIN entry",
            KotlinSourceGate.squeeze(screen.code.substring(block)).contains("currentInput = UnlockPinEntry.afterUnlockEnded(currentInput)"),
        )
    }

    @Test fun `the keypad decides through UnlockPinEntry`() {
        assertEquals("the keypad no longer asks UnlockPinEntry what a digit does", 1, screen.calls("UnlockPinEntry.press").size)
        assertTrue(
            "the keypad still guards on the entry length itself",
            !Regex("""currentInput\.length\s*<\s*UNLOCK_PIN_LENGTH""").containsMatchIn(screen.code),
        )
    }

    /** A correct PIN followed by a cancelled prompt is not a wrong PIN: the device-lock path never
     *  touches the PIN rate limit (its counter moves only inside `verifyPin`). */
    @Test fun `the device-lock path does not touch the PIN rate limit`() {
        val body = function("performUnlockAndNavigate")
        val catch = Regex("""catch\s*\(\s*e\s*:\s*[\w.]*KeystoreUserAuthRequiredException\s*\)\s*\{""").find(screen.code, body.first)
            ?: error("scanner is blind: no KeystoreUserAuthRequiredException catch")
        val block = screen.blockAt(catch.range.last) ?: error("unbalanced catch")
        assertTrue("scanner is blind: the catch no longer prompts", screen.calls("biometricAuth.authenticateDeviceCredential", block).size == 1)
        assertTrue("the device-lock path touches PinManager", !screen.code.substring(block).contains("pinManager"))
    }
}
