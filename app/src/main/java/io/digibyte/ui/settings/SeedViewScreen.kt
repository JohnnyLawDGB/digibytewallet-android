@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.digibyte.core.WalletManager
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.ui.theme.DigiByteRed
import io.digibyte.R

/**
 * Displays the recovery seed phrase.
 * FLAG_SECURE prevents screenshots and screen recording for the lifetime of this screen.
 *
 * Access: gated by PIN + biometric in SecuritySettingsScreen before navigating here.
 */
@Composable
fun SeedViewScreen(
    navController: NavController,
    walletManager: WalletManager,
    keyStoreManager: KeyStoreManager
) {
    val view = LocalView.current

    // Apply FLAG_SECURE on entry, remove on exit
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * The stored BIP39 passphrase, if this wallet has one.
     *
     * Shown WITH the phrase, deliberately. The app holds the passphrase so the wallet can rebuild
     * itself on every restart — which means a user who writes down only the twelve words is
     * holding half a backup while believing it is whole. Revealing the phrase without it would
     * make this screen actively misleading: it is titled "Recovery Phrase" and would be showing
     * something that no longer recovers anything on its own.
     *
     * Behind the same PIN + biometric gate as the phrase, because it is the same secret material.
     */
    val storedPassphrase: String? = remember {
        try {
            val prefs = view.context.getSharedPreferences("dgb_wallet_seed", android.content.Context.MODE_PRIVATE)
            val ct = prefs.getString("encrypted_pass", null)
            val iv = prefs.getString("encrypted_pass_iv", null)
            if (ct != null && iv != null) {
                val decrypted = keyStoreManager.decrypt(
                    io.digibyte.core.security.EncryptedData(
                        ct.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                        iv.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    )
                )
                decrypted?.let { String(it, Charsets.UTF_8).also { _ -> it.fill(0) } }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Decrypt the seed from SharedPreferences via KeyStoreManager
    val words: List<String> = remember {
        try {
            val prefs = view.context.getSharedPreferences("dgb_wallet_seed", android.content.Context.MODE_PRIVATE)
            val ciphertextHex = prefs.getString("encrypted_seed", null)
            val ivHex = prefs.getString("encrypted_seed_iv", null)
            if (ciphertextHex != null && ivHex != null) {
                val ciphertext = ciphertextHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val decrypted = keyStoreManager.decrypt(
                    io.digibyte.core.security.EncryptedData(ciphertext, iv)
                )
                val phrase = String(decrypted, Charsets.UTF_8)
                decrypted.fill(0) // zero the ByteArray after use
                phrase.trim().split(" ")
            } else {
                List(12) { "••••••" }
            }
        } catch (e: Exception) {
            android.util.Log.e("SeedViewScreen", "Failed to decrypt seed: ${e.message}")
            List(12) { "error" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        TopAppBar(
            title = { Text("Recovery Phrase", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warning banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DigiByteRed.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = DigiByteRed, modifier = Modifier.size(22.dp))
                    Column {
                        Text(
                            "Never share your recovery phrase",
                            color = DigiByteRed,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Anyone with these words can steal your funds.",
                            color = Color(0xFFB0BEC5),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Your ${words.size}-word Recovery Phrase",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Write these words down in order and store them safely.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8899AA)
            )

            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(words) { idx, word ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                color = Color(0xFF546E7A),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                text = word,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // The passphrase, if there is one. Silent when there is not — the overwhelming
            // majority of wallets — so this screen is unchanged for them.
            if (storedPassphrase != null) {
                Spacer(Modifier.height(20.dp))
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.pass_enter),
                            color = Color(0xFF8FA1B8),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = storedPassphrase,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.pass_warn),
                            color = Color(0xFFFFCC66),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2742))
            ) {
                Text("Done — Hide Phrase", color = Color.White)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
