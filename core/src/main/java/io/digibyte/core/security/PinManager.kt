package io.digibyte.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.argon2.Argon2
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Result of a [PinManager.verifyPin] call. Replaces the old `Boolean` return so
 * every caller enforces the persisted rate-limit uniformly.
 *
 * Forward-compatible with the Phase 2.2 duress PIN: [Success] is the ONLY
 * valid-credential outcome and can later split into `REAL | DURESS` without any
 * change to the rate-limit logic — the limiter only distinguishes valid-vs-invalid,
 * and any valid PIN (real or decoy) resets the counter.
 */
sealed interface PinVerifyResult {
    /** Correct PIN. Counters were reset. (Later: carries REAL|DURESS for 2.2.) */
    data object Success : PinVerifyResult

    /** Wrong PIN. [failCount] is the new consecutive-failure count; [lockedUntil]
     *  is the wall-clock epoch-ms a cooldown expires, or null if this failure did
     *  not start a cooldown (still within the free attempts). */
    data class Wrong(val failCount: Int, val lockedUntil: Long?) : PinVerifyResult

    /** Already locked out — the PIN was NOT checked (compare never ran). [until]
     *  is the wall-clock epoch-ms the lock expires. */
    data class LockedOut(val until: Long) : PinVerifyResult

    /** Wipe-after-N is enabled AND the failure count reached the wipe threshold.
     *  [PinManager] cannot wipe the wallet itself (it only owns `dgb_pin_store`);
     *  the CALLER performs the destructive wallet wipe + routes to onboarding.
     *  A `pin_wipe_pending` flag is set so a kill mid-wipe completes on next launch. */
    data object ShouldWipe : PinVerifyResult
}

/**
 * Minimal key/value abstraction over the `dgb_pin_store` EncryptedSharedPreferences.
 *
 * Extracting this seam keeps the rate-limit state machine PURE-JVM unit-testable
 * (no Robolectric / AndroidX Security in the test path — the core module has
 * neither): tests drive [PinManager] over an in-memory fake, and constructing a
 * fresh [PinManager] over the SAME store instance simulates a force-stop (the
 * persisted lockout survives).
 */
interface PinStore {
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getString(key: String): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun contains(key: String): Boolean
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun clear()
}

/** Production [PinStore] backed by the hardware-keyed `dgb_pin_store`
 *  EncryptedSharedPreferences. Writes use `commit()` (synchronous) so the
 *  rate-limit counters are durable against a force-stop between attempts —
 *  an async `apply()` could be dropped by a process kill, resetting the count. */
private class EncryptedPrefsPinStore(context: Context) : PinStore {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "dgb_pin_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).commit() }
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
    override fun remove(key: String) { prefs.edit().remove(key).commit() }
    override fun clear() { prefs.edit().clear().commit() }
}

/**
 * PIN storage + verification with a persisted, tamper-resistant rate-limit.
 *
 * The counter and lockout live in `dgb_pin_store` ONLY — never the Room DB, which
 * [io.digibyte.StaleDataWiper] wipes on crash-loop recovery (that would reset an
 * attacker's count). They survive force-stop and are cleared atomically by
 * [clearPin].
 *
 * Rate-limit (confirmed defaults): 3 free attempts, then 1 / 5 / 30 / 60-minute
 * cooldowns; optional wipe-after-N (default OFF) once the failure count reaches
 * [WIPE_THRESHOLD]. See the design spec at
 * `docs/superpowers/specs/2026-07-16-pin-rate-limit-design.md`.
 */
class PinManager internal constructor(private val store: PinStore) {

    /** Production constructor used by Hilt — backs the store with EncryptedSharedPreferences. */
    constructor(context: Context) : this(EncryptedPrefsPinStore(context))

