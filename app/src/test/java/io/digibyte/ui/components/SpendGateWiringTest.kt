package io.digibyte.ui.components

import io.digibyte.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate, not a behaviour test: it proves each value-moving / identity
 * screen references the shared gate, and that no screen still performs the "no biometric →
 * proceed" branch on its own. The behaviour of the gate itself is covered by
 * [SpendAuthPolicyTest]; this exists because the seven sites were found ungated one at a
 * time, and a new site (or a refactor of an old one) that quietly drops the gate compiles.
 *
 * It reads code, not text: a reference in a comment or a literal is no reference. And it reads
 * what the gate's answer decides — on each screen the answer is, by itself, the condition the
 * screen's value-moving or identity calls stand under, and none of those calls stands anywhere
 * else.
 */
class SpendGateWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    /** Each gated screen, and the calls on it that move value or sign for an identity. */
    private val gatedCalls = mapOf(
        "wallet/SendScreen.kt" to listOf("viewModel.send", "viewModel.sendDigiDollar"), // DGB send + DigiDollar send
        "asset/AssetSendScreen.kt" to listOf("viewModel.sendAssetTransfer"),            // DigiAsset send
        "digiid/DigiIdConfirmScreen.kt" to listOf("viewModel.authenticate"),            // Digi-ID approve
        "recovery/RecoverFundsScreen.kt" to listOf("vm.sweep", "vm.sweepForeign"),      // native / external / foreign sweep
        "hub/ProfileView.kt" to listOf("viewModel.quickLogin"),                         // Hub quick-login (identity signing)
        "settings/NodePairConfirmScreen.kt" to listOf("viewModel.pairFromUri"),         // own-node pairing
    )

    private val gatedScreens = gatedCalls.keys.toList()

    /** The screen as code: comments and the text inside literals reference nothing. */
    private fun gate(rel: String): KotlinSourceGate = KotlinSourceGate.of(File(uiRoot, rel).readText())

    @Test
    fun `every value-moving or identity screen goes through rememberSpendAuth`() {
        val missing = gatedScreens.filterNot { rel ->
            val screen = gate(rel)
            screen.calls("rememberSpendAuth").isNotEmpty() && Regex("""\.\s*authorize\s*\(""").containsMatchIn(screen.code)
        }
        assertTrue("screens that do not consult the shared spend gate: $missing", missing.isEmpty())
    }

    /**
     * Consulting the gate is not enough; its answer has to decide. On every gated screen each
     * `authorize` call is made on the screen's own gate, its answer is the WHOLE condition of a
     * branch — `if (answer) act`, or `if (!answer) return` and then the act — and every call in
     * [gatedCalls] stands under such an answer: one that stands beside it, or after it, is
     * reported. A local function that calls its parameter under the answer (`gated { … }`)
     * passes the answer on to the block it is given.
     */
    @Test
    fun `the gate's answer is, by itself, the condition every gated call stands under`() {
        val gaps = mutableListOf<String>()
        for ((rel, gatedOnThisScreen) in gatedCalls) {
            val screen = gate(rel)
            val holder = Regex("""val\s+(\w+)\s*=\s*rememberSpendAuth\s*\(""").find(screen.code)?.groupValues?.get(1)
            if (holder == null) { gaps += "$rel: the gate is not held in a value the scan can follow"; continue }
            val prompts = screen.calls("$holder.authorize")
            if (prompts.isEmpty()) gaps += "$rel: `$holder.authorize(` is never called"
            if (Regex("""\.\s*authorize\s*\(""").findAll(screen.code).count() != prompts.size) {
                gaps += "$rel: `authorize(` is called on something other than `$holder`"
            }
            val allowed = mutableListOf<IntRange>()
            for (prompt in prompts) {
                val under = screen.guardedBy(prompt)
                if (under == null) {
                    gaps += "$rel: the answer of `${KotlinSourceGate.squeeze(prompt.text)}` is not, by itself, the condition of a branch"
                } else {
                    allowed += under
                }
            }
            // `fun gated(action: () -> Unit)`: when `action()` stands under the answer, so does the block of each `gated { … }`.
            for (passedOn in Regex("""fun\s+(\w+)\s*\(\s*(\w+)\s*:\s*\(\s*\)\s*->\s*Unit\s*\)""").findAll(screen.code).toList()) {
                val (function, parameter) = passedOn.destructured
                val invoked = screen.calls(parameter)
                if (invoked.isEmpty() || !invoked.all { call -> allowed.any { call.range.first in it && call.range.last in it } }) continue
                for (use in Regex("""(?<!\w)$function\s*\{""").findAll(screen.code)) {
                    screen.blockAt(use.range.last)?.let { allowed += it }
                }
            }
            for (callee in gatedOnThisScreen) {
                val calls = screen.calls(callee)
                if (calls.isEmpty()) gaps += "$rel: cannot find `$callee(` — the gate is blind, not clean"
                for (call in calls) {
                    if (allowed.none { call.range.first in it && call.range.last in it }) {
                        val line = screen.code.substring(0, call.range.first).count { it == '\n' } + 1
                        gaps += "$rel:$line: `${KotlinSourceGate.squeeze(call.text)}` does not stand under the gate's answer"
                    }
                }
            }
        }
        assertTrue(gaps.joinToString("\n", prefix = "\n"), gaps.isEmpty())
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
            Regex("""\.\s*Dialogs\s*\(\s*\)""").containsMatchIn(gate(rel).code)
        }
        assertTrue("screens whose PIN dialog can never render: $missing", missing.isEmpty())
    }
}
