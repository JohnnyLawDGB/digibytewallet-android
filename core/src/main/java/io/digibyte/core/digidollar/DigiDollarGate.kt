package io.digibyte.core.digidollar

/**
 * Runtime feature gate (issue #5): the DigiDollar UI is visible only when
 * the softfork last reported ACTIVE on the current network, with the result
 * cached through [Store] so brief API outages don't flap the UI. A cached
 * ACTIVE older than [LAST_KNOWN_TTL_MS] without fresh confirmation closes
 * the gate again — fail-closed in every ambiguous state.
 *
 * Production [Store] wraps the network-suffixed settings SharedPreferences;
 * tests use an in-memory map.
 */
class DigiDollarGate(private val store: Store) {

    interface Store {
        fun get(key: String): Long?
        fun put(key: String, value: Long)
    }

    companion object {
        /** How long a last-known ACTIVE keeps the gate open with no fresh
         *  fetch. A softfork cannot deactivate, so a week only bounds how
         *  long a wrong cached positive can outlive an API rollback. */
        const val LAST_KNOWN_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val KEY_STATUS = "dd_gate_status"
        private const val KEY_AT = "dd_gate_at"
        private const val STATUS_ACTIVE = 1L
    }

    /** Record a successful status fetch. Outages simply don't record. */
    fun record(deployment: DigiDollarDeployment, nowMs: Long) {
        store.put(KEY_STATUS, if (deployment == DigiDollarDeployment.ACTIVE) STATUS_ACTIVE else 0L)
        store.put(KEY_AT, nowMs)
    }

    fun isOpen(nowMs: Long): Boolean {
        if (store.get(KEY_STATUS) != STATUS_ACTIVE) return false
        val at = store.get(KEY_AT) ?: return false
        return nowMs - at <= LAST_KNOWN_TTL_MS
    }
}
