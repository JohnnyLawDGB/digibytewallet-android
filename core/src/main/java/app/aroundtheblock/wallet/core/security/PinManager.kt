package app.aroundtheblock.wallet.core.security

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
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
 * [Success] is the ONLY valid-credential outcome, so it could later split into
 * variants without any change to the rate-limit logic — the limiter distinguishes
 * valid from invalid and nothing else. (The duress/decoy PIN that shape was
 * originally kept open for was cancelled 2026-08-16; the shape costs nothing and
 * is left as-is.)
 */
sealed interface PinVerifyResult {
    /** Correct PIN. Counters were reset. */
    data object Success : PinVerifyResult

    /** Wrong PIN. [failCount] is the new consecutive-failure count; [lockedUntil]
     *  is when the cooldown this failure started ends, as a wall-clock epoch-ms for the
     *  screens to count down to (the cooldown itself runs on [PinClock.sinceBootMs]), or
     *  null if this failure did not start a cooldown (still within the free attempts). */
    data class Wrong(val failCount: Int, val lockedUntil: Long?) : PinVerifyResult

    /** Already locked out — the PIN was NOT checked (compare never ran). [until]
     *  is when the cooldown ends, as a wall-clock epoch-ms for the screens to count down
     *  to: the wall clock now plus the time left on [PinClock.sinceBootMs]. */
    data class LockedOut(val until: Long) : PinVerifyResult

    /** Wipe-after-N is enabled AND the failure count reached the wipe threshold.
     *  [PinManager] cannot wipe the wallet itself (it only owns `dgb_pin_store`);
     *  the CALLER performs the destructive wallet wipe + routes to onboarding.
     *  A `pin_wipe_pending` flag is set so a kill mid-wipe completes on next launch. */
    data object ShouldWipe : PinVerifyResult

    /** The PIN could not be checked at all: the hashing library failed before any comparison was
     *  made, so nothing is known about the entry. It is neither a right nor a wrong PIN and NOTHING
     *  was counted — the failure counter, the cooldown and wipe-after-N are exactly as they were.
     *  The caller says so and lets the owner enter the PIN again. */
    data object Unavailable : PinVerifyResult
}

/**
 * Minimal key/value abstraction over the PIN store: the sealed file [FilePinStore] (PIN-STORE), or the
 * old `dgb_pin_store` EncryptedSharedPreferences for a session whose migration could not be done.
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

    /** Stores every change in [edit] together: a process stopped there leaves all of them stored or none.
     *  A store that persists overrides this with one commit. This default applies them one at a time,
     *  which is only right for a store that keeps nothing across a process stop. */
    fun commit(edit: PinStoreEdit) {
        edit.removals.forEach { remove(it) }
        edit.ints.forEach { (key, value) -> putInt(key, value) }
        edit.longs.forEach { (key, value) -> putLong(key, value) }
        edit.booleans.forEach { (key, value) -> putBoolean(key, value) }
        edit.strings.forEach { (key, value) -> putString(key, value) }
    }
}

/** Changes to the PIN store that [PinStore.commit] stores together, or not at all. */
class PinStoreEdit {
    internal val ints = LinkedHashMap<String, Int>()
    internal val longs = LinkedHashMap<String, Long>()
    internal val booleans = LinkedHashMap<String, Boolean>()
    internal val strings = LinkedHashMap<String, String>()
    internal val removals = LinkedHashSet<String>()

    fun putInt(key: String, value: Int): PinStoreEdit = apply { ints[key] = value }
    fun putLong(key: String, value: Long): PinStoreEdit = apply { longs[key] = value }
    fun putBoolean(key: String, value: Boolean): PinStoreEdit = apply { booleans[key] = value }
    fun putString(key: String, value: String): PinStoreEdit = apply { strings[key] = value }
    fun remove(key: String): PinStoreEdit = apply { removals += key }
}

