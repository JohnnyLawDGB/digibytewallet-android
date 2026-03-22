package io.digibyte.core.tor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TorState {
    data object Disabled : TorState()
    data object Starting : TorState()
    data object Connecting : TorState()
    data class Connected(val socksPort: Int) : TorState()
    data class Failed(val reason: String) : TorState()
}

/**
 * Manages Tor lifecycle for privacy-by-default network routing.
 *
 * Phase 2 implementation: provides the TorManager interface and state management.
 * The actual tor-android library integration is complex and may need adjustment
 * based on the available library version.
 *
 * For now, this implements the state management, preference persistence,
 * and JNI proxy wiring. The Tor binary embedding will be finalized during
 * integration testing when we verify the tor-android artifact availability.
 */
class TorManager(private val context: Context) {
    private val _state = MutableStateFlow<TorState>(TorState.Disabled)
    val state: StateFlow<TorState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("dgb_tor", Context.MODE_PRIVATE)

    /**
     * Whether Tor is enabled in user preferences.
     * Default: true for new installs, false for upgrades (set by app on first Phase 2 launch).
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean("tor_enabled", false)
        set(value) { prefs.edit().putBoolean("tor_enabled", value).apply() }

    var upgradePromptShown: Boolean
        get() = prefs.getBoolean("upgrade_prompt_shown", false)
        set(value) { prefs.edit().putBoolean("upgrade_prompt_shown", value).apply() }

    /**
     * Start Tor. Returns the final state (Connected or Failed).
     */
    suspend fun start(): TorState {
        if (_state.value is TorState.Connected) return _state.value

        _state.value = TorState.Starting

        // TODO: Integrate actual tor-android library here.
        // The tor-android artifact from Guardian Project may need:
        // - Checking current artifact coordinates on Maven Central/JitPack
        // - Embedding the Tor binary via the library
        // - Starting the Tor process
        // - Waiting for SOCKS5 proxy to be ready
        // - Returning the port number
        //
        // For now, return Failed to gracefully degrade.
        // The wallet works fine without Tor — it's an enhancement.

        _state.value = TorState.Failed("Tor integration pending — wallet functions normally without it")
        return _state.value
    }

    /**
     * Stop Tor.
     */
    fun stop() {
        // TODO: stop Tor process
        _state.value = TorState.Disabled
    }

    fun isRunning(): Boolean = _state.value is TorState.Connected

    fun getSocksPort(): Int? = (_state.value as? TorState.Connected)?.socksPort
}
