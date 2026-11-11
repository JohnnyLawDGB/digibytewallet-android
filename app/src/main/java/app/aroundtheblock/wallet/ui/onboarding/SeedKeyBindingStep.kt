package app.aroundtheblock.wallet.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.security.BiometricAuth
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KS-BIND. Run by the step that is about to write a NEW wallet record — PIN setup for a create, the
 * restore's start for a restore — immediately before the write.
 *
 * Invariant: on a device with a secure lock screen the record is sealed under the auth-bound key
 * from creation. The bound key opens for 300 s after any device authentication; the keyguard unlock
 * that opened the phone usually still covers this step, so usually nothing is shown. When it does
 * not (the user spent longer than the window writing the phrase down or typing it in), ONE
 * device-credential prompt opens a fresh window and the write follows inside it.
 *
 * Never blocks onboarding: a declined or failed prompt, no activity, or any error just lets the write
 * go ahead, which then falls back to the legacy key as it always has (and the unlock-time migration
 * still moves it later). Returns the readiness, so the caller can tell a user with no lock screen.
 */
suspend fun prepareSeedKeyBeforeRecordWrite(
    walletManager: WalletManager,
    biometricAuth: BiometricAuth,
    activity: FragmentActivity?,
    promptTitle: String,
    promptSubtitle: String,
): SeedKeyReadiness {
    val readiness = try {
        withContext(Dispatchers.IO) { walletManager.prepareSeedKeyForNewRecord() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        SeedKeyReadiness.UNAVAILABLE
    }
    if (shouldPromptForDeviceCredential(readiness) && activity != null) {
        // The outcome does not matter here: success opens the window, anything else leaves the
        // seal to fall back. authenticateDeviceCredential never throws.
        biometricAuth.authenticateDeviceCredential(activity, title = promptTitle, subtitle = promptSubtitle)
    }
    return readiness
}

/** KS-BIND: the one case that shows a prompt. Every other readiness writes without one. */
fun shouldPromptForDeviceCredential(readiness: SeedKeyReadiness): Boolean =
    readiness == SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL

/**
 * KS-BIND. The one-time notice for a device with no secure lock screen, shown once when the wallet
 * is set up: the seed is still encrypted on the phone, but its key cannot be tied to a screen lock
 * the device does not have. Security settings carries the same fact as a status line afterwards.
 */
@Composable
fun NoScreenLockNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A2742),
        icon = { Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color(0xFFFFAA00)) },
        title = {
            Text(stringResource(R.string.ksb_notice_title), color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ksb_notice_body),
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ksb_notice_ok), color = Color(0xFFFFAA00))
            }
        },
    )
}