/**
 * The Argon2id hash [PinManager] stores and checks PINs with: the PIN's bytes and the salt in, the
 * 32-byte hash out. It THROWS when the hash cannot be computed (the native library did not load, or
 * the memory it asks for was not there); [PinManager] reads a throw as "not checked", never as a
 * wrong PIN. Replacing the hashing library means replacing [SignalArgon2id], and nothing else.
 */
internal fun interface Argon2idHash {
    fun hash(pin: ByteArray, salt: ByteArray): ByteArray
}

/**
 * Argon2id through the Signal library, with the parameters per OWASP recommendations for
 * interactive login (t=3, m=64MiB, p=4):
 * - iterations: 3
 * - memory: 65536 KiB (64 MiB)
 * - parallelism: 4
 * - hashLength: 32 bytes
 */
internal object SignalArgon2id : Argon2idHash {
    override fun hash(pin: ByteArray, salt: ByteArray): ByteArray =
        Argon2.Builder(Version.V13)
            .type(Type.Argon2id)
            .iterations(3)
            .memoryCostKiB(65536)
            .parallelism(4)
            .hashLength(32)
            .build()
            .hash(pin, salt)
            .hash
}

/**
 * The clocks the PIN cooldown reads. A cooldown RUNS on [sinceBootMs] alone, a clock nothing in
 * Settings can move. [wallMs] is read only to say when a cooldown ends as the wall-clock time the
 * screens count down to — never to decide whether it has ended.
 */
internal interface PinClock {
    /** Milliseconds since this boot, deep sleep included (`SystemClock.elapsedRealtime()`). The
     *  owner cannot set it, and it starts again from zero at every boot. */
    fun sinceBootMs(): Long

    /** Which boot this is (`Settings.Global.BOOT_COUNT`), or [UNKNOWN_BOOT] where it cannot be read. */
    fun bootCount(): Int

    /** The wall clock (`System.currentTimeMillis()`), which the owner can set to anything. */
    fun wallMs(): Long

    companion object {
        const val UNKNOWN_BOOT = -1
    }
}

/**
 * The device's clocks. [resolver] reads the boot count. Without one, or where the setting cannot be
 * read, the boot count is [PinClock.UNKNOWN_BOOT], and a reboot is then recognised only by
 * [sinceBootMs] reading less than it did when the cooldown was last measured.
 */
internal class DeviceClock(private val resolver: ContentResolver?) : PinClock {
    override fun sinceBootMs(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int {
        val resolver = resolver ?: return PinClock.UNKNOWN_BOOT
        return try {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, PinClock.UNKNOWN_BOOT)
        } catch (e: Exception) {
            PinClock.UNKNOWN_BOOT
        }
    }

    override fun wallMs(): Long = System.currentTimeMillis()
}

/**
 * The PIN store when it cannot be opened (4.1.2 row 23; PIN-STORE: also a sealed file that is there and
 * cannot be read): a Keystore or keyset fault made EncryptedSharedPreferences.create throw inside the Hilt-built [PinManager], so the activity died at
 * injection on every launch. Every operation refuses -- it is never read as "no PIN", which would let
 * anyone holding the phone set a new one -- and the store on disk is left exactly as it is (deleting
 * it would clear `pin_wipe_pending` and turn the fault into a PIN reset).
 */
internal object UnavailablePinStore : PinStore {
    private fun refuse(): Nothing = throw IllegalStateException("the PIN store could not be opened")
    override fun getInt(key: String, def: Int): Int = refuse()
    override fun getLong(key: String, def: Long): Long = refuse()
    override fun getString(key: String): String? = refuse()
    override fun getBoolean(key: String, def: Boolean): Boolean = refuse()
    override fun contains(key: String): Boolean = refuse()
    override fun putInt(key: String, value: Int) = refuse()
    override fun putLong(key: String, value: Long) = refuse()
    override fun putString(key: String, value: String) = refuse()
    override fun putBoolean(key: String, value: Boolean) = refuse()
    override fun remove(key: String) = refuse()
    override fun clear() = refuse()
    override fun commit(edit: PinStoreEdit) = refuse()
}

