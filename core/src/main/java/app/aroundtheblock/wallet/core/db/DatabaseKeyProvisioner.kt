package app.aroundtheblock.wallet.core.db

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.security.AndroidKeyVault
import app.aroundtheblock.wallet.core.security.EncryptedData
import app.aroundtheblock.wallet.core.security.KeyStoreManager
import app.aroundtheblock.wallet.core.security.KeyVault
import java.io.File
import java.security.SecureRandom

// STUB (seam step): final signatures, today's behaviour. Replaced by the fix.

/** What the wrapped database password on this device is, read without opening any database. */
sealed class DatabaseKeyState {
    /** The stored password unwraps. [dedicated]: under its own key, not the seed's legacy key. */
    class Stored(val password: ByteArray, val dedicated: Boolean) : DatabaseKeyState()
    /** No live record, but a re-key a previous start began: the password the file is moving to. */
    class Pending(val password: ByteArray) : DatabaseKeyState()
    data object NoRecord : DatabaseKeyState()
    data class KeyMissing(val alias: String) : DatabaseKeyState()
    data class RecordUnreadable(val alias: String) : DatabaseKeyState()
    data class KeystoreUnavailable(val cause: Throwable) : DatabaseKeyState()
}

/** What opening a database file with one password answered. */
enum class OpenAnswer { OPENS, NOT_A_DATABASE, CORRUPT, UNDECIDED }

/** What a change of a file's password left it under. */
enum class RekeyAnswer { REKEYED, UNCHANGED, UNKNOWN }

/** Why a database file was set aside. Part of the aside file's name. */
enum class AsideReason(val tag: String) {
    KEY_MISSING("key-missing"),
    RECORD_UNREADABLE("record-unreadable"),
    WRONG_PASSWORD("wrong-password"),
    CORRUPT("corrupt"),
    RECOVERY("recovery"),
}

/** How this start opens the wallet database. */
sealed class DatabaseOpenPlan {
    class Open(val password: ByteArray, val recoverOnFirstOpen: Boolean) : DatabaseOpenPlan()
    data class Temporary(val reason: String) : DatabaseOpenPlan()
}

/** One database file and its companions (`-wal`, `-shm`, `-journal`). */
interface DatabaseFileSet {
    fun exists(): Boolean
    fun setAside(reason: AsideReason): Boolean
}

/** Opening and re-keying one database file outside Room. */
interface CipherFiles {
    fun probe(password: ByteArray): OpenAnswer
    fun rekey(from: ByteArray, to: ByteArray): RekeyAnswer
}

