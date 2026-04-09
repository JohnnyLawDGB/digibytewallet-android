package io.digibyte.core.tor

import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.matthewnelson.kmp.tor.runtime.TorState as KmpTorState

sealed class TorState {
    data object Disabled : TorState()
    data object Starting : TorState()
    data object Connecting : TorState()
    data class Connected(val socksPort: Int) : TorState()
    data class Failed(val reason: String) : TorState()
}

/**
 * Manages Tor lifecycle via kmp-tor exec mode.
 *
 * Tor runs as a separate process — if it crashes, the wallet continues
 * functioning normally. SyncService calls [start] before sync when
 * [isEnabled] is true, and falls back to direct connections on failure.
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

    // I2: Nullable backing field instead of `by lazy` so stop() can skip
    // initialization when start() was never called. Uses double-checked locking
    // (same guarantee as the default `lazy` delegate).
    private var _runtime: TorRuntime? = null
    private val runtimeLock = Any()
    private val runtime: TorRuntime
        get() = _runtime ?: synchronized(runtimeLock) {
            _runtime ?: buildRuntime().also { _runtime = it }
        }

    private fun buildRuntime(): TorRuntime {
        val env = TorRuntime.Environment.Builder(
            workDirectory = context.filesDir.resolve("tor"),
            cacheDirectory = context.cacheDir.resolve("tor"),
            loader = ResourceLoaderTorExec::getOrCreate
        )

        return TorRuntime.Builder(env) {
            val executor = OnEvent.Executor.Immediate

            // Map kmp-tor STATE events to our TorState + bootstrap progress
            observerStatic(RuntimeEvent.STATE, executor) { kmpState ->
                when (val daemon = kmpState.daemon) {
                    is KmpTorState.Daemon.On -> {
                        // bootstrap is a Byte (0–100), treat as unsigned
                        val progress = daemon.bootstrap.toInt() and 0xFF
                        _bootstrapProgress.value = progress
                        if (progress < 100 && _state.value !is TorState.Connected) {
                            _state.value = TorState.Connecting
                        }
                    }
                    is KmpTorState.Daemon.Starting -> {
                        _state.value = TorState.Starting
                    }
                    is KmpTorState.Daemon.Off -> {
                        // Only reset to Disabled if we didn't already set Failed
                        if (_state.value !is TorState.Failed) {
                            _state.value = TorState.Disabled
                            _bootstrapProgress.value = 0
                        }
                    }
                    is KmpTorState.Daemon.Stopping -> { /* transient — ignore */ }
                }
            }

            // Capture SOCKS port once Tor has listeners
            observerStatic(RuntimeEvent.LISTENERS, executor) { listeners ->
                // addr.port is a Port value type — .value extracts the Int
                val addr = listeners.socks.firstOrNull()
                if (addr != null) {
                    _state.value = TorState.Connected(addr.port.value)
                    _bootstrapProgress.value = 100
                    Log.i(TAG, "Tor connected — SOCKS5 on 127.0.0.1:${addr.port.value}")
                }
            }

            observerStatic(RuntimeEvent.ERROR, executor) { t ->
                // Log only — fatal errors also fire Daemon.Off, which the STATE observer handles.
                Log.e(TAG, "Tor runtime error", t)
            }

            // Non-persistent config: SocksPort=auto, SafeSocks=1
            // ConfigCallback.invoke is an extension on TorConfig.BuilderScope,
            // so 'this' inside the lambda is TorConfig.BuilderScope.
            // configure() is a MEMBER EXTENSION — call it as option.configure { ... }
            config {
                TorOption.__SocksPort.configure { auto() }
                TorOption.SafeSocks.configure(true)
            }

            required(TorEvent.ERR)
            required(TorEvent.WARN)
        }
    }

    /**
     * Start the Tor daemon and wait for a SOCKS port.
     * Returns [TorState.Connected] on success or [TorState.Failed] on error/timeout.
     * - If already connected, returns immediately.
     * - If already starting/connecting, awaits the in-progress bootstrap.
     */
    suspend fun start(): TorState = withContext(Dispatchers.IO) {
        val current = _state.value
        // Already connected — nothing to do.
        if (current is TorState.Connected) return@withContext current
        // Already in progress — wait for the existing bootstrap rather than
        // restarting (which would reset progress and race on startDaemonAsync).
        if (current is TorState.Starting || current is TorState.Connecting) {
            return@withContext _state.first {
                it is TorState.Connected ||
                it is TorState.Failed ||
                it is TorState.Disabled
            }.let { terminal ->
                if (terminal is TorState.Disabled)
                    TorState.Failed("Tor daemon stopped before reaching bootstrap 100%")
                else terminal
            }
        }

        _state.value = TorState.Starting
        _bootstrapProgress.value = 0

        try {
            // startDaemonAsync is a member extension on Action.Companion.
            // Bring the companion into scope so the extension resolves.
            with(Action.Companion) { runtime.startDaemonAsync() }

            // Wait up to 90s for a terminal state (Connected, Failed, or Disabled).
            // Disabled during startup means the daemon went down before reaching
            // bootstrap 100% — treat as failure and report faster than the 90s timeout.
            val result = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) {
                _state.first {
                    it is TorState.Connected ||
                    it is TorState.Failed ||
                    it is TorState.Disabled
                }
            }

            when {
                result == null -> {
                    _state.value = TorState.Failed("Bootstrap timed out (${BOOTSTRAP_TIMEOUT_MS / 1000}s)")
                    Log.w(TAG, "Tor bootstrap timed out")
                }
                result is TorState.Disabled -> {
                    _state.value = TorState.Failed("Tor daemon stopped before reaching bootstrap 100%")
                    Log.w(TAG, "Tor daemon went down during startup")
                }
            }

            _state.value
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"
            _state.value = TorState.Failed(reason)
            Log.e(TAG, "Failed to start Tor: $reason", e)
            _state.value
        }
    }

    /**
     * Stop the Tor daemon. Non-blocking — fires stop request and returns.
     * State resets to [TorState.Disabled] immediately.
     */
    fun stop() {
        _bootstrapProgress.value = 0
        _state.value = TorState.Disabled
        // I2: Guard — only enqueue stop if the runtime was ever initialized.
        // Avoids triggering Tor binary extraction for a daemon that never started.
        _runtime?.enqueue(
            Action.StopDaemon,
            OnFailure { Log.w(TAG, "Stop failed", it) },
            OnSuccess.noOp()
        )
    }

    fun isRunning(): Boolean = _state.value is TorState.Connected

    fun getSocksPort(): Int? = (_state.value as? TorState.Connected)?.socksPort

    companion object {
        private const val TAG = "TorManager"
        private const val BOOTSTRAP_TIMEOUT_MS = 90_000L
    }
}
