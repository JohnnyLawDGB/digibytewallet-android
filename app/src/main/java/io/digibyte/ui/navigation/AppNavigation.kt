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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import io.digibyte.BootGuard
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiByteUri
import io.digibyte.core.security.BiometricAuth
import io.digibyte.core.security.KeyStoreManager
import io.digibyte.core.security.PinManager
import io.digibyte.game.DigiRunnerGame
import io.digibyte.service.SyncService
import io.digibyte.ui.asset.*
import io.digibyte.ui.components.QrScannerScreen
import io.digibyte.ui.digiid.DigiIdConfirmScreen
import io.digibyte.ui.digiid.DigiIdScreen
import io.digibyte.ui.hub.DigiRunnerLeaderboardScreen
import io.digibyte.ui.onboarding.*
import io.digibyte.ui.settings.*
import io.digibyte.ui.hub.ChatScreen
import io.digibyte.ui.hub.CreateThreadScreen
import io.digibyte.ui.hub.HubScreen
import androidx.compose.material.icons.filled.Storefront
import io.digibyte.ui.hub.ThreadDetailScreen
import io.digibyte.ui.wallet.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Wallet : Screen("wallet", "Wallet", Icons.Default.Home)
    data object Hub : Screen("hub", "Hub", Icons.AutoMirrored.Filled.Chat)
    data object DigiId : Screen("digiid", "Digi-ID", Icons.Default.Fingerprint)
    data object Digistamp : Screen("digistamp", "Assets", Icons.Default.Storefront)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens =
    listOf(Screen.Wallet, Screen.Hub, Screen.Digistamp, Screen.DigiId, Screen.Settings)

/** Routes that should NOT show the bottom navigation bar. */
private val fullScreenRoutes = setOf(
    "onboarding",
    "seed_display/{wordCount}",
    "seed_verify",
    "mnemonic_input",
    "recovery_scan",
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
    "settings_reconcile",
    "settings_view_seed",
    "assets",
    "asset_detail/{assetId}",
    "asset_send/{assetId}",
    "qr_scanner",
    "digiid_confirm/{uri}",
    "node_pair_confirm/{uri}",
    "thread_detail/{threadId}",
    "create_thread",
    "digirunner",
    "digirunner_leaderboard",
    "recover_funds"
)