/**
 * The OLD PIN store, the `dgb_pin_store` EncryptedSharedPreferences (androidx.security-crypto, an alpha
 * library). PIN-STORE: it is no longer the store. [PinStoreMigration] reads it once, copies it into the
 * sealed file and deletes it; it serves a session only when that migration could not be done, and is
 * then tried again at the next launch. Writes use `commit()` (synchronous), as they always did.
 */
internal class LegacyEncryptedPrefsPinStore(private val context: Context) : LegacyPinStore {

    override fun exists(): Boolean {
        val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        return java.io.File(dir, "$NAME.xml").exists() || java.io.File(dir, "$NAME.xml.bak").exists()
    }

    override fun open(): LegacyPinStore.Opened = Opened(context)

    override fun delete(): Boolean = context.deleteSharedPreferences(NAME) || !exists()

    private class Opened(context: Context) : LegacyPinStore.Opened {
        private val prefs: SharedPreferences

        init {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        // EncryptedSharedPreferences.getAll() leaves out its own keyset entries.
        override fun all(): Map<String, Any?> = prefs.all

        override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
        override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
        override fun contains(key: String): Boolean = prefs.contains(key)
        // A commit() that returns false did not land: thrown, as the sealed file's writes are, so the
        // session's fail-closed paths see it (PIN-STORE review item 2).
        private fun landed(ok: Boolean) { if (!ok) throw PinStoreWriteException("the old PIN store write did not land") }
        override fun putInt(key: String, value: Int) = landed(prefs.edit().putInt(key, value).commit())
        override fun putLong(key: String, value: Long) = landed(prefs.edit().putLong(key, value).commit())
        override fun putString(key: String, value: String) = landed(prefs.edit().putString(key, value).commit())
        override fun putBoolean(key: String, value: Boolean) = landed(prefs.edit().putBoolean(key, value).commit())
        override fun remove(key: String) = landed(prefs.edit().remove(key).commit())
        override fun clear() = landed(prefs.edit().clear().commit())

        override fun commit(edit: PinStoreEdit) {
            val editor = prefs.edit()
            edit.removals.forEach { editor.remove(it) }
            edit.ints.forEach { (key, value) -> editor.putInt(key, value) }
            edit.longs.forEach { (key, value) -> editor.putLong(key, value) }
            edit.booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
            edit.strings.forEach { (key, value) -> editor.putString(key, value) }
            landed(editor.commit())
        }
    }

