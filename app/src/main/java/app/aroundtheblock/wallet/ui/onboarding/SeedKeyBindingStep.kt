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
import android.content.Context
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.security.BiometricAuth

/**
 * KS-BIND. The device-credential prompt for the step that is about to write a NEW wallet record —
 * PIN setup for a create, the restore's start for a restore. The step hands it to
 * [OnboardingViewModel.createWallet] / [OnboardingViewModel.recoverWallet], which run
 * [app.aroundtheblock.wallet.core.prepareSeedKeyThenPrompt] and then the write in ONE coroutine in
 * the ViewModel's scope, so the screen leaving composition while the prompt is up cannot orphan
 * the write.
 *
 * Invariant: on a device with a secure lock screen the record is sealed under the auth-bound key
 * from creation. The bound key opens for 300 s after any device authentication; the keyguard unlock
 * that opened the phone usually still covers this step, so usually nothing is shown. When it does
 * not (the user spent longer than the window writing the phrase down or typing it in), this ONE
 * prompt opens a fresh window and the write follows inside it.
 *
 * Null when there is no activity to show it on: the write then goes ahead without a probe or a
 * prompt and falls back to the legacy key if the window has lapsed. The prompt's outcome does not
 * matter to the write: success opens the window, anything else leaves the seal to fall back.
 * [BiometricAuth.authenticateDeviceCredential] never throws.
 */
fun seedKeyPromptFor(
    biometricAuth: BiometricAuth,
    activity: FragmentActivity?,
    promptTitle: String,
    promptSubtitle: String,
): (suspend () -> Unit)? = activity?.let { a ->
    { biometricAuth.authenticateDeviceCredential(a, title = promptTitle, subtitle = promptSubtitle) }
}

/**
 * KS-BIND: the no-screen-lock notice is shown once per install. PIN setup marks it due when it
 * finds no secure lock screen; dismissing it acknowledges it for good. A notice that was due but
 * never dismissed (the app went to the background while it was up and the lock screen took over)
 * is shown again by MainActivity at the next unlock. An existing wallet that never passes through
 * PIN setup is never marked, so it sees nothing new.
 */
object NoScreenLockNoticeFlags {
    private const val PREFS = "dgb_settings"
    private const val KEY_DUE = "ksb_no_lock_notice_due"
    private const val KEY_ACK = "ksb_no_lock_notice_ack"

    /** True while PIN setup has the notice up, so MainActivity does not stack a second copy on it. */
    @Volatile var onScreen: Boolean = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Marks the notice due, unless it was already acknowledged; true when it should be shown now. */
    fun markDueIfUnacknowledged(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_ACK, false)) return false
        p.edit().putBoolean(KEY_DUE, true).apply()
        return true
    }

    fun isDue(context: Context): Boolean {
        val p = prefs(context)
        return noScreenLockNoticeDue(due = p.getBoolean(KEY_DUE, false), acknowledged = p.getBoolean(KEY_ACK, false))
    }

    fun acknowledge(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACK, true).putBoolean(KEY_DUE, false).apply()
    }
}

/** Pure: whether a marked notice still needs showing. */
fun noScreenLockNoticeDue(due: Boolean, acknowledged: Boolean): Boolean = due && !acknowledged

/**
 * KS-BIND. The one-time notice for a device with no secure lock screen, shown once when the wallet
 * is set up (see [NoScreenLockNoticeFlags]): the seed is still encrypted on the phone, but its key cannot be tied to a screen lock
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
