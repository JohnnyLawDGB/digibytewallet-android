@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.digibyte.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.ui.theme.DigiByteAccent

private const val PREFS_NAME = "dgb_settings"
private const val KEY_SYNC_MODE = "sync_mode"

@Composable
fun SyncModeScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var selected by remember {
        mutableStateOf(prefs.getInt(KEY_SYNC_MODE, NativeBridge.SyncMode.BOTH)) // default: Both (block preferred, bloom as parallel safety net)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    fun pick(mode: Int) {
        if (mode == selected) return
        selected = mode
        prefs.edit().putInt(KEY_SYNC_MODE, mode).apply()
        snackMessage = "Sync mode change applies on next app restart."
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sync Mode", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1628))
            )
        },
        containerColor = Color(0xFF0A1628)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    text = "How the wallet finds your transactions on the network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0BEC5),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            item {
                SettingsCategory(title = "Protocol") {
                    SyncModeRow(
                        label = "Bloom filter (BIP 37)",
                        description = "Default. Wallet asks peers to filter blocks for its addresses. " +
                                "Peers see the addresses you're watching.",
                        selected = selected == NativeBridge.SyncMode.BLOOM_ONLY,
                        onSelect = { pick(NativeBridge.SyncMode.BLOOM_ONLY) }
                    )
                    SettingsRowDivider()
                    SyncModeRow(
                        label = "Compact filters (BIP 158)",
                        description = "Wallet downloads compact filters per block and matches " +
                                "addresses locally. Peer never learns which addresses you watch.",
                        selected = selected == NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
                        onSelect = { pick(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY) }
                    )
                    SettingsRowDivider()
                    SyncModeRow(
                        label = "Both",
                        description = "Run both stacks in parallel. Best coverage during the rollout " +
                                "while filter-capable peers are still rare.",
                        selected = selected == NativeBridge.SyncMode.BOTH,
                        onSelect = { pick(NativeBridge.SyncMode.BOTH) }
                    )
                }
            }

            item {
                Text(
                    text = "Compact filters require a peer that serves them. The seeder routes you " +
                            "to filter-capable peers automatically when available, and falls back " +
                            "to bloom peers when not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8899AA),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SyncModeRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8899AA)
            )
        }

        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = DigiByteAccent,
                unselectedColor = Color(0xFF546E7A)
            )
        )
    }
}
