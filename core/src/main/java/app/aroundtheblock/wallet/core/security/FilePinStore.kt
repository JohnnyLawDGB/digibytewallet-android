package app.aroundtheblock.wallet.core.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import app.aroundtheblock.wallet.core.sync.DurableFile

/*
 * PIN-STORE: the PIN hash, its salt and the rate-limit counters live in a file the app controls,
 * encrypted with AES-256-GCM under a dedicated Android Keystore key. They no longer depend on the
 * alpha EncryptedSharedPreferences, which is read once, to migrate, and then deleted.
 *
 *  - A file that is there but cannot be read (bad tag, truncated, wrong format, key gone, Keystore
 *    fault) THROWS at open. [PinManager.openStore] turns that into [UnavailablePinStore]: the
 *    PIN-store-unavailable screen, never "no PIN". The file is left exactly as it is.
 *  - No file at all is an empty store, as no `dgb_pin_store` prefs file was before.
 *  - Every write replaces the whole file at once, through the one durable-write procedure
 *    ([DurableFile.write]: tmp, fsync, rename, directory sync): a process stopped there leaves the
 *    previous contents or the new ones, never a mix, and the rename is on disk before anything relies
 *    on it (above all, before the migration deletes the old store).
 *  - A write that does not land THROWS [PinStoreWriteException] and changes nothing, in memory or on
 *    disk: the caller decides, and fails closed (PIN-STORE review item 2).
 */

/** A PIN-store write (or the clear) that did not land. The store is exactly as it was before it. */
class PinStoreWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Makes a rename in a directory durable: fsync on the directory itself. Best-effort, as it is for
 * every app that does this: a filesystem that refuses a directory fsync has nothing more to give.
 */
