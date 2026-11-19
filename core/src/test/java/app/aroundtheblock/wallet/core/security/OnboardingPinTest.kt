package app.aroundtheblock.wallet.core.security

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.RecordingPinStore
import app.aroundtheblock.wallet.core.wallets.WalletIds
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INT-2026-11-19-B. Invariant: no onboarding path replaces or clears the app PIN while any wallet
 * record exists.
 *
 * [OnboardingPin] is the only PIN write a setup flow can reach, driven here over the real
 * [PinManager] (in-memory store) and the real [WalletRegistry]. The state every case starts from is
 * the one the first run is offered in while a wallet and its PIN are stored: the selected network
 * has no wallet, so its active wallet falls back to legacy, which holds no record.
 */
class OnboardingPinTest {

    private val current = "123456"
    private val chosen = "999999"

    private val store = RecordingPinStore()
    /** The cooldown's clocks, standing still. */
    private val clock = object : PinClock {
        override fun sinceBootMs() = 7L * 86_400_000L
        override fun bootCount() = 41
        override fun wallMs() = 1_000_000_000_000L
    }
    private val pinManager = PinManager(store, clock = clock)

    private val prefs = InMemoryPrefsFactory().prefsFor(WalletRegistry.PREFS)
    private val records = mutableSetOf<String>()
    private var legacyRecord = false
    private val registry = WalletRegistry(
        prefs = prefs,
        recordExists = { scope -> if (scope.isLegacy) legacyRecord else scope.walletId in records },
        recordsOnDisk = { records.map { it to null } },
    )
    private val onboardingPin = OnboardingPin(pinManager, registry)

    /** A READY wallet on mainnet, with its record. */
    private fun mainnetWallet(): String {
        val entry = registry.reserve(false, WalletOrigin.CREATED, 1_000L)!!
        records += entry.id
        assertTrue(registry.complete(entry.id, makeActive = true))
        return entry.id
    }

    /** The selected network (testnet) has no wallet: the app starts on the first run. */
    private fun assertFirstRunOnTestnet() {
        assertEquals("testnet lists a wallet", emptyList<String>(), registry.list(true).map { it.id })
        assertEquals(WalletIds.LEGACY, registry.activeId(true))
        assertFalse("the active wallet holds a record, so the app would start locked", legacyRecord)
        assertTrue("no wallet record is on the device", registry.anyRecordExists())
    }

    private fun assertCurrentPinStillOpens() {
        assertTrue(pinManager.hasPin())
        assertEquals(PinVerifyResult.Success, pinManager.verifyPin(current))
    }

    @Test fun `a first run beside another network's wallet cannot replace the PIN`() {
        mainnetWallet()
        pinManager.setPin(current)
        assertFirstRunOnTestnet()

        assertTrue("the stored PIN is not kept", onboardingPin.mustKeepPin())
        val refused = runCatching { onboardingPin.setFirstPin(chosen) }.exceptionOrNull()
        assertTrue("setup stored a PIN over the one that guards a wallet: $refused", refused is PinKeptException)
        assertTrue("the PIN setup chose opens the app", pinManager.verifyPin(chosen) is PinVerifyResult.Wrong)
        assertCurrentPinStillOpens()
    }

    @Test fun `a first run beside another network's wallet cannot clear the PIN`() {
        mainnetWallet()
        pinManager.setPin(current)
        assertFirstRunOnTestnet()

        assertFalse("setup cleared the PIN that guards a wallet", onboardingPin.clearStalePin())
        assertCurrentPinStillOpens()
    }

    /** The refused write is no way around the counter: a set PIN starts a clean slate, a refused one does not. */
    @Test fun `a refused setup write leaves the wrong-PIN count and its cooldown running`() {
        mainnetWallet()
        pinManager.setPin(current)
        repeat(PinManager.FREE_ATTEMPTS) { assertTrue(pinManager.verifyPin("000000") is PinVerifyResult.Wrong) }

        runCatching { onboardingPin.setFirstPin(chosen) }

        val next = pinManager.verifyPin("000000")
        assertEquals("the count was reset", PinManager.FREE_ATTEMPTS + 1, (next as PinVerifyResult.Wrong).failCount)
        assertNotNull("the fourth wrong PIN started no cooldown", next.lockedUntil)
        assertTrue("the cooldown does not hold", pinManager.verifyPin(current) is PinVerifyResult.LockedOut)
        assertTrue("the cooldown does not hold for the PIN setup chose", pinManager.verifyPin(chosen) is PinVerifyResult.LockedOut)
    }

