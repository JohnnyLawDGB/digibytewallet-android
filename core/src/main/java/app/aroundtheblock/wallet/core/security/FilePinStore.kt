package app.aroundtheblock.wallet.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * PIN-STORE: the PIN hash, its salt and the rate-limit counters live in a file the app controls,
 * encrypted with AES-256-GCM under a dedicated Android Keystore key. They no longer depend on the
 * alpha EncryptedSharedPreferences, which is read once, to migrate, and then deleted.
 *
 *  - A file that is there but cannot be read (bad tag, truncated, wrong format, key gone, Keystore
 *    fault) THROWS at open. [PinManager.openStore] turns that into [UnavailablePinStore]: the
 *    PIN-store-unavailable screen, never "no PIN". The file is left exactly as it is.
 *  - No file at all is an empty store, as no `dgb_pin_store` prefs file was before.
 *  - Every write replaces the whole file at once (a temporary file, synced, then renamed over it): a
 *    process stopped there leaves the previous contents or the new ones, never a mix.
 */

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

/** Writes [bytes] to [target] in one step: a temporary file beside it, synced, then renamed over it. */
internal fun writeWhole(target: File, bytes: ByteArray, tmpSuffix: String = ".tmp") {
    val dir = target.parentFile ?: throw IOException("no directory")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the directory")
    val tmp = File(dir, target.name + tmpSuffix)
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw IOException("rename did not land")
    }
}

/**
 * The [PinStore] over the sealed file. It holds the decoded entries in memory and writes the whole
 * set on every change. Construct through [open], which throws on a file that is there and unreadable.
 *
 * A write that does not land is logged and the change is kept in memory, as a SharedPreferences
 * `commit()` that returned false did (the old store ignored that result): the session goes on with
 * the change, and the next write that lands carries it, since each write is the whole set.
 */
internal class FilePinStore private constructor(
    private val file: File,
    private val cipher: PinStoreCipher,
    initial: Map<String, Any>,
) : PinStore {

    private var values: Map<String, Any> = initial

    companion object {
        /** Opens [file]: its entries, or none when there is no file. THROWS when it is there and cannot be read. */
        fun open(file: File, cipher: PinStoreCipher): FilePinStore {
            File(file.parentFile, file.name + ".tmp").delete() // a write that stopped before its rename
            val values = if (file.exists()) read(file, cipher) else emptyMap()
            return FilePinStore(file, cipher, values)
        }

        /** A store over entries already written to and verified in [file] (the migration's). */
        internal fun over(file: File, cipher: PinStoreCipher, values: Map<String, Any>) = FilePinStore(file, cipher, values)

        fun read(file: File, cipher: PinStoreCipher): Map<String, Any> = PinStoreCodec.decode(cipher.open(file.readBytes()))

        fun seal(cipher: PinStoreCipher, values: Map<String, Any>): ByteArray = cipher.seal(PinStoreCodec.encode(values))
    }

    @Synchronized private fun change(block: MutableMap<String, Any>.() -> Unit) {
        val next = LinkedHashMap(values).apply(block)
        values = next
        try {
            writeWhole(file, seal(cipher, next))
        } catch (t: Throwable) {
            android.util.Log.e("PinStore", "PIN-STORE: write not landed (${t.javaClass.simpleName}); kept in memory")
        }
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
    override fun clear() = change { clear() }

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
 * session from it, and is tried again at the next launch. Once the file is in place it is the
 * store; an old store still beside it is a delete that did not land, and is deleted again.
 */
internal object PinStoreMigration {
    const val FILE_NAME = "dgb_pin_store.bin"
    private const val STAGING = ".migrating"
    private const val TAG = "PinStore"

    fun open(target: File, cipher: PinStoreCipher, legacy: LegacyPinStore): PinStore {
        File(target.parentFile, target.name + STAGING).delete() // a migration stopped before its rename
        if (target.exists()) {
            val store = FilePinStore.open(target, cipher) // throws: unavailable, never "no PIN"
            if (legacy.exists()) deleteLegacy(legacy)
            return store
        }
        if (!legacy.exists()) return FilePinStore.open(target, cipher)
        val old = legacy.open() // throws as it always could: unavailable
        val migrated = try {
            migrate(old, target, cipher)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "PIN-STORE: migration not done (${t.javaClass.simpleName}); the old store serves this session")
            File(target.parentFile, target.name + STAGING).delete()
            return old
        }
        // In place and verified: from here the new file is the store, whatever the delete does.
        deleteLegacy(legacy)
        return FilePinStore.over(target, cipher, migrated)
    }

    /** Copies [old] into [target], verified by reading it back. Throws, with [target] untouched, on any failure. */
    private fun migrate(old: LegacyPinStore.Opened, target: File, cipher: PinStoreCipher): Map<String, Any> {
        val snapshot = LinkedHashMap<String, Any>()
        for ((key, value) in old.all()) {
            if (value != null) snapshot[key] = value
        }
        val staging = File(target.parentFile, target.name + STAGING)
        writeWhole(staging, FilePinStore.seal(cipher, snapshot), tmpSuffix = ".tmp")
        val readBack = FilePinStore.read(staging, cipher)
        if (readBack != snapshot) throw IOException("the new store did not read back as the old one")
        if (!staging.renameTo(target)) throw IOException("rename did not land")
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
