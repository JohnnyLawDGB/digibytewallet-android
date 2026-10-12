package app.aroundtheblock.wallet.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every send confirmation and the PIN dialog protect their OWN window.
 *
 * A Compose `Dialog` is a separate window. A flag set on the screen behind it — the obscured-touch
 * filter on the Activity's view, or FLAG_SECURE on the Activity's window — does nothing for the
 * dialog: touches on the dialog are not filtered, and the dialog is captured by a screenshot and by
 * the task-switcher snapshot. So the protection lives in one place, [SensitiveDialog], which reads
 * the dialog's window (its DialogWindowProvider), sets the obscured-touch filter on that window's
 * views, takes FLAG_SECURE for that window through the counted [SecureWindow] helper, and asks the
 * system to hide overlay windows while it is shown (Android 12 and later).
 *
 * ## Why this is a source-level gate
 *
 * None of these properties can be observed from a JVM unit test: the dialog window exists only
 * on a device, and the project runs no Compose UI tests in CI. Reading the source is the check that
 * fails the build when a refactor puts a spend or PIN dialog back on a plain `Dialog`, or sets the
 * touch filter on a view read outside the dialog again.
 */
class SensitiveDialogGateTest {

    private val main = File("src/main/java/app/aroundtheblock/wallet")
    private val components = File(main, "ui/components")

    private fun strip(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    private fun source(file: File): String {
        assertTrue("missing ${file.path}", file.exists())
        return strip(file.readText())
    }

    private val mainSources: List<File>
        get() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue("no sources under ${main.path} — the gate is blind", it.size > 50) }

    /** The body of composable [name] in [src]: from its declaration to the next top-level declaration. */
    private fun function(src: String, name: String): String {
        val start = Regex("fun $name\\s*\\(").find(src)?.range?.first
            ?: throw AssertionError("cannot find fun $name — the gate is blind, not clean")
        val next = Regex("\n(@Composable|private fun |internal fun |fun |class |object |private class |const val )")
            .findAll(src, start + 1).firstOrNull()?.range?.first ?: src.length
        return src.substring(start, next)
    }

    /** A call to the platform `Dialog(` or `AlertDialog(` — not `SensitiveDialog(` or `PinVerifyDialog(`. */
    private val plainDialogCall = Regex("(?<![A-Za-z0-9_])(androidx\\.compose\\.[a-z0-9.]+\\.)?(Alert)?Dialog\\s*\\(")

    private val sensitiveDialogs = listOf(
        "ui/wallet/SendScreen.kt" to "SendConfirmationDialog",
        "ui/wallet/SendScreen.kt" to "DigiDollarConfirmationDialog",
        "ui/asset/AssetSendScreen.kt" to "AssetSendConfirmDialog",
        "ui/components/PinVerifyDialog.kt" to "PinVerifyDialog",
    )

    @Test
    fun `SensitiveDialog reads its own window and protects it`() {
        val src = source(File(components, "SensitiveDialog.kt"))
        assertTrue("SensitiveDialog is not a composable", src.contains("fun SensitiveDialog("))
        // Its own window: the one the dialog's DialogWindowProvider owns, never the Activity's.
        assertTrue("SensitiveDialog does not read the dialog's DialogWindowProvider", src.contains("DialogWindowProvider"))
        assertFalse("SensitiveDialog reads the Activity window", src.contains("Activity"))
        assertFalse("SensitiveDialog reads a window from LocalContext", src.contains("LocalContext"))
        // The touch filter on the dialog window's views.
        assertTrue("SensitiveDialog does not set the obscured-touch filter", src.contains("filterTouchesWhenObscured = true"))
        assertTrue("the obscured-touch filter is not on the dialog window's decor view", src.contains(".decorView"))
        // FLAG_SECURE through the counted helper, for this window — not a second raw setter.
        assertTrue("SensitiveDialog does not take FLAG_SECURE through SecureWindow(window)", Regex("SecureWindow\\(\\s*window").containsMatchIn(src))
        for (raw in listOf("FLAG_SECURE", "addFlags(", "clearFlags(", "setFlags(")) {
            assertFalse("SensitiveDialog sets the window's secure flag itself ($raw)", src.contains(raw))
        }
        // Compose re-applies the dialog's securePolicy on every recomposition: the dialog is opened with
        // the one policy that agrees with the holder (see SensitiveDialogSecureFlagTest), and no other.
        assertTrue("the dialog is not opened with sensitiveDialogProperties()", src.contains("properties = sensitiveDialogProperties()"))
        for (other in listOf("SecureFlagPolicy.Inherit", "SecureFlagPolicy.SecureOff")) {
            assertFalse("SensitiveDialog names $other", src.contains(other))
        }
        // Overlays hidden from Android 12 (API 31, VERSION_CODES.S), where the call exists.
        assertTrue("SensitiveDialog does not hide overlay windows", src.contains("setHideOverlayWindows(true)"))
        assertTrue("the overlay call is not guarded by Android 12", src.contains("Build.VERSION_CODES.S"))
    }

