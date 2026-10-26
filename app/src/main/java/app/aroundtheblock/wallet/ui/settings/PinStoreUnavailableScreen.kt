package app.aroundtheblock.wallet.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aroundtheblock.wallet.R

/**
 * Shown when the PIN store cannot be opened (4.1.2 row 23). The app used to crash on every launch; the
 * wallet is not opened and no new PIN can be set, because nothing can be checked. Nothing is deleted:
 * the wallet, its recovery phrase and the PIN store stay as they are, so a passing Keystore fault
 * clears on the next launch. The text says what to do if it does not.
 */
@Composable
fun PinStoreUnavailableScreen() {
    val activity = LocalContext.current as? Activity
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.pinstore_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.pinstore_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = { activity?.finishAffinity() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.pinstore_close))
        }
    }
}
