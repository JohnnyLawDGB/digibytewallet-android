package io.digibyte.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue

@Composable
fun SettingsScreen(navController: NavController) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
        }

        item {
            SettingsCategory(title = "Security & Privacy") {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    iconTint = DigiByteAccent,
                    title = "Security",
                    subtitle = "PIN, biometric auth, seed phrase, auto-lock",
                    onClick = { navController.navigate("settings_security") }
                )
            }
        }

        item {
            SettingsCategory(title = "Network") {
                SettingsRow(
                    icon = Icons.Default.WifiTethering,
                    iconTint = Color(0xFF4CAF50),
                    title = "Network Info",
                    subtitle = "Peers, sync status, block height",
                    onClick = { navController.navigate("settings_network") }
                )
            }
        }

        item {
            SettingsCategory(title = "Appearance") {
                SettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = Color(0xFFFF9800),
                    title = "Display",
                    subtitle = "Fiat currency, theme",
                    onClick = { navController.navigate("settings_display") }
                )
            }
        }

        item {
            SettingsCategory(title = "Info") {
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = DigiByteBlue,
                    title = "About",
                    subtitle = "Version, licenses, open source",
                    onClick = { navController.navigate("settings_about") }
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "DigiByte Wallet v3.0.0-alpha1",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF546E7A),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF546E7A),
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2742))
        ) {
            Column { content() }
        }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8899AA)
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF546E7A),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = Color(0xFF243352),
        thickness = 0.5.dp
    )
}