    companion object {
        // Persisted state keys (all in dgb_pin_store).
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_METHOD = "pin_method"
        private const val KEY_FAIL_COUNT = "pin_fail_count"
        private const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_LAST_FAIL_AT = "pin_last_fail_at"
        private const val KEY_WIPE_AFTER_N = "pin_wipe_after_n"
        private const val KEY_WIPE_PENDING = "pin_wipe_pending"

        /** Consecutive wrong attempts allowed with NO cooldown. The first cooldown
         *  starts at failCount == FREE_ATTEMPTS + 1. */
        const val FREE_ATTEMPTS = 3

        /** Total consecutive failures that trigger a wipe when wipe-after-N is on. */
        const val WIPE_THRESHOLD = 10

        /** Longest cooldown — also the forced lockout on a backward-clock jump. */
        const val MAX_COOLDOWN_MS = 3_600_000L // 60 min

        /**
         * Backoff schedule (pure, unit-testable in isolation):
         *   failCount 1..3 -> 0 (free)
         *   4 -> 60_000 (1 min), 5 -> 300_000 (5 min),
         *   6 -> 1_800_000 (30 min), >=7 -> 3_600_000 (60 min)
         */
        fun cooldownMsForFailCount(n: Int): Long = when {
            n <= FREE_ATTEMPTS -> 0L
            n == 4 -> 60_000L
            n == 5 -> 300_000L
            n == 6 -> 1_800_000L
            else -> MAX_COOLDOWN_MS
        }
    }

    // ── PIN storage ───────────────────────────────────────────────────────────

    fun setPin(pin: String) {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (hashHex, method) = tryArgon2Hash(pin, salt) ?: run {
            Pair(hashWithPbkdf2(pin, salt).toHex(), "pbkdf2")
        }
        store.putString(KEY_HASH, hashHex)
        store.putString(KEY_SALT, salt.toHex())
        store.putString(KEY_METHOD, method)
        // A freshly (re)set PIN starts from a clean rate-limit slate.
        resetRateLimit()
    }

    fun hasPin(): Boolean = store.contains(KEY_HASH)

    /** Clears the ENTIRE pin store — hash AND every rate-limit counter/flag —
     *  atomically. Used on wallet wipe / PIN reset. */
    fun clearPin() {
        store.clear()
    }

    // ── Verification + rate-limit ─────────────────────────────────────────────

    /**
     * Verify [pin] against the stored hash, enforcing the persisted rate-limit.
     *
     * [nowMs] is injectable so tests can advance/rewind the clock — it is NEVER
     * passed in production (callers use the [System.currentTimeMillis] default).
     *
     * Algorithm (spec §3):
     *  1. Backward-clock guard: `now < last_fail` ⇒ force a max-cooldown lockout.
     *  2. Lockout check: `now < lockout_until` ⇒ [PinVerifyResult.LockedOut]
     *     WITHOUT running the (expensive, constant-time) compare — the check
     *     precedes the compare and never branches on PIN correctness.
     *  3. Compare (constant-time Argon2id/PBKDF2).
     *  4. Success ⇒ reset counters ⇒ [PinVerifyResult.Success].
     *  5. Fail ⇒ increment; if wipe-after-N && count ≥ threshold ⇒
     *     [PinVerifyResult.ShouldWipe] (+ set wipe-pending); else set the cooldown
     *     ⇒ [PinVerifyResult.Wrong].
     */
    fun verifyPin(pin: String, nowMs: Long = System.currentTimeMillis()): PinVerifyResult {
        // 1. Backward-clock-jump guard — treat a clock that moved before the last
        //    failure as tampering and force the maximum cooldown.
        val lastFail = store.getLong(KEY_LAST_FAIL_AT, 0L)
        if (lastFail > 0L && nowMs < lastFail) {
            val until = nowMs + MAX_COOLDOWN_MS
            store.putLong(KEY_LOCKOUT_UNTIL, until)
            store.putLong(KEY_LAST_FAIL_AT, nowMs)
            return PinVerifyResult.LockedOut(until)
        }

        // 2. Lockout check — runs BEFORE the compare; leaks nothing about the PIN.
        val lockoutUntil = store.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (nowMs < lockoutUntil) {
            return PinVerifyResult.LockedOut(lockoutUntil)
        }

        // 3. Constant-time compare.
        if (compareConstantTime(pin)) {
            // 4. Any valid PIN resets the limiter (duress-forward-compatible).
            resetRateLimit()
            return PinVerifyResult.Success
        }

        // 5. Failure — increment persisted counter.
        val count = store.getInt(KEY_FAIL_COUNT, 0) + 1
        store.putInt(KEY_FAIL_COUNT, count)
        store.putLong(KEY_LAST_FAIL_AT, nowMs)

        if (isWipeAfterNEnabled() && count >= WIPE_THRESHOLD) {
            // Persist a backstop so a kill between here and the caller's wipe still
            // completes the wipe on next launch.
            store.putBoolean(KEY_WIPE_PENDING, true)
            return PinVerifyResult.ShouldWipe
        }

        val cooldown = cooldownMsForFailCount(count)
        return if (cooldown > 0L) {
            val until = nowMs + cooldown
            store.putLong(KEY_LOCKOUT_UNTIL, until)
            PinVerifyResult.Wrong(count, until)
        } else {
            store.putLong(KEY_LOCKOUT_UNTIL, 0L)
            PinVerifyResult.Wrong(count, null)
        }
    }

