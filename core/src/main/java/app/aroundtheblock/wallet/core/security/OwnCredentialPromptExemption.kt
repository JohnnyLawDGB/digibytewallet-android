package app.aroundtheblock.wallet.core.security

/**
 * SEED-VIEW-AUTH: the one place that decides whether MainActivity.onStop may skip its immediate
 * UI lock. On API 26-29 the app's own device-credential confirm is a separate activity, so our
 * activity stops underneath it; that stop, and only that stop, is exempt.
 *
 * Invariants: armed only by BiometricAuth.authenticateDeviceCredential and cleared on every one of
 * its completion paths; good for one stop; never older than [MAX_EXEMPTION_MS]; any other stop
 * locks. Times are SystemClock.elapsedRealtime() ms, passed in so the rule stays pure.
 */
object OwnCredentialPromptExemption {

    const val MAX_EXEMPTION_MS: Long = 60_000L

    @Volatile private var promptStartedAtMs: Long? = null

    /** Pure rule: does a stop at [nowMs] lock, given our prompt started at [startedAtMs] (null = none)? */
    fun lockOnStop(startedAtMs: Long?, nowMs: Long): Boolean {
        if (startedAtMs == null) return true
        val age = nowMs - startedAtMs
        return age < 0 || age >= MAX_EXEMPTION_MS
    }

    /** Time left before an exemption that started at [startedAtMs] expires; never negative. */
    fun remainingMs(startedAtMs: Long, nowMs: Long): Long {
        val age = nowMs - startedAtMs
        if (age < 0) return 0L
        return (MAX_EXEMPTION_MS - age).coerceAtLeast(0L)
    }

    fun begin(nowMs: Long) { promptStartedAtMs = nowMs }

    fun end() { promptStartedAtMs = null }

    fun isActive(nowMs: Long): Boolean = !lockOnStop(promptStartedAtMs, nowMs)

    /**
     * For onStop: returns the prompt's start time when this stop is exempt (and uses the exemption
     * up, so a second stop during the same prompt locks), or null when the stop must lock.
     */
    @Synchronized
    fun consumeForStop(nowMs: Long): Long? {
        val started = promptStartedAtMs
        promptStartedAtMs = null
        return if (lockOnStop(started, nowMs)) null else started
    }
}
