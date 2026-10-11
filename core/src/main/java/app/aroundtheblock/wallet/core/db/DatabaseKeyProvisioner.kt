package app.aroundtheblock.wallet.core.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.security.AndroidKeyVault
import app.aroundtheblock.wallet.core.security.EncryptedData
import app.aroundtheblock.wallet.core.security.KeyStoreManager
import app.aroundtheblock.wallet.core.security.KeyVault
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/*
 * The wallet database's password.
 *
 * The database is SQLCipher-encrypted under a random password, kept in the record store
 * `dgb_db_key` wrapped under a Keystore key that the record names (`db_key_alias`). A record from
 * before that key existed names none: it is wrapped under the seed's legacy key, which the seed's
 * key migration deletes. Such a record is moved onto the database's own key — read back and
 * verified — before that key may go (WalletManager asks [DatabaseKeyProvisioner.releaseLegacyWrap]
 * first), and as soon as the database opens. A database made before passwords were stored is keyed
 * with a published literal; it is re-keyed, and its new password stored only after the file has
 * been reopened with it.
 *
 * Nothing here deletes a database or a key. A database that cannot be opened is set aside under a
 * name of its own, with its companions; what cannot be decided acts on nothing — the run uses a
 * temporary database and the next start asks again.
 */

/** What the wrapped database password on this device is, read without opening any database. */
sealed class DatabaseKeyState {
    /** The stored password unwraps. [dedicated]: under its own key, not the seed's legacy key. */
    class Stored(val password: ByteArray, val dedicated: Boolean) : DatabaseKeyState()

    /** No stored password, but a re-key a previous start began: the password the file is moving to. */
    class Pending(val password: ByteArray) : DatabaseKeyState()

    /** No record at all. */
    data object NoRecord : DatabaseKeyState()

    /** The Keystore answered that the record's key is not there: the password cannot be had any more. */
    data class KeyMissing(val alias: String) : DatabaseKeyState()

    /** The record is not one its key wrapped (malformed, or the key rejects it): the password cannot be had. */
    data class RecordUnreadable(val alias: String) : DatabaseKeyState()

    /** The Keystore could not be asked. Says nothing about the password; nothing may be acted on. */
    data class KeystoreUnavailable(val cause: Throwable) : DatabaseKeyState()
}

/** What opening a database file with one password answered. */
enum class OpenAnswer { OPENS, NOT_A_DATABASE, CORRUPT, UNDECIDED }

/** What a change of a file's password left it under: verified new, verified old, or not known. */
enum class RekeyAnswer { REKEYED, UNCHANGED, UNKNOWN }

/** Why a database file was set aside. Part of the aside file's name. */
enum class AsideReason(val tag: String) {
    KEY_MISSING("key-missing"),
    RECORD_UNREADABLE("record-unreadable"),
    WRONG_PASSWORD("wrong-password"),
    CORRUPT("corrupt"),
    /** The crash-loop breaker's: the database may be fine, starts keep dying. */
    RECOVERY("recovery"),
}

/** How this start opens the wallet database. */
sealed class DatabaseOpenPlan {
    /** Open the file with [password]. [recoverOnFirstOpen]: a first open that fails is set right once. */
    class Open(val password: ByteArray, val recoverOnFirstOpen: Boolean) : DatabaseOpenPlan()

    /** Touch nothing on disk: this run uses a database in memory, and the next start asks again. */
    data class Temporary(val reason: String) : DatabaseOpenPlan()
}

/** One database file and its companions (`-wal`, `-shm`, `-journal`). */
interface DatabaseFileSet {
    fun exists(): Boolean

    /** Moves the file and its companions to a name of their own. True when nothing is left at the live name. */
    fun setAside(reason: AsideReason): Boolean
}

/** Opening and re-keying one database file outside Room. */
interface CipherFiles {
    fun probe(password: ByteArray): OpenAnswer

