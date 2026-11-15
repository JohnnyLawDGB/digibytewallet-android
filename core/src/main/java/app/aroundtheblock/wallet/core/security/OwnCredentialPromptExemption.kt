package app.aroundtheblock.wallet.core.security

/**
 * SEED-VIEW-AUTH: the one place that decides whether MainActivity.onStop may defer its immediate
 * UI lock, and how that deferral resolves.
 *
 * Only the stop caused by the app's own device-credential CONFIRM ACTIVITY is deferred:
 * - API <= 28: armed when our activity launches ACTION_CONFIRM_DEVICE_CREDENTIAL while one of our
 *   prompts is open (androidx.biometric's fragment launches it through the host activity).
 * - API 29: the framework launches the confirm itself, so the arm is set when our prompt opens.
 * - API >= 30: never armed; the credential UI is a system dialog, so any stop is the user leaving.
 *
 * A deferred stop is resolved at the next onResume and keeps the UI unlocked only with proof the
 * confirm succeeded: RESULT_OK for our confirm request (API <= 28) or our success callback
 * (API 29), inside [MAX_EXEMPTION_MS] of arming, with no user-leave hint. Anything else locks,
 * as does the deadline timer if no resume comes. Times are SystemClock.elapsedRealtime() ms,
 * passed in so every rule here is pure and unit-tested.
 */
object OwnCredentialPromptExemption {

    /**
     * Long enough to type a PIN, pattern or password on the confirm screen; short enough that a
     * phone put down mid-prompt locks soon. Past it the user simply verifies again (fails closed).
     */
    const val MAX_EXEMPTION_MS: Long = 30_000L

    /** KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL (@hide in the SDK; AOSP value, pinned by test). */
    const val ACTION_CONFIRM_DEVICE_CREDENTIAL = "android.app.action.CONFIRM_DEVICE_CREDENTIAL"
    const val RESULT_OK = -1

    enum class Via { CONFIRM_INTENT, API29_FRAMEWORK }

    data class DeferredStop(
        val armedAtMs: Long,
        val via: Via,
        val requestCode: Int?,
        val confirmResultOk: Boolean = false,
        val successCallback: Boolean = false,
        val userLeft: Boolean = false,
    )

    // ---- pure rules ----

    fun armsAtPromptStart(sdk: Int): Boolean = sdk == 29

    fun armsOnLaunch(sdk: Int, action: String?): Boolean =
        sdk <= 28 && action == ACTION_CONFIRM_DEVICE_CREDENTIAL

    fun lockOnStop(armedAtMs: Long?, nowMs: Long): Boolean {
        if (armedAtMs == null) return true
        val age = nowMs - armedAtMs
        return age < 0 || age >= MAX_EXEMPTION_MS
    }

    /** null = nothing deferred, nothing to lock. */
    fun lockOnResume(d: DeferredStop?, nowMs: Long): Boolean {
        if (d == null) return false
        if (lockOnStop(d.armedAtMs, nowMs) || d.userLeft) return true
        val proof = when (d.via) {
            Via.CONFIRM_INTENT -> d.confirmResultOk
            Via.API29_FRAMEWORK -> d.successCallback
        }
        return !proof
    }

    /** The deadline timer fires only while our activity is still stopped: a pending deferral locks. */
    fun lockOnTimer(d: DeferredStop?): Boolean = d != null

    // ---- the holder ----

    private var generation = 0
    private var openGeneration: Int? = null
    private var armedAtMs: Long? = null
    private var armedVia: Via? = null
    private var armedRequestCode: Int? = null
    private var deferred: DeferredStop? = null
    private var deferredGeneration: Int? = null

    /** Our device-credential prompt opens; returns its generation for [closePrompt]. */
    @Synchronized fun openPrompt(nowMs: Long, sdk: Int): Int {
        val g = ++generation
        openGeneration = g
        armedAtMs = null; armedVia = null; armedRequestCode = null
        if (armsAtPromptStart(sdk)) { armedAtMs = nowMs; armedVia = Via.API29_FRAMEWORK }
        return g
    }

    /** Every completion path of prompt [gen]. A stale generation changes nothing. */
    @Synchronized fun closePrompt(gen: Int, success: Boolean) {
        if (gen != openGeneration) return
        openGeneration = null
        armedAtMs = null; armedVia = null; armedRequestCode = null
        val d = deferred
        if (success && d != null && deferredGeneration == gen) deferred = d.copy(successCallback = true)
    }

    /** MainActivity.startActivityForResult: arms on our confirm launch (API <= 28 only). */
    @Synchronized fun onActivityLaunch(nowMs: Long, sdk: Int, action: String?, requestCode: Int) {
        if (openGeneration == null || !armsOnLaunch(sdk, action)) return
        armedAtMs = nowMs; armedVia = Via.CONFIRM_INTENT; armedRequestCode = requestCode
    }

    /** MainActivity.onActivityResult: RESULT_OK for our confirm request is the API <= 28 proof. */
    @Synchronized fun onActivityResult(requestCode: Int, resultCode: Int) {
        val d = deferred ?: return
        if (d.via == Via.CONFIRM_INTENT && d.requestCode == requestCode) {
            deferred = d.copy(confirmResultOk = resultCode == RESULT_OK)
        }
    }

    /** onStop: true = this stop is deferred (uses the arm up); false = lock now. */
    @Synchronized fun consumeForStop(nowMs: Long): Boolean {
        val at = armedAtMs; val via = armedVia
        armedAtMs = null; armedVia = null
        if (lockOnStop(at, nowMs) || via == null) return false
        deferred = DeferredStop(at!!, via, armedRequestCode)
        deferredGeneration = openGeneration
        armedRequestCode = null
        return true
    }

    /** onUserLeaveHint: true = a deferral was pending and is now a lock. */
    @Synchronized fun onUserLeaveHint(): Boolean {
        val d = deferred ?: return false
        deferred = null; deferredGeneration = null
        return lockOnTimer(d.copy(userLeft = true))
    }

    /** onResume (API 29: from a post, after queued callbacks): true = lock. Clears the deferral. */
    @Synchronized fun resolveOnResume(nowMs: Long): Boolean {
        val d = deferred
        deferred = null; deferredGeneration = null
        return lockOnResume(d, nowMs)
    }

    /** The deadline timer: true = lock. Clears the deferral. */
    @Synchronized fun resolveOnTimer(): Boolean {
        val d = deferred
        deferred = null; deferredGeneration = null
        return lockOnTimer(d)
    }

    /** onDestroy outside a configuration change: the confirm's answer cannot come back here. */
    @Synchronized fun resolveOnDestroy(): Boolean = resolveOnTimer()

    @Synchronized fun isDeferred(): Boolean = deferred != null

    @Synchronized fun deferredVia(): Via? = deferred?.via

    /** Delay for the deadline timer of the current deferral (0 when none or already past). */
    @Synchronized fun remainingMs(nowMs: Long): Long {
        val d = deferred ?: return 0L
        val age = nowMs - d.armedAtMs
        if (age < 0) return 0L
        return (MAX_EXEMPTION_MS - age).coerceAtLeast(0L)
    }

    @Synchronized internal fun resetForTest() {
        openGeneration = null; armedAtMs = null; armedVia = null; armedRequestCode = null
        deferred = null; deferredGeneration = null
    }
}
