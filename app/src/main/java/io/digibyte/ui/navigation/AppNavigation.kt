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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.PinManager
import io.digibyte.ui.onboarding.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Wallet : Screen("wallet", "Wallet", Icons.Default.Home)
    data object Hub : Screen("hub", "Hub", Icons.Default.Chat)
    data object DigiId : Screen("digiid", "Digi-ID", Icons.Default.Fingerprint)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Wallet, Screen.Hub, Screen.DigiId, Screen.Settings)

/** Routes that should NOT show the bottom navigation bar. */
private val fullScreenRoutes = setOf(
    "onboarding",
    "seed_display/{wordCount}",
    "seed_verify",
    "mnemonic_input",
    "recovery_date",
    "pin_setup",
    "unlock"
)

@Composable
fun AppNavigation(
    walletManager: WalletManager,
    pinManager: PinManager,
    biometricAuth: BiometricAuth
) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    // Observe wallet state to gate navigation
    val walletState by walletManager.walletState.collectAsState()

    // Determine start destination based on wallet state at launch
    val startDestination = remember(walletState) {
        when (walletState) {
            is WalletState.NoWallet -> "onboarding"
            is WalletState.Locked -> "unlock"
            is WalletState.Unlocked -> Screen.Wallet.route
        }
    }

    val showBottomNav = currentRoute !in fullScreenRoutes

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentRoute == screen.route,
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
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            // ── Onboarding flow ───────────────────────────────────────────────
            composable("onboarding") {
                OnboardingScreen(navController = navController)
            }

            composable("seed_display/{wordCount}") { backStackEntry ->
                val wordCount = backStackEntry.arguments?.getString("wordCount")?.toIntOrNull() ?: 12
                SeedDisplayScreen(
                    navController = navController,
                    wordCount = wordCount
                )
            }

            composable("seed_verify") {
                SeedVerifyScreen(navController = navController)
            }

            composable("mnemonic_input") {
                MnemonicInputScreen(navController = navController)
            }

            composable("recovery_date") {
                RecoveryDateScreen(navController = navController)
            }

            composable("pin_setup") {
                PinSetupScreen(
                    navController = navController,
                    biometricAuth = biometricAuth
                )
            }

            // ── Unlock (returning users) ──────────────────────────────────────
            composable("unlock") {
                UnlockScreen(
                    navController = navController,
                    pinManager = pinManager,
                    biometricAuth = biometricAuth,
                    walletManager = walletManager
                )
            }

            // ── Main wallet tabs ──────────────────────────────────────────────
            composable(Screen.Wallet.route) {
                PlaceholderScreen("Wallet")
            }
            composable(Screen.Hub.route) {
                PlaceholderScreen("Community Hub\n(Phase 3)")
            }
            composable(Screen.DigiId.route) {
                PlaceholderScreen("Digi-ID\n(Phase 2)")
            }
            composable(Screen.Settings.route) {
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
