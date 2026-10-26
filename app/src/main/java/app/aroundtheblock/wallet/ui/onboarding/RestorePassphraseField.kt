package app.aroundtheblock.wallet.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.Bip39Passphrase

/**
 * The optional BIP39 passphrase, on the restore screen.
 *
 * The restore twin of [PassphraseSection], and deliberately not the same thing. At creation the
 * question is "do you want one?", with warnings about what it costs; at restore it is only "did you
 * set one?", and the person is reading it off their own backup. So: collapsed by default, one field
 * (a second, confirming field guards against inventing a typo, not copying one), and the one fact
 * that matters said plainly — a wrong passphrase does not fail, it opens a different, empty wallet.
 *
 * A separate file because MnemonicInputScreen must never mask anything: its words are checked by
 * eye against paper, and SensitiveInputHardeningTest holds it to that.
 */
@Composable
fun RestorePassphraseField(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tooLong = !Bip39Passphrase.isValid(passphrase)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.restore_pass_toggle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8FA1B8),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8FA1B8),
            )
        }

        if (expanded) {
            Text(
                text = stringResource(R.string.restore_pass_body),
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                ),
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
        }
    }
}
