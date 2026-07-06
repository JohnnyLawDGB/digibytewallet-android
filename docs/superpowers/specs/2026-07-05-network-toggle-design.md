# Runtime Mainnet⇄Testnet Toggle + testnet26 peer wiring — Design

**Date:** 2026-07-05
**Status:** PROPOSAL — **pending user review.** Invasive C-core change (network handling); no implementation until approved.
**Repo:** `digibytewallet-android`. `Digi-Mobile` out of scope.

> **Best-judgment calls made while the user was away** (flag on review): dev-gated exposure (Option A);
> same seed for both networks; per-network namespaced state; **restart-to-apply** switch (not in-place);
> testnet peers via the C-core testnet DNS seeds + a hardcoded testnet node. See §7 open decisions.

## 1. Goal / scope

An in-app toggle to run the wallet on **mainnet or testnet26 at runtime**, so DigiDollar (and Taproot)
can be tested on-device without a separate build. Gated behind a **dev/advanced Settings section,
compiled only into debug + `digiTestnet` builds** — the mainnet Play-Store release never exposes it.
Includes wiring **testnet26 peer discovery** so the testnet network actually syncs.

## 2. Non-goals

- Release-facing network switching for end users (§ decision A — dev-gated only).
- A third network (regtest/signet) — mainnet + testnet only.
- Changing the seed model — **same BIP39 seed for both networks** (see §4.1).
- Simultaneous dual-network operation — one active network at a time.

## 3. Key facts

- Network is currently **compile-time** `BITCOIN_TESTNET`, threaded through ~5 C files
  (`BRAddress.c/.h` address versions + bech32 HRP `dgb`/`dgbt`; `BRKey.c` key→address versions;
  `BRMerkleBlock.c` genesis/difficulty; `BRPeerManager.c` chain magic/port/seeds/genesis/checkpoints),
  ~20 `#if` sites. Making it runtime is mechanical but touches the core's most fundamental behavior.
- **BIP39 seeds are network-agnostic.** This app's testnet uses the *same* `m/84'/20'` derivation path;
  only the address *encoding* differs. So one seed controls both networks' addresses.
- The app's peer discovery uses the **mainnet-focused digiscope seeder**, not the C core's built-in
  testnet DNS seeds — that's why the current testnet build won't sync without wiring.

## 4. Architecture

### 4.1 Seed — shared, one backup
The encrypted seed (`dgb_wallet_seed`) is **shared** across networks — the user keeps one recovery
phrase. Switching networks does NOT create or require a new key; it re-derives the *same* keys and
re-encodes them for the target network. (Cryptographic note: because the derivation path is identical,
a mainnet address and its testnet counterpart share a key — acceptable for a test wallet; different
chains mean no fund-mixing risk.)

### 4.2 C core — runtime `g_network`
Replace the compile-time `#if BITCOIN_TESTNET … #else … #endif` blocks with a runtime check:
- A global `static int g_isTestnet` + `void BRSetNetwork(int isTestnet)` / `int BRNetworkIsTestnet(void)`
  (a small new `BRNetwork.c/.h`, or in an existing low-level TU). Set ONCE at core init, before any
  wallet/peer-manager creation.
- Convert each `#if` site to `if (BRNetworkIsTestnet())`: `BRAddress` (version bytes + `BRBech32`
  HRP), `BRKey` (address/WIF versions), `BRMerkleBlock` (genesis/target), `BRPeerManager` (magic,
  default port, DNS seeds list, genesis, checkpoints). Where a `#if` selects a *constant*, make it a
  ternary on `BRNetworkIsTestnet()`.
- `BRBech32Encode/Decode` already take an HRP string in some paths; ensure the HRP passed is
  `BRNetworkIsTestnet() ? "dgbt" : "dgb"` everywhere addresses are built.
- Keep the compile-time `BITCOIN_TESTNET` default (`=0`) only as the *initial* value of `g_isTestnet`
  so behavior is unchanged until `BRSetNetwork` is called.

### 4.3 JNI + startup
- `NativeBridge.setNetwork(isTestnet: Boolean)` → `BRSetNetwork`. Called at process start (from
  `AppModule`/wallet-init) reading the persisted `dgb_network` pref, BEFORE the wallet/peer manager
  are created. Idempotent, must precede wallet load.

### 4.4 Per-network state (namespaced)
Everything chain-specific is stored per network so testnet never pollutes mainnet:
- SharedPreferences: suffix the network-specific keys (`dgb_sync_data`, `dgb_settings`,
  `last_balance`, `last_dd_balance`, saved blocks/peers/filter-headers, `dgb_bloom_peers`, reconcile
  state) with `_testnet` when testnet is active. **`dgb_wallet_seed` + `dgb_pin_store` stay shared.**
- Room DB: a separate DB file per network (`fallbackToDestructiveMigration` already set) — e.g.
  `dgb.db` vs `dgb_testnet.db`, selected at `provideDatabase` time from the network pref.
- A single `dgb_network` pref (in a network-independent store) holds the active selection.

