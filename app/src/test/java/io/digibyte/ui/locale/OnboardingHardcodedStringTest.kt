package io.digibyte.ui.locale

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screens a new user is forced through must contain no hardcoded English.
 *
 * ## Why a source scan and not a UI test
 *
 * Translating a screen means replacing literals with `stringResource`, and the way it fails is by
 * missing one. The miss is invisible in English — the screen looks right — and invisible to
 * [LocaleResourceParityTest] too, because a string that was never extracted has no key to be
 * missing from any language. Every language is equally broken and nothing reports it.
 *
 * It happened three times before this test existed. The seed-phrase security tips stayed English
 * inside an inline `listOf(...)` while the heading around them translated. Then the verification
 * screen's Next/Finish buttons. Then its success text and the whole PIN setup and unlock flow —
 * including "Too many failed attempts — wiping wallet", which is the last thing a user reads
 * before their wallet is erased.
 *
 * All three were found by eye, on screenshots, after shipping. That does not scale to twelve
 * languages.
 *
 * ## Why it scans every literal
 *
 * The first version matched only `text = "..."` and sailed straight past
 * `text = if (last) "Finish" else "Next"` and `Text("Set Up PIN", ...)` — the exact lines it was
 * written for. A gate that cannot see the bug that prompted it is worse than no gate, because it
 * reports green. So it scans every string literal and subtracts a written-down list of the
 * non-prose ones, which keeps each exemption a visible decision in the diff.
 *
 * ## Scope
 *
 * Limited to the forced-path screens in [COVERED]: the ones a user cannot skip, which run before
 * Settings is reachable. The rest of the app is not localised yet and would bury a real finding
 * under hundreds of known gaps. Add screens here as they are translated.
 */
class OnboardingHardcodedStringTest {

    private val srcRoot = File("src/main/java/io/digibyte")

    private val COVERED = listOf(
        "ui/onboarding/OnboardingScreen.kt",
        "ui/onboarding/SeedDisplayScreen.kt",
        "ui/onboarding/SeedVerifyScreen.kt",
        "ui/onboarding/PinSetupScreen.kt",
        "ui/onboarding/UnlockScreen.kt",
        // Not an onboarding screen, but its two dialogs fire immediately after onboarding and
        // before the user has seen anything else — the battery one explains how to stop the
        // wallet losing its network connection, which is useless to someone who cannot read it.
        "MainActivity.kt",
    )

    /** Navigation routes — identifiers the code matches on, never shown to anyone. */
    private val ROUTES = setOf(
        "onboarding", "wallet", "unlock", "pin_setup", "seed_verify", "recover_funds",
        "seed_display/{wordCount}", "seed_display/\$selectedWordCount",
    )

    /** Compose animation labels, log tags, and format fragments. */
    private val NON_PROSE = setOf(
        "question_transition", "pin_step_label", "UnlockScreen", "MainActivity",
        // Log messages and pref keys, never rendered.
        "battery_prompt_dismissed", "beta_updates",
        "dgb_settings", "digiid://", "connection refused", "backstop wipe failed",
        "Captured Digi-ID deep link for \${intent.data?.host}",
        "\$number", "• \$tip", "%d:%02d", ", ", "DigiByte",
    )

    private val quoted = Regex("\"([^\"\\n]{2,})\"")

    /**
     * Literals are collected per line, with `//` comments stripped first. Scanning the file as one
     * blob made the regex span from a quote inside a comment to a quote hundreds of lines later,
     * reporting whole functions as one "string" — noise that would have got the gate deleted.
     */
    private fun literalsIn(file: File): List<String> =
        file.readLines()
            .map { it.substringBefore("//") }
            // KDoc/block-comment bodies quote UI copy while explaining it, and log lines are for
            // us, not users. Neither is translatable, and both would otherwise be reported as
            // findings the reader has to dismiss by hand every run.
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.contains("Log.") }
            .flatMap { line -> quoted.findAll(line).map { it.groupValues[1] }.toList() }

    @Test fun `the sources this test scans actually exist`() {
        val missing = COVERED.filterNot { File(srcRoot, it).isFile }
        assertTrue("scan list is stale, cannot see: $missing", missing.isEmpty())
        assertTrue("nothing scanned", COVERED.size >= 5)
    }

    @Test fun `the scan sees literals at all`() {
        // Without this, a rename or a bad regex reduces the gate to observing nothing and passing.
        val total = COVERED.sumOf { literalsIn(File(srcRoot, it)).size }
        assertTrue("scanner found no literals — it is blind, not clean", total >= 15)
    }

    @Test fun `no forced-path screen shows an untranslated literal`() {
        val offenders = COVERED.flatMap { rel ->
            literalsIn(File(srcRoot, rel))
                .filterNot { it in ROUTES || it in NON_PROSE }
                // Prose has letters. Symbols, spacers and pure numbers are not translatable.
                .filter { it.any(Char::isLetter) }
                .map { "$rel: \"$it\"" }
        }
        assertTrue(
            "hardcoded English on a screen the user cannot skip:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
