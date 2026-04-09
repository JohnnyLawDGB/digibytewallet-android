package io.digibyte.core.tor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class TorState {
    data object Disabled : TorState()
    data object Starting : TorState()
    data object Connecting : TorState()
    data class Connected(val socksPort: Int) : TorState()
    data class Failed(val reason: String) : TorState()
}

/**
 * Manages Tor lifecycle.
 *
 * Kotlin 2.2 upgrade complete — tor-android dependency can now be enabled.
 * Uncomment the dependency in core/build.gradle.kts and implement start().
 */
class TorManager(private val context: Context) {
    private val _state = MutableStateFlow<TorState>(TorState.Disabled)
    val state: StateFlow<TorState> = _state.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow(0)
    val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

    private val prefs = context.getSharedPreferences("dgb_tor", Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean("tor_enabled", false)
        set(value) { prefs.edit().putBoolean("tor_enabled", value).apply() }

    var upgradePromptShown: Boolean
        get() = prefs.getBoolean("upgrade_prompt_shown", false)
        set(value) { prefs.edit().putBoolean("upgrade_prompt_shown", value).apply() }

    suspend fun start(): TorState = withContext(Dispatchers.IO) {
        if (_state.value is TorState.Connected) return@withContext _state.value

        _state.value = TorState.Starting
        _bootstrapProgress.value = 0

        // TODO: Enable tor-android dependency and implement real Tor startup
        // The Kotlin 2.2 upgrade is complete — tor-android:0.4.9.5.1 now compiles.
        // Next step: uncomment dependency, bind to TorService, get SOCKS port.
        _state.value = TorState.Failed("Tor integration in progress — wallet functions normally without it")
        Log.i("TorManager", "Tor not yet integrated — Kotlin 2.2 prerequisite complete")
        _state.value
    }

    fun stop() {
        _bootstrapProgress.value = 0
        _state.value = TorState.Disabled
    }

    fun isRunning(): Boolean = _state.value is TorState.Connected

    fun getSocksPort(): Int? = (_state.value as? TorState.Connected)?.socksPort
}
