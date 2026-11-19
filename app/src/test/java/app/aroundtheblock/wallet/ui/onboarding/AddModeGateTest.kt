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
        assertTrue("commitAdd touches the app PIN", !Regex("""(?i)pin(?:Manager)?\s*\.""").containsMatchIn(vm.code.substring(body)))
    }

    /**
     * F31, and INT-2026-11-19-B: no onboarding path replaces or clears the app PIN while any wallet
     * record exists. The rule is `OnboardingPin`'s (core, `OnboardingPinTest`); this pins that the
     * ViewModel has no way to the PIN but that, so no caller can clear or set around it.
     */
    @Test fun `a restore clears the PIN only through the guard that knows whether a wallet exists`() {
        val vm = gate("ui/onboarding/OnboardingViewModel.kt")
        assertTrue("the onboarding ViewModel holds the PIN manager itself", !Regex("""(?<!\w)[pP]inManager(?!\w)""").containsMatchIn(vm.code))
        val clears = vm.calls("onboardingPin.clearStalePin")
        assertEquals("scanner is blind: first-run restore no longer clears a stale PIN", 1, clears.size)
        val body = vm.bodyOf("recoverWallet", "fun recoverWallet(seedKeyPrompt")
        assertTrue("the PIN is cleared outside recoverWallet", clears.single().range.first in body)
        assertTrue("recoverWallet does not refuse ADD mode", vm.calls("check", body).any { it.arguments == listOf("mode == OnboardingMode.FIRST_RUN") })
        // Every use of the guard, by name: nothing else of it is reachable from here.
        val uses = Regex("""onboardingPin\s*\.\s*(\w+)""").findAll(vm.code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("mustKeepPin", "setFirstPin", "clearStalePin"), uses)
    }

    /** INT-2026-11-19-B: the only PIN replace the app can reach outside onboarding is Settings' change of a verified PIN. */
    @Test fun `only Security settings replaces a PIN`() {
        val replace = Regex("""\.\s*changePin\s*\(""")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanner is blind: no sources", files.size > 50)
        val callers = files.filter { replace.containsMatchIn(KotlinSourceGate.of(it.readText()).code) }.map { it.relativeTo(root).path }.sorted()
        assertEquals(listOf("ui/settings/SecuritySettingsScreen.kt", "ui/settings/SettingsViewModel.kt"), callers)
    }

    /**
     * INT-2026-11-19-B: a first run that keeps the stored PIN asks for it before the phrase is shown
     * or typed, through the app's one PIN dialog (same counter, cooldown and wipe-after-N).
     */
    @Test fun `the first screen takes the current PIN before either flow starts`() {
        val src = gate("ui/onboarding/OnboardingScreen.kt")
        val gated = Regex("""(?<!\w)afterCurrentPin\s*\{""").findAll(src.code).mapNotNull { src.blockAt(it.range.last) }.toList()
        assertEquals("scanner is blind: nothing runs behind the current-PIN step", 2, gated.size)
        val navigations = src.calls("navController.navigate")
        assertEquals("scanner is blind: the first screen starts two flows", 2, navigations.size)
        for (nav in navigations) {
            assertTrue("a flow starts without the current-PIN step: ${nav.text}", gated.any { nav.range.first in it })
        }
        val owed = src.branchesOn("viewModel.currentPinOwed()")
        assertEquals("the current-PIN step does not ask whether the PIN is owed", 1, owed.size)
        val asks = src.calls("spendAuth.authorizeWithPin", owed.single())
        assertEquals("an owed PIN is not asked through the app's PIN dialog", 1, asks.size)
        val accepted = src.guardedBy(asks.single()) ?: error("the PIN dialog's answer guards nothing")
        val marks = src.calls("viewModel.onCurrentPinVerified")
        assertEquals(1, marks.size)
        assertTrue("the PIN is marked entered without the dialog's yes", marks.single().range.first in accepted)
        val goes = src.calls("go", owed.single())
        assertEquals("with the PIN owed, the flow goes on more than once", 1, goes.size)
        assertTrue("with the PIN owed, the flow goes on without the dialog's yes", goes.single().range.first in accepted)
        // No PIN check of its own: the screen never touches the PIN manager.
        assertTrue(!Regex("""(?<!\w)verifyPin\s*\(""").containsMatchIn(src.code))
    }

    /** The PIN-only entry of the spend gate is the same dialog, and the gate has one PIN check. */
    @Test fun `the PIN-only gate is the spend gate's own dialog`() {
        val src = gate("ui/components/SpendAuth.kt")
        val body = src.bodyOf("authorizeWithPin")
        assertEquals("authorizeWithPin does not open the shared PIN dialog", 1, src.calls("awaitPin", body).size)
        assertTrue("authorizeWithPin runs without a stored PIN", src.calls("vm.hasPin", body).isNotEmpty())
        assertEquals("the spend gate checks a PIN in more than one place", 1, src.calls("vm.verifyPin").size)
        assertEquals("the spend gate's wipe-after-N is not in its one dialog", 1, src.calls("vm.wipeWallet").size)
    }

    /** A flow that keeps the PIN shows no PIN keypad and stores no PIN. */
    @Test fun `PIN setup shows its keypad only when the flow sets a PIN`() {
        val src = gate("ui/onboarding/PinSetupScreen.kt")
        val sets = src.branchesOn("keepsPin == false")
        assertEquals("PIN setup does not branch on whether the flow keeps the PIN", 1, sets.size)
        val keypads = src.calls("PinEntryContent")
        assertEquals("scanner is blind: PIN setup shows no keypad", 1, keypads.size)
        assertTrue("the keypad is shown to a flow that keeps the PIN", keypads.single().range.first in sets.single())
        val stores = src.calls("viewModel.setPin")
        assertEquals("scanner is blind: PIN setup stores no PIN", 1, stores.size)
        assertTrue("a PIN is stored outside the keypad's branch", stores.single().range.first in sets.single())
        val kept = src.branchesOn("keepsPin == true")
        assertEquals(1, kept.size)
        assertTrue("the kept-PIN branch stores a PIN", src.calls("viewModel.setPin", kept.single()).isEmpty())
        assertEquals("the kept-PIN branch does not go on to the wallet", 1, src.calls("createThenGoOn", kept.single()).size)
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
