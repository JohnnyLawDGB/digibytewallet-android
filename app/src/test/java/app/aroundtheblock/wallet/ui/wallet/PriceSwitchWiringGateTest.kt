package app.aroundtheblock.wallet.ui.wallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gates for the price rules (owner decisions for both apps, 2026-11-09): a
 * price over an hour old converts nothing, and Settings → Display → Show prices off means no
 * price request and no fiat figure. `PriceProviderTest` covers the provider as behaviour; no `app`
 * unit test builds a ViewModel or a composable, so these gates check the screens and view models
 * actually use it. Each one fails, never skips, when the code it looks for is not found.
 */
class PriceSwitchWiringGateTest {

    private val uiRoot = File("src/main/java/app/aroundtheblock/wallet/ui")

    private fun source(rel: String): String =
        File(uiRoot, rel).let {
            assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
            it.readLines()
                .map { l -> l.substringBefore("//") }
                .filterNot { l -> l.trimStart().startsWith("*") || l.trimStart().startsWith("/*") }
                .joinToString("\n")
        }

    /** The text from [from] up to the next occurrence of [until]. */
    private fun String.section(from: String, until: String): String {
        val start = indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return substring(start, end)
    }

    private fun String.indexOfOrFail(what: String): Int =
        indexOf(what).also { assertTrue("cannot find `$what` — the gate is blind, not clean", it >= 0) }

    @Test fun `review checks the conversion's price age before it builds the approval`() {
        val confirm = source("wallet/SendViewModel.kt").section("fun requestConfirm(", "fun cancelConfirm(")
        assertTrue(
            "requestConfirm approves an amount converted at a price over an hour old",
            confirm.indexOfOrFail("PriceProvider.isFreshAt(") < confirm.indexOfOrFail("ApprovedSend.dgb("),
        )
    }

    @Test fun `the Send screen rechecks the price age on resume and on a wall-clock tick`() {
        val screen = source("wallet/SendScreen.kt")
        val resume = screen.section("LifecycleResumeEffect(viewModel) {", "onPauseOrDispose")
        assertTrue("no recheck on resume", resume.contains("viewModel.recheckPrice()"))
        val tick = screen.section("LaunchedEffect(viewModel) {", "viewModel.recheckPrice()")
        assertTrue("no 5 s recheck while the form is open", tick.contains("delay(5_000L)"))
    }

    @Test fun `the home screen's price loop follows the switch`() {
        val loop = source("wallet/WalletViewModel.kt")
            .section("private fun fetchPricePeriodically(", "fun getReceiveAddress(")
        assertTrue("the price loop does not follow Show prices", loop.contains("pricesShown.collectLatest"))
        assertTrue("off does not drop the price held in memory", loop.contains("_price.value = null"))
        assertTrue(
            "the loop fetches before it knows whether prices are on",
            loop.indexOfOrFail("if (!on)") < loop.indexOfOrFail("priceProvider.fetchPrice("),
        )
    }

    @Test fun `turning prices off clears the cached price`() {
        val set = source("settings/SettingsViewModel.kt").section("fun setPricesShown(", "\n    }")
        val off = set.indexOfOrFail("if (!on)")
        assertTrue("setPricesShown(false) does not clear the cached price", off < set.indexOfOrFail("priceProvider.clearCache()"))
        assertTrue(
            "the cache is cleared before the switch is set, so a fetch out could write it again",
            set.indexOfOrFail("PricePrefs.setEnabled(") < off,
        )
    }

    @Test fun `with prices off the Send screen has no toggle and no dollar line`() {
        val screen = source("wallet/SendScreen.kt")
        assertTrue(
            "\"Switch to USD\" is shown with prices off",
            Regex("""if \(pricesShown\) \{\s*TextButton\(onClick = \{ inputIsDgb = !inputIsDgb \}\)""").containsMatchIn(screen),
        )
        screen.indexOfOrFail("if (inputIsDgb || !pricesShown) {")
        assertTrue(
            "the dollars line under the DGB amount is not gated on prices being shown",
            screen.section("if (pricesShown && amountFiat.isNotBlank()) {", "\n                }")
                .contains("R.string.send_approx_usd,"),
        )
        assertEquals(
            "a dollars line outside the gated one",
            1, Regex(Regex.escape("R.string.send_approx_usd,")).findAll(screen).count(),
        )
        assertTrue(
            "turning prices off does not return the form to DGB entry",
            screen.section("LaunchedEffect(pricesShown) {", "LifecycleResumeEffect").contains("inputIsDgb = true"),
        )
    }

    @Test fun `with prices off the hero has no fiat line`() {
        val hero = source("wallet/WalletScreen.kt").section("BalanceDisplay(", "onDigiDollarTap")
        assertTrue("the hero's fiat line is shown with prices off", hero.contains("fiatAmount = if (pricesShown) fiatBalance else null"))
        assertTrue("the hero's currency picker opens with prices off", hero.contains("onFiatTap = if (pricesShown)"))
    }
}
