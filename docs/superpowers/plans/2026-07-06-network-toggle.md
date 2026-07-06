# Runtime Mainnet⇄Testnet Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An in-app (dev-gated) toggle to run the wallet on mainnet or the live testnet26 at runtime, enabling on-device DigiDollar testing.

**Architecture:** Refresh the core's stale testnet chain params to testnet26; make network selection runtime (`BRSetNetwork`/`BRNetworkIsTestnet`) across the ~15 compile-time `#if BITCOIN_TESTNET` sites + the peer-manager param selection; namespace wallet state per network (shared seed); a dev-gated Settings toggle that persists the choice and restarts.

**Tech Stack:** C (digibytewallet-core submodule), JNI, Kotlin, Compose.

**Source of truth:** `docs/superpowers/specs/2026-07-05-network-toggle-design.md`; params in memory `reference_testnet26_chain_params`.

## Global Constraints

- **Dev-gated:** the toggle UI + code paths compile/enable ONLY in debug + `digiTestnet` builds (`BuildConfig.DEBUG` / flavor). The mainnet Play-Store release is behavior-identical to today — `g_isTestnet` defaults to 0.
- **Same BIP39 seed both networks** — `dgb_wallet_seed` + `dgb_pin_store` are SHARED; only chain state is per-network.
- **Live testnet26 params (verified from my synced node):** genesis[0] `0c9af936f28f7bd0e90c8f6235399063a026ed267bb53da398313b5d7aa55d82` (time 1780156800, bits 0x1e0ffff0), **port 12033**, **magic `0xe2b8d1fc`**, DNS seeds `testnetseed.digibyte.io/.link/.services`, checkpoint height 80000 `66b32adec9b7eeecfae28899679a64e5994aee5babe75fc881d0ef07c0e16f85` (time 1783178076, bits 0x1e020dd4), public peers `164.68.98.125:12033` / `129.212.182.152:12033`.
- **Restart-to-apply** the switch (no fragile in-process teardown of wallet + peer manager).
- **No mainnet regression:** existing mainnet address/sync/DGB/DD paths unchanged; 42 security tests green; `:app:assembleMainnetDebug` green.
- **Submodule commits** (`BRChainParams.h`, `BRNetwork.*`, `BRAddress`, `BRKey`, `BRMerkleBlock`) via `git -C native/src/main/jni/digibytewallet-core commit -F -`; root commits for JNI/Kotlin/UI + pin bump.

---

## Task 1: Refresh `BRTestNetParams` to testnet26 + runtime network foundation + param selection

**Files:**
- Create: `native/.../digibytewallet-core/BRNetwork.h` + `BRNetwork.c`
- Modify: `native/.../digibytewallet-core/BRChainParams.h` (testnet seeds/checkpoints/params)
- Modify: `native/src/main/jni/bridge/jni_peer.c:690` + `native/.../BRPeerManager.c` testnet-manager creators (`:3206`, `:3221`)
- Create: `native/src/test/host/network_switch_kat/` (host KAT — see Task 2; Task 1 adds the runtime-switch scaffold)

**Interfaces:**
- Produces: `void BRSetNetwork(int isTestnet);` `int BRNetworkIsTestnet(void);` (from `BRNetwork.h`); a testnet26-correct `BRTestNetParams`.

- [ ] **Step 1: Add the network global.** `BRNetwork.h`:
```c
#ifndef BRNetwork_h
#define BRNetwork_h
#ifdef __cplusplus
extern "C" {
#endif
// Runtime network selection. Set ONCE at core init before any wallet/peer-manager creation.
// Defaults to mainnet (0) so mainnet builds are unchanged until BRSetNetwork is called.
void BRSetNetwork(int isTestnet);
int  BRNetworkIsTestnet(void);
#ifdef __cplusplus
}
#endif
#endif
```
`BRNetwork.c`:
```c
#include "BRNetwork.h"
static int g_isTestnet = 0;
void BRSetNetwork(int isTestnet) { g_isTestnet = isTestnet ? 1 : 0; }
int  BRNetworkIsTestnet(void)    { return g_isTestnet; }
```
Add both to `native/CMakeLists.txt` (after `BRDigiDollar.c/.h`).

- [ ] **Step 2: Refresh the testnet DNS seeds** in `BRChainParams.h` (replace `BRTestNetDNSSeeds`):
```c
static const char *BRTestNetDNSSeeds[] = {
    "testnetseed.digibyte.io", "testnetseed.digibyte.link", "testnetseed.digibyte.services", NULL
};
```

