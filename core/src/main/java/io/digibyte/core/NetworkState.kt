package io.digibyte.core

import android.content.Context

/**
 * Runtime mainnet/testnet namespace helper for per-network chain state.
 *
 * Reads the SAME network-independent `dgb_settings` store that
 * [io.digibyte.DigiByteApp]'s `applyNetworkSelection()` reads at process
 * start to pick `BRSetNetwork` (via `NativeBridge.setNetwork`). `dgb_settings`
 * itself is NEVER suffixed by this helper — it holds the network-selection
 * pref (`dgb_network_testnet`) itself, so suffixing it would make the
 * selector unreadable without already knowing which selection to read.
 *
 * Callers append [networkSuffix] to the SharedPreferences FILE name (or the
 * Room DB file name) of anything chain-specific — synced blocks/peers/
 * filter-headers, balance snapshots, bloom/dandelion peer caches, reconcile
 * bookkeeping, the Room DB — so a testnet session never reads or overwrites
 * mainnet data, and vice versa.
 *
 * Left shared / NEVER suffixed: `dgb_wallet_seed`, `dgb_pin_store`,
 * `dgb_db_key`, `dgb_settings` (holds the network selection itself),
 * `dgb_digiscope` (Hub account JWT — user account, not chain state).
 *
 * Default (pref absent, or explicitly false = mainnet) yields `""` — every
 * suffixed name is IDENTICAL to its pre-toggle name, so existing mainnet
 * installs are byte-for-byte unaffected until a user opts into testnet.
 */
private const val NETWORK_SETTINGS_PREFS = "dgb_settings"
private const val KEY_NETWORK_TESTNET = "dgb_network_testnet"

/** `"_testnet"` when the persisted network selection is testnet, else `""`. */
fun networkSuffix(context: Context): String =
    if (context.getSharedPreferences(NETWORK_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NETWORK_TESTNET, false)
    ) "_testnet" else ""
