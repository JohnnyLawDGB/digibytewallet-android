package app.aroundtheblock.wallet.ui.locale

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The funds scan's result lines (ReconcileScreen) and the Market's Tor notice (DigistampScreen)
 * read their text from string resources, which exist in every language ([LocaleResourceParityTest]
 * checks the other locales against English). ReconcileScreen as a whole is not yet in
 * [OnboardingHardcodedStringTest]'s list, so these lines are pinned here by their English text.
 */
class ScanAndMarketStringsTest {

    private val srcRoot = File("src/main/java/app/aroundtheblock/wallet")
    private val english = File("src/main/res/values/strings_wallet.xml").readText()

    /** Key → English text. */
    private val scanStrings = linkedMapOf(
        "reconcile_outputs_found" to "Outputs found on chain",
        "reconcile_found_not_compared" to "Found on chain, not compared",
        "reconcile_spendable" to "Spendable",
        "reconcile_wallet_spendable" to "Wallet spendable balance",
        "reconcile_matches_wallet" to "Matches the wallet\\'s spendable balance.",
        "reconcile_does_not_match_wallet" to "Does not match the wallet\\'s spendable balance — see below.",
        "reconcile_held_asset" to "Held for a DigiAsset",
        "reconcile_held_unknown" to "Held: asset state unknown",
        "reconcile_proven_plain" to "Plain DGB, spendable after restart",
        "reconcile_digidollar_outputs" to "DigiDollar outputs",
        "reconcile_immature" to "Immature (not yet spendable)",
        "reconcile_pending" to "Pending in the wallet",
        "reconcile_not_in_wallet" to "Not in this wallet",
        "reconcile_wallet_only" to "In the wallet, not reported by the node",
        "reconcile_no_missing_funds" to "No missing funds found.",
        "reconcile_txs_already_in_wallet" to "Transactions already in wallet",
        "reconcile_txs_not_added" to "Transactions not added (not this wallet\\'s)",
        "reconcile_stage_comparing" to "Comparing with the wallet…",
    )

    private val marketStrings = linkedMapOf(
        "market_tor_notice_title" to "Market is not routed through Tor",
        "market_tor_notice_body" to "Market is not routed through Tor; the site will see your IP address.",
        "market_tor_notice_continue" to "Continue",
    )

    private fun englishText(key: String): String? =
        Regex("""<string name="$key">(.*?)</string>""").find(english)?.groupValues?.get(1)

    private fun literals(rel: String): Set<String> {
        val gate = KotlinSourceGate.of(File(srcRoot, rel).readText())
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(gate.written).map { it.groupValues[1] }.toSet()
    }

    @Test fun `every key exists in English with its text`() {
        for ((key, text) in scanStrings + marketStrings) {
            assertEquals("English text of $key", text, englishText(key))
        }
    }

    @Test fun `the scan result reads its lines from resources`() {
        val rel = "ui/settings/ReconcileScreen.kt"
        val source = File(srcRoot, rel).readText()
        val left = literals(rel)
        for ((key, text) in scanStrings) {
            val plain = text.replace("\\'", "'")
            assertTrue("$rel still shows \"$plain\" as a literal", plain !in left)
            assertTrue("$rel does not use R.string.$key", source.contains("R.string.$key"))
        }
    }

    @Test fun `the scan's comparing stage is named by the service and worded by the screen`() {
        val service = File("../core/src/main/java/app/aroundtheblock/wallet/core/reconcile/ChainReconciliationService.kt").readText()
        assertTrue("the service does not name its comparing stage",
            Regex("""State\.Scanning\(\s*STAGE_COMPARING\b""").containsMatchIn(service))
        val screen = File(srcRoot, "ui/settings/ReconcileScreen.kt").readText()
        assertTrue("the screen does not word the comparing stage",
            Regex("""ChainReconciliationService\.STAGE_COMPARING\s*->\s*stringResource\(R\.string\.reconcile_stage_comparing\)""")
                .containsMatchIn(screen))
    }

    @Test fun `the Market notice reads its text from resources`() {
        val screen = File(srcRoot, "ui/digistamp/DigistampScreen.kt").readText()
        for (constant in listOf("MarketTorNotice.TITLE", "MarketTorNotice.BODY", "MarketTorNotice.CONTINUE")) {
            assertTrue("DigistampScreen still shows $constant", !screen.contains(constant))
        }
        for (key in marketStrings.keys) {
            assertTrue("DigistampScreen does not use R.string.$key", screen.contains("R.string.$key"))
        }
    }
}
