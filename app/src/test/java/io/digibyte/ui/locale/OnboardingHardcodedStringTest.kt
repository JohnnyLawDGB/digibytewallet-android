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
        // The money path. Localised after a user walked a German build screen by screen and
        // found English on every one of them.
        "ui/wallet/WalletScreen.kt",
        "ui/wallet/ReceiveScreen.kt",
        "ui/wallet/TransactionDetailScreen.kt",
        "ui/wallet/SendScreen.kt",
        "ui/settings/SettingsScreen.kt",
        "ui/locale/LanguagePickerSheet.kt",
        "ui/asset/AssetSendScreen.kt",
        "ui/asset/AssetDetailScreen.kt",
        "ui/asset/AssetListScreen.kt",
        "ui/recovery/RecoverFundsScreen.kt",
        "ui/settings/NetworkInfoScreen.kt",
        "ui/settings/AboutScreen.kt",
        "ui/settings/SecuritySettingsScreen.kt",
        "ui/settings/DisplaySettingsScreen.kt",
        "ui/digiid/DigiIdConfirmScreen.kt",
        // The activity row: the most-read component in the app, and the one the source scan
        // missed because it is a shared component rather than a screen. Found by eye on a
        // German build on the Note 8 — which is exactly the failure mode the gate exists to
        // stop, so the file goes IN the gate rather than just getting fixed.
        "ui/components/TransactionItem.kt",
        // AppNavigation is deliberately NOT here. It is a router: ~100 of its literals are route
        // names and argument keys, so an every-literal scan would need an allow-list longer than
        // the file and would stop meaning anything. Its user-visible text is three toast/label
        // strings, now in resources, and its nav labels are R.string references on Screen.
    )

    /** Navigation routes — identifiers the code matches on, never shown to anyone. */
    private val ROUTES = setOf(
        "onboarding", "wallet", "unlock", "pin_setup", "seed_verify", "recover_funds",
        "seed_display/{wordCount}", "seed_display/\$selectedWordCount",
        "settings_security", "settings_network", "settings_display", "settings_reconcile",
        "settings_about",
    )

    /** Compose animation labels, log tags, and format fragments. */
    private val NON_PROSE = setOf(
        "question_transition", "pin_step_label", "UnlockScreen", "MainActivity",
        // Log messages and pref keys, never rendered.
        "battery_prompt_dismissed", "beta_updates",
        "dgb_settings", "digiid://", "connection refused", "backstop wipe failed",
        // Ticker, network name and unit — brand-class, never translated (see strings_wallet.xml).
        "DGB", "TESTNET", "USD",
        // Composites of already-localised parts plus a ticker or a bare number.
        "\$amountPrefix\$amountFormatted DGB", "\$feeFormatted DGB", "\$\$priceFormatted",
        "\$changePrefix \$changeFormatted%",
        // Send: literal address prefixes and value+ticker composites.
        "TD…", "DD…", "%.8f DGB", "\$amountDgb DGB", "\$\$amountFiat", "txid",
        // Build flavour, a version fallback, and the brand+version footer.
        "digiTestnet", "unknown", "DigiByte Wallet v\$versionName",
        // Asset screens: truncated ids, unit composites ("sats" is a unit like DGB) and format
        // patterns. Each is a value plus a symbol, with no sentence to translate.
        "txid \${s.txid.take(12)}…\${s.txid.takeLast(8)}",
        "\$quantityInput \${asset.metadata?.symbol ?: stringResource(R.string.as_tokens)}",
        "\${formatSats(DA_MARKER_SATS_UI)} sats", "≈ \${formatSats(feeSats)} sats",
        "≈ \${formatSats(totalSats)} sats (\${formatDgb(totalSats)} DGB)",
        "\${ownedAsset.utxoCount}", "ID: \${assetId.take(12)}…",
        "\${value.take(12)}…\${value.takeLast(8)}", "\${tx.txid.take(8)}…",
        "MMM dd, yyyy HH:mm", "%,d", "%.\${decimals}f", ".repeat(decimals)}",
        // Recovery: these are MATCHERS against exception and service text, not UI copy. They MUST
        // stay English — translating them would silently stop the friendly-message mapping from
        // ever matching, and the user would get the raw stack-trace text in every language.
        "UnknownHostException", "Unable to resolve host", "SocketTimeoutException", "timeout",
        "seed unavailable", "Couldn\'t reach", "no mappable UTXOs", "seed derivation failed",
        "broadcast",
        // Placeholder and format pattern.
        "0.00123456 DGB", "%.8f", "\$formatted DGB",
        // Settings: ISO currency codes (the NAMES come from java.util.Currency and are already
        // localised), URLs, the derivation path, and format-only fragments.
        "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "KRW", "BRL", "MXN", "INR", "ZAR",
        "SEK", "NOK", "\$it — \${currencyName(it)}", "CoinGecko · Binance",
        "m/84\'/20\'/0\'", "DigiByte Wallet", "v\$versionName",
        "github.com/JohnnyLawDGB/digibytewallet-android", "digibyte.org",
        "github.com/JohnnyLawDGB/digibytewallet-android/issues",
        "• \$range", "  ·  \$it", "10.0.0.5  or  node.example.com:12024",
        // Activity row: value+ticker composites, a date pattern, a truncated address, and the
        // two asset-kind badges (brand names).
        "\$amountPrefix\$typedAmount", "\$amountPrefix\$amountFormatted DGB", "MMM dd, yyyy",
        "\${address.take(8)}…\${address.takeLast(8)}", "DigiDollar", "DigiAsset",
        "Locked — try again in M:SS", "%d:%02d", "settings_view_seed", "DigiIdResult", "\$\$priceFormatted", " · v\$versionName",
        "\\n\${DigiByteUri.encode(address, sats)}",
        // Date/number patterns and a MIME type. NOTE: the date patterns are also pinned to
        // Locale.US at their call sites — a real i18n gap, but a behaviour change, not a
        // missing string, so it is tracked separately rather than silenced here.
        "MMMM yyyy", "MMM dd, yyyy HH:mm:ss", "UTC", "%.2f", "text/plain",
        // Regex artefact: a line of the form `if (x) "a" else "b"` splits across the quotes.
        " else ",
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
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .filterNot { it.contains("Log.") }
            .flatMap { line -> quoted.findAll(line).map { it.groupValues[1] }.toList() }

    @Test fun `the sources this test scans actually exist`() {
        val missing = COVERED.filterNot { File(srcRoot, it).isFile }
        assertTrue("scan list is stale, cannot see: $missing", missing.isEmpty())
        assertTrue("nothing scanned", COVERED.size >= 21)
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