internal fun interface DirSync {
    fun sync(dir: File)

    companion object {
        /** The production one: `open(dir, O_RDONLY)` then `fsync`. Never throws. */
        val OS = DirSync { dir ->
            try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            } catch (t: Throwable) {
                try {
                    android.util.Log.w("PinStore", "PIN-STORE: directory sync not done (${t.javaClass.simpleName})")
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/** Seals and opens the PIN store's bytes. Production is [KeystorePinStoreCipher]; tests use a software key. */
internal interface PinStoreCipher {
    fun seal(plain: ByteArray): ByteArray

    /** Throws when [sealed] is not something [seal] produced under this key, or the key is gone. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM under one Keystore key, [ALIAS], used for nothing else. The key is not bound to
 * device authentication: the PIN store must be readable to check the PIN that unlocks the app.
 * The key is created by the first [seal] and never by [open], so a key that has gone makes the
 * store unreadable rather than silently new.
 *
 * Sealed layout: [FORMAT] (1 byte) ‖ IV (12 bytes) ‖ ciphertext with its 16-byte tag.
 */
internal class KeystorePinStoreCipher(private val alias: String = ALIAS) : PinStoreCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val key = existingKey() ?: createKey()
        return sealWith(key, plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val key = existingKey() ?: throw GeneralSecurityException("the PIN store key is not there")
        return openWith(key, sealed)
    }

    companion object {
        const val ALIAS = "dgb_pin_store_key"
        private const val FORMAT: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "dgb_pin_store/v1".toByteArray(Charsets.US_ASCII)

        /** The sealing itself, shared with the tests' software key. The IV is the cipher's own. */
        fun sealWith(key: SecretKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(AAD)
            val body = cipher.doFinal(plain)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected IV")
            return byteArrayOf(FORMAT) + iv + body
        }

        fun openWith(key: SecretKey, sealed: ByteArray): ByteArray {
            if (sealed.size < 1 + IV_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT) {
                throw GeneralSecurityException("not a sealed PIN store")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
            cipher.updateAAD(AAD)
            return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        }
    }
}

/**
 * The plaintext: a typed, sorted list of entries. Only the four types [PinManager] writes exist;
 * anything else refuses to encode, and anything malformed refuses to decode.
 */
internal object PinStoreCodec {
    private const val MAGIC = 0x44505331 // "DPS1"
    private const val T_INT = 1
    private const val T_LONG = 2
    private const val T_BOOLEAN = 3
    private const val T_STRING = 4

    fun encode(values: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(values.size)
            for (key in values.keys.sorted()) {
                out.writeUTF(key)
                when (val value = values.getValue(key)) {
                    is Int -> { out.writeByte(T_INT); out.writeInt(value) }
                    is Long -> { out.writeByte(T_LONG); out.writeLong(value) }
                    is Boolean -> { out.writeByte(T_BOOLEAN); out.writeBoolean(value) }
                    is String -> { out.writeByte(T_STRING); out.writeUTF(value) }
                    else -> throw IllegalArgumentException("$key: a ${value.javaClass.simpleName} has no place in the PIN store")
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(plain: ByteArray): Map<String, Any> {
        val input = DataInputStream(ByteArrayInputStream(plain))
        if (input.readInt() != MAGIC) throw IOException("not a PIN store")
        val count = input.readInt()
        if (count < 0 || count > 1024) throw IOException("implausible entry count")
        val values = LinkedHashMap<String, Any>()
        repeat(count) {
            val key = input.readUTF()
            val value: Any = when (input.readUnsignedByte()) {
                T_INT -> input.readInt()
                T_LONG -> input.readLong()
                T_BOOLEAN -> input.readBoolean()
                T_STRING -> input.readUTF()
                else -> throw IOException("unknown entry type")
            }
            if (values.put(key, value) != null) throw IOException("duplicate entry")
        }
        if (input.read() != -1) throw IOException("trailing bytes")
        return values
    }
}

/**
 * Writes [bytes] to [target] in one step through [DurableFile.write], the one copy of the durable
 * write (tmp, fsync, rename, directory sync, a failed rename never touching the live file), with the
 * directory sync this store is given. THROWS when the write did not land; [target] is then as it was.
 */
internal fun writeWhole(target: File, bytes: ByteArray, tmpSuffix: String = ".tmp", dirSync: DirSync = DirSync.OS) {
    val dir = target.parentFile ?: throw IOException("no directory")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the directory")
    val tmp = File(dir, target.name + tmpSuffix)
    if (!DurableFile.write(target, tmp, bytes) { d -> d?.let(dirSync::sync) }) throw IOException("the write did not land")
}

/**
 * The [PinStore] over the sealed file. It holds the decoded entries in memory and writes the whole
 * set on every change. Construct through [open], which throws on a file that is there and unreadable.
 *
 * A write that does not land is tried once more at once (the Keystore call in [PinStoreCipher.seal]
 * can fail for a moment where the old store's in-memory keyset could not). One that still does not
 * land THROWS [PinStoreWriteException] and the change is NOT kept, in memory either: what this store
 * answers is always what is on disk, so no counter runs on that did not persist. [PinManager] fails
 * closed on it (no PIN is compared before its failure is on disk; a wipe whose PIN store did not clear
 * is not verified).
 *
 * [clear] deletes the file and the old store beside it (when [legacy] is given), and checks both are
 * gone: an empty file or an old store left behind would bring the wiped PIN back.
 */
internal class FilePinStore private constructor(
    private val file: File,
    private val cipher: PinStoreCipher,
    initial: Map<String, Any>,
    private val dirSync: DirSync,
    private val legacy: LegacyPinStore?,
) : PinStore {

    private var values: Map<String, Any> = initial

    companion object {
        /** Opens [file]: its entries, or none when there is no file. THROWS when it is there and cannot be read. */
        fun open(file: File, cipher: PinStoreCipher, dirSync: DirSync = DirSync.OS, legacy: LegacyPinStore? = null): FilePinStore {
            File(file.parentFile, file.name + ".tmp").delete() // a write that stopped before its rename
            val values = if (file.exists()) read(file, cipher) else emptyMap()
            return FilePinStore(file, cipher, values, dirSync, legacy)
        }

        /** A store over entries already written to and verified in [file] (the migration's). */
        internal fun over(file: File, cipher: PinStoreCipher, values: Map<String, Any>, dirSync: DirSync = DirSync.OS, legacy: LegacyPinStore? = null) =
            FilePinStore(file, cipher, values, dirSync, legacy)

        /** Attempts per write: the first and one immediate retry. */
        internal const val WRITE_ATTEMPTS = 2

        fun read(file: File, cipher: PinStoreCipher): Map<String, Any> = PinStoreCodec.decode(cipher.open(file.readBytes()))

        fun seal(cipher: PinStoreCipher, values: Map<String, Any>): ByteArray = cipher.seal(PinStoreCodec.encode(values))
    }

    @Synchronized private fun change(block: MutableMap<String, Any>.() -> Unit) {
        val next = LinkedHashMap(values).apply(block)
        var last: Throwable? = null
        for (attempt in 1..WRITE_ATTEMPTS) {
            try {
                writeWhole(file, seal(cipher, next), dirSync = dirSync)
                values = next // only what landed
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        android.util.Log.e("PinStore", "PIN-STORE: write not landed (${last?.javaClass?.simpleName}); nothing changed")
        throw PinStoreWriteException("the PIN store write did not land", last)
    }

    /**
     * Removes the PIN store from disk: the old store first (left alone it would be migrated back at the
     * next launch), then the file, then a directory sync, then a check that both are gone. THROWS
     * [PinStoreWriteException] when either is still there; the entries in memory are kept then, as
     * they are still what is on disk.
     */
    @Synchronized override fun clear() {
        val oldGone = legacy?.let { l -> try { !l.exists() || l.delete() } catch (t: Throwable) { false } } ?: true
        if (!oldGone) throw PinStoreWriteException("the old PIN store did not delete")
        DurableFile.delete(file, File(file.parentFile, file.name + ".tmp"))
        file.parentFile?.let { dirSync.sync(it) }
        val old = legacy
        if (file.exists() || (old != null && runCatching { old.exists() }.getOrDefault(true))) {
            android.util.Log.e("PinStore", "PIN-STORE: clear did not land")
            throw PinStoreWriteException("the PIN store is still on disk after its clear")
        }
        values = emptyMap()
    }

    @Synchronized private fun get(key: String): Any? = values[key]

    override fun getInt(key: String, def: Int): Int = get(key)?.let { it as Int } ?: def
    override fun getLong(key: String, def: Long): Long = get(key)?.let { it as Long } ?: def
    override fun getString(key: String): String? = get(key) as String?
    override fun getBoolean(key: String, def: Boolean): Boolean = get(key)?.let { it as Boolean } ?: def
    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)
    override fun putInt(key: String, value: Int) = change { put(key, value) }
    override fun putLong(key: String, value: Long) = change { put(key, value) }
    override fun putString(key: String, value: String) = change { put(key, value) }
    override fun putBoolean(key: String, value: Boolean) = change { put(key, value) }
    override fun remove(key: String) = change { remove(key) }

    override fun commit(edit: PinStoreEdit) = change {
        edit.removals.forEach { remove(it) }
        putAll(edit.ints)
        putAll(edit.longs)
        putAll(edit.booleans)
        putAll(edit.strings)
    }
}

/** The old `dgb_pin_store` EncryptedSharedPreferences, as the migration sees it. */
internal interface LegacyPinStore {
    /** Whether the old store is on disk at all (checked without opening it). */
    fun exists(): Boolean

    /** Opens it. Throws as opening it always could (a Keystore or keyset fault). */
    fun open(): Opened

    /** Deletes it from disk; true when it is gone. */
    fun delete(): Boolean

    interface Opened : PinStore {
        /** Every entry it holds, decrypted. */
        fun all(): Map<String, Any?>
    }
}

/**
 * Opens the PIN store, migrating the old one first when it is still there.
 *
 * Migration: read the old store once, write the new file under a staging name, read that back and
 * compare it with what was read, and only then rename it into place and delete the old store. A
 * migration that fails at any step before the rename leaves the old store as it was, serves this
 * session from it, and is tried again at the next launch.
 *
 * An old store found beside the file (PIN-STORE review item 1) is one of two things, told apart by
 * the digest of the old store's entries that the migration records in the file ([KEY_MIGRATED_FROM]):
 *  - UNCHANGED since that migration (its digest is the recorded one), or a file that records no digest
 *    (written before the digest existed; its absence is no evidence): a delete that did not land. The
 *    file is the newer copy, since only the file was written since; the old store is deleted again
 *    and never served. Re-reading it instead would hand back the counters as they were at the
 *    migration, at every launch for as long as the delete keeps failing: free PIN attempts.
 *  - CHANGED (a recorded digest that does not match): only an older build writes the old store, so this
 *    is a downgrade, and what the user did there -- a new PIN, its own counters -- is the newer copy,
 *    as for [app.aroundtheblock.wallet.core.sync.DurableFile.LegacyKey]. It is migrated again over the
 *    file. Its counters are the ones that older build enforced for that PIN; the file's are for a
 *    PIN it no longer has. An old store that cannot be opened cannot be compared: the file serves and
 *    the old store is kept.
 *
 * The old store is never deleted before the rename that put the new file in place is on disk: the
 * directory is synced first. Otherwise a power cut could keep the delete and lose the rename, and
 * the next launch would find no PIN at all.
 */
internal object PinStoreMigration {
    const val FILE_NAME = "dgb_pin_store.bin"

    /** In the file: the SHA-256 of the old store's entries it was migrated from (see the class doc). */
    const val KEY_MIGRATED_FROM = "pin_store_migrated_from"
    private const val STAGING = ".migrating"
    private const val TAG = "PinStore"

    fun open(target: File, cipher: PinStoreCipher, legacy: LegacyPinStore, dirSync: DirSync = DirSync.OS): PinStore {
        File(target.parentFile, target.name + STAGING).delete() // a migration stopped before its rename
        File(target.parentFile, target.name + STAGING + ".tmp").delete() // ... or before its staging write's rename
        if (target.exists()) {
            val store = FilePinStore.open(target, cipher, dirSync, legacy) // throws: unavailable, never "no PIN"
            return if (legacy.exists()) besideOld(store, target, cipher, legacy, dirSync) else store
        }
        if (!legacy.exists()) return FilePinStore.open(target, cipher, dirSync, legacy)
        val old = legacy.open() // throws as it always could: unavailable
        return migrateOrServeOld(old, target, cipher, legacy, dirSync)
    }

    /** The file is in place and the old store is beside it: which one is newer (see the class doc). */
    private fun besideOld(store: FilePinStore, target: File, cipher: PinStoreCipher, legacy: LegacyPinStore, dirSync: DirSync): PinStore {
        val old = try {
            legacy.open()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "PIN-STORE: an old store beside the file cannot be opened (${t.javaClass.simpleName}); the file serves, the old store is kept")
            return store
        }
        val unchanged = try {
            // No recorded digest: a file from a build before the digest. That is no evidence the old
            // store is newer, so it is read as a delete that did not land, as that build did.
            store.getString(KEY_MIGRATED_FROM)?.let { it == digestOf(snapshotOf(old)) } ?: true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "PIN-STORE: an old store beside the file cannot be read (${t.javaClass.simpleName}); the file serves, the old store is kept")
            return store
        }
        if (unchanged) {
            dirSync.sync(target.parentFile!!) // the file in place is durable before the old store goes
            deleteLegacy(legacy)
            return store
        }
        android.util.Log.w(TAG, "PIN-STORE: the old store changed after the migration (an older build ran); it is the newer copy and is migrated again")
        return migrateOrServeOld(old, target, cipher, legacy, dirSync)
    }

    private fun migrateOrServeOld(old: LegacyPinStore.Opened, target: File, cipher: PinStoreCipher, legacy: LegacyPinStore, dirSync: DirSync): PinStore {
        val migrated = try {
            migrate(old, target, cipher, dirSync)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "PIN-STORE: migration not done (${t.javaClass.simpleName}); the old store serves this session")
            File(target.parentFile, target.name + STAGING).delete()
            File(target.parentFile, target.name + STAGING + ".tmp").delete()
            return old
        }
        // In place, verified and durable: from here the new file is the store, whatever the delete does.
        deleteLegacy(legacy)
        return FilePinStore.over(target, cipher, migrated, dirSync, legacy)
    }

    private fun snapshotOf(old: LegacyPinStore.Opened): Map<String, Any> {
        val snapshot = LinkedHashMap<String, Any>()
        for ((key, value) in old.all()) {
            if (value != null) snapshot[key] = value
        }
        return snapshot
    }

    /** SHA-256 (hex) of [entries] in the codec's sorted encoding; throws on a type the codec refuses. */
    internal fun digestOf(entries: Map<String, Any>): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(PinStoreCodec.encode(entries)).joinToString("") { "%02x".format(it) }

    /** Copies [old] into [target], verified by reading it back. Throws, with [target] untouched, on any failure. */
    private fun migrate(old: LegacyPinStore.Opened, target: File, cipher: PinStoreCipher, dirSync: DirSync): Map<String, Any> {
        val snapshot = snapshotOf(old)
        val written = LinkedHashMap(snapshot).apply { put(KEY_MIGRATED_FROM, digestOf(snapshot)) }
        val staging = File(target.parentFile, target.name + STAGING)
        writeWhole(staging, FilePinStore.seal(cipher, written), tmpSuffix = ".tmp", dirSync = dirSync)
        val readBack = FilePinStore.read(staging, cipher)
        if (readBack != written) throw IOException("the new store did not read back as the old one")
        if (!staging.renameTo(target)) throw IOException("rename did not land")
        dirSync.sync(target.parentFile!!) // before the caller deletes the old store
        return readBack
    }

    private fun deleteLegacy(legacy: LegacyPinStore) {
        val gone = try { legacy.delete() } catch (t: Throwable) { false }
        if (!gone) android.util.Log.w(TAG, "PIN-STORE: old store not deleted; tried again at the next launch")
    }

    /** The production opener: the file in no-backup storage, the Keystore cipher, the old prefs. */
    fun open(context: Context): PinStore = open(
        File(context.noBackupFilesDir, FILE_NAME),
        KeystorePinStoreCipher(),
        LegacyEncryptedPrefsPinStore(context),
    )
}
