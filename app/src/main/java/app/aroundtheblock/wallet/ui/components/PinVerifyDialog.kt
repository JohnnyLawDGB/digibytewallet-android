package app.aroundtheblock.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteRed

/** Digits in a wallet PIN; the dialog's dot row and auto-submit are sized to it. */
const val PIN_VERIFY_LENGTH = 6

/** Format a re-auth lockout deadline as a localised "locked, try again in M:SS" at press time.
 *  (The dialog re-renders per keystroke; a live tick isn't needed here — the enforcement
 *  lives in PinManager regardless of what this string says.)
 *  Takes Resources because it is not composable. */
fun pinLockedCountdownMessage(res: android.content.res.Resources, until: Long): String {
    val remainingMs = (until - System.currentTimeMillis()).coerceAtLeast(0L)
    val totalSec = (remainingMs + 999L) / 1000L
    // The M:SS clock itself is a number format, not prose — only the sentence around it is
    // translated, so the digits read the same in every language.
    val clock = "%d:%02d".format(totalSec / 60, totalSec % 60)
    return res.getString(R.string.sec_locked_countdown, clock)
}

/**
 * In-app PIN entry dialog with its own keypad — the same one Security settings uses for
 * change-PIN / view-seed / wipe, shared so every re-auth in the app looks and behaves alike.
 * The custom keypad (not the IME) is deliberate: no learnable keyboard sees the digits.
 */
@Composable
fun PinVerifyDialog(
    title: String,
    subtitle: String,
    pinInput: String,
    pinError: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit
) {
    // Its own window: obscured touches refused, no screenshots or task-switcher snapshot of the
    // keypad, overlays hidden. Laid out like the AlertDialog it replaces (which opens a plain window).
    SensitiveDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = Color(0xFF1A2742),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        subtitle,
                        color = Color(0xFF8899AA),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    // PIN dot indicators
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(PIN_VERIFY_LENGTH) { idx ->
                            val filled = idx < pinInput.length
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) DigiByteAccent else Color.Transparent)
                                    .border(2.dp, if (filled) DigiByteAccent else Color(0xFF243352), CircleShape)
                            )
                        }
                    }

                    pinError?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = DigiByteRed, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(20.dp))

                    // Compact keypad
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "⌫")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                row.forEach { key ->
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        when (key) {
                                            "" -> Spacer(Modifier.size(52.dp))
                                            "⌫" -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .clickable { onBackspace() },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.Backspace,
                                                        contentDescription = stringResource(R.string.pin_backspace),
                                                        tint = Color(0xFF8899AA),
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                            else -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF243352))
                                                        .border(1.dp, Color(0xFF3A4F6A), CircleShape)
                                                        .clickable { onDigit(key) },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        key,
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color.White
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
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel), color = Color(0xFF8899AA))
                    }
                }
            }
        }
    }
}
