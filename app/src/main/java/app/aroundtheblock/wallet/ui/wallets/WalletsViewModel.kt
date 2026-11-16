package app.aroundtheblock.wallet.ui.wallets

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aroundtheblock.wallet.AppRestart
import app.aroundtheblock.wallet.FreshStartAfterWipe
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.WalletSwitcher
import app.aroundtheblock.wallet.core.WipeReport
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.RemoveResult
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletRemoval
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.ui.components.showWipeIncompleteNotice
import app.aroundtheblock.wallet.ui.locale.LocaleController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One wallet as the switcher lists it. Balances are the last ones that wallet stored, or null. */
data class WalletRow(
    val id: String,
    val shownName: String,
    val active: Boolean,
    val lastBalanceSat: Long?,
    val lastDdCents: Long?,
    /** Legacy: one seed on both networks, so removing it removes it from both. */
    val alsoTestnet: Boolean,
)

/** A wallet's last stored balances. Both null while that wallet has never finished a sync. */
data class LastKnown(val balanceSat: Long?, val ddCents: Long?)

/** The name a wallet is shown by: its own, or the localised default "Wallet <number>". */
internal fun shownName(entry: WalletEntry, defaultName: (Int) -> String): String =
    entry.name ?: defaultName(entry.number)

/**
 * The last balances [scope] stored, read from that wallet's own sync prefs only. Its seed and its
 * native wallet are never loaded. A wallet that never finished a sync shows nothing rather than
 * a zero it never measured.
 */
internal fun readLastKnown(context: Context, scope: WalletScope): LastKnown {
    val prefs = context.getSharedPreferences(WalletStores.prefsName("dgb_sync_data", scope), Context.MODE_PRIVATE)
    if (!prefs.getBoolean("has_synced", false)) return LastKnown(null, null)
    return LastKnown(
        balanceSat = if (prefs.contains("last_balance")) prefs.getLong("last_balance", 0L) else null,
        ddCents = if (prefs.contains("last_dd_balance")) prefs.getLong("last_dd_balance", 0L) else null,
    )
}

/**
 * The wallet switcher and the Manage screen: the list of this network's wallets, switching,
 * renaming, reordering and removing. A switch and the removal of the running wallet restart the
 * app; removing the last wallet is the full wipe and hands over as every wipe does.
 */
@HiltViewModel
class WalletsViewModel internal constructor(
    private val registry: WalletRegistry,
    private val switcher: WalletSwitcher,
    private val removal: WalletRemoval,
    private val testnet: Boolean,
    private val lastKnown: (WalletScope) -> LastKnown,
    private val defaultName: (Int) -> String,
    /** The wallet THIS process runs (an add can already have made another one the registry's active id). */
    private val runningId: String,
    private val restart: () -> Unit,
    /** [FreshStartAfterWipe.afterWipe]; true when the wipe left the seed and the caller reports it. */
    private val afterWipe: (WipeReport) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    @Inject constructor(
        application: Application,
        registry: WalletRegistry,
        switcher: WalletSwitcher,
        removal: WalletRemoval,
    ) : this(
        registry = registry,
        switcher = switcher,
        removal = removal,
        testnet = ActiveWallet.scope(application).testnet,
        lastKnown = { scope -> readLastKnown(application, scope) },
        defaultName = { n -> LocaleController.wrap(application).getString(R.string.wallet_default_name, n) },
        runningId = ActiveWallet.scope(application).walletId,
        restart = { AppRestart.restart(application) },
        afterWipe = { report ->
            FreshStartAfterWipe.afterWipe(application, report).also { incomplete ->
                if (incomplete) showWipeIncompleteNotice(application)
            }
        },
    )

    private val _rows = MutableStateFlow<List<WalletRow>>(emptyList())
    val rows: StateFlow<List<WalletRow>> = _rows.asStateFlow()

    private val roomOnNetwork = MutableStateFlow(false)

    private val _onlyWalletId = MutableStateFlow<String?>(null)

    /** The one wallet on the device, on either network, when there is only one: removing it is the full wipe. */
    val onlyWalletId: StateFlow<String?> = _onlyWalletId.asStateFlow()

    /** Room for one more wallet on this network ([WalletRegistry.canReserve] counts PENDING, F31), and no removal running. */
    val canAdd: StateFlow<Boolean> = combine(roomOnNetwork, removalRunning) { room, removing -> room && !removing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A removal is running in this process; the screens hold their back and Add while it does. */
    val removing: StateFlow<Boolean> = removalRunning.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(io) { read() }
            _rows.value = snapshot.rows
            roomOnNetwork.value = snapshot.room
            _onlyWalletId.value = snapshot.only
        }
    }

    private class Snapshot(val rows: List<WalletRow>, val room: Boolean, val only: String?)

    private fun read(): Snapshot {
        val rows = registry.list(testnet).map { e ->
            val known = try {
                lastKnown(WalletScope(e.id, e.testnet ?: testnet))
            } catch (t: Throwable) {
                // The class only: no wallet id, no balance.
                runCatching { Log.w(TAG, "last-known balance unreadable (${t.javaClass.simpleName})") }
                LastKnown(null, null)
            }
            WalletRow(
                id = e.id,
                shownName = shownName(e, defaultName),
                active = e.id == runningId,
                lastBalanceSat = known.balanceSat,
                lastDdCents = known.ddCents,
                alsoTestnet = e.testnet == null,
            )
        }
        val everyId = (registry.list(false) + registry.list(true)).map { it.id }.distinct()
        return Snapshot(rows, registry.canReserve(testnet), everyId.singleOrNull())
    }

    fun switchTo(id: String): WalletSwitcher.Result = switcher.switchTo(id)

    /** False when the name is empty, too long or another wallet's shown name, its default included (the registry decides). */
    fun rename(id: String, name: String): Boolean = registry.rename(id, name, defaultName).also { if (it) refresh() }

    /** Moves [id] one place: -1 up, +1 down. False at either end. */
    fun move(id: String, delta: Int): Boolean {
        val ids = registry.list(testnet).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices || delta == 0) return false
        ids.add(to, ids.removeAt(from))
        return registry.reorder(testnet, ids).also { if (it) refresh() }
    }

    /**
     * Removes [id] once [typedName] equals its shown name. Runs to its end even if the screen goes
     * away: the removal, and the restart or wipe hand-over it calls for, are not left half done.
     * Never run beside an add (WalletRemoval): Add is hidden while this runs.
     */
    suspend fun remove(id: String, typedName: String): RemoveResult = withContext(NonCancellable) {
        val entry = withContext(io) { registry.list(testnet).firstOrNull { it.id == id } }
            ?: return@withContext RemoveResult.NotRemoved
        if (!removalRunning.compareAndSet(expect = false, update = true)) return@withContext RemoveResult.NotRemoved
        try {
            val result = removal.remove(id, typedName, shownName(entry, defaultName))
            when (result) {
                RemoveResult.RemovedRestartNeeded -> restart()
                is RemoveResult.WipedEverything -> withContext(io) { afterWipe(result.report) }
                else -> Unit
            }
            result
        } finally {
            removalRunning.value = false
            refresh()
        }
    }

    private companion object {
        /** Process-wide: the sheet and the Manage screen hold different instances. */
        val removalRunning = MutableStateFlow(false)
        const val TAG = "WalletsViewModel"
    }
}
