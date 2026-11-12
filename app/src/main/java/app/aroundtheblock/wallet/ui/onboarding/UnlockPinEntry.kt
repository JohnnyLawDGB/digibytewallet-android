package app.aroundtheblock.wallet.ui.onboarding

/**
 * The unlock screen's PIN entry, as pure functions so the keypad's rules can be shown on a plain JVM.
 *
 * The keypad takes a digit only while the entry is short of [length]; the digit that completes it
 * starts an unlock. So an entry left full after an attempt that did not open the wallet makes the
 * keypad ignore every later digit, and the screen keeps whatever it last said for as long as the
 * process lives. [afterUnlockEnded] is what the entry becomes once an attempt has ended without
 * opening the wallet, whichever way it ended: a device-lock prompt cancelled or failed, a wallet that
 * did not load, or an error.
 */
internal object UnlockPinEntry {

    sealed interface Press {
        /** The entry is full: the digit is not taken and nothing is started. */
        data object Ignored : Press

        /** The digit was taken and the entry is still short. */
        data class Typed(val entry: String) : Press

        /** The digit completed the entry: the screen checks it. */
        data class Complete(val entry: String) : Press
    }

    fun press(entry: String, digit: String, length: Int): Press {
        if (entry.length >= length) return Press.Ignored
        val next = entry + digit
        return if (next.length == length) Press.Complete(next) else Press.Typed(next)
    }

    /** The entry once an unlock attempt for it has ended without opening the wallet. */
    @Suppress("UNUSED_PARAMETER")
    fun afterUnlockEnded(entry: String): String = ""
}
