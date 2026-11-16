package app.aroundtheblock.wallet.core.wallets

import android.content.SharedPreferences
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

enum class WalletOrigin { CREATED, RESTORED }

enum class EntryState { READY, PENDING, REMOVING }

data class WalletEntry(
    val id: String,
    /** null = the localised default "Wallet <number>". */
    val name: String?,
    val number: Int,
    /** null only for legacy, whose one seed serves both networks. */
    val testnet: Boolean?,
    /** Unix seconds; 0 for legacy. */
    val createdAt: Long,
    val origin: WalletOrigin,
    val order: Int,
    val state: EntryState,
)

/**
 * The list of wallets and the active one per network. The whole registry is one JSON string under
 * one key, so every change is a single atomic `commit()`. Keeps no state between calls: every call
 * reads and parses [prefs] afresh, and reading never writes.
 */
class WalletRegistry(
    private val prefs: SharedPreferences,
    private val recordExists: (WalletScope) -> Boolean,
    private val recordsOnDisk: () -> List<Pair<String, Boolean?>>,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The parsed registry. Legacy is held as fields because it has no array entry. */
    private class Doc(
        val entries: MutableList<WalletEntry> = mutableListOf(),
        var legacyName: String? = null,
        var legacyOrder: Int = 0,
        var legacyState: EntryState = EntryState.READY,
        var activeMainnet: String? = null,
        var activeTestnet: String? = null,
    ) {
        fun active(testnet: Boolean) = if (testnet) activeTestnet else activeMainnet
        fun setActive(testnet: Boolean, id: String?) {
            if (testnet) activeTestnet = id else activeMainnet = id
        }
    }

    fun list(testnet: Boolean): List<WalletEntry> = readyOn(testnet, load())

    fun incomplete(): List<WalletEntry> {
        val doc = load()
        val all = doc.entries.filter { it.state != EntryState.READY } +
            listOfNotNull(legacyEntry(doc).takeIf { it.state == EntryState.REMOVING })
        return all
    }

    fun find(id: String): WalletEntry? {
        val doc = load()
        if (id == WalletIds.LEGACY) {
            return legacyEntry(doc).takeIf { it.state == EntryState.REMOVING || legacyRecordExists() }
        }
        return doc.entries.firstOrNull { it.id == id }
    }

    fun activeId(testnet: Boolean): String = activeId(testnet, load())

    fun setActive(testnet: Boolean, id: String): Boolean {
        val doc = load()
        if (readyOn(testnet, doc).none { it.id == id }) return false
        doc.setActive(testnet, id)
        return save(doc)
    }

    fun canReserve(testnet: Boolean): Boolean = canReserve(testnet, load())

    fun reserve(testnet: Boolean, origin: WalletOrigin, nowSecs: Long): WalletEntry? {
        val doc = load()
        if (!canReserve(testnet, doc)) return null
        var id = WalletIds.newId(random)
        while (id == WalletIds.LEGACY || doc.entries.any { it.id == id }) id = WalletIds.newId(random)
        val used = usedNumbers(testnet, doc)
        val number = generateSequence(1) { it + 1 }.first { it !in used }
        val order = (occupied(testnet, doc).maxOfOrNull { it.order } ?: -1) + 1
        val entry = WalletEntry(id, null, number, testnet, nowSecs, origin, order, EntryState.PENDING)
        doc.entries += entry
        return entry.takeIf { save(doc) }
    }

    fun complete(id: String, makeActive: Boolean): Boolean {
        val doc = load()
        val i = doc.entries.indexOfFirst { it.id == id }
        if (i < 0 || doc.entries[i].state != EntryState.PENDING) return false
        val entry = doc.entries[i].copy(state = EntryState.READY)
        doc.entries[i] = entry
        if (makeActive) doc.setActive(entry.testnet == true, id)
        return save(doc)
    }

    fun markRemoving(id: String): Boolean {
        val doc = load()
        // Which networks name this wallet as active is read before the state changes.
        val activeOn = listOf(false, true).filter { activeId(it, doc) == id }
        if (id == WalletIds.LEGACY) {
            if (find(id) == null) return false
            doc.legacyState = EntryState.REMOVING
        } else {
            val i = doc.entries.indexOfFirst { it.id == id }
            if (i < 0) return false
            doc.entries[i] = doc.entries[i].copy(state = EntryState.REMOVING)
        }
        for (net in activeOn) doc.setActive(net, readyOn(net, doc).firstOrNull { it.id != id }?.id)
        return save(doc)
    }

    fun anyRecordExists(): Boolean = legacyRecordExists() || recordsOnDisk().isNotEmpty()

    fun drop(id: String): Boolean {
        val doc = load()
        if (id == WalletIds.LEGACY) {
            // Legacy has no array entry; its record going away is what unlists it.
            doc.legacyState = EntryState.READY
            doc.legacyName = null
        } else if (!doc.entries.removeAll { it.id == id }) {
            return false
        }
        for (net in listOf(false, true)) if (doc.active(net) == id) doc.setActive(net, null)
        return save(doc)
    }

    fun rename(id: String, name: String): Boolean {
        val doc = load()
        val target = if (id == WalletIds.LEGACY) legacyEntry(doc) else doc.entries.firstOrNull { it.id == id }
        if (target == null) return false
        val others = (listOf(legacyEntry(doc)) + doc.entries)
            .filter { it.id != id && (target.testnet == null || it.testnet == null || it.testnet == target.testnet) }
            .mapNotNull { it.name }
        if (!validName(name, others)) return false
        val trimmed = name.trim()
        if (id == WalletIds.LEGACY) {
            doc.legacyName = trimmed
        } else {
            doc.entries[doc.entries.indexOf(target)] = target.copy(name = trimmed)
        }
        return save(doc)
    }

    fun reorder(testnet: Boolean, ids: List<String>): Boolean {
        val doc = load()
        val current = readyOn(testnet, doc).map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) return false
        ids.forEachIndexed { pos, id ->
            if (id == WalletIds.LEGACY) {
                doc.legacyOrder = pos
            } else {
                val i = doc.entries.indexOfFirst { it.id == id }
                doc.entries[i] = doc.entries[i].copy(order = pos)
            }
        }
        return save(doc)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun legacyRecordExists(): Boolean = recordExists(WalletScope.legacy(false))

    private fun legacyEntry(doc: Doc) = WalletEntry(
        WalletIds.LEGACY, doc.legacyName, 1, null, 0L, WalletOrigin.CREATED, doc.legacyOrder, doc.legacyState,
    )

    /** READY wallets of [testnet] by order; legacy only while its record exists. */
    private fun readyOn(testnet: Boolean, doc: Doc): List<WalletEntry> {
        val legacy = legacyEntry(doc).takeIf { it.state == EntryState.READY && legacyRecordExists() }
        return (listOfNotNull(legacy) + doc.entries.filter { it.testnet == testnet && it.state == EntryState.READY })
            .sortedBy { it.order }
    }

    /** Entries that hold a slot on [testnet]: READY and PENDING (legacy while its record exists). */
    private fun occupied(testnet: Boolean, doc: Doc): List<WalletEntry> =
        readyOn(testnet, doc) + doc.entries.filter { it.testnet == testnet && it.state == EntryState.PENDING }

    private fun canReserve(testnet: Boolean, doc: Doc) = occupied(testnet, doc).size < MAX_PER_NETWORK

    private fun usedNumbers(testnet: Boolean, doc: Doc): Set<Int> {
        val legacy = legacyEntry(doc).takeIf { it.state == EntryState.REMOVING || legacyRecordExists() }
        return (listOfNotNull(legacy) + doc.entries.filter { it.testnet == testnet }).map { it.number }.toSet()
    }

    private fun activeId(testnet: Boolean, doc: Doc): String {
        val ready = readyOn(testnet, doc)
        val stored = doc.active(testnet)
        if (stored != null && ready.any { it.id == stored } && recordExists(WalletScope(stored, testnet))) return stored
        return ready.firstOrNull { recordExists(WalletScope(it.id, testnet)) }?.id ?: WalletIds.LEGACY
    }

    // -- storage ------------------------------------------------------------------------------

    private fun load(): Doc {
        val raw = prefs.getString(KEY, null) ?: return Doc()
        return try {
            parse(raw)
        } catch (_: Exception) {
            rebuild()
        }
    }

    /** A registry that cannot be read is rebuilt, in memory, from the seed records on disk. */
    private fun rebuild(): Doc {
        val doc = Doc()
        val legacyHolds = legacyRecordExists()
        var order = 1
        for ((id, testnet) in recordsOnDisk().distinctBy { it.first }) {
            if (!WalletIds.isValid(id) || id == WalletIds.LEGACY) continue
            // A record that does not name its network goes on mainnet, the only network a user
            // can reach without the developer toggle.
            val net = testnet ?: false
            val used = doc.entries.filter { it.testnet == net }.map { it.number }.toSet() +
                (if (legacyHolds) setOf(1) else emptySet())
            val number = generateSequence(1) { it + 1 }.first { it !in used }
            doc.entries += WalletEntry(id, null, number, net, 0L, WalletOrigin.RESTORED, order++, EntryState.READY)
        }
        return doc
    }

    private fun parse(raw: String): Doc {
        val root = JSONObject(raw)
        require(root.getInt("v") == VERSION)
        val doc = Doc()
        val arr = root.getJSONArray("entries")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            require(WalletIds.isValid(id) && id != WalletIds.LEGACY)
            doc.entries += WalletEntry(
                id = id,
                name = if (o.isNull("name")) null else o.getString("name"),
                number = o.getInt("number"),
                testnet = o.getBoolean("testnet"),
                createdAt = o.getLong("createdAt"),
                origin = WalletOrigin.valueOf(o.getString("origin")),
                order = o.getInt("order"),
                state = EntryState.valueOf(o.getString("state")),
            )
        }
        val legacy = root.getJSONObject("legacy")
        doc.legacyName = if (legacy.isNull("name")) null else legacy.optString("name", "").ifEmpty { null }
        doc.legacyOrder = legacy.getInt("order")
        // Absent means READY, so an upgrade's reads stay write-free.
        doc.legacyState = if (legacy.has("state")) EntryState.valueOf(legacy.getString("state")) else EntryState.READY
        val active = root.optJSONObject("active")
        doc.activeMainnet = active?.optString("mainnet", "")?.ifEmpty { null }
        doc.activeTestnet = active?.optString("testnet", "")?.ifEmpty { null }
        return doc
    }

    private fun serialize(doc: Doc): String {
        val arr = JSONArray()
        for (e in doc.entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name ?: JSONObject.NULL)
                    .put("number", e.number)
                    .put("testnet", e.testnet == true)
                    .put("createdAt", e.createdAt)
                    .put("origin", e.origin.name)
                    .put("order", e.order)
                    .put("state", e.state.name),
            )
        }
        val legacy = JSONObject()
            .put("name", doc.legacyName ?: JSONObject.NULL)
            .put("order", doc.legacyOrder)
        if (doc.legacyState != EntryState.READY) legacy.put("state", doc.legacyState.name)
        val active = JSONObject()
        doc.activeMainnet?.let { active.put("mainnet", it) }
        doc.activeTestnet?.let { active.put("testnet", it) }
        return JSONObject().put("v", VERSION).put("entries", arr).put("legacy", legacy).put("active", active).toString()
    }

    /** One `commit()`. A commit that does not land leaves the previous registry in place. */
    private fun save(doc: Doc): Boolean {
        val before = prefs.getString(KEY, null)
        if (prefs.edit().putString(KEY, serialize(doc)).commit()) return true
        // The platform's in-memory view already shows the edit; put the old value back so a
        // reader is not told about a change a restart would undo.
        val undo = prefs.edit()
        (if (before == null) undo.remove(KEY) else undo.putString(KEY, before)).commit()
        return false
    }

    companion object {
        const val PREFS = "dgb_wallets"
        const val MAX_PER_NETWORK = 8
        const val MAX_NAME_CHARS = 32
        private const val KEY = "registry"
        private const val VERSION = 1

        /** Trimmed 1..32 chars and unique ignoring case among [others]. */
        fun validName(name: String, others: List<String>): Boolean {
            val trimmed = name.trim()
            return trimmed.length in 1..MAX_NAME_CHARS && others.none { it.trim().equals(trimmed, ignoreCase = true) }
        }
    }
}
