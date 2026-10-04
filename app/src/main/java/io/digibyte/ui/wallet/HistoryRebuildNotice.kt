package io.digibyte.ui.wallet

import androidx.annotation.StringRes
import io.digibyte.R
import io.digibyte.core.sync.HistoryRebuildOnUpgrade
import io.digibyte.core.sync.HistoryRebuildOnUpgrade.OutcomeKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the wallet screen says about the one-time history rebuild ([HistoryRebuildOnUpgrade]):
 * a title, or none, and a body.
 */
data class HistoryRebuildNotice(
    val kind: OutcomeKind,
    @StringRes val title: Int?,
    @StringRes val body: Int,
) {
    companion object {
        /**
         * The notice for the rebuild's [outcome] in this process, or null: nothing before the first
         * evaluation, nothing when no rebuild was needed, and nothing for a kind the user has
         * already dismissed in this process ([dismissed]).
         */
        fun of(outcome: HistoryRebuildOnUpgrade.Outcome?, dismissed: Set<OutcomeKind>): HistoryRebuildNotice? {
            val kind = outcome?.kind ?: return null
            if (kind in dismissed) return null
            return when (kind) {
                OutcomeKind.RAN -> HistoryRebuildNotice(kind, R.string.history_rebuild_notice_title, R.string.history_rebuild_notice_body)
                OutcomeKind.DEFERRED -> HistoryRebuildNotice(kind, null, R.string.history_rebuild_deferred)
                OutcomeKind.NOT_NEEDED -> null
            }
        }
    }
}

/**
 * The rebuild notices dismissed in this process, by outcome kind. Kept outside the screen so a
 * dismissed notice does not return when the wallet screen is entered again; a new process starts
 * with none, which matches the rebuild's own outcome, evaluated once per process.
 */
object HistoryRebuildNoticeDismissals {
    private val _dismissed = MutableStateFlow<Set<OutcomeKind>>(emptySet())
    val dismissed: StateFlow<Set<OutcomeKind>> = _dismissed.asStateFlow()

    fun dismiss(kind: OutcomeKind) {
        _dismissed.update { it + kind }
    }

    /** Test seam: forget this process's dismissals. */
    internal fun resetForTest() {
        _dismissed.value = emptySet()
    }
}
