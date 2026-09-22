package io.digibyte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for the wipe entry points: the PIN store is released only by the one shared
 * routine that first checks what the wipe established (`WalletManager.wipeThenReleasePin`).
 *
 * A scan rather than a behaviour test because the entry points are an Activity, two view
 * models and a composable, and the way this regresses is a new entry point that runs the wipe
 * and then clears the PIN on its own — whatever the shape (a swallowed failure followed by the
 * clear, both inside one `try`, or two bare lines in a coroutine). The routine's behaviour is
 * covered by `WalletWipeTest` in `core`.
 */
class WipeCallSiteGateTest {

    private val appRoot = File("src/main/java/io/digibyte")

    /** The wipe entry points: Settings, the unlock screen, the spend dialog, the launch backstop. */
    private val entryPoints = listOf(
        "MainActivity.kt",
        "ui/settings/SettingsViewModel.kt",
        "ui/components/SpendAuth.kt",
        "ui/onboarding/UnlockScreen.kt",
    )

    /**
     * Restoring a wallet replaces a PIN left by an earlier install before there is any wallet
     * for it to guard. It runs no wipe, so it is the one PIN clear outside the shared routine.
     */
    private val pinClearAllowed = setOf("ui/onboarding/OnboardingViewModel.kt")

    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun sources(): List<File> =
        appRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(file: File) = file.relativeTo(appRoot).path

    @Test fun `the scan sees the entry points at all`() {
        val missing = entryPoints.filterNot { File(appRoot, it).isFile }
        assertTrue("entry-point list is stale, cannot see: $missing", missing.isEmpty())
        assertTrue("scanner found no sources — it is blind, not clean", sources().size >= 50)
    }

    @Test fun `no app file runs the bare wipe`() {
        // Any receiver named like a manager: the view models' own `wipeWallet()` entry functions
        // are called as `viewModel.wipeWallet()` / `vm.wipeWallet()` and are not the bare wipe.
        val bare = Regex("""\b\w*[mM]anager\s*\.\s*wipeWallet\s*\(""")
        val offenders = sources().filter { bare.containsMatchIn(code(it)) }.map(::rel)
        assertEquals(
            "files that run the wipe without the shared routine that checks its result: $offenders",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `no app file clears the PIN on its own around a wipe`() {
        val clear = Regex("""\.\s*clearPin\s*\(""")
        val offenders = sources()
            .filter { clear.containsMatchIn(code(it)) }
            .map(::rel)
            .filterNot { it in pinClearAllowed }
        assertEquals(
            "files that clear the PIN store outside the shared routine: $offenders",
            emptyList<String>(), offenders,
        )
    }

    // GUARD, not red-then-green: before the identity-session eraser existed there was no file
    // for these two to read.
    @Test fun `the wallet manager is handed the identity-session eraser`() {
        val module = code(File(appRoot, "di/AppModule.kt"))
        assertTrue("no provider for the eraser", module.contains("fun provideIdentitySessionEraser("))
        assertTrue(
            "WalletManager is built without the eraser, so a wipe would not end the identity sessions",
            Regex("""WalletManager\([^)]*identitySessions\s*=""").containsMatchIn(module),
        )
    }

    @Test fun `ending the web session never creates a WebView`() {
        val eraser = code(File(appRoot, "IdentitySessionEraser.kt"))
        assertTrue(
            "scanner is blind: the eraser no longer goes through CookieManager and WebStorage",
            eraser.contains("CookieManager.getInstance()") && eraser.contains("WebStorage.getInstance()"),
        )
        assertTrue("a WebView is constructed", !Regex("""\bWebView\s*\(""").containsMatchIn(eraser))
    }

    @Test fun `every wipe entry point goes through the shared routine`() {
        val missing = entryPoints.filterNot { code(File(appRoot, it)).contains("wipeThenReleasePin(") }
        assertEquals("entry points that do not use the shared routine: $missing", emptyList<String>(), missing)
    }

    /** The range of the block a `withContext(NonCancellable …) {` opens in [text], braces matched. */
    private fun uncancellableBlock(text: String): IntRange? {
        val opener = Regex("""withContext\s*\([^)]*NonCancellable[^)]*\)\s*\{""").find(text) ?: return null
        val open = text.indexOf('{', opener.range.first)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return open..i
            }
        }
        return null
    }