    /** Changes the file's password; [RekeyAnswer.REKEYED] only once it reopens with [to] and no longer with [from]. */
    fun rekey(from: ByteArray, to: ByteArray): RekeyAnswer
}

/** [DatabaseFileSet] over the files in [dir]. */
class DatabaseFiles(
    private val dir: File,
    private val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : DatabaseFileSet {

    override fun exists(): Boolean = File(dir, name).exists()

    /**
     * Moves the database to `<name>.aside-<reason>-<time>`, its companions alongside under the same
     * stem — companions first, so a new database is never opened beside an earlier one's
     * write-ahead log. A move that fails puts back what it had moved. Nothing already aside is
     * touched, and a set aside is never removed: the crash-loop breaker's asides are bounded
     * ([MAX_RECOVERY_ASIDES]) instead, because only those recur.
     */
    @Synchronized
    override fun setAside(reason: AsideReason): Boolean {
        val present = (COMPANIONS + "").map { File(dir, name + it) }.filter { it.exists() }
        if (present.isEmpty()) return true
        if (reason == AsideReason.RECOVERY && asides().count { it.name.startsWith(stemOf(AsideReason.RECOVERY)) } >= MAX_RECOVERY_ASIDES) {
            Log.w(TAG, "$name: ${MAX_RECOVERY_ASIDES} recovery asides kept already; left where it is")
            return false
        }
        val stem = freeStem(reason)
        val moved = mutableListOf<Pair<File, File>>()
        for (from in present) {
            val to = File(dir, stem + from.name.removePrefix(name))
            if (!from.renameTo(to)) {
                moved.asReversed().forEach { (back, aside) -> aside.renameTo(back) }
                Log.w(TAG, "$name: could not be set aside (${from.name})")
                return false
            }
            moved += from to to
        }
        Log.w(TAG, "$name set aside as $stem (${reason.tag})")
        return (COMPANIONS + "").none { File(dir, name + it).exists() }
    }

    /** The database files set aside so far (not their companions). */
    fun asides(): List<File> =
        dir.listFiles()?.filter { f -> f.name.startsWith("$name$ASIDE") && COMPANIONS.none { f.name.endsWith(it) } }.orEmpty()

    private fun stemOf(reason: AsideReason) = "$name$ASIDE${reason.tag}-"

    private fun freeStem(reason: AsideReason): String {
        var time = clock()
        while ((COMPANIONS + "").any { File(dir, "${stemOf(reason)}$time$it").exists() }) time++
        return "${stemOf(reason)}$time"
    }

    companion object {
        /** The crash-loop breaker's asides kept at most; beyond that it leaves the database where it is. */
        const val MAX_RECOVERY_ASIDES = 3
        internal const val ASIDE = ".aside-"
        internal val COMPANIONS = listOf("-wal", "-shm", "-journal")
        private const val TAG = "DatabaseFiles"
    }
}

/**
 * Reads what a database's own failure says about a file (null: it opened). "Not a database" (SQLite
 * 26: under SQLCipher, page 1 does not decrypt with this password) and "corrupt" (SQLite 11: the
 * password opens the file, its contents are damaged) are the file's answers; every other failure —
 * locked, a disk or space failure, a file that cannot be opened at all — says nothing about the file
 * or its password. Only the database's own exception counts, anywhere in the chain of causes.
 */
fun openFailureAnswer(failure: Throwable?): OpenAnswer {
    if (failure == null) return OpenAnswer.OPENS
    val messages = generateSequence(failure) { it.cause }
        .take(MAX_CAUSES_READ)
        .filterIsInstance<android.database.sqlite.SQLiteException>()
        .mapNotNull { it.message }
    for (message in messages) {
        if (NOT_A_DATABASE_MESSAGE.containsMatchIn(message)) return OpenAnswer.NOT_A_DATABASE
        if (CORRUPT_MESSAGE.containsMatchIn(message)) return OpenAnswer.CORRUPT
    }
    return OpenAnswer.UNDECIDED
}

private const val MAX_CAUSES_READ = 8
private val NOT_A_DATABASE_MESSAGE = Regex("""\bcode 26\b|file is not a database|SQLITE_NOTADB""")
private val CORRUPT_MESSAGE = Regex("""\bcode 11\b|database disk image is malformed|SQLITE_CORRUPT""")

/**
 * The database password's record (`dgb_db_key`) over a [KeyVault]. Every write is one checked commit,
 * read back and verified; a write that did not land leaves this process reading what the store's file
 * still holds. Nothing here deletes a key.
 */
class DatabaseKeyProvisioner(
    private val records: SharedPreferences,
    private val vault: KeyVault,
    private val legacyWrapAlias: String = KeyStoreManager.KEY_ALIAS,
    private val dedicatedAlias: String = DATABASE_KEY_ALIAS,
    private val random: SecureRandom = SecureRandom(),
) {

    /** What the record holds, told apart: see [DatabaseKeyState]. Opens no database, changes nothing. */
    fun resolve(): DatabaseKeyState {
        val wrapped = records.getString(WRAPPED, null) ?: return pending()
        val alias = records.getString(ALIAS, null) ?: legacyWrapAlias
        return unwrap(alias, wrapped) { DatabaseKeyState.Stored(it, dedicated = alias != legacyWrapAlias) }
    }

    private fun pending(): DatabaseKeyState {
        val wrapped = records.getString(PENDING, null) ?: return DatabaseKeyState.NoRecord
        return when (val state = unwrap(dedicatedAlias, wrapped) { DatabaseKeyState.Pending(it) }) {
            is DatabaseKeyState.Pending, is DatabaseKeyState.KeystoreUnavailable -> state
            // A re-key's password that can no longer be read: as if no re-key had begun.
            else -> DatabaseKeyState.NoRecord
        }
    }

    private inline fun unwrap(alias: String, wrapped: String, found: (ByteArray) -> DatabaseKeyState): DatabaseKeyState {
        val data = parse(wrapped) ?: return DatabaseKeyState.RecordUnreadable(alias)
        val present = try {
            vault.contains(alias)
        } catch (e: Exception) {
            return DatabaseKeyState.KeystoreUnavailable(e)
        }
        if (!present) return DatabaseKeyState.KeyMissing(alias)
        return try {
            found(vault.decrypt(alias, data))
        } catch (e: Exception) {
            // Only the cipher's own verdict that this key did not wrap this record is final. Anything
            // else (the key not answering, the Keystore busy) says nothing about the password.
            if (generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES_READ).any { it is AEADBadTagException }) {
                DatabaseKeyState.RecordUnreadable(alias)
            } else {
                DatabaseKeyState.KeystoreUnavailable(e)
            }
        }
    }

    /**
     * Makes sure the record does not depend on the seed's legacy key, so that key can be deleted. True
     * when it does not: there is no record, or it names its own key (the beta cohort's records did
     * already, and every record does from now on — left alone). A record still under the legacy key is
     * re-wrapped under its own key, written with its alias in one checked commit, read back and
     * verified; any failure puts the old record back and answers false, and the caller keeps the key.
     */
    fun releaseLegacyWrap(): Boolean = synchronized(LOCK) {
        val wrapped = records.getString(WRAPPED, null) ?: return true
        val named = records.getString(ALIAS, null)
        if (named != null && named != legacyWrapAlias) return true
        val data = parse(wrapped) ?: return false.also { Log.w(TAG, "key record unreadable; the legacy key stays") }
        val password = try {
            vault.decrypt(legacyWrapAlias, data)
        } catch (e: Exception) {
            Log.w(TAG, "key record not moved (${e.javaClass.simpleName}); the legacy key stays")
            return false
        }
        val held = records.all
        var written = false
        try {
            vault.createUnboundKey(dedicatedAlias)
            val rewrapped = format(vault.encrypt(dedicatedAlias, password))
            written = true
            val landed = records.edit().putString(WRAPPED, rewrapped).putString(ALIAS, dedicatedAlias).commit()
            if (landed && readsBack(WRAPPED, rewrapped, password)) {
                Log.i(TAG, "key record moved onto its own key")
                return true
            }
            Log.w(TAG, "key record move not confirmed; the old record and the legacy key stay")
        } catch (e: Exception) {
            Log.w(TAG, "key record not moved (${e.javaClass.simpleName}); the legacy key stays")
        } finally {
            password.fill(0)
        }
        if (written) putBack(held)
        return false
    }

    /**
     * A new database's password: random, wrapped under the database's own key, stored with its alias in
     * one checked commit, read back and verified. Null when any of that failed (nothing is left changed).
     * A record it replaces is kept beside it.
     */
    fun provisionNew(): ByteArray? = synchronized(LOCK) {
        val password = ByteArray(PASSWORD_BYTES).also { random.nextBytes(it) }
        val held = records.all
        var written = false
        try {
            vault.createUnboundKey(dedicatedAlias)
            val wrapped = format(vault.encrypt(dedicatedAlias, password))
            val edit = records.edit()
                .putString(WRAPPED, wrapped)
                .putString(ALIAS, dedicatedAlias)
                .remove(PENDING)
                .remove(LEGACY_CONFIRMED)
            (held[WRAPPED] as? String)?.let { previous ->
                edit.putString(SUPERSEDED, previous)
                (held[ALIAS] as? String)?.let { edit.putString(SUPERSEDED_ALIAS, it) } ?: edit.remove(SUPERSEDED_ALIAS)
            }
            written = true
            if (edit.commit() && readsBack(WRAPPED, wrapped, password)) return password
            Log.w(TAG, "new key record not confirmed")
        } catch (e: Exception) {
            Log.w(TAG, "new key record not made (${e.javaClass.simpleName})")
        }
        if (written) putBack(held)
        password.fill(0)
        return null
    }

    /**
     * The password a legacy-password file is about to be re-keyed to, kept — pending, under the
     * database's own key, read back and verified — BEFORE the file changes, so a re-key the process
     * does not live to finish is never left with its password unknown. It is not the stored password:
     * that is [promotePending], once the file has reopened with it. Null when it could not be kept.
     */
    fun beginRekey(): ByteArray? = synchronized(LOCK) {
        val password = ByteArray(PASSWORD_BYTES).also { random.nextBytes(it) }
        val held = records.all
        var written = false
        try {
            vault.createUnboundKey(dedicatedAlias)
            val wrapped = format(vault.encrypt(dedicatedAlias, password))
            written = true
            if (records.edit().putString(PENDING, wrapped).commit() && readsBack(PENDING, wrapped, password)) return password
            Log.w(TAG, "re-key password not confirmed; the database stays as it is")
        } catch (e: Exception) {
            Log.w(TAG, "re-key password not kept (${e.javaClass.simpleName}); the database stays as it is")
        }
        if (written) putBack(held)
        password.fill(0)
        return null
    }

    /** The pending password becomes the stored one — called only once the file has reopened with it. */
    fun promotePending(password: ByteArray): Boolean = synchronized(LOCK) {
        val wrapped = records.getString(PENDING, null)
            ?: return (records.getString(ALIAS, null) == dedicatedAlias && records.contains(WRAPPED))
        val held = records.all
        val landed = records.edit()
            .putString(WRAPPED, wrapped)
            .putString(ALIAS, dedicatedAlias)
            .remove(PENDING)
            .remove(LEGACY_CONFIRMED)
            .commit()
        if (landed && !records.contains(PENDING) && readsBack(WRAPPED, wrapped, password)) return true
        Log.w(TAG, "re-keyed; the stored password is written at a later start")
        putBack(held)
        return false
    }

    fun legacyConfirmed(): Boolean = records.getBoolean(LEGACY_CONFIRMED, false)

    /** The legacy password has opened this install's database file: later starts need not ask it again. */
    fun confirmLegacy() {
        if (!legacyConfirmed()) records.edit().putBoolean(LEGACY_CONFIRMED, true).commit()
    }

    /** [field] holds [expected] (and, for the stored password, the database's own alias), and it unwraps to [password]. */
    private fun readsBack(field: String, expected: String, password: ByteArray): Boolean {
        if (records.getString(field, null) != expected) return false
        if (field == WRAPPED && records.getString(ALIAS, null) != dedicatedAlias) return false
        val data = parse(expected) ?: return false
        val back = try {
            vault.decrypt(dedicatedAlias, data)
        } catch (e: Exception) {
            return false
        }
        return try {
            back.contentEquals(password)
        } finally {
            back.fill(0)
        }
    }

    /** After a write that did not land or did not verify: put [held] back, in this process and (if it lands) on disk. */
    private fun putBack(held: Map<String, *>) {
        val editor = records.edit().clear()
        for ((key, value) in held) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!editor.commit()) Log.w(TAG, "key record put back in this process only")
    }

    companion object {
        /** The database password's own Keystore key. Not bound to user authentication: the database opens in the background. */
        const val DATABASE_KEY_ALIAS = "dgb_db_passphrase"
        const val RECORDS = "dgb_db_key"
        internal const val WRAPPED = "encrypted_key"
        internal const val ALIAS = "db_key_alias"
        internal const val PENDING = "pending_key"
        internal const val LEGACY_CONFIRMED = "legacy_key_confirmed"
        internal const val SUPERSEDED = "superseded_key"
        internal const val SUPERSEDED_ALIAS = "superseded_key_alias"
        internal const val PASSWORD_BYTES = 32
        private const val TAG = "DatabaseKey"
        private val LOCK = Any()

        fun forContext(context: Context): DatabaseKeyProvisioner =
            DatabaseKeyProvisioner(context.getSharedPreferences(RECORDS, Context.MODE_PRIVATE), AndroidKeyVault)

        /**
         * The wallet database [dbFileName], opened as [WalletDatabaseOpener] decides. Never deletes a
         * database or a key; see the file comment.
         */
        fun openWalletDatabase(
            context: Context,
            dbFileName: String,
            keys: DatabaseKeyProvisioner = forContext(context),
        ): WalletDatabase {
            val path = context.getDatabasePath(dbFileName)
            val dir = path.parentFile ?: throw IllegalStateException("database path has no directory")
            dir.mkdirs()
            val opener = WalletDatabaseOpener(keys, DatabaseFiles(dir, dbFileName), WalletDatabase.cipherFiles(context, path))
            return when (val plan = opener.plan()) {
                is DatabaseOpenPlan.Open -> WalletDatabase.create(
                    context, plan.password, dbFileName,
                    onFirstOpenFailure = if (plan.recoverOnFirstOpen) {
                        { failure -> opener.recoverFirstOpen(failure, plan.password) }
                    } else null,
                )
                is DatabaseOpenPlan.Temporary -> WalletDatabase.createTemporary(context)
            }
        }

        internal fun format(data: EncryptedData): String = "${bytesToHex(data.ciphertext)}:${bytesToHex(data.iv)}"

        /** A record field as written by [format]; null when it is not one. */
        internal fun parse(wrapped: String): EncryptedData? {
            val parts = wrapped.split(":")
            if (parts.size != 2) return null
            val ciphertext = hexToBytesOrNull(parts[0]) ?: return null
            val iv = hexToBytesOrNull(parts[1]) ?: return null
            if (ciphertext.isEmpty() || iv.isEmpty()) return null
            return EncryptedData(ciphertext, iv)
        }

        private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytesOrNull(hex: String): ByteArray? {
            if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}

/** The legacy literal password: what databases made before passwords were stored are keyed with. */
internal const val LEGACY_DB_PASSWORD = "digibyte-wallet-db"

/**
 * Decides how a start opens the wallet database, over seams (the record, the files, the cipher) so
 * every branch runs on a plain JVM. The invariants, with the tests that hold them
 * (`DatabaseOpenPlanTest`, `DatabaseKeyDeviceTest`):
 *  - a database that cannot be opened is renamed aside, never deleted;
 *  - a missing key, a temporary Keystore error, a wrong password and a corrupt file are told apart;
 *  - a legacy-password database is re-keyed, and its wrapped password stored only after a verified
 *    reopen.
 */
class WalletDatabaseOpener(
    private val keys: DatabaseKeyProvisioner,
    private val files: DatabaseFileSet,
    private val cipher: CipherFiles,
    private val legacyPassword: () -> ByteArray = { LEGACY_DB_PASSWORD.toByteArray() },
    private val pause: (Long) -> Unit = Thread::sleep,
) {

    fun plan(): DatabaseOpenPlan = when (val state = resolveSteadily()) {
        is DatabaseKeyState.KeystoreUnavailable ->
            temporary("the Keystore could not be asked (${state.cause.javaClass.simpleName})")
        is DatabaseKeyState.Stored -> {
            // A record still under the seed's legacy key moves onto its own key now, so the seed's
            // key migration never finds the database depending on the key it deletes. Deletes nothing.
            if (!state.dedicated && !keys.releaseLegacyWrap()) Log.w(TAG, "key record still under the legacy key; asked again later")
            DatabaseOpenPlan.Open(state.password, recoverOnFirstOpen = true)
        }
        is DatabaseKeyState.Pending -> resumeRekey(state.password)
        is DatabaseKeyState.KeyMissing -> replace(AsideReason.KEY_MISSING)
        is DatabaseKeyState.RecordUnreadable -> replace(AsideReason.RECORD_UNREADABLE)
        DatabaseKeyState.NoRecord -> if (files.exists()) legacyFile() else newDatabase()
    }

    /**
     * Room's first open of [password]'s database failed with [failure]: set the file right, once. True
     * when something changed and the open should be tried again. Not a database under the stored
     * password: a file the legacy password opens is re-keyed to it; one nothing here opens is set aside
     * as a wrong password. Corrupt: set aside. Anything else acts on nothing.
     */
    fun recoverFirstOpen(failure: Throwable, password: ByteArray): Boolean {
        val answer = openFailureAnswer(failure)
        val acted = when (answer) {
            OpenAnswer.NOT_A_DATABASE -> when (cipher.probe(legacyPassword())) {
                OpenAnswer.OPENS -> cipher.rekey(legacyPassword(), password) == RekeyAnswer.REKEYED
                OpenAnswer.NOT_A_DATABASE -> files.setAside(AsideReason.WRONG_PASSWORD)
                OpenAnswer.CORRUPT -> files.setAside(AsideReason.CORRUPT)
                OpenAnswer.UNDECIDED -> false
            }
            OpenAnswer.CORRUPT -> files.setAside(AsideReason.CORRUPT)
            OpenAnswer.OPENS, OpenAnswer.UNDECIDED -> false
        }
        Log.w(TAG, "first open failed ($answer); ${if (acted) "set right, opening again" else "nothing acted on"}")
        return acted
    }

    private fun resolveSteadily(): DatabaseKeyState {
        var state = keys.resolve()
        var tries = 1
        while (state is DatabaseKeyState.KeystoreUnavailable && tries < KEYSTORE_TRIES) {
            pause(KEYSTORE_RETRY_MS)
            state = keys.resolve()
            tries++
        }
        return state
    }

    /** The password this file was keyed with cannot be had on this device: set it aside, then a new one. */
    private fun replace(reason: AsideReason): DatabaseOpenPlan {
        if (files.exists() && !files.setAside(reason)) return temporary("a database no key here opens could not be set aside")
        return newDatabase()
    }

    private fun newDatabase(): DatabaseOpenPlan {
        val password = keys.provisionNew() ?: return temporary("a new database password could not be stored")
        return DatabaseOpenPlan.Open(password, recoverOnFirstOpen = true)
    }

    /** A file with no stored password: one made before passwords were stored, or one whose record a wipe removed. */
    private fun legacyFile(): DatabaseOpenPlan {
        val legacy = legacyPassword()
        return when (if (keys.legacyConfirmed()) OpenAnswer.OPENS else cipher.probe(legacy)) {
            OpenAnswer.OPENS -> {
                keys.confirmLegacy()
                rekeyLegacy(legacy)
            }
            OpenAnswer.NOT_A_DATABASE -> replace(AsideReason.WRONG_PASSWORD)
            OpenAnswer.CORRUPT -> replace(AsideReason.CORRUPT)
            // The probe said nothing about the file's password: keep it and open it as before; record nothing.
            OpenAnswer.UNDECIDED -> DatabaseOpenPlan.Open(legacy, recoverOnFirstOpen = false)
        }
    }

    private fun rekeyLegacy(legacy: ByteArray): DatabaseOpenPlan {
        val target = keys.beginRekey() ?: return DatabaseOpenPlan.Open(legacy, recoverOnFirstOpen = false)
        return finishRekey(legacy, target)
    }

    private fun finishRekey(from: ByteArray, target: ByteArray): DatabaseOpenPlan = when (cipher.rekey(from, target)) {
        // The file reopened with its new password (verified inside rekey): only now is it stored.
        RekeyAnswer.REKEYED -> promoted(target)
        RekeyAnswer.UNCHANGED -> DatabaseOpenPlan.Open(from, recoverOnFirstOpen = false)
        RekeyAnswer.UNKNOWN -> temporary("a re-key could not be verified; the next start finishes it")
    }

    /** A re-key a previous start began: finish it from whatever the file is keyed with now. */
    private fun resumeRekey(target: ByteArray): DatabaseOpenPlan {
        if (!files.exists()) return promoted(target)
        return when (cipher.probe(target)) {
            OpenAnswer.OPENS -> promoted(target)
            OpenAnswer.CORRUPT -> setAsideThen(AsideReason.CORRUPT) { promoted(target) }
            OpenAnswer.UNDECIDED -> temporary("a database being re-keyed could not be asked")
            OpenAnswer.NOT_A_DATABASE -> when (cipher.probe(legacyPassword())) {
                OpenAnswer.OPENS -> finishRekey(legacyPassword(), target)
                OpenAnswer.NOT_A_DATABASE -> setAsideThen(AsideReason.WRONG_PASSWORD) { promoted(target) }
                OpenAnswer.CORRUPT -> setAsideThen(AsideReason.CORRUPT) { promoted(target) }
                OpenAnswer.UNDECIDED -> temporary("a database being re-keyed could not be asked")
            }
        }
    }

    private inline fun setAsideThen(reason: AsideReason, then: () -> DatabaseOpenPlan): DatabaseOpenPlan =
        if (files.setAside(reason)) then() else temporary("a database could not be set aside")

    private fun promoted(target: ByteArray): DatabaseOpenPlan {
        keys.promotePending(target)
        return DatabaseOpenPlan.Open(target, recoverOnFirstOpen = true)
    }

    private fun temporary(reason: String): DatabaseOpenPlan {
        Log.w(TAG, "wallet database not opened this run: $reason; a temporary database is used")
        return DatabaseOpenPlan.Temporary(reason)
    }

    private companion object {
        const val TAG = "DatabaseKey"
        const val KEYSTORE_TRIES = 3
        const val KEYSTORE_RETRY_MS = 150L
    }
}
