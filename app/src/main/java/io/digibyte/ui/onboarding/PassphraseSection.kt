package io.digibyte.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.R
import io.digibyte.core.Bip39Passphrase

/**
 * The optional BIP39 passphrase, on the seed-display screen.
 *
 * ## Closed by default, and silent when closed
 *
 * Most people should not use this, and the ones who should already know they want it. Collapsed
 * it is a single muted line; it never prompts, warns, or interrupts. A wallet created without
 * opening it derives exactly what it would have derived before this feature existed.
 *
 * ## Confirmed twice, because there is no checksum
 *
 * A mnemonic has a checksum: mistype a word and the wallet tells you. A passphrase has none.
 * Mistype it and you get a different, perfectly valid, empty wallet — at creation you would
 * never notice, and at restore it looks exactly like stolen coins. Two fields that must match is
 * the cheapest defence available, and it is the same shape as the PIN, which users already
 * expect to type twice.
 *
 * ## The copy is deliberately unflattering
 *
 * It says the phrase alone will no longer restore the wallet, and it says the passphrase protects
 * the written backup rather than the phone. Both are true and both are things a user would
 * otherwise learn at the worst possible moment.
 */
@Composable
fun PassphraseSection(
    passphrase: String,
    confirm: String,
    onPassphraseChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val tooLong = !Bip39Passphrase.isValid(passphrase)
    val mismatch = passphrase.isNotEmpty() && confirm.isNotEmpty() && passphrase != confirm

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pass_advanced),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8FA1B8),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (passphrase.isNotEmpty()) R.string.pass_set else R.string.pass_optional
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FA1B8),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = Color(0xFF8FA1B8),
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            Text(
                text = stringResource(R.string.pass_what),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = { Text(stringResource(R.string.pass_enter)) },
                singleLine = true,
                isError = tooLong,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (tooLong) {
                Text(
                    text = stringResource(R.string.pass_too_long, Bip39Passphrase.MAX_BYTES),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = confirm,
                onValueChange = onConfirmChange,
                label = { Text(stringResource(R.string.pass_confirm)) },
                singleLine = true,
                isError = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mismatch) {
                Text(
                    text = stringResource(R.string.pass_mismatch),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            // The warning that prevents a lost wallet, and the honest scope note.
            Text(
                text = stringResource(R.string.pass_warn),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFFFCC66),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.pass_scope),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8FA1B8),
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Whether the seed screen may continue.
 *
 * Kept as a free function so the rule is unit-testable without Compose: an untouched section
 * must never block anyone, and a half-filled one must never create a wallet whose passphrase the
 * user has not confirmed.
 */
fun passphraseEntryReady(passphrase: String, confirm: String): Boolean = when {
    passphrase.isEmpty() -> true                       // untouched: the common case
    !Bip39Passphrase.isValid(passphrase) -> false      // over the cap
    else -> passphrase == confirm                      // must be confirmed
}
