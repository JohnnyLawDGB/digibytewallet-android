package app.aroundtheblock.wallet.core.security

/**
 * The app PIN as onboarding may touch it: the ONLY PIN writes the setup screens can reach.
 *
 * INT-2026-11-19-B. Invariant: no onboarding path replaces or clears the app PIN while any wallet
 * record exists. One PIN guards every wallet on the device, on either network, so a setup flow
 * that starts while a wallet and its PIN are stored keeps that PIN and sets none
 * ([mustKeepPin]); the flow asks for the PIN instead, through the app's one PIN check.
 *
 * The rule lives here and not at the callers: [PinManager.setPin] and [PinManager.clearPin] are
 * visible inside `core` only, so no screen or ViewModel can write the PIN around it.
 *
 * A PIN store that cannot be read while a wallet record exists throws from every call here; it is
 * never read as "no PIN".
 */
class OnboardingPin(
    private val pinManager: PinManager,
    /** Any wallet's seed record on this device, of either network and in any registry state. */
    private val anyWalletRecordExists: () -> Boolean,
) {
    /**
     * True while a wallet record exists and a PIN is stored: that PIN guards the wallet, and
     * onboarding must ask for it and keep it. False on a device with no wallet record (a PIN
     * stored there is stale, and the PIN store is not asked), and false with no PIN stored.
     */
    fun mustKeepPin(): Boolean = anyWalletRecordExists() && pinManager.hasPin()

    /**
     * Stores the PIN a setup flow chose. Refused with [PinKeptException], nothing written, while
     * [mustKeepPin]. With no PIN stored it is the first PIN, also after a restore has just written
     * its own record; with no wallet record it replaces a stale one.
     */
    fun setFirstPin(pin: String) {
        if (mustKeepPin()) throw PinKeptException()
        pinManager.setPin(pin)
    }

    /**
     * Clears a PIN left by an earlier install, so a restore is followed by PIN setup. Only with no
     * wallet record on the device: a PIN that guards another wallet is not stale (F31). Returns
     * whether the PIN store was cleared.
     */
    fun clearStalePin(): Boolean {
        if (anyWalletRecordExists()) return false
        pinManager.clearPin()
        return true
    }
}

/** A setup flow tried to set a PIN over the one that guards a wallet already on the device. */
class PinKeptException : IllegalStateException("the stored PIN guards a wallet on this device; setup keeps it")
