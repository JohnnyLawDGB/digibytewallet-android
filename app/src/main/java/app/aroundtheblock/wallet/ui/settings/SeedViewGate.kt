package app.aroundtheblock.wallet.ui.settings

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /** Bumped by every [revoke]: a pass spent before a lock is dead after it (4.1.3, review R18). */
    private val epoch = AtomicInteger(0)

    fun grant() = granted.set(true)

    /** True once per [grant]. */
    fun consume(): Boolean = granted.getAndSet(false)

    /**
     * A lock withdraws the pass: an unspent grant, and every pass already spent. Before 4.1.3 only the
     * unspent grant went, so a same-process recreate restored the phrase screen with a live pass -- while
     * locked for a frame or two, and after an unlock that took the PIN OR a biometric where the phrase takes
     * both (review R18).
     */
    fun revoke() {
        granted.set(false)
        epoch.incrementAndGet()
    }

    /**
     * Different in every process. The phrase screen saves the token of the pass it spent, not a
     * plain "passed": state saved before a process death carries the dead process's token, which
     * never matches here, so a restored screen asks again (LOCK-PD).
     */
    val processToken: String = java.util.UUID.randomUUID().toString()

    /** The token to save for a pass spent now, or "" when there was no pass. */
    fun spend(): String = if (consume()) "$processToken:${epoch.get()}" else ""

    /** Whether a saved [spend] token is a pass of this process, spent since the last lock. */
    fun isLive(token: String): Boolean = token.isNotEmpty() && token == "$processToken:${epoch.get()}"

    /** Whether the phrase may be drawn now: a live pass, and the wallet unlocked at this moment. */
    fun mayShow(token: String, unlocked: Boolean): Boolean = unlocked && isLive(token)
}
