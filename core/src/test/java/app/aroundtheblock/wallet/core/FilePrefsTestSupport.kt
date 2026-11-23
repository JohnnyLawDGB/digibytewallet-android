package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Preferences files on a real directory, with the platform's file behaviour where a wipe cares
 * about it — what `InMemoryPrefsFactory` cannot show, since it has no files to list:
 *
 *  - `getSharedPreferences(name)` hands out ONE cached instance per name, loaded from
 *    `<root>/shared_prefs/<name>.xml`; opening a name creates no file.
 *  - `commit()` writes the file whenever it is missing, even when nothing changed — a commit to a
 *    store whose file is gone creates an (empty) file (`SharedPreferencesImpl.writeToFile`).
 *  - `deleteSharedPreferences(name)` evicts the cached instance and deletes the file and its
 *    backup; an instance a caller still holds keeps writing to that path, and recreates the file.
 */
internal class FilePrefsFactory(private val dir: File) {
    private val cache = HashMap<String, FilePrefs>()

    fun prefsFor(name: String): FilePrefs = synchronized(cache) {
        cache.getOrPut(name) { FilePrefs(File(dir, "$name.xml")) }
    }

    fun delete(name: String): Boolean = synchronized(cache) {
        cache.remove(name)
        val file = File(dir, "$name.xml")
        file.delete()
        File(dir, "$name.xml.bak").delete()
        !file.exists()
    }

    /** Every file in the preferences directory now, sorted. */
    fun files(): List<String> = dir.list()?.sorted().orEmpty()
}

internal class FilePrefs(private val file: File) : SharedPreferences {
    private val memory = LinkedHashMap<String, Any?>().apply { putAll(read()) }

    private fun read(): Map<String, Any?> {
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText())
        return json.keys().asSequence().associateWith { key ->
            when (val v = json.get(key)) {
                is JSONArray -> (0 until v.length()).map { v.getString(it) }.toMutableSet()
                else -> v
            }
        }
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        memory.forEach { (k, v) -> json.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) }
        file.writeText(json.toString())
    }

    /**
     * While set, a `commit()` does what the platform's does when the file write fails: this
     * process's view takes the edit, the file is left as it was (or absent), and the answer is false.
     */
    var refuseWrites = false

    /** As [refuseWrites], for this many commits from now and then no more. */
    var refuseNextWrites = 0

    /** Puts a value in memory and on disk, as an earlier session would have left it. */
    fun seed(key: String, value: Any?) { memory[key] = value; write() }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)
    override fun getString(key: String?, defValue: String?) = memory[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        memory[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = (memory[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String?, defValue: Long) = (memory[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String?, defValue: Float) = (memory[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = memory[key] as? Boolean ?: defValue
    override fun contains(key: String?) = memory.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = LinkedHashSet<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            val before = LinkedHashMap(memory)
            if (clearAll) memory.clear()
            removes.forEach(memory::remove)
            memory.putAll(puts)
            if (refuseWrites) return false
            if (refuseNextWrites > 0) { refuseNextWrites--; return false }
            // The platform writes when the file is missing, changed or not.
            if (!file.exists() || memory != before) write()
            return true
        }
        override fun apply() { commit() }
    }
}

/**
 * A context over [root] as an app's data directory: preferences from [prefs] (files under
 * `shared_prefs`), files and databases under their own directories.
 */
internal fun filePrefsContext(root: File): Pair<Context, FilePrefsFactory> {
    val prefs = FilePrefsFactory(File(root, "shared_prefs"))
    val filesDir = File(root, "files").apply { mkdirs() }
    val dbDir = File(root, "databases").apply { mkdirs() }
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.getSharedPreferences(any(), any()) } answers { prefs.prefsFor(firstArg()) }
    every { ctx.deleteSharedPreferences(any()) } answers { prefs.delete(firstArg()) }
    every { ctx.filesDir } returns filesDir
    every { ctx.dataDir } returns root
    every { ctx.getDatabasePath(any()) } answers { File(dbDir, firstArg<String>()) }
    every { ctx.applicationContext } returns ctx
    return ctx to prefs
}