class DatabaseFiles(
    private val dir: File,
    private val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : DatabaseFileSet {
    override fun exists(): Boolean = File(dir, name).exists()

    override fun setAside(reason: AsideReason): Boolean = false

    /** STUB ONLY — today's discard: the file and its companions are deleted. */
    internal fun deleteToday() {
        for (suffix in listOf("", "-journal", "-shm", "-wal")) File(dir, name + suffix).delete()
    }

    companion object {
        const val MAX_RECOVERY_ASIDES = 3
    }
}

/** Today's legacyProbeAnswer: only "not a database" (code 26) is told apart. */
fun openFailureAnswer(failure: Throwable?): OpenAnswer {
    if (failure == null) return OpenAnswer.OPENS
    val fileAnswered = generateSequence(failure) { it.cause }
        .take(8)
        .any { it is android.database.sqlite.SQLiteException && it.message?.let(Regex("""\bcode 26\b|file is not a database""")::containsMatchIn) == true }
    return if (fileAnswered) OpenAnswer.NOT_A_DATABASE else OpenAnswer.UNDECIDED
}

class DatabaseKeyProvisioner(
    private val records: SharedPreferences,
    private val vault: KeyVault,
    private val legacyWrapAlias: String = KeyStoreManager.KEY_ALIAS,
    private val dedicatedAlias: String = DATABASE_KEY_ALIAS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun resolve(): DatabaseKeyState {
        val stored = records.getString(WRAPPED, null) ?: return DatabaseKeyState.NoRecord
        val named = records.getString(ALIAS, null)
        val alias = named ?: legacyWrapAlias
        return try {
            val parts = stored.split(":")
            if (parts.size != 2) throw IllegalStateException("Corrupt DB key format")
            val password = vault.decrypt(alias, EncryptedData(hexToBytes(parts[0]), hexToBytes(parts[1])))
            DatabaseKeyState.Stored(password, dedicated = named != null && named != legacyWrapAlias)
        } catch (e: Exception) {
            // Today every failure is one outcome: the start's catch-all wipes the database and its key.
            DatabaseKeyState.RecordUnreadable(alias)
        }
    }

    /** Today nothing moves the record off the seed's legacy key before that key is deleted. */
    fun releaseLegacyWrap(): Boolean = true

    /** Today's newDatabaseKey: the password is wrapped under the seed's legacy key, no alias, unchecked. */
    fun provisionNew(): ByteArray? {
        vault.createUnboundKey(legacyWrapAlias)
        val password = ByteArray(PASSWORD_BYTES).also { random.nextBytes(it) }
        val wrapped = vault.encrypt(legacyWrapAlias, password)
        records.edit().putString(WRAPPED, format(wrapped)).remove(LEGACY_CONFIRMED).apply()
        return password
    }

    fun beginRekey(): ByteArray? = null

    fun promotePending(): Boolean = false

    fun legacyConfirmed(): Boolean = records.getBoolean(LEGACY_CONFIRMED, false)

    fun confirmLegacy() {
        records.edit().putBoolean(LEGACY_CONFIRMED, true).apply()
    }

    /** STUB ONLY — today's StaleDataWiper.wipeDatabase: the record and the dedicated key go. */
    internal fun clearRecordToday() {
        records.edit().clear().apply()
        runCatching { vault.delete(dedicatedAlias) }
    }

    companion object {
        const val DATABASE_KEY_ALIAS = "dgb_db_passphrase"
        const val RECORDS = "dgb_db_key"
        internal const val WRAPPED = "encrypted_key"
        internal const val ALIAS = "db_key_alias"
        internal const val PENDING = "pending_key"
        internal const val LEGACY_CONFIRMED = "legacy_key_confirmed"
        internal const val PASSWORD_BYTES = 32

        fun forContext(context: Context): DatabaseKeyProvisioner =
            DatabaseKeyProvisioner(context.getSharedPreferences(RECORDS, Context.MODE_PRIVATE), AndroidKeyVault)

        fun openWalletDatabase(
            context: Context,
            dbFileName: String,
            keys: DatabaseKeyProvisioner = forContext(context),
        ): WalletDatabase {
            val path = context.getDatabasePath(dbFileName)
            val dir = path.parentFile ?: throw IllegalStateException("database path has no directory")
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

        private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** The legacy literal password: what databases made before passwords were stored are keyed with. */
internal const val LEGACY_DB_PASSWORD = "digibyte-wallet-db"

class WalletDatabaseOpener(
    private val keys: DatabaseKeyProvisioner,
    private val files: DatabaseFileSet,
    private val cipher: CipherFiles,
    private val legacyPassword: () -> ByteArray = { LEGACY_DB_PASSWORD.toByteArray() },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    /** Today's provideDatabase, expressed through the seams. */
    fun plan(): DatabaseOpenPlan = when (val state = keys.resolve()) {
        is DatabaseKeyState.Stored -> DatabaseOpenPlan.Open(state.password, recoverOnFirstOpen = false)
        DatabaseKeyState.NoRecord ->
            if (!files.exists()) DatabaseOpenPlan.Open(keys.provisionNew()!!, recoverOnFirstOpen = false) else legacyToday()
        else -> {
            // StaleDataWiper.wipeAll, then a new database
            (files as? DatabaseFiles)?.deleteToday()
            keys.clearRecordToday()
            DatabaseOpenPlan.Open(keys.provisionNew()!!, recoverOnFirstOpen = false)
        }
    }

    private fun legacyToday(): DatabaseOpenPlan {
        val legacy = legacyPassword()
        val confirmed = keys.legacyConfirmed()
        return when (if (confirmed) OpenAnswer.OPENS else cipher.probe(legacy)) {
            OpenAnswer.OPENS -> {
                if (!confirmed) keys.confirmLegacy()
                DatabaseOpenPlan.Open(legacy, recoverOnFirstOpen = false)
            }
            OpenAnswer.NOT_A_DATABASE -> {
                (files as? DatabaseFiles)?.deleteToday()
                DatabaseOpenPlan.Open(keys.provisionNew()!!, recoverOnFirstOpen = false)
            }
            OpenAnswer.CORRUPT, OpenAnswer.UNDECIDED -> DatabaseOpenPlan.Open(legacy, recoverOnFirstOpen = false)
        }
    }

    /** Today nothing is done when the first open fails; the failure reaches the first query. */
    fun recoverFirstOpen(failure: Throwable, password: ByteArray): Boolean = false
}
