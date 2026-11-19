package app.aroundtheblock.wallet

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which stay in the foreground this is: [MainActivity] counts one more each time the app leaves it.
 *
 * INT-2026-11-19-B: a first-run setup that took the current app PIN holds that answer only for the
 * stay it was given in (`OnboardingViewModel`). The app has no lock to fall back on while the
 * selected network has no wallet, so the PIN entered there must not outlive the visit.
 */
@Singleton
class ForegroundVisits @Inject constructor() {
    private val count = AtomicLong()

    /** The stay in the foreground the app is in now. */
    fun current(): Long = count.get()

    /** The app left the foreground: what was held for the stay just ended is held no longer. */
    fun left() {
        count.incrementAndGet()
    }
}
