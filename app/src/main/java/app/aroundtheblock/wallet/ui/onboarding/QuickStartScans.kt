package app.aroundtheblock.wallet.ui.onboarding

import android.content.Context
import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import app.aroundtheblock.wallet.core.recovery.DerivationProfile
import app.aroundtheblock.wallet.core.recovery.ReconcileBackendUtxoSource
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.wallets.QuickStart
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Where a restored wallet's quick start asks for its balance. */
enum class QuickStartSource { OWN_NODE, DIGISCOPE }

/** Which choices the screen offers. Own node only when one is set; DigiScope always, as an explicit, disclosed opt-in. */
internal fun quickStartChoices(customEndpoint: String?): List<QuickStartSource> =
    if (customEndpoint.isNullOrBlank()) listOf(QuickStartSource.DIGISCOPE)
    else listOf(QuickStartSource.OWN_NODE, QuickStartSource.DIGISCOPE)

/** The host (and a non-default port) of [url], for the screen: never its credentials, path or query. */
internal fun quickStartHost(url: String): String? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return null
    return if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) "${parsed.host}:${parsed.port}" else parsed.host
}

/** What the quick start screen offers: its choices, and the own node's host when one is set. */
data class QuickStartOffer(val choices: List<QuickStartSource>, val ownNodeHost: String?)

/** The quick start as the screen shows it. */
sealed class QuickStartUi {
    data object Idle : QuickStartUi()
    data object Running : QuickStartUi()
    /** Display only: never spendable, never in the balance (see [QuickStart]). */
    data class Found(val quickStart: QuickStart) : QuickStartUi()
    data object Failed : QuickStartUi()
}

/**
 * Builds the quick start's lookup for a source, on the shared client (Tor routing, DigiScope pins).
 * Only this wallet's own address formats are asked about: the native profiles, which are what its
 * compact-filter sync will prove.
 */
class QuickStartScans @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    /** The app's reconcile client: its endpoint is the own node whenever one is set. */
    private val nodeClient: DgbNodeClient,
) {
    /** The "Use my own node" endpoint of Scan for missing funds, or null. Reads prefs: off the main thread. */
    fun customEndpoint(): String? = nodeClient.customEndpoint()

    /**
     * The lookup for [source] and the name of the server it asks, or null when it cannot be made:
     * an own-node lookup with no own node set is refused, never sent to DigiScope instead.
     */
    fun serviceFor(source: QuickStartSource): Pair<RecoveryScanService, String>? {
        val node: DgbNodeClient
        val label: String
        when (source) {
            QuickStartSource.OWN_NODE -> {
                node = nodeClient
                label = nodeClient.customEndpoint()?.let(::quickStartHost) ?: return null
            }
            QuickStartSource.DIGISCOPE -> {
                // Never the own node, whatever is set: the user addressed this to DigiScope.
                node = DgbNodeClient.digiScopeOnly(context, client)
                label = DIGISCOPE_LABEL
            }
        }
        val service = RecoveryScanService(
            ReconcileBackendUtxoSource(node),
            profiles = DerivationProfile.BUILT_INS.filter { it.isNative },
        )
        return service to label
    }

    companion object {
        const val DIGISCOPE_LABEL = "digiscope.me"
    }
}