    @Test fun `a record in any registry state keeps the PIN`() {
        // An add that never completed, with its record written.
        val pending = registry.reserve(false, WalletOrigin.RESTORED, 1_000L)!!
        records += pending.id
        pinManager.setPin(current)
        assertEquals(emptyList<String>(), registry.list(false).map { it.id })
        assertTrue("a PENDING wallet's record does not keep the PIN", onboardingPin.mustKeepPin())

        // A removal whose erase has not finished.
        assertTrue(registry.complete(pending.id, makeActive = true))
        assertTrue(registry.markRemoving(pending.id))
        assertEquals(emptyList<String>(), registry.list(false).map { it.id })
        assertTrue("a REMOVING wallet's record does not keep the PIN", onboardingPin.mustKeepPin())

        // A record the registry no longer names.
        assertTrue(registry.drop(pending.id))
        assertTrue("a record the registry lost does not keep the PIN", onboardingPin.mustKeepPin())
        assertTrue(runCatching { onboardingPin.setFirstPin(chosen) }.exceptionOrNull() is PinKeptException)
        assertFalse(onboardingPin.clearStalePin())
        assertCurrentPinStillOpens()
    }

    @Test fun `legacy's record keeps the PIN`() {
        legacyRecord = true
        pinManager.setPin(current)
        assertTrue(onboardingPin.mustKeepPin())
        assertTrue(runCatching { onboardingPin.setFirstPin(chosen) }.exceptionOrNull() is PinKeptException)
        assertCurrentPinStillOpens()
    }

    @Test fun `a PIN store that cannot be read beside a wallet is never no PIN`() {
        mainnetWallet()
        val unreadable = OnboardingPin(PinManager(UnavailablePinStore), registry)
        assertTrue("an unreadable store read as no PIN", runCatching { unreadable.mustKeepPin() }.isFailure)
        assertTrue("a PIN was offered to an unreadable store", runCatching { unreadable.setFirstPin(chosen) }.isFailure)
    }

    // GUARDS, not red-then-green: what setup did before and must keep doing.

    @Test fun `a clean device still sets its first PIN`() {
        assertFalse(registry.anyRecordExists())
        assertFalse(onboardingPin.mustKeepPin())
        onboardingPin.setFirstPin(chosen)
        assertEquals(PinVerifyResult.Success, pinManager.verifyPin(chosen))
    }

    @Test fun `with no wallet record a stale PIN is replaced or cleared`() {
        pinManager.setPin(current)
        assertFalse(onboardingPin.mustKeepPin())
        onboardingPin.setFirstPin(chosen)
        assertEquals(PinVerifyResult.Success, pinManager.verifyPin(chosen))
        assertTrue(onboardingPin.clearStalePin())
        assertFalse(pinManager.hasPin())
    }

    /** A restore writes its record before PIN setup; with no PIN stored that setup still sets one. */
    @Test fun `a restore's own record does not stop its first PIN`() {
        legacyRecord = true
        assertFalse(pinManager.hasPin())
        assertFalse(onboardingPin.mustKeepPin())
        onboardingPin.setFirstPin(chosen)
        assertEquals(PinVerifyResult.Success, pinManager.verifyPin(chosen))
    }

    /**
     * "Any wallet record" is the registry's own answer, asked inside [OnboardingPin]: whoever builds
     * one hands over the registry and cannot hand over a narrower question in its place.
     */
    @Test fun `the guard asks the registry itself, not a question its builder supplies`() {
        val constructors = OnboardingPin::class.java.declaredConstructors.map { it.parameterTypes.toList() }
        assertEquals(
            "OnboardingPin can be built over something other than the PIN manager and the wallet registry",
            listOf(listOf<Class<*>>(PinManager::class.java, WalletRegistry::class.java)),
            constructors,
        )
    }

    /** The app module cannot write the PIN around [OnboardingPin]: both writes are `internal` to core. */
    @Test fun `the PIN writes are not visible outside core`() {
        val source = File("src/main/java/app/aroundtheblock/wallet/core/security/PinManager.kt").readText()
        for (write in listOf("setPin", "clearPin")) {
            val declared = Regex("""^\s*((?:\w+\s+)*)fun\s+$write\s*\(""", RegexOption.MULTILINE).findAll(source).toList()
            assertEquals("scanner is blind: PinManager.$write is not declared once", 1, declared.size)
            assertTrue("PinManager.$write can be called from the app", "internal" in declared.single().groupValues[1].split(Regex("""\s+""")))
        }
        // The one public replace is Settings' change of a PIN it has just verified.
        assertTrue(Regex("""^\s*fun\s+changePin\s*\(""", RegexOption.MULTILINE).containsMatchIn(source))
    }
}
