package app.aroundtheblock.wallet.update

import androidx.compose.runtime.Composable
import app.aroundtheblock.wallet.core.tor.TorManager
import okhttp3.OkHttpClient

/**
 * PLAY builds: nothing. Google Play updates the app, and Play's policy forbids an app it
 * distributes from updating itself any other way — so the GitHub check, its dialog and the
 * APK download link exist only in the sideload flavor (src/sideload), not here.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SelfUpdatePrompt(okHttpClient: OkHttpClient, torManager: TorManager) = Unit