    @Test
    fun `the manifest asks for the permission that hides overlays`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml does not declare HIDE_OVERLAY_WINDOWS: setHideOverlayWindows has no effect without it",
            manifest.contains("android.permission.HIDE_OVERLAY_WINDOWS"),
        )
    }

    @Test
    fun `SecureWindow takes FLAG_SECURE for a given window through a counted holder`() {
        val src = source(File(components, "SecureWindow.kt"))
        assertTrue("SecureWindow has no overload for a given window", Regex("fun SecureWindow\\(\\s*window: Window").containsMatchIn(src))
        assertEquals(
            "SecureWindow.kt sets FLAG_SECURE in more than one place",
            1, Regex(Regex.escape("addFlags(WindowManager.LayoutParams.FLAG_SECURE)")).findAll(src).count(),
        )
        assertEquals(
            "SecureWindow.kt clears FLAG_SECURE in more than one place",
            1, Regex(Regex.escape("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)")).findAll(src).count(),
        )
    }

    @Test
    fun `no file sets the obscured-touch filter on a view read outside a dialog`() {
        val offenders = mainSources
            .filter { it.name != "SensitiveDialog.kt" }
            .filter { strip(it.readText()).contains("filterTouchesWhenObscured") }
            .map { it.relativeTo(main).path }
        assertEquals("these files set the obscured-touch filter outside SensitiveDialog", emptyList<String>(), offenders)
    }

    @Test
    fun `every send confirmation and the PIN dialog go through SensitiveDialog`() {
        val failures = mutableListOf<String>()
        for ((path, name) in sensitiveDialogs) {
            val body = function(source(File(main, path)), name)
            if (!body.contains("SensitiveDialog(")) failures += "$name ($path) does not use SensitiveDialog"
            plainDialogCall.find(body)?.let { failures += "$name ($path) opens a plain ${it.value.trim()}" }
            if (body.contains("LocalView")) failures += "$name ($path) reads the view outside its dialog"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `every confirmation or PIN dialog in the app goes through SensitiveDialog`() {
        // Catches a new spend or PIN dialog added later under the naming the app uses.
        val declared = Regex("fun ([A-Za-z]*(Confirm|Confirmation|Pin)[A-Za-z]*Dialog)\\s*\\(")
        val failures = mutableListOf<String>()
        var seen = 0
        for (file in mainSources) {
            val src = strip(file.readText())
            for (m in declared.findAll(src)) {
                seen++
                val body = function(src, m.groupValues[1])
                if (!body.contains("SensitiveDialog(") || plainDialogCall.containsMatchIn(body)) {
                    failures += "${m.groupValues[1]} (${file.relativeTo(main).path})"
                }
            }
        }
        assertTrue("found $seen confirmation/PIN dialogs, expected at least ${sensitiveDialogs.size}", seen >= sensitiveDialogs.size)
        assertEquals("not routed through SensitiveDialog", emptyList<String>(), failures)
    }

    @Test
    fun `the PIN keypad screens hold FLAG_SECURE`() {
        // The task-switcher snapshot of a secure window is blank; the keypad must not be in one.
        for (path in listOf("ui/onboarding/UnlockScreen.kt", "ui/onboarding/PinSetupScreen.kt")) {
            val src = source(File(main, path))
            assertTrue("$path does not apply SecureWindow()", src.contains("SecureWindow()"))
        }
    }

    @Test
    fun `FLAG_SECURE is set and cleared only by the counted holder in SecureWindow kt`() {
        // A raw clear anywhere else releases the flag under every counted holder of that window:
        // a screen leaving with clearFlags while the unlock screen holds the window leaves the
        // keypad capturable with the count still at one.
        val offenders = mainSources
            .filter { it.name != "SecureWindow.kt" }
            .filter { strip(it.readText()).contains("FLAG_SECURE") }
            .map { it.relativeTo(main).path }
        assertEquals("these files set or clear FLAG_SECURE themselves", emptyList<String>(), offenders)
        assertTrue(
            "SeedViewScreen does not hold FLAG_SECURE through SecureWindow()",
            source(File(main, "ui/settings/SeedViewScreen.kt")).contains("SecureWindow()"),
        )
    }

    @Test
    fun `SecureWindow keeps one count per window and no process-wide window`() {
        // One count for the process, applied to whichever window acquired first, leaves a second
        // window (an Activity created while the first still holds) without the flag.
        val src = source(File(components, "SecureWindow.kt"))
        assertFalse("SecureWindow.kt keeps a process-wide current window", src.contains("currentWindow"))
        assertFalse("SecureWindow.kt keeps a process-wide count", src.contains("processFlag"))
        val screen = function(src, "SecureWindow")
        assertTrue("SecureWindow() does not go through the per-window holder", screen.contains("SecureWindow(window)") || screen.contains("windowFlags."))
    }
}