- [ ] **Step 3: Refresh the testnet checkpoints** — replace `BRTestNetCheckpoints[]` with genesis + height-80000, **matching the existing `BRCheckPoint` field format** (the current testnet[0] uses a bare hex string for `.hash`; keep that form):
```c
static const BRCheckPoint BRTestNetCheckpoints[] = {
    {     0, "0c9af936f28f7bd0e90c8f6235399063a026ed267bb53da398313b5d7aa55d82", 1780156800, 0x1e0ffff0 },
    { 80000, "66b32adec9b7eeecfae28899679a64e5994aee5babe75fc881d0ef07c0e16f85", 1783178076, 0x1e020dd4 }
};
```

- [ ] **Step 4: Refresh the params struct** — in `BRTestNetParams`, change `standardPort` `12026`→`12033` and `magicNumber` `0xeeb791d1`→`0xe2b8d1fc`. Leave `BRTestNetVerifyDifficulty` + the checkpoint count expression.

- [ ] **Step 5: Runtime param selection.** In `jni_peer.c` (add `#include "BRNetwork.h"`), change `:690`:
```c
const BRChainParams *params = BRNetworkIsTestnet() ? &BRTestNetParams : &BRMainNetParams;
```
Grep `jni_peer.c` + `jni_wallet.c` for every `&BRMainNetParams` used to create the peer manager and apply the same runtime selection. (The `BRPeerManagerNewTestNet*` convenience fns in `BRPeerManager.c:3206/3221` already pass `&BRTestNetParams`; leave them — the JNI creates the manager directly.)

- [ ] **Step 6: Verify — build + connect.** `./gradlew :app:assembleMainnetDebug` green (mainnet default unaffected). Then **empirically confirm the magic**: build a tiny host program (or a temporary `BRSetNetwork(1)` + `BRPeerManagerNewEx(&BRTestNetParams, …)` harness) that connects to `164.68.98.125:12033` and asserts the version handshake succeeds (magic accepted). If the peer immediately disconnects on the version message, the magic is wrong — re-derive from a 9.26 `pchMessageStart`. **This step validates the whole feature's premise.**

- [ ] **Step 7: Commit.** Submodule (`BRNetwork.*`, `BRChainParams.h`) + root (`jni_peer.c`, CMakeLists, pin bump).

---

## Task 2: Convert address/key/difficulty `#if BITCOIN_TESTNET` → runtime + host KAT

**Files:**
- Modify: `BRAddress.c`/`.h`, `BRKey.c`, `BRMerkleBlock.c` (submodule)
- Create: `native/src/test/host/network_switch_kat/network_switch_kat_main.c` + `run.sh`

**Interfaces:** Consumes `BRNetworkIsTestnet()` (Task 1).

- [ ] **Step 1: Write the failing KAT.** A host test that, from ONE build, derives an address from a fixed key and asserts it round-trips as `dgb1…` under `BRSetNetwork(0)` and `dgbt1…` under `BRSetNetwork(1)` (and legacy base58 version flips):
```c
// pseudocode: same pubkey -> BRSetNetwork(0) -> BRKeyAddress == dgb1…; BRSetNetwork(1) -> dgbt1…
```
(Model the harness on the existing address KATs; assert both HRPs + that `BRAddressIsValid` accepts the matching-network form and rejects the other.)

- [ ] **Step 2: Convert each `#if` site to runtime.** Pattern: `X = mainnet; #if BITCOIN_TESTNET X = testnet; #endif`  →  `X = BRNetworkIsTestnet() ? testnet : mainnet;`. Sites (add `#include "BRNetwork.h"` to each TU):
  - `BRAddress.c` ~275/284/293/326/339 (base58 version bytes → `BRNetworkIsTestnet() ? BITCOIN_*_TEST : DIGIBYTE_*`), ~304 (`BRBech32Encode(a, BRNetworkIsTestnet() ? "dgbt" : "dgb", script)`), ~367 (`bech32Prefix`), ~376/430 (decode: pick the version bytes + HRP by `BRNetworkIsTestnet()`).
  - `BRAddress.h`: replace the `#if BITCOIN_TESTNET`-defined `DIGIBYTE_PUBKEY_BECH32` macro with a runtime helper `const char *BRDigiByteBech32Hrp(void)` (returns `BRNetworkIsTestnet() ? "dgbt" : "dgb"`), and update decode call sites accordingly.
  - `BRKey.c` ~152/199/260/319 (WIF/address versions → runtime ternary).
  - `BRMerkleBlock.c` ~387 (the testnet difficulty-skip `#if` → `if (BRNetworkIsTestnet()) return r;`). (The per-params `verifyDifficulty` fn already differs; this local `#if` becomes runtime.)
  Keep the compile-time `#if BITCOIN_TESTNET` fallbacks OUT — everything routes through `BRNetworkIsTestnet()`, which defaults to 0.