    /**
     * The verdict is reported from INSIDE the block that runs to its end. A wipe flips the wallet
     * state; navigation then pops the screen that started it, and with it the view model's scope,
     * so a report placed after that block is resumed as cancelled and the one message the user
     * gets is never shown.
     */
    @Test fun `the one wipe message is reported from inside the block that outlives the screen`() {
        val reporters = listOf("ui/settings/SettingsViewModel.kt", "ui/components/SpendAuth.kt")
        val blind = reporters.filter { uncancellableBlock(code(File(appRoot, it))) == null }
        assertEquals("scanner is blind: no block that runs to its end found in: $blind", emptyList<String>(), blind)
        val notInside = reporters.filterNot { rel ->
            val text = code(File(appRoot, rel))
            val block = uncancellableBlock(text)!!
            // The definition of the helper is not a call to it.
            val calls = Regex("""(?<!fun )showWipeIncompleteNotice\s*\(""")
                .findAll(text).map { it.range.first }.toList()
            calls.isNotEmpty() && calls.all { it in block }
        }
        assertEquals(
            "the wipe's one message can be lost to the cancellation the wipe itself causes in: $notInside",
            emptyList<String>(), notInside,
        )
    }

    /**
     * The web session's result must not be a question asked of the cookie store in the statement
     * after the removal was handed to it: that answer is about the state BEFORE the removal, so a
     * session that really was ended would report itself unfinished. The evidence is the removal
     * the store accepted and the flush that forced it to disk, and the store's own answer arrives
     * through the callback it is given.
     */
    @Test fun `the web session's result is not read back from the removal that was just handed over`() {
        val eraser = code(File(appRoot, "IdentitySessionEraser.kt"))
        assertTrue(
            "scanner is blind: the eraser no longer removes the cookies at all",
            eraser.contains("removeAllCookies"),
        )
        assertTrue(
            "the result is a read-back of an asynchronous removal (hasCookies)",
            !eraser.contains("hasCookies("),
        )
        assertTrue(
            "the cookie store's own answer is discarded (removeAllCookies(null))",
            !Regex("""removeAllCookies\s*\(\s*null\s*\)""").containsMatchIn(eraser),
        )
    }

    /**
     * While a wipe is owed the unlock screen takes no credential and retries the wipe instead. That
     * hold is BOUNDED: a store that will not take its write must never add up to a wallet that can
     * no longer be opened at all. After the bound the screen either offers onboarding (nothing is
     * left on the device for the owed wipe to hold it for) or stands down and takes the PIN again.
     */
    @Test fun `the unlock screen's hold on an owed wipe is bounded`() {
        val screen = code(File(appRoot, "ui/onboarding/UnlockScreen.kt"))
        assertTrue("scanner is blind: the screen no longer knows about an owed wipe", screen.contains("isWipePending()"))
        val unbounded = Regex("""if\s*\(\s*pinManager\s*\.\s*isWipePending\s*\(\s*\)\s*\)""").findAll(screen).count()
        assertEquals("gates that hold the screen for an owed wipe with no bound: $unbounded", 0, unbounded)
        assertTrue("the hold has no bound", screen.contains("OWED_WIPE_HOLD_ATTEMPTS"))
        assertTrue(
            "the bound has no way out for a device with no wallet left on it",
            screen.contains("releaseOwedWipeIfNoWalletIsLeft("),
        )
    }

    /**
     * The unlock screen shows the wallet only when the wallet actually loaded.
     *
     * A store that did not take its write reads as empty for the rest of the process while its
     * durable copy is still there, so the credential can be right and the record still not be
     * readable until the next start. Navigating anyway puts the owner on a wallet screen with
     * nothing in it, and the only way off it is to kill the app — so the result of the load
     * decides, and it must stay decided here rather than being read and dropped.
     */
    @Test
    fun `the unlock screen opens the wallet only when it loaded`() {
        val src = code(File(appRoot, "ui/onboarding/UnlockScreen.kt"))
        val body = src.substringAfter("suspend fun performUnlockAndNavigate")
            .substringBefore("\n    // Attempt biometric automatically")
        assertTrue(
            "performUnlockAndNavigate must keep the load's answer, not discard it",
            Regex("""val\s+opened\s*=\s*withContext""").containsMatchIn(body),
        )
        val guard = body.indexOf("if (!opened)")
        val nav = body.indexOf("navController.navigate(\"wallet\")")
        assertTrue("the answer must be acted on", guard >= 0)
        assertTrue("the wallet route must be reached only past that check", nav > guard)
        assertTrue(
            "the check must leave rather than fall through to the wallet route",
            body.substring(guard, nav).contains("return"),
        )
    }

}
