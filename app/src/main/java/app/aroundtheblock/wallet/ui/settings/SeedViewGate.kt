package app.aroundtheblock.wallet.ui.settings

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A one-time pass from Security settings' PIN + biometric check to the recovery phrase screen.
 *
 * The phrase screen is a plain route, so anything that can navigate there (a resumed back stack,
 * a deep link added later, a lock that reopens the last screen) would otherwise show the phrase
 * behind whatever single factor that path asked for (BB-2026-10-08-dino). The check grants a pass
 * immediately before it navigates; the screen spends it on entry; a lock withdraws it.
 */
object SeedViewGate {
    private val granted = AtomicBoolean(false)

    fun grant() = granted.set(true)

    /** True once per [grant]. */
    fun consume(): Boolean = granted.getAndSet(false)

    fun revoke() = granted.set(false)
}
