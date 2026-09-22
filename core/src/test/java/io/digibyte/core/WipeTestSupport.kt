package io.digibyte.core

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.security.PinStore
import io.mockk.every
import io.mockk.mockk
import java.io.File

/**
 * In-memory stand-ins for the wipe tests. Ported from the app module's stale-data test harness
 * (`InMemoryPrefsFactory` + a context over a scratch directory) — a port, not an import, because
 * `core` cannot see `app` test sources — and extended with the one thing a wipe test needs that
 * the original did not: a store whose `commit()` reports that the write did not land.
 */
internal class InMemoryPrefsFactory {
    private val stores = LinkedHashMap<String, InMemoryPrefs>()

    fun prefsFor(name: String): InMemoryPrefs = stores.getOrPut(name) { InMemoryPrefs() }

    /** Every store name that was ever opened or seeded. */
    fun names(): Set<String> = stores.keys

    /** From now on `commit()` on [name] reports failure. */
    fun refuseWritesTo(name: String) { prefsFor(name).refuseWrites = true }
}

/**
 * A `SharedPreferences` over a map. With [refuseWrites] set it behaves as the platform does when
 * the file write does not land: the in-memory view already shows the edit, the durable copy
 * ([durable]) does not, and `commit()` returns false. A reader that trusts the in-memory view
 * alone is therefore told "cleared" about data a restart would bring back.
 */
internal class InMemoryPrefs : SharedPreferences {
    private val memory = LinkedHashMap<String, Any?>()
    val durable = LinkedHashMap<String, Any?>()
    var refuseWrites = false

    fun seed(key: String, value: Any?) { memory[key] = value; durable[key] = value }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)
    override fun getString(key: String?, defValue: String?) = memory[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        memory[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = memory[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = memory[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = memory[key] as? Float ?: defValue
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
            write(memory)
            if (refuseWrites) return false
            write(durable)
            return true
        }
        override fun apply() { commit() }
        private fun write(target: MutableMap<String, Any?>) {
            if (clearAll) target.clear()
            removes.forEach(target::remove)
            target.putAll(puts)
        }
    }
}

/**
 * A context whose preferences come from [prefs] and whose files and databases live under
 * [root] — real file I/O against a scratch directory, no device.
 */
internal fun wipeTestContext(prefs: InMemoryPrefsFactory, root: File): Context {
    val filesDir = File(root, "files").apply { mkdirs() }
    val dbDir = File(root, "databases").apply { mkdirs() }
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.getSharedPreferences(any(), any()) } answers { prefs.prefsFor(firstArg()) }
    every { ctx.filesDir } returns filesDir
    every { ctx.getDatabasePath(any()) } answers { File(dbDir, firstArg<String>()) }
    every { ctx.applicationContext } returns ctx
    return ctx
}

/** A [PinStore] over a map that remembers the order of its writes. */
internal class RecordingPinStore : PinStore {
    val map = LinkedHashMap<String, Any>()
    val writes = mutableListOf<String>()
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
    override fun getString(key: String) = map[key] as? String
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun contains(key: String) = map.containsKey(key)
    override fun putInt(key: String, value: Int) { map[key] = value; writes += "put:$key" }
    override fun putLong(key: String, value: Long) { map[key] = value; writes += "put:$key" }
    override fun putString(key: String, value: String) { map[key] = value; writes += "put:$key" }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value; writes += "put:$key" }
    override fun remove(key: String) { map.remove(key); writes += "remove:$key" }
    override fun clear() { map.clear(); writes += "clear" }
}
