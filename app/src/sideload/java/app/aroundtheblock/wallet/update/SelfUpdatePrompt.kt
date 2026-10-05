package app.aroundtheblock.wallet.update

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.aroundtheblock.wallet.BuildConfig
import app.aroundtheblock.wallet.core.AppUpdate
import app.aroundtheblock.wallet.core.UpdateChecker
import app.aroundtheblock.wallet.core.tor.TorManager
import app.aroundtheblock.wallet.core.tor.TorRoute
import app.aroundtheblock.wallet.di.NetworkModule
import app.aroundtheblock.wallet.ui.components.UpdateDialog
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * SIDELOAD builds only: once per launch, ask this app's releases repository whether a newer APK
 * exists, and offer it. The play flavor has an empty twin — Play updates the app itself, and its
 * policy forbids an app it distributes from updating any other way.
 *
 * The repository is BuildConfig.RELEASES_REPO, never the source repository: io.digibyte installs
 * already in the wild read that one's releases with no tag filter.
 */
@Composable
fun SelfUpdatePrompt(okHttpClient: OkHttpClient, torManager: TorManager) {
    val context = LocalContext.current
    var pendingUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) { "0" }
        // Opt-in beta channel (Settings). Default false: /releases/latest
        // excludes prereleases, so nobody is pushed an unverified build.
        val wantsBeta = context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)
            .getBoolean("beta_updates", false)
        // Tor on and not connected yet (it starts after unlock and takes a while to
        // bootstrap): requests are held and then refused rather than sent direct, so
        // a check made now would simply fail. Wait for Tor — or the announced
        // clearnet fallback, or the user turning Tor off — and check then.
        while (NetworkModule.torRoute(torManager) is TorRoute.Blocked) {
            delay(2_000L)
        }
        val update = UpdateChecker(okHttpClient, BuildConfig.RELEASES_REPO)
            .checkForUpdate(currentVersion, includePrereleases = wantsBeta)
        if (update != null) pendingUpdate = update
    }
    pendingUpdate?.let { update ->
        UpdateDialog(update = update, onDismiss = { pendingUpdate = null })
    }
}