- [ ] **Step 3: Run KAT → PASS** (both networks from one build). **Step 4:** app build + 42 security green (mainnet default → same addresses as before). **Step 5:** commit (submodule + root pin).

---

## Task 3: JNI `setNetwork` + startup wiring

**Files:** `native/src/main/jni/bridge/jni_wallet.c` (or a network JNI), `core/.../NativeBridge.kt` (+ androidTest stub), the app startup (`AppModule`/wallet-init).

- [ ] **Step 1:** JNI `Java_..._setNetwork(JNIEnv*, jobject, jboolean isTestnet)` → `BRSetNetwork(isTestnet)`. Kotlin `external fun setNetwork(isTestnet: Boolean)` (+ androidTest stub).
- [ ] **Step 2:** Call `NativeBridge.setNetwork(prefs.getBoolean("dgb_network_testnet", false))` at app/core init **before** any wallet or peer-manager creation (find the earliest wallet-load site in `AppModule`/the sync service).
- [ ] **Step 3:** Build + 42 security green. Commit (root).

---

## Task 4: Per-network state namespacing (shared seed/PIN)

**Files:** `AppModule` (DB provider), the SharedPreferences accessors across `app`/`core`/`service`.

- [ ] **Step 1:** A single helper `networkSuffix()` = `"_testnet"` when testnet else `""`, from the `dgb_network_testnet` pref. Apply it to the network-specific prefs files/keys — `dgb_sync_data`, `dgb_settings`, `last_balance`, `last_dd_balance`, saved blocks/peers/filter-headers, `dgb_bloom_peers`, reconcile state — so testnet uses `<key>_testnet`. **Leave `dgb_wallet_seed` + `dgb_pin_store` + `dgb_db_key` shared** (network-independent).
- [ ] **Step 2:** In `AppModule.provideDatabase()` open a per-network DB file (`dgb.db` vs `dgb_testnet.db`) selected from the pref. `fallbackToDestructiveMigration` already set.
- [ ] **Step 3:** Build + 42 security green; confirm mainnet uses the unsuffixed keys/DB exactly as before. Commit (root).

---

## Task 5: testnet26 peer injection

**Files:** `native/src/main/jni/bridge/jni_peer.c`, `SyncService.kt` / `PeerServicesPolicy.kt`.

- [ ] **Step 1:** When `BRNetworkIsTestnet()`, skip the mainnet digiscope seeder + `digiscope.me` priority-peer injection; instead inject the hardcoded testnet26 peers `164.68.98.125:12033` and `129.212.182.152:12033` (via the existing `injectPeerByIp` path) and rely on the refreshed testnet DNS seeds. Mainnet path unchanged.
- [ ] **Step 2:** Build green. Commit (root). (End-to-end sync verified on-device in Task 6.)

---

## Task 6: Dev-gated Settings network toggle + TESTNET badge

**Files:** the Settings screen/VM, `WalletScreen.kt` (badge), a small restart helper.

- [ ] **Step 1:** In Settings, render an **Advanced / Developer** section only when `BuildConfig.DEBUG || <digiTestnet flavor>`. Inside it, a **Network** row (Mainnet / Testnet) reading `dgb_network_testnet`.
- [ ] **Step 2:** Flipping it → confirm dialog ("Switch to Testnet? The app restarts and syncs the test chain. Your recovery phrase is unchanged.") → persist `dgb_network_testnet` → trigger an app restart (relaunch the launcher activity + kill the process, the existing pattern for a clean re-init).
- [ ] **Step 3:** A small "TESTNET" badge on `WalletScreen` when the testnet pref is set (so the active network is never ambiguous).
- [ ] **Step 4:** Build green; 42 security green. **On-device verify:** install the testnet build, toggle → app restarts → syncs testnet26 → the DD hero-card balance + send UI work against the wallet's testnet DD (the C SEND path is already proven). Commit (root).

---

## Out of scope
- Release-facing (non-dev) network switching; live in-place switch (no restart); regtest/signet; base58 legacy testnet version exactness (wallet uses bech32/DD-TD).
