package io.digibyte.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate, not a behaviour test: it proves each value-moving / identity
 * screen references the shared gate, and that no screen still performs the "no biometric →
 * proceed" branch on its own. The behaviour of the gate itself is covered by
 * [SpendAuthPolicyTest]; this exists because the seven sites were found ungated one at a
 * time, and a new site (or a refactor of an old one) that quietly drops the gate compiles.
 */
class SpendGateWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    private val gatedScreens = listOf(
        "wallet/SendScreen.kt",            // DGB send + DigiDollar send
        "asset/AssetSendScreen.kt",        // DigiAsset send
        "digiid/DigiIdConfirmScreen.kt",   // Digi-ID approve
        "recovery/RecoverFundsScreen.kt",  // native / external / foreign sweep
        "hub/ProfileView.kt",              // Hub quick-login (identity signing)
        "settings/NodePairConfirmScreen.kt", // own-node pairing
    )

    @Test
    fun `every value-moving or identity screen goes through rememberSpendAuth`() {
        val missing = gatedScreens.filterNot { rel ->
            val text = File(uiRoot, rel).readText()
            text.contains("rememberSpendAuth(") && text.contains(".authorize(")
        }
        assertTrue("screens that do not consult the shared spend gate: $missing", missing.isEmpty())
    }

    @Test
    fun `no screen authenticates with BiometricAuth on its own any more`() {
        // The per-screen biometric branches are exactly where "no biometric → proceed"
        // lived. Three files use BiometricAuth for something other than a spend: unlock
        // (the PIN screen IS the fallback), PIN setup (biometric enrolment) and Security
        // settings (PIN + biometric, stricter). Excluded by name so a fourth is noticed.
        val legitimate = setOf("UnlockScreen.kt", "PinSetupScreen.kt", "SecuritySettingsScreen.kt", "SpendAuth.kt")
        val offenders = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in legitimate }
            .filter { it.readText().contains("biometricAuth.authenticate(") }
            .map { it.path }
            .toList()
        assertTrue("screens calling BiometricAuth directly: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the Dialogs host is placed in each gated screen's tree`() {
        val missing = gatedScreens.filterNot { rel ->
            File(uiRoot, rel).readText().contains(".Dialogs()")
        }
        assertTrue("screens whose PIN dialog can never render: $missing", missing.isEmpty())
    }
}