### 4.5 Switch flow — restart to apply
The toggle **persists** the new `dgb_network` value, shows "Restart required to switch network," and
the app re-initializes on next launch with the selected network (BRSetNetwork → per-network storage →
wallet load → sync the target chain). Restart-to-apply avoids a fragile in-process teardown of the
wallet + peer manager + native chain state mid-run (which risks the v3.7.1-class UAF races). (An
in-place switch is possible later if desired — §7.)

### 4.6 testnet26 chain params — MUST be refreshed (the core's are a DEAD old testnet)
**Critical finding (2026-07-06):** the core's `BRTestNetParams` points at a dead old testnet
(genesis `2a0f89ab…`/port 12026/magic `0xeeb791d1`) and `jni_peer.c:690` HARDCODES
`&BRMainNetParams` — so the toggle is NOT just a `#if`→runtime refactor; `BRTestNetParams` must be
**refreshed to the live testnet26** and the peer manager must SELECT it at runtime. Correct values
(from my synced v9.26.3 node — see reference memory `reference_testnet26_chain_params`):
- genesis[0]: `0c9af936f28f7bd0e90c8f6235399063a026ed267bb53da398313b5d7aa55d82`, time 1780156800, bits 0x1e0ffff0
- port **12033**; magic **`0xe2b8d1fc`** (inferred `fc d1 b8 e2`→LE; **verify by connecting**)
- DNS seeds `testnetseed.digibyte.io/.link/.services`; checkpoint height 80000 `66b32ade…` (time 1783178076, bits 0x1e020dd4)
- hardcode public peers `164.68.98.125:12033` / `129.212.182.152:12033` as fallback.

When `BRNetworkIsTestnet()`: the peer manager is created with the refreshed `BRTestNetParams` (its
DNS seeds + magic + port) instead of the digiscope bloom seeder path, plus the hardcoded testnet
peers injected so first sync is reliable. The mainnet path (digiscope seeder + `digiscope.me`
priority peer) is unchanged.

### 4.7 UI
Settings → an **Advanced / Developer** section (rendered only when `BuildConfig.DEBUG` or the
`digiTestnet` flavor) → a **Network** toggle (Mainnet / Testnet), current value from `dgb_network`.
Flipping it: confirm dialog ("Switch to Testnet? The app will restart and sync the test chain. Your
recovery phrase is unchanged.") → persist → restart. A small "TESTNET" banner/badge on the wallet
screen while testnet is active (so it's never ambiguous which network you're on).

## 5. Data flow (switch)
toggle → confirm → write `dgb_network=testnet` → restart → `AppModule` reads pref → `setNetwork(true)`
→ `provideDatabase()` opens `dgb_testnet.db` → wallet loads testnet-encoded addresses from the shared
seed → SyncService uses testnet seeds/peer → syncs testnet26 → DD features work on-device.

## 6. Safety / testing

- **Same seed, isolated state** — no fund confusion; mainnet state untouched while on testnet.
- **Dev-gated** — the mainnet release is byte-for-byte unaffected (the toggle + testnet code paths are
  behind the debug/flavor gate; `g_isTestnet` defaults to 0).
- **Explicit + confirmed switch** with a persistent TESTNET badge.
- **Testing:** a C-core host KAT that, from ONE build, sets `BRSetNetwork(0/1)` and asserts an address
  round-trips as `dgb1…` vs `dgbt1…` from the same key (proves the runtime switch); the mainnet
  address KATs still pass with the default; 42 security tests green; on-device: toggle → syncs
  testnet26 → the DD send UI drives a real transfer (the C path is already proven).

## 7. Open decisions (for review)
1. **Exposure:** dev-gated (recommended, assumed) vs release-facing. (§ decision A)
2. **Switch UX:** restart-to-apply (recommended, assumed) vs in-place live switch (more work/risk).
3. **Testnet priority peer:** which reachable 9.26 testnet26 node to hardcode (johnnylaw `192.168.7.59`
   is LAN-only; need a public one — or rely on the testnet DNS seeds alone).
4. **State on first switch:** fresh per-network state (assumed) vs migrating anything.

## 8. Suggested phase order (for the plan)
A. **Refresh `BRTestNetParams` to live testnet26** (genesis `0c9af936…`, port 12033, magic
   `0xe2b8d1fc`, `testnetseed.digibyte.*` seeds, height-80000 checkpoint) + fix the param-selection
   sites (`jni_peer.c:690`, `BRPeerManager` testnet-manager creators) to pick by `BRNetworkIsTestnet()`.
   Verify the magic by actually connecting to testnet26 peers.
B. C core: `BRSetNetwork`/`BRNetworkIsTestnet` + convert the ~15 `#if BITCOIN_TESTNET` address/key/merkle
   sites to runtime — host KAT (both networks' addresses from one build).
C. JNI `setNetwork` + startup wiring (read `dgb_network` before wallet init).
D. Per-network state namespacing (prefs suffix + per-network DB), shared seed/PIN.
E. testnet26 peer injection (hardcoded public peers `164.68.98.125:12033` / `129.212.182.152:12033`).
F. Settings Advanced network toggle (dev-gated) + confirm + restart + TESTNET badge.