    /** Reset the rate-limit counters. Called on a successful PIN verify AND on a
     *  successful BIOMETRIC unlock (a valid unlock must clear any stale lockout so
     *  a legit user isn't locked out of their own PIN afterward). */
    fun onUnlockSuccess() {
        resetRateLimit()
    }

    /** The stored lockout deadline (wall-clock epoch-ms), or 0 if none. Lets the
     *  UnlockScreen resume a countdown that spans a process restart. */
    fun currentLockout(): Long = store.getLong(KEY_LOCKOUT_UNTIL, 0L)

    private fun resetRateLimit() {
        store.putInt(KEY_FAIL_COUNT, 0)
        store.putLong(KEY_LOCKOUT_UNTIL, 0L)
        store.putLong(KEY_LAST_FAIL_AT, 0L)
    }

    // ── Wipe-after-N toggle + backstop ────────────────────────────────────────

    fun isWipeAfterNEnabled(): Boolean = store.getBoolean(KEY_WIPE_AFTER_N, false)

    fun setWipeAfterN(enabled: Boolean) {
        store.putBoolean(KEY_WIPE_AFTER_N, enabled)
    }

    /** True if [verifyPin] returned [PinVerifyResult.ShouldWipe] but the caller's
     *  wipe has not yet completed (set atomically, honored at startup). */
    fun isWipePending(): Boolean = store.getBoolean(KEY_WIPE_PENDING, false)

    /** Clear the wipe-pending backstop after a successful wallet wipe. (A full
     *  [clearPin] also clears it — this is for the startup path that wipes the
     *  wallet but recreates a fresh pin store.) */
    fun clearWipePending() {
        store.remove(KEY_WIPE_PENDING)
    }

    // ── Hashing (unchanged crypto) ────────────────────────────────────────────

    private fun compareConstantTime(pin: String): Boolean {
        val storedHash = store.getString(KEY_HASH) ?: return false
        val salt = store.getString(KEY_SALT)?.hexToBytes() ?: return false
        val method = store.getString(KEY_METHOD) ?: "pbkdf2"
        return when (method) {
            "argon2id" -> {
                val result = tryArgon2Hash(pin, salt) ?: return false
                constantTimeEquals(result.first.hexToBytes(), storedHash.hexToBytes())
            }
            else -> {
                val computedHash = hashWithPbkdf2(pin, salt)
                constantTimeEquals(computedHash, storedHash.hexToBytes())
            }
        }
    }

    /**
     * Attempts to hash with Argon2id (Signal library). Returns (hashHex, "argon2id") on success,
     * null if the native library fails (e.g., unsupported ABI).
     *
     * Parameters per OWASP recommendations for interactive login (t=3, m=64MiB, p=4):
     * - iterations: 3
     * - memory: 65536 KiB (64 MiB)
     * - parallelism: 4
     * - hashLength: 32 bytes
     */
    private fun tryArgon2Hash(pin: String, salt: ByteArray): Pair<String, String>? {
        return try {
            val argon2 = Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .iterations(3)
                .memoryCostKiB(65536)
                .parallelism(4)
                .hashLength(32)
                .build()
            val result = argon2.hash(pin.toByteArray(Charsets.UTF_8), salt)
            Pair(result.hash.toHex(), "argon2id")
        } catch (t: Throwable) {
            // Throwable, not Exception: an unsupported ABI (the documented fallback
            // case) surfaces as an UnsatisfiedLinkError / ExceptionInInitializerError
            // — both Errors, not Exceptions — when the Signal native lib can't load.
            // Falling back to PBKDF2 there (instead of crashing) is the intent.
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 fallback with 600,000 iterations (OWASP 2023 recommendation).
     */
    private fun hashWithPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 600_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