    private companion object {
        const val NAME = "dgb_pin_store"
    }
}

/**
 * PIN storage + verification with a persisted, tamper-resistant rate-limit.
 *
 * The counter and lockout live in `dgb_pin_store` ONLY — never the Room DB, which
 * [app.aroundtheblock.wallet.StaleDataWiper] wipes on crash-loop recovery (that would reset an
 * attacker's count). They survive force-stop and are cleared atomically by
 * [clearPin].
 *
 * Rate-limit (confirmed defaults): 3 free attempts, then 1 / 5 / 30 / 60-minute
 * cooldowns; optional wipe-after-N (default OFF) once the failure count reaches
 * [WIPE_THRESHOLD]. See the design spec at
 * `docs/superpowers/specs/2026-07-16-pin-rate-limit-design.md`.
 *
 * A cooldown runs on the monotonic clock ([PinClock.sinceBootMs]). The wall clock never decides
 * whether one has ended, so setting the date neither ends, lengthens nor starts one. The time a
 * cooldown still has to run is stored with the monotonic reading, and the boot, it was measured at,
 * and is measured again each time it is read. That clock starts again from zero at every boot: in a
 * new boot its reading is the time the device has run since it started, and that counts. The time from
 * the last reading through the shutdown cannot be known without the wall clock and does not count, so
 * a reboot never takes more off a cooldown than the time since boot.
 */
class PinManager internal constructor(
    private val store: PinStore,
    /** The one Argon2id call. Production always uses [SignalArgon2id]; tests put a stand-in here. */
    private val argon2id: Argon2idHash = SignalArgon2id,
    /** The clocks the cooldown reads. Production passes [DeviceClock] with the content resolver; tests put a fake here. */
    private val clock: PinClock = DeviceClock(resolver = null),
) {

    /** Production constructor used by Hilt — the sealed PIN store file (PIN-STORE), migrated from the old
     *  EncryptedSharedPreferences on first open. A store that cannot be opened -- the file there and
     *  unreadable, or the old store failing to open -- becomes [UnavailablePinStore] rather than a crash
     *  at injection, and never "no PIN". */
    constructor(context: Context) : this(openStore { PinStoreMigration.open(context) }, clock = DeviceClock(context.contentResolver))

    /** False when the PIN store could not be opened: nothing can be checked, so nothing is unlocked
     *  and no new PIN can be set. The app shows the PIN-store-unavailable screen instead. */
    val storeAvailable: Boolean get() = store !== UnavailablePinStore

    companion object {
        /** [open] the store, or [UnavailablePinStore] when it throws (a Keystore or keyset fault). */
        internal fun openStore(open: () -> PinStore): PinStore = try {
            open()
        } catch (t: Throwable) {
            android.util.Log.e("PinManager", "the PIN store could not be opened: ${t.javaClass.simpleName}")
            UnavailablePinStore
        }

        // Persisted state keys (all in dgb_pin_store).
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_METHOD = "pin_method"
        private const val KEY_FAIL_COUNT = "pin_fail_count"
        // The running cooldown: the time it still had to run when last measured, and the monotonic
        // reading and the boot it was measured at. No time left (absent or 0) = no cooldown.
        private const val KEY_COOLDOWN_LEFT_MS = "pin_cooldown_left_ms"
        private const val KEY_COOLDOWN_MEASURED_AT = "pin_cooldown_measured_at"
        private const val KEY_COOLDOWN_BOOT = "pin_cooldown_boot"
        // Written by 4.0.89 and earlier, which kept the cooldown on the wall clock: read once, to
        // carry a cooldown still running over (see carryOverWallClockCooldown), then removed.
        private const val KEY_WALL_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_WALL_LAST_FAIL_AT = "pin_last_fail_at"
        private const val KEY_WIPE_AFTER_N = "pin_wipe_after_n"
        private const val KEY_WIPE_PENDING = "pin_wipe_pending"

        /** Consecutive wrong attempts allowed with NO cooldown. The first cooldown
         *  starts at failCount == FREE_ATTEMPTS + 1. */
        const val FREE_ATTEMPTS = 3

        /** Total consecutive failures that trigger a wipe when wipe-after-N is on. */
        const val WIPE_THRESHOLD = 10

        /** Longest cooldown — also the most a cooldown carried over from the wall clock keeps. */
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
        // The record and a clean rate-limit slate are one write: a process stopped there leaves the old PIN
        // working with its counters, or the new one with none -- never a record that neither PIN opens.
        store.commit(
            rateLimitReset().putString(KEY_HASH, hashHex).putString(KEY_SALT, salt.toHex()).putString(KEY_METHOD, method)
        )
    }

    fun hasPin(): Boolean = store.contains(KEY_HASH)

    /** Clears the ENTIRE pin store — hash AND every rate-limit counter/flag —
     *  atomically. Used on wallet wipe / PIN reset. THROWS [PinStoreWriteException] when the store is
     *  still on disk afterwards (the sealed file deletes itself and the old store, and checks both are
     *  gone): the caller must not treat the PIN as cleared ([app.aroundtheblock.wallet.core.WalletManager.wipeThenReleasePin]
     *  reports the wipe as not verified, and it is finished at the next launch). */
    fun clearPin() {
        store.clear() // throws when it did not land: the latch stays
        wipeOwedInProcess = false
    }

    // ── Verification + rate-limit ─────────────────────────────────────────────

    /**
     * Verify [pin] against the stored hash, enforcing the persisted rate-limit.
     *
     * Algorithm (spec §3, with the cooldown on the monotonic clock):
     *  1. Cooldown check: time left on the running cooldown ⇒ [PinVerifyResult.LockedOut]
     *     WITHOUT running the (expensive, constant-time) compare — the check
     *     precedes the compare and never branches on PIN correctness.
     *  2. Compare (constant-time Argon2id/PBKDF2). A hasher that could not run has
     *     checked nothing ⇒ [PinVerifyResult.Unavailable], with every counter untouched.
     *  3. Success ⇒ reset counters ⇒ [PinVerifyResult.Success].
     *  4. Fail ⇒ [PinVerifyResult.ShouldWipe] (+ set wipe-pending) if wipe-after-N && count ≥
     *     threshold; else [PinVerifyResult.Wrong] with the cooldown that count starts.
     *
     * PIN-STORE review item 2 — no PIN is compared before its failure is on disk. The count this entry
     * would reach, and the cooldown it would start, are written BEFORE the compare (one write); a match
     * then resets them. If that write does not land, nothing is compared and the result is
     * [PinVerifyResult.Unavailable]: a store that cannot take a write gives no free guesses, in this
     * process or across restarts, and no match/mismatch signal either. A process stopped during the
     * compare leaves the entry counted as a failure (fail closed). A hasher that cannot run puts every
     * key it touched back exactly as it was (best effort; left counted if that write fails).
     *
     * The entry that reaches wipe-after-N is no exception: its count is written before the compare
     * too, WITHOUT the owed-wipe flag, so a right PIN stopped mid-compare is never wiped, and a store
     * that cannot take that write gets no compare (Unavailable). The owed wipe is written after a
     * mismatch; if that write fails, [isWipePending] still answers true in this process (an in-process
     * latch) until [clearWipePending] lands, so the unlock screen keeps holding for the wipe rather
     * than taking another PIN.
     *
     * Synchronized: the count read, its write and the compare are one step, so two entries racing
     * (the unlock screen and a spend dialog, say) cannot both be compared against one count.
     *
     * The wall clock decides nothing here: setting it neither ends, lengthens nor starts a cooldown.
     */
    @Synchronized
    fun verifyPin(pin: String): PinVerifyResult {
        // 1. Cooldown check — runs BEFORE the compare; leaks nothing about the PIN.
        val left = cooldownLeftMs()
        if (left > 0L) {
            return PinVerifyResult.LockedOut(clock.wallMs() + left)
        }

        // 2. The failure this entry would be, on disk before the compare.
        val count = store.getInt(KEY_FAIL_COUNT, 0) + 1
        val wipes = isWipeAfterNEnabled() && count >= WIPE_THRESHOLD
        val cooldown = if (wipes) 0L else cooldownMsForFailCount(count)
        val undo = undoOf(KEY_FAIL_COUNT, KEY_COOLDOWN_LEFT_MS, KEY_COOLDOWN_MEASURED_AT, KEY_COOLDOWN_BOOT)
        run {
            val failure = PinStoreEdit().putInt(KEY_FAIL_COUNT, count)
            if (cooldown > 0L) failure.withCooldown(cooldown, clock.sinceBootMs(), clock.bootCount())
            try {
                store.commit(failure)
            } catch (e: PinStoreWriteException) {
                android.util.Log.e("PinManager", "the failure count did not persist; the PIN was not checked")
                return PinVerifyResult.Unavailable
            }
        }

        // 3. Constant-time compare.
        when (checkPin(pin)) {
            PinCheck.MATCH -> {
                // Any valid PIN resets the limiter. A reset that does not land leaves the count higher
                // on disk than it is: the safe direction, and the PIN was right.
                try { resetRateLimit() } catch (e: PinStoreWriteException) {
                    android.util.Log.e("PinManager", "the reset after a right PIN did not persist")
                }
                return PinVerifyResult.Success
            }
            PinCheck.UNAVAILABLE -> {
                // Nothing was checked, so nothing is counted: what step 2 wrote goes back as it was.
                try { store.commit(undo) } catch (e: PinStoreWriteException) {
                    android.util.Log.e("PinManager", "the count of an unchecked PIN could not be put back; it stays counted")
                }
                return PinVerifyResult.Unavailable
            }
            PinCheck.MISMATCH -> Unit
        }

        // 4. Failure — already counted, with its cooldown.
        if (wipes) {
            // The owed wipe: a backstop so a kill between here and the caller's wipe still completes the
            // wipe on next launch. The latch holds it in this process whether or not the write lands.
            wipeOwedInProcess = true
            try {
                store.putBoolean(KEY_WIPE_PENDING, true)
            } catch (e: PinStoreWriteException) {
                android.util.Log.e("PinManager", "the owed wipe did not persist; held in this process, the wipe goes ahead")
            }
            return PinVerifyResult.ShouldWipe
        }
        return PinVerifyResult.Wrong(count, if (cooldown > 0L) clock.wallMs() + cooldown else null)
    }

    /** Reset the rate-limit counters. Called on a successful PIN verify AND on a
     *  successful BIOMETRIC unlock (a valid unlock must clear any stale lockout so
     *  a legit user isn't locked out of their own PIN afterward). */
    fun onUnlockSuccess() {
        try { resetRateLimit() } catch (e: PinStoreWriteException) {
            android.util.Log.e("PinManager", "the reset after an unlock did not persist") // count left higher: safe
        }
    }

    /** When the running cooldown ends, as a wall-clock epoch-ms for the screens to count down
     *  to — the wall clock now plus the time left on the monotonic clock — or 0 if none is
     *  running. Lets the UnlockScreen resume a countdown that spans a process restart or a reboot. */
    fun currentLockout(): Long {
        val left = cooldownLeftMs()
        return if (left > 0L) clock.wallMs() + left else 0L
    }

    /** An edit that puts [keys] back exactly as they are now (present with this value, or absent). */
    private fun undoOf(vararg keys: String): PinStoreEdit {
        val edit = PinStoreEdit()
        for (key in keys) {
            if (!store.contains(key)) { edit.remove(key); continue }
            when (key) {
                KEY_FAIL_COUNT, KEY_COOLDOWN_BOOT -> edit.putInt(key, store.getInt(key, 0))
                else -> edit.putLong(key, store.getLong(key, 0L))
            }
        }
        return edit
    }

    private fun resetRateLimit() {
        store.commit(rateLimitReset())
    }

    /** Clears the failure count, the running cooldown, and a wall-clock cooldown 4.0.89 left behind. */
    private fun rateLimitReset(): PinStoreEdit =
        PinStoreEdit().putInt(KEY_FAIL_COUNT, 0)
            .remove(KEY_COOLDOWN_LEFT_MS).remove(KEY_WALL_LOCKOUT_UNTIL).remove(KEY_WALL_LAST_FAIL_AT)

    // ── The cooldown on the monotonic clock ───────────────────────────────────

    /**
     * The time the running cooldown still has to run, or 0 if none is running. It is measured on
     * the monotonic clock and recorded, so the next reading — after a process restart or a reboot —
     * starts from this one; a cooldown found to have run out is cleared, so no reboot brings it back.
     * Where the boot count cannot be read, a new boot whose clock already reads past the last reading
     * is taken for the same boot: then only the time past that reading counts, less than the time
     * since boot.
     */
    private fun cooldownLeftMs(): Long {
        val unsaved = carryOverWallClockCooldown()
        if (unsaved > 0L) return unsaved // not persisted, still enforced
        val left = store.getLong(KEY_COOLDOWN_LEFT_MS, 0L)
        if (left <= 0L) return 0L
        val now = clock.sinceBootMs()
        val boot = clock.bootCount()
        val measuredAt = store.getLong(KEY_COOLDOWN_MEASURED_AT, 0L)
        val sameBoot = boot == store.getInt(KEY_COOLDOWN_BOOT, PinClock.UNKNOWN_BOOT) && now >= measuredAt
        // In the boot it was measured in, the time that passed is the difference of two readings of
        // the monotonic clock. In a new boot that clock reads the time since the device started: real
        // time no one can move, so it counts. The time from the last reading through the shutdown cannot
        // be known without the wall clock and does not count: a reboot never takes more off a cooldown
        // than the time since boot.
        val stillLeft = if (sameBoot) left - (now - measuredAt) else left - now
        if (stillLeft <= 0L) {
            clearCooldown()
            return 0L
        }
        recordCooldown(stillLeft, now, boot)
        return stillLeft
    }

    /** Records [leftMs] as the time the cooldown still has to run, measured at [sinceBootMs] in boot
     *  [boot], in one write: a process stopped there leaves this measurement or the one before it. */
    private fun recordCooldown(leftMs: Long, sinceBootMs: Long, boot: Int) {
        // A measurement that does not land leaves the one before it, which reads as at least as much
        // time left: never less cooldown. So a failure here is not a reason to refuse.
        try { store.commit(PinStoreEdit().withCooldown(leftMs, sinceBootMs, boot)) } catch (e: PinStoreWriteException) {
            android.util.Log.w("PinManager", "a cooldown measurement did not persist; the earlier one stands")
        }
    }

    private fun PinStoreEdit.withCooldown(leftMs: Long, sinceBootMs: Long, boot: Int): PinStoreEdit =
        putLong(KEY_COOLDOWN_LEFT_MS, leftMs).putLong(KEY_COOLDOWN_MEASURED_AT, sinceBootMs).putInt(KEY_COOLDOWN_BOOT, boot)

    private fun clearCooldown() {
        // Not landed: the ended cooldown is found ended again at the next reading.
        if (store.contains(KEY_COOLDOWN_LEFT_MS)) {
            try { store.remove(KEY_COOLDOWN_LEFT_MS) } catch (e: PinStoreWriteException) { }
        }
    }

    /**
     * 4.0.89 and earlier kept a cooldown as a wall-clock deadline. One still running is carried over
     * once, with the time the wall clock now says it has left (at most [MAX_COOLDOWN_MS]); from then
     * on it runs on the monotonic clock like any other. The wall-clock keys are removed in the same write.
     */
    /** Returns the time left of a carried-over cooldown whose write did not land (it is still enforced), else 0. */
    private fun carryOverWallClockCooldown(): Long {
        val until = store.getLong(KEY_WALL_LOCKOUT_UNTIL, 0L)
        if (until <= 0L) return 0L
        val left = (until - clock.wallMs()).coerceIn(0L, MAX_COOLDOWN_MS)
        val edit = PinStoreEdit().remove(KEY_WALL_LOCKOUT_UNTIL).remove(KEY_WALL_LAST_FAIL_AT)
        if (left > 0L && store.getLong(KEY_COOLDOWN_LEFT_MS, 0L) <= 0L) {
            edit.withCooldown(left, clock.sinceBootMs(), clock.bootCount())
        }
        // Not landed: the wall-clock keys stay and are carried over again at the next reading; until
        // then the cooldown they hold is still enforced.
        return try {
            store.commit(edit)
            0L
        } catch (e: PinStoreWriteException) {
            left
        }
    }

    // ── Wipe-after-N toggle + backstop ────────────────────────────────────────

    fun isWipeAfterNEnabled(): Boolean = store.getBoolean(KEY_WIPE_AFTER_N, false)

    fun setWipeAfterN(enabled: Boolean) {
        store.putBoolean(KEY_WIPE_AFTER_N, enabled)
    }

    /** True if [verifyPin] returned [PinVerifyResult.ShouldWipe] but the caller's
     *  wipe has not yet completed (set atomically, honored at startup). */
    fun isWipePending(): Boolean = wipeOwedInProcess || store.getBoolean(KEY_WIPE_PENDING, false)

    /** Set by a [PinVerifyResult.ShouldWipe]; cleared only by a [clearWipePending] or [clearPin] that
     *  landed. Keeps a wipe owed in this process when its flag could not be written. */
    @Volatile private var wipeOwedInProcess = false

    /**
     * Whether a wipe is owed, as launch asks it before any screen exists (4.1.2 row 23). A store
     * that cannot be read owes none: nothing is erased on a guess, and the start route then shows
     * the store-unavailable screen. [isWipePending] threw there, before that screen could show.
     */
    fun wipeOwedAtLaunch(): Boolean = storeAvailable && readOr(false, "wipe-owed") { isWipePending() }

    /**
     * Whether a PIN is known to exist, for prompts that wait for one (the notification and battery
     * prompts). Never throws; false when the store cannot be read, so they keep waiting. Not for a
     * lock decision -- that is [pinAssumedForLock].
     */
    fun pinKnownToExist(): Boolean = storeAvailable && readOr(false, "has-pin") { hasPin() }

    /**
     * Whether the lock must treat the wallet as PIN-protected. A store that cannot be read counts
     * as "a PIN is set": the wallet is routed to the PIN screen rather than left open (review R10).
     */
    fun pinAssumedForLock(): Boolean = !storeAvailable || readOr(true, "lock has-pin") { hasPin() }

    private inline fun readOr(fallback: Boolean, what: String, read: () -> Boolean): Boolean = try {
        read()
    } catch (e: Exception) {
        android.util.Log.e("PinManager", "$what: the PIN store could not be read (${e.javaClass.simpleName}); taking $fallback")
        fallback
    }

    /** Clear the wipe-pending backstop after a successful wallet wipe. (A full
     *  [clearPin] also clears it — this is for the startup path that wipes the
     *  wallet but recreates a fresh pin store.) */
    fun clearWipePending() {
        store.remove(KEY_WIPE_PENDING) // throws when it did not land: the latch stays
        wipeOwedInProcess = false
    }

    // ── Hashing (unchanged crypto) ────────────────────────────────────────────

    /** What checking an entry against the stored record established. */
    private enum class PinCheck {
        /** The entry hashes to the stored record. */
        MATCH,
        /** The entry was checked and does not match. */
        MISMATCH,
        /** The hasher could not run, so the entry was not checked at all. */
        UNAVAILABLE,
    }

    private fun checkPin(pin: String): PinCheck {
        val storedHash = store.getString(KEY_HASH) ?: return PinCheck.MISMATCH
        val salt = store.getString(KEY_SALT)?.hexToBytes() ?: return PinCheck.MISMATCH
        val method = store.getString(KEY_METHOD) ?: "pbkdf2"
        val matches = when (method) {
            "argon2id" -> {
                val result = tryArgon2Hash(pin, salt) ?: return PinCheck.UNAVAILABLE
                constantTimeEquals(result.first.hexToBytes(), storedHash.hexToBytes())
            }
            else -> {
                val computedHash = hashWithPbkdf2(pin, salt)
                constantTimeEquals(computedHash, storedHash.hexToBytes())
            }
        }
        return if (matches) PinCheck.MATCH else PinCheck.MISMATCH
    }

    /**
     * Attempts to hash with Argon2id ([argon2id]). Returns (hashHex, "argon2id") on success,
     * null if the hash throws (e.g., unsupported ABI, or the 64 MiB it asks for is not
     * available). This is the one Argon2id call: [setPin] reads null as "write a PBKDF2 record
     * instead", and [verifyPin] reads it as [PinVerifyResult.Unavailable] — never as a wrong PIN.
     */
    private fun tryArgon2Hash(pin: String, salt: ByteArray): Pair<String, String>? {
        return try {
            Pair(argon2id.hash(pin.toByteArray(Charsets.UTF_8), salt).toHex(), "argon2id")
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
