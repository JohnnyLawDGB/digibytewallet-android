package io.digibyte.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Wallet : Screen("wallet", "Wallet", Icons.Default.Home)
    data object Hub : Screen("hub", "Hub", Icons.Default.Chat)
    data object DigiId : Screen("digiid", "Digi-ID", Icons.Default.Fingerprint)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Wallet, Screen.Hub, Screen.DigiId, Screen.Settings)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentRoute by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute?.destination?.route == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Wallet.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Wallet.route) {
                // Placeholder — Task 10 builds the real WalletScreen
                PlaceholderScreen("Wallet")
            }
            composable(Screen.Hub.route) {
                PlaceholderScreen("Community Hub\n(Phase 3)")
            }
            composable(Screen.DigiId.route) {
                PlaceholderScreen("Digi-ID\n(Phase 2)")
            }
            composable(Screen.Settings.route) {
                // Placeholder — Task 12 builds the real SettingsScreen
                PlaceholderScreen("Settings")
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
    }
}
