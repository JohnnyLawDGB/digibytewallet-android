package io.digibyte.ui.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (B236): the DigiDollar balance on the home screen is a button that opens DigiDollar
 * receive — the tester's first instinct on v4.0.81 was to tap it, and nothing happened.
 */
class DigiDollarBalanceOpensReceiveGateTest {

    private fun src(rel: String) = File("src/main/java/io/digibyte/$rel").readText()

    @Test fun `the pill is tappable and the home screen wires it to DigiDollar receive`() {
        val pill = src("ui/components/BalanceDisplay.kt")
        assertTrue(pill.contains("onClick = onDigiDollarTap"))
        assertTrue("the tap is not announced as a button", pill.contains("role = androidx.compose.ui.semantics.Role.Button"))
        assertTrue(src("ui/wallet/WalletScreen.kt").contains("onDigiDollarTap = onNavigateReceiveDigiDollar"))
        val nav = src("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("onNavigateReceiveDigiDollar = { navController.navigate(\"receive_dd\") }"))
        val route = nav.substring(nav.indexOf("composable(\"receive_dd\")"))
        assertTrue("receive_dd does not open on the DigiDollar format", route.substring(0, route.indexOf("\n            }\n")).contains("initialFormat = 3"))
    }
}
