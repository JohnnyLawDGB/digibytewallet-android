package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.digibyte.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.BootGuard
import io.digibyte.core.WalletManager
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.BiometricResult
import io.digibyte.core.security.PinManager
import io.digibyte.core.security.PinVerifyResult
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UNLOCK_PIN_LENGTH = 6

/**
 * How many times this screen retries an owed wipe before it stops holding itself for it. A wipe
 * that will not complete must not be a screen that never opens: at the bound the screen either
 * offers onboarding, when no wallet record is left on the device, or takes the PIN again over the
 * wallet that is still there. The wipe stays owed either way — the launch backstop and each later
 * entry to this screen retry it — so the bound costs a retry, not the wipe.
 */
private const val OWED_WIPE_HOLD_ATTEMPTS = 3

/** Format a remaining-lockout duration (ms) as M:SS for the countdown. */
private fun formatLockCountdown(remainingMs: Long): String {
    val totalSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L) // round up
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun UnlockScreen(
    navController: NavController,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    walletManager: WalletManager
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    // Hoisted: these are assigned inside event lambdas, which are not composable.
    val unlockFailedMsg = stringResource(R.string.unlock_err_failed)
    val deviceLockRemovedMsg = stringResource(R.string.unlock_device_lock_removed)
    val wipingMsg = stringResource(R.string.unlock_wiping)
    val wipeIncompleteMsg = stringResource(R.string.wipe_incomplete)
    val attemptsLeftFmt = stringResource(R.string.unlock_attempts_left)
    var currentInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Persisted PIN rate-limit (PinManager). lockedUntil is the wall-clock epoch-ms
    // the lockout expires (0 = not locked); nowTick drives the live M:SS countdown.
    // Both survive a process restart because PinManager persists the lockout in
    // dgb_pin_store — currentLockout() rehydrates it on enter (below).
    var lockedUntil by remember { mutableStateOf(0L) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val isLocked = lockedUntil > nowTick
    // True while unlockFromUi()/restoreFromDisk() are running in the background.
    // restoreFromDisk() calls NativeBridge.stopSync(), which takes the native
    // peer-manager lock (PEER_GUARD) — the keepalive sweep can hold that lock for
    // up to ~K×10s pinging half-dead sockets, so this work must run off the main
    // thread (this was the ANR trace's attemptUnlock → unlockFromUi/restoreFromDisk
    // → stopSync path). The keypad/biometric button are disabled and a brief
    // "Unlocking…" indicator is shown while this is in flight.
    var isUnlocking by remember { mutableStateOf(false) }
    val biometricAvailable = remember { activity?.let { biometricAuth.canAuthenticate(it) } ?: false }
    // True while an owed wipe is running, so a second entry cannot start another beside it.
    var isWiping by remember { mutableStateOf(false) }
    // Attempts this screen has made at an owed wipe, and whether it has stood down (below).
    var owedWipeAttempts by remember { mutableStateOf(0) }
    var owedWipeStoodDown by remember { mutableStateOf(false) }

    // A wipe is OWED from the moment wipe-after-N trips (PinManager persists pin_wipe_pending)
    // until WalletManager.wipeThenReleasePin finds the seed verifiably gone. While it is owed this
    // screen takes no PIN and no biometric — every attempt, and every entry to the screen, retries
    // the wipe — and because the PIN store is released only after a verified wipe, PIN setup is
    // never offered over a wallet that was not wiped.
    //
    // The hold is BOUNDED, and that bound comes first: a store that will not take its write must
    // never add up to a device that can no longer be opened at all. After
    // OWED_WIPE_HOLD_ATTEMPTS attempts that could not be verified, the screen asks
    // WalletManager.releaseOwedWipeIfNoWalletIsLeft and then either offers onboarding (no wallet
    // record is left for the owed wipe to hold this screen for) or stands down and takes the PIN
    // again over the wallet that is still there. The wipe stays owed either way, so the launch
    // backstop and every later entry to this screen retry it.
    fun owedWipeHoldsTheScreen(): Boolean = !owedWipeStoodDown && pinManager.isWipePending()

    suspend fun runOwedWipe() {
        if (isWiping) return
        isWiping = true
        currentInput = ""
        errorMessage = wipingMsg
        try {
            // The wipe and its hand-over run to their end even if this screen goes away meanwhile.
            // Once the wipe has removed the seed it does not come back from the hand-over: this
            // process ends and a fresh one starts on onboarding, which finishes and reports whatever
            // the wipe left undone. Everything below is for a wipe that could not remove the seed.
            val incomplete = io.digibyte.FreshStartAfterWipe.wipeThenHandOver(context) {
                walletManager.wipeThenReleasePin(pinManager)
            }
            if (!incomplete) return
            owedWipeAttempts++
            val walletGone = walletManager.walletState.value is io.digibyte.core.WalletState.NoWallet
            if (walletGone) {
                // No wallet is left here for a credential to open, so onboarding; leaving this
                // screen takes its error line with it, hence the notice.
                io.digibyte.ui.components.showWipeIncompleteNotice(context)
                navController.navigate("onboarding") {
                    popUpTo(0) { inclusive = true }
                }
                return
            }
            errorMessage = wipeIncompleteMsg
            if (owedWipeAttempts >= OWED_WIPE_HOLD_ATTEMPTS) {
                val nothingLeft = withContext(Dispatchers.IO) {
                    walletManager.releaseOwedWipeIfNoWalletIsLeft(pinManager)
                }
                if (nothingLeft) {
                    io.digibyte.ui.components.showWipeIncompleteNotice(context)
                    navController.navigate("onboarding") {
                        popUpTo(0) { inclusive = true }
                    }
                } else {
                    // The wallet is still here and still opens with its PIN. Stand down so it can.
                    owedWipeStoodDown = true
                }
            }
        } finally {
            isWiping = false
        }
    }

    // Shared unlock body for all three entry points below (auto-biometric,
    // PIN entry, manual biometric button). Runs the wallet-ready check off
    // the main thread — see the class comment for why (native PEER_GUARD
    // lock inside restoreFromDisk()) — and fails safe:
    //  - CancellationException is expected when the hosting scope is torn
    //    down mid-unlock (e.g. rotation recreates the activity/composition)
    //    and MUST propagate for structured concurrency to work; it is not an
    //    error. The flow self-heals on the next PIN/biometric attempt, since
    //    isWalletReady() will already be true by then and unlockFromUi() is
    //    just a state flip.
    //  - Any other exception is caught so a genuine unlock failure surfaces
    //    the existing error UX instead of crashing the process.
    //  - isUnlocking is always reset in `finally` so the keypad/biometric
    //    button never get stuck disabled if navigation is skipped.
    suspend fun performUnlockAndNavigate(isAuthRetry: Boolean = false) {
        // No credential — biometric included — opens a wallet whose wipe is owed and still held.
        if (owedWipeHoldsTheScreen()) { runOwedWipe(); return }
        isUnlocking = true
        // Any successful unlock — PIN or BIOMETRIC — clears the PIN rate-limit
        // counter. Critical for the biometric path: a legit user who unlocks with a
        // fingerprint must not carry a stale PIN lockout the next time they type it.
        // (The PIN path already reset via verifyPin()==Success; this is idempotent.)
        pinManager.onUnlockSuccess()
        try {
            val opened = withContext(Dispatchers.IO) {
                if (!walletManager.hasSavedWallet()) {
                    // No wallet is stored on this device, so a credential opens nothing — even
                    // when this process still holds the one a wipe that could not be verified
                    // took off the device. (A wallet whose seed is still here reads as stored.)
                    false
                } else if (walletManager.isWalletReady()) {
                    walletManager.unlockFromUi()
                    true
                } else {
                    // Restore-crash bracket: BootGuard lives in the app module and
                    // WalletManager.restoreFromDisk() is core (core cannot depend on
                    // app), so beginRestore is armed here, at the call site, rather
                    // than inside restoreFromDisk() itself. This IS the start of the
                    // post-unlock restore — recoverWalletFromBytes runs synchronously
                    // inside the call below. commit()'d before the risky call so a
                    // crash mid-restore leaves restore_pending=true for BootGuard
                    // .recoverFromCrashedRestoreIfNeeded to see next launch.
                    BootGuard.beginRestore(context)
                    walletManager.restoreFromDisk()
                }
            }
            if (!opened) {
                // The credential was right and the wallet still did not load, so there is
                // nothing behind this screen to show. (After a wipe that could not remove the
                // seed, the stood-down PIN does open it: a store whose write did not land still
                // reads as what its file holds.) Say so and keep the screen, rather than
                // navigating to a wallet with nothing in it — from which the only way back is
                // to kill the app.
                errorMessage = if (owedWipeStoodDown) wipeIncompleteMsg else unlockFailedMsg
                return
            }
            navController.navigate("wallet") {
                popUpTo("unlock") { inclusive = true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.digibyte.core.security.KeystoreUserAuthRequiredException) {
            // Auth-bound seed key outside its device-unlock window
            // (docs/specs/keystore-auth-binding.md): refresh with a
            // DEVICE_CREDENTIAL|BIOMETRIC_STRONG prompt and retry ONCE.
            // This path replaces the crash that reverted the first binding attempt.
            if (!isAuthRetry && activity != null) {
                isUnlocking = false
                val r = biometricAuth.authenticateDeviceCredential(activity)
                if (r is io.digibyte.core.security.BiometricResult.Success) {
                    performUnlockAndNavigate(isAuthRetry = true)
                    return
                }
            }
            errorMessage = unlockFailedMsg
        } catch (e: io.digibyte.core.security.KeystoreKeyInvalidatedException) {
            // Device lock screen was removed - the bound key is permanently gone.
            // Only the written recovery phrase recovers this wallet; say exactly that.
            android.util.Log.e("UnlockScreen", "wallet key permanently invalidated", e)
            errorMessage = deviceLockRemovedMsg
        } catch (t: Throwable) {
            android.util.Log.e("UnlockScreen", "unlock failed: ${t.message}", t)
            errorMessage = unlockFailedMsg
        } finally {
            isUnlocking = false
        }
    }

    // Attempt biometric automatically on first composition if available.
    // Biometric stays available even during a PIN lockout (decision E): it's a
    // separate credential with its own OS lockout, and a PIN brute-forcer has no
    // finger — a legit owner shouldn't be locked out of their own fingerprint by
    // someone else fat-fingering the PIN.
    LaunchedEffect(Unit) {
        // An owed wipe comes first: retry it on entry instead of prompting for anything.
        if (owedWipeHoldsTheScreen()) { runOwedWipe(); return@LaunchedEffect }
        // No wallet is stored for a credential to open, so onboarding — for instance when the task
        // is brought back, with this screen on it, in the fresh process a verified wipe started.
        if (!walletManager.hasSavedWallet()) {
            navController.navigate("onboarding") {
                popUpTo(0) { inclusive = true }
            }
            return@LaunchedEffect
        }
        if (biometricAvailable && activity != null) {
            val result = biometricAuth.authenticate(activity)
            if (result is BiometricResult.Success) {
                performUnlockAndNavigate()
            }
        }
    }

    // Rehydrate a persisted lockout on enter (survives force-stop / process restart).
    LaunchedEffect(Unit) {
        val until = pinManager.currentLockout()
        if (until > System.currentTimeMillis()) {
            lockedUntil = until
            nowTick = System.currentTimeMillis()
        }
    }

    // Live countdown: tick while locked, then re-enable the keypad automatically.
    LaunchedEffect(lockedUntil) {
        if (lockedUntil > 0L) {
            while (System.currentTimeMillis() < lockedUntil) {
                nowTick = System.currentTimeMillis()
                delay(500)
            }
            lockedUntil = 0L
            nowTick = System.currentTimeMillis()
            errorMessage = null
        }
    }

    fun attemptUnlock(pin: String) {
        // While a wipe is owed and this screen still holds itself for it, the entry is not checked
        // against the PIN at all — so it can neither unlock the wallet nor touch the attempt
        // counters — it retries the wipe.
        if (owedWipeHoldsTheScreen()) {
            currentInput = ""
            scope.launch { runOwedWipe() }
            return
        }
        when (val result = pinManager.verifyPin(pin)) {
            is PinVerifyResult.Success -> {
                scope.launch {
                    // For UI-only relock (wallet already loaded in memory), just flip state.
                    // For fresh process (wallet not loaded), restore from disk — both run
                    // off the main thread since restoreFromDisk() can block on the native
                    // peer-manager lock.
                    performUnlockAndNavigate()
                }
            }
            is PinVerifyResult.Wrong -> {
                currentInput = ""
                val startedLockout = result.lockedUntil
                if (startedLockout != null) {
                    lockedUntil = startedLockout
                    nowTick = System.currentTimeMillis()
                    errorMessage = null // the countdown text takes over
                } else {
                    val before = (PinManager.FREE_ATTEMPTS + 1) - result.failCount
                    errorMessage = attemptsLeftFmt.format(before)
                }
            }
            is PinVerifyResult.LockedOut -> {
                currentInput = ""
                lockedUntil = result.until
                nowTick = System.currentTimeMillis()
                errorMessage = null
            }
            is PinVerifyResult.ShouldWipe -> {
                // Wipe-after-N tripped: PinManager already persisted pin_wipe_pending
                // (a kill here completes the wipe next launch). The caller owns the
                // destructive wallet wipe — PinManager can only clear dgb_pin_store.
                // The PIN store is released by wipeThenReleasePin, only after a verified wipe.
                currentInput = ""
                scope.launch { runOwedWipe() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DigiByteLogoHeader()

                Spacer(Modifier.height(40.dp))

                Text(
                    text = stringResource(R.string.unlock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.unlock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0BEC5),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // PIN dot row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(UNLOCK_PIN_LENGTH) { idx ->
                        val filled = idx < currentInput.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (filled) DigiByteAccent else Color.Transparent)
                                .border(2.dp, if (filled) DigiByteAccent else Color(0xFF243352), CircleShape)
                        )
                    }
                }

                if (isLocked) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            R.string.unlock_locked,
                            formatLockCountdown(lockedUntil - nowTick),
                        ),
                        color = Color(0xFFFF9800),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                } else errorMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        color = DigiByteRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                if (isUnlocking) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = DigiByteAccent
                        )
                        Text(
                            text = stringResource(R.string.unlock_unlocking),
                            color = Color(0xFFB0BEC5),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Biometric button
                if (biometricAvailable) {
                    Spacer(Modifier.height(24.dp))
                    IconButton(
                        enabled = !isUnlocking,
                        onClick = {
                            scope.launch {
                                if (activity != null) {
                                    val result = biometricAuth.authenticate(activity)
                                    if (result is BiometricResult.Success) {
                                        performUnlockAndNavigate()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = stringResource(R.string.unlock_biometrics_cd),
                            tint = if (isUnlocking) Color(0xFF546E7A) else DigiByteAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // Numeric keypad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫")
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEach { key ->
                            when (key) {
                                "" -> Spacer(Modifier.size(72.dp))
                                "⌫" -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = !isLocked && !isUnlocking) {
                                                if (currentInput.isNotEmpty()) {
                                                    currentInput = currentInput.dropLast(1)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Backspace,
                                            contentDescription = stringResource(R.string.pin_backspace),
                                            tint = Color(0xFFB0BEC5),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1A2742))
                                            .border(1.dp, Color(0xFF243352), CircleShape)
                                            .clickable(enabled = !isLocked && !isUnlocking) {
                                                if (currentInput.length < UNLOCK_PIN_LENGTH) {
                                                    currentInput += key
                                                    errorMessage = null
                                                    if (currentInput.length == UNLOCK_PIN_LENGTH) {
                                                        attemptUnlock(currentInput)
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (!isLocked && !isUnlocking) Color.White else Color(0xFF546E7A)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
