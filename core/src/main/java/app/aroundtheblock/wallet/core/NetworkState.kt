package app.aroundtheblock.wallet.core

import android.content.Context

/**
 * Runtime mainnet/testnet namespace helper for per-network chain state.
 *
 * Reads the SAME network-independent `dgb_settings` store that
 * [app.aroundtheblock.wallet.DigiByteApp]'s `applyNetworkSelection()` reads at process
 * start to pick `BRSetNetwork` (via `NativeBridge.setNetwork`). `dgb_settings`
 * itself is NEVER suffixed by this helper — it holds the network-selection
 * pref (`dgb_network_testnet`) itself, so suffixing it would make the
 * selector unreadable without already knowing which selection to read.
 *
 * Store names do NOT come from here: every prefs file, file and DB name of anything
 * chain-specific — synced blocks/peers/filter-headers, balance snapshots, bloom/dandelion
 * peer caches, reconcile bookkeeping, the Room DB — is resolved by
 * [app.aroundtheblock.wallet.core.wallets.storageSuffix] (through
 * [app.aroundtheblock.wallet.core.wallets.WalletStores]), so a testnet session never reads or
 * overwrites mainnet data, and vice versa.
 *
 * Never network-suffixed: `dgb_wallet_seed` (per wallet, see WalletStores), `dgb_pin_store`,
 * `dgb_db_key`, `dgb_settings` (holds the network selection itself),
 * `dgb_hub_session` (Hub account JWT, encrypted — user account, not chain state; the
 * legacy plaintext `dgb_digiscope` copy exists only until HubTokenStore migrates it).
 *
 * Default (pref absent, or explicitly false = mainnet) yields the empty network suffix — every
 * suffixed name is IDENTICAL to its pre-toggle name, so existing mainnet
 * installs are byte-for-byte unaffected until a user opts into testnet.
 */
private const val NETWORK_SETTINGS_PREFS = "dgb_settings"
private const val KEY_NETWORK_TESTNET = "dgb_network_testnet"

/** `true` when the persisted network selection is testnet, else `false` (mainnet default). */
fun isTestnet(context: Context): Boolean =
    context.getSharedPreferences(NETWORK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_NETWORK_TESTNET, false)
