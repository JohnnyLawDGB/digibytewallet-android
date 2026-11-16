package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for onboarding in ADD mode: adding a wallet beside the ones the app holds reuses the
 * onboarding screens, and must never set or clear the app PIN, never write the running wallet's
 * record, and never run the opt-in recovery scan (whose disclosure does not say it links wallets).
 *
 * Each rule is a branch the screens take on [OnboardingViewModel.mode]; this pins that the branch
 * is there and that the ADD arm goes where it must. What the add itself writes is covered by
 * `WalletProvisionerTest` in `core`.
 */
class AddModeGateTest {

    private val root = File("src/main/java/app/aroundtheblock/wallet")
    private fun gate(rel: String) = KotlinSourceGate.of(File(root, rel).readText())

    private val firstRun = "viewModel.mode == OnboardingMode.FIRST_RUN"

    /** The `else` arm written straight after [branch], or null when there is none. */
    private fun KotlinSourceGate.elseArm(branch: IntRange): IntRange? {
        val rest = code.substring(branch.last + 1)
        val at = Regex("""^\s*else\s*\{""").find(rest) ?: return null
        return blockAt(branch.last + 1 + at.range.last)
    }

    /** The navigations in [within] whose first argument is written as [target]. */
    private fun KotlinSourceGate.navigationsTo(target: String, within: IntRange = code.indices) =
        calls("navController.navigate", within).filter { it.arguments.firstOrNull() == target }

    /** The body of `fun [name](`, from its opening brace to its closing one. */
    private fun KotlinSourceGate.bodyOf(name: String, signatureStart: String = "fun $name("): IntRange {
        val start = code.indexOf(signatureStart).takeIf { it >= 0 } ?: error("no $signatureStart")
        var depth = 0
        var at = start + signatureStart.length - 1
        while (true) {
            when (code[at]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            at++
        }
        val open = code.indexOf('{', at)
        return blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `add mode never reaches PIN setup`() {
        for (rel in listOf("ui/onboarding/PassphraseScreen.kt", "ui/onboarding/RecoveryDateScreen.kt")) {
            val src = gate(rel)
            val toPin = src.navigationsTo("\"pin_setup\"")
            assertTrue("scanner is blind: $rel no longer navigates to pin_setup", toPin.isNotEmpty())
            val branches = src.branchesOn(firstRun)
            for (nav in toPin) {
                val branch = branches.firstOrNull { nav.range.first in it }
                assertTrue("$rel navigates to pin_setup outside the FIRST_RUN branch", branch != null)
                val otherwise = src.elseArm(branch!!)
                assertTrue("$rel: the FIRST_RUN branch has no ADD arm", otherwise != null)
                val addTargets = src.calls("navController.navigate", otherwise!!).mapNotNull { it.arguments.firstOrNull() }
                assertTrue(
                    "$rel: the ADD arm does not go to the add commit or the quick start: $addTargets",
                    addTargets.any { it == "ADD_COMMIT_ROUTE" || it == "QUICK_START_ROUTE" },
                )
                assertTrue("$rel: the ADD arm reaches pin_setup", addTargets.none { it == "\"pin_setup\"" })
            }
        }
    }

    @Test fun `add mode never reaches the recovery scan`() {
        val src = gate("ui/onboarding/MnemonicInputScreen.kt")
        val toScan = src.navigationsTo("\"recovery_scan\"")
        assertEquals("scanner is blind: the phrase screen no longer opens the recovery scan", 1, toScan.size)
        val branch = src.branchesOn(firstRun).firstOrNull { toScan.single().range.first in it }
        assertTrue("the recovery scan is opened outside the FIRST_RUN branch", branch != null)
        val otherwise = src.elseArm(branch!!) ?: error("the FIRST_RUN branch has no ADD arm")
        assertEquals("the ADD arm does not go straight to the birth date", 1, src.navigationsTo("\"recovery_date\"", otherwise).size)
        assertTrue("the ADD arm opens the recovery scan", src.navigationsTo("\"recovery_scan\"", otherwise).isEmpty())
    }

    @Test fun `add mode never clears the PIN`() {
        val vm = gate("ui/onboarding/OnboardingViewModel.kt")
        val body = vm.bodyOf("commitAdd")
        assertTrue("scanner is blind: commitAdd adds nothing", vm.calls("provisioner.add", body).size == 1)
        assertTrue("commitAdd touches the app PIN", !vm.code.substring(body).contains("pinManager."))
    }

    @Test fun `a restore clears the PIN only when no wallet exists`() {
        val vm = gate("ui/onboarding/OnboardingViewModel.kt")
        val clears = vm.calls("pinManager.clearPin")
        assertEquals("scanner is blind: first-run restore no longer clears a stale PIN", 1, clears.size)
        val body = vm.bodyOf("recoverWallet", "fun recoverWallet(seedKeyPrompt")
        assertTrue("the PIN is cleared outside recoverWallet", clears.single().range.first in body)
        val guarded = vm.branchesOn("!registry.anyRecordExists()", body)
        assertTrue(
            "the PIN is cleared while a wallet record may exist",
            guarded.any { clears.single().range.first in it },
        )
        assertTrue("recoverWallet does not refuse ADD mode", vm.calls("check", body).any { it.arguments == listOf("mode == OnboardingMode.FIRST_RUN") })
    }

    @Test fun `add mode never creates through PIN setup`() {
        val src = gate("ui/onboarding/AddWalletCommitScreen.kt")
        assertTrue("scanner is blind: the commit screen does not commit", src.calls("viewModel.commitAdd").isNotEmpty())
        for (callee in listOf("pinSetupCreatesWallet", "createWallet", "recoverWallet")) {
            assertTrue("the commit screen calls $callee", !Regex("""(?<!\w)$callee\s*\(""").containsMatchIn(src.code))
        }
    }

    @Test fun `no bare onboarding route`() {
        val bare = Regex("""(?:navigate|popUpTo|getBackStackEntry|StartDecision|composable)\s*\(\s*"onboarding"""")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanner is blind: no sources", files.size > 50)
        val offenders = files.filter { bare.containsMatchIn(KotlinSourceGate.of(it.readText()).written) }
            .map { it.relativeTo(root).path }
        assertEquals("a bare \"onboarding\" route is used; use ONBOARDING_ROUTE / onboardingRoute(...)", emptyList<String>(), offenders)
    }

    @Test fun `the onboarding route names its mode`() {
        assertEquals("onboarding?mode={mode}", ONBOARDING_ROUTE)
        assertEquals("onboarding?mode=first_run", onboardingRoute(OnboardingMode.FIRST_RUN))
        assertEquals("onboarding?mode=add", onboardingRoute(OnboardingMode.ADD))
        assertEquals(OnboardingMode.ADD, OnboardingMode.of("add"))
        // Anything else is the first run: an unknown argument never opens the add flow.
        for (arg in listOf(null, "", "first_run", "ADD", "added")) assertEquals(arg, OnboardingMode.FIRST_RUN, OnboardingMode.of(arg))
    }
}