@Composable
fun AppNavigation(
    walletManager: WalletManager,
    pinManager: PinManager,
    biometricAuth: BiometricAuth,
    keyStoreManager: KeyStoreManager,
    digiScopeClient: DigiScopeClient
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    // Observe wallet state to gate navigation
    val walletState by walletManager.walletState.collectAsStateWithLifecycle()

    // Set (once, below) when the lost-PIN branch is taken: wallet exists on
    // disk but PIN was lost. Consumed by the pin_setup composable, which
    // performs the actual restoreFromDisk() call off the main thread — see
    // the comment there for why it can't happen synchronously here.
    val lostPinRestorePending = remember { mutableStateOf(false) }

    // Determine start destination ONCE at launch — do not recompute
    // when walletState changes mid-session (causes double PIN prompt).
    val startDestination = remember {
        val walletState = walletManager.walletState.value
        val hasPin = try { pinManager.hasPin() } catch (e: Exception) {
            android.util.Log.e("AppNavigation", "hasPin() failed: ${e.message}")
            false
        }
        val dest = when (walletState) {
            is WalletState.NoWallet -> "onboarding"
            is WalletState.Locked   -> {
                if (hasPin) {
                    "unlock"
                } else {
                    // Wallet exists but PIN was lost. Route to pin_setup to set
                    // a new PIN — the wallet is already on disk, don't go to
                    // onboarding. restoreFromDisk() must NOT run here: it calls
                    // NativeBridge.stopSync(), which takes the native PEER_GUARD
                    // lock and can block for many seconds inside the keepalive
                    // sweep — doing that synchronously inside this remember{}
                    // (i.e. during composition, on the main thread) is the exact
                    // ANR this fix removes. The pin_setup composable performs
                    // the restore off the main thread instead.
                    android.util.Log.w("AppNavigation", "Wallet exists but no PIN — routing to pin_setup, restore deferred off-main")
                    lostPinRestorePending.value = true
                    "pin_setup"
                }
            }
            is WalletState.Unlocked -> if (!hasPin) "pin_setup" else Screen.Wallet.route
        }
        android.util.Log.i("AppNavigation", "startDestination=$dest walletState=$walletState hasPin=$hasPin")
        dest
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
        // Start sync service when wallet screen is reached — from any path
        var syncStarted by remember { mutableStateOf(false) }
        LaunchedEffect(currentRoute) {
            if (currentRoute == Screen.Wallet.route && !syncStarted) {
                syncStarted = true
                startSyncService()
            }
            // Handle pending Digi-ID deep link after reaching wallet screen
            if (currentRoute == Screen.Wallet.route) {
                val activity = context as? io.digibyte.MainActivity
                val pendingUri = activity?.pendingDigiIdUri
                if (pendingUri != null) {
                    activity.pendingDigiIdUri = null
                    val encoded = java.net.URLEncoder.encode(pendingUri, "UTF-8")
                    navController.navigate("digiid_confirm/$encoded")
                }
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

            composable("recovery_scan") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                io.digibyte.ui.onboarding.RecoveryScanScreen(
                    navController = navController, viewModel = sharedViewModel
                )
            }

            composable("recovery_date") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("onboarding")
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(parentEntry)
                RecoveryDateScreen(navController = navController, viewModel = sharedViewModel)
            }

            composable("pin_setup") { backStackEntry ->
                // Get the shared onboarding ViewModel if we came from the onboarding
                // flow. If pin_setup is the startDestination (wallet exists but no PIN),
                // "onboarding" won't be on the back stack — use own entry instead.
                val hasOnboarding = remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry("onboarding") }.isSuccess
                }
                val vmEntry = remember(backStackEntry) {
                    if (hasOnboarding) navController.getBackStackEntry("onboarding")
                    else backStackEntry
                }
                val sharedViewModel: io.digibyte.ui.onboarding.OnboardingViewModel = hiltViewModel(vmEntry)

                // Lost-PIN path (see startDestination above): wallet exists on disk
                // but PIN was lost. Perform the restore off the main thread here —
                // restoreFromDisk() calls NativeBridge.stopSync(), which takes the
                // native PEER_GUARD lock and can block for seconds inside the
                // keepalive sweep (the confirmed ANR trace). A brief loading state
                // is shown instead of PinSetupScreen until the restore completes,
                // so PinSetupScreen's own isWalletLoaded() check (which decides
                // "wallet already exists, skip creation" vs. "create a new wallet")
                // can never race an in-flight restore and create a duplicate wallet.
                var restoring by remember { mutableStateOf(lostPinRestorePending.value) }
                if (restoring) {
                    LaunchedEffect(Unit) {
                        // Restore-crash bracket (see UnlockScreen's unlock path for the
                        // same pattern + rationale): armed at this call site rather than
                        // inside WalletManager.restoreFromDisk() itself, since BootGuard
                        // (app module) isn't importable from core. This IS the start of
                        // the post-unlock (lost-PIN) restore.
                        BootGuard.beginRestore(context)
                        withContext(Dispatchers.IO) { walletManager.restoreFromDisk() }
                        lostPinRestorePending.value = false
                        restoring = false
                    }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    PinSetupScreen(
                        navController = navController,
                        biometricAuth = biometricAuth,
                        viewModel = sharedViewModel
                    )
                }
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
                    onNavigateAssets = { navController.navigate("assets") },
                    onNavigateNetworkInfo = { navController.navigate("settings_network") },
                    // Abandoned-band banner action — the reconcile screen hosts BOTH
                    // recovery paths ("Scan for missing transactions" and "Full
                    // rebuild from chain").
                    // autostart=true: the banner's button is labelled "Scan for missing
                    // transactions", so tapping it must START the scan, not just open the
                    // screen that has another button on it.
                    onNavigateReconcile = { navController.navigate("settings_reconcile?autostart=true") }
                )
            }

            composable(Screen.Digistamp.route) {
                io.digibyte.ui.digistamp.DigistampScreen(
                    // A page can ask for a wallet screen; it cannot ask for an action. Whatever
                    // opens here builds its own confirmation from data the wallet verified.
                    onWalletAction = { uri ->
                        android.util.Log.i("Digistamp", "wallet action requested: ${uri.scheme}")
                    },
                )
            }

            composable(Screen.Hub.route) {
                HubScreen(
                    onNavigateToThread = { threadId ->
                        navController.navigate("thread_detail/$threadId")
                    },
                    onNavigateCreateThread = {
                        navController.navigate("create_thread")
                    },
                    onPlayDigiRunner = { navController.navigate("digirunner") },
                    onViewLeaderboard = { navController.navigate("digirunner_leaderboard") },
                    digiScopeClient = digiScopeClient
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

            // ── DigiRunner: standalone game ───────────────────────────────
            composable("digirunner") {
                val gameContext = LocalContext.current
                val coroutineScope = rememberCoroutineScope()

                Box(modifier = Modifier.fillMaxSize()) {
                    DigiRunnerGame(
                        standalone = true,
                        onScoreSubmit = { score, distance, coins, livesRemaining ->
                            coroutineScope.launch {
                                val ok = digiScopeClient.submitDigiRunnerScore(
                                    score, distance, coins, livesRemaining
                                )
                                val msg = if (ok) "Score submitted!" else "Score submit failed — are you logged in?"
                                Toast.makeText(gameContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onShowLeaderboard = {
                            navController.navigate("digirunner_leaderboard")
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Close button overlay
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close game",
                            tint = Color.White
                        )
                    }
                }
            }

            // ── DigiRunner: leaderboard ───────────────────────────────────
            composable("digirunner_leaderboard") {
                DigiRunnerLeaderboardScreen(
                    digiScopeClient = digiScopeClient,
                    onNavigateBack = { navController.popBackStack() }
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
                NetworkInfoScreen(
                    navController = navController,
                    onScanNode = { navController.navigate("qr_scanner") }
                )
            }

            composable("settings_display") {
                DisplaySettingsScreen(navController = navController)
            }

            composable("settings_about") {
                AboutScreen(navController = navController)
            }

            composable(
                "settings_reconcile?autostart={autostart}",
                arguments = listOf(navArgument("autostart") {
                    type = NavType.BoolType; defaultValue = false
                }),
            ) { backStackEntry ->
                io.digibyte.ui.settings.ReconcileScreen(
                    navController = navController,
                    autoStart = backStackEntry.arguments?.getBoolean("autostart") ?: false,
                )
            }

            composable("recover_funds") {
                io.digibyte.ui.recovery.RecoverFundsScreen(navController)
            }

            composable("settings_view_seed") {
                SeedViewScreen(
                    navController = navController,
                    walletManager = walletManager,
                    keyStoreManager = keyStoreManager
                )
            }

            // ── Send flow ─────────────────────────────────────────────────────
            // Share the Wallet route's WalletViewModel so peerCount/syncState
            // are live on entry — preventing the "not connected" banner from
            // flashing while a fresh VM's 5s poll catches up.
            composable(
                "send?address={address}",
                arguments = listOf(navArgument("address") { defaultValue = "" })
            ) { backStackEntry ->
                val prefillAddress = backStackEntry.arguments?.getString("address") ?: ""
                val walletEntry = remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Screen.Wallet.route) }
                        .getOrDefault(backStackEntry)
                }
                val sharedWalletVm: WalletViewModel = hiltViewModel(walletEntry)
                SendScreen(
                    biometricAuth = biometricAuth,
                    onNavigateBack = { navController.popBackStack() },
                    prefillAddress = prefillAddress,
                    onScanQr = { callback ->
                        navController.navigate("qr_scanner")
                    },
                    walletViewModel = sharedWalletVm
                )
            }

            // ── Receive flow ──────────────────────────────────────────────────
            composable("receive") { backStackEntry ->
                val walletEntry = remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Screen.Wallet.route) }
                        .getOrDefault(backStackEntry)
                }
                val sharedWalletVm: WalletViewModel = hiltViewModel(walletEntry)
                ReceiveScreen(
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = sharedWalletVm
                )
            }

            // ── Transaction detail ────────────────────────────────────────────
            composable("transaction_detail/{txid}") { backStackEntry ->
                val txid = backStackEntry.arguments?.getString("txid") ?: ""
                val walletEntry = remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Screen.Wallet.route) }
                        .getOrDefault(backStackEntry)
                }
                val sharedWalletVm: WalletViewModel = hiltViewModel(walletEntry)
                TransactionDetailScreen(
                    txid = txid,
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = sharedWalletVm
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

            composable(
                "asset_send/{assetId}?address={address}&quantity={quantity}",
                arguments = listOf(
                    navArgument("address") { defaultValue = "" },
                    navArgument("quantity") { defaultValue = "" },
                )
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getString("assetId") ?: ""
                val prefillAddress = backStackEntry.arguments?.getString("address") ?: ""
                val prefillQuantity = backStackEntry.arguments?.getString("quantity") ?: ""
                AssetSendScreen(
                    assetId = assetId,
                    onNavigateBack = { navController.popBackStack() },
                    prefillAddress = prefillAddress,
                    prefillQuantity = prefillQuantity,
                    onScanQr = {
                        navController.navigate(
                            "qr_scanner?returnTo=" + Uri.encode("asset_send/$assetId")
                        )
                    }
                )
            }

            // ── QR Scanner (shared) ───────────────────────────────────────
            composable(
                "qr_scanner?returnTo={returnTo}",
                arguments = listOf(navArgument("returnTo") { defaultValue = "" })
            ) { backStackEntry ->
                val localContext = LocalContext.current
                val returnTo = backStackEntry.arguments?.getString("returnTo") ?: ""
                QrScannerScreen(
                    onDigiId = { request ->
                        val encoded = Uri.encode(request.rawUri)
                        navController.navigate("digiid_confirm/$encoded") {
                            popUpTo("qr_scanner") { inclusive = true }
                        }
                    },
                    onDigiByteUri = { uri ->
                        // Route the scanned address back to the caller: the asset
                        // send screen when returnTo is set, else the DGB send flow.
                        val encoded = Uri.encode(uri.address)
                        // An asset transfer request names what to send, so it goes to that
                        // asset's send screen rather than the DGB flow — which would
                        // otherwise silently drop the asset and prompt for a coin payment.
                        val assetId = uri.assetId
                        val dest = when {
                            assetId != null -> "asset_send/${Uri.encode(assetId)}" +
                                "?address=$encoded&quantity=${uri.assetAmount}"
                            returnTo.isNotBlank() -> "$returnTo?address=$encoded"
                            else -> "send?address=$encoded"
                        }
                        navController.navigate(dest) {
                            popUpTo("qr_scanner") { inclusive = true }
                        }
                    },
                    onNode = { raw ->
                        navController.navigate("node_pair_confirm/${Uri.encode(raw)}") {
                            popUpTo("qr_scanner") { inclusive = true }
                        }
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

            // ── Own-node pairing confirm (after scanning dgbnode://) ──────
            composable("node_pair_confirm/{uri}") { backStackEntry ->
                val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
                val rawUri = Uri.decode(encodedUri)
                NodePairConfirmScreen(
                    rawUri = rawUri,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
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
