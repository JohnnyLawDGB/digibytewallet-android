package io.digibyte.ui.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiByteUri
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.service.SyncService
import io.digibyte.ui.asset.*
import io.digibyte.ui.components.QrScannerScreen
import io.digibyte.ui.digiid.DigiIdConfirmScreen
import io.digibyte.ui.digiid.DigiIdScreen
import io.digibyte.ui.onboarding.*
import io.digibyte.ui.settings.*
import io.digibyte.ui.hub.ChatScreen
import io.digibyte.ui.hub.CreateThreadScreen
import io.digibyte.ui.hub.HubScreen
import io.digibyte.ui.hub.ThreadDetailScreen
import io.digibyte.ui.wallet.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Wallet : Screen("wallet", "Wallet", Icons.Default.Home)
    data object Hub : Screen("hub", "Hub", Icons.AutoMirrored.Filled.Chat)
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
    "unlock",
    "send",
    "receive",
    "transaction_detail/{txid}",
    "settings_security",
    "settings_network",
    "settings_display",
    "settings_about",
    "settings_view_seed",
    "assets",
    "asset_detail/{assetId}",
    "asset_send/{assetId}",
    "qr_scanner",
    "digiid_confirm/{uri}",
    "thread_detail/{threadId}",
    "create_thread"
)

@Composable
fun AppNavigation(
    walletManager: WalletManager,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    keyStoreManager: KeyStoreManager
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    // Observe wallet state to gate navigation
    val walletState by walletManager.walletState.collectAsState()

    // Determine start destination based on wallet state at launch
    val startDestination = remember(walletState) {
        when (walletState) {
            is WalletState.NoWallet -> "onboarding"
            is WalletState.Locked   -> "unlock"
            is WalletState.Unlocked -> Screen.Wallet.route
        }
    }

    // Helper: start SPV sync foreground service whenever the wallet is unlocked.
    val startSyncService: () -> Unit = {
        val intent = Intent(context, SyncService::class.java)
        ContextCompat.startForegroundService(context, intent)
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
        // Start sync service ONCE when the wallet is unlocked — not per-tab
        var syncStarted by remember { mutableStateOf(false) }
        LaunchedEffect(startDestination) {
            if (startDestination == "wallet" && !syncStarted) {
                syncStarted = true
                startSyncService()
            }
        }

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
                // Share ViewModel across onboarding flow so mnemonic persists between screens
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                SeedDisplayScreen(
                    navController = navController,
                    wordCount = wordCount,
                    viewModel = sharedViewModel
                )
            }

            composable("seed_verify") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                SeedVerifyScreen(navController = navController, viewModel = sharedViewModel)
            }

            composable("mnemonic_input") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                MnemonicInputScreen(navController = navController, viewModel = sharedViewModel)
            }

            composable("recovery_date") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                RecoveryDateScreen(navController = navController, viewModel = sharedViewModel)
            }

            composable("pin_setup") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                PinSetupScreen(
                    navController = navController,
                    biometricAuth = biometricAuth,
                    viewModel = sharedViewModel
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
                WalletScreen(
                    onNavigateSend = { navController.navigate("send") },
                    onNavigateReceive = { navController.navigate("receive") },
                    onNavigateScan = { navController.navigate("qr_scanner") },
                    onNavigateTx = { txid ->
                        navController.navigate("transaction_detail/${txid}")
                    },
                    onNavigateAssets = { navController.navigate("assets") }
                )
            }

            composable(Screen.Hub.route) {
                HubScreen(
                    onNavigateToThread = { threadId ->
                        navController.navigate("thread_detail/$threadId")
                    },
                    onNavigateCreateThread = {
                        navController.navigate("create_thread")
                    }
                )
            }

            // ── Forum: thread detail ───────────────────────────────────────
            composable("thread_detail/{threadId}") { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId")?.toIntOrNull() ?: 0
                ThreadDetailScreen(
                    threadId = threadId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ── Forum: create thread ───────────────────────────────────────
            composable("create_thread") {
                CreateThreadScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onThreadCreated = { threadId ->
                        navController.navigate("thread_detail/$threadId") {
                            popUpTo("create_thread") { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.DigiId.route) {
                DigiIdScreen(
                    onNavigateScan = { navController.navigate("qr_scanner") }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(navController = navController)
            }

            // ── Settings sub-screens ──────────────────────────────────────────
            composable("settings_security") {
                SecuritySettingsScreen(
                    navController = navController,
                    pinManager = pinManager,
                    biometricAuth = biometricAuth,
                    walletManager = walletManager
                )
            }

            composable("settings_network") {
                NetworkInfoScreen(navController = navController)
            }

            composable("settings_display") {
                DisplaySettingsScreen(navController = navController)
            }

            composable("settings_about") {
                AboutScreen(navController = navController)
            }

            composable("settings_view_seed") {
                SeedViewScreen(
                    navController = navController,
                    walletManager = walletManager,
                    keyStoreManager = keyStoreManager
                )
            }

            // ── Send flow ─────────────────────────────────────────────────────
            composable("send") {
                SendScreen(
                    biometricAuth = biometricAuth,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ── Receive flow ──────────────────────────────────────────────────
            composable("receive") {
                ReceiveScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ── Transaction detail ────────────────────────────────────────────
            composable("transaction_detail/{txid}") { backStackEntry ->
                val txid = backStackEntry.arguments?.getString("txid") ?: ""
                TransactionDetailScreen(
                    txid = txid,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ── Asset screens ─────────────────────────────────────────────────
            composable("assets") {
                AssetListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAsset = { assetId ->
                        navController.navigate("asset_detail/${assetId}")
                    }
                )
            }

            composable("asset_detail/{assetId}") { backStackEntry ->
                val assetId = backStackEntry.arguments?.getString("assetId") ?: ""
                AssetDetailScreen(
                    assetId = assetId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateSend = { id ->
                        navController.navigate("asset_send/${id}")
                    }
                )
            }

            composable("asset_send/{assetId}") { backStackEntry ->
                val assetId = backStackEntry.arguments?.getString("assetId") ?: ""
                AssetSendScreen(
                    assetId = assetId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ── QR Scanner (shared) ───────────────────────────────────────
            composable("qr_scanner") {
                val localContext = LocalContext.current
                QrScannerScreen(
                    onDigiId = { request ->
                        // URL-encode the raw digiid:// URI for safe nav arg transport
                        val encoded = Uri.encode(
                            "digiid://${request.callbackUrl
                                .removePrefix("https://")
                                .removePrefix("http://")}?x=${request.nonce}" +
                                if (request.isUnsecure) "&u=1" else ""
                        )
                        navController.navigate("digiid_confirm/$encoded") {
                            popUpTo("qr_scanner") { inclusive = true }
                        }
                    },
                    onDigiByteUri = { uri ->
                        // Navigate to send with pre-filled address (and optional amount)
                        navController.navigate("send") {
                            popUpTo("qr_scanner") { inclusive = true }
                        }
                        // The SendViewModel can be seeded via SavedStateHandle; for now
                        // navigate to send — pre-fill wiring is handled in SendViewModel.
                    },
                    onInvalidQr = { reason ->
                        Toast.makeText(localContext, reason, Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() }
                )
            }

            // ── Digi-ID confirm (after scan) ──────────────────────────────
            composable("digiid_confirm/{uri}") { backStackEntry ->
                val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
                val rawUri = Uri.decode(encodedUri)
                val request = DigiIdRequest.parse(rawUri)
                if (request == null) {
                    // Malformed — go back
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    DigiIdConfirmScreen(
                        request = request,
                        biometricAuth = biometricAuth,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
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
